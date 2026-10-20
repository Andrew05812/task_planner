package com.taskplanner.data.insight

import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the days ahead are asked for, folded out of the task rows that land on them.
 *
 * The reading an insight offers as "Thursday looks heavy" is only as honest as the arithmetic underneath it,
 * and §8 makes that arithmetic awkward on purpose: a task with a stated block has hours, a task with a
 * deadline and an estimate has *guessed* hours, and a task with a deadline alone has neither. Turning the
 * last of those into "an hour, probably" would be the app inventing a fact, so it is counted as a number of
 * tasks and the day is marked approximate wherever the minutes came from a guess.
 *
 * Days are epoch days, the unit the insights stamp their windows with, and the zone is passed in rather than
 * read from the device — the same rule every other day-bounded read in this app follows, so a block at
 * 23:30 is charged to the day the user saw it on.
 */
class WorkloadMathTest {

    private lateinit var zone: ZoneId

    private val monday = LocalDate.of(2026, 6, 1)
    private val tuesday = LocalDate.of(2026, 6, 2)
    private val wednesday = LocalDate.of(2026, 6, 3)
    private val thursday = LocalDate.of(2026, 6, 4)

    @Before
    fun setUp() {
        zone = ZoneId.of("Europe/Berlin")
    }

    private fun instant(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun block(start: LocalDate, end: LocalDate, endHour: Int = 12): Task =
        Task(
            id = 1L,
            title = "block",
            description = "",
            startTime = instant(start, 10),
            endTime = instant(end, endHour),
            userId = 1L
        )

    private fun deadline(day: LocalDate, estimate: Int? = null, priority: Priority = Priority.MEDIUM): Task =
        Task(
            id = 2L,
            title = "deadline",
            description = "",
            dueDate = instant(day, 17, 0),
            estimatedMinutes = estimate,
            priority = priority,
            userId = 1L
        )

    private fun loadOf(vararg tasks: Task): List<DayLoad> =
        WorkloadMath.fold(
            tasks.toList(),
            fromDay = monday.toEpochDay(),
            toDay = thursday.toEpochDay(),
            zone = zone
        ).days

    @Test
    fun `a stated block charges the day it was planned for with the hours it names`() {
        val load = loadOf(block(monday, monday, endHour = 11))

        assertEquals(1, load.size)
        assertEquals(monday.toEpochDay(), load.first().epochDay)
        assertEquals(60, load.first().minutes)
        assertEquals(1, load.first().tasks)
        assertFalse("a block the user typed is not an estimate", load.first().approximate)
    }

    @Test
    fun `a block that runs past midnight is split over the days it covers`() {
        val load = loadOf(block(tuesday, wednesday, endHour = 2))

        assertEquals(
            listOf(tuesday.toEpochDay(), wednesday.toEpochDay()),
            load.map { it.epochDay }
        )
        assertEquals(
            "the night and the next morning each carry what fell in them",
            listOf(840, 120),
            load.map { it.minutes }
        )
    }

    @Test
    fun `a deadline with an estimate charges its day the estimate and marks the hours as guessed`() {
        val load = loadOf(deadline(wednesday, estimate = 45))

        assertEquals(1, load.size)
        assertEquals(45, load.first().minutes)
        assertEquals("an estimated task is a task on the day all the same", 1, load.first().tasks)
        assertTrue(load.first().approximate)
    }

    @Test
    fun `a deadline with no estimate is a number of tasks rather than an invented length`() {
        val load = loadOf(deadline(wednesday))

        assertEquals(1, load.size)
        assertEquals("nothing is known about how long it takes, so nothing is claimed", 0, load.first().minutes)
        assertEquals(1, load.first().tasks)
        assertTrue("and the day has to say its hours are not the whole story", load.first().approximate)
    }

    @Test
    fun `a task with neither a deadline nor a block belongs to no day`() {
        val load = loadOf(
            Task(id = 3L, title = "someday", description = "", userId = 1L, status = TaskStatus.TODO)
        )

        assertTrue("an undated task must not appear as load anywhere", load.isEmpty())
    }

    @Test
    fun `the estimate of a task that also names its hours is not charged twice`() {
        val load = WorkloadMath.fold(
            listOf(
                Task(
                    id = 4L,
                    title = "both",
                    description = "",
                    startTime = instant(thursday, 9),
                    endTime = instant(thursday, 10),
                    estimatedMinutes = 240,
                    dueDate = instant(thursday, 18),
                    userId = 1L
                )
            ),
            fromDay = monday.toEpochDay(),
            toDay = thursday.toEpochDay(),
            zone = zone
        ).days

        assertEquals("the block the user stated is the better figure", listOf(60), load.map { it.minutes })
        assertFalse("and it is not a guess", load.first().approximate)
    }

    @Test
    fun `days with no work at all come back as no rows rather than as zeroes`() {
        val load = loadOf(deadline(wednesday, estimate = 30))

        assertEquals(listOf(wednesday.toEpochDay()), load.map { it.epochDay })
        assertTrue(
            "a screen drawing a week has to fill the empty days itself, from the calendar, not from a claim " +
                "that the profile put zero minutes there",
            load.none { it.epochDay == monday.toEpochDay() }
        )
    }

    @Test
    fun `the fold returns whole ordered days between the bounds it was given`() {
        val load = loadOf(
            deadline(thursday, estimate = 60),
            deadline(monday, estimate = 30),
            block(tuesday, tuesday, endHour = 13)
        )

        assertEquals(
            "oldest day first, whatever order the rows arrived in",
            listOf(monday.toEpochDay(), tuesday.toEpochDay(), thursday.toEpochDay()),
            load.map { it.epochDay }
        )
        assertEquals(listOf(30, 180, 60), load.map { it.minutes })
        assertEquals(listOf(1, 1, 1), load.map { it.tasks })
    }

    @Test
    fun `a block that starts before the window and ends after it is charged in full to the days it covers`() {
        val load = WorkloadMath.fold(
            listOf(
                Task(
                    id = 5L,
                    title = "across",
                    description = "",
                    startTime = instant(monday, 22),
                    endTime = instant(wednesday, 3),
                    userId = 1L
                )
            ),
            fromDay = tuesday.toEpochDay(),
            toDay = wednesday.toEpochDay(),
            zone = zone
        ).days

        assertEquals(
            "the whole of Tuesday and the part of Wednesday the block ran into, nothing of Monday",
            listOf(24 * 60, 3 * 60),
            load.map { it.minutes }
        )
        assertEquals(listOf(1, 1), load.map { it.tasks })
    }

    @Test
    fun `an empty profile has no load on any day`() {
        assertTrue(loadOf().isEmpty())
    }

    // -- what the same fold says about the rows it counted -------------------------------------------

    @Test
    fun `the priority split covers exactly the rows that landed in the window`() {
        val fold = WorkloadMath.fold(
            listOf(
                deadline(monday, priority = Priority.HIGH),
                block(tuesday, tuesday),
                deadline(wednesday, priority = Priority.LOW),
                deadline(LocalDate.of(2026, 6, 30), priority = Priority.HIGH)
            ),
            fromDay = monday.toEpochDay(),
            toDay = thursday.toEpochDay(),
            zone = zone
        )

        assertEquals(PriorityLoad(high = 1, medium = 1, low = 1), fold.priorities)
        assertEquals(
            "the row outside the window is not this reading's business, so it is not in its split either",
            3,
            fold.priorities.total
        )
    }

    @Test
    fun `a row charged to two days is one row in the split`() {
        val fold = WorkloadMath.fold(
            listOf(block(tuesday, wednesday, endHour = 2)),
            fromDay = monday.toEpochDay(),
            toDay = thursday.toEpochDay(),
            zone = zone
        )

        assertEquals("two days carry the block", 2, fold.days.size)
        assertEquals("and it is still one piece of work", 1, fold.priorities.total)
    }

    @Test
    fun `the fold names the rows that put a day on the calendar without stating a length`() {
        val fold = WorkloadMath.fold(
            listOf(
                block(monday, monday, endHour = 11),
                deadline(tuesday),
                deadline(wednesday, estimate = 30)
            ),
            fromDay = monday.toEpochDay(),
            toDay = thursday.toEpochDay(),
            zone = zone
        )

        assertEquals(
            "the bare deadline is the only row whose hours nobody said — an estimate is a stated length, " +
                "even a guessed one, and a block is a measured one",
            1,
            fold.lengthUnknownTasks
        )
    }

    @Test
    fun `an empty window folds to no days, no priorities and no unknown lengths`() {
        val fold = WorkloadMath.fold(
            emptyList(),
            fromDay = monday.toEpochDay(),
            toDay = thursday.toEpochDay(),
            zone = zone
        )

        assertEquals(emptyList<DayLoad>(), fold.days)
        assertEquals(PriorityLoad(), fold.priorities)
        assertEquals(0, fold.lengthUnknownTasks)
    }
}
