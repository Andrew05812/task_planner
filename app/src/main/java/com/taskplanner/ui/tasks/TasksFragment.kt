package com.taskplanner.ui.tasks

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.databinding.FragmentTasksBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import androidx.core.view.MenuProvider
import com.taskplanner.ui.tasks.TasksFragmentDirections.Companion.actionTasksToTaskDetail

@AndroidEntryPoint
class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TasksViewModel by viewModels()
    private lateinit var tasksAdapter: TasksAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_tasks, menu)
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        R.id.action_sort_by_date -> {
                            viewModel.setSortOrder(SortOrder.DATE_DESC)
                            true
                        }
                        R.id.action_sort_by_priority -> {
                            viewModel.setSortOrder(SortOrder.PRIORITY_DESC)
                            true
                        }
                        R.id.action_sort_by_name -> {
                            viewModel.setSortOrder(SortOrder.NAME_ASC)
                            true
                        }
                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
        setupRecyclerView()
        setupObservers()
        setupListeners()
        setupStatusChips()
    }

    private fun setupRecyclerView() {
        tasksAdapter = TasksAdapter(
            onTaskClick = { task ->
                findNavController().navigate(
                    actionTasksToTaskDetail(task.id)
                )
            },
            onTaskStatusChange = { task ->
                viewModel.updateTaskStatus(task)
            },
            onTaskDelete = { task ->
                viewModel.deleteTask(task)
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tasksAdapter
        }

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                tasksAdapter.currentList.toMutableList().apply {
                    val movedTask = removeAt(fromPosition)
                    add(toPosition, movedTask)
                    tasksAdapter.submitList(this.toList())
                }
                return true
            }

            override fun onSwiped(
                viewHolder: RecyclerView.ViewHolder,
                direction: Int
            ) {
            }

            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.7f
                } else {
                    viewHolder?.itemView?.alpha = 1.0f
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewModel.updateTaskOrder(tasksAdapter.currentList.map { it.task })
            }
        })

        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.emptyView.visibility = if (state.tasks.isEmpty() && !state.isLoading) View.VISIBLE else View.GONE
                    tasksAdapter.submitList(state.tasks)
                    
                    state.error?.let { error ->
                        Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadTasks()
            binding.swipeRefreshLayout.isRefreshing = false
        }

        binding.fabAddTask.setOnClickListener {
            findNavController().navigate(
                actionTasksToTaskDetail(0L)
            )
        }

        binding.chipGroupStatus.setOnCheckedStateChangeListener { group, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipTodo -> viewModel.setSelectedStatus(TaskStatus.TODO)
                R.id.chipInProgress -> viewModel.setSelectedStatus(TaskStatus.IN_PROGRESS)
                R.id.chipDone -> viewModel.setSelectedStatus(TaskStatus.COMPLETED)
                else -> viewModel.setSelectedStatus(null)
            }
        }
    }

    private fun setupStatusChips() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedStatus.collect { status ->
                    val chipId = when (status) {
                        TaskStatus.TODO -> R.id.chipTodo
                        TaskStatus.IN_PROGRESS -> R.id.chipInProgress
                        TaskStatus.COMPLETED -> R.id.chipDone
                        null -> View.NO_ID
                    }
                    if (chipId == View.NO_ID) {
                        binding.chipGroupStatus.clearCheck()
                    } else {
                        binding.chipGroupStatus.check(chipId)
                    }
                }
            }
        }
    }

    private fun showDeleteConfirmationDialog(task: Task) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_task_title)
            .setMessage(R.string.delete_task_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteTask(task)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}