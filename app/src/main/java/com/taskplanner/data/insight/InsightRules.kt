package com.taskplanner.data.insight

import com.taskplanner.data.model.EstimateComparison
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The readings the app is allowed to make about a profile, as arithmetic.
 *
 * Each rule answers one question about the numbers it is handed and stays quiet otherwise, and the three
 * decisions §7 asks for live here rather than in a screen:
 *
 *  * **The minimum sample is silence, not low confidence.** A rule that has not seen enough rows returns no
 *    finding at all. The alternative — the same sentence with "60%" printed beside it — puts a claim in
 *    front of the reader that the profile cannot support, and a percentage nobody can check is decoration.
 *  * **The kind follows the evidence.** A count over rows that exist now is a [InsightKind.FACT]; a
 *    regularity spread over a stretch of days is a [InsightKind.PATTERN]; a reading of what the rows point
 *    at, where the days in question are ahead of the reader rather than behind them, is a
 *    [InsightKind.HYPOTHESIS]. The three cannot be confused here because each rule is written against the
 *    shape of what it counts.
 *  * **Confidence is derived from the sample and never reaches certainty.** [FACT_CONFIDENCE] is the ceiling
 *    a count may approach, patterns and readings sit strictly under it, and no formula in this file can
 *    return 100 — a rule that could be certain of a handful of rows would be claiming more than the rows
 *    were ever asked to hold.
 *
 * Nothing in here touches Android, a database, or a string resource: [InsightInputs] in, [Finding]s out. The
 * repository decides *which* numbers to count and puts words on the result; the rules decide what may be
 * said about them.
 */
object InsightRules {

    /** The confidence a count may reach, and the ceiling every other kind of claim is held below. */
    const val FACT_CONFIDENCE = 95

    // -- the smallest profile each rule will speak about -------------------------------------------

    /** One overdue high-priority task is a task; two are the beginning of a pile worth naming. */
    private const val MIN_OVERDUE_HIGH = 2

    /** A category week needs intervals on both sides of the comparison to be compared at all. */
    private const val MIN_CATEGORY_INTERVALS_PER_WEEK = 2
    private const val MIN_CATEGORY_INTERVALS_TOTAL = 6

    /** Growth under a fifth of a quiet week is the difference between two weeks, not a trend. */
    private const val MIN_CATEGORY_GROWTH_PERCENT = 20

    /** Two estimates are a coincidence; three is the smallest set that can show a habit. */
    private const val MIN_ESTIMATES_COMPARED = 3

    /** A majority of the estimated tasks has to have overran before the word "underestimate" is used. */
    private const val MIN_ESTIMATE_GAP_PERCENT = 60

    /** Ten completions is the point where the shape of a week or a day stops being noise. */
    private const val MIN_TIMING_TOTAL = 10
    private const val MIN_WEEKDAY_SHARE_PERCENT = 40
    private const val MIN_HOURS_SHARE_PERCENT = 50

    /** A habit asked for on fewer than four days has no week to be missed in. */
    private const val MIN_HABIT_ASKED_DAYS = 4
    private const val MIN_HABIT_MISSED_DAYS = 2

    /** A pace needs at least two days to be a pace rather than a to-do list. */
    private const val MIN_GOAL_DAYS_LEFT = 2

    /** A heavy day is heavy *against* something; without measured days behind it there is nothing to
     * compare to, and no amount of minutes on tomorrow makes a reading. */
    private const val MIN_BASELINE_DAYS = 2
    private const val MIN_BASELINE_MINUTES = 1

    /** The floor a crowded day has to clear against the profile's own average before it is called heavy. */
    private const val MIN_HEAVY_MINUTES = 30
    private const val MIN_PERCENT_OF_BASELINE = 150

