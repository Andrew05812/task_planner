package com.taskplanner.ui.tasks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy.MoveDestination
import com.taskplanner.databinding.ItemMoveDestinationBinding

/**
 * The §6.3 sheet's destination list: one `item_move_destination.xml` row per [MoveDestination] the policy handed
 * over, bound by the app's own list machinery instead of by a loop that inflates the whole profile.
 *
 * **The adapter draws; it does not decide.** Which places a branch may go to, what level it lands at, how many rows
 * travel with it and which path tells two same-named rows apart are [TaskHierarchyPolicy.destinationsFor]'s answers,
 * already resolved into the [MoveDestination] it returns; the three lines a row prints and the one sentence it speaks
 * are assembled by [MoveToDialog] out of resources, so the sheet's copy has exactly one author. What is left here is
 * the view-holder work the brief puts on a list: inflate once, bind the three texts, name the row for a screen
 * reader, and hand the pressed destination back — the write itself belongs to the confirmation behind it, and to
 * [HierarchyEditor]'s call into [TasksViewModel.moveTaskUnder].
 *
 * **Why a `ListAdapter` rather than the loop this replaces.** A destination list is as long as the profile is wide:
 * on the §14 10k notebook the old code built one four-view row per stored destination on the main thread before the
 * dialog had even been shown. `RecyclerView` binds what fits the sheet and recycles the rest, and the diff below is
 * what makes *changing* that list cheap as well: the editor re-measures the destinations on every snapshot it
 * publishes, so the moment a sheet is kept open across one of those emissions, a list that grew by a row has to
 * rebind a row rather than the whole panel. That moment has not arrived in this file — [MoveToDialog.open] builds one
 * adapter per press and submits into it once, so the differ's first-insert fast path is what runs and no re-submit
 * happens yet. The callback is written out rather than left implied, because a `ListAdapter` without a real
 * `ItemCallback` is a list that can be drawn but never updated: the diff is the part of this shape that can grow with
 * the screen, and its two answers are pinned by tests rather than by that promise. That is also the shape
 * every other list in this app already uses (`ui/tasks/TasksAdapter`, the Stage 15 keep preview), which is the house
 * pattern this sheet was the last screen to skip.
 */
class MoveDestinationAdapter(
    private val onPicked: (MoveDestination) -> Unit
) : ListAdapter<MoveDestination, MoveDestinationAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMoveDestinationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    /** One destination: its name, the path that tells it apart, the level and count it promises, and its press. */
    inner class Holder(private val binding: ItemMoveDestinationBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(destination: MoveDestination) {
            val context = binding.root.context
            val title = MoveToDialog.titleOf(context, destination)
            val path = MoveToDialog.pathOf(context, MoveToDialog.pathLegs(context, destination))
            val details = MoveToDialog.detailsOf(context, destination)
            binding.moveDestinationTitle.text = title
            binding.moveDestinationPath.text = path
            binding.moveDestinationMeta.text = details
            // One description for the whole choice, so a screen reader says it in one breath and the three lines under
            // it — each marked unimportant in the layout — are not left to be walked a stop at a time.
            binding.root.contentDescription = MoveToDialog.rowDescription(context, title, path, details)
            binding.root.setOnClickListener { onPicked(destination) }
        }
    }

    companion object {

        /**
         * Two destinations are the same *place* when they name the same topic and the same row above the block.
         *
         * The identity is the pair of uuids rather than the [MoveDestination] value because the same place can be
         * measured twice against two different reads of the tree and come back as two equal-but-distinct `Task`
         * objects; the data-class equality below is then the content answer, which is what says a row has to be
         * rebound because the level it promises or the count it carries changed rather than because the sheet was
         * rebuilt. `(null, null)` — the unfiled tree's own root — is a place like any other, so both halves are
         * compared as nullable uuids and one `null` never matches a topic.
         *
         * Two destinations on one pair cannot reach here: `TaskHierarchyPolicy.destinationsFor` offers each
         * (topic, parent) at most once, and refuses an ambiguous parent's identity before it is offered at all.
         * `internal` because `HierarchyMoveSheetTest` asks these two questions of the callback the list runs on,
         * rather than of a copy of them.
         */
        internal val DIFF = object : DiffUtil.ItemCallback<MoveDestination>() {
            override fun areItemsTheSame(oldItem: MoveDestination, newItem: MoveDestination): Boolean =
                oldItem.topic?.uuid == newItem.topic?.uuid && oldItem.parent?.uuid == newItem.parent?.uuid

            override fun areContentsTheSame(oldItem: MoveDestination, newItem: MoveDestination): Boolean =
                oldItem == newItem
        }
    }
}
