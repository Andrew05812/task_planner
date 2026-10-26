package com.taskplanner.ui.tasks

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.taskplanner.R
import com.taskplanner.databinding.FragmentTaskDetailBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The editor's layout, checked the way the screen sees it: inflated under the app's own theme, so a missing
 * style, an id collision or a measure that cannot resolve fails here instead of on a phone.
 *
 * Two things about the editor are load-bearing and both are easy to lose in a regrouping, so both are
 * pinned:
 *
 *  * the form is six blocks in an order a reader answers as one question after another — what the task is,
 *    when it happens, how it is classified, how often it comes back, when to warn, what it actually cost;
 *  * the screen is that scrolling form *above* a footer that does not scroll, so Save and Cancel are on the
 *    window whether or not the reader has reached the bottom of the fields.
 *
 * What stays out of this file on purpose: any claim about what a field *saves*. The editor writes the same
 * task it wrote before the regrouping — that is the stage's rule, and [TaskEditorInstrumentedTest] and
 * `TasksRecurrenceTest` are where the saving is proved.
 */
@RunWith(RobolectricTestRunner::class)
class TaskDetailLayoutTest {

    /** The six blocks the design names (spec 4.3), in the order it names them. */
    private val sections = listOf(
        R.id.task_section, R.id.when_section, R.id.how_section,
        R.id.repeat_section, R.id.reminder_section, R.id.work_section
    )

    /** Every field that has a place to live, and the block it belongs to. */
    private val homes = mapOf(
        R.id.task_title to R.id.task_section,
        R.id.task_description to R.id.task_section,
        R.id.task_due_date to R.id.when_section,
        R.id.task_due_time to R.id.when_section,
        R.id.plan_start_date to R.id.when_section,
        R.id.plan_end_time to R.id.when_section,
        R.id.plan_estimate_minutes to R.id.when_section,
        R.id.task_priority to R.id.how_section,
        R.id.task_status to R.id.how_section,
        R.id.task_category to R.id.how_section,
        R.id.repeat_preset to R.id.repeat_section,
        R.id.repeat_interval to R.id.repeat_section,
        R.id.day_tue to R.id.repeat_section,
        R.id.repeat_day_of_month to R.id.repeat_section,
        R.id.reminder_five_min to R.id.reminder_section,
        R.id.reminder_custom_minutes to R.id.reminder_section,
        R.id.workStartButton to R.id.work_section,
        R.id.work_intervals to R.id.work_section
    )

    private fun inflated(): FragmentTaskDetailBinding {
        val context: Context = ApplicationProvider.getApplicationContext()
        // The fragment always lives inside the activity's theme; without it ?attr/colorPrimary and
        // the chip styles do not resolve, which is not how the screen is used.
        val themed = ContextThemeWrapper(context, R.style.Theme_TaskPlanner)
        return FragmentTaskDetailBinding.inflate(LayoutInflater.from(themed))
    }

    private fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()

    /**
     * The block the six sections are stacked in, found through a section rather than through an id of its
     * own. The layout has no reason to name that container, and a test that made it add one would be
     * asserting that a name exists rather than that the form is grouped.
     */
    private fun FragmentTaskDetailBinding.content(): ViewGroup = taskSection.parent as ViewGroup

    /** The section a field lives in — the question a grouped form makes a reader answer. */
    private fun blockOf(content: ViewGroup, id: Int): View {
        val field = content.findViewById<View>(id)
        assertNotNull(
            "the editor has no field ${name(content, id)}",
            field
        )
        var ancestor: View = field
        while (ancestor.parent !== content) ancestor = ancestor.parent as View
        return ancestor
    }

    private fun sectionOf(content: ViewGroup, id: Int): Int = content.indexOfChild(blockOf(content, id))

    private fun name(context: Context, id: Int): String = context.resources.getResourceName(id)

