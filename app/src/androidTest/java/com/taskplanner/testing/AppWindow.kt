package com.taskplanner.testing

import android.app.Activity
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Reading whether the app's window is the one the system is sending input to.
 *
 * **Why this exists as a shared reading rather than a per-class trick.** Three instrumented classes failed in
 * one Stage 14 completion matrix, and all three said the same impossible thing about the app: a tab was pressed
 * and neither the destination nor the bottom bar's selection moved (`pressing navigation_profile left the app
 * on navigation_tasks, and the bar's selection is navigation_tasks`). Espresso's `click()` does not fail when
 * that happens — it waits for the window to be *idle* and the view to be *displayed*, and then injects a tap at
 * the view's coordinates. Whether the tap *arrives* is the window focus's decision, and a drawn-but-unfocused
 * app window lets the tap land on whatever window the system is holding instead. The first version of this
 * reading is `Stage14LanguageFramesInstrumentedTest.waitForWindowFocus`, and its note is the measured one: a
 * window that draws without taking focus passes `findViewById`, and the first press of the walk then dies ten
 * seconds later with `RootViewWithoutFocusException` — three configurations of it, on a day when the emulator's
 * Bluetooth stack was crash-looping every two minutes and `com.taskplanner` appeared nowhere in the crash
 * buffer. This object is that reading in one place, so a class that taps can say which of the two it is
 * looking at.
 *
 * **What it is not.** It is not a retry and not a longer timeout. A lost tap stays lost however long the wait
 * is — the matrix that failed was running on a device with a load average of 16.8 on four cores, and the two
 * fixes that follow from that are a quieter device and a test that names the condition. A wait that expires
 * with `ENVIRONMENT` in its message and a wait that expires with `focused=true` are different answers, and only
 * the second one is allowed to be read as a screen defect.
 */
object AppWindow {

    /** Whether the app's decor view currently holds window focus. False while no activity is alive. */
    fun <A : Activity> focused(scenario: ActivityScenario<A>): Boolean {
        var focused = false
        runCatching {
            scenario.onActivity { activity -> focused = activity.window.decorView.hasWindowFocus() }
        }
        return focused
    }

    /**
     * Wait for the app's window to hold focus, sending one wake-up key on the way.
     *
     * The key is sent because the screen can simply be asleep — which is indistinguishable from an unfocused
     * window from inside the app. Nothing else is tried, because nothing else a test may do will take focus
     * away from whatever system window is holding it; a second `ActivityScenario.launch` or a blind `pressKey`
     * loop would only be a way of making the run longer without making it mean more.
     */
    fun <A : Activity> awaitFocus(scenario: ActivityScenario<A>, withinMillis: Long = WAIT_MILLIS): Boolean {
        if (focused(scenario)) return true
        runCatching {
            InstrumentationRegistry.getInstrumentation().sendKeySync(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP)
            )
        }
        val deadline = System.currentTimeMillis() + withinMillis
        while (System.currentTimeMillis() < deadline) {
            if (focused(scenario)) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * Wait for the activity on screen to be the one that stays.
     *
     * Focus says the window is receiving input now; it says nothing about the next moment. A per-app language
     * change is applied by recreating activities, and AppCompat can hold a change to apply to the next activity
     * created — so a window that is drawn, focused and about to be replaced is a real state, and a tap injected
     * into it dies with it. The reading that separates the two is the activity's identity held steady: ask twice
     * a second apart, and only a tap on an instance that is still the same instance is a tap on the screen the
     * reader is looking at.
     *
     * Returns false when the identity keeps changing, which is the app or the framework recreating the activity
     * under the test — a fact worth a message, not a thing to sleep off.
     */
    fun <A : Activity> awaitSettledActivity(
        scenario: ActivityScenario<A>,
        holdMillis: Long = 1_500L,
        withinMillis: Long = SETTLE_DEADLINE_MILLIS
    ): Boolean {
        val deadline = System.currentTimeMillis() + withinMillis
        while (System.currentTimeMillis() < deadline) {
            val first = identity(scenario)
            Thread.sleep(holdMillis)
            val second = identity(scenario)
            if (first != 0 && first == second && focused(scenario)) return true
        }
        return false
    }

    /** The current activity's identity, or 0 when no activity answers. */
    fun <A : Activity> identity(scenario: ActivityScenario<A>): Int {
        var identity = 0
        runCatching { scenario.onActivity { activity -> identity = System.identityHashCode(activity) } }
        return identity
    }

    /**
     * The message for a tap that never arrived.
     *
     * It names the environment rather than a screen so the next reader looks at the device before they look at
     * the code — and it carries the focus reading, because "the tab did nothing" with `focused=true` is a
     * defect in the app and belongs in a different file than this one.
     */
    fun focusMessage(where: String, focused: Boolean): String =
        "ENVIRONMENT, not a screen defect: the app drew $where but its window never took focus, so a tap " +
            "injected at its coordinates does not reach it. Read the device's load and crash buffer before " +
            "believing anything else this run says — focused=$focused."

    /** The message for a window that would not stop being replaced. */
    fun settleMessage(where: String): String =
        "the activity showing $where kept being recreated: its identity did not hold for one second, so no " +
            "tap injected into it can be expected to survive. Either the app is recreating itself in a loop " +
            "or a pending locale change is being applied over and over — neither is a test that should press on."
}

private const val WAIT_MILLIS = 30_000L
private const val SETTLE_DEADLINE_MILLIS = 20_000L
