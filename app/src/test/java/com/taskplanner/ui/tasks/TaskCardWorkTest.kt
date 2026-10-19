package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.ui.util.WorkTimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The measured-time line on a task card, checked on the inflated row rather than in the state object.
 *
 * The line has to stay out of the way of what the card already does — swipe, long press, drag — and it
 * has to say which of its figures is a stored fact and which one is still being counted. Both are
 * properties of the row the user sees, so that is where they are asserted.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardWorkTest {

    private lateinit var context: Context
    private lateinit var parent: ViewGroup
    private var tapped: Task? = null

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        // The card uses Material attributes and styles, which only resolve inside the app's theme.
        context = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)
        parent = FrameLayout(context)
        tapped = null
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    private fun bindRow(work: CardWork, status: TaskStatus = TaskStatus.TODO): View {
        val adapter = TasksAdapter(
            onTaskClick = {},
            onLongPressTask = {},
            onToggleSelection = {},
            onEdit = {},
            onDeleteRequest = {},
            onStart = {},
            onPause = {},
            onResume = {},
            onComplete = {},
            onWorkToggle = { task -> tapped = task },
            onToggleGroup = {},
            onGroupMenu = { _, _ -> },
            onStartDrag = {}
        )
        val task = Task(
            id = 1,
            uuid = "task-work",
            title = "Write the report",
            description = "",
            userId = 1,
            status = status
        )
        adapter.submitList(
            listOf(TasksListItem.TaskRow(task, null, isSelected = false, work = work))
        )
        awaitUntil("the row to be diffed") { adapter.itemCount == 1 }

        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        val row = holder.itemView
        row.measure(
            View.MeasureSpec.makeMeasureSpec(320.dp(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        row.layout(0, 0, 320.dp(), row.measuredHeight)
        return row
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `a task with nothing measured and no timer has no work line at all`() {
        val row = bindRow(CardWork())

        assertFalse(
            "a line reading zero on every card would say nothing about any of them",
            row.findViewById<LinearLayout>(R.id.workRow).isVisible
        )
    }

    @Test
    fun `a finished stretch shows its total and offers to measure again`() {
        val row = bindRow(CardWork(closedSeconds = 7_500L))
        val time = row.findViewById<TextView>(R.id.workTime)

        assertTrue(row.findViewById<LinearLayout>(R.id.workRow).isVisible)
        assertTrue(time.isVisible)
        assertEquals(WorkTimeFormat.compact(context, 7_500L), time.text.toString())
        assertEquals(
            "the same figure has to reach a screen reader in words",
            context.getString(R.string.work_card_description, WorkTimeFormat.compact(context, 7_500L)),
            time.contentDescription.toString()
        )
        assertFalse("nothing is being counted, so no ticker", row.findViewById<View>(R.id.workTicker).isVisible)
        assertFalse("and no active dot", row.findViewById<View>(R.id.workDot).isVisible)
        assertEquals(
            context.getString(R.string.work_start),
            row.findViewById<ImageButton>(R.id.workControlButton).contentDescription.toString()
        )
    }

    @Test
    fun `a running stretch shows the dot, the moving figure and a pause command`() {
        val row = bindRow(CardWork(closedSeconds = 60L, liveSeconds = 45L, phase = TrackingPhase.RUNNING))
        val ticker = row.findViewById<TextView>(R.id.workTicker)

        assertTrue(row.findViewById<View>(R.id.workDot).isVisible)
        assertTrue("the open stretch is shown apart from the stored total", ticker.isVisible)
        assertEquals("0:00:45", ticker.text.toString())
        assertEquals(
            context.getString(R.string.work_pause),
            row.findViewById<ImageButton>(R.id.workControlButton).contentDescription.toString()
        )
    }

    @Test
    fun `a held stretch keeps its dot but has no figure to count`() {
        val row = bindRow(CardWork(closedSeconds = 60L, phase = TrackingPhase.PAUSED))

        assertTrue(row.findViewById<View>(R.id.workDot).isVisible)
        assertFalse(row.findViewById<View>(R.id.workTicker).isVisible)
        assertEquals(
            "the one control answers for the phase it was given",
            context.getString(R.string.work_resume),
            row.findViewById<ImageButton>(R.id.workControlButton).contentDescription.toString()
        )
    }

    @Test
    fun `a finished task is not offered a way to measure more work`() {
        val row = bindRow(
            CardWork(closedSeconds = 600L, liveSeconds = 10L, phase = TrackingPhase.RUNNING),
            status = TaskStatus.COMPLETED
        )

        assertFalse(
            "its timer was stopped when it was completed, so the line belongs to history now",
            row.findViewById<LinearLayout>(R.id.workRow).isVisible
        )
    }

    @Test
    fun `the control passes its own task to the tracker`() {
        val row = bindRow(CardWork(closedSeconds = 60L))

        row.findViewById<ImageButton>(R.id.workControlButton).performClick()

        assertEquals("Write the report", tapped?.title)
    }

    @Test
    fun `the work line cannot steal the card's own gestures`() {
        val row = bindRow(CardWork(closedSeconds = 60L))
        val workRow = row.findViewById<LinearLayout>(R.id.workRow)

        assertEquals(
            "the line is a sibling of the fields inside the card, not a overlay over the swipe panel",
            row.findViewById<View>(R.id.taskTitle).parent,
            workRow.parent
        )
        assertFalse("a row that swallows touches would break the tap", workRow.isClickable)
        assertFalse(workRow.isLongClickable)
    }
}
