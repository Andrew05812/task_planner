package com.taskplanner.data.hierarchy

import com.taskplanner.data.model.Task
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.data.repository.TaskRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the reader is being asked to choose, in the domain's numbers.
 *
 * [branchRows] is how many of the rows the command names have something under them, [children] how many rows hang
 * directly below those, and [affectedRows] how many rows the branch delete would take. A pre-read ask about a whole
 * set carries the set's numbers; an ask that surfaces mid-loop is about the one row the loop had just reached, which
 * is why it says so with `1`.
 */
data class BranchAsk(val branchRows: Int, val children: Int, val affectedRows: Int)

/**
 * What one delete command did.
 *
 * It is a record rather than a three-case sum because a batch really can do several of these at once: three selected
 * rows can retire two branches, refuse a third that is still being measured, and meet a row that grew a child since
 * the pre-read. A `Deleted | NeedsChoice | Refused` union would have to drop two of those to name one, and the screen
 * would then be unable to say what happened to the rows it did not get an answer about.
 *
 * [tombstones] is every row the command retired, across all of them, in the order they went. [refusal] is the last
 * refusal the loop met, the way the tasks screen has reported one since §4 — the set either went or it did not, and a
 * refusal names the row the reader has to deal with first. [asks] is what the command could not write without an
 * answer, and is empty whenever a policy was supplied.
 *
 * [askedBeforeWriting] separates the one case where the whole command was called off: the pre-read found a branch, so
 * not one row was touched and the screen has to ask before it can say it did anything. It is the same flag that keeps
 * a bulk command from closing selection mode over a question the reader has not answered yet — an ask that surfaces
 * *mid-loop* does not set it, because by then rows really have gone.
 */
data class DeleteOutcome(
    val tombstones: List<Task>,
    val refusal: HierarchyDeleteRefusal? = null,
    val asks: List<BranchAsk> = emptyList(),
    val askedBeforeWriting: Boolean = false
)

/**
 * The one domain answer to "delete this", for every screen that asks.
 *
 * Stage 16 §9 put the two deletes in [TaskHierarchyRepository.deleteTask] and §4 made the tasks screen ask before it
 * wrote, but the *orchestration* — read the branch, ask when it has one, write through the tree rules, then do the
 * follow-ups that are not database state — lived inside `TasksViewModel.deleteRows`, where a second screen could not
 * reach it. The category screen kept its own single-row delete, which never consulted the tree at all: a parent
 * pressed there was retired while its children went on naming it, and the task list drew them at the margin
 * (`CategoryTaskDeleteTest`). So the orchestration moves here, and both screens answer through it.
 *
 * What it owns, and nothing else: [summary] is the pre-read a window is built from, and [delete] is the write. The
 * follow-ups [delete] performs are the ones a single-row delete always performed — [TaskRepository.finishDelete]
 * retires the reminder rows the transaction already took out of the live set, cancels the wakeups they armed, and asks
 * the recurrence engine for the next instance of a series whose instance went. Those are not database state, so they
 * cannot be left to whichever screen remembered them; a route that skips them leaves a notification for a card nobody
 * can any more.
 *
 * What it deliberately does not know: strings, dialogs, view models, selection mode. The numbers come back as
 * [DeleteOutcome] and each screen decides what to say — which is why the same record can drive the tasks screen's
 * window and the category screen's list refresh without either one owning the rule.
 */
