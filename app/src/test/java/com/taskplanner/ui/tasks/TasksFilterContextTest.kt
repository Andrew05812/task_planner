package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * §7's filter clause: a filter may not present somebody's subtask as a root task.
 *
 * The brief is exact about this — «Если фильтр скрывает родителя, нельзя показывать его потомка так, будто он стал
 * корневой задачей», with the preferred answer being «показывать путь предков без превращения скрытой задачи в ложного
 * root» — and the app before this file did the forbidden thing: `TaskTree.depthsByTaskId` measures over the rows the
 * filters left, so a category or a status filter that dropped a parent moved its child to the card's own margin and
 * drew it as a topic's first task.
 *
 * The chosen approach is the preferred one: the missing ancestors are drawn, immediately above the row that needs
 * them, marked as what they are. Four rules make that safe rather than a leak, and each is asserted here:
 *
 *  * a context row is **marked in three channels** — a shape at the head of its title, a lowered card, and line B's
 *    category slot saying what it is — because alpha alone is a colour claim and §15 refuses colour alone;
 *  * it is **not a match**: the topic's count and the reader's own count say how many rows answered the filter, so an
 *    inserted ancestor cannot inflate either;
 *  * it is **not selectable**, and it offers no fold and no drag handle: it is scaffolding the reader did not ask
 *    for, and a batch that swallows a row nobody matched is a data-loss story, not a convenience;
 *  * **a fold above the path still holds** — inserting a chain must not uncover a branch the reader closed, which is
 *    the same rule §7's first half pinned for filters, now pinned against the renderer that could break it.
 *
 * An ancestor that *did* match the filter is never drawn twice: a `ListAdapter` keyed on task id would crash on the
 * duplicate, and a row that appears twice is its own lie.
 */
