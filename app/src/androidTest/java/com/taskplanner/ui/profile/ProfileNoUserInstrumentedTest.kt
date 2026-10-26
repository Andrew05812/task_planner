package com.taskplanner.ui.profile

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The account screen with no account on it.
 *
 * This is the first screen a new reader meets and the screen a reader returns to after deleting their
 * profile, and until now nothing in the project had ever looked at it: `ProfileFragment` shows or hides the
 * group from a state the JVM tests can produce but no test had rendered. So this class asks three things —
 * that the empty state is what is shown, that both ways in are still there and still pressable, and that
 * "Create profile" really opens the dialog it promises.
 *
 * It clears only the profiles this project's own tests seed, which all live at `@example.test`, and it
 * refuses rather than reaching further: if a device holds a profile that is not a test fixture, the empty
 * state cannot be shown here and the test says so. User data is not this screen's to tidy.
 */
@RunWith(AndroidJUnit4::class)
class ProfileNoUserInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheAccountScreenWithNobodySignedIn() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the app starts on Tasks", waitUntil("the tasks screen") {
            findView(R.id.recyclerView) != null
        })
        onView(withId(R.id.navigation_profile)).perform(click())
        assertTrue("the account opens", waitUntil("the account screen") {
            findView(R.id.noUserGroup) != null
        })
    }

    @After
    fun closeTheApp() {
        scenario.close()
    }

    @Test
    fun theEmptyAccountOffersTheTwoWaysIn() {
        onView(withId(R.id.noUserGroup)).check(matches(isDisplayed()))
        assertEquals("the rows are not shown at nobody", View.GONE, findView(R.id.contentGroup)!!.visibility)
        assertEquals("and neither is the spinner", View.GONE, findView(R.id.progressBar)!!.visibility)
        onView(withId(R.id.createProfileButton)).check(matches(isDisplayed()))
        onView(withId(R.id.switchProfileButtonNoUser)).check(matches(isDisplayed()))
        val floor = (48 * context.resources.displayMetrics.density).toInt()
        listOf(R.id.createProfileButton, R.id.switchProfileButtonNoUser).forEach { id ->
            val view = findView(id)!!
            assertTrue("${context.resources.getResourceEntryName(id)} is ${view.height}px tall " +
                "against a ${floor}px floor", view.height >= floor)
        }
        shot("s5-profile-nouser")
    }

    @Test
    fun creatingAProfileFromTheEmptyStateOpensTheDialog() {
        onView(withId(R.id.createProfileButton)).perform(click())
        onView(withText(R.string.dialog_create_profile_title)).inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(withId(R.id.nameInput)).inRoot(isDialog()).check(matches(withText("")))
        assertEquals("nothing was written while the dialog was open", View.GONE,
            findView(R.id.contentGroup)!!.visibility)
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun findView(id: Int): View? {
        var found: View? = null
        scenario.onActivity { activity -> found = activity.findViewById<View>(id) }
        return found
    }

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(50)
        }
        System.err.println("no-user profile wait gave up on: $description")
        return false
    }

    /** Photographed only once the group has really drawn itself; see `ProfileScreenInstrumentedTest.shot`. */
    private fun shot(name: String) {
        assertTrue("the empty state is on screen before it is photographed",
            waitUntil("the empty group drawn") {
                val group = findView(R.id.noUserGroup)
                group != null && group.width > 0 && group.isShown
            })
        instrumentation.waitForIdleSync()
        val group = findView(R.id.noUserGroup)!!
        val where = IntArray(2).also { group.getLocationInWindow(it) }
        val bounds = android.graphics.Rect(where[0], where[1], where[0] + group.width, where[1] + group.height)
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
            "the empty account never drew into a photographed frame in $CAPTURE_ATTEMPTS attempts"
        )
        File(context.filesDir, "shots").apply { mkdirs() }
            .let { File(it, "$name.png") }
            .outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
        bitmap.recycle()
        println("screenshot written $name.png at ${context.resources.configuration.screenWidthDp}dp")
    }

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

    companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val CAPTURE_ATTEMPTS = 6
        const val SETTLE_MILLIS = 250L
        const val MIN_CONTRAST_SPREAD = 120

        /** Every profile this project's tests seed lives at this address and nowhere else. */
        const val TEST_DOMAIN = "@example.test"

        @JvmStatic
        @BeforeClass
        fun signNobodyOut() {
            val database = Room.databaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                AppDatabase::class.java, AppDatabase.NAME
            ).addMigrations(*AppMigrations.ALL).build()
            runBlocking {
                val fixtures = database.userDao().getAllUsersOnce()
                    .filter { it.email.endsWith(TEST_DOMAIN) }
                val others = database.userDao().getAllUsersOnce() - fixtures.toSet()
                assertTrue("this screen can only be photographed empty while every profile on the device is a " +
                    "test fixture; ${others.size} real profile(s) are here, and clearing them is not this " +
                    "test's to do", others.isEmpty())
                fixtures.forEach { database.userDao().deleteUser(it) }
            }
            database.close()
        }
    }
}
