package com.taskplanner.testing

import android.app.Activity
import android.content.res.Resources
import android.os.IBinder
import android.util.Log
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.taskplanner.R
import org.hamcrest.Matcher

/**
 * Who is who, when a tab press does nothing.
 *
 * **The question this answers.** Two explanations of the lost press survived four matrices and a quiet-machine
 * run, and they demand opposite fixes. (A) Espresso's `withId(navigation_categories)` matched a bottom bar
 * belonging to a window that is no longer the one on screen — the tap is delivered, to a view nobody is looking
 * at, and the live app never moves; the fix belongs in the harness. (B) The live bar was tapped, its own
 * selection was already the pressed item, so Material treated it as a re-selection, this app's
 * `setOnItemReselectedListener` called `popBackStack(itemId, false)`, that destination is not on the stack, and
 * the press correctly did nothing; the fix belongs in `MainActivity`. Reading the screen through
 * `ActivityScenario.onActivity` cannot separate them, because it reports where the *live* activity thinks the
 * reader is, and hypothesis A is precisely a difference between the live activity and the window being tapped.
 *
 * **So identity is read, not inferred.** Every object that could be the stale one gets an
 * `System.identityHashCode`: the activity, its window, its decor view, the decor's application window token, the
 * `BottomNavigationView`, its menu, and the item view Espresso's matcher would press. A press is then described
 * by whether the item it landed on is *the same object* the resumed activity holds, and how many live and
 * destroyed activities hold a bar of their own. `applicationWindowToken` is in the set because a decor can be
 * attached, drawn and still belong to a window the system has finished with — the token is what the window
 * manager actually keys on.
 *
 * **The accessibility half is the system's opinion, gathered without trusting Espresso at all.**
 * `uiAutomation.rootInActiveWindow` is the tree the input pipeline is pointed at (Stage 7 established that
 * `View.createAccessibilityNodeInfo()` is not the sealed tree a service sees), and counting nodes with the
 * item's view id inside it says whether the focused window contains one, none or two copies. If the view Espresso
 * chooses is not in that window, hypothesis A is proven and no production code should be touched.
 *
 * **What it deliberately does not do.** It never installs, replaces or wraps a listener:
 * `BottomNavigationView.setOnItemReselectedListener` is a setter, so a test that hooked it would remove the
 * behaviour it is trying to observe. The re-selection path is inferred from the readings that are honest — the
 * bar's `selectedItemId` before the press, and whether it moved after — not from a listener the test added.
 * Nothing here changes the app either: every method reads.
 */
object NavIdentity {

    private const val TAG = "NavIdentity"

    /** One live activity's own account of itself, its window, its bar and its navigation state. */
    data class ActivityReading(
        val activityIdentity: Int,
        val activityClass: String,
        val lifecycle: String,
        val finishing: Boolean,
        val destroyed: Boolean,
        val windowIdentity: Int,
        val decorIdentity: Int,
        val decorAttached: Boolean,
        val windowToken: Int,
        val decorFocus: Boolean,
        val barIdentity: Int,
        val barShown: Boolean,
        val barAttached: Boolean,
        val barBounds: String,
        val parentIdentity: Int,
        val menuIdentity: Int,
        val selected: String,
        val destination: String,
        val previous: String,
        val attachedFragments: List<String>,
        val nestedFragmentManagerEntries: Int,
        val itemIdentity: Int,
        val itemChecked: Boolean
    ) {
        /** One line, in the order §3.2 asks for it, so a log can be read as a table. */
        fun line(): String =
            "activity=$activityIdentity/$activityClass state=$lifecycle finishing=$finishing " +
                "destroyed=$destroyed | window=$windowIdentity decor=$decorIdentity attached=$decorAttached " +
                "token=$windowToken focus=$decorFocus | bar=$barIdentity parent=$parentIdentity menu=$menuIdentity " +
                "shown=$barShown barAttached=$barAttached bounds=$barBounds selected=$selected | " +
                "item=$itemIdentity checked=$itemChecked | destination=$destination previous=$previous " +
                "fragments=$attachedFragments nestedEntries=$nestedFragmentManagerEntries"
    }

