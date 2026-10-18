package com.taskplanner.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskplanner.R
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

    @Test
    fun statisticsAndBackKeepsTheTaskListUsable() {
        onView(withId(R.id.navigation_statistics)).perform(click())
        onView(withId(R.id.contentGroup)).check(matches(isDisplayed()))

        onView(withId(R.id.navigation_tasks)).perform(click())
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
    }

    @Test
    fun theTasksScreenSurvivesTheActivityBeingRecreated() {
        scenario.recreate()
        onView(withId(R.id.recyclerView)).check(matches(isDisplayed()))
    }
}
