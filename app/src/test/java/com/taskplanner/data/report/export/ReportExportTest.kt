package com.taskplanner.data.report.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.testing.AppFixture
import com.taskplanner.testing.ReportFixtures
import java.io.IOException
import java.io.OutputStream
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser

/**
 * Saving a report where the user pointed, and handing one to another app.
 *
 * The mechanism is the Storage Access Framework, and what the tests below hold onto is the part of it that a
 * unit test can reach: the app is given a uri it does not resolve, it writes through the stream the resolver
 * hands back, and it says what happened afterwards in words that match. A cancelled dialog, an unreadable
 * place, a stream that died halfway and a document writer that produced no bytes are four different answers,
 * and the one answer they must never get is "saved".
 *
 * Two limits are worth stating where they apply rather than in a footnote. First, the PDF path is exercised
 * here against the platform's document writer as Robolectric runs it, which accepts every drawing call and
 * writes no bytes — the test below asserts that this is reported as a failure, and the bytes themselves are
 * checked on a handset, in `PdfReportInstrumentedTest`. Second, the Sharesheet is a system surface: what can be
 * proved off a device is the intent, the uri and the provider's own configuration, and that is what these
 * cases cover.
 */
@RunWith(RobolectricTestRunner::class)
class ReportExportTest {

