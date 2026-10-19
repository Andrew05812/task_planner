package com.taskplanner.ui.util

import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.repository.AdjustmentRejection
import com.taskplanner.data.repository.AdjustmentResult
import com.taskplanner.databinding.DialogWorkIntervalBinding

/**
 * The two things every screen says about a hand-entered measurement: the form it is entered through, and
 * what the repository answered.
 *
 * Shared by the task screen, where work is added, and the history screen, where a closed stretch is
 * corrected. Both ask the same question of the user — which instant did this work begin and end at — and
 * both have to refuse the same three answers: text that names no instant, a stretch that ends before it
 * starts, and a stretch other measurements already occupy. A screen that guessed its own way past those
 * would write history the user did not ask for.
 */
object WorkAdjustmentUi {

    /**
     * Opens the entry or correction form.
     *
     * [start] and [end] prefilled are the numbers a row already holds, so a correction edits what is
     * stored instead of asking the user to retype it; a new entry passes nulls and names the instants
     * itself. The reason is asked only where a measurement is being changed — a hand-entered stretch has
     * no earlier figure to explain.
     */
    fun showIntervalForm(
        anchor: Fragment,
        titleRes: Int,
        start: String?,
        end: String?,
        reasonVisible: Boolean,
        previous: String? = null,
        onSubmit: (startedAt: Long, endedAt: Long, reason: String?) -> Unit
    ) {
        val form = DialogWorkIntervalBinding.inflate(anchor.layoutInflater)
        form.workStartInput.setText(start)
        form.workEndInput.setText(end)
        form.workReasonInput.isVisible = reasonVisible
        form.workPrevious.isVisible = !previous.isNullOrEmpty()
        form.workPrevious.text = previous
        MaterialAlertDialogBuilder(anchor.requireContext())
            .setTitle(titleRes)
            .setView(form.root)
            .setPositiveButton(R.string.save) { _, _ -> submit(anchor, form, onSubmit) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun submit(
        anchor: Fragment,
        form: DialogWorkIntervalBinding,
        onSubmit: (startedAt: Long, endedAt: Long, reason: String?) -> Unit
    ) {
        val startedAt = WorkInstantInput.parse(form.workStartInput.text)
        val endedAt = WorkInstantInput.parse(form.workEndInput.text)
        when {
            startedAt == null || endedAt == null -> say(anchor, R.string.history_bad_instant)
            endedAt <= startedAt -> say(anchor, R.string.history_bad_range)
            else -> onSubmit(
                startedAt,
                endedAt,
                form.workReasonInput.text?.toString()?.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * What an adjustment did.
     *
     * An accepted write needs only a notice — the lists on both screens re-read themselves. A rejection is
     * said in the words of the reason, because "it did not save" tells the user nothing about what to do
     * next. A conflict becomes the list of measurements in the way, since §13 asks for conflicts to be
     * shown rather than settled by guessing which of them the user meant.
     */
    fun report(anchor: Fragment, result: AdjustmentResult, acceptedRes: Int) {
        when (result) {
            is AdjustmentResult.Accepted -> say(anchor, acceptedRes)
            is AdjustmentResult.Rejected -> say(
                anchor,
                when (result.reason) {
                    AdjustmentRejection.NO_PROFILE -> R.string.history_no_profile
                    AdjustmentRejection.START_NOT_BEFORE_END -> R.string.history_bad_range
                    AdjustmentRejection.STILL_BEING_MEASURED -> R.string.history_still_measured
                    AdjustmentRejection.NOT_FOUND -> R.string.history_not_found
                }
            )
            is AdjustmentResult.Overlapping -> showConflict(anchor, result.intervals)
        }
    }

    private fun showConflict(anchor: Fragment, intervals: List<WorkSession>) {
        val context = anchor.requireContext()
        val body = StringBuilder()
            .append(context.getString(R.string.history_conflict_message))
            .append("\n\n")
        intervals.forEach { interval ->
            body.append(
                context.getString(
                    R.string.work_interval_row_format,
                    WorkInstantInput.format(interval.startedAt),
                    interval.endedAt?.let { WorkInstantInput.format(it) }
                        ?: context.getString(R.string.work_interval_open),
                    WorkTimeFormat.compact(context, interval.durationSeconds ?: 0L)
                )
            )
            if (interval.source == WorkSource.MANUAL) {
                body.append(" · ").append(context.getString(R.string.work_interval_manual))
            }
            body.append("\n")
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(
                context.resources.getQuantityString(
                    R.plurals.history_conflict_count,
                    intervals.size,
                    intervals.size
                )
            )
            .setMessage(body.toString())
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun say(anchor: Fragment, messageRes: Int) {
        Snackbar.make(anchor.requireView(), messageRes, Snackbar.LENGTH_LONG).show()
    }
}
