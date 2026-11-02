package com.taskplanner.data.hierarchy

import com.taskplanner.data.model.Task

/**
 * The rules of the task tree, computed over the rows themselves and free of both the database and the screen.
 *
 * Stage 15 put a reader's nested notebook into `tasks.parent_uuid`; Stage 16 lets the reader change it. Every answer
 * this file gives — which rows are siblings, what a subtree holds, where a move would land, whether a move is
 * allowed at all — has to be the same answer the parser gives an imported line, the renderer gives a card, the
 * backup gives a restored row and the sync gives a document from another device. So there is one place for these
 * rules, and it is here: a second copy is how two of those components end up disagreeing about the reader's own tree.
 *
 * Nothing in this file reaches a database or a view. The transactional half is
 * [com.taskplanner.data.repository.TaskHierarchyRepository]; the gesture half is
 * [com.taskplanner.ui.tasks.TasksFragment], which asks before it moves a row.
 */
object TaskHierarchyPolicy {

    /**
     * The block of the ordering a row belongs to.
     *
     * A plain drag reorders a row *among its siblings*, which means rows of the same profile, under the same topic
     * and hanging from the same parent. Two of those three are already what the screen groups by; the parent is the
     * new one, and it is the reason a reorder and a reparenting cannot share a gesture: without the parent in the
     * key, dragging a child next to a row of another parent would quietly move it under that row.
     */
    data class SiblingKey(val userId: Long, val groupId: String?, val parentUuid: String?)

    fun siblingKeyOf(task: Task): SiblingKey = SiblingKey(task.userId, task.groupId, task.parentUuid)

    /**
     * The one sequence these rows describe.
     *
     * [canonicalSequence] is what a reorder reads and writes, so it settles the ties the list query leaves open.
     * SQLite sorts a NULL `due_date` *before* every date, and it returns rows sharing an `order` in whichever order
     * the planner reaches them; the identifier makes both of those answers fixed, so a reorder, a render and a
     * re-read of the same database agree. The screen's own query is left as it is because after one move a profile
     * holds no shared positions for the difference to show up in.
     */
    fun canonicalSequence(rows: List<Task>): List<Task> =
        rows.sortedWith(compareBy({ it.order }, { it.dueDate ?: Long.MAX_VALUE }, { it.id }))

    /**
     * The identities more than one row of this set claims, blank ones excluded.
     *
     * `tasks.uuid` carries no unique index, so this is not a hypothetical: a pull, a restored backup or a legacy row
     * can leave two live rows on one identity, and then "everything under that identity" is a question with two
     * answers. A blank uuid is not on this list because nothing is ever looked up by it — [subtreeOf] treats a blank
     * identity and a blank link as "no parent", which is what they mean.
     */
    fun ambiguousUuids(rows: List<Task>): Set<String> =
        rows.filter { it.uuid.isNotBlank() }
            .groupingBy { it.uuid }
            .eachCount()
            .filterValues { it > 1 }
            .keys

    /**
     * Whether the block a move would carry can be named: the head's own identity, and every identity inside the
     * block, have to belong to exactly one row.
     *
     * Narrow on purpose. Two children of a doubled parent are still siblings of each other, and reordering them
     * needs no answer about who their parent is — refusing that would turn one bad row into a reader who cannot use
     * their list at all.
     */
    fun blockIsKnowable(head: Task, block: List<Task>, ambiguous: Set<String>): Boolean =
        head.uuid !in ambiguous && block.none { it.uuid in ambiguous }

