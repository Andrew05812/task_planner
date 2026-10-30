package com.taskplanner.data.report.export

import com.taskplanner.ui.settings.AppLocale
import com.taskplanner.BuildConfig
import com.taskplanner.data.model.Achievement
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.GoalMeasure
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportFinding
import com.taskplanner.data.report.OpenWorkStanding
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.statistics.FigureChange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The report as rows a spreadsheet or a script can read.
 *
 * The second of the stage's two exports, and the reason it is not the same document as the PDF: a person
 * reading a report and a program reading data ask different questions of it, and a file written to answer both
 * answers neither. So nothing here is a sentence. One column set runs from the first row to the last —
 * section, subject_id, subject, metric, value, unit, scope, day, text — which is what lets a reader filter the file to
 * "every standing figure" or "every row about habits" without a parser that has to learn a new shape per
 * table, and what keeps §6.2's "стабильный порядок колонок" true of the whole file rather than of one part of
 * it.
 *
 * Six rules, all of them traceable to the stage's own list for csv:
 *
 *  * **UTF-8, with the byte-order mark.** The mark is not decoration: it is how Excel on Windows decides a file
 *    is UTF-8 rather than its own code page, and without it a Russian category name arrives as mojibake. Every
 *    other reader either ignores the three bytes or is told the encoding explicitly.
 *  * **Numbers are numbers, not pictures of numbers.** `Locale.ROOT`, no grouping separators, a full stop for
 *    the decimal point, and a duration in whole seconds — the figure the database holds. `1234.5` and `44100`
 *    survive a round trip through any tool; `1 234,5` and `12 h 15 min` do not.
 *  * **Dates are ISO-8601.** A day is `uuuu-MM-dd`, an instant is the ISO offset form of the report's own zone.
 *    A localised date string is a claim about a calendar the reader may not use and something no parser can
 *    order by.
 *  * **An absent figure is an empty field.** The PDF shows a dash because a page needs a visible sign that the
 *    report asked and could not answer; a csv cell that says `—` is a cell a script has to special-case, and a
 *    zero in its place would be a measurement nobody took.
 *  * **The reader's text is verbatim.** A title, a description and an insight's paragraph are written exactly
 *    as stored, including newlines, emoji and commas — the quoting below is what makes that safe rather than
 *    what makes it tidy.
 *  * **Nothing is secret and nothing is internal.** No profile id, no row id, no `sync_state`, no uuid that
 *    only the local database understands. Where a subject has to be named across two runs of the same export
 *    the file uses the identity the app itself gives a row for exactly that purpose — a goal's uuid, an
 *    achievement's key — and for the rows whose only local handle is a numeric id it names the subject and
 *    leaves the identifier out.
 */
object CsvReport {

    /** The columns, in the order every row of every section uses. */
    val COLUMNS = listOf(
        "section", "subject_id", "subject", "metric", "value", "unit", "scope", "day", "text"
    )

    /** The three answers the `scope` column can hold, in the words the report's own split uses. */
    private const val SCOPE_META = "meta"
    private const val SCOPE_PERIOD = "period"
    private const val SCOPE_STANDING = "standing"
    private const val SCOPE_PREVIOUS = "previous"

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
    private val INSTANT_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /**
     * The whole file: the header row, then every section of the report in §6's order.
     *
     * [delimiter] is a parameter rather than a constant because of one disagreement the stage cannot settle for
     * every reader: Excel takes its list separator from the locale, and a locale that writes `3,14` expects
     * `;` between fields while a locale that writes `3.14` expects `,`. The default below follows that rule, so
     * the file a Russian user double-clicks opens as columns; a caller writing for a parser or for a different
     * audience asks for the other one. Column order, quoting and formats are identical either way.
     */
    fun rows(report: Report, delimiter: Char = defaultDelimiter()): List<List<String>> {
        val rows = ArrayList<List<String>>()
        rows += COLUMNS
        rows += meta(report)
        rows += summary(report)
        rows += standing(report.summary.standing)
        rows += completedTasks(report)
        rows += workedTime(report)
        rows += categories(report)
        rows += goals(report)
        rows += habits(report)
        rows += dynamics(report)
        rows += achievements(report)
        rows += comparison(report)
        rows += findings(report)
        return rows
    }

