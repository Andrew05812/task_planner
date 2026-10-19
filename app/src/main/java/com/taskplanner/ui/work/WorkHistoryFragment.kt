package com.taskplanner.ui.work

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.WorkHistoryRow
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkIntervalRow
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.repository.HistorySort
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.databinding.DialogWorkRangeBinding
import com.taskplanner.databinding.FragmentWorkHistoryBinding
import com.taskplanner.databinding.ItemHistoryIntervalBinding
import com.taskplanner.databinding.ItemWorkHistoryBinding
import com.taskplanner.ui.util.WorkAdjustmentUi
import com.taskplanner.ui.util.WorkInstantInput
import com.taskplanner.ui.util.WorkTimeFormat
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * The account of what was measured: one entry per tracking session, filtered by a period and ordered the
 * way the user chose.
 *
 * Nothing on this screen computes a total. The header, the per-entry figures and the order all come from
 * SQLite, so the list a profile with ten thousand intervals shows costs the same few queries as one with
 * ten, and a number here cannot disagree with the statistics screen beside it. Opening an entry reads that
 * entry's stretches — the only place the history is walked row by row, and it is one session's rows by
 * design.
 *
 * A running session is labelled rather than counted: its open stretch has no length yet, and a history
 * list whose entries changed every second would be unreadable and would present an unfinished measurement
 * as a finished one.
 */
@AndroidEntryPoint
class WorkHistoryFragment : Fragment() {

    private var _binding: FragmentWorkHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WorkHistoryViewModel by viewModels()
    private lateinit var adapter: WorkHistoryAdapter

    /** The chip row is rebuilt from the state, and rebuilding must not read back as a user choice. */
    private var renderingChips = false

    private var periodChips: List<Pair<Chip, WorkPeriod?>> = emptyList()
    private var renderedChipSignature = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupMenu()
        setupList()
        setupChips()
        observeState()
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.menu_work_history, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                val sort = viewModel.state.value.sort
                menu.findItem(R.id.action_sort_date).isChecked = sort == HistorySort.DATE
                menu.findItem(R.id.action_sort_duration).isChecked = sort == HistorySort.DURATION
                menu.findItem(R.id.action_sort_task).isChecked = sort == HistorySort.TASK
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
                R.id.action_sort_date -> choose(HistorySort.DATE)
                R.id.action_sort_duration -> choose(HistorySort.DURATION)
                R.id.action_sort_task -> choose(HistorySort.TASK)
                else -> false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun choose(sort: HistorySort): Boolean {
        viewModel.setSort(sort)
        requireActivity().invalidateOptionsMenu()
        return true
    }

    private fun setupList() {
        adapter = WorkHistoryAdapter(
            onExpand = { uuid -> viewModel.toggleExpand(uuid) },
            onCorrect = { row -> showCorrectionDialog(row) },
            onDelete = { row -> confirmDeletion(row) }
        )
        binding.historyList.layoutManager = LinearLayoutManager(requireContext())
        binding.historyList.adapter = adapter
    }

