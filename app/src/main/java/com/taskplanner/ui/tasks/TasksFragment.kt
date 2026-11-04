package com.taskplanner.ui.tasks

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
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
import com.taskplanner.data.hierarchy.HierarchyDeletePolicy
import com.taskplanner.data.hierarchy.HierarchyMoveResult
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.databinding.FragmentTasksBinding
import com.taskplanner.ui.util.EditTextDialog
import com.taskplanner.ui.util.WorkConflictDialog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TasksViewModel by viewModels()
    private lateinit var tasksAdapter: TasksAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    /**
     * The move the gesture in progress has last been allowed to make, kept until the finger lifts.
     *
     * ItemTouchHelper reports a drag one neighbour at a time, and the row the reader drops on is the answer to
     * where it belongs; writing on every step would put the database in an order the reader never finished making.
     * Null means the gesture moved nothing, so nothing is written.
     */
    private var pendingDrag: HierarchyMoveResult.Accepted? = null

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
        setupHeader()
        setupRecyclerView()
        setupObservers()
        setupListeners()
    }

    /**
     * The header is the screen's only chrome, and it is the reason four features work.
     *
     * `menu_tasks.xml` declared search, sort, new topic, archive and work history since the stage that
     * added them, and `addMenuProvider` wired all five — but the app runs a NoActionBar theme with no
     * Toolbar in any layout, so the menu was never drawn and each of those actions had exactly one caller
     * behind chrome that did not exist. Archive had no other entry point in the app at all.
     *
     * The two popups are built here rather than through a `MenuProvider` so there is one path to each
     * action. The search field is a field and not a `SearchView` because it lives on the screen, where the
     * query it holds is visible while the list below it narrows.
     */
    private fun setupHeader() {
        val header = binding.tasksHeader
        header.headerTitle.setText(R.string.title_tasks)

        header.headerSearchButton.setOnClickListener {
            header.headerSearchLayout.visibility = View.VISIBLE
            header.headerSearchField.requestFocus()
        }
        header.headerSearchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })
        header.headerSortButton.setOnClickListener { anchor -> showSortMenu(anchor) }
        header.headerMenuButton.setOnClickListener { anchor -> showOverflowMenu(anchor) }
        // One press for all three: the chip that counts the filters is the control that removes them, so a
        // reader who has turned two on never has to find each one again to turn them off.
        binding.filtersChip.setOnClickListener { viewModel.clearFilters() }
    }

    private fun showSortMenu(anchor: View) = PopupMenu(requireContext(), anchor).apply {
        menuInflater.inflate(R.menu.menu_tasks_sort, menu)
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort_manual -> viewModel.setSortOrder(SortOrder.MANUAL)
                R.id.action_sort_by_date -> viewModel.setSortOrder(SortOrder.DATE_DESC)
                R.id.action_sort_by_priority -> viewModel.setSortOrder(SortOrder.PRIORITY_DESC)
                R.id.action_sort_by_name -> viewModel.setSortOrder(SortOrder.NAME_ASC)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }.show()

    private fun showOverflowMenu(anchor: View) = PopupMenu(requireContext(), anchor).apply {
        menuInflater.inflate(R.menu.menu_tasks_overflow, menu)
        setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_group -> promptCreateGroup()
                R.id.action_archive ->
                    findNavController().navigate(TasksFragmentDirections.actionTasksToArchive())
                R.id.action_work_history ->
                    findNavController().navigate(TasksFragmentDirections.actionTasksToWorkHistory())
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }.show()

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
            onWorkToggle = { task -> viewModel.toggleWork(task) },
            onToggleBranch = { task -> viewModel.toggleBranchCollapse(task.id) },
            onHierarchy = { anchor, task -> showHierarchyMenu(anchor, task) },
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
                val list = tasksAdapter.currentList
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from !in list.indices || to !in list.indices) return false
                val dragged = list[from] as? TasksListItem.TaskRow ?: return false
                val droppedOn = list[to] as? TasksListItem.TaskRow ?: return false
                val plan = TaskHierarchyPolicy.planSiblingDrag(
                    list.filterIsInstance<TasksListItem.TaskRow>().map { it.task },
                    dragged.task.id,
                    droppedOn.task.id
                ) as? HierarchyMoveResult.Accepted ?: return false
                // Only a step the list actually took is a step worth writing: a plan recorded when the rows never
                // moved would put the database somewhere the reader never dragged anything to.
                val moved = applyDragToRows(plan)
                if (moved) pendingDrag = plan
                return moved
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                // A gesture starts with nothing accepted. Without this, a drag the reader abandoned — a tap, or a
                // first step onto a row that is not a sibling — would let `clearView` commit the *previous*
                // gesture's plan a second time.
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) pendingDrag = null
            }

            override fun isLongPressDragEnabled() = false

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1f
                commitPendingDrag()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
    }

    /**
     * Moves the dragged row *and everything hanging under it*, as one piece, to the side of the row it was dropped on.
     *
     * A row the reader grabbed is the head of a branch, and moving only the head would leave its children sitting
     * above their own parent — an order no reader asked for and no screen could undo. So the block lifts together and
     * comes down in the order it already had, next to the neighbour the gesture ended on.
     */
    private fun applyDragToRows(plan: HierarchyMoveResult.Accepted): Boolean {
        val moving = plan.movedTaskIds.toSet()
        val rows = tasksAdapter.currentList
        val block = rows.filter { (it as? TasksListItem.TaskRow)?.task?.id in moving }
        val rest = rows.filterNot { (it as? TasksListItem.TaskRow)?.task?.id in moving }
        val anchorAt = rest.indexOfFirst { (it as? TasksListItem.TaskRow)?.task?.id == plan.anchorTaskId }
        if (anchorAt < 0 || block.isEmpty()) return false
        val reordered = rest.toMutableList()
        reordered.addAll(if (plan.placeAfter) anchorAt + 1 else anchorAt, block)
        tasksAdapter.submitList(reordered)
        return true
    }

    /**
     * Writes the move the gesture finished, rather than the whole list the reader happened to be looking at.
     *
     * The list holds the rows the filters let through, so persisting it is what made a filtered drag renumber the
     * profile from zero and collide with the rows the filter was hiding. The screen now says only "this row, beside
     * that one, on this side" and lets [com.taskplanner.data.repository.TaskHierarchyRepository] place it in the
     * profile's one sequence.
     */
    private fun commitPendingDrag() {
        val plan = pendingDrag ?: return
        pendingDrag = null
        val head = plan.movedTaskIds.firstOrNull() ?: return
        viewModel.moveTaskBeside(head, plan.anchorTaskId, plan.placeAfter)
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.workConflict.collect { conflict -> conflict?.let { showWorkConflict(it) } }
            }
        }
        // The delete windows. §4 makes the pre-read async — the press asks the repository what the set would take
        // before anything can be shown — so the answer arrives on its own and this is the one place that turns it
        // into a window. `bind` is production, shared with whatever screen takes it next: it collects the view
        // model's one-shot channel while this view is started, shows one dialog per request, and stops with the view,
        // which is what keeps a recreate from summoning a window the reader was already shown.
        DeleteDialog.bind(
            owner = viewLifecycleOwner,
            scope = viewLifecycleOwner.lifecycleScope,
            requests = viewModel.deleteRequests,
            context = { requireContext() },
            onAnswer = { request, policy -> viewModel.resolveDelete(request, policy) },
            onBusy = { viewModel.reportDeleteBusy() }
        )
    }

    /**
     * Start was refused because another task holds the profile's single timer, so the screen names that
     * task and lets the user decide what happens to it.
     *
     * Nothing is written until an answer is given — not the other session, not this one. Dismissing the
     * dialog by any means other than a choice is the "keep measuring the other task" answer.
     */
    private fun showWorkConflict(conflict: WorkConflict) {
        WorkConflictDialog.show(this, viewModel, conflict)
    }

    private fun render(state: TasksState) {
        // The count is the account's open tasks, not the rows currently on screen: spec §4.1 names
        // `activeTaskCount` for this slot, and a line that changed under a filter would stop answering the
        // question the header is asked ("how much is still open") to answer one the list already shows.
        binding.tasksHeader.headerSubtitle.text =
            getString(R.string.tasks_open_count_format, state.activeTaskCount)
        binding.filtersChip.apply {
            visibility = if (state.activeFilterCount > 0) View.VISIBLE else View.GONE
            text = getString(R.string.filters_count_format, state.activeFilterCount)
        }
        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.emptyView.visibility =
            if (state.items.isEmpty() && !state.isLoading) View.VISIBLE else View.GONE

        tasksAdapter.selectionMode = state.selectionMode
        tasksAdapter.reorderEnabled = state.sortOrder == SortOrder.MANUAL
        tasksAdapter.submitList(state.items)

        // The bar replaces the header rather than stacking on it: while selecting, the count of what is
        // selected is the line the reader needs, and the two occupy the same slot.
        val barVisible = if (state.selectionMode) View.VISIBLE else View.GONE
        binding.selectionBar.root.visibility = barVisible
        binding.tasksHeader.root.visibility = if (state.selectionMode) View.GONE else View.VISIBLE
        if (state.selectionMode) {
            binding.selectionBar.selectionCount.text =
                getString(R.string.selection_count_format, state.selectedIds.size)
        }

        if (!swipeRefreshLayoutGuard()) binding.swipeRefreshLayout.isRefreshing = false

        state.error?.let { errorRes ->
            Snackbar.make(binding.root, getString(errorRes), Snackbar.LENGTH_LONG).show()
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

        binding.selectionBar.selectAllButton.setOnClickListener { viewModel.selectAllVisible() }
        binding.selectionBar.cancelSelectionButton.setOnClickListener {
            tasksAdapter.closeOpenPanel()
            viewModel.exitSelection()
        }
        binding.selectionBar.deleteSelectedButton.setOnClickListener { confirmDeleteSelected() }
    }

    // ---------------------------------------------------------------- dialogs

    /**
     * One press of a card's delete control asks the domain and then shows whatever one window the answer calls for.
     *
     * §4's whole requirement is about this line and the order it puts things in. The shape used to be a confirmation
     * whose positive button *then* ran the command — and only the command's `Confirm` answer, the one that says "this
     * row holds a branch", put up the two real choices in a second window. So the reader confirmed a delete before
     * knowing which delete they had confirmed. Now the screen states the press to the repository first
     * ([TasksViewModel.requestDelete], whose `deleteBatchSummary` read is a read and writes nothing) and the window
     * that follows is chosen from that answer: the plain confirmation for a row with nothing under it, the branch
     * choice for a row with something under it. [DeleteDialog] draws both, because the shape is not this screen's to
     * invent twice.
     */
    private fun confirmDelete(task: Task) {
        viewModel.requestDelete(listOf(task.id))
    }

    /**
     * The selection bar's delete: the same single window, over the whole set.
     *
     * The set is evaluated before any of it is written — `deleteBatchSummary` counts a parent and its own selected
     * descendant once each, and [TasksViewModel.deleteRows] refuses to start on the first half while the second half
     * still has a question open — so bulk never regresses into "delete, then discover".
     *
     * What the press is about is the view model's live selection, so the screen says only that its bar was pressed
     * ([TasksViewModel.requestDeleteSelection]) rather than handing back `lastState.selectedIds`, the set the *last
     * render* drew. The two can differ by a tick: a tick lands in the selection at once, while the drawn state
     * catches up when the render collector runs, and a press taken from the snapshot asks about the rows the reader
     * was looking at a moment ago — measured as one row and its singular sentence where the bar counted two. The
     * snapshot is a copy of a copy of the selection; the selection is the authority.
     */
    private fun confirmDeleteSelected() {
        viewModel.requestDeleteSelection()
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

    /**
     * Opens the hierarchy menu for one row, over the lists the last render drew from.
     *
     * The screen supplies the context and the commands; the decision about what may be pressed is `HierarchyMenu`'s,
     * read from the same policy the repository writes under — so a greyed row is a refusal the domain would have
     * answered, and an enabled one is a write it will make. A row that a newer render has already dropped answers
     * "nothing is available", which is the honest reading of a card that stopped being on screen mid-press.
     */
    private fun showHierarchyMenu(anchor: View, task: Task) {
        val screen = viewModel.hierarchyContext ?: return
        // The block the card was drawn inside, not the profile's sequence: an indent hangs a row on the row the
        // reader sees above it, and a topic's first row has nothing above it even when storage says otherwise.
        val block = screen.blockFor(task.id) ?: return
        HierarchyMenu.build(
            context = requireContext(),
            anchor = anchor,
            row = task,
            drawn = block,
            stored = screen.stored,
            onIndent = { row, above -> viewModel.indentTask(row.id, above.id) },
            onOutdent = { row -> viewModel.outdentTask(row.id) }
        ).show()
    }

    private fun showGroupMenu(anchor: View, group: TaskGroup) {
        // `GroupHeaderMenu` builds the popup, names the state row and decides which state a press on it asks for —
        // see the object's comment for why that pairing is not left split across two files. What stays here is the
        // screen's own business: the prompt, the confirmation, and the commands.
        GroupHeaderMenu.build(
            context = requireContext(),
            anchor = anchor,
            group = group,
            onRename = { topic ->
                EditTextDialog.prompt(
                    requireContext(),
                    title = getString(R.string.rename_group),
                    hint = getString(R.string.hint_group_name),
                    prefill = topic.name,
                    positive = getString(R.string.action_save)
                ) { name ->
                    if (name.isNotBlank()) viewModel.renameGroup(topic.uuid, name)
                }
            },
            onCompletion = { completed ->
                // §8. The mark is the topic's own: the tasks under it keep the statuses the reader gave them, and
                // nothing here recomputes the mark from those statuses either.
                viewModel.setGroupCompleted(group.uuid, completed)
            },
            onDelete = { topic ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm_delete_group_title)
                    .setMessage(R.string.confirm_delete_group_message)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        viewModel.deleteGroup(topic.uuid)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ).show()
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
