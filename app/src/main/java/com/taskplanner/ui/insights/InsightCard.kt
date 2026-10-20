package com.taskplanner.ui.insights

import android.content.Context
import androidx.annotation.StringRes
import com.taskplanner.R
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.tracking.WorkTimeMath
import java.text.DateFormat
import java.time.ZoneId
import java.util.Locale

/**
 * Where an insight's action takes the reader, in the terms the screen navigates on.
 *
 * The row stores its target as an enum name and a string, because an insight is derived data and the pair has
 * to survive being written before the row it points at is understood. A screen cannot navigate on text, so
 * this is the one place that turns the pair back into an address — and the one place that decides the address
 * is not there: [targetOf] returns null for a reference this build cannot read as a number, which leaves the
 * card without a button rather than sending the reader to row zero.
 *
 * A day is kept as an epoch day plus "the whole week or just the day", rather than as a calendar screen mode,
 * so the insights screen does not have to know how the calendar names its own zoom levels.
 */
sealed interface InsightTarget {
    data class Task(val taskId: Long) : InsightTarget
    data class Goal(val goalId: Long) : InsightTarget
    data class Habit(val habitId: Long) : InsightTarget
    data class Category(val categoryId: Long) : InsightTarget
    data class Day(val epochDay: Long, val wholeWeek: Boolean) : InsightTarget
}

/**
 * The words one insight card prints, decided in one place.
 *
 * The three sentences that carry a reading — title, body, basis — arrive from the repository already written,
 * and this object deliberately does not touch them: a card that reformatted from the rule key would print
 * yesterday's reading in today's phrasing, which is exactly what storing the text was meant to prevent. What
 * is left for the screen to say is the framing around those sentences: what kind of claim it is, over what
 * window, with how much evidence, and where the reader can go to check it.
 *
 * Two rules the wording keeps:
 *
 *  * **The three kinds are named, not implied.** §7 asks the reader to be told whether they are holding a
 *    count, a regularity or a guess about what the rows point at, because the three are answered differently:
 *    a fact can be checked, a pattern can be doubted on its sample, a hypothesis can only be agreed with.
 *  * **A number without its base is not printed.** Confidence and sample size travel with the row that
 *    computed them, and a card that showed "80% sure" beside one row counted would be the app sounding more
 *    certain of the profile than the profile has told it.
 */
object InsightCard {

    /** The claim's reach, in the words §7 asks the screen to use. */
    @StringRes
    fun kindLabelRes(kind: InsightKind): Int = when (kind) {
        InsightKind.FACT -> R.string.insight_kind_fact
        InsightKind.PATTERN -> R.string.insight_kind_pattern
        InsightKind.HYPOTHESIS -> R.string.insight_kind_hypothesis
    }

    /**
     * The window, the confidence and the sample, as one line under the basis.
     *
     * A one-day reading prints its one date rather than a range of the same day twice: the heavy-day rule
     * counts a single Thursday, and "Jun 4 – Jun 4" reads like a field nobody filled in properly.
     */
    fun meta(context: Context, insight: Insight): String {
        val period = if (insight.periodFromDay == insight.periodToDay) {
            context.getString(R.string.insight_card_period_single, dayText(insight.periodFromDay))
        } else {
            context.getString(
                R.string.insight_card_period_range,
                dayText(insight.periodFromDay),
                dayText(insight.periodToDay)
            )
        }
        return listOf(
            period,
            context.getString(R.string.insight_card_confidence, insight.confidence),
            context.resources.getQuantityString(
                R.plurals.insight_card_sample,
                insight.sampleSize,
                insight.sampleSize
            )
        ).joinToString(context.getString(R.string.insight_card_meta_joiner))
    }

    /** The label on the button that goes there, or null when the reading leads nowhere. */
    @StringRes
    fun actionLabelRes(action: InsightAction?): Int? = when (action) {
        null -> null
        InsightAction.TASK -> R.string.insight_action_task
        InsightAction.DAY -> R.string.insight_action_day
        InsightAction.CALENDAR_WEEK -> R.string.insight_action_week
        InsightAction.GOAL -> R.string.insight_action_goal
        InsightAction.HABIT -> R.string.insight_action_habit
        InsightAction.CATEGORY -> R.string.insight_action_category
    }

    /**
     * The address the action names, or null when it names none.
     *
     * A target the card cannot resolve is treated as no target at all. The alternative — navigating on a
     * reference that does not parse — opens a screen about the row with id 0, which is a wrong answer presented
     * as a working button; a card with nothing to open is only an incomplete one.
     */
    fun targetOf(insight: Insight): InsightTarget? {
        val ref = insight.actionRef?.toLongOrNull() ?: return null
        return when (insight.actionType) {
            InsightAction.TASK -> InsightTarget.Task(ref)
            InsightAction.GOAL -> InsightTarget.Goal(ref)
            InsightAction.HABIT -> InsightTarget.Habit(ref)
            InsightAction.CATEGORY -> InsightTarget.Category(ref)
            InsightAction.DAY -> InsightTarget.Day(ref, wholeWeek = false)
            InsightAction.CALENDAR_WEEK -> InsightTarget.Day(ref, wholeWeek = true)
            null -> null
        }
    }

    /**
     * The sentence for a list with nothing in it.
     *
     * The two sides of the switch are empty for opposite reasons and have to be said differently: the live
     * list is empty because the newest pass found nothing to say, while the history is empty because nothing
     * the app once said has since been dropped. Printing the first sentence on the second screen would tell
     * a reader who retired a card that they had never had one.
     */
    @StringRes
    fun emptyTextRes(showHistory: Boolean): Int =
        if (showHistory) R.string.insights_empty_history else R.string.insights_empty_current

    private fun dayText(epochDay: Long): String = DateFormat
        .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        .format(
            WorkTimeMath.toDate(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
}
