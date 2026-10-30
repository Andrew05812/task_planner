package com.taskplanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.testing.SeededProfile
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkSession
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
 * The same seven screens, photographed in both languages, so a Russian string is judged on a screen rather
 * than in a table.
 *
 * Stage 14 did not only add words — it added *longer* words. Russian puts more characters into the same
 * meaning, and the redesign of Stages 3 to 8 budgeted its widths against English: the five bottom-bar labels
 * that Stage 7 measured to fit, the task card's 82dp and 84dp allowances that Stage 4 proved at 320dp, the
 * section headers of the editor. Those budgets are claims about strings, and the strings changed. A frame is
 * the only place that shows whether a header ellipsised, a nav label wrapped to a second line, or a card
 * pushed its category chip off the row.
 *
 * **What the run is told and what it checks.** `-e language ru|en` selects the language by pressing the
 * Settings row a reader presses, and `-e tag 393-light-10` names the width, the theme and the font scale the
 * device was configured to before the run. The tag is not trusted: [photograph] reads the device's own
 * metrics and refuses a frame whose tag lies, because a mislabelled matrix is worse than none — it is evidence
 * for a configuration that was never on screen.
 *
 * **What is seeded.** A profile, one category and three tasks whose titles are the Russian text a reader of
 * this app would actually type (the coursework the app is built around). Task titles are data, not interface:
 * they stay as written in both languages, and that is the point of seeding them — a card has to fit a real
 * title under real labels, not a placeholder.
 *
 * **How to run it, and why the order is not optional.** Each configuration is two invocations of this class
 * with an `am force-stop` between them:
 *
 * ```
 * am instrument -e class …Stage14LanguageFramesInstrumentedTest -e language ru -e tag 393-light-10 -e mode=choose -e reset=false
 * adb shell am force-stop com.taskplanner
 * am instrument -e class …Stage14LanguageFramesInstrumentedTest -e language ru -e tag 393-light-10 -e mode=walk -e apply=false
 * ```
 *
 * The force-stop is load-bearing, and a first matrix learned that the hard way: a run's `@AfterClass` takes
 * the seeded profile away, so a walk that reuses the process the choose run left behind is a walk in which no
 * profile is signed in — the dashboard comes back empty and the account screen shows «No profile found»
 * instead of a name. Killing the process is also the truer sequence: it is the reader closing the app after
 * choosing a language and coming back, which is the case `theChoiceIsStillThereWhenTheAppIsStartedAgain`
 * proves. [photograph] now refuses a frame whose screen is not on the window, so a run that forgets the
 * force-stop fails with the words on the screen rather than writing a mislabelled picture.
 */