    /**
     * Every reading this profile supports, most checkable first.
     *
     * Deterministic by construction: each rule sorts its own candidates rather than trusting the order rows
     * came back in, and the rules run in a fixed sequence. A second pass over the same numbers returns the
     * same list, which is what lets the repository land on the rows it wrote last time instead of laying a
     * second copy beside them.
     */
    fun evaluate(inputs: InsightInputs): List<Finding> = buildList {
        highPriorityOverload(inputs)?.let(::add)
        addAll(categoryGrowth(inputs))
        estimateGap(inputs)?.let(::add)
        completionWeekday(inputs)?.let(::add)
        completionHours(inputs)?.let(::add)
        addAll(habitMisses(inputs))
        addAll(goalPaces(inputs))
        heavyDay(inputs)?.let(::add)
    }

    // -- the rules -------------------------------------------------------------------------------

    /**
     * High-priority work sitting past its deadline, counted over the open rows as they are right now.
     *
     * A fact in the plainest sense: every number is a row the reader can open. The window is one day because
     * that is what the count is over — "as of today" — and the reading leads to the week rather than to one
     * task, since the point of the pile is that it is a pile.
     */
    private fun highPriorityOverload(inputs: InsightInputs): Finding? {
        val counts = inputs.overdue
        if (counts.highOverdue < MIN_OVERDUE_HIGH) return null
        return Finding(
            ruleKey = "high-priority-overload",
            kind = InsightKind.FACT,
            periodFromDay = inputs.today,
            periodToDay = inputs.today,
            sampleSize = counts.highOverdue,
            confidence = factConfidence(counts.highOverdue),
            actionType = InsightAction.CALENDAR_WEEK,
            actionRef = inputs.today.toString(),
            evidence = FindingEvidence.HighPriorityOverload(
                highOverdue = counts.highOverdue,
                overdue = counts.overdue,
                open = counts.open
            )
        )
    }

    /**
     * A category whose measured time grew on the strength of measurements taken in both weeks.
     *
     * Both bounds are half-open week windows the caller counted, so the reading is about the reader's own
     * calendar rather than about two arbitrary stretches of milliseconds. The bucket holding work with no
     * category is dropped: there is no category screen to lead the reader to, and "your uncategorised work
     * grew" is a claim about the app's filing, not about their week.
     *
     * Ordered by the share that grew, biggest first, because the pass may raise several and the reader
     * deserves to meet the loudest one at the top of the list.
     */
    private fun categoryGrowth(inputs: InsightInputs): List<Finding> =
        inputs.categoryShifts
            .mapNotNull { shift ->
                val id = shift.categoryId ?: return@mapNotNull null
                val name = shift.categoryName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (shift.currentIntervals < MIN_CATEGORY_INTERVALS_PER_WEEK) return@mapNotNull null
                if (shift.previousIntervals < MIN_CATEGORY_INTERVALS_PER_WEEK) return@mapNotNull null
                val sample = shift.currentIntervals + shift.previousIntervals
                if (sample < MIN_CATEGORY_INTERVALS_TOTAL) return@mapNotNull null
                if (shift.previousMinutes <= 0) return@mapNotNull null
                if (shift.currentMinutes <= shift.previousMinutes) return@mapNotNull null
                val growth = (shift.currentMinutes - shift.previousMinutes) * 100 / shift.previousMinutes
                if (growth < MIN_CATEGORY_GROWTH_PERCENT) return@mapNotNull null
                GrewCategory(id, name, shift, growth)
            }
            .sortedWith(compareByDescending<GrewCategory> { it.growthPercent }.thenBy { it.categoryId })
            .map { grew ->
                val shift = grew.shift
                val sample = shift.currentIntervals + shift.previousIntervals
                Finding(
                    ruleKey = "category-growth-${grew.categoryId}",
                    kind = InsightKind.PATTERN,
                    periodFromDay = inputs.weekFromDay,
                    periodToDay = inputs.weekToDay,
                    sampleSize = sample,
                    confidence = patternConfidence(sample),
                    actionType = InsightAction.CATEGORY,
                    actionRef = grew.categoryId.toString(),
                    evidence = FindingEvidence.CategoryGrowth(
                        categoryId = grew.categoryId,
                        categoryName = grew.name,
                        currentMinutes = shift.currentMinutes,
                        previousMinutes = shift.previousMinutes,
                        growthPercent = grew.growthPercent,
                        currentIntervals = shift.currentIntervals,
                        previousIntervals = shift.previousIntervals
                    )
                )
            }

