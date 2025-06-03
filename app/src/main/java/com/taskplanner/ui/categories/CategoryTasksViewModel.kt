package com.taskplanner.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.data.repository.TaskRepository
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
                    _tasksState.value = CategoryTasksState.Error("Category not found")
                    return@launch
                }
                _category.value = category

                taskRepository.getTasksByCategory(categoryId)
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
                _tasksState.value = CategoryTasksState.Error(e.message ?: "Failed to load tasks")
            }
        }
    }

    fun updateTaskStatus(task: Task) {
        viewModelScope.launch {
            try {
                taskRepository.updateTask(task)
                // Reload tasks after update
                currentCategoryId?.let { loadCategoryTasks(it) }
            } catch (e: Exception) {
                _tasksState.value = CategoryTasksState.Error(e.message ?: "Failed to update task")
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
                _tasksState.value = CategoryTasksState.Error(e.message ?: "Failed to delete task")
            }
        }
    }
}

sealed class CategoryTasksState {
    object Loading : CategoryTasksState()
    object Empty : CategoryTasksState()
    data class Success(val tasks: List<TaskWithCategory>) : CategoryTasksState()
    data class Error(val message: String) : CategoryTasksState()
} 