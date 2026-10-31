package com.taskplanner.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.taskplanner.R
import com.taskplanner.testing.SeededProfile
import com.taskplanner.ui.MainActivity
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The language row, proven on a device: what a reader sees after choosing Russian, and what is still there when
 * the app is started again.
 *
 * **Why this file asserts literal words when the rest of the suite asserts resources.** The rule a functional
 * test follows — compare rendered text to `getString(...)`, never to a literal — exists because a resource
 * comparison cannot say *which* language rendered it. Here the language is the subject: `getString(R.string
 * .title_tasks)` answers "Задачи" in a Russian build and "Tasks" in an English one, and both pass. So the
 * expectations below are the Russian forms and the English forms, written out. That is the one place a literal is
 * the honest choice, and it is why the two lists are named for their language rather than for a screen.
 *
 * **What a test inside the process cannot do.** Instrumentation runs in the app's own process, so it cannot
 * force-stop that process and keep going. An activity restart is asserted here; persistence across a real process
 * death is [theChoiceIsStillThereWhenTheAppIsStartedAgain], which is the same class run again after
 * `am force-stop` and asserts the language it finds *before* touching anything. The runner drives the two in that
 * order with the stop between them; one run alone proves less than it looks like it proves.
 */
@RunWith(AndroidJUnit4::class)
class LanguageSwitchInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    private val russianTabs = listOf("Задачи", "Календарь", "Категории", "Статистика", "Профиль")
    private val englishTabs = listOf("Tasks", "Calendar", "Categories", "Statistics", "Profile")

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the app opens on Tasks",
            waitUntil("the tasks header") { findView(R.id.tasksHeader) != null }
        )
    }

    @After
    fun closeTheWindow() {
        scenario.close()
    }

    @Test
    fun choosingRussianTurnsEveryTabAndEveryHeadingAndEnglishTurnsItBack() {
        startFromSystemDefault()
        openSettings()

        // The row's first job is to name "no choice of our own" as a choice of its own — not to leave the
        // reader with English on screen and no way to tell whether that is the app's decision or the device's.
        assertEquals(
            "and the row marks the system-default choice",
            R.id.languageSystemDefault, checkedLanguageRow()
        )

        onView(withId(R.id.languageRussian)).perform(scrollTo(), click())

        assertTrue(
            "the bar never became Russian; it reads ${tabLabels()}",
            waitUntil("russian tabs") { tabLabels() == russianTabs }
        )
        scenario.onActivity { activity ->
            assertEquals("ru", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            assertEquals(
                "the configuration the activity resolves resources from",
                "ru", activity.resources.configuration.locales[0].language
            )
            assertEquals(
                "the Settings title is Russian too, so it is the whole screen and not the library's labels",
                "Настройки", (activity.findViewById<View>(R.id.settingsTitleText) as TextView).text.toString()
            )
            assertEquals(
                "the row keeps the choice it just applied",
                R.id.languageRussian, checkedLanguageRow()
            )
            assertEquals(
                "and the account section is Russian, which is the part Stage 13 wrote last",
                "Не настроено",
                (activity.findViewById<View>(R.id.accountStateText) as TextView).text.toString()
            )
        }

        // An activity restart inside the same process: the framework must hand the same language back, and the
        // reader must still be standing where they were standing. Settings is a destination on the back stack,
        // so a recreate that lost the screen would show up here as a missing title — the brief's "switching
        // language does not reset the current screen", proved by staying put rather than by navigating back.
        scenario.onActivity { activity -> activity.recreate() }
        assertTrue(
            "the app did not come back Russian after the activity was recreated",
            waitUntil("russian tabs again") { tabLabels() == russianTabs }
        )
        assertTrue(
            "the recreate moved the reader off Settings; the app is on ${name(destination())}",
            waitUntil("settings again") { destination() == R.id.navigation_settings }
        )
        scenario.onActivity { activity ->
            assertEquals(
                "and the screen it restored is still the Russian one",
                "Настройки", (activity.findViewById<View>(R.id.settingsTitleText) as TextView).text.toString()
            )
            assertEquals(
                "the row still marks the choice it holds",
                R.id.languageRussian, checkedLanguageRow()
            )
        }

        onView(withId(R.id.languageEnglish)).perform(scrollTo(), click())
        assertTrue(
            "the bar never became English; it reads ${tabLabels()}",
            waitUntil("english tabs") { tabLabels() == englishTabs }
        )
        scenario.onActivity { activity ->
            assertEquals("en", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            assertEquals(R.id.languageEnglish, checkedLanguageRow())
        }
    }

    /**
     * The second run, after `am force-stop`. Nothing here sets a language before the assertions: the whole point
     * is what the process found at startup. Run after [choosingRussianTurnsEveryTabAndEveryHeadingAndEnglishTurnsItBack]
     * with a force-stop between, this is the only assertion in the repository that says the choice survived the
     * app being killed — which is what the row promises a reader.
     *
     * It ends on System default, so every other class in the matrix keeps running against the language it was
     * written in.
     */
    @Test
    fun theChoiceIsStillThereWhenTheAppIsStartedAgain() {
        val stored = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        assertTrue(
            "this method only means something when an earlier run left a choice behind; this process started " +
                "with '$stored'",
            stored == "en" || stored == "ru"
        )
        val expected = if (stored == "ru") russianTabs else englishTabs
        scenario.onActivity { activity ->
            assertEquals(
                "the bar shows the language the process started with",
                expected, tabLabels()
            )
            assertEquals(stored, activity.resources.configuration.locales[0].language)
        }

        openSettings()
        onView(withId(R.id.languageSystemDefault)).perform(scrollTo(), click())
        assertTrue(
            "choosing System default did not hand the language back to the device; the bar reads " +
                "${tabLabels()} and the device says ${deviceLanguageTabs()}",
            waitUntil("device language") { tabLabels() == deviceLanguageTabs() }
        )
        scenario.onActivity { activity ->
            assertEquals(
                "an empty app locale list is what System default means to the framework",
                "", AppCompatDelegate.getApplicationLocales().toLanguageTags()
            )
            assertEquals(
                R.id.languageSystemDefault, checkedLanguageRow()
            )
        }
    }

    // ---------------------------------------------------------------- the reading

    /**
     * System default, established rather than assumed.
     *
     * The app's language does not live in the app's own storage, and the runner's package-data clear does not
     * take it back: a run that left the app Russian was found reporting `ru` by the next one, with only the
     * app's own `task_planner_prefs.xml` left behind in `shared_prefs`. That is the right behaviour for the
     * promise the row makes — a choice that survives an update and a data wipe is a choice a reader can trust —
     * and it is a precondition this class has to set rather than take on faith. Setting it also exercises the
     * return to System default, which the row promises and the framework owns.
     */
    private fun startFromSystemDefault() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        assertTrue(
            "handing the language back to the device did not reach the screen; the bar reads ${tabLabels()} " +
                "and the device's labels are ${deviceLanguageTabs()}",
            waitUntil("the device language") { tabLabels() == deviceLanguageTabs() }
        )
        scenario.onActivity { activity ->
            assertEquals(
                "an empty app locale list is what System default means to the framework",
                "", AppCompatDelegate.getApplicationLocales().toLanguageTags()
            )
            assertEquals(
                "and the activity resolves resources in the device's language",
                deviceLanguageTag(), activity.resources.configuration.locales[0].toLanguageTag()
            )
        }
    }

    private fun openSettings() {
        pressTab(R.id.navigation_profile)
        assertTrue("the account screen drew", waitUntil("account") { findView(R.id.openSettingsButton) != null })
        onView(withId(R.id.openSettingsButton)).perform(scrollTo(), click())
        assertTrue("Settings did not draw", waitUntil("settings") { findView(R.id.settingsTitleText) != null })
        onView(withId(R.id.languageGroup)).check(matches(isDisplayed()))
    }

    /**
     * The tab, pressed the way a reader presses it, with the destination as the proof.
     *
     * The first version of this helper wrote [BottomNavigationView.setSelectedItemId] instead of clicking, and
     * it held until the test recreated the activity. `NavigationBarView.setSelectedItemId` calls
     * `menu.performItemAction(item, presenter, 0)` and, when that answers false, falls back to
     * `item.setChecked(true)` — which never reaches the listener NavigationUI installed. A recreate restores the
     * bar's selection along with its state, so the assignment arrived at the item the bar already believed was
     * selected, was refused for that reason, and left the app on the screen it was already showing.
     *
     * A reader's tap has the same blind spot, and that was a defect rather than a fixture detail: while the
     * screen is on Settings the bar still holds `navigation_profile`, because Settings is reached from the
     * account screen and is not a bottom-bar destination of its own, and a tap on the tab the bar already holds
     * was a re-selection that navigated nowhere. `MainActivity` answers re-selections now, so this helper is
     * the plain click it always wanted to be, and [theTabThatOwnsTheScreenCarriesTheReaderBackToIt] is the
     * proof that a reader on Settings can leave by the tab they can see lit.
     */
    private fun pressTab(id: Int) {
        val from = destination()
        onView(withId(id)).perform(click())
        assertTrue(
            "pressing ${name(id)} left the app on ${name(from)}, and the bar's selection is ${name(selection())}",
            waitUntil("the tab") { destination() == id }
        )
    }

    /**
     * The screen a reader is on, and the tab that owns it, have to agree about how to leave.
     *
     * Recorded by Stage 14 as an open defect and fixed by the completion pass. The state it establishes first is
     * not the defect: Settings is reached *from* the account screen, so the bar holding `navigation_profile`
     * while Settings is drawn is the Material convention, and the destination the reader was on survives the
     * recreation — which is the part the brief asks to be preserved rather than forced back to a tab. The
     * defect was the tap: the lit tab is the only control on the bar that looks like a way out, and pressing it
     * did nothing at all, leaving the system back gesture as the only exit.
     *
     * It never had anything to do with language. This method chooses no language; its one call hands the choice
     * back to the device. It is kept here because the row is where a reader actually arrives at this state.
     */
    @Test
    fun theTabThatOwnsTheScreenCarriesTheReaderBackToIt() {
        startFromSystemDefault()
        openSettings()
        assertEquals(
            "and the row keeps its system-default choice when nothing has been pressed",
            R.id.languageSystemDefault, checkedLanguageRow()
        )

        scenario.onActivity { activity -> activity.recreate() }
        assertTrue(
            "the window did not come back",
            waitUntil("settings again") { findView(R.id.settingsTitleText) != null }
        )
        assertEquals(
            "the screen restored to Settings, which is where the reader was",
            R.id.navigation_settings, destination()
        )
        assertEquals(
            "and the bar holds the tab that owns it",
            R.id.navigation_profile, selection()
        )

        // The reader's own action, on the row this screen exists to offer. Choosing a language is the moment
        // the pair above is most likely to come apart, and it is the moment the completion pass was asked
        // about: the app must stay on Settings, keep Profile lit as its owner, and still let the reader leave.
        onView(withId(R.id.languageRussian)).perform(scrollTo(), click())
        assertTrue(
            "the bar never became Russian; it reads ${tabLabels()}",
            waitUntil("russian tabs") { tabLabels() == russianTabs }
        )
        assertEquals(
            "choosing Russian moved the reader out of the screen they were using",
            R.id.navigation_settings, destination()
        )
        assertEquals(
            "and the bar still holds the tab that owns it",
            R.id.navigation_profile, selection()
        )

        pressTab(R.id.navigation_profile)
        assertTrue(
            "the account screen did not draw after its own tab was pressed",
            waitUntil("profile") { findView(R.id.openSettingsButton) != null }
        )
        assertEquals(
            "leaving Settings by the tab left the bar somewhere else",
            R.id.navigation_profile, selection()
        )
        assertEquals(
            "the reader is back on the account screen, still in Russian",
            russianTabs, tabLabels()
        )
    }

    /** The destination the nav host is showing, or 0 before it has one. */
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

    /** The item the bottom bar thinks is selected, which after a recreate need not be the screen on show. */
    private fun selection(): Int {
        var id = 0
        runCatching { scenario.onActivity { activity -> id = bar(activity).selectedItemId } }
        return id
    }

    private fun bar(activity: MainActivity): BottomNavigationView =
        activity.findViewById(R.id.bottom_navigation)

    private fun name(id: Int): String =
        if (id == 0) "no destination" else runCatching { context.resources.getResourceEntryName(id) }
            .getOrDefault("id/$id")

    private fun checkedLanguageRow(): Int {
        var id = -1
        scenario.onActivity { activity ->
            id = activity.findViewById<RadioGroup>(R.id.languageGroup).checkedRadioButtonId
        }
        return id
    }

    /** The five words the bottom bar shows, left to right. */
    private fun tabLabels(): List<String> {
        var labels = emptyList<String>()
        runCatching {
            scenario.onActivity { activity ->
                val bar = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
                labels = (0 until bar.menu.size()).map { bar.menu.getItem(it).title?.toString().orEmpty() }
            }
        }
        return labels
    }

    /**
     * What the bar would say if the app followed the device rather than its own choice.
     *
     * The device's language is read from the *instrumentation* context, not the target app's: the test package
     * has no per-app locale of its own, so its configuration is the system's, while the target app's reflects
     * whatever the app last chose. Reading the expectation from the target app would make this comparison
     * circular — a still-Russian app would be asked to match a Russian expectation it produced itself, and it
     * would pass.
     */
    private fun deviceLanguageTabs(): List<String> {
        val configuration = Configuration(instrumentationContext().resources.configuration)
        configuration.setLocale(instrumentationContext().resources.configuration.locales[0])
        val resources = context.createConfigurationContext(configuration).resources
        return listOf(
            R.string.title_tasks, R.string.title_calendar, R.string.title_categories,
            R.string.title_statistics, R.string.title_profile
        ).map { resources.getString(it) }
    }

    /** The device's own language tag, from the same unpolluted source. */
    private fun deviceLanguageTag(): String =
        instrumentationContext().resources.configuration.locales[0].toLanguageTag()

    private fun findView(id: Int): View? {
        var found: View? = null
        runCatching { scenario.onActivity { activity -> found = activity.findViewById<View>(id) } }
        return found
    }

    private fun waitUntil(description: String, condition: () -> Boolean): Boolean {
        android.os.Trace.beginSection("stage 14 waiting for $description")
        try {
            val deadline = System.currentTimeMillis() + WAIT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                if (runCatching(condition).getOrDefault(false)) return true
                Thread.sleep(100)
            }
            return false
        } finally {
            android.os.Trace.endSection()
        }
    }

    private companion object {
        /**
         * A language change recreates every activity in the task, and on a cold emulator that has been measured
         * at tens of seconds. The wait is generous because a refusal that arrives too early is a false failure,
         * and this class is the one place the feature is proved.
         */
        const val WAIT_MILLIS = 60_000L

        /**
         * Long enough for a navigation transaction to have happened if it was going to, and short enough that a
         * test which waits this is not polling for a state it cannot reach. Only [theDeadTabIsDeadWithoutAnyLanguageChange]
         * uses it, because that method's claim is about a tap that does nothing — there is no arrival to watch.
         */
        const val TAP_SETTLE_MILLIS = 3_000L

        lateinit var signedIn: SeededProfile.SignedIn

        /**
         * The *test package's* context, on purpose.
         *
         * `targetContext` is the app under test, and on API 33+ its configuration carries the app's chosen
         * locale — so deriving "what the device would say" from it asks the app what it is already showing and
         * calls the answer independent. The instrumentation package holds no app locale of its own, so its
         * configuration really is the system's.
         */
        private fun instrumentationContext(): Context =
            InstrumentationRegistry.getInstrumentation().context

        /**
         * A profile to be signed into. With no current user the app opens on the profile picker rather than on
         * Tasks, and every assertion in this class about the bottom bar would then be reading a bar that is not
         * on screen — a failure that says "the language did not change" about a screen that never showed one.
         */
        @BeforeClass
        @JvmStatic
        fun seedAProfile() {
            val database = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.sweepLeftovers(database)
                signedIn = SeededProfile.signIn(
                    database, "stage14-${System.nanoTime()}@example.test", "Stage 14"
                )
            } finally {
                database.close()
            }
        }

        @AfterClass
        @JvmStatic
        fun takeTheProfileAway() {
            if (!::signedIn.isInitialized) return
            val database = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.takeAway(database, signedIn)
            } finally {
                database.close()
            }
        }
    }
}
