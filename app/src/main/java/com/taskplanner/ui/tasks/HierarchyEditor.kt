package com.taskplanner.ui.tasks

import android.content.Context
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.Task
import com.taskplanner.databinding.FragmentTaskDetailBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * §6.2: the hierarchy block of the task editor — the three facts about where this task sits, and the moves the
 * reader can actually make from here.
 *
 * **Everything it prints comes out of the tree.** The three values are read from [TasksViewModel.hierarchyContext],
 * the snapshot the list's own render publishes: the topic is the group the headers were drawn from, the parent is the
 * row `tasks.parent_uuid` names inside the profile's stored rows, and the level is [TaskHierarchyPolicy.levelOf]'s
 * number. Nothing here derives a parent from a title or from a place in a sequence — which is what the brief forbids
 * («не выводить родителя из названия или порядка строки»), and what a reader would experience as the app inventing a
 * tree they did not write.
 *
 * **The rule is not re-implemented here either.** What an Indent or an Outdent press would do is [HierarchyMenu]'s
 * answer, asked from the candidate [TaskHierarchyPolicy.previousSibling] names and the facts this class already
 * built — the same offer the card's menu builds and the same two policy functions that answer under
 * [com.taskplanner.data.repository.TaskHierarchyRepository]'s transaction; every refusal is titled by
 * [HierarchyMenu.indentLabel] and [HierarchyMenu.outdentLabel], the list's own sentences, and an action the app has
 * no sentence for is hidden by the same [HierarchyMenu.isSilent] the list hides it with. A second opinion in the
 * editor is how a button ends up offering what the domain then refuses, and the reader who presses it has learned
 * that the tree is not safe here.
 *
 * **§6.3's third verb is the domain's list, not this screen's opinion.** Where the block could go is
 * [TaskHierarchyPolicy.destinationsFor], read over the published snapshot's whole stored profile through
 * [MoveToDialog.destinations] — with the [TaskHierarchyPolicy.TreeFacts] this emission already built, so one snapshot
 * costs one tree rather than one per question. The editor keeps that list as the answer the button was drawn from and
 * hands it to the sheet when the button is pressed. An empty answer draws no button at all — the same rule
 * §6.1 applies to a row whose moves are unexplainable, and for the same reason: a control that can only ever open a
 * list of nothing, or die on press, is a control the reader is right to stop trusting.
 *
 * **One copy is the editor's own, on purpose.** An offered Indent names the task it would hang the row on
 * (`hierarchy_indent_action`) rather than borrowing `row_indent`, whose «the row above» is a fact about the block the
 * Tasks list draws: this screen shows no rows, and the stored sibling the press actually writes is not always the
 * task the list would have named. A refusal needs no such split — the domain's reason does not care which screen
 * read it out — and so only the refusal sentences are shared verbatim.
 *
 * **It owns seven views and nothing else.** The topic, parent and level lines, the three buttons and the block that
 * holds them: those are the only views this class touches, which is what lets a hierarchy press leave the title the
 * reader is typing alone (rule 6 of §6.2). The commands go to [TasksViewModel.indentTask], [TasksViewModel.outdentTask]
 * and [TasksViewModel.moveTaskUnder] — the list's own commands, into the repository's one transaction — and the
 * block redraws itself from the snapshot the write re-emits, never from a guess made before it.
 */