@RunWith(AndroidJUnit4::class)
class Stage14LanguageFramesInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the app never opened on Tasks", waitUntil { findView(R.id.tasksHeader) != null })
        // A window can draw and still not have focus, and `findViewById` cannot tell the two apart — so the
        // assertion above passes, and the first Espresso click of the walk then dies with
        // `RootViewWithoutFocusException` ten seconds later, blamed on whatever tab happened to be first.
        // This configuration changes density and font scale between runs, which is exactly when the system
        // keeps the window unfocused a while longer, so focus is waited for where it is needed and named as
        // the environment condition it is when it does not come.
        waitForWindowFocus()
    }

    /**
     * Wait for the app's window to hold focus, and say so plainly when the device will not give it.
     *
     * A wake-up key is sent once, because the screen can simply be asleep; nothing else is tried, because
     * nothing else a test can do will take focus away from whatever system window is holding it. When the
     * wait expires the run stops with `ENVIRONMENT` in its message: a frame never taken and a screen that
     * cannot be reached are different answers, and the matrix has to be able to tell them apart. This is not
     * a hypothetical — three configurations failed with `RootViewWithoutFocusException` while the emulator's
     * Bluetooth stack was crash-looping every two minutes, and the app was nowhere in the crash buffer.
     */
    private fun waitForWindowFocus() {
        if (hasFocus()) return
        runCatching {
            InstrumentationRegistry.getInstrumentation().sendKeySync(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP)
            )
        }
        assertTrue(
            "ENVIRONMENT, not a screen defect: the app drew its Tasks screen but its window never took " +
                "focus, so Espresso cannot press anything. Check the device's crash buffer before " +
                "believing anything else this run says.",
            waitUntil { hasFocus() }
        )
    }

    private fun hasFocus(): Boolean {
        var focused = false
        runCatching { scenario.onActivity { activity -> focused = activity.window.decorView.hasWindowFocus() } }
        return focused
    }

    /**
     * Hand the language back, then close.
     *
     * Through [AppCompatDelegate] rather than the Settings row, on purpose: the row is the feature
     * `LanguageSwitchInstrumentedTest` proves, and pressing it again from wherever this walk happened to end —
     * a detail screen with no bottom bar, or a bar still holding the tab the recreate restored — is a
     * navigation exercise, not the point of this class. The window is still open here, which is the one
     * condition the delegate needs to accept the change; see `CsvLocaleTest` on what happens without it.
     */
    @After
    fun closeTheWindow() {
        if (InstrumentationRegistry.getArguments().getString("reset", "true") == "true") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        scenario.close()
    }

    /**
     * Choose the language and photograph the screen the choice lives on.
     *
     * Deliberately its own run rather than a prefix of [walkTheSevenScreens]: a locale change recreates every
     * activity in the task, and the bottom bar comes back holding the tab the reader arrived on, which makes
     * the next tab press a re-selection that navigates nowhere. Doing the choosing in one process and the
     * walking in the next is also the truer sequence — it is what a reader does, closing the app and coming
     * back, and it is the case `theChoiceIsStillThereWhenTheAppIsStartedAgain` proves for a single screen.
     */
    @Test
    fun choosingTheLanguagePhotographsTheRowThatCarriesIt() {
        val wanted = InstrumentationRegistry.getArguments().getString("language", "en")
        if (wanted == "en" ||
            InstrumentationRegistry.getArguments().getString("mode", "walk") != "choose"
        ) {
            return
        }
        chooseLanguage(wanted)
        assertLanguageOnScreen(wanted)
        photograph("06-settings", R.id.settingsTitleText)
    }

    @Test
    fun walkTheSevenScreens() {
        val wanted = InstrumentationRegistry.getArguments().getString("language", "en")
        if (InstrumentationRegistry.getArguments().getString("mode", "walk") != "walk") {
            return
        }
        assertLanguageOnScreen(wanted)
        // `-e apply false` walks and photographs without touching the language at all, which is how a second
        // run over the same configuration is separated from the run that chose it. A locale change recreates
        // every activity in the task, and the bar comes back holding the tab it was on — from that state the
        // tab presses below are re-selections, and the frames would be of a screen the walk never reached. The
        // choice is made once, in the run that has `apply true`, and the run that follows sees the same
        // language through a process start, which is also the way a reader returns to the app.
        val apply = InstrumentationRegistry.getArguments().getString("apply", "true") == "true"
        if (wanted != "en" && apply) chooseLanguage(wanted)

        // A locale change recreates every activity, and the bar comes back holding the tab the reader chose
        // language from — so the screen under the frame is Settings, not Tasks, and the first frame of the run
        // would be a lie. Go to Tasks by the bar, the way a reader does on the way out of Settings.
        gotoTab(R.id.navigation_tasks, R.id.tasksHeader)
        photograph("01-tasks", R.id.tasksHeader)
        gotoTab(R.id.navigation_calendar, R.id.calendarTitle)
        photograph("02-calendar", R.id.calendarTitle)
        gotoTab(R.id.navigation_categories, R.id.recyclerViewCategories)
        photograph("03-categories", R.id.recyclerViewCategories)
        gotoTab(R.id.navigation_statistics, R.id.dashboardScroll)
        photograph("04-statistics", R.id.dashboardScroll)
        gotoTab(R.id.navigation_profile, R.id.profileName)
        photograph("05-profile", R.id.profileName)

        // The Settings row is below the fold on a 320dp screen, so it is scrolled to before it is pressed.
        onView(withId(R.id.openSettingsButton)).perform(scrollTo(), click())
        wait(R.id.settingsTitleText)
        photograph("06-settings", R.id.settingsTitleText)

        // The editor, reached the way a reader reaches it, with the language row already behind it. This is the
        // screen with the most words on it in the app: five section headings, eleven field labels, four
        // buttons in the footer.
        gotoTab(R.id.navigation_tasks, R.id.taskCard)
        // Three seeded cards share the id `taskCard`, and Espresso refuses a matcher that answers three
        // views, so the row is opened by the title the seed wrote. It is data the reader typed, not interface
        // text, so it reads the same in both languages — which is what makes it a stable handle.
        onView(withText(SEEDED_TASK_TITLE)).perform(click())
        wait(R.id.task_title)
        photograph("07-task-detail", R.id.task_title)
    }

    // ---------------------------------------------------------------- the language

    /**
     * Choose the language through the Settings row.
     *
     * Deliberately not `AppCompatDelegate.setApplicationLocales` from the test: the row is the feature under
     * review, and pressing it also proves it is reachable and tappable at whatever width and font scale this
     * run was configured to.
     */
    private fun chooseLanguage(to: String) {
        // The walk can end on a detail screen, which has no bottom bar to press; back out of it first.
        if (findView(R.id.bottom_navigation) == null) {
            runCatching { androidx.test.espresso.Espresso.pressBack() }
            Thread.sleep(400)
        }
        val button = when (to) {
            "ru" -> R.id.languageRussian
            "en" -> R.id.languageEnglish
            else -> R.id.languageSystemDefault
        }
        leaveDetailScreen()
        // Through Tasks first, every time. The bar keeps its selection across a locale-triggered recreate,
        // and a tap on the tab it already holds is a re-selection that navigates nowhere — the defect
        // `theDeadTabIsDeadWithoutAnyLanguageChange` records. Coming in from Tasks means the tab being pressed
        // is never the tab already held.
        press(R.id.navigation_tasks)
        wait(R.id.tasksHeader)
        press(R.id.navigation_profile)
        onView(withId(R.id.openSettingsButton)).perform(scrollTo(), click())
        wait(R.id.settingsTitleText)
        onView(withId(button)).perform(scrollTo(), click())
        val wanted = when (to) {
            "ru" -> "ru"
            "en" -> "en"
            else -> ""
        }
        assertTrue(
            "pressing the language row did not apply '$wanted'; the app reports " +
                "'${androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags()}'",
            waitUntil {
                androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() == wanted
            }
        )
        Thread.sleep(SETTLE_MILLIS)
    }

    // ---------------------------------------------------------------- the walk

    /**
     * Back out of a detail screen, because the bottom bar is not on one.
     *
     * The walk ends on a task, and the teardown still has to hand the language back — which lives on Settings,
     * which is reached from the bar. Pressing back until the bar is on the window is what a reader does; the
     * guard is there because Espresso's `pressBack` refuses when a screen does not take it, and a teardown that
     * throws hides the reason the walk failed.
     */
    private fun leaveDetailScreen() {
        var guard = 0
        while (findView(R.id.bottom_navigation) == null && guard < BACK_PRESS_LIMIT) {
            runCatching { androidx.test.espresso.Espresso.pressBack() }
            Thread.sleep(400)
            guard++
        }
    }

    /**
     * Go to a bottom-bar screen, pressing until it opens.
     *
     * The repeated press is `ProfileScreenInstrumentedTest.pressUntil`, the shape this suite already uses for
     * the same reason: after a locale change the bar restores the selection it had, so the first tap on a tab
     * can be a re-selection that navigates nowhere. A second tap arrives; a third, and the screen is still
     * missing, is a real failure and is reported as one, with the destination named.
     */
    private fun gotoTab(tab: Int, screen: Int) {
        for (attempt in 1..TAB_ATTEMPTS) {
            press(tab)
            if (settled(screen)) return
            // A second press on the same tab is another re-selection. The way out is a tab that is *not* the
            // one the bar holds, which is what makes the next press of the target a real navigation.
            press(R.id.navigation_calendar)
            settled(R.id.calendarTitle)
        }
        assertTrue(
            "${context.resources.getResourceEntryName(screen)} never drew after $TAB_ATTEMPTS presses on " +
                "${context.resources.getResourceEntryName(tab)}. The nav host is on " +
                "${nameOr0(destination())}, the bar holds ${nameOr0(barSelection())}, and the screen reads: " +
                visibleText(),
            settled(screen)
        )
    }

    /** The item the bottom bar thinks is selected, which after a recreate need not be the screen on show. */
    private fun barSelection(): Int {
        var id = 0
        runCatching { scenario.onActivity { activity -> id = bar(activity).selectedItemId } }
        return id
    }

    private fun bar(activity: MainActivity): com.google.android.material.bottomnavigation.BottomNavigationView =
        activity.findViewById(R.id.bottom_navigation)

    private fun nameOr0(id: Int): String =
        if (id == 0) "nothing" else context.resources.getResourceEntryName(id)

    /**
     * The words on the window, for the message of a press that never arrived.
     *
     * The first matrix failed with only a destination name to go on, and that was not enough to tell a tab
     * that did not navigate from a screen that navigated but drew nothing — which is the difference between a
     * navigation defect and a missing profile. Ten seconds of the reader's own vocabulary says more about
     * which of those happened than any id does.
     */
    private fun visibleText(): String {
        var text = ""
        runCatching {
            scenario.onActivity { activity ->
                val words = StringBuilder()
                collectWords(activity.window.decorView, words)
                text = words.toString().trim()
            }
        }
        return if (text.length > MAX_WORDS) text.take(MAX_WORDS) + "…" else text.ifEmpty { "(nothing)" }
    }

    private fun collectWords(view: View, into: StringBuilder) {
        if (view.visibility != View.VISIBLE || into.length > MAX_WORDS) return
        if (view is android.widget.TextView && view.text.isNotBlank()) {
            into.append(view.text.toString().trim()).append(" | ")
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectWords(view.getChildAt(i), into)
        }
    }

    /**
     * A short wait for a screen that is *on*, not merely in the tree.
     *
     * `findViewById` answers a view that is `GONE`, and the dashboard's scroll container is `GONE` until its
     * first snapshot arrives — so the first version of this helper let the walk photograph a Statistics
     * screen that was still loading, in both languages, and the frame looked like an empty dashboard. The
     * brief asked for Russian figures measured on a device; a blank panel measures nothing. Presence is not
     * arrival, so this asks the view whether it is shown.
     */
    private fun settled(id: Int): Boolean {
        val deadline = System.currentTimeMillis() + SHORT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (findView(id)?.isShown == true) return true
            Thread.sleep(100)
        }
        return findView(id)?.isShown == true
    }

    /** The destination the nav host is on, for the message of a press that never arrived. */
    private fun destination(): Int {
        var id = 0
        runCatching {
            scenario.onActivity { activity ->
                id = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                    as androidx.navigation.fragment.NavHostFragment)
                    .navController.currentDestination?.id ?: 0
            }
        }
        return id
    }


    /**
     * The language the run's tag claims, checked against the window it is about to photograph.
     *
     * Added after a first matrix came back with a directory named `s14-ru-…` holding an English Tasks screen:
     * the class that chose the language also handed it back on the way out, so the run that followed walked the
     * app in the device's language and wrote its frames under the Russian name. Width, theme and font scale
     * were already checked; the language was assumed, and an assumed label is exactly how a matrix starts
     * proving something it never measured.
     */
    private fun assertLanguageOnScreen(wanted: String) {
        var seen = ""
        scenario.onActivity { activity -> seen = activity.resources.configuration.locales[0].language }
        assertEquals(
            "the run asked for '$wanted' and the window is resolving '$seen'",
            wanted, seen
        )
    }

    private fun press(id: Int) {
        onView(withId(id)).perform(click())
        assertTrue(
            "the bar never answered the press on ${context.resources.getResourceEntryName(id)}",
            waitUntil { findView(id)?.let { true } ?: false }
        )
    }

    private fun wait(id: Int) {
        assertTrue("${context.resources.getResourceEntryName(id)} never drew", waitUntil { findView(id) != null })
    }

    private fun findView(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }

    // ---------------------------------------------------------------- the shutter

    /**
     * One frame, named, checked for lying about its configuration and for being a picture at all.
     *
     * The guard reads the device rather than the argument: width in dp from the display metrics, night from
     * the ui mode, font scale from the ratio a sp and a dp resolve to. A matrix assembled by an operator who
     * forgot one `wm density` between runs would otherwise present 320dp frames under a 393dp column header.
     *
     * **And it reads the screen.** The first matrix had a `01-tasks.png` that was a photograph of Settings,
     * because a locale change recreates the activity with the bar still holding the tab the reader chose the
     * language from, and the walk then shot whatever was up. A frame that names one screen and shows another is
     * worse than a missing frame: it is evidence nobody can re-check. So every shutter is asked which view it
     * expects to find, and refuses if that view is not on the window.
     */
    private fun photograph(name: String, onScreen: Int) {
        assertTrue(
            "refusing to write '$name': ${context.resources.getResourceEntryName(onScreen)} is not on the " +
                "window. The bar holds ${nameOr0(barSelection())}, the nav host is on " +
                "${nameOr0(destination())}, and the screen reads: ${visibleText()}",
            settled(onScreen)
        )

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
            parts[2].toFloat() / 10f, font, 0.02f
        )

        val language = InstrumentationRegistry.getArguments().getString("language", "en")
        val bitmap = shutter(name)
        try {
            val spread = luminanceSpread(bitmap)
            assertTrue(
                "the frame '$name' is not a drawn screen: over a grid across the whole window the brightest " +
                    "sample is $spread levels above the darkest",
                spread >= 40
            )
            val directory = File(
                context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory(),
                "shots/s14-$language-$tag"
            )
            directory.mkdirs()
            File(directory, "$name.png").outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            println("stage14 frame written: s14-$language-$tag/$name.png")
        } finally {
            bitmap.recycle()
        }
    }

    private fun shutter(what: String): Bitmap {
        var refusal: Throwable? = null
        repeat(SHUTTER_ATTEMPTS) {
            val outcome = runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            }
            outcome.getOrNull()?.let { return it }
            refusal = outcome.exceptionOrNull()
            Thread.sleep(500)
        }
        throw AssertionError(
            "CAPTURE FAILED, not a screen defect: the platform refused to photograph $what on all " +
                "$SHUTTER_ATTEMPTS attempts (${refusal?.javaClass?.simpleName}: ${refusal?.message})"
        )
    }

    private fun luminanceSpread(bitmap: Bitmap): Int {
        var brightest = 0
        var darkest = 255
        val stepX = max(1, bitmap.width / GRID)
        val stepY = max(1, bitmap.height / GRID)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) +
                    0.114 * Color.blue(color)).roundToInt()
                brightest = max(brightest, luminance)
                darkest = min(darkest, luminance)
                x += stepX
            }
            y += stepY
        }
        return brightest - darkest
    }

    private companion object {
        const val WAIT_MILLIS = 30_000L
        const val SETTLE_MILLIS = 1_500L
        const val SHUTTER_ATTEMPTS = 5
        const val GRID = 12
        const val BACK_PRESS_LIMIT = 4
        const val TAB_ATTEMPTS = 3
        const val SHORT_MILLIS = 8_000L
        /** How much of the screen's vocabulary a failure message carries — enough to identify it, not a dump. */
        const val MAX_WORDS = 400
        /** The design system's category teal, the same value the card-state frames use. */
        val CATEGORY_COLOR: Int = 0xFF2F6F5E.toInt()
        const val DAY = 86_400_000L
        val SEEDED_TASK_TITLE = "Написать обзор литературы"

        /**
         * A profile with a category and three tasks, in the words a Russian reader of this app would type.
         *
         * Titles are the reader's own text and stay as written in either language — that is what makes them
         * useful here: a card has to fit real content under real interface labels, not a placeholder chosen to
         * fit. `SeededProfile` owns the profile's life, and taking it away removes the rows it owned too.
         */
        lateinit var signedIn: SeededProfile.SignedIn

        @BeforeClass
        @JvmStatic
        fun seedAProfileWithWork() {
            val opened = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.sweepLeftovers(opened)
                signedIn = SeededProfile.signIn(
                    opened, "stage14-${System.nanoTime()}@example.test", "Аня Родина"
                )
                val now = System.currentTimeMillis()
                runBlocking {
                    val categoryId = opened.categoryDao().insertCategory(
                        Category(
                            id = 0, uuid = UUID.randomUUID().toString(), name = "Диссертация",
                            color = CATEGORY_COLOR, icon = "", userId = signedIn.seededUserId
                        )
                    )
                    val firstTaskUuid = UUID.randomUUID().toString()
                    opened.taskDao().insertTask(
                        Task(
                            uuid = firstTaskUuid, title = SEEDED_TASK_TITLE,
                            description = "", userId = signedIn.seededUserId, status = TaskStatus.TODO,
                            priority = Priority.LOW, categoryId = categoryId, dueDate = now - 3 * DAY
                        )
                    )
                    opened.taskDao().insertTask(
                        Task(
                            uuid = UUID.randomUUID().toString(), title = "Сформировать цель ВКР",
                            description = "", userId = signedIn.seededUserId,
                            status = TaskStatus.IN_PROGRESS, priority = Priority.HIGH,
                            categoryId = categoryId, dueDate = now + 2 * DAY
                        )
                    )
                    opened.taskDao().insertTask(
                        Task(
                            uuid = UUID.randomUUID().toString(), title = "Разобрать замечания руководителя",
                            description = "", userId = signedIn.seededUserId, status = TaskStatus.PAUSED,
                            priority = Priority.MEDIUM
                        )
                    )
                    // Two closed intervals on two different days. Without them the Statistics screen has no
                    // snapshot to render and the frame is an empty panel in either language — which is what
                    // the first two matrices actually shipped, because `findViewById` answers a container the
                    // renderer had left `GONE`. With them the dashboard draws a bar, a heatmap cell and a
                    // duration, and the Russian frame shows how this app writes `2 ч 30 мин` beside a figure.
                    val measured = opened.taskDao().findTaskByUuid(firstTaskUuid)
                        ?: error("the frame seed's own task row is missing; nothing below can be measured")
                    val today = java.time.LocalDate.now()
                    listOf(9_000L to 0L, 2_700L to 1L).forEach { (seconds, daysBack) ->
                        opened.workSessionDao().insert(
                            WorkSession(
                                uuid = "stage14-frame-${System.nanoTime()}-$daysBack",
                                userId = signedIn.seededUserId,
                                trackingSessionUuid = "stage14-session-$daysBack",
                                taskId = measured.id, taskUuid = measured.uuid,
                                startedAt = System.currentTimeMillis() - daysBack * DAY - seconds * 1000L,
                                endedAt = System.currentTimeMillis() - daysBack * DAY,
                                durationSeconds = seconds,
                                localDay = today.minusDays(daysBack).toEpochDay(),
                                utcOffsetMinutes = java.time.ZoneId.systemDefault()
                                    .rules.getOffset(java.time.Instant.now()).totalSeconds / 60
                            )
                        )
                    }
                }
            } finally {
                opened.close()
            }
        }

        @AfterClass
        @JvmStatic
        fun takeTheProfileAway() {
            if (!::signedIn.isInitialized) return
            val opened = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                // `SeededProfile.orphanTables` is tasks, categories and reminders; a measured interval is
                // none of those, so this class's own seed rows would outlive the profile they belong to and
                // sit in the database as residue no screen can address. They are removed here, by the user id
                // this class wrote them under — the only ownership that can be proved from the fixture.
                val db = opened.openHelper.writableDatabase
                val gone = db.delete(
                    "work_sessions", "user_id = ?",
                    arrayOf(signedIn.seededUserId.toString())
                )
                println("SeededProfile: this class removed $gone measured interval(s) it had written")
                SeededProfile.takeAway(opened, signedIn)
            } finally {
                opened.close()
            }
        }
}
}
