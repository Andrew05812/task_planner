package com.taskplanner.ui.tasks

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyDeletePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * What one delete press would take, in the shape the domain's read gave it.
 *
 * The four numbers are `HierarchyDeleteSummary`'s, produced by
 * [com.taskplanner.data.repository.TaskHierarchyRepository.deleteBatchSummary] before anything is written, and they
 * travel together for one reason: the window a press opens has to be decided by what the repository says about those
 * rows, not by what the screen can see of them. A filter can hide the children that make a delete a branch delete,
 * and a request built from the rows on screen would then ask the wrong question — which is the same mistake §4 is
 * about, one layer down.
 *
 * [branchRows] is the whole decision: zero means the press has one answer and the window is a confirmation, more than
 * zero means it has two and the window has to say what each does. [children] counts the rows directly under the heads
 * named, [affectedRows] every row the press would retire, counted once even where the reader selected a parent *and*
 * its own child — a set holding both must not be told the parent twice. [fromSelection] is not a number and not a
 * rule: it only says whether the answer closes selection mode, so a screen that owns a batch can hand the same window
 * to it.
 */
data class DeleteRequest(
    val ids: List<Long>,
    val branchRows: Int,
    val children: Int,
    val affectedRows: Int,
    val fromSelection: Boolean = false
) {
    /** The rows the press names, in the order it named them. */
    val rows: Int get() = ids.size

    /** Whether the reader is being asked to choose between two deletes rather than confirm one. */
    val asksForBranch: Boolean get() = branchRows > 0
}

/**
 * §4: the two windows a delete press can open, in one place, so no screen has to guess which one it owes.
 *
 * **Why this object exists.** A row that has rows under it has two deletes, and §9 put the choice in the domain:
 * `deleteTask(taskId, policy)` answers `Confirm` rather than picking. For a long time the tasks screen honoured that
 * in *two* windows — the generic "Delete Task?" first, the branch question only after the reader confirmed — so the
 * confirmation came before the fact that made it a bigger question. The roadmap's §4 asks for the choice to be
 * legible before the first change to the data, and that is a statement about how many windows a press produces: one,
 * whose shape was already decided by the read that preceded it. [show] is that single decision point, and it is the
 * only place in the app that spells either window out, which is what lets the category screen take the same pair in
 * Task 5 instead of writing the branch sentence a second time.
 *
 * **The two shapes.**
 *  * Nothing under the row → [confirmDialog]: `dialog_delete_task_title`/`dialog_delete_task_message` for one row,
 *    `confirm_delete_selected_title` and the counted message for a set. Its positive button is the write; cancel is
 *    on the same window.
 *  * Something under it → [branchDialog], straight away: `delete_branch_title`, a message naming the immediate
 *    children, the size of the branch, and what each option does, and three actions — the brief's two sentences
 *    (`delete_policy_promote` hands the children to the head's former parent, `delete_policy_subtree` retires the
 *    whole branch) and `cancel`.
 *
 * A set is not the interesting case twice over: the batch message (`delete_branch_message_batch`) is the one window
 * the selection bar gets, and the set was evaluated as a whole before it opened, so no part of it is deleted while
 * another part is still unasked.
 *
 * **Asking is not writing.** Neither window reaches the database: [show] hands the caller a
 * [HierarchyDeletePolicy] — or `null`, meaning "the reader confirmed the single delete this row can only have" — and
 * the caller's answer goes to [TasksViewModel.resolveDelete], which is the one path that writes. Every number a
 * window prints is the repository's; this object counts nothing, walks no tree and decides no admissibility, because
 * a second opinion in a view layer is a second door a reader can be invited through and then refused. Cancel, and the
 * dialog closing by any other means, call nothing at all.
 *
 * **One press, one window, once.** [bind] is the screen-side half: it collects the view model's one-shot channel
 * inside `repeatOnLifecycle(STARTED)`, so the window appears while the view is alive and a press that lands while it
 * is stopped waits instead of stacking. The channel is the reason a rotation cannot double it — see [bind]. Two
 * follow-ups ride on the same collector, because both are about the window rather than the request: a press the app
 * refuses gets its reason said out loud instead of disappearing, and a window the reader never got to answer is
 * dismissed when the view stops, so it neither leaks nor comes back as a question nobody was asked.
 *
 * **Every sentence is a resource, in both languages**, and the counts are plurals: Russian declines a number three
 * ways where English has two forms, and `StringFormatContractTest`, `PluralsContractTest` and
 * `scripts/check-localization.py` are the gates that keep the two locales asking for the same arguments. The window
 * says its counts as words in a message rather than as colour, so a screen reader reaches them on the same pass that
 * reads the title and the buttons.
 */
