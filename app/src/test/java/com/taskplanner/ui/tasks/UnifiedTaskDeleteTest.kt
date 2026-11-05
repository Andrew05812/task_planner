package com.taskplanner.ui.tasks

import android.app.AlarmManager
import android.content.Context
import android.content.DialogInterface
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.testing.AppFixture
import java.util.Date
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowDialog

/**
 * Stage 16 §4: one delete press, one window, and the branch's fate named *inside* that window.
 *
 * A row with rows under it has two deletes — hand the children up to whatever held the head, or retire the whole
 * branch — and §9's repository refuses to pick one for the reader (`HierarchyDeleteResult.Confirm`, returned by
 * `TaskHierarchyRepository.deleteTask`). §4 is about where the reader meets that refusal, and until now the tasks
 * screen met it in two windows: the card's delete opened the generic confirmation, that window's own button called
 * the command, and only the command's answer — "this row holds a branch" — put up a second window carrying the two
 * real choices. A reader who confirmed the first had agreed to a delete before knowing which delete they had agreed
 * to, which is the sentence the roadmap's §4 exists to make false: what happens to the children has to be legible
 * before the first change to the data, not after it.
 *
 * **What this file measures is a number of windows.** Content alone cannot pin §4 — the old second window was
 * already a correct branch dialog, so every "does the message name the count" assertion passed with the flow still
 * in two steps. [press] therefore marks the list of shown dialogs before the press and reads it after, and
 * [theOneWindow] asserts the count before it lets a test look at a word.
 *
 * **The counter is live, and so is the rest of the file — every claim here was re-run on the tree that ships.** Two
 * scratch edits, each against these 25 tests, then reverted and `cmp`-verified byte-identical. Earlier drafts of this
 * comment quoted counts from smaller versions of the file (22, then 23 tests); those numbers are gone on purpose, and
 * what follows is reproducible by anyone with five minutes:
 *  * a press that puts *two* windows up at once (`DeleteDialog.show` drawing the plain confirmation as well as the
 *    branch window for `asksForBranch`) fails **20 of 25**, every one of them at
 *    `one press puts exactly one window on the screen expected:<1> but was:<2>` — the count is what refuses it. The
 *    five that stay green are `a_row_with_no_children_raises_one_dialog_and_it_is_the_plain_confirmation`,
 *    `a_plain_batch_of_rows_without_children_raises_one_confirmation_naming_the_count`,
 *    `the_bulk_press_asks_about_the_selection_the_reader_is_holding_and_not_the_one_last_drawn`,
 *    `pressing_the_bar_with_nothing_selected_says_so_instead_of_vanishing` and
 *    `a_second_press_while_a_question_is_open_adds_no_window_and_writes_nothing`: presses that never open a branch
 *    window, so there is nothing for the mutation to stack;
 *  * the shape the roadmap actually forbids — a confirmation first, the branch question only after the reader
 *    confirms (`requestDelete` reporting `branchRows = 0` for a press the summary calls a branch) — fails **17 of 25**,
 *    and not on the count: its second window only appears on the answer, so what refuses it is which window the press
 *    opened (`… expected:<[Tasks with subtasks]> but was:<[Delete tasks?]>`), the delete the unasked branch never
 *    performs, and — since [click] began insisting the button it presses is on the screen — the two cancel cases that
 *    used to "cancel" by tapping a neutral button their window had never offered. The eight green are that
 *    mutation's honest blind spots: the same five as above, plus `a_recreate_does_not_summon_the_dialog_a_second_time`
 *    and `stopping_the_view_takes_the_open_window_with_it_and_writes_nothing`, which are about a window going away
 *    rather than which window opens, and
 *    `an_answer_given_against_a_tree_that_grew_a_child_is_asked_about_again_and_writes_nothing`, which builds its own
 *    stale read and so cannot be fooled by the read being wrong.
 *
 * The sentence this replaces claimed the second mutation failed at `expected:<1> but was:<2>`; that run had never
 * been done, and done now it says something narrower and worth keeping straight: the count refuses two windows at
 * once, and a confirmation-that-leads-somewhere is refused by the window's identity and by what the press then
 * writes. Both halves of §4 are therefore watched, and neither rests on the other.
 *
 * **The domain supplies the shape, the screen only draws it.** No number here comes from a walk this file or the
 * screen performs: the request the dialog is built from is `deleteBatchSummary(ids)`, the same read the write path
 * consults, so a row whose children a filter hid is still a branch and still gets the branch window. Nothing in
 * `TasksFragment` counts children any more, and this file never asks a Fragment to be that authority.
 *
 * **Nothing is written before the reader answers.** Every case that stops at a window compares [shape] — which rows
 * are live, in what order, under which parent, in which topic — against the same value taken before the press, so a
 * cancel is shown to be a cancel rather than a delete that reported success.
 *
 * **The harness is production.** [DeleteDialog.bind] is the function `TasksFragment.setupObservers` calls, with the
 * real one-shot channel, the real dialog object and a real `LifecycleRegistry` driving the same
 * `repeatOnLifecycle(STARTED)` the fragment runs under; the rotation cases stop, recreate and press through it, and
 * so does every refusal the collector reports through `onBusy`. What the harness still cannot reach is *which control*
 * calls the view model — `TasksFragment` is an `@AndroidEntryPoint` fragment and this module's unit-test classpath
 * carries no Hilt test component, so the two `setOnClickListener` lines are read rather than run, and the report says
 * so. The bulk press's *set*, at least, moved onto the reachable side of that line this round: the screen now says
 * only that its bar was pressed and [TasksViewModel.requestDeleteSelection] decides what that means, so
 * [the_bulk_press_asks_about_the_selection_the_reader_is_holding_and_not_the_one_last_drawn] measures the authority
 * the reader's ticks land in rather than a copy of a render.
 *
 * Robolectric gives the resource set of the test's own qualifiers, so every string is compared to
 * `getString(...)`/`getQuantityString(...)` and to no English word — the rule `EnglishLiteralAssertionTest` holds
 * over this package — and every geometric claim is box arithmetic, never a guess about glyph widths.
 */
