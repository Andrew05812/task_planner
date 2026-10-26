package com.taskplanner.ui.profile

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.User
import com.taskplanner.ui.MainActivity
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Profile screen on a handset: eight rows, and where each of them actually goes.
 *
 * The screen is the account's front door, and until Stage 5 it had never been tested at all. A JVM test can
 * say what the layout contains; only a device can answer the two questions that matter here — whether a row
 * that looks like a destination reaches it, and whether the destructive row asks before it acts. So this
 * file signs in, opens the account, and presses every row, reading the answer from the navigation
 * controller's own current destination rather than from whatever happens to be on screen.
 *
 * Two of the eight are not destinations: `editProfileButton` and `deleteProfileButton` open dialogs, and they
 * are asserted as dialogs, which is what they are. The delete confirmation is checked twice over — that it
 * names what the reader loses, and that pressing *Cancel* on it leaves the profile in the database exactly
 * where it was, because a confirmation that deletes on cancel is the worst kind of lie a screen can tell.
 *
 * The profile is seeded once for the class, straight into the app's own storage. One app process serves one
 * profile — `SessionRepository` is a Hilt singleton and reads the current user once — so a class that seeds
 * in `@Before` would hand its second test the first test's rows. The seeded account has an ordinary name; the
 * worst legal values, a name long enough for two lines and an address long enough to need an ellipsis, are
 * written by the one test that asks about them and taken back again when it has photographed them.
 */
