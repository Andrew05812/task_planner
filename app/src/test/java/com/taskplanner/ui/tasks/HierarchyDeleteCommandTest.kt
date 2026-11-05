package com.taskplanner.ui.tasks

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyDeletePolicy
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.testing.AppFixture
import com.taskplanner.testing.deleteSelectionThroughTheWindow
import com.taskplanner.testing.deleteThroughTheWindow
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The tasks screen's delete command, after Stage 16 gave a row the right to have rows under it.
 *
 * Before the tree could be edited at all, a delete could hand a list of ids to the repository and be done: every row
 * was a top-level row, so nothing was orphaned and no question had to be asked. Stage 15 put a nested notebook in the
 * same table through the Keep import, and Stage 16 lets the reader nest by hand — so the screen now has to answer
 * "what happens to the branch?" and it may not answer it on the reader's behalf.
 *
 * **The shape this pins.** The command is asked with no policy and either deletes or *asks*: the press reads the tree
 * and hands the screen the branch's size through `onChoice`, and writes nothing until the reader picks one of the two
 * operations. A row with nothing under it never asks — a dialog with one possible answer is noise — and a refusal is
 * reported through the state's error channel as a resource id, the way every other failure on this screen already
 * is, so the sentence the reader sees is the one their language speaks.
 *
 * **Why the cases call a helper and not the view model.** `TasksViewModel.deleteSelected` and `deleteSelectedFor`
 * were deleted by Stage 16 §5, and the two local helpers below send the same command through the route a reader takes
 * — see [com.taskplanner.testing.deleteThroughTheWindow]. Pinning a method no screen can reach would pin a door that
 * does not exist.
 *
 * The follow-ups the old single call performed are checked here too rather than assumed: an import-created branch
 * deleted through the new path must still cancel the alarms of the rows that went and must still let a running series
 * materialise its next instance.
 */
