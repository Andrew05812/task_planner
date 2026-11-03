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
     * The deepest task level a tree may hold under one topic.
     *
     * **This is the one number for the contract, and everything that bounds a tree reads it here.** Stage 15 ended
     * with two components each holding a constant called 3 and meaning different things by it: [KeepTextParser]'s
     * counted a topic as level 0 and so allowed three task levels, while the renderer allowed a fourth indent — one
     * level more than the parser would ever have written, and more than the 320dp card budget was photographed at.
     * The brief settles it (topic + three task levels), the parser and renderer now agree, and `HierarchyMoveTest`
     * fails if they drift apart again.
     */
    const val MAX_TASK_LEVELS: Int = 3

    /** The deepest indent a card may draw: a topic is a header, not a step of indentation. */
    const val MAX_INDENT_LEVEL: Int = MAX_TASK_LEVELS - 1

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
     * How deep each row of this set sits, counted from one: a topic's own child is level 1.
     *
     * Measured within the rows given, because that is the only ancestry a card can be held to — and the same rule is
     * what makes [heightOf] work on a subtree on its own, where the head counts as level 1. A row whose parent is
     * blank, missing or already on the walk contributes no further level, so a cycle costs a stopped walk and not a
     * stack overflow on the reader's main thread.
     */
    fun levelsByUuid(rows: List<Task>): Map<String, Int> {
        val byUuid = rows.filter { it.uuid.isNotBlank() }.associateBy { it.uuid }
        return byUuid.values.associate { row ->
            var level = 1
            var walker = row.parentUuid
            val seen = hashSetOf(row.uuid)
            while (walker != null && walker.isNotBlank() && seen.add(walker)) {
                val parent = byUuid[walker] ?: break
                if (level == MAX_TASK_LEVELS) break
                level++
                walker = parent.parentUuid
            }
            row.uuid to level
        }
    }

    /** The height of a subtree: 1 for a row with nothing under it, one more for each level below. */
    fun heightOf(rows: List<Task>, head: Task): Int =
        levelsByUuid(subtreeOf(rows, head)).values.maxOrNull() ?: 1

    /**
     * Whether [blockHeight] rows can hang under a parent at [parentLevel] and still stay inside the contract.
     *
     * The bound is the deepest row of the block, not the grabbed one — which is why an indent of a row carrying a
     * child is refused a level earlier than the same indent of a leaf, and why the reader is told the reason before
     * the tree is written past the depth the card was measured to fit.
     */
    fun fitsUnder(parentLevel: Int, blockHeight: Int): Boolean = parentLevel + blockHeight <= MAX_TASK_LEVELS

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
     * The rows the reader cannot see because an ancestor of theirs is folded away.
     *
     * Measured over [rows] — the profile's own set rather than the screen's filtered subset — because the fold is a
     * fact about the tree: a branch closed two levels up still hides what is under it even when a filter dropped the
     * row in between, and a renderer that only looked at what survived its filter would uncover exactly the rows the
     * reader closed. The fold belongs to the branch, not to the generation: a grandchild is away when its
     * grandparent is folded and its own parent is open.
     */
    fun collapsedAwayIds(rows: List<Task>): Set<Long> {
        val byUuid = rows.filter { it.uuid.isNotBlank() }.associateBy { it.uuid }
        return rows.filter { row -> ancestorsOf(row, byUuid).any { it.childrenCollapsed } }.map { it.id }.toSet()
    }

    /**
     * What one card's fold would move, as the reader sees the screen right now.
     *
     * Two numbers, because a fold is a pair of presses: [FoldDelta.hides] is how many rows on screen hang under this
     * row, and [FoldDelta.reveals] is how many rows this row's *own* fold is holding back. A card drawn open shows
     * the first, a card drawn closed shows the second, and either way the digit on it is the truth about what the
     * next press does — which is the only thing a reader can check it against.
     *
     * Two cases the older "count every descendant" answer got wrong, and that a card would have advertised:
     *
     *  * **A fold inside a fold.** Close the child, then look at the head above it: the head's subtree is two rows
     *    and its press moves one, because the second is already away behind the child. Attributing a hidden row to
     *    the *nearest* folded ancestor rather than to every folded ancestor is what says so — opening the head does
     *    not reveal a row another fold is holding.
     *  * **A filter that dropped the branch.** A parent whose children are all out of view has nothing on screen to
     *    hide, so it gets no chevron: a control that changes nothing a reader can see is a decoration.
     *
     * [rowsAfterFilters] is the set the filters left *before* the fold was applied, and [drawn] is the set that
     * reached the list — which is what separates "hidden by a fold" from "not in this question". One pass, each row
     * walking its own chain, so a 10k-task profile costs 10k walks bounded by the depth rather than 10k subtree
     * rebuilds (§14).
     */
    fun foldDeltas(rowsAfterFilters: List<Task>, drawn: Set<Long>): Map<Long, FoldDelta> {
        val byUuid = rowsAfterFilters.filter { it.uuid.isNotBlank() }.associateBy { it.uuid }
        val deltas = HashMap<Long, FoldDelta>()
        rowsAfterFilters.forEach { row ->
            val chain = ancestorsOf(row, byUuid)
            if (row.id in drawn) {
                chain.forEach { ancestor ->
                    deltas[ancestor.id] = deltas.getOrDefault(ancestor.id, FoldDelta()).let { it.copy(hides = it.hides + 1) }
                }
            } else {
                val holder = chain.firstOrNull { it.childrenCollapsed }
                if (holder != null) {
                    deltas[holder.id] = deltas.getOrDefault(holder.id, FoldDelta()).let { it.copy(reveals = it.reveals + 1) }
                }
            }
        }
        return deltas
    }

    /** What a card's fold would move: see [foldDeltas]. */
    data class FoldDelta(val hides: Int = 0, val reveals: Int = 0)

    /**
     * For every row in [drawnIds], the ancestors the screen would otherwise lose — in the order a reader has to meet
     * them: topmost first, so a caller can emit them above the row that needed the path.
     *
     * One map for the whole screen, built in one pass. Asking it per row would rebuild the uuid index per row, which
     * is quadratic in the size of the account on the path that re-renders every second a timer runs — the same
     * arithmetic §14's 10k benchmark exists to catch, and the reason [foldDeltas] is a map for the same reason.
     *
     * This is §7's filter clause as a function. A category or status filter that drops a parent while keeping its
     * child must not present that child as somebody's topic's first task, and the brief's preferred answer is to keep
     * the ancestor rows as context rather than to invent a marker for a missing one. Walking the chain over
     * [rows] — the profile, not the screen — is what makes the path *complete*: an ancestor two levels up can be
     * dropped by the same filter as its descendant, and a partial path would leave the same false root one step down.
     *
     * The two blank rules and the cycle bound are [ancestorsOf]'s, for the same reasons it states.
     */
    fun missingAncestorsBy(rows: List<Task>, drawnIds: Set<Long>): Map<Long, List<Task>> {
        val byUuid = rows.filter { it.uuid.isNotBlank() }.associateBy { it.uuid }
        val paths = HashMap<Long, List<Task>>()
        rows.forEach { row ->
            if (row.id !in drawnIds) return@forEach
            val path = ancestorsOf(row, byUuid).filter { it.id !in drawnIds }.reversed()
            if (path.isNotEmpty()) paths[row.id] = path
        }
        return paths
    }

    /**
     * The chain of rows above [row], nearest first, as far as the given set reaches.
     *
     * Bounded by a visited set and by the two blank rules: `tasks.uuid` defaults to `''` and a pull can write an
     * empty `parent_uuid`, so "the rows whose parent is the empty string" is a bucket of unrelated legacy rows
     * rather than anybody's ancestors — and a cycle a sync pull left behind has to stop the walk rather than hang
     * the reader's main thread.
     */
    private fun ancestorsOf(row: Task, byUuid: Map<String, Task>): List<Task> {
        val chain = ArrayList<Task>()
        val seen = hashSetOf(row.uuid)
        var walker = row.parentUuid
        while (walker != null && walker.isNotBlank() && seen.add(walker)) {
            val ancestor = byUuid[walker] ?: break
            chain += ancestor
            walker = ancestor.parentUuid
        }
        return chain
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
