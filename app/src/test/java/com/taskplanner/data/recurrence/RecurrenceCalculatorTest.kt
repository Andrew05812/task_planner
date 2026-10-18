package com.taskplanner.data.recurrence

import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repetition calendar, tested as pure maths on a fixed zone.
 *
 * These are the cases that hand-rolled date arithmetic usually gets wrong — month ends, February,
 * leap years, the two DST transitions, year rollovers — plus the invariants the series relies on:
 * occurrences only ever move forward, and an occurrence id is reproducible from the series and the
 * scheduled instant so nothing can duplicate an instance.
 */
class RecurrenceCalculatorTest {

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    private val berlin = "Europe/Berlin"

    @Test
    fun `daily steps one day at a time`() {
        val rule = series(RepeatUnit.DAY, anchor = "2026-01-05T09:00")
        expect("2026-01-06T09:00+01:00[Europe/Berlin]", next(rule))
    }

    @Test
    fun `every n days uses the interval`() {
        val rule = series(RepeatUnit.DAY, interval = 3, anchor = "2026-01-05T09:00")
        expect("2026-01-08T09:00+01:00[Europe/Berlin]", next(rule))
    }

    @Test
    fun `weekly keeps the anchor weekday`() {
        // 2026-01-05 is a Monday.
        val weekly = series(RepeatUnit.WEEK, anchor = "2026-01-05T09:00")
        expect("2026-01-12T09:00+01:00[Europe/Berlin]", next(weekly))

        val biweekly = series(RepeatUnit.WEEK, interval = 2, anchor = "2026-01-05T09:00")
        expect("2026-01-19T09:00+01:00[Europe/Berlin]", next(biweekly))
    }

    @Test
    fun `chosen weekdays walk through the block and then continue in the next one`() {
        val rule = series(RepeatUnit.WEEK, weekDays = "1,3,5", anchor = "2026-01-05T09:00")
        val monday = rule.anchorAt
        val wednesday = next(rule, monday)
        val friday = next(rule, wednesday)
        val nextMonday = next(rule, friday)

        expect("2026-01-07T09:00+01:00[Europe/Berlin]", wednesday)
        expect("2026-01-09T09:00+01:00[Europe/Berlin]", friday)
        expect("2026-01-12T09:00+01:00[Europe/Berlin]", nextMonday)
    }

    @Test
    fun `biweekly chosen weekdays skip the block in between`() {
        val rule = series(RepeatUnit.WEEK, interval = 2, weekDays = "1,3,5", anchor = "2026-01-05T09:00")
        val friday = next(rule, next(rule))

        expect("2026-01-19T09:00+01:00[Europe/Berlin]", next(rule, friday))
    }

    @Test
    fun `monthly clamps a short month without dragging the later ones down`() {
        val rule = series(RepeatUnit.MONTH, anchor = "2026-01-31T09:00")
        val february = next(rule)
        expect("2026-02-28T09:00+01:00[Europe/Berlin]", february)
        expect("2026-03-31T09:00+02:00[Europe/Berlin]", next(rule, february))
    }

    @Test
    fun `monthly reaches the leap day and comes back afterwards`() {
        val rule = series(RepeatUnit.MONTH, anchor = "2024-01-31T09:00")
        val february = next(rule)
        expect("2024-02-29T09:00+01:00[Europe/Berlin]", february)
        expect("2024-03-31T09:00+02:00[Europe/Berlin]", next(rule, february))
    }

    @Test
    fun `monthly counts from the anchor month when the interval is wider than a month`() {
        val rule = series(RepeatUnit.MONTH, interval = 2, anchor = "2026-01-31T09:00")
        val march = next(rule)
        expect("2026-03-31T09:00+02:00[Europe/Berlin]", march)
        expect("2026-05-31T09:00+02:00[Europe/Berlin]", next(rule, march))
    }

    @Test
    fun `monthly honours an explicit day of month over the anchor day`() {
        val rule = series(RepeatUnit.MONTH, dayOfMonth = 31, anchor = "2026-01-10T09:00")
        expect("2026-02-28T09:00+01:00[Europe/Berlin]", next(rule))
    }

    @Test
    fun `monthly crosses the year boundary`() {
        val rule = series(RepeatUnit.MONTH, anchor = "2026-11-15T09:00")
        val december = next(rule)
        expect("2026-12-15T09:00+01:00[Europe/Berlin]", december)
        expect("2027-01-15T09:00+01:00[Europe/Berlin]", next(rule, december))
    }

