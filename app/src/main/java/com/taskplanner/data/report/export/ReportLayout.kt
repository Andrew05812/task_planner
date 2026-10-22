package com.taskplanner.data.report.export

import android.content.Context
import androidx.core.content.ContextCompat
import com.taskplanner.BuildConfig
import com.taskplanner.R
import com.taskplanner.data.model.AchievementUnit
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.GoalMeasure
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.statistics.ChangeDirection
import com.taskplanner.data.statistics.FigureChange
import java.time.LocalDate

/**
 * The report's ten sections, written out as a document.
 *
 * This is the only place in the export that decides *what a report says*. The page layout decides where a
 * sentence lands and the csv decides how a figure is quoted, but neither of them chooses a word, and neither of
 * them does arithmetic: every number here comes out of [Report] exactly as
 * [com.taskplanner.data.repository.ReportRepository] handed it over. That is the whole point of the split. A
 * report whose PDF added up its own percentages would be a report whose two exports could disagree about the
 * reader's own week, and the reader would have no way to tell which of them was wrong.
 *
 * Four rules, all of them about the difference between a personal report and a table dump:
 *
 *  * **An absent figure is said, not invented.** A habit percentage the window cannot support is printed as the
 *    app's own dash rather than as 0 %, a goal with no target says there is no target, and a section with
 *    nothing in it gets a sentence that says so. A zero is a measurement, and printing one where nobody
 *    measured anything is the report making a claim the data does not make.
 *  * **A standing figure is labelled standing.** §6 of the stage spends its whole argument on the difference
 *    between what happened in a period and what is true right now, and the document keeps it visible: the
 *    summary prints its period lines, then a sentence that says what the next lines are, then them.
 *  * **The reader's own words are the reader's own words.** An achievement's title and description and an
 *    insight's paragraph are stored as finished sentences precisely so that a later build cannot rewrite them,
 *    so they travel here untouched — except for the pictures the font cannot draw, which are marked rather than
 *    dropped.
 *  * **Order is the report's, not the page's.** Categories stay most-worked first, days stay oldest first,
 *    grants stay in the order they were read. A report that re-sorted itself to look tidier would be a second
 *    answer to the question the repository already answered.
 */
object ReportLayout {

    /** Above this many days the day-by-day table stops being readable and the chart takes over. */
    private const val PER_DAY_TABLE_LIMIT = 31

    /** How many of a long period's days are named individually when the whole run cannot be. */
    private const val BUSIEST_DAYS = 10

    /** How many categories are drawn as parts of one bar before the rest become a sentence. */
    private const val CHART_PARTS = 8

    fun build(context: Context, report: Report, profileName: String?, palette: ReportPalette): ReportDocument {
        val builder = Builder(context, report, palette)
        val window = report.window
        val blocks = ArrayList<ReportBlock>()

        blocks += ReportBlock.Title(context.getString(R.string.report_document_title))
        blocks += ReportBlock.MetaLine(
            context.getString(
                R.string.report_meta_period,
                ReportFormatting.periodWord(context, window.period),
                builder.day(window.firstDate),
                builder.day(window.lastDate)
            )
        )
        blocks += ReportBlock.MetaLine(context.getString(R.string.report_meta_timezone, window.zone.id))
        blocks += ReportBlock.MetaLine(
            ReportFormatting.instant(context, window.generatedAt, window.zone)
        )
        profileName?.let { blocks += ReportBlock.MetaLine(context.getString(R.string.report_meta_profile, it)) }
        if (window.period is ReportPeriod.Week) {
            // A week is only a fact about days once the reader knows which day it starts on, and the report was
            // built with the window's own rule rather than with whatever locale is on the device now.
            blocks += ReportBlock.MetaLine(
                context.getString(R.string.report_meta_week_starts, ReportFormatting.weekday(window.firstDay))
            )
        }

        blocks += builder.summary()
        blocks += builder.completedTasks()
        blocks += builder.workedTime()
        blocks += builder.categories()
        blocks += builder.goals()
        blocks += builder.habits()
        blocks += builder.dynamics()
        blocks += builder.achievements()
        blocks += builder.comparison()
        blocks += builder.findings()

        // Marking happens once, over the finished blocks, rather than wherever a string was picked up: a
        // single pass cannot forget a column, and the alternative — a `text()` call at every read of user
        // data — has exactly one bug per place somebody forgets it.
        val marker = context.getString(R.string.report_glyph_marker)
        val needed = blocks.any(::holdsMarker)
        val marked = markBlocks(blocks, marker)
        return ReportDocument(
            title = context.getString(R.string.report_document_title),
            meta = marked.filterIsInstance<ReportBlock.MetaLine>().map { it.text },
            blocks = marked + limitNotes(context, needed, marker, report.window)
        )
    }

