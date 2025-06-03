package com.taskplanner.ui.categories

sealed class CategorySaveState {
    object Initial : CategorySaveState()
    object Loading : CategorySaveState()
    object Success : CategorySaveState()
    data class Error(val message: String) : CategorySaveState()
} 