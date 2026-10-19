package com.taskplanner.data.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The calendar's date and window maths, tested as pure java.time values.
 *
 * These are the bugs the calendar would otherwise ship with: a grid that cuts a week in half, a
 * February length memorised wrong at the century rule, a day window that steals a task due at
 * midnight from the next day, and a dragged task that drifts an hour every DST change. DST dates
 * are verified against the JDK's own tzdb inside each test, so a fixture that stops being a
 * transition day fails loudly instead of passing on a normal day.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarMathTest {

    private companion object {
        const val HOUR_MILLIS = 60L * 60 * 1000
        const val DAY_MILLIS = 24 * HOUR_MILLIS
    }

    private val newYork = ZoneId.of("America/New_York")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val santiago = ZoneId.of("America/Santiago")

    @Test
    fun `february 2024 has 29 days`() {
        assertEquals(29, CalendarMath.daysInMonth(2024, Month.FEBRUARY))
    }

    @Test
    fun `february 2025 has 28 days`() {
        assertEquals(28, CalendarMath.daysInMonth(2025, Month.FEBRUARY))
    }

    @Test
    fun `february 2100 has 28 days because the century rule denies the leap year`() {
        assertEquals(28, CalendarMath.daysInMonth(2100, Month.FEBRUARY))
    }

    @Test
    fun `november has 30 days`() {
        assertEquals(30, CalendarMath.daysInMonth(2026, Month.NOVEMBER))
    }

    @Test
    fun `december has 31 days`() {
        assertEquals(31, CalendarMath.daysInMonth(2026, Month.DECEMBER))
    }

    @Test
    fun `month grid covers whole weeks and contains every day of the month`() {
        val months = listOf(
            YearMonth.of(2026, 9),   // opens on a Tuesday, neighbours on both sides
            YearMonth.of(2025, 9),   // opens exactly on a Monday
            YearMonth.of(2026, 3),   // opens exactly on a Sunday
            YearMonth.of(2024, 2),   // 29 days
            YearMonth.of(2026, 1),   // opens on a Thursday, 31 days
            YearMonth.of(2100, 2)    // common-year February
        )
        listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY).forEach { first ->
            months.forEach { month ->
                val grid = CalendarMath.monthGrid(month, first)

                assertEquals("weeks only, for $month with $first", 0, grid.size % 7)
                assertEquals("opens on $first", first, grid.first().dayOfWeek)
                assertEquals("closes the day before the next $first", first, grid.last().plusDays(1).dayOfWeek)

                val cells = grid.toSet()
                assertEquals("no duplicated cells", grid.size, cells.size)
                assertEquals("cells are consecutive days", grid.size.toLong(),
                    ChronoUnit.DAYS.between(grid.first(), grid.last()) + 1)
                (1..month.lengthOfMonth()).forEach { day ->
                    assertTrue("day $day of $month missing from the grid", month.atDay(day) in cells)
                }
            }
        }
    }

    @Test
    fun `month grid with monday first differs from sunday first by one cell at the front`() {
        // September 2026 opens on a Tuesday, so the Sunday grid carries Sunday and Monday as
        // leading neighbour days while the Monday grid carries only Monday.
        val month = YearMonth.of(2026, 9)
        val sunday = CalendarMath.monthGrid(month, DayOfWeek.SUNDAY)
        val monday = CalendarMath.monthGrid(month, DayOfWeek.MONDAY)

        assertEquals(sunday.size, monday.size)
        assertEquals(sunday.first().plusDays(1), monday.first())
        assertEquals(sunday.drop(1), monday.dropLast(1))

        val leadingCells = { grid: List<LocalDate> -> grid.count { it.isBefore(month.atDay(1)) } }
        assertEquals(1, leadingCells(sunday) - leadingCells(monday))
    }

    @Test
    fun `a month that opens on the first day of week has no leading neighbour days`() {
        val september = YearMonth.of(2025, 9) // 2025-09-01 is a Monday
        assertEquals(DayOfWeek.MONDAY, september.atDay(1).dayOfWeek)
        assertEquals(september.atDay(1), CalendarMath.monthGrid(september, DayOfWeek.MONDAY).first())

        val march = YearMonth.of(2026, 3) // 2026-03-01 is a Sunday
        assertEquals(DayOfWeek.SUNDAY, march.atDay(1).dayOfWeek)
        assertEquals(march.atDay(1), CalendarMath.monthGrid(march, DayOfWeek.SUNDAY).first())
    }

    @Test
    fun `week grid returns seven consecutive days containing the anchor`() {
        val anchor = LocalDate.parse("2026-03-05") // a Thursday
        listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY).forEach { first ->
            val grid = CalendarMath.weekGrid(anchor, first)

            assertEquals(7, grid.size)
            assertTrue("the anchor must be on its own week", anchor in grid)
            assertEquals(first, grid.first().dayOfWeek)
            assertEquals("consecutive days", 6L, ChronoUnit.DAYS.between(grid.first(), grid.last()))
        }
    }

    @Test
    fun `week grid starts with an anchor on the first column and ends with an anchor on the last`() {
        val monday = LocalDate.parse("2026-03-02")
        assertEquals(monday, CalendarMath.weekGrid(monday, DayOfWeek.MONDAY).first())

        val sunday = monday.plusDays(6)
        assertEquals(sunday, CalendarMath.weekGrid(sunday, DayOfWeek.MONDAY).last())
    }

    @Test
    fun `day window covers the whole local day and ends one millisecond before the next midnight`() {
        val day = LocalDate.parse("2026-06-10") // ordinary Berlin day, no transition
        val window = CalendarMath.dayWindow(day, berlin)
        val nextDayStart = CalendarMath.startOfDay(day.plusDays(1), berlin)

        assertEquals(CalendarMath.startOfDay(day, berlin), window.first)
        assertEquals(nextDayStart - 1, window.last)
        assertEquals(DAY_MILLIS, window.last - window.first + 1)

        // LongRange is inclusive at both ends, so exactly midnight belongs to the NEXT window.
        assertTrue("midnight of the next day is outside its own window", nextDayStart !in window)
        assertTrue(nextDayStart in CalendarMath.dayWindow(day.plusDays(1), berlin))
        assertTrue(CalendarMath.startOfDay(day, berlin) in window)
    }

    @Test
    fun `spring forward day window is 23 hours long`() {
        val transition = newYork.rules.getTransition(LocalDateTime.of(2026, 3, 8, 2, 30))
        assertNotNull("2026-03-08 must still be the New York spring-forward day", transition)
        assertTrue("expected a gap, not an overlap", transition!!.isGap)

        val window = CalendarMath.dayWindow(LocalDate.parse("2026-03-08"), newYork)
        assertEquals(23 * HOUR_MILLIS, window.last - window.first + 1)
    }

    @Test
    fun `fall back day window is 25 hours long`() {
        val transition = newYork.rules.getTransition(LocalDateTime.of(2026, 11, 1, 1, 30))
        assertNotNull("2026-11-01 must still be the New York fall-back day", transition)
        assertTrue("expected an overlap, not a gap", transition!!.isOverlap)

        val window = CalendarMath.dayWindow(LocalDate.parse("2026-11-01"), newYork)
        assertEquals(25 * HOUR_MILLIS, window.last - window.first + 1)
    }

    @Test
    fun `to local date and minutes of day round trip an instant`() {
        val day = LocalDate.parse("2026-06-10")
        val evening = instantOf("2026-06-10", "21:30", berlin)

        assertEquals(day, CalendarMath.toLocalDate(evening, berlin))
        assertEquals(21 * 60 + 30, CalendarMath.minutesOfDay(evening, berlin))
        assertTrue("the instant sits inside its own day window", evening in CalendarMath.dayWindow(day, berlin))

        val lastMinute = instantOf("2026-06-10", "23:59", berlin)
        assertEquals(23 * 60 + 59, CalendarMath.minutesOfDay(lastMinute, berlin))
        assertEquals(0, CalendarMath.minutesOfDay(CalendarMath.startOfDay(day, berlin), berlin))
    }

    @Test
    fun `minutes of day still read the wall clock on the day the clock jumped forward`() {
        val nine = instantOf("2026-03-08", "09:00", newYork)

        assertEquals(540, CalendarMath.minutesOfDay(nine, newYork))
        assertEquals(LocalDate.parse("2026-03-08"), CalendarMath.toLocalDate(nine, newYork))
    }

    @Test
    fun `start of day returns the first instant of the day when midnight does not exist`() {
        val gapDay = LocalDate.parse("2025-09-07")
        val transition = santiago.rules.getTransition(LocalDateTime.of(2025, 9, 7, 0, 0))
        assertNotNull("Santiago must still swallow the midnight hour on 2025-09-07", transition)
        assertTrue("expected a gap", transition!!.isGap)

        val start = CalendarMath.startOfDay(gapDay, santiago)

        assertEquals(ZonedDateTime.parse("2025-09-07T01:00-03:00[America/Santiago]"), at(start, santiago))
        assertEquals("the window starts exactly where the day starts",
            start, CalendarMath.dayWindow(gapDay, santiago).first)
    }

    @Test
    fun `move wall clock keeps the time of day across the spring forward`() {
        // 2026-03-02 is the Monday before the jump, 2026-03-11 the Wednesday after it.
        val monday = instantOf("2026-03-02", "09:30", newYork)

        val moved = CalendarMath.moveWallClock(monday, LocalDate.parse("2026-03-11"), newYork)

        assertEquals(ZonedDateTime.parse("2026-03-11T09:30-04:00[America/New_York]"), at(moved, newYork))
        assertEquals("a naive nine whole days of millis would land at 08:30",
            9 * 60 + 30, CalendarMath.minutesOfDay(moved, newYork))
    }

    @Test
    fun `move wall clock onto a missing half past midnight lands after the gap`() {
        val transition = santiago.rules.getTransition(LocalDateTime.of(2025, 9, 7, 0, 30))
        assertNotNull("Santiago must still have no 00:30 on 2025-09-07", transition)
        assertTrue("expected a gap", transition!!.isGap)

        val friday = instantOf("2025-09-05", "00:30", santiago)
        val moved = CalendarMath.moveWallClock(friday, LocalDate.parse("2025-09-07"), santiago)

        assertEquals("the first instant after the gap", transition.instant.toEpochMilli(), moved)
        assertEquals(LocalTime.parse("01:00"), at(moved, santiago).toLocalTime())
        assertTrue("not silently one hour early",
            at(moved, santiago).toLocalTime().isAfter(LocalTime.parse("00:30")))
    }

    @Test
    fun `month window spans every cell of the month grid`() {
        val month = YearMonth.of(2026, 9)
        val grid = CalendarMath.monthGrid(month, DayOfWeek.MONDAY)
        val window = CalendarMath.monthWindow(month, DayOfWeek.MONDAY)

        // Everything is measured against the system zone here, because monthWindow is defined on
        // it; the assertions are about the window agreeing with the grid, whatever that zone is.
        assertEquals(CalendarMath.startOfDay(grid.first()), window.first)
        assertEquals(CalendarMath.startOfDay(grid.last().plusDays(1)) - 1, window.last)
        grid.forEach { cell ->
            assertTrue("every grid cell begins inside the month window",
                CalendarMath.startOfDay(cell) in window)
        }
        assertTrue(CalendarMath.startOfDay(month.atDay(1)) in window)
        assertTrue(CalendarMath.startOfDay(month.atEndOfMonth()) in window)
    }

    @Test
    fun `first day of week follows the device locale`() {
        val defaultWas = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals(DayOfWeek.SUNDAY, CalendarMath.firstDayOfWeek())

            Locale.setDefault(Locale.GERMANY)
            assertEquals(DayOfWeek.MONDAY, CalendarMath.firstDayOfWeek())
        } finally {
            Locale.setDefault(defaultWas)
        }
    }

    @Test
    fun `system zone is the device clock the deadlines are read on`() {
        assertEquals(ZoneId.systemDefault(), CalendarMath.systemZone())
    }

    private fun instantOf(date: String, time: String, zone: ZoneId): Long =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun at(millis: Long, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(millis).atZone(zone)
}