@Singleton
class TaskDeleteCoordinator @Inject constructor(
    private val hierarchy: TaskHierarchyRepository,
    private val tasks: TaskRepository
) {

    /** What deleting [ids] would take with it, read before any of it is written. */
    suspend fun summary(ids: List<Long>): HierarchyDeleteSummary = hierarchy.deleteBatchSummary(ids)

    /**
     * Removes [ids] from the table for good, after the tree has been told they are going.
     *
     * The archive screen's "delete forever" is the one delete that does not leave a tombstone, and `parent_uuid` is a
     * plain column with no foreign key behind it — the entity's only key is `category_id` — so the database does not
     * object when the row a child names stops existing. Archiving is not a tree operation either: `completeAndArchive`
     * archives the one row it is handed, so an archived parent with a live child is a normal state of a notebook.
     * Removed on its own, that parent leaves the child naming nothing, and the task list — which reads a parent it
     * cannot find as "no parent" — quietly promotes the row a level.
     *
     * So the rows are resolved the way every other delete resolves them: [delete] with
     * [HierarchyDeletePolicy.PROMOTE_CHILDREN] re-hangs each child onto what held its parent, re-numbers the block it
     * lifted and retires the head — that part is one transaction in [TaskHierarchyRepository] — and then performs the
     * follow-ups that are not database state, which [TaskRepository.finishDelete] does *outside* it, because cancelling
     * an alarm and materialising a series' next instance are not things a row write can be made to wait for. Only after
     * all of that does the physical verb take the rows that are already tombstones. The order is the point: reversed,
     * the orphan is written before the repair can find the row it hangs from.
     *
     * The answer is the promote's, so the caller can say which rows went and which it would not touch; a refusal
     * leaves those rows in the archive rather than removing them under it, and the screen is expected to name the
     * refusal — `ArchiveViewModel` maps it to `R.string.delete_blocked_running`.
     *
     * **What this is not safe against, and who has to fix it.** A tombstone is how a deletion reaches the cloud:
     * `TaskDao.getAllRowsOnce` includes retired rows with the comment "the sync queue has to push deletions too", and
     * `TaskDao.findTaskByUuid` deliberately reads tombstones with the comment "otherwise a deleted row could be
     * re-inserted". This verb destroys both guards at the moment it is called. A "delete forever" on a row whose
     * deletion has not been pushed yet therefore leaves the cloud holding a live document that this device will never
     * tell otherwise, and any pull that later sees that document — another device editing it, or a watermark that
     * starts below it — re-inserts the row the reader deleted for good. That is not new here: before §5 the archive
     * removed rows with no tombstone at all, so the cloud was never told either. What is new is that the two-step
     * became *the* domain verb for a final delete, which is the moment the hole stopped being a quirk of one screen.
     * Closing it needs a purge, not a re-order: remove a retired row only once the sync queue no longer lists it, which
     * is Stage 16 §11's work, and §11's brief now carries that requirement. Recorded in `docs/HIERARCHY.md` and in
     * Stage 16 §5's report as an open finding, and deliberately not papered over with a network call from a view model.
     */
    suspend fun hardDelete(ids: List<Long>): DeleteOutcome {
        val resolved = delete(ids, HierarchyDeletePolicy.PROMOTE_CHILDREN)
        if (resolved.tombstones.isEmpty()) return resolved
        tasks.hardDeleteTasks(resolved.tombstones.map { it.id })
        return resolved
    }

    /**
     * Deletes [ids] through the tree rules, asking rather than choosing when a row holds a branch.
     *
     * With no [policy] the whole set is checked first — a two-row selection whose second row is a parent must not
     * delete the first and then discover the question, which is why the pre-read returns before the loop starts. Once
     * a policy is supplied the loop runs, and a row already retired inside a branch that went with its head is skipped
     * rather than reported missing: the reader selected both, and "this row does not exist" is a sentence about a row
     * they can see they deleted.
     *
     * Each row that goes has its follow-ups performed here, in the same call, so no screen can half-do them. A
     * refusal does not stop the rows that went, and a mid-loop ask does not undo one either — both are reported in the
     * answer, because both are facts the reader has to be told.
     *
     * **The one difference from the ViewModel this came out of**, named because sixty passing tests cannot see it: the
     * old `deleteRows` handed each mid-loop ask to `onChoice` *while its loop still had rows to write*, and this method
     * collects them and lets the screen put them up once the loop is done. What the reader is asked, and the order the
     * asks come in among themselves, is the same; what changed is that no window appears until every row the command
     * could write has been written. Nothing in the app observes that — the asks travel on a capacity-one channel a
     * `STARTED` view drains, and two of them in one batch reach the same `reportDeleteBusy` either way — but a claim
     * that a lift preserved the behaviour has to say which behaviour it did not.
     */
    suspend fun delete(ids: List<Long>, policy: HierarchyDeletePolicy?): DeleteOutcome {
        if (policy == null) {
            val summary = hierarchy.deleteBatchSummary(ids)
            if (summary.branchRows > 0) {
                return DeleteOutcome(
                    tombstones = emptyList(),
                    asks = listOf(BranchAsk(summary.branchRows, summary.children, summary.affectedRows)),
                    askedBeforeWriting = true
                )
            }
        }
        val gone = ArrayList<Task>()
        val retired = HashSet<Long>()
        var refusal: HierarchyDeleteRefusal? = null
        val asks = ArrayList<BranchAsk>()
        for (id in ids) {
            if (id in retired) continue
            when (val outcome = hierarchy.deleteTask(id, policy)) {
                is HierarchyDeleteResult.Deleted -> {
                    gone += outcome.deletedRows
                    retired += outcome.deletedRows.map { it.id }
                    tasks.finishDelete(outcome.deletedRows, outcome.retiredReminders)
                }
                is HierarchyDeleteResult.Confirm ->
                    asks.add(BranchAsk(1, outcome.childCount, outcome.subtreeSize))
                is HierarchyDeleteResult.Refused -> refusal = outcome.reason
            }
        }
        return DeleteOutcome(tombstones = gone, refusal = refusal, asks = asks)
    }
}
