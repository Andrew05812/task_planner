package com.taskplanner.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.repository.AdjustmentResult
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.GroupRepository
import com.taskplanner.data.repository.RecurrenceRepository
import com.taskplanner.data.repository.ReminderRepository
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.data.repository.WorkStatisticsRepository
import com.taskplanner.data.repository.WorkTrackingRepository
import com.taskplanner.data.tracking.ConflictResolution
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.data.repository.TrackingOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.taskplanner.ui.util.LocalisedNames
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
        val series: RecurrenceRule? = null,
        /** Lead times still armed on this task — what puts the bell on the card. */
        val pendingReminders: Int = 0,
        /** The measured-time line, read from the same snapshot for every card on the screen. */
        val work: CardWork = CardWork()
    ) : TasksListItem
}

/**
 * The measured time a card prints.
 *
 * [liveSeconds] is null for every card but the one being measured now, and it is the only figure here
 * that is not a stored fact: it is the open stretch re-measured each second from the start in the
 * database. A card with nothing measured and nothing running shows no line at all rather than a zero.
 */
data class CardWork(
    val closedSeconds: Long = 0L,
    val liveSeconds: Long? = null,
    val phase: TrackingPhase = TrackingPhase.IDLE
) {
    val isMeasured: Boolean get() = liveSeconds != null

    val isShown: Boolean get() = closedSeconds > 0L || phase != TrackingPhase.IDLE

    fun totalSeconds(): Long = closedSeconds + (liveSeconds ?: 0L)
}

/**
 * Start was refused because another task already holds the profile's single timer.
 *
 * The screen has to say which task and what will happen to it: quietly pausing or stopping work the
 * user is measuring is a decision about their data, so the app asks instead of choosing.
 */
