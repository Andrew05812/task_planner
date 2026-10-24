package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Environment
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.User
import com.taskplanner.ui.MainActivity
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The swipe that opens a task row, performed by a real finger-shaped motion stream.
 *
 * `TaskCardClosedPanelTest` measures the closed row in a JVM, and Stage 2's photographs show the closed
 * row on this device — the panel that used to leak 11px of colour and a 6px band down the right edge is
 * gone. Neither proves the other half: that a person can still *open* the row. `adb shell input
 * swipe` was tried six times across three timings for that and moved the card zero pixels while vertical
 * drags of the same construction scrolled the list, so the gesture had to be driven from inside the
 * process instead. Espresso's [GeneralSwipeAction] injects a down/move/up sequence through `UiController`
 * with proper motion history, which is what `TasksAdapter.handleTouch` reads (`event.rawX - downX`
 * against the touch slop). What that settles:
 *
 *  * if the card slides and stops at exactly minus the panel's width, the reveal works and the row's
 *    `OnTouchListener` is fine, and the earlier failure was the shell's injection and nothing more;
 *  * if it does not move here either, the defect is in the listener and this file is the report.
 *
 * The press on the revealed button is asserted as behaviour rather than as geometry, because the two
 * actions are distinguishable at that coordinate: a tap that lands on the card calls `closeSelf()` and
 * goes nowhere, and only a tap that lands on the button opens the editor. So "the editor came up, for
 * the task I swiped" is what proves the panel was actually reachable and not merely uncovered.
 *
 * One task, one profile, seeded into the app's own storage the way [TaskEditorInstrumentedTest] seeds
 * its series: this is a test of the row, and the project still has no Hilt test harness.
 */
