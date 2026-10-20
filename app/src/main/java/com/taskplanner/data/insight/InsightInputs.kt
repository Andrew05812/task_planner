package com.taskplanner.data.insight

import com.taskplanner.data.model.EstimateComparison
import com.taskplanner.data.model.PriorityOverdueCounts
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * What the rules are handed to think about: figures already counted, in the units a reading states them in.
 *
 * This is the seam that keeps §7's recommendations testable. The rules take numbers and return claims, so
 * the minimum sample, the fact-versus-pattern distinction, and the silence of a rule that has not seen
 * enough can all be checked without a database, a context, or a string resource — and the repository that
 * fills these in is free to change how it reads the profile without changing what may be said about it.
 *
 * Two conversions happen on the way in, both deliberate. Seconds become whole minutes, because every
 * sentence an insight prints speaks in minutes and an insight that rounded at both ends would disagree with
 * the dashboard beside it. And the day bounds arrive as epoch days, derived by `java.time` from the zone the
 * reader is in rather than by arithmetic on milliseconds, so "the last seven days" means the seven days they
 * lived through, including the short and long ones at a daylight-saving change.
 */
data class InsightInputs(
    /** The day the pass runs on, and the two windows it counts over. */
    val today: Long,
    val weekFromDay: Long,
    val weekToDay: Long,
    val monthFromDay: Long,
    val monthToDay: Long,

    /** Open work, split by priority and by whether its deadline has passed. */
    val overdue: PriorityOverdueCounts = PriorityOverdueCounts(),

    /** Categories whose measured work this week is set against the week before it. */
    val categoryShifts: List<CategoryShift> = emptyList(),

    /** Finished tasks that carry an estimate, with the time actually measured on them. */
    val estimateComparisons: List<EstimateComparison> = emptyList(),

    /** When in the week and the day the profile's completions landed. */
    val completionTiming: CompletionTiming = CompletionTiming(),

    /** Each habit's own schedule, answered over the week the pass counted. */
    val habitWindows: List<HabitWindow> = emptyList(),

    /** Each dated goal's figures, with the days left to reach them in. */
    val goalFigures: List<GoalFigures> = emptyList(),

    /** What the days ahead are asked for, and what the days behind were actually worked. */
    val upcomingLoad: List<DayLoad> = emptyList(),
    val baselineDailyMinutes: Int = 0,
    val baselineMeasuredDays: Int = 0
)

/**
 * One category's measured work, this week against the week before.
 *
 * The two windows travel together because a share of growth is meaningless on its own: 300 minutes is a
 * doubling of a quiet week and a third of a busy one, and the reading the reader deserves names which.
 * [categoryName] is null for the bucket that holds work with no category at all, which no rule can offer as
 * a reading about a category — there is no screen for it to lead the reader to.
 */
data class CategoryShift(
    val categoryId: Long?,
    val categoryName: String?,
    val currentMinutes: Int,
    val previousMinutes: Int,
    val currentIntervals: Int,
    val previousIntervals: Int
)

/**
 * One habit's schedule answered over the window the pass counted.
 *
 * [asked] is the number of days the habit required, which is not the number of days that went by: a habit
 * begun on Wednesday has four days to be judged on, a weekday-only habit asks for none of its off-days, and
 * a rule that counted calendar days instead would call a two-day-old habit a missed week. [kept] is the days
 * carrying a live mark, so the misses are what the reader can check against the grid on the habit's screen.
 */
data class HabitWindow(
    val habitId: Long,
    val habitTitle: String,
    val asked: Int,
    val kept: Int
)

/**
 * One dated goal and the work still standing between it and its date.
 *
 * The figures are the goal's own, recomputed at the moment of the read — [target] and [counted] are what the
 * goals screen prints, and [remaining] is the difference the pace is worked out from. [asksTasks] says which
 * unit the numbers are in, because "six left" and "three hours left" are two different sentences and a rule
 * that printed the wrong one would be a claim about a goal the reader never set.
 */
data class GoalFigures(
    val goalId: Long,
    val title: String,
    val asksTasks: Boolean,
    val remaining: Int,
    val target: Int,
    val counted: Int,
    val daysLeft: Int,
    val deadlineDay: Long
)

/**
 * When the profile's completions landed, sorted by weekday and by hour of day.
 *
 * Built from the instants the tasks carry, read in the zone the reader is in — the reason the bucketing is
 * done here rather than by dividing milliseconds in SQL is that a completion at 00:20 local belongs to the
 * day the user finished it on, across a daylight-saving boundary, and no fixed-length division says so.
 */
data class CompletionTiming(
    val total: Int = 0,
    val byWeekday: Map<DayOfWeek, Int> = emptyMap(),
    val byHour: Map<Int, Int> = emptyMap()
) {
    companion object {
        /** The timing of a set of completion instants, as the reader's own calendar divides them. */
        fun of(instants: List<Long>, zone: ZoneId): CompletionTiming {
            val byWeekday = mutableMapOf<DayOfWeek, Int>()
            val byHour = mutableMapOf<Int, Int>()
            instants.forEach { instant ->
                val moment = Instant.ofEpochMilli(instant).atZone(zone)
                byWeekday[moment.dayOfWeek] = (byWeekday[moment.dayOfWeek] ?: 0) + 1
                byHour[moment.hour] = (byHour[moment.hour] ?: 0) + 1
            }
            return CompletionTiming(instants.size, byWeekday, byHour)
        }
    }
}
