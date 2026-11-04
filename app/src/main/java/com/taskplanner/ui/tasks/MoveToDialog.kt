package com.taskplanner.ui.tasks

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.taskplanner.R
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy.MoveDestination
import com.taskplanner.data.model.Task
import com.taskplanner.databinding.ItemMoveDestinationBinding

/**
 * §6.3: the "Move to" sheet — the destination list [TaskHierarchyPolicy.destinationsFor] already answers, put on
 * screen, and the confirmation in front of the write it earns.
 *
 * **It owns no rule.** Which places a branch may go to, how deep it would land, how many rows travel with it and
 * which path tells two same-named rows apart are the policy's answers, read once through [destinations] over the
 * snapshot the render publishes; the sheet draws what it is handed and in that order. It filters nothing, sorts
 * nothing, counts nothing and measures no depth of its own, because a second opinion in a view layer is a second
 * door a reader can be invited through and then refused — and §6.3's «не показывай недопустимые варианты как
 * кликабельные» is satisfied by that absence rather than by a greyed-out row. Where the domain has nothing to offer,
 * [HierarchyEditor] draws no button at all, so an empty dialog is a state this file cannot be asked to produce.
 *
 * **Shape: a Material alert dialog with a custom row layout, then a second dialog that confirms.** The house stack
 * is [MaterialAlertDialogBuilder] — eight uses and zero bottom sheets — so the sheet is the app's own dialog with
 * `item_move_destination.xml` rows in a scrollable panel rather than a new dependency in the tree. Two steps because
 * the brief asks for a preview *before* the press: choosing a row opens [confirm], whose message is the whole
 * sentence — the path the task sits on now, the path the press writes, how many tasks travel, the level it lands at
 * and whether there is a topic at all. Only that dialog's own button reaches
 * [TasksViewModel.moveTaskUnder], which runs the repository's one transaction inside `runHierarchyCommand`, so a
 * refusal leaves the screen on storage exactly as an indent or a drag does.
 *
 * **Every sentence is a resource, in both languages.** The arrow between path legs lives in `move_path_format` —
 * the `goal_change_format` precedent — because a path joined in Kotlin is an English word order no translation may
 * change; the travelling count is `move_travelling_count`, a plurals block, never a number with a noun pasted onto
 * it; the topic leg of a destination that has no topic is `move_topic_none`, so "is there a topic" is answered in
 * words rather than by an empty column. The joiners carry only arguments, which is why they read the same in both
 * locales: they are punctuation, not copy. `PluralsContractTest`, `StringFormatContractTest` and
 * `scripts/check-localization.py` are the gates that hold that, and `HierarchyMoveSheetTest` is the gate on what the
 * screen does with it.
 *
 * **Accessibility is a property of the row, not of its parts.** Each row carries one `contentDescription` assembled
 * from those resources — the name, the path, the level, the count and the action — so TalkBack reads a whole choice
 * in one breath; the dialog's title is the heading, and the confirmation repeats the same facts in a message. The
 * three text views inside the row are then taken out of the accessibility tree, which is only honest while the
 * description covers everything they say, and the row itself is focusable and clears `touch_target_min`: a choice a
 * keyboard or a switch-access user cannot reach is not a choice.
 */
object MoveToDialog {

    /**
     * Every place the sheet may put [head]'s branch, straight from the domain.
     *
     * [screen] is [TasksViewModel.hierarchyContext] — the snapshot the render published — and the facts are built
     * from `stored`, the profile's own rows, never from `blocks`: a filter decides what the reader sees, not what
     * their notebook holds, and Task 2's agreement between this list and the repository's transaction is written
     * against the whole profile. A sheet built over a filtered snapshot would inherit a wrong empty answer, and by
     * the rule above that silently takes the whole control away.
     *
     * The topic set is the same snapshot's `topics`, which is what the render drew its headers from and what the
     * repository's `ownsTopic` agrees with: a destination the list wears a name for has to be one the write files
     * under.
     *
     * [facts] is the tree read from `screen.stored`, asked for rather than rebuilt: the editor that opens this sheet has
     * already measured that same tree once for the emission being drawn, and a [TaskHierarchyPolicy.TreeFacts] indexes
     * the whole profile — its uuid map, its levels, its heights and its ambiguity sets — so building it twice inside one
     * emission is the per-question rebuild §14's render benchmark exists to catch. The profile is still what is read;
     * only the ownership of the build moves.
     */
    fun destinations(
        head: Task,
        screen: TasksViewModel.HierarchyScreen,
        facts: TaskHierarchyPolicy.TreeFacts
    ): List<MoveDestination> = TaskHierarchyPolicy.destinationsFor(
        head = head,
        topics = screen.topics,
        profileTopicUuids = screen.topics.map { it.uuid }.toSet(),
        facts = facts
    )

    /**
     * The same answer for a caller that holds no facts yet: they are read here, once, from `screen.stored`.
     *
     * A caller that already measured the tree hands its [TaskHierarchyPolicy.TreeFacts] to the overload above; this
     * one exists so a caller with nothing but the snapshot still builds exactly one, over the profile rather than the
     * screen.
     */
    fun destinations(head: Task, screen: TasksViewModel.HierarchyScreen): List<MoveDestination> =
        destinations(head, screen, TaskHierarchyPolicy.factsOf(screen.stored))