@RunWith(AndroidJUnit4::class)
class TaskCardSwipeInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    /** The profile this class seeded, remembered so `@After` can take it and everything under it away. */
    private var seededUserId: Long = 0

    @Before
    fun signInAProfileWithOneTask() {
        val database = openDatabase()
        runBlocking {
            // The address is this test's own: users.email is unique, and a second test signing in under
            // the same one would take the first test's profile — and its rows — away with it.
            seededUserId = database.userDao().insertUser(
                User(email = "swipe-${System.nanoTime()}@example.test", name = "Ирина Волкова")
            )
            database.userDao().setCurrentUser(database.userDao().getUserById(seededUserId)!!)
            // Exactly one row: `withId(R.id.taskCard)` has to name the card being dragged, and a second
            // task would make the matcher ambiguous rather than merely longer.
            database.taskDao().insertTask(
                Task(
                    uuid = UUID.randomUUID().toString(),
                    title = TITLE,
                    description = "",
                    userId = seededUserId
                )
            )
        }
        database.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the row is drawn before anything is dragged on it",
            waitUntil("the task row") { card() != null }
        )
        // One card, or the swipe has no honest target. This fails here rather than letting Espresso report
        // `matches 8 views`, because the eight are another class's: `SessionRepository` is a Hilt singleton
        // and one connected invocation keeps one app process across classes, so a class that seeded a
        // profile earlier is still who the app thinks it is — the report class seeds eight tasks. The
        // project's connected gate is therefore per class (plan §14.3), and this says so in words.
        assertEquals(
            "this class seeds exactly one task, so the list belongs to it",
            1,
            rowCount()
        )
    }

    /** How many rows the list is holding right now. */
    private fun rowCount(): Int {
        var count = -1
        scenario.onActivity { activity ->
            count = activity.findViewById<RecyclerView>(R.id.recyclerView).childCount
        }
        return count
    }

    @After
    fun closeTheAppAndTakeTheSeedAway() {
        scenario.close()
        val database = openDatabase()
        runBlocking {
            database.userDao().getUserById(seededUserId)?.let { database.userDao().deleteUser(it) }
        }
        database.close()
    }

    /** Where Stage 2 left the row: the panel entirely behind the card, at rest. */
    @Test
    fun theClosedRowKeepsThePanelBehindTheCardOnTheDevice() {
        val card = card()!!
        val panel = panel()!!
        assertTrue(
            "no strip of panel past the card's right edge: ${panel.right} vs ${card.right}",
            panel.right <= card.right
        )
        assertTrue("no band above it either: ${panel.top} vs ${card.top}", panel.top >= card.top)
        assertTrue("no band below it either: ${panel.bottom} vs ${card.bottom}", panel.bottom <= card.bottom)
        assertEquals("and the card has not moved", 0f, card.translationX, TOLERANCE_PX)
        shot("20-row-closed")
    }

    /**
     * The gesture a reader makes, and the button it is made to reach.
     *
     * Asserted in the order the person experiences it: the card slides by the panel's own width; the
     * edit button's centre falls clear of the card, so the touch has something to land on; pressing it
     * opens that task's editor; and coming back leaves the row shut, which is what stops the next
     * reader's first tap from hitting a panel nobody remembers opening.
     */
    @Test
    fun swipingTheCardRevealsThePanelAndEditOpensTheTask() {
        val panelWidth = panel()!!.width
        assertTrue("the panel has two buttons to reveal, so it has width: $panelWidth px", panelWidth > 0)

        onView(withId(R.id.taskCard)).perform(REVEAL_SWIPE)

        assertTrue(
            "the card landed on the panel's left edge, not short of it or past it",
            waitUntil("the reveal") { abs(card()!!.translationX + panelWidth) <= TOLERANCE_PX }
        )
        assertEquals("the card slid by exactly the panel's width", -panelWidth.toFloat(),
            card()!!.translationX, TOLERANCE_PX)
        val cardOnScreen = IntArray(2).also { card()!!.getLocationOnScreen(it) }
        val cardRight = cardOnScreen[0] + card()!!.width
        val editOnScreen = IntArray(2).also { editButton()!!.getLocationOnScreen(it) }
        val editCentre = editOnScreen[0] + editButton()!!.width / 2
        assertTrue(
            "the edit button's centre ($editCentre px) is clear of the card's right edge ($cardRight px)",
            editCentre > cardRight
        )
        shot("21-row-swiped")

        onView(withId(R.id.editTaskButton)).perform(click())
        assertTrue(
            "the editor opened — the press reached the button, not the card behind it",
            waitUntil("the editor") { view(R.id.task_title) != null && isInFront() }
        )
        assertEquals("and it is the task the row was swiped on", TITLE, text(R.id.task_title))
        shot("22-editor-from-panel")

        leaveTheEditor()
        assertTrue("back at the list", waitUntil("the list again") { card() != null })
        assertEquals("and the row came home closed", 0f, card()!!.translationX, TOLERANCE_PX)
        shot("23-row-closed-again")
    }

    // -- the path ---------------------------------------------------------------------------------

    private fun leaveTheEditor() {
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            host.navController.popBackStack()
        }
    }

    // -- the helpers ------------------------------------------------------------------------------

    private fun card(): View? = view(R.id.taskCard)

    private fun panel(): View? = view(R.id.actionPanel)

    private fun editButton(): View? = view(R.id.editTaskButton)

    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(*AppMigrations.ALL)
        .build()

    private fun isInFront(): Boolean = scenario.state == Lifecycle.State.RESUMED

    private fun view(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun text(id: Int): String = (view(id) as? TextView)?.text?.toString().orEmpty()

    /**
     * Waiting for the screen, carrying away what it threw if it never answered.
     *
     * A swipe settles over 180ms and the list binds on a flow, so every assertion here is a condition
     * that has to become true; the message says which one did not, and what the row looked like when the
     * deadline passed. The condition's *value* is what ends the wait — an exception on its own is not
     * success, and treating it as such is what let a first draft of this file pass a wait for a card that
     * had not moved at all.
     */
    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val outcome = runCatching(condition)
            if (outcome.getOrNull() == true) return true
            lastFailure = outcome.exceptionOrNull()
            Thread.sleep(50)
        }
        throw AssertionError(
            "$description did not happen within $TIMEOUT_MILLIS ms. state=${scenario.state} " +
                "translationX=${runCatching { card()!!.translationX }.getOrNull()} " +
                "panelWidth=${runCatching { panel()!!.width }.getOrNull()} " +
                "title='${runCatching { text(R.id.task_title) }.getOrNull()}'",
            lastFailure
        )
    }

    /**
     * A screen of this app, photographed where adb can reach it afterwards.
     *
     * A refused screenshot fails the run rather than passing quietly: the photographs are cited as the
     * device evidence for the reveal, and a green suite that wrote none would make that claim untrue.
     */
    private fun shot(name: String) {
        runCatching { instrumentation.waitForIdleSync() }
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
        if (bitmap == null) {
            throw AssertionError("the platform refused the screenshot '$name' this run cites as evidence")
        }
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(), "shots"
        ).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        println("screenshot written $name.png")
    }

    private companion object {
        const val TITLE = "Свайп: проверка панели"
        const val TIMEOUT_MILLIS = 60_000L

        /** The slide is animated over 180ms, so the resting position is read to within a pixel. */
        const val TOLERANCE_PX = 1f

        /**
         * A fast, finger-sized drag from the card's centre leftward — the swipe itself, not a substitute.
         *
         * It starts at the centre rather than at the right edge for two measured reasons: the drag has to
         * travel past half the panel to be a reveal at all, and a drag that begins in the screen's edge
         * band is taken by the system Back gesture (`navigation_mode=2` on both AVDs — the activity left
         * RESUMED from a start at 95% of the card's width, and stayed running from every interior one).
         */
        val REVEAL_SWIPE = GeneralSwipeAction(
            Swipe.FAST, GeneralLocation.CENTER, GeneralLocation.CENTER_LEFT, Press.FINGER
        )
    }
}