    /**
     * Finished work that took longer than the plan said, against the tasks that were planned at all.
     *
     * Intervals never measured are not evidence of anything, so a completion with no measured seconds leaves
     * the sample rather than counting as a zero-length task — the same reason a zero estimate is read as the
     * absence of a plan rather than a plan of no time. The action names the task that missed by the most,
     * because "you tend to underestimate" is only checkable through one real example.
     */
    private fun estimateGap(inputs: InsightInputs): Finding? {
        val measured = inputs.estimateComparisons.filter { it.measuredSeconds > 0L }
        if (measured.size < MIN_ESTIMATES_COMPARED) return null
        val overruns = measured.filter { it.measuredMinutes > it.estimatedMinutes }
        if (overruns.isEmpty()) return null
        if (overruns.size * 100 < measured.size * MIN_ESTIMATE_GAP_PERCENT) return null
        // Earliest read wins a tie, so the same rows always point at the same example.
        val worst = overruns
            .sortedWith(compareByDescending<EstimateComparison> { overrunRatio(it) }.thenBy { it.taskId })
            .first()
        return Finding(
            ruleKey = "estimate-gap",
            kind = InsightKind.PATTERN,
            periodFromDay = inputs.monthFromDay,
            periodToDay = inputs.monthToDay,
            sampleSize = measured.size,
            confidence = patternConfidence(measured.size),
            actionType = InsightAction.TASK,
            actionRef = worst.taskId.toString(),
            evidence = FindingEvidence.EstimateGap(
                over = overruns.size,
                compared = measured.size,
                taskId = worst.taskId,
                taskTitle = worst.title,
                estimatedMinutes = worst.estimatedMinutes,
                measuredMinutes = worst.measuredMinutes
            )
        )
    }

