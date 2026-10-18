package com.taskplanner.ui.tasks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.databinding.FragmentTasksBinding
import com.taskplanner.ui.util.EditTextDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TasksViewModel by viewModels()
    private lateinit var tasksAdapter: TasksAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var lastState: TasksState = TasksState()

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
        setupMenu()
        setupRecyclerView()
        setupObservers()
        setupListeners()
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                    menuInflater.inflate(R.menu.menu_tasks, menu)
                    (menu.findItem(R.id.action_search).actionView as? SearchView)?.apply {
                        setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                            override fun onQueryTextSubmit(query: String?) = false
                            override fun onQueryTextChange(newText: String?): Boolean {
                                viewModel.setSearchQuery(newText.orEmpty())
                                return true
                            }
                        })
                    }
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean =
                    when (menuItem.itemId) {
                        R.id.action_sort_manual -> {
                            viewModel.setSortOrder(SortOrder.MANUAL); true
                        }
                        R.id.action_sort_by_date -> {
                            viewModel.setSortOrder(SortOrder.DATE_DESC); true
                        }
                        R.id.action_sort_by_priority -> {
                            viewModel.setSortOrder(SortOrder.PRIORITY_DESC); true
                        }
                        R.id.action_sort_by_name -> {
                            viewModel.setSortOrder(SortOrder.NAME_ASC); true
                        }
                        R.id.action_new_group -> {
                            promptCreateGroup(); true
                        }
                        R.id.action_archive -> {
                            findNavController().navigate(
                                TasksFragmentDirections.actionTasksToArchive()
                            )
                            true
                        }
                        else -> false
                    }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    private fun setupRecyclerView() {
        tasksAdapter = TasksAdapter(
            onTaskClick = { task ->
                findNavController().navigate(
                    TasksFragmentDirections.actionTasksToTaskDetail(task.id)
                )
            },
            onLongPressTask = { task -> viewModel.enterSelection(task.id) },
            onToggleSelection = { task -> viewModel.toggleSelection(task.id) },
            onEdit = { task ->
                findNavController().navigate(
                    TasksFragmentDirections.actionTasksToTaskDetail(task.id)
                )
            },
            onDeleteRequest = { task -> confirmDelete(task) },
            onStart = { task -> viewModel.startTask(task) },
            onPause = { task -> viewModel.pauseTask(task) },
            onResume = { task -> viewModel.resumeTask(task) },
            onComplete = { task -> viewModel.completeTask(task) },
            onToggleGroup = { group -> viewModel.toggleGroupCollapsed(group) },
            onGroupMenu = { anchor, group -> showGroupMenu(anchor, group) },
            onStartDrag = { vh -> itemTouchHelper.startDrag(vh) }
        )
        tasksAdapter.onGroupHeaderLongPress = { group ->
            viewModel.toggleGroupSelection(group)
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tasksAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) tasksAdapter.closeOpenPanel()
                }
            })
        }

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = if (viewHolder.itemViewType == VIEW_TYPE_TASK &&
                tasksAdapter.reorderEnabled && !tasksAdapter.selectionMode
            ) {
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            } else {
                makeMovementFlags(0, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (target.itemViewType != viewHolder.itemViewType) return false
                val list = tasksAdapter.currentList.toMutableList()
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from !in list.indices || to !in list.indices) return false
                list.add(to, list.removeAt(from))
                tasksAdapter.submitList(list)
                return true
            }

            override fun isLongPressDragEnabled() = false

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                persistVisibleOrder()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun persistVisibleOrder() {
        val ids = tasksAdapter.currentList
            .filterIsInstance<TasksListItem.TaskRow>()
            .map { it.task.id }
        viewModel.saveVisibleOrder(ids)
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: TasksState) {
        lastState = state
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.emptyView.visibility =
            if (state.items.isEmpty() && !state.isLoading) View.VISIBLE else View.GONE

        tasksAdapter.selectionMode = state.selectionMode
        tasksAdapter.reorderEnabled = state.sortOrder == SortOrder.MANUAL
        tasksAdapter.submitList(state.items)

        binding.selectionBar.visibility = if (state.selectionMode) View.VISIBLE else View.GONE
        if (state.selectionMode) {
            binding.selectionCount.text =
                getString(R.string.selection_count_format, state.selectedIds.size)
        }

        if (!swipeRefreshLayoutGuard()) binding.swipeRefreshLayout.isRefreshing = false

        state.error?.let { error ->
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            viewModel.clearError()
        }
        renderCategoryChips(state)
    }

    private fun swipeRefreshLayoutGuard() = binding.swipeRefreshLayout.isRefreshing

    private var renderedChipSignature: String = ""

    private fun renderCategoryChips(state: TasksState) {
        val signature = state.categories.joinToString("|") { "${it.id}:${it.name}" } +
            "#${state.filterCategoryId}"
        if (signature == renderedChipSignature) return
        renderedChipSignature = signature

        val group = binding.chipGroupCategory
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()

        val allChip = buildChip(getString(R.string.filter_all), null)
        group.addView(allChip)
        state.categories.forEach { category ->
            group.addView(buildChip(category.name, category.id, category.color))
        }
        group.addView(buildChip(getString(R.string.no_category), TasksViewModel.NO_CATEGORY_ID))

        val checkedId = (0 until group.childCount)
            .map { group.getChildAt(it) }
            .firstOrNull { (it.tag as? Long?) == state.filterCategoryId }
            ?.id ?: allChip.id
        group.check(checkedId)
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { id -> group.findViewById<View>(id) }
            when (val tag = chip?.tag as? Long) {
                null -> viewModel.setCategoryFilter(null)
                else -> viewModel.setCategoryFilter(tag)
            }
            tasksAdapter.closeOpenPanel()
        }
    }

    private fun buildChip(label: String, categoryId: Long?, color: Int = 0): Chip {
        val chip = Chip(requireContext())
        // A generated id is required: ChipGroup.check() and the checked-state callback
        // both work with view ids, and chips built here have none from XML.
        chip.id = View.generateViewId()
        chip.text = label
        chip.tag = categoryId
        chip.isCheckable = true
        chip.isCheckedIconVisible = false
        if (color != 0) chip.setTextColor(color)
        return chip
    }

    private fun setupListeners() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            lifecycleScope.launch {
                viewModel.retry()
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
        binding.fabAddTask.setOnClickListener {
            findNavController().navigate(TasksFragmentDirections.actionTasksToTaskDetail(0L))
        }
        binding.emptyCreateTopicButton.setOnClickListener { promptCreateGroup() }

        binding.chipGroupStatus.setOnCheckedStateChangeListener { group, checkedIds ->
            val status = when (checkedIds.firstOrNull()) {
                R.id.chipTodo -> TaskStatus.TODO
                R.id.chipInProgress -> TaskStatus.IN_PROGRESS
                R.id.chipPaused -> TaskStatus.PAUSED
                else -> null
            }
            viewModel.setStatusFilter(status)
        }

        binding.selectAllButton.setOnClickListener { viewModel.selectAllVisible() }
        binding.cancelSelectionButton.setOnClickListener {
            tasksAdapter.closeOpenPanel()
            viewModel.exitSelection()
        }
        binding.deleteSelectedButton.setOnClickListener { confirmDeleteSelected() }
    }

    // ---------------------------------------------------------------- dialogs

    private fun confirmDelete(task: Task) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_task_title)
            .setMessage(R.string.dialog_delete_task_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteSelectedFor(listOf(task.id))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        val count = lastState.selectedIds.size
        if (count == 0) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_delete_selected_title)
            .setMessage(getString(R.string.confirm_delete_selected_message, count))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteSelected() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptCreateGroup() {
        EditTextDialog.prompt(
            requireContext(),
            title = getString(R.string.new_group),
            hint = getString(R.string.hint_group_name),
            positive = getString(R.string.action_create)
        ) { name ->
            if (name.isNotBlank()) viewModel.createGroup(name)
        }
    }

    private fun showGroupMenu(anchor: View, group: TaskGroup) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.rename_group)
        popup.menu.add(0, 2, 1, R.string.delete_group)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    EditTextDialog.prompt(
                        requireContext(),
                        title = getString(R.string.rename_group),
                        hint = getString(R.string.hint_group_name),
                        prefill = group.name,
                        positive = getString(R.string.action_save)
                    ) { name ->
                        if (name.isNotBlank()) viewModel.renameGroup(group.uuid, name)
                    }
                }
                2 -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.confirm_delete_group_title)
                        .setMessage(R.string.confirm_delete_group_message)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            viewModel.deleteGroup(group.uuid)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        binding.recyclerView.clearOnScrollListeners()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val VIEW_TYPE_TASK = 1
    }
}
