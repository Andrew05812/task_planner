package com.taskplanner.testing

import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.habit.HabitAdherence
import com.taskplanner.data.habit.HabitStreak
import com.taskplanner.data.habit.StreakUnit
import com.taskplanner.data.report.AchievementsReport
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.CompletedTasksReport
import com.taskplanner.data.report.DayCount
import com.taskplanner.data.report.DaySeconds
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
import com.taskplanner.data.report.export.ReportStyle
import com.taskplanner.data.report.WorkedTimeReport
import com.taskplanner.data.repository.GoalProgress
import com.taskplanner.data.repository.HabitFigures
import com.taskplanner.data.report.export.ReportMeasurer
import com.taskplanner.data.report.export.ReportSurface
import com.taskplanner.data.report.export.ReportTypography
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reports that are typed by hand rather than read from storage.
 *
 * `ReportRepositoryTest` already proves what a section means over real rows, so an export test that asked the
 * same question again through Room would be testing the database a second time instead of the file format. What
 * an export has to be checked against is a figure the test can name exactly — a summary of seventeen finished
 * tasks, a category called `"Work, "deadline""`, a grant whose description runs over three lines — and a hand
 * built report is the only way to put those in front of a writer without a query agreeing by accident.
 *
 * The default is a report of the week 1 to 7 June 2026 in Europe/Berlin with nothing unusual in it. Every
 * builder takes the parts a test wants to name and leaves the rest at the honest empty value, so a test that
 * says "a report with no achievements in it" and one that says "the empty report" cannot silently mean the
 * same thing.
 */
object ReportFixtures {

    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    val monday: LocalDate = LocalDate.of(2026, 6, 1)
    val wednesday: LocalDate = LocalDate.of(2026, 6, 3)
    val generatedAt: Long = wednesday.atTime(17, 30).atZone(zone).toInstant().toEpochMilli()

    fun window(period: ReportPeriod = ReportPeriod.Day(wednesday)): ReportWindow =
        ReportWindow.of(period, zone, today = wednesday, generatedAt = generatedAt)

    fun report(
        period: ReportPeriod = ReportPeriod.Day(wednesday),
        summary: ReportSummary = summary(),
        completedTasks: CompletedTasksReport = CompletedTasksReport(0, emptyList(), 0),
        workedTime: WorkedTimeReport = WorkedTimeReport(0L, 0, 0, null, emptyList()),
        categoryActivity: List<CategoryActivity> = emptyList(),
        goals: GoalsReport = GoalsReport(emptyList(), 0, 0),
        habits: HabitsReport = HabitsReport(emptyList(), 0, 0, 0),
        dynamics: List<com.taskplanner.data.report.DynamicsDay> = emptyList(),
        achievements: AchievementsReport = AchievementsReport(emptyList()),
        findings: List<ReportFinding> = emptyList()
    ): Report {
        val window = window(period)
        return Report(
            window = window,
            summary = summary,
            completedTasks = completedTasks,
            workedTime = workedTime,
            categoryActivity = categoryActivity,
            goals = goals,
            habits = habits,
            dynamics = dynamics,
            achievements = achievements,
            comparison = PeriodComparison(
                window = window.preceding(),
                completions = com.taskplanner.data.statistics.DashboardMath.change(0L, 0L),
                workedSeconds = com.taskplanner.data.statistics.DashboardMath.change(0L, 0L),
                createdTasks = com.taskplanner.data.statistics.DashboardMath.change(0L, 0L),
                habitMarks = com.taskplanner.data.statistics.DashboardMath.change(0L, 0L),
                achievements = com.taskplanner.data.statistics.DashboardMath.change(0L, 0L)
            ),
            findings = findings
        )
    }

    fun summary(
        createdTasks: Int = 0,
        completedTasks: Int = 0,
        workedSeconds: Long = 0L,
        intervals: Int = 0,
        workSessions: Int = 0,
        days: Int = 1,
        activeDays: Int = 0,
        undated: Int = 0,
        standing: OpenWorkStanding = NO_STANDING
    ): ReportSummary = ReportSummary(
        createdTasks = createdTasks,
        completedTasks = completedTasks,
        workedSeconds = workedSeconds,
        intervals = intervals,
        workSessions = workSessions,
        averageSessionSeconds = if (workSessions > 0) workedSeconds.toDouble() / workSessions else null,
        days = days,
        activeDays = activeDays,
        completionsPerDay = if (days > 0) completedTasks.toDouble() / days else 0.0,
        standing = standing,
        undatedCompletions = undated
    )

    fun grant(
        key: String = "tasks-10",
        title: String = "Ten done",
        description: String = "Finish 10 tasks.",
        unit: AchievementUnit = AchievementUnit.TASKS,
        target: Long = 10L,
        progress: Long = 10L,
        day: LocalDate = wednesday,
        ruleVersion: Int = 1
    ): Achievement = Achievement(
        uuid = Achievement.uuidFor(7L, key),
        userId = 7L,
        achievementKey = key,
        ruleVersion = ruleVersion,
        category = AchievementCategory.VOLUME,
        title = title,
        description = description,
        unit = unit,
        targetValue = target,
        progressValue = progress,
        earnedDay = day.toEpochDay(),
        earnedAt = generatedAt
    )

