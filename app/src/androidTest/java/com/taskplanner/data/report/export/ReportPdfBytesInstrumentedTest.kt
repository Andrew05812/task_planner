package com.taskplanner.data.report.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.data.report.AchievementsReport
import com.taskplanner.data.report.CategoryActivity
import com.taskplanner.data.report.CompletedTasksReport
import com.taskplanner.data.report.DayCount
import com.taskplanner.data.report.DaySeconds
import com.taskplanner.data.report.DynamicsDay
import com.taskplanner.data.report.GoalsReport
import com.taskplanner.data.report.HabitsReport
import com.taskplanner.data.report.OpenWorkStanding
import com.taskplanner.data.report.PeriodComparison
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportSummary
import com.taskplanner.data.report.ReportWindow
import com.taskplanner.data.report.WorkedTimeReport
import com.taskplanner.data.statistics.ChangeDirection
import com.taskplanner.data.statistics.DashboardMath
import com.taskplanner.data.statistics.FigureChange
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the bytes of a real report PDF actually contain.
 *
 * Part 4 wrote [PdfReportInstrumentedTest] with one assertion about fonts — that the file carries a `FontFile2`
 * entry — and this sub-stage is the first occasion it could run on a handset. It failed there, and the fault was
 * in the test rather than in the document: the platform's writer carries its letterforms as `Type3` outline
 * programs with a `ToUnicode` map beside them, not as a TrueType program, and it keeps its dictionaries inside
 * compressed streams, so a search of the raw bytes for one key reports "no font" about a file that has one.
 * [ReportPdfInspection] is the corrected question, and this file asks it of a report with Russian in it:
 *
 *  * the file is a PDF — header, trailer, and not an empty output;
 *  * it carries its own letterforms and a text layer beside them, so `Привет, ё` is readable and searchable on a
 *    machine that has never heard of Roboto;
 *  * and every page the pagination counted is a page the platform's renderer opens **with ink on it** — the one
 *    check that separates a well-formed blank from a report.
 *
 * The bytes are also written to the app's external files directory before anything is asserted, so the exact file
 * that answered can be pulled off the device and examined by something that is not this test.
 */
@RunWith(AndroidJUnit4::class)
class ReportPdfBytesInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theBytesOfARealReportCarryItsOwnLetterformsAndItsPages() {
        val paged = ReportExportFixtures.pages(context)
        val surface = PdfDocumentSurface()
        val out = ByteArrayOutputStream()

        val started = System.nanoTime()
        PdfReport.draw(paged, surface)
        val bytes = surface.finish(out)
        val millis = (System.nanoTime() - started) / 1_000_000
        val written = out.toByteArray()

        assertTrue("the document writer produced no bytes", bytes > 0L)
        assertEquals(bytes, written.size.toLong())
        assertEquals("%PDF-", String(written, 0, 5, Charsets.ISO_8859_1))
        assertTrue(
            "a PDF ends with its trailer marker",
            String(written, Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF")
        )

        val dumped = File(context.getExternalFilesDir(null), "report-proof.pdf")
        dumped.writeBytes(written)
        println("PDF dumped ${dumped.absolutePath} bytes=$bytes pages=${paged.pageCount} millis=$millis")

        val carried = ReportPdfInspection.inspect(written)
        println("PDF inspection $carried")
        assertTrue("the file carries no letterforms of its own: $carried", carried.carriesItsOwnLetterforms)
        assertTrue("the file has no text layer, so its words are pictures: $carried", carried.hasTextLayer)

        val ink = renderPages(written)
        assertEquals("the renderer sees the pages the pagination counted", paged.pageCount, ink.size)
        ink.forEachIndexed { index, painted ->
            println("PDF page ${index + 1} dark sample pixels=$painted")
            assertTrue("page ${index + 1} has no ink on it", painted > 0)
        }
    }

    /**
     * Dark sample pixels per page, counted by the platform's own renderer.
     *
     * Asked of `PdfRenderer` rather than of the bytes, because "the ink landed on the paper" is a question about
     * what a reader sees: an empty file, a file of noise, and a file whose text fell outside its own sheet all
     * pass a byte count and fail this.
     */
    private fun renderPages(bytes: ByteArray): List<Int> {
        val temporary = File(context.cacheDir, "render-proof.pdf")
        temporary.writeBytes(bytes)
        val descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
        val ink = ArrayList<Int>()
        try {
            PdfRenderer(descriptor).use { renderer ->
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var painted = 0
                        for (y in 0 until bitmap.height step 11) {
                            for (x in 0 until bitmap.width step 7) {
                                val pixel = bitmap.getPixel(x, y)
                                if (pixel != Color.TRANSPARENT && isDark(pixel)) painted++
                            }
                        }
                        ink += painted
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            descriptor.close()
            temporary.delete()
        }
        return ink
    }

    private fun isDark(pixel: Int): Boolean {
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        return red + green + blue < 600
    }
}

/**
 * The report a handset is asked to write, built here rather than read from a database.
 *
 * The bytes of a PDF depend on the words and figures inside it, and this file is about the bytes; the read that
 * produces those figures is exercised against real Room rows in the JVM suite, and running the same SQL again
 * here would only prove the database twice on slower hardware. What a handset alone answers is what the document
 * writer does with a font, and how long a real page takes.
 *
 * The names are Russian and the letter `ё` is among them, because that is the claim under test; one category is
 * deliberately unclassified, so the "no category" line is in the document too.
 */
object ReportExportFixtures {

