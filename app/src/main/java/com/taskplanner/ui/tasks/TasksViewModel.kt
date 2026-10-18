package com.taskplanner.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.GroupRepository
import com.taskplanner.data.repository.RecurrenceRepository
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID
import javax.inject.Inject

sealed interface TasksListItem {
    data class GroupHeader(
        val group: TaskGroup,
        val taskCount: Int,
        val allSelected: Boolean
    ) : TasksListItem

    data class TaskRow(
        val task: Task,
        val category: Category?,
        val isSelected: Boolean,
        /** The series this row is an instance of, when the task repeats. */
        val series: RecurrenceRule? = null
    ) : TasksListItem
}

data class TasksState(
    val items: List<TasksListItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    val activeTaskCount: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val filterCategoryId: Long? = null,
    val filterStatus: TaskStatus? = null,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.MANUAL,
    val collapseVersion: Int = 0
) {
    val hasActiveFilters: Boolean
        get() = filterCategoryId != null || filterStatus != null || searchQuery.isNotBlank()
}

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val groupRepository: GroupRepository,
    private val recurrenceRepository: RecurrenceRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TasksState())
    val state: StateFlow<TasksState> = _state.asStateFlow()

    private val _taskSaveState = MutableStateFlow<TaskSaveState>(TaskSaveState.Initial)
    val taskSaveState: StateFlow<TaskSaveState> = _taskSaveState.asStateFlow()

    /** Convenience for TaskDetailFragment dropdowns. */
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _currentTask = MutableStateFlow<Task?>(null)
    val currentTask: StateFlow<Task?> = _currentTask.asStateFlow()

    /** The series of the task open in the editor, null when the task does not repeat. */
    private val _seriesRule = MutableStateFlow<RecurrenceRule?>(null)
    val seriesRule: StateFlow<RecurrenceRule?> = _seriesRule.asStateFlow()

    private data class Filters(
        val categoryId: Long?,
        val status: TaskStatus?,
        val search: String,
        val sortOrder: SortOrder
    )

    private val filters = MutableStateFlow(Filters(null, null, "", SortOrder.MANUAL))

    private data class RenderInput(
        val tasks: List<Task>,
        val categories: List<Category>,
        val groups: List<TaskGroup>,
        val rules: Map<String, RecurrenceRule>,
        val filters: Filters
    )

    // keeps `filters` and selection observable by a single combine without re-launching collectors
    private val selectionFlow = MutableStateFlow<Pair<Set<Long>, Boolean>>(emptySet<Long>() to false)

    private val filterSignals =
        kotlinx.coroutines.flow.combine(filters, selectionFlow) { f, sel ->
            FilterWithSelection(f, sel.first, sel.second)
        }

    init {
        // Exactly one collector for the whole screen: every raw list and every filter
        // change flows through combine, so no leaked collectors remain.
        // Declared last on purpose: viewModelScope starts on the main thread immediately, so the
        // collector would read these properties before they are initialised.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                taskRepository.observeActiveTasks(),
                categoryRepository.getAllCategories(),
                groupRepository.observeGroups(),
                recurrenceRepository.observeRules(),
                filterSignals
            ) { tasks, categories, groups, rules, signal ->
                RenderInput(tasks, categories, groups, rules.associateBy { it.uuid }, signal.filters)
            }.collect { input ->
                render(input)
            }
        }
        // A series that was due while the app was closed owes the user its next task. Bringing the
        // open series up to date whenever a profile becomes active is idempotent, so it is safe to
        // run on every start, profile switch and sign-in; WorkManager covers the time in between.
        viewModelScope.launch {
            sessionRepository.currentUser.collect {
                recurrenceRepository.ensureOpenInstances()
            }
        }
    }

    private data class FilterWithSelection(val filters: Filters, val selected: Set<Long>, val mode: Boolean)

    private fun render(input: RenderInput) {
        val categoryMap = input.categories.associateBy { it.id }
        var visible = input.tasks

        val (categoryId, status, search, sortOrder) = input.filters
        if (categoryId != null) {
            visible = if (categoryId == NO_CATEGORY_ID) {
                visible.filter { it.categoryId == null }
            } else {
                visible.filter { it.categoryId == categoryId }
            }
        }
        if (status != null) visible = visible.filter { it.status == status }
        if (search.isNotBlank()) {
            val q = search.trim().lowercase()
            visible = visible.filter {
                it.title.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
        }

        visible = when (sortOrder) {
            SortOrder.MANUAL -> visible.sortedWith(compareBy({ it.order }, { it.dueDate ?: Long.MAX_VALUE }))
            SortOrder.DATE_ASC -> visible.sortedBy { it.dueDate ?: Long.MAX_VALUE }
            SortOrder.DATE_DESC -> visible.sortedByDescending { it.dueDate ?: Long.MIN_VALUE }
            SortOrder.PRIORITY_ASC -> visible.sortedBy { it.priority.rank() }
            SortOrder.PRIORITY_DESC -> visible.sortedByDescending { it.priority.rank() }
            SortOrder.NAME_ASC -> visible.sortedBy { it.title.lowercase() }
            SortOrder.NAME_DESC -> visible.sortedByDescending { it.title.lowercase() }
            else -> visible
        }

        val (selected, mode) = selectionFlow.value

        val items = mutableListOf<TasksListItem>()
        val groupedTaskIds = mutableSetOf<Long>()
        val groupsWithTasks = input.groups.map { group ->
            val groupTasks = visible.filter { it.groupId == group.uuid }
            group to groupTasks
        }
        groupsWithTasks.forEach { (group, groupTasks) ->
            if (groupTasks.isEmpty() && !input.groups.contains(group)) return@forEach
            val allSelected = groupTasks.isNotEmpty() && groupTasks.all { it.id in selected }
            items += TasksListItem.GroupHeader(group, groupTasks.size, allSelected)
            if (!group.collapsed) {
                groupTasks.forEach { task ->
                    items += TasksListItem.TaskRow(
                        task,
                        task.categoryId?.let { categoryMap[it] },
                        task.id in selected,
                        task.seriesUuid?.let { input.rules[it] }
                    )
                    groupedTaskIds += task.id
                }
            } else {
                groupedTaskIds += groupTasks.map { it.id }
            }
        }
        visible.filter { it.id !in groupedTaskIds }.forEach { task ->
            items += TasksListItem.TaskRow(
                task,
                task.categoryId?.let { categoryMap[it] },
                task.id in selected,
                task.seriesUuid?.let { input.rules[it] }
            )
        }

        _categories.value = input.categories
        _state.update {
            it.copy(
                items = items,
                categories = input.categories,                activeTaskCount = input.tasks.size,
                isLoading = false,
                filterCategoryId = categoryId,
                filterStatus = status,
                searchQuery = search,
                sortOrder = sortOrder,
                selectionMode = mode,
                selectedIds = selected,
                collapseVersion = it.collapseVersion + 1
            )
        }
    }

    private fun Priority.rank(): Int = when (this) {
        Priority.LOW -> 0
        Priority.MEDIUM -> 1
        Priority.HIGH -> 2
    }

    private fun updateFilters(transform: (Filters) -> Filters) {
        filters.update(transform)
    }

    fun setCategoryFilter(categoryId: Long?) = updateFilters { it.copy(categoryId = categoryId) }

    fun setNoCategoryFilter() = updateFilters { it.copy(categoryId = NO_CATEGORY_ID) }

    fun setStatusFilter(status: TaskStatus?) = updateFilters { it.copy(status = status) }

    fun setSearchQuery(query: String) = updateFilters { it.copy(search = query) }

    fun setSortOrder(order: SortOrder) = updateFilters { it.copy(sortOrder = order) }

    fun clearFilters() = updateFilters { it.copy(categoryId = null, status = null, search = "") }

    // ---------------------------------------------------------------- selection

    fun toggleSelection(taskId: Long) {
        val (selected, mode) = selectionFlow.value
        val next = if (taskId in selected) selected - taskId else selected + taskId
        selectionFlow.value = next to true
        refreshSelectionViews()
    }

    fun enterSelection(taskId: Long) {
        selectionFlow.value = setOf(taskId) to true
        refreshSelectionViews()
    }

    fun exitSelection() {
        selectionFlow.value = emptySet<Long>() to false
        refreshSelectionViews()
    }

    fun selectAllVisible() {
        val ids = _state.value.items
            .filterIsInstance<TasksListItem.TaskRow>()
            .map { it.task.id }
            .toSet()
        selectionFlow.value = ids to true
        refreshSelectionViews()
    }

    fun toggleGroupSelection(group: TaskGroup) {
        val groupIds = _state.value.items
            .filterIsInstance<TasksListItem.TaskRow>()
            .filter { it.task.groupId == group.uuid }
            .map { it.task.id }
            .toSet()
        val (selected, _) = selectionFlow.value
        val next = if (groupIds.all { it in selected } && groupIds.isNotEmpty()) {
            selected - groupIds
        } else {
            selected + groupIds
        }
        selectionFlow.value = next to true
        refreshSelectionViews()
    }

    private fun refreshSelectionViews() {
        // force a re-render so TaskRow.isSelected flags and header flags update
        updateFilters { it.copy() }
    }

    val selectedIds: Set<Long> get() = selectionFlow.value.first

    private suspend fun deleteSelectedNow() {
        val ids = selectionFlow.value.first.toList()
        if (ids.isEmpty()) return
        taskRepository.softDeleteTasks(ids)
        exitSelection()
    }

    fun deleteSelected(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                deleteSelectedNow()
                onDone()
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    /** Swipe-panel delete for a single task; same soft-delete path as bulk removal. */
    fun deleteSelectedFor(ids: List<Long>) {
        viewModelScope.launch {
            try {
                taskRepository.softDeleteTasks(ids)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun startTask(task: Task) = lifecycleOp { taskRepository.startTask(task.id) }

    fun pauseTask(task: Task) = lifecycleOp { taskRepository.pauseTask(task.id) }

    fun resumeTask(task: Task) = lifecycleOp { taskRepository.resumeTask(task.id) }

    /** DONE: completed and archived in one step. */
    fun completeTask(task: Task) = lifecycleOp { taskRepository.completeAndArchive(task.id) }

    private fun lifecycleOp(op: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                op()
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    // ---------------------------------------------------------------- groups

    fun createGroup(name: String, onCreated: (TaskGroup?) -> Unit = {}) {
        viewModelScope.launch {
            try {
                onCreated(groupRepository.createGroup(name))
            } catch (e: Exception) {
                setError(e)
                onCreated(null)
            }
        }
    }

    fun renameGroup(uuid: String, name: String) {
        viewModelScope.launch {
            try {
                groupRepository.rename(uuid, name)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    fun deleteGroup(uuid: String) {
        viewModelScope.launch {
            try {
                val tasks = _state.value.items
                    .filterIsInstance<TasksListItem.TaskRow>()
                    .filter { it.task.groupId == uuid }
                    .map { it.task }
                tasks.forEach { taskRepository.moveTaskToGroup(it.id, null) }
                groupRepository.deleteGroup(uuid)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    fun toggleGroupCollapsed(group: TaskGroup) {
        viewModelScope.launch {
            try {
                groupRepository.setCollapsed(group.uuid, !group.collapsed)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    fun moveTaskToGroup(taskId: Long, groupUuid: String?) {
        viewModelScope.launch {
            try {
                taskRepository.moveTaskToGroup(taskId, groupUuid)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    // ---------------------------------------------------------------- ordering

    /** Persists the manual order for the currently visible (filtered) subset. */
    fun saveVisibleOrder(orderedIds: List<Long>) {
        viewModelScope.launch {
            try {
                taskRepository.reorderTasks(orderedIds)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    // ---------------------------------------------------------------- task editor

    fun saveTask(
        @Suppress("UNUSED_PARAMETER") taskId: Long = 0,
        title: String,
        description: String,
        dueDate: Date? = null,
        priority: Priority = Priority.MEDIUM,
        status: TaskStatus = TaskStatus.TODO,
        categoryId: Long? = null,
        groupId: String? = null,
        repeat: RepeatPlan = RepeatPlan()
    ) {
        if (title.isBlank()) {
            _taskSaveState.value = TaskSaveState.Error("Title cannot be empty")
            return
        }
        viewModelScope.launch {
            _taskSaveState.value = TaskSaveState.Loading
            try {
                val existing = _currentTask.value
                val task = (existing ?: Task(title = title, description = description, userId = 0)).copy(
                    title = title.trim(),
                    description = description.trim(),
                    status = status,
                    priority = priority,
                    dueDate = dueDate?.time,
                    categoryId = categoryId,
                    groupId = groupId
                )
                val resultId = if (existing != null) {
                    taskRepository.updateTask(task)
                    existing.id
                } else {
                    taskRepository.insertTask(task.copy(order = nextOrder()))
                }
                if (resultId <= 0 && existing == null) {
                    _taskSaveState.value = TaskSaveState.Error("Create a profile first")
                } else {
                    // DONE means "completed and filed in the archive"; keeping the editor
                    // and the card button consistent avoids a COMPLETED row in active list.
                    if (status == TaskStatus.COMPLETED) taskRepository.completeAndArchive(resultId)
                    val conflict = applyRepeat(resultId, existing?.dueDate, repeat)
                    _seriesRule.value = seriesOf(resultId)
                    _taskSaveState.value = if (conflict) {
                        TaskSaveState.ScheduleConflict
                    } else {
                        TaskSaveState.Success
                    }
                }
            } catch (e: Exception) {
                _taskSaveState.value = TaskSaveState.Error(e.message ?: "Failed to save task")
            }
        }
    }

    /**
     * Carries the editor's repeat section over to the stored series.
     *
     * Returns true when the user asked to re-schedule the series onto a date another instance of it
     * already owns. The task itself is saved either way — only the schedule change is refused, so
     * pressing save never throws away what was typed.
     */
    private suspend fun applyRepeat(
        taskId: Long,
        previousDueDate: Long?,
        plan: RepeatPlan
    ): Boolean {
        val saved = taskRepository.getTaskById(taskId) ?: return false
        val rule = saved.seriesUuid?.let { recurrenceRepository.ruleFor(saved) }

        if (plan.preset.unit == null) {
            // "Does not repeat" on a running series means "stop it here"; the task on screen stays.
            if (rule != null && plan.appliesToSeries && rule.endedAt == null) {
                recurrenceRepository.finish(rule.uuid)
            }
            return false
        }
        if (rule == null) {
            recurrenceRepository.startSeries(
                saved,
                plan.preset.unit,
                plan.effectiveInterval,
                plan.effectiveWeekDays,
                plan.effectiveDayOfMonth
            )
            return false
        }
        // "Only this task" leaves the rule alone: the row keeps the slot it was materialised for,
        // so the series goes on from the date it had planned.
        if (!plan.appliesToSeries) return false
        // Rebase only when the date on screen actually moved, otherwise a series whose instance was
        // shifted by an earlier "only this task" edit would be dragged back to that date.
        val rebaseAt = saved.dueDate.takeIf { it != previousDueDate }
        return recurrenceRepository.updateThisAndFuture(
            rule.uuid,
            plan.preset.unit,
            plan.effectiveInterval,
            plan.effectiveWeekDays,
            plan.effectiveDayOfMonth,
            rebaseAt
        ) == null
    }

    private suspend fun seriesOf(taskId: Long): RecurrenceRule? =
        taskRepository.getTaskById(taskId)?.let { recurrenceRepository.ruleFor(it) }

    // ---------------------------------------------------------------- series management

    fun setSeriesPaused(paused: Boolean, onResult: (Boolean) -> Unit = {}) = seriesCommand(
        onResult
    ) { uuid -> recurrenceRepository.setPaused(uuid, paused) }

    fun finishSeries(onResult: (Boolean) -> Unit = {}) =
        seriesCommand(onResult) { recurrenceRepository.finish(it) }

    fun deleteSeries(onResult: (Boolean) -> Unit = {}) {
        val uuid = _seriesRule.value?.uuid ?: return onResult(false)
        viewModelScope.launch {
            try {
                val done = recurrenceRepository.deleteSeries(uuid)
                // The instances the series owed the user are gone, so the editor no longer belongs
                // to a series at all.
                _seriesRule.value = null
                _currentTask.value = _currentTask.value?.copy(seriesUuid = null, occurrenceAt = null)
                onResult(done)
            } catch (e: Exception) {
                setError(e)
                onResult(false)
            }
        }
    }

    private fun seriesCommand(
        onResult: (Boolean) -> Unit,
        change: suspend (String) -> Boolean
    ) {
        val uuid = _seriesRule.value?.uuid ?: return onResult(false)
        viewModelScope.launch {
            try {
                val done = change(uuid)
                _seriesRule.value = _currentTask.value?.let { seriesOf(it.id) }
                onResult(done)
            } catch (e: Exception) {
                setError(e)
                onResult(false)
            }
        }
    }

    private suspend fun nextOrder(): Int =
        taskRepository.getActiveTasksOnce().maxOfOrNull { it.order }?.plus(1) ?: 0

    fun resetTaskSaveState() {
        _taskSaveState.value = TaskSaveState.Initial
    }

    fun loadTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val task = if (taskId == 0L) null else taskRepository.getTaskById(taskId)
                _currentTask.value = task
                _seriesRule.value = task?.let { recurrenceRepository.ruleFor(it) }
            } catch (e: Exception) {
                _taskSaveState.value = TaskSaveState.Error(e.message ?: "Failed to load task")
            }
        }
    }

    fun clearCurrentTask() {
        _currentTask.value = null
        _seriesRule.value = null
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun setError(e: Exception) {
        _state.update { it.copy(error = e.message ?: "Unexpected error") }
    }

    fun retry() {
        viewModelScope.launch { taskRepository.getActiveTasksOnce() }
    }

    companion object {
        const val NO_CATEGORY_ID = -1L
        fun newTaskUuid(): String = UUID.randomUUID().toString()
    }
}

sealed class TaskSaveState {
    object Initial : TaskSaveState()
    object Loading : TaskSaveState()
    object Success : TaskSaveState()

    /** The task was stored but its series kept the old schedule; the editor stays open. */
    object ScheduleConflict : TaskSaveState()

    data class Error(val message: String) : TaskSaveState()
}
