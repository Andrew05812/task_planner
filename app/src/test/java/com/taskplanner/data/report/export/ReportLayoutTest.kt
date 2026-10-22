package com.taskplanner.data.report.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.report.AchievementsReport
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.CompletedTasksReport
import com.taskplanner.data.report.DynamicsDay
import com.taskplanner.data.report.GoalMeasure
import com.taskplanner.data.report.GoalsReport
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.report.HabitsReport
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.testing.ReportFixtures
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a report says, before anybody decides where on a page it goes.
 *
 * This is the layer the stage's "a personal report, not a table dump" is judged in, so the assertions are about
 * sentences: which section comes first, what a missing figure is allowed to look like, whether the reader's own
 * stored words came out unchanged, and whether a standing figure was kept away from the period it is not about.
 * The reports here are the hand-built ones from [ReportFixtures], because a test of the words cannot wait on a
 * query to agree about them — `ReportRepositoryTest` already proves what the figures mean.
 *
 * The locale is pinned to one the app's own strings were written in. That is a decision about the test rather
 * than about the feature: a report in German is the same report with different numbers, and a suite that read
 * "1.234" in one place and "1,234" in another would be a suite that failed on whichever machine ran it last.
 * The Cyrillic that the export is really about is the *data*, and it is asserted on below exactly as it was
 * written.
 */
@RunWith(RobolectricTestRunner::class)
class ReportLayoutTest {

    private lateinit var context: Context
    private val palette get() = ReportLayout.palette(context)
    private var defaultLocale = Locale.getDefault()

    @Before
    fun setUp() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    private fun build(report: Report, profileName: String? = null): ReportDocument =
        ReportLayout.build(context, report, profileName, palette)

    @Test
    fun `the ten sections arrive in the order the report was built in`() {
        val headings = build(ReportFixtures.report()).blocks
            .filterIsInstance<ReportBlock.Section>()
            .map { it.text }
        assertEquals(
            listOf(
                "Summary",
                "Finished tasks",
                "Measured work",
                "Where the work went",
                "Goals",
                "Habits",
                "Day by day",
                "Achievements",
                "Against the period before",
                "What the app noticed",
                "About these figures"
            ),
            headings
        )
    }

    @Test
    fun `the document names the period, the timezone and the moment it was written`() {
        val document = build(
            ReportFixtures.report(period = ReportPeriod.Month(YearMonth.of(2026, 6)))
        )
        val meta = document.meta.joinToString(" | ")
        assertTrue(meta, meta.contains("A month: Jun 1, 2026 to Jun 30, 2026"))
        assertTrue(meta, meta.contains("Europe/Berlin"))
        assertTrue(meta, meta.contains("Written"))
    }

    @Test
    fun `a week says which day its weeks begin on and a day does not`() {
        val week = build(ReportFixtures.report(period = ReportPeriod.Week(LocalDate.of(2026, 6, 3), java.time.DayOfWeek.MONDAY)))
        assertTrue(week.meta.joinToString(" "), week.meta.any { it.contains("Weeks begin on Monday") })

        val day = build(ReportFixtures.report())
        assertFalse(day.meta.joinToString(" "), day.meta.any { it.contains("Weeks begin on") })
    }

    @Test
    fun `the profile is named when the caller names it and absent when it does not`() {
        val named = build(ReportFixtures.report(), profileName = "Анна").meta.joinToString(" ")
        assertTrue(named, named.contains("Анна"))
        val unnamed = build(ReportFixtures.report()).meta.joinToString(" ")
        assertFalse(unnamed, unnamed.contains("For the profile"))
    }

    @Test
    fun `an empty period is said rather than counted in zeroes`() {
        val texts = build(ReportFixtures.report()).allText()
        assertTrue(texts.any { it == "No task was finished in these days." })
        assertTrue(texts.any { it == "Nothing was measured in these days." })
        assertTrue(texts.any { it == "No achievement was earned in these days." })
        assertTrue(texts.any { it == "The app had nothing to say about these days." })
        // A section with no answer is a sentence, not a row of figures that read as measurements.
        assertFalse(texts.any { it.matches(Regex("0 tasks finished")) })
    }

