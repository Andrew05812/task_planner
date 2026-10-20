package com.taskplanner.ui.goals

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.repository.GoalWriteResult
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.databinding.FragmentGoalEditorBinding
import com.taskplanner.ui.util.WorkInstantInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The one form that sets or moves a goal.
 *
 * The fields are the goal's own figures, which are the only numbers this feature stores. What a goal has
 * reached is never on this screen: it is answered from the work the app already keeps, and a form that showed
 * it would look like something the user could type.
 *
 * Reading the texts and turning them into a window, a target and a deadline is the ViewModel's job. This
 * fragment only puts the words on the fields, carries the user's choice of type between the chips and the
 * save, and says what the save did.
 */
@AndroidEntryPoint
class GoalEditorFragment : Fragment() {

    private var _binding: FragmentGoalEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GoalEditorViewModel by viewModels()
    private val args: GoalEditorFragmentArgs by navArgs()

    /** The row the form has been filled from, so a later state change cannot overwrite what is being typed. */
    private var prefilledFrom: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGoalEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.load(args.goalId)
        binding.goalEditorTypeGroup.setOnCheckedStateChangeListener { _, _ ->
            binding.goalEditorTargetNote.isVisible = selectedType() == GoalType.TIME_SPENT
        }
        binding.goalEditorSave.setOnClickListener { save() }
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    state.goal?.let { goal -> fillFrom(goal) }
                    binding.goalEditorReasonLayout.isVisible = state.asksForReason
                    binding.goalEditorProgress.isVisible = state.isLoading
                    binding.goalEditorSave.isEnabled = !state.isLoading && !state.isMissing
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

    /**
     * Puts the stored row into the fields, once.
     *
     * Once is the whole care here: the state is re-emitted when a write lands, and a form that filled itself
     * again at that moment would throw away what the user had just typed.
     */
    private fun fillFrom(goal: Goal) {
        if (prefilledFrom == goal.uuid) return
        prefilledFrom = goal.uuid
        binding.goalEditorTitle.setText(goal.title)
        binding.goalEditorTypeGroup.check(chipResFor(goal.type))
        binding.goalEditorTarget.setText(goal.targetValue.toString())
        binding.goalEditorWindowFrom.setText(
            goal.windowFromDay?.let { WorkInstantInput.formatDay(WorkTimeMath.toDate(it)) }.orEmpty()
        )
        binding.goalEditorWindowTo.setText(
            goal.windowToDay?.let { WorkInstantInput.formatDay(WorkTimeMath.toDate(it)) }.orEmpty()
        )
        goal.deadlineAt?.let {
            binding.goalEditorDeadline.setText(WorkInstantInput.format(it))
        }
        binding.goalEditorTargetNote.isVisible = goal.type == GoalType.TIME_SPENT
    }

    private fun save() {
        viewModel.save(
            title = binding.goalEditorTitle.text?.toString().orEmpty(),
            type = selectedType(),
            targetText = binding.goalEditorTarget.text?.toString(),
            windowFromText = binding.goalEditorWindowFrom.text?.toString(),
            windowToText = binding.goalEditorWindowTo.text?.toString(),
            deadlineText = binding.goalEditorDeadline.text?.toString(),
            reason = binding.goalEditorReason.text?.toString()
        ) { outcome ->
            when (outcome) {
                // A field the form could not read is the one case where nothing at all was written, and the
                // words say how to write the field rather than that the button failed.
                is GoalSaveOutcome.Unreadable -> show(fieldRes(outcome.field), long = true)
                is GoalSaveOutcome.Written -> when (val result = outcome.result) {
                    is GoalWriteResult.Saved -> {
                        show(R.string.goal_saved, long = false)
                        findNavController().popBackStack()
                    }
                    is GoalWriteResult.Rejected -> show(GoalCard.rejectionRes(result.reason), long = true)
                }
            }
        }
    }

    /** The type the chips hold, which is the type the save writes. */
    private fun selectedType(): GoalType = when (binding.goalEditorTypeGroup.checkedChipId) {
        R.id.goalEditorTypeSet -> GoalType.TASK_SET
        R.id.goalEditorTypeTime -> GoalType.TIME_SPENT
        R.id.goalEditorTypeDeadline -> GoalType.DEADLINE
        else -> GoalType.TASK_COUNT
    }

    private fun chipResFor(type: GoalType): Int = when (type) {
        GoalType.TASK_SET -> R.id.goalEditorTypeSet
        GoalType.TIME_SPENT -> R.id.goalEditorTypeTime
        GoalType.DEADLINE -> R.id.goalEditorTypeDeadline
        GoalType.TASK_COUNT -> R.id.goalEditorTypeCount
    }

    /** Which field the form could not read, in the words that tell the user how to write it. */
    @StringRes
    private fun fieldRes(field: GoalEditorField): Int = when (field) {
        GoalEditorField.TARGET -> R.string.goal_bad_target
        GoalEditorField.WINDOW_FROM, GoalEditorField.WINDOW_TO -> R.string.goal_bad_day
        GoalEditorField.DEADLINE -> R.string.goal_bad_instant
    }

    private fun show(@StringRes message: Int, long: Boolean) {
        Snackbar.make(
            binding.root,
            message,
            if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prefilledFrom = null
        _binding = null
    }
}
