package com.taskplanner.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.*

@Suppress("UNUSED_PARAMETER")
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TasksState())
    val state: StateFlow<TasksState> = _state.asStateFlow()

    private val _selectedStatus = MutableStateFlow<TaskStatus?>(null)
    val selectedStatus: StateFlow<TaskStatus?> = _selectedStatus.asStateFlow()

    private val _taskSaveState = MutableStateFlow<TaskSaveState>(TaskSaveState.Initial)
    val taskSaveState: StateFlow<TaskSaveState> = _taskSaveState

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _currentTask = MutableStateFlow<Task?>(null)
    val currentTask: StateFlow<Task?> = _currentTask

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories

    init {
        loadTasks()
        loadCategories()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            try {
                categoryRepository.getAllCategories().collect { 
                    _categories.value = it
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun loadTasks() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                // val userId = 1L // TODO: Get actual user ID from user session
                combine(
                    taskRepository.getAllTasks(), // Already sorted by order in Dao
                    categoryRepository.getAllCategories()
                ) { tasks, categories ->
                    val categoryMap = categories.associateBy { it.id }
                    val filteredTasks = when (_selectedStatus.value) {
                        null -> tasks
                        else -> tasks.filter { it.status == _selectedStatus.value }
                    }

                    // Apply user's sort preference for display, unless it's the default order
                     val sortedTasks = if (_sortOrder.value == SortOrder.DEFAULT_ORDER) {
                         filteredTasks
                     } else {
                         when (_sortOrder.value) {
                            SortOrder.DATE_DESC -> filteredTasks.sortedByDescending { it.dueDate }
                            SortOrder.DATE_ASC -> filteredTasks.sortedBy { it.dueDate }
                            SortOrder.PRIORITY_DESC -> filteredTasks.sortedByDescending { it.priority }
                            SortOrder.PRIORITY_ASC -> filteredTasks.sortedBy { it.priority }
                            SortOrder.NAME_ASC -> filteredTasks.sortedBy { it.title }
                            SortOrder.NAME_DESC -> filteredTasks.sortedByDescending { it.title }
                             else -> filteredTasks // Should not happen if all SortOrder values are handled
                         }
                     }

                    sortedTasks.map { task ->
                        TaskWithCategory(
                            task = task,
                            category = task.categoryId?.let { categoryMap[it] }
                        )
                    }
                }.collect { tasksWithCategory ->
                    _state.update {
                        it.copy(tasks = tasksWithCategory, isLoading = false)
                    }
                }

            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        error = e.message ?: "Failed to load tasks",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun setSelectedStatus(status: TaskStatus?) {
        when (status) {
            TaskStatus.TODO -> _selectedStatus.value = TaskStatus.TODO
            TaskStatus.IN_PROGRESS -> _selectedStatus.value = TaskStatus.IN_PROGRESS
            TaskStatus.COMPLETED -> _selectedStatus.value = TaskStatus.COMPLETED
            null -> _selectedStatus.value = null
        }
        loadTasks()
    }

    fun setSortOrder(sortOrder: SortOrder) {
        _sortOrder.value = sortOrder
        loadTasks() // Reload tasks with the new sort order applied
    }

    fun updateTaskStatus(task: Task) {
        viewModelScope.launch {
            try {
                val updatedTask = task.copy(
                    updatedAt = System.currentTimeMillis()
                )
                taskRepository.updateTask(updatedTask)
                loadTasks() // Reload tasks to update the list
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        error = e.message ?: "Failed to update task status"
                    )
                }
            }
        }
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            try {
                taskRepository.deleteTask(task)
                loadTasks() // Reload tasks to update the list
            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        error = e.message ?: "Failed to delete task"
                    )
                }
            }
        }
    }

    fun saveTask(
        taskId: Long = 0,
        title: String,
        description: String,
        dueDate: Date? = null,
        priority: Priority = Priority.MEDIUM,
        status: TaskStatus = TaskStatus.TODO,
        categoryId: Long? = null
    ) {
        if (title.isBlank()) {
            _taskSaveState.value = TaskSaveState.Error("Title cannot be empty")
            return
        }

        viewModelScope.launch {
            _taskSaveState.value = TaskSaveState.Loading
            try {
                val currentTask = _currentTask.value
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val task = if (currentTask != null) {
                        currentTask.copy(
                            title = title.trim(),
                            description = description.trim(),
                            status = status,
                            priority = priority,
                            updatedAt = System.currentTimeMillis(),
                            dueDate = dueDate?.time,
                            categoryId = categoryId,
                            userId = currentUser.id
                        )
                    } else {
                        Task(
                            id = taskId,
                            title = title.trim(),
                            description = description.trim(),
                            status = status,
                            priority = priority,
                            userId = currentUser.id,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            dueDate = dueDate?.time,
                            categoryId = categoryId
                        )
                    }

                    if (currentTask != null) {
                        taskRepository.updateTask(task)
                    } else {
                        taskRepository.insertTask(task)
                    }
                    
                    _taskSaveState.value = TaskSaveState.Success
                    loadTasks()
                } else {
                    _taskSaveState.value = TaskSaveState.Error("User not found")
                }
            } catch (e: Exception) {
                _taskSaveState.value = TaskSaveState.Error(e.message ?: "Failed to save task")
            }
        }
    }

    fun resetTaskSaveState() {
        _taskSaveState.value = TaskSaveState.Initial
    }

    fun loadTask(taskId: Long) {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val task = taskRepository.getTaskById(taskId)
                    _currentTask.value = task
                } else {
                    _taskSaveState.value = TaskSaveState.Error("User not found")
                }
            } catch (e: Exception) {
                _taskSaveState.value = TaskSaveState.Error(e.message ?: "Failed to load task")
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun moveTask(fromPosition: Int, toPosition: Int) {
        val currentTasks = (_state.value.tasks).toMutableList()
        if (fromPosition < 0 || fromPosition >= currentTasks.size ||
            toPosition < 0 || toPosition >= currentTasks.size
        ) {
            return
        }

        val taskToMove = currentTasks.removeAt(fromPosition)
        currentTasks.add(toPosition, taskToMove)

        // Update the order property of the moved tasks
        currentTasks.forEachIndexed { index, taskWithCategory ->
            taskWithCategory.task.order = index // Assuming 'order' is a var in the Task data class
        }

        // Update tasks in the database (this might be slow for many tasks)
        viewModelScope.launch {
            try {
                currentTasks.forEach { taskWithCategory ->
                    taskRepository.updateTask(taskWithCategory.task)
                }
                // No need to call loadTasks() here, as the flow from the repository should update the UI
            } catch (e: Exception) {
                // Handle error
                _state.update { 
                    it.copy(
                        error = e.message ?: "Failed to reorder tasks"
                    )
                }
            }
        }

        // Update the state immediately for a smoother visual experience
        _state.update { 
            it.copy(
                tasks = currentTasks.toList()
            )
        }
    }

    fun updateTaskOrder(tasks: List<Task>) {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val tasksToUpdate = tasks.mapIndexed { index, task ->
                        task.copy(order = index)
                    }

                    tasksToUpdate.forEach { task ->
                        taskRepository.updateTask(task)
                    }

                    // Temporarily set sort order to default after saving to ensure UI updates with the saved order
                    val originalSortOrder = _sortOrder.value
                    _sortOrder.value = SortOrder.DEFAULT_ORDER

                    // Reload tasks after updating order to ensure UI is in sync with the saved order
                    // This will now load and display by 'order' due to the temporary sort order change
                    loadTasks()
                } else {
                     _state.update { 
                        it.copy(
                            error = "User not found"
                        )
                    }
                }

            } catch (e: Exception) {
                _state.update { 
                    it.copy(
                        error = e.message ?: "Failed to save task order"
                    )
                }
            }
        }
    }
}

data class TasksState(
    val tasks: List<TaskWithCategory> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed class TaskSaveState {
    object Initial : TaskSaveState()
    object Loading : TaskSaveState()
    object Success : TaskSaveState()
    data class Error(val message: String) : TaskSaveState()
} 