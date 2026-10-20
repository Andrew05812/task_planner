package com.taskplanner.ui.goals

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
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
import com.taskplanner.data.local.GoalTaskRow
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.repository.GoalProgress
import com.taskplanner.data.repository.GoalWriteResult
import com.taskplanner.databinding.FragmentGoalDetailBinding
import com.taskplanner.databinding.ItemGoalChangeBinding
import com.taskplanner.databinding.ItemGoalTaskBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * One goal, the work it names, and the record of what it asked for.
 *
 * The card at the top is the same card the list draws — the same [GoalCard] words over the same figure the
 * repository assembled — so the two screens cannot tell different stories about one row. What is only here is
 * the set and the history, which are the goal's own rows rather than anything derived.
 *
 * Every write on this screen is a reference or a state of the goal: naming a task, un-naming one, putting the
 * goal away, throwing it away. None of them touches a task, and the words beside each one say so.
 */
@AndroidEntryPoint
class GoalDetailFragment : Fragment() {

    private var _binding: FragmentGoalDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GoalDetailViewModel by viewModels()
    private val args: GoalDetailFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGoalDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.load(args.goalId)
        binding.editGoal.setOnClickListener {
            findNavController().navigate(
                GoalDetailFragmentDirections.actionGoalDetailToGoalEditor(args.goalId)
            )
        }
        binding.linkTask.setOnClickListener { showTaskPicker() }
        setupWriteButtons()
        observeState()
    }

    /**
     * The two state changes a goal itself can take, each said before it is done.
     *
     * Putting a goal away needs no warning — it is reversible from the same button and nothing it counted is
     * touched. Throwing one away is the write the screen cannot undo quietly, so it asks first and says what
     * stays.
     */
    private fun setupWriteButtons() {
        binding.archiveGoal.setOnClickListener {
            // Which way the button is going — away or back — is read off the row before the write, because
            // the state the screen has is the state the user is looking at, and it is the button's own label.
            val wasArchived = viewModel.state.value.goal?.archivedAt != null
            viewModel.setArchived { result ->
                report(
                    result,
                    if (wasArchived) R.string.goal_unarchived_toast else R.string.goal_archived_toast
                )
            }
        }
        binding.deleteGoal.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.goal_delete_confirm_title)
                .setMessage(R.string.goal_delete_confirm_message)
                .setPositiveButton(R.string.goal_delete_confirm_yes) { _, _ ->
                    viewModel.remove { result ->
                        if (result is GoalWriteResult.Saved) {
                            findNavController().popBackStack()
                        } else {
                            report(result, R.string.goal_removed_toast)
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
                    val progress = state.progress
                    binding.goalDetailProgress.isVisible = state.isLoading
                    binding.goalCard.isVisible = progress != null
                    // A count or time goal reads its figure from everything the account holds, so it has no set
                    // to show and nothing to add to. A heading over an empty list would read as work the goal
                    // lost rather than as a type that never names any.
                    val namesTasks = progress?.goal?.type?.namesTasks == true
                    binding.goalTasksTitle.isVisible = namesTasks
                    // The note explains what the set underneath it does not do — copy a task — so it belongs
                    // with the heading and disappears with it for the types that name no tasks at all.
                    binding.goalTasksNote.isVisible = namesTasks
                    binding.goalHistoryTitle.isVisible = progress != null
                    // A goal whose row is gone has no figures to show, and a screen that kept the card up
                    // with the last numbers it saw would be reporting a goal that no longer exists.
                    binding.goalNotFound.isVisible = state.isMissing
                    // Outside the card, and so outside the card's own hide: a goal that is not here has
                    // nothing to throw away, and a button that refuses is worse than one that is absent.
                    binding.deleteGoal.isVisible = progress != null
                    progress?.let { renderCard(it) }
                    renderTasks(namesTasks, state.tasks)
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

    /** The card, drawn from the figure the repository assembled and nothing this screen worked out. */
    private fun renderCard(progress: GoalProgress) {
        val context = requireContext()
        binding.goalTitle.text = progress.goal.title
        binding.goalType.setText(GoalCard.typeLabelRes(progress.goal.type))
        binding.goalFigure.text = GoalCard.figure(context, progress)

        val stateRes = GoalCard.stateLabelRes(progress)
        binding.goalState.isVisible = stateRes != null
        stateRes?.let { label ->
            binding.goalState.setText(label)
            binding.goalState.setTextColor(ContextCompat.getColor(context, stateColorRes(label)))
        }

        val fraction = GoalCard.fraction(progress)
        binding.goalBar.isVisible = fraction != null
        if (fraction != null) {
            binding.goalBar.setSegments(
                listOf(
                    progress.counted.coerceAtLeast(0L),
                    (progress.target - progress.counted).coerceAtLeast(0L)
                ),
                intArrayOf(
                    ContextCompat.getColor(context, R.color.chart_bar),
                    ContextCompat.getColor(context, R.color.chart_track)
                )
            )
        }

        val meta = GoalCard.meta(context, progress)
        binding.goalMeta.isVisible = meta != null
        binding.goalMeta.text = meta

        val missing = GoalCard.missingNote(context, progress)
        binding.goalMissing.isVisible = missing != null
        binding.goalMissing.text = missing

        val archived = progress.goal.archivedAt != null
        binding.goalArchivedNote.isVisible = archived
        binding.archiveGoal.setText(if (archived) R.string.goal_unarchive else R.string.goal_archive)
    }

    /**
     * The goal's own set, in the order the user put it together.
     *
     * Inflated into the column rather than given a RecyclerView: the set is the handful of tasks a goal is
     * about, and a list that scrolls inside a scrolling screen is a gesture that never works.
     */
    private fun renderTasks(canLink: Boolean, rows: List<GoalTaskRow>) {
        val inflater = LayoutInflater.from(requireContext())
        binding.linkTask.isVisible = canLink
        binding.goalTasksList.removeAllViews()
        rows.forEach { row ->
            val item = ItemGoalTaskBinding.inflate(inflater, binding.goalTasksList, true)
            // A link whose task is gone still shows, because the denominator counts it. It has nowhere to
            // open, so it is drawn as the sentence about it rather than as a row that goes dead on tap.
            item.goalTaskTitle.text = row.title ?: getString(R.string.goal_task_missing)
            item.goalTaskTitle.isEnabled = row.taskId != null
            item.goalTaskDone.isVisible = row.completed && row.taskId != null
            item.goalTaskUnlink.isVisible = canLink
            item.root.setOnClickListener {
                val taskId = row.taskId ?: return@setOnClickListener
                findNavController().navigate(
                    GoalDetailFragmentDirections.actionGoalDetailToTaskDetail(taskId)
                )
            }
            item.goalTaskUnlink.setOnClickListener { confirmUnlink(row) }
        }
        binding.goalTasksEmpty.isVisible = canLink && binding.goalTasksList.childCount == 0
    }

    /** The aim's own history, oldest first — the order the user made the changes in. */
    private fun renderHistory(changes: List<GoalValueChange>) {
        val inflater = LayoutInflater.from(requireContext())
        binding.goalHistoryList.removeAllViews()
        changes.forEach { change ->
            val item = ItemGoalChangeBinding.inflate(inflater, binding.goalHistoryList, true)
            item.goalChangeWhat.text = GoalCard.changeLine(requireContext(), change)
            item.goalChangeWhen.text = GoalCard.changeWhen(requireContext(), change)
        }
        binding.goalHistoryEmpty.isVisible =
            binding.goalHistoryTitle.isVisible && binding.goalHistoryList.childCount == 0
    }

    /**
     * The tasks the goal could still be pointed at.
     *
     * A dialog rather than a second screen, because the choice is one row and the list beside it is short by
     * construction: the read hands back the account's open tasks that this goal does not already name. The
     * dialog the repository answered is the one the user tapped, so a slow read that lands after the screen
     * moved on has nowhere to put its list — and nothing is written by a cancelled picker.
     */
    private fun showTaskPicker() {
        viewModel.linkableTasks { candidates ->
            if (candidates.isEmpty()) {
                Snackbar.make(binding.root, R.string.goal_no_tasks_to_link, Snackbar.LENGTH_LONG).show()
                return@linkableTasks
            }
            val titles = candidates.map { it.title }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.goal_link_task)
                .setItems(titles) { _, which ->
                    val taskUuid = candidates[which].taskUuid
                    viewModel.link(taskUuid) { result ->
                        report(result, R.string.goal_link_saved)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * Taking a task out of the goal, said before it is done.
     *
     * The words matter here: "remove" reads like the task goes too, and it does not. Only the link is written
     * away, and the figure the card prints moves with it.
     */
    private fun confirmUnlink(row: GoalTaskRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.goal_unlink_confirm_title)
            .setMessage(R.string.goal_unlink_confirm_message)
            .setPositiveButton(R.string.goal_unlink_confirm_yes) { _, _ ->
                viewModel.unlink(row.taskUuid) { result ->
                    report(result, R.string.goal_unlinked_toast)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * What a write ended up doing, in the words the action needs.
     *
     * A goal write has two kinds of answer and the screen keeps them apart: it landed, in which case the
     * figures on the card are already moving by themselves — the read is still open and the aggregate under
     * them re-runs — or it was refused, in which case nothing moved and the refusal says why.
     */
    private fun report(result: GoalWriteResult, @StringRes savedRes: Int) {
        when (result) {
            is GoalWriteResult.Saved -> Snackbar.make(binding.root, savedRes, Snackbar.LENGTH_SHORT).show()
            is GoalWriteResult.Rejected -> Snackbar.make(
                binding.root,
                GoalCard.rejectionRes(result.reason),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    @ColorRes
    private fun stateColorRes(@StringRes labelRes: Int): Int = when (labelRes) {
        R.string.goal_state_reached -> R.color.status_done
        R.string.goal_state_late -> R.color.health_overdue
        else -> R.color.status_todo
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
