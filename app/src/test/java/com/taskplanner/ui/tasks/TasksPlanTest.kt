package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The plan rows of the task editor, end to end: what saving them stores, what the calendar can then
 * draw from it, and what the editor is told when the plan cannot exist.
 *
 * The promise these tests hold is the one the whole feature rests on: the calendar draws the plan the
 * user described and nothing else. A task saved with a start and an end gets that length; a task
 * saved without one is drawn from its deadline alone, with no minute added by the app.
 */
@RunWith(RobolectricTestRunner::class)
class TasksPlanTest {

    private lateinit var context: Context
    private lateinit var fixture: AppFixture
    private lateinit var viewModel: TasksViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor keeps every collector on the
        // test thread instead of waiting for a looper that nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        runBlocking { fixture.signIn(email = "plan-ui@example.com") }
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

    private fun moment(daysAhead: Int, hour: Int, minute: Int): Pair<Date, LocalDate> {
        val day = LocalDate.now().plusDays(daysAhead.toLong())
        val millis = day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Date(millis) to day
    }

    private fun rows() = viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>()

    private fun stored(title: String) = runBlocking {
        fixture.taskDao.getAllRowsOnce(fixture.sessionRepository.currentUserId()!!)
            .first { it.title == title }
    }

    /** Room notifies flow collectors from a background executor, so the test has to wait for it. */
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

    private fun awaitSaved(what: String = "the save to report success") {
        awaitUntil(what) { viewModel.taskSaveState.value == TaskSaveState.Success }
    }

    /** The list screen re-renders from Room, so a test reads a card only once it is there. */
    private fun awaitRow(title: String) {
        awaitUntil("the card for $title") { rows().any { it.task.title == title } }
    }

    @Test
    fun `saving a task with a start and an end puts that block on its day in the calendar`() {
        val (start, day) = moment(3, 9, 0)
        val (end, _) = moment(3, 10, 30)

        viewModel.saveTask(
            title = "Workshop",
            description = "Room two",
            dueDate = end,
            startTime = start,
            endTime = end
        )

        awaitSaved()
        val plan = runBlocking { fixture.calendarRepository.dayOnce(day) }
        assertEquals(
            listOf(9 * 60 to 10 * 60 + 30),
            plan.blocks.map { it.startMinute to it.endMinute }
        )
        assertEquals(90, plan.busyMinutes)
    }

    @Test
    fun `a deadline with no plan is drawn as a point with no length`() {
        val (due, day) = moment(4, 14, 0)

        viewModel.saveTask(title = "Client call", description = "", dueDate = due)

        awaitSaved()
        val block = runBlocking { fixture.calendarRepository.dayOnce(day) }.blocks.single()
        assertEquals(14 * 60, block.startMinute)
        assertNull(
            "the app knows when the call is due and nothing about how long it takes",
            block.endMinute
        )
        assertEquals(0, runBlocking { fixture.calendarRepository.dayOnce(day) }.busyMinutes)
    }

    @Test
    fun `an estimate the user typed becomes the length of the block`() {
        val (due, day) = moment(5, 11, 0)

        viewModel.saveTask(title = "Write chapter", description = "", dueDate = due, estimatedMinutes = 45)

        awaitSaved()
        val plan = runBlocking { fixture.calendarRepository.dayOnce(day) }
        assertEquals(45, plan.busyMinutes)
        assertEquals(11 * 60 + 45, plan.blocks.single().endMinute)
        assertEquals(45, stored("Write chapter").estimatedMinutes)
    }

    @Test
    fun `an end before the start is refused and the stored plan is left alone`() {
        val (start, _) = moment(6, 9, 0)
        val (end, _) = moment(6, 10, 0)
        viewModel.saveTask(title = "Review", description = "", dueDate = end, startTime = start, endTime = end)
        awaitSaved()
        awaitRow("Review")
        val taskId = rows().single { it.task.title == "Review" }.task.id
        viewModel.loadTask(taskId)
        awaitUntil("the plan to load back") { viewModel.currentTask.value?.startTime != null }

        val (badEnd, _) = moment(6, 8, 0)
        viewModel.saveTask(
            taskId = taskId,
            title = "Review",
            description = "",
            dueDate = end,
            startTime = start,
            endTime = badEnd
        )

        assertEquals(
            "the editor stays open instead of storing a block that runs backwards",
            TaskSaveState.PlanConflict,
            viewModel.taskSaveState.value
        )
        assertEquals(end.time, stored("Review").endTime)
        assertEquals(
            "a refused save must not leave a second copy of the task behind",
            1,
            rows().count { it.task.title == "Review" }
        )
    }

