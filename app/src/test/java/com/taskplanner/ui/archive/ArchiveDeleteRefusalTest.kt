package com.taskplanner.ui.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.hierarchy.TaskDeleteCoordinator
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
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

/**
 * The archive screen's "delete forever", read as the screen leaves the reader.
 *
 * **Why this file exists.** [ArchiveHardDeleteTest] proves the domain half: the branch under a physically removed row
 * is re-hung, so no live row names a row that is gone. This class asks the other question — what the reader is *told*
 * when the domain refuses. `TaskHierarchyRepository.deleteTask` answers `Refused(MEASUREMENT_RUNNING)` for a row whose
 * timer is still running, and the screen used to drop that answer on the floor: the row stayed in the list, the control
 * looked dead, and nothing said why. Stage 16 §5 put the archive behind the coordinator, which is the route that can
 * refuse, so the refusal became this screen's problem to answer rather than the repository's to explain.
 *
 * **The state is reachable, and here is the way in.** `completeAndArchive` stops the timer on the way in
 * (`TaskRepository:107-116`), which is why the local flow cannot produce an archived row that is being measured — but
 * the archive does not only hold rows this device archived. A pull writes `archived = remote.archived`
 * (`SyncAppliers.applyRemoteTask`) and does not touch this device's `TrackingSession` rows at all, so a task started
 * here and archived on another device arrives in the archive still running. That row is refused, and the sentence has
 * to be said.
 *
 * **What the cases insist on.** That a refusal names itself as a resource id — §14's rule that a sentence the reader
 * reads lives in resources, and `R.string.delete_blocked_running` is §4's existing word for it, in both locales, so
 * this adds no string and no hardcoded English; that the refused row and everything under it are untouched; and, as
 * the control, that the delete which *is* allowed leaves the row out of the table and the screen with nothing to say.
 *
 * **Why the id rides beside `error` rather than inside it.** `ArchiveState.error` is an exception's own sentence, and
 * Stage 14 §4 wrote that this screen is one of three where a machine's words may still reach a Snackbar, that the fix
 * is a resource for the reader and the log for the cause, and that it belongs to the error-handling stage. §5 has no
 * business doing another stage's work on the side, so it adds [ArchiveState.refusalRes] for the sentence the app does
 * own — its refusal — and leaves the exception channel alone. The measured failure before the field existed was
 * `expected:<2131886308> but was:<null>` against the screen's single message channel: no word, whatever the reason.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveDeleteRefusalTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: ArchiveViewModel
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "archive-refusal@example.com").id }
        viewModel = ArchiveViewModel(
            fixture.taskRepository,
            fixture.categoryRepository,
            TaskDeleteCoordinator(fixture.taskHierarchyRepository, fixture.taskRepository)
        )
        settle()
    }

    @After
    fun tearDown() {
        runBlocking { fixture.awaitIdle() }
        fixture.close()
        Dispatchers.resetMain()
    }

    private fun row(title: String, parent: String? = null, position: Int, archived: Boolean = false): Task =
        runBlocking {
            val id = fixture.taskDao.insertTask(
                Task(
                    title = title, description = "", uuid = title, userId = userId, parentUuid = parent,
                    order = position, archived = archived, syncState = SyncState.SYNCED
                )
            )
            fixture.taskDao.getTaskById(id)!!
        }

    /** A timer running on this device, measured against the row it belongs to. */
    private fun startMeasuring(task: Task) = runBlocking {
        fixture.trackingSessionDao.insert(
            TrackingSession(
                uuid = "open-${task.uuid}",
                userId = userId,
                taskId = task.id,
                taskUuid = task.uuid,
                taskTitle = task.title,
                state = TrackingState.RUNNING,
                startedAt = fixture.timeBase.wall,
                runningMarker = userId.toString()
            )
        )
    }

    /** Drains the fixture's two storage threads, repeatedly, until a chain of them has run to the end. */
    private fun settle() {
        repeat(10) { runBlocking { fixture.awaitIdle() } }
    }

    /**
     * Waits for the archive's own live reader to have drawn what the table holds.
     *
     * The list is a Room query the view model collects, so it arrives on the fixture's threads rather than on the test
     * thread's; a case that asserts on it before it lands would be measuring the harness. Convergence is checked by
     * name, so a reader that never arrives fails as the missing list rather than as a later, unrelated assertion.
     */
    private fun awaitListed(expected: List<String>) {
        repeat(20) {
            runBlocking { fixture.awaitIdle() }
            if (titles() == expected) return
        }
        assertEquals("the archive never came round to listing ${expected}", expected, titles())
    }

    private fun titles(): List<String> = viewModel.state.value.items.map { it.task.title }

    private fun everyRow(): List<Task> = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }

    @Test
    fun a_row_still_being_measured_is_refused_out_loud_and_stays_exactly_where_it_was() {
        val timed = row("audited", position = 0, archived = true)
        startMeasuring(timed)
        awaitListed(listOf("audited"))

        viewModel.deleteForever(timed)
        settle()

        assertEquals(
            "the refusal reaches the reader as the resource id that means it, not as nothing at all",
            R.string.delete_blocked_running,
            viewModel.state.value.refusalRes
        )
        assertEquals("and the row is still on the list", listOf("audited"), titles())
        assertEquals("still in the table, still a live row", false, everyRow().single().isDeleted)
    }

    @Test
    fun a_refused_head_leaves_the_rows_under_it_alone_as_well() {
        val timed = row("audited", position = 0, archived = true)
        row("work", parent = "audited", position = 1)
        startMeasuring(timed)
        awaitListed(listOf("audited"))

        viewModel.deleteForever(timed)
        settle()

        assertEquals("the refusal is the whole answer", R.string.delete_blocked_running, viewModel.state.value.refusalRes)
        assertEquals(
            "and nothing retired: the branch is exactly as it was, head included",
            emptyList<String>(),
            everyRow().filter { it.isDeleted }.map { it.uuid }
        )
        assertEquals(
            "both rows are still live",
            listOf("audited", "work"),
            runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }.map { it.title }
        )
    }

    @Test
    fun a_row_that_is_only_archived_goes_forever_and_leaves_the_screen_with_nothing_to_say() {
        val archived = row("done", position = 0, archived = true)
        awaitListed(listOf("done"))

        viewModel.deleteForever(archived)
        settle()

        assertNull("no refusal, so no sentence either", viewModel.state.value.refusalRes)
        assertTrue("the row is out of the table, not merely out of the list", everyRow().isEmpty())
        awaitListed(emptyList())
    }
}
