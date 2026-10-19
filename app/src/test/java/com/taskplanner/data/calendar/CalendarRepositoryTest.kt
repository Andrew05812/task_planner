package com.taskplanner.data.calendar

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The calendar against real storage: the bounded window query it reads through, the aggregation of
 * what that query returns, and what a drag-and-drop actually writes.
 *
 * The device zone is pinned to New York because these tests are about instants: the window bounds,
 * which local day a deadline falls on, and the one case a calendar cannot fake — moving an
 * appointment across the day the clock springs forward.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarRepositoryTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/New_York")
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE

        /** The week of the spring-forward transition: Sunday 2026-03-08 is 23 hours in New York. */
        val SPRING_FORWARD: LocalDate = LocalDate.of(2026, 3, 8)
    }

    private lateinit var defaultZone: TimeZone
    private lateinit var context: Context
    private lateinit var fixture: AppFixture
    private lateinit var alarms: AlarmManager
    private var userId: Long = 0

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        alarms = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        userId = runBlocking { fixture.signIn(email = "calendar@example.com").id }
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(defaultZone)
    }

    private fun at(wallClock: String): Long =
        LocalDateTime.parse(wallClock).atZone(ZONE).toInstant().toEpochMilli()

    private fun insert(
        title: String,
        due: String? = null,
        start: String? = null,
        end: String? = null,
        estimate: Int? = null,
        categoryId: Long? = null,
        asProfile: Long = userId,
        uuid: String = "uuid-$title"
    ): Long = runBlocking {
        fixture.taskDao.insertTask(
            Task(
                uuid = uuid,
                title = title,
                description = "",
                dueDate = due?.let { at(it) },
                startTime = start?.let { at(it) },
                endTime = end?.let { at(it) },
                estimatedMinutes = estimate,
                categoryId = categoryId,
                userId = asProfile
            )
        )
    }

    private val weekOfTuesday = (0 until 7).map { LocalDate.of(2026, 3, 2).plusDays(it.toLong()) }

    private fun cellsOnce(grid: List<LocalDate> = weekOfTuesday) =
        runBlocking { fixture.calendarRepository.cellsOnce(grid, null) }

    private fun dayOnce(date: LocalDate) =
        runBlocking { fixture.calendarRepository.dayOnce(date) }

    private fun stored(title: String): Task = runBlocking {
        fixture.taskDao.getAllRowsOnce(userId).first { it.title == title }
    }

    // ---- the window query -------------------------------------------------

    @Test
    fun `a window reads exactly the days the grid shows`() {
        insert("Monday standup", start = "2026-03-02T09:00", end = "2026-03-02T09:15")
        insert("Friday report", due = "2026-03-06T17:00")
        insert("Next week", due = "2026-03-09T10:00")
        insert("Last week", due = "2026-02-27T10:00")

        val titles = cellsOnce().flatMap { it.tasks.map { task -> task.title } }
        assertEquals(
            "the grid covers the 2nd through the 8th, and nothing beyond it is loaded",
            listOf("Friday report", "Monday standup"),
            titles.sorted()
        )
    }

    @Test
    fun `the window boundary belongs to the day that starts, not the one that ends`() {
        insert("Midnight at the end", due = "2026-03-02T00:00")
        insert("Midnight next door", due = "2026-03-01T00:00")

        val inGrid = cellsOnce().flatMap { it.tasks.map { task -> task.title } }
        assertEquals(
            listOf("Midnight at the end"),
            inGrid
        )
        assertEquals(
            "the earlier one has its own window instead",
            listOf("Midnight next door"),
            cellsOnce(listOf(LocalDate.of(2026, 3, 1))).flatMap { it.tasks.map { task -> task.title } }
        )
    }

    @Test
    fun `a block that starts before the window and ends after it is not lost from either screen`() {
        insert("Weekend shift", start = "2026-03-01T20:00", end = "2026-03-09T04:00")

        val days = cellsOnce().filter { it.tasks.isNotEmpty() }.map { it.date }
        assertTrue(
            "a block spanning the grid shows on the days it covers",
            days.containsAll(listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 8)))
        )
    }

    @Test
    fun `a task with no instant anywhere is not placed on a day`() {
        insert("Some day maybe")
        assertEquals(emptyList<String>(), cellsOnce().flatMap { it.tasks.map { task -> task.title } })
    }

    @Test
    fun `an archived or deleted task leaves the calendar`() {
        val id = insert("Finished", due = "2026-03-03T10:00")
        runBlocking { fixture.taskRepository.completeAndArchive(id) }
        insert("Thrown away", due = "2026-03-03T11:00")
        runBlocking { fixture.taskDao.softDeleteTasks(listOf(stored("Thrown away").id)) }

        assertEquals(emptyList<String>(), cellsOnce().flatMap { it.tasks.map { task -> task.title } })
    }

    @Test
    fun `one profile's calendar never shows another profile's plans`() {
        insert("Mine", due = "2026-03-03T10:00")
        val other = runBlocking { fixture.signIn(email = "other@example.com").id }
        insert("Theirs", due = "2026-03-03T11:00", asProfile = other)

        val backToMine = runBlocking {
            fixture.switchTo(fixture.userDao.getUserById(userId)!!)
            cellsOnce().flatMap { it.tasks.map { task -> task.title } }
        }
        assertEquals(listOf("Mine"), backToMine)
    }

    @Test
    fun `the cells carry the day's categories with their colours and counts`() {
        val home = runBlocking { fixture.addCategory(userId, "Home", color = -12345) }
        insert("Hoover", due = "2026-03-03T10:00", categoryId = home)
        insert("Bin bags", due = "2026-03-03T18:00", categoryId = home)
        insert("No category", due = "2026-03-03T19:00")

        val cell = cellsOnce().single { it.date == LocalDate.of(2026, 3, 3) }
        assertEquals(listOf("Home" to 2), cell.categories.map { it.name to it.count })
        assertEquals(-12345, cell.categories.single().color)
        assertEquals(1, cell.unassignedCount)
    }

    // ---- drag and drop ----------------------------------------------------

    @Test
    fun `dragging an appointment across the spring-forward keeps its clock time`() {
        val id = insert(
            "Night train",
            due = "2026-03-05T21:30",
            start = "2026-03-05T21:30",
            end = "2026-03-05T22:15"
        )
        val target = LocalDate.of(2026, 3, 10)

        assertTrue(runBlocking { fixture.calendarRepository.moveToDay(id, target) })

        val moved = stored("Night train")
        assertEquals(
            "the train leaves at 21:30 on the new day, not at 20:30 because a day of milliseconds is not a day of clock",
            target,
            CalendarMath.toLocalDate(moved.dueDate!!, ZONE)
        )
        assertEquals(21 * 60 + 30, CalendarMath.minutesOfDay(moved.dueDate!!, ZONE))
        assertEquals(21 * 60 + 30, CalendarMath.minutesOfDay(moved.startTime!!, ZONE))
        assertEquals(
            "the meeting is still fifty minutes long, which is what its own end time says",
            22 * 60 + 15,
            CalendarMath.minutesOfDay(moved.endTime!!, ZONE)
        )
        assertNotEquals(
            "adding four whole days would have landed an hour off",
            at("2026-03-05T21:30") + 5 * DAY,
            moved.dueDate
        )
    }

    @Test
    fun `dragging a task that only has a deadline leaves it a task that only has a deadline`() {
        val id = insert("Pay rent", due = "2026-03-03T00:00")

        assertTrue(runBlocking { fixture.calendarRepository.moveToDay(id, LocalDate.of(2026, 3, 7)) })

        val moved = stored("Pay rent")
        assertNull(
            "a day was moved, so no start time, no end time and no length may appear out of nowhere",
            moved.startTime
        )
        assertNull(moved.endTime)
        assertNull(moved.estimatedMinutes)
        assertEquals(
            LocalDate.of(2026, 3, 7),
            CalendarMath.toLocalDate(moved.dueDate!!, ZONE)
        )
        assertEquals(
            "and it is still a day without a time: it sits at that day's own first instant",
            CalendarMath.startOfDay(LocalDate.of(2026, 3, 7), ZONE),
            moved.dueDate
        )
    }

    @Test
    fun `dragging takes the lead times to the new day and puts their alarms there`() {
        val id = insert("Renew passport", due = inDays(4, "10:00"))
        val before = stored("Renew passport")
        runBlocking { fixture.reminderRepository.applyReminders(before, listOf(60)) }

        assertTrue(runBlocking { fixture.calendarRepository.moveToDay(id, LocalDate.now(ZONE).plusDays(9)) })

        val after = stored("Renew passport")
        val reminder = runBlocking { fixture.reminderDao.getLiveForTask(after.uuid).single() }
        assertEquals(
            "the same hour-before chip, measured from the day the task is now on",
            after.dueDate!! - 60 * MINUTE,
            reminder.triggerAt
        )
        assertEquals(
            listOf(reminder.triggerAt),
            shadowOf(alarms).scheduledAlarms.map { it.triggerAtMs }
        )
    }

    @Test
    fun `a drag updates the row it moved instead of planting a second task`() {
        val id = insert("Doctor", due = "2026-03-03T14:00")

        runBlocking { fixture.calendarRepository.moveToDay(id, LocalDate.of(2026, 3, 5)) }

        val rows = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }
        assertEquals(1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals(
            "and the row is marked for the cloud, with a new update time",
            SyncState.PENDING,
            rows.single().syncState
        )
        assertEquals("Doctor", rows.single().title)
        assertEquals("uuid-Doctor", rows.single().uuid)
    }

    @Test
    fun `a drag onto a row of another profile is refused and changes nothing`() {
        val other = runBlocking { fixture.signIn(email = "second@example.com").id }
        val foreign = insert("Theirs", due = "2026-03-03T10:00", asProfile = other)
        runBlocking { fixture.switchTo(fixture.userDao.getUserById(userId)!!) }

        assertFalse(
            "a row of the profile that is not active is nobody else's to move",
            runBlocking { fixture.calendarRepository.moveToDay(foreign, LocalDate.of(2026, 3, 20)) }
        )
        assertEquals(
            "the other profile's task stays exactly where it was",
            LocalDate.of(2026, 3, 3),
            CalendarMath.toLocalDate(runBlocking { fixture.taskDao.getTaskById(foreign)!! }.dueDate!!, ZONE)
        )
    }

    @Test
    fun `a task with no time anywhere cannot be dragged`() {
        val id = insert("Someday")
        assertFalse(runBlocking { fixture.calendarRepository.moveToDay(id, LocalDate.of(2026, 3, 20)) })
        assertNull(stored("Someday").dueDate)
    }

    // ---- the editor's plan fields -----------------------------------------

    @Test
    fun `saving a start an end and an estimate stores all three`() {
        val id = insert("Write chapter", due = "2026-03-03T09:00")

        assertTrue(
            runBlocking {
                fixture.calendarRepository.setPlannedTimes(
                    id, at("2026-03-03T09:00"), at("2026-03-03T11:30"), 120
                )
            }
        )

        val task = stored("Write chapter")
        val plan = dayOnce(LocalDate.of(2026, 3, 3))
        assertEquals(
            "the column draws the block the user described",
            listOf(9 * 60 to 11 * 60 + 30),
            plan.blocks.map { it.startMinute to it.endMinute }
        )
        assertEquals(150, plan.busyMinutes)
        assertEquals(120, task.estimatedMinutes)
        assertEquals(
            "typing a block never moves the deadline the task already had",
            at("2026-03-03T09:00"),
            task.dueDate
        )
    }

    @Test
    fun `an end before the start is refused instead of being quietly swapped`() {
        val id = insert("Backwards", due = "2026-03-03T09:00")

        assertFalse(
            runBlocking {
                fixture.calendarRepository.setPlannedTimes(
                    id, at("2026-03-03T11:00"), at("2026-03-03T10:00"), null
                )
            }
        )

        val task = stored("Backwards")
        assertNull(task.startTime)
        assertNull(task.endTime)
    }

    @Test
    fun `clearing the plan times puts a task back to a bare deadline`() {
        val id = insert("Loose", due = "2026-03-03T15:00")
        runBlocking {
            fixture.calendarRepository.setPlannedTimes(id, at("2026-03-03T15:00"), null, 30)
        }

        runBlocking {
            fixture.calendarRepository.setPlannedTimes(id, null, null, null)
        }

        val task = stored("Loose")
        assertNull(task.startTime)
        assertNull(task.endTime)
        assertNull(task.estimatedMinutes)
        assertEquals(
            "the deadline the user set is nobody else's to clear",
            at("2026-03-03T15:00"),
            task.dueDate
        )
        assertEquals(listOf(task.id), dayOnce(LocalDate.of(2026, 3, 3)).blocks.map { it.task.id })
    }

    @Test
    fun `an estimate of zero is not a length`() {
        val id = insert("No length", due = "2026-03-03T15:00")

        runBlocking {
            fixture.calendarRepository.setPlannedTimes(id, at("2026-03-03T15:00"), null, 0)
        }

        assertNull(stored("No length").estimatedMinutes)
        assertNull(
            "so the day view shows a marker and promises no minutes",
            dayOnce(LocalDate.of(2026, 3, 3)).blocks.single().endMinute
        )
    }

    // ---- live refresh -----------------------------------------------------

    @Test
    fun `the grid re-emits after a drag instead of the screen reloading the month`() {
        val monday = LocalDate.of(2026, 3, 2)
        val thursday = LocalDate.of(2026, 3, 5)
        val id = insert("Standup", due = "${thursday}T09:00")
        val grid = weekOfTuesday
        val flow = fixture.calendarRepository.observeCells(grid, YearMonth.of(2026, 3))

        assertEquals(
            listOf(thursday.toString()),
            runBlocking { flow.first() }.filter { it.tasks.isNotEmpty() }.map { it.date.toString() }
        )

        runBlocking { fixture.calendarRepository.moveToDay(id, monday) }

        val after = runBlocking { flow.first() }
        assertEquals(
            listOf(monday.toString()),
            after.filter { it.tasks.isNotEmpty() }.map { it.date.toString() }
        )
    }

    @Test
    fun `the day column re-emits when a task is dragged into it`() {
        val monday = LocalDate.of(2026, 3, 2)
        val id = insert("Shift", due = "2026-03-05T20:00")
        val flow = fixture.calendarRepository.observeDay(monday)

        assertTrue(runBlocking { flow.first() }.blocks.isEmpty())

        runBlocking { fixture.calendarRepository.moveToDay(id, monday) }

        val plan = runBlocking { flow.first() }
        assertEquals(listOf("Shift"), plan.blocks.map { it.task.title })
        assertEquals(
            "the evening appointment is where the user left it",
            20 * 60,
            plan.blocks.single().startMinute
        )
    }

    @Test
    fun `a signed-out calendar shows no task of anybody's`() {
        insert("Belonging to someone", due = "2026-03-03T10:00")
        runBlocking { fixture.sessionRepository.endSession() }

        val flow = fixture.calendarRepository.observeCells(weekOfTuesday, YearMonth.of(2026, 3))
        assertEquals(
            "the grid is still drawn, and there is nothing in it",
            0,
            runBlocking { flow.first() }.sumOf { it.tasks.size }
        )
        assertEquals(0, cellsOnce().sumOf { it.tasks.size })
        assertTrue(runBlocking { fixture.calendarRepository.dayOnce(LocalDate.of(2026, 3, 3)).blocks.isEmpty() })
    }

    private fun inDays(days: Int, wallClock: String): String =
        LocalDate.now(ZONE).plusDays(days.toLong()).toString() + "T" + wallClock

}
