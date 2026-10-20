package com.taskplanner.ui.goals

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.repository.GoalWithProgress
import com.taskplanner.databinding.ItemGoalBinding

/**
 * The goal cards.
 *
 * A row is a way to the goal it names, and pressing it is the only action the list offers: archiving and
 * deleting belong to the screen that can also show what the goal has reached and what its target used to be,
 * rather than to a list item that would have to explain them from a dialog.
 */
class GoalsAdapter(
    private val onGoalClick: (GoalWithProgress) -> Unit
) : ListAdapter<GoalWithProgress, GoalsAdapter.GoalViewHolder>(GoalDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GoalViewHolder = GoalViewHolder(
        ItemGoalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: GoalViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class GoalViewHolder(private val binding: ItemGoalBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GoalWithProgress) {
            val context = binding.root.context
            val progress = item.progress
            binding.goalTitle.text = item.goal.title
            binding.goalType.setText(GoalCard.typeLabelRes(progress.goal.type))
            binding.goalFigure.text = GoalCard.figure(context, progress)

            val stateRes = GoalCard.stateLabelRes(progress)
            binding.goalState.isVisible = stateRes != null
            stateRes?.let { label ->
                binding.goalState.setText(label)
                binding.goalState.setTextColor(ContextCompat.getColor(context, stateColorRes(label)))
            }

            // The bar is the same two numbers the line above it prints, split into what is reached and what
            // is left. A goal with no figure has no bar at all rather than an empty one: a track with
            // nothing on it reads as "started and stopped", which is not what "no figure yet" means.
            val fraction = GoalCard.fraction(progress)
            binding.goalBar.isVisible = fraction != null
            if (fraction != null) {
                binding.goalBar.setSegments(
                    listOf(progress.counted.coerceAtLeast(0L), (progress.target - progress.counted).coerceAtLeast(0L)),
                    intArrayOf(
                        ContextCompat.getColor(context, R.color.chart_bar),
                        ContextCompat.getColor(context, R.color.chart_track)
                    )
                )
            }

            val meta = GoalCard.meta(context, progress)
            binding.goalMeta.isVisible = meta != null
            binding.goalMeta.text = meta

            val missing = GoalCard.missingNote(context, progress)
            binding.goalMissing.isVisible = missing != null
            binding.goalMissing.text = missing

            binding.root.setOnClickListener { onGoalClick(item) }
        }

        /**
         * The colour the badge is worn in.
         *
         * The three states are a success, a failure and an absence, and the app already has a colour for
         * each of those three ideas from the task list and the dashboard. Borrowing them is the point: a
         * reader who has learned that red means a date has passed should not have to learn it again here.
         */
        @StringRes
        private fun stateColorRes(labelRes: Int): Int = when (labelRes) {
            R.string.goal_state_reached -> R.color.status_done
            R.string.goal_state_late -> R.color.health_overdue
            else -> R.color.status_todo
        }
    }
}

private class GoalDiffCallback : DiffUtil.ItemCallback<GoalWithProgress>() {
    override fun areItemsTheSame(oldItem: GoalWithProgress, newItem: GoalWithProgress) =
        oldItem.goal.uuid == newItem.goal.uuid

    // The figure is part of what a card is drawn from: a goal whose count moved has to be redrawn even
    // though its row did not change, which is the point of deriving progress instead of storing it.
    override fun areContentsTheSame(oldItem: GoalWithProgress, newItem: GoalWithProgress) =
        oldItem == newItem
}
