package com.taskplanner.ui.habits

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.repository.HabitWithFigures
import com.taskplanner.databinding.ItemHabitBinding

/**
 * The habit cards.
 *
 * A row is a way to the habit it names, and pressing it is the only action the list offers: keeping a day,
 * correcting one and changing the schedule all belong to the screen that can also show the grid and the
 * record of corrections, rather than to a card that would have to explain which week its percentage counted.
 */
class HabitsAdapter(
    private val onHabitClick: (HabitWithFigures) -> Unit
) : ListAdapter<HabitWithFigures, HabitsAdapter.HabitViewHolder>(HabitDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HabitViewHolder = HabitViewHolder(
        ItemHabitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: HabitViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class HabitViewHolder(private val binding: ItemHabitBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HabitWithFigures) {
            val context = binding.root.context
            val habit = item.habit
            val figures = item.figures
            binding.habitTitle.text = habit.title
            binding.habitType.setText(HabitCard.frequencyLabelRes(habit.frequency))

            val schedule = HabitCard.scheduleText(context, habit)
            binding.habitSchedule.isVisible = schedule != null
            binding.habitSchedule.text = schedule

            binding.habitFigure.text = HabitCard.streakText(context, figures)
            val week = HabitCard.weekText(context, figures)
            binding.habitWeek.isVisible = week != null
            binding.habitWeek.text = week

            // The bar is the share the caption prints, split into kept and not kept rather than measured
            // again. A window that asked for nothing has no bar at all: an empty track would read as a habit
            // the user abandoned rather than as a schedule that has not begun to ask.
            val share = HabitCard.share(figures)
            binding.habitBar.isVisible = share != null
            share?.let { (kept, owed) ->
                binding.habitBar.setSegments(
                    listOf(kept.toLong(), owed.toLong()),
                    intArrayOf(
                        ContextCompat.getColor(context, R.color.chart_bar),
                        ContextCompat.getColor(context, R.color.chart_track)
                    )
                )
            }

            val kept = HabitCard.keptText(context, figures)
            binding.habitKept.isVisible = kept != null
            kept?.let {
                binding.habitKept.text = context.getString(
                    R.string.habit_kept_line_format,
                    it,
                    HabitCard.keptWindow(context, figures)
                )
            }

            val badge = HabitCard.stateBadgeRes(
                owedToday = HabitCard.isOwedOn(habit, figures.today, figures.markedToday),
                markedToday = figures.markedToday
            )
            binding.habitState.isVisible = badge != null
            badge?.let { label ->
                binding.habitState.setText(label)
                binding.habitState.setTextColor(
                    ContextCompat.getColor(context, HabitCard.stateColorRes(label))
                )
            }

            val meta = HabitCard.meta(context, habit)
            binding.habitMeta.isVisible = meta != null
            binding.habitMeta.text = meta

            binding.root.setOnClickListener { onHabitClick(item) }
        }
    }
}

private class HabitDiffCallback : DiffUtil.ItemCallback<HabitWithFigures>() {
    override fun areItemsTheSame(oldItem: HabitWithFigures, newItem: HabitWithFigures) =
        oldItem.habit.uuid == newItem.habit.uuid

    // The figures are part of what a card is drawn from: a day kept has to be redrawn even though the habit's
    // row did not change, which is the point of deriving the series instead of storing it.
    override fun areContentsTheSame(oldItem: HabitWithFigures, newItem: HabitWithFigures) =
        oldItem == newItem
}
