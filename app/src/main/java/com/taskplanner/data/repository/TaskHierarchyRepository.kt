package com.taskplanner.data.repository

import androidx.room.withTransaction
import com.taskplanner.data.hierarchy.HierarchyCollapseRefusal
import com.taskplanner.data.hierarchy.HierarchyCollapseResult
import com.taskplanner.data.hierarchy.HierarchyDeletePolicy
import com.taskplanner.data.hierarchy.HierarchyDeleteRefusal
import com.taskplanner.data.hierarchy.HierarchyDeleteResult
import com.taskplanner.data.hierarchy.HierarchyDeleteSummary
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.TrackingSessionDao
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
    private val trackingSessionDao: TrackingSessionDao,
    private val reminderDao: ReminderDao,
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
        // Every question about whether this may be written is `indentRefusal`'s, because §6's menu reads that same
        // function to decide what to offer. The candidate is still chosen here — this is the one thing the screen
        // cannot be trusted with, which row the profile's own sequence actually holds above the head.
        TaskHierarchyPolicy.indentRefusal(plan.head, above, plan.facts)?.let {
            return@edit HierarchyMoveResult.Refused(it)
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
        // Which lift is *possible* is `outdentRefusal`'s — the same question §6's menu asks before it draws a row.
        // What follows is the part only the transaction can do: where the block lands once it is released.
        TaskHierarchyPolicy.outdentRefusal(plan.head, plan.facts)?.let {
            return@edit HierarchyMoveResult.Refused(it)
        }
        // Read from the same facts the rule above consulted, rather than searching the stored list again: a `first`
        // over the list takes the first row claiming an identity while `byUuid` keeps the last, and the refusal the
        // reader is told has to be the one the transaction actually checked.
        val parent = plan.facts.byUuid[plan.head.parentUuid]
            ?: return@edit HierarchyMoveResult.Refused(HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE)
        val grandparentUuid = parent.parentUuid?.takeIf { it.isNotBlank() }
        val anchor = TaskHierarchyPolicy.subtreeOf(plan.stored, parent).lastOrNull { it.id !in plan.blockIds }
            ?: parent
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
        // Two questions belong here because only this transaction can ask them: whether the profile owns the
        // destination topic, and which stored row `newParentId` actually names. Everything else the write has to
        // decide — the row itself, the block, a parent with no identity or with two, a topic that does not match, a
        // branch that would pass the third level — is `moveRefusal`'s, because §6.3's "Move to" sheet reads that same
        // function to decide what it draws as pressable. A destination the sheet names and the write refuses is the
        // failure the extraction exists to prevent, and `HierarchyMoveTest` (§5's cases, unedited) is the proof that
        // reading the rule out of this body did not change what it decides.
        val parent = newParentId?.let { id -> plan.stored.firstOrNull { it.id == id } }
        val profileTopicUuids = if (destination != null && ownsTopic(plan.userId, destination)) {
            setOf(destination)
        } else {
            emptySet()
        }
        // The lookup's own answer is reached *after* the rule runs, so a topic this profile cannot hold is still the
        // reason named first — the order the §5 tests pin, and the one place where the two halves of one decision
        // have to be put back in line.
        val refusal = TaskHierarchyPolicy.moveRefusal(
            head = plan.head,
            blockIds = plan.blockIds,
            blockHeight = plan.height,
            parent = parent,
            destinationGroupUuid = destination,
            profileTopicUuids = profileTopicUuids,
            facts = plan.facts
        ) ?: (if (newParentId != null && parent == null) HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE else null)
        refusal?.let { return@edit HierarchyMoveResult.Refused(it) }
        val anchor: Task?
        if (parent != null) {
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
     * Deletes [taskId] one of the two ways the reader can choose, and returns the rows that became tombstones.
     *
     * With no [policy] on a row that has rows under it, this asks instead of writing: the answer is
     * [HierarchyDeleteResult.Confirm] carrying the branch's size, and not one row is touched. That is the brief's
     * rule — a parent's delete may not be left without an explicit policy — and putting it here rather than in a
     * screen is what stops the next caller from forgetting the question.
     *
     * [HierarchyDeletePolicy.PROMOTE_CHILDREN] re-hangs the immediate children on whatever held this row — the
     * grandparent, or the topic — and each keeps its own descendants, its topic and its place among its siblings, so
     * a branch does not fall to the margin because a line above it disappeared.
     * [HierarchyDeletePolicy.DELETE_SUBTREE] marks the row and every descendant as tombstones; nothing is removed
     * physically, because history is retired rather than deleted.
     *
     * A row of either set that is being measured refuses the whole request: an open interval has seconds already
     * spent on it, and a delete that stopped the timer on its own would throw that work away or leave a timer
     * pointing at a row that is gone. Stopping the measurement is an action the reader takes, not a side effect of
     * one they took for something else. The same holds for a block whose identities are not single-valued: the set
     * being deleted is named by walking parent links, so a doubled `uuid` is refused rather than guessed at.
     *
     * Tombstoned rows lose their reminder *rows* here, in this transaction — a wakeup outliving the card it rings for
     * is the failure this prevents. Two follow-ups stay with the caller, as they already are for a single delete: the
     * `AlarmManager` cancellations, which are not database state, and the series' next instance, which
     * [com.taskplanner.data.repository.RecurrenceRepository] re-materialises. They read
     * [HierarchyDeleteResult.Deleted.deletedRows] and [HierarchyDeleteResult.Deleted.retiredReminders], so a subtree
     * costs the same follow-up as one row: what went is returned whole rather than as a count, and the reminders are
     * returned as the rows they were, because after this transaction nothing could read them again.
     */
    suspend fun deleteTask(taskId: Long, policy: HierarchyDeletePolicy?): HierarchyDeleteResult =
        database.withTransaction {
            val userId = sessionRepository.currentUserId()
                ?: return@withTransaction HierarchyDeleteResult.Refused(HierarchyDeleteRefusal.NO_PROFILE)
            val stored = TaskHierarchyPolicy.canonicalSequence(taskDao.getRowsInStoredOrder(userId))
            val head = stored.firstOrNull { it.id == taskId }
                ?: return@withTransaction HierarchyDeleteResult.Refused(HierarchyDeleteRefusal.ROW_MISSING)
            val block = TaskHierarchyPolicy.subtreeOf(stored, head)
            val ambiguous = TaskHierarchyPolicy.ambiguousUuids(stored)
            if (!TaskHierarchyPolicy.blockIsKnowable(head, block, ambiguous)) {
                return@withTransaction HierarchyDeleteResult.Refused(HierarchyDeleteRefusal.AMBIGUOUS_IDENTITY)
            }
            val beingMeasured = (if (policy == HierarchyDeletePolicy.DELETE_SUBTREE) block else listOf(head))
                .firstOrNull { task ->
                    trackingSessionDao.getLiveSessionsForTask(userId, task.id).isNotEmpty()
                }
            if (beingMeasured != null) {
                return@withTransaction HierarchyDeleteResult.Refused(HierarchyDeleteRefusal.MEASUREMENT_RUNNING)
            }
            val children = stored.filter { it.parentUuid == head.uuid && it.id != head.id }
            if (policy == null && children.isNotEmpty()) {
                // Asked without an answer, and the answer is not this class's to give: nothing is written, and the
                // screen is handed the size of what it has to ask about.
                return@withTransaction HierarchyDeleteResult.Confirm(children.size, block.size)
            }
            // A row with nothing under it has only one delete, so a caller that did not choose is not asked to: the
            // two policies write the same single tombstone.
            val chosen = policy ?: HierarchyDeletePolicy.PROMOTE_CHILDREN
            val now = timeBase.wallMillis()
            var written = 0
            if (chosen == HierarchyDeletePolicy.PROMOTE_CHILDREN) {
                // Only the immediate children move up; a grandchild still hangs from its own parent, which is what
                // keeps the shape of the branch instead of flattening it into the row that replaced its head.
                children.forEach { child ->
                    taskDao.setTaskParent(child.id, head.parentUuid?.takeIf { it.isNotBlank() }, now)
                }
                written = children.size
            }
            val doomed = if (chosen == HierarchyDeletePolicy.DELETE_SUBTREE) block else listOf(head)
            taskDao.softDeleteTasks(doomed.map { it.id }, now)
            // The alarms go in the same transaction as the rows: a tombstone written and its reminder left armed is
            // a notification for a card the reader deleted, and it is exactly the state a crash between two
            // statements would leave behind. The split is the one the single-row delete already makes — a plain task
            // loses its lead times with it, while an instance of a running series keeps them, because the next
            // instance is built from this row's content and retiring them here would shorten the series in silence.
            val retiring = doomed.filter { it.uuid.isNotBlank() && it.seriesUuid == null }
            val retiredReminders = retiring.flatMap { reminderDao.getLiveForTask(it.uuid) }
            reminderDao.softDeleteForTasks(retiring.map { it.uuid }, now)
            HierarchyDeleteResult.Deleted(
                policy = chosen,
                deletedRows = doomed,
                promotedTaskIds = if (chosen == HierarchyDeletePolicy.PROMOTE_CHILDREN) {
                    children.map { it.id }
                } else {
                    emptyList()
                },
                retiredReminders = retiredReminders,
                rowsWritten = written + doomed.size
            )
        }

    /**
     * What deleting [taskIds] would take with it, read before any of it is written.
     *
     * A read rather than a guess, because the screen has to say these numbers in the question it asks — and a filter
     * can hide the very children that make the question worth asking, so the rows on screen are not evidence. One
     * call for a whole batch, which is what lets a multi-row selection ask a single question before deleting any of
     * it, and [HierarchyDeleteSummary.affectedRows] counts a row once even where the reader selected both an ancestor
     * and its descendant.
     */
    suspend fun deleteBatchSummary(taskIds: List<Long>): HierarchyDeleteSummary = database.withTransaction {
        val userId = sessionRepository.currentUserId()
            ?: return@withTransaction HierarchyDeleteSummary(0, 0, 0)
        val stored = TaskHierarchyPolicy.canonicalSequence(taskDao.getRowsInStoredOrder(userId))
        val wanted = taskIds.toSet()
        var branchRows = 0
        var children = 0
        val affected = HashSet<Long>()
        stored.filter { it.id in wanted }.forEach { head ->
            val childCount = if (head.uuid.isBlank()) 0 else stored.count { it.parentUuid == head.uuid && it.id != head.id }
            if (childCount > 0) branchRows++
            children += childCount
            affected += TaskHierarchyPolicy.subtreeOf(stored, head).map { it.id }
        }
        HierarchyDeleteSummary(branchRows, children, affected.size)
    }

    /**
     * Folds [taskId]'s branch away from the list, or opens it again.
     *
     * One column moves: no parent link, no topic, no position, no status — which is the whole reason the state lives
     * on the row rather than in a screen. A reader who folds a branch, lets the list re-render, rotates the device,
     * switches profile and comes back is looking at the same fold they left, and the hidden rows are still stored
     * exactly as they were (§7: collapsing hides descendants *from the list*, it does not edit them).
     *
     * A row with nothing under it is refused: [HierarchyCollapseRefusal.NO_CHILDREN] is the difference between a
     * control and a decoration, and a card that folds zero rows teaches the reader that the app does not know their
     * own notebook. The answer carries the count of what went away so the card can say it — the branch's descendants,
     * not only the generation under it, because that is what the reader loses by the tap.
     */
    suspend fun toggleBranchCollapse(taskId: Long): HierarchyCollapseResult = database.withTransaction {
        val userId = sessionRepository.currentUserId()
            ?: return@withTransaction HierarchyCollapseResult.Refused(HierarchyCollapseRefusal.NO_PROFILE)
        val stored = TaskHierarchyPolicy.canonicalSequence(taskDao.getRowsInStoredOrder(userId))
        val head = stored.firstOrNull { it.id == taskId }
            ?: return@withTransaction HierarchyCollapseResult.Refused(HierarchyCollapseRefusal.ROW_MISSING)
        val block = TaskHierarchyPolicy.subtreeOf(stored, head)
        if (!TaskHierarchyPolicy.blockIsKnowable(head, block, TaskHierarchyPolicy.ambiguousUuids(stored))) {
            return@withTransaction HierarchyCollapseResult.Refused(HierarchyCollapseRefusal.AMBIGUOUS_IDENTITY)
        }
        val hidden = block.size - 1
        if (hidden == 0) {
            return@withTransaction HierarchyCollapseResult.Refused(HierarchyCollapseRefusal.NO_CHILDREN)
        }
        val collapseTo = !head.childrenCollapsed
        taskDao.setChildrenCollapsed(head.id, collapseTo, timeBase.wallMillis())
        HierarchyCollapseResult.Changed(head.id, collapseTo, hidden, rowsWritten = 1)
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
                // The block's own depth, which `destinationsFor` asks the same way from the same facts: a sheet that
                // measured the travelling branch differently would offer a place the write then refuses.
                height = TaskHierarchyPolicy.heightOf(stored, head),
                // The same shape §6's menu reads its answers from, built once per transaction rather than once per
                // question, so the rule the reader is shown and the rule that decides the write are literally the
                // same function over the same data. This is where the ambiguity set now lives for the rule:
                // [TaskHierarchyPolicy.moveRefusal] reads `facts.ambiguous`, which is the same answer the door above
                // reached for with [TaskHierarchyPolicy.blockIsKnowable] — the door is asked about the block rather
                // than about the tree, so it takes the set as an argument instead of reading it back from here.
                facts = TaskHierarchyPolicy.factsOf(stored),
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
        val facts: TaskHierarchyPolicy.TreeFacts,
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

    // No level helper of its own: `TaskHierarchyPolicy.levelOf` is what both this class and §6's menu read, and a
    // second copy in the repository is exactly the drift the extraction was done to prevent.

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
