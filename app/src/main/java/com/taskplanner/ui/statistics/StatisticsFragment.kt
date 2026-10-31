package com.taskplanner.ui.statistics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.repository.WorkPeriod
import com.taskplanner.databinding.FragmentStatisticsBinding
import com.taskplanner.ui.util.labelRes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The dashboard: one period, and every figure the account produced inside it.
 *
 * The screen subscribes to the read and offers the period to be chosen; it computes and lays out nothing.
 * Every card is handed the number SQLite answered for the same window the chip row names, so the headline,
 * the bars, the grid and the health panel cannot tell four different stories about one week the way four
 * separate folds of one loaded task list used to. What each figure becomes on screen is
 * [DashboardRenderer]'s, which is the part that can be tested without a device.
 *
 * A period switch clears the cards before the new read begins. That is a deliberate flicker: a card showing
 * last week's total under a chip that says "This month" is a mislabelled figure, and the screen has a
 * spinner whose whole meaning is "these numbers are on their way".
 */
@AndroidEntryPoint
class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StatisticsViewModel by viewModels()

    /** The chip row is repainted from the state, and repainting must not read back as a user choice. */
    private var renderingChips = false
    private var periodChips: List<Pair<Chip, WorkPeriod>> = emptyList()
    private var renderedPeriod: WorkPeriod? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatisticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChips()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        // The grid's last column, the streak and "due today" all hang on which day the screen thinks it is
        // showing. Re-reading it on the way back is what makes the dashboard right after midnight rather
        // than only right when the app started.
        viewModel.refreshDay()
    }

    private fun setupChips() {
        val group = binding.statisticsPeriodChips
        periodChips = WorkPeriod.entries.map { period -> buildChip(getString(period.labelRes())) to period }
        group.removeAllViews()
        periodChips.forEach { (chip, _) -> group.addView(chip) }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (renderingChips) return@setOnCheckedStateChangeListener
            val chosen = checkedIds.firstOrNull()?.let { id ->
                periodChips.firstOrNull { it.first.id == id }?.second
            }
            if (chosen != null) viewModel.setPeriod(chosen)
        }
    }

    private fun buildChip(label: String): Chip {
        val chip = Chip(requireContext())
        // ChipGroup.check() works with view ids, and chips built here have none from XML.
        chip.id = View.generateViewId()
        chip.text = label
        chip.isCheckable = true
        chip.isCheckedIconVisible = false
        return chip
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderChips(state)
                    DashboardRenderer.render(binding, state, navigation())
                    state.error?.let { errorRes ->
                        Snackbar.make(binding.root, getString(errorRes), Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_retry) { viewModel.retry() }
                            .show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    /**
     * Where the dashboard's rows lead: a category to its tasks, a task to its own screen.
     *
     * Built per render rather than held, because the nav controller belongs to this view and dies with it.
     * Which rows have a destination at all is the renderer's decision; this only says where the ones that do
     * are going.
     */
    private fun navigation(): DashboardNavigation = DashboardNavigation(
        openCategory = { categoryId ->
            findNavController().navigate(
                StatisticsFragmentDirections.actionStatisticsToCategoryTasks(categoryId)
            )
        },
        openTask = { taskId ->
            findNavController().navigate(
                StatisticsFragmentDirections.actionStatisticsToTaskDetail(taskId)
            )
        }
    )

    /** Checks the chip the state is on, without the check arriving as a new command. */
    private fun renderChips(state: DashboardState) {
        if (state.period == renderedPeriod) return
        renderedPeriod = state.period
        renderingChips = true
        periodChips.firstOrNull { it.second == state.period }?.let { (chip, _) ->
            binding.statisticsPeriodChips.check(chip.id)
        }
        renderingChips = false
    }

    override fun onDestroyView() {
        // The chips belong to this view, not to the fragment: after the view is gone the chips would be held
        // on to their dead hierarchy, and the next one rebuilds them anyway.
        renderedPeriod = null
        periodChips = emptyList()
        _binding = null
        super.onDestroyView()
    }
}
