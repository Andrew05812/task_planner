package com.taskplanner.data.report

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar half of the report: what a period means once it is resolved in a zone.
 *
 * Pure arithmetic, no database, because these are the bounds every figure in the report is measured between
 * and a mistake here cannot be caught later — a window one hour off makes every count in the report wrong in
 * the same direction, and no section looks broken.
 *
 * The dates are real: Europe/Berlin springs forward on the last Sunday of March 2026 and falls back on the last
 * Sunday of October, so 29 March 2026 is a 23-hour day and 25 October 2026 is a 25-hour one. Those two days are
 * what §7.1 asks a report to survive, and they are the reason [ReportWindow] resolves both of its instant
 * bounds through `java.time` instead of measuring a day as 86 400 000 milliseconds.
 */
class ReportPeriodTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val utc: ZoneId = ZoneOffset.UTC

    private val springForward = LocalDate.of(2026, 3, 29)
    private val fallBack = LocalDate.of(2026, 10, 25)

    /**
     * The window for a period, anchored on a day the tests can name.
     *
     * [ReportWindow.of] takes its anchor day and its generation instant rather than defaulting them, because a
     * value object that read the wall clock itself would be a second clock in an app that has agreed on one.
     * Nothing in this file depends on which June day or which millisecond is named — the bounds come from the
     * period and the zone — so the anchor is fixed here once instead of twenty times.
     */
    private fun windowOf(
        period: ReportPeriod,
        zone: ZoneId,
        today: LocalDate = LocalDate.of(2026, 6, 3),
        generatedAt: Long = 1_780_000_000_000L
    ): ReportWindow = ReportWindow.of(period, zone, today = today, generatedAt = generatedAt)

    /**
     * A range the test knows is well-formed, taken through the factory that checks it.
     *
     * `Range` cannot be constructed from outside the module's own report package: the reversed pair is
     * [ReportPeriod.range]'s to refuse, and a test that bypassed that would be testing a period no caller can
     * hand the app.
     */
    private fun between(from: LocalDate, to: LocalDate): ReportPeriod =
        (ReportPeriod.range(from, to) as ReportPeriodRead.Accepted).period

    // -- the two days that are not 24 hours long ---------------------------------------------------

    @Test
    fun `the spring-forward day is twenty-three hours long and still one day`() {
        val window = windowOf(ReportPeriod.Day(springForward), berlin, today = springForward)

        assertEquals(
            "the clock jumps from 02:00 to 03:00, so the day is 23 hours, not the 24 the arithmetic assumes",
            23L * 60 * 60 * 1000,
            window.toMillis - window.fromMillis
        )
        assertEquals(1, window.days)
        assertEquals(window.fromDay, window.toDayExclusive - 1)
    }

    @Test
    fun `the fall-back day is twenty-five hours long and still one day`() {
        val window = windowOf(ReportPeriod.Day(fallBack), berlin, today = fallBack)

        assertEquals(
            "an hour is lived twice, and a report that measured the day in milliseconds would end it an hour early",
            25L * 60 * 60 * 1000,
            window.toMillis - window.fromMillis
        )
        assertEquals(1, window.days)
    }

    @Test
    fun `a week over the spring-forward shift is seven days of unequal hours`() {
        // Sunday 22 March to Saturday 28 March is a clean week; the one that contains the jump runs
        // Monday 23 March to Sunday 29 March when the week is named by its Monday.
        val window = windowOf(
            ReportPeriod.Week(LocalDate.of(2026, 3, 25), DayOfWeek.MONDAY),
            berlin,
            today = springForward
        )

        assertEquals(7, window.days)
        assertEquals(LocalDate.of(2026, 3, 23), window.firstDate)
        assertEquals(LocalDate.of(2026, 3, 29), window.lastDate)
        // Seven days, but five hours short of what seven 24-hour days would measure.
        assertEquals(7L * 24 * 60 * 60 * 1000 - 3_600_000, window.toMillis - window.fromMillis)
    }

    @Test
    fun `a period across the autumn shift gains the hour it lost in spring`() {
        val window = windowOf(
            ReportPeriod.Day(fallBack),
            berlin,
            today = fallBack
        )
        val span = window.toMillis - window.fromMillis

        assertEquals(25L * 60 * 60 * 1000, span)
        assertTrue(
            "the day after a 25-hour day starts where this one ends, so the two windows have to touch",
            windowOf(ReportPeriod.Day(fallBack.plusDays(1)), berlin).fromMillis == window.toMillis
        )
    }

    @Test
    fun `day windows meet without a gap or an overlap`() {
        val today = windowOf(ReportPeriod.Day(LocalDate.of(2026, 6, 10)), berlin)
        val tomorrow = windowOf(ReportPeriod.Day(LocalDate.of(2026, 6, 11)), berlin)

        assertEquals(
            "half-open days must tile: one ends exactly where the next begins",
            today.toMillis,
            tomorrow.fromMillis
        )
        assertEquals(
            "and so do their day bounds",
            today.toDayExclusive,
            tomorrow.fromDay
        )
    }

    // -- boundaries: what is inside, what is not ---------------------------------------------------

    @Test
    fun `a period takes its first instant and refuses the instant it ends at`() {
        val window = windowOf(ReportPeriod.Day(springForward), berlin, today = springForward)

        assertFalse(
            "one millisecond before the day opens belongs to the day before",
            window.containsInstant(window.fromMillis - 1)
        )
        assertTrue("the opening instant is inside", window.containsInstant(window.fromMillis))
        assertTrue(
            "the last millisecond before it closes is inside",
            window.containsInstant(window.toMillis - 1)
        )
        assertFalse(
            "the closing instant is the next period's first instant, not this one's last",
            window.containsInstant(window.toMillis)
        )
    }

    @Test
    fun `a day bound is inclusive at both ends because days are counted, not divided`() {
        val window = windowOf(ReportPeriod.Day(springForward), berlin, today = springForward)

        assertTrue(window.containsDay(window.fromDay))
        assertTrue(window.containsDay(window.toDayInclusive))
        assertFalse(window.containsDay(window.toDayExclusive))
        assertFalse(window.containsDay(window.fromDay - 1))
    }

    // -- the zone is part of the question ----------------------------------------------------------

    @Test
    fun `the same instant is Tuesday in Berlin and Monday in UTC`() {
        // 23:30 UTC on 28 March is 00:30 on 29 March in Berlin: the shift has not happened yet that minute,
        // so the city is still on CET (UTC+1) and has already crossed into the new calendar day.
        val instant = LocalDate.of(2026, 3, 28).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

        val berlinDay = windowOf(ReportPeriod.Day(springForward), berlin, today = springForward)
        val utcDay = windowOf(
            ReportPeriod.Day(LocalDate.of(2026, 3, 28)), utc, today = LocalDate.of(2026, 3, 28)
        )

        assertTrue(
            "a report asked in Berlin counts this finish on the 29th",
            berlinDay.containsInstant(instant)
        )
        assertTrue(
            "a report asked in UTC counts the same milliseconds on the 28th",
            utcDay.containsInstant(instant)
        )
    }

    @Test
    fun `the same calendar day is a different pair of instants in two zones`() {
        val berlin = windowOf(ReportPeriod.Day(springForward), berlin, today = springForward)
        val utc = windowOf(ReportPeriod.Day(springForward), utc, today = springForward)

        assertFalse(
            "resolving the date is not the same as resolving a duration: neither bound may be assumed",
            berlin.fromMillis == utc.fromMillis && berlin.toMillis == utc.toMillis
        )
        assertEquals(24L * 60 * 60 * 1000, utc.toMillis - utc.fromMillis)
    }

    // -- month, year, range ------------------------------------------------------------------------

    @Test
    fun `a February report is as long as that February actually was`() {
        val leap = windowOf(ReportPeriod.Month(YearMonth.of(2028, 2)), berlin)
        val common = windowOf(ReportPeriod.Month(YearMonth.of(2026, 2)), berlin)

        assertEquals(29, leap.days)
        assertEquals(28, common.days)
        assertEquals(LocalDate.of(2028, 2, 29), leap.lastDate)
    }

    @Test
    fun `a year report covers the whole year and its month boundary costs nothing`() {
        val window = windowOf(ReportPeriod.Year(2026), berlin)

        assertEquals(365, window.days)
        assertEquals(LocalDate.of(2025, 12, 31), window.preceding(berlin).lastDate)
        assertEquals(366, windowOf(ReportPeriod.Year(2024), berlin).days)
    }

    @Test
    fun `a report whose week straddles the new year is one week, not two`() {
        val window = windowOf(
            ReportPeriod.Week(LocalDate.of(2026, 1, 1), DayOfWeek.MONDAY), berlin
        )

        assertEquals(7, window.days)
        assertEquals(LocalDate.of(2025, 12, 29), window.firstDate)
        assertEquals(LocalDate.of(2026, 1, 4), window.lastDate)
    }

    @Test
    fun `the first day of week decides which seven days a week is`() {
        val sunday = LocalDate.of(2026, 1, 4)

        val iso = windowOf(ReportPeriod.Week(sunday, DayOfWeek.MONDAY), berlin)
        val american = windowOf(ReportPeriod.Week(sunday, DayOfWeek.SUNDAY), berlin)

        // A Monday-named week containing Sunday 4 January is 29 December to 4 January. The same day is the
        // *first* day of a Sunday-named week, so that week runs 4 to 10 January: the anchor never belongs to
        // the week before its own, which is the one way this rule could quietly steal seven days from
        // somebody else's calendar.
        assertEquals(LocalDate.of(2025, 12, 29), iso.firstDate)
        assertEquals(LocalDate.of(2026, 1, 4), iso.lastDate)
        assertEquals(LocalDate.of(2026, 1, 4), american.firstDate)
        assertEquals(LocalDate.of(2026, 1, 10), american.lastDate)
        assertEquals(7, iso.days)
        assertEquals(7, american.days)
    }

    @Test
    fun `a range that names one day is a day, not nothing`() {
        val day = LocalDate.of(2026, 6, 15)
        val read = ReportPeriod.range(day, day)

        val accepted = read as ReportPeriodRead.Accepted
        val window = windowOf(accepted.period, berlin)
        assertEquals(1, window.days)
        assertEquals(day, window.firstDate)
        assertEquals(day, window.lastDate)
    }

    @Test
    fun `a range spanning a month boundary keeps every day of both months`() {
        val window = windowOf(between(LocalDate.of(2026, 1, 28), LocalDate.of(2026, 2, 3)), berlin)

        assertEquals(7, window.days)
        assertEquals(LocalDate.of(2026, 2, 3), window.lastDate)
    }

    // -- refused questions -------------------------------------------------------------------------

    @Test
    fun `a range that ends before it starts is refused rather than quietly turned around`() {
        val from = LocalDate.of(2026, 6, 20)
        val to = LocalDate.of(2026, 6, 10)

        val read = ReportPeriod.range(from, to)

        val rejected = read as ReportPeriodRead.Rejected
        assertEquals(ReportPeriodRejection.ENDS_BEFORE_IT_STARTS, rejected.reason)
        assertEquals("the reader's own pair comes back so the screen can name it", from, rejected.from)
        assertEquals(to, rejected.to)
    }

    @Test
    fun `a year the calendar cannot build is refused instead of throwing`() {
        val rejected = ReportPeriod.year(Int.MAX_VALUE) as ReportPeriodRead.Rejected

        assertEquals(ReportPeriodRejection.YEAR_OUT_OF_CALENDAR_RANGE, rejected.reason)
        assertTrue(
            "a year inside the calendar is accepted",
            ReportPeriod.year(2026) is ReportPeriodRead.Accepted
        )
    }

    // -- the window a comparison reads -------------------------------------------------------------

    @Test
    fun `the previous window has the same number of days and ends the day before`() {
        val window = windowOf(between(LocalDate.of(2026, 3, 23), springForward), berlin, today = springForward)
        val previous = window.preceding(berlin)

        assertEquals(window.days, previous.days)
        assertEquals(
            "the two windows meet at a day boundary rather than overlapping by a day",
            window.fromDay - 1,
            previous.toDayInclusive
        )
        assertEquals(LocalDate.of(2026, 3, 16), previous.firstDate)
        assertEquals(LocalDate.of(2026, 3, 22), previous.lastDate)
    }

    @Test
    fun `a comparison is aligned by days, which is why its length in hours differs`() {
        val window = windowOf(between(LocalDate.of(2026, 3, 23), springForward), berlin, today = springForward)
        val previous = window.preceding(berlin)

        assertEquals(7, window.days)
        assertEquals(7, previous.days)
        // The current week holds the 23-hour day; the week before it does not. Equal spans in days, unequal
        // in milliseconds — which is exactly why a comparison shifts days and never subtracts a duration.
        assertTrue(
            "the seven days before a shift-out week are one hour longer than it",
            previous.toMillis - previous.fromMillis > window.toMillis - window.fromMillis
        )
    }

    @Test
    fun `the previous window carries the same zone, anchor and instant`() {
        val window = windowOf(ReportPeriod.Month(YearMonth.of(2026, 5)), berlin, today = springForward)
        val previous = window.preceding(berlin)

        assertEquals(window.zone, previous.zone)
        assertEquals(window.today, previous.today)
        assertEquals(window.generatedAt, previous.generatedAt)
        assertEquals(LocalDate.of(2026, 4, 30), previous.lastDate)
        // Equal length rather than "the previous month": May has 31 days and April has 30, so a comparison
        // against April would measure one day of calendar as if it were a day of work. The window before May
        // is therefore 31 March to 30 April — 31 days, the same span, ending the day before May started.
        assertEquals(window.days, previous.days)
        assertEquals(31, previous.days)
        assertEquals(LocalDate.of(2026, 3, 31), previous.firstDate)
    }

    @Test
    fun `a period resolved twice gives back identical bounds`() {
        val period = ReportPeriod.Week(LocalDate.of(2026, 11, 3), DayOfWeek.MONDAY)
        val first = windowOf(period, berlin, today = fallBack, generatedAt = 1_770_000_000_000L)
        val second = windowOf(period, berlin, today = fallBack, generatedAt = 1_770_000_000_000L)

        // Repeatability starts here: if the same question could resolve to two windows, every figure built
        // on it would be unrepeatable no matter how careful the queries were.
        assertEquals(first, second)
    }
}
