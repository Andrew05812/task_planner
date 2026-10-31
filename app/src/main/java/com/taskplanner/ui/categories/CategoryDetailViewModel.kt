package com.taskplanner.ui.categories

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryDetailViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _categoryDetailState = MutableStateFlow<CategoryDetailState>(CategoryDetailState.Success())
    val categoryDetailState: StateFlow<CategoryDetailState> = _categoryDetailState

    private var currentCategory: Category? = null

    fun loadCategory(categoryId: Long) {
        viewModelScope.launch {
            _categoryDetailState.value = CategoryDetailState.Loading
            try {
                // currentUserId() resolves the session from disk when needed, so a cold
                // start can't mistake an existing profile for "no user".
                val userId = sessionRepository.currentUserId()
                if (userId != null) {
                    val category = categoryRepository.getCategoryById(categoryId)
                    if (category != null) {
                        currentCategory = category
                        _categoryDetailState.value = CategoryDetailState.Success(
                            name = category.name,
                            color = category.color,
                            isValid = true
                        )
                    } else {
                        _categoryDetailState.value = CategoryDetailState.Error(R.string.error_category_missing)
                    }
                } else {
                    _categoryDetailState.value = CategoryDetailState.Error(R.string.error_profile_missing)
                }
            } catch (e: Exception) {
                Log.w(TAG, "category could not be opened", e)
                _categoryDetailState.value = CategoryDetailState.Error(R.string.error_open_category)
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
                val currentUserId = sessionRepository.currentUserId()
                if (currentUserId != null) {
                    val category = currentCategory?.copy(
                        name = currentState.name,
                        color = currentState.color,
                        updatedAt = System.currentTimeMillis()
                    ) ?: Category(
                        name = currentState.name,
                        color = currentState.color,
                        icon = "ic_category", // Default icon
                        userId = currentUserId,
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
                    _categoryDetailState.value = CategoryDetailState.Error(R.string.error_profile_missing)
                }
            } catch (e: Exception) {
                Log.w(TAG, "category could not be saved", e)
                _categoryDetailState.value = CategoryDetailState.Error(R.string.error_save_category)
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
                Log.w(TAG, "category could not be deleted", e)
                _categoryDetailState.value = CategoryDetailState.Error(R.string.error_delete_category)
            }
        }
    }
}

private const val TAG = "CategoryDetailViewModel"

sealed class CategoryDetailState {
    object Loading : CategoryDetailState()
    data class Success(
        val name: String = "",
        val color: Int = 0xFF000000.toInt(), // Default black color
        val isValid: Boolean = false
    ) : CategoryDetailState()
    /** The sentence lives in resources; see `CategoriesState.Error` for why the id travels. */
    data class Error(@StringRes val messageRes: Int) : CategoryDetailState()
    object Saved : CategoryDetailState()
    object Deleted : CategoryDetailState()
} 