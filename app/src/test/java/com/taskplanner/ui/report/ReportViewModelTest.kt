package com.taskplanner.ui.report

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportPeriodRead
import com.taskplanner.data.report.export.CsvReport
import com.taskplanner.data.report.export.ReportDraw
import com.taskplanner.data.report.export.ReportExportRepository
import com.taskplanner.data.report.export.ReportFailure
import com.taskplanner.data.report.export.ReportFormat
import com.taskplanner.data.report.export.ReportNaming
import com.taskplanner.testing.AppFixture
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The report screen: which period it asks for, which zone it asks it in, what it lays out, and what it says
 * when a file is written or refused.
 *
 * Four claims hold these tests together.
 *
 *  * **The screen asks, the layers underneath answer.** Every figure comes from the report repository and every
 *    page from Part 4's layout and pagination, so the screen's own decisions are the *question* — the period,
 *    the zone, the format — and that is what is asserted here.
 *  * **A question is asked once.** Switching to csv shows the rows that were already produced and must not
 *    re-read or re-lay-out anything; one test holds the pages by identity to prove it, because a screen that
 *    regenerates on every redraw is the failure §9 names.
 *  * **A profile's report is that profile's.** When the signed-in profile changes, the screen puts the report
 *    away before the next one lands, and no frame of the whole transition may pair one account's figures with
 *    another account's name. That is checked against every state the screen passed through, not just the last.
 *  * **Nothing is promised early.** A success appears only for bytes that are on disk behind a closed stream;
 *    a closed picker is silence rather than an error; and each way an export can fail gets its own sentence,
 *    none of which is the exception's text.
 *
 * The screen is opened by each test rather than by the fixture, because a report is asked for after the profile
 * exists — and the state a screen starts in is part of what is being asserted. Every test anchors the clock on
 * Wednesday 3 June 2026 at midday in Europe/Berlin, so "this month" is the same month whatever time of day the
 * suite runs at: the midnight trap an earlier sub-stage fell into.
 */
