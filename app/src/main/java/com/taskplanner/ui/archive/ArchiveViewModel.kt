package com.taskplanner.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.hierarchy.TaskDeleteCoordinator
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArchiveState(
    val items: List<ArchivedTask> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val categoryFilter: Long? = null
) {
    data class ArchivedTask(val task: Task, val category: Category?)
}

@HiltViewModel
class ArchiveViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    categoryRepository: CategoryRepository,
    private val deleteCoordinator: TaskDeleteCoordinator
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<Long?>(null)

    private val _state = MutableStateFlow(ArchiveState())
    val state: StateFlow<ArchiveState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                taskRepository.observeArchivedTasks(),
                categoryRepository.getAllCategories(),
                query,
                category
            ) { tasks, categories, q, catId ->
                val categoryMap = categories.associateBy { it.id }
                var visible = tasks
                if (catId != null) {
                    visible = visible.filter { it.categoryId == catId }
                }
                if (q.isNotBlank()) {
                    val needle = q.trim().lowercase()
                    visible = visible.filter {
                        it.title.lowercase().contains(needle) ||
                            it.description.lowercase().contains(needle)
                    }
                }
                visible.map { ArchivedItem(it, it.categoryId?.let { id -> categoryMap[id] }) }
            }.collect { items ->
                _state.value = ArchiveState(
                    items = items.map { ArchiveState.ArchivedTask(it.task, it.category) },
                    isLoading = false,
                    searchQuery = query.value,
                    categoryFilter = category.value
                )
            }
        }
    }

    private data class ArchivedItem(val task: Task, val category: Category?)

    fun setSearch(query: String) {
        this.query.value = query
    }

    fun setCategoryFilter(categoryId: Long?) {
        this.category.value = categoryId
    }

    fun restore(task: Task) {
        viewModelScope.launch {
            try {
                taskRepository.restoreTask(task.id)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    /**
     * The archive's "delete forever", through the same domain operation every other delete uses.
     *
     * It used to call [TaskRepository.hardDeleteTasks] directly, which removes rows and says nothing to the tree: a
     * row archived with a live child under it left that child naming a row that was no longer in the database, and the
     * task list reads a missing parent as no parent, so the child lost a level in silence. The coordinator resolves
     * the branch first — the children go to what held their parent, and the block is re-numbered — and then takes the
     * rows that are already retired.
     *
     * What this screen still cannot say is *why* it would refuse one of these: a refusal surfaces as the row staying
     * on the list, because [ArchiveState.error] carries a raw string rather than a resource id and §14's rule is that
     * a sentence the reader reads lives in resources. An archived row whose timer is still running is the case, and
     * `completeAndArchive` stops the timer on the way in, so it is a state the app does not build. Recorded as an open
     * finding in Stage 16 §5's report rather than fixed with a hardcoded word.
     */
    fun deleteForever(task: Task) {
        viewModelScope.launch {
            try {
                deleteCoordinator.hardDelete(listOf(task.id))
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