    /** The delimiter the reader's own locale leads Excel to expect. */
    fun defaultDelimiter(): Char =
        if (',' == java.text.DecimalFormatSymbols.getInstance(AppLocale.current()).decimalSeparator) ';' else ','

    /**
     * The file as its writer sees it: one entry per row, each already quoted and joined, with no line ending on
     * the end of it.
     *
     * This exists so a preview of the file and the file itself are the same list. [bytes] joins these with the
     * line ending, and the report screen shows these — which means a field that carries a line break inside it,
     * quoted as RFC 4180 says it must be, is one row on the screen and one row in the file. A screen that took
     * the finished bytes and split them again would have to re-derive where each row ends, and the quoted break
     * is exactly the case it would get wrong: one row of the report arriving as two lines of the preview, in a
     * screen whose whole claim is that it is showing what the file holds.
     */
    fun lines(report: Report, delimiter: Char = defaultDelimiter()): List<String> =
        rows(report, delimiter).map { row -> row.joinToString(delimiter.toString()) { quote(it, delimiter) } }

    /**
     * The file as bytes, ready for a stream: UTF-8, the byte-order mark first, RFC 4180 line endings, and a
     * final newline so a reader that appends to the file cannot glue itself onto the last row.
     */
    fun bytes(report: Report, delimiter: Char = defaultDelimiter()): ByteArray {
        val text = StringBuilder()
        text.append(BYTE_ORDER_MARK)
        lines(report, delimiter).forEach { line ->
            text.append(line)
            text.append(CRLF)
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    // --- the sections -----------------------------------------------------------------------------------

    private fun meta(report: Report): List<List<String>> {
        val window = report.window
        val period = window.period
        return listOf(
            row("meta", "", "report", "period_type", periodKind(period), "", SCOPE_META, "", ""),
            row("meta", "", "report", "period_from", "", "", SCOPE_META, iso(window.firstDate), ""),
            row("meta", "", "report", "period_to", "", "", SCOPE_META, iso(window.lastDate), ""),
            row("meta", "", "report", "days", value(window.days), "days", SCOPE_META, "", ""),
            row("meta", "", "report", "timezone", window.zone.id, "", SCOPE_META, "", ""),
            row("meta", "", "report", "first_day_of_week", window.firstDay.name, "", SCOPE_META, "", ""),
            row(
                "meta", "", "report", "generated_at",
                iso(window.generatedAt, window.zone), "", SCOPE_META, "", ""
            ),
            row("meta", "", "report", "app_version", BuildConfig.VERSION_NAME, "", SCOPE_META, "", ""),
            // The window the comparison is drawn against, so a row of `previous` scope can be identified
            // without a reader having to work out the length of the period from its own calendar.
            row(
                "meta", "", "report", "previous_from", "", "", SCOPE_PREVIOUS,
                iso(report.comparison.window.firstDate), ""
            ),
            row(
                "meta", "", "report", "previous_to", "", "", SCOPE_PREVIOUS,
                iso(report.comparison.window.lastDate), ""
            )
        )
    }

    private fun summary(report: Report): List<List<String>> {
        val summary = report.summary
        return listOf(
            row("summary", "", "report", "created_tasks", value(summary.createdTasks), "tasks", SCOPE_PERIOD, "", ""),
            row("summary", "", "report", "completed_tasks", value(summary.completedTasks), "tasks", SCOPE_PERIOD, "", ""),
            row("summary", "", "report", "worked_seconds", value(summary.workedSeconds), "seconds", SCOPE_PERIOD, "", ""),
            row("summary", "", "report", "intervals", value(summary.intervals), "intervals", SCOPE_PERIOD, "", ""),
            row("summary", "", "report", "work_sessions", value(summary.workSessions), "sessions", SCOPE_PERIOD, "", ""),
            row(
                "summary", "", "report", "average_session_seconds",
                summary.averageSessionSeconds?.let { decimal(it) } ?: "",
                "seconds", SCOPE_PERIOD, "", ""
            ),
            row("summary", "", "report", "period_days", value(summary.days), "days", SCOPE_PERIOD, "", ""),
            row("summary", "", "report", "active_days", value(summary.activeDays), "days", SCOPE_PERIOD, "", ""),
            row(
                "summary", "", "report", "completions_per_day",
                decimal(summary.completionsPerDay), "tasks", SCOPE_PERIOD, "", ""
            ),
            row(
                "summary", "", "report", "undated_completions", value(summary.undatedCompletions), "tasks",
                SCOPE_PERIOD, "", ""
            )
        )
    }

    private fun standing(standing: OpenWorkStanding): List<List<String>> =
        listOf(
            row("summary", "", "account", "open_tasks", value(standing.openTasks), "tasks", SCOPE_STANDING, "", ""),
            row("summary", "", "account", "overdue_tasks", value(standing.overdue), "tasks", SCOPE_STANDING, "", ""),
            row("summary", "", "account", "due_today", value(standing.dueToday), "tasks", SCOPE_STANDING, "", ""),
            row("summary", "", "account", "due_this_week", value(standing.dueThisWeek), "tasks", SCOPE_STANDING, "", ""),
            row(
                "summary", "", "account", "without_deadline", value(standing.withoutDeadline), "tasks",
                SCOPE_STANDING, "", ""
            ),
            row("summary", "", "account", "in_progress", value(standing.inProgress), "tasks", SCOPE_STANDING, "", ""),
            row("summary", "", "account", "archived_tasks", value(standing.archivedTasks), "tasks", SCOPE_STANDING, "", ""),
            row("summary", "", "account", "total_tasks", value(standing.totalTasks), "tasks", SCOPE_STANDING, "", ""),
            row(
                "summary", "", "account", "lifetime_completion_rate",
                standing.lifetimeCompletionRate?.let { decimal(it.toDouble(), 4) } ?: "",
                "share", SCOPE_STANDING, "", ""
            )
        )

    private fun completedTasks(report: Report): List<List<String>> {
        val section = report.completedTasks
        val rows = ArrayList<List<String>>()
        rows += row("completed_tasks", "", "report", "finished", value(section.count), "tasks", SCOPE_PERIOD, "", "")
        if (section.undatable > 0) {
            rows += row(
                "completed_tasks", "", "report", "undatable", value(section.undatable), "tasks", SCOPE_PERIOD, "",
                "Finished with no instant the app holds; counted here and left out of the day rows."
            )
        }
        section.perDay.forEach { day ->
            rows += row(
                "completed_tasks", "", "report", "finished", value(day.count), "tasks", SCOPE_PERIOD, iso(day.date), ""
            )
        }
        return rows
    }

    private fun workedTime(report: Report): List<List<String>> {
        val section = report.workedTime
        val rows = ArrayList<List<String>>()
        rows += row("worked_time", "", "report", "worked_seconds", value(section.seconds), "seconds", SCOPE_PERIOD, "", "")
        rows += row("worked_time", "", "report", "intervals", value(section.intervals), "intervals", SCOPE_PERIOD, "", "")
        rows += row("worked_time", "", "report", "sessions", value(section.sessions), "sessions", SCOPE_PERIOD, "", "")
        rows += row(
            "worked_time", "", "report", "average_session_seconds",
            section.averageSessionSeconds?.let { decimal(it) } ?: "", "seconds", SCOPE_PERIOD, "", ""
        )
        section.perDay.forEach { day ->
            rows += row(
                "worked_time", "", "report", "worked_seconds", value(day.seconds), "seconds", SCOPE_PERIOD,
                iso(day.date), ""
            )
        }
        return rows
    }

    private fun categories(report: Report): List<List<String>> {
        val rows = ArrayList<List<String>>()
        report.categoryActivity.forEach { activity ->
            // A category is named rather than numbered: a local row id means nothing in a file the reader
            // sends to somebody else's machine, and the name is what the report's own table shows.
            val subject = text(activity.categoryName ?: "")
            val id = ""
            rows += row("categories", id, subject, "worked_seconds", value(activity.seconds), "seconds", SCOPE_PERIOD, "", "")
            rows += row("categories", id, subject, "intervals", value(activity.intervals), "intervals", SCOPE_PERIOD, "", "")
            rows += row("categories", id, subject, "finished", value(activity.completions), "tasks", SCOPE_PERIOD, "", "")
            if (activity.categoryId == null) {
                rows += row(
                    "categories", id, subject, "unclassified", "1", "flag", SCOPE_PERIOD, "",
                    "No category, or a category whose row has since been deleted."
                )
            }
        }
        return rows
    }

    private fun goals(report: Report): List<List<String>> {
        val rows = ArrayList<List<String>>()
        report.goals.rows.forEach { goal ->
            val subject = text(goal.goal.title)
            val id = goal.goal.uuid
            rows += row(
                "goals", id, subject, "counted", value(goal.standing.counted), unitOf(goal.measure),
                SCOPE_STANDING, "", ""
            )
            rows += row("goals", id, subject, "target", value(goal.standing.target), unitOf(goal.measure), SCOPE_STANDING, "", "")
            rows += row(
                "goals", id, subject, "reached", if (goal.standing.reached) "1" else "0", "flag", SCOPE_STANDING, "",
                "Read on the day the report was written, not on a day of the period."
            )
            rows += row(
                "goals", id, subject, "moved_in_period",
                goal.movedInWindow?.let { value(it) } ?: "", unitOf(goal.measure), SCOPE_PERIOD, "",
                if (goal.measure == GoalMeasure.SET_STATE) {
                    "A named set is a state rather than a running total, so nothing moved inside the period " +
                        "that the rows can date."
                } else {
                    ""
                }
            )
            if (goal.standing.missing > 0) {
                rows += row(
                    "goals", id, subject, "missing_named_tasks", value(goal.standing.missing), "tasks",
                    SCOPE_STANDING, "", "Named by the goal, no longer in the database."
                )
            }
        }
        return rows
    }

    private fun habits(report: Report): List<List<String>> {
        val rows = ArrayList<List<String>>()
        report.habits.rows.forEach { habit ->
            val subject = text(habit.habit.title)
            val id = habit.habit.uuid
            val figures = habit.figures
            rows += row("habits", id, subject, "marks", value(figures.marks), "days", SCOPE_PERIOD, "", "")
            rows += row(
                "habits", id, subject, "kept_percent",
                figures.period.percent?.let { value(it) } ?: "", "percent", SCOPE_PERIOD, "", ""
            )
            rows += row("habits", id, subject, "asked_days", value(figures.period.asked), "days", SCOPE_PERIOD, "", "")
            rows += row("habits", id, subject, "period_days", value(figures.periodDays), "days", SCOPE_PERIOD, "", "")
            rows += row(
                "habits", id, subject, "longest_run", value(figures.streak.best),
                if (figures.streak.unit.name == "WEEKS") "weeks" else "days", SCOPE_PERIOD, "",
                "Counted back from the last day of the period; a run longer than the period is stated as at " +
                    "least its length."
            )
        }
        return rows
    }

    private fun dynamics(report: Report): List<List<String>> {
        val rows = ArrayList<List<String>>()
        report.dynamics.forEach { day ->
            rows += row("dynamics", "", "report", "finished", value(day.completions), "tasks", SCOPE_PERIOD, iso(day.date), "")
            rows += row(
                "dynamics", "", "report", "worked_seconds", value(day.workedSeconds), "seconds", SCOPE_PERIOD,
                iso(day.date), ""
            )
            rows += row("dynamics", "", "report", "habit_marks", value(day.habitMarks), "days", SCOPE_PERIOD, iso(day.date), "")
        }
        return rows
    }

    private fun achievements(report: Report): List<List<String>> {
        val rows = ArrayList<List<String>>()
        report.achievements.grants.forEach { grant: Achievement ->
            rows += row(
                "achievements", grant.achievementKey, text(grant.title), "earned", "1", "flag", SCOPE_PERIOD,
                iso(LocalDate.ofEpochDay(grant.earnedDay)), ""
            )
            rows += row(
                "achievements", grant.achievementKey, text(grant.title), "description", "", "", SCOPE_PERIOD,
                iso(LocalDate.ofEpochDay(grant.earnedDay)), text(grant.description)
            )
            rows += row(
                "achievements", grant.achievementKey, text(grant.title), "progress", value(grant.progressValue),
                grant.unit.name.lowercase(Locale.ROOT), SCOPE_PERIOD, iso(LocalDate.ofEpochDay(grant.earnedDay)), ""
            )
            rows += row(
                "achievements", grant.achievementKey, text(grant.title), "target", value(grant.targetValue),
                grant.unit.name.lowercase(Locale.ROOT), SCOPE_PERIOD, iso(LocalDate.ofEpochDay(grant.earnedDay)), ""
            )
            rows += row(
                "achievements", grant.achievementKey, text(grant.title), "rule_version", value(grant.ruleVersion),
                "version", SCOPE_PERIOD, iso(LocalDate.ofEpochDay(grant.earnedDay)),
                "The edition of the rule that handed this out; the sentences above are the ones that were read " +
                    "on the day."
            )
        }
        return rows
    }

    /**
     * The two periods side by side, as rows that share a metric name.
     *
     * One row for this period and one for the one before, distinguished by `scope` rather than by a
     * `previous_value` column: a spreadsheet that has both halves of a comparison in one row cannot be filtered
     * to one period, and a script that wants the pair can pivot on the metric it already knows. The third row
     * carries the fraction the repository worked out — it is stored in [FigureChange] rather than computed here,
     * and a file that left it out would send the reader back to their own division.
     */
    private fun comparison(report: Report): List<List<String>> {
        val comparison = report.comparison
        val rows = ArrayList<List<String>>()
        listOf(
            Triple("completions", comparison.completions, "tasks"),
            Triple("worked_seconds", comparison.workedSeconds, "seconds"),
            Triple("created_tasks", comparison.createdTasks, "tasks"),
            Triple("habit_marks", comparison.habitMarks, "days"),
            Triple("achievements", comparison.achievements, "grants")
        ).forEach { (metric, change, unit) -> rows += change(metric, change, unit) }
        return rows
    }

    private fun change(metric: String, figures: FigureChange, unit: String): List<List<String>> {
        val note = when {
            figures.fraction == null && figures.current > 0L ->
                "The period before measured nothing, so this is a change from no base rather than a percentage."
            figures.current == 0L && figures.previous == 0L ->
                "Neither period measured anything; two absences are not a comparison."
            else -> ""
        }
        return listOf(
            row("comparison", "", "report", metric, value(figures.current), unit, SCOPE_PERIOD, "", note),
            row("comparison", "", "report", metric, value(figures.previous), unit, SCOPE_PREVIOUS, "", ""),
            row(
                "comparison", "", "report", "$metric-change_fraction",
                figures.fraction?.let { decimal(it, 4) } ?: "", "share", SCOPE_PERIOD, "", note
            )
        )
    }

    private fun findings(report: Report): List<List<String>> {
        val rows = ArrayList<List<String>>()
        report.findings.forEach { finding: ReportFinding ->
            val subject = text(finding.title)
            rows += row(
                "findings", finding.ruleKey, subject, "kind", finding.kind.name, "", SCOPE_PERIOD,
                iso(LocalDate.ofEpochDay(finding.periodFromDay)), ""
            )
            rows += row(
                "findings", finding.ruleKey, subject, "body", "", "", SCOPE_PERIOD,
                iso(LocalDate.ofEpochDay(finding.periodFromDay)), text(finding.body)
            )
            rows += row(
                "findings", finding.ruleKey, subject, "sample_size", value(finding.sampleSize), "rows",
                SCOPE_PERIOD, "", ""
            )
            rows += row(
                "findings", finding.ruleKey, subject, "confidence", value(finding.confidence), "percent",
                SCOPE_PERIOD, "", ""
            )
            rows += row(
                "findings", finding.ruleKey, subject, "current", if (finding.current) "1" else "0", "flag",
                SCOPE_PERIOD, "", ""
            )
            rows += row(
                "findings", finding.ruleKey, subject, "counted_to", "", "", SCOPE_PERIOD,
                iso(LocalDate.ofEpochDay(finding.periodToDay)), ""
            )
            rows += row(
                "findings", finding.ruleKey, subject, "first_seen",
                iso(Instant.ofEpochMilli(finding.firstSeenAt).atZone(report.window.zone).toLocalDate()),
                "", SCOPE_PERIOD, "", ""
            )
        }
        return rows
    }

    // --- the writing ------------------------------------------------------------------------------------

    private fun row(
        section: String,
        subjectId: String,
        subject: String,
        metric: String,
        value: String,
        unit: String,
        scope: String,
        day: String,
        text: String
    ): List<String> = listOf(section, subjectId, subject, metric, value, unit, scope, day, guarded(text))

    /** The stored words, as the file holds them. */
    private fun text(raw: String): String = guarded(raw)

    /**
     * [field] made safe to open in a spreadsheet, and quoted if it has to be.
     *
     * Two separate jobs, done in this order because the second one changes what the first one's output looks
     * like:
     *
     *  * **Formula injection.** A field that a spreadsheet would *run* rather than show is a security hole in a
     *    file that only meant to report. So a stored string beginning with `=`, `+`, `-` or `@` — or with the
     *    whitespace that hides which of those is first — gets the prefix the spreadsheet world agrees on: a
     *    leading apostrophe, which Excel and LibreOffice read as "this is text" and do not display. The rest of
     *    the field is untouched, so the reader's own words are all still there; the marker is visible to a
     *    program reading the file, which is why it is only ever put on fields that came from stored text and
     *    never on a number this object printed.
     *  * **Quoting, per RFC 4180.** A field is wrapped in double quotes when it holds the delimiter, a quote,
     *    or a line break, and every quote inside it is doubled. A field with neither is left bare, which keeps
     *    the file readable to a person and smaller on disk. Nothing is ever concatenated into a row without
     *    passing through here: a title with a comma in it is the case that breaks a naive `joinToString`, and
     *    it is the ordinary case, not the exotic one.
     */
    private fun quote(field: String, delimiter: Char): String {
        val needsQuoting = field.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }
        val body = field.replace("\"", "\"\"")
        return if (needsQuoting) "\"$body\"" else body
    }

    private fun guarded(value: String): String {
        if (value.isEmpty()) return value
        val first = value.first()
        val dangerous = first in FORMULA_LEADERS ||
            (first.isWhitespace() && value.trimStart().firstOrNull()?.let { it in FORMULA_LEADERS } == true)
        return if (dangerous) "$APOSTROPHE$value" else value
    }

    private fun value(number: Int): String = number.toString()
    private fun value(number: Long): String = number.toString()

    private fun decimal(number: Double, digits: Int = 3): String =
        String.format(Locale.ROOT, "%.${digits}f", number).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun iso(date: LocalDate): String = DAY_FORMAT.format(date)

    private fun iso(instant: Long, zone: ZoneId): String = INSTANT_FORMAT.format(Instant.ofEpochMilli(instant).atZone(zone))

    private fun periodKind(period: ReportPeriod): String = when (period) {
        is ReportPeriod.Day -> "day"
        is ReportPeriod.Week -> "week"
        is ReportPeriod.Month -> "month"
        is ReportPeriod.Year -> "year"
        is ReportPeriod.Range -> "range"
    }

    private fun unitOf(measure: GoalMeasure): String = when (measure) {
        GoalMeasure.TASKS -> "tasks"
        GoalMeasure.SECONDS -> "seconds"
        GoalMeasure.SET_STATE -> "items"
    }

    private const val CRLF = "\r\n"
    private const val BYTE_ORDER_MARK = "\uFEFF"
    private const val APOSTROPHE = "'"
    private val FORMULA_LEADERS = charArrayOf('=', '+', '-', '@')
}
