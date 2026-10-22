package com.taskplanner.data.report.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.data.report.AchievementsReport
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.CompletedTasksReport
import com.taskplanner.data.report.DayCount
import com.taskplanner.data.report.DaySeconds
import com.taskplanner.data.report.GoalsReport
import com.taskplanner.data.report.HabitsReport
import com.taskplanner.data.report.OpenWorkStanding
import com.taskplanner.data.report.PeriodComparison
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportSummary
import com.taskplanner.data.report.ReportWindow
import com.taskplanner.data.report.WorkedTimeReport
import com.taskplanner.data.statistics.DashboardMath
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What only a handset can answer about the PDF export.
 *
 * The pages of a report are decided in [ReportPagination], which is unit-tested on the JVM; the marks they
 * hold are handed to [PdfReport], which is unit-tested against a recording surface; and between those two sits
 * the thing no JVM test can reach — the platform's document writer, which on Robolectric accepts every drawing
 * call and hands back a file of no bytes at all. This file is that last check, and it is deliberately small:
 * it asks whether a real handset produces real PDF bytes, whether it embeds the font those bytes' Cyrillic is
 * drawn with, and how long the whole thing takes there.
 *
 * It runs on a device or an emulator, not in CI: the workflow compiles the instrumented sources and stops
 * there, because a runner has no handset to run them on. Whoever checks this feature by hand runs this class
 * first and reads the timings it prints.
 */
@RunWith(AndroidJUnit4::class)
class PdfReportInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aHandsetWritesRealPdfBytesForAReport() {
        val paged = pages()
        val surface = PdfDocumentSurface()
        val out = ByteArrayOutputStream()

        val started = System.nanoTime()
        PdfReport.draw(paged, surface)
        val bytes = surface.finish(out)
        val millis = (System.nanoTime() - started) / 1_000_000

        val written = out.toByteArray()
        assertTrue("the document writer produced no bytes", bytes > 0L)
        assertEquals("%PDF-", String(written, 0, 5, Charsets.ISO_8859_1))
        assertTrue("a PDF ends with its trailer marker", String(written, Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF"))
        // The font question in one assertion: a PDF that carries a FontFile2 entry has the glyph outlines
        // inside the file, which is what makes "Привет, ё" readable on a machine that has no Roboto.
        assertTrue(
            "no embedded font program: the Cyrillic would depend on whatever the reader has",
            String(written, Charsets.ISO_8859_1).contains("/FontFile2")
        )
        println("PDF pages=${paged.pageCount} bytes=$bytes layoutAndDrawMillis=$millis")
    }

    @Test
    fun aStagedFileIsHandedOverAsAContentUriAndNeverAsAPath() {
        val directory = java.io.File(context.cacheDir, "report_exports").apply { mkdirs() }
        val file = java.io.File(directory, "taskplanner-report-instrumented.csv")
        file.writeBytes("section,subject_id
".toByteArray(Charsets.UTF_8))

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            ReportExportRepository.AUTHORITY,
            file
        )

        assertEquals("content", uri.scheme)
        assertEquals(ReportExportRepository.AUTHORITY, uri.authority)
        assertFalse(uri.toString(), uri.toString().startsWith("file://"))
        file.delete()
    }

    @Test
    fun aLongReportStillFitsInsideTheSheetsItWasGiven() {
        val geometry = ReportPageGeometry.A4_PORTRAIT
        val paged = pages(dynamicsDays = 366, categoryRows = 40)
        assertTrue("a year of days should need several sheets", paged.pageCount > 1)
        paged.pages.forEach { page ->
            page.draws.forEach { draw ->
                when (draw) {
                    is ReportDraw.Text -> assertTrue(
                        "'${draw.text}' below the sheet",
                        draw.baseline <= geometry.height - geometry.marginBottom + 1f
                    )
                    is ReportDraw.Rect -> assertTrue(draw.bottom <= geometry.height.toFloat())
                    is ReportDraw.Rule -> assertTrue(draw.y <= geometry.height.toFloat())
                }
            }
        }
    }

    private fun pages(dynamicsDays: Int = 31, categoryRows: Int = 3) = ReportPagination.paginate(
        document = ReportLayout.build(context, report(dynamicsDays, categoryRows), "Anna", ReportLayout.palette(context)),
        measurer = PaintReportMeasurer(),
        palette = ReportLayout.palette(context)
    )

    /** A report of the same shape the JVM tests use, built here because the two sources cannot share one. */
    private fun report(days: Int, categories: Int): Report {
        val zone = ZoneId.of("Europe/Berlin")
        val first = LocalDate.of(2026, 1, 1)
        val window = ReportWindow.of(ReportPeriod.Day(first), zone, today = first, generatedAt = 1_767_225_600_000L)
        return Report(
            window = window,
            summary = ReportSummary(
                createdTasks = 40,
                completedTasks = 25,
                workedSeconds = 123_456L,
                intervals = 30,
                workSessions = 12,
                averageSessionSeconds = 10_288.0,
                days = 1,
                activeDays = 1,
                completionsPerDay = 25.0,
                standing = OpenWorkStanding(6, 1, 0, 2, 1, 1, 0, 8, 0.125f),
                undatedCompletions = 0
            ),
            completedTasks = CompletedTasksReport(25, (0 until days).map { DayCount(first.plusDays(it.toLong()).toEpochDay(), first.plusDays(it.toLong()), it % 4) }, 0),
            workedTime = WorkedTimeReport(123_456L, 30, 12, 10_288.0, (0 until days).map { DaySeconds(first.plusDays(it.toLong()).toEpochDay(), first.plusDays(it.toLong()), 4_000L + it) }),
            categoryActivity = (0 until categories).map { CategoryActivity(it.toLong(), "Категория $it с длинным названием", 0xFF6750A4.toInt(), (categories - it) * 1_000L, it, it % 3) },
            goals = GoalsReport(emptyList(), 0, 0),
            habits = HabitsReport(emptyList(), 0, 0, 0),
            dynamics = (0 until days).map {
                com.taskplanner.data.report.DynamicsDay(
                    localDay = first.plusDays(it.toLong()).toEpochDay(),
                    date = first.plusDays(it.toLong()),
                    completions = it % 4,
                    workedSeconds = 4_000L + it,
                    habitMarks = it % 2
                )
            },
            achievements = AchievementsReport(emptyList()),
            comparison = PeriodComparison(
                window = window.preceding(),
                completions = DashboardMath.change(25L, 18L),
                workedSeconds = DashboardMath.change(123_456L, 100_000L),
                createdTasks = DashboardMath.change(40L, 0L),
                habitMarks = DashboardMath.change(0L, 0L),
                achievements = DashboardMath.change(0L, 0L)
            ),
            findings = emptyList()
        )
    }
}
