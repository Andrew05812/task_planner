package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.model.Task
import com.taskplanner.data.tracking.TimeBase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a change to the task tree is validated and written.
 *
 * Stage 16 lets the reader edit a tree that Stage 15 only let them import, and every edit has to satisfy the same
 * promises the import did: one owner, one topic down the whole subtree, no cycle, no row lost, a position nobody
 * else holds. So the edits live here rather than in a ViewModel — a screen that could reach around these rules is a
 * screen that can corrupt a notebook, and the four UI surfaces that will want them (the list, the editor, the
 * backup and the sync) should all be answering to one set of checks.
 *
 * Each operation is one transaction over the profile's stored rows, and each answers with a named result rather
 * than a half-applied change: [TaskHierarchyPolicy] decides, this class writes what it decided, and a refusal
 * writes nothing at all.
 */
@Singleton
class TaskHierarchyRepository @Inject constructor(
    private val database: AppDatabase,
    private val taskDao: TaskDao,
    private val groupDao: GroupDao,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase
) {

    /**
     * Reorders [taskId] among its own siblings, setting it down beside [anchorTaskId].
     *
     * The plan is checked against the rows as the database holds them, not as one screen drew them: a drag made while
     * a filter is on reorders the reader's whole sequence rather than re-numbering the visible subset, which is what
     * stopped two rows of one profile sharing a position. A reorder changes positions and nothing else — never the
     * owner, the topic or the parent.
     */
    suspend fun moveWithinSiblings(taskId: Long, anchorTaskId: Long, placeAfter: Boolean): HierarchyMoveResult =
        edit(taskId) { plan ->
            val anchor = plan.stored.firstOrNull { it.id == anchorTaskId }
                ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ANCHOR_MISSING)
            TaskHierarchyPolicy.reorderRefusal(plan.head, anchor, plan.blockIds)?.let {
                return@edit HierarchyMoveResult.Refused(it)
            }
            val reordered = TaskHierarchyPolicy.sequenceAfterMove(plan.stored, plan.block, anchorTaskId, placeAfter)
                ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ANCHOR_MISSING)
            HierarchyMoveResult.Accepted(
                movedTaskIds = plan.block.map { it.id },
                anchorTaskId = anchorTaskId,
                placeAfter = placeAfter,
                rowsRewritten = writePositions(plan.stored, reordered, plan.now).size
            )
        }

    /**
     * Hangs [taskId] on the row above it — one level deeper, with everything under it still attached.
     *
     * The new parent is the predecessor in the profile's own sequence, or [underTaskId] when a screen names the row it
     * actually drew above: under a filter the stored predecessor can be a card the reader cannot see, and hanging a
     * row on a parent that is not on screen looks like an indent that did nothing. Never a row chosen because its
     * title looked right, and never one from another topic: an indent inside a notebook must not be able to move a
     * row out of the topic it belongs to. Only the head's parent link changes, because a row that moves under another
     * does not re-hang its own children.
     */
    suspend fun indentTask(taskId: Long, underTaskId: Long? = null): HierarchyMoveResult = edit(taskId) { plan ->
        val above = if (underTaskId != null) {
            plan.stored.firstOrNull { it.id == underTaskId }
                ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE)
        } else {
            plan.stored.getOrNull(plan.stored.indexOfFirst { it.id == taskId } - 1)
                ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.NO_ROW_ABOVE)
        }
        if (above.id in plan.blockIds) {
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ANCHOR_IN_SUBTREE)
        }
        if (above.groupId != plan.head.groupId) {
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.DIFFERENT_TOPIC)
        }
        if (above.uuid.isBlank()) {
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.PARENT_UNNAMED)
        }
        if (above.uuid == plan.head.parentUuid) {
            // It already holds this row: an indent has nowhere deeper to go, and doing it again would rewrite the
            // same link and stamp a row for a push that carries no change.
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.NO_DEEPER_PARENT)
        }
        if (above.uuid in plan.ambiguous) {
            // The parent is chosen by identity, so a doubled identity is unusable here too: the level validated and
            // the level drawn would come from two different chains.
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.AMBIGUOUS_PARENT)
        }
        if (!TaskHierarchyPolicy.fitsUnder(levelOf(plan.stored, above), plan.height)) {
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.DEPTH_EXCEEDED)
        }
        taskDao.setTaskParent(plan.head.id, above.uuid, plan.now)
        HierarchyMoveResult.Accepted(plan.block.map { it.id }, above.id, placeAfter = true, rowsRewritten = 1)
    }

    /**
     * Lifts [taskId] one level: it hangs from its parent's parent now, beside its parent's other children.
     *
     * The block lands *after* the subtree it came out of rather than in the middle of it, which is what keeps the old
     * parent's branch readable — an outdent moves one branch, it does not reshuffle the ones next to it. A row that is
     * already a topic's own child has nowhere further up to go, and so does one whose parent link names nothing: the
     * renderer draws that row at the margin already, so an outdent would invent a level nobody can see.
     */
    suspend fun outdentTask(taskId: Long): HierarchyMoveResult = edit(taskId) { plan ->
        val parentUuid = plan.head.parentUuid?.takeIf { it.isNotBlank() }
            ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT)
        val parent = plan.stored.firstOrNull { it.uuid == parentUuid }
            ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT)
        if (parent.uuid in plan.ambiguous) {
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.AMBIGUOUS_PARENT)
        }
        // The row rises to whatever holds its parent, and that link is written by identity — so it has to be as
        // single-valued as the parent it leaves behind.
        val grandparentUuid = parent.parentUuid?.takeIf { it.isNotBlank() }
        if (grandparentUuid != null && grandparentUuid in plan.ambiguous) {
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.AMBIGUOUS_PARENT)
        }
        if (grandparentUuid != null && plan.stored.none { it.uuid == grandparentUuid }) {
            // The row above the parent is gone. Lifting further would write a link to nothing, which the renderer
            // would draw as a margin row with an invisible owner; the reader gets a refusal instead.
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE)
        }
        val anchor = TaskHierarchyPolicy.subtreeOf(plan.stored, parent).lastOrNull { it.id !in plan.blockIds } ?: parent
        val reordered = TaskHierarchyPolicy.sequenceAfterMove(plan.stored, plan.block, anchor.id, placeAfter = true)
            ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ROW_MISSING)
        taskDao.setTaskParent(plan.head.id, grandparentUuid, plan.now)
        HierarchyMoveResult.Accepted(
            movedTaskIds = plan.block.map { it.id },
            anchorTaskId = anchor.id,
            placeAfter = true,
            rowsRewritten = (writePositions(plan.stored, reordered, plan.now) + plan.head.id).size
        )
    }

    /**
     * Moves the subtree under [taskId] to hang from [newParentId] inside the topic [destinationGroupUuid].
     *
     * A null [newParentId] means "become a topic's own child"; a null [destinationGroupUuid] means the unfiled
     * standalone tree. Every row of the block keeps its own children and takes the destination's topic together, in
     * this one transaction — a subtree that changed topic row by row would leave a parent in one topic with its
     * children in another, which is the kind of state no screen can explain to a reader afterwards.
     */
    suspend fun moveSubtreeUnder(
        taskId: Long,
        newParentId: Long?,
        destinationGroupUuid: String?
    ): HierarchyMoveResult = edit(taskId) { plan ->
        val destination = destinationGroupUuid?.takeIf { it.isNotBlank() }
        if (destination != null && !ownsTopic(plan.userId, destination)) {
            return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.TOPIC_MISSING)
        }
        val parent = newParentId?.let { id ->
            plan.stored.firstOrNull { it.id == id }
                ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE)
        }
        val anchor: Task?
        if (parent != null) {
            if (parent.id == plan.head.id) return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.SAME_ROW)
            if (parent.id in plan.blockIds) {
                return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ANCHOR_IN_SUBTREE)
            }
            if (parent.uuid.isBlank()) return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.PARENT_UNNAMED)
            if (parent.uuid in plan.ambiguous) {
                return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.AMBIGUOUS_PARENT)
            }
            if (parent.groupId != destination) {
                return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.TOPIC_MISMATCH)
            }
            if (!TaskHierarchyPolicy.fitsUnder(levelOf(plan.stored, parent), plan.height)) {
                return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.DEPTH_EXCEEDED)
            }
            anchor = TaskHierarchyPolicy.subtreeOf(plan.stored, parent).lastOrNull { it.id !in plan.blockIds } ?: parent
        } else {
            // A row arriving as a topic's own child belongs at the end of that topic's list of roots — and *after*
            // the last root's whole subtree. Landing it directly after the root row itself would push the reader's
            // children below a row they do not own, and a card drawn under an indent is a card read as a child.
            val lastRoot = plan.stored.lastOrNull {
                it.groupId == destination && it.parentUuid.isNullOrBlank() && it.id !in plan.blockIds
            }
            anchor = lastRoot?.let { root ->
                TaskHierarchyPolicy.subtreeOf(plan.stored, root).lastOrNull { it.id !in plan.blockIds } ?: root
            }
        }
        val reordered = if (anchor == null) {
            plan.stored.filterNot { it.id in plan.blockIds } + plan.block
        } else {
            TaskHierarchyPolicy.sequenceAfterMove(plan.stored, plan.block, anchor.id, placeAfter = true)
                ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.ROW_MISSING)
        }
        taskDao.setTaskParent(plan.head.id, parent?.uuid, plan.now)
        var written = setOf(plan.head.id)
        if (destination != plan.head.groupId) {
            taskDao.setTopicForTasks(plan.block.map { it.id }, destination, plan.now)
            written = written + plan.blockIds
        }
        written = written + writePositions(plan.stored, reordered, plan.now)
        HierarchyMoveResult.Accepted(
            movedTaskIds = plan.block.map { it.id },
            anchorTaskId = anchor?.id ?: 0L,
            placeAfter = true,
            rowsRewritten = written.size
        )
    }

    /**
     * One transaction over the profile: [plan] asks what every edit has to know about [taskId], and [body] decides
     * and writes for the shape it produced.
     *
     * Every operation starts in the same place so every operation asks the same three questions — is this ours, what
     * travels with it, and can that set of rows be named at all. The third is
     * [HierarchyMoveRefusal.AMBIGUOUS_PARENT]: `tasks.uuid` carries no unique index (Stage 16 §4 records why one was
     * not added blind), so a doubled identity is answered by refusing rather than by whichever row a `LIMIT 1`
     * happened to reach first. A refusal reached here has touched no row.
     */
    private suspend fun edit(
        taskId: Long,
        body: suspend (Ready) -> HierarchyMoveResult
    ): HierarchyMoveResult = database.withTransaction {
        val userId = sessionRepository.currentUserId()
            ?: return@withTransaction HierarchyMoveResult.Refused(HierarchyMoveRefusal.NO_PROFILE)
        val stored = TaskHierarchyPolicy.canonicalSequence(taskDao.getRowsInStoredOrder(userId))
        val head = stored.firstOrNull { it.id == taskId }
            ?: return@withTransaction HierarchyMoveResult.Refused(HierarchyMoveRefusal.ROW_MISSING)
        val block = TaskHierarchyPolicy.subtreeOf(stored, head)
        val blockIds = block.map { it.id }.toSet()
        val ambiguous = TaskHierarchyPolicy.ambiguousUuids(stored)
        if (!TaskHierarchyPolicy.blockIsKnowable(head, block, ambiguous)) {
            return@withTransaction HierarchyMoveResult.Refused(HierarchyMoveRefusal.AMBIGUOUS_PARENT)
        }
        body(
            Ready(
                userId = userId,
                stored = stored,
                head = head,
                block = block,
                blockIds = blockIds,
                height = TaskHierarchyPolicy.heightOf(stored, head),
                ambiguous = ambiguous,
                // One instant per edit: an operation that writes the head, the block's topic and the crossed
                // positions must not stamp them minutes apart, or a device comparing two rows of one tree would
                // read them as unrelated changes.
                now = timeBase.wallMillis()
            )
        )
    }

    /** The shape an edit works on: the profile's rows in their one sequence, the head, and what travels with it. */
    private data class Ready(
        val userId: Long,
        val stored: List<Task>,
        val head: Task,
        val block: List<Task>,
        val blockIds: Set<Long>,
        val height: Int,
        val ambiguous: Set<String>,
        val now: Long
    )

    /**
     * Re-numbers the new sequence densely and writes only the rows whose position moved; says who was written.
     *
     * The rows outside the crossing keep their stored `updated_at` and `sync_state` rather than lighting up for a
     * push they have no part in — which is what keeps a drag costing the distance it travelled instead of the size of
     * the account.
     */
    private suspend fun writePositions(before: List<Task>, after: List<Task>, now: Long): Set<Long> {
        val changed = after.withIndex().filter { (position, task) -> task.order != position }.toList()
        changed.forEach { (position, task) -> taskDao.assignTaskOrder(task.id, position, now) }
        return changed.map { (_, task) -> task.id }.toSet()
    }

    /**
     * The level [row] sits at in this profile's tree, counted from 1 for a topic's own child.
     *
     * A row with a blank identity has no level to report — nothing can point at it as a parent, which is what the two
     * callers have just established before asking — so the answer is the topic's own level rather than a crash.
     */
    private fun levelOf(rows: List<Task>, row: Task): Int =
        if (row.uuid.isBlank()) 1 else TaskHierarchyPolicy.levelsByUuid(rows).getValue(row.uuid)

    /**
     * Whether this profile owns [uuid] as a live topic.
     *
     * `task_groups.uuid` is the primary key with no owner in the lookup, and `getGroupByUuid` also returns a
     * soft-deleted one — so both the owner and the tombstone flag have to be asked here, or a subtree could be moved
     * into a topic the reader has already deleted.
     */
    private suspend fun ownsTopic(userId: Long, uuid: String): Boolean =
        groupDao.getGroupByUuid(uuid)?.let { it.userId == userId && !it.isDeleted } == true
}
