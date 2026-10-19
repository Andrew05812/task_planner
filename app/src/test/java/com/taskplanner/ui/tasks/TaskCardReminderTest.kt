package com.taskplanner.ui.tasks

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The task card's reminder bell: the one thing on the list screen that tells the user a task will
 * make noise later, so it is checked on the inflated row instead of only in the state.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardReminderTest {

    private lateinit var context: Context
    private lateinit var parent: ViewGroup

    @Before
    fun setUp() {
        val app: Context = ApplicationProvider.getApplicationContext()
        // The card uses Material attributes and styles, which only resolve inside the app's theme.
        context = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)
        parent = FrameLayout(context)
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    private fun bindRow(pendingReminders: Int): View {
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
            onWorkToggle = {},
            onToggleGroup = {},
            onGroupMenu = { _, _ -> },
            onStartDrag = {}
        )
        val task = Task(
            id = 1,
            uuid = "task-bell",
            title = "Pick up the prescription",
            description = "",
            dueDate = System.currentTimeMillis() + 3_600_000L,
            userId = 1
        )
        adapter.submitList(
            listOf(TasksListItem.TaskRow(task, null, isSelected = false, pendingReminders = pendingReminders))
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
    fun `a task with no reminder waiting shows no bell`() {
        val badge = bindRow(0).findViewById<ImageView>(R.id.reminderBadge)

        assertFalse("a bell that means nothing would sit on every card", badge.isVisible)
    }

    @Test
    fun `a task with reminders waiting shows the bell and says how many`() {
        val badge = bindRow(2).findViewById<ImageView>(R.id.reminderBadge)

        assertTrue(badge.isVisible)
        assertEquals(
            "a screen reader has to hear the same number the bell stands for",
            context.resources.getQuantityString(R.plurals.reminder_count, 2, 2),
            badge.contentDescription.toString()
        )
    }

    @Test
    fun `the bell stays on the narrowest screen the app supports`() {
        val badge = bindRow(1).findViewById<ImageView>(R.id.reminderBadge)

        assertTrue("it has to be visible to mean anything", badge.isVisible)
        assertTrue(
            "a bell pushed off the edge cannot be seen",
            badge.left >= 0 && badge.right <= 320.dp()
        )
    }
}