@RunWith(AndroidJUnit4::class)
class ProfileScreenInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheAccountScreen() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the app starts on Tasks", waitUntil("the tasks screen") {
            findView(R.id.recyclerView) != null
        })
        pressUntil(R.id.navigation_profile, "the account screen") { findView(R.id.profileName) != null }
    }

    /**
     * Press a bottom-bar tab until the screen it promises answers.
     *
     * The first run of this class failed in `@Before` on one test out of six: the tap was delivered and the
     * account never appeared, on a handset that was still bringing the previous scenario's activity down.
     * Espresso waits for a view to be *there*, not for the navigation it triggers to have finished, and a
     * re-selection of the tab that is already current is a no-op — so the press is repeated, and the state
     * the screen was in is carried into the failure message rather than left out of it.
     */
    private fun pressUntil(tab: Int, description: String, settled: () -> Boolean) {
        val seen = StringBuilder()
        for (attempt in 1..TAB_ATTEMPTS) {
            onView(withId(tab)).perform(click())
            if (waitUntil("$description (press $attempt)", SHORT_MILLIS, settled)) return
            seen.append("[press ").append(attempt).append(": ").append(screenState()).append("] ")
        }
        throw AssertionError("$description did not open after $TAB_ATTEMPTS presses. $seen")
    }

    /** What the screen is holding, for the message of a wait that had to give up. */
    private fun screenState(): String = buildString {
        append("destination=").append(currentDestinationName())
        append(" profile=").append(findView(R.id.profileName) != null)
        append(" rows=").append(findView(R.id.contentGroup) != null)
        append(" noUser=").append(findView(R.id.noUserGroup) != null)
        append(" list=").append(findView(R.id.recyclerView) != null)
    }

    private fun currentDestinationName(): String {
        val id = currentDestination()
        return if (id == null) "none" else
            runCatching { context.resources.getResourceName(id) }.getOrNull() ?: "id:$id"
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    @Test
    fun theAccountNamesTheReaderInFull() {
        onView(withId(R.id.contentGroup)).check(matches(isDisplayed()))
        assertEquals("the whole name is the reader's name", NAME, text(R.id.profileName))
        assertEquals("the whole address is the reader's address", EMAIL, text(R.id.profileEmail))
        val avatar = findView(R.id.profileAvatar) as com.taskplanner.ui.components.AvatarView
        assertEquals("the circle says the two letters the name starts with",
            NAME.take(2).uppercase(), avatar.initialsText)
        shot("s5-profile-light")
    }

    /**
     * The worst legal account: a name long enough for two lines and an address long enough to need an
     * ellipsis. The screen may not push the rows off the fold with it, and the name may not be cut.
     *
     * The name's height is compared with its own `Layout`, not with `lineHeight × lineCount`: the two differ
     * by a pixel on a real device (the layout measures ascent-to-descent per line and rounds once, the
     * multiplication rounds twice), and an assertion that fails on a rounding difference is an assertion
     * about arithmetic rather than about whether the reader can read the name.
     */
    @Test
    fun aLongNameAndALongEmailStillFit() {
        write(LONG_NAME, LONG_EMAIL)
        try {
            reopen()
            assertEquals("the name is the long one, whole", LONG_NAME, text(R.id.profileName))
            val name = findView(R.id.profileName) as android.widget.TextView
            assertTrue("a name this long takes ${name.lineCount} lines of ${name.height}px",
                name.lineCount >= 2)
            assertTrue("and the whole two lines are inside the view, not cut off by it: layout is " +
                "${name.layout.height}px", name.height >= name.layout.height)
            val email = findView(R.id.profileEmail) as android.widget.TextView
            val needed = email.paint.measureText(email.text.toString()).toInt()
            assertTrue("the address is wider than the box it is drawn in ($needed px against " +
                "${email.width}px), so the ellipsis is doing real work rather than sitting there unused",
                needed > email.width)
            // `ellipsize="middle"`, because the domain is the half a reader checks. Android collapses the
            // middle with one ellipsis mark per character it drops, so the shown text is not shorter than
            // the original — what proves the cut is that it differs, keeps both ends, and carries the mark.
            val shown = email.layout?.text?.toString().orEmpty()
            assertTrue("the address is shortened: \"$shown\"", shown != email.text.toString())
            assertTrue("the middle is what went, so the mark is inside the text and not at its end",
                shown.contains("…") && !shown.endsWith("…"))
            assertTrue("and both ends a reader checks are still there: \"$shown\"",
                shown.startsWith("christopher.") && shown.endsWith("example.test"))
            shot("s5-profile-long")
            assertTrue("the first row still starts below the header, not under it",
                (findView(R.id.editProfileButton)?.top ?: 0) > name.bottom)
        } finally {
            // The account is this class's shared fixture: a test that leaves it renamed would fail the next
            // one for the wrong reason. Restoring is cleanup, not a swallowed assertion — the failure above
            // still travels out of this block unchanged, and a restore that itself fails is reported as its
            // own failure rather than hidden, because the next run then starts from the wrong account.
            write(NAME, EMAIL)
            reopen()
        }
    }

    /**
     * Every destination is a row: a glyph, a title, a chevron, and a height a finger can hit.
     *
     * This is the assertion the eight buttons cannot pass — they carry no chevron and no title view, and three
     * of the eight carried no icon at all. The height is measured on the device, in pixels, because 48dp is a
     * promise about a hand and not about XML, and the glyph is read as a *drawable* because the layout
     * declares the ImageView empty: an `ImageView` in the tree proves where a glyph goes, not that one is
     * there. The eight are also required to differ — eight identical squares would satisfy "has a glyph" and
     * tell a reader nothing.
     */
    @Test
    fun everyDestinationIsARowRatherThanAButton() {
        val floor = (48 * context.resources.displayMetrics.density).toInt()
        val drawn = mutableListOf<android.graphics.drawable.Drawable>()
        ALL_ROWS.forEach { id ->
            val name = context.resources.getResourceEntryName(id)
            // Scrolled before checked: eight rows and a header do not fit above the fold on a 393dp panel,
            // and `isDisplayed` is a statement about what the reader can see, not about what exists.
            onView(withId(id)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(id)).check(matches(hasDescendant(withId(R.id.rowChevron))))
            onView(withId(id)).check(matches(hasDescendant(withId(R.id.rowTitle))))
            onView(withId(id)).check(matches(hasDescendant(withId(R.id.rowIconContainer))))
            val row = findView(id)!!
            assertTrue("$name is ${row.height}px tall, under a ${floor}px floor", row.height >= floor)
            assertTrue("$name carries its own name for a screen reader",
                row.contentDescription?.isNotBlank() == true)
            // The title the row shows and the description the row announces are the same words; if they ever
            // differ, the screen reader is reading something the eye is not.
            val title = (row.findViewById<View>(R.id.rowTitle) as? android.widget.TextView)?.text?.toString()
            assertEquals("$name says one thing in text and another aloud", row.contentDescription, title)
            // The layout declares the ImageView empty on purpose, so "there is an ImageView" is a statement
            // about XML. This is the statement about the screen: a drawable is in it.
            val glyph = row.findViewById<android.widget.ImageView>(R.id.rowIcon)
            assertNotNull("$name shows a glyph rather than an empty plate", glyph?.drawable)
            drawn += glyph!!.drawable
        }
        assertEquals("eight rows, eight different marks — a screen of identical squares carries no more " +
            "information than a screen of none", ALL_ROWS.size,
            drawn.map { it.constantState }.distinct().size)
    }

    /** The six rows that leave the screen, each checked by the destination id the controller holds. */
    @Test
    fun theSixNavigatingRowsReachTheirOwnScreens() {
        DESTINATIONS.forEach { (id, destination) ->
            val name = context.resources.getResourceEntryName(id)
            onView(withId(id)).perform(scrollTo(), click())
            assertTrue("$name never reached $destination",
                waitUntil("$name opens its screen") { currentDestination() == destination })
            pressBack()
            assertTrue("back from $name did not return to the account",
                waitUntil("$name comes back") { currentDestination() == R.id.navigation_profile })
        }
    }

    @Test
    fun editingTheAccountIsADialogAndItKeepsWhatItFound() {
        onView(withId(R.id.editProfileButton)).perform(scrollTo(), click())
        onView(withText(R.string.dialog_edit_profile_title)).inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(withId(R.id.nameInput)).inRoot(isDialog())
            .check(matches(withText(NAME)))
        pressBack()
        assertEquals("cancelling the editor leaves the reader signed in", NAME, text(R.id.profileName))
        assertEquals(NAME, currentUserId()?.let { userName(it) })
    }

    /**
     * The confirmation names the consequences, and *Cancel* means cancel.
     *
     * The message this file was written against asked "Are you sure you want to delete profile %1$s?" — the
     * reader's name, and nothing about the work that goes with it. Spec §4.9 asks a destructive confirmation
     * to say what is lost, so the test reads the words on the dialog and requires them to name the loss,
     * then presses the button that must not delete and checks the database, not the screen.
     */
    @Test
    fun deletingAsksWhatItWillCostAndCancellingCostsNothing() {
        onView(withId(R.id.deleteProfileButton)).perform(scrollTo(), click())
        onView(withText(R.string.dialog_delete_profile_title)).inRoot(isDialog())
            .check(matches(isDisplayed()))
        // Read the words off the dialog's own message view rather than from the resource: a later stage
        // cannot satisfy this by editing the string and the assertion together.
        val message = dialogText(android.R.id.message)
        assertTrue("the confirmation names the reader it is asking about: $message",
            message.contains(NAME.substringBefore(' ')))
        listOf("task", "categor", "goal", "habit", "work").forEach { stem ->
            assertTrue("the delete confirmation says what is lost, but \"$message\" never mentions $stem",
                message.contains(stem, ignoreCase = true))
        }
        onView(withText(R.string.action_cancel)).inRoot(isDialog()).perform(click())
        assertTrue("the profile is still the current user after cancelling",
            waitUntil("the account stays") { text(R.id.profileName) == NAME })
        assertNotNull("and the database still holds it", currentUserId())
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Change the signed-in account the way the app's own editor would, then let the screen notice. */
    private fun write(name: String, email: String) = runBlocking {
        val database = open()
        database.userDao().getUserById(seededId)?.let {
            database.userDao().updateUser(it.copy(name = name, email = email))
        }
        database.close()
    }

    /**
     * Round-trip the activity so `onResume` reloads the profile.
     *
     * Not a process restart: the session lives in a Hilt singleton, and this is the same path a reader takes
     * when they come back to the tab — which is the path the screen has to get right.
     */
    private fun reopen() {
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        assertTrue("the account came back", waitUntil("the account reloads") {
            findView(R.id.profileName) != null && text(R.id.profileName).isNotBlank()
        })
        instrumentation.waitForIdleSync()
    }

    /**
     * A screen of this app, photographed where adb can reach it afterwards.
     *
     * The frame is not taken on trust: the platform will answer with a screenshot of a window that has not
     * drawn its content yet, and a photograph of an empty screen proves nothing about the rows on it. So the
     * capture waits for the first row to be laid out, then requires the account group's own rectangle to
     * span at least [MIN_CONTRAST_SPREAD] points of luminance — contrast rather than darkness, because the
     * dark theme is dark everywhere — and retakes the frame until it does.
     */
    private fun shot(name: String) {
        assertTrue("the rows are laid out before the screen is photographed",
            waitUntil("the first row drawn") {
                val row = findView(R.id.editProfileButton)
                row != null && row.width > 0 && row.isShown
            })
        instrumentation.waitForIdleSync()
        val group = findView(R.id.contentGroup)!!
        val where = IntArray(2).also { group.getLocationInWindow(it) }
        val bounds = android.graphics.Rect(
            where[0], where[1], where[0] + group.width, where[1] + group.height
        )
        var attempt = 0
        var frame: android.graphics.Bitmap? = null
        while (attempt < CAPTURE_ATTEMPTS) {
            attempt++
            val taken = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull()
                ?: throw AssertionError("the platform refused the screenshot '$name' this run cites")
            if (hasContrastInside(taken, bounds)) { frame = taken; break }
            taken.recycle()
            instrumentation.waitForIdleSync()
            Thread.sleep(SETTLE_MILLIS)
        }
        val bitmap = frame ?: throw AssertionError(
            "the account never drew into a photographed frame in $CAPTURE_ATTEMPTS attempts"
        )
        val directory = File(context.filesDir, "shots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { stream ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()
        println("screenshot written $name.png at ${context.resources.configuration.screenWidthDp}dp " +
            "fontScale=${context.resources.configuration.fontScale} after $attempt frame(s)")
    }

    /** Any real content in the rectangle: text, an icon or a rule is enough to call the screen drawn. */
    private fun hasContrastInside(frame: android.graphics.Bitmap, bounds: android.graphics.Rect): Boolean {
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

    private fun dialogText(id: Int): String {
        var captured = ""
        onView(withId(id)).inRoot(isDialog()).check { view, _ ->
            captured = (view as android.widget.TextView).text.toString()
        }
        return captured
    }

    private fun currentDestination(): Int? {
        var id: Int? = null
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                as? androidx.navigation.fragment.NavHostFragment
            id = host?.navController?.currentDestination?.id
        }
        return id
    }

    private fun findView(id: Int): View? {
        var found: View? = null
        scenario.onActivity { activity -> found = activity.findViewById<View>(id) }
        return found
    }

    private fun text(id: Int): String = (findView(id) as? android.widget.TextView)?.text?.toString().orEmpty()

    private fun waitUntil(description: String, millis: Long = TIMEOUT_MILLIS,
                          condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        System.err.println("profile wait gave up on: $description")
        return false
    }

    private fun currentUserId(): Long? = runBlocking {
        open().userDao().getCurrentUser()?.id
    }

    private fun userName(id: Long): String? = runBlocking {
        open().userDao().getUserById(id)?.name
    }

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(*AppMigrations.ALL).build()

    companion object {
        const val NAME = "Анна Ковалёва"
        const val EMAIL = "anna@example.test"
        const val LONG_NAME = "Christopher Maximilian von und zu Thalberg"
        const val LONG_EMAIL = "christopher.maximilian.von.und.zu.thalberg@universitaet.example.test"
        const val TIMEOUT_MILLIS = 15_000L
        const val SHORT_MILLIS = 5_000L
        const val TAB_ATTEMPTS = 3
        const val CAPTURE_ATTEMPTS = 6
        const val SETTLE_MILLIS = 250L

        /** Below this spread the rectangle is one flat colour, which is an empty screen in either theme. */
        const val MIN_CONTRAST_SPREAD = 120

        /** The six rows that leave the screen, and the destination each one is supposed to reach. */
        val DESTINATIONS = listOf(
            R.id.switchProfileButton to R.id.navigation_profile_selection,
            R.id.openSettingsButton to R.id.navigation_settings,
            R.id.openGoalsButton to R.id.navigation_goals,
            R.id.openHabitsButton to R.id.navigation_habits,
            R.id.openInsightsButton to R.id.navigation_insights,
            R.id.openReportsButton to R.id.navigation_reports
        )

        /** All eight, in the order the screen lists them: the two dialogs first, then the six destinations. */
        val ALL_ROWS = listOf(R.id.editProfileButton, R.id.deleteProfileButton) +
            DESTINATIONS.map { it.first }

        private lateinit var seeded: AppDatabase
        private var seededId: Long = 0L
        private var displaced: User? = null

        /** Every profile this project's tests seed lives at this address and nowhere else. */
        const val TEST_DOMAIN = "@example.test"

        @JvmStatic
        @BeforeClass
        fun signInTheProfileOnceForTheClass() {
            seeded = Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppDatabase::class.java, AppDatabase.NAME
            ).addMigrations(*AppMigrations.ALL).build()
            runBlocking {
                // Signing in is not a harmless write: `setCurrentUser` clears the flag on every other row,
                // so this class displaces whoever was signed in. That is fine between fixtures and not fine
                // with a reader's profile, so the class refuses rather than displacing one, and remembers the
                // fixture it moved aside to put it back at the end.
                val all = seeded.userDao().getAllUsersOnce()
                val foreign = all.filterNot { it.email.endsWith(TEST_DOMAIN) }
                assertTrue("this class has to make its own profile current, and would sign " +
                    "${foreign.size} real profile(s) out; the device is not a test device",
                    foreign.isEmpty())
                displaced = seeded.userDao().getCurrentUser()
                // `insertUser` is ABORT-on-conflict and the e-mail is unique, so a run that died before its
                // cleanup would leave this address behind and the next run would never start. Take the old
                // one away first — this profile's own address, and nothing else in the database.
                seeded.userDao().getUserByEmail(EMAIL)?.let { seeded.userDao().deleteUser(it) }
                seededId = seeded.userDao().insertUser(User(email = EMAIL, name = NAME))
                seeded.userDao().setCurrentUser(seeded.userDao().getUserById(seededId)!!)
            }
        }

        @JvmStatic
        @AfterClass
        fun takeTheProfileAway() {
            runBlocking {
                seeded.userDao().getUserById(seededId)?.let { seeded.userDao().deleteUser(it) }
                displaced?.let { seeded.userDao().setCurrentUser(it) }
            }
            seeded.close()
        }
    }
}
