package com.taskplanner.ui.tasks

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.databinding.FragmentTaskDetailBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The editor's layout, checked the way the screen sees it: inflated under the app's own theme, so a
 * missing style, an id collision or a constraint that cannot be resolved fails here instead of on a
 * phone. What matters for a plain task is that the recurrence section stays out of the way until it
 * is asked for.
 */
@RunWith(RobolectricTestRunner::class)
class TaskDetailLayoutTest {

    private fun inflated(): FragmentTaskDetailBinding {
        val context: Context = ApplicationProvider.getApplicationContext()
        // The fragment always lives inside the activity's theme; without it ?attr/colorPrimary and
        // the chip styles do not resolve, which is not how the screen is used.
        val themed = ContextThemeWrapper(context, R.style.Theme_TaskPlanner)
        return FragmentTaskDetailBinding.inflate(LayoutInflater.from(themed))
    }

    @Test
    fun `the repeat section is present but collapsed for a plain new task`() {
        val binding = inflated()

        assertTrue("the preset picker is the one visible part of the section", binding.repeatLayout.isVisible)
        assertFalse(binding.repeatIntervalLayout.isVisible)
        assertFalse(binding.repeatWeekDays.isVisible)
        assertFalse(binding.repeatWeekDaysLabel.isVisible)
        assertFalse(binding.repeatDayLayout.isVisible)
        assertFalse(binding.repeatDayHint.isVisible)
        assertFalse("there is no series to apply a change to yet", binding.repeatScopeGroup.isVisible)
        assertFalse(binding.seriesControls.isVisible)
        assertFalse(binding.seriesStatus.isVisible)
        assertEquals(
            "and the editor explains what choosing a repeat does",
            binding.root.context.getString(R.string.repeat_scope_hint_new),
            binding.repeatScopeHint.text.toString()
        )
    }

    @Test
    fun `all seven weekdays are offered as independent choices`() {
        val binding = inflated()
        val chips = listOf(
            binding.dayMon, binding.dayTue, binding.dayWed, binding.dayThu,
            binding.dayFri, binding.daySat, binding.daySun
        )

        assertEquals(7, chips.size)
        assertEquals(7, chips.map { it.text.toString() }.distinct().size)
        chips.forEach { chip ->
            assertTrue("a weekday must be checkable on its own", chip.isCheckable)
            assertFalse(chip.isChecked)
        }
        // They live in the group the editor reads, not next to it.
        chips.forEach { chip -> assertTrue(chip.parent === binding.repeatWeekDays) }
        assertFalse("a series may repeat on several weekdays at once", binding.repeatWeekDays.isSingleSelection)
    }

    @Test
    fun `the series controls are reachable and labelled with what they do`() {
        val binding = inflated()
        val context = binding.root.context

        assertEquals(context.getString(R.string.action_pause_series), binding.pauseSeriesButton.text)
        assertEquals(context.getString(R.string.action_finish_series), binding.finishSeriesButton.text)
        assertEquals(context.getString(R.string.action_delete_series), binding.deleteSeriesButton.text)
        assertEquals(
            context.getString(R.string.repeat_scope_future),
            binding.repeatScopeFuture.text
        )
        assertEquals(context.getString(R.string.repeat_scope_this), binding.repeatScopeThis.text)
    }

    @Test
    fun `the editor lays out on the narrowest screen the app supports`() {
        val binding = inflated()
        val context = binding.root.context
        val width = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 320f, context.resources.displayMetrics
        ).toInt()

        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        binding.root.layout(0, 0, width, binding.root.measuredHeight)

        assertTrue("the form must have a height at 320dp", binding.root.measuredHeight > 0)
        assertEquals("the scroll container takes the whole width", width, binding.root.measuredWidth)
        // Nothing may stick out past the screen: a view that does is unreachable for the user,
        // because the editor scrolls vertically only.
        listOf(
            binding.taskTitle, binding.taskDueDate, binding.taskCategory,
            binding.repeatPreset, binding.repeatInterval, binding.repeatDayOfMonth,
            binding.repeatScopeThis, binding.repeatScopeFuture,
            binding.pauseSeriesButton, binding.deleteSeriesButton, binding.saveTask
        ).forEach { view ->
            assertTrue(
                "view #${context.resources.getResourceName(view.id)} is off the 320dp screen",
                view.left >= 0 && view.right <= width
            )
        }
    }

    @Test
    fun `the recurrence section sits between the task fields and the save buttons`() {
        val binding = inflated()
        val content = binding.root.getChildAt(0) as ViewGroup
        val index = { id: Int ->
            (0 until content.childCount).first { content.getChildAt(it).id == id }
        }

        assertEquals(content.childCount - 1, index(R.id.button_container))
        assertEquals(
            "the repeat controls come after the task fields and before the progress and the buttons",
            index(R.id.repeat_section) + 2,
            index(R.id.button_container)
        )
        assertTrue(index(R.id.task_category_layout) < index(R.id.repeat_section))
    }
}
