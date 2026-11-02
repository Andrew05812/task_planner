package com.taskplanner.ui.tasks

import com.taskplanner.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The depth a task card indents by, computed from the parent links the visible rows carry.
 *
 * This exists because of Stage 15's promise: a Keep export's indentation is the reader's structure, the import
 * keeps it in `parent_uuid` (schema v17), and §10 says the imported subtree has to be *visible* in the task list
 * rather than flattened back to one level the moment it lands. Saving a tree and drawing it flat would repeat the
 * mistake the import was rewritten to stop, one layer later.
 *
 * Four cases, each a way the screen could be wrong while the import stays right:
 *
 *  * **A chain.** A topic's own child sits at the margin; each level below it steps in once.
 *  * **A parent that is not on screen.** A filter that hides the line a task hangs off must not invent a level
 *    for it or lose its place: with nothing above it *here*, it is drawn at the margin, because that is the
 *    truth about the list the reader is looking at.
 *  * **A chain deeper than the contract.** The parser clamps at four levels and says so, but `parent_uuid` is a
 *    plain column that other paths — a sync pull, a future editor — can write. The card stops counting.
 *  * **A cycle.** Same reason: a loop in a column with no database foreign key must cost a bounded indent, not a
 *    hang or a stack overflow on the reader's main thread.
 */
@RunWith(RobolectricTestRunner::class)
class TaskTreeDepthTest {

    private fun task(id: Long, uuid: String, parent: String?) = Task(
        id = id, uuid = uuid, title = uuid, description = "", userId = 1L, parentUuid = parent
    )

    @Test
    fun aChainNumbersEachLevelOneDeeperThanTheLast() {
        val chain = listOf(
            task(1L, "a", null), task(2L, "b", "a"), task(3L, "c", "b"), task(4L, "d", "c")
        )

        val depths = TaskTree.depthsByTaskId(chain)

        assertEquals(listOf(0, 1, 2, 3), chain.map { depths.getValue(it.id) })
    }

    @Test
    fun siblingsUnderOneParentAllReadTheSameDepth() {
        val family = listOf(
            task(1L, "child-of-topic", null),
            task(2L, "first", "child-of-topic"),
            task(3L, "second", "child-of-topic"),
            task(4L, "third", "child-of-topic")
        )

        val depths = TaskTree.depthsByTaskId(family)

        assertEquals(listOf(1, 1, 1), family.drop(1).map { depths.getValue(it.id) })
    }

    @Test
    fun aTaskWhoseParentIsNotAmongTheseRowsIsDrawnAtTheMargin() {
        // "b" is filtered out; "c" cannot be measured against a row the list does not hold, so it is drawn as the
        // top of what is here rather than at a level invented from a row the reader cannot see.
        val visible = listOf(task(3L, "c", "b"))

        val depths = TaskTree.depthsByTaskId(visible)

        assertEquals(0, depths.getValue(3L))
    }

    @Test
    fun aChainDeeperThanTheContractStopsIndentingInsteadOfRunningOffTheCard() {
        val deep = (0 until 8).map { index ->
            task(index.toLong(), "n$index", if (index == 0) null else "n${index - 1}")
        }

        val depths = TaskTree.depthsByTaskId(deep)

        assertEquals("the deepest indent the card owns is the fourth level",
            TaskTree.MAX_INDENT_LEVEL, depths.getValue(7L))
        assertTrue("and it is never exceeded, whatever the column says",
            depths.values.all { it in 0..TaskTree.MAX_INDENT_LEVEL })
    }

    @Test
    fun aCycleInTheParentLinksCostsABoundedIndentRatherThanAHang() {
        val loop = listOf(task(1L, "a", "c"), task(2L, "b", "a"), task(3L, "c", "b"))

        val depths = TaskTree.depthsByTaskId(loop)

        assertEquals("every row of a loop is still drawn, at the deepest indent the card allows",
            listOf(3, 3, 3), loop.map { depths.getValue(it.id) })
        assertEquals(TaskTree.MAX_INDENT_LEVEL, depths.getValue(1L))
    }

    @Test
    fun aRowThatNamesItselfAsItsOwnParentIsNotATree() {
        val self = listOf(task(1L, "a", "a"))

        val depths = TaskTree.depthsByTaskId(self)

        assertEquals(0, depths.getValue(1L))
    }
}
