package com.taskplanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.testing.SeededProfile
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five screens Stage 8a restyled, opened the way a reader opens them, and photographed there.
 *
 * Stage 8a changes no query and no calculation, so the risk it carries is not a wrong number — it is a screen
 * that silently stops working because a style, a container or a visibility rule moved. A JVM layout test can
 * see a row's geometry and read its tokens; only a running app can say that the Archive is still reachable
 * from the Tasks kebab, that the Settings screen still carries its four commands and its version line, that
 * the profile switcher still marks exactly one profile current, and that none of it crashed on the way.
 *
 * Every photograph is taken after the screen is *confirmed*: the destination id the navigation controller
 * reports, plus an anchor view only that screen draws. A screenshot of the wrong screen is the easiest false
 * evidence this app produces — Stage 6 reviewed a frame measured on the wrong subject, and Stage 7 found a
 * `uiautomator dump` taken after the window had already closed. The frame is also refused if the `-e tag` the
 * matrix script passed does not match what the device reports at the shutter, so a night frame cannot be
 * filed as a light one.
 *
 * The Settings method is also this stage's answer to a user-visible question: it asserts what the screen
 * shows about Google Sign-In on a build with no Firebase configuration, which is why the button is not there.
 * That is diagnosis, not a fix — Stage 13 owns the sign-in flow.
 */
