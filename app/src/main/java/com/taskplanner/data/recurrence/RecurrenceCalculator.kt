package com.taskplanner.data.recurrence

import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Repetition maths: given a rule and an instant, it says when the next occurrence of that series is.
 *
 * Every occurrence is computed from the anchor by an ordinal (1st, 2nd, 3rd … after the anchor) on
 * the local time-line of the zone the series was created in, through java.time. Deriving each step
 * from the anchor instead of from the previous instant is what makes the awkward cases come out
 * right rather than being hand-patched:
 *  - Jan 31 → Feb 28/29 → Mar 31: a clamped short month never drags the rest of the series to the
 *    28th, because the count is in whole months from the anchor;
 *  - Feb 29 yearly → Feb 28 in common years, back to Feb 29 in the next leap year;
 *  - DST: a task set for 02:30 that was shifted to 03:30 on the spring-forward day returns to
 *    02:30 the day after, because the wall clock belongs to the anchor, not to the last instance;
 *  - month, year and century rollovers, and the user moving between zones: the series keeps its own
 *    zone and its own time of day.
 *
 * No state, no Android API: the whole rule set is testable as plain maths.
 */
object RecurrenceCalculator {

    /** Guards the probe loop: a rule that cannot catch up gives up instead of spinning. */
    private const val MAX_PROBE_STEPS = 1000

    /** Instant of the first occurrence strictly after [from], or null if the series produces none. */
    fun nextOccurrence(rule: RecurrenceRule, from: Long): Long? {
        val zone = rule.zone()
        val anchor = Instant.ofEpochMilli(rule.anchorAt).atZone(zone)
        var ordinal = estimatedOrdinal(rule, anchor, from, zone)
        repeat(MAX_PROBE_STEPS) {
            val candidate = occurrenceAt(rule, anchor, ordinal, zone) ?: return null
            if (candidate > from) return candidate
            ordinal += 1
        }
        return null
    }

    /** The next [count] occurrences, oldest first — what the editor shows as a preview. */
    fun upcoming(rule: RecurrenceRule, from: Long, count: Int): List<Long> {
        val result = mutableListOf<Long>()
        var cursor = from
        repeat(count.coerceIn(0, MAX_PROBE_STEPS)) {
            val next = nextOccurrence(rule, cursor) ?: return result
            result += next
            cursor = next
        }
        return result
    }

    /**
     * Stable identity of an instance: derived from the series and the scheduled instant only, so two
     * devices that compute the same occurrence produce the same task uuid and sync cannot duplicate
     * it, and a retried materialisation collides with the row it was going to create.
     */
    fun occurrenceUuid(seriesUuid: String, occurrenceAt: Long): String =
        UUID.nameUUIDFromBytes("series:$seriesUuid@$occurrenceAt".toByteArray(Charsets.UTF_8)).toString()

    /** The [ordinal]-th occurrence after the anchor, as an epoch millis instant. */
    private fun occurrenceAt(
        rule: RecurrenceRule,
        anchor: ZonedDateTime,
        ordinal: Long,
        zone: ZoneId
    ): Long? {
        val step = rule.interval.coerceAtLeast(1).toLong()
        val time = anchor.toLocalTime()
        return when (rule.unit) {
            RepeatUnit.DAY -> at(anchor.toLocalDate().plusDays(ordinal * step), time, zone)

            RepeatUnit.WEEK -> {
                val weekdays = rule.scheduledWeekDays
                if (weekdays.isEmpty()) {
                    at(anchor.toLocalDate().plusWeeks(ordinal * step), time, zone)
                } else {
                    weekdayOccurrence(anchor, ordinal, step, weekdays, time, zone)
                }
            }

            RepeatUnit.MONTH -> {
                val month = YearMonth.from(anchor).plusMonths(ordinal * step)
                val requested = rule.dayOfMonth.takeIf { it in 1..31 } ?: anchor.dayOfMonth
                at(month.atDay(minOf(requested, month.lengthOfMonth())), time, zone)
            }

            RepeatUnit.YEAR -> {
                val month = YearMonth.of(anchor.year + (ordinal * step).toInt(), anchor.monthValue)
                val requested = rule.dayOfMonth.takeIf { it in 1..31 } ?: anchor.dayOfMonth
                at(month.atDay(minOf(requested, month.lengthOfMonth())), time, zone)
            }
        }
    }

    /**
     * Where the probe starts looking. Deliberately conservative — never past the real answer, at
     * most a step behind it — so the loop below can only walk forward over occurrences that are
     * already in the past and can never skip one that is still due.
     */
    private fun estimatedOrdinal(
        rule: RecurrenceRule,
        anchor: ZonedDateTime,
        from: Long,
        zone: ZoneId
    ): Long {
        val step = rule.interval.coerceAtLeast(1).toLong()
        val origin = if (from < rule.anchorAt) anchor else Instant.ofEpochMilli(from).atZone(zone)
        return when (rule.unit) {
            RepeatUnit.DAY -> {
                val days = ChronoUnit.DAYS.between(anchor.toLocalDate(), origin.toLocalDate())
                maxOf(1L, days / step)
            }

            RepeatUnit.WEEK -> if (rule.scheduledWeekDays.isEmpty()) {
                val weeks = ChronoUnit.WEEKS.between(anchor.toLocalDate(), origin.toLocalDate())
                maxOf(1L, weeks / step)
            } else {
                weekdayEstimate(rule, anchor, origin, step)
            }

            RepeatUnit.MONTH -> {
                val months = ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(origin))
                maxOf(1L, months.coerceAtLeast(0L) / step)
            }

            RepeatUnit.YEAR -> {
                val years = (origin.year - anchor.year).toLong().coerceAtLeast(0L)
                maxOf(1L, years / step)
            }
        }
    }

    /**
     * A series on chosen weekdays walks the days inside the repeating block: the week that contains
     * the anchor is block 0, and block n starts n * interval weeks later. So "every second week on
     * Mon, Wed, Fri" produces three instances per block and then jumps forward two weeks.
     */
    private fun weekdayOccurrence(
        anchor: ZonedDateTime,
        ordinal: Long,
        step: Long,
        weekdays: List<Int>,
        time: LocalTime,
        zone: ZoneId
    ): Long {
        val position = ordinal - 1
        val block = position / weekdays.size
        val dayOfWeek = weekdays[(position % weekdays.size).toInt()]
        val blockStart = anchor.toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(block * step)
        return at(blockStart.plusDays(dayOfWeek - 1L), time, zone)
    }

    private fun weekdayEstimate(
        rule: RecurrenceRule,
        anchor: ZonedDateTime,
        origin: ZonedDateTime,
        step: Long
    ): Long {
        val weekdays = rule.scheduledWeekDays
        val anchorBlock = anchor.toLocalDate().with(previousOrSameMonday())
        val originBlock = origin.toLocalDate().with(previousOrSameMonday())
        val weeksFromAnchor = ChronoUnit.WEEKS.between(anchorBlock, originBlock).coerceAtLeast(0L)
        return weeksFromAnchor / step * weekdays.size + 1
    }

    private fun previousOrSameMonday() = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)

    /** Attaches the series' wall-clock time to a local date, in the zone the series belongs to. */
    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
}
