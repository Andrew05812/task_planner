package com.taskplanner.data.tracking

import com.taskplanner.data.tracking.WorkTimeMath.MeasuredDuration
import com.taskplanner.testing.FakeTimeBase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The measurement maths behind the tracker, tested as instants and durations.
 *
 * These are the cases that decide whether the recorded time is worth anything: a pause that must not
 * be counted, a system clock the user moved in the middle of an interval, a reboot that leaves the
 * monotonic clock unable to arbitrate, a daylight-saving day that is 23 hours long, and a time zone
 * change that must move what the history screen groups together without touching a single duration.
 *
 * Nothing here trusts a fixture to still be a transition day: each DST test checks the JDK's own tzdb
 * for the offset change before asserting against it, so a stale date fails loudly.
 */
@RunWith(RobolectricTestRunner::class)
class TimeCalculationTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")

    /** Millis of a local date-time in [zone], the way a test states a wall-clock moment. */
    private fun at(local: LocalDateTime, zone: ZoneId): Long = local.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a normal interval is the difference of its two instants`() {
        val start = at(LocalDateTime.of(2026, 3, 4, 9, 0), newYork)
        val end = at(LocalDateTime.of(2026, 3, 4, 9, 30), newYork)

        assertEquals(MeasuredDuration(seconds = 1800), WorkTimeMath.measureInterval(start, end, null))
    }

    @Test
    fun `sub-second remainders are not rounded up into time that was not worked`() {
        val start = 1_000L
        val end = 3_999L

        assertEquals(2L, WorkTimeMath.measureInterval(start, end, null).seconds)
    }

    @Test
    fun `a wall clock that agrees with the monotonic clock is believed`() {
        val start = 10_000L
        val end = 40_000L

        val measured = WorkTimeMath.measureInterval(start, end, monotonicElapsedMillis = 29_500L)

        assertEquals(30L, measured.seconds)
        assertNull(measured.clockAnomaly)
    }

    @Test
    fun `a clock moved forward mid-interval is measured by the monotonic clock and recorded`() {
        val start = 0L
        val end = 65 * 60_000L
        // The user set the clock ahead by an hour while working; only five real minutes passed.
        val measured = WorkTimeMath.measureInterval(start, end, monotonicElapsedMillis = 5 * 60_000L)

        assertEquals(300L, measured.seconds)
        assertNotNull(measured.clockAnomaly)
        assertTrue(
            "the row has to say which clock won and by how much, was: ${measured.clockAnomaly}",
            measured.clockAnomaly!!.contains("3600")
        )
        assertTrue(measured.clockAnomaly!!.contains("monotonic"))
    }

    @Test
    fun `a clock set backwards mid-interval never yields a negative duration`() {
        val start = 100 * 60_000L
        val end = 10 * 60_000L
        val measured = WorkTimeMath.measureInterval(start, end, monotonicElapsedMillis = 20 * 60_000L)

        assertEquals(1200L, measured.seconds)
        assertTrue("measured: $measured", measured.clockAnomaly!!.contains("backwards"))
    }

    @Test
    fun `an interval that began before this process has no monotonic reading and is measured by the wall clock`() {
        val start = 0L
        val end = 45 * 60_000L

        val measured = WorkTimeMath.measureInterval(start, end, monotonicElapsedMillis = null)

        assertEquals(2700L, measured.seconds)
        assertNull(measured.clockAnomaly)
    }

    @Test
    fun `a backwards clock with no second clock to appeal to is recorded not silently clamped`() {
        val measured = WorkTimeMath.measureInterval(
            startedAt = 60 * 60_000L,
            endedAt = 0L,
            monotonicElapsedMillis = null
        )

        assertEquals(0L, measured.seconds)
        assertNotNull(
            "a zero-length row must never look like an honest measurement",
            measured.clockAnomaly
        )
        assertTrue(measured.clockAnomaly!!.contains("backwards"))
    }

    @Test
    fun `drift inside the tolerance is treated as scheduling noise rather than a clock change`() {
        val measured = WorkTimeMath.measureInterval(
            startedAt = 0L,
            endedAt = 10 * 60_000L,
            monotonicElapsedMillis = 10 * 60_000L - WorkTimeMath.CLOCK_TOLERANCE_MILLIS + 1_000L
        )
        assertNull(measured.clockAnomaly)
        assertEquals(600L, measured.seconds)

        val beyond = WorkTimeMath.measureInterval(
            startedAt = 0L,
            endedAt = 10 * 60_000L,
            monotonicElapsedMillis = 10 * 60_000L - WorkTimeMath.CLOCK_TOLERANCE_MILLIS - 1_000L
        )
        assertNotNull(beyond.clockAnomaly)
        assertEquals(
            "outside the tolerance the monotonic figure is the one kept",
            539L,
            beyond.seconds
        )
    }

    @Test
    fun `a daylight saving day is measured by its real length, not by twenty-four hours`() {
        val zone = newYork
        val transition = zone.rules.getTransition(LocalDateTime.of(2026, 3, 8, 2, 0))
        assertNotNull("expected 2026-03-08 to be the spring-forward day in $zone", transition)

        val start = at(LocalDateTime.of(2026, 3, 8, 1, 30), zone)
        val end = at(LocalDateTime.of(2026, 3, 8, 3, 30), zone)

        // 02:00 does not exist that day: the two wall-clock readings are one hour of real time apart.
        assertEquals(3600L, WorkTimeMath.measureInterval(start, end, null).seconds)
        assertEquals(
            "the local day is still the day the work happened on",
            LocalDate.of(2026, 3, 8),
            LocalDate.ofEpochDay(WorkTimeMath.epochDayOf(start, zone))
        )
    }

    @Test
    fun `changing the zone moves the day a measurement is filed under but never its duration`() {
        val instant = at(LocalDateTime.of(2026, 5, 10, 23, 30), newYork)
        val tokyo = ZoneId.of("Asia/Tokyo")

        assertEquals(
            "work in the late evening of one day is work in the early morning of the next in Tokyo",
            LocalDate.of(2026, 5, 10).toEpochDay(),
            WorkTimeMath.epochDayOf(instant, newYork)
        )
        assertEquals(LocalDate.of(2026, 5, 11).toEpochDay(), WorkTimeMath.epochDayOf(instant, tokyo))

        val nextMorning = at(LocalDateTime.of(2026, 5, 11, 0, 30), newYork)
        assertEquals(3600L, WorkTimeMath.measureInterval(instant, nextMorning, null).seconds)
    }

    @Test
    fun `the stored offset is the one the day was resolved with`() {
        val winter = at(LocalDateTime.of(2026, 1, 15, 12, 0), newYork)
        val summer = at(LocalDateTime.of(2026, 7, 15, 12, 0), newYork)

        assertEquals(-300, WorkTimeMath.utcOffsetMinutesOf(winter, newYork))
        assertEquals(-240, WorkTimeMath.utcOffsetMinutesOf(summer, newYork))
    }

    @Test
    fun `a live interval counts from its own start and never below zero`() {
        val open = at(LocalDateTime.of(2026, 4, 1, 14, 0), newYork)
        val now = at(LocalDateTime.of(2026, 4, 1, 14, 15), newYork)

        assertEquals(900L, WorkTimeMath.openSeconds(open, now, monotonicElapsedMillis = null))

        // The clock jumped back under a running ticker: the displayed number cannot go negative.
        val shiftedBack = now - 3_600_000L
        val measured = WorkTimeMath.openSeconds(open, shiftedBack, monotonicElapsedMillis = 900_000L)
        assertEquals("the monotonic reading is what a live interval shows", 900L, measured)
    }

    @Test
    fun `the monotonic clock cannot reach back before this process began`() {
        val timeBase = FakeTimeBase(wall = 5_000L, monotonic = 5_000L)
        val reference = MonotonicReference(timeBase)

        assertNull(
            "an interval started before the boot has no place on this clock",
            reference.elapsedSince(startedAtWallMillis = 4_000L)
        )

        timeBase.advance(millis = 30_000L)
        assertEquals(30_000L, reference.elapsedSince(startedAtWallMillis = 5_000L))
    }

    @Test
    fun `a clock pushed forward does not add time to an interval that was already running`() {
        val timeBase = FakeTimeBase(wall = 5_000L, monotonic = 5_000L)
        val reference = MonotonicReference(timeBase)

        // An hour of wall clock appears out of nowhere while the device keeps running normally.
        timeBase.shiftWall(millis = 60 * 60_000L)
        timeBase.advance(millis = 30_000L)

        assertEquals(
            "the interval started before the jump, so only the 30 seconds of real time count",
            30_000L,
            reference.elapsedSince(startedAtWallMillis = 5_000L),
        )
        assertNull(
            "an instant the shifted clock invented cannot be located on the monotonic line at all",
            reference.elapsedSince(startedAtWallMillis = 65_000L),
        )
    }

    @Test
    fun `a task with no estimate has no balance rather than an infinite overrun`() {
        val balance = WorkTimeMath.balance(estimatedMinutes = null, actualSeconds = 7200)

        assertNull(balance.estimatedSeconds)
        assertNull(balance.remainingSeconds)
        assertNull(balance.overrunSeconds)
        assertNull(balance.percentOfEstimate)
        assertEquals(7200L, balance.actualSeconds)
    }

    @Test
    fun `an estimate that is not yet used up reports what is left`() {
        val balance = WorkTimeMath.balance(estimatedMinutes = 45, actualSeconds = 1500)

        assertEquals(1200L, balance.remainingSeconds)
        assertNull(balance.overrunSeconds)
        assertEquals(55, balance.percentOfEstimate)
    }

    @Test
    fun `an estimate that is used up reports the overrun and no remaining time`() {
        val balance = WorkTimeMath.balance(estimatedMinutes = 20, actualSeconds = 1500)

        assertNull(balance.remainingSeconds)
        assertEquals(300L, balance.overrunSeconds)
        assertEquals(125, balance.percentOfEstimate)
    }

    @Test
    fun `a period that exactly exhausts the estimate is neither remaining nor over`() {
        val balance = WorkTimeMath.balance(estimatedMinutes = 25, actualSeconds = 1500)

        assertNull(balance.remainingSeconds)
        assertNull(balance.overrunSeconds)
        assertEquals(100, balance.percentOfEstimate)
    }

    @Test
    fun `period ranges are half-open so consecutive periods partition the time line`() {
        val day = LocalDate.of(2026, 6, 15)
        val range = WorkTimeMath.dayRange(day)
        assertEquals(day.toEpochDay(), range.first)
        assertEquals(day.toEpochDay() + 1, range.last + 1L)
        assertEquals(
            "the next day starts where this range ends",
            WorkTimeMath.dayRange(day.plusDays(1)).first,
            range.last + 1L
        )

        val month = WorkTimeMath.monthDayRange(day)
        assertEquals(LocalDate.of(2026, 6, 1).toEpochDay(), month.first)
        assertEquals(LocalDate.of(2026, 7, 1).toEpochDay(), month.last + 1L)
    }

    @Test
    fun `a week range opens on Monday and holds seven days`() {
        val thursday = LocalDate.of(2026, 6, 18)
        val week = WorkTimeMath.weekDayRange(thursday)

        assertEquals(LocalDate.of(2026, 6, 15).toEpochDay(), week.first)
        assertEquals(7L, week.last - week.first + 1L)
        assertEquals(
            "the following week starts where this one ends",
            WorkTimeMath.weekDayRange(thursday.plusDays(7)).first,
            week.last + 1L
        )
    }

    @Test
    fun `an instant exactly at midnight belongs to the day that begins`() {
        val midnight = at(LocalDateTime.of(2026, 9, 1, 0, 0), newYork)

        assertEquals(
            LocalDate.of(2026, 9, 1).toEpochDay(),
            WorkTimeMath.epochDayOf(midnight, newYork)
        )
    }

    @Test
    fun `the whole set of a session's intervals sums without the pauses between them`() {
        // The spec's own example: 09:00–09:30 and 10:00–10:45 on the same logical session.
        val intervals = listOf(
            WorkTimeMath.measureInterval(
                at(LocalDateTime.of(2026, 2, 3, 9, 0), newYork),
                at(LocalDateTime.of(2026, 2, 3, 9, 30), newYork),
                null
            ),
            WorkTimeMath.measureInterval(
                at(LocalDateTime.of(2026, 2, 3, 10, 0), newYork),
                at(LocalDateTime.of(2026, 2, 3, 10, 45), newYork),
                null
            ),
        )

        assertEquals(
            "the half hour of pause is not time anyone worked",
            75L * 60L,
            intervals.sumOf { it.seconds }
        )
        assertEquals(2, intervals.size)
    }
}
