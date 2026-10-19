package com.taskplanner.data.calendar

import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the calendar decides to show, tested as pure values on a fixed zone and a fixed clock.
 *
 * A calendar is easy to get subtly wrong in ways that only ever surface on the user's own device:
 * a late-evening deadline filed under the following day, an appointment the day view draws at 00:00
 * because the user never typed a time, a task given a length nobody told it, two meetings that
 * overlap silently. All of that is decided here, in one object, against instants the test writes
 * out in full.
 */
class CalendarAggregatorTest {

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        val MONDAY: LocalDate = LocalDate.of(2026, 3, 9)
        val TUESDAY: LocalDate = LocalDate.of(2026, 3, 10)
        const val MINUTE = 60_000L

        /** A Wednesday morning in Berlin: nine o'clock, before the summer-time jump. */
        val NINE_AM: Long = LocalDateTime.parse("2026-03-11T09:00").atZone(BERLIN).toInstant().toEpochMilli()

        /** The clock springs forward on this date in Berlin: 02:00 becomes 03:00. */
        val SPRING_FORWARD: LocalDate = LocalDate.of(2026, 3, 29)
    }

    private var nextId = 1L

    private fun at(wallClock: String, zone: ZoneId = BERLIN): Long =
        LocalDateTime.parse(wallClock).atZone(zone).toInstant().toEpochMilli()

    private fun task(
        title: String,
        due: String? = null,
        start: String? = null,
        end: String? = null,
        estimate: Int? = null,
        categoryId: Long? = null,
        status: TaskStatus = TaskStatus.TODO,
        zone: ZoneId = BERLIN
    ): Task = Task(
        id = nextId++,
        uuid = "task-$title",
        title = title,
        description = "",
        dueDate = due?.let { at(it, zone) },
        startTime = start?.let { at(it, zone) },
        endTime = end?.let { at(it, zone) },
        estimatedMinutes = estimate,
        categoryId = categoryId,
        status = status,
        userId = 1L
    )

    private fun category(id: Long, name: String, color: Int) =
        Category(id = id, uuid = "cat-$id", name = name, color = color, icon = "ic_tag", userId = 1L)

    private fun planOf(vararg tasks: Task, date: LocalDate = TUESDAY) =
        CalendarAggregator.dayPlan(date, tasks.toList(), NINE_AM, BERLIN)

    // ---- which day a task belongs to -------------------------------------

    @Test
    fun `a deadline late in the evening belongs to the day it is on, not the one it is close to`() {
        val task = task("Night train", due = "2026-03-09T23:30")
        val days = CalendarAggregator.daysOf(task, MONDAY, MONDAY.plusDays(6), BERLIN)
        assertEquals(listOf(MONDAY), days)
    }

    @Test
    fun `a task is filed under its start when the user gave one`() {
        val task = task("Prep", start = "2026-03-09T08:00", end = "2026-03-09T09:30", due = "2026-03-10T18:00")
        assertEquals(
            "the meeting is on Monday even though its deadline is Tuesday",
            listOf(MONDAY),
            CalendarAggregator.daysOf(task, MONDAY, TUESDAY, BERLIN)
        )
    }

    @Test
    fun `a block over midnight is drawn on both days it covers`() {
        val task = task("Overtime", start = "2026-03-09T23:00", end = "2026-03-10T01:00")
        assertEquals(
            listOf(MONDAY, TUESDAY),
            CalendarAggregator.daysOf(task, MONDAY, TUESDAY, BERLIN)
        )
    }

    @Test
    fun `a task with no instant anywhere belongs to no day`() {
        val task = task("Someday")
        assertEquals(emptyList<LocalDate>(), CalendarAggregator.daysOf(task, MONDAY, TUESDAY, BERLIN))
    }

    // ---- the day column ---------------------------------------------------

    @Test
    fun `a planned block sits at its own minutes`() {
        val plan = planOf(task("Standup", start = "2026-03-10T09:00", end = "2026-03-10T09:15"))
        val block = plan.blocks.single()
        assertEquals(9 * 60, block.startMinute)
        assertEquals(9 * 60 + 15, block.endMinute)
        assertEquals(15, plan.busyMinutes)
    }

    @Test
    fun `an estimate is the length when no end was given`() {
        val plan = planOf(task("Report", start = "2026-03-10T14:00", estimate = 90))
        val block = plan.blocks.single()
        assertEquals(14 * 60, block.startMinute)
        assertEquals(15 * 60 + 30, block.endMinute)
        assertEquals(90, plan.busyMinutes)
    }

    @Test
    fun `a deadline with a time and no length is a marker, not a invented hour`() {
        val plan = planOf(task("Call the bank", due = "2026-03-10T11:20"))
        val block = plan.blocks.single()
        assertEquals(11 * 60 + 20, block.startMinute)
        assertNull(
            "nothing in this app decides how long a call takes",
            block.endMinute
        )
        assertEquals(0, plan.busyMinutes)
    }

    @Test
    fun `a task due on a day with no time is listed as no time given instead of at midnight`() {
        val plan = planOf(task("Pay rent", due = "2026-03-10T00:00"))
        assertEquals(emptyList<CalendarAggregator.DayBlock>(), plan.blocks)
        assertEquals(listOf("Pay rent"), plan.noTimeGiven.map { it.title })
    }

    @Test
    fun `an end before the start does not take the task off the day it belongs to`() {
        val plan = planOf(task("Backwards", due = "2026-03-10T15:00", end = "2026-03-10T14:00"))
        val block = plan.blocks.single()
        assertEquals(15 * 60, block.startMinute)
        assertNull(
            "the impossible end is read as no length rather than as a block running backwards",
            block.endMinute
        )
        assertEquals(0, plan.busyMinutes)
    }

    @Test
    fun `an appointment that really is at midnight is drawn on the column`() {
        val plan = planOf(task("Night shift", start = "2026-03-10T00:00", estimate = 480))
        val block = plan.blocks.single()
        assertEquals(
            "a start the user entered is never treated as a missing time",
            0,
            block.startMinute
        )
        assertEquals(8 * 60, block.endMinute)
    }

    @Test
    fun `a block that began yesterday is clamped to the top of today and one that runs past midnight to the bottom`() {
        val overnight = task("Overtime", start = "2026-03-09T23:00", end = "2026-03-10T01:00")
        val mondayPlan = CalendarAggregator.dayPlan(MONDAY, listOf(overnight), NINE_AM, BERLIN)
        val tuesdayPlan = CalendarAggregator.dayPlan(TUESDAY, listOf(overnight), NINE_AM, BERLIN)
        val onMonday = mondayPlan.blocks.single()
        val onTuesday = tuesdayPlan.blocks.single()

        assertEquals(listOf(23 * 60, CalendarAggregator.MINUTES_PER_DAY), listOf(onMonday.startMinute, onMonday.endMinute))
        assertEquals(listOf(0, 60), listOf(onTuesday.startMinute, onTuesday.endMinute))
        assertEquals(
            "the two clamped pieces add up to the real length, so nothing is counted twice",
            120,
            mondayPlan.busyMinutes + tuesdayPlan.busyMinutes
        )
    }

    @Test
    fun `on the day the clock jumps forward a nine oclock deadline is still at minute five hundred and forty`() {
        val skipped = BERLIN.rules.getTransition(LocalDateTime.of(SPRING_FORWARD, LocalTime.of(2, 0)))
        assertTrue(
            "the fixture stopped being a spring-forward day",
            skipped != null && skipped.isGap
        )
        val plan = planOf(
            task("Flight", start = "2026-03-29T09:00", end = "2026-03-29T10:00"),
            date = SPRING_FORWARD
        )
        val block = plan.blocks.single()
        assertEquals(
            "the column follows the wall clock the user reads, not the twenty-three hours that passed",
            9 * 60,
            block.startMinute
        )
        assertEquals(10 * 60, block.endMinute)
    }

    // ---- overlaps ---------------------------------------------------------

    @Test
    fun `two blocks that share time are reported as one collision`() {
        val plan = planOf(
            task("Meeting", start = "2026-03-10T14:00", end = "2026-03-10T15:00"),
            task("Call", start = "2026-03-10T14:30", end = "2026-03-10T15:30")
        )
        assertEquals(1, plan.overlapCount)
        assertEquals(
            listOf(listOf("Call"), listOf("Meeting")),
            plan.blocks.map { block -> block.overlappingTaskIds.map { id -> plan.blocks.first { it.task.id == id }.task.title } }
        )
    }

    @Test
    fun `blocks that only touch at a boundary are not an overlap`() {
        val plan = planOf(
            task("First", start = "2026-03-10T14:00", end = "2026-03-10T15:00"),
            task("Second", start = "2026-03-10T15:00", end = "2026-03-10T16:00")
        )
        assertEquals(
            "back-to-back is what a planned day looks like, not what a clash looks like",
            0,
            plan.overlapCount
        )
        assertTrue(plan.blocks.all { it.overlappingTaskIds.isEmpty() })
    }

    @Test
    fun `a deadline that falls inside a meeting is a collision even though its length is unknown`() {
        val plan = planOf(
            task("Workshop", start = "2026-03-10T14:00", end = "2026-03-10T16:00"),
            task("Submit form", due = "2026-03-10T15:00")
        )
        assertEquals(1, plan.overlapCount)
        assertEquals(
            listOf(listOf("Submit form"), listOf("Workshop")),
            plan.blocks.map { block -> block.overlappingTaskIds.map { id -> plan.blocks.first { it.task.id == id }.task.title } }
        )
    }

    @Test
    fun `two things due at the same minute collide with each other`() {
        val plan = planOf(
            task("Dentist", due = "2026-03-10T09:00"),
            task("Client call", due = "2026-03-10T09:00")
        )
        assertEquals(1, plan.overlapCount)
    }

    @Test
    fun `three overlapping blocks are reported on each of them`() {
        val plan = planOf(
            task("a", start = "2026-03-10T10:00", end = "2026-03-10T12:00"),
            task("b", start = "2026-03-10T11:00", end = "2026-03-10T13:00"),
            task("c", start = "2026-03-10T11:30", estimate = 30)
        )
        assertEquals(3, plan.overlapCount)
        assertEquals(listOf(2, 2, 2), plan.blocks.map { it.overlappingTaskIds.size })
    }

    // ---- month and week cells ---------------------------------------------

    @Test
    fun `a cell counts its tasks and colours them by category`() {
        val tasks = listOf(
            task("Groceries", due = "2026-03-10T18:00", categoryId = 11),
            task("Cook dinner", start = "2026-03-10T19:00", estimate = 40, categoryId = 11),
            task("Standup", start = "2026-03-10T09:15", end = "2026-03-10T09:30", categoryId = 12),
            task("Unfiled", due = "2026-03-10T12:00")
        )
        val cells = CalendarAggregator.cells(
            grid = listOf(MONDAY, TUESDAY),
            displayedMonth = YearMonth.of(2026, 3),
            tasks = tasks,
            categories = listOf(category(11, "Home", -1), category(12, "Work", -2)),
            today = TUESDAY,
            now = at("2026-03-10T13:00"),
            zone = BERLIN
        )

        val tuesday = cells.first { it.date == TUESDAY }
        assertEquals(4, tuesday.tasks.size)
        assertEquals(
            listOf("Home" to 2, "Work" to 1),
            tuesday.categories.map { it.name to it.count }
        )
        assertEquals(1, tuesday.unassignedCount)
        assertEquals(
            "only the lengths the user gave count towards the day",
            15 + 40,
            tuesday.busyMinutes
        )
        assertEquals(
            "only the unfiled task is past due: being over is about a deadline, and the standup " +
                "has a start and no deadline at all",
            1,
            tuesday.overdueCount
        )
        assertTrue(
            "a day the window covers but nothing lands on stays empty",
            cells.first { it.date == MONDAY }.tasks.isEmpty()
        )
    }

    @Test
    fun `the cell marks today and neighbour-month days`() {
        val cells = CalendarAggregator.cells(
            grid = listOf(MONDAY, TUESDAY),
            displayedMonth = YearMonth.of(2026, 2),
            tasks = emptyList(),
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        )
        assertEquals(listOf(false, false), cells.map { it.inDisplayedMonth })
        assertEquals(listOf(false, true), cells.map { it.isToday })

        val inWeek = CalendarAggregator.cells(
            grid = listOf(MONDAY, TUESDAY),
            displayedMonth = null,
            tasks = emptyList(),
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        )
        assertTrue("week mode has no month to be outside of", inWeek.all { it.inDisplayedMonth })
    }

    @Test
    fun `overdue counts ignore a task the user already finished`() {
        val cells = CalendarAggregator.cells(
            grid = listOf(TUESDAY),
            displayedMonth = YearMonth.of(2026, 3),
            tasks = listOf(
                task("Missed", due = "2026-03-10T08:00"),
                task("Done late", due = "2026-03-10T08:00", status = TaskStatus.COMPLETED)
            ),
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        )
        assertEquals(1, cells.single().overdueCount)
    }

    @Test
    fun `a day is announced full once the planned minutes pass the working-day heuristic`() {
        val busy = CalendarAggregator.cells(
            grid = listOf(TUESDAY),
            displayedMonth = YearMonth.of(2026, 3),
            tasks = listOf(task("Long one", start = "2026-03-10T08:00", end = "2026-03-10T14:30")),
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        ).single()
        assertTrue(busy.overloaded)
        assertEquals(6 * 60 + 30, busy.busyMinutes)

        val calm = CalendarAggregator.cells(
            grid = listOf(TUESDAY),
            displayedMonth = YearMonth.of(2026, 3),
            tasks = listOf(task("Short one", start = "2026-03-10T08:00", end = "2026-03-10T09:00")),
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        ).single()
        assertFalse(calm.overloaded)
    }

    @Test
    fun `a day with more tasks than the heuristic allows is full even when none has a length`() {
        val tasks = (1..CalendarAggregator.TASK_COUNT_OVERLOAD).map {
            task("task $it", due = "2026-03-10T10:00")
        }
        val cell = CalendarAggregator.cells(
            grid = listOf(TUESDAY),
            displayedMonth = YearMonth.of(2026, 3),
            tasks = tasks,
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        ).single()
        assertTrue(cell.overloaded)
        assertEquals(
            "being full is not the same as being busy: not one minute was promised",
            0,
            cell.busyMinutes
        )
    }

    @Test
    fun `a task the grid does not show is left out instead of drawn on the nearest cell`() {
        val cells = CalendarAggregator.cells(
            grid = listOf(MONDAY, TUESDAY),
            displayedMonth = YearMonth.of(2026, 3),
            tasks = listOf(task("Next week", due = "2026-03-20T10:00")),
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        )
        assertEquals(0, cells.sumOf { it.tasks.size })
    }

    @Test
    fun `a category that was deleted still shows its tasks without a colour`() {
        val cells = CalendarAggregator.cells(
            grid = listOf(TUESDAY),
            displayedMonth = YearMonth.of(2026, 3),
            tasks = listOf(task("Orphan", due = "2026-03-10T10:00", categoryId = 99)),
            categories = emptyList(),
            today = TUESDAY,
            now = NINE_AM,
            zone = BERLIN
        ).single()
        assertEquals(1, cells.tasks.size)
        assertEquals(
            "the row is shown, only its chip has nothing to point at",
            emptyList<CalendarAggregator.CategoryChip>(),
            cells.categories
        )
        assertEquals(0, cells.unassignedCount)
    }

    @Test
    fun `an empty grid asks for no cells`() {
        assertEquals(
            emptyList<CalendarAggregator.DayCell>(),
            CalendarAggregator.cells(emptyList(), null, listOf(task("a")), emptyList(), TUESDAY, NINE_AM, BERLIN)
        )
    }

    @Test
    fun `a day nothing was planned for has a column of nothing rather than no answer`() {
        val plan = planOf()
        assertEquals(
            CalendarAggregator.DayPlan.empty(TUESDAY),
            plan
        )
        assertTrue(plan.blocks.isEmpty())
        assertFalse(plan.overloaded)
    }
}