    /** The document's blocks with every string that needs it marked, and every other string untouched. */
    private fun markBlocks(blocks: List<ReportBlock>, marker: String): List<ReportBlock> = blocks.map { block ->
        when (block) {
            is ReportBlock.Title -> block.copy(text = ReportGlyphs.show(block.text, marker))
            is ReportBlock.MetaLine -> block.copy(text = ReportGlyphs.show(block.text, marker))
            is ReportBlock.Section -> block.copy(text = ReportGlyphs.show(block.text, marker))
            is ReportBlock.Paragraph -> block.copy(text = ReportGlyphs.show(block.text, marker))
            is ReportBlock.Note -> block.copy(text = ReportGlyphs.show(block.text, marker))
            is ReportBlock.KeyValue -> block.copy(
                label = ReportGlyphs.show(block.label, marker),
                value = ReportGlyphs.show(block.value, marker)
            )
            is ReportBlock.Table -> block.copy(
                columns = block.columns.map { it.copy(title = ReportGlyphs.show(it.title, marker)) },
                rows = block.rows.map { row -> row.map { ReportGlyphs.show(it, marker) } }
            )
            is ReportBlock.Chart -> block.copy(chart = markChart(block.chart, marker))
        }
    }

    private fun markChart(chart: ReportChart, marker: String): ReportChart = when (chart) {
        is ReportChart.Bars -> chart.copy(
            title = ReportGlyphs.show(chart.title, marker),
            unit = ReportGlyphs.show(chart.unit, marker),
            caption = chart.caption?.let { ReportGlyphs.show(it, marker) },
            columns = chart.columns.map { it.copy(label = it.label?.let { text -> ReportGlyphs.show(text, marker) }) }
        )

        is ReportChart.Shares -> chart.copy(
            title = ReportGlyphs.show(chart.title, marker),
            unit = ReportGlyphs.show(chart.unit, marker),
            omitted = chart.omitted?.let { ReportGlyphs.show(it, marker) },
            parts = chart.parts.map { it.copy(label = ReportGlyphs.show(it.label, marker)) }
        )
    }

    /**
     * The section that says what the document is made of: the window it covers in the words and the zone it
     * was counted in, the build that wrote it, and — only when it was needed — the marker that stands for a
     * picture the font cannot draw.
     *
     * Written after every other block because it is the part of a report a careful reader turns to last and
     * the part a reviewer turns to first, and because the marker note can only be decided once the marking
     * has been looked at.
     */
    private fun limitNotes(
        context: Context,
        marked: Boolean,
        marker: String,
        window: com.taskplanner.data.report.ReportWindow
    ): List<ReportBlock> {
        val blocks = mutableListOf<ReportBlock>(
            ReportBlock.Section(context.getString(R.string.report_section_limits)),
            ReportBlock.Paragraph(
                context.getString(
                    R.string.report_limits_window,
                    ReportFormatting.day(context, window.firstDate),
                    ReportFormatting.day(context, window.lastDate),
                    window.zone.id
                )
            ),
            ReportBlock.Paragraph(context.getString(R.string.report_limits_written, BuildConfig.VERSION_NAME))
        )
        if (marked) {
            blocks += ReportBlock.Paragraph(context.getString(R.string.report_limits_glyphs, marker))
        }
        return blocks
    }

    /** Whether any string in [block] had to be marked, which is what decides the closing note. */
    private fun holdsMarker(block: ReportBlock): Boolean = when (block) {
        is ReportBlock.Title -> ReportGlyphs.needsMarker(block.text)
        is ReportBlock.MetaLine -> ReportGlyphs.needsMarker(block.text)
        is ReportBlock.Section -> ReportGlyphs.needsMarker(block.text)
        is ReportBlock.Paragraph -> ReportGlyphs.needsMarker(block.text)
        is ReportBlock.Note -> ReportGlyphs.needsMarker(block.text)
        is ReportBlock.KeyValue -> ReportGlyphs.needsMarker(block.label) || ReportGlyphs.needsMarker(block.value)
        is ReportBlock.Table -> block.rows.any { row -> row.any { ReportGlyphs.needsMarker(it) } } ||
            block.columns.any { ReportGlyphs.needsMarker(it.title) }
        is ReportBlock.Chart -> when (val chart = block.chart) {
            is ReportChart.Bars -> ReportGlyphs.needsMarker(chart.title) ||
                chart.columns.any { it.label?.let { text -> ReportGlyphs.needsMarker(text) } == true }
            is ReportChart.Shares -> ReportGlyphs.needsMarker(chart.title) ||
                chart.parts.any { ReportGlyphs.needsMarker(it.label) }
        }
    }

