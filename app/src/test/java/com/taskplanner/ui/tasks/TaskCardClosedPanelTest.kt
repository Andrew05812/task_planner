package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The panel is behind the card, and the user is never meant to see it until the card moves.
 *
 * It used to be visible on every row without any interaction: `actionPanel` sat at `gravity="end"` with
 * no margins while the card above it carried 8dp horizontally and 4dp vertically, so a blue/red band
 * showed along the top of the list and a red column down the right side. The audit photographs show it on
 * all nine rows.
 *
 * This measures the two rectangles on a laid-out row and requires the panel's to be inside the card's
 * while closed. It says nothing about the reveal, which the next test covers, and nothing about the card's
 * contents, which stage 4 rewrites.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardClosedPanelTest {

    private lateinit var context: Context
    private lateinit var parent: ViewGroup

    @Before fun setUp() {
        context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner
        )
        parent = FrameLayout(context)
    }

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()

    private fun row(): View {
        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {},
            onDeleteRequest = {}, onStart = {}, onPause = {}, onResume = {}, onComplete = {},
            onWorkToggle = {}, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.submitList(
            listOf(
                TasksListItem.TaskRow(
                    Task(
                        id = 1, uuid = "row-geometry", title = "Write the plan",
                        description = "", userId = 1, status = TaskStatus.TODO
                    ),
                    category = null, isSelected = false
                )
            )
        )
        awaitUntil("the row to be diffed") { adapter.itemCount == 1 }
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder.itemView.also { view ->
            // Two passes, because one cannot tell a match_parent child anything: ViewGroup's
            // getChildMeasureSpec maps MATCH_PARENT under an UNSPECIFIED parent to size 0, so with a single
            // UNSPECIFIED measure the action panel is laid out ZERO high and every vertical assertion
            // below passes on an empty rectangle. The first pass learns the row's own height; the second
            // gives it EXACTLY, which is what RecyclerView does when it measures an item.
            val widthSpec = View.MeasureSpec.makeMeasureSpec(393.dp(), View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val rowHeight = view.measuredHeight
            view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(rowHeight, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 393.dp(), rowHeight)
        }
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun `a closed row shows no part of the action panel`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)

        assertEquals("the card does not move while nothing is swiping", 0f, card.translationX, 0f)
        assertTrue("the panel is a real rectangle in this fixture: ${panel.height}px tall",
            panel.height >= 40.dp())
        assertTrue(
            "panel right ${panel.right} must not pass card right ${card.right}",
            panel.right <= card.right
        )
        assertTrue("panel left ${panel.left} must hide behind card left ${card.left}", panel.left >= card.left)
        assertTrue("panel top ${panel.top} must hide behind card top ${card.top}", panel.top >= card.top)
        assertTrue(
            "panel bottom ${panel.bottom} must hide behind card bottom ${card.bottom}",
            panel.bottom <= card.bottom
        )
    }

    @Test fun `the reveal distance is exactly the panel's own width`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)
        assertEquals("two 76dp buttons, which is what the drag measures", 152.dp(), panel.width)
        assertTrue("and the panel has height to reveal: ${panel.height}px", panel.height >= 40.dp())
        card.translationX = -panel.width.toFloat()
        // Equality, not <=. The earlier draft asserted `panel.left <= card.right - panel.width`, which is
        // the same statement as the closed-row test's `panel.right <= card.right` — panel.left IS
        // panel.right minus panel.width — so it proved nothing new, and it was satisfied pre-fix, when a
        // card sliding by 152dp still left 8dp of panel exposed down the right edge. Landing exactly on
        // the panel's left edge is the invariant the drag depends on.
        assertEquals(
            "sliding by panel.width reveals the panel exactly, no early strip, no shortfall",
            panel.left, (card.right + card.translationX).toInt()
        )
        assertTrue("and nothing of the panel sticks past the row", panel.right <= row.width)
    }
}
