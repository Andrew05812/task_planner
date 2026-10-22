package com.taskplanner.ui.report

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Environment
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.ui.MainActivity
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The report screen on a handset: does the reader get a document, and do the two hand-overs do what they say.
 *
 * A JVM test can say what the state holds and what the exporter writes. Only a device can say whether the pages
 * really appear — whether a `Canvas` handed the report's marks produces a page of text rather than a white
 * rectangle — and whether pressing Save leaves this app for the system's picker, which is the one thing §5 asks
 * of the screen and the one thing no coroutine test can reach.
 *
 * So this file opens the report the way a reader does, from the account, and checks six things:
 *
 *  * the preview is displayed and carries ink;
 *  * the period chips change the answer on screen;
 *  * the zone search narrows a list of several hundred, and the zone chosen is the one the header then states —
 *    with the offset that zone is standing at, so the choice stays legible to someone who does not know that
 *    `Asia/Tokyo` has no summer time;
 *  * the csv preview shows the reader's own category name, in the file's own quoting;
 *  * the zoom makes the sheet on screen bigger, level by level, and puts it back the size it was;
 *  * Save leaves the app for the platform's document picker without claiming anything first, and Share leaves a
 *    copy in this app's own cache that is **still there** after the sheet has been opened — because a copy taken
 *    away while the receiving app is opening it is a share that fails.
 *
 * The profile is seeded straight into the app's storage rather than typed into the profile form: what is under
 * test here is the report, and a form with its own tests would only make this slower and less predictable. The
 * seed builds the database the way the app's own provider does, because this project has no Hilt test harness
 * and adding one for a single screen is a bigger change than this file needs. From the navigation onwards every
 * step is the reader's own path, including "another app is in front", which is read from this activity's
 * lifecycle state rather than from a system API a test has no right to ask.
 *
 * Every wait is a wait for the screen and not a sleep: the document is laid out off the main thread, and a
 * handset running a software GPU is slower than the laptop driving it. When one of them gives up, the message
 * carries what the screen was actually holding at that moment, because "the preview never appeared" is only half
 * of what the next reader of this failure needs.
 *
 * Each state it passes through is photographed into the app's external files directory. The images are pulled
 * off afterwards and looked at, and the ones that decided something are kept beside the documentation: a visual
 * claim nobody can see is not a claim.
 *
 * A second test goes the whole way through Save, pressing the platform's own button in the picker, because a
 * hand-over that was only ever started is not a hand-over that worked. What the screen announces is written down
 * beside its own screenshot, and the file the picker was pointed at is then read from outside the app: an app
 * may not walk a shared folder looking for documents, so the byte count the screen promises and the bytes that
 * really landed are compared where both are visible — on the laptop driving the handset.
 */
