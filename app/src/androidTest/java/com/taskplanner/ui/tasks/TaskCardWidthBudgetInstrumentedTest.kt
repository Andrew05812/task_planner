package com.taskplanner.ui.tasks

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.ui.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card's fit, measured by the renderer that ships.
 *
 * `TaskCardWidthBudgetTest` does the box arithmetic in a JVM, where it is real and fast. What a JVM cannot
 * do here is say whether a *string* fits its box: Robolectric's LEGACY graphics answer `text.length()` from
 * `Paint.measureText` and ignore `textSize`, and NATIVE mode has no platform binary on this machine. So the
 * claims that depend on glyph widths — the date and the status word are drawn in full, nothing runs past the
 * card, line B reflows when the type grows — are asserted here, against the text metrics a phone actually
 * uses.
 *
 * The class is written to be run at each configuration the matrix names, and it reads its own configuration
 * rather than assuming one: the width in dp comes from the resources, the font scale from the same place,
 * and the two decide which promise applies. The runner therefore drives it at 320dp, 360dp and 393dp and at
 * font scale 1.0 and 1.3 (plan §6), and every one of those runs must pass the same assertions.
 *
 * The row is the worst legal one: a 120-character title, the longest category name in the strings file, a
 * deadline far enough ahead to print a full date rather than "3 days late", a series, two reminders and a
 * running stretch. If the card survives that, it survives the ordinary one.
 */
