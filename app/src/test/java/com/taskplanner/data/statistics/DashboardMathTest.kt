package com.taskplanner.data.statistics

import com.taskplanner.data.model.DailyCompletionCount
import com.taskplanner.data.model.DailyWorkSummary
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
}
