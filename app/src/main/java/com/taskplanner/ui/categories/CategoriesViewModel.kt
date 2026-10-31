package com.taskplanner.ui.categories

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.R
import com.taskplanner.data.model.Category
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.ui.util.LocalisedNames
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
                    // The row order a reader sees. `CategoryDao` asks SQLite for `ORDER BY name ASC`, which is
                    // binary collation — for Cyrillic that puts every capital before every small letter and
                    // `ё` above `а`, so it is a stable storage order and not an alphabet. The screen knows the
                    // language, so the alphabet is applied here and the stored order is left alone for the sync
                    // engine and the export, which have no use for either.
                    categories.map { category ->
                        CategoryWithTaskCount(
                            category = category,
                            taskCount = tasks.count { it.categoryId == category.id }
                        )
                    }.sortedWith(LocalisedNames.byKey<CategoryWithTaskCount> { it.category.name })
                }.collect { categoriesWithCount ->
                    _categoriesState.value = if (categoriesWithCount.isEmpty()) {
                        CategoriesState.Empty
                    } else {
                        CategoriesState.Success(categoriesWithCount)
                    }
                }
            } catch (e: Exception) {
                // The reader gets the sentence the app owns, in the language the app is drawn in; the storage
                // complaint that caused it goes to the log, where a machine can read it. Showing both was the
                // old shape, and it produced a Russian sentence wrapped around an English SQLite message.
                Log.w(TAG, "categories could not be loaded", e)
                _categoriesState.value = CategoriesState.Error(R.string.error_load_categories)
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

    private companion object {
        const val TAG = "CategoriesViewModel"
    }
}

sealed class CategoriesState {
    object Loading : CategoriesState()
    object Empty : CategoriesState()
    data class Success(val categories: List<CategoryWithTaskCount>) : CategoriesState()

    /**
     * A command that did not go through, named by the resource that says so.
     *
     * The id travels rather than the sentence, for two reasons the sentence could not answer. A state object
     * outlives the moment it was written — it survives the activity recreation that changing the language
     * causes — so a string put here stays in the language the reader has just left, while an id is read again
     * by whichever fragment draws the state. And the test can then assert *which* answer the app chose without
     * asserting what language the process happens to be in, which is the distinction Stage 14's third defect
     * is about.
     */
    data class Error(@StringRes val messageRes: Int) : CategoriesState()
} 