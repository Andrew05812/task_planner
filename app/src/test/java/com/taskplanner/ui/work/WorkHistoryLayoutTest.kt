package com.taskplanner.ui.work

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.text.InputType
import android.util.TypedValue
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.databinding.DialogWorkIntervalBinding
import com.taskplanner.databinding.DialogWorkRangeBinding
import com.taskplanner.databinding.FragmentWorkHistoryBinding
import com.taskplanner.databinding.ItemHistoryIntervalBinding
import com.taskplanner.databinding.ItemWorkHistoryBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The history screen's own layout, inflated under the app's theme the way the fragment uses it.
 *
 * View binding already proves the ids the code reads exist, so this file is not a check that a name was
 * typed correctly — it is a check of the shape the user gets: one period chosen at a time, a running
 * entry labelled rather than counted, the two commands on a stretch kept apart, and a row that still fits
 * the narrowest screen the app supports. Those are decisions a layout can undo silently.
 */
@RunWith(RobolectricTestRunner::class)
class WorkHistoryLayoutTest {

    private fun themed(): Context {
        val context: Context = ApplicationProvider.getApplicationContext()
        // The fragment always lives inside the activity's theme; without it ?attr/textAppearanceCaption and
        // the chip styles do not resolve, which is not how the screen is used.
        return ContextThemeWrapper(context, R.style.Theme_TaskPlanner)
    }

    private fun screen(): FragmentWorkHistoryBinding =
        FragmentWorkHistoryBinding.inflate(LayoutInflater.from(themed()))

    private fun row(): ItemWorkHistoryBinding =
        ItemWorkHistoryBinding.inflate(LayoutInflater.from(themed()))

    private fun intervalRow(): ItemHistoryIntervalBinding =
        ItemHistoryIntervalBinding.inflate(LayoutInflater.from(themed()))

    @Test
    fun `one period is chosen at a time and the row of them scrolls`() {
        val binding = screen()

        assertTrue(
            "the screen reads exactly one period, so the chips cannot disagree with each other",
            binding.historyPeriodChips.isSingleSelection
        )
        assertTrue(
            "more periods than fit a narrow screen, so the filter has to scroll sideways",
            binding.historyPeriodScroll.canScrollHorizontally(-1) ||
                binding.historyPeriodScroll.getChildAt(0) === binding.historyPeriodChips
        )
        assertTrue("and the list is the part that grows", binding.historyList.parent === binding.root)
    }

    @Test
    fun `the header and the empty state are both in place before anything is read`() {
        val binding = screen()
        val context = binding.root.context

        assertTrue(binding.historySummary.isVisible)
        assertTrue(binding.historyRange.isVisible)
        assertFalse(
            "the spinner waits for a read the screen has not asked for yet",
            binding.historyProgress.isVisible
        )
        assertFalse(
            "and the empty state is not a permanent caption: it is shown only once a read has answered",
            binding.historyEmpty.isVisible
        )
        assertEquals(
            "the empty state says what is missing rather than blaming the user",
            context.getString(R.string.history_empty),
            (binding.historyEmpty as TextView).text.toString()
        )
    }

    @Test
    fun `a history row carries its figures and hides the parts only an open entry has`() {
        val binding = row()
        val context = binding.root.context

        assertNotNull(
            "the row says it is tappable by itself: opening an entry is asked of the whole row, and the " +
                "screen only has to add the listener",
            binding.historyRowRoot.background
        )
        assertTrue(binding.historyTaskTitle.isVisible)
        assertTrue(binding.historyTotal.isVisible)
        assertFalse(
            "an entry that finished is not also marked as still being measured",
            binding.historyRunning.isVisible
        )
        assertEquals(
            context.getString(R.string.history_in_progress),
            (binding.historyRunning as TextView).text.toString()
        )
        assertEquals(
            "the stretches arrive only when the entry is opened, so the row starts with none",
            0,
            binding.historyIntervals.childCount
        )
    }

    @Test
    fun `an opened stretch has its two commands as separate buttons`() {
        val binding = intervalRow()
        val context = binding.root.context

        assertTrue(binding.historyIntervalLabel.isVisible)
        assertTrue("correcting and throwing away are different asks", binding.historyIntervalEdit.isVisible)
        assertTrue(binding.historyIntervalDelete.isVisible)
        assertEquals(
            "each one says what it does, since a stretch is not worth two guessing games",
            context.getString(R.string.history_edit_interval),
            binding.historyIntervalEdit.contentDescription.toString()
        )
        assertEquals(
            context.getString(R.string.history_delete_interval),
            binding.historyIntervalDelete.contentDescription.toString()
        )
    }

    @Test
    fun `the form asks for the instants in the shape the screen can read back`() {
        val form = DialogWorkIntervalBinding.inflate(LayoutInflater.from(themed()))
        val context = form.root.context

        assertEquals(context.getString(R.string.history_start_hint), form.workStartInput.hint.toString())
        assertEquals(context.getString(R.string.history_end_hint), form.workEndInput.hint.toString())
        assertEquals(context.getString(R.string.history_reason_hint), form.workReasonInput.hint.toString())
        assertFalse(
            "what the stretch said before is filled in by the screen, not typed by the user",
            form.workPrevious.isVisible
        )
        assertEquals("", form.workStartInput.text.toString())
    }

    @Test
    fun `the custom period asks for two whole days`() {
        val range = DialogWorkRangeBinding.inflate(LayoutInflater.from(themed()))
        val context = range.root.context

        assertEquals(context.getString(R.string.history_from_hint), range.rangeFromInput.hint.toString())
        assertEquals(context.getString(R.string.history_to_hint), range.rangeToInput.hint.toString())
        assertTrue(
            "a day is typed as digits, so the keyboard offers them",
            range.rangeFromInput.inputType and InputType.TYPE_CLASS_DATETIME != 0
        )
    }

    @Test
    fun `the screen and a row both fit the narrowest device the app supports`() {
        val context = themed()
        val width = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 320f, context.resources.displayMetrics
        ).toInt()

        listOf<ViewGroup>(screen().root, row().root).forEach { root ->
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            root.layout(0, 0, width, root.measuredHeight)

            assertTrue("the layout has to have a height at 320dp", root.measuredHeight > 0)
            assertEquals(width, root.measuredWidth)
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                assertTrue(
                    "child #$index of ${root.javaClass.simpleName} is off the 320dp screen",
                    child.left >= 0 && child.right <= width
                )
            }
        }
    }

    @Test
    fun `an opened stretch sits under its entry and keeps its own commands`() {
        val binding = row()
        val stretch = intervalRow()
        binding.historyIntervals.addView(stretch.root)

        assertEquals(1, binding.historyIntervals.childCount)
        assertSame(
            "the stretch belongs to the entry it was read for, not to the screen",
            binding.historyIntervals,
            stretch.root.parent
        )
        assertTrue("and each command on it is its own target", stretch.historyIntervalEdit.isClickable)
        assertTrue(stretch.historyIntervalDelete.isClickable)
        assertFalse(
            "the label is not a button: tapping the words of a stretch must not correct it",
            stretch.historyIntervalLabel.isClickable
        )
    }
}
