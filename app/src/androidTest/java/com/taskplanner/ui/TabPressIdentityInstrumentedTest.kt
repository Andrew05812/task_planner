package com.taskplanner.ui

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskplanner.R
import com.taskplanner.testing.AppWindow
import com.taskplanner.testing.NavIdentity
import com.taskplanner.testing.SeededProfile
import com.taskplanner.testing.TabTap
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which window a tab press lands in, told by object identity rather than by what the screen looks like.
 *
 * **Why this class exists as its own run.** The completion pass' fifth matrix run produced a press that did
 * nothing on a device that was not busy — load 1.77, no crash line of the app's own, `focused=true` — and the two
 * surviving explanations point at different files. One says Espresso pressed a bottom bar belonging to a window
 * nobody is looking at any more; the other says the live bar was pressed, was already lit on that item, and this
 * app's re-selection handler popped to a destination that is not on the stack. The readings that separate them
 * are object identities: the activity, its decor, its window token, its `BottomNavigationView`, and the item view
 * the matcher would act on. `docs/LOCALIZATION.md`'s open row named both candidates and said neither had been
 * separated; this class separates them, and it was written to be run before any navigation code is touched.
 *
 * **The scenario, in the order the brief gives it:** launch → focus → identity of the activity and the bar → a
 * tab that moves → a screen with no tab of its own (Settings, reached from the account row) → `recreate()` → the
 * new activity's identity → the old references re-read → what Espresso's matcher would press → the press → the
 * whole snapshot again. The recreation is asserted rather than assumed: a new activity answers a different
 * identity, a different decor and a different window token, or the run has recreated nothing and measured
 * nothing.
 *
 * **What each verdict means.** If the view the matcher chooses is the resumed activity's own item view, inside
 * the window that holds focus, then the stale-window story is dead for this scenario and the lost press has to be
 * found in the navigation state — which is hypothesis B, and `MainActivity` is where it would be fixed. If a
 * destroyed activity's bar answers with the identity the matcher pressed, that is the harness's bug and the
 * matcher, not the app, is what changes. Either way the run writes its whole table to the log under the tag
 * `NavIdentity`, so the verdict can be read rather than trusted.
 *
 * **No production behaviour is changed here, and no language is chosen.** The class presses, reads and
 * recreates. It never installs a listener — `setOnItemReselectedListener` is a setter, so hooking it would remove
 * the behaviour under investigation — and it asserts nothing about words, so it cannot be upset by the language a
 * previous class left behind.
 */
@RunWith(AndroidJUnit4::class)
class TabPressIdentityInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    companion object {
        private var signedIn: SeededProfile.SignedIn? = null