    @Test
    fun `the standing figures are separated from the period by the sentence that names them`() {
        val blocks = build(ReportFixtures.report()).blocks
        val note = blocks.indexOfFirst { it is ReportBlock.Paragraph && (it as ReportBlock.Paragraph).text.startsWith("Everything above") }
        val summary = blocks.indexOfFirst { it is ReportBlock.Section }
        val openNow = blocks.indexOfFirst {
            it is ReportBlock.KeyValue && it.label == "Still open"
        }
        assertTrue("the note is missing", note > 0)
        assertTrue("the note came before the period figures", note > summary)
        assertTrue("the standing figure arrived before the sentence that labels it", openNow > note)
    }

    @Test
    fun `a figure the report cannot answer is the app's own dash`() {
        val report = ReportFixtures.report(
            summary = ReportFixtures.summary(workedSeconds = 0L, workSessions = 0),
            goals = GoalsReport(listOf(ReportFixtures.goal(measure = GoalMeasure.SET_STATE, moved = null)), 0, 1)
        )
        val blocks = build(report).blocks
        val table = blocks.filterIsInstance<ReportBlock.Table>().first { table ->
            table.columns.any { it.title == "Moved in the period" }
        }
        val row = table.rows.first()
        assertEquals("a set that names nothing has no progress to state", "—", row[1])
        assertEquals("—", row[2])
        assertEquals("nothing moved in a period that cannot be dated", "—", row[3])
        val average = blocks.filterIsInstance<ReportBlock.KeyValue>().first { it.label == "Average session" }
        assertEquals("—", average.value)
    }

    @Test
    fun `the reader's own words are printed as they were stored`() {
        val grant = ReportFixtures.grant(
            title = "Первая сотня",
            description = "Сто задач закрыто.\nТретья строка — с тире и кавычками \"вот так\"."
        )
        val texts = build(ReportFixtures.report(achievements = AchievementsReport(listOf(grant)))).allText()
        assertTrue(texts.any { it == "Первая сотня" })
        assertTrue(
            "the stored description was rewritten",
            texts.any { it.contains("Сто задач закрыто.") && it.contains("кавычками \"вот так\"") }
        )
        assertTrue(texts.any { it.contains("10 of 10 tasks") })
    }

    @Test
    fun `a picture the font cannot draw is marked, and the report says so once`() {
        val report = ReportFixtures.report(
            categoryActivity = listOf(CategoryActivity(1L, "Дом 🏡", null, 600L, 2, 1))
        )
        val document = build(report)
        val texts = document.allText()
        val marked = texts.filter { it.contains("Дом") }
        assertTrue(marked.joinToString(" "), marked.all { it.contains("[?]") })
        assertEquals(
            "the explanation should be printed once",
            1,
            texts.count { it.startsWith("Some characters in the reader") }
        )
    }

    @Test
    fun `a period short enough to list does list every day in order`() {
        val report = ReportFixtures.report(
            period = ReportPeriod.Week(LocalDate.of(2026, 6, 3), java.time.DayOfWeek.MONDAY),
            dynamics = (0..6).map { ReportFixtures.day(it.toLong(), completions = it) }
        )
        val table = build(report).blocks.filterIsInstance<ReportBlock.Table>()
            .first { it.columns.first().title == "Day" }
        assertEquals(7, table.rows.size)
        assertEquals("Jun 1, 2026", table.rows.first()[0])
        assertEquals("Jun 7, 2026", table.rows.last()[0])
    }

    @Test
    fun `a long period draws the whole series and names only the busiest days`() {
        val report = ReportFixtures.report(
            period = ReportPeriod.Month(YearMonth.of(2026, 6)),
            dynamics = (0..354).map { ReportFixtures.day(it.toLong(), completions = it % 4, seconds = (it % 9) * 900L) }
        )
        val blocks = build(report).blocks
        val charts = blocks.filterIsInstance<ReportBlock.Chart>().map { it.chart }
        assertEquals("both series should be drawn", 2, charts.size)
        val bars = charts.filterIsInstance<ReportChart.Bars>()
        assertEquals(355, bars.first().columns.size)
        val tables = blocks.filterIsInstance<ReportBlock.Table>()
        val busiest = tables.first { it.columns.first().title == "The busiest days" }
        assertEquals(10, busiest.rows.size)
        assertTrue(blocks.filterIsInstance<ReportBlock.Paragraph>().any {
            it.text.startsWith("These days are too many")
        })
    }

