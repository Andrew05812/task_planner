package com.taskplanner.ui.tasks

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.tracking.TrackingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The card at 320dp, as arithmetic (spec §4.1).
 *
 * 320 − 16 of card margins − 32 of card padding − 64 of the left column (48 handle + 4 + 4 rule + 8)
 * leaves 208dp of text column. Three 48dp buttons are 144 of them, which is why the action cluster has its
 * own line: beside the title it would leave four or five characters. This inflates the worst legal row — a
 * 120-character title, a long category name, a due date, a series, two reminders and a running stretch —
 * and measures what survives.
 *
 * Being inside the screen is not the bar. The bar is that the two facts the list is sorted and filtered by
 * are drawn in full, that the title has a readable measure, that every control is still 48dp, and that the
 * one element allowed to give way is the category name — visibly, by the 48dp rule, not by truncation.
 *
 * **What this class can measure, and what it cannot.** Everything here is box arithmetic: the widths a
 * `ConstraintLayout` hands its children, the column the left gutter leaves, the band each line occupies,
 * the size of a touch target. Those are real, and they break in code. What is NOT measurable here is
 * whether a string fits its box: Robolectric's LEGACY graphics return `text.length()` from
 * `Paint.measureText` and ignore `textSize`, and the NATIVE mode that would measure it cannot run on this
 * machine — `org.robolectric:nativeruntime:4.11.1` in the local cache carries no platform binary at all
 * (`resource native/windows/x86_64/robolectric-nativeruntime.dll not found`), and fetching one is a network
 * and dependency decision, not a test edit. So the three assertions that need real glyphs — the pill and
 * date allowances, "drawn in full", and the line-B reflow at font scale 1.3 — live in
 * `TaskCardWidthBudgetInstrumentedTest`, where they are measured by the renderer that ships. A JVM test
 * that asserted them here would pass on a card that clipped the date away.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardWidthBudgetTest {

    private lateinit var context: Context

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun Int.asDp() = toFloat() / context.resources.displayMetrics.density
    /** How many characters the drawn Layout hid. The ellipsis lives here, never in `text`. */
    private fun TextView.clippedLines(): Int =
        layout?.let { l -> (0 until l.lineCount).sumOf { l.getEllipsisCount(it) } } ?: 0

    private companion object {
        const val DAY = 86_400_000L

        /** A real stored series, so the repeat icon is drawn from data and not from a mock. */
        fun aWeeklyRule() = RecurrenceRule(
            userId = 1, unit = RepeatUnit.WEEK, interval = 1, weekDays = "1,3",
            dayOfMonth = 0, anchorAt = 0, cursorAt = 0
        )
    }

    /** Copied from TaskCardWorkTest, which needs the same patience for ListAdapter's async diff. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("no $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /**
     * The worst legal row, with the two inputs that decide the budget pinned rather than defaulted.
     *
     * `priority = HIGH` because LOW draws no rule, and a rule measured at zero would quietly move the 64dp
     * column. `sortOrder = MANUAL` because anything else hides the drag handle and the column collapses to
     * 12dp — which is its own case, asserted separately below.
     */
    private val categoryColor = 0xFF2F6F5E.toInt()   // ink_accent, a real ARGB, not the default 0

    private fun bindWorstCase(
        widthDp: Int = 320, fontScale: Float = 1.0f, sortOrder: SortOrder = SortOrder.MANUAL,
        // Thirty days ahead: an overdue row prints "3 days late", which is SHORTER than the full date the
        // 84dp allowance is defined against, so a fixture that was itself overdue never measured the
        // widest legal date. Ahead rather than behind also keeps the wording out of the today/tomorrow
        // branches, whose strings CardPresentationTest already covers.
        due: Long = System.currentTimeMillis() + 30L * DAY
    ): View {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        // ContextThemeWrapper does not override createConfigurationContext, so the theme has to be applied
        // to the configuration context rather than wrapped before it — otherwise the inflated row resolves
        // ?attr/ink_* against the manifest theme and measures a different card.
        context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_TaskPlanner)

        val adapter = TasksAdapter(
            onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {},
            onDeleteRequest = {}, onStart = {}, onPause = {}, onResume = {}, onComplete = {},
            onWorkToggle = {}, onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
        )
        adapter.submitList(
            listOf(
                TasksListItem.TaskRow(
                    task = Task(
                        id = 1, uuid = "budget", title = "Т".repeat(120), description = "",
                        userId = 1, status = TaskStatus.IN_PROGRESS, dueDate = due,
                        priority = com.taskplanner.data.model.Priority.HIGH
                    ),
                    // A real colour: `color = 0` is transparent, and a dot tinted from it "carries the
                    // colour" while painting nothing, which is the exact defect spec §4.1 exists to fix.
                    category = Category(id = 1, uuid = "c", name = "Диссертация", color = categoryColor,
                        icon = "", userId = 1),
                    isSelected = false, series = aWeeklyRule(), pendingReminders = 2,
                    work = CardWork(closedSeconds = 6_300, liveSeconds = 60, phase = TrackingPhase.RUNNING)
                )
            )
        )
        awaitUntil("the row to be diffed") { adapter.itemCount == 1 }
        adapter.reorderEnabled = sortOrder == SortOrder.MANUAL
        val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
        adapter.bindViewHolder(holder, 0)
        return holder.itemView.also {
            it.measure(
                View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            it.layout(0, 0, dp(widthDp), it.measuredHeight)
            // Layout is synchronous, but the category name's 48dp rule runs in a `doOnLayout` post, so a
            // reader that returns here sees the visibility from BEFORE the decision. One idle makes the
            // posted work happen inside the fixture.
            shadowOf(Looper.getMainLooper()).idle()
            it.requestLayout()
            it.measure(
                View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            it.layout(0, 0, dp(widthDp), it.measuredHeight)
        }
    }

    @Test fun `the text column is what the budget says it is`() {
        val row = bindWorstCase(widthDp = 320)
        val card = row.findViewById<View>(R.id.taskCard)
        val content = row.findViewById<View>(R.id.cardContent)
        // The column is measured where line A actually begins — on the category dot, which is the first
        // thing after the gutter — and not rebuilt from gap literals: a draft of this test added `dp(4)`
        // and `dp(8)` by hand and stayed green while `spacing_xs` moved and the real card narrowed. The
        // title itself starts 16dp further in (the dot and its gap), which is why the budget's "before any
        // text begins" is read at the dot, and the title's own allowance is asserted separately below.
        val leftColumn = row.findViewById<View>(R.id.categoryDot).left - content.paddingLeft

        // getWidth() includes padding, so the subtraction is against content's own padding, not an
        // assumed 16dp on the card.
        assertEquals("card is the row less its 8dp margins", dp(320) - 2 * dp(8), card.width)
        assertEquals("the left column is 64dp", dp(64), leftColumn)
        assertEquals(
            "208dp of text column at 320dp", dp(208),
            content.width - content.paddingLeft - content.paddingRight - leftColumn
        )
    }


    /** The same card with the handle hidden, which is what a non-MANUAL sort does to the column. */
    @Test fun `a collapsed left column gives the title the 52dp the bind path promises`() {
        val row = bindWorstCase(widthDp = 320, sortOrder = SortOrder.DATE_DESC)
        val handle = row.findViewById<View>(R.id.dragHandle)
        val rule = row.findViewById<View>(R.id.priorityIndicator)
        assertEquals("a non-MANUAL sort hides the handle", View.GONE, handle.visibility)
        // A GONE view contributes no width, and the margin that separated it from the rule goes with it, so
        // the collapsed column is measured where line A begins rather than rebuilt from `dp(4)` and `dp(8)`
        // gap terms — a hand-added sum stayed green once while `spacing_xs` moved and the real card narrowed.
        assertEquals("handle gone, the column is the rule plus its one gap", dp(12),
            row.findViewById<View>(R.id.categoryDot).left -
                row.findViewById<View>(R.id.cardContent).paddingLeft)

        val manual = bindWorstCase(widthDp = 320, sortOrder = SortOrder.MANUAL)
        val collapsedTitle = row.findViewById<TextView>(R.id.taskTitle).width
        val manualTitle = manual.findViewById<TextView>(R.id.taskTitle).width
        assertEquals(
            "the handle is visible on a manual sort", View.VISIBLE,
            manual.findViewById<View>(R.id.dragHandle).visibility
        )
        assertTrue("title ${collapsedTitle.asDp()}dp vs ${manualTitle.asDp()}dp",
            collapsedTitle >= manualTitle + dp(52))
    }


    @Test fun `the title keeps a readable measure`() {
        val row = bindWorstCase()
        val title = row.findViewById<TextView>(R.id.taskTitle)
        assertTrue("title is ${title.width.asDp()}dp, floor 128", title.width >= dp(128))
        // The budget spec §4.1 names for line A at 320dp, measured on the laid-out view: the title is the
        // only text on the card allowed to lose two lines' worth of characters, and below this it is
        // reading a truncated stub rather than a task name.
        assertTrue("title is ${title.width.asDp()}dp, budget 152", title.width >= dp(152))
        assertEquals("two lines and no more", 2, title.maxLines)
    }

    /**
     * The line-C give-way rule, on the one input where it can be reached in a JVM.
     *
     * At the widths the spec verifies — 320, 360, 393dp — the action cluster and the widest measured figure
     * close inside the column, so the rule sits behind a constraint that already holds and
     * `line C fits the column and its two halves do not overlap` is the assertion that matters there. This
     * fixture takes the row below the supported floor, where the box the figures are given is narrower than
     * the figures themselves, and asks which half of line C gives way: the numbers, never the buttons. The
     * same rule with real glyph metrics is on the device, where 320dp is narrow enough on its own
     * ([TaskCardStatesInstrumentedTest]'s running-stretch case).
     */
    @Test fun `when the figures cannot share line C they give way and the buttons stay`() {
        val roomy = bindWorstCase(widthDp = 320)
        val roomyFigures = roomy.findViewById<ViewGroup>(R.id.workRow)
        val needed = (0 until roomyFigures.childCount).map { roomyFigures.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
            .sumOf { it.width }
        val narrow = bindWorstCase(widthDp = 150)
        val box = narrow.findViewById<ViewGroup>(R.id.workRow).width
        // The need is measured on a row where the figures are drawn, and the box on the row where they are
        // not: without this difference the three assertions below would also pass with the rule deleted.
        assertTrue("the figures need ${needed}px, the narrow row gives them ${box}px — the fixture is not " +
            "narrow yet", needed > box)
        assertEquals("the total gives way", View.GONE,
            narrow.findViewById<View>(R.id.workTime).visibility)
        assertEquals("the ticker with it", View.GONE,
            narrow.findViewById<View>(R.id.workTicker).visibility)
        val group = narrow.findViewById<ViewGroup>(R.id.buttonGroup)
        assertEquals("and every action is still drawn", 3,
            (0 until group.childCount).map { group.getChildAt(it) }
                .count { it.visibility == View.VISIBLE })
    }

    /** Line C's own budget: the figures and the cluster together fit the column, and neither overlaps. */
    @Test fun `line C fits the column and its two halves do not overlap`() {
        val row = bindWorstCase(widthDp = 320)
        val content = row.findViewById<View>(R.id.cardContent)
        val figures = row.findViewById<View>(R.id.workRow)
        val group = row.findViewById<View>(R.id.buttonGroup)
        val column = content.width - content.paddingLeft - content.paddingRight -
            (row.findViewById<View>(R.id.categoryDot).left - content.paddingLeft)
        assertTrue("figures ${figures.width.asDp()}dp + buttons ${group.width.asDp()}dp in a " +
            "${column.asDp()}dp column", figures.width + group.width <= column)
        assertTrue("the cluster starts at ${group.left}, the figures end at ${figures.right}",
            group.left >= figures.right)
    }


    @Test fun `the category dot is always on screen and its name only when it can say something`() {
        val row = bindWorstCase()
        val name = row.findViewById<TextView>(R.id.taskCategory)
        val dot = row.findViewById<View>(R.id.categoryDot)
        val title = row.findViewById<View>(R.id.taskTitle)
        assertTrue("the dot heads line A, so it is left of the title (${dot.left} vs ${title.left})",
            dot.left < title.left)
        assertEquals("and the dot always carries the colour", View.VISIBLE, dot.visibility)
        // "Carries the colour" has to mean something measurable: tint is how this adapter already colours
        // a dot, so that is what the test reads rather than the drawable's own solid.
        assertEquals("the dot is tinted with the row's own category colour", categoryColor,
            dot.backgroundTintList?.defaultColor)
        // The rule is the room that is left on the line, not the name's own width: under a `Flow` a child
        // that lacks room is wrapped onto a line of its own, where it always has its full text width. The
        // layout pass here is real; the glyph metrics are not (Robolectric's LEGACY mode answers
        // `measureText` with the character count), so what this pins is that the adapter decides by the
        // room, in the same arithmetic the card runs. Whether the strings themselves fit is
        // `TaskCardWidthBudgetInstrumentedTest`'s business, on a renderer that measures text properly.
        val flow = row.findViewById<View>(R.id.lineBFlow)
        val pill = row.findViewById<View>(R.id.taskStatus)
        val due = row.findViewById<View>(R.id.taskDueDate)
        val gap = row.resources.getDimensionPixelSize(R.dimen.spacing_small)
        val room = flow.width - pill.width - due.width - 2 * gap
        val floor = dp(CardPresentation.CATEGORY_NAME_MIN_DP)
        assertEquals(
            "the name is drawn only when at least ${floor.asDp()}dp survive the pill (${pill.width}px) " +
                "and the date (${due.width}px) on a ${flow.width.asDp()}dp line; this one leaves " +
                "${room.asDp()}dp",
            if (room >= floor) View.VISIBLE else View.GONE, name.visibility
        )
    }

    @Test fun `every visible control is a 48dp target`() {
        val group = bindWorstCase().findViewById<ViewGroup>(R.id.buttonGroup)
        val controls = (0 until group.childCount).map { group.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
        assertTrue("the worst row still has actions to press", controls.isNotEmpty())
        controls.forEach {
            assertTrue("${it.resources.getResourceEntryName(it.id)} is ${it.width}x${it.height}",
                it.width >= dp(48) && it.height >= dp(48))
        }
    }

    @Test fun `the work line stays a sibling of the title, as its own test demands`() {
        val row = bindWorstCase()
        assertEquals(
            "TaskCardWorkTest asserts this; three lines inside ONE ConstraintLayout keeps it true, three " +
                "nested line-layouts would break a test that is measuring something else entirely",
            row.findViewById<View>(R.id.taskTitle).parent,
            row.findViewById<View>(R.id.workRow).parent
        )
    }

    @Test fun `nothing overlaps, and each line keeps its own band`() {
        val row = bindWorstCase()
        val dot = row.findViewById<View>(R.id.categoryDot)
        val title = row.findViewById<View>(R.id.taskTitle)
        val repeat = row.findViewById<View>(R.id.repeatIcon)
        val pill = row.findViewById<View>(R.id.taskStatus)
        val due = row.findViewById<TextView>(R.id.taskDueDate)
        val group = row.findViewById<View>(R.id.buttonGroup)

        // Line A is dot, title, icons — in that order, so each right edge precedes the next left.
        assertTrue("dot ${dot.left} before title ${title.left}", dot.left <= title.left)
        assertTrue("title ${title.right} before the repeat icon ${repeat.left}", title.right <= repeat.left)
        // Line B is pill, then date, then the name (spec §4.1). Asserting it the other way round would
        // pass on a card that had swapped them, which is the whole thing this test is for.
        assertTrue("pill ${pill.right} before the date ${due.left}", pill.right <= due.left)
        // Bands: no view from one line may share a vertical band with the next line.
        assertTrue("line A bottom ${title.bottom} at or above line B top ${pill.top}", title.bottom <= pill.top)
        assertTrue("line B bottom ${due.bottom} at or above line C top ${group.top}", due.bottom <= group.top)
        assertEquals("the date is one line tall at the default scale", 1, due.lineCount)
    }
}
