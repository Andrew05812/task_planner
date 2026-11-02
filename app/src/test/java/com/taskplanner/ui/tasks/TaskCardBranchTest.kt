package com.taskplanner.ui.tasks

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.tracking.TrackingPhase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The fold control on the task card — §7's other half, and the half the reader actually presses.
 *
 * §7's first half put the fold in the schema (`tasks.children_collapsed`, v18) and taught the list to obey it, which
 * is a promise nobody can keep from the keyboard: a stored view state needs a control on the row it belongs to. This
 * file is that control, asserted on an inflated card rather than in a state object, because everything worth checking
 * here is a box, a picture, or a word the reader hears:
 *
 *  * **Only a branch gets it.** A leaf with a chevron on it is a control that refuses, and the list would fill up
 *    with them on a notebook that is mostly flat.
  * **It says the state, in the name and in the picture.** `childrenCollapsed` decides which way the next press
 *    goes, so a screen reader hears the direction and a sighted reader sees the chevron turned. Same fact, two
 *    channels, because "not by colour alone" (§15) is also "not by rotation alone".
 *  * **It says the number.** The digit is what this press moves, and the same number is in the name, so nothing is
 *    spoken that the eye cannot see.
 *  * **It is one real target, and it stays inside the card.** The wrapper is the control: a press on the digit beside
 *    the chevron has to fold the branch, not open the task it was aiming at.
 *  * **It does not pay for itself out of the title.** That is the trade-off the placement in `item_task.xml` is
 *    argued for, and it is the one assertion here a later stage could break by "tidying" the chevron onto line A:
 *    `the_fold_does_not_take_width_from_the_title_it_belongs_under` compares a parent's title with a leaf's, so a
 *    fold that eats column fails here rather than in a photograph someone has to notice.
 *
 * **Coordinates are per-parent, and one draft of this file forgot that.** An early assertion compared the control's
 * own `right` (measured inside the cluster, so bounded by its own width) with the card's inner edge, and could not
 * have failed for any layout. Every containment check below walks up to `cardContent` accumulating offsets — see
 * [leftIn] — because that is the only comparison that can see a cluster crossing the gutter.
 *
 * **What is not measured here, and where it is.** Whether the running figures at 320dp still fit beside the wider
 * action cluster is a text-metrics question, and Robolectric's LEGACY graphics answer `Paint.measureText` with the
 * character count (`TaskCardWidthBudgetTest` documents the same wall): the box arithmetic below is real, the glyph
 * widths are not. The Russian and font-scale-1.3 frames of it are the device matrix's job (§19), on the renderer
 * that ships.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardBranchTest {

    private lateinit var context: Context

    /** Every task the card asked the screen to fold, in the order it asked. */
    private val foldRequests = mutableListOf<Long>()

    private companion object {
        const val DAY = 86_400_000L

        /** The row the width budget is argued against: everything line A can carry at once. */
        fun aWeeklyRule() = RecurrenceRule(
            userId = 1, unit = RepeatUnit.WEEK, interval = 1, weekDays = "1,3",
            dayOfMonth = 0, anchorAt = 0, cursorAt = 0
        )
    }

    private fun themed(widthDp: Int = 320, fontScale: Float = 1.0f): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_TaskPlanner)
        return context
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun Int.asDp() = toFloat() / context.resources.displayMetrics.density

    private fun head(collapsed: Boolean = false, title: String = "Read the reviewer's notes") = Task(
        id = 7, uuid = "branch-head", title = title, description = "", userId = 1,
        status = TaskStatus.TODO, childrenCollapsed = collapsed
    )

    private fun row(
        branch: Int,
        collapsed: Boolean = false,
        fullyMarked: Boolean = false,
        selection: Boolean = false
    ): TasksListItem.TaskRow {
        val base = if (!fullyMarked) {
            TasksListItem.TaskRow(
                head(collapsed), null, isSelected = false, foldRows = branch, selectionMode = selection
            )
        } else {
            TasksListItem.TaskRow(
                task = head(collapsed, "Т".repeat(120)).copy(
                    status = TaskStatus.IN_PROGRESS,
                    dueDate = System.currentTimeMillis() + 30L * DAY,
                    priority = com.taskplanner.data.model.Priority.HIGH
                ),
                // A real colour: `color = 0` is transparent, and a dot tinted from it "carries the colour" while
                // painting nothing — the defect spec §4.1 exists to fix.
                category = Category(id = 1, uuid = "c", name = "Диссертация", color = 0xFF2F6F5E.toInt(),
                    icon = "", userId = 1),
                isSelected = false,
                series = aWeeklyRule(),
                pendingReminders = 2,
                work = CardWork(closedSeconds = 6_300, liveSeconds = 60, phase = TrackingPhase.RUNNING),
                foldRows = branch,
                selectionMode = selection
            )
        }
        return base
    }

    private fun adapter() = TasksAdapter(
        onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {}, onDeleteRequest = {},
        onStart = {}, onPause = {}, onResume = {}, onComplete = {}, onWorkToggle = {},
        onToggleBranch = { task -> foldRequests += task.id },
        onToggleGroup = {}, onGroupMenu = { _, _ -> }, onStartDrag = {}
    )

    /** A bound card, its adapter and its holder, because the recycling case needs the holder back. */
    private class Card(val adapter: TasksAdapter, val holder: TasksAdapter.TaskViewHolder) {
        fun view(): View = holder.itemView
    }

    private fun submit(item: TasksListItem.TaskRow, adapter: TasksAdapter) {
        adapter.submitList(listOf(item))
        val deadline = System.currentTimeMillis() + 5_000
        while (adapter.currentList != listOf(item)) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("the adapter is still showing ${adapter.currentList}, not $item")
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun laidOut(card: Card, widthDp: Int): View {
        val row = card.view()
        row.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED
        )
        row.layout(0, 0, dp(widthDp), row.measuredHeight)
        // Line C's give-way rule is posted from `doOnLayout`, so one more idle is the layout the reader sees.
        shadowOf(Looper.getMainLooper()).idle()
        row.layout(0, 0, dp(widthDp), row.measuredHeight)
        return row
    }

    private fun bind(
        item: TasksListItem.TaskRow,
        widthDp: Int = 320,
        fontScale: Float = 1.0f,
        manualSort: Boolean = false
    ): View = bindCard(item, widthDp, fontScale, manualSort).let { laidOut(it, widthDp) }

    private fun bindCard(
        item: TasksListItem.TaskRow,
        widthDp: Int = 320,
        fontScale: Float = 1.0f,
        manualSort: Boolean = false
    ): Card {
        themed(widthDp, fontScale)
        val adapter = adapter()
        adapter.reorderEnabled = manualSort
        submit(item, adapter)
        val holder = adapter.createViewHolder(FrameLayout(context), adapter.getItemViewType(0))
            as TasksAdapter.TaskViewHolder
        adapter.bindViewHolder(holder, 0)
        return Card(adapter, holder)
    }

    /**
     * The view's left edge in [ancestor]'s coordinate space.
     *
     * `View.left` is relative to the view's own parent, so a child of a child has to be walked up to the space the
     * assertion is stated in — which is the card's content, where the gutter and the inner edge are defined.
     */
    private fun leftIn(view: View, ancestor: View): Int {
        var x = view.left
        var next = view.parent
        while (next !== ancestor) {
            val step = next as? View
                ?: throw AssertionError("this control is not inside the content the budget is stated against")
            x += step.left
            next = step.parent
        }
        return x
    }

    private fun topIn(view: View, ancestor: View): Int {
        var y = view.top
        var next = view.parent
        while (next !== ancestor) {
            val step = next as? View
                ?: throw AssertionError("this control is not inside the content the lines are stated against")
            y += step.top
            next = step.parent
        }
        return y
    }

    private fun fold(row: View) = row.findViewById<View>(R.id.branchFold)
    private fun chevron(row: View) = row.findViewById<ImageView>(R.id.branchToggle)
    private fun count(row: View) = row.findViewById<TextView>(R.id.branchCount)
    private fun content(row: View) = row.findViewById<View>(R.id.cardContent)

    @Test
    fun `a_row_with_rows_under_it_gets_the_fold_control`() {
        val row = bind(row(3))

        assertTrue("a branch has to offer the fold that hides it", fold(row).isVisible)
        assertTrue("and the chevron it is drawn with", chevron(row).isVisible)
    }

    @Test
    fun `a_row_with_nothing_under_it_gets_no_fold_control`() {
        val row = bind(row(0))

        assertFalse("a leaf with a chevron on it is a control that refuses", fold(row).isVisible)
    }

    @Test
    fun `the_digit_says_how_many_rows_this_press_moves_and_is_drawn`() {
        val row = bind(row(3))

        assertTrue("a number nobody can see is not an answer to §7", count(row).isVisible)
        assertEquals("the count the reader weighs the press against",
            3, count(row).text.toString().trim().toInt())
    }

    @Test
    fun `the_control_names_which_way_the_press_goes_and_how_many_it_moves`() {
        val row = bind(row(3))

        assertEquals(
            "an open branch is asked to close, and the number travels with the ask",
            context.resources.getQuantityString(R.plurals.branch_collapse, 3, 3),
            fold(row).contentDescription.toString()
        )
    }

    @Test
    fun `a_closed_branch_is_asked_to_open_instead`() {
        val row = bind(row(3, collapsed = true))

        assertEquals(
            "the folded row says the other direction, so the announcement matches the press",
            context.resources.getQuantityString(R.plurals.branch_expand, 3, 3),
            fold(row).contentDescription.toString()
        )
    }

    @Test
    fun `the_chevron_is_turned_by_the_stored_state`() {
        val open = chevron(bind(row(3)))
        val closed = chevron(bind(row(3, collapsed = true)))

        // Exact comparison, not a tolerance: the bind writes one of two literals, and a rotation that is neither has
        // not been set at all — which is the defect, and a 0.5f window would hide it as "close enough".
        assertEquals("an open branch points the way down, like the topic header's", 90f, open.rotation)
        assertEquals("and a closed one points at the rows it is hiding", 0f, closed.rotation)
    }

    @Test
    fun `the_whole_control_is_a_real_target`() {
        val row = bind(row(3, fullyMarked = true))
        val control = fold(row)

        // The wrapper, not the chevron: the digit in front of it is inside the reader's aim, and a press there has to
        // fold the branch rather than fall through to the card and open the task.
        assertTrue("the control is ${control.width}x${control.height}px, and the floor is ${dp(48)}px",
            control.width >= dp(48) && control.height >= dp(48))
        assertTrue("and it is the thing that answers a press", control.isClickable)
    }

    @Test
    fun `the_control_stays_inside_the_card_at_the_narrowest_screen`() {
        val row = bind(row(3, fullyMarked = true))
        val control = fold(row)
        val content = content(row)

        assertTrue("the control's right edge is ${leftIn(control, content) + control.width}px against an inner " +
                "edge of ${content.width - content.paddingRight}px",
            leftIn(control, content) + control.width <= content.width - content.paddingRight)
    }

    @Test
    fun `the_cluster_does_not_cross_the_gutter_the_fold_lives_in`() {
        val row = bind(row(3, fullyMarked = true), manualSort = true)
        val control = fold(row)
        val content = content(row)
        val gutter = row.findViewById<View>(R.id.categoryDot).left - content.paddingLeft

        assertTrue("the cluster starts at ${leftIn(control, content)}px, and the left column ends at ${gutter}px " +
                "— a control that slides past the gutter is drawn over the indent that says where the task is",
            leftIn(control, content) >= gutter)
    }

    @Test
    fun `the_fold_does_not_take_width_from_the_title_it_belongs_under`() {
        // Manual sort, because that is the case the 64dp gutter and the 152dp title are both stated against: a
        // hidden handle opens the column by 52dp, and a comparison run there would pass on a card that had taken
        // the fold's width out of the gutter instead of the title.
        val asLeaf = bind(row(0, fullyMarked = true), manualSort = true)
        val asBranch = bind(row(3, fullyMarked = true), manualSort = true)
        val leafTitle = asLeaf.findViewById<TextView>(R.id.taskTitle)
        val branchTitle = asBranch.findViewById<TextView>(R.id.taskTitle)

        // The line the fold lives on is line C, and line A is what the 320dp budget defends. This is the whole
        // placement argument, stated as arithmetic: put the control in front of the title instead and this
        // comparison fails on a card that still looks fine at 393dp.
        assertEquals("a parent's title keeps the measure a leaf's gets", leafTitle.width, branchTitle.width)
        assertTrue("and the title is still at spec §4.1's allowance: ${branchTitle.width.asDp()}dp",
            branchTitle.width >= dp(152))
        assertEquals("the left column is untouched: the fold did not move the gutter",
            dp(64), asBranch.findViewById<View>(R.id.categoryDot).left - content(asBranch).paddingLeft)
    }

    @Test
    fun `a_press_on_the_control_asks_for_the_row_it_was_drawn_on`() {
        val row = bind(row(3))

        fold(row).performClick()

        assertEquals("the card says which task to fold, and nothing else", listOf(7L), foldRequests.toList())
    }

    @Test
    fun `selection_mode_withdraws_the_fold_from_a_row_being_batched`() {
        val row = bind(row(3, selection = true))

        assertFalse(
            "a fold that hides rows the reader has already picked would leave the batch holding cards nobody " +
                "can see, so the control stands down exactly like the drag handle does",
            fold(row).isVisible
        )
    }

    @Test
    fun `a_card_that_drew_a_branch_and_then_a_leaf_gives_the_control_up`() {
        val card = bindCard(row(3))
        val branchView = laidOut(card, 320)
        assertTrue("the branch drew a fold onto this holder", fold(branchView).isVisible)

        // The same holder, asked for the next row — which is what a scrolling list does, and the only way a card
        // that only ever turns the control *on* can be caught.
        submit(row(0), card.adapter)
        card.adapter.bindViewHolder(card.holder, 0)
        laidOut(card, 320)

        assertFalse("the leaf drawn into it does not inherit a fold", fold(card.view()).isVisible)
    }

    @Test
    fun `a_card_that_drew_a_closed_branch_and_then_an_open_one_turns_the_chevron_back`() {
        val card = bindCard(row(3, collapsed = true))
        laidOut(card, 320)
        assertEquals("the closed row turned it to point at what it is hiding", 0f,
            chevron(card.view()).rotation)

        submit(row(3), card.adapter)
        card.adapter.bindViewHolder(card.holder, 0)
        laidOut(card, 320)

        assertEquals("and the open row drawn into the same holder turns it back", 90f,
            chevron(card.view()).rotation)
    }

    @Test
    fun `the_figures_give_way_before_the_fold_does`() {
        // The cost the placement pays is line C's own: this app already hides the measured time rather than shrink a
        // control. Run below the supported floor, where the box the figures are given is narrower than they are, so
        // the rule is reached in a JVM at all — the same rule `TaskCardWidthBudgetTest` states for the buttons.
        val roomy = bind(row(3, fullyMarked = true))
        val roomyFigures = roomy.findViewById<ViewGroup>(R.id.workRow)
        val needed = (0 until roomyFigures.childCount).map { roomyFigures.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
            .sumOf { it.width }
        val narrow = bind(row(3, fullyMarked = true), widthDp = 150)
        val box = narrow.findViewById<ViewGroup>(R.id.workRow).width

        // Without this the two assertions below would also pass on a row that was never narrow, which is the shape
        // of vacuous test `TaskCardWidthBudgetTest` had to defend against once already.
        assertTrue("the figures need ${needed}px and the narrow row gives them ${box}px — the fixture is not " +
                "narrow yet", needed > box)
        assertEquals("the whole line gives way, dot included: a lone dot over no figure says nothing",
            View.GONE, narrow.findViewById<View>(R.id.workRow).visibility)
        assertTrue("and the fold is still drawn, because controls outlive figures", fold(narrow).isVisible)
    }

    /**
     * The glyph is the topic header's, and the header's fold is the convention this card joins.
     *
     * Read off the layout source and scoped to the element, because an `ImageView` keeps its `android:src` as a
     * drawable rather than as the id it came from, and a sweep over the whole file would pass on a comment that
     * merely mentions the drawable.
     */
    @Test
    fun `the_fold_wears_the_glyph_the_topic_header_already_uses`() {
        val card = cardLayoutOf("item_task.xml")
        val header = cardLayoutOf("item_group_header.xml")
        val toggleElement = card.substringAfter("branchToggle").substringBefore("/>")
        val headerChevron = header.substringAfter("groupChevron").substringBefore("/>")

        assertTrue("the card's chevron element carries the shared glyph, not one of its own: $toggleElement",
            toggleElement.contains("""android:src="@drawable/ic_chevron_right""""))
        assertTrue("and it is the same glyph the topic header turns: $headerChevron",
            headerChevron.contains("""android:src="@drawable/ic_chevron_right""""))
    }

    private fun cardLayoutOf(name: String): String {
        val tried = listOf(
            File("src/main/res/layout/$name"),
            File("app/src/main/res/layout/$name"),
            File("../$name")
        )
        val file = tried.firstOrNull(File::exists)
            ?: throw AssertionError("none of ${tried.map { it.path }} holds $name — this test cannot say " +
                "anything about a layout it cannot read")
        return file.readText()
    }

    @Test
    fun `the_fold_clears_the_touch_floor_at_the_largest_text_too`() {
        val row = bind(row(3, fullyMarked = true), widthDp = 320, fontScale = 1.3f)
        val control = fold(row)

        // The box is defined in dp, so this is a re-measure rather than a re-decision: what a real glyph does to the
        // cluster at 1.3 is the device matrix's, and §19 photographs it.
        assertTrue("a control that shrinks below the floor at 1.3 is a control a thumb misses: " +
                "${control.width}x${control.height}",
            control.width >= dp(48) && control.height >= dp(48))
    }

    @Test
    fun `the_fold_is_part_of_the_action_cluster_and_below_line_A`() {
        val row = bind(row(3, fullyMarked = true))
        val title = row.findViewById<TextView>(R.id.taskTitle)
        val control = fold(row)
        val group = row.findViewById<ViewGroup>(R.id.buttonGroup)

        // Which child of the cluster it is is a layout choice nobody is owed; belonging to the cluster is the claim
        // this test defends, so it is stated as parentage rather than as an index.
        assertSame("the fold joins the action cluster, where this card spends its controls", group, control.parent)
        val controlTopInCard = topIn(control, content(row))
        assertTrue("and it sits below line A, not across it: line $controlTopInCard under title ${title.bottom}",
            controlTopInCard >= title.bottom)
    }
}
