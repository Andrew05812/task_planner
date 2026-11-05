package com.taskplanner.ui.categories

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.hierarchy.HierarchyDeletePolicy
import com.taskplanner.data.hierarchy.HierarchyDeleteRefusal
import com.taskplanner.data.hierarchy.TaskDeleteCoordinator
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.R
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.ui.tasks.DeleteRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.parcelize.Parcelize

@HiltViewModel
class CategoryTasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val deleteCoordinator: TaskDeleteCoordinator
) : ViewModel() {

    private val _tasksState = MutableStateFlow<CategoryTasksState>(CategoryTasksState.Loading)
    val tasksState: StateFlow<CategoryTasksState> = _tasksState

    private val _category = MutableStateFlow<Category?>(null)
    val category: StateFlow<Category?> = _category

    private val _deleteRequests = Channel<DeleteRequest>(capacity = 1)

    /**
     * The delete questions this screen owes the reader, one at a time, in the same queue shape the tasks screen uses.
     *
     * [com.taskplanner.ui.tasks.DeleteDialog] reads it while the view is at least `STARTED`, so a press made while
     * the screen is away is held rather than lost, and a recreate cannot be shown the same question twice.
     */
    val deleteRequests: ReceiveChannel<DeleteRequest> = _deleteRequests

    private var currentCategoryId: Long? = null

    fun loadCategoryTasks(categoryId: Long) {
        currentCategoryId = categoryId
        viewModelScope.launch {
            _tasksState.value = CategoryTasksState.Loading
            try {
                val category = categoryRepository.getCategoryById(categoryId)
                if (category == null) {
                    _tasksState.value = CategoryTasksState.Error(R.string.error_category_missing)
                    return@launch
                }
                _category.value = category

                taskRepository.observeTasksByCategory(categoryId)
                    .map { tasks ->
                        tasks.map { task ->
                            TaskWithCategory(
                                task = task,
                                category = category
                            )
                        }
                    }
                    .collect { tasksWithCategory ->
                        _tasksState.value = if (tasksWithCategory.isEmpty()) {
                            CategoryTasksState.Empty
                        } else {
                            CategoryTasksState.Success(tasksWithCategory)
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "category tasks could not be loaded", e)
                _tasksState.value = CategoryTasksState.Error(R.string.error_load_tasks)
            }
        }
    }

    fun startTask(task: Task) = runTransition { taskRepository.startTask(task.id) }

    fun pauseTask(task: Task) = runTransition { taskRepository.pauseTask(task.id) }

    fun resumeTask(task: Task) = runTransition { taskRepository.resumeTask(task.id) }

    fun completeTask(task: Task) = runTransition { taskRepository.completeAndArchive(task.id) }

    private fun runTransition(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                currentCategoryId?.let { loadCategoryTasks(it) }
            } catch (e: Exception) {
                Log.w(TAG, "task could not be updated", e)
                _tasksState.value = CategoryTasksState.Error(R.string.error_update_task)
            }
        }
    }

    /**
     * The row's delete, asked about before it is written.
     *
     * This used to be `taskRepository.deleteTask(task)` — a single-row tombstone that never consulted the tree, so a
     * parent pressed here retired its row while its children went on naming it and the task list drew them at the
     * margin as though they had no parent. Stage 16 §5 closes that door: the branch is read from the domain
     * ([TaskDeleteCoordinator.summary]) and the same window the tasks screen shows is what answers it, because two
     * screens cannot be allowed to invent the shape of a delete twice.
     *
     * Nothing is written by this call. The write is [resolveDelete]'s, and it goes through the same coordinator the
     * tasks screen goes through — including its follow-ups, which are the reminder cancellations and the series' next
     * instance the single-row path performed and which no screen may have to remember to redo.
     */
    fun requestDelete(task: Task) {
        viewModelScope.launch {
            try {
                val summary = deleteCoordinator.summary(listOf(task.id))
                offerDelete(
                    DeleteRequest(
                        ids = listOf(task.id),
                        branchRows = summary.branchRows,
                        children = summary.children,
                        affectedRows = summary.affectedRows
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "the branch of a task could not be read", e)
                _tasksState.value = CategoryTasksState.Error(R.string.error_delete_task)
            }
        }
    }

    /**
     * The reader's answer, and the only way a delete is written on this screen.
     *
     * A plain confirmation answers with no policy and the coordinator's own pre-read decides whether the row really
     * has nothing under it; a branch window answers with one of the two operations. If the row gained a child between
     * the read and this call, the command comes back asking rather than guessing, and the question is put on screen
     * again — the tree as it is when the pen moves is what the reader is being asked about.
     */
    fun resolveDelete(request: DeleteRequest, policy: HierarchyDeletePolicy?) {
        viewModelScope.launch {
            try {
                val outcome = deleteCoordinator.delete(request.ids, policy)
                outcome.asks.forEach {
                    offerDelete(
                        request.copy(
                            branchRows = it.branchRows,
                            children = it.children,
                            affectedRows = it.affectedRows
                        )
                    )
                }
                outcome.refusal?.let { reason ->
                    _tasksState.value = CategoryTasksState.Error(
                        if (reason == HierarchyDeleteRefusal.MEASUREMENT_RUNNING) {
                            R.string.delete_blocked_running
                        } else {
                            R.string.delete_row_missing
                        }
                    )
                }
                if (outcome.tombstones.isNotEmpty()) currentCategoryId?.let { loadCategoryTasks(it) }
            } catch (e: Exception) {
                Log.w(TAG, "task could not be deleted", e)
                _tasksState.value = CategoryTasksState.Error(R.string.error_delete_task)
            }
        }
    }

    /** Hands the screen one question; a second, unanswered press is refused out loud rather than stacked or dropped. */
    private fun offerDelete(request: DeleteRequest) {
        if (_deleteRequests.trySend(request).isFailure) reportDeleteBusy()
    }

    /** Says the press was refused. A tap that opened nothing would otherwise read as a broken control. */
    fun reportDeleteBusy() {
        _tasksState.value = CategoryTasksState.Error(R.string.delete_one_question_at_a_time)
    }
}

private const val TAG = "CategoryTasksViewModel"

sealed class CategoryTasksState {
    object Loading : CategoryTasksState()
    object Empty : CategoryTasksState()
    data class Success(val tasks: List<TaskWithCategory>) : CategoryTasksState()
    /** The sentence lives in resources; see `CategoriesState.Error` for why the id travels. */
    data class Error(@StringRes val messageRes: Int) : CategoryTasksState()
} 