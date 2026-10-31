package com.taskplanner.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.taskplanner.R
import com.taskplanner.testing.AppWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the reader was, after the activity is rebuilt under them.
 *
 * Stage 14 found this by changing the language: the app went to Profile, the language was switched, and the
 * screen that came back was Profile's while the bar was lit on Tasks. That is not a cosmetic disagreement —
 * the highlighted tab is the one the bar believes is selected, and pressing the tab the bar already holds is a
 * re-selection that never navigates, so the reader who taps the bright icon to get back to their list gets
 * nothing at all.
 *
 * **Three facts have to agree, so three are read.** The bar's selection, the destination the `NavController`
 * is on, and the fragment actually attached inside the host. Any two can be right while the third is wrong:
 * the bar can show the start destination because nobody ever told it otherwise, and the controller can sit on
 * the right node while a second transaction has stacked a copy of the screen under it. Each is asserted on
 * every tab, after a bare `recreate()` and after the recreation a reader causes by choosing a language.
 *
 * **Why the probe is a fragment class rather than a view id.** The Profile screen draws one set of views with
 * a profile and another without one, so a view id would make this test depend on what the previous test left
 * in the database. The class the host has attached is the same either way, and naming it is also the stronger
 * claim: it is the screen, not a widget on it.
 *
 * **The two premises this class used to leave unproven, and now asserts.** A matrix run found both, and the
 * second one cost the class that ran after it:
 *
 *  * *The language the run starts in.* The first version handed the language back in `@Before`, before any
 *    window existed, which `CsvLocaleTest` records as a silent no-op — and no `@After`, so the app went away
 *    holding Russian. `Stage14LanguageFramesInstrumentedTest` then failed with "the run asked for 'en' and the
 *    window is resolving 'ru'". The language is not in the app's own storage, and the runner's
 *    `clearPackageData` does not take it back, so a class that changes it has to establish its starting
 *    language on a live window and hand it back on the way out — the same discipline
 *    `LanguageSwitchInstrumentedTest.startFromSystemDefault` sets.
 *  * *That a language change happened at all.* Walking five tabs in Russian and then five in English asks for
 *    the language the app already holds on eight of those ten iterations. A repeated locale is not a change:
 *    nothing recreates, so there is nothing to restore, and the assertions pass while testing a screen that
 *    never moved. The walk now alternates — Russian, English, Russian — so every iteration changes the
 *    language, and each one *asserts* the change landed: a new activity instance, the delegate answering the
 *    wanted tag, the window resolving it. A premise stated is a premise a later edit cannot quietly drop.
 */
