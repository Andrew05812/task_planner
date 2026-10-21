package com.taskplanner.data.report

import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.repository.GoalProgress
import com.taskplanner.data.repository.HabitFigures
import com.taskplanner.data.statistics.FigureChange
import java.time.LocalDate

/**
 * One report's worth of answers, in the ten parts §6 asks for and in the units the stored rows use.
 *
 * This is the contract the report screen, the CSV writer and the PDF layout all read, which is why nothing
 * in it knows about any of them: no formatted strings, no colours chosen for a chart, no `Float` where a
 * count belongs, no field that only exists because a screen wanted a column. A figure that needs the reader
 * to know whether it means seconds or hours is not carried at all — every field here names its own unit in
 * its name, because §14 asks for a formula per metric and a formula cannot be checked against a field whose
 * unit is a matter of guesswork.
 *
 * Two kinds of number appear, and they are kept visibly apart. **Period figures** are answers about what
 * happened between [ReportWindow.fromMillis] and [ReportWindow.toMillis] — how much was finished, how much
 * was measured, which marks were made. **Standing figures** are answers about the account as it is now: how
 * much is still open, what each aim has reached, how many of everything the profile holds. The second kind
 * cannot be recomputed for a past period, because this schema stores events rather than snapshots and
 * nothing records what was open on 3 March. Every standing figure says so in its own name (`asAt`,
 * `standing`, `lifetime`), and the report never adds the two together.
 */
data class Report(
    val window: ReportWindow,

    /** §6.1 — Общая сводка. */
    val summary: ReportSummary,

    /** §6.2 — Выполненные задачи. */
    val completedTasks: CompletedTasksReport,

    /** §6.3 — Фактическое рабочее время. */
    val workedTime: WorkedTimeReport,

    /** §6.4 — Активность по категориям, most-worked first. */
    val categoryActivity: List<CategoryActivity>,

    /** §6.5 — Прогресс целей. */
    val goals: GoalsReport,

    /** §6.6 — Привычки. */
    val habits: HabitsReport,

    /** §6.7 — Динамика: one row per day of the period, oldest first. */
    val dynamics: List<DynamicsDay>,

    /** §6.8 — Достижения, as the grants were written. */
    val achievements: AchievementsReport,

    /** §6.9 — Сравнение с предыдущим периодом. */
    val comparison: PeriodComparison,

    /** §6.10 — Выявленные особенности: what the app itself had noticed about these days. */
    val findings: List<ReportFinding>
)

/**
 * The heading figures of the period, plus the account's standing alongside them and named as such.
 *
 * The formulas, so each is checkable against the row it came from:
 *
 *  * [createdTasks] — tasks with `created_at` inside the window, tombstones out.
 *  * [completedTasks] — tasks with a non-null `completed_at` inside the window. This is the only completion
 *    figure a period can hold; see [CompletedTasksReport.undatable] for the finished work that cannot be
 *    dated at all.
 *  * [workedSeconds] — the sum of `duration_seconds` over closed, live intervals measured on a day of the
 *    window. An interval still running is not in it and never can be: it has no length yet.
 *  * [workSessions] — distinct tracking sessions that contributed at least one such closed interval.
 *    A session that started before the window and was worked on inside it counts here, which is the same
 *    population [workedSeconds] sums; a "sessions started in the window" figure would be a different
 *    question and would not divide into [averageSessionSeconds].
 *  * [averageSessionSeconds] — [workedSeconds] over [workSessions], null when nothing was measured. A ratio
 *    of the two figures above rather than a third query, so the sentence "4 h over 8 sessions, so 30 min
 *    each" cannot contradict either half of itself.
 *  * [completionsPerDay] — [completedTasks] over [ReportWindow.days], including the days that held nothing.
 *    A per-active-day figure would flatter a stretch with two busy days in it, and the active-day count is
 *    beside it for whoever wants that reading instead.
 */
data class ReportSummary(
    val createdTasks: Int,
    val completedTasks: Int,
    val workedSeconds: Long,
    val intervals: Int,
    val workSessions: Int,
    val averageSessionSeconds: Double?,
    val days: Int,
    val activeDays: Int,
    val completionsPerDay: Double,

    /** What is still open right now. Never a period figure; see [OpenWorkStanding]. */
    val standing: OpenWorkStanding,

    /** Finished work the account holds no instant for, counted so the omission is on the page. */
    val undatedCompletions: Int
)

/**
 * The open work of the account as it stands at [ReportWindow.generatedAt].
 *
 * §3.2 asks for active, overdue, archived and a completion percentage in the same heading as the period
 * figures, and the first three are only ever true of *now*: this schema keeps no record of which tasks were
 * open on a past date, and inventing one by subtracting the period's completions from today's count would
 * be arithmetic about events nobody stored. So they arrive here, in a type that says `standing`, with the
 * instant they were read at carried by the window above them.
 */