@RunWith(RobolectricTestRunner::class)
class TasksFilterContextTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var viewModel: TasksViewModel
    private lateinit var hierarchy: TaskHierarchyRepository
    private var userId: Long = 0

    private companion object {
        /** Seeded `SYNCED` at a fixed instant, so a later write can be told apart from a touch that changed nothing. */
        const val FIXTURE_MILLIS = 1_700_000_000_000L
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>()
        fixture = AppFixture(app)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "context@example.com").id }
        hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
        viewModel = TasksViewModel(
            fixture.taskRepository, fixture.categoryRepository, fixture.groupRepository,
            fixture.recurrenceRepository, fixture.reminderRepository, fixture.sessionRepository,
            fixture.workTrackingRepository, fixture.workStatisticsRepository, hierarchy
        )
    }

    @After
    fun tearDown() {
        // The fixture first, the dispatcher after — a resetMain() landing while a Room thread is still
        // emitting races the dispatcher. CI run 81 found it; AppFixture.close keeps the account.
        fixture.close()
        Dispatchers.resetMain()
    }

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        categoryId: Long? = null,
        status: TaskStatus = TaskStatus.TODO,
        group: String? = null,
        collapsed: Boolean = false
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = title, userId = userId, groupId = group,
                parentUuid = parent, categoryId = categoryId, status = status, order = position,
                childrenCollapsed = collapsed, syncState = SyncState.SYNCED,
                createdAt = FIXTURE_MILLIS, updatedAt = FIXTURE_MILLIS
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun settle(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            runBlocking { fixture.awaitIdle() }
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
        runBlocking { fixture.awaitIdle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rows() = viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>()

    private fun titles() = rows().map { it.task.title }

    private fun awaitTitles(what: String, expected: List<String>) {
        settle(what) { titles() == expected }
        assertEquals(what, expected, titles())
    }

    private fun rowOf(title: String) = rows().first { it.task.title == title }

    private fun headers() = viewModel.state.value.items.filterIsInstance<TasksListItem.GroupHeader>()

    /**
     * The card paints Material components and theme attributes, which the bare application context cannot resolve
     * — the same reason every other card test in this package wraps one in a `ContextThemeWrapper` (see
     * `TaskCardReminderTest`), and the reason an `InflateException` at the card's own line is a fixture bug rather
     * than a product one.
     */

    @Test
    fun `a_topic_header_long_press_sweeps_the_answers_and_not_the_path`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val topic = runBlocking { fixture.addGroup(userId, "grp-sweep") }
        val plan = row("planning", position = 0, categoryId = work, group = topic.uuid)
        val draft = row("draft", parent = "planning", position = 1, categoryId = errand, group = topic.uuid)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path and the answer", listOf("planning", "draft"))

        // The other door into a batch: the header's long press selects the topic's rows. `planning` is on the
        // screen because `draft` needs it, not because the reader asked, so a sweep that takes it would delete a
        // task the filter had excluded.
        viewModel.toggleGroupSelection(topic)
        settle("the sweep to be written") { viewModel.state.value.selectedIds.isNotEmpty() }

        assertEquals("only the answer is in the batch", setOf(draft.id), viewModel.state.value.selectedIds)
        assertFalse("and the header did not take the path row with it",
            viewModel.state.value.selectedIds.contains(plan.id))
    }

    @Test
    fun `a_row_demoted_to_context_keeps_its_tick_and_can_leave_the_batch`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val plan = row("planning", position = 0, categoryId = work)
        row("draft", parent = "planning", position = 1, categoryId = errand)

        // Selected while it was an answer, demoted by the next filter change.
        runBlocking { viewModel.setCategoryFilter(work) }
        awaitTitles("the head alone", listOf("planning"))
        viewModel.enterSelection(plan.id)
        settle("the selection to be written") { viewModel.state.value.selectedIds == setOf(plan.id) }

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the same row drawn as the path", listOf("planning", "draft"))
        settle("the demotion to reach the state") { rowOf("planning").shownForContext }

        assertTrue("it stays in the batch it joined as an answer, because the count on the bar has to be true",
            viewModel.state.value.selectedIds.contains(plan.id))
        val checked = bindAll(selection = true).first { (item, _) -> item.task.id == plan.id }
        assertTrue("and its tick is drawn, so the reader can see it and take it out",
            checked.second.findViewById<View>(R.id.selectionCheck).isVisible)

        viewModel.toggleSelection(plan.id)
        settle("the removal to be written") { !viewModel.state.value.selectedIds.contains(plan.id) }
        assertEquals("a demoted row can leave; it simply cannot arrive",
            emptySet<Long>(), viewModel.state.value.selectedIds)
    }

    @Test
    fun `a_parent_from_another_topic_is_still_drawn_above_the_answer_it_leads`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val second = runBlocking { fixture.addGroup(userId, "grp-second") }
        // A profile no gesture can produce, written straight through the DAO the way a pull or a restore can leave
        // it: an ancestor filed under a topic that is not in the profile's own list of topics at all. Filing the
        // path row under *its* header would then drop it into the tail, below the child it exists to lead — the
        // false root again, one header away.
        row("planning", position = 0, categoryId = work, group = "grp-ghost")
        val draft = row("draft", parent = "planning", position = 1, categoryId = errand, group = second.uuid)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the foreign parent above its child", listOf("planning", "draft"))

        assertTrue("the ancestor did not match, so it is scaffolding", rowOf("planning").shownForContext)
        assertEquals("the placement is the screen's, not a write: the row keeps the topic it was stored under",
            "grp-ghost", rowOf("planning").task.groupId)
        assertEquals("and the header above them counts one answer",
            1, headers().single { it.group.uuid == second.uuid }.taskCount)
        assertTrue("the answer is where the path says it should be",
            titles().indexOf("planning") < titles().indexOf("draft"))
        assertEquals("nothing was written by the screen", draft.id, rowOf("draft").task.id)
    }

    @Test
    fun `a_collapsed_topic_hides_its_path_rows_with_the_rest`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val topic = runBlocking { fixture.addGroup(userId, "grp-collapsed") }
        row("planning", position = 0, categoryId = work, group = topic.uuid)
        row("draft", parent = "planning", position = 1, categoryId = errand, group = topic.uuid)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path and the answer under the topic", listOf("planning", "draft"))

        viewModel.toggleGroupCollapsed(topic)
        settle("the topic to close") { viewModel.state.value.items.none { it is TasksListItem.TaskRow } }

        assertEquals(
            "a closed topic draws none of its rows — a path row left over would arrive under no header at all, " +
                "which is the false root this block exists to stop, wearing a different hat",
            emptyList<String>(), titles()
        )
    }

    @Test
    fun `a_scaffolding_row_with_a_count_still_grows_no_fold_control`() {
        // The renderer cannot produce this pair today: `foldDeltas` counts within the filter's survivors, and a
        // context row is by definition not among them. The card is still the place that decides whether to draw the
        // control, so the pair is bound directly — and if the count's universe ever changes, this is the test that
        // says which way the card answers.
        val item = TasksListItem.TaskRow(
            task = Task(id = 91, uuid = "scaffold", title = "planning", description = "", userId = userId,
                childrenCollapsed = false),
            category = null, isSelected = false, foldRows = 3, shownForContext = true
        )

        val card = bindOne(item)

        assertFalse("a scaffolding row keeps its fold to itself",
            card.findViewById<View>(R.id.branchFold).isVisible)
        assertTrue("and an answer carrying the same count draws it",
            bindOne(item.copy(shownForContext = false)).findViewById<View>(R.id.branchFold).isVisible)
    }

    @Test
    fun `a_move_anchored_on_a_scaffolding_row_is_refused`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        // The arrangement where a scaffolding row and a match really are siblings, so the data would *accept* the
        // move and only the screen's own guard can refuse it: `spine` matches and holds three children, one of them
        // (`branch`) dropped by the filter and redrawn as the path above its own child `leaf`. `other` and `third`
        // are `branch`'s siblings, so ordering `other` beside the scaffolding `branch` is a legal sibling move in the
        // profile — and a gesture whose result would move on the next render, because `branch` is emitted above
        // whichever answer needs it rather than where the finger left it.
        val spine = row("spine", position = 0, categoryId = errand)
        row("branch", parent = "spine", position = 1, categoryId = work)
        val other = row("other", parent = "spine", position = 2, categoryId = errand)
        val third = row("third", parent = "spine", position = 3, categoryId = errand)
        row("leaf", parent = "branch", position = 4, categoryId = errand)
        val stored = listOf("spine", "branch", "other", "third", "leaf")

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path above its own child", listOf("spine", "other", "third", "branch", "leaf"))
        assertTrue("and the row used as an anchor here is scaffolding", rowOf("branch").shownForContext)

        viewModel.moveTaskBeside(other.id, rowOf("branch").task.id, placeAfter = false)
        settle("the refusal to leave the stored order alone") { storedTitles() == stored }
        assertEquals(
            "the profile is untouched by a gesture the screen refused. `placeAfter = false` is the aim that would " +
                "actually move something: in the stored sequence `other` already sits *after* `branch`, so asking for " +
                "after would have been a no-op the test could not tell from a refusal — the first draft of this test " +
                "did exactly that and stayed green with the guard deleted",
            stored, storedTitles()
        )

        // The positive control, without which the assertion above could only mean that moving is broken: the same
        // command between two rows the reader picked does write, in the same fixture.
        viewModel.moveTaskBeside(third.id, other.id, placeAfter = false)
        settle("the accepted move to be written") { storedTitles() != stored }
        assertEquals("a real sibling move lands where it was aimed",
            listOf("spine", "branch", "third", "other", "leaf"), storedTitles())
    }

    /** One item, painted by the real adapter into an inflated card, at the narrowest screen this app supports. */
    private fun bindOne(item: TasksListItem.TaskRow): View {
        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {}, onDeleteRequest = {},
            onStart = {}, onPause = {}, onResume = {}, onComplete = {}, onWorkToggle = {}, onToggleBranch = {}, onHierarchy = { _, _ -> },
            onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.submitList(listOf(item))
        settle("the item to reach the adapter") { adapter.currentList == listOf(item) }
        val holder = adapter.createViewHolder(FrameLayout(themed()), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder.itemView
    }

    private fun storedTitles(): List<String> = runBlocking {
        fixture.taskDao.getRowsInStoredOrder(userId).map { it.title }
    }

    private fun themed(): Context =
        android.view.ContextThemeWrapper(app, R.style.Theme_TaskPlanner)

    /** The screen's own path: the real adapter painting the state the view model published. */
    private fun bindAll(
        selection: Boolean = false,
        items: List<TasksListItem> = viewModel.state.value.items
    ): List<Pair<TasksListItem.TaskRow, View>> {
        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {}, onDeleteRequest = {},
            onStart = {}, onPause = {}, onResume = {}, onComplete = {}, onWorkToggle = {}, onToggleBranch = {}, onHierarchy = { _, _ -> },
            onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.reorderEnabled = true
        adapter.selectionMode = selection
        val items = viewModel.state.value.items
        adapter.submitList(items)
        settle("the adapter to hold the screen") { adapter.itemCount == items.size }
        return items.filterIsInstance<TasksListItem.TaskRow>().mapIndexed { index, item ->
            val holder = adapter.createViewHolder(FrameLayout(themed()), adapter.getItemViewType(index))
            adapter.bindViewHolder(holder, index)
            item to holder.itemView
        }
    }

    @Test
    fun `a_filtered_row_is_drawn_under_the_ancestors_the_filter_dropped`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        row("planning", position = 0, categoryId = work)
        row("draft", parent = "planning", position = 1, categoryId = work)
        row("figures", parent = "draft", position = 2, categoryId = errand)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path to be drawn above the match", listOf("planning", "draft", "figures"))

        assertTrue("the filter dropped the head of the path, so it is here as scaffolding",
            rowOf("planning").shownForContext)
        assertTrue("and the middle row was dropped by the same filter", rowOf("draft").shownForContext)
        assertFalse("only the row the reader asked for is an answer", rowOf("figures").shownForContext)
        assertEquals("the depth is read off the rows on screen, so the match sits two steps in, not at the margin",
            listOf(0, 1, 2), listOf(rowOf("planning").depth, rowOf("draft").depth, rowOf("figures").depth))
    }

    @Test
    fun `an_ancestor_that_matched_the_filter_is_never_drawn_twice`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        row("planning", position = 0, categoryId = work)
        row("draft", parent = "planning", position = 1, categoryId = errand)
        row("figures", parent = "draft", position = 2, categoryId = work)

        runBlocking { viewModel.setCategoryFilter(work) }
        awaitTitles("the plan drawn once", listOf("planning", "draft", "figures"))

        assertEquals("three rows on the screen, and `planning` is one of them",
            1, rows().count { it.task.title == "planning" })
        assertTrue("`planning` matched, so it is not scaffolding", !rowOf("planning").shownForContext)
        assertTrue("`draft` was dropped by the filter and is here to hold the path",
            rowOf("draft").shownForContext)
        assertTrue("and `figures` matched the filter itself", !rowOf("figures").shownForContext)
    }

    @Test
    fun `context_rows_answer_to_the_topic_but_are_not_counted_as_matches`() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-context") }
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        row("planning", position = 0, categoryId = work, group = topic.uuid)
        row("draft", parent = "planning", position = 1, categoryId = errand, group = topic.uuid)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path and the match", listOf("planning", "draft"))

        assertEquals("the topic holds one row the reader asked for, and the header has to say one",
            1, headers().single().taskCount)
        assertEquals(
            "and the header subtitle still answers to the notebook, not to one filter: the profile holds both " +
                "rows whether the screen draws them or not",
            2, viewModel.state.value.activeTaskCount
        )
    }

    @Test
    fun `a_fold_above_the_path_still_holds_the_branch_from_a_filter`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val plan = row("planning", position = 0, categoryId = work)
        row("draft", parent = "planning", position = 1, categoryId = work)
        row("figures", parent = "draft", position = 2, categoryId = errand)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path to reach the match", listOf("planning", "draft", "figures"))

        runBlocking { hierarchy.toggleBranchCollapse(plan.id) }
        settle("the fold to hold the branch it closed") { titles().isEmpty() }

        assertEquals(
            "inserting an ancestor chain must not uncover what the reader folded — the fold is a fact about the " +
                "branch, and a filter is not a way of opening it",
            emptyList<String>(), titles()
        )
    }

    @Test
    fun `a_search_hit_is_drawn_under_its_parent_because_a_hit_without_a_place_is_a_riddle`() {
        val plan = row("planning", position = 0)
        row("draft the figures", parent = "planning", position = 1)

        runBlocking { viewModel.setSearchQuery("figures") }
        awaitTitles("the hit and the row above it", listOf("planning", "draft the figures"))

        assertTrue("the query did not match the parent, so the parent is context",
            rowOf("planning").shownForContext)
        assertTrue("and the hit is the answer", !rowOf("draft the figures").shownForContext)
    }

    @Test
    fun `a_context_row_refuses_to_join_a_batch`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val plan = row("planning", position = 0, categoryId = work)
        row("draft", parent = "planning", position = 1, categoryId = errand)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path to be drawn", listOf("planning", "draft"))

        // The positive control first: a long press works, so the two refusals below are refusals and not a test
        // that was never able to select anything. `settle` on the selection being *empty* before a command is a
        // condition that is already true, and a vacuous one.
        viewModel.enterSelection(rowOf("draft").task.id)
        settle("the answer to be selected") { viewModel.state.value.selectedIds == setOf(rowOf("draft").task.id) }

        viewModel.enterSelection(plan.id)
        settle("the demotion of the batch to be written") { viewModel.state.value.selectedIds.size == 1 }
        assertEquals(
            "a long press on the path above an answer replaces the batch with nothing — the reader never asked for " +
                "this row, and a batch that deletes a task the filter had excluded is a data-loss story",
            setOf(rowOf("draft").task.id), viewModel.state.value.selectedIds
        )
        viewModel.toggleSelection(plan.id)
        assertEquals("and a tick on it cannot add it either",
            setOf(rowOf("draft").task.id), viewModel.state.value.selectedIds)
        assertTrue("a matched row is still selectable on its own",
            viewModel.state.value.selectedIds.contains(rowOf("draft").task.id))
    }

    @Test
    fun `select_all_under_a_filter_selects_the_answers_and_not_the_path`() {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        val plan = row("planning", position = 0, categoryId = work)
        val draft = row("draft", parent = "planning", position = 1, categoryId = errand)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("the path and the answer", listOf("planning", "draft"))

        viewModel.selectAllVisible()
        settle("the batch to be written") { viewModel.state.value.selectedIds.isNotEmpty() }

        assertEquals("only the row that answered the filter", setOf(draft.id), viewModel.state.value.selectedIds)
        assertFalse("and the ancestor the screen inserted is not in it",
            viewModel.state.value.selectedIds.contains(plan.id))
    }

    /**
     * The three-row arrangement the presentation tests share: the filter keeps the middle of a branch and drops its
     * head, so the screen holds one scaffolding row above two answers — which is the only arrangement that can say
     * both halves of the rule in one picture: what a context row withholds, and what a matched parent keeps.
     */
    private fun contextAndMatchCards(): Map<String, View> {
        val work = runBlocking { fixture.addCategory(userId, "cat-work") }
        val errand = runBlocking { fixture.addCategory(userId, "cat-errand") }
        row("planning", position = 0, categoryId = work)
        row("draft", parent = "planning", position = 1, categoryId = errand)
        row("figures", parent = "draft", position = 2, categoryId = errand)

        runBlocking { viewModel.setCategoryFilter(errand) }
        awaitTitles("all three rows to reach the screen", listOf("planning", "draft", "figures"))

        return bindAll().associate { (item, view) -> item.task.title to view }
    }

    // Four tests, not one with five assertions: a break in one channel should name itself in the failure list, and a
    // single test that stops at its first assertion cannot do that.

    @Test
    fun `a_scaffolding_card_leads_its_title_with_a_shape_rather_than_only_a_colour`() {
        // One fixture per test: `row()` seeds uuids equal to titles, and the profile is walked by uuid, so building
        // the same three rows twice would create the ambiguity the hierarchy refuses on purpose.
        val cards = contextAndMatchCards()

        assertNotNull(
            "the mark is a picture at the head of the title, so it survives a colour-blind reader, a screen in " +
                "monochrome and a reader who cannot see the alpha difference",
            cards.getValue("planning").findViewById<TextView>(R.id.taskTitle).compoundDrawablesRelative[0]
        )
        assertNull("and a matched row carries no such mark",
            cards.getValue("draft").findViewById<TextView>(R.id.taskTitle).compoundDrawablesRelative[0])
    }

    @Test
    fun `a_scaffolding_card_says_what_it_is_in_the_line_that_names_a_category`() {
        val cards = contextAndMatchCards()

        assertEquals(
            "the word that would stand here is the category the filter was not asking for; the slot says what the " +
                "row is instead, in a sentence a screen reader and a magnified reader both get",
            app.getString(R.string.row_shown_for_context),
            cards.getValue("planning").findViewById<TextView>(R.id.taskCategory).text.toString()
        )
        assertNotEquals("a matched row's slot is still its category's name",
            app.getString(R.string.row_shown_for_context),
            cards.getValue("draft").findViewById<TextView>(R.id.taskCategory).text.toString())
    }

    @Test
    fun `a_scaffolding_card_sits_back_from_an_answer`() {
        val cards = contextAndMatchCards()

        assertTrue(
            "a third channel, and the weakest of the three on its own: ${cards.getValue("planning")
                .findViewById<View>(R.id.taskCard).alpha} against ${cards.getValue("draft")
                .findViewById<View>(R.id.taskCard).alpha}",
            cards.getValue("planning").findViewById<View>(R.id.taskCard).alpha <
                cards.getValue("draft").findViewById<View>(R.id.taskCard).alpha
        )
    }

    @Test
    fun `a_scaffolding_card_withholds_the_fold_and_the_handle_and_an_answer_keeps_both`() {
        val cards = contextAndMatchCards()
        val context = cards.getValue("planning")
        val match = cards.getValue("draft")

        assertFalse("a scaffolding row is not a thing to drag, because a reordering gesture on it would reorder " +
                "a row the reader is not looking at",
            context.findViewById<View>(R.id.dragHandle).isVisible)
        assertFalse("nor a thing to fold, even though two rows hang under it",
            context.findViewById<View>(R.id.branchFold).isVisible)
        assertTrue("the matched parent keeps its own fold control",
            match.findViewById<View>(R.id.branchFold).isVisible)
        assertTrue("and its own handle", match.findViewById<View>(R.id.dragHandle).isVisible)
    }

    @Test
    fun `a_plain_list_without_a_filter_draws_no_context_rows_at_all`() {
        row("planning", position = 0)
        row("draft", parent = "planning", position = 1)

        awaitTitles("the tree", listOf("planning", "draft"))

        assertEquals("nothing is scaffolding when the whole notebook is on screen",
            0, rows().count { it.shownForContext })
        assertEquals("and the indent is the tree's own, not a context row's doing",
            listOf(0, 1), rows().map { it.depth })
    }
}
