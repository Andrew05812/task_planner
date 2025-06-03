package com.taskplanner.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.taskplanner.data.model.TaskStatus

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    private val _statisticsState = MutableStateFlow<StatisticsState>(StatisticsState.Loading)
    val statisticsState: StateFlow<StatisticsState> = _statisticsState

    init {
        loadStatistics()
    }

    fun loadStatistics() {
        viewModelScope.launch {
            _statisticsState.value = StatisticsState.Loading
            try {
                // val userId = 1L // TODO: Get actual user ID
                val tasks = taskRepository.getAllTasks().first()
                
                if (tasks.isEmpty()) {
                    _statisticsState.value = StatisticsState.Empty
                    return@launch
                }

                // Calculate completion rate
                val completionRate = tasks.count { it.status == TaskStatus.COMPLETED }.toFloat() / tasks.size

                // Get all categories for the user
                val categories = categoryRepository.getAllCategories().first()
                    .associateBy { it.id }

                // Group tasks by category and count them
                val categoryStats = tasks.groupBy { task ->
                    task.categoryId?.let { categoryId ->
                        categories[categoryId]?.name
                    } ?: "Uncategorized"
                }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }

                _statisticsState.value = StatisticsState.Success(
                    completionRate = completionRate,
                    categoryDistribution = categoryStats.associate { it.first to it.second }
                )
            } catch (e: Exception) {
                _statisticsState.value = StatisticsState.Error(e.message ?: "Failed to load statistics")
            }
        }
    }
}

sealed class StatisticsState {
    object Loading : StatisticsState()
    object Empty : StatisticsState()
    data class Success(
        val completionRate: Float,
        val categoryDistribution: Map<String, Int>
    ) : StatisticsState()
    data class Error(val message: String) : StatisticsState()
} 