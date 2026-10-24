package com.taskplanner.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskplanner.R
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens the user actually touches, started from the real DI graph on a device. The JVM suite
 * covers what the list renders (TasksViewModelTest); this covers that the fragments mount at all,
 * that the bottom navigation switches between them, and that no view id drifted away.
 *
 * Runs on a device or emulator only: `connectedDebugAndroidTest`. It is compile-checked in CI but
 * not executed there, because the runner needs an Android system.
 */
@RunWith(AndroidJUnit4::class)
class TasksScreenInstrumentedTest {

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun startTheApp() {
        // The tasks screen is the start destination of the navigation graph.
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun stopTheApp() {
        scenario.close()
    }

    @Test
    fun theTasksScreenOpensWithItsListAndItsAddButton() {
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
        onView(withId(R.id.fabAddTask)).check(matches(isDisplayed()))
    }

    @Test
    fun theStatusFilterChipsAreOnTheTaskScreen() {
        onView(withId(R.id.chipTodo)).check(matches(isDisplayed()))
        onView(withId(R.id.chipInProgress)).check(matches(isDisplayed()))
        onView(withId(R.id.chipPaused)).check(matches(isDisplayed()))
    }

    /**
     * The round trip a reader takes when they glance at their numbers and come back.
     *
     * The middle assertion used to name `R.id.contentGroup`, a container that exists on Profile
     * (`fragment_profile.xml:19`) and on the category screen (`fragment_category_detail.xml:15`) but
     * not on Statistics at all — `grep -c contentGroup app/src/main/res/layout/fragment_statistics.xml`
     * is `0`. The test therefore could not pass, and could not be seen failing either: CI runs
     * `assembleDebugAndroidTest` and never instruments, so the suite only ever compiled. It is fixed by
     * asserting what the name promises on the screen the test actually navigates to, and by naming
     * more than the list on the way back: "usable" means the screen came home whole, which a bare
     * `recyclerView` check would not have caught.
     */
    @Test
    fun statisticsAndBackKeepsTheTaskListUsable() {
        onView(withId(R.id.navigation_statistics)).perform(click())
        // The period rail is Statistics' own chrome: `statisticsPeriodScroll` is declared in
        // fragment_statistics.xml:11 and the chips inside it are added by `StatisticsFragment.setupChips()`
        // from `WorkPeriod.entries`, so the rail has content whatever the data looks like. The dashboard
        // body deliberately is not the subject here — `DashboardRenderer:83` sets
        // `dashboardScroll.isVisible = snapshot != null && !snapshot.isEmpty`, so asserting it would tie
        // this navigation test to whether the profile happens to have anything in the window.
        onView(withId(R.id.statisticsPeriodScroll)).check(matches(isDisplayed()))
        onView(withId(R.id.statisticsPeriodChips)).check(matches(isDisplayed()))

        onView(withId(R.id.navigation_tasks)).perform(click())
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
        // The filter rail is the rest of the Tasks screen. A fragment recreated by the navigation
        // round trip can restore its list and lose its chrome; only the chips prove the screen is
        // the one the reader left.
        onView(withId(R.id.chipTodo)).check(matches(isDisplayed()))
        onView(withId(R.id.chipInProgress)).check(matches(isDisplayed()))
        onView(withId(R.id.chipPaused)).check(matches(isDisplayed()))

        // Presence is inflation; a tap is behaviour. `TasksFragment` never toggles the visibility of the
        // list or the chip group, so the three checks above would still pass on a screen whose filter
        // listener was never wired. This taps the filter the reader came back to use and reads what the
        // group did about it: the chip the tap landed on is checked and the two that share its group are
        // not, which is single selection surviving a navigation round trip. What the checked chip then
        // *does* — `viewModel.setStatusFilter(TaskStatus.TODO)` — is a JVM concern and is asserted there
        // (`TasksViewModelTest`); Espresso cannot see the ViewModel from this side of the process.
        onView(withId(R.id.chipTodo)).perform(click())
        onView(withId(R.id.chipTodo)).check(matches(isChecked()))
        onView(withId(R.id.chipInProgress)).check(matches(not(isChecked())))
        onView(withId(R.id.chipPaused)).check(matches(not(isChecked())))
    }

    @Test
    fun theTasksScreenSurvivesTheActivityBeingRecreated() {
        scenario.recreate()
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
    }
}
