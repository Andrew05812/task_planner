package com.taskplanner.ui.tasks

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyCollapseResult
import com.taskplanner.data.hierarchy.HierarchyDeletePolicy
import com.taskplanner.data.hierarchy.HierarchyDeleteRefusal
import com.taskplanner.data.hierarchy.HierarchyDeleteResult
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
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
import com.taskplanner.data.repository.TaskHierarchyRepository
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
        val work: CardWork = CardWork(),
        /**
         * How deep this task sits inside its topic, counted from [Task.parentUuid].
         *
         * Zero for a topic's own child and for anything with no parent at all. It is a fact about the rows on
         * screen rather than a column the reader has to trust: [TaskTree] measures it over the list the filters
         * left, so a row whose parent is genuinely not drawn sits at the margin instead of inventing a level for
         * it. §7's filter clause is what keeps "genuinely not drawn" rare — a parent a filter dropped is drawn
         * above the child as context — so the margin case is left to the arrangements no rule can complete, like
         * a parent another device deleted while its child was being pulled.
         */
        val depth: Int = 0,
        /**
         * How many rows this card's fold would move if the reader pressed it right now.
         *
         * The number is about the screen, not about the notebook: an open row counts the rows on screen that hang
         * under it, and a closed one counts the rows its own fold is holding back. Two cases make that difference
         * matter, and both were wrong before it was stated — a branch closed *inside* a branch made the outer card
         * promise rows that a press would not move, and a filter that dropped the children left a chevron offering
         * to hide nothing. Zero therefore means "this card has nothing to fold", and the card draws no control for
         * it: a chevron over an empty branch is a decoration that lies about the reader's own notebook.
         */
        val foldRows: Int = 0,
        /**
         * Whether this row is drawn while the reader is picking a batch.
         *
         * It travels on the row rather than only on the adapter because a `ListAdapter` rebinds what *changed*, and
         * entering selection mode changes one row's tick mark — so every other card would go on showing a fold
         * control under an adapter flag it never re-read. Folding a branch while a batch is being picked would hide
         * rows the reader has already committed to deleting, so the control stands down for the whole list, and the
         * only way a diff can do that is if the mode is part of what the rows say about themselves.
         */
        val selectionMode: Boolean = false,
        /**
         * Whether this row is on the screen because something *else* needs the path above it, rather than because
         * the reader asked for it.
         *
         * §7 forbids presenting somebody's subtask as a root: a category or status filter that drops a parent must
         * not move its child to the card's margin and draw it as a topic's own first task. The chosen answer is the
         * one the brief prefers — the missing ancestors are drawn above the row that needs them — and a row inserted
         * that way is not a match. It is marked, it is not counted as an answer, and it offers none of the controls
         * a row the reader selected would offer: a fold, a handle and a tick that act on a task nobody asked about
         * are three ways to lose data the filter was hiding.
         */
        val shownForContext: Boolean = false,
        /**
         * Whether this row's place in the tree can be changed by a press right now — so whether the card draws the
         * control that offers it (§6).
         *
         * Three facts make it up, and the render is the only place holding all three: the rows on screen in the order
         * the reader sees them (an indent hangs a row on what is *drawn* above it), the profile's rows as stored
         * (which is what the domain will validate against, and the only source for a parent link that names nothing),
         * and the mode the screen is in. A scaffolding row gets nothing for the same reason it gets no fold and no
         * handle: it is on the screen because something else needed the path, and re-parenting the reader's real row
         * from behind that would be an edit to a task nobody pointed at. A batch in progress stands down too — a
         * moved row is a row whose children moved with it, and a selection is a promise about specific ones.
         */
        val canEditHierarchy: Boolean = false
    ) : TasksListItem
}

/**
 * The measured time a card prints.
 *
 * [liveSeconds] is null for every card but the one being measured now, and it is the only figure here
 * that is not a stored fact: it is the open stretch re-measured each second from the start in the
 * database. A card with nothing measured and nothing running shows no line at all rather than a zero.
 */
