package com.taskplanner.ui.keep

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.taskplanner.R
import com.taskplanner.data.keep.KeepImportFailure
import com.taskplanner.data.keep.KeepImportResult
import com.taskplanner.data.keep.KeepParserIssue
import com.taskplanner.data.keep.KeepPreviewRow
import com.taskplanner.data.keep.KeepRowStatus
import com.taskplanner.databinding.FragmentKeepImportBinding
import com.taskplanner.ui.settings.AppLocale
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * The Google Keep import screen: paste, preview, confirm.
 *
 * The screen owns no parsing and no writing decisions — [KeepImportViewModel] holds the parse and the plan, and the
 * importer is the only thing that touches Room. What belongs here is the ordering of the reader's own steps and the
 * language they are spoken in:
 *
 *  * **Two phases, never both.** The field and the preview swap places rather than stacking, because a paste of
 *    439 lines and a tree of 382 rows in one scroll container would build every view of both at once — and because
 *    the preview has to be *looked at*, not scrolled past on the way to a button.
 *  * **Every sentence is a resource.** Stage 14's rule, and the reason the result is assembled from two plural
 *    forms and a joiner that is itself a string: «импортировано 2 темы **и** добавлено 5 задач» and
 *    “2 topics imported**, **5 tasks added” are the same code path with different glue, and the glue is not the
 *    screen's to decide.
 *  * **A failure's own text never reaches the reader.** [KeepImportResult.Failed.detail] is the exception chain —
 *    useful in a log, meaningless in a language the reader speaks, and it carries no note titles either. It goes to
 *    `Log.w`; the sentence stays the localized one.
 */
@AndroidEntryPoint
class KeepImportFragment : Fragment() {

    private var _binding: FragmentKeepImportBinding? = null
    private val binding get() = _binding!!
    private val viewModel: KeepImportViewModel by viewModels()
    private lateinit var adapter: KeepPreviewAdapter