    @Test
    fun `yearly turns the leap anniversary into the last day of february`() {
        val rule = series(RepeatUnit.YEAR, anchor = "2024-02-29T09:00")
        var cursor = rule.anchorAt
        val seen = mutableListOf<String>()
        repeat(4) {
            cursor = next(rule, cursor)
            seen += at(cursor).toLocalDate().toString()
        }
        assertEquals(listOf("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), seen)
    }

    @Test
    fun `yearly keeps the anniversary across several years`() {
        val rule = series(RepeatUnit.YEAR, interval = 3, anchor = "2026-05-10T09:00")
        expect("2029-05-10T09:00+02:00[Europe/Berlin]", next(rule))
    }

    @Test
    fun `a daily series keeps its wall clock through the spring forward gap`() {
        // In Berlin the clock jumps 02:00 -> 03:00 on 2026-03-29, so 02:30 does not exist there.
        val rule = series(RepeatUnit.DAY, anchor = "2026-03-28T02:30")
        val gapDay = next(rule)
        expect("2026-03-29T03:30+02:00[Europe/Berlin]", gapDay)
        expect("2026-03-30T02:30+02:00[Europe/Berlin]", next(rule, gapDay))
    }

    @Test
    fun `a daily series keeps its wall clock through the autumn repeated hour`() {
        // 2026-10-25 is the day 02:30 happens twice in Berlin.
        val rule = series(RepeatUnit.DAY, anchor = "2026-10-24T02:30")
        val ambiguous = next(rule)
        expect("2026-10-25T02:30+02:00[Europe/Berlin]", ambiguous)
        expect("2026-10-26T02:30+01:00[Europe/Berlin]", next(rule, ambiguous))
    }

    @Test
    fun `the series zone decides the wall clock even when the device moves`() {
        val rule = series(RepeatUnit.DAY, anchor = "2026-06-30T23:30", zone = "Asia/Tokyo")
        val defaultWas = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            expect("2026-07-01T23:30+09:00[Asia/Tokyo]", next(rule), zone = "Asia/Tokyo")
        } finally {
            TimeZone.setDefault(defaultWas)
        }
    }

    @Test
    fun `a rule without a recorded zone follows the device`() {
        val deviceZone = ZoneId.systemDefault()
        val anchorAt = LocalDateTime.parse("2026-01-05T09:00").atZone(deviceZone).toInstant().toEpochMilli()
        val rule = RecurrenceRule(
            userId = 1,
            unit = RepeatUnit.DAY,
            anchorAt = anchorAt,
            cursorAt = anchorAt,
            zoneId = ""
        )
        assertEquals(
            LocalDateTime.parse("2026-01-06T09:00"),
            at(next(rule), deviceZone.id).toLocalDateTime()
        )
    }

    @Test
    fun `an interval below one still advances the series`() {
        val stalled = series(RepeatUnit.DAY, interval = 0, anchor = "2026-01-05T09:00")
        val stepping = series(RepeatUnit.DAY, interval = 1, anchor = "2026-01-05T09:00")
        assertEquals(next(stepping), next(stalled))
    }

    @Test
    fun `a cursor behind the anchor never produces an occurrence before the series starts`() {
        val daily = series(RepeatUnit.DAY, anchor = "2026-01-05T09:00")
        val stale = daily.copy(cursorAt = daily.anchorAt - 5L * DAY_MILLIS)

        assertTrue(next(stale) >= daily.anchorAt)
    }

    @Test
    fun `long runs only ever move forward`() {
        listOf(
            series(RepeatUnit.DAY, anchor = "2026-01-05T09:00"),
            series(RepeatUnit.DAY, interval = 7, anchor = "2026-01-05T09:00"),
            series(RepeatUnit.WEEK, weekDays = "1,3,5", anchor = "2026-01-05T09:00"),
            series(RepeatUnit.MONTH, anchor = "2026-01-31T09:00"),
            series(RepeatUnit.MONTH, interval = 3, anchor = "2026-01-31T09:00"),
            series(RepeatUnit.YEAR, anchor = "2024-02-29T09:00")
        ).forEach { rule ->
            val instants = RecurrenceCalculator.upcoming(rule, rule.anchorAt, 400)
            assertEquals("every step must produce an occurrence", 400, instants.size)
            assertEquals("occurrences must be unique", instants.size, instants.distinct().size)
            instants.zipWithNext().forEach { (before, after) ->
                assertTrue("occurrences must move forward", after > before)
            }
        }
    }

    @Test
    fun `upcoming stops at the number asked for`() {
        val rule = series(RepeatUnit.MONTH, anchor = "2026-01-31T09:00")
        val days = RecurrenceCalculator.upcoming(rule, rule.anchorAt, 6)
            .map { at(it).toLocalDate().dayOfMonth }
        assertEquals(listOf(28, 31, 30, 31, 30, 31), days)
    }

    @Test
    fun `an occurrence id is reproducible and specific to its series`() {
        val first = RecurrenceCalculator.occurrenceUuid("series-a", 1_700_000_000_000L)
        val sameAgain = RecurrenceCalculator.occurrenceUuid("series-a", 1_700_000_000_000L)
        val otherInstant = RecurrenceCalculator.occurrenceUuid("series-a", 1_700_000_086_400_000L)
        val otherSeries = RecurrenceCalculator.occurrenceUuid("series-b", 1_700_000_000_000L)

        assertEquals(first, sameAgain)
        assertNotEquals(first, otherInstant)
        assertNotEquals(first, otherSeries)
        assertEquals(first, UUID.fromString(first).toString())
    }

    private fun series(
        unit: RepeatUnit,
        interval: Int = 1,
        weekDays: String = "",
        dayOfMonth: Int = 0,
        anchor: String,
        zone: String = berlin
    ): RecurrenceRule {
        val anchorAt = LocalDateTime.parse(anchor).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
        return RecurrenceRule(
            userId = 1,
            unit = unit,
            interval = interval,
            weekDays = weekDays,
            dayOfMonth = dayOfMonth,
            anchorAt = anchorAt,
            zoneId = zone,
            cursorAt = anchorAt
        )
    }

    private fun next(rule: RecurrenceRule, from: Long = rule.cursorAt): Long =
        RecurrenceCalculator.nextOccurrence(rule, from)
            ?: throw AssertionError("expected an occurrence after ${at(from)}")

    private fun at(instant: Long, zone: String = berlin): ZonedDateTime =
        Instant.ofEpochMilli(instant).atZone(ZoneId.of(zone))

    private fun expect(expected: String, actual: Long, zone: String = berlin) {
        assertEquals(ZonedDateTime.parse(expected), at(actual, zone))
    }
}