    @Test
    fun `a chart is offered only when there is something to draw`() {
        val flat = ReportFixtures.report(
            period = ReportPeriod.Week(LocalDate.of(2026, 6, 3), java.time.DayOfWeek.MONDAY),
            dynamics = (0..6).map { DynamicsDay(0L, LocalDate.of(2026, 6, 1), 0, 0L, 0) }
        )
        val blocks = build(flat).blocks
        assertTrue("a series of nothing is not a chart", blocks.none { it is ReportBlock.Chart })
        assertTrue(blocks.filterIsInstance<ReportBlock.Paragraph>().any { it.text == "These days hold no work to draw." })
    }

    @Test
    fun `categories keep the order the report put them in`() {
        val report = ReportFixtures.report(
            categoryActivity = listOf(
                CategoryActivity(1L, "Разработка", 0xFF6750A4.toInt(), 9_000L, 7, 5),
                CategoryActivity(2L, "Дом", 0xFF00FF00.toInt(), 3_345L, 4, 2),
                CategoryActivity(null, null, null, 0L, 0, 1)
            )
        )
        val table = build(report).blocks.filterIsInstance<ReportBlock.Table>().first()
        assertEquals(listOf("Разработка", "Дом", "No category, or one that has since been deleted."), table.rows.map { it[0] })
        val shares = build(report).blocks.filterIsInstance<ReportBlock.Chart>().first().chart as ReportChart.Shares
        assertEquals(listOf("Разработка", "Дом"), shares.parts.map { it.label })
    }

    @Test
    fun `the unclassified bucket is named and not hidden`() {
        val report = ReportFixtures.report(
            categoryActivity = listOf(CategoryActivity(null, null, null, 600L, 1, 0))
        )
        val blocks = build(report).blocks
        assertTrue(blocks.filterIsInstance<ReportBlock.Note>().any { it.text.startsWith("No category") })
    }

    @Test
    fun `a comparison over no base says so instead of dividing by it`() {
        val report = ReportFixtures.report().withComparison(
            completions = com.taskplanner.data.statistics.DashboardMath.change(4L, 0L)
        )
        val table = build(report).blocks.filterIsInstance<ReportBlock.Table>()
            .first { it.columns.first().title == "Measure" }
        val completions = table.rows.first { it[0] == "Finished" }
        assertEquals("0", completions[1])
        assertEquals("4", completions[2])
        assertEquals("from nothing", completions[3])
        assertTrue(
            "the report should name the window it compared against",
            build(report).allText().any { it.startsWith("The period before:") }
        )
    }

    @Test
    fun `two empty periods are not reported as staying the same`() {
        val document = build(ReportFixtures.report())
        assertTrue(
            document.allText().joinToString(" | "),
            document.allText().any { it == "The period before this one has nothing to be measured against." }
        )
        // A table of nothing against nothing would be a comparison the report has no right to print.
        assertTrue(document.blocks.filterIsInstance<ReportBlock.Table>().none {
            it.columns.first().title == "Measure"
        })
    }

    @Test
    fun `an achievement keeps the rule edition it was granted on`() {
        val report = ReportFixtures.report(
            achievements = AchievementsReport(listOf(ReportFixtures.grant(ruleVersion = 4)))
        )
        val texts = build(report).allText()
        assertTrue(texts.any { it.contains("10 of 10 tasks") })
        // The version is the row's own, and the sentences printed beside it are the stored ones; neither is
        // re-derived here, so the report cannot rewrite the reader's history.
        assertEquals(0, texts.count { it.contains("Five hundred") })
    }

