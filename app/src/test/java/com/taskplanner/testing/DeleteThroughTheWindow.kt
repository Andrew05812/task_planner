package com.taskplanner.testing

import com.taskplanner.data.hierarchy.HierarchyDeletePolicy
import com.taskplanner.ui.tasks.DeleteRequest
import com.taskplanner.ui.tasks.TasksViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A delete press, taken through the only door the app leaves it: the read, the one question, the answer.
 *
 * Stage 16 §4 made the tasks screen ask through `requestDelete` → a window → `resolveDelete`, and §5 deleted
 * `TasksViewModel.deleteSelected` and `deleteSelectedFor` — two public methods that could write without asking and
 * that no screen called. The suites that pinned those methods now send the same command through the route a reader
 * actually takes, which is what makes the pinning worth anything: a test of a door nobody can reach measures nothing.
 *
 * [awaitIdle] is the calling class's own way of settling the storage threads, because each suite has its own —
 * `AppFixture.awaitIdle`, a `pump()`, or a `awaitUntil` over the rendered titles. The window's request is read with a
 * timeout rather than an open-ended `receive`, so a press that produced no question fails by name instead of hanging
 * the suite that made it.
 *
 * The two branches are the semantics the deleted methods had, unchanged: with no policy the pre-read either writes
 * (and [onChoice] stays silent) or asks (and [onChoice] hears the set's numbers while nothing is written); with a
 * policy there is no question to report and the write goes at once.
 */
fun TasksViewModel.deleteThroughTheWindow(
    ids: List<Long>,
    policy: HierarchyDeletePolicy? = null,
    awaitIdle: () -> Unit,
    onChoice: (branchRows: Int, children: Int, subtree: Int) -> Unit = { _, _, _ -> }
) {
    val request = press(ids, awaitIdle) ?: return
    if (policy == null && request.asksForBranch) {
        onChoice(request.branchRows, request.children, request.affectedRows)
        return
    }
    resolveDelete(request, policy)
    awaitIdle()
}

/**
 * The selection bar's press, for the cases that are about the selection rather than about a list of ids.
 *
 * It reads the same live `selectedIds` the bar's own button reads, so a test cannot pass by handing the view model a
 * set the reader never held.
 */
fun TasksViewModel.deleteSelectionThroughTheWindow(
    policy: HierarchyDeletePolicy? = null,
    awaitIdle: () -> Unit,
    onChoice: (branchRows: Int, children: Int, subtree: Int) -> Unit = { _, _, _ -> }
) {
    requestDeleteSelection()
    val request = takeRequest(awaitIdle) ?: return
    if (policy == null && request.asksForBranch) {
        onChoice(request.branchRows, request.children, request.affectedRows)
        return
    }
    resolveDelete(request, policy)
    awaitIdle()
}

private fun TasksViewModel.press(ids: List<Long>, awaitIdle: () -> Unit): DeleteRequest? {
    requestDelete(ids)
    awaitIdle()
    return takeRequest(awaitIdle)
}

private fun TasksViewModel.takeRequest(awaitIdle: () -> Unit): DeleteRequest? {
    awaitIdle()
    return runBlocking { withTimeout(5_000) { deleteRequests.receive() } }
}
