package com.taskplanner.ui.categories

import androidx.annotation.StringRes

sealed class CategorySaveState {
    object Initial : CategorySaveState()
    object Loading : CategorySaveState()
    object Success : CategorySaveState()
    /** The sentence lives in resources; see `CategoriesState.Error` for why the id travels. */
    data class Error(@StringRes val messageRes: Int) : CategorySaveState()
} 