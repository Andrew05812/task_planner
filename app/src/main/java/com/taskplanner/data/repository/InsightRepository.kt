package com.taskplanner.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.insight.CategoryShift
import com.taskplanner.data.insight.CompletionTiming
import com.taskplanner.data.insight.Finding
import com.taskplanner.data.insight.FindingEvidence
import com.taskplanner.data.insight.GoalFigures
import com.taskplanner.data.insight.HabitWindow
import com.taskplanner.data.insight.InsightInputs
import com.taskplanner.data.insight.InsightRules
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.GoalDao
import com.taskplanner.data.local.HabitDao
import com.taskplanner.data.local.InsightDao
import com.taskplanner.data.local.TaskStatisticsDao
import com.taskplanner.data.local.WorkStatisticsDao
import com.taskplanner.data.model.CategoryWorkSummary
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTimeMath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * What one pass of the rules did, as counts rather than as a log.
 *
 * [considered] is what the rules found before the reader's answers were consulted, and [silenced] is how many
 * of those the reader had already said something about — the difference between "nothing was true of this
 * profile" and "this profile has told the app to stop saying it", which a screen and a bug report both have
 * to be able to make. [retired] counts the readings the pass stopped finding, and [forgotten] the history the
 * notebook gave up on.
 */
data class InsightPass(
    val considered: Int = 0,
    val raised: Int = 0,
    val refreshed: Int = 0,
    val silenced: Int = 0,
    val retired: Int = 0,
    val forgotten: Int = 0
)

