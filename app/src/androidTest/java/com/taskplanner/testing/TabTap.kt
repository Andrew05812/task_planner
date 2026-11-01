package com.taskplanner.testing

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher

/**
 * Pressing a bottom tab in a way that reports what actually happened to the gesture.
 *
 * **The measurement that produced this file.** A tab press that "did nothing" survived four matrix runs and a
 * quiet-machine run, and `docs/LOCALIZATION.md` carried two candidate explanations for it — a stale bar from an
 * old window, or a re-selection popping to a destination that is not on the stack. Neither is what the device
 * said. On a clean single-window launch the app's own log wrote, in the middle of the press:
 *
 *     W/MotionEvents: Overslept and turned a tap into a long press
 *
 * and a `Window{… Tooltip}` was created and disposed around it. Espresso's `click()` injects a DOWN, lets the
 * main thread run for the gesture's short span, then injects an UP. When the app's main thread is stalled — this
 * device spent **30 s** to `Displayed com.taskplanner/.ui.MainActivity` on that run, with `Skipped 278 frames`
 * and `Davey! duration=5815ms` on either side of it — the span the platform measures between the two events
 * exceeds `ViewConfiguration.getLongPressTimeout()`, so the gesture *is a long press*, and on a Material
 * navigation item a long press shows the tooltip and never becomes a click. Nothing moved because nothing was
 * clicked: no `PerformException`, because Espresso performed its action perfectly well, and no stale view,
 * because there was only ever one bar. Espresso knows this happened — that warning is its own — and reports the
 * interaction as successful anyway.
 *
 * **The second shape, which the first does not explain.** The matrix of 2026-11-01 14:38 lost two presses with a
 * main thread that answered a round trip in 38 ms and 81 ms — no oversleep, no long press, and the tight gesture
 * refused too, with the bar's *selection* unchanged as well as the destination. A tap that is stretched is one
 * thing; a gesture that never reaches the view is another, and the reading has to tell them apart, because only
 * the second shape can be an app defect. That is what [Reading.tapInjected] and the two focus fields are for.
 *
 * **What [press] does.** The ordinary Espresso click first, because that is the gesture the app is expected to
 * answer. If the destination did not move, it injects a DOWN and an UP as one sequence with a fixed short span,
 * which the platform cannot read as a long press however busy the app's main thread is, and reports whether
 * *that* moved the app. Both answers come back in the reading, so a caller asserts the app moved and says, in the
 * same message, which of the two gestures got there and what the device's long-press timeout was:
 *
 *  * the ordinary click moved it — nothing unusual, `verdict()` says "pressed normally";
 *  * only the sequence moved it — the first gesture was absorbed as a long press. That is the machine's timing,
 *    and the line is labelled `TAP-DEGRADED` so no matrix row can be read as an app defect;
 *  * the sequence was tried and the input system said it did not take it — `INPUT-NOT-DELIVERED`. Nothing was
 *    given the chance to answer, so the app cannot be the one that refused;
 *  * the sequence went into a window that the app itself reports as not holding input focus — `NO-FOCUS`. This is
 *    the shape the contaminated batch of 2026-11-01 had: the refusals were real, and `dumpsys window` said the
 *    notification shade held focus, but nothing in the reading carried that, so the row looked like an app defect.
 *    The press is addressed to whatever is on screen, and it was not this app;
 *  * injected, focused, and still nothing moved, neither the bar's selection nor the destination — `PRESS-REFUSED`,
 *    and that is the only remaining combination that is a production defect worth a RED test.
 *
 * Two of those labels exist because the reader was the guilty party, not the app, and was previously reporting it
 * as the app's refusal: `MOVED-UNOBSERVED` when the settle window polled nothing while the read taken one step
 * later shows the app already on the pressed tab's destination, and `UNREAD` when the polls could not answer at
 * all. `NavIdentity.readings()` answers with an empty list when its own read fails, and eight seconds of empty
 * list is indistinguishable from an app that did not move — unless the window counts them, which is what
 * [Reading.settlePolls] and [Reading.settleUnread] are.
 *
 * **What this file does not do.** It does not retry until something passes, it does not skip the ordinary path,
 * and it does not weaken an assertion: every caller still requires the destination to change, and gets the whole
 * reading in its failure message. A tap is still a tap — this only stops a stalled main thread from silently
 * turning one into a long press and the app from being blamed for the result.
 */