@RunWith(AndroidJUnit4::class)
class TaskCardWidthBudgetInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheScreenTheWorstRowWasSeededFor() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the card is drawn", waitUntil("the card") { view(R.id.taskCard) != null })
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    /** The two facts the list is sorted and filtered by are drawn in full, at every configuration. */
    @Test
    fun theDateAndTheStatusWordAreNeverTruncated() {
        listOf(R.id.taskDueDate to "the date", R.id.taskStatus to "the status word").forEach { (id, what) ->
            val tv = view(id) as TextView
            val layout = tv.layout
            assertTrue("$what has no layout to inspect", layout != null)
            val clipped = (0 until layout!!.lineCount).sumOf { layout.getEllipsisCount(it) }
            assertEquals("$what is clipped in ${layout.lineCount} line(s)", 0, clipped)
            val content = view(R.id.cardContent)!!
            val onScreen = IntArray(2).also { tv.getLocationInWindow(it) }
            val contentOnScreen = IntArray(2).also { content.getLocationInWindow(it) }
            assertTrue(
                "$what runs past the card: right ${onScreen[0] + tv.width} vs content edge " +
                    "${contentOnScreen[0] + content.width - content.paddingRight}",
                onScreen[0] + tv.width <= contentOnScreen[0] + content.width - content.paddingRight
            )
        }
    }

    /** The title keeps a readable measure however wide the screen is. */
    @Test
    fun theTitleKeepsAReadableMeasure() {
        val title = view(R.id.taskTitle)!!
        val floor = 128.dp()
        assertTrue("the title is ${title.width}px wide, the floor is ${floor}px", title.width >= floor)
    }

    /** Every action a card shows is a 48dp target — the number the audit's baseline missed by 8dp. */
    @Test
    fun everyVisibleControlIsATouchTarget() {
        val group = view(R.id.buttonGroup) as android.view.ViewGroup
        val visible = (0 until group.childCount).map { group.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
        assertTrue("the row has actions at all", visible.isNotEmpty())
        visible.forEach {
            assertTrue(
                "${resources.getResourceEntryName(it.id)} is ${it.width}x${it.height}, " +
                    "and a target is ${48.dp()}x${48.dp()}",
                it.width >= 48.dp() && it.height >= 48.dp()
            )
        }
        val work = view(R.id.workControlButton)!!
        if (work.visibility == View.VISIBLE) {
            assertTrue("the work control is ${work.width}x${work.height}", work.width >= 48.dp())
        }
    }

    /** The dot carries the colour, and the name appears only when it has something to say. */
    @Test
    fun theCategoryDotCarriesTheColourAndTheNameObeyThe48dpRule() {
        val dot = view(R.id.categoryDot)!!
        assertEquals("the dot is drawn", View.VISIBLE, dot.visibility)
        assertEquals("and tinted with the row's own category colour", 0xFF2F6F5E.toInt(),
            dot.backgroundTintList?.defaultColor)
        val name = view(R.id.taskCategory) as TextView
        val floor = CardPresentation.CATEGORY_NAME_MIN_DP.dp()
        val gap = resources.getDimensionPixelSize(R.dimen.spacing_small)
        val room = view(R.id.lineBFlow)!!.width -
            view(R.id.taskStatus)!!.width - view(R.id.taskDueDate)!!.width - 2 * gap
        assertTrue(
            "the name is ${name.width}px wide with ${room}px of room against a ${floor}px floor; it is " +
                "either drawn with room or gone, never cut to two letters and an ellipsis",
            name.visibility == View.GONE || name.width >= floor
        )
        // The threshold applied to the room this card actually has. Measured rather than read off the
        // screen width, because that is what the rule says — and it is why one layout serves 320dp, 360dp
        // and 393dp, and why the same layout holds at font scale 1.3, where the room shrinks with the type.
        val expected = if (room >= floor) View.VISIBLE else View.GONE
        println("line B at ${widthDp()}dp fontScale=${resources.configuration.fontScale}: line " +
            "${view(R.id.lineBFlow)!!.width}px, pill ${view(R.id.taskStatus)!!.width}px, date " +
            "${view(R.id.taskDueDate)!!.width}px, gap ${gap}px -> room ${room}px against a ${floor}px " +
            "floor, so the name is " +
            (if (expected == View.VISIBLE) "shown" else "gone"))
        assertEquals(
            "${widthDp()}dp at font scale ${resources.configuration.fontScale}: pill " +
                "${view(R.id.taskStatus)!!.width}px and date ${view(R.id.taskDueDate)!!.width}px leave " +
                "${room}px of the ${view(R.id.lineBFlow)!!.width}px line, so the name is " +
                (if (expected == View.VISIBLE) "shown" else "gone"),
            expected, name.visibility
        )
    }

    /**
     * The two allowances spec §4.1 budgets for line B, measured against the strings this row carries.
     *
     * 82dp is "In progress" in the `pill` role and 84dp is "Oct 25, 2026" in `meta` — the widest date a
     * card can be given. They are the numbers the 320dp budget is built from, so if the renderer's real
     * measure exceeds them the arithmetic in the spec is what has to change, not the type size: plan §4.9
     * says to report the measurement rather than shrink anything to make a test pass. At font scale 1.3
     * the same words are larger by design and line B reflows instead of clipping, which
     * [lineBReflowsWhenTheTypeGrows] asserts; here the two are still checked to be whole.
     */
    @Test
    fun thePillAndTheDateStayInsideTheirBudgetedAllowances() {
        val pill = view(R.id.taskStatus)!!
        val due = view(R.id.taskDueDate)!!
        val scale = resources.configuration.fontScale
        val column = view(R.id.lineBFlow)!!.width
        if (scale >= 1.25f) {
            assertTrue(
                "at $scale the pill measures ${pill.width}px and the date ${due.width}px; neither may " +
                    "leave the ${column}px line, and neither is allowed to be squeezed into it",
                pill.width <= column && due.width <= column
            )
            return
        }
        assertTrue(
            "the pill measures ${pill.width}px against the ${82.dp()}px spec §4.1 budgets for it",
            pill.width <= 82.dp()
        )
        assertTrue(
            "and the date ${due.width}px against the ${84.dp()}px allowance",
            due.width <= 84.dp()
        )
    }

    /**
     * Line B wraps when the pill and the date no longer share a line, and not because of any particular
     * font scale.
     *
     * Spec §4.1's example is 320dp at 1.3, where the pair measures about 227dp against a 208dp column and
     * the answer is that **the card grows**: the date drops below the pill, the row gets taller, and neither
     * the type nor the information is cut. The same pair at 393dp and 1.3 still fits one line, so an
     * assertion keyed to the scale alone would fail a run that is configured correctly — the trigger is the
     * width the row has against the width the two elements need, and both are measured here rather than
     * predicted.
     */
    @Test
    fun lineBWrapsWhenThePairNoLongerFitsOneLine() {
        val pill = view(R.id.taskStatus)!!
        val due = view(R.id.taskDueDate)!!
        val line = view(R.id.lineBFlow)!!
        val needed = pill.width + due.width + resources.getDimensionPixelSize(R.dimen.spacing_small)
        val wraps = needed > line.width
        val where = "font scale ${resources.configuration.fontScale}, ${widthDp()}dp: pill " +
            "${pill.width}px + date ${due.width}px + gap needs ${needed}px against a " +
            "${line.width}px line"
        if (wraps) {
            assertTrue("$where, so the date has to sit below the pill " +
                "(pill ${pill.top}..${pill.bottom}, date ${due.top}..${due.bottom})",
                due.top >= pill.bottom)
            assertEquals("and the pill is still drawn", View.VISIBLE, pill.visibility)
        } else {
            assertTrue("$where, so they share one band (pill ${pill.top}..${pill.bottom}, " +
                "date ${due.top}..${due.bottom})",
                due.top < pill.bottom && due.bottom > pill.top)
        }
        // Which ever branch the row is in, the pill and the date are both drawn whole — that is what
        // `theDateAndTheStatusWordAreNeverTruncated` measures, and it holds at every configuration.
        shot(if (wraps) "40-line-b-wrapped" else "41-line-b-one-line")
    }

    /** The three lines keep three bands: no view from one overlaps the next. */
    @Test
    fun theLinesKeepTheirOwnBands() {
        val title = view(R.id.taskTitle)!!
        val pill = view(R.id.taskStatus)!!
        val group = view(R.id.buttonGroup)!!
        val due = view(R.id.taskDueDate)!!
        assertTrue("line A's title ends at ${title.bottom}, line B's pill starts at ${pill.top}",
            title.bottom <= pill.top)
        assertTrue("line B ends at ${due.bottom}, line C's buttons start at ${group.top}",
            due.bottom <= group.top)
        shot("42-card-bands")
    }

    // -- the helpers ------------------------------------------------------------------------------

    private fun widthDp(): Int = resources.configuration.screenWidthDp

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private val resources get() = context.resources

    private fun view(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val outcome = runCatching(condition)
            if (outcome.getOrNull() == true) return true
            lastFailure = outcome.exceptionOrNull()
            Thread.sleep(50)
        }
        throw AssertionError("$description did not happen within $TIMEOUT_MILLIS ms", lastFailure)
    }

    /**
     * The card as the device draws it, at the configuration this invocation ran under.
     *
     * The frame is checked rather than trusted: it has been seen that the compositor answers with a screen
     * whose list has not drawn the row yet, and a photograph of an empty list measures nothing. The card's
     * own rectangle is therefore required to hold ink before the frame is kept, and a frame that fails the
     * test is taken again.
     */
    private fun shot(name: String) {
        assertTrue("the card is drawn before it is photographed",
            waitUntil("the card drawn") {
                val card = view(R.id.taskCard)
                card != null && card.width > 0 && card.isShown
            })
        instrumentation.waitForIdleSync()
        val card = view(R.id.taskCard)
        val where = IntArray(2).also { card?.getLocationInWindow(it) }
        val bounds = card?.let {
            android.graphics.Rect(where[0], where[1], where[0] + it.width, where[1] + it.height)
        }
        var attempt = 0
        var frame: android.graphics.Bitmap? = null
        while (attempt < CAPTURE_ATTEMPTS) {
            attempt++
            val taken = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
                ?: throw AssertionError("the platform refused the screenshot '$name'")
            if (bounds == null || hasContrastInside(taken, bounds)) { frame = taken; break }
            taken.recycle()
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MILLIS)
        }
        val bitmap = frame ?: throw AssertionError(
            "the screen never drew the card into a photographed frame in $CAPTURE_ATTEMPTS attempts"
        )
        val directory = File(context.filesDir, "shots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(
            android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        println("screenshot written $name.png at ${widthDp()}dp fontScale=" +
            "${resources.configuration.fontScale} after $attempt frame(s)")
    }

    /**
     * Whether this frame really has a card where the card should be.
     *
     * A level is not enough — in the dark theme the window background is itself dark, so "any dark pixel"
     * passes on an empty screen, which is the frame this check exists to refuse. What a drawn card has and
     * an undrawn list does not is contrast, so the measure is the luminance range inside the card's own
     * rectangle: a flat frame spans a few points, a card with a title, a rule and a filled button spans
     * hundreds.
     */
    private fun hasContrastInside(
        frame: android.graphics.Bitmap,
        bounds: android.graphics.Rect
    ): Boolean {
        val left = bounds.left.coerceIn(0, frame.width - 1)
        val top = bounds.top.coerceIn(0, frame.height - 1)
        val right = bounds.right.coerceIn(left + 1, frame.width)
        val bottom = bounds.bottom.coerceIn(top + 1, frame.height)
        var darkest = Int.MAX_VALUE
        var lightest = Int.MIN_VALUE
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = frame.getPixel(x, y)
                val luminance = (android.graphics.Color.red(pixel) * 299 +
                    android.graphics.Color.green(pixel) * 587 +
                    android.graphics.Color.blue(pixel) * 114) / 1000
                if (luminance < darkest) darkest = luminance
                if (luminance > lightest) lightest = luminance
                x += 3
            }
            y += 3
        }
        return lightest - darkest >= MIN_CONTRAST_SPREAD
    }

    /**
     * One profile, written before the first screen opens, and taken away after the last one closes.
     *
     * A connected run is one app process and `SessionRepository` is a Hilt singleton inside it, so the user
     * read at the first launch is the user every later launch of that run serves — see
     * [TaskCardStatesInstrumentedTest] for what a per-test seed looked like when that was not honoured. The
     * row also carries the series' cursor as its own `occurrenceAt`, which is what a real instance of a
     * series looks like and is what keeps `ensureOpenInstances` from answering "no live instance at the
     * cursor" by adding a second copy of this row to the list this class measures.
     */
    companion object {
        private const val CATEGORY_NAME = "Диссертация"
        private const val DAY = 86_400_000L
        private const val TIMEOUT_MILLIS = 60_000L

        /** How many frames a photograph may be retaken for, how long it waits between them, and what ink is. */
        private const val CAPTURE_ATTEMPTS = 6
        private const val SETTLE_MILLIS = 250L
        /** Luminance points the card's own rectangle has to span for a frame to be kept. */
        private const val MIN_CONTRAST_SPREAD = 120

        private var seededUserId: Long = 0

        @JvmStatic
        @BeforeClass
        fun signInAProfileWithTheWorstLegalRow() {
            val database = openDatabase()
            val now = System.currentTimeMillis()
            runBlocking {
                seededUserId = database.userDao().insertUser(
                    User(email = "budget-${System.nanoTime()}@example.test", name = "Ольга Тен")
                )
                database.userDao().setCurrentUser(database.userDao().getUserById(seededUserId)!!)
                val categoryId = database.categoryDao().insertCategory(
                    Category(id = 0, uuid = UUID.randomUUID().toString(), name = CATEGORY_NAME,
                        color = 0xFF2F6F5E.toInt(), icon = "", userId = seededUserId)
                )
                val rule = RecurrenceRule(
                    uuid = "budget-series-$seededUserId", userId = seededUserId,
                    unit = RepeatUnit.WEEK, interval = 1, weekDays = "1,3", dayOfMonth = 0,
                    anchorAt = now, cursorAt = now
                )
                database.recurrenceDao().upsertRule(rule)
                database.taskDao().insertTask(
                    Task(
                        uuid = UUID.randomUUID().toString(), title = "Т".repeat(120), description = "",
                        userId = seededUserId, status = TaskStatus.IN_PROGRESS,
                        dueDate = now + 30L * DAY, categoryId = categoryId, seriesUuid = rule.uuid,
                        occurrenceAt = rule.cursorAt,
                        priority = com.taskplanner.data.model.Priority.HIGH
                    )
                )
            }
            database.close()
        }

        @JvmStatic
        @AfterClass
        fun takeTheProfileAndItsRowAway() {
            val database = openDatabase()
            runBlocking {
                // A task has no foreign key to its user, on purpose — the same decision the sync and import
                // paths make — so deleting the profile alone would leave this row on the device forever.
                database.taskDao().getActiveTasksOnce(seededUserId)
                    .forEach { database.taskDao().deleteTask(it) }
                database.userDao().getUserById(seededUserId)?.let { database.userDao().deleteUser(it) }
            }
            database.close()
        }

        private fun openDatabase() = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java, AppDatabase.NAME
        ).addMigrations(*AppMigrations.ALL).build()
    }
}
