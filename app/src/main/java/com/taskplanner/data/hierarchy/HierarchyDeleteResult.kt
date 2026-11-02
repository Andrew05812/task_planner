package com.taskplanner.data.hierarchy

import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.Task

/**
 * What the reader chose to do with a row that has rows hanging under it.
 *
 * Stage 15 could not produce a parent with children unless the whole tree arrived through an import, so deleting a
 * row had no orphan question to answer. An indent creates that path, and the brief is explicit that the answer ships
 * with it rather than after it: the two cases below are the only ways out, and the screen has to say which one it
 * took before it writes.
 */
enum class HierarchyDeletePolicy {
    /**
     * Delete this row and nothing else: its children go up to whatever held it — the grandparent, or the topic —
     * and keep their own children, their topic and their order.
     */
    PROMOTE_CHILDREN,

    /**
     * Delete this row and every descendant, as tombstones. History is retired rather than removed, so a second
     * device can be told and a restore can still read the shape of what the reader had.
     */
    DELETE_SUBTREE
}

/**
 * What a set of deletes would take with it, read before any of it is written.
 *
 * [affectedRows] counts each row once, even where a selected row sits inside another selected row's branch — a
 * "select all" over a three-level notebook would otherwise be told a number larger than the notebook. [branchRows] is
 * how many of the asked-for rows have something directly under them, and [children] their direct children together;
 * for one row they are the two figures the dialog speaks, and for a batch [branchRows] is what the batch sentence
 * names.
 */
data class HierarchyDeleteSummary(val branchRows: Int, val children: Int, val affectedRows: Int)

/** Why a delete did not happen. */
enum class HierarchyDeleteRefusal {
    /** No profile is signed in. */
    NO_PROFILE,

    /** The row is not a live row of this profile — another account's, or already deleted. */
    ROW_MISSING,

    /**
     * The row, or something under it, is being measured.
     *
     * An open session has seconds already spent on it, and a delete that stopped the timer on its own would either
     * throw that work away or leave the reader believing they deleted a task that is still running. The measurement
     * is stopped by an explicit action instead, and then the delete can go ahead.
     */
    MEASUREMENT_RUNNING,

    /**
     * Some identity inside the block about to be deleted is claimed by more than one live row.
     *
     * A delete names its set by walking parent links, and `tasks.uuid` carries no unique index — so with one identity
     * held twice, "every descendant" has two answers. The same doubled row makes a move refuse
     * ([HierarchyMoveRefusal.AMBIGUOUS_PARENT]); here the cost of guessing is higher, because the wrong answer
     * silently retires rows nobody named.
     */
    AMBIGUOUS_IDENTITY
}

/**
 * How a delete ended.
 *
 * [Deleted.deletedRows] is the set that became tombstones — returned whole rather than as a count, because the
 * caller has to hand it to the reminder and recurrence paths exactly as the existing single-task delete does, and
 * because the dialog that asked wants to be able to say what it did afterwards. [Deleted.promotedTaskIds] is empty
 * for a subtree delete: nothing moved, everything under the head went with it.
 *
 * [Deleted.retiredReminders] is what the delete took out of the live reminder set. It travels because the wakeup a
 * reminder armed is not database state: the rows are gone by the time this returns, so a caller that wanted the
 * alarms cancelled could not find the rows again to cancel them. Leaving them would be a wakeup that fires into an
 * empty sweep — which is what the single-row delete's own path never did.
 *
 * [Confirm] is not an error. It is the domain refusing to answer a question the reader owns: a row with rows under it
 * has two deletes and only they can say which one they meant. A caller that already knows — because the dialog asked
 * first, or because it is deleting a batch it checked as a whole — passes a [HierarchyDeletePolicy] and never sees
 * this case. It carries the sizes rather than the rows because it is a sentence about the branch that is being
 * written, and it is cheap to keep out of a transaction's way.
 */
sealed class HierarchyDeleteResult {
    data class Deleted(
        val policy: HierarchyDeletePolicy,
        val deletedRows: List<Task>,
        val promotedTaskIds: List<Long>,
        val retiredReminders: List<Reminder>,
        val rowsWritten: Int
    ) : HierarchyDeleteResult()

    /**
     * What the delete would take with it: [childCount] rows hang directly under this one, and the branch it heads
     * holds [subtreeSize] rows including itself.
     */
    data class Confirm(val childCount: Int, val subtreeSize: Int) : HierarchyDeleteResult()

    data class Refused(val reason: HierarchyDeleteRefusal) : HierarchyDeleteResult()
}