object TabTap {

    private const val TAG = "TabTap"

    /** The span the synthetic gesture claims for itself: far inside every device's long-press timeout. */
    private const val GESTURE_SPAN_MILLIS = 20L

    /** How long to wait for a press to take effect before concluding that it did not. */
    private const val SETTLE_MILLIS = 8_000L

    /** A main-thread round trip at or under this is a thread that is not behind by a gesture's worth. */
    private const val IDLE_LATENCY_MILLIS = 100L

    /** How long to wait for that before pressing anyway. */
    private const val IDLE_WAIT_MILLIS = 20_000L

    /** What the reading carries when the framework will not say its long-press timeout. */
    private const val UNKNOWN_LONG_PRESS_TIMEOUT = -1

    /** Everything one press attempt is worth reporting. */
    data class Reading(
        val tab: String,
        val clickMoved: Boolean,
        val sequenceTried: Boolean,
        val sequenceMoved: Boolean,
        val tapInjected: Boolean?,
        val focusBefore: Boolean,
        val focusAfter: Boolean,
        val selectedBefore: String,
        val selectedAfter: String,
        val destinationBefore: String,
        val destinationAfter: String,
        val settlePolls: Int,
        val settleUnread: Int,
        val target: String,
        val longPressTimeoutMillis: Int,
        val mainThreadLatencyMillis: Long
    ) {
        /** The press succeeded if either gesture moved the app to where the tab owns the screen. */
        val moved: Boolean get() = clickMoved || sequenceMoved

        /** The app had reached the tab's screen by the time the final read happened, just not in the window. */
        val reachedEventually: Boolean get() = destinationAfter == tab || destinationAfter != destinationBefore

        /** The one line a test message or a log should carry. */
        fun summarize(): String =
            "$tab: click moved=$clickMoved, tightTapTried=$sequenceTried moved=$sequenceMoved" +
                (tapInjected?.let { " injected=$it" } ?: "") +
                "; windowFocus $focusBefore->$focusAfter; destination " +
                "$destinationBefore->$destinationAfter; selection $selectedBefore->$selectedAfter; " +
                "settle ${settlePolls} polls ${settleUnread} unread; " +
                "longPressTimeout=${longPressTimeoutMillis}ms; mainThreadRoundTrip=${mainThreadLatencyMillis}ms; " +
                "$target"

        /** How a matrix or a reviewer should read this press. */
        fun verdict(): String = when {
            clickMoved -> "pressed normally"
            sequenceMoved -> "TAP-DEGRADED — the injected tap was stretched past the long-press timeout by a " +
                "busy main thread and read as a long press; the tight gesture navigated"
            // Measured on 2026-11-01 16:27: the settle window reported both gestures as moving nothing, and the
            // read taken one step later said the app had been on the pressed tab's destination all along. A
            // reader that missed the answer is not an app that refused the press, and the row has to say which
            // of the two it is. This is the reader's timing, not the navigation's.
            reachedEventually && settleUnread > 0 ->
                "MOVED-UNOBSERVED — the app's own state reads $destinationAfter after the press, but $settleUnread " +
                    "of $settlePolls settle polls returned no reading, so the window could not see it; the reader " +
                    "is the unproven part here, not the navigation"
            reachedEventually ->
                "MOVED-UNOBSERVED — the destination reads $destinationAfter at the after-read while the settle " +
                    "window said otherwise; the press is not the proven failure"
            tapInjected == false -> "INPUT-NOT-DELIVERED — the system refused to inject the gesture at all, so " +
                "no view was given the chance to answer it"
            !focusBefore || !focusAfter -> "NO-FOCUS — the app's window did not hold input focus while the press " +
                "was made, so whatever the gesture was addressed to, it was not this window"
            settleUnread > 0 -> "UNREAD — $settleUnread of $settlePolls settle polls could not read the app at " +
                "all, so 'it did not move' is not established by this press"
            else -> "PRESS-REFUSED — the gesture was injected into a focused window that was readable " +
                "throughout, and neither the bar's selection nor the destination changed"
        }
    }

