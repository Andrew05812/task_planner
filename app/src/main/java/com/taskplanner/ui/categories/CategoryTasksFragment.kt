package com.taskplanner.ui.categories

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
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.databinding.FragmentCategoryTasksBinding
import com.taskplanner.ui.tasks.DeleteDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CategoryTasksFragment : Fragment() {
    private var _binding: FragmentCategoryTasksBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CategoryTasksViewModel by viewModels()
    private val args: CategoryTasksFragmentArgs by navArgs()
    private lateinit var tasksAdapter: CategoryTaskAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCategoryTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        bindDeleteDialog()
        observeViewModel()
        viewModel.loadCategoryTasks(args.categoryId)
    }

    /**
     * The delete windows this screen can show, drawn by the one object that knows how both of them look.
     *
     * A row with something under it has two deletes and the reader owns the choice; a row with nothing under it has
     * one and must not be made to answer a question with a single answer. §4 put that pair in
     * [com.taskplanner.ui.tasks.DeleteDialog] rather than in the tasks screen, and this screen reads the same channel
     * shape it was written for — so the category list cannot grow a second, narrower idea of what deleting means.
     */
    private fun bindDeleteDialog() {
        DeleteDialog.bind(
            owner = viewLifecycleOwner,
            scope = viewLifecycleOwner.lifecycleScope,
            requests = viewModel.deleteRequests,
            context = { requireContext() },
            onAnswer = { request, policy -> viewModel.resolveDelete(request, policy) },
            onBusy = { viewModel.reportDeleteBusy() }
        )
    }

    private fun setupRecyclerView() {
        tasksAdapter = CategoryTaskAdapter(
            onTaskClick = { item ->
                findNavController().navigate(
                    CategoryTasksFragmentDirections.actionCategoryTasksToTaskDetail(item.task.id)
                )
            },
            onTaskLongClick = { item -> confirmDelete(item) },
            onStart = { item -> viewModel.startTask(item.task) },
            onPause = { item -> viewModel.pauseTask(item.task) },
            onResume = { item -> viewModel.resumeTask(item.task) },
            onComplete = { item -> viewModel.completeTask(item.task) }
        )

        binding.tasksRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tasksAdapter
        }
    }

    /**
     * The row's delete: one press, one window, whose shape the domain decides.
     *
     * What used to live here was a generic "Delete Task?" built by this screen, whose confirmation called
     * `taskRepository.deleteTask` — a tombstone for the pressed row and no word to the rows under it. The question is
     * now asked by the view model through the shared coordinator, and drawn by [DeleteDialog], which shows the plain
     * confirmation only when the domain says the row has nothing under it.
     */
    private fun confirmDelete(item: TaskWithCategory) {
        viewModel.requestDelete(item.task)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.tasksState.collect { state ->
                    when (state) {
                        is CategoryTasksState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.emptyView.visibility = View.GONE
                            tasksAdapter.submitList(emptyList()) // Clear list while loading
                        }
                        is CategoryTasksState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.emptyView.visibility = if (state.tasks.isEmpty()) View.VISIBLE else View.GONE
                            tasksAdapter.submitList(state.tasks)
                        }
                         is CategoryTasksState.Empty -> {
                             binding.progressBar.visibility = View.GONE
                             binding.emptyView.visibility = View.VISIBLE
                             tasksAdapter.submitList(emptyList()) // Clear the list
                        }
                        is CategoryTasksState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.emptyView.visibility = View.GONE
                            Snackbar.make(
                                binding.root, getString(state.messageRes), Snackbar.LENGTH_LONG
                            ).show()
                            // No viewModel.clearError() here as it doesn't exist in CategoryTasksViewModel
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.category.collect { category ->
                    category?.let {
                        // requireActivity().title = it.name
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 