package com.taskplanner.ui.tasks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.ui.MainActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The add button against the last card: who may occupy the same pixel.
 *
 * A reviewer looked at the Stage 4 photographs and saw the floating button sitting on a card — in
 * `s4-card-overdue.png` it covers the green square that completes the third row. That is true of the frame
 * and needs an answer that is not an opinion. A list whose content passes under a floating button while it
 * scrolls is ordinary Material behaviour; what would be a defect is a row whose actions the reader can
 * *never* reach: a card stuck under the button at the end of the scroll, or a complete button too small a
 * slice of which the platform will accept as displayed. So this file measures three things on the device and
 * photographs each of them.
 *
 *  1. The gutter under the last row is the one the design names, not a literal that can drift away from the
 *     button it is supposed to clear.
 *  2. At the end of the scroll the last row's complete button does not intersect the button's own rectangle,
 *     and Espresso's own 90%-visible rule accepts it as displayed.
 *  3. A row that *is* under the button mid-list comes clear on the next scroll — the overlap is a state a
 *     reader passes through, not a place a control can be stranded.
 *
 * The profile and its rows are seeded once for the class, straight into the app's storage: one app process
 * serves one profile, and a class that seeded per test would read the first test's rows from the second
 * onward (plan §20l). Screenshots go to the app's internal files directory, which is the only place `adb`
 * can reach through `run-as`, and each frame is checked for content before it is kept.
 */
