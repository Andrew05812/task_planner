package com.taskplanner.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.taskplanner.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 */
@RunWith(AndroidJUnit4::class)
class ProfileNavigationRestorationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun openTheApp() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue(
            "the bottom bar never drew",
            waitUntil { bar() != null }
        )
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
     * The same five tabs through the recreation the reader causes.
     *
     * Both languages, because the defect was reported on the way into Russian and a fix that only holds in one
     * direction is not a fix — and because the choice has to survive the walk back.
     */
    @Test
    fun everyTabIsStillItselfAfterTheLanguageChanges() {
        for (tag in listOf("ru", "en-US")) {
            for (tab in tabs) {
                go(tab)
                val before = state()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                settle(tab)
                assertRestored(tab, before, "language '$tag'")
            }
        }
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
        Espresso.onView(ViewMatchers.withId(tab.menuId)).perform(ViewActions.click())
        settle(tab)
    }

    /** The screen is on: its fragment attached, resumed, and drawn. */
    private fun settle(tab: Tab) {
        assertTrue(
            "${tab.screen} never came to the front after ${tab.label} was chosen",
            waitUntil {
                state().attached == listOf(tab.screen) && resumed() && shown()
            }
        )
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