@RunWith(RobolectricTestRunner::class)
class ReportViewModelTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var context: Context
    private lateinit var fixture: AppFixture
    private lateinit var exporter: ReportExportRepository
    private lateinit var viewModel: ReportViewModel

    private val zone get() = CalendarMath.systemZone()
    private val wednesday = LocalDate.of(2026, 6, 3)
    private val juneFirst = LocalDate.of(2026, 6, 1)

    private fun at(day: LocalDate, hour: Int = 10): Long =
        day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.US)
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor makes every collector run on the thread
        // that resumed it rather than wait for a looper nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        runBlocking { fixture.timeBase.shiftTo(at(wednesday, 12)) }
        exporter = ReportExportRepository(context, fixture.reportRepository, fixture.sessionRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    /** Opening the screen is what asks the first question, so the state before it is a state a test can name. */
    private fun openScreen() {
        viewModel = ReportViewModel(
            context, fixture.reportRepository, exporter, fixture.sessionRepository, fixture.timeBase
        )
    }

    /**
     * Seeds the account the screen reads: one category and two tasks finished in the first days of June.
     *
     * The rows go through the dao with an explicit instant rather than through `TaskRepository`, which stamps
     * completions from the process wall clock — the known Stage-1 gap. Finished work dated to the machine's real
     * today would fall outside the June window this test asks about, every figure would read zero, and the
     * assertions below would pass for the wrong reason.
     */
    private fun seedWork(userId: Long) {
        runBlocking {
            val categoryId = fixture.categoryDao.insertCategory(
                Category(name = "Thesis", color = -16711936, icon = "ic_tag", userId = userId)
            )
            listOf("Intro", "Method").forEachIndexed { index, title ->
                fixture.taskDao.insertTask(
                    Task(
                        title = title,
                        description = "",
                        status = TaskStatus.COMPLETED,
                        userId = userId,
                        categoryId = categoryId,
                        completedAt = at(juneFirst.plusDays(index.toLong()), 9)
                    )
                )
            }
        }
    }

    private fun signIn(name: String = "Test User", email: String = "test@example.com"): Long =
        runBlocking { fixture.signIn(name = name, email = email).id }

    /**
     * Pumps the main looper until the screen says what the test asked it to say.
     *
     * The read runs on Room's own threads and the layout on a coroutine dispatcher of its own, so neither is
     * finished when a command returns. A timeout is not a failure in itself — the assertion that follows this
     * call is what decides, and the message carries the state a screen got stuck on.
     */
    private fun awaitUntil(description: String, condition: (ReportState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition(viewModel.state.value)) return
            Thread.sleep(5)
        }
        throw AssertionError("$description never happened. state=${viewModel.state.value}")
    }

    /** A report on screen, laid out, ready to be exported. */
    private fun awaitReport() {
        awaitUntil("a report arrived") { it.report != null && it.pages != null && !it.isLoading }
    }

    /**
     * Every state the screen passes through from now on, recorded in order.
     *
     * A profile switch is a transition rather than a pair of frames, and the bug worth catching is the one that
     * lives in between: one account's figures under another account's name. Asserting on the last frame only
     * would let that go by.
     */
    private fun record(into: MutableList<ReportState>): Job =
        MainScope().launch { viewModel.state.toList(into) }

    private fun periodOf(read: ReportPeriodRead): ReportPeriod =
        (read as ReportPeriodRead.Accepted).period

    // -- the question the screen asks ----------------------------------------------------------------

    @Test
    fun `opens on the month the clock is in, in the device zone`() {
        signIn()
        openScreen()

        val state = viewModel.state.value
        assertEquals(ReportKind.MONTH, state.kind)
        assertEquals(ReportPeriod.Month(YearMonth.of(2026, 6)), state.period)
        assertEquals(zone, state.zone)
        assertTrue("the device zone is where a report starts", state.followsDeviceZone)
        assertEquals(ReportFormat.PDF, state.format)
        assertTrue("the screen is busy until the first frame", state.isLoading)
    }

    @Test
    fun `one report fills both previews`() {
        val userId = signIn()
        seedWork(userId)
        openScreen()
        awaitReport()

        val state = viewModel.state.value
        val report = state.report!!
        assertEquals(2, report.summary.completedTasks)
        assertTrue("the document needs at least one page", state.pages!!.pageCount >= 1)

        val drawn = state.pages!!.draws.filterIsInstance<ReportDraw.Text>().map { it.text }
        assertTrue("the pages carry the section headings", drawn.contains(sectionHeading()))
        assertTrue("and the profile they belong to", drawn.contains(profileLine("Test User")))

        assertEquals(
            "the preview shows the file's own lines, quotes and all",
            CsvReport.lines(report),
            state.csvLines
        )
        assertEquals(
            "and those lines really are the file, with its line endings put back",
            String(CsvReport.bytes(report), Charsets.UTF_8).removePrefix(BOM),
            state.csvLines.joinToString("\r\n") + "\r\n"
        )
        assertTrue("and it starts where the file starts", state.csvLines.first().startsWith("section,"))
        assertFalse(state.isLoading)
    }

    /**
     * The reader's own words, with a comma and a line break inside them, are shown as the file holds them.
     *
     * §4 of the stage's spec treats a multiline name as a layout case, and the csv answer to it is RFC 4180
     * quoting: the field is wrapped and its break is *inside* the row. A preview built by re-splitting the
     * finished bytes at every line ending would show that one row as two, in a screen whose whole claim is that
     * it is showing the file — so the lines come from the writer, and this test is the difference between the
     * two ways of doing it.
     */
    @Test
    fun `a name with a comma and a line break in it is one line of the preview, quoted as the file quotes it`() {
        val userId = signIn()
        val awkward = "Thesis, draft 2\r\nagain"
        runBlocking {
            val categoryId = fixture.categoryDao.insertCategory(
                Category(name = awkward, color = -16711936, icon = "ic_tag", userId = userId)
            )
            fixture.taskDao.insertTask(
                Task(
                    title = "Chapter 2",
                    description = "",
                    status = TaskStatus.COMPLETED,
                    userId = userId,
                    categoryId = categoryId,
                    completedAt = at(juneFirst, 9)
                )
            )
        }
        openScreen()
        awaitReport()

        val state = viewModel.state.value
        val namingIt = state.csvLines.filter { it.contains("draft 2") }
        assertTrue("the category the reader named is in the file", namingIt.isNotEmpty())
        assertTrue(
            "and every row that names it quotes it, because a comma inside a field is not a column break",
            namingIt.all { it.contains("\"$awkward\"") }
        )

        val resplit = String(CsvReport.bytes(state.report!!), Charsets.UTF_8)
            .removePrefix(BOM)
            .split("\r\n")
            .dropLastWhile { it.isEmpty() }
        assertTrue(
            "re-splitting the finished bytes would have shown that one row as two",
            resplit.size > state.csvLines.size
        )
    }

    @Test
    fun `each period chip names one of the five real periods`() {
        signIn()
        openScreen()
        awaitReport()

        viewModel.chooseKind(ReportKind.DAY)
        awaitUntil("a day report") { it.period is ReportPeriod.Day && !it.isLoading }
        assertEquals(ReportPeriod.Day(wednesday), viewModel.state.value.period)

        viewModel.chooseKind(ReportKind.YEAR)
        awaitUntil("a year report") { it.period is ReportPeriod.Year && !it.isLoading }
        assertEquals(periodOf(ReportPeriod.year(2026)), viewModel.state.value.period)

        viewModel.chooseKind(ReportKind.RANGE)
        awaitUntil("a range report") { it.period is ReportPeriod.Range && !it.isLoading }
        assertEquals(
            periodOf(ReportPeriod.range(juneFirst, LocalDate.of(2026, 6, 30))),
            viewModel.state.value.period
        )
    }

    @Test
    fun `a period chip keeps the day being looked at`() {
        signIn()
        openScreen()
        awaitReport()

        viewModel.chooseDate(LocalDate.of(2026, 3, 17))
        awaitUntil("a march month") {
            it.period == ReportPeriod.Month(YearMonth.of(2026, 3)) && !it.isLoading
        }

        viewModel.chooseKind(ReportKind.WEEK)
        awaitUntil("the week of that day") { it.period is ReportPeriod.Week && !it.isLoading }
        val week = viewModel.state.value.period as ReportPeriod.Week
        assertEquals("the day the reader was looking at", LocalDate.of(2026, 3, 17), week.anchor)
        assertEquals(CalendarMath.firstDayOfWeek(), week.firstDay)
    }

    @Test
    fun `a reversed range is refused and the period does not move`() {
        signIn()
        openScreen()
        awaitReport()
        val before = viewModel.state.value.period

        viewModel.chooseRange(LocalDate.of(2026, 6, 7), juneFirst)

        val feedback = viewModel.state.value.feedback
        assertEquals("nothing was swapped behind the reader's back", before, viewModel.state.value.period)
        assertTrue("the screen says which rule the range broke", feedback is ReportFeedback.PeriodRejected)
        assertEquals(
            context.getString(R.string.report_period_rejected),
            (feedback as ReportFeedback.PeriodRejected).message
        )
    }

    @Test
    fun `a range that names one day is that day`() {
        signIn()
        openScreen()
        awaitReport()

        viewModel.chooseRange(juneFirst, juneFirst)
        awaitUntil("a one-day range") { it.period is ReportPeriod.Range && !it.isLoading }

        val state = viewModel.state.value
        val period = state.period as ReportPeriod.Range
        assertEquals(juneFirst, period.from)
        assertEquals(juneFirst, period.to)
        assertNull(state.feedback)
        assertEquals(1, state.report!!.window.days)
    }

    // -- the zone ------------------------------------------------------------------------------------

    @Test
    fun `choosing a zone re-reads the same period in that zone`() {
        signIn()
        openScreen()
        awaitReport()
        val period = viewModel.state.value.period
        val newYork = ZoneId.of("America/New_York")

        viewModel.chooseZone(newYork)
        awaitUntil("a New York report") { it.zone == newYork && it.report != null && !it.isLoading }

        val state = viewModel.state.value
        assertEquals("the same question, counted in another calendar", period, state.period)
        assertEquals(newYork, state.report!!.window.zone)
        assertFalse("the device zone is no longer the one in use", state.followsDeviceZone)
    }

    @Test
    fun `the device zone can be given back`() {
        signIn()
        openScreen()
        viewModel.chooseZone(ZoneId.of("Asia/Tokyo"))
        awaitUntil("a Tokyo report") { it.zone == ZoneId.of("Asia/Tokyo") && !it.isLoading }

        viewModel.useDeviceZone()
        awaitUntil("the device zone again") { it.followsDeviceZone && it.report != null && !it.isLoading }

        assertEquals(zone, viewModel.state.value.zone)
        assertEquals(zone, viewModel.state.value.report!!.window.zone)
    }

    @Test
    fun `the zone list is long, ordered and searchable`() {
        signIn()
        openScreen()

        val all = viewModel.zoneOptions("")
        assertTrue("every zone the device knows", all.size > 100)
        assertEquals("the device's own zone leads the list", zone.id, all.first().id)
        val rest = all.drop(1).map { it.id }
        assertEquals("the rest in their own order", rest.sorted(), rest)

        val berlin = viewModel.zoneOptions("berlin")
        assertTrue(berlin.map { it.id }.contains("Europe/Berlin"))
        assertTrue(
            "a search returns only what matches, beside the device's own entry",
            berlin.all { it.id.contains("berlin", ignoreCase = true) || it.id == zone.id }
        )
        assertEquals("an impossible search leaves only the device's own", 1, viewModel.zoneOptions("~").size)
    }

    @Test
    fun `a zone option states the offset that zone is at right now`() {
        signIn()
        openScreen()

        val tokyo = viewModel.zoneOptions("tokyo").first { it.id == "Asia/Tokyo"}
        assertTrue(tokyo.label.startsWith("Asia/Tokyo"))
        assertTrue("the offset java.time resolves for today", tokyo.label.contains("UTC+09:00"))

        val device = viewModel.zoneOptions("").first()
        assertTrue("and the device's own zone states its own offset", device.label.startsWith("${zone.id} · UTC"))
    }

    // -- the two views of one report -----------------------------------------------------------------

    @Test
    fun `choosing csv does not re-lay-out the pages`() {
        signIn()
        openScreen()
        awaitReport()
        val pages = viewModel.state.value.pages!!

        viewModel.chooseFormat(ReportFormat.CSV)

        val state = viewModel.state.value
        assertSame("the layout the screen already had", pages, state.pages)
        assertEquals(ReportFormat.CSV, state.format)
        assertFalse("a view switch started no new read", state.isLoading)
    }

    @Test
    fun `zoom is bounded by the levels the screen offers`() {
        signIn()
        openScreen()
        awaitReport()
        assertEquals(ReportPageFit.MIN_ZOOM, viewModel.state.value.zoom)

        repeat(6) { viewModel.zoomIn() }
        assertEquals(ReportPageFit.MAX_ZOOM, viewModel.state.value.zoom)
        assertFalse("there is nothing to zoom into past the last level", viewModel.state.value.canZoomIn)

        repeat(6) { viewModel.zoomOut() }
        assertEquals(ReportPageFit.MIN_ZOOM, viewModel.state.value.zoom)
        assertFalse("and the page is already as wide as the view", viewModel.state.value.canZoomOut)
    }

    @Test
    fun `zooming redraws the pages it already has`() {
        signIn()
        openScreen()
        awaitReport()
        val pages = viewModel.state.value.pages!!

        viewModel.zoomIn()

        val state = viewModel.state.value
        assertSame("a bigger sheet is the same document", pages, state.pages)
        assertFalse("and asking for it started no read", state.isLoading)
    }

    @Test
    fun `a new report is laid out again rather than reused`() {
        signIn()
        openScreen()
        awaitReport()
        val pages = viewModel.state.value.pages!!

        viewModel.chooseKind(ReportKind.WEEK)
        awaitUntil("a week report") { it.period is ReportPeriod.Week && it.pages != null && !it.isLoading }

        assertNotSame("a different window cannot keep the old pages", pages, viewModel.state.value.pages)
    }

    @Test
    fun `the suggested file name is the exporter's own`() {
        signIn()
        openScreen()
        awaitReport()
        val report = viewModel.state.value.report!!

        assertEquals(ReportNaming.suggest(report.window, ReportFormat.PDF), viewModel.suggestedName())
        viewModel.chooseFormat(ReportFormat.CSV)
        assertEquals(ReportNaming.suggest(report.window, ReportFormat.CSV), viewModel.suggestedName())
    }

    @Test
    fun `no report means no name to offer`() {
        signIn()
        openScreen()
        assertNull("nothing is suggested before the first frame", viewModel.suggestedName())
    }

    // -- profiles ------------------------------------------------------------------------------------

    @Test
    fun `a second profile never inherits the first one's report`() {
        val first = signIn("First", "first@example.com")
        seedWork(first)
        openScreen()
        awaitReport()
        assertEquals(2, viewModel.state.value.report!!.summary.completedTasks)
        assertEquals("First", viewModel.state.value.profileName)

        val seen = mutableListOf<ReportState>()
        val recording = record(seen)
        val second = runBlocking { fixture.signIn(name = "Second", email = "second@example.com").id }
        awaitUntil("the second profile's own report") {
            it.profileId == second && it.report != null && !it.isLoading
        }
        recording.cancel()

        val state = viewModel.state.value
        assertEquals(second, state.profileId)
        assertEquals("Second", state.profileName)
        assertEquals("the empty account, not the one that was on screen", 0, state.report!!.summary.completedTasks)
        assertTrue(
            "and the pages name the account they were read for",
            state.pages!!.draws.filterIsInstance<ReportDraw.Text>().map { it.text }
                .contains(profileLine("Second"))
        )
        assertTrue(
            "no frame of the whole transition put the first account's figures under the second one's name",
            seen.none { it.profileName == "Second" && it.report?.summary?.completedTasks == 2 }
        )
        assertTrue("the transition went through a screen with nothing to show", seen.any { it.report == null })
    }

    @Test
    fun `no profile is an answer, not a spinner and not an error`() {
        openScreen()
        awaitUntil("the screen settled on nobody") { !it.isLoading }

        val state = viewModel.state.value
        assertNull(state.profileId)
        assertNull(state.report)
        assertNull(state.readError)
        assertFalse("there is nothing to export", state.canExport)
        assertEquals(context.getString(R.string.report_empty_no_profile), state.emptyMessage)
    }

    // -- saving --------------------------------------------------------------------------------------

    @Test
    fun `a save writes the bytes and only then says saved`() {
        val userId = signIn()
        seedWork(userId)
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)
        val capture = fixture.capture("june.csv")

        viewModel.saveTo(capture.uri)
        awaitUntil("a finished save") { it.feedback != null }

        val state = viewModel.state.value
        assertFalse(state.saving)
        val feedback = state.feedback
        assertTrue("the success sentence is only for a file that exists", feedback is ReportFeedback.Saved)
        feedback as ReportFeedback.Saved
        assertEquals(capture.bytes.size.toLong(), feedback.size)
        assertTrue("the stream was closed before anything was promised", capture.closed)
        assertFalse("the reader's sentence is not an exception's", feedback.message.contains("java.io"))
        assertTrue("the csv really landed", csvText(capture.bytes).startsWith("section,"))
    }

    @Test
    fun `the file holds the days on screen and no others`() {
        val userId = signIn()
        seedWork(userId)
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)
        val capture = fixture.capture("window.csv")

        viewModel.saveTo(capture.uri)
        awaitUntil("a finished save") { it.feedback != null }

        val text = csvText(capture.bytes)
        val state = viewModel.state.value
        assertEquals(
            "what was previewed is what was written, byte for byte",
            state.csvLines.joinToString("\r\n") + "\r\n",
            text
        )
        assertTrue("the days the report was asked about are in it", text.contains("2026-06-01"))
        assertFalse("and a month nobody chose is not", text.contains("2026-03-17"))
    }

    @Test
    fun `a cancelled picker is silence, not an error`() {
        signIn()
        openScreen()
        awaitReport()

        viewModel.saveTo(null)

        val state = viewModel.state.value
        assertTrue(state.feedback is ReportFeedback.Cancelled)
        assertNull("nothing is shown for a choice the reader made", viewModel.readerMessage())
        assertFalse(state.saving)
        assertNull(state.readError)
    }

    @Test
    fun `a location that will not open is a complaint about the location`() {
        signIn()
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)
        // Nothing is registered behind this uri, which is what a document provider that is not there looks
        // like from this side: the resolver either hands back no stream or refuses to open one at all. Both are
        // answered the same way, and neither of them has anything to do with the report.
        val uri = Uri.parse("content://document/not.there")

        viewModel.saveTo(uri)
        awaitUntil("a refused save") { it.feedback != null }

        val feedback = viewModel.state.value.feedback
        assertTrue(feedback is ReportFeedback.Failed)
        feedback as ReportFeedback.Failed
        assertEquals(ReportFailure.LOCATION_UNWRITABLE, feedback.reason)
        assertEquals(context.getString(R.string.report_fail_unwritable), feedback.message)
    }

    @Test
    fun `a write that breaks partway is not called success`() {
        signIn()
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)
        val uri = Uri.parse("content://document/breaking")
        shadowOf(context.contentResolver).registerOutputStream(uri, breakingStream())

        viewModel.saveTo(uri)
        awaitUntil("a broken save") { it.feedback != null }

        val feedback = viewModel.state.value.feedback
        assertTrue("a half-written file is a failure", feedback is ReportFeedback.Failed)
        feedback as ReportFeedback.Failed
        assertEquals(ReportFailure.WRITE_INTERRUPTED, feedback.reason)
        assertEquals(context.getString(R.string.report_fail_interrupted), feedback.message)
        assertFalse("no exception text reaches the reader", feedback.message.contains("boom"))
    }

    @Test
    fun `a pdf the device cannot draw is reported and never called saved`() {
        signIn()
        openScreen()
        awaitReport()
        // Robolectric's document writer accepts every drawing call a report makes and hands back no bytes, which
        // is the case the exporter's empty-file answer exists for. The emulator run in this sub-stage is what
        // proves a real pdf; what is proved here is the promise beside it — that nothing empty is ever
        // announced as a saved report.
        viewModel.chooseFormat(ReportFormat.PDF)
        val capture = fixture.capture("empty.pdf")

        viewModel.saveTo(capture.uri)
        awaitUntil("a pdf answer") { it.feedback != null }

        val feedback = viewModel.state.value.feedback
        assertTrue("a file with no bytes in it is not a saved report", feedback is ReportFeedback.Failed)
        feedback as ReportFeedback.Failed
        assertEquals(ReportFailure.EMPTY_FILE, feedback.reason)
        assertEquals(context.getString(R.string.report_fail_empty), feedback.message)
    }

    @Test
    fun `a second save while one is running is refused`() {
        signIn()
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)
        val slow = fixture.capture("slow.csv")
        val blocked = Uri.parse("content://document/blocked")
        shadowOf(context.contentResolver).registerOutputStream(blocked, slowStream(slow))

        viewModel.saveTo(blocked)
        awaitUntil("the save in flight") { it.saving }
        val second = fixture.capture("second.csv")
        viewModel.saveTo(second.uri)

        assertTrue("still the one pass", viewModel.state.value.saving)
        assertEquals("the second place was never opened", 0, second.bytes.size)

        awaitUntil("the first save finished") { it.feedback != null }
        val feedback = viewModel.state.value.feedback
        assertTrue(feedback is ReportFeedback.Saved)
        assertEquals(
            "the file the reader pointed at first is the one that was written",
            slow.bytes.size.toLong(),
            (feedback as ReportFeedback.Saved).size
        )
        assertEquals("and nothing at all was written to the second", 0, second.bytes.size)
    }

    // -- sharing -------------------------------------------------------------------------------------

    /**
     * The screen's share button is two steps: write the copy, then point this app's own provider at it. On the
     * test JVM the first works and the second does not — `FileProvider` will not resolve Robolectric's temp
     * directory, the same limit that keeps Part 4's Sharesheet assertions on a handset — and the two failures
     * have to be told apart, because "the copy is missing" would send the reader to retry something that had
     * already succeeded. What the copy looks like on disk is the claim of
     * [a staged copy is left where the receiving app can still find it], and the shape of a real sheet — its
     * content uri, its mime type, its read grant, and the one-shot the screen makes of it — belongs to
     * `ReportScreenInstrumentedTest`, which runs where the provider really is wired up.
     */
    @Test
    fun `a copy the provider cannot point at is reported as the hand-over, not as a missing file`() {
        signIn()
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)

        viewModel.share()
        awaitUntil("the share attempt answered") { it.share != null || it.feedback != null }

        val state = viewModel.state.value
        assertNull("no sheet is offered for a uri this platform cannot mint", state.share)
        val feedback = state.feedback
        assertTrue(feedback is ReportFeedback.Failed)
        assertEquals(
            "a hand-over that failed is not reported as a copy that is missing",
            context.getString(R.string.report_fail_handover),
            (feedback as ReportFeedback.Failed).message
        )
    }

    @Test
    fun `a staged copy is left where the receiving app can still find it`() {
        signIn()
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)

        viewModel.share()
        awaitUntil("a sheet to launch, or a refusal") { it.share != null || it.feedback != null }
        val staged = File(context.cacheDir, "report_exports").listFiles()!!.single { it.name.endsWith(".csv") }

        assertTrue("the copy the uri points at is still on disk", staged.exists())
        assertTrue("and it holds the report", staged.length() > 0L)
        assertEquals(
            "under the name the exporter suggested",
            ReportNaming.suggest(viewModel.state.value.report!!.window, ReportFormat.CSV),
            staged.name
        )
    }

    @Test
    fun `a copy that could not be written says so and offers no sheet`() {
        signIn()
        openScreen()
        awaitReport()
        // The pdf branch is the one this JVM cannot produce: Robolectric's document writer accepts every draw
        // call and hands back no bytes. The emulator run is what proves the pdf itself.
        viewModel.chooseFormat(ReportFormat.PDF)

        viewModel.share()
        awaitUntil("a refused copy") { it.feedback != null }

        val state = viewModel.state.value
        assertNull("no sheet for a file that does not exist", state.share)
        val feedback = state.feedback
        assertTrue(feedback is ReportFeedback.Failed)
        assertEquals(context.getString(R.string.report_fail_staging), (feedback as ReportFeedback.Failed).message)
        assertFalse(state.staging)
    }

    @Test
    fun `nothing is promised for a report the screen does not have`() {
        openScreen()
        awaitUntil("the screen settled on nobody") { !it.isLoading }

        viewModel.saveTo(fixture.capture("nothing.csv").uri)
        val failed = viewModel.state.value.feedback
        assertTrue(failed is ReportFeedback.Failed)
        assertEquals(ReportFailure.NO_PROFILE, (failed as ReportFeedback.Failed).reason)
        assertEquals(context.getString(R.string.report_fail_no_profile), failed.message)

        viewModel.share()
        assertNull("no sheet without a report", viewModel.state.value.share)
        assertEquals(
            ReportFailure.NO_PROFILE,
            (viewModel.state.value.feedback as ReportFeedback.Failed).reason
        )
    }

    // -- failures the screen has to survive -----------------------------------------------------------

    @Test
    fun `a read that fails is a complaint with a retry, not an endless spinner`() {
        signIn()
        openScreen()
        awaitReport()
        // Storage taken away underneath the read: the table the completions are counted from is gone, so the
        // next question is answered by SQLite rather than by the report. This is the failure the screen cannot
        // draw around, and the retry it offers has to be a real second question rather than a re-render.
        runBlocking { fixture.database.openHelper.writableDatabase.execSQL("DROP TABLE tasks") }

        viewModel.chooseKind(ReportKind.WEEK)
        awaitUntil("a complaint about the read") { it.readError != null }

        val state = viewModel.state.value
        assertFalse("the screen stops spinning", state.isLoading)
        assertEquals(context.getString(R.string.report_fail_read), state.readError)
        assertNull("a report that did not arrive cannot be exported", state.report)
        assertFalse(state.canExport)

        viewModel.retry()
        awaitUntil("the same complaint after a retry") { it.readError != null && !it.isLoading }
        assertEquals(context.getString(R.string.report_fail_read), viewModel.state.value.readError)
    }

    @Test
    fun `a message is shown once and then gone`() {
        val userId = signIn()
        seedWork(userId)
        openScreen()
        awaitReport()
        viewModel.chooseFormat(ReportFormat.CSV)
        viewModel.saveTo(fixture.capture("once.csv").uri)
        awaitUntil("a save to report") { it.feedback != null }

        viewModel.feedbackShown()

        assertNull(viewModel.state.value.feedback)
        assertNull(viewModel.readerMessage())
    }

    @Test
    fun `nothing can be exported while a new question is on its way`() {
        signIn()
        openScreen()
        assertFalse("before the first frame", viewModel.state.value.canExport)
        awaitReport()
        assertTrue(viewModel.state.value.canExport)

        viewModel.chooseKind(ReportKind.DAY)
        assertFalse("a new question puts the old answer away", viewModel.state.value.canExport)
        assertNull(viewModel.state.value.report)
    }

    @Test
    fun `a week report opens on the day the device says weeks open on`() {
        signIn()
        openScreen()
        awaitReport()

        viewModel.chooseKind(ReportKind.WEEK)
        awaitUntil("a week report") { it.period is ReportPeriod.Week && !it.isLoading }

        val window = viewModel.state.value.report!!.window
        assertEquals(
            "the device's own first day of week",
            CalendarMath.firstDayOfWeek(),
            LocalDate.ofEpochDay(window.fromDay).dayOfWeek
        )
        assertEquals(7, window.days)
    }

    // -- helpers -------------------------------------------------------------------------------------

    private fun sectionHeading(): String = context.getString(R.string.report_section_summary)

    private fun profileLine(name: String): String =
        context.getString(R.string.report_meta_profile, name)

    /** The file as text with the three bytes that say what encoding it is in taken back off. */
    private fun csvText(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).removePrefix(BOM)

    /** A stream that opens and then breaks, the way a full disk or a dying provider does mid-write. */
    private fun breakingStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            throw IOException("boom")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("boom")
        }
    }

    /** A stream that takes its time, so the test can catch the screen holding a save open. */
    private fun slowStream(sink: AppFixture.CapturedUri): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            sink.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            Thread.sleep(200)
            sink.write(b, off, len)
        }
    }

    private companion object {
        val BOM = String(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), Charsets.UTF_8)
    }
}
