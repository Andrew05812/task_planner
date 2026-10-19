package com.taskplanner.data.tracking

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The time maths behind the tracker: what a duration is, what day it belongs to, and what an
 * estimate minus actual work means.
 *
 * Every duration here is the difference of two UTC instants. Nothing is computed from local dates,
 * because a local date is a display property: it changes when the user travels, while the work they
 * did does not. Grouping and display do use the local day, and that day is resolved once at
 * measurement time and stored, so a later time-zone change moves only what the screen shows, never
 * what was counted.
 *
 * No Android API and no state: the whole file is plain maths, which is what makes [measureInterval]
 * testable against hand-written expectations.
 */
object WorkTimeMath {

    /**
     * How far the wall clock may disagree with the monotonic clock before the difference is treated
     * as a clock change rather than as scheduling jitter.
     *
     * A busy main thread can delay a read by tens of milliseconds, and an NTP step can move a device
     * by a second or two; neither is worth flagging. A minute is far outside that noise and small
     * enough to catch the case this guards — a user who moved the system clock mid-interval.
     */
    const val CLOCK_TOLERANCE_MILLIS: Long = 60_000L

    /** The local calendar day [millis] falls on, as an epoch day — the value stored in `local_day`. */
    fun epochDayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /**
     * The zone's offset from UTC at [millis], in minutes — stored beside [epochDayOf] so the day a
     * measurement was filed under can be re-read exactly, including across a daylight-saving change.
     */
    fun utcOffsetMinutesOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        zone.rules.getOffset(Instant.ofEpochMilli(millis)).totalSeconds / 60

    /** The local date an epoch day refers to — the inverse of [epochDayOf], for display. */
    fun toDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    /** A duration together with the reason it is not simply the difference of the two instants. */
    data class MeasuredDuration(val seconds: Long, val clockAnomaly: String? = null)

    /**
     * The length of an interval that began at [startedAt] and ended at [endedAt].
     *
     * Normally that is the difference of the two instants. It is not always: the system clock can be
     * moved by the user, by the network, or by a time-zone change while an interval is open, and then
     * the wall clock alone would produce a negative duration or a fictitious one. Where this process
     * has a monotonic reading for the interval ([monotonicElapsedMillis], from [MonotonicReference])
     * it arbitrates, and the wall-clock figure is kept only when it agrees within
     * [CLOCK_TOLERANCE_MILLIS].
     *
     * A disagreement is *recorded*, never repaired silently: the row keeps the figure the reliable
     * clock supported and writes why in [MeasuredDuration.clockAnomaly]. An interval that began before
     * this process started has no second clock to appeal to — that is the accepted compromise, it is
     * measured by the wall clock alone — and if even that is negative, the length is zero and the
     * anomaly says so, rather than the row quietly looking like no work was done.
     */
    fun measureInterval(
        startedAt: Long,
        endedAt: Long,
        monotonicElapsedMillis: Long?
    ): MeasuredDuration {
        val wallMillis = endedAt - startedAt
        if (monotonicElapsedMillis == null) {
            return if (wallMillis < 0) {
                MeasuredDuration(
                    seconds = 0,
                    clockAnomaly = "clock set backwards by ${-wallMillis / 1000}s; " +
                        "measured by wall clock only, no monotonic reading for this interval"
                )
            } else {
                MeasuredDuration(seconds = wallMillis / 1000L)
            }
        }
        val drift = wallMillis - monotonicElapsedMillis
        if (wallMillis < 0 || kotlin.math.abs(drift) > CLOCK_TOLERANCE_MILLIS) {
            val reason = if (wallMillis < 0) {
                "clock set backwards by ${-wallMillis / 1000}s"
            } else {
                "clock moved by ${drift / 1000}s during the interval"
            }
            return MeasuredDuration(
                seconds = monotonicElapsedMillis / 1000L,
                clockAnomaly = "$reason; measured by monotonic clock"
            )
        }
        return MeasuredDuration(seconds = wallMillis / 1000L)
    }

    /**
     * What an interval that is still open has accumulated so far, in seconds.
     *
     * This is the ticker's number and nothing else: it is never written to the database as a duration,
     * because the interval has not ended and its length is not yet a fact. The stored total of a live
     * session is always its closed intervals plus this, computed from the open row's own `started_at`.
     */
    fun openSeconds(
        openSince: Long,
        nowMillis: Long,
        monotonicElapsedMillis: Long?
    ): Long {
        val measured = measureInterval(openSince, nowMillis, monotonicElapsedMillis)
        return measured.seconds
    }

    /** Estimate against measured work, as the detail and card screens present them. */
    data class WorkBalance(
        val estimatedSeconds: Long?,
        val actualSeconds: Long
    ) {
        /** Time still available inside the estimate; null once the estimate is used up. */
        val remainingSeconds: Long?
            get() = estimatedSeconds?.let { (it - actualSeconds).takeIf { r -> r > 0 } }

        /** Time spent beyond the estimate; null while the estimate holds. */
        val overrunSeconds: Long?
            get() = estimatedSeconds?.let { (actualSeconds - it).takeIf { o -> o > 0 } }

        /** Whole percent of the estimate consumed, above 100 when the work overran it. */
        val percentOfEstimate: Int?
            get() = estimatedSeconds?.takeIf { it > 0 }
                ?.let { ((actualSeconds * 100) / it).toInt() }
    }

    /**
     * The estimate-vs-actual comparison for a task.
     *
     * A task with no estimate has no balance to strike, which is expressed by a null estimate rather
     * than by an infinite overrun: showing "100% over" for work the user never sized would present a
     * missing input as a missed deadline.
     */
    fun balance(estimatedMinutes: Int?, actualSeconds: Long): WorkBalance =
        WorkBalance(
            estimatedSeconds = estimatedMinutes?.let { it * 60L },
            actualSeconds = actualSeconds
        )

    /**
     * The half-open epoch-day range of the local week containing [anchor], Monday first.
     *
     * Period filters are epoch-day ranges because every aggregate groups on `local_day`.
     */
    fun weekDayRange(anchor: LocalDate): LongRange {
        val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        return monday.toEpochDay() until monday.plusDays(7).toEpochDay()
    }

    /** The half-open epoch-day range of [anchor]'s month. */
    fun monthDayRange(anchor: LocalDate): LongRange {
        val first = anchor.withDayOfMonth(1)
        return first.toEpochDay() until first.plusMonths(1).toEpochDay()
    }

    /** The half-open epoch-day range of [anchor]'s day. */
    fun dayRange(anchor: LocalDate): LongRange =
        anchor.toEpochDay() until anchor.plusDays(1).toEpochDay()
}
