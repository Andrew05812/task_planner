package com.taskplanner.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R

/**
 * Shared scaffolding for the Stage 8a layout tests: a themed inflater, a measured width, and the two
 * questions a laid-out view can answer that an XML file cannot — where it ended up and how tall it is.
 *
 * The theme is not optional. These screens are fragments inside `MainActivity`, which carries
 * `Theme.TaskPlanner`; inflated without it, `?attr/textAppearanceTitleMedium` and the chip and card styles
 * resolve to nothing, and a test would then be measuring the absence of a theme rather than the shape of a
 * screen. Stage 7 found the same trap from the other side: a colour read through the application context
 * answers with the manifest's theme, which is not the theme the view wears.
 */
object Stage8aLayoutHarness {

    /** The narrowest handset the app declares, in pixels, at the density Robolectric's resources carry. */
    const val NARROW_DP = 320

    fun themed(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_TaskPlanner)

    fun inflater(): LayoutInflater = LayoutInflater.from(themed())

    fun inflate(layout: Int): View = inflater().inflate(layout, null, false)

    /**
     * Measure and lay a view out at a given width, the way a RecyclerView row is given its space, and
     * return it so the caller can ask about `left`, `right`, `top`, `bottom` and `height`.
     *
     * Height is `UNSPECIFIED` on purpose: the claim under test is usually "this row is at least 48dp tall"
     * or "the count stayed inside the card", and both are about what the view asked for, not about a box it
     * was handed. Passing an `EXACTLY` height and then asserting the height would compare the test with
     * itself — Stage 6's review caught exactly that tautology in the card tests.
     */
    fun laidOut(view: View, widthDp: Int = NARROW_DP): View {
        val density = view.context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /**
     * The same, with a bounded height — which is how a screen is actually given its space.
     *
     * A row is asked "how tall do you want to be", so `UNSPECIFIED` is the honest measure and `laidOut` uses
     * it. A whole screen inside a `NestedScrollView` is not: it is handed the window's height, and measured
     * with `UNSPECIFIED` a scroll view lays its content out at zero offset, which is how one Stage 8a
     * assertion ended up comparing two views that both reported y=0 and calling it a layout bug.
     */
    fun screenOnScreen(view: View, widthDp: Int = NARROW_DP, heightDp: Int = 640): View {
        val density = view.context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density

    /** Every view under `root`, in traversal order — including the anonymous ones a screen still shows. */
    fun allViews(root: View): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return found
    }

    /**
     * Where a view actually sits on the screen, in pixels from the top of the window.
     *
     * `view.top` is relative to the view's own parent, which makes comparing a header with a field three
     * containers deep meaningless — the first draft of two Stage 8a assertions did exactly that and failed on
     * a layout that was right. This is the same lesson Stage 6 and Stage 7 wrote down about
     * `getGlobalVisibleRect`: measure the thing you are claiming about, in the coordinate space the claim is
     * about.
     */
    fun absoluteTop(view: View): Int {
        // The offsets are added by hand rather than read from `getLocationOnScreen`, because that call needs
        // a window and a test tree that was never attached to one answers 0 for every view in it — which is
        // how this helper's first version managed to "prove" a header and a card heading sat at the same
        // height. Walking `parent.top` is the same arithmetic, done where the data actually is.
        var y = view.top
        var node: Any? = view.parent
        while (node is View) {
            y += node.top
            node = node.parent
        }
        return y
    }

    /** Every view under `root` whose id is set, in traversal order. */
    fun viewsWithIds(root: View): List<View> {
        val found = ArrayList<View>()
        fun walk(view: View) {
            if (view.id != View.NO_ID) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return found
    }

    /** The entry named by `id`, or a message that lists what the inflated tree actually holds. */
    fun require(root: View, id: Int, name: String): View =
        root.findViewById(id) ?: throw AssertionError(
            "$name is not in the inflated tree. Ids present: " +
                viewsWithIds(root).joinToString { context -> "${context.id}#${context.javaClass.simpleName}" }
        )
}
