package com.taskplanner.ui.categories

import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.hierarchy.TaskDeleteCoordinator
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.testing.AppFixture
import com.taskplanner.ui.tasks.DeleteDialog
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * The category screen's delete, end to end: the press, the one window, the domain write, and the tree afterwards.
 *
 * **Why this file exists.** Stage 16 gave a row the right to have rows under it, and this screen kept the delete it
 * had always had — `taskRepository.deleteTask(task)`, a single-row tombstone that never asks the hierarchy anything.
 * A parent pressed here retired its own row while its children went on naming it, so the task list, which takes its
 * indent from the parent links, drew them as though they had no parent at all. The tasks screen was closed in §4 by
 * making the domain ask; this route was still open. Task 5 lifts the orchestration into
 * [TaskDeleteCoordinator] and puts the same [DeleteDialog] on this screen, so a delete on a category row and a delete
 * on a tasks row are the same domain operation with two sets of words around it.
 *
 * **What the cases insist on.** That a press writes nothing until it is answered; that the window is the branch
 * window when the row holds a branch and a plain confirmation when it does not; that each of the two answers leaves
 * the tree in the shape the domain promises; that the follow-ups which are not database state — the reminder rows and
 * the wakeups they armed, for every row in the branch — go with the delete rather than being left for a screen to
 * remember; that a refusal is said rather than swallowed; and that the orphan the old path left, a live row naming a
 * retired parent, cannot appear at any point in the flow. The last of those is the regression the brief asked for, and
 * it is written as a query over the database rather than as a check that some method was called: a second
 * implementation that orphaned rows differently would still be caught.
 *
 * **And one thing that is not about the tree.** The screen's list is a live query, so a reload that launches a second
 * collector without cancelling the first leaves the view model holding one reader per command it has answered. That is
 * what `answering_a_delete_replaces_the_screen_s_reader_of_its_query_instead_of_adding_one` measures, counting the
 * view model scope's still-active children; §5's review found the shape and the count, `expected:<1> but was:<2>`,
 * is what it was found with.
 *
 * **The harness.** A `LifecycleRegistry` screen rather than a hosted fragment, for the reason §4 gave: what the
 * window needs is a real `STARTED` transition and a Main dispatcher that is a real looper, because a `Dialog` cannot
 * be constructed on the fixture's storage thread. The `DeleteDialog.bind` call below is the same five arguments the
 * fragment passes.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryTaskDeleteTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var viewModel: CategoryTasksViewModel
    private lateinit var screen: TestScreen
    private var category: Long = 0
    private var userId: Long = 0

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
        val toMain = Handler(Looper.getMainLooper())
        Dispatchers.setMain(Executor { command -> toMain.post(command) }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "category-delete@example.com").id }
        category = runBlocking { fixture.addCategory(userId, "Errands") }
        viewModel = CategoryTasksViewModel(
            fixture.taskRepository,
            fixture.categoryRepository,
            TaskDeleteCoordinator(fixture.taskHierarchyRepository, fixture.taskRepository)
        )
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
        viewModel.loadCategoryTasks(category)
        // A StateFlow hands a later collector only the value that is current when it starts, so a state that appears
        // and is replaced between two pumps would never be seen by an assertion on `tasksState.value`. Collecting from
        // the screen's own scope, from before any press, keeps the whole trail — which is what the case about a
        // cancelled reader has to read, because the defect it measures is a state the screen shows for one moment only.
        screen.scope.launch { viewModel.tasksState.collect { observedStates.add(it) } }
        pump()
    }

    @After
    fun tearDown() {
        screen.scope.cancel()
        runBlocking { fixture.awaitIdle() }
        fixture.close()
        Dispatchers.resetMain()
    }

    // ── the rows, the tree, and the press ─────────────────────────────────────────────────────────────────────

    private fun themed(): Context = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)

    private fun row(title: String, parent: String? = null, position: Int): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = title, userId = userId, parentUuid = parent,
                categoryId = category, order = position, syncState = SyncState.SYNCED
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    /** Settles both halves of a press: the main looper, then the storage threads its next step ran on. */
    private fun pump() {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(30) {
            looper.idle()
            runBlocking { fixture.awaitIdle() }
            if (looper.isIdle) return
        }
        throw AssertionError("pump did not converge in 30 passes over the looper and the storage threads")
    }

    private fun live(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

    private fun allRows(): List<Task> = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }

    private fun retiredUuids(): Set<String> = allRows().filter { it.isDeleted }.map { it.uuid }.toSet()

    /** The titles of the live rows whose parent is a tombstone — the orphan the old path left behind. */
    private fun orphaned(): List<String> =
        live().filter { it.parentUuid != null && retiredUuids().contains(it.parentUuid) }.map { it.title }

    /** The press, marked by how many windows the app had shown before it. */
    private fun press(task: Task): Int {
        val mark = ShadowDialog.getShownDialogs().size
        viewModel.requestDelete(task)
        pump()
        return mark
    }

    private fun theOneWindow(mark: Int): AlertDialog {
        val shown = ShadowDialog.getShownDialogs()
        assertEquals("the press puts exactly one window on the screen", mark + 1, shown.size)
        return shown.last() as AlertDialog
    }

    /** Presses a button the window actually offered, and fails if it did not. */
    private fun click(window: AlertDialog, which: Int) {
        val button = window.getButton(which)
        assertNotNull("the window has no button ${which.toButtonName()}", button)
        assertTrue("and that button is not on the screen, so the reader could not have pressed it", button!!.isShown)
        button.performClick()
        pump()
    }

    private fun Int.toButtonName(): String = when (this) {
        DialogInterface.BUTTON_POSITIVE -> "positive"
        DialogInterface.BUTTON_NEGATIVE -> "negative"
        DialogInterface.BUTTON_NEUTRAL -> "neutral"
        else -> "unknown($this)"
    }

    private fun errorRes(): Int? = (viewModel.tasksState.value as? CategoryTasksState.Error)?.messageRes

    private fun shownTitles(): List<String> = when (val state = viewModel.tasksState.value) {
        // The screen's own word for "there is nothing here" is `Empty`, and an emptied category is exactly that case,
        // so the two states read as the same list here rather than one of them being called a failure.
        is CategoryTasksState.Success -> state.tasks.map { it.task.title }
        is CategoryTasksState.Empty -> emptyList()
        else -> error("the screen is showing ${state.javaClass.simpleName}, not a list")
    }

    /**
     * How many coroutines the view model is still holding open, and which one is its reader.
     *
     * The screen's list is a live Room query, so its reader stays active for the life of the view model; every other
     * coroutine the screen launches finishes. A count that grows is therefore a query with more than one reader
     * attached to it, each writing the same state — the shape a reload leaves when it launches a second collector
     * instead of replacing the first. Read from the view model's own scope, which is the scope the collector lives in.
     *
     * The identity comes back as well, because a count alone is a weaker claim than the screen makes: a reload that
     * did not happen at all would keep the count at one, so the case below compares the counts *and* asks that the one
     * reader left standing be a different coroutine than the one that started.
     */
    private fun activeChildren(): List<Job> =
        viewModel.viewModelScope.coroutineContext[Job]!!.children.filter { it.isActive }.toList()

    /** Every state the screen has published since the last clear — the record a transient error cannot hide from. */
    private val observedStates = mutableListOf<CategoryTasksState>()

    private fun hasReminderFor(uuid: String): Boolean =
        runBlocking { fixture.reminderDao.getLiveForTask(uuid) }.isNotEmpty()

    // ── the cases ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_press_on_a_row_with_rows_under_it_does_not_leave_them_naming_a_tombstone() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        row("grandchild", parent = "child", position = 2)

        val mark = press(parent)

        assertEquals("nothing has been retired while the question is open", 3, live().size)
        assertEquals(
            "a branch pressed here may not be answered for the reader: while the question is open nothing is retired, " +
                "and a row that was retired must not still be named by a row the screen shows",
            emptyList<String>(),
            orphaned()
        )
        assertEquals("and the one window is the question itself", mark + 1, ShadowDialog.getShownDialogs().size)
    }

    @Test
    fun the_category_window_offers_the_two_deletes_and_a_way_out() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)

        val window = theOneWindow(press(parent))

        val promote = window.getButton(DialogInterface.BUTTON_POSITIVE)
        val subtree = window.getButton(DialogInterface.BUTTON_NEGATIVE)
        val cancel = window.getButton(DialogInterface.BUTTON_NEUTRAL)
        assertEquals("by the sentence the resources carry", window.context.getString(R.string.delete_policy_promote), promote!!.text)
        assertEquals("the destructive one on the negative button", window.context.getString(R.string.delete_policy_subtree), subtree!!.text)
        assertEquals("and the way out is the window's own", window.context.getString(R.string.cancel), cancel!!.text)
    }

    @Test
    fun choosing_only_this_task_promotes_the_children_and_leaves_the_shape_below_them() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        row("grandchild", parent = "child", position = 2)
        row("errands", position = 3)

        val window = theOneWindow(press(parent))
        click(window, DialogInterface.BUTTON_POSITIVE)

        assertEquals("only the pressed row went", listOf("parent"), allRows().filter { it.isDeleted }.map { it.uuid })
        assertNull("the child hangs on what the parent hung on", allRows().first { it.uuid == "child" }.parentUuid)
        assertEquals("and the grandchild still hangs from its own parent", "child", allRows().first { it.uuid == "grandchild" }.parentUuid)
        assertEquals("nothing is printed apart from its head", emptyList<String>(), orphaned())
        assertEquals(
            "the list the screen shows is the tree without the row that went",
            listOf("child", "grandchild", "errands"),
            shownTitles()
        )
    }

    @Test
    fun choosing_the_branch_retires_every_row_of_it_and_nothing_beside() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        row("grandchild", parent = "child", position = 2)
        row("errands", position = 3)

        val window = theOneWindow(press(parent))
        click(window, DialogInterface.BUTTON_NEGATIVE)

        assertEquals(
            "the block went with its head, as tombstones rather than as dust",
            listOf("child", "grandchild", "parent"),
            allRows().filter { it.isDeleted }.map { it.uuid }.sorted()
        )
        assertEquals("and the neighbour is the only row left to read", listOf("errands"), live().map { it.title })
        assertEquals("still no live row names a retired one", emptyList<String>(), orphaned())
        assertEquals("the screen shows what is left", listOf("errands"), shownTitles())
    }

    @Test
    fun cancelling_writes_nothing_and_leaves_the_question_answered_only_by_the_next_press() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        val before = live().map { listOf(it.title, it.parentUuid, it.syncState) }

        val window = theOneWindow(press(parent))
        click(window, DialogInterface.BUTTON_NEUTRAL)

        assertEquals("the branch is exactly where it was", before, live().map { listOf(it.title, it.parentUuid, it.syncState) })
        assertEquals("nothing was retired", emptyList<String>(), allRows().filter { it.isDeleted }.map { it.uuid })
    }

    @Test
    fun a_row_with_nothing_under_it_is_confirmed_rather_than_asked() {
        val leaf = row("errands", position = 0)

        val window = theOneWindow(press(leaf))

        // AppCompat hands back all three button views whatever the dialog offered, so "the neutral button is null"
        // cannot fail and proves nothing. What says this window is a confirmation rather than a choice is that no
        // third action is on the screen at all.
        assertFalse(
            "a delete with one possible answer is not a choice",
            window.getButton(DialogInterface.BUTTON_NEUTRAL)!!.isShown
        )
        assertEquals(
            "and it is titled with the sentence for a single task",
            window.context.getString(R.string.dialog_delete_task_title),
            window.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text
        )

        click(window, DialogInterface.BUTTON_POSITIVE)

        assertEquals("the answer retired it", listOf("errands"), allRows().filter { it.isDeleted }.map { it.uuid })
        assertEquals("and the screen has nothing left to show", emptyList<String>(), shownTitles())
    }

    @Test
    fun a_row_being_measured_refuses_the_answer_and_says_why_instead_of_saying_nothing() {
        val timed = row("measured", position = 0)
        runBlocking {
            fixture.trackingSessionDao.insert(
                TrackingSession(
                    uuid = "open-category-session",
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
        val before = live().map { listOf(it.title, it.isDeleted) }

        val window = theOneWindow(press(timed))
        click(window, DialogInterface.BUTTON_POSITIVE)

        assertEquals("the refusal is the named sentence the state carries", R.string.delete_blocked_running, errorRes())
        assertEquals("and the tree is exactly what it was", before, live().map { listOf(it.title, it.isDeleted) })
    }

    @Test
    fun a_second_press_while_the_question_is_open_says_so_instead_of_stacking_a_second_window() {
        val first = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        val other = row("standalone", position = 2)
        val mark = press(first)

        val secondMark = press(other)

        assertEquals("the first press opened exactly the one window", mark + 1, secondMark)
        assertEquals(
            "and the second press opened no other — it was refused while the first was still unanswered",
            secondMark,
            ShadowDialog.getShownDialogs().size
        )
        assertEquals(
            "and it said which refusal the reader met, rather than vanishing",
            R.string.delete_one_question_at_a_time,
            errorRes()
        )
    }

    @Test
    fun the_window_the_reader_never_answered_dies_with_the_view_and_writes_nothing() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        val window = theOneWindow(press(parent))

        screen.moveTo(Lifecycle.State.CREATED)
        pump()

        assertTrue("the open window is gone with the view that held it", !window.isShowing)
        assertEquals("and nothing was written by its passing", emptyList<String>(), allRows().filter { it.isDeleted }.map { it.uuid })
        assertEquals("the branch is intact", 2, live().size)
    }

    @Test
    fun a_branch_deleted_here_retires_the_reminder_rows_of_every_row_in_it() {
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        val due = fixture.timeBase.wall + 86_400_000L
        runBlocking {
            fixture.reminderRepository.applyReminders(parent.copy(dueDate = due), listOf(30))
            fixture.reminderRepository.applyReminders(child.copy(dueDate = due), listOf(15))
        }
        pump()
        assertTrue("the branch was armed before the delete", hasReminderFor(parent.uuid))

        val window = theOneWindow(press(parent))
        click(window, DialogInterface.BUTTON_NEGATIVE)

        assertEquals(
            "the head's reminder went with it",
            emptyList<String>(),
            runBlocking { fixture.reminderDao.getLiveForTask(parent.uuid) }.map { it.taskUuid }
        )
        assertEquals(
            "and so did the child's, which the raw single-row delete never even saw — the follow-ups are the " +
                "coordinator's, which is why this screen does not have to remember them",
            emptyList<String>(),
            runBlocking { fixture.reminderDao.getLiveForTask(child.uuid) }.map { it.taskUuid }
        )
    }

    @Test
    fun answering_a_delete_replaces_the_screen_s_reader_of_its_query_instead_of_adding_one() {
        val leaf = row("leaf", position = 0)
        val readersBefore = activeChildren()

        val window = theOneWindow(press(leaf))
        click(window, DialogInterface.BUTTON_POSITIVE)
        val readersAfter = activeChildren()

        assertEquals(
            "exactly one live reader of the category query survives the reload — a screen that collects a live query " +
                "in a launch it never cancels grows one observer per command it answers, all of them writing the same " +
                "list",
            1,
            readersAfter.size
        )
        assertEquals("and the screen started with one", 1, readersBefore.size)
        assertNotSame(
            "the one that is left is the reload's own, not the row it replaced: a count that cannot move would also " +
                "be satisfied by a reload that never happened",
            readersBefore.single(),
            readersAfter.single()
        )
        assertEquals("and the row really did go", emptyList<String>(), shownTitles())
    }

    @Test
    fun replacing_the_screen_s_reader_does_not_tell_the_reader_the_list_failed_to_load() {
        val leaf = row("leaf", position = 0)

        val window = theOneWindow(press(leaf))
        observedStates.clear()
        click(window, DialogInterface.BUTTON_POSITIVE)

        assertEquals(
            "cancelling the collector a reload replaces is not a load that failed. A `catch (e: Exception)` around a " +
                "`collect` swallows the `CancellationException` the cancel forces, and the screen answers a routine " +
                "reload with «Couldn't load tasks» — transiently, so an assertion on the state at the end of the "
                    + "pump would never see it, which is why this case reads the trail the collector recorded",
            emptyList<CategoryTasksState.Error>(),
            observedStates.filterIsInstance<CategoryTasksState.Error>()
        )
        assertEquals("and the list the reload asked for is what is on the screen", emptyList<String>(), shownTitles())
    }
}
