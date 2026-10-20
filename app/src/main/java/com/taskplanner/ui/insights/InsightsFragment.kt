package com.taskplanner.ui.insights

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.databinding.FragmentInsightsBinding
import com.taskplanner.ui.calendar.CalendarMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * What the app noticed about this profile, and the fortnight of load those readings were taken against.
 *
 * The screen owns no arithmetic and writes nothing but the reader's answer. Each card is a row the repository
 * assembled with its own title, body and basis, and the panel beside them is the same [com.taskplanner.data.
 * insight.WorkloadRead] the heavy-day rule is handed — so the day the panel marks busiest is the day a card can
 * name, stated with the same minutes, and a task written on the other side of the app moves both without this
 * fragment reloading anything.
 *
 * The only action a card takes is to open the rows it counted. §8 forbids the app rescheduling work or changing
 * a priority on the strength of its own reading, so nothing here can be pressed into the plan: a card offers the
 * day, the task, the goal, the habit or the category it read, and the reader decides what to do about it.
 */
@AndroidEntryPoint
class InsightsFragment : Fragment() {

    private var _binding: FragmentInsightsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: InsightsViewModel by viewModels()
    private lateinit var adapter: InsightsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInsightsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupChips()
        observeState()
    }

    private fun setupList() {
        adapter = InsightsAdapter(
            onOpen = { target -> open(target) },
            onAnswer = { insight, feedback -> viewModel.answer(insight.uuid, feedback) },
            onOpenDay = { epochDay -> openCalendar(epochDay, wholeWeek = false) }
        )
        binding.insightsList.layoutManager = LinearLayoutManager(requireContext())
        binding.insightsList.adapter = adapter
    }

    /**
     * The switch between what is being said now and what was said before.
     *
     * The listener only reports the reader's choice and the checked chip is painted from the state, exactly as
     * on the goal list: coming back from a card answered in the history has to leave the screen on the list it
     * is actually reading, and a chip that disagreed with the rows under it would be a filter nobody chose.
     */
    private fun setupChips() {
        binding.insightsScope.setOnCheckedStateChangeListener { _, checkedIds ->
            checkedIds.firstOrNull()?.let { id ->
                viewModel.setShowHistory(id == R.id.insightsScopeHistory)
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.insightsProgress.isVisible = state.isLoading
                    adapter.submitRows(
                        read = state.workload,
                        insights = state.insights,
                        emptyTextRes = if (state.isEmpty) InsightCard.emptyTextRes(state.showHistory) else null
                    )
                    val wanted = if (state.showHistory) {
                        R.id.insightsScopeHistory
                    } else {
                        R.id.insightsScopeCurrent
                    }
                    if (binding.insightsScope.checkedChipId != wanted) binding.insightsScope.check(wanted)

                    // One snackbar per complaint, and each leaves the state as soon as it is shown, so a later
                    // frame of the same screen cannot fire the same message a second time. The two are said in
                    // turn rather than at once: a read that failed and a pass that failed are two answers about
                    // two questions, and one snackbar would hide whichever came second.
                    when {
                        state.error != null -> {
                            Snackbar.make(binding.root, state.error, Snackbar.LENGTH_LONG)
                                .setAction(R.string.action_retry) { viewModel.retry() }
                                .show()
                            viewModel.clearError()
                        }

                        state.passError != null -> {
                            Snackbar.make(binding.root, state.passError, Snackbar.LENGTH_LONG)
                                .setAction(R.string.action_retry) { viewModel.retry() }
                                .show()
                            viewModel.clearPassError()
                        }

                        state.feedbackRefused -> {
                            Snackbar.make(
                                binding.root,
                                R.string.insight_answer_refused,
                                Snackbar.LENGTH_LONG
                            ).show()
                            viewModel.clearFeedbackRefused()
                        }
                    }
                }
            }
        }
    }

    /** The screen the reading counted its rows on. */
    private fun open(target: InsightTarget) {
        val directions = when (target) {
            is InsightTarget.Task ->
                InsightsFragmentDirections.actionInsightsToTaskDetail(target.taskId)

            is InsightTarget.Goal ->
                InsightsFragmentDirections.actionInsightsToGoalDetail(target.goalId)

            is InsightTarget.Habit ->
                InsightsFragmentDirections.actionInsightsToHabitDetail(target.habitId)

            is InsightTarget.Category ->
                InsightsFragmentDirections.actionInsightsToCategoryDetail(target.categoryId)

            is InsightTarget.Day -> return openCalendar(target.epochDay, target.wholeWeek)
        }
        findNavController().navigate(directions)
    }

    /**
     * The calendar, opened on the day the panel or a reading named.
     *
     * The level travels with the day because the two claims differ: a heavy Thursday is a day, and a week the
     * reader was warned about is a week. The calendar's own screen decides how to hold that page.
     */
    private fun openCalendar(epochDay: Long, wholeWeek: Boolean) {
        val mode = if (wholeWeek) CalendarMode.WEEK else CalendarMode.DAY
        findNavController().navigate(
            InsightsFragmentDirections.actionInsightsToCalendar(epochDay, mode.name)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.insightsList.adapter = null
        _binding = null
    }
}
