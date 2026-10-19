package com.taskplanner.ui.util

import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.taskplanner.R
import com.taskplanner.data.tracking.ConflictResolution
import com.taskplanner.ui.tasks.TasksViewModel
import com.taskplanner.ui.tasks.WorkConflict

/**
 * The one dialog that answers a refused Start, shared by every screen that can start a measurement.
 *
 * A profile has a single timer, so tapping Start on a second task is not a command the app may carry out
 * on its own: it has to say which task is being measured and let the user decide what happens to it. The
 * three answers are the same wherever the request comes from, and "Cancel" writes nothing.
 */
object WorkConflictDialog {

    fun show(anchor: Fragment, viewModel: TasksViewModel, conflict: WorkConflict) {
        MaterialAlertDialogBuilder(anchor.requireContext())
            .setTitle(R.string.work_conflict_title)
            .setMessage(
                anchor.getString(
                    R.string.work_conflict_message,
                    conflict.runningTaskTitle,
                    conflict.targetTask.title
                )
            )
            .setPositiveButton(R.string.work_conflict_pause) { _, _ ->
                viewModel.resolveWorkConflict(ConflictResolution.PAUSE_ACTIVE)
            }
            .setNeutralButton(R.string.work_conflict_stop) { _, _ ->
                viewModel.resolveWorkConflict(ConflictResolution.STOP_ACTIVE)
            }
            .setNegativeButton(R.string.work_conflict_cancel) { _, _ ->
                viewModel.resolveWorkConflict(ConflictResolution.CANCEL)
            }
            .setOnDismissListener { viewModel.clearWorkConflict() }
            .show()
    }
}
