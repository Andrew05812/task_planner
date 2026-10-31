package com.taskplanner.ui.categories

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.R
import com.taskplanner.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.parcelize.Parcelize

@HiltViewModel
class CategoryTasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _tasksState = MutableStateFlow<CategoryTasksState>(CategoryTasksState.Loading)
    val tasksState: StateFlow<CategoryTasksState> = _tasksState

    private val _category = MutableStateFlow<Category?>(null)
    val category: StateFlow<Category?> = _category

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

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            try {
                taskRepository.deleteTask(task)
                // Reload tasks after deletion
                currentCategoryId?.let { loadCategoryTasks(it) }
            } catch (e: Exception) {
                Log.w(TAG, "task could not be deleted", e)
                _tasksState.value = CategoryTasksState.Error(R.string.error_delete_task)
            }
        }
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