    private lateinit var fixture: AppFixture
    private lateinit var context: Context
    private lateinit var export: ReportExportRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        export = ReportExportRepository(context, fixture.reportRepository, fixture.sessionRepository)
    }

    @After
    fun tearDown() {
        stagedDirectory().deleteRecursively()
        fixture.close()
    }

    private fun stagedDirectory() = java.io.File(context.cacheDir, "report_exports")

    @Test
    fun `a csv export lands in the uri the picker chose`() = runTest {
        val capture = fixture.capture("report.csv")

        val outcome = export.save(ReportFixtures.report(), capture.uri, ReportFormat.CSV)

        val saved = outcome as ReportExport.Saved
        assertEquals(capture.uri, saved.uri)
        assertTrue("the file should not be empty", saved.bytes > 0L)
        assertEquals(saved.bytes, capture.bytes.size.toLong())
        assertTrue(String(capture.bytes, Charsets.UTF_8).contains("section,subject_id"))
    }

    @Test
    fun `the stream is closed before an export is called saved`() = runTest {
        val capture = fixture.capture("report.csv")

        val outcome = export.save(ReportFixtures.report(), capture.uri, ReportFormat.CSV)

        assertTrue("the file was left open", capture.closed)
        assertTrue(outcome is ReportExport.Saved)
    }

    @Test
    fun `a cancelled picker is not reported as a failure`() = runTest {
        val outcome = export.save(ReportPeriod.Day(ReportFixtures.wednesday), ReportFixtures.zone, null, ReportFormat.CSV)

        assertEquals(ReportExport.Cancelled, outcome)
    }

    @Test
    fun `a place the resolver refuses is said as unwritable`() = runTest {
        val stranger = Uri.parse("content://nowhere.at.all/report.csv")

        val outcome = export.save(ReportFixtures.report(), stranger, ReportFormat.CSV)

        val failed = outcome as ReportExport.Failed
        assertEquals(ReportFailure.LOCATION_UNWRITABLE, failed.reason)
    }

    @Test
    fun `a stream that dies halfway is a failed export, not a saved file`() = runTest {
        val uri = Uri.parse("content://document/broken")
        shadowOf(context.contentResolver).registerOutputStream(
            uri,
            object : OutputStream() {
                override fun write(byte: Int) = throw IOException("the disk filled up")
            }
        )

        val outcome = export.save(ReportFixtures.report(), uri, ReportFormat.CSV)

        val failed = outcome as ReportExport.Failed
        assertEquals(ReportFailure.WRITE_INTERRUPTED, failed.reason)
        assertNotNull(failed.detail)
    }

    @Test
    fun `a document the platform cannot write here is not staged as a file either`() = runTest {
        val outcome = export.stage(ReportFixtures.report(), ReportFormat.PDF)

        // Robolectric's document writer refuses the very first call it is given, and staging answers with a
        // failure and no file rather than with a half-made one.
        assertTrue(outcome.isFailure)
        assertFalse(java.io.File(stagedDirectory(), "taskplanner-report-2026-06-03.pdf").exists())
    }

    @Test
    fun `a document that came out with no bytes is never called saved`() = runTest {
        val capture = fixture.capture("report.pdf")

        val outcome = export.save(ReportFixtures.report(), capture.uri, ReportFormat.PDF)

        // Robolectric's document writer accepts the drawing calls and produces nothing, which is exactly the
        // case the check exists for: a report that promised its reader a file and handed them zero bytes is
        // the failure §5.1 names, and the real platform's own answer to it is checked on a handset.
        val failed = outcome as ReportExport.Failed
        assertEquals(ReportFailure.EMPTY_FILE, failed.reason)
        assertTrue("the stream was still left open", capture.closed)
        assertEquals(0, capture.bytes.size)
    }

    @Test
    fun `a report with nobody signed in is said as having no profile`() = runTest {
        val capture = fixture.capture("report.csv")

        val outcome = export.save(ReportPeriod.Day(ReportFixtures.wednesday), ReportFixtures.zone, capture.uri, ReportFormat.CSV)

        assertEquals(ReportFailure.NO_PROFILE, (outcome as ReportExport.Failed).reason)
        assertEquals(0, capture.bytes.size)
    }

    @Test
    fun `the report the reader signed in as is the one written`() = runTest {
        val user = fixture.signIn(name = "Анна", email = "anna@example.com")
        fixture.addTask(user.id, "Подготовить отчёт")
        val capture = fixture.capture("report.csv")

        val outcome = export.save(ReportPeriod.Day(ReportFixtures.wednesday), ReportFixtures.zone, capture.uri, ReportFormat.CSV)

        assertTrue(outcome is ReportExport.Saved)
        val text = String(capture.bytes, Charsets.UTF_8)
        assertTrue(text, text.contains("Europe/Berlin"))
        assertFalse("a second profile's rows must not appear", text.contains("secret@example.com"))
    }

    @Test
    fun `the mime type and the extension belong to the format`() {
        assertEquals("text/csv", ReportFormat.CSV.mimeType)
        assertEquals("csv", ReportFormat.CSV.extension)
        assertEquals("application/pdf", ReportFormat.PDF.mimeType)
        assertEquals("pdf", ReportFormat.PDF.extension)
    }

    @Test
    fun `a file name is ascii, dated, and ends with the format's extension`() {
        fun name(period: ReportPeriod, format: ReportFormat) =
            ReportNaming.suggest(ReportFixtures.window(period), format)

        assertEquals("taskplanner-report-2026-06-03.csv", name(ReportPeriod.Day(ReportFixtures.wednesday), ReportFormat.CSV))
        assertEquals("taskplanner-report-2026-06.pdf", name(ReportPeriod.Month(YearMonth.of(2026, 6)), ReportFormat.PDF))
        assertEquals("taskplanner-report-2026.pdf", name(ReportPeriod.Year(2026), ReportFormat.PDF))
        assertEquals("taskplanner-report-2026-06-01.pdf", name(ReportPeriod.Week(ReportFixtures.wednesday, java.time.DayOfWeek.MONDAY), ReportFormat.PDF))
        val range = name(
            (ReportPeriod.range(java.time.LocalDate.of(2026, 3, 4), java.time.LocalDate.of(2026, 4, 9))
                as com.taskplanner.data.report.ReportPeriodRead.Accepted).period,
            ReportFormat.CSV
        )
        assertEquals("taskplanner-report-2026-03-04-2026-04-09.csv", range)
    }

    @Test
    fun `saving twice writes the same name and leaves one file`() = runTest {
        val report = ReportFixtures.report(period = ReportPeriod.Day(ReportFixtures.wednesday))

        val first = export.stage(report, ReportFormat.CSV).getOrThrow()
        val second = export.stage(report, ReportFormat.CSV).getOrThrow()

        assertEquals(first.file, second.file)
        assertEquals(
            listOf("taskplanner-report-2026-06-03.csv"),
            stagedDirectory().listFiles()?.map { it.name }
        )
    }

    @Test
    fun `staged exports are capped rather than kept forever`() = runTest {
        repeat(9) { index ->
            val outcome = export.stage(
                ReportFixtures.report(period = ReportPeriod.Day(ReportFixtures.wednesday.plusDays(index.toLong()))),
                ReportFormat.CSV
            )
            assertTrue("stage $index failed", outcome.isSuccess)
        }
        val left = stagedDirectory().listFiles()!!.toList()
        assertTrue("the cache kept ${left.size} exports", left.size <= 5)
        // What is kept is the newest, which is the one a reader may still be waiting for a recipient to open.
        assertTrue(left.map { it.name }.contains("taskplanner-report-2026-06-11.csv"))
    }

    @Test
    fun `a staged report is written into the app's own cache directory`() = runTest {
        val staged = export.stage(ReportFixtures.report(), ReportFormat.CSV).getOrThrow()

        assertTrue(staged.file.isFile)
        assertEquals("taskplanner-report-2026-06-03.csv", staged.file.name)
        assertEquals(staged.bytes, staged.file.length())
        assertTrue(
            "the copy lives outside the app's files and outside any shared storage: ${staged.file}",
            staged.file.canonicalPath.startsWith(java.io.File(context.cacheDir, "report_exports").canonicalPath)
        )
    }

    @Test
    fun `the share intent carries the file and grants one read`() = runTest {
        val uri = Uri.parse("content://${ReportExportRepository.AUTHORITY}/report_exports/report.pdf")

        val chooser = export.shareIntent(uri, ReportFormat.PDF)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val payload = chooser.extras?.get(Intent.EXTRA_INTENT) as Intent
        assertEquals(Intent.ACTION_SEND, payload.action)
        assertEquals("application/pdf", payload.type)
        assertEquals(uri, payload.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(
            "the receiving app gets no standing right to read",
            payload.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
    }

    @Test
    fun `the provider in the manifest is private and grants only a read`() {
        val info = context.packageManager.resolveContentProvider(ReportExportRepository.AUTHORITY, 0)

        assertNotNull("the authority ${ReportExportRepository.AUTHORITY} is not registered", info)
        assertFalse("a share provider must not be open to other apps", info!!.exported)
        assertTrue("the grant has to travel with the intent", info.grantUriPermissions)
        assertEquals("androidx.core.content.FileProvider", info.name)
    }

    @Test
    fun `the provider reaches the export directory and nothing else`() {
        val rules = ArrayList<Triple<String, String, String>>()
        context.resources.getXml(com.taskplanner.R.xml.file_paths).use { parser ->
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.depth > 1) {
                    rules += Triple(
                        parser.name,
                        parser.getAttributeValue(null, "name") ?: "",
                        parser.getAttributeValue(null, "path") ?: ""
                    )
                }
                event = parser.next()
            }
        }

        assertEquals(
            listOf(Triple("cache-path", "report_exports", "report_exports/")),
            rules
        )
        // The types that would open the app's own storage: no files-path, no external-path, no root-path.
        assertTrue(rules.none { it.first == "files-path" })
        assertTrue(rules.none { it.first == "external-path" })
        assertTrue(rules.none { it.first == "root-path" })
        assertTrue(rules.none { it.second == "." || it.third == "/" })
    }

    @Test
    fun `pruning at start-up leaves the newest files alone`() = runTest {
        repeat(9) { index ->
            export.stage(
                ReportFixtures.report(period = ReportPeriod.Day(ReportFixtures.wednesday.plusDays(index.toLong()))),
                ReportFormat.CSV
            )
        }
        val before = stagedDirectory().listFiles()!!.map { it.name }.sorted()

        export.pruneStaged()

        assertEquals(before, stagedDirectory().listFiles()!!.map { it.name }.sorted())
    }
}
