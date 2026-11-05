package com.taskplanner.ui.archive

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyDeleteRefusal
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArchiveState(
    val items: List<ArchivedTask> = emptyList(),
    val isLoading: Boolean = true,
    /**
     * A command that threw. The exception's own sentence, which is why it stays a `String`.
     *
     * Stage 14 §4 counted this screen among the three where a machine's words can still reach a Snackbar and wrote that
     * the fix — a resource for the reader, the log for the cause — belongs to the error-handling stage. §5 has no
     * business doing that stage's work quietly, so the field is left as it was and the sentence the app *does* own, its
     * refusal, goes beside it in [refusalRes].
     */
    val error: String? = null,
    /**
     * The domain's refusal to delete the row the reader pointed at, as a resource id — §14's rule that a sentence the
     * reader reads lives in resources.
     *
     * It is here because [deleteForever] used to answer a refusal by doing nothing at all: the outcome came back, and
     * the screen dropped it. `ArchiveDeleteRefusalTest` measured that (`expected:<2131886308> but was:<null>`) before
     * this field existed, and the row staying in the archive is exactly what a reader cannot explain to themselves.
     */
    @StringRes val refusalRes: Int? = null,
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
                // Updated, not rebuilt. A whole new `ArchiveState` here resets the fields the collector does not know
                // about — which used to be only [ArchiveState.error], and since §5 is `refusalRes` too — so a refusal
                // that had not reached the reader yet could be wiped by the next invalidation of the table or a filter
                // change. `ArchiveDeleteRefusalTest.a_refusal_outlives_the_next_reading_of_the_archive_s_own_list`
                // measured the wiping (`expected:<2131886308> but was:<null>` after a search moved) before this copy.
                _state.update {
                    it.copy(
                        items = items.map { item -> ArchiveState.ArchivedTask(item.task, item.category) },
                        isLoading = false,
                        searchQuery = query.value,
                        categoryFilter = category.value
                    )
                }
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
     * **The refusal is reachable, and this is where that comes from.** `completeAndArchive` stops the timer on the way
     * in, so this device cannot *archive* a row it is measuring; what can put one here is a pull. `SyncAppliers` writes
     * `archived = remote.archived` into the local row (`SyncAppliers.applyRemoteTask`) and never touches this device's
     * `TrackingSession` rows, so a task started here and archived on another phone arrives in the archive still
     * running — and `TaskHierarchyRepository.deleteTask` refuses a row that is being measured. A first version of this
     * method called that state "one the app does not build", which was wrong in exactly the way that matters: the
     * reader taps, nothing happens, and the screen has no word for it. `ArchiveDeleteRefusalTest` drives that pair.
     *
     * So [ArchiveState.refusalRes] carries which refusal it was, in the reader's language, and the rows it names stay
     * exactly where they were. What the screen still cannot do is say *how* to unblock the row beyond §4's sentence,
     * and the exception path beside this one still shows a machine's own words — that half is named in
     * [ArchiveState.error] and belongs to the error-handling stage, as Stage 14 §4 wrote.
     */
    fun deleteForever(task: Task) {
        viewModelScope.launch {
            try {
                val outcome = deleteCoordinator.hardDelete(listOf(task.id))
                outcome.refusal?.let { reason ->
                    _state.update {
                        it.copy(
                            refusalRes = when (reason) {
                                HierarchyDeleteRefusal.MEASUREMENT_RUNNING -> R.string.delete_blocked_running
                                HierarchyDeleteRefusal.ROW_MISSING,
                                HierarchyDeleteRefusal.NO_PROFILE,
                                HierarchyDeleteRefusal.AMBIGUOUS_IDENTITY -> R.string.delete_row_missing
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    /** The screen has shown the machine's sentence; only that channel goes back to nothing. */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * The screen has shown the refusal; only that channel goes back to nothing.
     *
     * Clearing both, as an earlier draft of this method did, makes the precedence in `ArchiveFragment` a message
     * silencer: a refusal shown while an exception sentence was still waiting would retire the exception unsaid. The
     * two channels are independent writes from independent commands, so the screen dismisses the one it read.
     */
    fun clearRefusal() {
        _state.value = _state.value.copy(refusalRes = null)
    }
}
