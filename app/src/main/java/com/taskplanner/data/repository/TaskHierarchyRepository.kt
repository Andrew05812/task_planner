package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.TaskDao
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
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase
) {

    /**
     * Reorders [taskId] among its own siblings, setting it down beside [anchorTaskId] and writing the new order.
     *
     * The plan is checked against the rows as the database holds them, not as one screen drew them: a drag made
     * while a filter is on reorders the reader's whole sequence rather than re-numbering the visible subset, which
     * is what stopped two rows of one profile from sharing a position. Only the rows whose position actually moved
     * are written, so the cost of a drag is the distance it travelled.
     *
     * **The read, the decision and the write are one transaction.** A row created by the editor takes the next free
     * position, a recurrence materialisation appends an instance, and a sync pull writes the position the other
     * device held — all while a reader's finger is still on the card. Deciding from a list read *outside* the write
     * would hand the position the renumbering just assigned to whoever arrived in between, which is the collision
     * this method exists to remove.
     */
    suspend fun moveWithinSiblings(taskId: Long, anchorTaskId: Long, placeAfter: Boolean): HierarchyMoveResult =
        database.withTransaction {
            val userId = sessionRepository.currentUserId()
                ?: return@withTransaction HierarchyMoveResult.Refused(HierarchyMoveRefusal.NO_PROFILE)
            val stored = TaskHierarchyPolicy.canonicalSequence(taskDao.getRowsInStoredOrder(userId))
            val head = stored.firstOrNull { it.id == taskId }
                ?: return@withTransaction HierarchyMoveResult.Refused(HierarchyMoveRefusal.ROW_MISSING)
            val anchor = stored.firstOrNull { it.id == anchorTaskId }
                ?: return@withTransaction HierarchyMoveResult.Refused(HierarchyMoveRefusal.ANCHOR_MISSING)
            val block = TaskHierarchyPolicy.subtreeOf(stored, head)
            TaskHierarchyPolicy.reorderRefusal(head, anchor, block.map { it.id }.toSet())?.let {
                return@withTransaction HierarchyMoveResult.Refused(it)
            }
            val reordered = TaskHierarchyPolicy.sequenceAfterMove(stored, block, anchorTaskId, placeAfter)
                ?: return@withTransaction HierarchyMoveResult.Refused(HierarchyMoveRefusal.ANCHOR_MISSING)
            // The new sequence is numbered densely, so a row is written exactly when its own position moved: the
            // rows outside the crossing keep their stored `updated_at` and `sync_state` rather than lighting up for
            // a push they have no part in.
            val changed = reordered.withIndex().filter { (position, task) -> task.order != position }.toList()
            val now = timeBase.wallMillis()
            changed.forEach { (position, task) -> taskDao.assignTaskOrder(task.id, position, now) }
            HierarchyMoveResult.Accepted(
                movedTaskIds = block.map { it.id },
                anchorTaskId = anchorTaskId,
                placeAfter = placeAfter,
                rowsRewritten = changed.size
            )
        }
}