@RunWith(AndroidJUnit4::class)
class TasksFabClearanceInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheList() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the app starts on Tasks", waitUntil("the list") { list() != null })
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    /**
     * The gutter is deep enough for the button, whatever the design calls it.
     *
     * Stage 1 added `list_bottom_gutter` with the note that it keeps the last row and the floating button
     * from drifting apart, and pinned it by nothing — and `fragment_tasks.xml` did not read it either: it
     * carried the same 88dp as a literal, so the two could separate silently. The layout now names the
     * token, which makes "padding equals the dimen" a tautology worth no test. What is worth a test is the
     * geometry the token exists to guarantee: the space under the last row is at least the button's own
     * height plus its margin, and it scrolls with the content rather than clipping it.
     */
    @Test
    fun theGutterUnderTheListClearsTheButtonsOwnFootprint() {
        val recycler = list()!!
        val fab = view(R.id.fabAddTask) as FloatingActionButton
        val footprint = fab.height + marginOf(fab)
        assertTrue(
            "the list leaves ${recycler.paddingBottom}px of gutter under its last row, and the floating " +
                "button takes ${fab.height}px plus ${marginOf(fab)}px of margin = ${footprint}px",
            recycler.paddingBottom >= footprint
        )
        assertTrue("the gutter scrolls with the content instead of cutting the last row off",
            !recycler.clipToPadding)
        shot("s6-fab-top")
    }

    @Test
    fun theLastRowClearsTheButtonAtTheEndOfTheScroll() {
        scrollToEnd()
        val fab = view(R.id.fabAddTask) as FloatingActionButton
        val row = lastRow()
        val complete = row.findViewById<View>(R.id.completeTaskButton)
        val fabBox = boxOf(fab)
        val buttonBox = boxOf(complete)

        assertTrue(
            "at the end of the scroll the complete button is " +
                "${describeOverlap(buttonBox, fabBox)}, and the button's own footprint is " +
                "${fab.height}px + ${marginOf(fab)}px of margin against a " +
                "${list()!!.paddingBottom}px gutter",
            !Rect.intersects(buttonBox, fabBox)
        )
        // The same bar Espresso's `isDisplayed()` applies — at least 90% of the view inside the window —
        // computed on *this* row's button. `onView(withId(completeTaskButton))` would match every row on
        // screen and throw the ambiguity instead of answering the question (Stage 5 learned that the hard
        // way on a seven-row list).
        val shown = Rect().also { complete.getGlobalVisibleRect(it) }
        val shownArea = shown.width().toLong() * shown.height().toLong()
        val wholeArea = complete.width.toLong() * complete.height.toLong()
        assertTrue(
            "the complete button is only ${shown.width()}x${shown.height()} of its " +
                "${complete.width}x${complete.height} visible on screen",
            shownArea * 10 >= wholeArea * 9
        )
        shot("s6-fab-bottom-row")
    }

    /**
     * The reviewer's frame, made on purpose and then measured.
     *
     * A row parked under the button is not yet a defect; a row that stays there however far the reader
     * scrolls is. The first version of this test looked for a row the button happened to cover, and at 320dp
     * it found none — the cards are taller in pixels there, so the frame simply did not contain the state it
     * wanted to talk about, and the test failed for the shape of the screen rather than for the behaviour of
     * the design. So the state is now *created*: scroll until one row's complete button sits under the
     * floating button, photograph that, scroll on by one item, and require the same button to have left.
     *
     * That is a property, and it holds at every width this project supports.
     */
    @Test
    fun aRowUnderTheButtonLeavesItOnTheNextScroll() {
        val fab = view(R.id.fabAddTask) as FloatingActionButton
        val fabBox = boxOf(fab)
        scrollToTop()

        // A row whose button can still be brought up to the button's own line: scrolling forward moves
        // content up, so only a row drawn below that line can be parked there.
        val candidate = (0 until list()!!.childCount)
            .map { list()!!.getChildAt(it) }
            .firstOrNull { row ->
                val complete = row.findViewById<View>(R.id.completeTaskButton)
                complete != null && boxOf(complete).centerY() > fabBox.centerY()
            }
        assertNotNull("the list has a row below the button to bring up to it", candidate)

        val button = candidate!!.findViewById<View>(R.id.completeTaskButton)
        scrollBy(button.boxInList().centerY() - fabBox.centerY())
        assertTrue(
            "the scroll did not park the row under the button as this test intends: " +
                "${describeOverlap(boxOf(button), boxOf(fab))}",
            Rect.intersects(boxOf(button), boxOf(fab))
        )
        shot("s6-fab-mid-list")

        scrollBy(lastRow().height + 16)
        assertTrue(
            "one item of scroll later the button still covers it: ${describeOverlap(boxOf(button), boxOf(fab))}",
            !Rect.intersects(boxOf(button), boxOf(fab))
        )
    }

    // ---- geometry ------------------------------------------------------------------------------

    private fun list(): RecyclerView? = view(R.id.recyclerView) as? RecyclerView

    private fun view(id: Int): View? {
        var found: View? = null
        scenario.onActivity { activity -> found = activity.findViewById<View>(id) }
        return found
    }

    private fun boxOf(target: View): Rect {
        val where = IntArray(2)
        target.getLocationInWindow(where)
        return Rect(where[0], where[1], where[0] + target.width, where[1] + target.height)
    }

    private fun describeOverlap(a: Rect, b: Rect): String =
        if (!Rect.intersects(a, b)) "clear of the button" else
            "under it by ${Rect(a).apply { intersect(b) }.height()}px"

    private fun marginOf(target: View): Int =
        (target.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.bottomMargin
            ?: (target.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    /** The row drawn at the end of the list — the one the gutter exists for. */
    private fun lastRow(): View {
        val recycler = list()!!
        val child = recycler.getChildAt(recycler.childCount - 1)
        assertNotNull("the list has a row drawn at its end", child)
        return child!!
    }

    private fun lastVisibleRowAt(row: View): View? =
        (0 until list()!!.childCount).map { list()!!.getChildAt(it) }.firstOrNull { it === row }

    private fun scrollToEnd() {
        val recycler = list()!!
        scenario.onActivity {
            recycler.scrollToPosition(recycler.adapter!!.itemCount - 1)
        }
        settle()
        // One more page, because the last row is only *aligned* to the end after the fling settles.
        scenario.onActivity { recycler.scrollBy(0, recycler.height) }
        settle()
    }

    private fun scrollBy(delta: Int) {
        val recycler = list()!!
        scenario.onActivity { recycler.scrollBy(0, delta) }
        settle()
    }

    private fun scrollToTop() {
        val recycler = list()!!
        scenario.onActivity {
            recycler.scrollToPosition(0)
        }
        settle()
    }

    /** Where this view actually is, in the list's own coordinates, at the moment of asking. */
    private fun View.boxInList(): Rect = boxOf(this)

    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(SETTLE_MILLIS)
    }

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        System.err.println("fab wait gave up on: $description")
        return false
    }

    /** Photographed only when the screen really holds a drawn card: see plan §20l on empty frames. */
    private fun shot(name: String) {
        settle()
        val bounds = boxOf(lastRow())
        var attempt = 0
        var frame: Bitmap? = null
        while (attempt < CAPTURE_ATTEMPTS) {
            attempt++
            val taken = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
                ?: throw AssertionError("the platform refused the screenshot '$name' this run cites")
            if (hasContrastInside(taken, bounds)) { frame = taken; break }
            taken.recycle()
            settle()
        }
        val bitmap = frame ?: throw AssertionError(
            "the list never drew into a photographed frame in $CAPTURE_ATTEMPTS attempts"
        )
        File(context.filesDir, "shots").apply { mkdirs() }
            .let { File(it, "$name.png") }
            .outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        bitmap.recycle()
        println("screenshot written $name.png after $attempt frame(s)")
    }

    private fun hasContrastInside(frame: Bitmap, bounds: Rect): Boolean {
        val left = bounds.left.coerceIn(0, frame.width - 1)
        val top = bounds.top.coerceIn(0, frame.height - 1)
        val right = bounds.right.coerceIn(left + 1, frame.width)
        val bottom = bounds.bottom.coerceIn(top + 1, frame.height)
        var min = 255
        var max = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = frame.getPixel(x, y)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 +
                    Color.blue(pixel) * 114) / 1000
                if (luminance < min) min = luminance
                if (luminance > max) max = luminance
                x += 3
            }
            y += 3
        }
        return max - min >= MIN_CONTRAST_SPREAD
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val SETTLE_MILLIS = 250L
        const val CAPTURE_ATTEMPTS = 6
        const val MIN_CONTRAST_SPREAD = 120

        /** Enough rows that the list scrolls past the button on every panel this project supports. */
        const val ROWS = 9
        const val EMAIL = "fab-clearance@example.test"

        private lateinit var seeded: AppDatabase
        private var seededId: Long = 0L
        private var displaced: User? = null

        @JvmStatic
        @BeforeClass
        fun signInAProfileWithALongList() {
            seeded = Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppDatabase::class.java, AppDatabase.NAME
            ).addMigrations(*AppMigrations.ALL).build()
            runBlocking {
                val foreign = seeded.userDao().getAllUsersOnce()
                    .filterNot { it.email.endsWith("@example.test") }
                assertTrue("this class has to make its own profile current and would sign out " +
                    "${foreign.size} real profile(s)", foreign.isEmpty())
                displaced = seeded.userDao().getCurrentUser()
                seeded.userDao().getUserByEmail(EMAIL)?.let { seeded.userDao().deleteUser(it) }
                seededId = seeded.userDao().insertUser(User(email = EMAIL, name = "Мария Орлова"))
                val user = seeded.userDao().getUserById(seededId)!!
                seeded.userDao().setCurrentUser(user)
                val day = 86_400_000L
                val now = System.currentTimeMillis()
                for (index in 0 until ROWS) {
                    seeded.taskDao().insertTask(
                        Task(
                            uuid = UUID.randomUUID().toString(),
                            title = "Строка ${index + 1} длинного списка задач",
                            description = "",
                            priority = Priority.LOW,
                            status = TaskStatus.TODO,
                            dueDate = now + (index + 1) * day,
                            userId = user.id
                        )
                    )
                }
            }
        }

        @JvmStatic
        @AfterClass
        fun takeTheListAway() {
            runBlocking {
                seeded.taskDao().getAllTasksOnce(seededId).forEach { seeded.taskDao().deleteTask(it) }
                seeded.userDao().getUserById(seededId)?.let { seeded.userDao().deleteUser(it) }
                displaced?.let { seeded.userDao().setCurrentUser(it) }
            }
            seeded.close()
        }
    }
}