    @Test
    fun `an estimate with nothing to belong to is refused`() {
        viewModel.saveTask(title = "Timeless", description = "", estimatedMinutes = 30)

        assertEquals(
            "a length needs a moment: without a start or a deadline there is nothing to draw",
            TaskSaveState.PlanNeedsInstant,
            viewModel.taskSaveState.value
        )
        assertEquals(emptyList<String>(), rows().map { it.task.title })
    }

    @Test
    fun `clearing the plan in the editor really clears it`() {
        val (start, day) = moment(7, 9, 0)
        val (end, _) = moment(7, 17, 0)
        viewModel.saveTask(
            title = "Offsite",
            description = "",
            dueDate = end,
            startTime = start,
            endTime = end,
            estimatedMinutes = 480
        )
        awaitSaved()
        awaitRow("Offsite")
        val taskId = rows().single { it.task.title == "Offsite" }.task.id

        viewModel.loadTask(taskId)
        awaitUntil("the plan to load back") { viewModel.currentTask.value?.startTime != null }
        viewModel.saveTask(taskId = taskId, title = "Offsite", description = "", dueDate = end)

        awaitSaved()
        val cleared = stored("Offsite")
        assertNull(cleared.startTime)
        assertNull(cleared.endTime)
        assertNull(
            "and the estimate goes with them, so the day is not left holding a promised length",
            cleared.estimatedMinutes
        )
        assertEquals(
            listOf(17 * 60),
            runBlocking { fixture.calendarRepository.dayOnce(day) }.blocks.map { it.startMinute }
        )
    }

    @Test
    fun `the editor reads the plan back so editing a task cannot lose it`() {
        val (start, _) = moment(8, 13, 30)
        val (end, _) = moment(8, 15, 0)
        viewModel.saveTask(title = "Dentist", description = "", dueDate = end, startTime = start, endTime = end)
        awaitSaved()
        awaitRow("Dentist")
        val taskId = rows().single { it.task.title == "Dentist" }.task.id

        viewModel.clearCurrentTask()
        viewModel.loadTask(taskId)

        awaitUntil("the plan to load back") { viewModel.currentTask.value?.startTime != null }
        val reloaded = viewModel.currentTask.value!!
        assertEquals(start.time, reloaded.startTime)
        assertEquals(end.time, reloaded.endTime)
        assertNull(reloaded.estimatedMinutes)
    }

    @Test
    fun `moving the deadline of a planned task leaves its own times where the user put them`() {
        val (start, day) = moment(9, 9, 0)
        val (end, _) = moment(9, 10, 0)
        viewModel.saveTask(title = "Standup", description = "", dueDate = end, startTime = start, endTime = end)
        awaitSaved()
        awaitRow("Standup")
        val taskId = rows().single { it.task.title == "Standup" }.task.id
        viewModel.loadTask(taskId)
        awaitUntil("the plan to load back") { viewModel.currentTask.value?.startTime != null }

        val (laterDue, _) = moment(9, 12, 0)
        viewModel.saveTask(
            taskId = taskId,
            title = "Standup",
            description = "",
            dueDate = laterDue,
            startTime = start,
            endTime = end
        )

        awaitSaved()
        val moved = stored("Standup")
        assertEquals(laterDue.time, moved.dueDate)
        assertEquals(start.time, moved.startTime)
        assertEquals(
            "the block is still the one the user described on that day",
            listOf(9 * 60 to 10 * 60),
            runBlocking { fixture.calendarRepository.dayOnce(day) }.blocks.map { it.startMinute to it.endMinute }
        )
    }

}
