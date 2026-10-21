package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.local.AchievementDao
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GoalDao
import com.taskplanner.data.local.HabitDao
import com.taskplanner.data.local.InsightDao
import com.taskplanner.data.local.TaskStatisticsDao
import com.taskplanner.data.local.TrackingSessionDao
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.local.WorkStatisticsDao
import com.taskplanner.data.model.DailyCompletionCount
import com.taskplanner.data.model.DailyWorkSummary
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitMarkedDay
import com.taskplanner.data.model.Insight
import com.taskplanner.data.report.AchievementsReport
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.CompletedTasksReport
import com.taskplanner.data.report.DayCount
import com.taskplanner.data.report.DaySeconds
import com.taskplanner.data.report.DynamicsDay
import com.taskplanner.data.report.GoalMeasure
import com.taskplanner.data.report.GoalReportRow
import com.taskplanner.data.report.GoalsReport
import com.taskplanner.data.report.HabitReportRow
import com.taskplanner.data.report.HabitsReport
import com.taskplanner.data.report.OpenWorkStanding
import com.taskplanner.data.report.PeriodComparison
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportFinding
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportSummary
import com.taskplanner.data.report.ReportWindow
import com.taskplanner.data.report.WorkedTimeReport
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.data.tracking.WorkTimeMath
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The report layer: one period in, the ten sections §6 names out, every figure measured in SQLite.
 *
 * This is the single place the app turns stored rows into report answers, and it exists so the report screen,
 * the CSV writer and the PDF layout can all be built against one contract instead of each repeating the
 * arithmetic and quietly disagreeing. Nothing here knows what a report looks like: no formatted strings, no
 * column widths, no rounding for a page. A caller that wants "4 h 12 min" gets 15 120 seconds and formats it,
 * because §14 asks for a formula per metric and a formula can only be checked against a figure that still
 * carries what it measured.
 *
 * ## Why so little of it is new SQL
 *
 * Almost every number here is an aggregate an earlier stage already wrote and already tested:
 * [TaskStatisticsDao] for what the tasks table can say about a window, [WorkStatisticsDao] for measured time,
 * [HabitDao] for marks, [InsightDao] for what the app noticed, [AchievementDao] for the grants. Four
 * statements were added because a question §6 asks had no answer anywhere: completions grouped by category,
 * the account's standing in one scan including the finished work that carries no date, the profile's marks
 * inside one window rather than one read per habit, the insights whose own window closed inside the period,
 * the aims a profile stands behind as a one-shot read, and a change signal per table the report reads. A
 * category's measured minutes and its finished tasks are grouped on the same joined column on purpose — group
 * them differently and one real bucket comes out of the merged section as two half-empty rows. Restating an
 * existing aggregate against another table would have produced a second definition of "finished", free to
 * drift from the first, which is the specific failure this layer exists to prevent.
 *
 * ## One profile, one snapshot
 *
 * [reportFor] takes the profile as an argument and passes it to every read, including the goal and habit
 * figures, which resolve the signed-in account themselves when a screen asks them. Without that, a reader
 * switching account halfway through a report would get one account's completions beside another's sessions,
 * and no number on the page would look wrong. The whole read also runs inside
 * [androidx.room.RoomDatabase.withTransaction], so the ten sections are answers about one snapshot of the
 * database rather than about ten separate moments: the case §6 rules out is a heading counted before a
 * completion landed and a chart counted after it.
 *
 * That transaction covers the reads and nothing else. Laying out a page, writing a file and drawing a chart
 * all happen after this returns an immutable [Report], which is why the lock is never held across formatting —
 * a report generating a PDF while holding SQLite's write lock would freeze every screen that writes.
 *
 * ## Where the time goes
 *
 * Measured in `ReportWindowBenchmarkTest` on a JVM harness over the same SQLite the app uses — not a device's
 * numbers, and not offered as one: the whole read of a report week medians 106 ms at 1 000 tasks and 326 ms at
 * 10 000, with 50 000 measured intervals, 60 habits, 40 aims and 200 grants underneath, and runs about three
 * times that while the rest of the suite competes for the machine. The
 * windowed reads are index seeks and cost milliseconds; what the account's size buys is the standing figures,
 * which are about the profile rather than about the period — a scan per standing question and one aggregate per
 * aim the reader holds. A caller that wants only the period's numbers should read the sections it names; the
 * standing block is what costs, and it is printed as what it is.
 *
 * ## Standing figures beside period figures
 *
 * A period figure is measured between two instants of the window. A standing figure — what is open now, what
 * each aim has reached, how much of the account is finished — is a read of the account as it stands, and this
 * schema cannot produce one for a past date: it stores events, not snapshots, and no row says which tasks were
 * open on 3 March. Rather than reconstruct that from today's state, which §3.2 forbids, the report carries
 * both kinds and names the second for what it is ([OpenWorkStanding], [GoalsReport.reachedStanding]). The two
 * are never added together.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val database: AppDatabase,
    private val taskStatisticsDao: TaskStatisticsDao,
    private val workStatisticsDao: WorkStatisticsDao,
    private val workSessionDao: WorkSessionDao,
    private val trackingSessionDao: TrackingSessionDao,
    private val goalDao: GoalDao,
    private val habitDao: HabitDao,
    private val categoryDao: CategoryDao,
    private val insightDao: InsightDao,
    private val achievementDao: AchievementDao,
    private val goalRepository: GoalRepository,
    private val habitRepository: HabitRepository,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase,
    private val ticker: WorkTicker
) {

    /**
     * The report for [period] in [zone], for whoever is signed in. Null while nobody is.
     *
     * The profile is resolved once, here, and handed to every read below — which is the difference between
     * this and a screen calling each repository in turn and hoping the account held still in between.
     */
    suspend fun report(
        period: ReportPeriod,
        zone: ZoneId = CalendarMath.systemZone()
    ): Report? {
        val userId = sessionRepository.currentUserId() ?: return null
        return reportFor(userId, period, zone)
    }

    /**
     * The same report for the profile the caller names.
     *
     * Public because a report is not necessarily about the account currently on screen, and because a test
     * with two signed-in profiles has to be able to say which one it is asking about. The read is a function
     * of its arguments — profile, period, zone — and of the injected clock, never of whichever account
     * happened to be resolved halfway through.
     */
    suspend fun reportFor(
        userId: Long,
        period: ReportPeriod,
        zone: ZoneId = CalendarMath.systemZone()
    ): Report = database.withTransaction {
        val now = timeBase.wallMillis()
        val today = LocalDate.ofEpochDay(WorkTimeMath.epochDayOf(now, zone))
        val window = ReportWindow.of(period, zone, today = today, generatedAt = now)
        val previous = window.preceding(zone)

        // One bounded read serves the period's count, its per-day bars and its dynamics rows: the instants
        // carry both the total and the days, and a second statement counting the same rows would be a number
        // able to disagree with the chart drawn from it.
        val completedInstants =
            taskStatisticsDao.completedInstantsInPeriod(userId, window.fromMillis, window.toMillis)
        val completions = DashboardMath.completionCounts(completedInstants, zone)
        val worked = workStatisticsDao.periodSummary(userId, window.fromDay, window.toDayExclusive)
        val workedDaily = workStatisticsDao.dailySeries(userId, window.fromDay, window.toDayExclusive)
        val habits = habitDao.getStandingHabits(userId)
        // One crediting rule for the three places marks are counted — the section, the daily rows and the
        // comparison — because a mark counted in one and not in another is the report disagreeing with itself.
        val markedDays = creditedMarks(
            habitDao.markedDaysInWindow(userId, window.fromDay, window.toDayInclusive), habits
        )
        val grants = achievementDao.earnedBetween(userId, window.fromDay, window.toDayInclusive)
        val findings = insightDao.findingsClosingBetween(userId, window.fromDay, window.toDayInclusive)
        val account = taskStatisticsDao.accountCounts(userId)
        val created = taskStatisticsDao.createdInPeriod(userId, window.fromMillis, window.toMillis)
        val health = taskStatisticsDao.healthCounts(
            userId = userId,
            now = now,
            dayEnd = CalendarMath.startOfDay(today.plusDays(1), zone),
            // The same rolling seven days the health panel calls "this week", not the calendar week: two
            // screens naming one bucket differently is the drift this layer exists to prevent.
            weekEnd = CalendarMath.startOfDay(today.plusDays(7), zone),
            staleBefore = CalendarMath.startOfDay(today.minusDays(STALE_AFTER_DAYS), zone)
        )

        val habitsReport = habitsOf(markedDays, habits, window)
        val averageSessionSeconds = averageSession(worked.seconds, worked.sessions)

        Report(
            window = window,
            summary = ReportSummary(
                createdTasks = created,
                completedTasks = completedInstants.size,
                workedSeconds = worked.seconds,
                intervals = worked.intervals,
                workSessions = worked.sessions,
                averageSessionSeconds = averageSessionSeconds,
                days = window.days,
                activeDays = activeDays(workedDaily, completions),
                completionsPerDay = completedInstants.size.toDouble() / window.days.toDouble(),
                standing = OpenWorkStanding(
                    openTasks = health.open,
                    overdue = health.overdue,
                    dueToday = health.dueToday,
                    dueThisWeek = health.dueThisWeek,
                    withoutDeadline = health.withoutDeadline,
                    inProgress = health.inProgress,
                    archivedTasks = account.archived,
                    totalTasks = account.tasks,
                    lifetimeCompletionRate = DashboardMath.completionRate(
                        completed = account.completed,
                        total = account.tasks
                    )
                ),
                undatedCompletions = account.undated
            ),
            completedTasks = CompletedTasksReport(
                count = completedInstants.size,
                perDay = completions.map {
                    DayCount(it.localDay, LocalDate.ofEpochDay(it.localDay), it.count)
                },
                undatable = account.undated
            ),
            workedTime = WorkedTimeReport(
                seconds = worked.seconds,
                intervals = worked.intervals,
                sessions = worked.sessions,
                averageSessionSeconds = averageSessionSeconds,
                perDay = workedDaily.map {
                    DaySeconds(it.localDay, LocalDate.ofEpochDay(it.localDay), it.seconds)
                }
            ),
            categoryActivity = categories(userId, window),
            goals = goals(userId, today, zone, window),
            habits = habitsReport,
            dynamics = dynamics(window, completions, workedDaily, markedDays),
            achievements = AchievementsReport(grants = grants),
            comparison = comparison(
                userId = userId,
                window = window,
                previous = previous,
                completions = completedInstants.size,
                workedSeconds = worked.seconds,
                marks = habitsReport.marks,
                created = created,
                grants = grants.size,
                habits = habits
            ),
            findings = findings.map { it.asReportFinding() }
        )
    }

    /**
     * The same report as a Flow that re-reads itself, so a report cannot go stale behind a write.
     *
     * §10.2 lists the things that must invalidate an open report: a task completed, a session added, a
     * category changed, a row deleted or restored, a goal edited, a habit marked, an import, a sync. Rather
     * than store a snapshot and argue about when it dies, this re-runs [reportFor] on the profile's own
     * change signals — one indexed scalar per table — plus the day tick that moves a period's anchor at
     * midnight with no write at all. Nothing is cached, so nothing can be out of date, and §10's rule about
     * not storing computed figures stays unbroken.
     *
     * The account comes from the Flow, not from the reads: [flatMapLatest] over [SessionRepository.currentUser]
     * abandons the old profile's stream when the reader switches and re-reads against the new id.
     */
    fun observe(
        period: ReportPeriod,
        zone: ZoneId = CalendarMath.systemZone()
    ): Flow<Report?> = sessionRepository.currentUser.flatMapLatest { user ->
        val userId = user?.id
        if (userId == null) {
            flowOf(null)
        } else {
            val signals: List<Flow<Unit>> = listOf(
                taskStatisticsDao.observeChanges(userId),
                workSessionDao.observeChanges(userId),
                trackingSessionDao.observeChanges(userId),
                habitDao.observeChanges(userId),
                goalDao.observeChanges(userId),
                categoryDao.observeChanges(userId),
                insightDao.observeChanges(userId),
                achievementDao.observeChanges(userId)
            ).map { change -> change.map { Unit } } + ticker.localDays(zone).map { Unit }

            combine(signals) { Unit }.map { reportFor(userId, period, zone) }
        }
    }

    // -- sections ------------------------------------------------------------------------------

    /**
     * Time and finished work, per category, merged into one row each.
     *
     * Two statements, because they group two different tables — intervals by the category of the task they
     * were measured on, completions by the category of the task that was finished — and one row per category,
     * because §6.4 wants one section. A category present in only one of the two still gets a row with a zero
     * in the other half, and that zero is true: work nobody tracked, or tracked time on tasks still open.
     *
     * The null bucket is kept rather than dropped. Those minutes were really spent — on a task with no
     * category, or on one whose category row has since been deleted, which cannot be recovered — and a chart
     * that silently lost them would be a chart whose slices stop adding up to the total beside them.
     */
    private suspend fun categories(userId: Long, window: ReportWindow): List<CategoryActivity> {
        val worked = workStatisticsDao.workByCategory(userId, window.fromDay, window.toDayExclusive)
        val finished = taskStatisticsDao.completedByCategory(
            userId, window.fromMillis, window.toMillis
        )

        val merged = LinkedHashMap<Long?, CategoryActivity>()
        worked.forEach { row ->
            merged[row.categoryId] = CategoryActivity(
                categoryId = row.categoryId,
                categoryName = row.categoryName,
                color = row.color,
                seconds = row.seconds,
                intervals = row.intervals,
                completions = 0
            )
        }
        finished.forEach { row ->
            val existing = merged[row.categoryId]
            merged[row.categoryId] = CategoryActivity(
                categoryId = row.categoryId,
                categoryName = row.categoryName ?: existing?.categoryName,
                color = row.color ?: existing?.color,
                seconds = existing?.seconds ?: 0L,
                intervals = existing?.intervals ?: 0,
                completions = row.completions
            )
        }

        return merged.values.sortedWith(
            compareByDescending<CategoryActivity> { it.seconds }
                .thenByDescending { it.completions }
                .thenBy(nullsLast()) { it.categoryId }
        )
    }

    /**
     * Where each aim stands, plus how much of its own measure was produced inside this window.
     *
     * [GoalMeasure.SET_STATE] goals carry null: their progress is a named set's current state, and no stored
     * row says whether a task that is finished now was part of the set at the moment it was finished.
     * Inventing that would be a report claiming knowledge the schema does not hold.
     *
     * The overlap is computed in the units the goal itself uses, through [dayBounds] and [instantBounds] —
     * the same two helpers the goal card runs — so a report and a card cannot diverge about what a goal's
     * dates meant.
     */
    private suspend fun goals(
        userId: Long,
        today: LocalDate,
        zone: ZoneId,
        window: ReportWindow
    ): GoalsReport {
        val rows = goalDao.getStandingGoals(userId).map { goal ->
            val standing = goalRepository.progressOf(goal, userId, today, zone)
            val measure = when (goal.type) {
                GoalType.TASK_COUNT -> GoalMeasure.TASKS
                GoalType.TIME_SPENT -> GoalMeasure.SECONDS
                GoalType.TASK_SET, GoalType.DEADLINE -> GoalMeasure.SET_STATE
            }
            val moved = when (goal.type) {
                GoalType.TASK_COUNT -> {
                    val (from, to) = goal.instantBounds(zone)
                    val overlapFrom = maxOf(from, window.fromMillis)
                    val overlapTo = minOf(to, window.toMillis)
                    if (overlapFrom >= overlapTo) 0L
                    else workStatisticsDao.completedTasksInPeriod(
                        userId, overlapFrom, overlapTo
                    ).toLong()
                }

                GoalType.TIME_SPENT -> {
                    val (from, to) = goal.dayBounds()
                    val overlapFrom = maxOf(from, window.fromDay)
                    val overlapTo = minOf(to, window.toDayExclusive)
                    if (overlapFrom >= overlapTo) 0L
                    else workStatisticsDao.periodSummary(userId, overlapFrom, overlapTo).seconds
                }

                GoalType.TASK_SET, GoalType.DEADLINE -> null
            }
            GoalReportRow(goal = goal, standing = standing, measure = measure, movedInWindow = moved)
        }
        return GoalsReport(
            rows = rows,
            reachedStanding = rows.count { it.standing.reached },
            standing = rows.size
        )
    }

    /**
     * The habits kept inside the window, from one read of the marks.
     *
     * Every habit the profile stands behind is loaded once and every mark in the window comes in one
     * statement, because one aggregate query per habit would be one query per card over rows the first query
     * had already returned. The figures themselves come from [HabitRepository.figuresOf] with the days this
     * read produced — the same arithmetic the habit screen runs, not a report's paraphrase of it. The days
     * arrive already credited by [creditedMarks], which is where the habit's own start day is applied: the
     * cards filter it in their read, and a profile-wide mark read cannot, because that day belongs to the
     * habit rather than to the mark.
     *
     * The figures are anchored on the period's last day rather than on the day the report is assembled. A
     * habit series walks back from the day it is given, so anchored on a June read day a March report would
     * state a series of nought for work the reader plainly did, and the row would print zeroes §4.1 forbids.
     * Anchored on the period, [HabitFigures.streak] is the run as the reported days left it — and, like every
     * windowed figure, it states exactly what the days it was given support: a run longer than the window can
     * only claim "at least".
     *
     * [HabitsReport.marks] sums the rows rather than counting the raw query, so the section adds up to itself;
     * the standing-habit filter is what makes that true when a habit was renamed or moved under a mark.
     *
     * Rows come back in the order the habits were created, which is the order
     * [HabitDao.getStandingHabits] states, so the same data yields the same page.
     */
    private fun habitsOf(
        markedDays: List<HabitMarkedDay>,
        habits: List<Habit>,
        window: ReportWindow
    ): HabitsReport {
        val daysByHabit = markedDays.groupBy({ it.habitUuid }, { it.localDay })
        val rows = habits.filter { daysByHabit.containsKey(it.uuid) }.map { habit ->
            HabitReportRow(
                habit = habit,
                figures = habitRepository.figuresOf(
                    habit = habit,
                    marked = daysByHabit.getValue(habit.uuid).toSet(),
                    today = window.toDayInclusive,
                    periodFromDay = window.fromDay,
                    periodToDay = window.toDayInclusive
                )
            )
        }.filter { it.figures.marks > 0 }
        return HabitsReport(
            rows = rows,
            marks = rows.sumOf { it.figures.marks },
            keptCount = rows.size,
            standing = habits.size
        )
    }

    /**
     * One row for every day of the period, whether anything happened in it or not.
     *
     * A sparse list would be the honest shape for a query and the dishonest one for a chart: two empty days
     * vanishing from a week makes five working days look like the whole story, and the reader cannot see the
     * rest. The days come from the window's own bounds, so a 23-hour daylight-saving day and a 25-hour one
     * each get exactly one row, as the reader counts them. The marks counted per day are the credited ones the
     * habit section sums, not the raw rows: [DynamicsDay.habitMarks] added across the period has to equal
     * [HabitsReport.marks].
     */
    private fun dynamics(
        window: ReportWindow,
        completions: List<DailyCompletionCount>,
        workedDaily: List<DailyWorkSummary>,
        markedDays: List<HabitMarkedDay>
    ): List<DynamicsDay> {
        val completed = completions.associateBy { it.localDay }
        val worked = workedDaily.associateBy { it.localDay }
        val marks = markedDays.groupingBy { it.localDay }.eachCount()

        return (window.fromDay until window.toDayExclusive).map { day ->
            DynamicsDay(
                localDay = day,
                date = LocalDate.ofEpochDay(day),
                completions = completed[day]?.count ?: 0,
                workedSeconds = worked[day]?.seconds ?: 0L,
                habitMarks = marks[day] ?: 0
            )
        }
    }

    /**
     * This period against the one of equal length before it.
     *
     * The window that ends the day before this one starts, with the same number of days — see
     * [ReportWindow.preceding] for why that is a calendar shift and not a subtraction of milliseconds. Every
     * figure is read again over the previous window rather than scaled from the current one, because §4.1 asks
     * for the two actual numbers and the change between them.
     */
    private suspend fun comparison(
        userId: Long,
        window: ReportWindow,
        previous: ReportWindow,
        completions: Int,
        workedSeconds: Long,
        marks: Int,
        created: Int,
        grants: Int,
        habits: List<Habit>
    ): PeriodComparison = PeriodComparison(
        window = previous,
        completions = DashboardMath.change(
            current = completions.toLong(),
            previous = workStatisticsDao.completedTasksInPeriod(
                userId, previous.fromMillis, previous.toMillis
            ).toLong()
        ),
        workedSeconds = DashboardMath.change(
            current = workedSeconds,
            previous = workStatisticsDao.periodSummary(
                userId, previous.fromDay, previous.toDayExclusive
            ).seconds
        ),
        createdTasks = DashboardMath.change(
            current = created.toLong(),
            previous = taskStatisticsDao.createdInPeriod(
                userId, previous.fromMillis, previous.toMillis
            ).toLong()
        ),
        habitMarks = DashboardMath.change(
            current = marks.toLong(),
            previous = attributedMarks(userId, previous, habits)
        ),
        achievements = DashboardMath.change(
            current = grants.toLong(),
            previous = achievementDao.countEarnedBetween(
                userId, previous.fromDay, previous.toDayInclusive
            ).toLong()
        )
    )

    /**
     * The marks attributable to a habit the profile still stands behind, over one window.
     *
     * The same rule [habitsOf] states for the current period, applied to the window before it, so the
     * comparison subtracts two like figures. A window nobody has looked at still costs two indexed reads here
     * rather than one per habit.
     */
    private suspend fun attributedMarks(
        userId: Long,
        window: ReportWindow,
        habits: List<Habit>
    ): Long = creditedMarks(
        habitDao.markedDaysInWindow(userId, window.fromDay, window.toDayInclusive), habits
    ).size.toLong()

    /**
     * The marks a report may count: this profile's standing habits', on or after each habit's own start day.
     *
     * [HabitDao.markedDaysInWindow] can narrow by profile and by day but not by the day a habit began on,
     * because that belongs to `habits`; and [HabitRepository] reads the start day into its own window, so a
     * report that counted raw rows would state more kept days beside the same habit's card than the card
     * admits — reachable today, because moving a habit's start forward leaves its earlier marks standing.
     */
    private fun creditedMarks(
        markedDays: List<HabitMarkedDay>,
        habits: List<Habit>
    ): List<HabitMarkedDay> {
        val startByHabit = habits.associate { it.uuid to it.startDay }
        return markedDays.filter { day ->
            val start = startByHabit[day.habitUuid]
            start != null && day.localDay >= start
        }
    }

    // -- arithmetic ------------------------------------------------------------------------------

    /**
     * Worked seconds over contributing sessions, or null when the window holds nothing to divide.
     *
     * A ratio of the two figures printed beside it rather than a third query over the same intervals: a
     * session the user paused twice is one stretch of working and several rows, so dividing by the interval
     * count would answer "how long a button press lasts" — and shrinking every time somebody taps pause is the
     * opposite of what §3.2 asks for. Computing it from the two aggregates makes the disagreement impossible.
     */
    private fun averageSession(seconds: Long, sessions: Int): Double? =
        if (sessions <= 0) null else seconds.toDouble() / sessions.toDouble()

    /**
     * Days that hold measured work or a finished task.
     *
     * The union rather than either half: a day of planning that ended with one task finished and no timer
     * running was still a day the reader was active, and calling it inactive would understate a period for
     * somebody who does not track time.
     */
    private fun activeDays(
        workedDaily: List<DailyWorkSummary>,
        completions: List<DailyCompletionCount>
    ): Int {
        val days = workedDaily.filter { it.seconds > 0L }.mapTo(HashSet()) { it.localDay }
        completions.filter { it.count > 0 }.forEach { days.add(it.localDay) }
        return days.size
    }

    private companion object {
        /** An open task untouched for this many days is one the report calls stale, as the panel does. */
        private const val STALE_AFTER_DAYS = 14L
    }
}

/** The finding as the report prints it: the row's own wording, its own window, its own sample. */
private fun Insight.asReportFinding() = ReportFinding(
    ruleKey = ruleKey,
    kind = kind,
    title = title,
    body = body,
    basis = basis,
    periodFromDay = periodFromDay,
    periodToDay = periodToDay,
    sampleSize = sampleSize,
    confidence = confidence,
    actionType = actionType,
    actionRef = actionRef,
    current = isCurrent,
    firstSeenAt = firstSeenAt
)
