package com.taskplanner.data.hierarchy

import com.taskplanner.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The order a promote leaves the profile's rows in, read from the policy alone.
 *
 * The claim this file exists to hold is a shape, not a sentence: a row's subtree sits immediately after it, so the
 * stored sequence and the tree drawn from the parent links say the same thing. `TaskTree.depthsByTaskId` indents
 * from parent links and takes the sequence as it is written, so a sequence that puts a grandchild after its
 * parent's sibling is printed as a child stranded under the wrong card — which is what Stage 16 §4 found in the
 * promote branch and §4b fixes. These cases pin the arrangement; the repository only writes it.
 *
 * The head appears in the answers below even though it is the row being deleted: a delete is a tombstone, the row
 * stays in the table, and its keeping its number is the thing that bounds the write to the block. A reader takes the
 * live rows, which is the same sequence without it.
 */
@RunWith(RobolectricTestRunner::class)
class PromoteSequenceTest {

    /** The rows named by [titles], each holding the position its place in the list names, so a re-numbering shows. */
    private fun profile(vararg titles: Pair<String, String?>): List<Task> =
        titles.mapIndexed { index, (title, parent) ->
            Task(
                id = (index + 1).toLong(),
                uuid = title,
                title = title,
                description = "",
                userId = 1L,
                parentUuid = parent,
                order = index
            )
        }

    private fun headOf(stored: List<Task>): Task = stored.first { it.title == "head" }

    private fun promote(stored: List<Task>): List<Task> {
        val head = headOf(stored)
        return TaskHierarchyPolicy.sequenceAfterPromote(stored, head, stored.filter { it.parentUuid == head.uuid })
    }

    @Test
    fun a_lifted_child_is_read_with_the_rows_under_it_rather_than_after_its_sibling() {
        val stored = profile("head" to null, "first" to "head", "second" to "head", "under-first" to "first")

        val after = promote(stored)

        assertEquals(
            "each child takes the head's place with its own block behind it",
            listOf("head", "first", "under-first", "second"),
            after.map { it.title }
        )
    }

    @Test
    fun the_answer_holds_every_row_of_the_profile_once() {
        val stored = profile(
            "before" to null,
            "head" to null,
            "first" to "head",
            "second" to "head",
            "under-first" to "first",
            "deep" to "under-first",
            "after" to null
        )

        val after = promote(stored)

        assertEquals("no row is lost", stored.map { it.id }.sorted(), after.map { it.id }.sorted())
        assertEquals("and none is placed twice", after.map { it.id }.distinct().size, after.size)
    }

    @Test
    fun rows_outside_the_deleted_block_keep_the_places_they_held() {
        val stored = profile(
            "before" to null,
            "head" to null,
            "first" to "head",
            "second" to "head",
            "under-first" to "first",
            "after" to null
        )

        val after = promote(stored)

        assertEquals(
            "only the block the head headed was re-arranged, so the neighbours write no position",
            listOf("before" to 0, "after" to 5),
            after.mapIndexed { index, row -> row.title to index }.filter { it.first in listOf("before", "after") }
        )
    }

    @Test
    fun a_child_already_printed_with_its_own_rows_is_not_moved() {
        // The positive control: the shape a promote reaches today without splitting anything, so the fix must leave
        // the sequence — and every position in it — exactly as it found it.
        val stored = profile("head" to null, "child" to "head", "grandchild" to "child", "neighbour" to null)

        val after = promote(stored)

        assertEquals("the answer is the sequence it was given", stored.map { it.title }, after.map { it.title })
        // Whether that answer costs any *write* is not this class's to prove: positions are assigned by place only
        // when the repository hands the list to `writePositions`, and a fixture whose rows already sit at their own
        // index could not tell a no-op from a re-numbering. `HierarchyDeleteTest` measures it against a database.
    }

    @Test
    fun three_children_each_keep_their_own_block_and_their_own_order() {
        val stored = profile(
            "head" to null,
            "one" to "head",
            "two" to "head",
            "three" to "head",
            "under-two" to "two",
            "deep-under-two" to "under-two"
        )

        val after = promote(stored)

        assertEquals(
            "the children in the order they were read, each with everything under it",
            listOf("head", "one", "two", "under-two", "deep-under-two", "three"),
            after.map { it.title }
        )
    }

    @Test
    fun no_row_is_left_printed_before_its_own_parent_or_apart_from_it() {
        val stored = profile(
            "head" to null,
            "first" to "head",
            "second" to "head",
            "under-first" to "first",
            "deep" to "under-first"
        )

        val after = promote(stored)

        val aboveItsParent = after.filter { row ->
            val parentAt = after.indexOfFirst { it.uuid == row.parentUuid }
            parentAt >= 0 && parentAt > after.indexOfFirst { it.id == row.id }
        }
        assertEquals("every row sits below the row it hangs from", emptyList<String>(), aboveItsParent.map { it.title })
        assertEquals("and no block is printed apart from its head", emptyList<String>(), splitBlocks(after))
    }

    @Test
    fun the_rows_a_reader_keeps_are_the_branch_with_its_head_taken_out() {
        val stored = profile("head" to null, "first" to "head", "second" to "head", "under-first" to "first")
        val head = headOf(stored)

        val kept = promote(stored).filterNot { it.id == head.id }

        assertEquals(
            "the head is the only row the delete takes, and it is read nowhere in what is left",
            listOf("first", "under-first", "second"),
            kept.map { it.title }
        )
        assertEquals("every other row is still there, once", stored.size - 1, kept.size)
        assertEquals("and none was duplicated by the lift", kept.map { it.id }.distinct().size, kept.size)
    }

    @Test
    fun a_head_this_sequence_does_not_hold_leaves_it_as_it_was() {
        val stored = profile("one" to null, "two" to "one")
        // An id the profile does not hold, paired with the whole profile as its "children": were the guard absent,
        // every row below would be taken as lifted and placed behind a head that is not there, and the answer would
        // come back empty rather than unchanged.
        val absent = Task(
            id = 99L,
            uuid = "gone",
            title = "gone",
            description = "",
            userId = 1L,
            parentUuid = null,
            order = 7
        )

        val after = TaskHierarchyPolicy.sequenceAfterPromote(stored, absent, stored)

        assertEquals("no head to give a place to, so the sequence is the one it was given", stored, after)
    }

    @Test
    fun a_row_written_before_the_parent_that_lifted_is_put_behind_it() {
        // The other way a split can be seeded: the grandchild written *ahead* of the child that owns it. The lifted
        // block is still the child with its own rows, and the answer still holds every row once.
        val stored = profile("head" to null, "early" to "parent", "parent" to "head")

        val after = promote(stored)

        assertEquals(
            "the child takes the head's place with its own row behind it",
            listOf("head", "parent", "early"),
            after.map { it.title }
        )
        assertEquals("and nothing is printed apart from its head", emptyList<String>(), splitBlocks(after))
    }

    /**
     * The titles of the rows whose own subtree is not printed, in order, directly behind them.
     *
     * The comparison is of two sequences rather than two sets: a branch printed with its rows scrambled inside it is
     * the same defect one row later, and a set comparison would call it whole.
     */
    private fun splitBlocks(rows: List<Task>): List<String> = rows.filter { head ->
        val block = TaskHierarchyPolicy.subtreeOf(rows, head).filterNot { it.id == head.id }
        if (block.isEmpty()) {
            return@filter false
        }
        val from = rows.indexOfFirst { it.id == head.id } + 1
        val window = rows.subList(from, minOf(from + block.size, rows.size))
        window.map { it.id } != block.map { it.id }
    }.map { it.title }
}