/**
 * The notebook of readings: it derives them, states each one once per window, and remembers what the reader said.
 *
 * Four rules shape the class, and every one of them is a §7 or §8 requirement rather than a taste:
 *
 *  * **The repository reads, the rules decide, the strings finish the sentence.** Figures come out of narrow
 *    aggregates, [InsightRules] turns them into typed findings, and only then are words put on them from
 *    `res/values/strings.xml`. A rule that returned a sentence could not be tested as arithmetic, and a
 *    finding whose wording was fixed inside the rules could not be re-stated when the app's phrasing changed
 *    — which is precisely what a stored history has to survive. The one figure that arrives as a whole read
 *    rather than as an aggregate is the workload: it comes from [WorkloadRepository], the same fortnight the
 *    overload panel shows, so a day cannot be crowded on one screen and ordinary on the next.
 *  * **One rule, one window, one row.** A finding's uuid is derived from the account, the rule key and the
 *    window, so a pass that finds the same thing again reaches the row it wrote last time: the reading keeps
 *    the day it first arrived and the answer the reader gave, and the screen never shows one observation
 *    twice. A row the reader answered to a stop is not re-stated at all.
 *  * **Nothing here writes to the work.** §8 forbids automatic rescheduling and automatic priority changes,
 *    and the strongest guarantee available is structural: this class issues no update against `tasks`,
 *    `goals`, `habits`, or their marks — only reads, and writes to its own table. `InsightRepositoryTest`
 *    holds it to that by comparing a signature of the profile's work rows across a pass.
 *  * **A reading is derived, so it may be let go.** Retired insights expire on their own; the only things the
 *    notebook keeps that no rule could rebuild are when a reading first arrived and what the reader said
 *    about it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class InsightRepository @Inject constructor(
    private val database: AppDatabase,
    private val insightDao: InsightDao,
    private val workloadRepository: WorkloadRepository,
    private val taskStatisticsDao: TaskStatisticsDao,
    private val workStatisticsDao: WorkStatisticsDao,
    private val goalDao: GoalDao,
    private val habitDao: HabitDao,
    private val habitRepository: HabitRepository,
    private val goalRepository: GoalRepository,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase,
    @ApplicationContext private val context: Context
) {

    /**
     * Runs the rules over the profile and writes down what they found.
     *
     * The reads happen before the transaction and only the writes inside it: an aggregate over the profile's
     * rows is not something to hold a write lock through, and a pass that read and wrote as one statement
     * would make the user's own writes queue behind the analysis.
     *
     * A signed-out profile is not an error but a nothing — no rows belong to anybody, so there is nothing to
     * read and nothing to write, and the pass reports itself as having considered none of the rules.
     */
    suspend fun refresh(): InsightPass {
        val userId = sessionRepository.currentUserId() ?: return InsightPass()
        val zone = CalendarMath.systemZone()
        val passStartedAt = timeBase.wallMillis()
        val today = WorkTimeMath.epochDayOf(passStartedAt, zone)
        val findings = InsightRules.evaluate(collectInputs(userId, today, zone, passStartedAt))
        // A rule the reader refused stays quiet for a stretch — see [InsightDao.rejectedRuleKeys].
        val refused = insightDao
            .rejectedRuleKeys(userId, passStartedAt - REJECTION_SILENCE_MILLIS)
            .toSet()
        var raised = 0
        var refreshed = 0
        var silenced = 0
        var retired = 0
        var forgotten = 0
        database.withTransaction {
            findings.forEach { finding ->
                val uuid = finding.uuid(userId)
                val existing = insightDao.getByUuid(uuid)
                val answered = existing?.feedback == InsightFeedback.REJECTED ||
                    existing?.feedback == InsightFeedback.HIDDEN
                if (answered || finding.ruleKey in refused) {
                    silenced++
                    return@forEach
                }
                val wording = wordingOf(finding)
                if (existing == null) {
                    insightDao.insert(
                        Insight(
                            uuid = uuid,
                            userId = userId,
                            ruleKey = finding.ruleKey,
                            kind = finding.kind,
                            title = wording.title,
                            body = wording.body,
                            basis = wording.basis,
                            periodFromDay = finding.periodFromDay,
                            periodToDay = finding.periodToDay,
                            sampleSize = finding.sampleSize,
                            confidence = finding.confidence,
                            actionType = finding.actionType,
                            actionRef = finding.actionRef,
                            firstSeenAt = passStartedAt,
                            lastSeenAt = passStartedAt,
                            isCurrent = true,
                            createdAt = passStartedAt,
                            updatedAt = passStartedAt
                        )
                    )
                    raised++
                } else {
                    insightDao.refresh(
                        uuid = uuid,
                        kind = finding.kind,
                        title = wording.title,
                        body = wording.body,
                        basis = wording.basis,
                        sampleSize = finding.sampleSize,
                        confidence = finding.confidence,
                        actionType = finding.actionType,
                        actionRef = finding.actionRef,
                        lastSeenAt = passStartedAt
                    )
                    refreshed++
                }
            }
            // Everything this pass did not touch stops being current. The reading is still true of the window
            // it was counted over, so it goes behind the live list rather than out of the notebook.
            retired = insightDao.markStale(userId, passStartedAt, passStartedAt)
            forgotten = insightDao.forgetUntouchedHistoryBefore(userId, today - HISTORY_RETENTION_DAYS)
        }
        return InsightPass(
            considered = findings.size,
            raised = raised,
            refreshed = refreshed,
            silenced = silenced,
            retired = retired,
            forgotten = forgotten
        )
    }

    /** The readings the newest pass still stands behind, empty while no profile is signed in. */
    fun observeCurrent(): Flow<List<Insight>> = forProfile { insightDao.observeCurrent(it) }

    /** What the app said about windows it no longer stands behind. */
    fun observeHistory(): Flow<List<Insight>> = forProfile { insightDao.observeHistory(it) }

    /**
     * Records what the reader said about one insight, and whether there was anything here to say it about.
     *
     * The write is keyed on the uuid *and* the signed-in profile: a tap that arrives after the session has
     * moved must not answer somebody else's card, and the row count is what turns "recorded" into "there was
     * nothing here". Whether the reading then leaves the offered list is decided in SQL by
     * [InsightDao.observeCurrent], so the answer takes effect in the same frame it was given.
     */
    suspend fun giveFeedback(uuid: String, feedback: InsightFeedback): Boolean {
        val userId = sessionRepository.currentUserId() ?: return false
        return insightDao.setFeedback(uuid, userId, feedback, timeBase.wallMillis()) > 0
    }

    // -- the figures the rules are handed -------------------------------------------------------

    private suspend fun collectInputs(userId: Long, today: Long, zone: ZoneId, now: Long): InsightInputs {
        val weekFrom = today - (WEEK_DAYS - 1)
        val monthFrom = today - (MONTH_DAYS - 1)
        val monthStart = CalendarMath.startOfDay(LocalDate.ofEpochDay(monthFrom), zone)
        val windowEnd = CalendarMath.startOfDay(LocalDate.ofEpochDay(today + 1), zone)
        // The load and the pace it is heavy against are read here rather than counted here. The overload panel
        // shows the same fortnight, and a day the app calls crowded in one screen and ordinary in the next
        // could not both be believed — so both figures come from WorkloadRepository, and the horizon it names
        // is the horizon the goal rule is measured against too.
        val load = workloadRepository.upcoming()
        val horizon = load.toDay
        return InsightInputs(
            today = today,
            weekFromDay = weekFrom,
            weekToDay = today,
            monthFromDay = monthFrom,
            monthToDay = today,
            overdue = taskStatisticsDao.priorityOverdue(userId, now),
            categoryShifts = categoryShifts(userId, weekFrom),
            estimateComparisons = workStatisticsDao.estimatedCompletions(userId, monthStart, windowEnd),
            completionTiming = CompletionTiming.of(
                taskStatisticsDao.completedInstantsInPeriod(userId, monthStart, windowEnd),
                zone
            ),
            habitWindows = habitWindows(userId, weekFrom, today),
            goalFigures = goalFigures(userId, today, zone, horizon),
            upcomingLoad = load.days,
            baselineDailyMinutes = load.baselineDailyMinutes,
            baselineMeasuredDays = load.measuredDays
        )
    }

    /**
     * This week's measured time per category against last week's.
     *
     * Two bounded aggregates rather than one wide query, because the two windows differ only in their day
     * bounds and the fold has to keep the categories that appear on one side of the comparison: a category
     * worked only last week has shrunk to nothing, which the rule reads, and one worked only this week has
     * grown from nothing, which it does not.
     */
    private suspend fun categoryShifts(userId: Long, weekFrom: Long): List<CategoryShift> {
        val current = workStatisticsDao.workByCategory(userId, weekFrom, weekFrom + WEEK_DAYS)
        val previous = workStatisticsDao.workByCategory(userId, weekFrom - WEEK_DAYS, weekFrom)
        val ids = (current.map { it.categoryId } + previous.map { it.categoryId }).toSet()
        return ids.sortedBy { it ?: UNCATEGORY_BUCKET_ORDER }
            .map { id ->
                val thisWeek = current.firstOrNull { it.categoryId == id }
                val lastWeek = previous.firstOrNull { it.categoryId == id }
                CategoryShift(
                    categoryId = id,
                    categoryName = thisWeek?.categoryName ?: lastWeek?.categoryName,
                    currentMinutes = minutesOf(thisWeek),
                    previousMinutes = minutesOf(lastWeek),
                    currentIntervals = thisWeek?.intervals ?: 0,
                    previousIntervals = lastWeek?.intervals ?: 0
                )
            }
    }

    /** Each open habit answered over the week the pass counts, on the schedule the habit itself names. */
    private suspend fun habitWindows(userId: Long, weekFrom: Long, today: Long): List<HabitWindow> =
        habitDao.getOpenHabits(userId)
            .filter { it.id > 0L }
            .sortedBy { it.id }
            .map { habit ->
                val figures = habitRepository.figuresOf(habit, weekFrom, today)
                HabitWindow(habit.id, habit.title, figures.period.asked, figures.period.kept)
            }

    /**
     * Each goal due inside the horizon, with the work still standing between it and its date.
     *
     * A time goal's figures become minutes here rather than in the rule: the pace a reader can act on is
     * "forty minutes a day", and the seconds the row stores are the unit the schema speaks, not the unit a
     * sentence about tomorrow should be written in.
     */
    private suspend fun goalFigures(
        userId: Long,
        today: Long,
        zone: ZoneId,
        horizon: Long
    ): List<GoalFigures> {
        val goals = goalDao.getGoalsDueBetween(
            userId,
            CalendarMath.startOfDay(LocalDate.ofEpochDay(today), zone),
            CalendarMath.startOfDay(LocalDate.ofEpochDay(horizon + 1), zone),
            GOALS_PER_PASS
        )
        val onDay = LocalDate.ofEpochDay(today)
        return goals.mapNotNull { goal ->
            val deadlineAt = goal.deadlineAt ?: return@mapNotNull null
            val progress = goalRepository.progressOf(goal, onDay = onDay, zone = zone)
            val remaining = (progress.remaining ?: 0L).coerceAtLeast(0L)
            val unit = if (goal.type.namesTasks) 1L else SECONDS_PER_MINUTE
            val deadlineDay = WorkTimeMath.epochDayOf(deadlineAt, zone)
            GoalFigures(
                goalId = goal.id,
                title = goal.title,
                asksTasks = goal.type.namesTasks,
                remaining = (remaining / unit).toInt(),
                target = (progress.target / unit).toInt(),
                counted = (progress.counted / unit).toInt(),
                daysLeft = (deadlineDay - today).toInt(),
                deadlineDay = deadlineDay
            )
        }
    }

    private fun forProfile(read: (Long) -> Flow<List<Insight>>): Flow<List<Insight>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            val userId = user?.id
            if (userId == null) flowOf(emptyList()) else read(userId)
        }

    // -- the sentence a finding becomes ---------------------------------------------------------

    private data class Wording(val title: String, val body: String, val basis: String)

    /**
     * The reading in the reader's language, from its numbers.
     *
     * One branch per rule, each naming the figures it prints: the title is the claim, the body states it in
     * the reader's terms, and the basis spells out the evidence with the counts a person can go and check. The
     * words never say more than the typed evidence holds, and §8's caveat about estimated durations is
     * appended where a day's minutes came from guesses rather than from hours the reader typed.
     */
    private fun wordingOf(finding: Finding): Wording = when (val evidence = finding.evidence) {
        is FindingEvidence.HighPriorityOverload -> Wording(
            title = string(R.string.insight_overload_title),
            body = string(R.string.insight_overload_body, evidence.highOverdue, evidence.overdue, evidence.open),
            basis = string(R.string.insight_overload_basis, evidence.highOverdue, evidence.open)
        )

        is FindingEvidence.CategoryGrowth -> Wording(
            title = string(R.string.insight_category_growth_title, evidence.categoryName),
            body = string(
                R.string.insight_category_growth_body,
                percent(evidence.growthPercent),
                duration(evidence.currentMinutes),
                duration(evidence.previousMinutes)
            ),
            basis = string(
                R.string.insight_category_growth_basis,
                evidence.currentIntervals,
                evidence.previousIntervals,
                duration(evidence.currentMinutes),
                duration(evidence.previousMinutes)
            )
        )

        is FindingEvidence.EstimateGap -> Wording(
            title = string(R.string.insight_estimate_gap_title),
            body = string(R.string.insight_estimate_gap_body, evidence.over, evidence.compared),
            basis = string(
                R.string.insight_estimate_gap_basis,
                evidence.taskTitle,
                duration(evidence.estimatedMinutes),
                duration(evidence.measuredMinutes)
            )
        )

        is FindingEvidence.CompletionWeekday -> Wording(
            title = string(R.string.insight_completion_weekday_title, weekday(evidence.weekday)),
            body = string(
                R.string.insight_completion_weekday_body,
                evidence.count,
                evidence.total,
                weekday(evidence.weekday)
            ),
            basis = string(
                R.string.insight_completion_weekday_basis,
                weekday(evidence.weekday),
                percent(evidence.sharePercent),
                MONTH_DAYS
            )
        )

        is FindingEvidence.CompletionHours -> Wording(
            title = string(R.string.insight_completion_hours_title, hourSpan(evidence.fromHour)),
            body = string(R.string.insight_completion_hours_body, evidence.count, evidence.total),
            basis = string(
                R.string.insight_completion_hours_basis,
                percent(evidence.sharePercent),
                MONTH_DAYS,
                hourSpan(evidence.fromHour)
            )
        )

        is FindingEvidence.HabitMiss -> Wording(
            title = string(R.string.insight_habit_miss_title, evidence.habitTitle),
            body = string(
                R.string.insight_habit_miss_body,
                quantity(R.plurals.insight_habit_missed_days, evidence.missed),
                evidence.asked
            ),
            basis = string(
                R.string.insight_habit_miss_basis,
                evidence.kept,
                evidence.asked,
                percent(evidence.kept * 100 / evidence.asked.coerceAtLeast(1))
            )
        )

        is FindingEvidence.GoalPace -> Wording(
            title = string(
                R.string.insight_goal_pace_title,
                evidence.title,
                paceFigure(evidence.asksTasks, evidence.perDay)
            ),
            body = string(
                R.string.insight_goal_pace_body,
                paceFigure(evidence.asksTasks, evidence.remaining),
                quantity(R.plurals.insight_days_span, evidence.daysLeft)
            ),
            basis = string(
                R.string.insight_goal_pace_basis,
                paceFigure(evidence.asksTasks, evidence.counted),
                paceFigure(evidence.asksTasks, evidence.target),
                quantity(R.plurals.insight_days_span, evidence.daysLeft)
            )
        )

        is FindingEvidence.HeavyDay -> {
            val body = string(
                R.string.insight_heavy_day_body,
                duration(evidence.minutes),
                evidence.tasks,
                duration(evidence.baselineMinutes)
            ) + if (evidence.approximate) string(R.string.insight_approximate_note) else ""
            Wording(
                title = string(R.string.insight_heavy_day_title, weekday(evidence.weekday)),
                body = body,
                basis = string(
                    R.string.insight_heavy_day_basis,
                    percent(evidence.percentOfBaseline),
                    quantity(R.plurals.insight_days_from_today, evidence.daysOut)
                )
            )
        }
    }

    private fun string(resId: Int, vararg args: Any?): String = context.getString(resId, *args)

    private fun quantity(resId: Int, count: Int): String =
        context.resources.getQuantityString(resId, count, count)

    /** A share, printed with the percent sign kept in the resource rather than in the code. */
    private fun percent(value: Int): String = string(R.string.insight_percent_format, value)

    /**
     * Minutes in the shape a plan is written in: minutes, hours, or both.
     *
     * A whole number of hours drops the minutes rather than printing "2 h 0 min", because the extra word is
     * noise beside a figure the reader is going to round anyway.
     */
    private fun duration(minutes: Int): String {
        if (minutes <= 0) return quantity(R.plurals.insight_minutes, 0)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> quantity(R.plurals.insight_minutes, minutes)
            rest == 0 -> quantity(R.plurals.insight_hours, hours)
            else -> string(R.string.insight_duration_hours_minutes, hours, rest)
        }
    }

    /** A goal's figure in its own unit — tasks for the kinds that name them, minutes for the rest. */
    private fun paceFigure(asksTasks: Boolean, value: Int): String =
        if (asksTasks) quantity(R.plurals.insight_goal_tasks, value) else duration(value)

    private fun weekday(day: DayOfWeek): String =
        day.getDisplayName(TextStyle.FULL, Locale.getDefault())

    /**
     * The two-hour stretch as a span: "16:00–18:00".
     *
     * Formatted by the locale rather than built from the number, so a reader whose clocks print "4 PM" gets
     * those hours named the way they read them — the span is the app's figure, the way it is said is theirs.
     */
    private fun hourSpan(fromHour: Int): String {
        val formatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
        val from = LocalTime.of(fromHour, 0).format(formatter)
        val to = LocalTime.of((fromHour + SPAN_HOURS) % 24, 0).format(formatter)
        return string(R.string.insight_hour_span, from, to)
    }

    private fun minutesOf(summary: CategoryWorkSummary?): Int =
        ((summary?.seconds ?: 0L) / SECONDS_PER_MINUTE).toInt()

    private companion object {
        /** The windows the rules count over. A month is four whole weeks rather than a calendar one. */
        private const val WEEK_DAYS = 7L
        private const val MONTH_DAYS = 28

        /** How long a refused rule stays quiet, measured from the answer rather than from its window. */
        private const val REJECTION_SILENCE_MILLIS = 30L * 24 * 3_600_000L

        /** How long untouched history is kept before the notebook forgets it. */
        private const val HISTORY_RETENTION_DAYS = 60L

        /** The most goals one pass looks at: the screen shows a handful, and a reading wants the soonest. */
        private const val GOALS_PER_PASS = 20

        private const val SECONDS_PER_MINUTE = 60L

        /** The width of the hour-of-day span the pattern rule reads. */
        private const val SPAN_HOURS = 2

        /** The bucket with no category row behind it, ordered last rather than by a null. */
        private const val UNCATEGORY_BUCKET_ORDER = Long.MAX_VALUE
    }
}
