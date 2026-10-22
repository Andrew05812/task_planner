package com.taskplanner.data.report.export

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.report.AchievementsReport
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.CompletedTasksReport
import com.taskplanner.data.report.DayCount
import com.taskplanner.data.report.DaySeconds
import com.taskplanner.data.report.DynamicsDay
import com.taskplanner.data.report.GoalsReport
import com.taskplanner.data.report.HabitsReport
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportSummary
import com.taskplanner.data.report.WorkedTimeReport
import com.taskplanner.testing.AppFixture
import com.taskplanner.testing.ReportFixtures
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a large report costs to write.
 *
 * §12 of the stage asks for the export's measurements beside the report's, and the honest scope of this file is
 * the half a JVM can reach: the sentences [ReportLayout] builds, the pages [ReportPagination] packs, and the
 * bytes [CsvReport] prints. The part it cannot measure is the handset's — `PdfDocument` draws into native
 * memory on a device, and no number printed here says anything about that. `PdfReportInstrumentedTest` is the
 * one that does, and it is run by hand.
 *
 * The assertions are absolute and roomy on purpose, and none of them compares two timings to each other: a
 * growth ratio between two JVM measurements measures whichever thread the runner scheduled last. What is worth
 * promising is a ceiling the reader's own limits make — a file that has to be mailed, a document that has to be
 * printed, a screen that has to say "written" before the user gives up — and the medians underneath those
 * ceilings are printed so the next change can see what it moved.
 */
@RunWith(RobolectricTestRunner::class)
class ReportExportBenchmarkTest {

    private lateinit var context: Context
    private var defaultZone: TimeZone = TimeZone.getDefault()
    private var defaultLocale: Locale = Locale.getDefault()

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val palette get() = ReportLayout.palette(context)
    private val measurer = ReportFixtures.FixedMeasurer()

    /**
     * A file a reader can mail and a spreadsheet can open without a warning.
     *
     * Measured here at 410 KB for the fullest account the app can produce (see the case below), so the ceiling
     * is about ten times what the code ever writes: it is a tripwire against a writer that started repeating
     * rows, not a claim that a larger file would be refused.
     */
    private val csvCeilingBytes = 4L * 1024L * 1024L

    /** A document nobody prints. The fullest account measured here fills 37 sheets of it. */
    private val pageCeiling = 60

