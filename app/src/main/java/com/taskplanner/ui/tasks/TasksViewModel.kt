package com.taskplanner.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.GroupRepository
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
        val isSelected: Boolean
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
    private val groupRepository: GroupRepository
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
                filterSignals
            ) { tasks, categories, groups, signal ->
                RenderInput(tasks, categories, groups, signal.filters)
            }.collect { input ->
                render(input)
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
                    items += TasksListItem.TaskRow(task, task.categoryId?.let { categoryMap[it] }, task.id in selected)
                    groupedTaskIds += task.id
                }
            } else {
                groupedTaskIds += groupTasks.map { it.id }
            }
        }
        visible.filter { it.id !in groupedTaskIds }.forEach { task ->
            items += TasksListItem.TaskRow(task, task.categoryId?.let { categoryMap[it] }, task.id in selected)
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
        groupId: String? = null
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
                    _taskSaveState.value = TaskSaveState.Success
                }
            } catch (e: Exception) {
                _taskSaveState.value = TaskSaveState.Error(e.message ?: "Failed to save task")
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
                _currentTask.value = if (taskId == 0L) null else taskRepository.getTaskById(taskId)
            } catch (e: Exception) {
                _taskSaveState.value = TaskSaveState.Error(e.message ?: "Failed to load task")
            }
        }
    }

    fun clearCurrentTask() {
        _currentTask.value = null
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
    data class Error(val message: String) : TaskSaveState()
}