/**
 * One row the screen will draw, and whether the reader asked for it.
 *
 * `isContext` is the difference between a match and the path above a match: a category or status filter that dropped
 * a parent §7 refuses to answer by drawing the child as a root, so the parent is drawn too — and everything that
 * treats the list as *answers* (the topic's count, a batch, a drag, a fold) has to be able to tell the two apart.
 */
private data class DisplayedRow(
    val task: Task,
    val isContext: Boolean,
    /** The topic whose header this row is drawn under — the answer's own, for a path row. */
    val displayGroupId: String? = task.groupId
)

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
    /** A resource, not a sentence: see `CategoriesState.Error`. */
    @StringRes val error: Int? = null,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val filterCategoryId: Long? = null,
    val filterStatus: TaskStatus? = null,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.MANUAL
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
    private val workStatisticsRepository: WorkStatisticsRepository,
    private val hierarchyRepository: TaskHierarchyRepository
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

    /**
     * The last thing this screen drew, kept so a refused edit can put the list back the way it was.
     *
     * A drag edits the rows on screen before it edits the stored ones, and the two can disagree: a repository that
     * refuses a plan leaves the card sitting where the reader's finger put it, and nothing else would ever move it
     * back. Drawing the last input again undoes the lie. It is not a re-read of the database — and it does not need
     * to be one, because a *real* change to the rows makes Room's flow re-emit and render by itself; this is for the
     * case where the data never moved and only the screen did.
     */
    private var lastRender: RenderInput? = null

    /**
     * The blocks the last render drew, in the order it drew them, the profile's rows it measured those against, and
     * the topics whose headers it drew.
     *
     * §6's hierarchy menu needs the first two and cannot rebuild either: a block is what an indent hangs a row on —
     * the rows the reader sees under one header, in the order the list put them — while [HierarchyScreen.stored] holds
     * the parents a filter or a fold took off screen, which is what the depth and the parent-link questions are answered
     * against, because that is what the write will be validated against. §6.2's editor reads the same snapshot: it is
     * the only place a task's topic has a name without a second read of storage, so [HierarchyScreen.topics] travels
     * with it. Read-only from outside, and `null` before the first render, which is the only state in which no card
     * can have been pressed.
     */
    var hierarchyContext: HierarchyScreen? = null
        private set

    /**
     * What the screen drew: [blocks] is one entry per topic header — the rows under it, in the order the list drew
     * them, and an empty one for a collapsed topic — followed by the rows drawn under no header at all.
     *
     * [stored] holds the profile's own rows, which is what the depth and the parent-link questions are answered
     * against, and [topics] holds the groups the headers were drawn from in the same pass — so a screen that is not
     * the list can name a task's topic without reading storage a second time.
     *
     * [blockFor] answers the one question a pressed card asks: which sequence was *this* row drawn inside, because
     * that is the only sequence in which "the row above it" means what the reader sees.
     */
    class HierarchyScreen internal constructor(
        val blocks: List<List<Task>>,
        val stored: List<Task>,
        val topics: List<TaskGroup>
    ) {
        fun blockFor(taskId: Long): List<Task>? = blocks.firstOrNull { block -> block.any { it.id == taskId } }
    }

    /** One header's worth of rows as the screen draws them; a null [group] is the tail drawn under no header. */
    private data class Section(val group: TaskGroup?, val rows: List<DisplayedRow>)

    /**
     * The ids this screen drew as the path above somebody's match, from the last render.
     *
     * The refusals that follow are decided here rather than in the view, because the view is a copy of the state and
     * the state is a copy of this: a row the reader did not ask for must not be added to a batch by a long press, by
     * a tick, or by "select all", and the renderer is the only place that knows which rows those were.
     */
    private var contextIds: Set<Long> = emptySet()

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
        lastRender = input
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

        // A fold hides a branch from the tree view, and a search is not the tree view — it is a question about it. So
        // a query ignores folds: the alternative is a list that says "nothing found" about a row the reader can open
        // in the editor, because a branch happened to be closed weeks ago. A *browsing* filter — category, status —
        // does not uncover a folded branch, because it selects rows out of the same notebook rather than asking where
        // something is. The hidden set is measured over the profile's rows either way, so a filter that dropped the
        // row in between cannot reveal what an ancestor hides.
        //
        // `afterFilters` is kept because the card's digit is measured over it rather than over the profile: the
        // number says what a press moves on this screen, and a row a filter dropped is not something any press can
        // bring back.
        val afterFilters = visible
        if (search.isBlank()) {
            val away = TaskHierarchyPolicy.collapsedAwayIds(input.tasks)
            if (away.isNotEmpty()) visible = visible.filter { it.id !in away }
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

        // §7's filter clause, stated as a rule rather than left to the renderer: a filter may hide a parent, but the
        // screen may not then present the parent's child as a root task. So the ancestors the filter dropped are
        // emitted above the row that needs the path — topmost first, once each, and never a row that matched.
        //
        // The walk runs over the profile (`input.tasks`) rather than over the filter's survivors, because a partial
        // path leaves the same false root one level down: two generations can be dropped by one filter.
        val drawnMatches = visible
        val matchedIds = drawnMatches.mapTo(HashSet()) { it.id }
        val displayed = ArrayList<DisplayedRow>(drawnMatches.size)
        val emittedIds = HashSet<Long>(drawnMatches.size)
        val paths = TaskHierarchyPolicy.missingAncestorsBy(input.tasks, matchedIds)
        drawnMatches.forEach { match ->
            paths[match.id].orEmpty().forEach { ancestor ->
                // The ancestor is drawn under *this* answer's header, not under the one its own `group_id` names:
                // a subtree is supposed to keep one topic, and where a pull or a restore left it not doing so,
                // filing the path row under its own header would put a parent below the child it exists to lead —
                // which is the false root §7 forbids, redrawn in a new shape.
                if (emittedIds.add(ancestor.id)) {
                    displayed += DisplayedRow(ancestor, isContext = true, displayGroupId = match.groupId)
                }
            }
            if (emittedIds.add(match.id)) displayed += DisplayedRow(match, isContext = false)
        }
        // The refusals that follow are decided from this: a row nobody asked for is not a row a long press can put
        // into a batch, and the screen is the only place that knows which ones those are.
        contextIds = displayed.filter { it.isContext }.mapTo(HashSet()) { it.task.id }

        val ordered = displayed.map { it.task }
        // Measured over the rows that will actually be drawn, context rows included: the tree the reader imported is
        // the tree they see, and a nested row whose parent the filter dropped is now drawn under that parent rather
        // than at the margin, which is the whole point of inserting the path.
        val depths = TaskTree.depthsByTaskId(ordered)
        // What each card's fold would move on the screen the reader is looking at: the rows drawn under it, or the
        // rows its own fold is holding back. Measured over the filter's survivors rather than over the whole profile,
        // because a row a filter dropped is not something any press can bring back, and a card that counted it would
        // advertise a fold that changes nothing the reader can see.
        val drawn = ordered.mapTo(HashSet()) { it.id }
        val foldDeltas = TaskHierarchyPolicy.foldDeltas(afterFilters, drawn)
        // The screen is drawn as one block per topic header, and that is the shape the hierarchy questions are asked
        // in. The profile holds one sequence, in which two topics' rows alternate; the reader never sees that sequence,
        // they see `notes` under its own header and `receipt` under another. Measuring an indent over the flat order
        // would judge a topic's first row by the last row of the block before it — refusing a move the reader can see
        // is admissible, and naming the reason "another topic" about a row that is not above it on the screen.
        val rowsByGroup = displayed.groupBy { it.displayGroupId }
        val matchesByGroup = drawnMatches.groupBy { it.groupId }
        val groupUuids = input.groups.mapTo(HashSet()) { it.uuid }
        val groupedIds = displayed.mapNotNullTo(HashSet()) { row ->
            row.task.id.takeIf { row.displayGroupId in groupUuids }
        }
        val sections = input.groups.map { group ->
            // A collapsed topic draws none of its rows, and the path rows belong to it as much as the answers do —
            // counting them as grouped is what keeps them out of the tail, under no header at all.
            Section(group, if (group.collapsed) emptyList() else rowsByGroup[group.uuid].orEmpty())
        } + Section(null, displayed.filterNot { it.task.id in groupedIds })
        val blockTasks = sections.map { section -> section.rows.map { it.task } }
        // §6: which rows a press could re-parent, asked once for the whole screen on one read of storage, over the
        // blocks the reader is looking at. The same rule the menu will read when the press arrives, so a card cannot
        // promise a control whose menu has nothing enabled in it.
        val hierarchyOffers = TaskHierarchyPolicy.hierarchyOffers(
            blockTasks, TaskHierarchyPolicy.factsOf(input.tasks)
        )

        val items = mutableListOf<TasksListItem>()
        sections.forEach { section ->
            val group = section.group
            if (group != null) {
                val groupMatches = matchesByGroup[group.uuid].orEmpty()
                val allSelected = groupMatches.isNotEmpty() && groupMatches.all { it.id in selected }
                // The count answers "how many of this topic's rows matched", and scaffolding is not an answer to a
                // question nobody asked it.
                items += TasksListItem.GroupHeader(group, groupMatches.size, allSelected)
            }
            section.rows.forEach { row ->
                items += taskRow(
                    row, input, categoryMap, selected, depths[row.task.id] ?: 0, foldDeltas, hierarchyOffers, mode
                )
            }
        }

        _categories.value = input.categories
        // Whatever a card is about to be asked about, it is asked about the screen that was just drawn: the blocks and
        // the storage are published with the items, so a press and a render can never be reading different trees.
        hierarchyContext = HierarchyScreen(blockTasks, input.tasks, input.groups)
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
                selectedIds = selected
            )
        }
    }

    private fun taskRow(
        row: DisplayedRow,
        input: RenderInput,
        categoryMap: Map<Long, Category>,
        selected: Set<Long>,
        depth: Int,
        foldDeltas: Map<Long, TaskHierarchyPolicy.FoldDelta>,
        hierarchyOffers: Map<Long, Boolean>,
        selectionMode: Boolean
    ) = TasksListItem.TaskRow(
        row.task,
        row.task.categoryId?.let { categoryMap[it] },
        row.task.id in selected,
        row.task.seriesUuid?.let { input.rules[it] },
        input.pendingReminders[row.task.uuid] ?: 0,
        CardWork(
            closedSeconds = input.work.closedSecondsOf(row.task.id),
            liveSeconds = input.work.liveSecondsOf(row.task.id),
            phase = input.work.phaseOf(row.task.id)
        ),
        depth = depth,
        // Which of the two numbers the card prints is the stored state's decision, not the screen's: a row drawn
        // closed advertises what opening it returns, and a row drawn open what folding it takes away. A context row
        // gets no number from [foldDeltas] at all — its universe is the filter's survivors, and a row a filter
        // dropped is not in it — so the flag below is the belt on that brace, and `TasksFilterContextTest` binds a
        // row that carries both, so a future change to that universe cannot quietly grow a control on scaffolding.
        foldRows = if (row.task.childrenCollapsed) {
            foldDeltas[row.task.id]?.reveals ?: 0
        } else {
            foldDeltas[row.task.id]?.hides ?: 0
        },
        selectionMode = selectionMode,
        shownForContext = row.isContext,
        // Two of the three facts are the screen's, not the tree's: a scaffolding row is not a row to re-parent (it
        // is here because an answer needs the path), and nothing moves while a batch is being picked, because a moved
        // row takes its children with it and a selection is a promise about specific ones.
        canEditHierarchy = !row.isContext && !selectionMode && hierarchyOffers[row.task.id] == true
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
        // A scaffolding row cannot *join* a batch — it is here because something else needed the path, not because
        // the reader asked. It can still leave one: a row selected while it was an answer may be drawn as context
        // after the next filter change, and hiding its tick then would leave the reader counting a row they cannot
        // un-count.
        if (taskId in contextIds && taskId !in selectionFlow.value.first) return
        val (selected, mode) = selectionFlow.value
        val next = if (taskId in selected) selected - taskId else selected + taskId
        selectionFlow.value = next to true
        refreshSelectionViews()
    }

    fun enterSelection(taskId: Long) {
        if (taskId in contextIds) return
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
            // "All" means all of the answers, not all of the rows on the screen: the path above a match is drawn
            // because the match needed it, and a filter that hid it hid it for a reason.
            .filterNot { it.shownForContext }
            .map { it.task.id }
            .toSet()
        selectionFlow.value = ids to true
        refreshSelectionViews()
    }

    fun toggleGroupSelection(group: TaskGroup) {
        val groupIds = _state.value.items
            .filterIsInstance<TasksListItem.TaskRow>()
            .filter { it.task.groupId == group.uuid }
            // The same rule as "select all", reached from the other direction: a header's long press sweeps the
            // topic's answers, and a path row above an answer is not one.
            .filterNot { it.shownForContext }
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

    fun deleteSelected(
        onDone: () -> Unit = {},
        policy: HierarchyDeletePolicy? = null,
        onChoice: (branchRows: Int, children: Int, subtree: Int) -> Unit = { _, _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                val ids = selectionFlow.value.first.toList()
                if (ids.isEmpty()) return@launch
                if (deleteRows(ids, policy, onChoice)) {
                    exitSelection()
                    onDone()
                }
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    /**
     * Deletes [ids] through the tree rules, asking the reader rather than choosing for them when a row holds a branch.
     *
     * [onChoice] runs instead of a write when [policy] is null and one of the rows has something under it: the batch
     * is checked as a whole first, so a two-row selection where the second row is a parent cannot delete the first
     * and then ask. Says whether the batch went through, so a bulk command only closes selection mode once it did.
     */
    fun deleteSelectedFor(
        ids: List<Long>,
        policy: HierarchyDeletePolicy? = null,
        onChoice: (branchRows: Int, children: Int, subtree: Int) -> Unit = { _, _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                deleteRows(ids, policy, onChoice)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    private suspend fun deleteRows(
        ids: List<Long>,
        policy: HierarchyDeletePolicy?,
        onChoice: (branchRows: Int, children: Int, subtree: Int) -> Unit
    ): Boolean {
        if (policy == null) {
            val summary = hierarchyRepository.deleteBatchSummary(ids)
            if (summary.branchRows > 0) {
                onChoice(summary.branchRows, summary.children, summary.affectedRows)
                return false
            }
        }
        var refused: HierarchyDeleteRefusal? = null
        val gone = HashSet<Long>()
        for (id in ids) {
            // A subtree delete takes the rows under it, and a selection can hold both a parent and its own child.
            // Deleting the child again would report it as missing — a sentence about a row the reader already retired.
            if (id in gone) continue
            when (val outcome = hierarchyRepository.deleteTask(id, policy)) {
                is HierarchyDeleteResult.Deleted -> {
                    gone += outcome.deletedRows.map { it.id }
                    taskRepository.finishDelete(outcome.deletedRows, outcome.retiredReminders)
                }
                is HierarchyDeleteResult.Confirm -> onChoice(1, outcome.childCount, outcome.subtreeSize)
                is HierarchyDeleteResult.Refused -> refused = outcome.reason
            }
        }
        refused?.let { reportDeleteRefusal(it) }
        return refused == null
    }

    /** A refusal the reader can act on, named in their language: the state carries the id, the screen the words. */
    private fun reportDeleteRefusal(reason: HierarchyDeleteRefusal) {
        val message = when (reason) {
            HierarchyDeleteRefusal.MEASUREMENT_RUNNING -> R.string.delete_blocked_running
            HierarchyDeleteRefusal.ROW_MISSING,
            HierarchyDeleteRefusal.NO_PROFILE,
            HierarchyDeleteRefusal.AMBIGUOUS_IDENTITY -> R.string.delete_row_missing
        }
        _state.update { it.copy(error = message) }
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
            _state.update { it.copy(error = R.string.error_nothing_measured) }
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

    /**
     * Writes a topic's own completed mark (§8), and the header redraws from the stored row.
     *
     * The command takes the state to write rather than a row to flip, because the one door a reader uses — the ⋮ menu —
     * has two decisions behind it, the sentence on the row and the state the press asks for, and [GroupHeaderMenu]
     * decides both from the same `TaskGroup` so they cannot point different ways.
     *
     * Nothing here reads a task: a topic's mark says one thing about one row, and the tasks under it keep the statuses
     * the reader gave them.
     */
    fun setGroupCompleted(uuid: String, completed: Boolean) {
        viewModelScope.launch {
            try {
                groupRepository.setCompleted(uuid, completed)
            } catch (e: Exception) {
                setError(e)
            }
        }
    }

    /**
     * §6: hang a row on the one the screen drew above it, or lift it out of the one it hangs on.
     *
     * The screen names the candidate rather than letting the repository reach for its own predecessor, because under
     * a filter the two are different rows and the reader pressed a control about the one they were looking at. The
     * rule that decided whether the control was there at all is the domain's ([HierarchyMenu]), so a refusal here
     * means the tree changed underneath the card between the draw and the press.
     *
     * A refusal is not nothing: the list is drawn again from storage, the way [toggleBranchCollapse] does for the
     * same reason, rather than left holding a shape no write ever produced.
     */
    fun indentTask(taskId: Long, underTaskId: Long) {
        viewModelScope.launch {
            runHierarchyCommand { hierarchyRepository.indentTask(taskId, underTaskId) }
        }
    }

    /** The lift that undoes an indent: the row goes back to whatever holds its parent, with its own branch intact. */
    fun outdentTask(taskId: Long) {
        viewModelScope.launch {
            runHierarchyCommand { hierarchyRepository.outdentTask(taskId) }
        }
    }

    /**
     * One answer for every command that edits the tree: run it, and if the domain refused, draw the stored order again.
     *
     * The redraw is load-bearing for [moveTaskBeside] and courteous for the other two. A drag has already been applied
     * to the list — the gesture calls `submitList` with the rows as the finger left them, and a refusal writes nothing,
     * so without this the screen would keep showing an order that never existed. An indent or a lift moves the list
     * only when the write lands, so the same call here republishes what is already drawn; it is shared rather than
     * split because "a refused command leaves the screen on storage" is one rule, and two handlers is where a rule
     * starts to disagree with itself.
     */
    private suspend fun runHierarchyCommand(call: suspend () -> HierarchyMoveResult) {
        try {
            if (call() is HierarchyMoveResult.Refused) lastRender?.let(::render)
        } catch (e: Exception) {
            setError(e)
            lastRender?.let(::render)
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

    /**
     * Folds or opens one branch, and draws what the storage now says.
     *
     * The screen holds no collapse state of its own: the column is on the row, the task flow re-emits after the
     * write, and the list re-renders from it — which is why the fold survives a rotation, a restart and a profile
     * switch without a line of code that carries it between screens.
     *
     * A refusal redraws the last render rather than being reported as a failure. The only one a reader can reach is
     * [com.taskplanner.data.hierarchy.HierarchyCollapseRefusal.NO_CHILDREN], and it means the card they tapped had
     * stopped being a parent since it was drawn — someone else deleted its children, or a sync pull re-hung them.
     * The list they are looking at is then the stale thing, so it is rebuilt from storage; nothing they asked for
     * was lost, so there is nothing to apologise for.
     */
    fun toggleBranchCollapse(taskId: Long) {
        viewModelScope.launch {
            try {
                if (hierarchyRepository.toggleBranchCollapse(taskId) is HierarchyCollapseResult.Refused) {
                    lastRender?.let(::render)
                }
            } catch (e: Exception) {
                setError(e)
                lastRender?.let(::render)
            }
        }
    }

    // ---------------------------------------------------------------- ordering

    /**
     * Reorders [taskId] among the rows that share its parent, landing it beside [anchorTaskId].
     *
     * The command names an anchor and a side rather than a list of ids, which is the whole point of it: what the
     * screen used to do was renumber the rows it could see, so a drag made under a filter handed those rows the
     * positions of rows the filter was hiding, and two rows of one profile could claim the same place. Asking for
     * the anchor instead lets the repository place the row in the profile's one sequence and leave every other row's
     * place against every row the drag did not cross exactly where it was.
     *
     * A refusal is not nothing: the list the reader is looking at was already moved by the gesture, so the screen
     * draws the stored order again rather than showing an order that was never written.
     */
    fun moveTaskBeside(taskId: Long, anchorTaskId: Long, placeAfter: Boolean) {
        // A row drawn as context cannot be a drop anchor. Its place in the list is derived from whichever answer
        // needs it, so ordering against it would be a gesture whose result moves when the next filter redraws the
        // path — the card would land somewhere other than where the finger left it. The same answer as any other
        // refusal: draw the stored order again.
        if (anchorTaskId in contextIds) {
            lastRender?.let(::render)
            return
        }
        viewModelScope.launch {
            runHierarchyCommand { hierarchyRepository.moveWithinSiblings(taskId, anchorTaskId, placeAfter) }
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
            _taskSaveState.value = TaskSaveState.Error(R.string.error_title_required)
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
                // The row as storage holds it now, not as this screen loaded it. `updateTask` writes every column,
                // and §6.2 lets the reader move the task from this very screen, so copying the loaded snapshot would
                // hand back the `parent_uuid` the move had just changed — indent a task, press Save, watch the tree
                // put it back. Every field the editor owns still comes from the fields below; only the columns this
                // screen does not own are taken from storage. If the row is gone, the loaded copy stands, which is
                // the same write the old code made and the same one that updates nothing.
                val stored = existing?.let { taskRepository.getTaskById(it.id) }
                val task = (stored ?: existing ?: Task(title = title, description = description, userId = 0)).copy(
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
                    _taskSaveState.value = TaskSaveState.Error(R.string.error_no_profile_first)
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
                Log.w(TAG, "task could not be saved", e)
                _taskSaveState.value = TaskSaveState.Error(R.string.error_save_task)
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

    private suspend fun nextOrder(): Int = taskRepository.nextFreeOrder()

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
                Log.w(TAG, "task could not be opened", e)
                _taskSaveState.value = TaskSaveState.Error(R.string.error_open_task)
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
        // The reader gets the app's own sentence; the complaint that caused it goes to the log.
        Log.w(TAG, "the tasks screen hit a failure", e)
        _state.update { it.copy(error = R.string.error_generic) }
    }

    fun retry() {
        viewModelScope.launch { taskRepository.getActiveTasksOnce() }
    }

    companion object {
        const val NO_CATEGORY_ID = -1L
        fun newTaskUuid(): String = UUID.randomUUID().toString()
    }
}

private const val TAG = "TasksViewModel"

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

    /** The sentence lives in resources; see `CategoriesState.Error` for why the id travels. */
    data class Error(@StringRes val messageRes: Int) : TaskSaveState()
}