    @Test
    fun `a habit percentage that has no schedule behind it is a dash`() {
        val report = ReportFixtures.report(
            habits = HabitsReport(listOf(ReportFixtures.habit(kept = 0, asked = 0, marks = 0)), 0, 0, 1)
        )
        val table = build(report).blocks.filterIsInstance<ReportBlock.Table>().first { it.columns.first().title == "Habit" }
        assertEquals("—", table.rows.first()[2])
    }

    @Test
    fun `a decrease is printed with one sign, not two`() {
        val report = ReportFixtures.report().withComparison(
            completions = com.taskplanner.data.statistics.DashboardMath.change(3L, 12L)
        )
        val table = build(report).blocks.filterIsInstance<ReportBlock.Table>()
            .first { it.columns.first().title == "Measure" }
        val row = table.rows.first { it[0] == "Finished" }
        assertEquals("12", row[1])
        assertEquals("3", row[2])
        assertEquals("−75%", row[3])
    }

    @Test
    fun `a goal that names a set is explained where its dash sits`() {
        val report = ReportFixtures.report(
            goals = GoalsReport(listOf(ReportFixtures.goal(measure = GoalMeasure.SET_STATE)), 0, 1)
        )
        val notes = build(report).blocks.filterIsInstance<ReportBlock.Note>().map { it.text }
        assertTrue(notes.joinToString(" | "), notes.any { it.startsWith("A goal that names a set") })
    }

    @Test
    fun `a finding keeps its own kind and the sample it was counted from`() {
        val report = ReportFixtures.report(
            findings = listOf(ReportFixtures.finding(kind = InsightKind.HYPOTHESIS, sampleSize = 7, confidence = 62))
        )
        val texts = build(report).allText()
        assertTrue(texts.any { it == "a hypothesis" })
        assertTrue(texts.any { it.contains("7 in the sample, 62% confidence") })
    }

    @Test
    fun `a reading the app has stopped making is said as one`() {
        val report = ReportFixtures.report(findings = listOf(ReportFixtures.finding(current = false)))
        assertTrue(build(report).allText().any { it == "A reading the app has stopped making." })
    }

    @Test
    fun `the closing note states the window in the zone the report was counted in`() {
        val texts = build(
            ReportFixtures.report(period = ReportPeriod.Month(YearMonth.of(2026, 2)))
        ).allText()
        assertTrue(
            texts.joinToString(" | "),
            texts.any { it.startsWith("The period runs from Feb 1, 2026 to Feb 28, 2026, counted in Europe/Berlin") }
        )
        assertTrue(texts.any { it.startsWith("Written by Task Planner") })
    }

    @Test
    fun `a month of February asks its own question of the year it is in`() {
        val texts = build(ReportFixtures.report(period = ReportPeriod.Month(YearMonth.of(2024, 2)))).allText()
        assertTrue(texts.joinToString(" | "), texts.any { it.contains("to Feb 29, 2024") })
    }

    /** The same report, with the comparison section answering about figures the fixture did not set. */
    private fun Report.withComparison(
        completions: com.taskplanner.data.statistics.FigureChange
    ): Report = copy(comparison = comparison.copy(completions = completions))

    /** Every word a document holds, in the order a reader meets them. */
    private fun ReportDocument.allText(): List<String> = blocks.flatMap { block ->
        when (block) {
            is ReportBlock.Title -> listOf(block.text)
            is ReportBlock.MetaLine -> listOf(block.text)
            is ReportBlock.Section -> listOf(block.text)
            is ReportBlock.Paragraph -> listOf(block.text)
            is ReportBlock.Note -> listOf(block.text)
            is ReportBlock.KeyValue -> listOf(block.label, block.value)
            is ReportBlock.Table -> block.columns.map { it.title } + block.rows.flatten()
            is ReportBlock.Chart -> when (val chart = block.chart) {
                is ReportChart.Bars -> listOfNotNull(chart.title, chart.unit, chart.caption) + chart.columns.mapNotNull { it.label }
                is ReportChart.Shares ->
                    listOfNotNull(chart.title, chart.totalText, chart.omitted) + chart.parts.map { it.label } +
                        chart.parts.map { it.valueText }
            }
        }
    }
}