    fun goal(
        title: String = "Ship the report",
        id: String = "goal-1",
        counted: Long = 6L,
        target: Long = 10L,
        measure: GoalMeasure = GoalMeasure.TASKS,
        moved: Long? = 3L,
        linked: Int = 0,
        completedLinks: Int = 0,
        missing: Int = 0
    ): GoalReportRow = GoalReportRow(
        goal = Goal(userId = 7L, uuid = id, title = title, targetValue = target),
        standing = GoalProgress(
            goal = Goal(userId = 7L, uuid = id, title = title, targetValue = target),
            counted = counted,
            target = target,
            linked = linked,
            completedLinks = completedLinks,
            missing = missing
        ),
        measure = measure,
        movedInWindow = moved
    )

    fun habit(
        title: String = "Read before bed",
        id: String = "habit-1",
        marks: Int = 4,
        kept: Int = 4,
        asked: Int = 7,
        periodDays: Int = 7,
        best: Int = 3
    ): HabitReportRow = HabitReportRow(
        habit = Habit(userId = 7L, uuid = id, title = title, startDay = monday.toEpochDay()),
        figures = HabitFigures(
            habit = Habit(userId = 7L, uuid = id, title = title, startDay = monday.toEpochDay()),
            markedToday = false,
            today = wednesday.toEpochDay(),
            streak = HabitStreak(current = best, best = best, unit = StreakUnit.DAYS),
            thisWeek = HabitAdherence(kept, asked),
            period = HabitAdherence(kept, asked),
            periodDays = periodDays,
            marks = marks
        )
    )

    fun finding(
        key: String = "high-priority-overload",
        title: String = "Nine tasks are late",
        body: String = "Nine of the open tasks were due before today.",
        kind: InsightKind = InsightKind.FACT,
        sampleSize: Int = 12,
        confidence: Int = 100,
        current: Boolean = true,
        action: InsightAction? = InsightAction.TASK
    ): ReportFinding = ReportFinding(
        ruleKey = key,
        kind = kind,
        title = title,
        body = body,
        basis = "counted over the open tasks",
        periodFromDay = monday.toEpochDay(),
        periodToDay = wednesday.toEpochDay(),
        sampleSize = sampleSize,
        confidence = confidence,
        actionType = action,
        actionRef = null,
        current = current,
        firstSeenAt = generatedAt
    )

    fun day(index: Long, completions: Int = 0, seconds: Long = 0L, marks: Int = 0) =
        com.taskplanner.data.report.DynamicsDay(
            localDay = monday.plusDays(index).toEpochDay(),
            date = monday.plusDays(index),
            completions = completions,
            workedSeconds = seconds,
            habitMarks = marks
        )

    fun count(index: Long, value: Int) =
        DayCount(monday.plusDays(index).toEpochDay(), monday.plusDays(index), value)

    fun seconds(index: Long, value: Long) =
        DaySeconds(monday.plusDays(index).toEpochDay(), monday.plusDays(index), value)

    /** A measurer a test can count with: every character is half its size wide, in every style. */
    class FixedMeasurer : ReportMeasurer {
        override fun width(text: String, style: ReportStyle): Float =
            text.length * ReportTypography.spec(style).size * 0.5f
    }

    /** A page that remembers what it was asked to draw instead of drawing it. */
    class RecordingSurface : ReportSurface {

        data class Drawn(val x: Float, val baseline: Float, val sizePt: Float, val bold: Boolean, val text: String)
        data class Sheet(
            val width: Int,
            val height: Int,
            val text: List<Drawn>,
            val rules: List<Float>,
            val rects: List<Quad>
        )

        data class Quad(val left: Float, val top: Float, val right: Float, val bottom: Float)

        val sheets = ArrayList<Sheet>()
        private var width = 0
        private var height = 0
        private var text = ArrayList<Drawn>()
        private var rules = ArrayList<Float>()
        private var rects = ArrayList<Quad>()
        private var open = false

        override fun startPage(width: Int, height: Int) {
            check(!open) { "a sheet was started twice without being finished" }
            this.width = width
            this.height = height
            text = ArrayList()
            rules = ArrayList()
            rects = ArrayList()
            open = true
        }

        override fun finishPage() {
            check(open) { "a sheet was finished that had not been started" }
            sheets += Sheet(width, height, text.toList(), rules.toList(), rects.toList())
            open = false
        }

        override fun text(x: Float, baseline: Float, sizePt: Float, bold: Boolean, color: Int, text: String) {
            this.text += Drawn(x, baseline, sizePt, bold, text)
        }

        override fun rule(left: Float, right: Float, y: Float, thickness: Float, color: Int) {
            rules += y
        }

        override fun rect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
            rects += Quad(left, top, right, bottom)
        }

        /** Every word drawn anywhere, in page order. */
        val allText: List<String> get() = sheets.flatMap { sheet -> sheet.text.map { it.text } }
    }
}

/**
 * The account behind every fixture report: eight tasks held, one of them late.
 *
 * Not all zeroes, because a test that means "this period held nothing" should still be able to tell the period
 * figures from the standing ones — and a report built on an account with nothing in it at all cannot show the
 * difference it is being asked to preserve.
 */
val NO_STANDING: OpenWorkStanding = OpenWorkStanding(
    openTasks = 6,
    overdue = 1,
    dueToday = 0,
    dueThisWeek = 2,
    withoutDeadline = 1,
    inProgress = 1,
    archivedTasks = 0,
    totalTasks = 8,
    lifetimeCompletionRate = 0.125f
)

/** The week of the fixture's default report, named once rather than spelled out in every test. */
fun juneFirstWeek(): ReportPeriod = ReportPeriod.Week(LocalDate.of(2026, 6, 3), DayOfWeek.MONDAY)