    /**
     * The period filter: the periods the repository can name, plus the custom range.
     *
     * The custom chip is a question rather than a filter. Picking it opens the date form, and a form the
     * user cancels or fills wrongly puts the previous period back, because a screen showing a range nobody
     * chose is worse than one that stays where it was.
     */
    private fun setupChips() {
        val group = binding.historyPeriodChips
        periodChips = WorkPeriod.entries.map { period ->
            buildChip(getString(period.labelRes()), period) to period
        } + (buildChip(getString(R.string.history_period_custom), null) to null)
        group.removeAllViews()
        periodChips.forEach { (chip, _) -> group.addView(chip) }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (renderingChips) return@setOnCheckedStateChangeListener
            val chip = checkedIds.firstOrNull()?.let { id -> periodChips.firstOrNull { it.first.id == id } }
            if (chip?.second != null) {
                viewModel.setPeriod(chip.second!!)
            } else {
                askForRange()
            }
        }
    }

    private fun buildChip(label: String, period: WorkPeriod?): Chip {
        val chip = Chip(requireContext())
        // ChipGroup.check() works with view ids, and chips built here have none from XML.
        chip.id = View.generateViewId()
        chip.text = label
        chip.isCheckable = true
        chip.isCheckedIconVisible = false
        return chip
    }

    private fun askForRange() {
        val form = DialogWorkRangeBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_period_custom)
            .setView(form.root)
            .setPositiveButton(R.string.history_apply) { _, _ ->
                val from = WorkInstantInput.parseDay(form.rangeFromInput.text)
                val to = WorkInstantInput.parseDay(form.rangeToInput.text)
                when {
                    from == null || to == null -> keepPreviousPeriod(R.string.history_bad_instant)
                    !viewModel.setCustomRange(from, to) -> keepPreviousPeriod(R.string.history_bad_dates)
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> keepPreviousPeriod(null) }
            .setOnCancelListener { keepPreviousPeriod(null) }
            .show()
    }

    /** The period the screen was on is still the period it shows, whatever the chip row looks like. */
    private fun keepPreviousPeriod(messageRes: Int?) {
        messageRes?.let { say(it) }
        renderChips(viewModel.state.value, force = true)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderChips(state, force = false)
                    renderHeader(state)
                    adapter.submitList(state.rows.map { row ->
                        HistoryEntry(row, state.intervalsOf[row.uuid])
                    })
                    binding.historyProgress.isVisible = state.isLoading
                    binding.historyEmpty.isVisible = state.isEmpty
                    state.error?.let {
                        Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun renderChips(state: WorkHistoryState, force: Boolean) {
        val signature = "${state.period?.name ?: CUSTOM}|${state.customDays.first}|${state.customDays.last}"
        if (!force && signature == renderedChipSignature) return
        renderedChipSignature = signature
        renderingChips = true
        val checkedId = if (state.period == null) {
            periodChips.lastOrNull()?.first?.id ?: View.NO_ID
        } else {
            periodChips.firstOrNull { it.second == state.period }?.first?.id ?: View.NO_ID
        }
        binding.historyPeriodChips.check(checkedId)
        renderingChips = false
    }

    private fun renderHeader(state: WorkHistoryState) {
        val context = requireContext()
        val totals = state.totals
        binding.historySummary.text = getString(
            R.string.history_summary,
            WorkTimeFormat.compact(context, totals?.seconds ?: 0L),
            totals?.intervals ?: 0,
            totals?.sessions ?: 0
        )
        val days = state.visibleDays()
        binding.historyRange.text = getString(
            R.string.history_range_format,
            WorkInstantInput.formatDayLong(days.first),
            WorkInstantInput.formatDayLong(days.second)
        )
    }

    /**
     * The correction form for one closed stretch.
     *
     * It opens with the numbers the row currently holds, so the user edits what is stored rather than
     * writing an interval from memory, and it names the stretch's earlier accounts: a row corrected before
     * shows what it said, because the point of keeping those figures is that the user can see how often
     * this measurement has moved.
     */
    private fun showCorrectionDialog(row: WorkIntervalRow) {
        viewModel.correctionsOf(row.uuid) { corrections ->
            WorkAdjustmentUi.showIntervalForm(
                anchor = this,
                titleRes = R.string.history_correct_title,
                start = WorkInstantInput.format(row.startedAt),
                end = WorkInstantInput.format(row.endedAt ?: row.startedAt),
                reasonVisible = true,
                previous = corrections
                    .takeIf { it.isNotEmpty() }
                    ?.let { describeCorrections(it) }
            ) { startedAt, endedAt, reason ->
                viewModel.correctInterval(row.uuid, startedAt, endedAt, reason) { result ->
                    WorkAdjustmentUi.report(this, result, R.string.history_corrected)
                }
            }
        }
    }

    private fun confirmDeletion(row: WorkIntervalRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_delete_confirm_title)
            .setMessage(R.string.history_delete_confirm_message)
            .setPositiveButton(R.string.history_delete_confirm_yes) { _, _ ->
                viewModel.removeInterval(row.uuid) { result ->
                    WorkAdjustmentUi.report(this, result, R.string.history_removed)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun describeCorrections(corrections: List<WorkIntervalCorrection>): String {
        val context = requireContext()
        val text = corrections.joinToString("; ") { correction ->
            context.getString(
                R.string.history_interval_was_format,
                WorkInstantInput.format(correction.oldStartedAt),
                correction.oldEndedAt?.let { WorkInstantInput.format(it) }
                    ?: context.getString(R.string.work_interval_open)
            )
        }
        return context.getString(R.string.history_previous_corrections, text)
    }

    private fun say(messageRes: Int) = WorkAdjustmentUi.say(this, messageRes)

    override fun onDestroyView() {
        binding.historyList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val CUSTOM = "CUSTOM"
    }
}

/** The days the period label shows: the named period's range, or the range the user picked. */
private fun WorkHistoryState.visibleDays(): Pair<LocalDate, LocalDate> {
    val range = period?.dayRange(LocalDate.now()) ?: customDays
    return LocalDate.ofEpochDay(range.first) to LocalDate.ofEpochDay(range.last)
}

/** The chip label for a period the repository can name. */
private fun WorkPeriod.labelRes(): Int = when (this) {
    WorkPeriod.TODAY -> R.string.history_period_today
    WorkPeriod.YESTERDAY -> R.string.history_period_yesterday
    WorkPeriod.THIS_WEEK -> R.string.history_period_this_week
    WorkPeriod.LAST_WEEK -> R.string.history_period_last_week
    WorkPeriod.THIS_MONTH -> R.string.history_period_this_month
    WorkPeriod.LAST_MONTH -> R.string.history_period_last_month
}

/**
 * A history entry and the stretches the user has opened beneath it.
 *
 * The intervals travel with the row rather than being read from a side map at bind time so that opening an
 * entry changes what the list compares as content: without that, the diff would call the row unchanged and
 * the newly opened stretches would never appear.
 */
data class HistoryEntry(val row: WorkHistoryRow, val intervals: List<WorkIntervalRow>?)

private class WorkHistoryAdapter(
    private val onExpand: (String) -> Unit,
    private val onCorrect: (WorkIntervalRow) -> Unit,
    private val onDelete: (WorkIntervalRow) -> Unit
) : ListAdapter<HistoryEntry, WorkHistoryAdapter.VH>(
    object : DiffUtil.ItemCallback<HistoryEntry>() {
        override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry) =
            oldItem.row.uuid == newItem.row.uuid

        override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry) = oldItem == newItem
    }
) {

    class VH(val binding: ItemWorkHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWorkHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val row = entry.row
        val context = holder.itemView.context
        with(holder.binding) {
            historyTaskTitle.text = row.taskTitle
            historyWhen.text = context.getString(
                R.string.history_meta_format,
                WorkInstantInput.format(row.startedAt),
                row.intervals
            ) + manualSuffix(context, row)
            historyTotal.text = WorkTimeFormat.compact(context, row.closedSeconds)
            historyRunning.isVisible = row.state == RUNNING_STATE
            historyIntervals.removeAllViews()
            entry.intervals?.forEach { interval ->
                val item = ItemHistoryIntervalBinding.inflate(
                    LayoutInflater.from(context), historyIntervals, false
                )
                item.historyIntervalLabel.text = intervalLabel(context, interval)
                item.historyIntervalEdit.setOnClickListener { onCorrect(interval) }
                item.historyIntervalDelete.setOnClickListener { onDelete(interval) }
                historyIntervals.addView(item.root)
            }
            historyRowRoot.setOnClickListener { onExpand(row.uuid) }
        }
    }

    private fun manualSuffix(context: Context, row: WorkHistoryRow): String =
        if (row.manual == 0) "" else " · " + context.getString(R.string.history_manual_format, row.manual)

    private fun intervalLabel(context: Context, interval: WorkIntervalRow): String {
        val base = context.getString(
            R.string.work_interval_row_format,
            WorkInstantInput.timeOfDay(interval.startedAt),
            interval.endedAt?.let { WorkInstantInput.timeOfDay(it) }
                ?: context.getString(R.string.work_interval_open),
            WorkTimeFormat.compact(context, interval.durationSeconds ?: 0L)
        )
        val notes = listOfNotNull(
            if (interval.source == WorkSource.MANUAL.name) {
                context.getString(R.string.work_interval_manual)
            } else null,
            if (interval.clockAnomaly != null) {
                context.getString(R.string.work_interval_anomaly)
            } else null
        )
        return if (notes.isEmpty()) base else "$base · ${notes.joinToString(" · ")}"
    }

    companion object {
        /** The state column as the history query returns it; a running entry is labelled, not counted. */
        private const val RUNNING_STATE = "RUNNING"
    }
}