    fun pages(context: Context): PagedReport {
        val palette = ReportLayout.palette(context)
        val document = ReportLayout.build(
            context = context,
            report = juneReport(),
            profileName = "Анна Ковалёва",
            palette = palette
        )
        return ReportPagination.paginate(document, measurer = PaintReportMeasurer(), palette = palette)
    }

    private fun juneReport(): Report {
        val zone = ZoneId.of("Europe/Berlin")
        val first = LocalDate.of(2026, 6, 1)
        val window = ReportWindow.of(
            period = ReportPeriod.Month(YearMonth.of(2026, 6)),
            zone = zone,
            today = LocalDate.of(2026, 6, 22),
            generatedAt = first.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        )
        return Report(
            window = window,
            summary = ReportSummary(
                createdTasks = 6,
                completedTasks = 14,
                workedSeconds = 41_700L,
                intervals = 22,
                workSessions = 9,
                averageSessionSeconds = 41_700.0 / 9,
                days = 30,
                activeDays = 11,
                completionsPerDay = 14 / 30.0,
                standing = OpenWorkStanding(
                    openTasks = 3,
                    overdue = 2,
                    dueToday = 1,
                    dueThisWeek = 4,
                    withoutDeadline = 5,
                    inProgress = 1,
                    archivedTasks = 7,
                    totalTasks = 60,
                    lifetimeCompletionRate = DashboardMath.completionRate(31, 60)
                ),
                undatedCompletions = 1
            ),
            completedTasks = CompletedTasksReport(
                count = 14,
                perDay = (0 until 6).map { index ->
                    DayCount(
                        localDay = first.plusDays(index.toLong()).toEpochDay(),
                        date = first.plusDays(index.toLong()),
                        count = 2 + index
                    )
                },
                undatable = 1
            ),
            workedTime = WorkedTimeReport(
                seconds = 41_700L,
                intervals = 22,
                sessions = 9,
                averageSessionSeconds = 41_700.0 / 9,
                perDay = (0 until 6).map { index ->
                    DaySeconds(
                        localDay = first.plusDays(index.toLong()).toEpochDay(),
                        date = first.plusDays(index.toLong()),
                        seconds = 1_800L * (index + 1)
                    )
                }
            ),
            categoryActivity = listOf(
                CategoryActivity(1L, "Диссертация", -0xB0C4, 22_400L, 12, 8),
                CategoryActivity(2L, "Семья", -0x5C40, 9_600L, 5, 3),
                CategoryActivity(null, null, null, 4_200L, 3, 2),
                CategoryActivity(3L, "Учёба", -0x3C0000, 5_500L, 2, 1)
            ),
            goals = GoalsReport(rows = emptyList(), reachedStanding = 0, standing = 0),
            habits = HabitsReport(rows = emptyList(), marks = 0, keptCount = 0, standing = 0),
            dynamics = (0 until 12).map { index ->
                DynamicsDay(
                    localDay = first.plusDays(index.toLong()).toEpochDay(),
                    date = first.plusDays(index.toLong()),
                    completions = if (index % 3 == 0) 3 else 1,
                    workedSeconds = 1_200L * (index % 4 + 1),
                    habitMarks = index % 2
                )
            },
            achievements = AchievementsReport(grants = emptyList()),
            comparison = PeriodComparison(
                window = window.preceding(),
                completions = FigureChange(14L, 9L, ChangeDirection.UP, 0.555),
                workedSeconds = FigureChange(41_700L, 38_000L, ChangeDirection.UP, 0.097),
                createdTasks = FigureChange(6L, 6L, ChangeDirection.FLAT, 0.0),
                habitMarks = FigureChange(0L, 0L, ChangeDirection.FLAT, null),
                achievements = FigureChange(0L, 0L, ChangeDirection.FLAT, null)
            ),
            findings = emptyList()
        )
    }
}