@RunWith(RobolectricTestRunner::class)
class HierarchyDeleteCommandTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: TasksViewModel
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "delete-command@example.com").id }
        val hierarchy = TaskHierarchyRepository(
            fixture.database, fixture.taskDao, fixture.groupDao, fixture.trackingSessionDao, fixture.reminderDao,
            fixture.sessionRepository, fixture.timeBase
        )
        viewModel = TasksViewModel(
            fixture.taskRepository,
            fixture.categoryRepository,
            fixture.groupRepository,
            fixture.recurrenceRepository,
            fixture.reminderRepository,
            fixture.sessionRepository,
            fixture.workTrackingRepository,
            fixture.workStatisticsRepository,
            hierarchy
        )
    }

    @After
    fun tearDown() {
        // The fixture first, the dispatcher after — a resetMain() landing while a Room thread is still
        // emitting races the dispatcher. CI run 81 found it; AppFixture.close keeps the account.
        fixture.close()
        Dispatchers.resetMain()
    }

    private fun row(title: String, parent: String? = null, position: Int): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = title, userId = userId, parentUuid = parent,
                order = position, syncState = SyncState.SYNCED
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun stored(): Map<String, Task> =
        runBlocking { fixture.taskDao.getAllRowsOnce(userId) }.associateBy { it.title }

    private fun live(): List<String> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }.map { it.title }

    /**
     * The command the cases send, taken through the only door a delete has left.
     *
     * `TasksViewModel.deleteSelectedFor` and `deleteSelected` were removed in Stage 16 §5: two public methods that
     * could write without asking, wired to no screen — the live path is `requestDelete` → the window →
     * `resolveDelete`, and a second way in is exactly the kind of door §4 spent its round closing. Both helpers here
     * are that round trip without a dialog, and the shared implementation is documented in
     * [com.taskplanner.testing.deleteThroughTheWindow].
     */
    private fun deleteSelectedFor(
        ids: List<Long>,
        policy: HierarchyDeletePolicy? = null,
        onChoice: (branchRows: Int, children: Int, subtree: Int) -> Unit = { _, _, _ -> }
    ) = viewModel.deleteThroughTheWindow(ids, policy, { fixture.awaitIdle() }, onChoice)

    /** The selection bar's press, for the one case that is about the selection rather than a list of ids. */
    private fun deleteSelected(
        policy: HierarchyDeletePolicy? = null,
        onChoice: (branchRows: Int, children: Int, subtree: Int) -> Unit = { _, _, _ -> }
    ) = viewModel.deleteSelectionThroughTheWindow(policy, { fixture.awaitIdle() }, onChoice)

    @Test
    fun a_leaf_deletes_straight_through_and_never_opens_the_branch_question() {
        val leaf = row("leaf", position = 0)
        val asked = AtomicInteger(0)

        deleteSelectedFor(listOf(leaf.id), onChoice = { _, _, _ -> asked.incrementAndGet() })
        fixture.awaitIdle()

        assertEquals("nothing was asked", 0, asked.get())
        assertEquals("and the live list lost it", emptyList<String>(), live())
    }

    @Test
    fun a_branch_delete_asks_the_screen_and_writes_nothing_until_an_answer_comes() {
        val parent = row("parent", position = 0)
        row("first", parent = "parent", position = 1)
        row("second", parent = "first", position = 2)
        val asked = AtomicReference<Pair<Int, Int>?>(null)

        deleteSelectedFor(listOf(parent.id), onChoice = { _, children, subtree ->
            asked.set(children to subtree)
        })
        fixture.awaitIdle()

        assertEquals("the dialog is told two rows hang below, three in the branch", 1 to 3, asked.get())
        assertEquals("nothing was deleted while the question is open", listOf("parent", "first", "second"), live())
        assertEquals(
            "and no row was marked for a push either",
            SyncState.SYNCED,
            stored().getValue("parent").syncState
        )
    }

    @Test
    fun the_answer_moves_the_children_up_and_leaves_the_grandchild_with_its_own_parent() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        row("grandchild", parent = "child", position = 2)

        deleteSelectedFor(listOf(parent.id), policy = HierarchyDeletePolicy.PROMOTE_CHILDREN)
        fixture.awaitIdle()

        assertEquals("the parent is gone from the list", listOf("child", "grandchild"), live())
        assertNull("the child is now the topic's own row", stored().getValue("child").parentUuid)
        assertEquals("and the grandchild still hangs from it", "child", stored().getValue("grandchild").parentUuid)
        assertEquals("the row that went is a tombstone", true, stored().getValue("parent").isDeleted)
    }

    @Test
    fun the_other_answer_retires_the_whole_branch_as_tombstones() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        row("grandchild", parent = "child", position = 2)
        row("neighbour", position = 3)

        deleteSelectedFor(listOf(parent.id), policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertEquals("only the neighbour is left", listOf("neighbour"), live())
        assertEquals("the branch is still stored, retired", 4, stored().size)
        listOf("parent", "child", "grandchild").forEach { title ->
            assertEquals("$title is a tombstone", true, stored().getValue(title).isDeleted)
            assertEquals("$title is queued for a delete push", SyncState.DELETING, stored().getValue(title).syncState)
        }
    }

    @Test
    fun a_batch_with_one_branch_in_it_asks_once_and_deletes_none_of_the_batch() {
        val leaf = row("leaf", position = 0)
        val parent = row("parent", position = 1)
        row("child", parent = "parent", position = 2)
        val asked = AtomicInteger(0)

        deleteSelectedFor(listOf(leaf.id, parent.id), onChoice = { _, _, _ -> asked.incrementAndGet() })
        fixture.awaitIdle()

        assertEquals("one question for the whole batch", 1, asked.get())
        assertEquals("and the row that needed no question is still there too", 3, live().size)
    }

    @Test
    fun a_batch_answered_with_a_policy_goes_whole() {
        val leaf = row("leaf", position = 0)
        val parent = row("parent", position = 1)
        row("child", parent = "parent", position = 2)

        deleteSelectedFor(listOf(leaf.id, parent.id), policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertEquals("every row of the batch went, and the branch with its head", emptyList<String>(), live())
        assertEquals("nothing was physically removed", 3, stored().size)
    }

    @Test
    fun a_task_still_being_measured_refuses_and_says_so_in_the_readers_language() {
        val timed = row("timed", position = 0)
        runBlocking {
            fixture.trackingSessionDao.insert(
                TrackingSession(
                    uuid = "open-command-session",
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

        deleteSelectedFor(listOf(timed.id), policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertEquals(
            "the refusal reaches the state as a resource id, not as a sentence the app wrote at the moment of failure",
            R.string.delete_blocked_running,
            viewModel.state.value.error
        )
        assertEquals("the row is still on the list", listOf("timed"), live())
    }

    @Test
    fun a_row_that_is_already_gone_says_so_rather_than_failing_quietly() {
        val gone = row("gone", position = 0)
        runBlocking { fixture.taskRepository.deleteTask(gone) }
        fixture.awaitIdle()

        deleteSelectedFor(listOf(gone.id), policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertEquals(R.string.delete_row_missing, viewModel.state.value.error)
        assertTrue("and the tombstone stayed a tombstone", stored().getValue("gone").isDeleted)
    }

    @Test
    fun deleting_a_branch_retires_the_reminder_rows_of_every_row_in_it() {
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        val due = fixture.timeBase.wall + 86_400_000L
        runBlocking {
            fixture.reminderRepository.applyReminders(parent.copy(dueDate = due), listOf(30))
            fixture.reminderRepository.applyReminders(child.copy(dueDate = due), listOf(15))
        }
        fixture.awaitIdle()
        assertTrue("the branch was armed before the delete", hasAlarmFor(parent.uuid))

        deleteSelectedFor(listOf(parent.id), policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertEquals(
            "a retired row must not keep an alarm that will fire for it",
            emptyList<String>(),
            runBlocking {
                listOf(parent, child).flatMap { fixture.reminderDao.getLiveForTask(it.uuid) }.map { it.taskUuid }
            }
        )
    }

    @Test
    fun a_branch_delete_takes_the_scheduled_alarm_with_the_row() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        val due = fixture.timeBase.wall + 86_400_000L
        runBlocking {
            fixture.reminderRepository.applyReminders(parent.copy(dueDate = due), listOf(30))
            fixture.reminderRepository.applyReminders(child.copy(dueDate = due), listOf(15))
        }
        fixture.awaitIdle()
        assertEquals("two reminders, two alarms on the real clock", 2, alarms().size)

        deleteSelectedFor(listOf(parent.id), policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertEquals(
            "a row the reader retired must not keep a wakeup waiting for it — the reminder *rows* go inside the " +
                "delete's transaction, and only the caller, which still has them, can cancel what they scheduled",
            emptyList<Long>(),
            alarms().map { it.triggerAtMs }
        )
    }

    @Test
    fun a_selection_holding_a_parent_and_its_own_child_deletes_once_and_does_not_report_the_child_as_missing() {
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)

        deleteSelectedFor(listOf(parent.id, child.id), policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertNull(
            "the child went with its parent, so the second delete is not a failure the reader should be told about",
            viewModel.state.value.error
        )
        assertEquals("both rows are tombstones", 2, stored().values.count { it.isDeleted })
        assertEquals("and the live list holds neither", emptyList<String>(), live())
    }

    @Test
    fun a_batch_that_still_holds_a_question_leaves_the_selection_open_and_the_answer_closes_it() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        viewModel.toggleSelection(parent.id)
        fixture.awaitIdle()
        assertEquals("the row is selected", setOf(parent.id), viewModel.selectedIds)

        deleteSelected()
        fixture.awaitIdle()

        assertEquals(
            "the batch has not been deleted, so the reader is still holding a selection they can act on",
            setOf(parent.id),
            viewModel.selectedIds
        )

        deleteSelected(policy = HierarchyDeletePolicy.DELETE_SUBTREE)
        fixture.awaitIdle()

        assertEquals("and answering the question closes the mode", emptySet<Long>(), viewModel.selectedIds)
        assertEquals(
            "with the branch gone",
            listOf("child", "parent"),
            stored().values.filter { it.isDeleted }.map { it.title }.sorted()
        )
    }

    private fun alarms() = shadowOf(
        fixture.context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    ).scheduledAlarms

    private fun hasAlarmFor(taskUuid: String): Boolean =
        runBlocking { fixture.reminderDao.getLiveForTask(taskUuid) }.isNotEmpty()
}