    /** True while the screen writes the field from the state, so the watcher does not report it back as typing. */
    private var syncingField = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeepImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupField()
        setupList()
        setupButtons()
        observeState()
    }

    override fun onDestroyView() {
        // The watcher holds the field and the field holds this view: a rotation that left either attached would
        // report the next fragment's text as typing into this one's state, and the list would keep drawing rows
        // into views that no longer exist.
        binding.pasteInput.removeTextChangedListener(watcher)
        binding.previewList.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupField() {
        binding.pasteInput.addTextChangedListener(watcher)
    }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (!syncingField) viewModel.onTextChanged(s?.toString().orEmpty())
        }
    }

    private fun setupList() {
        adapter = KeepPreviewAdapter(
            indentPerLevel = resources.getDimensionPixelSize(R.dimen.keep_preview_indent),
            onToggle = { key -> viewModel.toggleExpanded(key) }
        )
        binding.previewList.layoutManager = LinearLayoutManager(requireContext())
        binding.previewList.adapter = adapter
    }

    private fun setupButtons() {
        binding.parseButton.setOnClickListener { viewModel.parse() }
        binding.clearButton.setOnClickListener { viewModel.clear() }
        binding.confirmButton.setOnClickListener { viewModel.confirmImport() }
        // "Back to the text" does not discard anything: the field keeps what the reader pasted and the preview is
        // what goes away, so the next step is theirs rather than a re-paste of a notebook.
        binding.backToTextButton.setOnClickListener { viewModel.onTextChanged(viewModel.state.value.text) }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: KeepImportState) {
        val showingPreview = state.preview != null
        binding.pasteScroller.isVisible = !showingPreview
        binding.previewList.isVisible = showingPreview
        binding.confirmBar.isVisible = showingPreview

        syncingField = true
        if (binding.pasteInput.text?.toString() != state.text) binding.pasteInput.setText(state.text)
        syncingField = false

        binding.parseButton.isEnabled = !state.busy && state.text.isNotBlank()
        binding.clearButton.isEnabled = !state.busy && state.text.isNotEmpty()
        binding.pasteProgress.isVisible = state.busy && !showingPreview
        binding.confirmProgress.isVisible = state.busy && showingPreview

        val message = messageFor(state)
        binding.pasteMessage.isVisible = message != null && !showingPreview
        binding.confirmMessage.isVisible = message != null && showingPreview
        if (message != null) {
            binding.pasteMessage.text = message
            binding.confirmMessage.text = message
        }
        binding.confirmButton.isVisible = state.canImport && !state.busy

        if (showingPreview) {
            val preview = state.preview!!
            val rows = state.rows
            adapter.submitList(
                buildList<KeepPreviewEntry> {
                    add(
                        KeepPreviewEntry.Header(
                            stats = statsOf(preview, rows),
                            warningHeader = resources.getQuantityString(
                                R.plurals.keep_import_warning_count,
                                preview.document.issues.size,
                                preview.document.issues.size
                            ),
                            warnings = preview.document.issues.map(::issueText),
                            showEmptyNotice = rows.isEmpty() ||
                                rows.none { it.status == KeepRowStatus.WILL_WRITE }
                        )
                    )
                    rows.forEach { add(KeepPreviewEntry.Line(it)) }
                }
            )
        } else {
            adapter.submitList(emptyList())
        }
    }

    /**
     * The figures the reader confirms against. The first ten describe the paste as [com.taskplanner.data.keep.
     * KeepTextParser] read it; the last two describe the rows this profile does or does not already own, which is
     * the difference §11 asks the preview to make visible rather than leave for the import to reveal.
     */
    private fun statsOf(preview: com.taskplanner.data.keep.KeepImportPreview, rows: List<KeepPreviewRow>) =
        preview.document.stats.let { stats ->
            listOf(
                line(R.string.keep_import_stat_topics, stats.topics),
                line(R.string.keep_import_stat_tasks, stats.tasks),
                line(R.string.keep_import_stat_completed, stats.checked),
                line(R.string.keep_import_stat_open, stats.unchecked),
                line(R.string.keep_import_stat_nested, preview.plan.stats.nestedTasks),
                line(R.string.keep_import_stat_no_box, stats.unknownStatus),
                line(R.string.keep_import_stat_separators, stats.separatorLines),
                line(R.string.keep_import_stat_blanks, stats.blankLines),
                line(R.string.keep_import_stat_duplicates, stats.duplicateTitles),
                // The depth is a counted phrase rather than a number, because "4 levels" and "1 level" are the
                // same fact in two grammars and the screen has no business deciding which noun a count takes.
                KeepStatLine(
                    label = getString(R.string.keep_import_stat_depth),
                    value = resources.getQuantityString(
                        R.plurals.keep_import_depth_levels, stats.maxDepth, stats.maxDepth
                    )
                ),
                line(R.string.keep_import_stat_new, rows.count { it.status == KeepRowStatus.WILL_WRITE }),
                line(R.string.keep_import_stat_already, rows.count { it.status == KeepRowStatus.ALREADY_IMPORTED })
            )
        }

    private fun line(labelRes: Int, value: Int) = KeepStatLine(
        label = getString(labelRes),
        // The device's own grouping of digits, the same way the dashboard and the report print a figure: a reader
        // who reads «1 000» should not meet "1000" in their own app.
        value = NumberFormat.getIntegerInstance(AppLocale.current()).format(value)
    )

    private fun issueText(issue: KeepParserIssue) = when (issue) {
        is KeepParserIssue.NoCheckbox -> getString(R.string.keep_import_issue_no_box, issue.line)
        is KeepParserIssue.NoParent -> getString(R.string.keep_import_issue_no_parent, issue.line)
        is KeepParserIssue.IndentSkipped -> getString(R.string.keep_import_issue_indent, issue.line)
        is KeepParserIssue.TooDeep -> getString(R.string.keep_import_issue_deep, issue.line)
    }

    /**
     * The one sentence the screen says about where things stand, or null while it has nothing to say.
     *
     * A successful import is stated as two counted phrases joined by a resource rather than one string with two
     * numbers in it, because the two nouns decline independently in the languages this app speaks — and the
     * joiner (« и » / “, ”) is punctuation the language owns, not the fragment.
     */
    private fun messageFor(state: KeepImportState): String? = when (val result = state.result) {
        // Nothing has been confirmed and nothing has been refused: the screen has nothing to say about the outcome,
    // and the preview itself already shows which rows are already here.
    null -> null
        KeepImportResult.NoProfile -> getString(R.string.keep_import_result_no_profile)
        KeepImportResult.NothingToImport -> getString(R.string.keep_import_result_nothing)
        is KeepImportResult.AlreadyImported -> getString(R.string.keep_import_result_already)
        // state.alreadyImported is the same answer read off the result, kept on the state so the ViewModel can be
        // asked the question without the fragment holding the outcome type.
        is KeepImportResult.Refused -> resources.getQuantityString(
            R.plurals.keep_import_conflict_count, result.conflicts.size, result.conflicts.size
        )
        is KeepImportResult.Imported -> listOf(
            resources.getQuantityString(
                R.plurals.keep_import_result_topics, result.topics, result.topics
            ),
            resources.getQuantityString(R.plurals.keep_import_result_tasks, result.tasks, result.tasks)
        ).joinToString(getString(R.string.keep_import_result_joiner))
        is KeepImportResult.Failed -> {
            Log.w(TAG, "keep import failed: ${result.detail}")
            getString(
                when (result.failure) {
                    KeepImportFailure.SAFETY_COPY_UNAVAILABLE -> R.string.keep_import_error_copy
                    KeepImportFailure.WRITE_FAILED -> R.string.keep_import_error_write
                    KeepImportFailure.TREE_REFUSED -> R.string.keep_import_error_tree
                    KeepImportFailure.IDENTITY_TAKEN -> R.string.keep_import_error_tree
                }
            )
        }
    }

    private companion object {
        const val TAG = "KeepImport"
    }
}