@RunWith(AndroidJUnit4::class)
class Stage8aScreensInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the app opens on Tasks, and every route in this class starts from there",
            waitUntil("the tasks header") { findView(R.id.tasksHeader) != null }
        )
    }

    @After
    fun closeTheWindow() {
        scenario.close()
    }

    // ---------------------------------------------------------------- Categories

    /**
     * Categories through the bar's third tab: the screen names itself and lists the seeded row.
     *
     * The title is asserted as text rather than as "a view exists", because Stage 8a moved it into a header
     * include — an include that lost its `android:text` would still leave every id in place.
     */
    @Test
    fun categoriesListsTheSeededRowAndNamesItself() {
        pressTab(R.id.navigation_categories)
        assertEquals(
            "the bar's third tab went somewhere other than Categories",
            R.id.navigation_categories,
            destination()
        )
        // Compared against the resource rather than against an English literal: the claim is that the screen
        // is named, and the app is about to be named in more than one language (Stage 14).
        assertEquals(
            "the screen's own title is not the destination's name",
            context.getString(R.string.title_categories),
            textOf(R.id.categoriesTitleText)
        )
        onView(withId(R.id.recyclerViewCategories))
            .check(matches(hasDescendant(withText(CATEGORY))))

        // Whether a name fits the box it was given is not a question the JVM tests can ask: Robolectric's
        // LEGACY graphics return one pixel per character from `measureText` and ignore `textSize`, and the
        // NATIVE mode that would measure it has no binary for this machine — the wall
        // `TaskCardWidthBudgetTest` wrote down in Stage 4. So it is asked here, of the renderer that ships.
        // At the reference width the row's name is the only thing that tells two categories apart, and this
        // stage's own photograph showed it cut to "Quarterly reconciliation of supp…"; the narrowest width is
        // a different bargain, asserted in `theDensestScreenStillFitsTheNarrowestWidth`, where the name is
        // allowed to give way as long as it says so.
        val widthDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        if (widthDp >= 360f) {
            val row = RecyclerViewReference(R.id.recyclerViewCategories).attachedRows().firstOrNull {
                it.findViewById<TextView>(R.id.categoryName)?.text?.toString() == LONG_CATEGORY
            } ?: throw AssertionError(
                "the long-named category is not on the screen to measure at ${widthDp}dp"
            )
            val name = row.findViewById<TextView>(R.id.categoryName)
            val layout = name.layout
                ?: throw AssertionError("the category name was never laid out, so nothing proves it is readable")
            assertEquals(
                "at ${widthDp}dp the row still cuts the one field that tells two categories apart: asked " +
                    "'$LONG_CATEGORY', shown '${layout.text}'",
                0, layout.getEllipsisCount(layout.lineCount - 1)
            )
        }
        photograph("s8a-categories")
    }

    // ---------------------------------------------------------------- Archive

    /**
     * The Archive, reached from the Tasks kebab, with both seeded rows on it.
     *
     * Two rows are seeded on purpose: one completed and one archived-but-unfinished, because the difference
     * between them is the thing Stage 8a added — a word and a strike that read without relying on colour. The
     * words are asked of the visible label on each row, keyed by its title, and not of a `contentDescription`:
     * the first version of this test passed on text only a screen reader was ever given, and the review
     * caught it. The counts are asked of the attached rows, because `onView(withId(…))` with two matching rows
     * is an ambiguity failure of the test, not a defect in the screen.
     */
    @Test
    fun archiveOpensFromTheKebabAndItsRowsSayWhatTheyAre() {
        openFromTasksMenu(R.string.action_archive, R.id.navigation_archive, R.id.archiveTitleText)
        assertEquals(
            "the archive's title is not the word the menu promised",
            context.getString(R.string.title_archive),
            textOf(R.id.archiveTitleText)
        )
        onView(withId(R.id.archiveList)).check(matches(isDisplayed()))
        onView(withId(R.id.archiveList)).check(matches(hasDescendant(withText(FINISHED))))

        val rows = RecyclerViewReference(R.id.archiveList).attachedRows()
        assertEquals("both seeded archive rows are on the screen", 2, rows.size)
        val states = rows.map { row ->
            val title = row.findViewById<TextView>(R.id.archiveTitle)
                ?: throw AssertionError("an archive row lost its title")
            val mark = row.findViewById<TextView>(R.id.archiveStateMark)
                ?: throw AssertionError("an archive row lost its state mark")
            title.text.toString() to mark.text.toString()
        }.sortedBy { it.first }
        assertEquals(
            "each row names its own state in a word a reader can see: $states",
            listOf(
                FINISHED to context.getString(R.string.status_done),
                UNFINISHED to context.getString(R.string.status_todo)
            ),
            states
        )
        val struck = rows.map { row ->
            val title = row.findViewById<TextView>(R.id.archiveTitle)
            title.text.toString() to
                (title.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG != 0)
        }
        assertEquals(
            "only the finished row is struck through: $struck",
            listOf(FINISHED to true, UNFINISHED to false),
            struck.sortedBy { it.first }
        )
        // The row's two commands are the 48dp targets the restyled layout asks for; a restyle that shrank a
        // touch target to its glyph would still list the row correctly, so the size is asked here too.
        rows.forEach { row ->
            listOf(R.id.restoreButton, R.id.deleteForeverButton).forEach { id ->
                val button = row.findViewById<View>(id)
                    ?: throw AssertionError("row ${row.hashCode()} lost its command $id")
                assertTrue(
                    "the command $id is ${button.width}x${button.height}px; a finger needs ${dp(48f)} in both",
                    button.width >= dp(48f) && button.height >= dp(48f)
                )
            }
        }
        // The name is the row's identifier, and the state word added by this stage is its newest neighbour:
        // with the mark constrained into the title's path, 320dp left the name ~86dp and this stage's own
        // frame read "Stage 8a / unfinished a…". Whether a name fits its box is asked here rather than on the
        // JVM for the reason written on `categoriesListsTheSeededRowAndNamesItself`.
        rows.forEach { row ->
            val title = row.findViewById<TextView>(R.id.archiveTitle) ?: return@forEach
            val layout = title.layout
                ?: throw AssertionError(
                    "an archive row's title was never laid out, so nothing proves it is readable"
                )
            assertEquals(
                "the archived task's own name is cut short: asked '${title.text}', shown '${layout.text}'",
                0, layout.getEllipsisCount(layout.lineCount - 1)
            )
        }
        photograph("s8a-archive")
    }

    // ---------------------------------------------------------------- Work history

    @Test
    fun workHistoryOpensFromTheKebabAndStillComputesItsHeader() {
        openFromTasksMenu(R.string.action_work_history, R.id.navigation_work_history, R.id.historyTitleText)
        assertEquals(
            "the work history's title is not the word the menu promised",
            context.getString(R.string.history_title),
            textOf(R.id.historyTitleText)
        )
        val summary = textOf(R.id.historySummary)
        assertTrue(
            "the summary line went empty, so the screen no longer says what was measured: '$summary'",
            summary.isNotBlank()
        )
        val range = textOf(R.id.historyRange)
        assertTrue(
            "the period line went empty, so the reader cannot tell what window the figures cover: '$range'",
            range.isNotBlank()
        )
        photograph("s8a-work-history")
    }

    // ---------------------------------------------------------------- Settings

    /**
     * Settings keeps its five named controls, and says why Sign In is not one of them.
     *
     * `SettingsFragment.render()` shows `signInButton` only when `state.cloudConfigured` holds, and
     * `CloudConfig.isConfigured` is false on every build this repository produces: the Gradle properties
     * `firebase.projectId` and `firebase.appId` default to the literal `NOT_CONFIGURED`, so
     * `BuildConfig.FIREBASE_PROJECT_ID` is that placeholder and no `google-services.json` exists to override
     * it. The button being gone is therefore the designed local-only mode, not a rendering accident — and the
     * screen states it, which is what the last two assertions ask. The stage's rule is to diagnose this, not
     * to decorate over it: no fake sign-in control is added, and `syncNowButton` is asserted *disabled*,
     * because that is what the same state produces.
     */
    @Test
    fun settingsKeepsItsControlsAndExplainsTheAbsentSignIn() {
        goToProfile()
        onView(withId(R.id.openSettingsButton)).perform(scrollTo(), click())
        assertEquals("Settings reached", R.id.navigation_settings, destination())

        // Every control Stage 8a was told to preserve is still on the window under the same id. `findViewById`
        // answers null and this helper throws when an id has gone, which is the claim; the visibility that
        // follows is the state machine's, read off the screen rather than off the layout.
        for (id in listOf(R.id.exportButton, R.id.importButton, R.id.syncNowButton)) {
            assertTrue("command $id is on the window but not shown", findViewById(id).isVisible)
        }
        onView(withId(R.id.exportButton)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.importButton)).perform(scrollTo()).check(matches(isDisplayed()))

        // The version line is the one control that is never hidden; a restyle that dropped it off the layout
        // would leave the reader with no way to tell which build they are looking at. Asked for the version
        // *number* the package manager reports rather than the English word "Version", because the word is a
        // translation target (Stage 14), the number is not, and this is the same string the settings screen
        // reads its own label from.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val version = textOf(R.id.versionText)
        assertTrue(
            "the version line does not carry the installed version name ($installed): '$version'",
            installed != null && version.contains(installed)
        )

        assertEquals(
            "Sign In must not appear on a build with no cloud configuration — and it must be *gone*, not " +
                "merely invisible, so a reader with a screen reader is never offered it",
            View.GONE,
            findViewById(R.id.signInButton).visibility
        )
        assertEquals(
            "and the matching sign-out is gone too — nobody is signed in",
            View.GONE,
            findViewById(R.id.signOutButton).visibility
        )
        onView(withId(R.id.cloudNoticeText)).perform(scrollTo()).check(matches(isDisplayed()))
        val notice = textOf(R.id.cloudNoticeText)
        // Compared with the resource rather than with the English word "Firebase": the class's own rule is
        // that a rendered string is checked against what the app writes it from, because every one of these
        // sentences is a translation target (Stage 14).
        assertEquals(
            "the notice that stands in for the sign-in affordance is not the sentence the app writes",
            context.getString(R.string.app_locked_message),
            notice
        )
        assertTrue(
            "sync is offered as a command the app cannot honour, so it has to read as unavailable: " +
                "syncNow.isEnabled=${findViewById(R.id.syncNowButton).isEnabled}",
            !findViewById(R.id.syncNowButton).isEnabled
        )
        photograph("s8a-settings")
    }

    // ---------------------------------------------------------------- Profile selection

    /**
     * The switcher keeps every profile and marks exactly one current, in words.
     *
     * Stage 8a's rule for this screen was that the active profile must read beyond colour, so the mark is
     * asked for as a visible view carrying the word "Current" — and the count is asked of the rows, because
     * two profiles marked current is the failure this stage could plausibly introduce by binding the flag to
     * the wrong view.
     */
    @Test
    fun profileSelectionMarksOneProfileInTheWordsTheScreenUses() {
        goToProfile()
        onView(withId(R.id.switchProfileButton)).perform(scrollTo(), click())
        assertEquals("the switcher reached", R.id.navigation_profile_selection, destination())
        assertEquals(
            "the switcher's title",
            context.getString(R.string.title_select_profile),
            textOf(R.id.profilesTitleText)
        )

        val rows = RecyclerViewReference(R.id.recyclerViewProfiles).attachedRows()
        assertTrue(
            "the switcher lists ${rows.size} rows and both seeded profiles are expected: " +
                rows.map { it.findViewById<TextView>(R.id.profileName)?.text?.toString() },
            rows.size >= 2
        )
        val names = rows.map { row ->
            val name = row.findViewById<TextView>(R.id.profileName)
                ?: throw AssertionError("a profile row lost its name view")
            val mark = row.findViewById<TextView>(R.id.currentProfileMark)
                ?: throw AssertionError("a profile row lost its current mark view")
            name.text.toString() to mark.isVisible
        }
        val marked = names.filter { it.second }
        assertEquals(
            "the switcher marked ${marked.size} profiles as current; exactly one is the app's profile: $names",
            1,
            marked.size
        )
        assertEquals(
            "the marked profile is '${marked.first().first}', not the one this class signed the app into",
            PROFILE,
            marked.first().first
        )
        assertEquals(
            "the mark is a word a reader can hear, not only a colour a reader can see",
            context.getString(R.string.state_current),
            rows.first { it.findViewById<View>(R.id.currentProfileMark).isVisible }
                .findViewById<TextView>(R.id.currentProfileMark).text.toString()
        )
        // The address is what tells two profiles apart, and Stage 8a's first cut pinned it to one ellipsized
        // line — a frame where the only thing a reader could compare was "stage8a-other-9429194…". Asked of
        // the laid-out text, not of the attribute: the last line has to have dropped no characters.
        val other = rows.first {
            it.findViewById<TextView>(R.id.profileName).text.toString() == OTHER
        }
        val email = other.findViewById<TextView>(R.id.profileEmail)
        val emailLayout = email.layout
            ?: throw AssertionError("the profile address was never laid out, so nothing proves it is readable")
        assertEquals(
            "the address line is cut short on the row that distinguishes two profiles: '${email.text}' laid " +
                "out as '${emailLayout.text}'",
            0,
            emailLayout.getEllipsisCount(emailLayout.lineCount - 1)
        )
        photograph("s8a-profile-selection")
    }

    // ---------------------------------------------------------------- the narrow screen

    /**
     * The densest of the five screens at the narrowest width the app supports, with a name long enough to
     * need the room.
     *
     * `CategoriesLayoutTest` budgets this on the JVM with `tools:text` placeholders; this is the same claim
     * against real text metrics on a real row, which is the only kind of width claim Stage 4 learned to trust.
     * The first cut of this method seeded "Stage 8a work" — thirteen characters that fit at every width, so
     * the assertion could not have failed and the frame proved nothing about the budget. The long category is
     * seeded for this method's own row. The name is the part allowed to give room; the count has to stay on
     * the card.
     */
    @Test
    fun theDensestScreenStillFitsTheNarrowestWidth() {
        pressTab(R.id.navigation_categories)
        onView(withId(R.id.recyclerViewCategories))
            .check(matches(hasDescendant(withText(LONG_CATEGORY))))

        val rows = RecyclerViewReference(R.id.recyclerViewCategories).attachedRows()
        val row = rows.firstOrNull {
            it.findViewById<TextView>(R.id.categoryName)?.text?.toString() == LONG_CATEGORY
        } ?: throw AssertionError(
            "the long-named category is not on the screen to measure; rows are " +
                rows.map { it.findViewById<TextView>(R.id.categoryName)?.text?.toString() }
        )
        val name = row.findViewById<TextView>(R.id.categoryName)
        val count = row.findViewById<TextView>(R.id.taskCount)
        assertTrue(
            "the count ends at ${count.right} of a card that ends at ${row.right}, so it sits outside what " +
                "the card draws",
            count.right <= row.right
        )
        assertTrue(
            "the name reaches ${name.right} and overlaps the count, which starts at ${count.left}",
            name.right <= count.left
        )
        assertTrue(
            "the card reaches ${row.right} on a ${context.resources.displayMetrics.widthPixels}px screen, so " +
                "it is drawn wider than the window",
            row.right <= context.resources.displayMetrics.widthPixels
        )
        val layout = name.layout ?: throw AssertionError("the category name was never laid out")
        val shown = layout.text.toString()
        assertTrue(
            "a name that does not fit is shortened in front of the reader, not left to overlap or vanish: " +
                "asked for '$LONG_CATEGORY', shown '$shown'",
            shown == LONG_CATEGORY || (shown.isNotEmpty() && layout.getEllipsisCount(layout.lineCount - 1) > 0)
        )
        photograph("s8a-categories-narrow")
    }

    // ---------------------------------------------------------------- the helpers

    private inner class RecyclerViewReference(val id: Int) {
        /**
         * The rows currently attached to a list.
         *
         * `childCount` is the attached-window count, not the adapter's item count — enough here because every
         * screen in this class seeds two rows at most, and taking the adapter's count instead would let a
         * screen that binds nothing but reports the right number pass.
         */
        fun attachedRows(): List<View> {
            val found = AtomicReference<List<View>>(emptyList())
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(id)
                    ?: throw AssertionError("no list with id $id on the window")
                found.set((0 until list.childCount).map { list.getChildAt(it) })
            }
            return found.get()
        }
    }

    private fun openFromTasksMenu(titleRes: Int, expectedDestination: Int, anchor: Int) {
        onView(withId(R.id.headerMenuButton)).perform(click())
        assertTrue(
            "the kebab's popup did not offer '${context.getString(titleRes)}'",
            runCatching {
                onView(withText(context.getString(titleRes))).inRoot(isPlatformPopup())
                    .check(matches(isDisplayed()))
            }.isSuccess
        )
        onView(withText(context.getString(titleRes))).inRoot(isPlatformPopup()).perform(click())
        assertTrue(
            "'${context.getString(titleRes)}' did not bring up its anchor view",
            waitUntil("the anchor") { findView(anchor) != null }
        )
        assertEquals(
            "the kebab item '${context.getString(titleRes)}' left the reader somewhere else",
            expectedDestination,
            destination()
        )
    }

    private fun pressTab(id: Int) {
        onView(withId(id)).perform(click())
        assertTrue(
            "the tab $id did not draw its screen",
            waitUntil("the tab") { destination() == id }
        )
    }

    /**
     * The account screen, by its destination and by a control only it draws.
     *
     * `profileName` is not used here as the anchor even though the screen shows it: the same id lives on every
     * row of the switcher two presses away (`item_profile.xml`), and a window-scoped `findViewById` answers
     * whichever match comes first. `openSettingsButton` belongs to one screen — and it is below the fold at
     * this width, so it is scrolled to before it is claimed to be displayed. The first version of this helper
     * asked `isDisplayed()` of the un-scrolled row and failed on a screen that was perfectly healthy: the
     * matcher means "in the window right now", which is a different claim from "on the screen".
     */
    private fun goToProfile() {
        pressTab(R.id.navigation_profile)
        assertEquals("the bar's fifth tab is the account screen", R.id.navigation_profile, destination())
        onView(withId(R.id.openSettingsButton)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    private fun destination(): Int {
        var id = 0
        runCatching {
            scenario.onActivity { activity ->
                id = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment)
                    .navController.currentDestination?.id ?: 0
            }
        }
        return id
    }

    private fun findView(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun findViewById(id: Int): View =
        findView(id) ?: throw AssertionError("no view with id $id on the running window")

    private fun textOf(id: Int): String =
        (findViewById(id) as TextView).text.toString()

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        // Named in the system trace as well as in the failure message: when a wait spends its whole budget,
        // the trace is where a reader finds out what the run was waiting for. Stage 7's helper, carried over.
        android.os.Trace.beginSection("stage 8a waiting for $description")
        try {
            val deadline = System.currentTimeMillis() + WAIT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (runCatching(condition).getOrDefault(false)) return true
                Thread.sleep(50)
            }
            return false
        } finally {
            android.os.Trace.endSection()
        }
    }

    /**
     * Photograph the window the reader is looking at, and refuse to file it under a configuration the device
     * does not report.
     *
     * The tag guard is Stage 7's, carried forward: the matrix script sets density, night mode and font scale
     * and then passes `-e tag 393-dark-10`. A frame whose device disagrees with its name is worse than no
     * frame, because the next reader cannot tell a mislabelled gallery from a real regression.
     */
    private fun photograph(name: String) {
        val tag = requireConfigurationMatchesTag()
        val bitmap = photographed(name)
        // A non-empty PNG is not a picture of anything: `takeScreenshot` answers with a valid file of a black
        // display, and Stage 7's frame checks exist because that happened. The spread is asked of a grid over
        // the whole window, so a screen of one flat colour is refused the same way a blank one is.
        val spread = luminanceSpread(bitmap)
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
            "shots/$tag"
        )
        directory.mkdirs()
        val file = File(directory, "$name.png")
        try {
            assertTrue(
                "the frame '$name' is not a drawn screen: over a grid across the whole window the brightest " +
                    "sample is $spread levels above the darkest, and a screen with a title, a list and a " +
                    "bottom bar spans far more than that",
                spread >= 40
            )
            file.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        } finally {
            bitmap.recycle()
        }
        assertTrue("$name was written empty", file.length() > 0)
        println("stage 8a frame written ${file.absolutePath} (${file.length()} bytes)")
    }

    /** The brightest minus the darkest luminance on a 20x40 grid over the frame. */
    private fun luminanceSpread(bitmap: Bitmap): Int {
        var brightest = 0
        var darkest = 255
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (
                    0.2126 * (pixel shr 16 and 0xFF) +
                        0.7152 * (pixel shr 8 and 0xFF) +
                        0.0722 * (pixel and 0xFF)
                    ).roundToInt()
                if (luminance > brightest) brightest = luminance
                if (luminance < darkest) darkest = luminance
                x += bitmap.width / 20
            }
            y += bitmap.height / 40
        }
        return brightest - darkest
    }

    private fun photographed(what: String): Bitmap {
        var refusal: Throwable? = null
        repeat(SHUTTER_ATTEMPTS) {
            val outcome = runCatching { instrumentation.uiAutomation.takeScreenshot() }
            outcome.getOrNull()?.let { return it }
            refusal = outcome.exceptionOrNull()
            Thread.sleep(500)
        }
        throw AssertionError(
            "CAPTURE FAILED, not a screen defect: the platform refused to photograph $what on all " +
                "$SHUTTER_ATTEMPTS attempts (${refusal?.javaClass?.simpleName}: ${refusal?.message}). " +
                "Nothing this class photographs has been measured on this run."
        )
    }

    private fun requireConfigurationMatchesTag(): String {
        val tag = InstrumentationRegistry.getArguments().getString("tag", "393-light-10")
        val parts = tag.split("-")
        assertEquals("the tag names a width, a theme and a font scale: got '$tag'", 3, parts.size)
        val metrics = context.resources.displayMetrics
        val widthDp = (metrics.widthPixels / metrics.density).roundToInt()
        val night = (
            context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            ) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val font = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, metrics) /
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, metrics)
        assertTrue(
            "'$tag' claims ${parts[0]}dp of width; the device reports ${widthDp}dp " +
                "(${metrics.widthPixels}px at ${metrics.densityDpi}dpi)",
            abs(widthDp - parts[0].toInt()) <= 3
        )
        assertEquals("'$tag' claims the ${parts[1]} theme; the device reports night=$night", parts[1] == "dark", night)
        assertEquals(
            "'$tag' claims a font scale of ${parts[2]} tenths; the device reports $font",
            parts[2].toFloat() / 10f,
            font,
            0.02f
        )
        return tag
    }

    companion object {
        private const val WAIT_MILLIS = 20_000L
        private const val SHUTTER_ATTEMPTS = 5

        private const val PROFILE = "Stage 8a"
        private const val OTHER = "Stage 8a other"
        private const val CATEGORY = "Stage 8a work"
        private const val LONG_CATEGORY = "Quarterly reconciliation of supplier invoices"
        private const val FINISHED = "Stage 8a finished task"
        private const val UNFINISHED = "Stage 8a unfinished archive"

        /** The same blue the category colour presets offer, so a seeded row looks like a row a person made. */
        private const val SEED_AVATAR_COLOR = 0xFF1E88E5.toInt()

        private lateinit var signedIn: SeededProfile.SignedIn
        private var otherProfile = 0L

        /**
         * Two profiles, two categories and two archived tasks — the smallest data that lets each of the five
         * screens show a row.
         *
         * The second profile is what makes the switcher's claim testable: one profile cannot show whether the
         * current one is marked. The two archive rows differ in `status` only, which is the difference the
         * stage's word and strike are about. The second category is long on purpose — the narrow-width method
         * measures how the row gives room, and a thirteen-character name fits anywhere and proves nothing.
         *
         * `avatarColor` is set because the app's own creation path sets it (`ProfileViewModel` calls a
         * private generator the fixture cannot reach), and `User.avatarColor` defaults to 0 — a transparent
         * circle with white initials in it. Without this the stage's own evidence photograph showed two
         * rows whose avatars had vanished, which reads as a broken row rather than as a seeded one.
         */
        @BeforeClass
        @JvmStatic
        fun seedWhatTheScreensNeed() {
            val database = SeededProfile.open(instrumentationContext())
            try {
                SeededProfile.sweepLeftovers(database)
                signedIn = SeededProfile.signIn(database, "stage8a-${System.nanoTime()}@example.test", PROFILE)
                val user = signedIn.seededUserId
                kotlinx.coroutines.runBlocking {
                    database.userDao().getUserById(user)?.let { current ->
                        database.userDao().updateUser(current.copy(avatarColor = SEED_AVATAR_COLOR))
                    }
                    otherProfile = database.userDao().insertUser(
                        User(
                            email = "stage8a-other-${System.nanoTime()}@example.test", name = OTHER,
                            avatarColor = SEED_AVATAR_COLOR
                        )
                    )
                    database.categoryDao().insertCategory(
                        Category(
                            name = CATEGORY, color = 0xFF1B5E20.toInt(), icon = "category",
                            userId = user
                        )
                    )
                    database.categoryDao().insertCategory(
                        Category(
                            name = LONG_CATEGORY, color = SEED_AVATAR_COLOR, icon = "folder",
                            userId = user
                        )
                    )
                    database.taskDao().insertTask(
                        Task(
                            title = FINISHED, description = "", userId = user,
                            status = TaskStatus.COMPLETED, archived = true,
                            archivedAt = System.currentTimeMillis()
                        )
                    )
                    database.taskDao().insertTask(
                        Task(
                            title = UNFINISHED, description = "", userId = user,
                            status = TaskStatus.TODO, archived = true,
                            archivedAt = System.currentTimeMillis()
                        )
                    )
                }
            } finally {
                database.close()
            }
        }

        /**
         * Both seeded profiles go, not just the signed-in one.
         *
         * `SeededProfile.takeAway` deletes the profile it was handed and the rows under it; the second
         * profile belongs to no session, so nothing else would ever remove it, and the next class to count
         * profiles would inherit a stranger's row. The categories and tasks need no separate call —
         * `takeAway` clears the three tables that carry a `user_id` without a cascade.
         */
        @AfterClass
        @JvmStatic
        fun takeTheSeededRowsAway() {
            if (!::signedIn.isInitialized) return
            val database = SeededProfile.open(instrumentationContext())
            try {
                kotlinx.coroutines.runBlocking {
                    if (otherProfile != 0L) {
                        database.userDao().getUserById(otherProfile)
                            ?.let { database.userDao().deleteUser(it) }
                    }
                }
                SeededProfile.takeAway(database, signedIn)
            } finally {
                database.close()
            }
        }

        private fun instrumentationContext(): Context =
            InstrumentationRegistry.getInstrumentation().targetContext
    }
}
