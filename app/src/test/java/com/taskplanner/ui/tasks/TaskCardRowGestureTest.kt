package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A gesture dispatched the way the screen dispatches it — at the row, not at the card.
 *
 * The reveal has never been reachable by touch. [TasksAdapter.TaskViewHolder] answers `ACTION_DOWN` with
 * `false`, and the card it is listening on is neither clickable nor long-clickable — measured on the
 * device through the real adapter: `clickable=false longClickable=false` — so `View.onTouchEvent`
 * declines it too. A child that declines the down is never made the parent's touch target, so it is never
 * handed `ACTION_MOVE` or `ACTION_UP` for that gesture. Everything the audit photographs then follows
 * from one line: the drag cannot start (translationX stays 0.0 for every interior start point, while the
 * same injected stream moves a listener that does consume the down to -345px), a tap cannot land (so
 * `onTaskClick` never runs and the row's own detail screen is unreachable from the row), and the long
 * press still fires, because it was armed on the down and only the missing up would have cancelled it —
 * which is the inversion the photographs show, a light press opening the selection bar and a held press
 * doing nothing extra.
 *
 * Every test in this file therefore dispatches to `holder.itemView` and lets `ViewGroup` route the
 * gesture. Dispatching to the card itself would prove nothing: `View.dispatchTouchEvent` hands a view
 * whatever it is given, whether or not a real parent would have routed the rest of the stream to it.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardRowGestureTest {

    private lateinit var context: Context
    private lateinit var parent: ViewGroup
    private lateinit var window: android.app.Activity
    private val clicks = mutableListOf<Task>()
    private val longPresses = mutableListOf<Task>()
    private val selectionToggles = mutableListOf<Task>()

    @Before fun setUp() {
        context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_TaskPlanner
        )
        parent = FrameLayout(context)
        // A real window, because `View.postDelayed` on a view that is not attached goes to
        // `HandlerActionQueue` and waits for an attach that never comes — so the row's armed long press
        // would not run, and the difference between a tap and a held press could not be measured at all.
        window = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        clicks.clear(); longPresses.clear(); selectionToggles.clear()
    }

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()

    /** The row, with the three callbacks this file reads back as user-visible outcomes. */
    private fun row(): View {
        val adapter = TasksAdapter(
            onTaskClick = { clicks += it },
            onLongPressTask = { longPresses += it },
            onToggleSelection = { selectionToggles += it },
            onEdit = {}, onDeleteRequest = {}, onStart = {}, onPause = {}, onResume = {},
            onComplete = {}, onWorkToggle = {}, onToggleGroup = {}, onGroupMenu = { _, _ -> },
            onStartDrag = {}
        )
        adapter.submitList(
            listOf(
                TasksListItem.TaskRow(
                    Task(
                        id = 7, uuid = "row-gesture", title = "Swipe the row",
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
            window.addContentView(
                view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowMeasuredHeight(view))
            )
            // Measured twice for the same reason as TaskCardClosedPanelTest: a match_parent child under an
            // UNSPECIFIED parent measures zero high, and a zero-high panel would answer every gesture
            // "nothing to reveal".
            val widthSpec = View.MeasureSpec.makeMeasureSpec(393.dp(), View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val rowHeight = view.measuredHeight
            view.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(rowHeight, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 393.dp(), rowHeight)
        }
    }

    /** The row's own height, asked before the view goes into the window's layout params. */
    private fun rowMeasuredHeight(view: View): Int {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(393.dp(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return view.measuredHeight
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    // -- the gestures ------------------------------------------------------------------------------

    private fun down(view: View, x: Float, y: Float): MotionEvent = MotionEvent.obtain(
        SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0
    ).also { view.dispatchTouchEvent(it) }

    private fun move(view: View, down: MotionEvent, x: Float, y: Float) {
        val event = MotionEvent.obtain(
            down.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun up(view: View, down: MotionEvent, x: Float, y: Float) {
        val event = MotionEvent.obtain(
            down.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * A drag of more than the panel's width, in steps, started at the card's own centre and let go.
     *
     * The centre, for two reasons that are the same reason at different sizes: a press that lands on one
     * of the card's buttons belongs to that button — children are offered the down before the card's
     * handler is asked — and on the device a drag that begins in the screen's edge band belongs to the
     * system Back gesture (`navigation_mode=2` on both test AVDs; measured, a start at 95% of the card's
     * width paused the activity while every interior start left it running).
     *
     * The drag overshoots on purpose, because a real one does: the handler lets the card travel 24px past
     * the panel, and it is the release that decides where the card ends up.
     */
    private fun swipeLeftBy(row: View, distancePx: Int, from: View) {
        val startX = from.left + from.width * 0.5f
        val startY = (from.top + from.bottom) / 2f
        val touched = down(row, startX, startY)
        val steps = 8
        for (step in 1..steps) {
            move(row, touched, startX - distancePx * step / steps, startY)
            shadowOf(Looper.getMainLooper()).idle()
        }
        up(row, touched, startX - distancePx, startY)
        touched.recycle()
        // The snap is a 180ms animation, so the resting place is read after it has been given its time.
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
    }

    // -- the tests ---------------------------------------------------------------------------------

    @Test fun `a drag handed to the row slides the card onto the panel's left edge`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)
        assertTrue("the panel has two buttons worth of width to reveal: ${panel.width}px",
            panel.width >= 100.dp())

        swipeLeftBy(row, panel.width + 40, from = card)

        assertEquals(
            "a horizontal drag of more than the panel's width lands the card on the panel's left edge",
            -panel.width.toFloat(), card.translationX, 1f
        )
    }

    @Test fun `the same row closes the card it just opened, and a second drag opens it again`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val panel = row.findViewById<View>(R.id.actionPanel)

        swipeLeftBy(row, panel.width + 40, from = card)
        assertEquals("open first", -panel.width.toFloat(), card.translationX, 1f)

        // A tap on an open row is the close gesture the handler describes; it must not also navigate.
        val before = clicks.size
        val x = (card.left + card.right) / 2f - panel.width
        val y = (card.top + card.bottom) / 2f
        val touched = down(row, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        up(row, touched, x, y)
        touched.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)

        assertEquals("the card is shut again", 0f, card.translationX, 1f)
        assertEquals("and closing it did not open the task", before, clicks.size)

        swipeLeftBy(row, panel.width + 40, from = card)
        assertEquals("and the gesture works a second time on the same row",
            -panel.width.toFloat(), card.translationX, 1f)
    }

    @Test fun `a tap handed to the row opens the task`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val x = (card.left + card.right) / 2f
        val y = (card.top + card.bottom) / 2f

        val touched = down(row, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        up(row, touched, x, y)
        touched.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("one task was asked for", 1, clicks.size)
        assertEquals("and it is the task on the row that was pressed", "row-gesture", clicks[0].uuid)
        assertTrue("pressing it is not selecting it", selectionToggles.isEmpty())
    }

    @Test fun `a tap that is not held down does not enter selection`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val x = (card.left + card.right) / 2f
        val y = (card.top + card.bottom) / 2f

        val touched = down(row, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
        up(row, touched, x, y)
        touched.recycle()
        // Past the row's own long-press timeout: a tap whose up was never delivered still has its armed
        // long press running here, and that is the selection bar appearing under a finger that only
        // pressed and let go.
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)

        assertEquals("a held-once tap opened the task, not the selection bar", 1, clicks.size)
        assertTrue("and no long press fired", longPresses.isEmpty())
    }

    @Test fun `a press that is held does enter selection`() {
        val row = row()
        val card = row.findViewById<View>(R.id.taskCard)
        val x = (card.left + card.right) / 2f
        val y = (card.top + card.bottom) / 2f

        val touched = down(row, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        up(row, touched, x, y)
        touched.recycle()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("holding the row selects it", 1, longPresses.size)
        assertEquals("and it does not also open", 0, clicks.size)
    }
}