@RunWith(RobolectricTestRunner::class)
class UnifiedTaskDeleteTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var viewModel: TasksViewModel
    private lateinit var screen: TestScreen
    private var userId: Long = 0

    private companion object {
        const val STAMP = 1_700_000_000_000L

        /** The narrowest width the app is designed against — the one the card budget was photographed at. */
        const val NARROW_DP = 320
    }

    /**
     * The screen side of a press: a lifecycle the test drives, and the collector the screen installs.
     *
     * A `LifecycleRegistry` rather than a hosted fragment, because what §4 needs from the lifecycle is the honest
     * `STARTED`/`CREATED` transition `repeatOnLifecycle` reacts to. A fragment would need a Hilt component this
     * classpath does not have and would add no fact the registry does not already pin.
     */
    private inner class TestScreen : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun moveTo(state: Lifecycle.State) {
            when (state) {
                Lifecycle.State.CREATED -> registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                Lifecycle.State.STARTED -> registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                Lifecycle.State.RESUMED -> registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                else -> registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(app)
        // §4's press is a read followed by a window, and the read is a Room query. A coroutine that resumes after a
        // suspending call is dispatched on the continuation's own dispatcher — here `Dispatchers.Main` — so the
        // collector that puts the window up wakes on Main, wherever the storage thread happened to finish. An inline
        // main executor (the shape the rest of the suite uses) would run that wake-up *on the fixture's transaction
        // thread*, and a `Dialog` cannot even be constructed off a looper: the first draft of this class proved it,
        // `Can't create handler inside thread Thread[fixture-transaction-thread …] that has not called
        // Looper.prepare()` swallowed by the collector, and every test then reporting zero windows. So Main posts to
        // the real main looper here, and `pump()` is what drains it: the window is built on the thread the app builds
        // windows on, and nothing in this class races a background resume.
        val toMain = Handler(Looper.getMainLooper())
        Dispatchers.setMain(Executor { command -> toMain.post(command) }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "unified-delete@example.com").id }
        viewModel = TasksViewModel(
            fixture.taskRepository,
            fixture.categoryRepository,
            fixture.groupRepository,
            fixture.recurrenceRepository,
            fixture.reminderRepository,
            fixture.sessionRepository,
            fixture.workTrackingRepository,
            fixture.workStatisticsRepository,
            fixture.taskHierarchyRepository
        )
        bindScreen()
    }

    @After
    fun tearDown() {
        screen.scope.cancel()
        runBlocking { fixture.awaitIdle() }
        fixture.close()
        Dispatchers.resetMain()
    }

    // ── the screen, the press, and the windows it opened ─────────────────────────────────────────────────────

    /** The same five arguments `TasksFragment` passes, into the same production function. */
    private fun bindScreen() {
        screen = TestScreen()
        DeleteDialog.bind(
            owner = screen,
            scope = screen.scope,
            requests = viewModel.deleteRequests,
            context = { themed() },
            onAnswer = { request, policy -> viewModel.resolveDelete(request, policy) },
            onBusy = { viewModel.reportDeleteBusy() }
        )
        screen.moveTo(Lifecycle.State.RESUMED)
        pump()
    }

    private fun themed(): Context = android.view.ContextThemeWrapper(app, R.style.Theme_TaskPlanner)

    /**
     * Settles the two halves of a press: the main looper, then the storage threads it handed its next step to.
     *
     * The two must be driven in that alternating order, and the looper must get the last word. A coroutine that wakes
     * from a Room read posts its *next* suspending call back through Main — so draining storage first and the looper
     * last would read a tree where the second delete of a batch had been written and the third had not even started.
     * The repetition is the convergence: each pass lets one more link of the chain run, and a cycle that never
     * settles is stopped by a named failure rather than being judged on whatever partial state it happened to reach.
     */
    private fun pump() {
        val looper = shadowOf(Looper.getMainLooper())
        // A batch is a chain, not a step: three rows means three transactions, each with its reminder and series
        // follow-up in between, and every link wakes on Main and suspends on storage again. So the two are driven
        // until the looper reports nothing left to run rather than a fixed number of times.
        repeat(30) {
            looper.idle()
            runBlocking { fixture.awaitIdle() }
            if (looper.isIdle) return
        }
        // A chain that never settles is not allowed to be judged on the partial state it happened to reach: that is
        // the exact bug this class was written against — a batch whose third delete had not been dispatched yet read
        // back as a tree the answer had left wrong. Bound alone was a way of hanging up a wrong assertion.
        fail("pump did not converge in 30 passes: the press never settled, so anything read after it is a partial state")
    }

    /** Every window shown since the mark, in the order the screen put them up. */
    private fun windowsSince(mark: Int): List<Dialog> = ShadowDialog.getShownDialogs().drop(mark)

    /**
     * The window one press owes.
     *
     * The count is asserted before any content is read, so a flow that opens a plain confirmation and then a branch
     * dialog fails here instead of passing on the second window's wording.
     */
    private fun theOneWindow(mark: Int): AlertDialog {
        val shown = windowsSince(mark)
        assertEquals("one press puts exactly one window on the screen", 1, shown.size)
        return shown.single() as AlertDialog
    }

    /** A press of one card's delete control: the command that control now runs, and nothing else. */
    private fun press(row: Task): Int {
        val mark = ShadowDialog.getShownDialogs().size
        viewModel.requestDelete(listOf(row.id))
        pump()
        return mark
    }

    /** A press of the selection bar's delete button, over the ids the reader ticked. */
    private fun pressSelection(ids: List<Long>): Int {
        val mark = ShadowDialog.getShownDialogs().size
        viewModel.requestDelete(ids, fromSelection = true)
        pump()
        return mark
    }

    private fun titleOf(window: AlertDialog): String =
        window.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString().orEmpty()

    private fun messageOf(window: AlertDialog): String =
        window.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()

    private fun buttonOf(window: AlertDialog, which: Int): String? = window.getButton(which)?.text?.toString()

    /**
     * Presses one action of a window, and refuses to pretend an absent action was pressed.
     *
     * AppCompat builds all three button views from the alert layout whether or not the dialog offered them, so
     * `getButton(BUTTON_NEUTRAL)` is non-null on a window with no cancel — and `performClick()` on that invisible
     * view still routes through the controller's click handler and dismisses. Without the `isShown` check below, a
     * case that means "the reader cancelled" would pass on a window that never offered a way out, which is how three
     * of this file's cases stayed green under a mutation they were written to catch. A click is only evidence when
     * the thing clicked was on the screen.
     */
    private fun click(window: AlertDialog, which: Int) {
        val button = window.getButton(which)
        assertNotNull("the window has no button ${which}to click", button)
        assertTrue("the action the test presses must be one the window shows", button!!.isShown)
        button.performClick()
        pump()
    }

    // ── the rows ────────────────────────────────────────────────────────────────────────────────────────────

    /** Topics get their own `sort_order`: left at the default they tie, and the query planner picks. */
    private var nextSortOrder = 0

    private fun group(name: String): TaskGroup = runBlocking {
        val group = TaskGroup(
            uuid = name, userId = userId, name = name, sortOrder = nextSortOrder++,
            syncState = SyncState.SYNCED, createdAt = STAMP, updatedAt = STAMP
        )
        fixture.groupDao.upsertGroup(group)
        fixture.groupDao.getGroupByUuid(name)!!
    }

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        groupId: String? = null,
        uuid: String = title
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = uuid, userId = userId, groupId = groupId,
                parentUuid = parent, order = position, syncState = SyncState.SYNCED,
                createdAt = STAMP, updatedAt = STAMP
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun stored(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

    private fun allRows(): List<Task> = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }

    /** Everything a delete could change, in one comparable value: which rows, in what order, hanging where. */
    private fun shape(): List<List<Any?>> = stored().map {
        listOf(it.uuid, it.parentUuid, it.groupId, it.order, it.status)
    }

    private fun retired(): List<String> = allRows().filter { it.isDeleted }.map { it.uuid }.sorted()

    // ── §4: one window, and which window ─────────────────────────────────────────────────────────────────────

    @Test
    fun a_row_with_no_children_raises_one_dialog_and_it_is_the_plain_confirmation() {
        val quiet = row("water the ferns", position = 0)

        val mark = press(quiet)

        val window = theOneWindow(mark)
        assertEquals(
            "a row with nothing under it has one delete, so the window is the confirmation",
            app.getString(R.string.dialog_delete_task_title),
            titleOf(window)
        )
        assertEquals(
            "and it says what it asks",
            app.getString(R.string.dialog_delete_task_message),
            messageOf(window)
        )
        assertEquals(
            "the confirm button is the write",
            app.getString(R.string.delete),
            buttonOf(window, DialogInterface.BUTTON_POSITIVE)
        )
        assertEquals(
            "and cancelling is on the same window, not behind another one",
            app.getString(R.string.cancel),
            buttonOf(window, DialogInterface.BUTTON_NEGATIVE)
        )
        assertEquals(
            "nothing is gone before the answer",
            listOf("water the ferns"),
            stored().map { it.uuid }
        )
    }

    @Test
    fun a_row_with_children_raises_one_dialog_and_it_is_the_branch_choice() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        row("week two", parent = "field notes", position = 2)

        val mark = press(head)

        val window = theOneWindow(mark)
        assertEquals(
            "the one window is the branch question, not a confirmation that leads to it",
            app.getString(R.string.delete_branch_title),
            titleOf(window)
        )
    }

    @Test
    fun the_branch_dialog_states_the_immediate_children_count_and_the_subtree_size() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        row("week two", parent = "field notes", position = 2)
        row("draft", parent = "week one", position = 3)

        val window = theOneWindow(press(head))

        val children = app.resources.getQuantityString(R.plurals.delete_branch_children, 2, 2)
        val rows = app.resources.getQuantityString(R.plurals.delete_branch_rows, 4, 4)
        assertTrue(
            "the message is the branch sentence built from the domain's two figures",
            messageOf(window) == app.getString(R.string.delete_branch_message, children, rows)
        )
        assertTrue(
            "and it names the immediate children (${children})",
            messageOf(window).contains(children)
        )
        assertTrue(
            "the size of the whole branch (${rows})",
            messageOf(window).contains(rows)
        )
    }

    @Test
    fun the_branch_dialog_offers_the_two_fates_and_a_cancel() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)

        val window = theOneWindow(press(head))

        assertEquals(
            "the first choice is the brief's: only this task, with its children handed up",
            app.getString(R.string.delete_policy_promote),
            buttonOf(window, DialogInterface.BUTTON_POSITIVE)
        )
        assertEquals(
            "the second is the branch going with it",
            app.getString(R.string.delete_policy_subtree),
            buttonOf(window, DialogInterface.BUTTON_NEGATIVE)
        )
        assertEquals(
            "and the third is the way out that writes nothing",
            app.getString(R.string.cancel),
            buttonOf(window, DialogInterface.BUTTON_NEUTRAL)
        )
    }

    @Test
    fun the_three_actions_of_the_branch_window_clear_the_touch_floor() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)

        val window = theOneWindow(press(head))
        val decor = window.window!!.decorView
        val width = (NARROW_DP * decor.resources.displayMetrics.density).toInt()
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val floor = app.resources.getDimensionPixelSize(R.dimen.touch_target_min)

        val actions = listOf(
            DialogInterface.BUTTON_POSITIVE to R.string.delete_policy_promote,
            DialogInterface.BUTTON_NEGATIVE to R.string.delete_policy_subtree,
            DialogInterface.BUTTON_NEUTRAL to R.string.cancel
        )
        // Three claims per action, none of them a number this file agreed with itself about. AppCompat inflates all
        // three button views whether or not the dialog offered them, so "three non-null views" is true of a window
        // with two actions too — what a window cannot fake is a button that is shown, says the one thing it was
        // asked to say, and is a different view from its neighbours.
        val views = actions.map { (which, label) ->
            val button = window.getButton(which)
            assertNotNull("the window built a view for action $which", button)
            assertTrue(
                "action $which is not shown, so a reader has nothing to press",
                button!!.isShown
            )
            assertEquals(
                "and the shown view speaks its own resource",
                app.getString(label),
                button.text.toString()
            )
            assertTrue(
                "the action measures ${button.height}px against the app's floor of ${floor}px",
                button.height >= floor
            )
            button
        }
        assertEquals(
            "the three actions are three views, not one view wearing three names",
            3,
            views.distinctBy { System.identityHashCode(it) }.size
        )
    }

    // ── the answers, and what each one writes ────────────────────────────────────────────────────────────────

    @Test
    fun choosing_only_this_task_promotes_the_direct_children_and_retires_only_the_head() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        row("week two", parent = "field notes", position = 2)
        row("draft", parent = "week one", position = 3)

        val window = theOneWindow(press(head))
        click(window, DialogInterface.BUTTON_POSITIVE)

        assertEquals(
            "the head is gone and both its rows are live again",
            listOf("week one", "week two", "draft"),
            stored().map { it.uuid }
        )
        // What a promote leaves in the sequence is what a promote is: the head is retired, its direct children are
        // lifted to the head's parent, and nobody is re-positioned. `getRowsInStoredOrder` is the raw stored order, so
        // `draft` — a child of `week one` — stays written after `week two`, which is `week one`'s sibling. That is not
        // a claim about the drawn tree being right: `TaskTree.depthsByTaskId` indents from parent links, independent of
        // position, so the list shows `draft` at `week one`'s depth *below* `week two`, stranding a grandchild under
        // its parent's sibling. The fix is re-sequencing the promoted subtrees in the domain and is tracked as T4b, in
        // another dispatch; `TaskHierarchyPolicy.canonicalSequence` is not the place that would settle it — it is a
        // tie-breaking sort by `order, dueDate, id`, which fixes *ties* the query leaves open and moves nothing else.
        // This assertion therefore describes today's behaviour, not a promise: when T4b lands, this expectation flips
        // back to `week one, draft, week two` and the test that owns the claim is the one that changes.
        assertNull(
            "the promoted rows hang on nothing now",
            allRows().first { it.uuid == "week one" }.parentUuid
        )
        assertEquals(
            "and the shape below a promoted row is still its own",
            "week one",
            allRows().first { it.uuid == "draft" }.parentUuid
        )
        assertEquals("only the head is a tombstone", listOf("field notes"), retired())
    }

    @Test
    fun choosing_the_branch_retires_the_whole_block_and_nothing_else() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        row("week two", parent = "field notes", position = 2)
        row("draft", parent = "week one", position = 3)
        row("errands", position = 4)

        val window = theOneWindow(press(head))
        click(window, DialogInterface.BUTTON_NEGATIVE)

        assertEquals("the block went with its head", listOf("errands"), stored().map { it.uuid })
        assertEquals(
            "every row of it is retired, none removed",
            listOf("draft", "field notes", "week one", "week two"),
            retired()
        )
        assertEquals("and the neighbour is untouched", 5, allRows().size)
    }

    @Test
    fun cancelling_writes_nothing_and_leaves_the_tree_exactly_as_it_was() {
        group("notebook")
        val head = row("field notes", position = 0, groupId = "notebook")
        row("week one", parent = "field notes", position = 1, groupId = "notebook")
        row("draft", parent = "week one", position = 2, groupId = "notebook")
        val before = shape()

        val window = theOneWindow(press(head))
        click(window, DialogInterface.BUTTON_NEUTRAL)

        assertEquals(
            "a cancel is not a delete that reported success",
            before,
            shape()
        )
        assertEquals("nothing is a tombstone", emptyList<String>(), retired())
        assertEquals(
            "and no row was queued for a push either",
            emptyList<String>(),
            allRows().filter { it.syncState != SyncState.SYNCED }.map { it.uuid }
        )
        assertNull("the screen says nothing, because nothing happened", viewModel.state.value.error)
    }

    // ── a batch is one question about the whole set ──────────────────────────────────────────────────────────

    @Test
    fun a_selection_holding_a_parent_and_its_own_child_asks_once_and_counts_that_row_once() {
        val head = row("field notes", position = 0)
        val child = row("week one", parent = "field notes", position = 1)
        row("draft", parent = "week one", position = 2)

        val mark = pressSelection(listOf(head.id, child.id))

        val window = theOneWindow(mark)
        assertEquals(
            "one question for the whole set, even though two of its rows are branches",
            app.getString(R.string.delete_branch_title),
            titleOf(window)
        )
        val rows = app.resources.getQuantityString(R.plurals.delete_branch_rows, 3, 3)
        assertTrue(
            "the set takes three rows and the sentence counts each once, not the parent twice",
            messageOf(window).contains(rows)
        )
        click(window, DialogInterface.BUTTON_NEGATIVE)
        assertEquals(
            "and the answer retires the block once",
            listOf("draft", "field notes", "week one"),
            retired()
        )
    }

    @Test
    fun a_set_of_several_rows_with_one_branch_row_is_asked_about_as_a_set() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        val first = row("buy stamps", position = 2)
        val second = row("post them", position = 3)

        val window = theOneWindow(pressSelection(listOf(head.id, first.id, second.id)))

        assertEquals(
            "one branch row among three selected tasks is still a set, and the window may not address it as «this " +
                "task» — the singular sentence names one head's own children, and two of these rows are not under it",
            app.getString(
                R.string.delete_branch_message_batch,
                1,
                app.resources.getQuantityString(R.plurals.delete_branch_rows, 4, 4)
            ),
            messageOf(window)
        )
    }

    @Test
    fun a_bulk_selection_whose_branch_rows_are_in_the_second_half_writes_nothing_before_the_choice() {
        val first = row("buy stamps", position = 0)
        val second = row("post them", position = 1)
        val head = row("field notes", position = 2)
        row("week one", parent = "field notes", position = 3)
        val before = shape()

        val mark = pressSelection(listOf(first.id, second.id, head.id))

        val window = theOneWindow(mark)
        assertEquals(
            "the set is asked about as one, and the branch in its second half is part of the question",
            app.getString(R.string.delete_branch_title),
            titleOf(window)
        )
        assertEquals(
            "the rows of the first half are still there, in order",
            before,
            shape()
        )

        click(window, DialogInterface.BUTTON_POSITIVE)
        assertEquals("one answer, then the whole set goes", listOf("week one"), stored().map { it.uuid })
        assertEquals(
            "every row the press named is retired",
            listOf("buy stamps", "field notes", "post them"),
            retired()
        )
    }

    @Test
    fun a_plain_batch_of_rows_without_children_raises_one_confirmation_naming_the_count() {
        val first = row("buy stamps", position = 0)
        val second = row("post them", position = 1)

        val mark = pressSelection(listOf(first.id, second.id))

        val window = theOneWindow(mark)
        assertEquals(
            "a batch with no branch in it is the confirmation, not the branch question",
            app.getString(R.string.confirm_delete_selected_title),
            titleOf(window)
        )
        assertEquals(
            "and it says how many rows it means",
            app.getString(R.string.confirm_delete_selected_message, 2),
            messageOf(window)
        )
        click(window, DialogInterface.BUTTON_POSITIVE)
        assertEquals("both are gone", emptyList<String>(), stored().map { it.uuid })
    }

    @Test
    fun a_cancelled_batch_leaves_the_selection_the_reader_was_building() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        viewModel.toggleSelection(head.id)
        pump()
        assertEquals(
            "the press came from the selection bar",
            setOf(head.id),
            viewModel.selectedIds
        )

        val window = theOneWindow(pressSelection(listOf(head.id)))
        click(window, DialogInterface.BUTTON_NEUTRAL)

        assertEquals("a cancel keeps the batch the reader ticked", setOf(head.id), viewModel.selectedIds)
    }

    @Test
    fun the_bulk_press_asks_about_the_selection_the_reader_is_holding_and_not_the_one_last_drawn() {
        val first = row("buy stamps", position = 0)
        val second = row("post them", position = 1)
        viewModel.toggleSelection(first.id)
        pump()
        assertEquals(
            "the render has caught up with the first tick",
            setOf(first.id),
            viewModel.state.value.selectedIds
        )

        viewModel.toggleSelection(second.id)

        assertEquals("the reader has ticked both", setOf(first.id, second.id), viewModel.selectedIds)
        assertEquals(
            "and the drawn snapshot is still one row behind — which is the set the selection bar used to hand " +
                "over, so a press taken from it asks about rows the reader has already outgrown",
            setOf(first.id),
            viewModel.state.value.selectedIds
        )

        val mark = ShadowDialog.getShownDialogs().size
        viewModel.requestDeleteSelection()
        pump()

        val window = theOneWindow(mark)
        assertEquals(
            "the window asks about the two rows the reader ticked, not the one the last render drew",
            app.getString(R.string.confirm_delete_selected_message, 2),
            messageOf(window)
        )
    }

    // ── the domain's refusals still reach the reader ─────────────────────────────────────────────────────────

    @Test
    fun a_running_measurement_is_refused_names_its_reason_and_leaves_the_tree_untouched() {
        val timed = row("stretch the hamstrings", position = 0)
        row("notes", parent = "stretch the hamstrings", position = 1)
        runBlocking {
            fixture.trackingSessionDao.insert(
                TrackingSession(
                    uuid = "open-unified-session",
                    userId = userId,
                    taskId = timed.id,
                    taskUuid = timed.uuid,
                    taskTitle = timed.title,
                    state = TrackingState.RUNNING,
                    startedAt = fixture.timeBase.wall,
                    runningMarker = userId.toString()
                )
            )
        }
        val before = shape()

        val window = theOneWindow(press(timed))
        click(window, DialogInterface.BUTTON_NEGATIVE)

        assertEquals(
            "the refusal is the named sentence the state carries, not a silent nothing",
            R.string.delete_blocked_running,
            viewModel.state.value.error
        )
        assertEquals("and the tree is exactly what it was", before, shape())
        assertEquals("no second window appeared to explain it", 1, ShadowDialog.getShownDialogs().size)
    }

    // ── the follow-ups that are not database state ───────────────────────────────────────────────────────────

    @Test
    fun promoting_the_children_cancels_the_head_s_reminder_and_keeps_the_children_s_alarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val head = row("field notes", position = 0)
        val child = row("week one", parent = "field notes", position = 1)
        val due = fixture.timeBase.wall + 86_400_000L
        runBlocking {
            fixture.reminderRepository.applyReminders(head.copy(dueDate = due), listOf(30))
            fixture.reminderRepository.applyReminders(child.copy(dueDate = due), listOf(15))
        }
        pump()
        assertEquals("two rows armed, two alarms on the clock", 2, alarms().size)

        val window = theOneWindow(press(head))
        click(window, DialogInterface.BUTTON_POSITIVE)

        assertEquals(
            "the retired row keeps no reminder row",
            emptyList<String>(),
            runBlocking { fixture.reminderDao.getLiveForTask(head.uuid) }.map { it.taskUuid }
        )
        assertEquals(
            "the row that survived keeps its own",
            1,
            runBlocking { fixture.reminderDao.getLiveForTask(child.uuid) }.size
        )
        assertEquals("and only the head's wakeup came off the clock", 1, alarms().size)
    }

    @Test
    fun deleting_a_series_instance_with_its_branch_still_owes_the_next_instance() {
        val instance = saveRepeating("ride the route")
        row("packing list", parent = instance.uuid, position = 1)
        val series = instance.seriesUuid!!

        val window = theOneWindow(press(instance))
        click(window, DialogInterface.BUTTON_NEGATIVE)

        assertEquals(
            "the instance and its branch are retired together",
            listOf("packing list", instance.uuid).sorted(),
            retired()
        )
        val replacement = stored().firstOrNull { it.seriesUuid == series }
        assertNotNull("the series still owes the reader its next task", replacement)
        assertTrue(
            "which is a row of its own, not the tombstone this press made",
            replacement!!.uuid != instance.uuid
        )
    }

    @Test
    fun promoting_the_children_of_a_series_instance_still_owes_the_next_instance() {
        val instance = saveRepeating("ride the route")
        row("packing list", parent = instance.uuid, position = 1)
        val series = instance.seriesUuid!!

        val window = theOneWindow(press(instance))
        click(window, DialogInterface.BUTTON_POSITIVE)

        assertEquals("only the instance was retired", listOf(instance.uuid), retired())
        assertNotNull(
            "and the follow-up the old single delete performed still runs on this path",
            stored().firstOrNull { it.seriesUuid == series && it.uuid != instance.uuid }
        )
    }

    // ── rotation: the pre-read is async, so the window must be summoned once ────────────────────────────────

    @Test
    fun a_recreate_does_not_summon_the_dialog_a_second_time() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)

        val mark = press(head)
        theOneWindow(mark)

        // The view goes away and a new one attaches to the same view model — a rotation.
        screen.scope.cancel()
        bindScreen()

        assertEquals(
            "the question that was already on screen is not asked again",
            emptyList<Dialog>(),
            windowsSince(mark + 1)
        )
        assertEquals("and it was shown once in total", 1, windowsSince(mark).size)
    }

    @Test
    fun a_press_arriving_while_the_screen_is_stopped_raises_one_dialog_when_it_returns() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        screen.moveTo(Lifecycle.State.CREATED)
        pump()

        val mark = press(head)
        assertEquals(
            "nothing is put up while the screen is away",
            emptyList<Dialog>(),
            windowsSince(mark)
        )

        screen.moveTo(Lifecycle.State.STARTED)
        pump()

        val window = theOneWindow(mark)
        assertEquals(
            "and the one window it returns with is the branch question",
            app.getString(R.string.delete_branch_title),
            titleOf(window)
        )
    }

    @Test
    fun stopping_the_view_takes_the_open_window_with_it_and_writes_nothing() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        val before = shape()

        val window = theOneWindow(press(head))
        assertTrue("the question is on the screen before the view goes away", window.isShowing)

        screen.moveTo(Lifecycle.State.CREATED)
        pump()

        assertFalse(
            "a window the reader was never able to answer does not outlive the view that drew it: on a real " +
                "rotation that is a leaked window and a lost question",
            window.isShowing
        )
        assertEquals("and taking it down is not an answer — nothing was written", before, shape())
        assertEquals("no row became a tombstone on the way out", emptyList<String>(), retired())
    }

    @Test
    fun a_second_press_while_a_question_is_open_adds_no_window_and_writes_nothing() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        val before = shape()

        press(head)
        val mark = ShadowDialog.getShownDialogs().size
        viewModel.requestDelete(listOf(head.id))
        pump()

        assertEquals(
            "a question the reader has not answered is not stacked under a second one",
            emptyList<Dialog>(),
            windowsSince(mark)
        )
        assertEquals("and the tree is still the one they were asked about", before, shape())
        assertEquals(
            "the refused press still reaches the reader: a control that did nothing at all reads as a dead " +
                "control, so the drop is named in the state the screen renders",
            R.string.delete_one_question_at_a_time,
            viewModel.state.value.error
        )
    }

    @Test
    fun a_press_the_screen_cannot_even_queue_names_the_same_reason() {
        val head = row("field notes", position = 0)
        row("week one", parent = "field notes", position = 1)
        val other = row("buy stamps", position = 2)
        screen.moveTo(Lifecycle.State.CREATED)
        pump()

        // The collector is stopped, so the first request is still sitting in the channel — and the channel holds
        // one question. The second press has nowhere to go, and `trySend` is the only thing that knows it.
        press(head)
        val mark = ShadowDialog.getShownDialogs().size
        viewModel.requestDelete(listOf(other.id))
        pump()

        assertEquals("nothing was put up while the screen was away", emptyList<Dialog>(), windowsSince(mark))
        assertEquals(
            "and a press dropped at the door is named just like one dropped at the window",
            R.string.delete_one_question_at_a_time,
            viewModel.state.value.error
        )

        screen.moveTo(Lifecycle.State.STARTED)
        pump()

        val window = theOneWindow(mark)
        assertEquals(
            "the question that did get queued is the one the screen returns with",
            app.getString(R.string.delete_branch_title),
            titleOf(window)
        )
    }

    @Test
    fun pressing_the_bar_with_nothing_selected_says_so_instead_of_vanishing() {
        val only = row("buy stamps", position = 0)
        val before = shape()
        viewModel.toggleSelection(only.id)
        pump()
        viewModel.toggleSelection(only.id)
        pump()
        assertEquals("the set is empty", emptySet<Long>(), viewModel.selectedIds)

        val mark = ShadowDialog.getShownDialogs().size
        viewModel.requestDeleteSelection()
        pump()

        assertEquals(
            "a bar whose set emptied is still on screen, and its delete still answers — with a reason, not with " +
                "silence: this is the same swallow §4 closed on the other two paths",
            emptyList<Dialog>(),
            windowsSince(mark)
        )
        assertEquals(
            "the reader is told there is nothing to delete",
            R.string.delete_nothing_selected,
            viewModel.state.value.error
        )
        assertEquals("and no row was touched", before, shape())
    }

    @Test
    fun an_answer_given_against_a_tree_that_grew_a_child_is_asked_about_again_and_writes_nothing() {
        val leaf = row("buy stamps", position = 0)
        // The reader was shown the plain confirmation: at the moment of the read nothing was under this row.
        val asked = DeleteRequest(ids = listOf(leaf.id), branchRows = 0, children = 0, affectedRows = 1)

        // …and a child appeared before the answer reached the write — a sync landing, a series instance, a second
        // device. The write path re-reads, so the confirmation the reader gave cannot silently become a branch delete.
        row("parcel desk", parent = "buy stamps", position = 1)
        pump()
        // Measured after the tree grew: the claim is that the refused answer writes nothing more, so the snapshot has
        // to be the tree the answer arrived against — not the one the read saw, which the child has already changed.
        val before = shape()
        val mark = ShadowDialog.getShownDialogs().size
        viewModel.resolveDelete(asked, null)
        pump()

        val window = theOneWindow(mark)
        assertEquals(
            "the branch question comes back, about the tree as it is now",
            app.getString(R.string.delete_branch_title),
            titleOf(window)
        )
        assertEquals(
            "and it carries the two rows the first read never saw",
            app.getString(
                R.string.delete_branch_message,
                app.resources.getQuantityString(R.plurals.delete_branch_children, 1, 1),
                app.resources.getQuantityString(R.plurals.delete_branch_rows, 2, 2)
            ),
            messageOf(window)
        )
        assertEquals("nothing was written on the way to asking", before, shape())
        assertEquals("and the tree still has both rows live", 2, stored().size)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun alarms() = shadowOf(
        fixture.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    ).scheduledAlarms

    /** A repeating task saved the way the editor saves one, returned as the row it wrote. */
    private fun saveRepeating(title: String): Task {
        viewModel.saveTask(
            taskId = 0L,
            title = title,
            description = "",
            dueDate = Date(fixture.timeBase.wall),
            repeat = RepeatPlan(preset = RepeatPreset.EVERY_N_DAYS, interval = 3)
        )
        pump()
        return stored().first { it.title == title }
    }
}
