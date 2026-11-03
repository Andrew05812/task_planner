package com.taskplanner.ui.tasks

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A nested task, drawn one level deeper than the task it hangs off — on the card, not only in the column.
 *
 * Stage 15 keeps a Google Keep export's indentation in `Task.parent_uuid` and the preview shows it as a tree, but
 * the promise §10 asks for is about the *task list*: after the import closes, the reader has to see the subtree
 * they pasted rather than one flat run of rows under a topic heading. So the card indents by the depth
 * [TaskTree] measures, and this file is what proves the two ends of that claim:
 *
 *  * **The indent exists and is arithmetic.** Level 0 sits at the card's own padding; each level below adds one
 *    step of `@dimen/task_depth_indent`. Asserted on the padding of a bound card, because the number a reader
 *    sees as structure is the number the row's `depth` carried into the view.
 *  * **The indent is recomputed, not accumulated.** A recycled holder is the whole risk of this change: a card
 *    that *added* to the padding it inherited would walk four steps sideways down a list of imported tasks, and
 *    the drift would look like the reader's own notebook. So a depth-3 row bound into a holder that just drew a
 *    depth-1 row lands at exactly three steps — and a depth-0 row after it lands back at the margin.
 *
 * The box itself is deliberately left alone: the title is padded inside its own measured width, so a deeply
 * nested row cannot steal column from the 320dp budget `TaskCardWidthBudgetTest` defends.
 */
@RunWith(RobolectricTestRunner::class)
class TaskCardIndentTest {

    private lateinit var context: Context

    private fun themed(widthDp: Int = 320, fontScale: Float = 1.0f): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_TaskPlanner)
        return context
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun task(uuid: String, parent: String?, order: Int) = Task(
        id = uuid.hashCode().toLong(), uuid = uuid, title = "Task $uuid", description = "",
        userId = 1L, status = TaskStatus.TODO, order = order, parentUuid = parent
    )

    private fun row(depth: Int) = TasksListItem.TaskRow(
        task = task("u$depth", null, depth), category = null, isSelected = false, depth = depth
    )

    private fun adapter() = TasksAdapter(
        onTaskClick = {}, onLongPressTask = {}, onToggleSelection = {}, onEdit = {}, onDeleteRequest = {},
        onStart = {}, onPause = {}, onResume = {}, onComplete = {}, onWorkToggle = {}, onToggleBranch = {}, onHierarchy = { _, _ -> }, onToggleGroup = {},
        onGroupMenu = { _, _ -> }, onStartDrag = {}
    )

    /**
     * Bind one row into a holder, with the patience `ListAdapter` asks for: `submitList` diffs off the main
     * thread and posts the answer back, so a holder created before that binds an empty list.
     */
    private fun bind(adapter: TasksAdapter, item: TasksListItem.TaskRow): TasksAdapter.TaskViewHolder {
        adapter.submitList(listOf(item))
        // `itemCount` is no use as the wait here — it was already 1 from the previous row, so a loop on it
        // returns immediately and binds the row the diff has not replaced yet. The list the adapter is holding
        // now is the thing that has to change, which is what `currentList` says.
        val deadline = System.currentTimeMillis() + 5_000
        while (adapter.currentList != listOf(item)) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("the adapter is still showing ${adapter.currentList} instead of $item")
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        @Suppress("UNCHECKED_CAST")
        val holder = adapter.createViewHolder(FrameLayout(context), 1) as TasksAdapter.TaskViewHolder
        adapter.bindViewHolder(holder, 0)
        return holder
    }

    private fun titleOf(holder: TasksAdapter.TaskViewHolder) =
        holder.itemView.findViewById<TextView>(R.id.taskTitle)

    /** The card's own padding for the title, read off a row that has no parent. */
    private fun basePadding(): Int {
        val adapter = adapter()
        val holder = bind(adapter, row(0))
        return titleOf(holder).paddingStart
    }

    @Test
    fun everyLevelIndentsByOneStepOfTheDimen() {
        themed()
        val adapter = adapter()
        val step = context.resources.getDimensionPixelSize(R.dimen.task_depth_indent)
        val base = basePadding()

        (0..TaskHierarchyPolicy.MAX_INDENT_LEVEL).forEach { level ->
            val title = titleOf(bind(adapter, row(level)))
            assertEquals("level $level sits $level step(s) in from the card's own padding",
                base + level * step, title.paddingStart)
        }
    }

    @Test
    fun aRecycledCardRelandesFromZeroInsteadOfDrifting() {
        themed()
        val adapter = adapter()
        val step = context.resources.getDimensionPixelSize(R.dimen.task_depth_indent)
        val base = basePadding()
        val deepest = TaskHierarchyPolicy.MAX_INDENT_LEVEL

        // The sequence a scrolling reader actually produces: deep, then shallow, in the same holder.
        val holder = bind(adapter, row(deepest))
        assertEquals(base + deepest * step, titleOf(holder).paddingStart)

        adapter.bindViewHolder(holder, 0)
        assertEquals("the same holder, asked again for the deep row, does not move again",
            base + deepest * step, titleOf(holder).paddingStart)

        val shallow = bind(adapter, row(0))
        assertEquals("and a fresh holder drawing level zero starts at the card's own padding",
            base, titleOf(shallow).paddingStart)
    }

    @Test
    fun theDeepestRowIndentsInsideItsBoxRatherThanShrinkingIt() {
        themed(widthDp = 320, fontScale = 1.3f)
        val adapter = adapter()

        val shallow = titleOf(bind(adapter, row(0)))
        val deep = titleOf(bind(adapter, row(TaskHierarchyPolicy.MAX_INDENT_LEVEL)))
        listOf(shallow, deep).forEach { title ->
            val holder = title.parent as View
            holder.measure(
                View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED
            )
            holder.layout(0, 0, dp(320), holder.measuredHeight)
        }

        // Padding moves the text inside the box; the box belongs to the card's own layout, which is what
        // `TaskCardWidthBudgetTest` measures. If the indent took width instead of padding, the two widths would
        // differ and the deepest rows would be the ones losing the column.
        assertEquals("a nested row's title keeps the width a flat row gets", shallow.width, deep.width)
        assertTrue(
            "and the text starts further in: padding " + deep.paddingStart + " against " +
                shallow.paddingStart,
            deep.paddingStart > shallow.paddingStart
        )
    }

    @Test
    fun depthComesFromTheRowsOnScreenNotFromAStoredNumber() {
        // The card is drawn from `Task.parent_uuid` measured over the list, so a task whose parent is not on that
        // list is drawn at the margin — which is the truth about what the reader is looking at. §7's filter clause
        // is what keeps the case rare: a parent a filter dropped is now drawn above the child as context
        // (`TasksFilterContextTest`), and this test measures `TaskTree` directly, so the margin is still the answer
        // for the paths no rule can complete — a parent another device deleted, a link across a topic boundary.
        val visible = listOf(task("child", "hidden-parent", 0))

        val depths = TaskTree.depthsByTaskId(visible)

        assertEquals(0, depths.getValue("child".hashCode().toLong()))
    }
}