    private fun name(content: ViewGroup, id: Int): String = name(content.context, id)

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (parent === ancestor) return true
            parent = (parent as View).parent
        }
        return false
    }

    /**
     * Whether the form is drawing this view at all — the view and every block above it have to be visible.
     *
     * `view.isVisible` alone is not enough: the custom-minutes field is visible, and the layout wrapped
     * round it is not, until the reader chooses the Custom chip. A field the editor has not opened yet is
     * never measured, so asking it for its bounds proves nothing — and asking it to have a width would fail
     * on a form that is correct.
     */
    private fun isDrawn(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (!current.isVisible) return false
            current = current.parent as? View
        }
        return true
    }

    /**
     * A view's distance from the top of the window, adding up every parent's offset.
     *
     * `view.top` is relative to its own parent, so a button inside the footer and the footer itself are two
     * numbers in two coordinate spaces, and comparing them says nothing about the screen. The footer's
     * claim is about the window, so the arithmetic has to be too.
     */
    private fun topInWindow(view: View): Int {
        var y = 0
        var current: View? = view
        while (current != null) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }

    @Test
    fun `the form is the six sections the design names, in that order`() {
        val binding = inflated()
        val content = binding.content()

        assertEquals(
            "the editor is six blocks and nothing else: a seventh child would be a field nobody grouped, " +
                "and a missing one a block the design asks for that the screen does not have",
            sections,
            (0 until content.childCount).map { content.getChildAt(it).id }
        )
    }

    @Test
    fun `each field lives in the block whose question it answers`() {
        val binding = inflated()
        val content = binding.content()

        homes.forEach { (field, section) ->
            assertSame(
                "${name(content, field)} belongs in ${name(content, section)}",
                content.findViewById(section),
                blockOf(content, field)
            )
        }
    }

    @Test
    fun `the blocks answer what then when then how then how often then when to warn`() {
        val binding = inflated()
        val content = binding.content()
        val index = { id: Int -> sectionOf(content, id) }

        assertEquals("${name(content, R.id.task_section)} is the first thing on the screen", 0, index(R.id.task_section))
        assertTrue(
            "what the task is comes before when it happens",
            index(R.id.task_section) < index(R.id.when_section)
        )
        assertTrue(
            "when it happens comes before how it is classified: the deadline is the task's own fact, " +
                "priority and category are how it sits among the others",
            index(R.id.when_section) < index(R.id.how_section)
        )
        assertTrue(
            "classification comes before repetition — this is the first of three boundaries that moved. " +
                "The fields used to run category, reminders, repeat; a reader setting up a series wants the " +
                "rule beside the schedule it changes, not below the warnings",
            index(R.id.how_section) < index(R.id.repeat_section)
        )
        assertTrue(
            "and the warnings come after the rule that decides which deadlines there are to warn about — " +
                "the second boundary that moved: Reminders used to sit above Repeat",
            index(R.id.repeat_section) < index(R.id.reminder_section)
        )
        assertTrue(
            "the record of what the task cost is last, because it is read and not typed, and only a saved " +
                "task has anything in it — the third boundary that moved: it used to sit above the " +
                "classification fields, splitting When from How",
            index(R.id.reminder_section) < index(R.id.work_section)
        )
    }

    @Test
    fun `save and cancel are a footer the form scrolls behind`() {
        val binding = inflated()
        val screen: ViewGroup = binding.root
        assertEquals(
            "the editor screen is exactly two blocks: the scrolling form, and the footer on the window",
            2, screen.childCount
        )
        val scroll = screen.getChildAt(0)
        val footer = binding.buttonContainer

        assertTrue(
            "the first of them scrolls",
            scroll is NestedScrollView
        )
        assertSame("the footer belongs to the screen, not to the form", screen, footer.parent)
        assertEquals("and it is the last thing on it", screen.childCount - 1, screen.indexOfChild(footer))
        assertFalse(
            "Save and Cancel must not be inside the scrolling form: a reader who has not scrolled to the " +
                "bottom of the fields cannot save the task",
            isDescendantOf(footer, scroll)
        )
        assertTrue(
            "the fields do scroll — they are inside that view",
            isDescendantOf(binding.taskSection, scroll)
        )
        val scrollParams = scroll.layoutParams as LinearLayout.LayoutParams
        assertEquals(
            "the form takes the height the window has left after the footer, which is what keeps the " +
                "footer on the screen",
            0, scrollParams.height
        )
        assertEquals("and no more of it than that", 1f, scrollParams.weight, 0f)
        assertNotNull(
            "the footer is a surface of its own, so fields sliding behind it do not read through the labels",
            footer.background
        )
    }

    @Test
    fun `the footer keeps its band at the bottom while the fields run past it`() {
        val binding = inflated()
        val context = binding.root.context
        val width = dp(context, 320f)
        // Shorter than the form on purpose: the claim is about what happens when the fields do not fit.
        val height = dp(context, 500f)
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        binding.root.layout(0, 0, width, height)

        val screen: ViewGroup = binding.root
        val footer = binding.buttonContainer
        val finger = dp(context, 48f)

        assertEquals(height, screen.height)
        assertEquals(
            "the footer sits on the bottom edge of the window",
            height, footer.bottom
        )
        assertTrue("and it has a band of its own above that edge", footer.top > 0 && footer.top < height)
        val scroll = screen.getChildAt(0) as NestedScrollView
        assertEquals("the form ends where the footer starts", footer.top, scroll.bottom)
        assertTrue(
            "the fields are taller than the room left for them, which is why they need the scroll",
            scroll.getChildAt(0).height > scroll.height
        )
        assertTrue(
            "Save is fully inside the window without any scrolling",
            topInWindow(binding.saveTask) >= topInWindow(footer) &&
                topInWindow(binding.saveTask) + binding.saveTask.height <= height
        )
        assertTrue("Save is at least a finger tall", binding.saveTask.height >= finger)
        assertTrue("Cancel is at least a finger tall", binding.cancelButton.height >= finger)
    }

    @Test
    fun `the saving spinner is beside the buttons that it disables`() {
        val binding = inflated()

        assertFalse(
            "nothing is being saved, so nothing is spinning",
            binding.progressBar.isVisible
        )
        assertTrue(
            "the progress sits with the footer, not at the end of the scroll: a reader who pressed Save " +
                "should not have to scroll to find out whether anything happened",
            isDescendantOf(binding.progressBar, binding.buttonContainer)
        )
    }

    @Test
    fun `every section opens with a heading a screen reader can jump between`() {
        val binding = inflated()
        val content = binding.content()
        val context = content.context
        val headings = listOf(
            R.id.task_section to R.string.section_task,
            R.id.when_section to R.string.section_when,
            R.id.how_section to R.string.section_how,
            R.id.repeat_section to R.string.section_repeat,
            R.id.reminder_section to R.string.reminder_section_title,
            R.id.work_section to R.string.work_section_title
        )

        headings.forEach { (section, headingRes) ->
            val block = content.findViewById<ViewGroup>(section)
            val heading = block.getChildAt(0)
            assertTrue(
                "${name(context, section)} opens with a label rather than with a field",
                heading is TextView
            )
            assertEquals(context.getString(headingRes), (heading as TextView).text.toString())
            assertTrue(
                "and it announces itself as a heading, which is how TalkBack moves round a long form",
                heading.isAccessibilityHeading
            )
        }
    }

    @Test
    fun `the editor lays out on the narrowest screen the app supports`() {
        val binding = inflated()
        val context = binding.root.context
        val width = dp(context, 320f)
        val height = dp(context, 500f)

        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        binding.root.layout(0, 0, width, height)

        assertTrue("the form must have a height at 320dp", binding.root.height > 0)
        assertEquals("the screen takes the whole width", width, binding.root.width)
        // Nothing may stick out past the screen: a view that does is unreachable for the reader, because
        // the editor scrolls vertically only. A field the editor has not shown yet (the interval, the
        // weekday chips, the custom minutes) is measured by its container only when it is opened, so it is
        // asked for its bounds *and* to be either on screen or hidden — a view that has no width and is not
        // hidden either was laid out wrong, and that is the case this check exists for.
        val checked = listOf(
            binding.taskTitle, binding.taskDueDate, binding.taskDueTime, binding.taskCategory,
            binding.reminderAtTime, binding.reminderOneDay, binding.reminderCustomMinutes,
            binding.reminderHint, binding.reminderStatus,
            binding.repeatPreset, binding.repeatInterval, binding.repeatDayOfMonth,
            binding.repeatScopeThis, binding.repeatScopeFuture,
            binding.pauseSeriesButton, binding.deleteSeriesButton, binding.saveTask
        )
        val content = binding.content()
        val drawn = checked.filter { isDrawn(it) }
        assertTrue(
            "most of what the check walks has to be drawn for the bounds to mean anything: " +
                "${drawn.size} of ${checked.size} are",
            drawn.size >= 9
        )
        assertEquals(
            "every view the check walks is either inside the scrolling form or inside the footer — one in " +
                "neither would be a view the layout has lost somewhere off both",
            emptyList<String>(),
            checked.filter {
                !isDescendantOf(it, content) && !isDescendantOf(it, binding.buttonContainer)
            }.map { name(context, it.id) }
        )
        checked.forEach { view ->
            val label = name(context, view.id)
            assertTrue(
                "$label is drawn and was given no width",
                !isDrawn(view) || view.width > 0
            )
            assertTrue(
                "$label is off the 320dp screen",
                view.left >= 0 && view.right <= width
            )
        }
    }

    @Test
    fun `a chip the reader has ticked says so without needing the colour`() {
        val binding = inflated()
        val stateChips: List<Chip> = listOf(
            binding.reminderAtTime, binding.reminderFiveMin, binding.reminderFifteenMin,
            binding.reminderThirtyMin, binding.reminderOneHour, binding.reminderOneDay,
            binding.reminderCustom,
            binding.dayMon, binding.dayTue, binding.dayWed, binding.dayThu,
            binding.dayFri, binding.daySat, binding.daySun
        )

        stateChips.forEach { chip ->
            chip.isChecked = true
            val name = name(binding.root.context, chip.id)
            assertNotNull("$name carries no mark for its checked state", chip.checkedIcon)
            assertTrue("$name hides the mark it has", chip.isCheckedIconVisible)
        }
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
    fun `every preset lead time is offered as its own chip`() {
        val binding = inflated()
        val context = binding.root.context
        val chips = listOf(
            binding.reminderAtTime, binding.reminderFiveMin, binding.reminderFifteenMin,
            binding.reminderThirtyMin, binding.reminderOneHour, binding.reminderOneDay,
            binding.reminderCustom
        )

        assertEquals(
            listOf(
                context.getString(R.string.reminder_at_time),
                context.getString(R.string.reminder_five_min),
                context.getString(R.string.reminder_fifteen_min),
                context.getString(R.string.reminder_thirty_min),
                context.getString(R.string.reminder_one_hour),
                context.getString(R.string.reminder_one_day),
                context.getString(R.string.reminder_custom)
            ),
            chips.map { it.text.toString() }
        )
        chips.forEach { chip ->
            assertTrue("a lead time has to be ticked on its own", chip.isCheckable)
            assertFalse("nothing is chosen until the user chooses it", chip.isChecked)
            assertTrue(chip.parent === binding.reminderChips)
        }
        assertFalse(
            "one deadline can be worth warning about several times",
            binding.reminderChips.isSingleSelection
        )
        assertFalse(
            "the custom minutes only appear once Custom is chosen",
            binding.reminderCustomLayout.isVisible
        )
    }

    @Test
    fun `the tracking block is hidden for a new task and holds the four commands`() {
        val binding = inflated()
        val context = binding.root.context

        assertFalse(
            "a task not yet saved has nothing to measure, so the block stays out of the way",
            binding.workSection.isVisible
        )
        assertTrue("Start is the only command an unstarted task can be offered", binding.workStartButton.isVisible)
        assertFalse(binding.workPauseButton.isVisible)
        assertFalse(binding.workResumeButton.isVisible)
        assertFalse(binding.workStopButton.isVisible)
        assertFalse("no stretch is running, so no figure is counting", binding.workLiveSeconds.isVisible)
        assertFalse("and no dot is lit", binding.workStateDot.isVisible)
        assertEquals(context.getString(R.string.work_start), binding.workStartButton.text)
        assertEquals(context.getString(R.string.work_stop), binding.workStopButton.text)
        assertEquals(
            "the empty state says there is no history rather than showing a blank list",
            context.getString(R.string.work_no_intervals),
            binding.workIntervalsEmpty.text.toString()
        )
        // The measured figures are read, never typed: the block has no editable field in it.
        assertEquals(
            "the stretch rows are built from whatever the task has, so the container starts empty",
            0,
            binding.workIntervals.childCount
        )
    }

    @Test
    fun `measured time sits under the plan it is kept apart from`() {
        val binding = inflated()
        val content = binding.content()
        val index = { id: Int -> sectionOf(content, id) }

        assertTrue(
            "the estimate and the actual are neighbours in the form but never the same line",
            index(R.id.plan_section) < index(R.id.work_section)
        )
        assertTrue(
            "and the actual is nowhere between the schedule and the classification it belongs to",
            index(R.id.task_priority_layout) < index(R.id.work_section)
        )
    }

    @Test
    fun `the reminder section says what it needs before anything is chosen`() {
        val binding = inflated()
        val context = binding.root.context

        assertEquals(
            context.getString(R.string.reminder_hint_needs_date),
            binding.reminderHint.text.toString()
        )
        assertEquals(
            context.getString(R.string.reminder_custom_hint),
            binding.reminderCustomLayout.hint.toString()
        )
        assertFalse("a plan is only worth showing once there is one", binding.reminderStatus.isVisible)
        assertEquals(
            "the custom lead time is typed in minutes, so the keyboard offers digits",
            InputType.TYPE_CLASS_NUMBER,
            binding.reminderCustomMinutes.inputType
        )
    }

    @Test
    fun `reminders sit with the deadline they count back from`() {
        val binding = inflated()
        val content = binding.content()
        val index = { id: Int -> sectionOf(content, id) }

        assertTrue(
            "the time of the deadline comes before the warnings about it",
            index(R.id.task_due_time_layout) < index(R.id.reminder_section)
        )
        assertTrue(
            "and the rule the warnings hang off — which deadlines there will be — is settled before them",
            index(R.id.repeat_section) < index(R.id.reminder_section)
        )
    }
}