data class WorkConflict(val runningTaskId: Long, val runningTaskTitle: String, val targetTask: Task)

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
    /**
     * How many of the three filters are on: category, status, query.
     *
     * The header's chip shows this number and clears all three in one press, so the count and the boolean
     * below are the same fact in two shapes — a chip reading one and the rest of the screen reading the
     * other is how a filter stays on after the control that would remove it has gone quiet.
     */
    val activeFilterCount: Int
        get() = listOfNotNull(filterCategoryId, filterStatus, searchQuery.ifBlank { null }).size

    val hasActiveFilters: Boolean
        get() = activeFilterCount > 0
}

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val groupRepository: GroupRepository,
    private val recurrenceRepository: RecurrenceRepository,
    private val reminderRepository: ReminderRepository,
    private val sessionRepository: SessionRepository,
    private val workTrackingRepository: WorkTrackingRepository,
    private val workStatisticsRepository: WorkStatisticsRepository
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

    /** The lead times stored on the task open in the editor, soonest fire first. */
    private val _reminderOffsets = MutableStateFlow<List<Int>>(emptyList())
    val reminderOffsets: StateFlow<List<Int>> = _reminderOffsets.asStateFlow()

    /** Set when Start was refused because another task holds the profile's single timer. */
    private val _workConflict = MutableStateFlow<WorkConflict?>(null)
    val workConflict: StateFlow<WorkConflict?> = _workConflict.asStateFlow()

    /** The tracking block of the task open in the editor, re-measured while a stretch runs. */
    private val _taskWork = MutableStateFlow<WorkStatisticsRepository.TaskWorkView>(
        WorkStatisticsRepository.TaskWorkView()
    )
    val taskWork: StateFlow<WorkStatisticsRepository.TaskWorkView> = _taskWork.asStateFlow()

    private data class Filters(
        val categoryId: Long?,
        val status: TaskStatus?,
        val search: String,
        val sortOrder: SortOrder
    )

    private val filters = MutableStateFlow(Filters(null, null, "", SortOrder.MANUAL))

    /** The collector that follows the task on screen; cancelled when another task replaces it. */
    private var workFollow: Job? = null

    private data class RenderInput(
        val tasks: List<Task>,
        val categories: List<Category>,
        val groups: List<TaskGroup>,
        val rules: Map<String, RecurrenceRule>,
        val pendingReminders: Map<String, Int>,
        val work: WorkStatisticsRepository.WorkBoard,
        val filters: Filters
    )

    // keeps `filters` and selection observable by a single combine without re-launching collectors
    private val selectionFlow = MutableStateFlow<Pair<Set<Long>, Boolean>>(emptySet<Long>() to false)

    private val filterSignals =
        combine(filters, selectionFlow) { f, sel ->
            FilterWithSelection(f, sel.first, sel.second)
        }

    init {
        // Exactly one collector for the whole screen: every raw list and every filter
        // change flows through combine, so no leaked collectors remain.
        // Declared last on purpose: viewModelScope starts on the main thread immediately, so the
        // collector would read these properties before they are initialised.
        viewModelScope.launch {
            // combine has typed overloads up to five sources; the three plain lists share one.
            val lists = combine(
                taskRepository.observeActiveTasks(),
                categoryRepository.getAllCategories(),
                groupRepository.observeGroups()
            ) { tasks, categories, groups -> Triple(tasks, categories, groups) }

            combine(
                lists,
                recurrenceRepository.observeRules(),
                reminderRepository.observeLiveForCurrentUser(),
                filterSignals,
                workStatisticsRepository.observeWorkBoard()
            ) { (tasks, categories, groups), rules, reminders, signal, work ->
                RenderInput(
                    tasks,
                    categories,
                    groups,
                    rules.associateBy { it.uuid },
                    reminders.filter { it.isPending }.groupBy { it.taskUuid }.mapValues { it.value.size },
                    work,
                    signal.filters
                )
            }.collect { input ->
                render(input)
            }
        }
        // A series that was due while the app was closed owes the user its next task. Bringing the
        // open series up to date whenever a profile becomes active is idempotent, so it is safe to
        // run on every start, profile switch and sign-in; WorkManager covers the time in between.
        // The same pass rebuilds the alarms from the rows — the instances have to exist first, because
        // a reminder belongs to one — so opening the app delivers what came due while it was away
        // instead of waiting for the six-hourly sweep.
        viewModelScope.launch {
            sessionRepository.currentUser.collect {
                recurrenceRepository.ensureOpenInstances()
                reminderRepository.rescheduleAll()
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
            // The reader's alphabet rather than the code-point order: `sortedBy { it.title.lowercase() }` put
            // "Ёлка" above "Афиша" because U+0451 is a lower number than U+0430, which is not a fact about
            // Russian and not an order anyone reads. `LocalisedNames` explains the strength choice.
            SortOrder.NAME_ASC -> visible.sortedWith(LocalisedNames.byKey<Task> { it.title })
            SortOrder.NAME_DESC -> visible.sortedWith(LocalisedNames.byKey<Task> { it.title }.reversed())
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
                    items += taskRow(task, input, categoryMap, selected)
                    groupedTaskIds += task.id
                }
            } else {
                groupedTaskIds += groupTasks.map { it.id }
            }
        }
        visible.filter { it.id !in groupedTaskIds }.forEach { task ->
            items += taskRow(task, input, categoryMap, selected)
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

    private fun taskRow(
        task: Task,
        input: RenderInput,
        categoryMap: Map<Long, Category>,
        selected: Set<Long>
    ) = TasksListItem.TaskRow(
        task,
        task.categoryId?.let { categoryMap[it] },
        task.id in selected,
        task.seriesUuid?.let { input.rules[it] },
        input.pendingReminders[task.uuid] ?: 0,
        CardWork(
            closedSeconds = input.work.closedSecondsOf(task.id),
            liveSeconds = input.work.liveSecondsOf(task.id),
            phase = input.work.phaseOf(task.id)
        )
    )

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

    // ---------------------------------------------------------------- work tracking

    /**
     * The card's and the detail screen's one control: measure this task, or stop measuring it.
     *
     * The phase is read from the stored rows rather than from what the user last tapped, so two screens
     * on one task agree and a task already being measured cannot be started twice. Nothing is started
     * over another task's running timer: that answer comes from [resolveWorkConflict], after the user
     * has been told which task holds it.
     */
    fun toggleWork(task: Task) {
        viewModelScope.launch {
            try {
                val outcome = when (workTrackingRepository.snapshotOf(task).phase) {
                    TrackingPhase.RUNNING -> workTrackingRepository.pause(task)
                    TrackingPhase.PAUSED -> workTrackingRepository.resume(task)
                    else -> workTrackingRepository.start(task)
                }
                if (outcome == TrackingOutcome.CONFLICT) askAboutConflict(task)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    /** Ends the run on [task]: the open stretch is measured and stored, the session is finished. */
    fun stopWork(task: Task) {
        viewModelScope.launch {
            try {
                workTrackingRepository.stop(task)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    /**
     * Records time the user says they worked on [task] without the timer measuring it.
     *
     * The request goes to the tracking repository rather than to the task row, because whether the stretch
     * can be written is a question about the history: an interval that ends before it starts measures
     * nothing, and one whose time is already occupied by other measurements has to be shown to the user
     * before it is saved. The answer is handed back for the screen to say.
     */
    fun recordManualWork(
        task: Task,
        startedAt: Long,
        endedAt: Long,
        onResult: (AdjustmentResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                onResult(workTrackingRepository.recordManualInterval(task, startedAt, endedAt))
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    private suspend fun askAboutConflict(target: Task) {
        val running = workTrackingRepository.observeRunningSession().first()
        if (running == null) {
            _state.update { it.copy(error = "Nothing is being measured, so nothing was started") }
        } else {
            _workConflict.value = WorkConflict(running.taskId, running.taskTitle, target)
        }
    }

    fun clearWorkConflict() {
        _workConflict.value = null
    }

    /** The user said what happens to the other timer; the request is made again with that answer. */
    fun resolveWorkConflict(resolution: ConflictResolution) {
        val conflict = _workConflict.value ?: return
        _workConflict.value = null
        if (resolution == ConflictResolution.CANCEL) return
        viewModelScope.launch {
            try {
                workTrackingRepository.start(conflict.targetTask, resolution)
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
        repeat: RepeatPlan = RepeatPlan(),
        reminders: List<Int>? = null,
        /** When the user says the task starts. Null means it was never planned to a minute. */
        startTime: Date? = null,
        endTime: Date? = null,
        estimatedMinutes: Int? = null
    ) {
        if (title.isBlank()) {
            _taskSaveState.value = TaskSaveState.Error("Title cannot be empty")
            return
        }
        val offsets = reminders?.let { ReminderPreset.normalize(it) }
        // A lead time is measured backwards from a deadline. Without one there is no minute to ring
        // at, so the editor is told to ask for a date instead of the task quietly losing the chips.
        if (!offsets.isNullOrEmpty() && dueDate == null) {
            _taskSaveState.value = TaskSaveState.ReminderNeedsDeadline
            return
        }
        if (startTime != null && endTime != null && endTime.time < startTime.time) {
            _taskSaveState.value = TaskSaveState.PlanConflict
            return
        }
        if (estimatedMinutes != null && estimatedMinutes > 0 && startTime == null && dueDate == null) {
            _taskSaveState.value = TaskSaveState.PlanNeedsInstant
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
                    startTime = startTime?.time,
                    endTime = endTime?.time,
                    estimatedMinutes = estimatedMinutes?.takeIf { it > 0 },
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
                    // getTaskById is what gives a brand new task the uuid its reminders attach to, and
                    // applying them before the archive step means finishing the task here also
                    // silences the alarms that were just armed.
                    val saved = taskRepository.getTaskById(resultId)
                    saved?.let { reminderRepository.applyReminders(it, offsets) }
                    // DONE means "completed and filed in the archive"; keeping the editor
                    // and the card button consistent avoids a COMPLETED row in active list.
                    if (status == TaskStatus.COMPLETED) taskRepository.completeAndArchive(resultId)
                    val conflict = applyRepeat(resultId, existing?.dueDate, repeat)
                    _seriesRule.value = seriesOf(resultId)
                    _reminderOffsets.value = saved?.let { reminderRepository.offsetsFor(it.uuid) }
                        ?: emptyList()
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
                followWork(task)
                _seriesRule.value = task?.let { recurrenceRepository.ruleFor(it) }
                _reminderOffsets.value = task?.let { reminderRepository.offsetsFor(it.uuid) }
                    ?: emptyList()
            } catch (e: Exception) {
                _taskSaveState.value = TaskSaveState.Error(e.message ?: "Failed to load task")
            }
        }
    }

    fun clearCurrentTask() {
        _currentTask.value = null
        _seriesRule.value = null
        _reminderOffsets.value = emptyList()
        followWork(null)
    }

    /**
     * Follows the measured time of the task on screen, and stops following it when the screen moves on.
     *
     * One collector per editor instance rather than one per recomposition: the flow it collects is the
     * ticker, which produces a value every second a stretch runs and no value at all while one does not.
     */
    private fun followWork(task: Task?) {
        workFollow?.cancel()
        _taskWork.value = WorkStatisticsRepository.TaskWorkView()
        if (task != null) {
            workFollow = viewModelScope.launch {
                workStatisticsRepository.observeTaskWork(task).collect { _taskWork.value = it }
            }
        }
    }

    /** Whether the notification bar will actually show a reminder for this app. */
    fun canPostNotifications(): Boolean = reminderRepository.canPostNotifications()

    /** Whether Android lets this app ring at the exact minute the user chose. */
    fun canScheduleExactAlarms(): Boolean = reminderRepository.canScheduleExactAlarms()

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

    /** Nothing was stored: the chosen lead times have no deadline to count back from. */
    object ReminderNeedsDeadline : TaskSaveState()

    /**
     * Nothing was stored: an estimate needs a moment to be measured from. A length without one would
     * be a number with nothing to attach to, and the calendar would have to guess where to draw it.
     */
    object PlanNeedsInstant : TaskSaveState()

    /** Nothing was stored: the planned end comes before the planned start. */
    object PlanConflict : TaskSaveState()

    data class Error(val message: String) : TaskSaveState()
}