data class OpenWorkStanding(
    val openTasks: Int,
    val overdue: Int,
    val dueToday: Int,
    val dueThisWeek: Int,
    val withoutDeadline: Int,
    val inProgress: Int,
    val archivedTasks: Int,
    val totalTasks: Int,
    /**
     * Finished over held, over the whole account, null when the account holds nothing.
     *
     * Not a period ratio: a task finished this week was mostly created before it, so "completed this month
     * over created this month" divides two unrelated sets and would print a percentage that means nothing.
     * The lifetime figure is the one the account actually supports, and it is the same one the dashboard
     * prints — [com.taskplanner.data.statistics.DashboardMath.completionRate].
     */
    val lifetimeCompletionRate: Float?
)

/**
 * §6.2 — the finished work of the period.
 *
 * [perDay] is built by putting each completion's instant into the report's own zone, which is why it is
 * computed here rather than grouped in SQL: `completed_at` is an instant, and the day a reader calls an
 * event "Tuesday's" depends on the zone they were standing in. Dividing epoch milliseconds by 86 400 000
 * would answer a question about UTC instead.
 */
data class CompletedTasksReport(
    val count: Int,
    val perDay: List<DayCount>,
    /**
     * Rows that read finished with no instant on them: work completed by a build from before `completed_at`
     * existed, which the migration refused to backfill from `updated_at`.
     *
     * Kept out of [count] and printed next to it. A report that quietly skipped these would tell the reader
     * they had finished less than they had, and a report that guessed their date would be inventing events.
     */
    val undatable: Int
)

/** One day and one whole number on it — the shape a trend bar and a CSV column both want. */
data class DayCount(
    val localDay: Long,
    val date: LocalDate,
    val count: Int
)

/**
 * §6.3 — the measured work of the period.
 *
 * Whole seconds, never hours: a report that rounded 3 h 47 min to "4 h" would be a report whose columns no
 * longer add up, and the hours-and-minutes spelling belongs to the screen printing it.
 */
data class WorkedTimeReport(
    val seconds: Long,
    val intervals: Int,
    val sessions: Int,
    val averageSessionSeconds: Double?,
    val perDay: List<DaySeconds>
)

/** One measured day and the whole seconds closed on it. */
data class DaySeconds(
    val localDay: Long,
    val date: LocalDate,
    val seconds: Long
)

/**
 * §6.4 — one category's share of the period, time and finished work side by side.
 *
 * The two figures come from two different tables and are not each other's proxy: a category of small tasks
 * can hold most of a week's finishes and almost none of its measured time. A row is present when either
 * figure is non-zero, which is why a category can appear with zero seconds (work nobody tracked) and why
 * work can appear with zero completions (a task still open).
 *
 * [categoryId] null is the unclassified bucket, and it is a real bucket rather than a gap: minutes spent on
 * a task with no category, and minutes spent on a task whose category row has since been deleted — the
 * latter cannot be recovered, so they are named as unclassified and not dropped.
 */
data class CategoryActivity(
    val categoryId: Long?,
    val categoryName: String?,
    val color: Int?,
    val seconds: Long,
    val intervals: Int,
    val completions: Int
)

/**
 * §6.5 — the aims the profile holds, each with where it stands and what moved inside this period.
 *
 * [reached] is read from the goal's own window at the moment of reporting, and it can move backwards:
 * un-completing a task takes it off a `TASK_COUNT` aim that had reached its target, because the figure is
 * counted from the tasks as they stand. This schema keeps no event for "the goal was reached on day X" —
 * [com.taskplanner.data.model.GoalValueChange] records title, type, target, window and deadline edits only —
 * so a report cannot say which goals were reached *during* the period it covers, and does not pretend to.
 *
 * That is also why [movedInWindow] is here. It answers the question the period can support: not whether the
 * goal was achieved, but how much of the work that counts toward it was done inside these days.
 */
data class GoalsReport(
    val rows: List<GoalReportRow>,
    /** How many of the profile's aims have reached their target as of this read, archived included. */
    val reachedStanding: Int,
    /** Every aim the profile stands behind, so a report can say "of 5 goals". */
    val standing: Int
)

/**
 * One aim and its two figures.
 *
 * [standing] is the goal's own progress against its own window — the same read the goal card makes, through
 * the same function. [movedInWindow] is that goal's measure restricted to the report's window.
 */
data class GoalReportRow(
    val goal: Goal,
    val standing: GoalProgress,
    val measure: GoalMeasure,
    val movedInWindow: Long?
)

/** The unit [GoalReportRow.movedInWindow] is stated in, per goal type. */
enum class GoalMeasure {
    /** Finished tasks inside the overlap of the goal's window and the report's. */
    TASKS,

    /** Measured seconds inside that overlap. */
    SECONDS,