    /**
     * The head and everything hanging under it, in the order a reader reads a tree: parent first, then each child's
     * own branch complete before the next sibling starts.
     *
     * Iterative with a visited set, for two reasons that are not decoration. `parent_uuid` is a plain column with no
     * database foreign key, so a cycle is a thing a sync pull or a hand-edited backup can write, and the main thread
     * must not be the place that finds out. And a row whose parent is not among [rows] is its own subtree: the list
     * cannot show a row it does not hold, and inventing an ancestor would move a row nobody grabbed.
     *
     * **A blank identity is not a parent.** `tasks.uuid` defaults to `''`, and a pull that carries an empty
     * `parent_uuid` writes one, so "the rows whose parent is the empty string" is a bucket holding every legacy row
     * of the profile rather than the children of one. Treating it as a parent would let a single drag lift a dozen
     * unrelated rows along with it, which is the mistake Stage 16 §4 exists to find; here it simply cannot happen.
     */
    fun subtreeOf(rows: List<Task>, head: Task): List<Task> {
        val childrenOf = rows.filter { !it.parentUuid.isNullOrBlank() }
            .groupBy { it.parentUuid }
            .mapValues { (_, kids) -> canonicalSequence(kids) }
        val walked = ArrayList<Task>()
        val seen = HashSet<Long>()
        val pending = kotlin.collections.ArrayDeque<Task>()
        pending.addLast(head)
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            if (!seen.add(next.id)) continue
            walked += next
            if (next.uuid.isNotBlank()) {
                childrenOf[next.uuid]?.asReversed()?.forEach { kid -> pending.addLast(kid) }
            }
        }
        return walked
    }

    /**
     * Why this pair of rows may not be ordered against each other, or null when they may.
     *
     * Checked in this order on purpose: a row dropped onto its own child is refused as a subtree problem rather than
     * as a sibling one, because the reason the reader gets is "that is inside the thing you are moving".
     */
    fun reorderRefusal(head: Task, anchor: Task, moving: Set<Long>): HierarchyMoveRefusal? = when {
        head.id == anchor.id -> HierarchyMoveRefusal.SAME_ROW
        siblingKeyOf(head).groupId != siblingKeyOf(anchor).groupId -> HierarchyMoveRefusal.DIFFERENT_TOPIC
        siblingKeyOf(head).parentUuid != siblingKeyOf(anchor).parentUuid -> HierarchyMoveRefusal.DIFFERENT_PARENT
        anchor.id in moving -> HierarchyMoveRefusal.ANCHOR_IN_SUBTREE
        else -> null
    }

    /**
     * What the gesture of dropping [draggedTaskId] onto [targetTaskId] means, decided over the rows on screen.
     *
     * [sequence] is the list as the reader is looking at it, in the order it is drawn — so a row the filter hides is
     * simply absent, and the plan says which side of the visible neighbour the row lands on. It is deliberately not
     * the stored order: after a first step of a drag the list is ahead of the database, and reading the database
     * here would make the second step of one gesture contradict the first.
     *
     * The answer is the same whether or not a filter is on, which is the property Stage 16 §3 asks for: applying a
     * filter and clearing it cannot change where a dropped row ends up.
     */
    fun planSiblingDrag(sequence: List<Task>, draggedTaskId: Long, targetTaskId: Long): HierarchyMoveResult {
        val dragged = sequence.firstOrNull { it.id == draggedTaskId }
            ?: return HierarchyMoveResult.Refused(HierarchyMoveRefusal.ROW_MISSING)
        val anchor = sequence.firstOrNull { it.id == targetTaskId }
            ?: return HierarchyMoveResult.Refused(HierarchyMoveRefusal.ANCHOR_MISSING)
        val block = subtreeOf(sequence, dragged)
        reorderRefusal(dragged, anchor, block.map { it.id }.toSet())?.let {
            return HierarchyMoveResult.Refused(it)
        }
        return HierarchyMoveResult.Accepted(
            movedTaskIds = block.map { it.id },
            anchorTaskId = anchor.id,
            placeAfter = sequence.indexOf(anchor) > sequence.indexOf(dragged)
        )
    }

    /**
     * The profile's whole order after [block] is set down beside [anchorTaskId], or null when there is no such place.
     *
     * The block leaves the sequence and returns on one side of its anchor, in the order it already had; every other
     * row keeps its place against every row the block did not cross. Renumbering the result is what makes the
     * positions unique again, which is the half of the fix the filtered re-numbering never did.
     */
    fun sequenceAfterMove(
        all: List<Task>,
        block: List<Task>,
        anchorTaskId: Long,
        placeAfter: Boolean
    ): List<Task>? {
        val moving = block.map { it.id }.toSet()
        val rest = all.filterNot { it.id in moving }
        val at = rest.indexOfFirst { it.id == anchorTaskId }
        if (at < 0) return null
        val insertAt = if (placeAfter) at + 1 else at
        return rest.subList(0, insertAt) + block + rest.subList(insertAt, rest.size)
    }
}