    /** Completions gathered on one weekday, over the month the reader has been finishing things in. */
    private fun completionWeekday(inputs: InsightInputs): Finding? {
        val timing = inputs.completionTiming
        if (timing.total < MIN_TIMING_TOTAL) return null
        // Largest bucket first, and the weekday's own order breaks a tie, so two passes over one set of rows
        // name the same day rather than whichever the query happened to return first.
        val busiest = timing.byWeekday.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<DayOfWeek, Int>> { it.value }.thenBy { it.key.value })
            .firstOrNull()
            ?: return null
        val share = busiest.value * 100 / timing.total
        if (share < MIN_WEEKDAY_SHARE_PERCENT) return null
        return Finding(
            ruleKey = "completion-weekday",
            kind = InsightKind.PATTERN,
            periodFromDay = inputs.monthFromDay,
            periodToDay = inputs.monthToDay,
            sampleSize = timing.total,
            confidence = patternConfidence(timing.total),
            // The reading is about a weekday that recurs across the window, so no single day opens it — the
            // honest action for a pattern like this is no action.
            actionType = null,
            actionRef = null,
            evidence = FindingEvidence.CompletionWeekday(
                weekday = busiest.key,
                count = busiest.value,
                total = timing.total,
                sharePercent = share
            )
        )
    }

    /**
     * The two hours of the day most of the completions land in.
     *
     * Read as a span rather than a single hour because the hour a task was *marked* done in is a tap away
     * from the hour the work stopped, and a rule that named one exact hour would be reading more precision
     * out of the rows than they hold. Two neighbouring hours are the smallest claim that survives that.
     */
    private fun completionHours(inputs: InsightInputs): Finding? {
        val timing = inputs.completionTiming
        if (timing.total < MIN_TIMING_TOTAL) return null
        val spans = (0..22).map { hour ->
            hour to ((timing.byHour[hour] ?: 0) + (timing.byHour[hour + 1] ?: 0))
        }
        val busiest = spans
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .firstOrNull()
            ?: return null
        val share = busiest.second * 100 / timing.total
        if (share < MIN_HOURS_SHARE_PERCENT) return null
        return Finding(
            ruleKey = "completion-hours",
            kind = InsightKind.PATTERN,
            periodFromDay = inputs.monthFromDay,
            periodToDay = inputs.monthToDay,
            sampleSize = timing.total,
            confidence = patternConfidence(timing.total),
            actionType = null,
            actionRef = null,
            evidence = FindingEvidence.CompletionHours(
                fromHour = busiest.first,
                count = busiest.second,
                total = timing.total,
                sharePercent = share
            )
        )
    }

    /**
     * A habit's week, as its own schedule asked for it.
     *
     * [HabitWindow.asked] is what the frequency required inside the window rather than the number of days
     * that went by, which is what keeps a habit begun on Thursday from being reported as a week of misses.
     * One finding per habit, because the reader can keep one habit and drop another — the readings have to
     * stay answerable separately, and the key names which habit each one is about.
     */
    private fun habitMisses(inputs: InsightInputs): List<Finding> =
        inputs.habitWindows
            .filter { it.habitId > 0L && it.asked >= MIN_HABIT_ASKED_DAYS }
            .map { it to (it.asked - it.kept) }
            .filter { it.second >= MIN_HABIT_MISSED_DAYS }
            .sortedBy { it.first.habitId }
            .map { (window, missed) ->
                Finding(
                    ruleKey = "habit-miss-${window.habitId}",
                    kind = InsightKind.FACT,
                    periodFromDay = inputs.weekFromDay,
                    periodToDay = inputs.weekToDay,
                    sampleSize = window.asked,
                    confidence = factConfidence(window.asked),
                    actionType = InsightAction.HABIT,
                    actionRef = window.habitId.toString(),
                    evidence = FindingEvidence.HabitMiss(
                        habitId = window.habitId,
                        habitTitle = window.habitTitle,
                        missed = missed,
                        asked = window.asked,
                        kept = window.kept
                    )
                )
            }

    /**
     * What the days a goal has left would have to hold for it to be reached.
     *
     * A hypothesis in the strict sense: the deadline is in the reader's future, and the only thing in the
     * database is what has been counted so far. The pace is rounded *up* because a fraction is not
     * something a person can do — "1.2 tasks a day" asks the reader to invent a unit — and a goal already
     * reached, or due inside a single day, has no pace to read off.
     */
    private fun goalPaces(inputs: InsightInputs): List<Finding> =
        inputs.goalFigures
            .filter { it.goalId > 0L && it.remaining > 0 && it.daysLeft >= MIN_GOAL_DAYS_LEFT }
            .filter { it.deadlineDay >= inputs.today }
            .sortedBy { it.goalId }
            .map { goal ->
                val perDay = (goal.remaining + goal.daysLeft - 1) / goal.daysLeft
                Finding(
                    ruleKey = "goal-pace-${goal.goalId}",
                    kind = InsightKind.HYPOTHESIS,
                    periodFromDay = inputs.today,
                    periodToDay = goal.deadlineDay,
                    sampleSize = goal.daysLeft,
                    confidence = hypothesisConfidence(goal.daysLeft),
                    actionType = InsightAction.GOAL,
                    actionRef = goal.goalId.toString(),
                    evidence = FindingEvidence.GoalPace(
                        goalId = goal.goalId,
                        title = goal.title,
                        asksTasks = goal.asksTasks,
                        remaining = goal.remaining,
                        perDay = perDay,
                        target = goal.target,
                        counted = goal.counted,
                        daysLeft = goal.daysLeft
                    )
                )
            }

    /**
     * The heaviest day ahead, measured against the days behind.
     *
     * One reading per pass rather than one per crowded day, because the screen would otherwise turn a
     * question about the reader's week into a list. The heaviest day is the one offered; the others stay in
     * the workload panel the reading points at.
     *
     * [FindingEvidence.HeavyDay.approximate] carries straight through from [DayLoad]: where the minutes came
     * from estimates rather than stated blocks, §8 requires the caveat to be part of the claim rather than an
     * apology in the footer.
     */
    private fun heavyDay(inputs: InsightInputs): Finding? {
        if (inputs.baselineMeasuredDays < MIN_BASELINE_DAYS) return null
        if (inputs.baselineDailyMinutes < MIN_BASELINE_MINUTES) return null
        val heaviest = inputs.upcomingLoad
            .filter { it.minutes >= MIN_HEAVY_MINUTES }
            .map { it to it.minutes * 100 / inputs.baselineDailyMinutes }
            .filter { it.second >= MIN_PERCENT_OF_BASELINE }
            .sortedWith(compareByDescending<Pair<DayLoad, Int>> { it.first.minutes }.thenBy { it.first.epochDay })
            .firstOrNull()
            ?.let { it.first to it.second }
            ?: return null
        val (day, percent) = heaviest
        return Finding(
            ruleKey = "heavy-day-${day.epochDay}",
            kind = InsightKind.HYPOTHESIS,
            periodFromDay = inputs.today,
            periodToDay = inputs.upcomingLoad.maxOf { it.epochDay },
            sampleSize = inputs.baselineMeasuredDays,
            confidence = hypothesisConfidence(inputs.baselineMeasuredDays),
            actionType = InsightAction.DAY,
            actionRef = day.epochDay.toString(),
            evidence = FindingEvidence.HeavyDay(
                epochDay = day.epochDay,
                weekday = LocalDate.ofEpochDay(day.epochDay).dayOfWeek,
                minutes = day.minutes,
                tasks = day.tasks,
                baselineMinutes = inputs.baselineDailyMinutes,
                percentOfBaseline = percent,
                daysOut = (day.epochDay - inputs.today).toInt(),
                approximate = day.approximate
            )
        )
    }

    // -- the arithmetic the rules share --------------------------------------------------------------

    /**
     * A count, stated as confidently as a count can be and never as certainty.
     *
     * Rises with the number of rows behind it and approaches [FACT_CONFIDENCE] from below without reaching
     * it: three overdue tasks are a fact, and a hundred of them are the same fact with more weight, but
     * nothing that was counted by this app from a handful of rows is worth 100%.
     */
    private fun factConfidence(sampleSize: Int): Int =
        (70 + sampleSize * 29 / (sampleSize + 10)).coerceIn(1, 99)

    /** A regularity is worth less than a count, and what it is worth depends on how many rows it took. */
    private fun patternConfidence(sampleSize: Int): Int =
        (55 + sampleSize * 39 / (sampleSize + 30)).coerceIn(1, FACT_CONFIDENCE - 1)

    /** A reading of days that have not happened yet is held the furthest from certainty of the three. */
    private fun hypothesisConfidence(sampleSize: Int): Int =
        (40 + sampleSize * 54 / (sampleSize + 25)).coerceIn(1, 90)

    /** How many times the measured time went past the estimate, as whole percent — the worst gap's size. */
    private fun overrunRatio(comparison: EstimateComparison): Int =
        comparison.measuredMinutes * 100 / comparison.estimatedMinutes
}

/**
 * A category that cleared every gate, with its key and name resolved.
 *
 * [CategoryShift] carries a nullable id because one of its buckets is "no category at all". The gate drops
 * that bucket, and holding the resolved id here means the finding downstream cannot be built from a null
 * it would have to guess its way past.
 */
private data class GrewCategory(
    val categoryId: Long,
    val name: String,
    val shift: CategoryShift,
    val growthPercent: Int
)
