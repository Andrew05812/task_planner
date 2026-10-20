package com.taskplanner.ui.calendar

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
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.data.model.Task
import com.taskplanner.databinding.FragmentCalendarBinding
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * The calendar: one month, one week or one day of the user's plans, with the tasks of the day they are
 * looking at.
 *
 * The fragment owns three things and nothing else: the view, the navigation the screen can start, and
 * the one collection that turns the state flow into pixels. It never asks for data — not on a mode
 * change, not after a drag, not on resume — because the grid and the day column are the repository's
 * own flows over one bounded window, and they re-emit when a task is dragged, edited, synced in or
 * produced by a repeating series.
 */
@AndroidEntryPoint
class CalendarFragment : Fragment() {

    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CalendarViewModel by viewModels()
    private lateinit var controller: CalendarViewController

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = CalendarViewController(
            binding = binding,
            onTaskClick = { task -> openTask(task) },
            onDropTask = { taskId, date -> viewModel.moveTask(taskId, date) },
            onSelect = { date -> viewModel.select(date) },
            onModeSelected = { mode -> viewModel.setMode(mode) },
            onPageBack = { viewModel.previous() },
            onPageForward = { viewModel.next() },
            onToday = { viewModel.goToday() },
            onMessage = { message ->
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }
        )
        controller.bindControls()
        openOnRequestedDay()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    controller.render(state)
                    // The refusal is one-shot: the screen has now said it, so the state says it again
                    // only if the user drags and is refused once more.
                    if (state.dragRefused) viewModel.clearDragRefused()
                }
            }
        }
    }

    /**
     * Moves to the day another screen named, when it named one.
     *
     * The insights panel and the heavy-day reading count a particular day, and a "go and look" that opens the
     * calendar on today makes the reader find the day again by hand. The graph's default is the day no device
     * ever gave, so a calendar opened from the bottom bar — or from any destination that has no day in mind —
     * is left to open the way it always did. A level this build cannot name is read as the month, which is the
     * level the screen opens at anyway.
     */
    private fun openOnRequestedDay() {
        val args = CalendarFragmentArgs.fromBundle(requireArguments())
        if (args.dayEpoch < 0L) return
        viewModel.focus(
            LocalDate.ofEpochDay(args.dayEpoch),
            CalendarMode.entries.firstOrNull { it.name == args.mode } ?: CalendarMode.MONTH
        )
    }

    /** The editor for the task behind a chip, a block or a marker. */
    private fun openTask(task: Task) {
        findNavController().navigate(
            CalendarFragmentDirections.actionCalendarToTaskDetail(task.id)
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
