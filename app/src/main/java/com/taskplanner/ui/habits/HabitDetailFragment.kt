package com.taskplanner.ui.habits

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.HabitCheckChange
import com.taskplanner.data.repository.HabitFigures
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.databinding.DialogHabitMarkBinding
import com.taskplanner.databinding.FragmentHabitDetailBinding
import com.taskplanner.databinding.ItemHabitChangeBinding
import com.taskplanner.databinding.ItemHabitDayBinding
import com.taskplanner.databinding.ItemHabitWeekdayBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * One habit, the week it is being read on, and the record of what was corrected.
 *
 * The card at the top is the same card the list draws — the same [HabitCard] words over the same figures the
 * repository assembled — so the two screens cannot tell different stories about one row. What is only here is
 * the grid and the corrections, and both are here because a day can only be kept, taken back or moved against
 * the schedule the grid shows.
 *
 * The grid is drawn from the cells the ViewModel built rather than from days this screen worked out: which
 * days a week holds, which of them the schedule asked for, and which may be marked at all are date questions
 * with answers the repository already enforces, and a grid that guessed would invite marks it has to refuse.
 */
@AndroidEntryPoint
class HabitDetailFragment : Fragment() {

    private var _binding: FragmentHabitDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HabitDetailViewModel by viewModels()
    private val args: HabitDetailFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHabitDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.load(args.habitId)
        binding.editHabit.setOnClickListener {
            findNavController().navigate(
                HabitDetailFragmentDirections.actionHabitDetailToHabitEditor(args.habitId)
            )
        }
        binding.weekBack.setOnClickListener { viewModel.shiftWeek(-1) }
        binding.weekForward.setOnClickListener { viewModel.shiftWeek(1) }
        binding.weekLabel.setOnClickListener { viewModel.showCurrentWeek() }
        binding.keepToday.setOnClickListener { onKeepToday() }
        setupWriteButtons()
        observeState()
    }

    /**
     * The two state changes a habit itself can take, each said before it is done.
     *
     * Putting a habit away needs no warning — it is reversible from the same button and none of the days the
     * user kept is touched. Throwing one away is the write this screen cannot undo quietly, because it retires
     * the live marks with the row, so it asks first and says what that does.
     */
    private fun setupWriteButtons() {
        binding.archiveHabit.setOnClickListener {
            // Which way the button is going — away or back — is read off the row before the write, because the
            // state the screen has is the state the user is looking at, and it is the button's own label.
            val wasArchived = viewModel.state.value.habit?.archivedAt != null
            viewModel.setArchived { result ->
                report(
                    result,
                    if (wasArchived) R.string.habit_unarchived_toast else R.string.habit_archived_toast
                )
            }
        }
        binding.deleteHabit.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.habit_delete_confirm_title)
                .setMessage(R.string.habit_delete_confirm_message)
                .setPositiveButton(R.string.habit_delete_confirm_yes) { _, _ ->
                    viewModel.remove { result ->
                        if (result is HabitWriteResult.Saved) {
                            findNavController().popBackStack()
                        } else {
                            report(result, R.string.habit_removed_toast)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.habitDetailProgress.isVisible = state.isLoading
                    binding.habitCard.isVisible = state.figures != null
                    // A habit whose row is gone has no figures to show, and a screen that kept the card up with
                    // the last numbers it saw would be reporting a habit that no longer exists. Everything under
                    // the card is outside it, and so outside the card's own hide.
                    binding.habitNotFound.isVisible = state.isMissing
                    binding.keepToday.isVisible = state.figures != null
                    binding.habitGridTitle.isVisible = state.figures != null
                    binding.weekBack.isEnabled = state.figures != null
                    binding.weekForward.isEnabled = state.figures != null
                    state.figures?.let { renderCard(state, it) }
                    renderWeek(state)
                    renderGrid(state)
                    renderHistory(state.changes)
                    state.error?.let { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_retry) { viewModel.retry() }
                            .show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    /** The card, drawn from the figures the repository assembled and nothing this screen worked out. */
    private fun renderCard(state: HabitDetailState, figures: HabitFigures) {
        val context = requireContext()
        val habit = figures.habit
        binding.habitTitle.text = habit.title
        binding.habitType.setText(HabitCard.frequencyLabelRes(habit.frequency))

        val schedule = HabitCard.scheduleText(context, habit)
        binding.habitSchedule.isVisible = schedule != null
        binding.habitSchedule.text = schedule

        binding.habitFigure.text = HabitCard.streakText(context, figures)
        val best = HabitCard.bestText(context, figures)
        binding.habitBest.isVisible = best != null
        binding.habitBest.text = best

        val week = HabitCard.weekText(context, figures)
        binding.habitWeek.isVisible = week != null
        binding.habitWeek.text = week

        val share = HabitCard.share(figures)
        binding.habitBar.isVisible = share != null
        share?.let { (kept, owed) ->
            binding.habitBar.setSegments(
                listOf(kept.toLong(), owed.toLong()),
                intArrayOf(
                    ContextCompat.getColor(context, R.color.chart_bar),
                    ContextCompat.getColor(context, R.color.chart_track)
                )
            )
        }

        val kept = HabitCard.keptText(context, figures)
        binding.habitKept.isVisible = kept != null
        kept?.let {
            binding.habitKept.text = context.getString(
                R.string.habit_kept_line_format,
                it,
                HabitCard.keptWindow(context, figures)
            )
        }
        val marks = HabitCard.marksText(context, figures)
        binding.habitMarks.isVisible = figures.hasAnyMark
        binding.habitMarks.text = marks

        val badge = HabitCard.stateBadgeRes(
            owedToday = HabitCard.isOwedOn(habit, figures.today, figures.markedToday),
            markedToday = figures.markedToday
        )
        binding.habitState.isVisible = badge != null
        badge?.let { label ->
            binding.habitState.setText(label)
            binding.habitState.setTextColor(
                ContextCompat.getColor(context, HabitCard.stateColorRes(label))
            )
        }

        val meta = HabitCard.meta(context, habit)
        binding.habitMeta.isVisible = meta != null
        binding.habitMeta.text = meta

        val archived = habit.archivedAt != null
        binding.habitArchivedNote.isVisible = archived
        binding.archiveHabit.setText(if (archived) R.string.habit_unarchive else R.string.habit_archive)
        binding.keepToday.setText(
            if (state.isTodayMarked) R.string.habit_correct else R.string.habit_keep
        )
        // A habit that is no longer being asked for has no today to keep, and a button that would be refused is
        // worse than one that is absent. Correcting a day already kept stays available: the repository allows a
        // walk-back on a put-away habit precisely so a wrong mark is not left standing where it cannot be fixed.
        binding.keepToday.isEnabled = habit.isOpen || state.isTodayMarked
    }

    /**
     * The week the grid is standing in, named by its own days.
     *
     * The current week is called "this week" rather than by its dates, because the figures above the grid are
     * anchored on it and the reader has to be able to tell that the "3 of 4" and the seven cells are the same
     * seven days. A week looked at from the past gets its dates instead.
     */
    private fun renderWeek(state: HabitDetailState) {
        val from = state.weekFrom
        val to = state.weekTo
        if (from == null || to == null) {
            binding.weekLabel.text = ""
            return
        }
        binding.weekLabel.text = if (state.isCurrentWeek) {
            getString(R.string.habit_this_week)
        } else {
            val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            getString(
                R.string.habit_week_format,
                formatter.format(WorkTimeMath.toDate(from)),
                formatter.format(WorkTimeMath.toDate(to))
            )
        }
    }

    /**
     * The seven days, in the order the reader's calendar opens on.
     *
     * Inflated into the row rather than given a RecyclerView: a week is always seven cells, and a list that
     * scrolls inside a scrolling screen is a gesture that never works. The header row is rebuilt beside them
     * because which weekday a column is depends on the locale's first day, not on a fixed label in a layout.
     */
    private fun renderGrid(state: HabitDetailState) {
        val context = requireContext()
        val inflater = LayoutInflater.from(context)
        binding.habitDayLabels.removeAllViews()
        binding.habitDayCells.removeAllViews()
        state.cells.forEach { cell ->
            val label = ItemHabitWeekdayBinding.inflate(inflater, binding.habitDayLabels, true)
            label.root.text = LocalDate.ofEpochDay(cell.day)
                .dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            val day = ItemHabitDayBinding.inflate(inflater, binding.habitDayCells, true)
            day.root.text = LocalDate.ofEpochDay(cell.day).dayOfMonth.toString()
            bindDay(day, cell)
        }
    }

    /**
     * One cell: its state carried by the colour of its number, its panel saying only whether it is today.
     *
     * The panel is deliberately colourless, as the goal badge is, because seven coloured tiles in a row would
     * make the reader compare hues rather than read days. Kept is the app's own done green, an asked day still
     * standing open is its to-do red, a day the schedule did not name is the secondary text colour, and a day
     * that cannot be marked at all is faded as well as grey — the two are different, and only one of them is
     * worth pressing.
     */
    private fun bindDay(day: ItemHabitDayBinding, cell: HabitDayCell) {
        val context = day.root.context
        day.root.setBackgroundResource(
            if (cell.isToday) R.drawable.bg_habit_day_today else R.drawable.bg_habit_day
        )
        val colourRes = when {
            cell.marked -> R.color.status_done
            cell.asked && cell.canMark -> R.color.priority_high
            else -> null
        }
        day.root.alpha = if (cell.canMark || cell.marked) 1.0f else DISABLED_ALPHA
        day.root.setTextColor(
            colourRes?.let { ContextCompat.getColor(context, it) }
                ?: HabitCard.themedColor(context, android.R.attr.textColorSecondary)
        )
        day.root.setOnClickListener { onDayTapped(cell) }
    }

    /**
     * What pressing a day does, decided by what the day already is.
     *
     * A kept day opens the correction, because the only writes left for it take the mark back or move it. An
     * unkept day the schedule asked for is kept without asking — that is the whole point of the grid. An
     * unkept day the schedule did not name is asked about first: the repository accepts it, and the figures
     * then keep it out of the percentage, so the user should know the day was off the plan before it stands.
     */
    private fun onDayTapped(cell: HabitDayCell) {
        when {
            cell.marked -> showCorrectionForm(cell)
            !cell.canMark -> say(if (cell.isFuture()) R.string.habit_day_future else R.string.habit_day_outside)
            !cell.asked -> confirmOffSchedule(cell)
            else -> viewModel.mark(cell.day) { result ->
                report(result, R.string.habit_mark_saved)
            }
        }
    }

    private fun confirmOffSchedule(cell: HabitDayCell) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.habit_day_not_asked)
            .setPositiveButton(R.string.habit_keep) { _, _ ->
                viewModel.mark(cell.day) { result ->
                    report(result, R.string.habit_mark_saved)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun HabitDayCell.isFuture(): Boolean =
        day > (viewModel.state.value.figures?.today ?: day)

    /**
     * The correction form for one kept day.
     *
     * It is one form rather than two buttons because the two answers the user can mean — "that day never
     * happened" and "that work happened on another day" — are the same question about the same mark, and the
     * field says which: empty takes it back, a day moves it. The reason is asked only here, where a day the
     * screen had been showing as kept is being walked away from.
     */
    private fun showCorrectionForm(cell: HabitDayCell) {
        val form = DialogHabitMarkBinding.inflate(layoutInflater)
        form.habitMarkStands.text = getString(R.string.habit_correct_stands, HabitCard.dayText(requireContext(), cell.day))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.habit_correct_title)
            .setView(form.root)
            .setPositiveButton(R.string.habit_correct_save) { _, _ ->
                val moveTo = form.habitMarkMoveInput.text?.toString()
                val savedRes = if (moveTo.isNullOrBlank()) {
                    R.string.habit_taken_back_toast
                } else {
                    R.string.habit_moved_toast
                }
                viewModel.correctMark(cell.day, moveTo, form.habitMarkReasonInput.text?.toString()) { outcome ->
                    when (outcome) {
                        HabitCorrectionOutcome.UnreadableDay -> say(R.string.habit_bad_day, long = true)
                        is HabitCorrectionOutcome.Written -> report(outcome.result, savedRes)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Today, kept or corrected: the one day the card above the grid is anchored on. */
    private fun onKeepToday() {
        val state = viewModel.state.value
        if (state.isTodayMarked) {
            state.cells.firstOrNull { it.isToday }?.let { showCorrectionForm(it) }
        } else {
            viewModel.markToday { result -> report(result, R.string.habit_mark_saved) }
        }
    }

    /** The habit's own corrections, oldest first — the order the user made them in. */
    private fun renderHistory(changes: List<HabitCheckChange>) {
        val inflater = LayoutInflater.from(requireContext())
        binding.habitHistoryList.removeAllViews()
        changes.forEach { change ->
            val item = ItemHabitChangeBinding.inflate(inflater, binding.habitHistoryList, true)
            item.habitChangeWhat.text = HabitCard.changeLine(requireContext(), change)
            item.habitChangeWhen.text = HabitCard.changeWhen(requireContext(), change)
        }
        binding.habitHistoryEmpty.isVisible =
            binding.habitHistoryTitle.isVisible && binding.habitHistoryList.childCount == 0
    }

    /**
     * What a write ended up doing, in the words the action needs.
     *
     * A habit write has two kinds of answer and the screen keeps them apart: it landed, in which case the grid
     * and the figures are already moving by themselves — the read is still open and the marks under it re-run —
     * or it was refused, in which case nothing moved and the refusal says which day or which field was the
     * problem, because the thirteen of them differ in what the user does next.
     */
    private fun report(result: HabitWriteResult, @StringRes savedRes: Int) {
        when (result) {
            is HabitWriteResult.Saved -> Snackbar.make(binding.root, savedRes, Snackbar.LENGTH_SHORT).show()
            is HabitWriteResult.Rejected -> Snackbar.make(
                binding.root,
                HabitCard.rejectionRes(result.reason),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun say(@StringRes message: Int, long: Boolean = false) {
        Snackbar.make(
            binding.root,
            message,
            if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        ).show()
    }

    private companion object {
        /** The fade a day the user cannot act on is drawn with — a colour alone would read as a state. */
        private const val DISABLED_ALPHA = 0.38f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
