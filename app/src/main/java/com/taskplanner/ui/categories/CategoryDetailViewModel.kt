package com.taskplanner.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.model.Category
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryDetailViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _categoryDetailState = MutableStateFlow<CategoryDetailState>(CategoryDetailState.Success())
    val categoryDetailState: StateFlow<CategoryDetailState> = _categoryDetailState

    private var currentCategory: Category? = null

    fun loadCategory(categoryId: Long) {
        viewModelScope.launch {
            _categoryDetailState.value = CategoryDetailState.Loading
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val category = categoryRepository.getCategoryById(categoryId)
                    if (category != null) {
                        currentCategory = category
                        _categoryDetailState.value = CategoryDetailState.Success(
                            name = category.name,
                            color = category.color,
                            isValid = true
                        )
                    } else {
                        _categoryDetailState.value = CategoryDetailState.Error("Category not found")
                    }
                } else {
                    _categoryDetailState.value = CategoryDetailState.Error("User not found")
                }
            } catch (e: Exception) {
                _categoryDetailState.value = CategoryDetailState.Error(e.message ?: "Failed to load category")
            }
        }
    }

    fun updateCategoryName(name: String) {
        val currentState = _categoryDetailState.value
        if (currentState is CategoryDetailState.Success) {
            _categoryDetailState.value = currentState.copy(
                name = name,
                isValid = name.isNotBlank()
            )
        }
    }

    fun updateCategoryColor(color: Int) {
        val currentState = _categoryDetailState.value
        if (currentState is CategoryDetailState.Success) {
            _categoryDetailState.value = currentState.copy(color = color)
        }
    }

    fun saveCategory() {
        val currentState = _categoryDetailState.value
        if (currentState !is CategoryDetailState.Success || !currentState.isValid) {
            return
        }

        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    val category = currentCategory?.copy(
                        name = currentState.name,
                        color = currentState.color,
                        updatedAt = System.currentTimeMillis()
                    ) ?: Category(
                        name = currentState.name,
                        color = currentState.color,
                        icon = "ic_category", // Default icon
                        userId = currentUser.id,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )

                    if (currentCategory != null) {
                        categoryRepository.updateCategory(category)
                    } else {
                        categoryRepository.insertCategory(category)
                    }
                    _categoryDetailState.value = CategoryDetailState.Saved
                } else {
                    _categoryDetailState.value = CategoryDetailState.Error("User not found")
                }
            } catch (e: Exception) {
                _categoryDetailState.value = CategoryDetailState.Error(e.message ?: "Failed to save category")
            }
        }
    }

    fun deleteCategory() {
        val category = currentCategory ?: return

        viewModelScope.launch {
            try {
                categoryRepository.deleteCategory(category)
                _categoryDetailState.value = CategoryDetailState.Deleted
            } catch (e: Exception) {
                _categoryDetailState.value = CategoryDetailState.Error(e.message ?: "Failed to delete category")
            }
        }
    }
}

sealed class CategoryDetailState {
    object Loading : CategoryDetailState()
    data class Success(
        val name: String = "",
        val color: Int = 0xFF000000.toInt(), // Default black color
        val isValid: Boolean = false
    ) : CategoryDetailState()
    data class Error(val message: String) : CategoryDetailState()
    object Saved : CategoryDetailState()
    object Deleted : CategoryDetailState()
} 