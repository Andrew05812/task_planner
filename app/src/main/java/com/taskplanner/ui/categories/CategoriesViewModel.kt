package com.taskplanner.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Category
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _categoriesState = MutableStateFlow<CategoriesState>(CategoriesState.Loading)
    val categoriesState: StateFlow<CategoriesState> = _categoriesState

    init {
        loadCategories()
    }

    fun loadCategories() {
        viewModelScope.launch {
            _categoriesState.value = CategoriesState.Loading
            try {
                // val userId = 1L // TODO: Get actual user ID
                combine(
                    categoryRepository.getAllCategories(),
                    taskRepository.getAllTasks()
                ) { categories, tasks ->
                    categories.map { category ->
                        CategoryWithTaskCount(
                            category = category,
                            taskCount = tasks.count { it.categoryId == category.id }
                        )
                    }
                }.collect { categoriesWithCount ->
                    _categoriesState.value = if (categoriesWithCount.isEmpty()) {
                        CategoriesState.Empty
                    } else {
                        CategoriesState.Success(categoriesWithCount)
                    }
                }
            } catch (e: Exception) {
                _categoriesState.value = CategoriesState.Error(e.message ?: "Failed to load categories")
            }
        }
    }

    fun deleteCategory(category: Category) {
        viewModelScope.launch {
            try {
                categoryRepository.deleteCategory(category)
                // The UI will be automatically updated through the Flow
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}

sealed class CategoriesState {
    object Loading : CategoriesState()
    object Empty : CategoriesState()
    data class Success(val categories: List<CategoryWithTaskCount>) : CategoriesState()
    data class Error(val message: String) : CategoriesState()
} 