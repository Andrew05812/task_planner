package com.taskplanner.ui.keep

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.testing.AppWindow
import com.taskplanner.testing.SeededProfile
import com.taskplanner.testing.TabTap
import com.taskplanner.ui.MainActivity
import com.taskplanner.ui.settings.AppLocale
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The Google Keep import, proven on the device: Settings → paste → preview → confirm → rows in the database.
 *
 * **What only a device can say.** Everything else in this stage runs against a Room database the test builds
 * itself, on a JVM, with views inflated but never drawn. This class runs against the app's own storage through the
 * app's own Hilt graph, the real navigation graph, the widget a paste actually lands in, and the renderer that
 * ships. Four claims survive nowhere else:
 *
 *  * **The route exists in the app a reader opens.** Settings is reached through the account tab, and the entry
 *    has to be on it and speak the device's language.
 *  * **Indentation survives the widget.** The tree this stage exists to keep is decided by leading spaces. A field
 *    that trimmed them would promote a reader's task to a topic before the parser ever saw the line, so what is
 *    asserted is not what the box holds but what the *stored rows* say — `parent_uuid` read out of the database
 *    the app itself wrote.
 *  * **The imported subtree renders nested in the task list.** `TaskCardIndentTest` proves the arithmetic on a
 *    JVM where a title has no glyphs and no width; here a real `paddingStart` is measured against another, which
 *    is what the reader actually sees.
 *  * **Confirming twice writes once.**
 *
 * **The fixture is invented** — titles, emoji and a date in the shape of a Keep export, none of it anybody's real
 * note (§4). The 382-element corpus is the JVM's ground: `KeepImportTransactionTest` imports the depersonalized
 * file whole and counts every row it lands. What is proved here is the path a person takes to get there.
 *
 * **The order is stated, not assumed.** The methods are named `a`…`e` and pinned with [FixMethodOrder]: they
 * share one seeded profile and each measures a delta against what the previous one left, so a re-ordered run
 * would compare the wrong baseline. A fresh profile per method is not available — the session lives in a Hilt
 * singleton for the life of the process, which is what the Stage 14 ledger records, and re-seating it would mean
 * restarting the app between every assertion.
 *
 * **The sixth method is a different run.** `zFramesForThisConfiguration` is not part of that chain: it seeds its
 * own profile in the same class, applies a language, and photographs the route in the width, theme and font
 * scale the matrix script put the device in. It sorts last so a whole-class behaviour run never pays for twenty-
 * four shutters, and the script invokes it by name, one configuration per process.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KeepImportInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheAppOnTasks() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the app did not open on Tasks", waitUntil("the tasks header") { view(R.id.tasksHeader) != null })
        val focused = AppWindow.awaitFocus(scenario)
        assertTrue(AppWindow.focusMessage("the Tasks screen", AppWindow.focused(scenario)), focused)
    }

    /**
     * Close the window, and hand the device's language back if this run had taken it away.
     *
     * The reset is not tidiness: the language an `AppCompatDelegate` chooses is not in the app's own storage, so
     * the runner's package clearing does not take it back, and the class that runs after this one photographs
     * Russian screens while filing them under `en`. Stage 14 recorded that exact defect for the class that left
     * the locale behind (`Ошибка №5`), and the frames below are labelled by the language the run asked for, so
     * leaving it applied would mislabel the evidence this class exists to produce.
     */
    @After
    fun closeTheWindowAndHandTheLanguageBack() {
        if (argument("reset", "true") == "true" && !AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        scenario.close()
    }

    // ---------------------------------------------------------------- a. the way in

    @Test
    fun aTheSettingsPanelOffersTheEntryAndTheScreenOpensOnTheField() {
        val baseline = counts()

        openSettings()

        // The row is below the fold, so it is scrolled to before it is asked whether it is on the screen.
        // `isDisplayed` means "inside the visible rectangle", and a Settings panel with more rows than a phone
        // has no obligation to show the last one at once — a reader scrolls, and a test that claims to be one does
        // too. Measured: this check failed on the first device run with the button `VISIBLE` at y=315 of a window
        // that was not showing that far down.
        Espresso.onView(ViewMatchers.withId(R.id.keepImportButton)).perform(ViewActions.scrollTo())
        Espresso.onView(ViewMatchers.withId(R.id.keepImportButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.keepImportButton)).check(
            ViewAssertions.matches(ViewMatchers.withText(appText(R.string.settings_keep_import_entry)))
        )
        frame("a-settings-entry")

        Espresso.onView(ViewMatchers.withId(R.id.keepImportButton)).perform(ViewActions.click())

        assertTrue("the paste field never opened", waitUntil("the field") { view(R.id.pasteInput) != null })
        Espresso.onView(ViewMatchers.withId(R.id.pasteInput))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        // The two actions are scrolled to before they are called displayed, for the same reason the Settings row
        // is: the phase-one panel is a title, a hint, a ten-line field, and the three notes §11 asks for before
        // anybody presses Parse — so the buttons are below the fold at 393dp, and further below at 320dp with the
        // font at 1.3. `isDisplayed` is a claim about the visible rectangle, and a reader scrolls to reach them.
        Espresso.onView(ViewMatchers.withId(R.id.parseButton)).perform(ViewActions.scrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.clearButton)).perform(ViewActions.scrollTo())
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        assertEquals("the confirm bar waits for a preview", View.GONE, view(R.id.confirmBar)?.visibility)
        frame("b-paste-field")

        assertEquals("opening the screen writes nothing", baseline, counts())
    }

    // ---------------------------------------------------------------- b. the preview writes nothing

    @Test
    fun bPreviewingShowsTheCountsAndTheTreeAndWritesNothing() {
        val baseline = counts()
        openKeepImport()

        paste(FIXTURE_A)
        Espresso.onView(ViewMatchers.withId(R.id.parseButton)).perform(ViewActions.scrollTo(), ViewActions.click())

        assertTrue("the preview never drew", waitUntil("the preview") { previewRows() == PREVIEW_A })
        Espresso.onView(ViewMatchers.withId(R.id.statsHeader))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        assertEquals("the statistics card plus two topics and six tasks", PREVIEW_A, previewRows())
        assertEquals("a paste that holds lines shows no empty-tree notice", View.GONE,
            view(R.id.emptyPreviewText)?.visibility)
        Espresso.onView(ViewMatchers.withId(R.id.confirmButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        frame("c-preview-tree")

        // Close the first topic: its branch goes, the topic itself stays, and the list says how much it hid.
        val topic = toggleFor("🟥Сегодня")
        assertNotNull("the topic drew no control of its own", topic)
        click(topic)
        assertTrue("the closed topic left its branch on screen",
            waitUntil("the collapse") { previewRows() == PREVIEW_A - A_CHILDREN })
        assertEquals("the topic is still named, with what it hides counted",
            appText(R.string.keep_import_hidden_rows, A_CHILDREN),
            hiddenLabelFor("🟥Сегодня"))
        click(toggleFor("🟥Сегодня"))
        assertTrue("and opening it again brought the branch back",
            waitUntil("the expand") { previewRows() == PREVIEW_A })

        assertEquals("a preview writes nothing at all", baseline, counts())
    }

    // ---------------------------------------------------------------- c. the confirm

    @Test
    fun cConfirmingWritesTheTopicsTheirHierarchyTheirChecksAndNoInventedTimes() {
        val baseline = counts()
        openKeepImport()
        paste(FIXTURE_A)
        Espresso.onView(ViewMatchers.withId(R.id.parseButton)).perform(ViewActions.scrollTo(), ViewActions.click())
        assertTrue("the preview never drew", waitUntil("the preview") { previewRows() == PREVIEW_A })

        pressInConfirmBar(R.id.confirmButton)

        assertTrue("the import never answered", waitUntil("the result") {
            viewText(R.id.confirmMessage).isNotEmpty()
        })
        assertEquals("two topics and six tasks arrived", Counts(A_TOPICS, A_TASKS), subtract(counts(), baseline))
        frame("d-import-result")

        val stored = rowsForUser(userId)
        val byTitle = stored.associateBy { it.title }
        val groups = groupsForUser(userId)

        assertTrue("the topic's own `[X]` is kept on the topic",
            groups.first { it.name == "🟥Сегодня" }.completed)
        assertFalse("and the open topic stayed open", groups.first { it.name == "🟨Завтра" }.completed)
        assertEquals("the topic count is what the preview promised", A_TOPICS, groups.size)
        // The status is compared to the enum, never to the string the column stores: `TaskStatus.COMPLETED.name`
        // is machine-facing, and writing it as a literal is what `EnglishLiteralAssertionTest` exists to catch.
        assertEquals("a completed topic did not complete what hangs under it", TaskStatus.TODO,
            byTitle.getValue("open child").status)
        assertEquals(TaskStatus.COMPLETED, byTitle.getValue("done child").status)
        assertEquals("a lowercase box is a check too", TaskStatus.COMPLETED,
            byTitle.getValue("lowercase done").status)
        assertEquals("the grandchild names the task it was indented under",
            byTitle.getValue("done child").uuid, byTitle.getValue("grandchild").parentUuid)
        assertEquals("and belongs to the same topic either way",
            byTitle.getValue("open child").groupId, byTitle.getValue("grandchild").groupId)
        assertNull("a topic's own child has no task above it", byTitle.getValue("open child").parentUuid)
        assertEquals("two copies of one title are two rows", 2, stored.count { it.title == "repeated item" })
        assertEquals("with two identities", 2,
            stored.filter { it.title == "repeated item" }.map { it.uuid }.distinct().size)
        assertEquals("nothing carries a completion time the export never stated", 0,
            stored.count { it.completedAt != null })
        assertEquals("every task knows its topic", 0, stored.count { it.groupId == null })
        assertEquals("no separator row reached the database as a task", 0, stored.count { it.title.isBlank() })
        assertEquals("and the source order survived: the first child of the first topic is `open child`",
            "open child", stored.minByOrNull { it.order }?.title)
    }

    // ---------------------------------------------------------------- d. the second paste

    /**
     * The same text, pasted again in a *later* visit to the screen — which is the case §13 is written for, and the
     * one a reader is most likely to produce by accident: next month they copy the notebook out of Keep again and
     * put the same paste through the same route.
     *
     * Method `c` already imported this exact text, so this method does not need to import it a second time to
     * make the point — and that is what makes the claim strong rather than tautological. Identity here is not a
     * session marker or a stored log of what this screen did: it is derived from the profile, the parent row,
     * the title and the occurrence, so a parse in a new visit reaches the same uuids and the plan comes back with
     * nothing to write.
     */
    @Test
    fun dPastingTheSameTextAgainInASecondVisitWritesNothing() {
        val before = counts()
        openKeepImport()

        paste(FIXTURE_A)
        Espresso.onView(ViewMatchers.withId(R.id.parseButton)).perform(ViewActions.scrollTo(), ViewActions.click())

        assertTrue("the repeat paste never answered", waitUntil("the already-imported answer") {
            viewText(R.id.confirmMessage) == appText(R.string.keep_import_result_already)
        })
        assertEquals("every line is still drawn, labelled as what it is", PREVIEW_A, previewRows())
        assertNull("and the confirm is not armed for a paste with nothing left to write",
            viewIfVisible(R.id.confirmButton))
        frame("e-already-imported")

        assertEquals("no second copy of the notebook arrived", before, counts())
        assertEquals("the six tasks of that text are still six", A_TASKS,
            rowsForUser(userId).count { it.title in A_TITLES })
    }

    // ---------------------------------------------------------------- e. the subtree in the task list

    @Test
    fun eTheImportedSubtreeIsDrawnOneStepDeeperPerLevelInTheTaskList() {
        openKeepImport()
        paste(FIXTURE_B)
        Espresso.onView(ViewMatchers.withId(R.id.parseButton)).perform(ViewActions.scrollTo(), ViewActions.click())
        assertTrue("the nesting fixture never previewed", waitUntil("the preview") { previewRows() == PREVIEW_B })
        pressInConfirmBar(R.id.confirmButton)
        assertTrue("the nesting fixture never imported", waitUntil("the nesting import") {
            rowsForUser(userId).any { it.title == "nest level three" }
        })

        val reading = TabTap.press(R.id.navigation_tasks, "navigation_tasks")
        assertTrue("the tasks tab did not answer: ${reading.summarize()} ${reading.verdict()}", reading.moved)
        assertTrue("the task list never drew", waitUntil("the list") { view(R.id.recyclerView) != null })

        val step = context.resources.getDimensionPixelSize(R.dimen.task_depth_indent)
        // The list is scrolled past the whole chain and every card it passes is measured, because a RecyclerView
        // only holds the rows it can show: the parent and its grandchild are not on screen at the same moment at
        // every size, and a reader scrolls one to reach the other.
        val paddings = paddingsFor("nest level one", "nest level two", "nest level three")
        assertEquals("each level steps in by exactly one dimen from the row above it", step,
            paddings.getValue("nest level two") - paddings.getValue("nest level one"))
        assertEquals(step, paddings.getValue("nest level three") - paddings.getValue("nest level two"))
        frame("f-tasks-nested")

        assertEquals("the deepest row carries the box the source gave it", TaskStatus.COMPLETED,
            rowsForUser(userId).first { it.title == "nest level three" }.status)
        assertEquals("its parent is the level above it, not the topic",
            rowsForUser(userId).first { it.title == "nest level two" }.uuid,
            rowsForUser(userId).first { it.title == "nest level three" }.parentUuid)
    }

    // ---------------------------------------------------------------- the matrix

    /**
     * The whole route, photographed in the configuration the run was told to use.
     *
     * Invoked on its own by `scripts/capture-stage15-matrix.sh` — `-e class …#zFramesForThisConfiguration -e
     * language ru -e tag 320-light-13` — once per configuration, each in a process the script force-stopped and
     * restarted. It is named `z…` because [FixMethodOrder] sorts by name and a behaviour run of the whole class
     * must not spend twenty-four shutters on a device that was asked for five assertions.
     *
     * **Why the language is applied here rather than pressed.** Stage 14 proved the Settings row that switches
     * language, and that matrix chooses through the row because the row was the feature under review. Here the
     * feature under review is the import screen and the row is a means: this calls the same
     * `AppCompatDelegate.setApplicationLocales` the row ends in, with a window alive, which is the one condition
     * `CsvLocaleTest` measured for the change to actually apply. Choosing it through a separate process would
     * also cost the run its profile: the session is read once per process into a Hilt singleton, and Stage 14's
     * own teardown takes the seeded profile away, so a walk that reused that process would photograph an import
     * screen with nobody signed in.
     *
     * **Five frames, each one the screen a claim is made about.** The field before anything is typed, in this
     * language at this width; the statistics card the preview opens with; one topic closed, which is the state
     * where the row has to say what it hid instead of silently shortening; the tree scrolled to its deepest line,
     * where the indentation the whole stage exists to keep is visible as a difference between rows; and the
     * imported chain drawn in the task list, which is the frame that shows nesting a reader sees rather than
     * arithmetic a test did.
     *
     * Every one of them is taken after the *drawn* row has been read and the window has been left alone long
     * enough to present a frame. Two matrix runs taught that the hard way: an adapter count changes a layout pass
     * before the RecyclerView rebinds what it is showing, and a rebind happens a composition before the
     * compositor shows it.
     *
     * **It does nothing without a tag, and that is the same self-selection the Stage 14 matrix class uses.** The
     * methods above leave the profile holding both fixtures, and this one needs a profile that holds neither — it
     * counts its own import from zero. A whole-class run is a behaviour run, so the absence of `-e tag` is how it
     * says so; the matrix passes one, and only then does this method paste, import and photograph.
     */
    @Test
    fun zFramesForThisConfiguration() {
        val language = argument("language", "en")
        val tag = argument("tag", "")
        if (tag.isEmpty()) return
        handTheLanguageTo(language)
        assertTheWindowSpeaks(language)
        assertTheDeviceMatches(tag)

        openKeepImport()
        assertTrue("the screen opened with something already in the field", viewText(R.id.pasteInput).isEmpty())
        frame("g1-paste-field")

        paste(FIXTURE_A)
        parse()
        assertTrue("the preview never drew", waitUntil("the preview") { previewRows() == PREVIEW_A })
        // Where the preview list is scrolled decides what a frame can show: at 320dp with the font at 1.3 the
        // statistics card alone is taller than the viewport, which is how this run first found that the topic's
        // control was not on the window yet. So each frame is positioned by what it claims, in the order the
        // screen allows — the card at the top, the closed row while the topic is still drawn, then the tree after
        // a scroll down to its deepest line.
        frame("g2-preview-summary")

        scrollPreviewTo("🟥Сегодня")
        val topic = toggleFor("🟥Сегодня")
        assertNotNull("the topic drew no control of its own", topic)
        click(topic)
        assertTrue("the closed topic left its branch on screen",
            waitUntil("the collapse") { previewRows() == PREVIEW_A - A_CHILDREN })
        // The drawn words, not the adapter's count: see [toggleLabelFor] and [frame]. A frame of a closed topic has
        // to show a row that says it is closed.
        assertTrue("the closed topic never reached the drawn row", waitUntil("the drawn label") {
            hiddenLabelFor("🟥Сегодня") == appText(R.string.keep_import_hidden_rows, A_CHILDREN) &&
                toggleLabelFor("🟥Сегодня") == appText(R.string.keep_import_expand_topic)
        })
        assertEveryDrawnTitleHasRoom("g3-topic-closed")
        frame("g3-topic-closed")

        click(toggleFor("🟥Сегодня"))
        assertTrue("and opening it again brought the branch back",
            waitUntil("the expand") { previewRows() == PREVIEW_A })
        scrollPreviewTo("grandchild")
        assertTrue("the tree frame would photograph a card, not a tree",
            "grandchild" in visibleRowTitles() && "open child" in visibleRowTitles())
        assertEveryDrawnTitleHasRoom("g4-preview-tree")
        frame("g4-preview-tree")

        pressInConfirmBar(R.id.confirmButton)
        assertTrue("the first import never answered", waitUntil("the first import") {
            viewText(R.id.confirmMessage).isNotEmpty()
        })
        assertEquals("this run's own profile holds exactly the paste it confirmed",
            Counts(A_TOPICS, A_TASKS), counts())

        pressInConfirmBar(R.id.backToTextButton)
        assertTrue("the field never came back", waitUntil("the field") { view(R.id.pasteInput) != null })
        paste(FIXTURE_B)
        parse()
        assertTrue("the nesting fixture never previewed", waitUntil("the nesting preview") {
            previewRows() == PREVIEW_B
        })
        pressInConfirmBar(R.id.confirmButton)
        assertTrue("the nesting fixture never imported", waitUntil("the nesting import") {
            rowsForUser(userId).any { it.title == "nest level three" }
        })
        assertEquals("both pastes together are three topics and nine tasks", Counts(3, 9), counts())

        backToTheTabBar()
        val reading = TabTap.press(R.id.navigation_tasks, "navigation_tasks")
        assertTrue("the tasks tab did not answer: ${reading.summarize()} ${reading.verdict()}", reading.moved)
        assertTrue("the task list never drew", waitUntil("the list") { view(R.id.recyclerView) != null })
        // The measurement first, then the picture. What this configuration has to prove is that a level steps in
        // by exactly the design-system dimen on a real renderer at a real font scale — and that the frame shows
        // more than one row of the chain together. Requiring all three at once would fail the matrix on the
        // smallest screen for a fact about height rather than about nesting.
        val step = context.resources.getDimensionPixelSize(R.dimen.task_depth_indent)
        val paddings = paddingsFor("nest level one", "nest level two", "nest level three")
        assertEquals("each level steps in by one dimen from the row above it, on this screen", step,
            paddings.getValue("nest level two") - paddings.getValue("nest level one"))
        assertEquals(step, paddings.getValue("nest level three") - paddings.getValue("nest level two"))
        val drawn = bringTheChainOnScreen()
        assertTrue("fewer than two rows of the chain are drawn together at this size: $drawn", drawn.size >= 2)
        frame("g5-tasks-nested")
    }

    // ---------------------------------------------------------------- the walk

    private fun openSettings() {
        val reading = TabTap.press(R.id.navigation_profile, "navigation_profile")
        assertTrue("the profile tab did not answer: ${reading.summarize()} ${reading.verdict()}", reading.moved)
        assertTrue("the account screen never drew", waitUntil("the account screen") {
            view(R.id.openSettingsButton) != null
        })
        Espresso.onView(ViewMatchers.withId(R.id.openSettingsButton))
            .perform(ViewActions.scrollTo(), ViewActions.click())
        assertTrue("Settings never drew", waitUntil("R.id.settingsTitleText") { view(R.id.settingsTitleText) != null })
    }

    private fun openKeepImport() {
        openSettings()
        Espresso.onView(ViewMatchers.withId(R.id.keepImportButton))
            .perform(ViewActions.scrollTo(), ViewActions.click())
        assertTrue("the Keep import screen never opened", waitUntil("the field") { view(R.id.pasteInput) != null })
    }

    /** Press Parse, the way a reader does after a paste. */
    private fun parse() {
        Espresso.onView(ViewMatchers.withId(R.id.parseButton)).perform(ViewActions.scrollTo(), ViewActions.click())
    }

    /**
     * Press a button that lives in the pinned confirm bar, without scrolling to it first.
     *
     * `ViewActions.scrollTo` refuses this one, and it is right to: the action checks that the target is a
     * descendant of a scroll container, and the confirm bar is a plain LinearLayout pinned to the bottom of the
     * screen precisely so the step that writes rows is never below a fold. Measured on the first device run, where
     * the press on `confirmButton` died in the scroll action before the import was ever attempted.
     */
    private fun pressInConfirmBar(id: Int) {
        try {
            Espresso.onView(ViewMatchers.withId(id)).perform(ViewActions.click())
        } catch (refusal: Throwable) {
            // Espresso says only that the view did not meet a visibility constraint, which is not an answer about
            // a pinned bar at the bottom of a window: whether the keyboard is over it, whether the bar grew past
            // the fold, or whether it was never laid out are three different findings and the message has to say
            // which. The readings are taken from the window that refused, at the moment it refused, and the frame
            // is what a reader would have seen.
            runCatching { frame("x-refusal") }
            throw AssertionError(
                "the confirm bar's ${context.resources.getResourceEntryName(id)} refused the press. " +
                    "bar=${barReading()} button=${buttonReading(id)} ime=${imeReading()} " +
                    "| ${refusal.message?.take(400)}",
                refusal
            )
        }
    }

    private fun buttonReading(id: Int): String = onScreen { screen ->
        val button = screen?.findViewById<View>(id) ?: return@onScreen "(no button)"
        val rect = android.graphics.Rect()
        val shown = button.getGlobalVisibleRect(rect)
        "size=${button.width}x${button.height} globalVisible=$rect shown=$shown"
    }

    private fun barReading(): String = onScreen { screen ->
        val bar = screen?.findViewById<View>(R.id.confirmBar) ?: return@onScreen "(no bar on this screen)"
        val rect = android.graphics.Rect()
        val shown = bar.getGlobalVisibleRect(rect)
        "size=${bar.width}x${bar.height} globalVisible=$rect shown=$shown top=${bar.top} " +
            "rootHeight=${bar.rootView.height}"
    }

    private fun imeReading(): String = onScreen { screen ->
        val root = screen?.rootView ?: return@onScreen "(no view)"
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root) ?: return@onScreen "(no insets yet)"
        "imeVisible=" + insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) +
            " imeBottom=" + insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
    }

    /**
     * Back out until the bottom bar is on the window.
     *
     * The import screen is a destination reached from Settings, so the way to the task list is out of it. Pressing
     * back until the bar answers is what a reader does; the guard is there because Espresso refuses a back a
     * screen does not take, and a teardown that throws hides the reason the walk failed.
     */
    private fun backToTheTabBar() {
        var guard = 0
        while (view(R.id.bottom_navigation) == null && guard < BACK_PRESS_LIMIT) {
            runCatching { Espresso.pressBack() }
            Thread.sleep(400)
            guard++
        }
        assertNotNull("the bottom bar never came back after $BACK_PRESS_LIMIT presses",
            view(R.id.bottom_navigation))
    }

    /**
     * Put the paste in the way a long-press → Paste does, and prove what arrived.
     *
     * The equality is not decoration: `replaceText` writes the widget's text in one go, with no per-key
     * translation and nothing to auto-correct, which is the only path that has to be shown to keep a line's
     * leading space. A field that lost it would turn the reader's task into a topic, and the import would look
     * like it had invented a structure.
     */
    private fun paste(text: String) {
        Espresso.onView(ViewMatchers.withId(R.id.pasteInput))
            .perform(ViewActions.scrollTo(), ViewActions.replaceText(text))
        assertEquals("the field kept every leading space and every line ending", text, viewText(R.id.pasteInput))
    }

    // ---------------------------------------------------------------- reads

    /**
     * A read of the screen that is actually showing, performed on the main thread.
     *
     * `onActivity` is the whole mechanism, and it is chosen over a remembered activity reference for two reasons:
     * a RecyclerView's adapter may only be asked on the main thread, and a language change recreates the activity
     * under the scenario — a cached field would then be a read of a window that no longer exists, which is the
     * mistake the Stage 14 ledger records for the Profile tab.
     */
    private fun <T> onScreen(read: (View?) -> T): T {
        var outcome: T? = null
        scenario.onActivity { activity ->
            val host = activity.supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            val screen = host?.childFragmentManager?.primaryNavigationFragment?.view
            outcome = read(screen ?: activity.window.decorView)
        }
        @Suppress("UNCHECKED_CAST")
        return outcome as T
    }

    /**
     * A view of the screen that is actually showing, reached through the nav host's own fragment.
     *
     * The fragment's own view is asked first, because that is where the screen under the reader lives; the root
     * view is asked next, because the bottom bar and the app bar belong to the activity and a destination has to
     * be able to name them. The second half is not decoration: without it `view(R.id.bottom_navigation)` answers
     * null on a screen whose own layout holds no bar, and a helper that presses back until the bar appears would
     * empty the whole back stack looking for a view that was on the window the whole time.
     */
    private fun view(id: Int): View? = onScreen { screen ->
        screen?.findViewById<View>(id) ?: screen?.rootView?.findViewById(id)
    }

    private fun viewIfVisible(id: Int): View? = view(id)?.takeIf { it.isVisibleToTheReader() }

    private fun View.isVisibleToTheReader(): Boolean = isShown && visibility == View.VISIBLE

    private fun viewText(id: Int): String = (view(id) as? TextView)?.text?.toString() ?: ""

    /**
     * A sentence in the language the app is rendering right now, not the language the device shipped with.
     *
     * [com.taskplanner.ui.settings.AppLocale] exists because a notification channel or a worker holds the
     * application context, which is built from the device's configuration; the same asymmetry is here — the
     * instrumentation's target context is the application's, while the window under the shutter is wrapped by
     * AppCompat in whatever the reader chose. Measured: this device answers `ru` for the app while `ro.product
     * .locale` says `en-US`, and comparing a Russian row to an English resource string is a monolingual test
     * failing for the reason Stage 14 removed.
     */
    private fun appText(resId: Int, vararg args: Any): String =
        AppLocale.localized(context).getString(resId, *args)

    /**
     * The rows the preview list holds — the statistics card plus every line the plan names.
     *
     * Read from the adapter rather than from the visible children, because a list that recycles has already thrown
     * away the rows the reader scrolled past, and "nine rows" is a claim about the whole preview.
     */
    private fun previewRows(): Int? = onScreen { screen ->
        (screen?.findViewById<RecyclerView>(R.id.previewList))?.adapter?.itemCount
    }

    /** The control that opens or closes one topic's branch, found by the title it is drawn beside. */
    private fun toggleFor(title: String): View? = onScreen { screen ->
        // The type argument is written out because `findViewById` takes one, and left to itself Kotlin infers it
        // from the `return@onScreen null` further up this function's shape — which is `Nothing?`, so the erased
        // cast becomes `Void` and the read dies with a ClassCastException naming a MaterialButton. Measured on the
        // first device run.
        drawnPreviewRow(screen, title)?.findViewById<View>(R.id.rowToggle)
    }

    private fun hiddenLabelFor(title: String): String? = onScreen { screen ->
        drawnPreviewRow(screen, title)?.findViewById<TextView>(R.id.rowHidden)?.text?.toString()
    }

    /**
     * The words drawn on one topic's own control.
     *
     * This is a frame's oracle. `previewRows()` answers the moment the differ has applied the new list, which is
     * one main-thread pass before the RecyclerView has rebound the rows it is still showing — so an adapter count
     * can be true while the screen is a frame behind, and a shutter taken in that gap photographs the state the
     * click has not reached yet. The first matrix run did exactly that, and its `g3-topic-closed.png` showed an
     * open topic wearing a closed topic's name.
     */
    private fun toggleLabelFor(title: String): String? = onScreen { screen ->
        drawnPreviewRow(screen, title)?.findViewById<TextView>(R.id.rowToggle)?.text?.toString()
    }

    /** The titles of the preview rows the list is drawing right now. */
    private fun visibleRowTitles(): List<String> = onScreen { screen ->
        val list = screen?.findViewById<RecyclerView>(R.id.previewList) ?: return@onScreen emptyList()
        (0 until list.childCount).mapNotNull { index ->
            list.getChildAt(index).findViewById<TextView>(R.id.rowTitle)?.text?.toString()
        }
    }

    /** The row of the preview list that is drawing this title, or null while it is not on the window. */
    private fun drawnPreviewRow(screen: View?, title: String): View? {
        val list = screen?.findViewById<RecyclerView>(R.id.previewList) ?: return null
        return (0 until list.childCount)
            .map { list.getChildAt(it) }
            .firstOrNull { row -> row.findViewById<TextView>(R.id.rowTitle)?.text?.toString() == title }
    }

    /** Each drawn preview row's title, with how wide the device actually measured it. */
    private fun drawnTitleWidths(): List<Pair<String, Int>> = onScreen<List<Pair<String, Int>>> { screen ->
        val list = screen?.findViewById<RecyclerView>(R.id.previewList) ?: return@onScreen emptyList()
        (0 until list.childCount).mapNotNull { index ->
            list.getChildAt(index).findViewById<TextView>(R.id.rowTitle)
                ?.takeIf { it.text.isNotEmpty() }
                ?.let { Pair(it.text.toString(), it.width) }
        }
    }

    /**
     * Every preview row on this window has a title column a reader can actually read.
     *
     * The 320dp frames at font 1.3 are why this is a claim about pixels rather than about data. The row's title was
     * its only weighted part, so when the state mark, the outcome caption, the hidden count and a toggle label in
     * Russian together take the whole width, the title is measured at zero and simply is not drawn: the frame read
     * «☑ · new · 3 hidden · OPEN THIS TOPIC» with nothing in the middle where the note's name belongs, and the
     * binding still held the text the whole time. Neither a JVM assertion on `rowTitle.text` nor a Robolectric
     * measure can see that — under LEGACY graphics `measureText` answers with a character count, so the labels that
     * crowd the row out on a device measure almost nothing there. The floor is a finger's width, and the frame is
     * what shows the rest.
     */
    private fun assertEveryDrawnTitleHasRoom(what: String) {
        val floor = (TOUCHABLE_TITLE_DP * context.resources.displayMetrics.density).roundToInt()
        val cramped = drawnTitleWidths().filter { (_, width) -> width < floor }
        assertTrue(
            "the frame '$what' would photograph ${cramped.size} row(s) whose title column is narrower than " +
                "${TOUCHABLE_TITLE_DP}dp ($floor px): " +
                cramped.joinToString { (title, width) -> "«$title» at $width px" },
            cramped.isEmpty()
        )
    }

    private fun click(target: View?) {
        assertNotNull("there is nothing to press", target)
        instrumentation.runOnMainSync { target!!.performClick() }
    }

    /**
     * Bring one preview row whole onto the window, a third of the list at a time.
     *
     * The first version swiped, and a swipe travels a screenful. At 320dp with the font at 1.3 the statistics card
     * is taller than the viewport, so the list starts with no tree row drawn at all, and the single `swipeUp` that
     * reaches the tree also flies straight past its first topic: the run reported
     * `[open child, done child, grandchild, 🟨Завтра, lowercase done, repeated item, repeated item]` — the rows
     * *below* «🟥Сегодня» — after eight swipes and refused. A reader hunting one line scrolls and looks, they do not
     * fling a page and hope, so the helper now scrolls a third of the list per step and asks, after each step,
     * whether the row is drawn *inside* the list. Merely present is not enough: a row clipped at the bottom edge has
     * a control that cannot be pressed and a label no frame can be trusted to show.
     */
    private fun scrollPreviewTo(title: String) {
        val list = view(R.id.previewList) as? RecyclerView
        assertNotNull("there was no preview list to scroll", list)
        var attempt = 0
        while (attempt < PREVIEW_SCROLL_LIMIT && !rowWhollyDrawn(title)) {
            val target = list!!
            instrumentation.runOnMainSync { target.scrollBy(0, target.height / 3) }
            attempt++
        }
        assertTrue(
            "no preview row titled '$title' was drawn whole after $PREVIEW_SCROLL_LIMIT scrolls of a third; the " +
                "list shows ${visibleRowTitles()}",
            rowWhollyDrawn(title)
        )
    }

    /** Whether this title's row is drawn inside the list's own bounds — visible, not merely laid out. */
    private fun rowWhollyDrawn(title: String): Boolean = onScreen<Boolean> { screen ->
        val list = screen?.findViewById<RecyclerView>(R.id.previewList) ?: return@onScreen false
        val row = (0 until list.childCount)
            .map { list.getChildAt(it) }
            .firstOrNull { it.findViewById<TextView>(R.id.rowTitle)?.text?.toString() == title }
            ?: return@onScreen false
        row.top >= 0 && row.bottom <= list.height
    }

    /**
     * How far in each named task card is drawn, collected by scrolling the list past all of them.
     *
     * `ViewActions.scrollTo` cannot reach a card the RecyclerView has not laid out — it is not an off-screen view,
     * it does not exist yet — so the run scrolls the way a reader does. What the first shape of this helper got
     * wrong is worth keeping: it looked for one title after each swipe, and a swipe travels a screenful, which at
     * 320dp with the font at 1.3 is two cards. The list went past `nest level one` between two rests and the run
     * reported `[nest level two, nest level three]` after eight swipes. So every card the list passes is harvested.
     *
     * Harvesting at the rests of a *swipe* then failed the same way twice, in both languages, at exactly that
     * configuration: two of three cards seen, eight swipes spent. The reason is the gesture rather than the
     * reading — `swipeUp()` ends in a fling, and the run reads the list at whatever instant Espresso calls idle,
     * which can be mid-glide, so consecutive rests can still skip a row. The scroll is now the same deterministic
     * third-of-the-list step the preview uses: consecutive rests overlap by two thirds of the viewport, and a card
     * is about a quarter of one, so no card of the chain can pass without being drawn at some rest.
     */
    private fun paddingsFor(vararg titles: String): Map<String, Int> {
        val list = view(R.id.recyclerView) as? RecyclerView
        assertNotNull("there was no task list to measure", list)
        val seen = HashMap<String, Int>()
        fun harvest() = drawnCardPaddings().forEach { (title, padding) ->
            if (title in titles && title !in seen) seen[title] = padding
        }
        harvest()
        var guard = 0
        while (seen.size < titles.size && guard < LIST_SCROLL_LIMIT) {
            val target = list!!
            instrumentation.runOnMainSync { target.scrollBy(0, target.height / 3) }
            harvest()
            guard++
        }
        assertEquals("every card this run means to measure was drawn somewhere in the list", titles.size, seen.size)
        return seen
    }

    private fun drawnCardPaddings(): List<Pair<String, Int>> = onScreen { screen ->
        val list = screen?.findViewById<RecyclerView>(R.id.recyclerView) ?: return@onScreen emptyList()
        (0 until list.childCount).mapNotNull { index ->
            list.getChildAt(index).findViewById<TextView>(R.id.taskTitle)
                ?.let { Pair(it.text.toString(), it.paddingStart) }
        }
    }

    /**
     * Scroll until at least two of the chain's rows are on the window together, which is what a frame of nesting
     * has to show. Requiring all three would fail the smallest configuration for a fact about height: two cards
     * are a screenful there, so the chain is never all visible at once at any size.
     *
     * The search starts from the top of the list rather than from wherever the measurement left it, because the
     * two rows that belong together are not necessarily the two the harvest stopped on.
     */
    private fun bringTheChainOnScreen(): List<String> {
        val list = view(R.id.recyclerView) as? RecyclerView
        assertNotNull("there was no task list to scroll", list)
        val target = list!!
        instrumentation.runOnMainSync { target.scrollToPosition(0) }
        var guard = 0
        while (visibleCardTitles().count { it in NESTING_CHAIN } < 2 && guard < LIST_SCROLL_LIMIT) {
            instrumentation.runOnMainSync { target.scrollBy(0, target.height / 3) }
            guard++
        }
        return visibleCardTitles().filter { it in NESTING_CHAIN }
    }

    /**
     * The titles of the cards the list is drawing right now.
     *
     * Deliberately the *drawn* children rather than the adapter's items: a frame can only show what is on the
     * window, so this is the reading that lets the shutter refuse a picture of a flat list filed under the name
     * of a nesting claim.
     */
    private fun visibleCardTitles(): List<String> = onScreen { screen ->
        val list = screen?.findViewById<RecyclerView>(R.id.recyclerView) ?: return@onScreen emptyList()
        (0 until list.childCount).mapNotNull { index ->
            list.getChildAt(index).findViewById<TextView>(R.id.taskTitle)?.text?.toString()
        }
    }

    private fun waitUntil(what: String, stop: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(stop).getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }

    // ---------------------------------------------------------------- the database, read from outside the app

    private data class Counts(val topics: Int, val tasks: Int)

    private fun subtract(later: Counts, earlier: Counts) =
        Counts(later.topics - earlier.topics, later.tasks - earlier.tasks)

    private fun counts(): Counts = runBlocking {
        Counts(database.groupDao().getAllGroupsOnce(userId).size, database.taskDao().getAllTasksOnce(userId).size)
    }

    private class StoredTask(
        val uuid: String, val title: String, val status: TaskStatus, val groupId: String?,
        val parentUuid: String?, val completedAt: Long?, val order: Int
    )

    private class StoredGroup(val uuid: String, val name: String, val completed: Boolean)

    private fun rowsForUser(id: Long): List<StoredTask> = runBlocking {
        database.taskDao().getAllTasksOnce(id).map {
            StoredTask(it.uuid, it.title, it.status, it.groupId, it.parentUuid, it.completedAt, it.order)
        }
    }

    private fun groupsForUser(id: Long): List<StoredGroup> = runBlocking {
        database.groupDao().getAllGroupsOnce(id).map { StoredGroup(it.uuid, it.name, it.isCompleted) }
    }

    // ---------------------------------------------------------------- the frames

    private fun argument(key: String, fallback: String): String =
        InstrumentationRegistry.getArguments().getString(key, fallback)

    /**
     * Put the app in the language this configuration claims, and wait for the window that follows.
     *
     * `en` means the device's own language, not a tag: it asks for an *empty* locale list, which is what "follow
     * the system" is. Coming in this way is load-bearing rather than tidy — the class that runs before this one
     * may have left the app Russian, the language is not in the app's own storage so the runner's package
     * clearing does not take it back, and a baseline frame shot in the wrong language is not a baseline.
     *
     * The waits after the change are for the recreate a locale applies: the window has to be back and *holding
     * focus* before a press is injected into it, or the press dies with the instance it was aimed at.
     */
    private fun handTheLanguageTo(language: String) {
        val wanted = if (language == "en") "" else language
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != wanted) {
            AppCompatDelegate.setApplicationLocales(
                if (wanted.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(wanted)
            )
            assertTrue(
                "the run asked for '$wanted' and the delegate would not take it: it answers " +
                    "'${AppCompatDelegate.getApplicationLocales().toLanguageTags()}'",
                waitUntil("the language") {
                    AppCompatDelegate.getApplicationLocales().toLanguageTags() == wanted
                }
            )
        }
        assertTrue("the app never came back to the tasks header after the language change",
            waitUntil("the tasks header") { view(R.id.tasksHeader) != null })
        val focused = AppWindow.awaitFocus(scenario)
        assertTrue(
            AppWindow.focusMessage("the tasks header after a language change", AppWindow.focused(scenario)),
            focused
        )
        assertTrue(AppWindow.settleMessage("the tasks header"), AppWindow.awaitSettledActivity(scenario))
    }

    /**
     * The language the *window* resolves, checked against the directory the frames are about to be filed under.
     *
     * Stage 14 produced a `ru` directory holding an English screen, and the reason was not the app but the run:
     * the class that chose the language also handed it back, so the walk that followed photographed the device's
     * own language under a Russian name. A frame label is a claim about pixels, so the pixels are asked.
     */
    private fun assertTheWindowSpeaks(language: String) {
        var seen = ""
        scenario.onActivity { activity -> seen = activity.resources.configuration.locales[0].language }
        assertEquals(
            "the frames would be filed under '$language' while the window answers '$seen'", language, seen
        )
    }

    /**
     * Refuse to photograph a screen whose tag lies about the device.
     *
     * The tag names the width, the theme and the font scale the matrix script set before this run started. All
     * three are read back from the resources rather than believed from the argument, because the failure this
     * guards is not malicious — it is one `wm density` that was skipped, and the matrix then presenting 393dp
     * frames under a 320dp header. The font scale is measured as the ratio an sp and a dp resolve to, which is
     * what the renderer actually uses; `settings get system font_scale` can answer 1.3 for a display that has
     * not taken it yet.
     */
    private fun assertTheDeviceMatches(tag: String) {
        val parts = tag.split("-")
        assertEquals("the tag names a width, a theme and a font scale: got '$tag'", 3, parts.size)
        val resources = context.resources
        val metrics = resources.displayMetrics
        val widthDp = (metrics.widthPixels / metrics.density).roundToInt()
        val night = (
            resources.configuration.uiMode and
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
            parts[2].toFloat() / 10f, font, 0.02f
        )
    }

    /**
     * One frame of this run's own bytes, named for the step it proves and filed under the configuration that
     * produced it.
     *
     * The bitmap is checked before it is kept, by [blankness]: a window that failed to draw and a screen that is
     * genuinely sparse are the same file size, and Stage 14 settled that difference by measuring the picture rather
     * than by trusting the capture call. A frame the check refuses is still written, named `x-refuse-*`, because a
     * guard that only reports a number cannot be reviewed by anyone who comes after it.
     *
     * A behaviour run passes no tag, and its frames go to a plain `s15/`; a matrix run passes one, and its frames
     * go to `s15-<language>-<tag>` so the script can pull one directory per configuration and refuse to copy a
     * partial set over a complete one.
     */
    private fun frame(name: String) {
        val language = argument("language", "en")
        val tag = argument("tag", "")
        val directory = File(
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
            if (tag.isEmpty()) "shots/s15" else "shots/s15-$language-$tag"
        )
        // Let the window finish drawing before asking it what it looks like. A view-tree read answers as soon as
        // the main thread has rebound a row, while a screenshot captures what the compositor last presented, and on
        // this emulator those are not the same instant: the second matrix run photographed a topic whose row had
        // already been rebound to "open this topic" and whose pixels still said "close this topic". `onIdle` drains
        // Espresso's queue; the settle is the frame that follows it — the same pause the Stage 14 walk takes after
        // a language change before it shoots.
        Espresso.onIdle()
        Thread.sleep(SETTLE_MILLIS)
        val bitmap = shutter(name)
        try {
            val refusal = blankness(bitmap)
            if (refusal != null) {
                // Keep what the guard refused. A run that only prints a number leaves the next reader with the
                // same number and no way to see what the window was doing — and this guard has now refused a
                // picture that was perfectly drawn, which only looking at it could show.
                directory.mkdirs()
                File(directory, "x-refuse-$name.png").outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
            assertNull("the frame '$name' is not a drawn screen — $refusal", refusal)
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            Log.i(TAG, "stage15 frame written: ${directory.name}/$name.png (${bitmap.width}x${bitmap.height})")
        } finally {
            bitmap.recycle()
        }
    }

    private fun shutter(what: String): Bitmap {
        var refusal: Throwable? = null
        repeat(SHUTTER_ATTEMPTS) {
            val outcome = runCatching { instrumentation.uiAutomation.takeScreenshot() }
            outcome.getOrNull()?.let { return it }
            refusal = outcome.exceptionOrNull()
            Thread.sleep(500)
        }
        throw AssertionError(
            "CAPTURE FAILED, not a screen defect: the platform refused to photograph $what on all " +
                "$SHUTTER_ATTEMPTS attempts (${refusal?.javaClass?.simpleName}: ${refusal?.message})"
        )
    }

    /**
     * Why this picture is not a drawn screen, or null when it is one.
     *
     * The first guard sampled a 9x9 grid and asked for contrast between its brightest and darkest point. That was a
     * lottery with a number attached: measured over the frames this stage kept, the same measure read **238** on a
     * dark tree the run accepted and **33** on a dark tree it refused — and the refused picture, which the run now
     * keeps precisely so that it can be looked at, shows the whole tree in white text with a mint button and a
     * bottom bar. The grid's 90 points landed between the rows both times; a screen with thin text on a dark
     * surface has no obligation to put a bright pixel under one of them, and a guard whose verdict depends on
     * where the rows happen to sit is not measuring what it names.
     *
     * So this looks at the whole frame instead, on the two things a window that failed to draw cannot fake: how
     * many different luminances it holds, and how much of it is not the single most common one. Every frame this
     * stage produced measures 189–225 distinct luminances and 18.5–62.2% non-dominant at a 3 px stride; a blank
     * window is one colour, which is 1 and 0. The thresholds sit far under the drawn range and far above the blank
     * one, and a refused frame is written to the device before the run stops, so a refusal is always reviewable
     * rather than only arguable.
     */
    private fun blankness(bitmap: Bitmap): String? {
        val seen = HashMap<Int, Int>()
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) +
                    0.114 * Color.blue(color)).roundToInt()
                seen[luminance] = (seen[luminance] ?: 0) + 1
                total++
                x += SCAN_STRIDE
            }
            y += SCAN_STRIDE
        }
        val dominant = seen.values.maxOrNull() ?: 0
        val share = if (total == 0) 0f else (total - dominant).toFloat() / total
        return if (seen.size >= MIN_DISTINCT_LUMINANCES && share >= MIN_NON_BACKGROUND_SHARE) null
        else "at a ${SCAN_STRIDE}px stride over ${bitmap.width}x${bitmap.height}: $total samples, ${seen.size} " +
            "distinct luminances, ${(share * 100).roundToInt()}% of them something other than the most common one"
    }

    private companion object {
        /**
         * Two topics, a four-level chain, a separator row, a lowercase `[x]` and a title written twice — the
         * shapes §7 and §9 ask the import to answer for, in words nobody owns. 8 lines of text become 2 topics
         * and 6 tasks, and the preview draws 9 rows counting its own statistics card.
         */
        const val FIXTURE_A = "[X] 🟥Сегодня\n [ ] open child\n [X] done child\n  [ ] grandchild\n" +
            "[ ]\n[ ] 🟨Завтра\n [x] lowercase done\n [ ] repeated item\n [ ] repeated item"
        const val A_TOPICS = 2
        const val A_TASKS = 6
        const val A_CHILDREN = 3
        const val PREVIEW_A = 1 + A_TOPICS + A_TASKS
        /** Every title `FIXTURE_A` writes, so "still six of them" is a count and not a guess. */
        val A_TITLES = setOf("open child", "done child", "grandchild", "lowercase done",
            "repeated item")

        /** A chain of its own, so the task-list indent is measured against rows no other method wrote. */
        const val FIXTURE_B = "[ ] Nesting fixture\n [ ] nest level one\n  [ ] nest level two\n" +
            "   [X] nest level three"
        const val PREVIEW_B = 1 + 1 + 3
        /** The three rows [FIXTURE_B] nests, so a frame can be asked whether it shows more than one of them. */
        val NESTING_CHAIN = setOf("nest level one", "nest level two", "nest level three")

        const val WAIT_MILLIS = 40_000L
        /** How long the window is left alone before a shutter: the frame has to be presented, not merely bound. */
        const val SETTLE_MILLIS = 1_500L
        /** How many pixels apart the [blankness] scan reads. Three keeps a frame under 0.2 s and still hits every glyph. */
        const val SCAN_STRIDE = 3
        const val SHUTTER_ATTEMPTS = 5
        const val TAG = "KeepImportFrames"
        /** How far a run will press back looking for the bottom bar before it calls the walk broken. */
        const val BACK_PRESS_LIMIT = 4
        /**
         * How many thirds of a list a run will scroll looking for a row. Twelve is four viewports of the preview,
         * which holds nine rows at any size, and twenty-four is more than the task list's thirteen rows ever need —
         * so a row not found in that many steps is genuinely absent rather than merely further down than the run
         * was willing to look.
         */
        const val PREVIEW_SCROLL_LIMIT = 12
        const val LIST_SCROLL_LIMIT = 24
        /**
         * What a drawn screen looks like, measured rather than assumed: at least this many distinct luminances in
         * the frame, and at least this much of the frame something other than its most common colour.
         *
         * Every frame this stage kept measured 189–225 distinct luminances and 18.5–62.2% non-dominant, and a window
         * that failed to draw is one colour — 1 and 0. The two thresholds sit an order of magnitude below the drawn
         * range and far above the blank one, so a sparse dark screen passes on its content while a black rectangle
         * still cannot. They are floors to be raised against a false accept, never lowered to buy a green run.
         */
        const val MIN_DISTINCT_LUMINANCES = 32
        const val MIN_NON_BACKGROUND_SHARE = 0.05f
        /** The least width a preview row's title may be given, in dp — the design system's own touch-target floor. */
        const val TOUCHABLE_TITLE_DP = 48f

        lateinit var database: AppDatabase
        lateinit var signed: SeededProfile.SignedIn
        var userId: Long = -1L

        @BeforeClass
        @JvmStatic
        fun seedOneProfileForTheClass() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            database = SeededProfile.open(context)
            val swept = SeededProfile.sweepLeftovers(database)
            Log.i(TAG, "swept $swept leftover fixture profile(s) before seeding")
            signed = SeededProfile.signIn(database, "keep-import@example.test", "Keep Import Fixture")
            userId = signed.seededUserId
        }

        @AfterClass
        @JvmStatic
        fun takeTheProfileAway() {
            SeededProfile.takeAway(database, signed)
            database.close()
        }
    }
}