    /**
     * Everything the resumed, started and created activities of this process say about the bar.
     *
     * Destroyed instances are listed separately and on purpose: a bar whose activity is destroyed but whose
     * window is still attached to a view root is exactly the object hypothesis A predicts, and a reading that
     * only walked the resumed activities would be blind to it.
     *
     * **Read on the app's own thread.** A `FragmentManager` lookup and a view's geometry are not safe to touch
     * from the instrumentation thread, and the existing classes already read them through
     * `ActivityScenario.onActivity`; `runOnMainSync` is the same thing without needing a scenario, because this
     * has to walk activities the scenario is no longer attached to. Every caller here lives on the
     * instrumentation thread.
     */
    fun readings(pressedItemId: Int? = null): List<ActivityReading> {
        var found: List<ActivityReading> = emptyList()
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val alive = LinkedHashMap<Activity, String>()
                // Insertion order is the story: RESUMED first, then the activities that are alive but not in
                // front, so a caller that takes `firstOrNull` takes the one the reader is looking at.
                runCatching {
                    monitor.getActivitiesInStage(Stage.RESUMED).forEach { alive[it] = "RESUMED" }
                    monitor.getActivitiesInStage(Stage.STARTED).forEach { alive.putIfAbsent(it, "STARTED") }
                    monitor.getActivitiesInStage(Stage.CREATED).forEach { alive.putIfAbsent(it, "CREATED") }
                }
                found = alive.map { (activity, stage) -> read(activity, stage, pressedItemId) }
            }
        }
        return found
    }

    /** Activities the framework still holds references to after their `onDestroy`. */
    fun destroyedCount(): Int {
        var count = -1
        runCatching {
            count = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.DESTROYED).size
        }
        return count
    }

    fun read(activity: Activity, stage: String, pressedItemId: Int?): ActivityReading {
        val res = activity.resources
        val decor = activity.window.decorView
        val bar = decor.findViewById<BottomNavigationView>(R.id.bottom_navigation)
        // Only a `FragmentActivity` has the host this app puts its nav controller in; the instrumentation's own
        // empty activities are still worth listing, and they answer "none" for the navigation half.
        val host = (activity as? FragmentActivity)?.supportFragmentManager
            ?.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val controller = host?.navController
        val children = host?.childFragmentManager?.fragments?.filter { it.isAdded }.orEmpty()
        val item = pressedItemId?.let { id -> bar?.findViewById<View>(id) }
        val location = IntArray(2)
        bar?.getLocationOnScreen(location)
        return ActivityReading(
            activityIdentity = System.identityHashCode(activity),
            activityClass = activity.javaClass.simpleName,
            lifecycle = stage,
            finishing = activity.isFinishing,
            destroyed = activity.isDestroyed,
            windowIdentity = System.identityHashCode(activity.window),
            decorIdentity = System.identityHashCode(decor),
            decorAttached = decor.isAttachedToWindow,
            windowToken = System.identityHashCode(decor.applicationWindowToken),
            decorFocus = decor.hasWindowFocus(),
            barIdentity = System.identityHashCode(bar),
            barShown = bar?.isShown == true,
            barAttached = bar?.isAttachedToWindow == true,
            barBounds = bar?.let { "[${location[0]},${location[1]}][${it.width}x${it.height}]" } ?: "none",
            parentIdentity = System.identityHashCode(bar?.parent),
            menuIdentity = System.identityHashCode(bar?.menu),
            selected = bar?.let { name(res, it.selectedItemId) } ?: "none",
            destination = controller?.currentDestination?.let { name(res, it.id) } ?: "none",
            previous = controller?.previousBackStackEntry?.destination?.let { name(res, it.id) } ?: "none",
            attachedFragments = children.map { it.javaClass.simpleName },
            nestedFragmentManagerEntries = host?.childFragmentManager?.backStackEntryCount ?: -1,
            itemIdentity = System.identityHashCode(item),
            itemChecked = pressedItemId?.let { bar?.menu?.findItem(it)?.isChecked == true } ?: false
        )
    }

    /**
     * What Espresso's `withId(id)` would act on, and whether that object is the live activity's own item view.
     *
     * The probe is a `ViewAction` that records and changes nothing, run before the real press. Espresso is free
     * to refuse it — no view, or two in one root — and either refusal is the measurement, so the outcome is
     * returned as text rather than allowed to end the run before the rest of the snapshot is taken.
     */
    fun probeEspressoTarget(id: Int, liveItemIdentity: Int): String {
        var seen = "no action ran"
        val outcome = runCatching {
            Espresso.onView(ViewMatchers.withId(id)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = ViewMatchers.withId(id)
                override fun getDescription(): String = "record which view the matcher would press"
                override fun perform(uiController: UiController, view: View) {
                    val location = IntArray(2).also { view.getLocationOnScreen(it) }
                    seen = "target=${System.identityHashCode(view)} root=${System.identityHashCode(view.rootView)} " +
                        "token=${System.identityHashCode(view.applicationWindowToken)} " +
                        "attached=${view.isAttachedToWindow} shown=${view.isShown} " +
                        "loc=[${location[0]},${location[1]}] clickable=${view.isClickable}"
                }
            })
            "performed"
        }
        val targetIdentity = Regex("target=(-?\\d+)").find(seen)?.groupValues?.get(1)?.toIntOrNull()
        return "$seen | outcome=${outcome.getOrElse { "refused: ${it.javaClass.simpleName}: ${it.message}" }} " +
            "sameAsLiveItem=${targetIdentity != null && targetIdentity == liveItemIdentity} " +
            "accessibilityCopies=${accessibilityCopies(id)}"
    }

    /**
     * How many nodes with this id the window the system is sending input to actually contains.
     *
     * Read through the accessibility tree rather than through Espresso, so a disagreement between the two is a
     * finding about the matcher and not about the app.
     */
    fun accessibilityCopies(id: Int): String {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = runCatching {
            "${target.packageName}/${target.resources.getResourceTypeName(id)}/${target.resources.getResourceEntryName(id)}"
        }.getOrDefault("<unresolvable>")
        val root = runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        }.getOrNull() ?: return "no active window"
        val copies = runCatching { root.findAccessibilityNodeInfosByViewId(entry).size }.getOrDefault(-1)
        val activePackage = root.packageName?.toString() ?: "<none>"
        root.recycle()
        return "$copies of $entry in $activePackage"
    }

    /** The snapshot as one block: a header, one line per live activity, and the destroyed count. */
    fun report(where: String, pressedItemId: Int?, extra: String = ""): String {
        val block = buildString {
            appendLine("SNAPSHOT $where")
            if (extra.isNotEmpty()) appendLine("    $extra")
            readings(pressedItemId).forEach { appendLine("    ${it.line()}") }
            append("    destroyedTracked=${destroyedCount()}")
        }
        Log.i(TAG, block)
        return block
    }

    private fun name(res: Resources, id: Int): String =
        if (id == 0) "nothing" else runCatching { res.getResourceEntryName(id) }.getOrDefault("$id")

    /** The token a test would compare against, for callers that only need to know "same window or not". */
    fun tokenOf(activity: Activity): IBinder? = runCatching { activity.window.decorView.applicationWindowToken }.getOrNull()
}
