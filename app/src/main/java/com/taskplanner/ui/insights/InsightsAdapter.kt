package com.taskplanner.ui.insights

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.taskplanner.R
import com.taskplanner.data.insight.WorkloadRead
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.databinding.ItemInsightBinding
import com.taskplanner.databinding.ItemInsightsEmptyBinding
import com.taskplanner.databinding.ItemWorkloadPanelBinding

/**
 * What the list holds: the panel first, then one row per reading.
 *
 * The panel is a row of the same list rather than a header bolted above it, so the fortnight and the readings
 * taken against it scroll together and cannot be drawn as two accounts of two different windows.
 */
sealed interface InsightsRow {
    data class Panel(val workload: WorkloadRead) : InsightsRow
    data class Card(val insight: Insight) : InsightsRow

    /** The line that says the pass found nothing, or null while there is something on screen already. */
    data class Empty(val textRes: Int) : InsightsRow

    companion object {
        /** The rows one state draws, in the order the reader meets them. */
        fun of(read: WorkloadRead?, insights: List<Insight>, emptyTextRes: Int? = null): List<InsightsRow> =
            buildList {
                // No panel while the window has not been read: a card is a reading of a window, and drawing the
                // readings above a panel that holds nothing would be one screen stating two windows at once.
                read?.let { add(Panel(it)) }
                insights.forEach { add(Card(it)) }
                emptyTextRes?.let { add(Empty(it)) }
            }
    }
}

/**
 * The insight cards.
 *
 * A card says what the rule said, in the words the rule was stored with, and offers two things: the answer the
 * reader gives about the reading, and the way to go and check it. Neither is a decision about the work — §8
 * forbids the app rescheduling or re-prioritising anything on its own, so the card's most action is to open the
 * day, task, goal or habit it counted.
 */
class InsightsAdapter(
    private val onOpen: (InsightTarget) -> Unit,
    private val onAnswer: (Insight, InsightFeedback) -> Unit,
    private val onOpenDay: (Long) -> Unit
) : ListAdapter<InsightsRow, RecyclerView.ViewHolder>(InsightsDiffCallback()) {

    /** The panel, the cards and the empty line arrive as one list, in the order the reader meets them. */
    fun submitRows(read: WorkloadRead?, insights: List<Insight>, emptyTextRes: Int?) =
        submitList(InsightsRow.of(read, insights, emptyTextRes))

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is InsightsRow.Panel -> VIEW_PANEL
        is InsightsRow.Card -> VIEW_CARD
        is InsightsRow.Empty -> VIEW_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_PANEL -> PanelViewHolder(ItemWorkloadPanelBinding.inflate(inflater, parent, false))
            VIEW_EMPTY -> EmptyViewHolder(ItemInsightsEmptyBinding.inflate(inflater, parent, false))
            else -> CardViewHolder(ItemInsightBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is InsightsRow.Panel -> (holder as PanelViewHolder).bind(row)
            is InsightsRow.Card -> (holder as CardViewHolder).bind(row.insight)
            is InsightsRow.Empty -> (holder as EmptyViewHolder).bind(row)
        }
    }

    private class EmptyViewHolder(private val binding: ItemInsightsEmptyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: InsightsRow.Empty) {
            binding.insightsEmpty.setText(row.textRes)
        }
    }

    private inner class PanelViewHolder(private val binding: ItemWorkloadPanelBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: InsightsRow.Panel) = WorkloadPanel.render(binding, row.workload) { onOpenDay(it) }
    }

    inner class CardViewHolder(private val binding: ItemInsightBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(insight: Insight) {
            val context = binding.root.context
            // The three sentences come off the row untouched: they were written by the pass that raised this
            // reading, and reformatting them here would rewrite history in the present tense.
            binding.insightKind.setText(InsightCard.kindLabelRes(insight.kind))
            binding.insightTitle.text = insight.title
            binding.insightBody.text = insight.body
            binding.insightBasis.text = insight.basis
            binding.insightMeta.text = InsightCard.meta(context, insight)

            val target = InsightCard.targetOf(insight)
            binding.insightAction.isVisible = target != null
            InsightCard.actionLabelRes(insight.actionType)?.let(binding.insightAction::setText)
            binding.insightAction.setOnClickListener { target?.let(onOpen) }

            bindAnswers(insight)
        }

        /**
         * The four answers, with the one already given checked.
         *
         * The chips are the reader's sentence about the reading, and the state of the group is drawn from the
         * row rather than from what was tapped: a card whose tick follows the finger rather than the row would
         * stay ticked after an answer the repository would not take. The write and the read are both the
         * repository's, so the answer takes effect in the frame it was given — which is also why nothing here
         * sets a chip locally on tap.
         */
        private fun bindAnswers(insight: Insight) {
            CHIP_FOR_FEEDBACK.forEach { (feedback, id) ->
                val chip = binding.insightAnswers.findViewById<Chip>(id)
                chip.setOnClickListener { onAnswer(insight, feedback) }
            }
            val wanted = insight.feedback?.let { CHIP_FOR_FEEDBACK[it] }
            if (wanted == null) {
                binding.insightAnswers.clearCheck()
            } else {
                binding.insightAnswers.check(wanted)
            }
        }
    }

    private companion object {
        const val VIEW_PANEL = 0
        const val VIEW_CARD = 1
        const val VIEW_EMPTY = 2

        /** Which chip says which answer — the same four the repository stores, in the order they are shown. */
        val CHIP_FOR_FEEDBACK = mapOf(
            InsightFeedback.ACCEPTED to R.id.answerAccepted,
            InsightFeedback.USEFUL to R.id.answerUseful,
            InsightFeedback.HIDDEN to R.id.answerHidden,
            InsightFeedback.REJECTED to R.id.answerRejected
        )
    }
}

private class InsightsDiffCallback : DiffUtil.ItemCallback<InsightsRow>() {

    override fun areItemsTheSame(oldItem: InsightsRow, newItem: InsightsRow): Boolean =
        when {
            oldItem is InsightsRow.Panel && newItem is InsightsRow.Panel -> true
            oldItem is InsightsRow.Card && newItem is InsightsRow.Card ->
                oldItem.insight.uuid == newItem.insight.uuid
            oldItem is InsightsRow.Empty && newItem is InsightsRow.Empty -> true
            else -> false
        }

    // The window is part of what a row is drawn from: a card whose figures moved has to be redrawn even though
    // its own row did not change, which is the point of the panel and the readings being one read.
    override fun areContentsTheSame(oldItem: InsightsRow, newItem: InsightsRow) = oldItem == newItem
}