    /**
     * Press a tab and report both gestures.
     *
     * [expected] is the destination entry name the press should produce: for a tab press it is the tab's own
     * entry name, and for the re-selection pop-back it is the tab the bar already holds.
     *
     * The main thread is waited for first, because that is the whole cause: Espresso's click injects a DOWN,
     * pumps the main thread for the gesture's span, then injects an UP, and a main thread that is 350 frames
     * behind turns that span into a long press. A round trip into an idle main thread takes tens of
     * milliseconds; the same round trip into a busy one takes seconds, which is what makes it the reading to
     * take — and it is reported whether or not the press then succeeds, so a run on a slow device is visibly a
     * run on a slow device.
     */
    fun press(itemId: Int, expected: String): Reading {
        val latency = awaitIdleMainThread()
        val before = live(itemId)
        val timeout = longPressTimeoutMillis()
        val target = NavIdentity.probeEspressoTarget(itemId, before?.itemIdentity ?: 0)
        var lastSettle = SettleCount()
        val clickMoved = runCatching {
            Espresso.onView(ViewMatchers.withId(itemId)).perform(ViewActions.click())
            val (reached, count) = settle(itemId, expected)
            lastSettle = count
            reached
        }.getOrDefault(false)
        var sequenceTried = false
        var sequenceMoved = false
        var injected: Boolean? = null
        if (!clickMoved) {
            sequenceTried = true
            val tight = TightTap()
            sequenceMoved = runCatching {
                Espresso.onView(ViewMatchers.withId(itemId)).perform(tight)
                injected = tight.injected
                val (reached, count) = settle(itemId, expected)
                lastSettle = count
                reached
            }.getOrDefault(false)
            if (injected == null) injected = tight.injected
        }
        val after = live(itemId)
        val reading = Reading(
            tab = entry(itemId),
            clickMoved = clickMoved,
            sequenceTried = sequenceTried,
            sequenceMoved = sequenceMoved,
            tapInjected = injected,
            focusBefore = before?.decorFocus == true,
            focusAfter = after?.decorFocus == true,
            selectedBefore = before?.selected ?: "<none>",
            selectedAfter = after?.selected ?: "<none>",
            destinationBefore = before?.destination ?: "<none>",
            destinationAfter = after?.destination ?: "<none>",
            settlePolls = lastSettle.polls,
            settleUnread = lastSettle.unread,
            target = target,
            longPressTimeoutMillis = timeout,
            mainThreadLatencyMillis = latency
        )
        Log.i(TAG, reading.summarize() + " | " + reading.verdict())
        return reading
    }

    /**
     * DOWN and UP injected as one sequence with a fixed span.
     *
     * `SOURCE_TOUCHSCREEN` is not decoration: without it `MotionEvent.obtain` describes a pointer device the app
     * answers differently, which Stage 7 measured on this same bar. The controller pumps the main thread
     * afterwards so the navigation transaction gets its chance, and nothing here waits for the app to be idle
     * *between* the two events — that wait is exactly what the ordinary path turns into a long press when the
     * main thread is busy.
     *
     * [injected] is kept on the instance and not only in the log, because it separates the two refusals the
     * completion pass could not tell apart: a gesture the input system declined to deliver, and a gesture it
     * delivered to a view that then did nothing.
     */
    private class TightTap : ViewAction {
        var injected: Boolean? = null
            private set

        override fun getConstraints(): Matcher<View> = ViewMatchers.isDisplayed()
        override fun getDescription(): String =
            "tap with DOWN and UP injected as one $GESTURE_SPAN_MILLIS ms sequence, so a busy main thread " +
                "cannot stretch the gesture past the long-press timeout"