class HierarchyEditor(
    private val context: Context,
    private val viewModel: TasksViewModel,
    binding: FragmentTaskDetailBinding
) {

    private val section: View = binding.hierarchySection
    private val topicRow = binding.hierarchyTopic
    private val parentRow = binding.hierarchyParent
    private val levelRow = binding.hierarchyLevel
    private val indentButton = binding.hierarchyIndentButton
    private val outdentButton = binding.hierarchyOutdentButton
    private val moveButton = binding.hierarchyMoveButton

    /**
     * The task on screen as the editor was told it, and as the published snapshot now holds it.
     *
     * [held] is what the fragment has — the row it loaded, or nothing before it arrives. [shown] is the same task
     * read back out of the tree, and it is the one the labels and the commands use: after a move the copy in hand is
     * the stale one, and a block that redrew itself from that would show the reader the place they had just left.
     */
    private var held: Task? = null
    private var shown: Task? = null

    /** The offer the last measurement produced, kept so a press names the row the policy chose rather than a new guess. */
    private var offer: HierarchyMenu.Offer? = null

    /**
     * The places the last snapshot could put this row, as the domain answered them.
     *
     * Kept for the same reason as [offer]: the sheet the button opens has to draw the list this screen was drawn
     * from, so the row a reader presses and the control they saw are one measurement. The list is the policy's
     * answer over `stored` — never over the blocks on screen — because that is what the repository's transaction is
     * validated against, and an empty answer is what takes the button off the screen.
     */
    private var destinations: List<TaskHierarchyPolicy.MoveDestination> = emptyList()

    private var following: Job? = null

    init {
        // `View#performClick` reaches a listener even when the button is disabled, and a keyboard or a switch-access
        // user can reach it that way too — so the enabled state is not the guard. The same answer as the menu's own
        // click handler: an action the offer refuses is not fired at the repository.
        indentButton.setOnClickListener {
            val head = shown ?: return@setOnClickListener
            val above = offer?.indentCandidate
            if (offer?.canIndent == true && above != null) viewModel.indentTask(head.id, above.id)
        }
        outdentButton.setOnClickListener {
            val head = shown ?: return@setOnClickListener
            if (offer?.canOutdent == true) viewModel.outdentTask(head.id)
        }
        // The door and the sheet are the same answer: a row with nothing admissible has no button, and a press that
        // reaches this handler anyway — through the listener, not through the screen — opens no dialog.
        moveButton.setOnClickListener {
            val head = shown ?: return@setOnClickListener
            val screen = viewModel.hierarchyContext ?: return@setOnClickListener
            val offered = destinations
            if (offered.isEmpty()) return@setOnClickListener
            MoveToDialog.open(context, head, screen, offered) { destination ->
                viewModel.moveTaskUnder(head.id, destination.parent?.id, destination.topic?.uuid)
            }
        }
    }

    /**
     * Redraws from every snapshot the screen publishes.
     *
     * The editor's view model is the same class the list uses and its render republishes [hierarchyContext] on every
     * emission, so collecting the state is the one signal that exists without adding a flow or a second hierarchy
     * field — which §6.2 forbids as loudly as it requires the refresh. A write, a refusal that redraws the stored
     * order, a Room emission and a recreation all arrive through here, and none of them is carried by anything the
     * screen remembers.
     */
    fun follow(scope: CoroutineScope) {
        following?.cancel()
        following = scope.launch {
            viewModel.state.collect { refresh() }
        }
    }

    /** The task the editor is holding, or null until it arrives. An unsaved row hides the whole block. */
    fun bind(row: Task?) {
        held = row
        refresh()
    }

    /** Recomputes the three values and both actions from the snapshot the render last published. */
    fun refresh() {
        val wanted = held?.takeIf { it.id > 0L }
        val screen = viewModel.hierarchyContext
        val current = if (wanted == null || screen == null) null else screen.stored.firstOrNull { it.id == wanted.id }
        if (current == null || screen == null) {
            // No task yet, no task row yet, or a task the published tree does not hold: in every case the honest
            // answer is no block at all, because both actions name an identity and this screen has nothing to name.
            // `held` stays as the fragment left it, so the next emission finds the row again by itself.
            shown = null
            offer = null
            destinations = emptyList()
            section.visibility = View.GONE
            return
        }

        // The one tree this emission reads: the menu's two offers, the destination list, the level line and the
        // parent row are all measured against this `TreeFacts`, so a snapshot costs one build of it rather than one
        // per question — which is the shape §14's render benchmark is held to.
        val facts = TaskHierarchyPolicy.factsOf(screen.stored)
        val candidate = TaskHierarchyPolicy.previousSibling(current, facts)
        val measured = offerAt(current, candidate, facts)
        shown = current
        offer = measured
        destinations = MoveToDialog.destinations(current, screen, facts)

        // A topic the snapshot has no header for is read the same way the list reads it — filed under nothing — so
        // the two screens cannot name two different topics for one row.
        val topic = screen.topics.firstOrNull { it.uuid == current.groupId }
        val parent = current.parentUuid?.takeIf { it.isNotBlank() }?.let { facts.byUuid[it] }
        write(topicRow, context.getString(R.string.hierarchy_topic_value,
            topic?.name ?: context.getString(R.string.hierarchy_no_topic)))
        write(parentRow, context.getString(R.string.hierarchy_parent_value,
            parent?.title
                ?: context.getString(
                    if (topic != null) R.string.hierarchy_topic_root else R.string.hierarchy_standalone
                )
        ))
        write(levelRow, context.getString(
            R.string.hierarchy_level_value,
            TaskHierarchyPolicy.levelOf(facts, current)
        ))
        writeAction(
            indentButton, measured.indentReason, indentWords(measured), measured.canIndent
        )
        writeAction(
            outdentButton, measured.outdentReason,
            context.getString(HierarchyMenu.outdentLabel(measured.outdentReason)), measured.canOutdent
        )
        writeMoveAction()
        section.visibility = View.VISIBLE
    }

    /**
     * The Move-to action, drawn from the destination list the domain returned.
     *
     * Two states, not three: a row whose block can be filed somewhere gets the button, and a row the policy answers
     * `emptyList()` for gets none. There is no disabled-but-drawn case here because the refusal is not a sentence the
     * screen owns — the list *is* the answer to "where could this go", and a control that opens nothing is the dead
     * button §6.1 already refuses, on the same grounds and with the same remedy.
     *
     * Visibility is the whole of it, and no enabled state is written along with it: the guard that decides whether a
     * press opens a sheet is the emptiness check in the click handler, because `View#performClick` reaches a listener
     * whatever `isEnabled` says (the note at the top of this class), and no screen or test reads the flag on this
     * button. A write nothing observes is a second state a later reader has to keep in step by hand.
     */
    private fun writeMoveAction() {
        val wanted = if (destinations.isNotEmpty()) View.VISIBLE else View.GONE
        if (moveButton.visibility != wanted) moveButton.visibility = wanted
    }

    /**
     * The words on the indent button.
     *
     * A refusal keeps [HierarchyMenu.indentLabel]'s sentence — the domain's reason is the same words on both screens,
     * and a silent one is hidden further down, exactly as the list hides it. An *offered* indent cannot borrow the
     * list's title, because `row_indent` says "the row above": a fact about the block the Tasks list draws, which
     * this screen has never shown and which [TaskHierarchyPolicy.previousSibling] does not always agree with. What
     * the reader is offered here is a named task, so the button names it — the same row the press hands to
     * [TasksViewModel.indentTask].
     */
    private fun indentWords(measured: HierarchyMenu.Offer): String {
        val candidate = measured.indentCandidate
        return if (measured.canIndent && candidate != null) {
            context.getString(R.string.hierarchy_indent_action, candidate.title)
        } else {
            context.getString(HierarchyMenu.indentLabel(measured.indentReason))
        }
    }

    /**
     * The offer the card's menu would build for this row, from the two inputs the editor already holds: the candidate
     * [TaskHierarchyPolicy.previousSibling] named and the [TaskHierarchyPolicy.TreeFacts] the three values were
     * measured against.
     *
     * Going through the menu rather than calling [TaskHierarchyPolicy.indentRefusal] and
     * [TaskHierarchyPolicy.outdentRefusal] by hand is what carries the third answer too — the block that cannot be
     * named because an identity inside it is doubled — which the list hides and a hand-written pair of calls would
     * have had to duplicate. The refusal sentences, the enabled state and the hide rule are then one computation,
     * shared by both screens by construction. What the editor does *not* do is hand the menu a two-row list standing
     * in for a block it does not have: the candidate is passed as itself, and the facts are passed rather than
     * rebuilt, so one emission costs one measurement.
     */
    private fun offerAt(
        row: Task,
        candidate: Task?,
        facts: TaskHierarchyPolicy.TreeFacts
    ): HierarchyMenu.Offer = HierarchyMenu.offer(row, candidate, facts)

    private fun write(row: TextView, value: String) {
        // Only written when it moved: a render lands here once a second while a measurement runs, and re-setting the
        // same words would invalidate a form the reader is typing into for a change that is not a change.
        if (row.text.toString() != value) row.text = value
    }

    private fun writeAction(
        button: MaterialButton,
        reason: HierarchyMoveRefusal?,
        words: String,
        enabled: Boolean
    ) {
        val silent = HierarchyMenu.isSilent(reason)
        // The block's own visibility is the section's; this is the action's, and it is what `isSilent` decides — a
        // refusal the app cannot put into words hides its button rather than wearing the action's own name.
        val wanted = if (silent) View.GONE else View.VISIBLE
        if (button.visibility != wanted) button.visibility = wanted
        if (button.isEnabled != enabled) button.isEnabled = enabled
        if (button.text.toString() != words) button.text = words
    }
}