object DeleteDialog {

    /**
     * The one window a press opens, chosen by [DeleteRequest.asksForBranch].
     *
     * Returns the dialog because the caller is the screen that has to be able to say it showed one — and because
     * `UnifiedTaskDeleteTest` counts the windows a press produces, which is §4's actual requirement.
     */
    fun show(
        context: Context,
        request: DeleteRequest,
        onAnswer: (HierarchyDeletePolicy?) -> Unit
    ): AlertDialog =
        if (request.asksForBranch) branchDialog(context, request, onAnswer) else confirmDialog(context, request, onAnswer)

    /**
     * The window for a set the domain says has nothing under it: one delete, so a confirmation and not a choice.
     *
     * A batch keeps its own sentence because "this task" about four rows is a lie of number; the count comes from
     * the request, never from a screen's memory of what it ticked.
     */
    private fun confirmDialog(
        context: Context,
        request: DeleteRequest,
        onAnswer: (HierarchyDeletePolicy?) -> Unit
    ): AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(confirmationTitle(request))
        .setMessage(confirmationMessage(context, request))
        .setPositiveButton(R.string.delete) { _, _ -> onAnswer(null) }
        .setNegativeButton(R.string.cancel, null)
        .create()
        .apply { show() }

    /**
     * The window for a row that holds a branch — and the only window that press produces.
     *
     * The destructive choice sits on the negative button and the way out sits on the neutral one, so a mis-tap is not
     * the branch's funeral and a reader who wants neither does not have to find a third place to say it.
     */
    private fun branchDialog(
        context: Context,
        request: DeleteRequest,
        onAnswer: (HierarchyDeletePolicy?) -> Unit
    ): AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.delete_branch_title)
        .setMessage(branchMessage(context, request))
        .setPositiveButton(R.string.delete_policy_promote) { _, _ ->
            onAnswer(HierarchyDeletePolicy.PROMOTE_CHILDREN)
        }
        .setNegativeButton(R.string.delete_policy_subtree) { _, _ ->
            onAnswer(HierarchyDeletePolicy.DELETE_SUBTREE)
        }
        .setNeutralButton(R.string.cancel, null)
        .create()
        .apply { show() }

    private fun confirmationTitle(request: DeleteRequest): Int = when (request.rows) {
        1 -> R.string.dialog_delete_task_title
        else -> R.string.confirm_delete_selected_title
    }

    private fun confirmationMessage(context: Context, request: DeleteRequest): String = when (request.rows) {
        1 -> context.getString(R.string.dialog_delete_task_message)
        else -> context.getString(R.string.confirm_delete_selected_message, request.rows)
    }

    /**
     * The branch sentence, with the two figures the reader is being asked to weigh.
     *
     * The switch is the size of the *press*, not the number of branch rows: [DeleteRequest.rows] is how many heads the
     * reader pointed at, and only one of them lets the sentence say «this task» and mean the head's own children. Keying
     * it on `branchRows` instead made a four-row selection whose single branch row was addressed as a lone task — the
     * window put "…directly under this task…" over two rows the reader had ticked that hold nothing, and offered the
     * one head's child count as the figure for a delete that covered the whole set. A set, however many of its rows hold
     * something, gets the batch sentence, which speaks the number of branch rows and the size of the set as a whole.
     * Both are built from the same request so a dialog cannot state one number in its title's language and another in
     * its message's.
     */
    fun branchMessage(context: Context, request: DeleteRequest): String {
        val rows = context.resources.getQuantityString(
            R.plurals.delete_branch_rows, request.affectedRows, request.affectedRows
        )
        return if (request.rows == 1) {
            context.getString(
                R.string.delete_branch_message,
                context.resources.getQuantityString(
                    R.plurals.delete_branch_children, request.children, request.children
                ),
                rows
            )
        } else {
            context.getString(R.string.delete_branch_message_batch, request.branchRows, rows)
        }
    }

    /**
     * Installs one screen's delete windows: collects [requests] while [owner]'s view is at least `STARTED`, shows
     * exactly one dialog per request, and takes that dialog down with the view that drew it.
     *
     * **Why this shape cannot re-deliver a window.** The requests arrive on a `Channel` of capacity one, and a
     * channel element is removed by the `receive` that reads it — unlike a `StateFlow` or a replaying `SharedFlow`,
     * nothing here can hand the same request to a second collector, so a recreate finds the channel empty and the
     * reader is not asked twice about a question they were already shown. The second mechanism is the one a reader can
     * reach: the collector keeps the window it drew as the only one it will draw, so a request arriving while a window
     * is on screen is dropped — a second question stacked behind a modal one is either unreachable or a surprise the
     * moment the first closes — and the drop is *named* through [onBusy], because a press that produces neither a
     * window nor a word reads as a dead control. The flag clears on dismissal, whichever button ended it.
     * `repeatOnLifecycle` cancels the collector
     * when the view stops, so a press that lands while the screen is away is held (one, not two: capacity is the
     * number of unanswered questions the app keeps) and delivered when the view comes back; [TasksViewModel.offerDelete]
     * reports the same reason when even that one slot is taken.
     *
     * **What the holder keeps, and what used to outlive it.** The request is a row id and three integers — no
     * `Context`, no `View`, no fragment — so *the request* cannot leak the screen. The window could, and did: until
     * this round the collector cancelled with the view and left the `AlertDialog` it had shown up, so a rotation took
     * the question away from the reader and handed Android a `WindowLeaked` complaint about a dialog whose owner was
     * gone. Dismissal therefore belongs to the collector, not to the caller: it happens in the `finally` that the
     * cancellation of the `repeatOnLifecycle` body unwinds through, which is the one place that runs on every way the
     * started state ends — stop, recreate, or the scope being cancelled — and travels with [bind] to whatever screen
     * takes it next (Task 5's category screen gets the leak for free if this stays the fragment's job). Dismissing is
     * not answering: no policy goes to [onAnswer] on that path, so the reader's next press is a fresh one.
     *
     * [context] is asked for per event rather than captured, because the fragment that supplies it can lose its view
     * between the emit and the collect; the collector is dead by then, so the lambda is never called against a
     * detached fragment.
     *
     * **The thread this runs on is a precondition, not a detail.** A `Dialog` can only be constructed on a thread with
     * a looper, and [show] is called straight from the collector. Production is safe because the scope callers pass is
     * `lifecycleScope`, whose dispatcher is `Dispatchers.Main.immediate`; a caller that handed this function a
     * background or a non-looper scope would crash on the first window rather than on the first tap — and the crash
     * would be one dispatcher swap away for whoever calls this next. `UnifiedTaskDeleteTest` had to learn that the
     * hard way: an inline `Main` that runs a resume on the Room transaction thread produced exactly that exception,
     * swallowed by the collector, and every test then saw zero windows. No `withContext(Dispatchers.Main)` is added
     * here, because nothing can test the swap except by being a different caller — which is a caller's bug, stated.
     */
    fun bind(
        owner: LifecycleOwner,
        scope: CoroutineScope,
        requests: ReceiveChannel<DeleteRequest>,
        context: () -> Context,
        onAnswer: (DeleteRequest, HierarchyDeletePolicy?) -> Unit,
        onBusy: () -> Unit
    ) {
        scope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var open: AlertDialog? = null
                try {
                    for (request in requests) {
                        if (open?.isShowing == true) {
                            onBusy()
                            continue
                        }
                        val window = show(context(), request) { policy -> onAnswer(request, policy) }
                        window.setOnDismissListener { open = null }
                        open = window
                    }
                } finally {
                    open?.takeIf { it.isShowing }?.dismiss()
                }
            }
        }
    }
}