        override fun perform(uiController: UiController, view: View) {
            val where = IntArray(2).also { view.getLocationOnScreen(it) }
            val x = where[0] + view.width / 2f
            val y = where[1] + view.height / 2f
            val down = SystemClock.uptimeMillis()
            val events = listOf(
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0),
                MotionEvent.obtain(down, down + GESTURE_SPAN_MILLIS, MotionEvent.ACTION_UP, x, y, 0)
            )
            events.forEach { it.source = InputDevice.SOURCE_TOUCHSCREEN }
            val result = uiController.injectMotionEventSequence(events)
            injected = result
            events.forEach { it.recycle() }
            uiController.loopMainThreadForAtLeast(200L)
            Log.i(TAG, "tight tap at [$x,$y] injected=$result span=$GESTURE_SPAN_MILLIS ms")
        }
    }

    /**
     * Wait until the app's main thread answers a round trip quickly, and report how long it took.
     *
     * A `runOnMainSync` that returns in tens of milliseconds is an idle main thread; one that takes seconds is
     * the state in which Espresso's tap stops being a tap. Returns the best latency seen, which is
     * [IDLE_LATENCY_MILLIS] or below when the wait succeeded and larger when it gave up — and giving up does not
     * stop the press, because the run still has to say what happened.
     */
    private fun awaitIdleMainThread(): Long {
        val deadline = System.currentTimeMillis() + IDLE_WAIT_MILLIS
        var best = Long.MAX_VALUE
        while (System.currentTimeMillis() < deadline) {
            val sample = runCatching {
                val started = SystemClock.uptimeMillis()
                InstrumentationRegistry.getInstrumentation().runOnMainSync { }
                SystemClock.uptimeMillis() - started
            }.getOrDefault(Long.MAX_VALUE)
            best = minOf(best, sample)
            if (best <= IDLE_LATENCY_MILLIS) return best
            Thread.sleep(100)
        }
        return best
    }

    /**
     * The device's long-press timeout, read the way a test is allowed to read it.
     *
     * `ViewConfiguration.getLongPressTimeout()` is only public from API 29 and `android.R.integer
     * .config_longPressTimeout` is not public at all, so this asks the object reflectively and says so in the
     * reading when it cannot get an answer. The number is reported, never assumed: it is the quantity that turns
     * "the tap did nothing" into "the tap was stretched into a long press", and a default in its place would be
     * a guess dressed as a measurement.
     */
    private fun longPressTimeoutMillis(): Int = runCatching {
        val configuration = ViewConfiguration.get(context())
        ViewConfiguration::class.java.getMethod("getLongPressTimeout").invoke(configuration) as Int
    }.getOrDefault(UNKNOWN_LONG_PRESS_TIMEOUT)

    private fun settle(itemId: Int, expected: String): Pair<Boolean, SettleCount> {
        val count = SettleCount()
        val deadline = System.currentTimeMillis() + SETTLE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            count.polls++
            val reading = live(itemId)
            if (reading == null) count.unread++ else if (reading.destination == expected) return true to count
            Thread.sleep(100)
        }
        return (live(itemId)?.destination == expected) to count
    }

    /**
     * What one settle window saw, in its own terms: how many times it asked, and how many of those asks could
     * not be answered. `NavIdentity.readings()` answers with an empty list when the read itself fails, and an
     * empty list through eight seconds of polling looks exactly like an app that did not move. Counting the
     * two apart is what lets a row say "the reader could not see" instead of "the navigation refused".
     */
    private class SettleCount {
        var polls: Int = 0
        var unread: Int = 0
    }

    /**
     * The resumed activity whose bar the press should move.
     *
     * A `recreate()` leaves the outgoing and the incoming activity in the registry at the same moment, and the
     * one the reader takes decides what "the app did not move" means: the old activity's controller really does
     * still hold the old destination. So the bar-owning activity is preferred, and a caller that wants the
     * whole picture asks [NavIdentity.readings] directly rather than this.
     */
    private fun live(itemId: Int): NavIdentity.ActivityReading? =
        NavIdentity.readings(itemId).filter { it.lifecycle == NavIdentity.resumedStage }
            .firstOrNull { it.destination != "none" }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun entry(id: Int): String =
        runCatching { context().resources.getResourceEntryName(id) }.getOrDefault("$id")
}
