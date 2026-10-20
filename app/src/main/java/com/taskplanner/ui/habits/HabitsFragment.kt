package com.taskplanner.ui.habits

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
import com.taskplanner.databinding.FragmentHabitsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The routines the profile is keeping, and how each one stands.
 *
 * The screen owns no arithmetic and writes nothing. Each card is a row the repository assembled together with
 * its figures, so keeping a day on the habit's own screen moves the card here without a reload — the read is
 * still open, and the marks under the card re-run when a day is kept.
 *
 * A card is a way to the habit it names. Keeping a day, correcting one and changing the schedule all belong to
 * the screen that can also show the week the figures were counted from.
 */
@AndroidEntryPoint
class HabitsFragment : Fragment() {

    private var _binding: FragmentHabitsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HabitsViewModel by viewModels()
    private lateinit var adapter: HabitsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHabitsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupChips()
        binding.addHabit.setOnClickListener {
            findNavController().navigate(HabitsFragmentDirections.actionHabitsToHabitEditor())
        }
        observeState()
    }

    private fun setupList() {
        adapter = HabitsAdapter(
            onHabitClick = { item ->
                findNavController().navigate(
                    HabitsFragmentDirections.actionHabitsToHabitDetail(item.habit.id)
                )
            }
        )
        binding.habitsList.layoutManager = LinearLayoutManager(requireContext())
        binding.habitsList.adapter = adapter
    }

    /**
     * The switch between habits in progress and habits put away.
     *
     * The listener only reports the user's choice; the checked chip is painted from the state. Coming back from
     * a habit that was put away has to leave the list on the side it is actually reading, and a chip that
     * disagreed with the rows under it would be a filter nobody chose.
     */
    private fun setupChips() {
        binding.habitsScope.setOnCheckedStateChangeListener { _, checkedIds ->
            checkedIds.firstOrNull()?.let { id ->
                viewModel.setShowArchived(id == R.id.habitsScopeArchived)
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.habitsProgress.isVisible = state.isLoading
                    binding.habitsEmpty.isVisible = state.isEmpty
                    binding.habitsEmpty.setText(
                        if (state.showArchived) R.string.habits_empty_archived
                        else R.string.habits_empty_open
                    )
                    adapter.submitList(state.habits)
                    val wanted = if (state.showArchived) {
                        R.id.habitsScopeArchived
                    } else {
                        R.id.habitsScopeOpen
                    }
                    if (binding.habitsScope.checkedChipId != wanted) binding.habitsScope.check(wanted)
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
        binding.habitsList.adapter = null
        _binding = null
    }
}