    /** The palette a printed report is drawn with, taken from the app's own fixed print colours. */
    fun palette(context: Context): ReportPalette = ReportPalette(
        ink = ContextCompat.getColor(context, R.color.report_ink),
        accent = ContextCompat.getColor(context, R.color.report_accent),
        muted = ContextCompat.getColor(context, R.color.report_muted),
        finished = ContextCompat.getColor(context, R.color.report_finished),
        hairline = ContextCompat.getColor(context, R.color.report_hairline)
    )

    // --- the builder ----------------------------------------------------------------------------------

    /**
     * One report's worth of sentences, with the two pieces of state the document needs beside them: the
     * context the words come from, and whether any of the reader's own text had to be marked.
     *
     * The flag is state because the note about it belongs at the end of the report, and the end is written
     * before the middle in this file's order of sections. Reading it back from the finished blocks would mean
     * looking for a marker string inside text that might contain those same characters legitimately.
     */
    private class Builder(
        private val context: Context,
        private val report: Report,
        private val palette: ReportPalette
    ) {
        fun day(date: LocalDate): String = ReportFormatting.day(context, date)

        private fun section(title: Int): ReportBlock.Section = ReportBlock.Section(context.getString(title))

        private fun paragraph(raw: String): ReportBlock.Paragraph = ReportBlock.Paragraph(raw)

        private fun note(raw: String): ReportBlock.Note = ReportBlock.Note(raw)

        private fun count(value: Int) = ReportFormatting.count(value)
        private fun count(value: Long) = ReportFormatting.count(value)

        private fun missing(): String = ReportFormatting.missing(context)

        // --- §6.1 — the summary ------------------------------------------------------------------------

        fun summary(): List<ReportBlock> {
            val summary = report.summary
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_summary))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_created), count(summary.createdTasks))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_completed), count(summary.completedTasks))
            blocks += ReportBlock.KeyValue(
                context.getString(R.string.report_metric_worked),
                ReportFormatting.seconds(context, summary.workedSeconds)
            )
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_intervals), count(summary.intervals))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_sessions), count(summary.workSessions))
            blocks += ReportBlock.KeyValue(
                context.getString(R.string.report_metric_average_session),
                summary.averageSessionSeconds
                    ?.let { ReportFormatting.seconds(context, it.toLong()) }
                    ?: missing()
            )
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_days), count(summary.days))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_active_days), count(summary.activeDays))
            blocks += ReportBlock.KeyValue(
                context.getString(R.string.report_metric_per_day),
                ReportFormatting.decimal(summary.completionsPerDay, 1)
            )
            if (summary.undatedCompletions > 0) {
                blocks += ReportBlock.KeyValue(
                    context.getString(R.string.report_metric_undated),
                    count(summary.undatedCompletions)
                )
            }

            val standing = summary.standing
            blocks += paragraph(context.getString(R.string.report_note_standing))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_open_now), count(standing.openTasks))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_overdue_now), count(standing.overdue))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_due_today), count(standing.dueToday))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_due_week), count(standing.dueThisWeek))
            blocks += ReportBlock.KeyValue(
                context.getString(R.string.report_metric_without_deadline),
                count(standing.withoutDeadline)
            )
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_in_progress), count(standing.inProgress))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_archived), count(standing.archivedTasks))
            blocks += ReportBlock.KeyValue(context.getString(R.string.report_metric_total), count(standing.totalTasks))
            blocks += ReportBlock.KeyValue(
                context.getString(R.string.report_metric_rate),
                standing.lifetimeCompletionRate?.let { ReportFormatting.percent(it) } ?: missing()
            )
            if (summary.undatedCompletions > 0) blocks += note(context.getString(R.string.report_note_undated))
            return blocks
        }

        // --- §6.2 — finished work ----------------------------------------------------------------------

        fun completedTasks(): List<ReportBlock> {
            val section = report.completedTasks
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_completed))
            blocks += if (section.count == 0 && section.undatable == 0) {
                paragraph(context.getString(R.string.report_none_completed))
            } else {
                paragraph(
                    context.resources.getQuantityString(R.plurals.dashboard_finished, section.count, section.count)
                )
            }
            if (section.perDay.isNotEmpty()) blocks += dayTable(section.perDay.map { it.date to count(it.count) })
            if (section.undatable > 0) {
                blocks += note(
                    context.resources.getQuantityString(
                        R.plurals.report_undated_count,
                        section.undatable,
                        section.undatable
                    )
                )
            }
            return blocks
        }

        // --- §6.3 — measured work ----------------------------------------------------------------------

        fun workedTime(): List<ReportBlock> {
            val section = report.workedTime
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_worked))
            blocks += if (section.seconds == 0L && section.intervals == 0) {
                paragraph(context.getString(R.string.report_none_worked))
            } else {
                paragraph(
                    context.getString(
                        R.string.report_worked_line,
                        ReportFormatting.seconds(context, section.seconds),
                        context.resources.getQuantityString(
                            R.plurals.history_stretch_count,
                            section.intervals,
                            section.intervals
                        )
                    )
                )
            }
            if (section.perDay.isNotEmpty()) {
                blocks += dayTable(section.perDay.map { it.date to ReportFormatting.seconds(context, it.seconds) })
            }
            return blocks
        }

        // --- §6.4 — categories -------------------------------------------------------------------------

        fun categories(): List<ReportBlock> {
            val rows = report.categoryActivity
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_categories))
            if (rows.isEmpty()) {
                blocks += paragraph(context.getString(R.string.report_none_categories))
                return blocks
            }
            val total = rows.sumOf { it.seconds }
            blocks += Table(
                columns = listOf(
                    column(R.string.report_table_category, 4f),
                    column(R.string.report_table_measured, 2f, ReportAlignment.END),
                    column(R.string.report_table_stretches, 1.6f, ReportAlignment.END),
                    column(R.string.report_table_finished, 1.6f, ReportAlignment.END)
                ),
                rows = rows.map { activity ->
                    listOf(
                        categoryText(activity),
                        ReportFormatting.seconds(context, activity.seconds),
                        count(activity.intervals),
                        count(activity.completions)
                    )
                }
            )
            // Only the rows that hold a share of the time are drawn as parts of it: a category that was
            // visited but never measured has a place in the table above and no width in a bar of seconds.
            val shares = rows.filter { it.seconds > 0L }
            if (total > 0L && shares.size > 1) {
                val shown = shares.take(CHART_PARTS)
                val rest = shares.drop(CHART_PARTS)
                blocks += ReportBlock.Chart(
                    ReportChart.Shares(
                        title = context.getString(R.string.report_categories_chart_title),
                        total = total,
                        unit = context.getString(R.string.report_chart_shares_unit),
                        parts = shown.map {
                            ChartPart(
                                label = categoryText(it),
                                value = it.seconds,
                                color = it.color ?: ContextCompat.getColor(context, R.color.report_category_none)
                            )
                        },
                        omitted = if (rest.isEmpty()) null else context.getString(
                            R.string.report_chart_shares_omitted,
                            rest.size,
                            ReportFormatting.seconds(context, rest.sumOf { it.seconds })
                        )
                    )
                )
            }
            if (rows.any { it.categoryId == null }) blocks += note(context.getString(R.string.report_note_category_none))
            return blocks
        }

        private fun categoryText(activity: CategoryActivity): String =
            activity.categoryName ?: context.getString(R.string.report_note_category_none)

        // --- §6.5 — goals ------------------------------------------------------------------------------

        fun goals(): List<ReportBlock> {
            val section = report.goals
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_goals))
            if (section.rows.isEmpty()) {
                blocks += paragraph(context.getString(R.string.report_none_goals))
                return blocks
            }
            blocks += paragraph(
                context.resources.getQuantityString(
                    R.plurals.report_goals_reached_of,
                    section.reachedStanding,
                    section.reachedStanding,
                    section.standing
                )
            )
            blocks += Table(
                columns = listOf(
                    column(R.string.report_table_goal, 3.6f),
                    column(R.string.report_table_progress, 2f, ReportAlignment.END),
                    column(R.string.report_table_reached, 1.4f, ReportAlignment.END),
                    column(R.string.report_table_in_period, 2f, ReportAlignment.END)
                ),
                rows = section.rows.map { row ->
                    listOf(row.goal.title, progressText(row), reachedText(row), movedText(row))
                }
            )
            blocks += note(context.getString(R.string.report_note_goals_standing))
            return blocks
        }

        private fun progressText(row: com.taskplanner.data.report.GoalReportRow): String {
            val progress = row.standing
            return when (row.measure) {
                GoalMeasure.TASKS -> if (progress.target <= 0L) missing() else context.getString(
                    R.string.report_progress_of,
                    count(progress.counted),
                    count(progress.target)
                )

                GoalMeasure.SECONDS -> if (progress.target <= 0L) missing() else context.getString(
                    R.string.report_progress_of,
                    ReportFormatting.seconds(context, progress.counted),
                    ReportFormatting.seconds(context, progress.target)
                )

                // A named set counts the tasks it holds rather than a target it was given, and a set that
                // names nothing has not been asked about yet.
                GoalMeasure.SET_STATE -> if (progress.linked <= 0) missing() else context.getString(
                    R.string.report_progress_of,
                    count(progress.completedLinks),
                    count(progress.linked)
                )
            }
        }

        private fun reachedText(row: com.taskplanner.data.report.GoalReportRow): String = when {
            row.measure == GoalMeasure.SET_STATE && row.standing.linked <= 0 -> missing()
            row.measure != GoalMeasure.SET_STATE && row.standing.target <= 0L ->
                context.getString(R.string.report_reached_unknown)
            row.standing.reached -> context.getString(R.string.report_reached_yes)
            else -> context.getString(R.string.report_reached_no)
        }

        private fun movedText(row: com.taskplanner.data.report.GoalReportRow): String {
            val moved = row.movedInWindow ?: return missing()
            return when (row.measure) {
                GoalMeasure.SECONDS -> ReportFormatting.seconds(context, moved)
                else -> count(moved)
            }
        }

        // --- §6.6 — habits -----------------------------------------------------------------------------

        fun habits(): List<ReportBlock> {
            val section = report.habits
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_habits))
            if (section.rows.isEmpty()) {
                if (section.standing > 0) blocks += keptLine(section.keptCount, section.standing)
                blocks += paragraph(context.getString(R.string.report_none_habits))
                return blocks
            }
            blocks += keptLine(section.keptCount, section.standing)
            blocks += Table(
                columns = listOf(
                    column(R.string.report_table_habit, 4f),
                    column(R.string.report_table_marks, 1.6f, ReportAlignment.END),
                    column(R.string.report_table_kept, 1.4f, ReportAlignment.END),
                    column(R.string.report_table_longest, 2f, ReportAlignment.END)
                ),
                rows = section.rows.map { row ->
                    val figures = row.figures
                    listOf(
                        row.habit.title,
                        count(figures.marks),
                        figures.period.percent?.let { ReportFormatting.wholePercent(it) } ?: missing(),
                        longestRun(figures)
                    )
                }
            )
            blocks += note(context.getString(R.string.report_note_habit_series))
            return blocks
        }

        private fun keptLine(kept: Int, standing: Int) = paragraph(
            context.resources.getQuantityString(R.plurals.report_habits_kept_of, kept, kept, standing)
        )

        private fun longestRun(figures: com.taskplanner.data.repository.HabitFigures): String {
            val best = figures.streak.best
            if (best <= 0) return context.getString(R.string.habit_streak_none)
            return context.resources.getQuantityString(
                when (figures.streak.unit) {
                    com.taskplanner.data.habit.StreakUnit.DAYS -> R.plurals.habit_best_days
                    com.taskplanner.data.habit.StreakUnit.WEEKS -> R.plurals.habit_best_weeks
                },
                best,
                best
            )
        }

        // --- §6.7 — day by day -------------------------------------------------------------------------

        fun dynamics(): List<ReportBlock> {
            val days = report.dynamics
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_dynamics))
            if (days.isEmpty()) {
                blocks += paragraph(context.getString(R.string.report_none_dynamics))
                return blocks
            }
            val work = days.map { it.workedSeconds }
            val finished = days.map { it.completions.toLong() }
            if (work.sum() > 0L) {
                val tallest = work.max()
                blocks += ReportBlock.Chart(
                    ReportChart.Bars(
                        title = context.getString(R.string.report_dynamics_chart_work),
                        unit = context.getString(R.string.report_chart_work_unit),
                        columns = days.map { BarColumn(shortLabel(it.date), it.workedSeconds) },
                        color = palette.accent,
                        caption = context.getString(
                            R.string.report_chart_tallest,
                            ReportFormatting.seconds(context, tallest)
                        )
                    )
                )
            }
            if (finished.sum() > 0L) {
                blocks += ReportBlock.Chart(
                    ReportChart.Bars(
                        title = context.getString(R.string.report_dynamics_chart_completions),
                        unit = context.getString(R.string.report_chart_completions_unit),
                        columns = days.map { BarColumn(shortLabel(it.date), it.completions.toLong()) },
                        color = palette.finished,
                        caption = context.getString(
                            R.string.report_chart_tallest_completions,
                            count(finished.max())
                        )
                    )
                )
            }
            if (work.sum() == 0L && finished.sum() == 0L) {
                blocks += paragraph(context.getString(R.string.report_none_dynamics))
            }
            if (days.size <= PER_DAY_TABLE_LIMIT) {
                blocks += Table(
                    columns = listOf(
                        column(R.string.report_table_day, 3f),
                        column(R.string.report_table_finished, 1.6f, ReportAlignment.END),
                        column(R.string.report_table_measured, 2f, ReportAlignment.END),
                        column(R.string.report_table_marks, 1.6f, ReportAlignment.END)
                    ),
                    rows = days.map { day ->
                        listOf(
                            this.day(day.date),
                            count(day.completions),
                            ReportFormatting.seconds(context, day.workedSeconds),
                            count(day.habitMarks)
                        )
                    }
                )
            } else {
                blocks += paragraph(context.getString(R.string.report_note_long_series))
                blocks += Table(
                    columns = listOf(
                        column(R.string.report_table_busiest, 3f),
                        column(R.string.report_table_finished, 1.6f, ReportAlignment.END),
                        column(R.string.report_table_measured, 2f, ReportAlignment.END),
                        column(R.string.report_table_marks, 1.6f, ReportAlignment.END)
                    ),
                    rows = days.sortedByDescending { it.workedSeconds }.take(BUSIEST_DAYS).map { day ->
                        listOf(
                            this.day(day.date),
                            count(day.completions),
                            ReportFormatting.seconds(context, day.workedSeconds),
                            count(day.habitMarks)
                        )
                    }
                )
            }
            blocks += note(context.getString(R.string.report_note_dynamics))
            return blocks
        }

        private fun shortLabel(date: LocalDate): String =
            if (report.window.days <= 14) date.dayOfMonth.toString() else ReportFormatting.shortDay(date)

        // --- §6.8 — achievements -----------------------------------------------------------------------

        fun achievements(): List<ReportBlock> {
            val section = report.achievements
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_achievements))
            if (section.grants.isEmpty()) {
                blocks += paragraph(context.getString(R.string.report_none_achievements))
                return blocks
            }
            blocks += paragraph(
                context.resources.getQuantityString(
                    R.plurals.report_achievements_granted,
                    section.granted,
                    section.granted
                )
            )
            section.grants.forEach { grant ->
                blocks += ReportBlock.KeyValue(
                    grant.title,
                    day(LocalDate.ofEpochDay(grant.earnedDay))
                )
                blocks += paragraph(grant.description)
                blocks += note(
                    context.getString(
                        R.string.report_achievement_progress,
                        count(grant.progressValue),
                        count(grant.targetValue),
                        context.getString(unitWord(grant.unit))
                    )
                )
            }
            return blocks
        }

        private fun unitWord(unit: AchievementUnit): Int = when (unit) {
            AchievementUnit.TASKS -> R.string.report_unit_tasks
            AchievementUnit.GOALS -> R.string.report_unit_goals
            AchievementUnit.PROJECTS -> R.string.report_unit_projects
            AchievementUnit.HABITS -> R.string.report_unit_habits
            AchievementUnit.MARKS -> R.string.report_unit_marks
            AchievementUnit.SESSIONS -> R.string.report_unit_sessions
            AchievementUnit.STREAK_DAYS -> R.string.report_unit_streak_days
            AchievementUnit.ACTIVE_DAYS -> R.string.report_unit_active_days
            AchievementUnit.HOURS -> R.string.report_unit_hours
            AchievementUnit.OTHER -> R.string.report_unit_other
        }

        // --- §6.9 — the period before -------------------------------------------------------------------

        fun comparison(): List<ReportBlock> {
            val comparison = report.comparison
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_comparison))
            blocks += paragraph(
                context.getString(
                    R.string.report_comparison_against,
                    day(comparison.window.firstDate),
                    day(comparison.window.lastDate)
                )
            )
            val rows = listOf(
                changeRow(context.getString(R.string.report_table_finished), comparison.completions),
                changeRow(context.getString(R.string.report_metric_worked), comparison.workedSeconds, seconds = true),
                changeRow(context.getString(R.string.report_metric_created), comparison.createdTasks),
                changeRow(context.getString(R.string.report_table_marks), comparison.habitMarks),
                changeRow(context.getString(R.string.report_metric_achievements), comparison.achievements)
            )
            if (rows.all { it.isEmpty }) {
                blocks += paragraph(context.getString(R.string.report_none_comparison))
                return blocks
            }
            blocks += Table(
                columns = listOf(
                    column(R.string.report_table_metric, 3f),
                    column(R.string.report_table_before, 1.6f, ReportAlignment.END),
                    column(R.string.report_table_now, 1.6f, ReportAlignment.END),
                    column(R.string.report_table_change, 1.8f, ReportAlignment.END)
                ),
                rows = rows.map { listOf(it.metric, it.before, it.now, it.change) }
            )
            return blocks
        }

        /**
         * One figure beside the same figure from the period before.
         *
         * The change is a signed percentage or it is nothing, because the two alternatives are the two ways a
         * report lies: a percentage over a period that measured nothing, and a flat "the same" over two
         * absences. [FigureChange] has already refused to compute the first and already names the second, so
         * this function only has to find words for what it says.
         */
        private fun changeRow(metric: String, change: FigureChange, seconds: Boolean = false): ChangeRow {
            fun figure(value: Long) = if (seconds) ReportFormatting.seconds(context, value) else count(value)
            val empty = change.current == 0L && change.previous == 0L
            val change1 = when {
                empty -> missing()
                change.fraction == null -> context.getString(R.string.report_change_from_nothing)
                change.direction == ChangeDirection.FLAT -> context.getString(R.string.report_change_flat)
                else -> (if (change.direction == ChangeDirection.UP) "+" else "−") +
                    ReportFormatting.percent(change.fraction.toFloat())
            }
            return ChangeRow(
                metric = metric,
                before = figure(change.previous),
                now = figure(change.current),
                change = change1,
                isEmpty = empty
            )
        }

        private data class ChangeRow(
            val metric: String,
            val before: String,
            val now: String,
            val change: String,
            val isEmpty: Boolean
        )

        // --- §6.10 — what the app noticed ---------------------------------------------------------------

        fun findings(): List<ReportBlock> {
            val findings = report.findings
            val blocks = mutableListOf<ReportBlock>(section(R.string.report_section_findings))
            if (findings.isEmpty()) {
                blocks += paragraph(context.getString(R.string.report_none_findings))
                return blocks
            }
            findings.forEach { finding ->
                blocks += ReportBlock.KeyValue(finding.title, kindWord(finding.kind))
                blocks += paragraph(finding.body)
                blocks += note(
                    context.getString(
                        R.string.report_finding_basis,
                        count(finding.sampleSize),
                        ReportFormatting.decimal(finding.confidence.toDouble(), 0) + "%"
                    )
                )
                if (!finding.current) blocks += note(context.getString(R.string.report_finding_superseded))
            }
            return blocks
        }

        private fun kindWord(kind: InsightKind): String = context.getString(
            when (kind) {
                InsightKind.FACT -> R.string.report_finding_kind_fact
                InsightKind.PATTERN -> R.string.report_finding_kind_pattern
                InsightKind.HYPOTHESIS -> R.string.report_finding_kind_hypothesis
            }
        )

        private fun column(title: Int, weight: Float, align: ReportAlignment = ReportAlignment.START) =
            ReportColumn(context.getString(title), weight, align)

        private fun Table(columns: List<ReportColumn>, rows: List<List<String>>) =
            ReportBlock.Table(columns, rows)

        private fun dayTable(days: List<Pair<LocalDate, String>>): ReportBlock.Table =
            ReportBlock.Table(
                columns = listOf(
                    column(R.string.report_table_day, 3f),
                    column(R.string.report_table_finished, 2f, ReportAlignment.END)
                ),
                rows = days.map { (date, value) -> listOf(day(date), value) }
            )
    }
}