    /**
     * Half a second for each of the three passes.
     *
     * Measured on this JVM at 86, 33 and 25 ms for the maximal account, the ceiling is roughly ten times that,
     * which is the margin a handset earns: a mid-range phone gives up two to four times the per-operation cost
     * of the machine running these tests, and a screen that has promised a progress state has to stay inside
     * what a user will wait for. It is an absolute bound on purpose — no ratio between two timings of one JVM
     * run, which measures the scheduler rather than the code.
     */
    private val layoutCeilingMillis = 500.0
    private val pagingCeilingMillis = 500.0
    private val csvCeilingMillis = 500.0

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.US)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `a year's report pages, writes and stays inside the costs a screen can promise`() {
        val document = ReportLayout.build(context, bigReport(), "Anna", palette)
        val layoutMillis = median { ReportLayout.build(context, bigReport(), "Anna", palette).blocks.size }
        val pagingMillis = median { ReportPagination.paginate(document, measurer = measurer, palette = palette).pageCount }
        val csvMillis = median { CsvReport.bytes(bigReport(), ',').size }

        val paged = ReportPagination.paginate(document, measurer = measurer, palette = palette)
        val bytes = CsvReport.bytes(bigReport(), ',')

        println(
            "EXPORT year report: blocks=${document.blocks.size} pages=${paged.pageCount} " +
                "draws=${paged.draws.size} csvBytes=${bytes.size} " +
                "layoutMillis=$layoutMillis pagingMillis=$pagingMillis csvMillis=$csvMillis " +
                "jvmHeapMaxMb=${Runtime.getRuntime().maxMemory() / 1024 / 1024}"
        )

        assertTrue("the layout took $layoutMillis ms", layoutMillis < layoutCeilingMillis)
        assertTrue("the paging took $pagingMillis ms", pagingMillis < pagingCeilingMillis)
        assertTrue("the csv took $csvMillis ms", csvMillis < csvCeilingMillis)
        assertTrue("the csv was ${bytes.size} bytes", bytes.size.toLong() < csvCeilingBytes)
        assertTrue("the document ran to ${paged.pageCount} sheets", paged.pageCount in 2..pageCeiling)
    }

    @Test
    fun `the file grows with the rows the period holds and not with the figures beside them`() {
        val day = CsvReport.bytes(ReportFixtures.report(), ',').size
        val year = CsvReport.bytes(bigReport(), ',').size
        println("EXPORT size: a day's report=$day bytes, a year's=$year bytes")

        // A day's report is a page of sentences; a year's is that plus every day it lived through. Neither is
        // a file a reader cannot open, and the ceiling is the mailing one the test above already states.
        assertTrue("a day's export was $day bytes", day in 1..100_000)
        assertTrue("a year's export was $year bytes", year in day..csvCeilingBytes.toInt())
    }

    @Test
    fun `the fullest account a profile can hold is still a document and still a file`() {
        // What the export is asked to write is a report, and a report's size is settled by the period and the
        // lists the profile holds — at most 366 day rows for a year, one row per category, per goal, per habit,
        // the twenty-five grants the catalogue can hand out, the findings the notebook is holding. The number of
        // *tasks* behind those figures does not reach this code at all: `ReportRepository` collapses them in SQL,
        // and `ReportWindowBenchmarkTest` measured that half (106, 221 and 326 ms for accounts of 1 000, 5 000
        // and 10 000 tasks). This case measures the other half, at the shape a maximal account produces.
        val report = fullAccount()
        val document = ReportLayout.build(context, report, "Anna", palette)
        val layoutMillis = median { ReportLayout.build(context, fullAccount(), "Anna", palette) }
        val pagingMillis = median { ReportPagination.paginate(document, measurer = measurer, palette = palette) }
        val csvMillis = median { CsvReport.bytes(fullAccount(), ',') }

        val paged = ReportPagination.paginate(document, measurer = measurer, palette = palette)
        val bytes = CsvReport.bytes(report, ',')
        println(
            "EXPORT full account: rows=${report.dynamics.size} categories=${report.categoryActivity.size} " +
                "goals=${report.goals.rows.size} habits=${report.habits.rows.size} " +
                "grants=${report.achievements.granted} findings=${report.findings.size} " +
                "blocks=${document.blocks.size} pages=${paged.pageCount} draws=${paged.draws.size} " +
                "csvBytes=${bytes.size} layoutMillis=$layoutMillis pagingMillis=$pagingMillis " +
                "csvMillis=$csvMillis"
        )

        assertTrue("the layout took $layoutMillis ms", layoutMillis < layoutCeilingMillis)
        assertTrue("the paging took $pagingMillis ms", pagingMillis < pagingCeilingMillis)
        assertTrue("the csv took $csvMillis ms", csvMillis < csvCeilingMillis)
        assertTrue("the csv was ${bytes.size} bytes", bytes.size.toLong() < csvCeilingBytes)
        assertTrue("the document ran to ${paged.pageCount} sheets", paged.pageCount in 2..pageCeiling)
    }

    /** A year the reader worked in every day, with every section full. */
    private fun bigReport(): Report {
        val first = LocalDate.of(2026, 1, 1)
        val window = ReportFixtures.window(ReportPeriod.Year(2026))
        val days = (0 until 365).map { index ->
            val date = first.plusDays(index.toLong())
            DynamicsDay(date.toEpochDay(), date, index % 5, (index % 9) * 1_200L, index % 2)
        }
        return Report(
            window = window,
            summary = ReportSummary(
                createdTasks = 1_840,
                completedTasks = 913,
                workedSeconds = 1_298_400L,
                intervals = 4_102,
                workSessions = 1_503,
                averageSessionSeconds = 863.9,
                days = 365,
                activeDays = 288,
                completionsPerDay = 2.5,
                standing = com.taskplanner.data.report.OpenWorkStanding(
                    openTasks = 41,
                    overdue = 9,
                    dueToday = 3,
                    dueThisWeek = 12,
                    withoutDeadline = 6,
                    inProgress = 4,
                    archivedTasks = 120,
                    totalTasks = 1_840,
                    lifetimeCompletionRate = 0.497f
                ),
                undatedCompletions = 12
            ),
            completedTasks = CompletedTasksReport(913, days.map { DayCount(it.localDay, it.date, it.completions) }, 12),
            workedTime = WorkedTimeReport(
                1_298_400L,
                4_102,
                1_503,
                863.9,
                days.map { DaySeconds(it.localDay, it.date, it.workedSeconds) }
            ),
            categoryActivity = (0 until 40).map { index ->
                CategoryActivity(
                    categoryId = index.toLong(),
                    categoryName = "Категория с длинным названием «$index», и ещё одна",
                    color = ContextCompat.getColor(context, com.taskplanner.R.color.report_accent),
                    seconds = (40 - index) * 31_000L,
                    intervals = (40 - index) * 7,
                    completions = (40 - index) * 3
                )
            },
            goals = GoalsReport((0 until 30).map { index -> ReportFixtures.goal("Цель $index с длинным названием", "goal-$index") }, 7, 30),
            habits = HabitsReport((0 until 30).map { index -> ReportFixtures.habit("Привычка $index, которую держат", "habit-$index") }, 21, 21, 30),
            dynamics = days,
            achievements = AchievementsReport(
                (0 until 25).map {
                    ReportFixtures.grant(
                        key = "rule-$it",
                        title = "Достижение $it 🏡",
                        description = "Условие, записанное в тот день, когда его выдали.\nВторая и третья строки того же текста, чтобы многострочный рассказ попал в файл.",
                        progress = 10L + it
                    )
                }
            ),
            comparison = ReportFixtures.report().comparison,
            findings = (0 until 20).map { ReportFixtures.finding(key = "rule-$it", title = "Наблюдение $it") }
        )
    }

    /**
     * The largest report a profile's own lists can produce: a full year of day rows, three hundred categories,
     * a hundred and twenty goals and habits each, every grant the catalogue hands out, and a notebook full of
     * findings.
     *
     * Built rather than seeded because this is a shape, not a storage question: the figures a report carries
     * have a ceiling set by the account's lists, and this is what sits at it.
     */
    private fun fullAccount(): Report {
        val first = LocalDate.of(2026, 1, 1)
        val days = (0 until 366).map { index ->
            val date = first.plusDays(index.toLong())
            DynamicsDay(date.toEpochDay(), date, index % 6, (index % 12) * 1_800L, index % 3)
        }
        return Report(
            window = ReportFixtures.window(ReportPeriod.Year(2026)),
            summary = ReportSummary(
                createdTasks = 5_400,
                completedTasks = 2_190,
                workedSeconds = 3_985_200L,
                intervals = 12_004,
                workSessions = 4_103,
                averageSessionSeconds = 971.3,
                days = 366,
                activeDays = 301,
                completionsPerDay = 6.0,
                standing = com.taskplanner.data.report.OpenWorkStanding(
                    openTasks = 180,
                    overdue = 44,
                    dueToday = 9,
                    dueThisWeek = 31,
                    withoutDeadline = 27,
                    inProgress = 12,
                    archivedTasks = 610,
                    totalTasks = 5_400,
                    lifetimeCompletionRate = 0.405f
                ),
                undatedCompletions = 48
            ),
            completedTasks = CompletedTasksReport(2_190, days.map { DayCount(it.localDay, it.date, it.completions) }, 48),
            workedTime = WorkedTimeReport(
                3_985_200L,
                12_004,
                4_103,
                971.3,
                days.map { DaySeconds(it.localDay, it.date, it.workedSeconds) }
            ),
            categoryActivity = (0 until 300).map { index ->
                CategoryActivity(
                    categoryId = index.toLong(),
                    categoryName = "Категория с длинным названием «$index», и ещё одна",
                    color = ContextCompat.getColor(context, com.taskplanner.R.color.report_accent),
                    seconds = (300 - index) * 9_000L,
                    intervals = (300 - index) * 4,
                    completions = (300 - index) % 17
                )
            },
            goals = GoalsReport(
                (0 until 120).map { index -> ReportFixtures.goal("Цель $index с длинным названием", "goal-$index") },
                40,
                120
            ),
            habits = HabitsReport(
                (0 until 120).map { index -> ReportFixtures.habit("Привычка $index, которую держат", "habit-$index") },
                90,
                90,
                120
            ),
            dynamics = days,
            achievements = AchievementsReport(
                (0 until 25).map {
                    ReportFixtures.grant(
                        key = "catalogue-$it",
                        title = "Достижение $it 🏡",
                        description = "Условие, записанное в тот день, когда его выдали.\nВторая и третья строки того же текста, чтобы многострочный рассказ попал в файл.",
                        progress = 10L + it
                    )
                }
            ),
            comparison = ReportFixtures.report().comparison,
            findings = (0 until 60).map { ReportFixtures.finding(key = "notebook-$it", title = "Наблюдение $it") }
        )
    }

    /** The middle of seven timings, because the first run of anything on a JVM includes whatever it loads. */
    private fun median(block: suspend () -> Any?): Double {
        val runs = ArrayList<Double>(7)
        repeat(7) {
            val started = System.nanoTime()
            runBlocking { block() }
            runs += (System.nanoTime() - started) / 1_000_000.0
        }
        return runs.sorted()[runs.size / 2]
    }
}