    /**
     * The head's own position, walked through the policy: the topic it is filed under (or the words that say it is
     * filed under nothing), every ancestor above it in the order a reader walks down, then the row itself.
     *
     * The walk is [TaskHierarchyPolicy.missingAncestorsBy] — the same bounded, visited-guarded ancestor chain the
     * list screen uses to keep a filtered child from becoming somebody's root — asked about the one row this sheet
     * holds, which is the question "what above this row would the screen otherwise lose". The sheet asks it because
     * the preview has to name where the task is *now*, and a current path derived from a title or from a place in a
     * sequence is the invention §6.2 forbids on this screen already.
     */
    private fun currentPathLegs(
        context: Context,
        head: Task,
        screen: TasksViewModel.HierarchyScreen
    ): List<String> {
        // The topic is read the way the editor's own topic line reads it, so the two screens cannot name two
        // different topics for one row.
        val topic = screen.topics.firstOrNull { it.uuid == head.groupId }
        val above = TaskHierarchyPolicy.missingAncestorsBy(screen.stored, setOf(head.id))[head.id].orEmpty()
        return listOf(topic?.name ?: context.getString(R.string.move_topic_none)) +
            above.map { it.title } + head.title
    }

    /**
     * The sheet on screen: one row per destination it was handed, in that order, and a press that opens the
     * confirmation rather than writing.
     *
     * Returns the dialog it showed, because the caller is the screen that has to be able to say it showed one.
     */
    fun open(
        context: Context,
        head: Task,
        screen: TasksViewModel.HierarchyScreen,
        destinations: List<MoveDestination>,
        onMove: (MoveDestination) -> Unit
    ): AlertDialog {
        val list = LinearLayout(context).apply {
            id = R.id.moveDestinationList
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.move_to_title)
            .setView(ScrollView(context).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .create()
        destinations.forEach { destination ->
            addRow(context, list, destination) { chosen ->
                // The choice is not the write: the first dialog goes away and the second one asks.
                dialog.dismiss()
                confirm(context, head, screen, chosen, onMove)
            }
        }
        dialog.show()
        return dialog
    }

    /**
     * The second step, whose message is the whole preview.
     *
     * The button is the only thing on this pair of dialogs that reaches the repository, and the sentence above it
     * repeats every fact the row stated, so a reader who confirms has been shown the position the press writes.
     */
    fun confirm(
        context: Context,
        head: Task,
        screen: TasksViewModel.HierarchyScreen,
        destination: MoveDestination,
        onMove: (MoveDestination) -> Unit
    ): AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.move_confirm_title)
        .setMessage(preview(context, head, screen, destination))
        .setPositiveButton(R.string.move_confirm_action) { _, _ -> onMove(destination) }
        .setNegativeButton(R.string.cancel, null)
        .create()
        .apply { show() }

    /** One row of the list: the destination's name, its path, its level and its count, and the sentence under them. */
    private fun addRow(
        context: Context,
        parent: LinearLayout,
        destination: MoveDestination,
        onPicked: (MoveDestination) -> Unit
    ) {
        val row = ItemMoveDestinationBinding.inflate(LayoutInflater.from(context), parent, true)
        val title = titleOf(context, destination)
        val path = pathOf(context, pathLegs(context, destination))
        val details = detailsOf(context, destination)
        row.moveDestinationTitle.text = title
        row.moveDestinationPath.text = path
        row.moveDestinationMeta.text = details
        // One description for the whole choice, so a screen reader says it in one breath and the three lines under
        // it — each marked unimportant in the layout — are not left to be walked a stop at a time.
        row.root.contentDescription = context.getString(
            R.string.move_row_description_format, title, path, details, context.getString(R.string.move_action)
        )
        row.root.setOnClickListener { onPicked(destination) }
    }

    /**
     * The preview the confirmation prints.
     *
     * Four legs out of four resources: where the block sits now, where the press puts it, how many rows travel, and
     * the level it lands at. The count is a plural and the topic answer is a word, not a blank — «есть ли тема» is
     * on the screen as «No topic» when there is none, in both languages.
     */
    private fun preview(
        context: Context,
        head: Task,
        screen: TasksViewModel.HierarchyScreen,
        destination: MoveDestination
    ): String = context.getString(
        R.string.move_confirm_message,
        context.getString(R.string.move_current_path_format, pathOf(context, currentPathLegs(context, head, screen))),
        context.getString(R.string.move_new_path_format, pathOf(context, pathLegs(context, destination))),
        travelling(context, destination),
        context.getString(R.string.move_level_format, destination.resultingLevel)
    )

    /** The row's name: the task the block would hang on, the topic it would join, or the standalone tree. */
    private fun titleOf(context: Context, destination: MoveDestination): String =
        destination.parent?.title ?: destination.topic?.name ?: context.getString(R.string.move_to_standalone)

    /**
     * The legs of one destination's path, topic first.
     *
     * [MoveDestination.pathTitles] already opens with the topic's own name when there is one, and carries nothing
     * when there is not — so the no-topic case is *said*, not left as an empty first column, which is the difference
     * between a row that answers «is there a topic» and a row a reader has to guess about.
     */
    private fun pathLegs(context: Context, destination: MoveDestination): List<String> =
        (if (destination.topic == null) listOf(context.getString(R.string.move_topic_none)) else emptyList()) +
            destination.pathTitles

    /** The legs joined with the arrow the resource carries, never one built in Kotlin. */
    private fun pathOf(context: Context, legs: List<String>): String = when (legs.size) {
        0, 1 -> legs.firstOrNull().orEmpty()
        else -> legs.reduce { left, right -> context.getString(R.string.move_path_format, left, right) }
    }

    /** How many rows the press carries, in the plural that matches that number. */
    private fun travelling(context: Context, destination: MoveDestination): String =
        context.resources.getQuantityString(
            R.plurals.move_travelling_count, destination.travellingCount, destination.travellingCount
        )

    /** The row's third line: the level the block lands at, and the count that travels to it. */
    private fun detailsOf(context: Context, destination: MoveDestination): String = context.getString(
        R.string.move_row_details_format,
        context.getString(R.string.move_level_format, destination.resultingLevel),
        travelling(context, destination)
    )
}
