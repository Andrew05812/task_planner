package com.taskplanner.data.insight

import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import java.time.DayOfWeek

/**
 * What a rule concluded, before anybody put words on it.
 *
 * The finding carries the three things §7 asks a recommendation to state — the kind of claim it is, the
 * window it was counted over, and how much of the profile stood behind it — and none of the sentences. That
 * split is what makes a rule testable as arithmetic and what lets the same finding be printed in another
 * language without another rule: the numbers are the reading, the words are a rendering of it.
 *
 * [ruleKey] names the rule and, where a rule speaks about one thing in particular, the thing it spoke about.
 * It is not display text and it is never edited: it is the address a rejected answer retires on, and the
 * reason two passes over one week reach one row rather than two.
 */
data class Finding(
    val ruleKey: String,
    val kind: InsightKind,
    val periodFromDay: Long,
    val periodToDay: Long,
    val sampleSize: Int,
    val confidence: Int,
    val actionType: InsightAction?,
    val actionRef: String?,
    val evidence: FindingEvidence
) {
    /** The row this reading belongs on: one rule, one account, one window. */
    fun uuid(userId: Long): String = Insight.uuidFor(userId, ruleKey, periodFromDay, periodToDay)
}

/**
 * The numbers behind a finding, named rather than positional.
 *
 * A list of formatted strings would put the wording in the rules and leave the screen to guess which number
 * was which; a bag of unnamed ints would do the same in reverse. One typed case per rule means the renderer
 * reads `evidence.missed` and a test can assert the same field, and a rule that changes what it counts has to
 * say so in a type rather than silently shifting a placeholder down a sentence.
 */
sealed class FindingEvidence {

    /** A count over the rows that are open right now. */
    data class HighPriorityOverload(
        val highOverdue: Int,
        val overdue: Int,
        val open: Int
    ) : FindingEvidence()

    /** One category's measured work, this stretch against the one before it. */
    data class CategoryGrowth(
        val categoryId: Long,
        val categoryName: String,
        val currentMinutes: Int,
        val previousMinutes: Int,
        val growthPercent: Int,
        val currentIntervals: Int,
        val previousIntervals: Int
    ) : FindingEvidence()

    /** The tasks that were estimated, how many of them overran, and the one that overran by the most. */
    data class EstimateGap(
        val over: Int,
        val compared: Int,
        val taskId: Long,
        val taskTitle: String,
        val estimatedMinutes: Int,
        val measuredMinutes: Int
    ) : FindingEvidence()

    /** Completions sorted by the weekday they landed on. */
    data class CompletionWeekday(
        val weekday: DayOfWeek,
        val count: Int,
        val total: Int,
        val sharePercent: Int
    ) : FindingEvidence()

    /** Completions sorted by the hour of day they landed on; the span is two hours from [fromHour]. */
    data class CompletionHours(
        val fromHour: Int,
        val count: Int,
        val total: Int,
        val sharePercent: Int
    ) : FindingEvidence()

    /** One habit's week, as its own schedule asked for it and as the marks answered. */
    data class HabitMiss(
        val habitId: Long,
        val habitTitle: String,
        val missed: Int,
        val asked: Int,
        val kept: Int
    ) : FindingEvidence()

    /** One dated goal, what is left of it, and the pace the remaining days would ask for. */
    data class GoalPace(
        val goalId: Long,
        val title: String,
        val asksTasks: Boolean,
        val remaining: Int,
        val perDay: Int,
        val target: Int,
        val counted: Int,
        val daysLeft: Int
    ) : FindingEvidence()

    /** A day ahead that asks for more than the profile has been measuring on a working day. */
    data class HeavyDay(
        val epochDay: Long,
        val weekday: DayOfWeek,
        val minutes: Int,
        val tasks: Int,
        val baselineMinutes: Int,
        val percentOfBaseline: Int,
        val daysOut: Int,
        /**
         * Whether the minutes rest on estimates rather than on stated blocks.
         *
         * §8 asks the screen to say so out loud: a day built from deadlines and guessed durations is a
         * reading of the plan, not a measurement of it, and a card that did not distinguish the two would be
         * the app inventing a fact about a day that has not happened.
         */
        val approximate: Boolean
    ) : FindingEvidence()
}