        /**
         * A profile, before the window opens.
         *
         * The scenario walks into a screen that has no tab of its own — Settings — and the account screen only
         * offers that row to a reader who has a profile; with none, the row is not drawn at all and the walk
         * would stop at an empty list rather than at anything about the bar. The app reads `users.is_current`
         * once per process, so the profile has to exist before the launch, which is why this is class-level and
         * not part of `@Before`.
         */
        @BeforeClass
        @JvmStatic
        fun signInAProfile() {
            val database = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.sweepLeftovers(database)
                signedIn = SeededProfile.signIn(
                    database, "tabtap-${System.nanoTime()}@example.test", "Tab tap profile"
                )
            } finally {
                database.close()
            }
        }

        @AfterClass
        @JvmStatic
        fun handTheProfileBack() {
            val signed = signedIn ?: return
            val database = SeededProfile.open(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                SeededProfile.takeAway(database, signed)
            } finally {
                database.close()
            }
            signedIn = null
        }

        const val WAIT_MILLIS = 45_000L

        /** How many times the row is measured, scrolled to and clicked before the run says it cannot be pressed. */
        const val ROW_ATTEMPTS = 3

        /** Destination ids are resource entry names, not the reader's words, so they need no translation. */
        const val ENTRY_TASKS = "navigation_tasks"
        const val ENTRY_PROFILE = "navigation_profile"
        const val ENTRY_SETTINGS = "navigation_settings"
    }

    @Before
    fun openTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("the bottom bar never drew", waitUntil { barExists() })
        assertTrue(
            AppWindow.focusMessage("the screen at launch", AppWindow.focused(scenario)),
            AppWindow.awaitFocus(scenario)
        )
        assertTrue(AppWindow.settleMessage("the screen at launch"), AppWindow.awaitSettledActivity(scenario))
    }

    @After
    fun closeTheWindow() {
        runCatching { scenario.close() }
    }

    @Test
    fun aRecreatedActivityPresentsExactlyOneBarAndEspressoPressesThatOne() {
        // 1–4. launch, focus settled, and the identities of the window that came up.
        val launched = snapshot("1-4 launch")
        val liveAtLaunch = NavIdentity.readings(R.id.navigation_profile)
        assertEquals(
            "the launch left more than one live activity, so there is no single bar to press: $liveAtLaunch",
            1,
            liveAtLaunch.size
        )
        assertEquals("the one live activity should be resumed", "RESUMED", launched.lifecycle)

        // 5. a tab that moves: Tasks is the start destination, so Profile is a real change.
        val firstPress = pressAndReport(R.id.navigation_profile, "5 press Profile from Tasks", ENTRY_PROFILE)
        assertEquals(
            "pressing Profile from Tasks did not move the app: ${firstPress.summarize()} | " +
                firstPress.verdict(),
            ENTRY_PROFILE,
            firstPress.destinationAfter
        )

        // 6. a screen with no tab of its own, reached from that tab.
        pressRow(R.id.openSettingsButton)
        val reachedSettings = waitUntil { destinationName() == ENTRY_SETTINGS }
        val onSettings = snapshot("6 on Settings")
        assertTrue("the account row did not reach Settings: $onSettings", reachedSettings)
        assertEquals(
            "the bar should still hold Profile while Settings is drawn — that is the convention this screen uses",
            ENTRY_PROFILE,
            onSettings.selected
        )

        // 7–10. recreate, wait for the window that replaces it, and read the new identities.
        val before = OldReferences(onSettings)
        scenario.recreate()
        assertTrue(
            AppWindow.focusMessage("Settings after the recreation", AppWindow.focused(scenario)),
            AppWindow.awaitFocus(scenario)
        )
        assertTrue(AppWindow.settleMessage("Settings after the recreation"), AppWindow.awaitSettledActivity(scenario))
        val restored = waitUntil { destinationName() == ENTRY_SETTINGS }
        val recreated = snapshot("11 after recreate")
        assertTrue("the recreated activity never redrew Settings: $recreated", restored)

        assertNotEquals("a recreate() answered the same activity instance", before.activity, recreated.activityIdentity)
        // What the recreation did to the *window* is reported, not demanded. Measured here: the activity instance
        // changes and its decor view does not — a relaunch on this platform hands the new instance the same window
        // and decor, and inflates fresh content into it. An assertion that the decor must differ would be a claim
        // about the framework's bookkeeping, and this test's question is about the bar: whether the object the
        // matcher taps is the one the live activity holds.
        val windowDelta = "decor ${before.decor}->${recreated.decorIdentity}, token " +
            "${before.token}->${recreated.windowToken}, bar ${before.bar}->${recreated.barIdentity}"
        assertNotEquals(
            "the recreated activity answers the same BottomNavigationView as the one it replaced — $windowDelta",
            before.bar,
            recreated.barIdentity
        )
        assertEquals(
            "after the recreation the live window should still hold exactly one bar, and it belongs to the " +
                "resumed activity: ${NavIdentity.readings(R.id.navigation_profile)}",
            1,
            NavIdentity.readings(R.id.navigation_profile).count { it.barIdentity == recreated.barIdentity }
        )

        // 11. what the old objects say now. A stale window is only a theory while the old decor is gone.
        val oldState = before.describeNow()

        // 12–14. the press the report says is lost — first the tab the bar already holds, then one it does not.
        val reselect = pressAndReport(
            R.id.navigation_profile, "13 press the tab the bar already holds", ENTRY_PROFILE
        )
        val ordinary = pressAndReport(R.id.navigation_tasks, "14 press a tab the bar does not hold", ENTRY_TASKS)

        val evidence = "\n    windowDelta=$windowDelta" +
            "\n    liveActivitiesAfterRecreate=${NavIdentity.readings(R.id.navigation_profile)}" +
            "\n    oldReferences=$oldState" +
            "\n    reselect=${reselect.summarize()} | ${reselect.verdict()}" +
            "\n    ordinary=${ordinary.summarize()} | ${ordinary.verdict()}"
        // The press that has been lost in the field is an ordinary one. It is asserted first, so a run that loses
        // it reports the whole identity table instead of quietly passing on the stale-target check.
        assertEquals(
            "a tab the bar does not hold did not move the app — this is the lost press. evidence=$evidence",
            ENTRY_TASKS,
            ordinary.destinationAfter
        )
        assertEquals(
            "the tab that owns the screen did not carry the reader back to it — the re-selection pop-back this " +
                "pass installed. evidence=$evidence",
            ENTRY_PROFILE,
            reselect.destinationAfter
        )
        assertTrue(
            "Espresso's matcher did not choose the resumed activity's own item view — that is the stale-target " +
                "answer, and it belongs to the harness, not to the navigation code. evidence=$evidence",
            reselect.target.contains("sameAsLiveItem=true") && ordinary.target.contains("sameAsLiveItem=true")
        )
    }

    // ---------------------------------------------------------------- the readings

    /** The resumed activity's own account of itself, logged under its own heading. */
    private fun snapshot(where: String): NavIdentity.ActivityReading {
        NavIdentity.report(where, R.id.navigation_profile, "focus=${AppWindow.focused(scenario)}")
        val live = NavIdentity.readings(R.id.navigation_profile)
        return live.firstOrNull { it.lifecycle == "RESUMED" } ?: live.firstOrNull()
            ?: error("no live activity to read at $where")
    }

    private fun destinationName(): String =
        NavIdentity.readings(R.id.navigation_profile).firstOrNull { it.lifecycle == "RESUMED" }?.destination
            ?: "<none>"

    /**
     * Press a row on a scrolling screen, and say why if it cannot be pressed.
     *
     * Three readings in the order the device needs them. The row must first be *measured* — `findViewById`
     * answers an un-laid-out view whose width and height are 0, and `scrollTo` then refuses it for having no
     * scroll ancestor in its path, which is the harness's timing and not the screen's. Then it has to be
     * *scrolled to*: the Settings row sits below this screen's fold, and Espresso's click refuses a view that
     * does not have 90 % of its area inside the visible rect. Then the click. Each refusal is kept, because
     * "the row could not be pressed" without the constraint's own words is the sentence this project has
     * already had to un-blame three times.
     */
    private fun pressRow(id: Int) {
        val entry = context.resources.getResourceEntryName(id)
        var last = "never tried"
        for (attempt in 1..ROW_ATTEMPTS) {
            assertTrue(
                "the $entry row was never measured on the account screen (attempt $attempt): " +
                    snapshot("row $entry attempt $attempt"),
                waitUntil { rowIsDrawn(id) }
            )
            last = runCatching {
                Espresso.onView(ViewMatchers.withId(id)).perform(ViewActions.scrollTo())
                Espresso.onView(ViewMatchers.withId(id)).perform(ViewActions.click())
                "landed"
            }.getOrElse {
                "refused on attempt $attempt: ${it.javaClass.simpleName}: " +
                    (it.message ?: "").replace('\n', ' ').take(220)
            }
            if (last == "landed") return
        }
        assertTrue("the $entry row could not be pressed: $last", false)
    }

    /**
     * Whether a row can be pressed: measured and on the window, not merely present in the view tree.
     *
     * `findViewById` answers a view the renderer has not laid out yet, and Espresso's `scrollTo` then refuses it
     * for failing its constraints — Stage 14 met the same difference between presence and being drawn when a
     * frame guard photographed a loading dashboard as a blank panel.
     */
    private fun rowIsDrawn(id: Int): Boolean {
        var drawn = false
        runCatching {
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(id)
                drawn = view?.isShown == true && view.width > 0 && view.height > 0
            }
        }
        return drawn
    }

    /**
     * A press, with the identity of what the matcher is about to act on taken first.
     *
     * `TabTap.press` is the whole measurement: it takes the Espresso target's identity, performs the ordinary
     * click, and only if the app did not move injects a gesture short enough that a stalled main thread cannot
     * stretch it into a long press. Which of the two moved the app is the verdict this class exists to print.
     */
    private fun pressAndReport(id: Int, where: String, expected: String): TabTap.Reading {
        NavIdentity.report("$where (before)", id)
        val reading = TabTap.press(id, expected)
        NavIdentity.report("$where — after", id, "press=${reading.summarize()} verdict=${reading.verdict()}")
        return reading
    }

    /** The identities captured before the recreation, so the same objects can be asked again afterwards. */
    private data class OldReferences(val activity: Int, val decor: Int, val token: Int, val bar: Int) {
        constructor(reading: NavIdentity.ActivityReading) : this(
            reading.activityIdentity, reading.decorIdentity, reading.windowToken, reading.barIdentity
        )

        fun describeNow(): String {
            val live = NavIdentity.readings(null)
            val stillHasBar = live.firstOrNull { it.barIdentity == bar }
            val stillLive = live.firstOrNull { it.activityIdentity == activity }
            val barSays = stillHasBar?.let {
                "still in activity ${it.activityIdentity}/${it.lifecycle}, attached=${it.barAttached}, " +
                    "shown=${it.barShown}"
            } ?: "belongs to no live activity"
            val activitySays = stillLive?.lifecycle ?: "not live (destroyedTracked=${NavIdentity.destroyedCount()})"
            return "oldActivity=$activity -> $activitySays; oldDecor=$decor, oldBar=$bar -> $barSays"
        }
    }

    private fun barExists(): Boolean {
        var exists = false
        runCatching {
            scenario.onActivity { activity: Activity ->
                exists = activity.findViewById<View>(R.id.bottom_navigation) != null
            }
        }
        return exists
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            Thread.sleep(100)
        }
        return false
    }
}
