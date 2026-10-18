package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.testing.AppFixture
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
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
 * Recurring tasks as the task screen drives them: what saving the repeat section stores, what
 * finishing or deleting an instance does to the series, and — the part a user judges most harshly —
 * whether "only this task" really touches one task while "this and following" really moves the
 * series. The series schedule and the date on the card are separate columns on purpose, and these
 * tests are where that separation is pinned down.
 */
@RunWith(RobolectricTestRunner::class)
class TasksRecurrenceTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: TasksViewModel
    private var userId: Long = 0
    private lateinit var defaultZone: TimeZone

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // The series computes wall-clock dates in its own zone, so the device zone is pinned to make
        // the expected instants readable instead of depending on the machine running the build.
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        // viewModelScope needs Dispatchers.Main; an inline executor keeps every collector on the
        // test thread instead of waiting for a looper that nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "repeat@example.com").id }
        viewModel = TasksViewModel(
            fixture.taskRepository,
            fixture.categoryRepository,
            fixture.groupRepository,
            fixture.recurrenceRepository,
            fixture.reminderRepository,
            fixture.sessionRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        TimeZone.setDefault(defaultZone)
        fixture.close()
    }

    private fun instant(iso: String): Long = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    private fun dayOf(millis: Long?): String = millis?.let {
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.of("Europe/Berlin")).toLocalDate().toString()
    } ?: "none"

    /** Saves through the editor and returns the row that was written. */
    private fun save(
        title: String,
        dueIso: String? = "2026-01-05T09:00:00+01:00",
        repeat: RepeatPlan = RepeatPlan(),
        taskId: Long = 0
    ): Task {
        viewModel.saveTask(
            taskId = taskId,
            title = title,
            description = "",
            dueDate = dueIso?.let { Date(instant(it)) },
            repeat = repeat
        )
        awaitUntil("the save to finish") { viewModel.taskSaveState.value !is TaskSaveState.Loading }
        return runBlocking { fixture.taskDao.getTaskById(taskId) }
            ?: runBlocking { fixture.taskDao.getActiveTasksOnce(userId).first { it.title == title } }
    }

    private fun ruleOf(task: Task): RecurrenceRule? =
        runBlocking { task.seriesUuid?.let { fixture.recurrenceDao.findByUuid(it) } }

    private fun openTasks() = runBlocking { fixture.taskDao.getActiveTasksOnce(userId) }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun rows() = viewModel.state.value.items.filterIsInstance<TasksListItem.TaskRow>()

    private fun titles() = rows().map { it.task.title }

    @Test
    fun `saving with a repeat makes the task the first instance of a series`() {
        val task = save("Water the flowers", repeat = RepeatPlan(preset = RepeatPreset.DAILY))
        val rule = ruleOf(task)

        assertNotNull("the editor must leave a rule behind", rule)
        assertEquals(RepeatUnit.DAY, rule!!.unit)
        assertEquals(1, rule.interval)
        assertEquals("2026-01-05", dayOf(rule.cursorAt))
        assertEquals(rule.uuid, task.seriesUuid)
        assertEquals("the task on screen is the instance the series exposes", rule.cursorAt, task.occurrenceAt)

        awaitUntil("the list to mark the task as repeating") {
            rows().firstOrNull { it.task.title == "Water the flowers" }?.series != null
        }
        assertEquals(listOf("Water the flowers"), titles())
    }

    @Test
    fun `completing an instance gives the series exactly one next task`() {
        val task = save("Take pills", repeat = RepeatPlan(preset = RepeatPreset.DAILY))

        viewModel.completeTask(task)

        awaitUntil("the next instance to appear") { titles() == listOf("Take pills") }
        val next = openTasks().single()
        assertEquals("2026-01-06", dayOf(next.dueDate))
        assertEquals("2026-01-06", dayOf(next.occurrenceAt))
        assertEquals(task.seriesUuid, next.seriesUuid)
        assertEquals(TaskStatus.TODO, next.status)
        assertEquals(SyncState.PENDING, next.syncState)
        assertEquals(
            "the finished instance stays as history instead of being reused",
            1,
            runBlocking { fixture.taskDao.getAllRowsOnce(userId).count { it.archived } }
        )
        assertEquals("2026-01-06", dayOf(ruleOf(next)?.cursorAt))
    }

    @Test
    fun `moving one instance does not move the series`() {
        val task = save("Team standup", dueIso = "2026-01-05T09:00:00+01:00", repeat = RepeatPlan(preset = RepeatPreset.DAILY))
        viewModel.loadTask(task.id)
        awaitUntil("the series to load") { viewModel.seriesRule.value != null }

        val moved = save(
            "Team standup",
            dueIso = "2026-01-09T09:00:00+01:00",
            repeat = RepeatPlan(preset = RepeatPreset.DAILY, appliesToSeries = false),
            taskId = task.id
        )
        val rule = ruleOf(moved)!!

        assertEquals("2026-01-09", dayOf(moved.dueDate))
        assertEquals(
            "the row keeps the slot it was materialised for, so the series is untouched",
            "2026-01-05",
            dayOf(moved.occurrenceAt)
        )
        assertEquals("2026-01-05", dayOf(rule.cursorAt))
        assertEquals(RepeatUnit.DAY, rule.unit)
        assertEquals("only one task of the series may be open", listOf("Team standup"), titles())
    }

    @Test
    fun `moving this and following re-anchors the series on the new date`() {
        val task = save("Pay rent", dueIso = "2026-01-05T09:00:00+01:00", repeat = RepeatPlan(preset = RepeatPreset.MONTHLY))
        viewModel.loadTask(task.id)
        awaitUntil("the series to load") { viewModel.seriesRule.value != null }

        val moved = save(
            "Pay rent",
            dueIso = "2026-02-14T09:00:00+01:00",
            repeat = RepeatPlan(preset = RepeatPreset.EVERY_N_MONTHS, interval = 2),
            taskId = task.id
        )
        val rule = ruleOf(moved)!!

        assertEquals("2026-02-14", dayOf(rule.cursorAt))
        assertEquals("2026-02-14", dayOf(rule.anchorAt))
        assertEquals(RepeatUnit.MONTH, rule.unit)
        assertEquals(2, rule.interval)
        assertEquals("the instance follows the series to its new slot", "2026-02-14", dayOf(moved.occurrenceAt))
        assertEquals(listOf("Pay rent"), titles())
    }

    @Test
    fun `the chosen-weekdays preset stores the days and its own interval`() {
        val task = save(
            "Training",
            dueIso = "2026-01-05T18:00:00+01:00",
            repeat = RepeatPlan(preset = RepeatPreset.WEEKDAYS, interval = 2, weekDays = listOf(5, 1))
        )
        val rule = ruleOf(task)!!

        assertEquals(RepeatUnit.WEEK, rule.unit)
        assertEquals(2, rule.interval)
        assertEquals("Mon and Fri, in the order the calculator walks them", listOf(1, 5), rule.scheduledWeekDays)
    }

    @Test
    fun `does not repeat stops the series after the task on screen`() {
        val task = save("Walk the dog", repeat = RepeatPlan(preset = RepeatPreset.DAILY))
        viewModel.loadTask(task.id)
        awaitUntil("the series to load") { viewModel.seriesRule.value != null }

        val kept = save("Walk the dog", repeat = RepeatPlan(), taskId = task.id)
        val rule = ruleOf(kept)!!

        assertNotNull("the series is finished, not deleted", rule.endedAt)

        viewModel.completeTask(kept)

        awaitUntil("the archive to receive the task") { titles().isEmpty() }
        assertEquals("a finished series produces nothing more", emptyList<Task>(), openTasks())
    }

    @Test
    fun `a paused series produces nothing until it is resumed`() {
        val task = save("Read a page", repeat = RepeatPlan(preset = RepeatPreset.DAILY))
        viewModel.loadTask(task.id)
        awaitUntil("the series to load") { viewModel.seriesRule.value != null }

        var paused = false
        viewModel.setSeriesPaused(true) { paused = it }
        awaitUntil("the pause to land") { paused && viewModel.seriesRule.value?.paused == true }

        viewModel.completeTask(task)
        awaitUntil("the archive to receive the task") { titles().isEmpty() }
        assertEquals("a paused series owes the user no new task", emptyList<Task>(), openTasks())

        var resumed = false
        viewModel.setSeriesPaused(false) { resumed = it }
        awaitUntil("the resumed series to catch up") {
            resumed && viewModel.seriesRule.value?.paused == false && titles() == listOf("Read a page")
        }
        val next = openTasks().single()
        assertEquals("the series continues from the slot it stopped at", "2026-01-06", dayOf(next.dueDate))
    }

    @Test
    fun `deleting the series takes the tasks it still owes and keeps the archive`() {
        val first = save("Empty the dishwasher", repeat = RepeatPlan(preset = RepeatPreset.DAILY))
        viewModel.completeTask(first)
        awaitUntil("the next instance to appear") { titles() == listOf("Empty the dishwasher") }
        val second = openTasks().single()
        viewModel.loadTask(second.id)
        awaitUntil("the series to load") { viewModel.seriesRule.value != null }

        var deleted = false
        viewModel.deleteSeries { deleted = it }
        awaitUntil("the delete to land") { deleted }

        assertEquals("the series leaves no open task behind", emptyList<Task>(), openTasks())
        assertEquals(
            "completed history stays in the archive",
            1,
            runBlocking { fixture.taskDao.getArchivedTasks(userId).first().size }
        )
        val rule = runBlocking { fixture.recurrenceDao.findByUuid(first.seriesUuid!!) }!!
        assertTrue("the series is tombstoned so other devices stop producing it", rule.isDeleted)
        assertEquals(SyncState.DELETING, rule.syncState)
    }

    @Test
    fun `deleting the open instance from the list gives the series its next task`() {
        val task = save("Stretch", repeat = RepeatPlan(preset = RepeatPreset.EVERY_N_DAYS, interval = 3))

        viewModel.deleteSelectedFor(listOf(task.id))

        awaitUntil("the replacement instance to appear") { titles() == listOf("Stretch") }
        val next = openTasks().single()
        assertEquals("2026-01-08", dayOf(next.dueDate))
        assertEquals("2026-01-08", dayOf(ruleOf(next)?.cursorAt))
    }

    @Test
    fun `a schedule that lands on another instance is refused without losing the text`() {
        val task = save("Review pull request", repeat = RepeatPlan(preset = RepeatPreset.DAILY))
        val rule = ruleOf(task)!!
        val jan12 = instant("2026-01-12T09:00:00+01:00")
        val other = runBlocking {
            fixture.taskDao.insertTask(
                Task(
                    title = "Already planned",
                    description = "",
                    userId = userId,
                    dueDate = jan12,
                    seriesUuid = rule.uuid,
                    occurrenceAt = jan12
                )
            )
        }
        viewModel.loadTask(task.id)
        awaitUntil("the series to load") { viewModel.seriesRule.value != null }

        viewModel.saveTask(
            taskId = task.id,
            title = "Renamed while the schedule clashed",
            description = "",
            dueDate = Date(jan12),
            repeat = RepeatPlan(preset = RepeatPreset.WEEKLY)
        )
        awaitUntil("the conflict to be reported") {
            viewModel.taskSaveState.value is TaskSaveState.ScheduleConflict
        }

        val kept = runBlocking { fixture.taskDao.getTaskById(task.id) }!!
        assertEquals("what the user typed is never thrown away", "Renamed while the schedule clashed", kept.title)
        assertEquals("the series keeps the schedule it had", "2026-01-05", dayOf(ruleOf(kept)!!.cursorAt))
        assertNotNull(runBlocking { fixture.taskDao.getTaskById(other) })
    }

    @Test
    fun `a task without a repeat stays an ordinary task`() {
        val task = save("Book the flight")

        assertNull(task.seriesUuid)
        assertNull(ruleOf(task))
        assertEquals(listOf("Book the flight"), titles())

        viewModel.completeTask(task)

        awaitUntil("the plain task to leave the list") { titles().isEmpty() }
        assertEquals("no series means no follow-up task", emptyList<Task>(), openTasks())
    }
}
