package com.taskplanner.ui.goals

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
import com.taskplanner.databinding.FragmentGoalsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The aims the profile is working toward, and how far each has reached.
 *
 * The screen owns no arithmetic and writes nothing. Each card is a row the repository assembled together
 * with its figure, so a task finished on the task screen changes the card here without this fragment
 * reloading anything — the read is still open, and the aggregate under the card re-runs when the work moves.
 *
 * A card is a way to the goal it names. Putting it away, throwing it away and changing its target all belong
 * to the screen that can also show what the goal has reached and what its aim used to be.
 */
@AndroidEntryPoint
class GoalsFragment : Fragment() {

    private var _binding: FragmentGoalsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GoalsViewModel by viewModels()
    private lateinit var adapter: GoalsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGoalsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupChips()
        binding.addGoal.setOnClickListener {
            findNavController().navigate(GoalsFragmentDirections.actionGoalsToGoalEditor())
        }
        observeState()
    }

    private fun setupList() {
        adapter = GoalsAdapter(
            onGoalClick = { item ->
                findNavController().navigate(
                    GoalsFragmentDirections.actionGoalsToGoalDetail(item.goal.id)
                )
            }
        )
        binding.goalsList.layoutManager = LinearLayoutManager(requireContext())
        binding.goalsList.adapter = adapter
    }

    /**
     * The switch between live aims and retired ones.
     *
     * The listener only reports the user's choice; the checked chip is painted from the state. Coming back
     * from a goal that was put away has to leave the list on the side it is actually reading, and a chip that
     * disagreed with the rows under it would be a filter nobody chose.
     */
    private fun setupChips() {
        binding.goalsScope.setOnCheckedStateChangeListener { _, checkedIds ->
            checkedIds.firstOrNull()?.let { id ->
                viewModel.setShowArchived(id == R.id.goalsScopeArchived)
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.goalsProgress.isVisible = state.isLoading
                    binding.goalsEmpty.isVisible = state.isEmpty
                    binding.goalsEmpty.setText(
                        if (state.showArchived) R.string.goals_empty_archived
                        else R.string.goals_empty_open
                    )
                    adapter.submitList(state.goals)
                    val wanted = if (state.showArchived) {
                        R.id.goalsScopeArchived
                    } else {
                        R.id.goalsScopeOpen
                    }
                    if (binding.goalsScope.checkedChipId != wanted) binding.goalsScope.check(wanted)
                    state.error?.let { message ->
                        // One snackbar per failed read: the error leaves the state as soon as it is shown, so
                        // a later row update on the same screen cannot fire the same message a second time.
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_retry) { viewModel.retry() }
                            .show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.goalsList.adapter = null
        _binding = null
    }
}