@RunWith(AndroidJUnit4::class)
class ProfileNavigationRestorationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the bottom bar never drew",
            waitUntil { bar() != null }
        )
        // After the window, never before it: see the class note on the silent no-op.
        startFromTheDeviceLanguage()
    }

    /**
     * Hand the language back with the window still alive, then close it.
     *
     * The order is the whole point — `CsvLocaleTest` and `CalendarLocaleTest` both carry a note about a run
     * that closed the window first, left the app holding a language, and handed the next instrumented class a
     * screen full of words it was not written for.
     */
    @After
    fun handTheLanguageBack() {
        runCatching { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
        runCatching { scenario.close() }
    }

    /** A tab: its menu item, the destination it navigates to, and the screen it is expected to show. */
    private data class Tab(val menuId: Int, val destinationId: Int, val screen: String, val label: String)

    private val tabs = listOf(
        Tab(R.id.navigation_tasks, R.id.navigation_tasks, "TasksFragment", "Tasks"),
        Tab(R.id.navigation_calendar, R.id.navigation_calendar, "CalendarFragment", "Calendar"),
        Tab(R.id.navigation_categories, R.id.navigation_categories, "CategoriesFragment", "Categories"),
        Tab(R.id.navigation_statistics, R.id.navigation_statistics, "StatisticsFragment", "Statistics"),
        Tab(R.id.navigation_profile, R.id.navigation_profile, "ProfileFragment", "Profile")
    )

    @Test
    fun everyTabIsStillItselfAfterTheActivityIsRebuilt() {
        for (tab in tabs) {
            go(tab)
            val before = state()
            scenario.recreate()
            settle(tab)
            assertRestored(tab, before, "recreate")
        }
    }

    /**
     * The same five tabs through the recreation the reader causes, alternating languages as it goes.
     *
     * Both languages, because the defect was reported on the way into Russian and a fix that only holds in one
     * direction is not a fix — and because the choice has to survive the walk back. Alternating rather than
     * two runs of one language so that every one of these ten iterations is a real change; the note at the top
     * of the class is what this used to be wrong about.
     */
    @Test
    fun everyTabIsStillItselfAfterTheLanguageChanges() {
        for (tab in tabs) {
            for (tag in listOf("ru", "en-US")) {
                go(tab)
                val before = state()
                chooseLanguage(tag)
                settle(tab)
                assertRestored(tab, before, "language '$tag'")
            }
        }
    }

    // ---------------------------------------------------------------- the language

    /**
     * Start from the device's own language, and prove the screen got there.
     *
     * The tab words are the visible proof: an app left Russian by whoever ran last still reports an empty
     * delegate list for a moment after the write, and only the words on the bar say whether the change
     * reached the window.
     */
    private fun startFromTheDeviceLanguage() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        // The message is built *after* the wait: written the other way it would report the state the run
        // started in and call that the state it gave up on.
        val reached = waitUntil {
            AppCompatDelegate.getApplicationLocales().toLanguageTags().isEmpty() &&
                tabLabels() == deviceLanguageLabels()
        }
        assertTrue(
            "handing the language back to the device never reached the window: the delegate answers " +
                "'${languageOnScreen()}' and the bar reads ${tabLabels()}",
            reached
        )
    }

    /**
     * Choose a language and wait for the recreation it causes to land.
     *
     * Three premises, each asserted, because the walk is worthless without them: the activity is a new
     * instance ([assertNotEquals] on identity — no recreation, no restoration to test), the delegate answers
     * the language asked for, and the window resolves it. Returning before the third is true is how a tab
     * press comes to be delivered to a window that is on its way out.
     */
    private fun chooseLanguage(tag: String) {
        val before = activityIdentity()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        val resolved = waitUntil { resolves(tag) }
        assertTrue(
            "the app never resolved '$tag' on screen: the delegate answers '${languageOnScreen()}' and the " +
                "bar reads ${tabLabels()}",
            resolved
        )
        val after = activityIdentity()
        assertNotEquals(
            "asked for '$tag' and the same activity instance is still on screen — nothing recreated, so " +
                "there is no restoration in this iteration to assert anything about",
            before,
            after
        )
        // …and the new window is the one the system is sending input to. Returning before the recreation has
        // settled is how the next tab press comes to be injected into a window that is on its way out: the bar
        // it was drawn on is replaced along with it, and the press arrives nowhere.
        val focused = AppWindow.awaitFocus(scenario)
        assertTrue(
            AppWindow.focusMessage("the screen after choosing '$tag'", AppWindow.focused(scenario)),
            focused
        )
    }

    /** The activity's identity, read on the main thread; a recreation necessarily answers a different one. */
    private fun activityIdentity(): Int {
        var identity = 0
        runCatching { scenario.onActivity { activity -> identity = System.identityHashCode(activity) } }
        return identity
    }

    /** The delegate answers the language asked for *and* the window is resolving it. */
    private fun resolves(tag: String): Boolean {
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != tag) return false
        var resolved = false
        runCatching {
            scenario.onActivity { activity ->
                val wanted = tag.substringBefore('-')
                resolved = activity.resources.configuration.locales[0].language == wanted
            }
        }
        return resolved
    }

    private fun languageOnScreen(): String =
        runCatching { AppCompatDelegate.getApplicationLocales().toLanguageTags() }.getOrDefault("<none>")

    /** The words the bar is drawing, in whatever language the window currently resolves. */
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
     * The five words the bar would show if the app followed the device rather than its own choice.
     *
     * The device's language comes from the *instrumentation* context: the test package has no per-app locale of
     * its own, so its configuration is the system's, while the target app's configuration reflects whatever the
     * app last chose. `LanguageSwitchInstrumentedTest` reads it the same way, and for the same reason.
     */
    private fun deviceLanguageLabels(): List<String> {
        // The *configuration* comes from the instrumentation context and the *resources* from the app's: the
        // test package has no per-app language of its own, so its configuration is still the device's, while
        // the app's reflects whatever a reader chose. `LanguageSwitchInstrumentedTest` reads it this way for the
        // same reason.
        val device = InstrumentationRegistry.getInstrumentation().context
        val configuration = Configuration(device.resources.configuration)
        configuration.setLocale(device.resources.configuration.locales[0])
        val resources = context.createConfigurationContext(configuration).resources
        return listOf(
            R.string.title_tasks, R.string.title_calendar, R.string.title_categories,
            R.string.title_statistics, R.string.title_profile
        ).map { resources.getString(it) }
    }

    // ---------------------------------------------------------------- the reading

    /**
     * Where the screen actually is.
     *
     * [attached] is read as class names on purpose: a duplicated fragment is the failure mode worth catching,
     * and it shows up as the same name twice — which a count alone would hide if one screen were replaced by
     * two copies of a different one.
     */
    private data class State(
        val selection: Int,
        val destination: Int,
        val attached: List<String>,
        val nested: Int
    )

    private fun state(): State {
        var value = State(0, 0, emptyList(), 0)
        scenario.onActivity { activity ->
            val bar = activity.findViewById<BottomNavigationView>(R.id.bottom_navigation)
            val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                as NavHostFragment
            val children = host.childFragmentManager.fragments.filter { it.isAdded }
            value = State(
                selection = bar.selectedItemId,
                destination = host.navController.currentDestination?.id ?: 0,
                attached = children.map { it.javaClass.simpleName },
                nested = children.sumOf { child -> child.childFragmentManager.fragments.count { it.isAdded } }
            )
        }
        return value
    }

    private fun assertRestored(tab: Tab, before: State, how: String) {
        val after = state()
        assertEquals(
            "$how on ${tab.label}: the bar lit '${name(after.selection)}' while the reader was on " +
                "'${name(before.selection)}' — the tab the bar holds cannot be tapped back to anything",
            before.selection,
            after.selection
        )
        assertEquals(
            "$how on ${tab.label}: the controller is on '${name(after.destination)}'",
            tab.destinationId,
            after.destination
        )
        assertEquals(
            "$how on ${tab.label} attached ${after.attached} — the screen the reader left was " +
                "${before.attached}, and a second copy of it carries a second set of observers",
            listOf(tab.screen),
            after.attached
        )
        assertEquals(
            "$how on ${tab.label} nested ${after.nested} fragments inside the screen; it was ${before.nested}",
            before.nested,
            after.nested
        )
    }

    private fun name(id: Int): String =
        if (id == 0) "nothing"
        else runCatching { context.resources.getResourceEntryName(id) }.getOrDefault("$id")

    private fun go(tab: Tab) {
        if (state().selection == tab.menuId && state().attached == listOf(tab.screen)) return
        // Focus before every press, not only at launch: the press that follows a recreation is the one at risk,
        // and this is the site where the run's "never came to the front" was actually decided.
        val focused = AppWindow.awaitFocus(scenario)
        assertTrue(
            AppWindow.focusMessage("the screen before ${tab.label}", AppWindow.focused(scenario)),
            focused
        )
        Espresso.onView(ViewMatchers.withId(tab.menuId)).perform(ViewActions.click())
        settle(tab)
    }

    /**
     * The screen is on: its fragment attached, resumed, and drawn.
     *
     * The message names every one of the three readings plus the window and the language, because "it never
     * came to the front" on its own cannot tell a lost tab press from a duplicated fragment from a screen that
     * attached and never got focus — and the matrix has to say which of those it saw, not just that it saw one.
     */
    private fun settle(tab: Tab) {
        val arrived = waitUntil {
            state().attached == listOf(tab.screen) && resumed() && shown()
        }
        assertTrue(
            "${describe(tab)} — expected ${tab.screen} attached, resumed and drawn after ${tab.label} was " +
                "chosen in '${languageOnScreen()}'",
            arrived
        )
    }

    private fun describe(tab: Tab): String {
        val current = state()
        return "${tab.label}: waited ${WAIT_MILLIS / 1000}s and the app stayed on " +
            "bar='${name(current.selection)}' controller='${name(current.destination)}' " +
            "attached=${current.attached} nested=${current.nested} resumed=${resumed()} " +
            "shown=${shown()} focused=${AppWindow.focused(scenario)} language='${languageOnScreen()}'"
    }

    private fun resumed(): Boolean {
        var resumed = false
        runCatching {
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                resumed = (host as? NavHostFragment)?.childFragmentManager?.fragments
                    ?.filter { it.isAdded }?.any { it.isResumed } == true
            }
        }
        return resumed
    }

    /**
     * The attached fragment has a view the window is actually drawing.
     *
     * `isShown` rather than "not null": a fragment can be attached, resumed and holding a view while the
     * container it sits in is still `GONE`, and a screen nobody can see is not the screen the reader was on.
     */
    private fun shown(): Boolean {
        var shown = false
        runCatching {
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                val child = (host as? NavHostFragment)?.childFragmentManager?.fragments
                    ?.firstOrNull { it.isAdded }
                shown = child?.view?.isShown == true
            }
        }
        return shown
    }

    private fun bar(): BottomNavigationView? {
        var bar: BottomNavigationView? = null
        runCatching {
            scenario.onActivity { activity ->
                bar = activity.findViewById(R.id.bottom_navigation)
            }
        }
        return bar
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }

    private companion object {
        const val WAIT_MILLIS = 45_000L
    }
}