    /**
     * A named set, whose progress is a state rather than a running total.
     *
     * Nothing moved "in the window" in any sense a report can prove: the link rows carry an `added_at`, but
     * whether a task is finished is read from the task, and a task finished inside this period tells nothing
     * about whether it belongs to this goal now or did not belong to it then. So the figure is null and the
     * report says which of the set is finished, which is what the row honestly holds.
     */
    SET_STATE
}

/**
 * §6.6 — the habits, over the period the report was asked for.
 *
 * [rows] lists only the habits that were kept at least once inside the window: a habit that was never marked
 * in these days is absent rather than a row of zeroes, and [HabitsReport.keptCount] is stated against the
 * profile's whole habit list so "3 of 9 habits were kept this month" can be written from the report alone.
 *
 * Each row's [HabitFigures] is honest about its own scope and says so in its own fields: [HabitFigures.marks]
 * and [HabitFigures.period] are answers about this window, while [HabitFigures.streak] walks back from the day
 * the window ends on. A window longer than the run states the run exactly; a window shorter than it can only
 * claim "at least", because the walk back stops at the first day the read was not given.
 *
 * That anchoring is the reason [ReportRepository] passes the period's last day rather than the day the report
 * was assembled on. A habit section for March read in June is asked about March: anchored on June, every
 * series would walk back from a day no mark of that period sits on, and the page would print a row of zeroes
 * for work the reader actually did — §4.1's forbidden invention of a zero where the figure is simply absent.
 */
data class HabitsReport(
    val rows: List<HabitReportRow>,
    val marks: Int,
    val keptCount: Int,
    val standing: Int
)

/** One habit and the figures its marks in this window produced. */
data class HabitReportRow(
    val habit: Habit,
    val figures: HabitFigures
)

/**
 * §6.7 — the day-by-day shape of the period, one row for every day it covers.
 *
 * Days with nothing in them are present with zeroes rather than skipped, because a trend drawn from sparse
 * rows silently compresses: a week of five working days and two empty ones looks like a five-day week if the
 * empty ones are missing, and the reader cannot see the rest. A zero here means the rows say nothing happened
 * — for completions it can also mean the finish was not dated, which [CompletedTasksReport.undatable] counts.
 */
data class DynamicsDay(
    val localDay: Long,
    val date: LocalDate,
    val completions: Int,
    val workedSeconds: Long,
    val habitMarks: Int
)

/**
 * §6.8 — the achievements handed out during the period.
 *
 * The rows are the stored ones, verbatim: title, description, [Achievement.progressValue],
 * [Achievement.targetValue], [Achievement.ruleVersion], the day and instant. Nothing here is recomputed from
 * the catalogue, and a rule whose bar or wording has moved since will not move what the report prints about
 * the day the grant was made — a grant is a memory of what the account had done at the time, which is the
 * whole reason it was written to a table instead of derived on every read.
 */
data class AchievementsReport(
    val grants: List<Achievement>
) {
    /** How many cards the period produced. */
    val granted: Int get() = grants.size
}

/**
 * §6.9 — this period beside the one immediately before it, of the same length.
 *
 * The previous window is the same number of *days* ending the day before this one starts, resolved to
 * instants in the same zone: an equal number of milliseconds would put a week before a daylight-saving shift
 * and a week after it on different day counts, and §4.1 compares weeks, not durations.
 *
 * Every figure is a [com.taskplanner.data.statistics.FigureChange] — the same comparison the dashboard's
 * period-change line is built from, rather than a second implementation of it that could decide the rules
 * differently. It carries no percentage where the earlier period had nothing to divide by: an increase over
 * zero is not an infinite one, and two empty periods did not "stay the same".
 */
data class PeriodComparison(
    val window: ReportWindow,
    val completions: FigureChange,
    val workedSeconds: FigureChange,
    val createdTasks: FigureChange,
    val habitMarks: FigureChange,
    val achievements: FigureChange
)

/**
 * §6.10 — one thing the app noticed about these days, as its row stated it.
 *
 * The wording travels with the row because an insight's sentence is chosen at the moment its rule fires, from
 * the sample and window it had. Re-deriving it for the report would put today's arithmetic in a paragraph
 * dated last month. [kind] and [confidence] are the honesty fields §8 asks for: a fact, a pattern and a
 * hypothesis are three different claims, and a confidence figure is never printed without the [sampleSize]
 * it was computed from.
 */
data class ReportFinding(
    val ruleKey: String,
    val kind: InsightKind,
    val title: String,
    val body: String,
    val basis: String,
    val periodFromDay: Long,
    val periodToDay: Long,
    val sampleSize: Int,
    val confidence: Int,
    val actionType: InsightAction?,
    val actionRef: String?,
    val current: Boolean,
    val firstSeenAt: Long
)