@RunWith(AndroidJUnit4::class)
class ReportScreenInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun signInAProfileThatHasWork() {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppMigrations.ALL)
            .build()
        runBlocking {
            val id = database.userDao().insertUser(
                User(email = "report@example.test", name = "Анна Ковалёва")
            )
            database.userDao().setCurrentUser(database.userDao().getUserById(id)!!)
            val category = database.categoryDao().insertCategory(
                Category(name = "Диссертация", color = -0xB0C4, icon = "ic_tag", userId = id)
            )
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            for (day in 0 until 5) {
                database.taskDao().insertTask(
                    Task(
                        title = "Глава ${day + 1}",
                        description = "",
                        status = TaskStatus.COMPLETED,
                        completedAt = today.minusDays(day.toLong()).atTime(10, 0)
                            .atZone(zone).toInstant().toEpochMilli(),
                        userId = id,
                        categoryId = category
                    )
                )
            }
            for (open in 0 until 3) {
                database.taskDao().insertTask(
                    Task(
                        title = "Открытая задача $open",
                        description = "",
                        userId = id,
                        categoryId = category
                    )
                )
            }
        }
        database.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    @Test
    fun aReaderOpensTheReportFromTheirAccountAndCanHandItOver() {
        openTheReport()

        // The document is laid out off the main thread, so this is a wait rather than an immediate check; what
        // is asserted afterwards is not that the screen got around to it but that the sheet is on the screen.
        assertTrue("the preview arrived", waitUntil("the report pages") { shown(R.id.reportPagesScroll) })
        onView(withId(R.id.reportPages)).check(matches(isDisplayed()))
        assertTrue("a sheet was laid out", waitUntil("a page of paper") {
            val sheet = findView(R.id.reportPages)
            sheet != null && sheet.height > MIN_SHEET && sheet.width > MIN_SHEET
        })
        assertTrue("and the paper is not blank", waitUntil("ink on the sheet") {
            inkOn(findView(R.id.reportPages) ?: return@waitUntil false) > INK_MINIMUM
        })
        shot("01-report-opened")

        assertTrue("the two hand-overs are offered", waitUntil("live buttons") {
            findView(R.id.reportSaveButton)?.isEnabled == true &&
                findView(R.id.reportShareButton)?.isEnabled == true
        })

        chooseAWeek()
        chooseAnotherZone()
        zoomThroughTheLevels()
        csvPreviewShowsTheFileItself()
        saveAsksTheSystemWhereToPutIt()
        shareLeavesItsCopyBehind()
    }

    /**
     * A save the reader finishes, finished: the picker is pressed, the platform hands a location back, and the
     * screen is heard saying what it wrote.
     *
     * The first test stops at the picker on purpose — a cancelled hand-over must write nothing and say nothing,
     * and that is the case most likely to be got wrong. This one goes the whole way, because §6 asks for a
     * sequence that ends in a file and §15 refuses to call a pdf verified from a JVM. The platform's own SAVE is
     * pressed through the accessibility connection — the picker is another app's window and Espresso has no claim
     * on it — and what is asserted is the sentence the screen prints afterwards, which is the app's own
     * confirmation that bytes reached the place the reader chose.
     *
     * The file itself is then looked at from outside the app, over the debug bridge, against the byte count
     * recorded beside these screenshots: an app may not walk another app's folder looking for a document it was
     * handed, and a test that could would be proving a permission this one deliberately does not ask for.
     */
    @Test
    fun aSaveTheReaderFinishesLeavesTheReportOnDisk() {
        openTheReport()
        assertTrue("the preview arrived", waitUntil("the report pages") { shown(R.id.reportPagesScroll) })
        assertTrue("the sheet was laid out", waitUntil("a page of paper") {
            (findView(R.id.reportPages)?.height ?: 0) > MIN_SHEET
        })
        val note = text(R.id.reportViewNote)

        onView(withId(R.id.reportSaveButton)).perform(click())
        assertTrue("the picker is in front", waitUntil("the system's picker") { !isInFront() })
        Thread.sleep(PICKER_SETTLE_MILLIS)
        assertTrue("and its SAVE was pressed", pressThePlatformButton(SAVE_LABEL))
        assertTrue(
            "the report is where the reader lands again",
            waitUntil("back at the report") { isInFront() && shown(R.id.reportSaveButton) }
        )

        assertTrue(
            "the screen says the file was written",
            waitUntil("the saved sentence") { savedMessage() != null }
        )
        val message = savedMessage()!!
        val bytes = digitsOf(message)
        assertTrue("and it names the bytes it wrote: $message", bytes > MIN_REPORT_BYTES)
        assertTrue(
            "the promise came only after the writing: the buttons are live again, not stuck on a spinner",
            findView(R.id.reportSaveButton)?.isEnabled == true
        )
        shot("10-saved")
        record("10-saved-message", "announced=$message\nnote=$note\n")
        println("saved report: note=$note announced=$message bytes=$bytes")
    }

    // -- the path ---------------------------------------------------------------------------------

    private fun openTheReport() {
        onView(withId(R.id.navigation_profile)).perform(click())
        waitUntil("the account screen") { findView(R.id.openReportsButton) != null }
        shot("00-profile-entry")
        onView(withId(R.id.openReportsButton)).perform(scrollTo(), click())
        waitUntil("the report screen") { findView(R.id.reportPages) != null }
    }

    private fun chooseAWeek() {
        onView(withText(WEEK_CHIP)).perform(click())
        assertTrue("the week's pages", waitUntil("the week's pages") {
            shown(R.id.reportPagesScroll) && (findView(R.id.reportPages)?.height ?: 0) > MIN_SHEET
        })
        assertTrue(
            "the header states the span the report was measured between",
            text(R.id.reportWindowLabel).isNotBlank()
        )
        shot("02-period-week")
    }

    /**
     * The zone picker, used the way a reader who does not live here would: type, then take the row.
     *
     * The searched zone is chosen by its own text rather than by a row number, because the device's zone is
     * offered whatever the search says — a picker that hides its own default is one that cannot be got out of —
     * and a test that counted rows would be checking that courtesy rather than the choice.
     */
    private fun chooseAnotherZone() {
        onView(withId(R.id.reportZoneButton)).perform(click())
        onView(withId(R.id.reportZoneSearch)).check(matches(isDisplayed()))
        onView(withId(R.id.reportZoneSearch)).perform(replaceText("tokyo"), closeSoftKeyboard())
        onView(withText(startsWith("Asia/Tokyo"))).check(matches(isDisplayed()))
        shot("03-zone-search")
        onView(withText(startsWith("Asia/Tokyo"))).perform(click())
        assertTrue(
            "the header names the new zone",
            waitUntil("the header names the new zone") { text(R.id.reportZoneLabel).contains("Asia/Tokyo") }
        )
        assertTrue(
            "and states the offset that zone is standing at today",
            text(R.id.reportZoneLabel).contains("UTC+09:00")
        )
        shot("04-zone-tokyo")
    }

    /**
     * The zoom, taken through every level it offers, and back to where it started.
     *
     * A JVM test can prove the sheet's geometry scales. Only a device shows whether the view underneath the
     * reader actually grew — a scaling that left the drawing at its old size would look correct in the state and
     * be useless on screen — and whether the buttons let go at the ends of the range, since a control that stays
     * live past the largest sheet invites presses that do nothing.
     *
     * The width is what a level is defined by, so it is measured against the level's own factor. The height is
     * only required to follow it upward: the gap between sheets belongs to the screen rather than to the sheet,
     * so a document of several pages grows by the pages and not by the spaces. Coming back down is measured to
     * the pixel, because a round trip that ended at "almost the original" would be a document the reader never
     * asked to resize.
     */
    private fun zoomThroughTheLevels() {
        val base = sheetSize()
        assertTrue("a sheet to measure", base.width > MIN_SHEET && base.height > MIN_SHEET)

        onView(withId(R.id.reportZoomIn)).perform(click())
        val middle = waitSheet("a sheet $base grown to ${ReportPageFit.factor(1)}") {
            abs(it.width - base.width * ReportPageFit.factor(1)) < SIZE_TOLERANCE && it.height > base.height
        }
        onView(withId(R.id.reportZoomIn)).perform(click())
        waitSheet("the sheet at its largest") {
            abs(it.width - base.width * ReportPageFit.factor(ReportPageFit.MAX_ZOOM)) < SIZE_TOLERANCE &&
                it.height > middle.height
        }
        assertTrue(
            "at the largest sheet the zoom-in has nothing left to do",
            waitUntil("the zoom-in lets go") { findView(R.id.reportZoomIn)?.isEnabled == false }
        )
        shot("08-zoom-max")

        onView(withId(R.id.reportZoomOut)).perform(click())
        waitSheet("the sheet back down one level") {
            abs(it.width - base.width * ReportPageFit.factor(1)) < SIZE_TOLERANCE
        }
        onView(withId(R.id.reportZoomOut)).perform(click())
        val back = waitSheet("the sheet back at $base") {
            abs(it.width - base.width) < SIZE_TOLERANCE && abs(it.height - base.height) < SIZE_TOLERANCE
        }
        assertTrue(
            "and the zoom-out has nothing left to take back at the first level",
            waitUntil("the zoom-out lets go") { findView(R.id.reportZoomOut)?.isEnabled == false }
        )
        shot("09-zoom-base")
        assertTrue("the round trip is the size it started at, to the pixel: $back against $base",
            back == base)
    }

    private data class SheetSize(val width: Int, val height: Int)

    private fun sheetSize(): SheetSize {
        val sheet = findView(R.id.reportPages) ?: return SheetSize(0, 0)
        return SheetSize(sheet.width, sheet.height)
    }

    /** Pumps until the laid-out sheet answers to [condition], then hands back what it measured. */
    private fun waitSheet(description: String, condition: (SheetSize) -> Boolean): SheetSize {
        waitUntil(description) { condition(sheetSize()) }
        return sheetSize()
    }

    /**
     * The csv view, checked as the file rather than as a block of text.
     *
     * Two things are only answerable on a device. Whether a row that is longer than the screen is still one row
     * on it — a wrapped row reads as two, and a reader counting rows counts wrong — and whether the caption above
     * them states the truth of what is on screen, which for a file that fits is "all of them" rather than a
     * "first 79 of 79" that leaves someone hunting for the missing part.
     */
    private fun csvPreviewShowsTheFileItself() {
        onView(withId(R.id.reportFormatCsv)).perform(click())
        assertTrue("the csv lines", waitUntil("the csv lines") { text(R.id.reportCsv).startsWith("section") })
        shot("06-csv-preview")
        assertTrue(
            "the reader's own category name, quoted by the writer, is among the lines shown",
            text(R.id.reportCsv).contains("Диссертация")
        )

        assertTrue(
            "and the zoom is put away with the sheets it acts on",
            findView(R.id.reportZoomIn)?.visibility != View.VISIBLE &&
                findView(R.id.reportZoomOut)?.visibility != View.VISIBLE
        )

        val rows = text(R.id.reportCsv).split("\n")
        val drawn = (findView(R.id.reportCsv) as? TextView)?.layout
        assertTrue(
            "a row stays one line on the screen: ${rows.size} rows, ${drawn?.lineCount} lines drawn",
            drawn != null && drawn.lineCount == rows.size
        )
        assertTrue(
            "and the caption says the whole file is here when the whole file is here: ${text(R.id.reportViewNote)}",
            text(R.id.reportViewNote) == context.resources.getQuantityString(
                R.plurals.report_csv_all_rows,
                rows.size,
                rows.size
            )
        )
    }

    private fun saveAsksTheSystemWhereToPutIt() {
        onView(withId(R.id.reportFormatPdf)).perform(click())
        waitUntil("the pages again") { (findView(R.id.reportPages)?.height ?: 0) > MIN_SHEET }
        val window = text(R.id.reportWindowLabel)
        val zone = text(R.id.reportZoneLabel)

        onView(withId(R.id.reportSaveButton)).perform(click())
        waitUntil("the system's picker") { !isInFront() }
        // The picker is another app's window and this one has no right to ask when it finished drawing, so the
        // photograph waits for the platform to catch up rather than claiming to show a picker it may have
        // captured a moment before it appeared.
        Thread.sleep(PICKER_SETTLE_MILLIS)
        shot("05-save-picker")
        comeBack()

        assertTrue(
            "the reader comes back to the report, not to the start of the app",
            waitUntil("back at the report") { isInFront() && shown(R.id.reportSaveButton) }
        )
        // The screen re-reads when it is returned to, because what the reader did elsewhere belongs in the
        // report — but the document they left is the document they come back to. A sheet that blinked out and
        // came again would be the screen admitting it had thrown the answer away for nothing.
        assertTrue(
            "the pages were never taken away",
            shown(R.id.reportPagesScroll) && (findView(R.id.reportPages)?.height ?: 0) > MIN_SHEET
        )
        assertEquals("a save nobody finished left the period where it was", window, text(R.id.reportWindowLabel))
        assertEquals("and the timezone with it", zone, text(R.id.reportZoneLabel))
        assertTrue(
            "and Save is offered again once the re-read has landed",
            waitUntil("Save live again") { findView(R.id.reportSaveButton)?.isEnabled == true }
        )
        assertTrue(
            "a save nobody finished wrote nothing: this app's cache holds no pdf",
            staged().none { it.name.endsWith(".pdf") }
        )
    }

    private fun shareLeavesItsCopyBehind() {
        onView(withId(R.id.reportShareButton)).perform(click())
        waitUntil("the sheet") { !isInFront() }
        Thread.sleep(PICKER_SETTLE_MILLIS)
        shot("07-share-sheet")
        comeBack()
        assertTrue(
            "the reader comes back to the report",
            waitUntil("back at the report") { isInFront() && shown(R.id.reportShareButton) }
        )

        val copies = staged().filter { it.length() > 0L }
        assertTrue(
            "the copy the sheet was handed is still where the receiving app can read it",
            copies.isNotEmpty()
        )
        assertTrue(
            "and it is named the way the exporter names a report of this period",
            copies.any { it.name.startsWith("taskplanner-report-") }
        )
    }

    // -- the helpers ------------------------------------------------------------------------------

    /**
     * Pressing a button the platform owns.
     *
     * The document picker is another app's window, so Espresso has no claim on it and this test does not pretend
     * otherwise: the node is asked for through the instrumentation's own accessibility connection, which is the
     * API designed for reaching across apps, and nothing is pressed unless the platform says it can be. A label
     * is searched rather than a screen coordinate, because the picker lays its buttons out differently between
     * versions and a coordinate that moved would press the wrong thing rather than report that it missed.
     */
    private fun pressThePlatformButton(label: String): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val root = runCatching { instrumentation.uiAutomation.rootInActiveWindow }.getOrNull()
            val target = root?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.isClickable }
            if (target != null) {
                val pressed = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                println("platform button '$label' pressed=$pressed")
                return pressed
            }
            Thread.sleep(200)
        }
        return false
    }

    /**
     * The sentence the screen is saying about the last hand-over, while it is still saying it.
     *
     * A snackbar belongs to the screen rather than to the state, so this is read off the view the material
     * library paints it with; the test asks for it repeatedly because it is on screen for a few seconds and the
     * writing it reports on takes longer than a press.
     */
    private fun savedMessage(): String? {
        var found: String? = null
        scenario.onActivity { activity ->
            val view = activity.findViewById<View>(com.google.android.material.R.id.snackbar_text)
            val text = (view as? TextView)?.text?.toString()
            if (text != null && text.startsWith(SAVED_PREFIX)) found = text
        }
        return found
    }

    /** The figure inside the saved sentence, with its grouping taken off. */
    private fun digitsOf(message: String): Long =
        message.filter { it.isDigit() }.toLongOrNull() ?: 0L

    /** A line of text written beside the screenshots, for the claims checked from outside the app. */
    private fun record(name: String, content: String) {
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(), "shots"
        ).apply { mkdirs() }
        File(directory, "$name.txt").writeText(content)
    }

    /**
     * Whether this app is the one the reader is looking at.
     *
     * The activity's own lifecycle state is the honest answer: the moment another app's window — the document
     * picker, the chooser — covers it, this one stops being resumed. Asking the platform which activity is on top
     * needs a system API a test has no right to.
     */
    private fun isInFront(): Boolean = scenario.state == Lifecycle.State.RESUMED

    /**
     * Coming back from a window this app does not own.
     *
     * The press is aimed at the other app and nowhere else: a back sent after this one is already home pops the
     * report screen itself, which is how a reader loses the period they just chose. So this presses only while
     * another window is in front, and after each press waits — for as long as ten seconds on a software GPU —
     * for this activity to be resumed again before it considers pressing twice.
     */
    private fun comeBack() {
        repeat(PICKER_RETREATS) {
            if (isInFront()) return
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            val deadline = System.currentTimeMillis() + LANDING_MILLIS
            while (System.currentTimeMillis() < deadline && !isInFront()) Thread.sleep(100)
        }
    }

    private fun staged(): List<File> =
        File(context.cacheDir, "report_exports").listFiles()?.filter { it.isFile }.orEmpty()

    private fun findView(id: Int): View? {
        var found: View? = null
        scenario.onActivity { activity -> found = activity.findViewById<View>(id) }
        return found
    }

    private fun shown(id: Int): Boolean = findView(id)?.visibility == View.VISIBLE

    private fun text(id: Int): String = (findView(id) as? TextView)?.text?.toString().orEmpty()

    /** What the screen is holding, in one line, for the message of a wait that had to give up. */
    private fun screenState(): String {
        val sheet = findView(R.id.reportPages)
        return buildString {
            append("problem=").append(shown(R.id.reportProblem)).append(':').append(text(R.id.reportProblemText))
            append(" empty=").append(shown(R.id.reportEmpty)).append(':').append(text(R.id.reportEmpty))
            append(" progress=").append(shown(R.id.reportProgress))
            append(" sheet=").append(shown(R.id.reportPagesScroll)).append(sheet?.width).append('x').append(sheet?.height)
            append(" csv=").append(shown(R.id.reportCsvScroll)).append(':').append(text(R.id.reportCsv).take(40))
            append(" window=").append(text(R.id.reportWindowLabel))
            append(" zone=").append(text(R.id.reportZoneLabel))
            append(" note=").append(text(R.id.reportViewNote))
        }
    }

    /** Dark pixels of a view as it would be drawn — "did the report appear", asked of pixels. */
    private fun inkOn(view: View): Int {
        if (view.width <= 0 || view.height <= 0) return 0
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        var dark = 0
        for (y in 0 until bitmap.height step 7) {
            for (x in 0 until bitmap.width step 5) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 0 &&
                    Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 600
                ) {
                    dark++
                }
            }
        }
        bitmap.recycle()
        return dark
    }

    /** Pumps until [condition] holds, and names the thing that never happened when it does not. */
    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        // The screen's own state travels with the complaint: "the preview never appeared" is half of what the
        // next reader needs, and the other half is what the screen said instead of a preview.
        throw AssertionError(
            "$description did not happen within $TIMEOUT_MILLIS ms. ${screenState()}"
        )
    }

    /** A screen of this app, photographed where adb can reach it afterwards. */
    private fun shot(name: String) {
        runCatching { instrumentation.waitForIdleSync() }
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        if (bitmap == null) {
            System.err.println("screenshot $name was refused by the platform")
            return
        }
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(), "shots"
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        println("screenshot written $name.png")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L

        /** The chip that asks for a week, in the language the test device speaks. */
        const val WEEK_CHIP = "A week"

        /** A sheet below this height was never laid out. */
        const val MIN_SHEET = 400

        /** Fewer dark samples than this and the page is blank; a page of Russian text gives hundreds. */
        const val INK_MINIMUM = 200

        /** How far a scaled sheet may sit from the pixel its factor asks for: a page is computed in points. */
        const val SIZE_TOLERANCE = 2f

        /** How many times to press back before saying the picker would not let go. */
        const val PICKER_RETREATS = 6

        /** How long the activity is given to be resumed again after a press aimed at another app's window. */
        const val LANDING_MILLIS = 10_000L

        /** How long another app's window is given to draw itself before it is photographed. */
        const val PICKER_SETTLE_MILLIS = 2_500L

        /** The button the platform's own save sheet puts the reader's answer behind. */
        const val SAVE_LABEL = "save"

        /** How the screen opens the sentence it says about a file it wrote. */
        const val SAVED_PREFIX = "Written to the file you chose"

        /** A report of a real month is tens of kilobytes; below this, nothing was written. */
        const val MIN_REPORT_BYTES = 10_000
    }
}
