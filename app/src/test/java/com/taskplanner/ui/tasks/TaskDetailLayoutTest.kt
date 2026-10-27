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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
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
import org.xmlpull.v1.XmlPullParser

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

        // No claim about `screen.height`: the window was measured EXACTLY, so the root's height is the
        // number this test handed it and asserting it back proves nothing. What is not given by the spec is
        // where the footer lands inside it, and that is what the next line asks.
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
        // Only the bottom edge is claimed. "Save starts no higher than the footer does" is arithmetic —
        // Save is inside the footer, so its offset from the window top is the footer's plus a non-negative
        // padding — and a check that cannot fail is worse than none, because it reads like coverage.
        assertTrue(
            "Save sits inside the window: bottom=${topInWindow(binding.saveTask) + binding.saveTask.height} " +
                "of $height",
            topInWindow(binding.saveTask) + binding.saveTask.height <= height
        )
        assertTrue(
            "the spinner comes before the buttons it sits beside, so a reader walking the footer from the " +
                "left finds the reason before the action",
            footer.indexOfChild(binding.progressBar) < footer.indexOfChild(binding.cancelButton)
        )
        assertTrue("Save is at least a finger tall", binding.saveTask.height >= finger)
        assertTrue("Cancel is at least a finger tall", binding.cancelButton.height >= finger)
    }

    /**
     * What the section tests cannot say: `sectionOf` answers "which block", so every field inside one block
     * has the same index and their order among themselves is invisible. Inside a block the order still
     * matters — a reader who has set the deadline reaches for the clock, not for the category — so it is
     * asserted where it can be, against the views themselves.
     */
    @Test
    fun `inside each block the fields are in the order the reader fills them in`() {
        val binding = inflated()
        val context = binding.root.context
        // Without a measure and a layout every `top` below is 0, and the comparisons pass for the wrong
        // reason — which is the exact mistake this file exists to stop making.
        val width = dp(context, 393f)
        val height = dp(context, 500f)
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        binding.root.layout(0, 0, width, height)
        val order: List<Pair<View, View>> = listOf(
            binding.taskTitleLayout to binding.taskDescriptionLayout,
            binding.taskDueDateLayout to binding.taskDueTimeLayout,
            binding.taskDueTimeLayout to binding.planSection,
            binding.taskPriorityLayout to binding.taskStatusLayout,
            binding.taskStatusLayout to binding.taskCategoryLayout,
            binding.repeatLayout to binding.reminderChips,      // Repeat's block, then Reminders'
            binding.reminderTitle to binding.reminderChips
        )
        order.forEach { (before, after) ->
            // Both halves have to be on the screen or the comparison is arithmetic about nothing: a field
            // under a collapsed block keeps `top = 0` however tall the form is, and `0 < n` then passes for
            // every `after` — the ordering claim would be a claim about a view that was never placed.
            listOf(before to after, after to before).forEach { (subject, other) ->
                assertTrue(
                    "${name(context, subject.id)} is drawn at all, or its place relative to " +
                        "${name(context, other.id)} proves nothing",
                    isDrawn(subject) && subject.height > 0
                )
            }
            assertTrue(
                "${name(context, before.id)} comes before ${name(context, after.id)} in the " +
                    "vertical order: top=${topInWindow(before)} vs ${topInWindow(after)}",
                topInWindow(before) < topInWindow(after)
            )
        }
    }

    @Test
    fun `the saving spinner is beside the buttons that it disables`() {
        val binding = inflated()

        assertEquals(
            "the slot is reserved, so nothing in the row has to move when a save starts. A `gone` spinner " +
                "took 40dp out of the weighted row the moment Save was pressed and both buttons slid under " +
                "the reader's finger",
            View.INVISIBLE,
            binding.progressBar.visibility
        )
        assertFalse(
            "and it is not being drawn while nothing is being saved",
            binding.progressBar.isVisible
        )
        assertTrue(
            "the progress sits with the footer, not at the end of the scroll: a reader who pressed Save " +
                "should not have to scroll to find out whether anything happened",
            isDescendantOf(binding.progressBar, binding.buttonContainer)
        )
    }

    /**
     * The footer's geometry, before and during a save.
     *
     * The claim is about movement, so it is measured as two layouts of the same window: the widths of the two
     * buttons with the spinner invisible, and again with it visible. Equal widths are the whole point of
     * reserving the slot — a `gone` spinner fails the second measure, and that failure is the defect.
     */
    @Test
    fun `the footer does not move when a save starts`() {
        val binding = inflated()
        val context = binding.root.context
        val width = dp(context, 320f)
        val height = dp(context, 500f)
        binding.root.measureAndLayout(width, height)
        val before = binding.cancelButton.width to binding.saveTask.width
        assertTrue(
            "the footer was never measured, so there is nothing to compare: $before",
            before.first > 0 && before.second > 0
        )

        binding.progressBar.visibility = View.VISIBLE
        binding.root.measureAndLayout(width, height)

        assertEquals(
            "Cancel keeps its width while a save is spinning beside it",
            before.first,
            binding.cancelButton.width
        )
        assertEquals(
            "and so does Save, which is the button the reader's finger is still on",
            before.second,
            binding.saveTask.width
        )
    }

    /** Measure and lay this view out at [width]x[height], the way a window that size would. */
    private fun View.measureAndLayout(width: Int, height: Int) {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, width, height)
    }

    /**
     * The lines under these fields say what the editor just did — "2 reminders set", "the series is paused",
     * "start and end are outside the plan". A screen reader only speaks a view when focus moves to it, so a
     * line that changes while the reader's focus is somewhere else changes silently, which is the same defect
     * as a colour-only chip: the state moved and nothing said so. `polite`, not `assertive`, because none of
     * these is an emergency and interrupting a reader mid-sentence to announce a hint is its own problem.
     */
    @Test
    fun `a line that changes while the reader is on the screen is a live region`() {
        val binding = inflated()
        val context = binding.root.context
        val changing = listOf(
            R.id.plan_hint, R.id.repeat_scope_hint, R.id.series_status,
            R.id.reminder_hint, R.id.reminder_status, R.id.work_state_hint
        )
        changing.forEach { id ->
            val view = binding.root.findViewById<View>(id)
            assertEquals(
                "${name(context, id)} is rewritten while the reader is elsewhere",
                View.ACCESSIBILITY_LIVE_REGION_POLITE,
                view.accessibilityLiveRegion
            )
        }
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
                "${name(context, section)} opens a block with a label rather than with a field",
                heading is TextView
            )
            assertEquals(context.getString(headingRes), (heading as TextView).text.toString())
            // What this proves, and what it does not. `android:accessibilityHeading` is API 28; the app's
            // minSdk is 24, and on 24-27 the framework has no such attribute in its resource table, so it is
            // dropped when the layout inflates — silently, without a crash. Robolectric runs here at API 34,
            // so this assertion shows the layout *asks* for a heading and that the platform honours it where
            // it can; it cannot show heading navigation on a 24-to-27 handset, and this stage does not claim
            // that. The words the heading carries are the fallback a reader on those versions still gets,
            // because they are the same words the section opens with.
            assertTrue(
                "and it announces itself as a heading, which is how TalkBack moves round a long form on " +
                    "API 28 and above",
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

        // The window is EXACTLY this wide because that is what a phone screen is; the root's own width and
        // height therefore cannot be anything else and asserting them back would be coverage that no layout
        // error could ever fail. The claims live on the children, which the root's spec does not constrain.
        // Nothing may stick out past the screen: a view that does is unreachable for the reader, because
        // the editor scrolls vertically only. A field the editor has not shown yet (the interval, the
        // weekday chips, the custom minutes) is measured by its container only when it is opened, so it is
        // asked for its bounds *and* to be either on screen or hidden — a view that has no width and is not
        // hidden either was laid out wrong, and that is the case this check exists for.
        // Named one by one, because "at least nine of the seventeen are drawn" is a count and not a
        // statement: a form that hides the category picker and shows three spares would satisfy it. The
        // eight that are not drawn are the ones behind a preset or a chip the reader has not chosen yet,
        // and for those the bounds claim would be about a view that was never measured — which is why they
        // are checked here for being *hidden*, and their bounds are not asserted at all.
        val drawnNow = listOf(
            R.id.task_title, R.id.task_due_date, R.id.task_due_time, R.id.task_category,
            R.id.reminder_at_time, R.id.reminder_one_day, R.id.repeat_preset, R.id.saveTask
        )
        val hiddenNow = listOf(
            R.id.reminder_custom_minutes, R.id.reminder_status, R.id.repeat_interval,
            R.id.repeat_day_of_month, R.id.repeat_scope_this, R.id.repeat_scope_future,
            R.id.pauseSeriesButton, R.id.deleteSeriesButton
        )
        val byId = (drawnNow + hiddenNow).associateWith {
            binding.root.findViewById<View>(it)
        }
        assertEquals(
            "the fields a plain new task shows, in the order the reader meets them",
            drawnNow.filter { isDrawn(byId.getValue(it)) },
            (drawnNow + hiddenNow).filter { isDrawn(byId.getValue(it)) }
        )
        byId.filterKeys { it in drawnNow }.forEach { (id, view) ->
            val label = name(context, id)
            assertTrue("$label was given no width at 320dp", view.width > 0)
            assertTrue(
                "$label is off the 320dp screen: left=${view.left} right=${view.right} of $width",
                view.left >= 0 && view.right <= width
            )
        }
        byId.filterKeys { it in hiddenNow }.forEach { (id, view) ->
            assertFalse(
                "${name(context, id)} is on the screen for a task that has no series, no custom lead time " +
                    "and no preset chosen — the collapsed blocks are supposed to stay away",
                isDrawn(view)
            )
        }
    }

    /**
     * Every control the reader can reach, measured against the floor the design promises.
     *
     * `touch_target_min` is 48dp and the component styles carry it, but a style is an intention and a box a
     * finger lands in is a fact, so the drawn controls are asked for their measured size. Chips are asked in
     * both axes: a chip 48dp tall and 20dp wide is still a target a thumb misses. A control the editor has
     * not opened is not measured at all — a 0x0 box satisfies any bound, and the claim would be about a view
     * nobody can reach either way. The count guard below is the difference: a form that drew no controls
     * would otherwise pass by having nothing to fail.
     */
    @Test
    fun `every control the reader can reach is at least the touch floor`() {
        val binding = inflated()
        val context = binding.root.context
        val width = dp(context, 393f)
        val height = dp(context, 500f)
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        binding.root.layout(0, 0, width, height)

        val floor = dp(context, 48f).toFloat()
        val controls = mutableListOf<View>()
        fun walk(view: View) {
            if (view is Chip || view is MaterialButton || view is TextInputLayout) {
                if (isDrawn(view)) controls += view
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(binding.root)
        assertTrue(
            "the editor drew only ${controls.size} controls, which means the walk found almost nothing and " +
                "the rest of this test would pass by absence",
            controls.size >= 20
        )

        val tooSmall = controls.filter { control ->
            control.height < floor || (control is Chip && control.width < floor)
        }
        assertEquals(
            "controls under the 48dp floor, measured: " +
                tooSmall.joinToString { "${name(context, it.id)} ${it.width}x${it.height}" },
            emptyList<View>(),
            tooSmall
        )
    }

    /**
     * "Apply the change to" is the question two radio buttons answer, so it is on the screen exactly when
     * they are. It was added to this layout by Stage 6 and shipped showing the words over a collapsed block
     * for every plain task — an editor that asks which instance of a series to change, when the task is not
     * in one. The label and the group therefore start in the same state, and `refreshRepeatUi` reveals the
     * pair together; asserting the pair rather than each half is what makes a later edit that shows only one
     * of them fail here.
     */
    @Test
    fun `the scope question appears only with the answers it belongs to`() {
        val binding = inflated()

        assertEquals(
            "the question and its two answers are shown or hidden together",
            binding.repeatScopeGroup.visibility,
            binding.repeatScopeLabel.visibility
        )
        assertFalse(
            "a plain editor draws ${name(binding.root.context, R.id.repeat_scope_label)} over nothing",
            binding.repeatScopeLabel.isVisible
        )
    }

    @Test
    fun `a chip the reader has ticked says so without needing the colour`() {        val binding = inflated()
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

        // Not "7 == 7": the group is asked how many chips it holds, so a weekday added to the layout without
        // being named here — or one moved out of the group — fails instead of being quietly untested.
        assertEquals(
            "the weekday group holds exactly the chips this test names",
            binding.repeatWeekDays.childCount,
            chips.size
        )
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
            "the plan is inside When and the record of what the task cost is its own block after it — " +
                "block indices, which is what `sectionOf` can honestly compare",
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
            "the deadline's own block comes before the warnings block",
            index(R.id.task_due_time_layout) < index(R.id.reminder_section)
        )
        assertTrue(
            "and the rule the warnings hang off — which deadlines there will be — is settled before them",
            index(R.id.repeat_section) < index(R.id.reminder_section)
        )
    }

    /**
     * Seven chips in a row announce themselves to TalkBack as "Monday, checkbox, checked" with nothing to
     * say what they are checkboxes *of*, and the reader has to remember which block they are in — the same
     * kind of gap as a chip whose only difference is its fill. `android:labelFor` is the platform's way of
     * tying the line above to the group below, and the editor already does it for the repeat scope; these two
     * groups were the ones left out, so the fix belongs with a claim that can notice it going missing again.
     *
     * The claim is read off the compiled layout rather than off an inflated tree, because the relation is
     * resolved when an accessibility service asks for the node — Robolectric never asks, and an inflated
     * `ChipGroup` would report the same nothing whether or not the attribute is there. Reading the resource
     * is the same choice the flow test makes for the same reason.
     */
    @Test
    fun `each chip group is named by the line above it`() {
        val named = labellersByName()
        val expected = mapOf(
            "repeat_week_days" to "repeat_week_days_label",
            "reminder_chips" to "reminder_title",
            "repeat_scope_group" to "repeat_scope_label",
        )
        expected.forEach { (group, label) ->
            val labeller = named[group]
            assertNotNull(
                "nothing in the layout names $group, so a reader hears the choices without their question; " +
                    "named groups here: ${named.keys.sorted()}",
                labeller
            )
            assertEquals("$group is named by ${labeller!!.id} rather than by $label", label, labeller.id)
            assertTrue(
                "${labeller.id} names $group but says nothing",
                labeller.words.isNotBlank()
            )
        }
    }

    /** Every `android:labelFor` in the editor layout, keyed by the entry it names. */
    private fun labellersByName(): Map<String, Labeller> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val found = mutableMapOf<String, Labeller>()
        context.resources.getXml(R.layout.fragment_task_detail).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val attrs = (0 until parser.attributeCount).associate { index ->
                        parser.getAttributeName(index).substringAfterLast('.') to
                            parser.getAttributeValue(index)
                    }
                    val group = attrs["labelFor"]?.let { entryName(context, it) }
                    if (group != null) {
                        val id = entryName(context, attrs["id"].orEmpty()) ?: ""
                        val words = referencedId(attrs["text"])?.let { resId ->
                            runCatching { context.getString(resId) }.getOrNull()
                        }
                        found[group] = Labeller(id = id, words = words.orEmpty())
                    }
                }
                parser.next()
            }
        }
        return found
    }

    /**
     * The resource id a compiled reference attribute points at, or nothing when the value is not a
     * reference. AAPT2 hands `@string/repeat_weekdays` to the parser as `@2131231515`, so the digits are
     * the only form this reader sees for a reference.
     */
    private fun referencedId(value: String?): Int? {
        val body = value?.removePrefix("@")?.removePrefix("+") ?: return null
        return if (body.all { it.isDigit() } && body.isNotEmpty()) body.toIntOrNull() else null
    }

    /**
     * `@id/x`, `@+id/x` and the compiled numeric form all name one entry; this returns that entry's name,
     * which is what survives a reshuffle of ids and still says which view was meant.
     */
    private fun entryName(context: Context, reference: String): String? {
        referencedId(reference)?.let { id ->
            return runCatching { context.resources.getResourceName(id).substringAfterLast('/') }.getOrNull()
        }
        val body = reference.removePrefix("@").removePrefix("+")
        return if (body.isEmpty()) null else body.substringAfterLast('/')
    }

    private class Labeller(val id: String, val words: String)
}
