package com.taskplanner.ui.keep

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.keep.KeepPreviewRow
import com.taskplanner.data.keep.KeepRowStatus
import com.taskplanner.databinding.ItemKeepPreviewHeaderBinding
import com.taskplanner.databinding.ItemKeepPreviewRowBinding
import com.taskplanner.databinding.ItemKeepStatLineBinding

/** One figure of the statistics block, already resolved into the reader's language. */
data class KeepStatLine(val label: String, val value: String)

/**
 * What the preview list holds: the numbers first, then the tree.
 *
 * The statistics are a row of the same list rather than a panel fixed above it, for the reason §11 gives: the
 * reader is being asked to confirm that "2 topics, 5 tasks" is what their paste holds, and the only way to check
 * that is to count the rows underneath it without the two scrolling apart into different screens.
 */
sealed interface KeepPreviewEntry {
    /**
     * @param warningHeader the counted heading over the warnings, empty when there are none to show
     * @param showEmptyNotice the paste parsed into nothing the import would write — which the reader has to be
     *   told, because an empty tree under a header that says "the tree as it will be written" reads as a bug
     */
    data class Header(
        val stats: List<KeepStatLine>,
        val warningHeader: String,
        val warnings: List<String>,
        val showEmptyNotice: Boolean
    ) : KeepPreviewEntry

    data class Line(val row: KeepPreviewRow) : KeepPreviewEntry
}

/**
 * The Keep preview list: one statistics card, a warnings card when the parser had to decide something the source
 * did not say, and one line per row of the tree.
 *
 * A line indents by its depth and carries three things the reader has to be able to check before importing: what
 * the box on that line said, what the import will do with it, and — for a topic — how many lines it is hiding
 * while closed. None of those is decoration. The export this stage was written against has 56 topics and one of
 * them holds 95 lines, so a tree the reader cannot close is a wall, and a wall is how an unnoticed mistake gets
 * confirmed.
 */
class KeepPreviewAdapter(
    private val indentPerLevel: Int,
    private val onToggle: (String) -> Unit
) : ListAdapter<KeepPreviewEntry, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is KeepPreviewEntry.Header -> VIEW_HEADER
        is KeepPreviewEntry.Line -> VIEW_LINE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_HEADER) {
            HeaderHolder(ItemKeepPreviewHeaderBinding.inflate(inflater, parent, false))
        } else {
            LineHolder(ItemKeepPreviewRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = getItem(position)) {
            is KeepPreviewEntry.Header -> (holder as HeaderHolder).bind(entry)
            is KeepPreviewEntry.Line -> (holder as LineHolder).bind(entry.row)
        }
    }

    private inner class HeaderHolder(private val binding: ItemKeepPreviewHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: KeepPreviewEntry.Header) {
            val inflater = LayoutInflater.from(binding.root.context)
            binding.statsContainer.removeAllViews()
            header.stats.forEach { line ->
                val row = ItemKeepStatLineBinding.inflate(inflater, binding.statsContainer, false)
                row.statLabel.setText(line.label)
                row.statValue.setText(line.value)
                binding.statsContainer.addView(row.root)
            }
            binding.emptyPreviewText.isVisible = header.showEmptyNotice
            binding.warningsCard.isVisible = header.warnings.isNotEmpty()
            binding.warningsHeader.text = header.warningHeader
            binding.warningContainer.removeAllViews()
            header.warnings.forEach { warning ->
                val line = ItemKeepStatLineBinding.inflate(inflater, binding.warningContainer, false)
                // A warning is one sentence with nothing on the right of it, so the figure column goes unused
                // rather than being invented as a second layout for a row of text.
                line.statLabel.text = warning
                line.statLabel.maxLines = 6
                line.statValue.visibility = android.view.View.GONE
                binding.warningContainer.addView(line.root)
            }
        }
    }

    private inner class LineHolder(private val binding: ItemKeepPreviewRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: KeepPreviewRow) {
            val context = binding.root.context
            // The indent is the depth, and only the depth: the source's own whitespace is what decided this row's
            // level, so the shape on screen is the shape the importer writes rather than a second reading of it.
            binding.rowIndent.layoutParams = binding.rowIndent.layoutParams.apply {
                width = row.depth * indentPerLevel
            }
            binding.rowTitle.text = row.title
            binding.rowStateMark.text = when (row.checked) {
                true -> MARK_DONE
                false -> MARK_OPEN
                null -> MARK_NONE
            }
            binding.rowStateMark.contentDescription = context.getString(
                when (row.checked) {
                    true -> R.string.keep_import_state_checked
                    false -> R.string.keep_import_state_open
                    null -> R.string.keep_import_state_unknown
                }
            )
            // Every row states its outcome, including the ordinary one. §11 asks the preview not to hide the
            // decisions it makes, and "no label, so presumably it is new" is a decision left for the reader to
            // guess from an absence.
            binding.rowStatus.setText(
                when (row.status) {
                    KeepRowStatus.WILL_WRITE -> R.string.keep_import_row_new
                    KeepRowStatus.ALREADY_IMPORTED -> R.string.keep_import_row_already
                    KeepRowStatus.CONFLICT -> R.string.keep_import_row_conflict
                }
            )
            binding.rowStatus.isVisible = true
            binding.rowHidden.isVisible = !row.expanded && row.descendants > 0
            if (binding.rowHidden.isVisible) {
                binding.rowHidden.text = context.getString(R.string.keep_import_hidden_rows, row.descendants)
            }

            binding.rowToggle.isVisible = row.isTopic
            if (row.isTopic) {
                binding.rowToggle.setText(
                    if (row.expanded) R.string.keep_import_collapse_topic else R.string.keep_import_expand_topic
                )
                // The button's own label says what it does and the title says which topic, so the pair reads as
                // one sentence under TalkBack without a description invented for each row.
                binding.rowToggle.contentDescription =
                    "${binding.rowTitle.text}, ${binding.rowToggle.text}"
                binding.rowToggle.setOnClickListener { onToggle(row.key) }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<KeepPreviewEntry>() {
        override fun areItemsTheSame(oldItem: KeepPreviewEntry, newItem: KeepPreviewEntry): Boolean =
            when {
                oldItem is KeepPreviewEntry.Header && newItem is KeepPreviewEntry.Header -> true
                oldItem is KeepPreviewEntry.Line && newItem is KeepPreviewEntry.Line ->
                    oldItem.row.key == newItem.row.key
                else -> false
            }

        override fun areContentsTheSame(oldItem: KeepPreviewEntry, newItem: KeepPreviewEntry): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val VIEW_HEADER = 0
        const val VIEW_LINE = 1

        /** Three marks, because the source has three answers: done, not done, and nothing said. */
        const val MARK_DONE = "☑"
        const val MARK_OPEN = "☐"
        const val MARK_NONE = "·"
    }
}
