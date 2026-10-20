package com.taskplanner.data.statistics

import com.taskplanner.data.model.DailyCompletionCount
import com.taskplanner.data.model.DailyWorkSummary
import com.taskplanner.data.model.TaskHealthCounts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic between an aggregate and a picture, tested on its own.
 *
 * A chart is where a wrong number becomes invisible: a bar that is half as tall as it should be, a square
 * shaded for a day that has not happened, a gap filled with a zero nobody measured. None of that can be
 * seen by asking the database, because the database answered correctly — so these tests call the
 * functions directly, with days and instants written out by hand.
 *
 * The health bar is the same failure wearing a different shape, so its cases are here too: the five parts
 * have to fill the panel's own open total exactly, come back in the order the canvas paints them, stay
 * whole when the aggregate contradicts itself, and leave out the two figures — `in progress` and `stale` —
 * that cut across the deadline parts instead of dividing them.
 */
class DashboardMathTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun worked(day: LocalDate, seconds: Long) =
        DailyWorkSummary(localDay = day.toEpochDay(), seconds = seconds, intervals = 1)

    private fun localInstant(zone: ZoneId, text: String): Long =
        LocalDate.parse(text).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a completion lands on the day the user lived through, not the day UTC says`() {
        // 00:30 in Tokyo on the 1st is 15:30 on the 31st in UTC. Dividing the epoch millis by a day
        // length — the tempting shortcut — would put the finish on the previous month's last day.
        val justAfterMidnight = LocalDate.of(2026, 6, 1).atTime(0, 30).atZone(tokyo)
            .toInstant().toEpochMilli()

        val counts = DashboardMath.completionCounts(listOf(justAfterMidnight), tokyo)

        assertEquals(
            listOf(DailyCompletionCount(LocalDate.of(2026, 6, 1).toEpochDay(), 1)),
            counts
        )
        assertEquals(
            "and the UTC day is a different answer, which is why the zone is a parameter",
            LocalDate.of(2026, 5, 31).toEpochDay(),
            justAfterMidnight / 86_400_000L
        )
    }

    @Test
    fun `completions are counted per day and come back oldest first`() {
        val first = localInstant(berlin, "2026-06-03")
        val second = localInstant(berlin, "2026-06-01")
        val third = localInstant(berlin, "2026-06-01")

        val counts = DashboardMath.completionCounts(listOf(first, second, third), berlin)

        assertEquals(2, counts.size)
        assertEquals(DashboardMath.epochDayOf(second, berlin), counts.first().localDay)
        assertEquals(2, counts.first().count)
        assertEquals(1, counts.last().count)
    }

    @Test
    fun `the trend shows the days that produced something and invents no others`() {
        val days = DashboardMath.trend(
            worked = listOf(worked(LocalDate.of(2026, 6, 1), 600L), worked(LocalDate.of(2026, 6, 3), 0L)),
            completed = listOf(
                DailyCompletionCount(LocalDate.of(2026, 6, 2).toEpochDay(), 2),
                DailyCompletionCount(LocalDate.of(2026, 6, 1).toEpochDay(), 1)
            )
        )

        // Three days appear because three days produced something; the fourth of June is absent rather
        // than drawn as a zero.
        assertEquals(
            listOf(
                TrendDay(LocalDate.of(2026, 6, 1).toEpochDay(), 600L, 1),
                TrendDay(LocalDate.of(2026, 6, 2).toEpochDay(), 0L, 2),
                TrendDay(LocalDate.of(2026, 6, 3).toEpochDay(), 0L, 0)
            ),
            days
        )
    }

    @Test
    fun `the grid is whole weeks ending with the week that holds today`() {
        // Wednesday the 3rd, weeks starting Monday: the week that holds it began on Monday the 1st, and
        // three weeks of grid start two weeks before that.
        val today = LocalDate.of(2026, 6, 3)
        val range = DashboardMath.heatmapRange(today, weeks = 3, firstDay = DayOfWeek.MONDAY)

        assertEquals(LocalDate.of(2026, 5, 18).toEpochDay(), range.first)
        assertEquals(LocalDate.of(2026, 6, 7).toEpochDay(), range.last)

        val grid = DashboardMath.heatmap(
            worked = listOf(worked(today, 1_800L)),
            completed = emptyList(),
            today = today,
            weeks = 3,
            firstDay = DayOfWeek.MONDAY
        )

        assertEquals(3, grid.weeks.size)
        assertEquals(7, grid.weeks.first().size)
        assertEquals(21, grid.weeks.flatten().size)
        // The right-hand column is this week, and its first cell is Monday the 1st.
        assertEquals(LocalDate.of(2026, 6, 1).toEpochDay(), grid.weeks.last().first().localDay)
        assertEquals(1_800L, grid.busiestDaySeconds)
    }

    @Test
    fun `the first row of the grid is the locale's first day of the week`() {
        val today = LocalDate.of(2026, 6, 3)

        val monday = DashboardMath.weekStart(today, DayOfWeek.MONDAY)
        val sunday = DashboardMath.weekStart(today, DayOfWeek.SUNDAY)

        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(LocalDate.of(2026, 6, 1), monday)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        assertEquals(LocalDate.of(2026, 5, 31), sunday)
    }

    @Test
    fun `days after today are marked instead of shaded`() {
        val today = LocalDate.of(2026, 6, 3)
        val grid = DashboardMath.heatmap(
            worked = listOf(
                worked(LocalDate.of(2026, 6, 1), 600L),
                worked(LocalDate.of(2026, 6, 5), 6_000L)
            ),
            completed = emptyList(),
            today = today,
            weeks = 2,
            firstDay = DayOfWeek.MONDAY
        )

        val cells = grid.weeks.flatten()
        val future = cells.filter { it.isFuture }

        // A square for the 5th would otherwise read as a day the user did nothing on, and shading it would
        // be worse than that: the measurement a hand-made fixture or a bad import put there is still
        // carried in the cell, so nothing is lost — only the drawing rule changes.
        assertEquals(
            listOf(LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 6),
                LocalDate.of(2026, 6, 7)).map { it.toEpochDay() },
            future.map { it.localDay }
        )
        assertTrue(future.all { it.level == 0 })
        assertEquals(6_000L, future.first { it.localDay == LocalDate.of(2026, 6, 5).toEpochDay() }.seconds)
        // The scale comes from the days that have happened: the future row is ten times the Monday's work
        // and must not leave every real square of the grid looking almost empty.
        assertEquals(600L, grid.busiestDaySeconds)
        assertEquals(4, cells.first { it.localDay == LocalDate.of(2026, 6, 1).toEpochDay() }.level)
    }

    @Test
    fun `the scale is quartiles of the busiest day and any work at all is visible`() {
        val busiest = 4_000L

        assertEquals(4, DashboardMath.levelOf(4_000L, busiest))
        assertEquals(4, DashboardMath.levelOf(3_001L, busiest))
        assertEquals(3, DashboardMath.levelOf(2_001L, busiest))
        assertEquals(2, DashboardMath.levelOf(1_001L, busiest))
        assertEquals("a single second of work is a day with work on it", 1, DashboardMath.levelOf(1L, busiest))
        // The bounds themselves: a day that is exactly a quartile of the busiest closes the band below it,
        // and one second past that boundary opens the band above. Stated because bands that overlap by a
        // second are bands a legend cannot be drawn for.
        assertEquals(1, DashboardMath.levelOf(1_000L, busiest))
        assertEquals(2, DashboardMath.levelOf(2_000L, busiest))
        assertEquals(3, DashboardMath.levelOf(3_000L, busiest))
        assertEquals(0, DashboardMath.levelOf(0L, busiest))
        assertEquals("with no busiest day there is no scale", 0, DashboardMath.levelOf(600L, 0L))
    }

    @Test
    fun `a grid with nothing measured has no busiest day and no shades`() {
        val grid = DashboardMath.heatmap(
            worked = listOf(DailyWorkSummary(LocalDate.of(2026, 6, 1).toEpochDay(), 0L, 0)),
            completed = emptyList(),
            today = LocalDate.of(2026, 6, 3),
            weeks = 2,
            firstDay = DayOfWeek.MONDAY
        )

        assertEquals(0L, grid.busiestDaySeconds)
        assertTrue(grid.weeks.flatten().all { it.level == 0 })
    }

    @Test
    fun `bar heights are fractions of the tallest bar and never divide by nothing`() {
        assertEquals(listOf(0.25f, 1f, 0.5f), DashboardMath.fractions(listOf(15L, 60L, 30L)))
        assertEquals("a chart with no data is empty, not full", listOf(0f, 0f), DashboardMath.fractions(listOf(0L, 0L)))
        assertEquals(emptyList<Float>(), DashboardMath.fractions(emptyList()))
        assertEquals(listOf(1f), DashboardMath.fractions(listOf(7L)))
    }

    @Test
    fun `a streak counts back from today and stays alive through the next morning`() {
        val today = LocalDate.of(2026, 6, 10)
        val run = listOf(
            worked(today.minusDays(2), 60L),
            worked(today.minusDays(1), 60L),
            worked(today, 60L),
            worked(today.minusDays(4), 60L)
        )

        assertEquals(3, DashboardMath.streak(run, today))
        assertEquals("yesterday's work is still a streak today", 3, DashboardMath.streak(run, today.plusDays(1)))
        assertEquals(
            "a day with no work measured breaks it",
            0,
            DashboardMath.streak(run, today.plusDays(2))
        )
        assertEquals(0, DashboardMath.streak(emptyList(), today))
        assertEquals(
            "a row that measured nothing is not a worked day",
            0,
            DashboardMath.streak(listOf(worked(today, 0L)), today)
        )
    }

    @Test
    fun `a share of nothing is no figure at all`() {
        assertNull(DashboardMath.completionRate(completed = 0, total = 0))
        assertEquals(0.25f, DashboardMath.completionRate(completed = 1, total = 4)!!)
        assertEquals(1f, DashboardMath.completionRate(completed = 4, total = 4)!!)
    }

    @Test
    fun `no future cell is drawn and no cell is both lived and empty by mistake`() {
        val today = LocalDate.of(2026, 6, 3)
        val grid = DashboardMath.heatmap(
            worked = listOf(worked(today.minusDays(1), 900L)),
            completed = listOf(DailyCompletionCount(today.toEpochDay(), 1)),
            today = today,
            weeks = 1,
            firstDay = DayOfWeek.MONDAY
        )

        val lived = grid.weeks.flatten().filterNot { it.isFuture }

        assertEquals(3, lived.size)
        assertFalse(lived.last().isFuture)
        assertEquals(1, lived.last().completions)
        assertEquals(900L, lived[1].seconds)
    }

    // ---------------------------------------------------------------- the health bar's own arithmetic

    /** The four deadline parts SQLite counts, with the two crossing figures left at zero. */
    private fun openCounts(
        overdue: Int,
        dueToday: Int,
        dueThisWeek: Int,
        withoutDeadline: Int,
        open: Int
    ) = TaskHealthCounts(
        overdue = overdue,
        dueToday = dueToday,
        dueThisWeek = dueThisWeek,
        withoutDeadline = withoutDeadline,
        inProgress = 0,
        stale = 0,
        open = open
    )

    private fun List<HealthBucketCount>.countOf(bucket: HealthBucket): Int =
        single { it.bucket == bucket }.count

    @Test
    fun `the health bar's parts fill the open tasks it divides`() {
        val counts = TaskHealthCounts(
            overdue = 3, dueToday = 2, dueThisWeek = 4, withoutDeadline = 5,
            inProgress = 1, stale = 2, open = 20
        )

        val buckets = DashboardMath.healthBuckets(counts)

        assertEquals(
            "a bar whose parts do not add up to its own whole either draws tasks that are not there or " +
                "hides ones that are, and the reader cannot tell which",
            counts.open,
            buckets.sumOf { it.count }
        )
        assertEquals(
            "the six open tasks no named part claimed are the part drawn as due later",
            6,
            buckets.countOf(HealthBucket.DUE_LATER)
        )
    }

    @Test
    fun `the due later part is derived because nothing in the aggregate asks for it`() {
        val counts = openCounts(
            overdue = 1, dueToday = 2, dueThisWeek = 3, withoutDeadline = 4, open = 15
        )

        val later = DashboardMath.healthBuckets(counts).countOf(HealthBucket.DUE_LATER)

        assertEquals(
            "the question it answers is the difference between the panel's total and the parts it lists",
            15 - (1 + 2 + 3 + 4),
            later
        )

        val moreOpen = DashboardMath.healthBuckets(counts.copy(open = 30))

        assertEquals(
            "the same four counted parts under a bigger total answer with a bigger remainder",
            20,
            moreOpen.countOf(HealthBucket.DUE_LATER)
        )
        assertEquals(
            "and no other part moves when only the total does",
            listOf(1, 2, 3, 4),
            moreOpen.filter { it.bucket != HealthBucket.DUE_LATER }.map { it.count }
        )
    }

    @Test
    fun `an aggregate that lies about its total gives a short bar and never a negative part`() {
        // The counts come off one scan, so this should not happen — which is exactly why the arithmetic
        // has to answer sanely when it does: a negative segment is drawn by taking width away from the
        // parts beside it, so one lying figure would misdraw the four honest ones.
        val corrupt = TaskHealthCounts(
            overdue = 10, dueToday = 10, dueThisWeek = 10, withoutDeadline = 10,
            inProgress = 0, stale = 0, open = 15
        )

        val buckets = DashboardMath.healthBuckets(corrupt)

        buckets.forEach {
            assertTrue(
                "${it.bucket} is drawn as a width, and a negative width is not a width",
                it.count >= 0
            )
        }
        assertEquals(
            "the remainder stops at nothing rather than going below it",
            0,
            buckets.countOf(HealthBucket.DUE_LATER)
        )
        assertEquals(
            "the bar is short by five and nothing else moved",
            listOf(10, 10, 10, 0, 10),
            buckets.map { it.count }
        )
        assertEquals(
            "the boundary the floor exists at: parts that fill the total exactly leave nothing, which is zero",
            0,
            DashboardMath.healthBuckets(
                openCounts(overdue = 10, dueToday = 10, dueThisWeek = 10, withoutDeadline = 10, open = 40)
            ).countOf(HealthBucket.DUE_LATER)
        )
    }

    @Test
    fun `the health parts come back in the order the bar paints them`() {
        val buckets = DashboardMath.healthBuckets(
            openCounts(overdue = 1, dueToday = 1, dueThisWeek = 1, withoutDeadline = 1, open = 4)
        )

        assertEquals(
            "the renderer hands this list straight to the canvas, so its order is the order of the segments",
            listOf(
                HealthBucket.OVERDUE,
                HealthBucket.DUE_TODAY,
                HealthBucket.DUE_THIS_WEEK,
                HealthBucket.DUE_LATER,
                HealthBucket.WITHOUT_DEADLINE
            ),
            buckets.map { it.bucket }
        )
        assertEquals(
            "every state the panel divides its open tasks into has a part, so a legend cannot name one " +
                "the bar left out",
            HealthBucket.values().toList(),
            buckets.map { it.bucket }
        )
    }

    @Test
    fun `in progress and stale are figures beside the bar and never parts of it`() {
        val counts = TaskHealthCounts(
            overdue = 2, dueToday = 1, dueThisWeek = 1, withoutDeadline = 1,
            inProgress = 500, stale = 999, open = 9
        )

        val buckets = DashboardMath.healthBuckets(counts)

        assertEquals(
            "a task can be late and being worked on at the same time, so a part for either would be the " +
                "same task drawn twice and the bar would add up to more than the whole it divides",
            counts.open,
            buckets.sumOf { it.count }
        )
        assertEquals(
            "however large the two crossed figures are, they reach none of the parts: the four counted " +
                "buckets keep their own figures and the rest of the open tasks is the derived one",
            listOf(2, 1, 1, 4, 1),
            buckets.map { it.count }
        )
        assertTrue(
            "the screen prints them as figures of their own, so nothing of them belongs in the bar",
            buckets.none { it.count == counts.inProgress || it.count == counts.stale }
        )
    }

    @Test
    fun `the window before a period is as long as the period and shares no day with it`() {
        val monday = LocalDate.of(2026, 6, 1).toEpochDay()
        val week = monday until monday + 7L

        val before = DashboardMath.preceding(week)

        assertEquals("the same length", week.count(), before.count())
        assertEquals("ending the day before the period starts", monday - 1L, before.last)
        assertTrue("and sharing no day with it", before.last < week.first)
        // A week that begins on Monday the 1st is preceded by the week that begins on Monday the 25th —
        // the window the user would call last week, not a rolling seven days that ends mid-week.
        assertEquals(LocalDate.of(2026, 5, 25), LocalDate.ofEpochDay(before.first))
        assertEquals(LocalDate.of(2026, 5, 31), LocalDate.ofEpochDay(before.last))

        // A single day's window precedes into the single day before it: the case the screen uses for
        // today, and the one a shift by length could most easily land wrong.
        val today = LocalDate.of(2026, 6, 3).toEpochDay()
        val oneDay = DashboardMath.preceding(today until today + 1L)
        assertEquals(1, oneDay.count())
        assertEquals(LocalDate.of(2026, 6, 2), LocalDate.ofEpochDay(oneDay.first))
    }

    @Test
    fun `a change over no base keeps its direction and gives up its percentage`() {
        val firstWeek = DashboardMath.change(current = 3_600L, previous = 0L)

        assertEquals(ChangeDirection.UP, firstWeek.direction)
        assertNull(
            "any increase over nothing is an infinite one, and infinity is not a percentage",
            firstWeek.fraction
        )
        assertEquals("the figures themselves survive the missing ratio", 3_600L, firstWeek.current)
        assertEquals(0L, firstWeek.previous)

        val secondWeek = DashboardMath.change(current = 3_600L, previous = 1_800L)
        assertEquals(
            "twice the period before is a hundred percent over it — the change, not the ratio",
            1.0,
            requireNotNull(secondWeek.fraction),
            1e-9
        )
    }

    @Test
    fun `nothing measured twice is not a comparison and nothing falling to nothing is`() {
        val bothEmpty = DashboardMath.change(current = 0L, previous = 0L)
        assertEquals(ChangeDirection.FLAT, bothEmpty.direction)
        assertNull("two absences have no change to report", bothEmpty.fraction)

        val stopped = DashboardMath.change(current = 0L, previous = 1_800L)
        assertEquals(ChangeDirection.DOWN, stopped.direction)
        assertEquals(
            "a fall to zero over a base that existed is the whole of the change, and says so",
            -1.0,
            requireNotNull(stopped.fraction),
            1e-9
        )
    }

    @Test
    fun `a change too small to show as a percent is still the direction it is`() {
        val barely = DashboardMath.change(current = 5_401L, previous = 5_400L)

        assertEquals(ChangeDirection.UP, barely.direction)
        assertTrue(
            "one second in five thousand rounds to no percent at all, which is exactly why the screen has " +
                "words for it rather than a number",
            requireNotNull(barely.fraction) < 0.005
        )

        val same = DashboardMath.change(current = 5_400L, previous = 5_400L)
        assertEquals(ChangeDirection.FLAT, same.direction)
        assertEquals("an unchanged figure over a base is zero, not no figure", 0.0, requireNotNull(same.fraction), 1e-9)
    }
}
