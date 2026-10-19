package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.tracking.ConflictResolution
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the tracking commands on the screens actually write.
 *
 * The card and the editor both offer one control per task, and the whole point of that control is that
 * the answer is stored rather than displayed: these tests read the rows back after each tap, because a
 * Start that only moved a number on screen would leave the history screen, the statistics and the next
 * app start with nothing to show.
 */
@RunWith(RobolectricTestRunner::class)
class TasksWorkCommandTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: TasksViewModel
    private var userId: Long = 0

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor makes every collector finish on the
        // test thread rather than wait for a looper nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "work-ui@example.com").id }
        viewModel = TasksViewModel(
            fixture.taskRepository,
            fixture.categoryRepository,
            fixture.groupRepository,
            fixture.recurrenceRepository,
            fixture.reminderRepository,
            fixture.sessionRepository,
            fixture.workTrackingRepository,
            fixture.workStatisticsRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun addTask(title: String): Task = runBlocking {
        val id = fixture.addTask(userId, title)
        fixture.taskDao.getTaskById(id)!!
    }

    private fun sessions() = runBlocking { fixture.trackingSessionDao.getAllForUser(userId) }

    private fun intervals() = runBlocking { fixture.workSessionDao.getAllForUser(userId) }

    private fun workOf(title: String): CardWork =
        viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>()
            .first { it.task.title == title }.work

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        // Room answers on its own threads, so the wait starts by letting them settle: without that the
        // first check can be satisfied by the state a previous command published.
        fixture.awaitIdle()
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `the card's control starts the measurement the row then shows as running`() {
        val report = addTask("Write the report")

        viewModel.toggleWork(report)

        awaitUntil("the card to show the run") { workOf("Write the report").phase == TrackingPhase.RUNNING }
        val session = sessions().single()
        assertEquals(report.id, session.taskId)
        assertEquals(TrackingState.RUNNING, session.state)
        assertEquals("the open stretch is what the ticker counts", 1, intervals().size)
        assertNull(intervals().single().endedAt)
    }

    @Test
    fun `the same control pauses what is running and resumes it without a second session`() {
        val report = addTask("Write the report")
        viewModel.toggleWork(report)
        awaitUntil("the run to start") { workOf("Write the report").phase == TrackingPhase.RUNNING }

        fixture.timeBase.advance(90_000L)
        viewModel.toggleWork(report)
        awaitUntil("the pause to close the stretch") { workOf("Write the report").phase == TrackingPhase.PAUSED }

        assertEquals(TrackingState.PAUSED, sessions().single().state)
        assertEquals("a pause is a closed interval, not a deleted one", 1, intervals().size)
        assertEquals(90L, intervals().single().durationSeconds)

        viewModel.toggleWork(report)
        awaitUntil("the resume to reopen measurement") { workOf("Write the report").phase == TrackingPhase.RUNNING }

        assertEquals("one task is one session, however many stretches it takes", 1, sessions().size)
        assertEquals(2, intervals().size)
    }

    @Test
    fun `starting a second task asks which timer to keep instead of choosing for the user`() {
        val report = addTask("Write the report")
        val draft = addTask("Review the draft")
        viewModel.toggleWork(report)
        awaitUntil("the first run") { workOf("Write the report").phase == TrackingPhase.RUNNING }

        viewModel.toggleWork(draft)

        awaitUntil("the question") { viewModel.workConflict.value != null }
        val conflict = viewModel.workConflict.value
        assertNotNull("the refusal has to name the task holding the timer", conflict)
        assertEquals(report.id, conflict!!.runningTaskId)
        assertEquals("Write the report", conflict.runningTaskTitle)
        assertEquals(draft.id, conflict.targetTask.id)
        assertEquals(
            "nothing is decided yet, so nothing is written for the second task",
            1,
            sessions().size
        )
        assertEquals(TrackingPhase.IDLE, workOf("Review the draft").phase)
    }

    @Test
    fun `cancelling the conflict leaves the first task measured and the second untouched`() {
        val report = addTask("Write the report")
        val draft = addTask("Review the draft")
        viewModel.toggleWork(report)
        awaitUntil("the first run") { workOf("Write the report").phase == TrackingPhase.RUNNING }
        viewModel.toggleWork(draft)
        awaitUntil("the question") { viewModel.workConflict.value != null }

        viewModel.resolveWorkConflict(ConflictResolution.CANCEL)

        awaitUntil("the question to go away") { viewModel.workConflict.value == null }
        assertEquals(1, sessions().size)
        assertEquals(TrackingState.RUNNING, sessions().single().state)
        assertEquals(TrackingPhase.RUNNING, workOf("Write the report").phase)
        assertEquals(TrackingPhase.IDLE, workOf("Review the draft").phase)
    }

    @Test
    fun `pausing the first task on request hands the timer to the second`() {
        val report = addTask("Write the report")
        val draft = addTask("Review the draft")
        viewModel.toggleWork(report)
        awaitUntil("the first run") { workOf("Write the report").phase == TrackingPhase.RUNNING }
        fixture.timeBase.advance(60_000L)
        viewModel.toggleWork(draft)
        awaitUntil("the question") { viewModel.workConflict.value != null }

        viewModel.resolveWorkConflict(ConflictResolution.PAUSE_ACTIVE)

        awaitUntil("the timer to change hands") {
            workOf("Review the draft").phase == TrackingPhase.RUNNING &&
                workOf("Write the report").closedSeconds == 60L
        }
        assertEquals(2, sessions().size)
        assertEquals(TrackingPhase.PAUSED, workOf("Write the report").phase)
        assertEquals(
            "the minute already measured on the first task is stored, not thrown away",
            60L,
            workOf("Write the report").closedSeconds
        )
    }

    @Test
    fun `stopping the first task on request saves it and starts the second`() {
        val report = addTask("Write the report")
        val draft = addTask("Review the draft")
        viewModel.toggleWork(report)
        awaitUntil("the first run") { workOf("Write the report").phase == TrackingPhase.RUNNING }
        fixture.timeBase.advance(120_000L)
        viewModel.toggleWork(draft)
        awaitUntil("the question") { viewModel.workConflict.value != null }

        viewModel.resolveWorkConflict(ConflictResolution.STOP_ACTIVE)

        awaitUntil("the second run") { workOf("Review the draft").phase == TrackingPhase.RUNNING }
        val stopped = sessions().first { it.taskId == report.id }
        assertEquals(TrackingState.COMPLETED, stopped.state)
        assertEquals(120L, intervals().first { it.taskId == report.id }.durationSeconds)
        assertTrue("no interval is left open on a stopped session", intervals().all { it.taskId != report.id || it.endedAt != null })
    }

    @Test
    fun `the stop command closes the stretch and leaves the measured time behind`() {
        val report = addTask("Write the report")
        viewModel.toggleWork(report)
        awaitUntil("the run") { workOf("Write the report").phase == TrackingPhase.RUNNING }
        fixture.timeBase.advance(45_000L)

        viewModel.stopWork(report)

        awaitUntil("the stored figure and the idle card") {
            workOf("Write the report").phase == TrackingPhase.IDLE &&
                workOf("Write the report").closedSeconds == 45L
        }
        assertEquals(TrackingState.COMPLETED, sessions().single().state)
        assertEquals(45L, workOf("Write the report").closedSeconds)
        assertEquals(45L, intervals().single().durationSeconds)
    }
}
