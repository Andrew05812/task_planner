package com.taskplanner.ui.keep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.data.keep.KeepImportFailure
import com.taskplanner.data.keep.KeepImportPreview
import com.taskplanner.data.keep.KeepImportRepository
import com.taskplanner.data.keep.KeepImportResult
import com.taskplanner.data.keep.KeepPreviewRow
import com.taskplanner.data.keep.KeepPreviewRows
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Keep import screen holds: the text in the field, the preview of it, and how the last import ended.
 *
 * @param busy a parse or an import is in flight, so the buttons say so rather than looking broken
 * @param preview the parse of [text] and the plan that came out of it, or null before the reader presses Parse
 * @param rows the tree as the list draws it, with each row labelled by what the plan does with it
 * @param expandedTopics which topic branches are open. The first few come open so the reader sees real hierarchy
 *   without hunting for it (§11), and everything past them stays closed until they ask.
 * @param result how the last confirm ended. A parse that finds a conflict sets it, because that is a refusal the
 *   reader has to see before pressing anything; every other value arrives from the import itself.
 */
data class KeepImportState(
    val text: String = "",
    val busy: Boolean = false,
    val preview: KeepImportPreview? = null,
    val rows: List<KeepPreviewRow> = emptyList(),
    val result: KeepImportResult? = null,
    val expandedTopics: Set<String> = emptySet()
) {
    /** There is something to write, it is allowed to be written, and this preview has not been confirmed yet. */
    val canImport: Boolean
        get() = preview != null && result == null &&
            preview.plan.conflicts.isEmpty() &&
            (preview.plan.groups.isNotEmpty() || preview.plan.tasks.isNotEmpty())

    /**
     * The paste holds lines and every one of them is already here. A parse answers this rather than waiting for a
     * confirm, because the reader is about to look at a screen that says "2 topics, 5 tasks" and has to be told
     * before pressing anything that those seven rows are the seven rows the profile already owns.
     */
    val alreadyImported: Boolean get() = result is KeepImportResult.AlreadyImported

    val topics get() = preview?.document?.stats?.topics ?: 0
    val tasks get() = preview?.document?.stats?.tasks ?: 0
}

/**
 * The Keep import screen's state machine: type, parse, read the preview, confirm.
 *
 * Three rules the class exists to hold, all of them the stage brief's:
 *
 *  * **The parser runs when the reader says so.** `onTextChanged` never parses. A 439-line paste re-read on every
 *    keystroke is a keyboard stutter, and §6 asks for the action to be the reader's.
 *  * **Editing invalidates the preview.** The confirm button is a promise about *this* text, so the moment the
 *    text changes the preview, the rows and the armed button are dropped together. A stale preview that still
 *    confirms is the one way this screen could write something nobody looked at.
 *  * **The screen decides nothing about the data.** It holds the [KeepImportPreview] the repository produced and
 *    hands the text back to it on confirm. The rows on screen come from the plan's own candidates, so what the
 *    reader counts is what the importer writes.
 *
 * No `Context` here, which is also Stage 14's rule: the state carries no sentences, only ids and shapes, so a
 * language switch that recreates this fragment cannot leave the previous language's words on screen.
 */
@HiltViewModel
class KeepImportViewModel @Inject constructor(
    private val importer: KeepImportRepository
) : ViewModel() {

    private val _state = MutableStateFlow(KeepImportState())
    val state: StateFlow<KeepImportState> = _state.asStateFlow()

    fun onTextChanged(text: String) {
        _state.update { it.copy(text = text, preview = null, rows = emptyList(), result = null,
            expandedTopics = emptySet()) }
    }

    fun clear() {
        _state.value = KeepImportState()
    }

    /** Parse what is in the field now, and arm the confirm only if the plan says something may be written. */
    fun parse() {
        val text = _state.value.text
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val preview = importer.preview(text)
            if (preview == null) {
                _state.update {
                    it.copy(
                        busy = false, preview = null, rows = emptyList(), expandedTopics = emptySet(),
                        result = KeepImportResult.NoProfile
                    )
                }
                return@launch
            }
            val opened = preview.plan.candidates.filter { it.isTopic }
                .take(DEFAULT_OPEN_TOPICS)
                .map { it.key }
                .toSet()
            _state.update {
                it.copy(
                    busy = false,
                    preview = preview,
                    expandedTopics = opened,
                    rows = KeepPreviewRows.of(preview.plan, opened),
                    result = refusalOf(preview)
                )
            }
        }
    }

    /** Open or close one topic's branch. The topics themselves stay on screen either way. */
    fun toggleExpanded(key: String) {
        _state.update { current ->
            val preview = current.preview ?: return@update current
            val opened = if (key in current.expandedTopics) current.expandedTopics - key
            else current.expandedTopics + key
            current.copy(expandedTopics = opened, rows = KeepPreviewRows.of(preview.plan, opened))
        }
    }

    fun confirmImport() {
        val current = _state.value
        if (!current.canImport) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val outcome = importer.import(current.text)
            // The preview stays: the reader is looking at the list, and "2 topics imported" beside the list it
            // came from is the sentence that closes the loop. A new parse is what arms the button again.
            _state.update { it.copy(busy = false, result = outcome) }
        }
    }

    /**
     * What the parse already knows will go wrong, before anybody presses a button.
     *
     * Two answers, and they are opposite in kind: a conflict is a refusal the reader has to fix elsewhere, while an
     * all-already-imported paste is a success that simply has nothing left to do. Both are named here rather than
     * left for the confirm to discover, so the screen never arms a button that would write nothing.
     */
    private fun refusalOf(preview: KeepImportPreview): KeepImportResult? {
        val plan = preview.plan
        if (plan.conflicts.isNotEmpty()) {
            return KeepImportResult.Refused(KeepImportFailure.IDENTITY_TAKEN, plan.conflicts)
        }
        if (plan.candidates.isNotEmpty() && plan.groups.isEmpty() && plan.tasks.isEmpty()) {
            return KeepImportResult.AlreadyImported(
                preview.fingerprint, preview.document.stats.topics, preview.document.stats.tasks
            )
        }
        return null
    }

    private companion object {
        /** How many topic branches come open. Enough to show the shape of a notebook, few enough to read. */
        const val DEFAULT_OPEN_TOPICS = 3
    }
}
