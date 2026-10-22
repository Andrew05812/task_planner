package com.taskplanner.data.report.export

import com.taskplanner.data.report.AchievementsReport
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.CompletedTasksReport
import com.taskplanner.data.report.GoalsReport
import com.taskplanner.data.report.HabitsReport
import com.taskplanner.data.report.WorkedTimeReport
import com.taskplanner.data.report.GoalMeasure
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.testing.ReportFixtures
import com.taskplanner.data.report.Report
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report as a file a spreadsheet or a script reads.
 *
 * Every assertion here is made on the bytes the writer produced, parsed back as a csv parser would, rather
 * than on the string the function returned: a quoting bug that survives only when a field is read again is the
 * bug that costs a reader their data, and the writer's own opinion about its output is not evidence.
 */
class CsvReportTest {

    /** The three bytes a UTF-8 file may open with, spelled out so no invisible character is load-bearing. */
    private val BOM = String(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), Charsets.UTF_8)

    @Test
    fun `the file opens with a byte order mark and the column names`() {
        val bytes = CsvReport.bytes(ReportFixtures.report(), delimiter = ',')
        assertEquals(
            "the three bytes Excel reads as UTF-8",
            listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            bytes.take(3)
        )
        val text = String(bytes, Charsets.UTF_8).removePrefix(BOM)
        assertTrue("rows are separated the way RFC 4180 says", text.contains("\r\n"))
        assertEquals(CsvReport.COLUMNS, parse(text).first())
    }

    @Test
    fun `every row carries the same nine columns`() {
        val report = fullReport()
        parseRows(report).forEachIndexed { index, row ->
            assertEquals("row $index", CsvReport.COLUMNS.size, row.size)
        }
    }

    @Test
    fun `a comma inside a category name is quoted and read back whole`() {
        val report = ReportFixtures.report(
            categoryActivity = listOf(CategoryActivity(1L, "Работа, срочное", 0xFF00FF00.toInt(), 3_600L, 4, 2))
        )
        val rows = parseRows(report).filter { it[0] == "categories" }
        assertTrue(
            "the name was split by its own comma",
            rows.any { it[2] == "Работа, срочное" }
        )
    }

    @Test
    fun `a quote inside the reader's own title is doubled and read back once`() {
        val report = ReportFixtures.report(
            achievements = AchievementsReport(
                listOf(ReportFixtures.grant(title = "«Первый» done", description = "Finish \"10\" tasks."))
            )
        )
        val rows = parseRows(report).filter { it[0] == "achievements" }
        assertTrue(rows.any { it[2] == "«Первый» done" })
        assertTrue(rows.any { it[8] == "Finish \"10\" tasks." })
    }

    @Test
    fun `a newline inside an achievement description stays inside its field`() {
        val report = ReportFixtures.report(
            achievements = AchievementsReport(
                listOf(ReportFixtures.grant(description = "первая\nвторая\r\nтретья"))
            )
        )
        val rows = parseRows(report)
        val described = rows.first { it[3] == "description" }
        assertEquals("первая\nвторая\r\nтретья", described[8])
    }

    @Test
    fun `cyrillic including the letter ё survives the round trip`() {
        val report = ReportFixtures.report(
            categoryActivity = listOf(CategoryActivity(1L, "Ёлки-иголки, щупальца", null, 60L, 1, 1))
        )
        val row = parseRows(report).first { it[0] == "categories" }
        assertEquals("Ёлки-иголки, щупальца", row[2])
    }

    @Test
    fun `an emoji is written exactly as the reader typed it`() {
        // The csv has no font and no text layer, so the report's own rule about marking what cannot be drawn
        // does not apply here: the bytes the reader wrote are the bytes the file holds.
        val report = ReportFixtures.report(
            categoryActivity = listOf(CategoryActivity(1L, "Дом 🏡 и работа", null, 60L, 1, 1))
        )
        val row = parseRows(report).first { it[0] == "categories" }
        assertEquals("Дом 🏡 и работа", row[2])
    }

    @Test
    fun `a title that would be a formula is marked and read back as text`() {
        val report = ReportFixtures.report(
            categoryActivity = listOf(
                CategoryActivity(1L, "=HYPERLINK(\"http://evil\")", null, 60L, 1, 1),
                CategoryActivity(2L, "+1 555 0100", null, 60L, 1, 1),
                CategoryActivity(3L, "-subtract this", null, 60L, 1, 1),
                CategoryActivity(4L, "@mention", null, 60L, 1, 1),
                CategoryActivity(5L, "  =indented", null, 60L, 1, 1)
            )
        )
        val names = parseRows(report).filter { it[0] == "categories" }.map { it[2] }.distinct()
        assertEquals(
            listOf(
                "'=HYPERLINK(\"http://evil\")",
                "'+1 555 0100",
                "'-subtract this",
                "'@mention",
                "'  =indented"
            ),
            names
        )
    }

    @Test
    fun `a figure this report could not answer is an empty field`() {
        val report = ReportFixtures.report(
            goals = GoalsReport(
                rows = listOf(ReportFixtures.goal(measure = GoalMeasure.SET_STATE, moved = null)),
                reachedStanding = 0,
                standing = 1
            )
        )
        val moved = parseRows(report).first { it[3] == "moved_in_period" }
        assertEquals("a null figure must not become a zero", "", moved[4])
    }

    @Test
    fun `a day is an ISO date and a duration is whole seconds`() {
        val report = ReportFixtures.report(
            period = ReportPeriod.Month(java.time.YearMonth.of(2026, 6)),
            workedTime = WorkedTimeReport(
                seconds = 3_600L,
                intervals = 2,
                sessions = 1,
                averageSessionSeconds = 3_600.0,
                perDay = listOf(ReportFixtures.seconds(0L, 4_410L))
            )
        )
        val rows = parseRows(report)
        val dayRow = rows.first { it[0] == "worked_time" && it[7].isNotEmpty() }
        assertEquals("2026-06-01", dayRow[7])
        assertEquals("4410", dayRow[4])
        assertEquals("seconds", dayRow[5])
        val from = rows.first { it[3] == "period_from" }
        assertEquals("2026-06-01", from[7])
    }

    @Test
    fun `a decimal figure keeps its digits and no grouping separator`() {
        val report = ReportFixtures.report(
            summary = ReportFixtures.summary(
                completedTasks = 1,
                days = 3,
                workSessions = 0
            )
        )
        val perDay = parseRows(report).first { it[3] == "completions_per_day" }
        assertEquals("0.333", perDay[4])
        assertFalse("grouping would split a number over an extra field", perDay[4].contains(" "))
    }

    @Test
    fun `the delimiter follows the locale's own convention`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("a locale that writes 3,14 needs semicolons for Excel", ';', CsvReport.defaultDelimiter())
            Locale.setDefault(Locale.US)
            assertEquals(',', CsvReport.defaultDelimiter())
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `the ten sections arrive in the order the report was asked in`() {
        val sections = parseRows(fullReport()).map { it[0] }.filter { it != "meta" }.distinct()
        assertEquals(
            listOf(
                "summary",
                "completed_tasks",
                "worked_time",
                "categories",
                "goals",
                "habits",
                "dynamics",
                "achievements",
                "comparison",
                "findings"
            ),
            sections
        )
    }

    @Test
    fun `a day by day series keeps its order and its empty days`() {
        val report = ReportFixtures.report(
            dynamics = listOf(
                ReportFixtures.day(0L, completions = 1),
                ReportFixtures.day(1L, completions = 0),
                ReportFixtures.day(2L, completions = 3)
            )
        )
        val days = parseRows(report).filter { it[0] == "dynamics" && it[3] == "finished" }
        assertEquals(listOf("2026-06-01", "2026-06-02", "2026-06-03"), days.map { it[7] })
        assertEquals(listOf("1", "0", "3"), days.map { it[4] })
    }

    @Test
    fun `the empty report still names its period and its timezone`() {
        val rows = parseRows(ReportFixtures.report())
        assertTrue(rows.any { it[3] == "timezone" && it[4] == "Europe/Berlin" })
        assertTrue(rows.any { it[3] == "period_from" && it[7] == "2026-06-03" })
        assertTrue(rows.any { it[3] == "days" && it[4] == "1" })
    }

    @Test
    fun `no internal field of the database is written into the file`() {
        val header = CsvReport.COLUMNS
        assertTrue(header.contains("section"))
        listOf("user_id", "sync_state", "is_deleted", "rowid", "password", "token").forEach { forbidden ->
            assertFalse("the column $forbidden has no business in a report", header.contains(forbidden))
        }
        parseRows(fullReport()).forEach { row ->
            assertFalse(row[0].contains("sync"))
        }
    }

    @Test
    fun `a standing figure is marked as standing and never mixed with a period one`() {
        val rows = parseRows(fullReport())
        val open = rows.first { it[3] == "open_tasks" }
        assertEquals("standing", open[6])
        assertEquals("summary", open[0])
        val completed = rows.first { it[3] == "completed_tasks" && it[6] == "period" }
        assertEquals("period", completed[6])
    }

    @Test
    fun `an achievement keeps the sentences it was granted with and the rule that granted it`() {
        val report = ReportFixtures.report(
            achievements = AchievementsReport(
                listOf(ReportFixtures.grant(key = "tasks-50", title = "Fifty done", ruleVersion = 3))
            )
        )
        val rows = parseRows(report).filter { it[1] == "tasks-50" }
        assertEquals("Fifty done", rows.first()[2])
        assertEquals("3", rows.first { it[3] == "rule_version" }[4])
        assertEquals("tasks", rows.first { it[3] == "progress" }[5])
    }

    @Test
    fun `the bytes the writer returns are the bytes a stream would have been given`() {
        val report = fullReport()
        val bytes = CsvReport.bytes(report, delimiter = ',')
        val throughStream = ByteArrayOutputStream()
        throughStream.use { it.write(bytes) }
        assertEquals(bytes.size.toLong(), throughStream.size().toLong())
        assertTrue(String(throughStream.toByteArray(), Charsets.UTF_8).endsWith("\r\n"))
    }

    /**
     * The file and the screen read the same lines.
     *
     * A preview that re-split the finished bytes would have to re-derive where a row ends, and a quoted field
     * holding a line break is the case it gets wrong: the row would be shown as two, in a screen whose whole
     * claim is that this is what the file contains. So the lines are stated once, here, and both readers take
     * them from this object — the writer joins them with its line endings, the report screen shows them.
     */
    @Test
    fun `the lines the writer states are the file with its line endings put back`() {
        val report = fullReport()
        val lines = CsvReport.lines(report, delimiter = ',')
        val text = String(CsvReport.bytes(report, delimiter = ','), Charsets.UTF_8).removePrefix(BOM)

        assertEquals(lines.joinToString("\r\n") + "\r\n", text)
    }

    @Test
    fun `a field holding a line break is one line of the file, not two`() {
        val report = fullReport().copy(
            findings = listOf(
                ReportFixtures.finding(
                    key = "quiet-friday",
                    title = "A finished thing",
                    body = "two\r\nlines of reading",
                    sampleSize = 3,
                    confidence = 70
                )
            )
        )

        val lines = CsvReport.lines(report, delimiter = ',')
        val row = lines.single { it.contains("lines of reading") }
        assertTrue(
            "the field is quoted, because a break inside it is not the end of the row",
            row.contains("\"two\r\nlines of reading\"")
        )

        // What the screen would have shown had it re-split the finished bytes instead of asking the writer:
        // one row of the file arriving as two lines of the preview, in a screen whose claim is that it is
        // showing the file.
        val resplit = String(CsvReport.bytes(report, delimiter = ','), Charsets.UTF_8)
            .removePrefix(BOM)
            .split("\r\n")
            .dropLastWhile { it.isEmpty() }
        assertTrue("re-splitting the bytes breaks that row in two", resplit.size > lines.size)
    }

    private fun fullReport() = ReportFixtures.report(
        period = ReportPeriod.Month(java.time.YearMonth.of(2026, 6)),
        summary = ReportFixtures.summary(
            createdTasks = 4,
            completedTasks = 7,
            workedSeconds = 12_345L,
            intervals = 11,
            workSessions = 3,
            days = 30,
            activeDays = 9
        ),
        completedTasks = CompletedTasksReport(
            count = 7,
            perDay = listOf(ReportFixtures.count(0L, 4), ReportFixtures.count(1L, 3)),
            undatable = 2
        ),
        workedTime = WorkedTimeReport(
            seconds = 12_345L,
            intervals = 11,
            sessions = 3,
            averageSessionSeconds = 4_115.0,
            perDay = listOf(ReportFixtures.seconds(0L, 12_345L))
        ),
        categoryActivity = listOf(
            CategoryActivity(1L, "Разработка", 0xFF6750A4.toInt(), 9_000L, 7, 5),
            CategoryActivity(2L, "Дом", 0xFF00FF00.toInt(), 3_345L, 4, 2),
            CategoryActivity(null, null, null, 0L, 0, 1)
        ),
        goals = GoalsReport(
            rows = listOf(ReportFixtures.goal(), ReportFixtures.goal("Прочитать 5 книг", "goal-2", 2, 5)),
            reachedStanding = 1,
            standing = 2
        ),
        habits = HabitsReport(
            rows = listOf(ReportFixtures.habit()),
            marks = 4,
            keptCount = 1,
            standing = 2
        ),
        dynamics = listOf(ReportFixtures.day(0L, 2, 600L, 1), ReportFixtures.day(1L, 0, 0L, 0)),
        achievements = AchievementsReport(listOf(ReportFixtures.grant())),
        findings = listOf(ReportFixtures.finding())
    )

    /** The file the report would be on disk, parsed back as any reader would: fields, quotes and line ends. */
    private fun parseRows(report: Report): List<List<String>> {
        val text = String(CsvReport.bytes(report, delimiter = ','), Charsets.UTF_8).removePrefix(BOM)
        return parse(text).drop(1)
    }

    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                quoted && character == '"' && text.getOrNull(index + 1) == '"' -> {
                    field.append('"'); index++
                }
                quoted && character == '"' -> quoted = false
                !quoted && character == '"' -> quoted = true
                !quoted && character == ',' -> {
                    row += field.toString(); field = StringBuilder()
                }
                !quoted && (character == '\r' || character == '\n') -> {
                    if (character == '\r' && text.getOrNull(index + 1) == '\n') index++
                    row += field.toString(); field = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(character)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
