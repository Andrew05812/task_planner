package com.taskplanner.ui.tasks

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import com.taskplanner.R
import com.taskplanner.data.hierarchy.HierarchyMoveRefusal
import com.taskplanner.data.hierarchy.TaskHierarchyPolicy
import com.taskplanner.data.model.Task

/**
 * §6: the hierarchy actions a task row offers, and what it says when one is not possible.
 *
 * The brief asks for indent and outdent on the row and allows exactly one shape for them: «не загромождай карточку
 * постоянными четырьмя кнопками. Допустимы компактное contextual menu, editor actions или раскрываемые controls». One
 * control in the swipe panel — where this screen already keeps the row's other verbs — opens a menu of two, so the
 * card itself spends no width at all on a shape only some rows can use.
 *
 * **The rule is not re-implemented here.** Whether a press would write anything is [TaskHierarchyPolicy.indentRefusal]
 * and [TaskHierarchyPolicy.outdentRefusal]'s answer — the same two functions `TaskHierarchyRepository` calls inside its
 * own transaction. A second opinion in the view layer is how a menu ends up offering what the domain then refuses, and
 * a reader who presses a refused button has learned that the app cannot be trusted with their tree. `HierarchyControlsTest`
 * runs the menu and the repository on one fixture and compares.
 *
 * **A refusal is a sentence, not an absence.** §6 forbids offering what cannot be done ("нельзя indent, если нет
 * допустимого родителя") and §6.3 forbids showing an impossible destination as clickable; both are satisfied by
 * naming the reason on the disabled row rather than by dropping it, because a row that simply does not appear is a
 * fact the reader cannot ask about. Two refusals are the exception, and they are silent on purpose: a doubled or blank
 * parent identity means the notebook itself is inconsistent, which is not a choice anyone made and not something a row
 * of a list should be asked to repair. Those rows draw no control at all.
 */
object HierarchyMenu {

    /**
     * Whether a refusal is one the menu keeps silent about, and so hides rather than titles.
     *
     * Derived from [reasonLabel] rather than from a list, so the two can never drift: a refusal the menu has no
     * sentence for is a row the menu must not show. Today that is two — a blank or doubled parent identity, which
     * describe a notebook that is inconsistent rather than a choice the reader made — and it will stay that way for
     * any refusal the domain adds before anyone writes its sentence. [TaskHierarchyPolicy.hierarchyOffers] answers
     * with no control at all when *both* directions are silent; when only one is, the control is drawn for the
     * direction that works and the silent row is not shown, because a title has to come from a real resource and the
     * honest title for an unexplainable action is the action's own name on a row the reader cannot use.
     */
    fun isSilent(reason: HierarchyMoveRefusal?): Boolean = reason != null && reasonLabel(reason) == 0

    /**
     * What a press of either action would do, computed from the rows the screen drew and the rows the profile holds.
     *
     * A `null` reason means "this would be written"; anything else is the domain's own refusal, which the menu turns
     * into a title it disables.
     */
    data class Offer(
        /** The row an indent would hang the head on. The list's overload names the one *drawn* above it — which a
         * filter can make not the one the stored sequence holds there — while §6.2's editor names the nearest row
         * the profile keeps in the same sibling set. Either way it is the row a press writes, so each screen has to
         * say its own answer out loud rather than borrow the other's words: see `hierarchy_indent_action`. */
        val indentCandidate: Task?,
        val indentReason: HierarchyMoveRefusal?,
        val outdentReason: HierarchyMoveRefusal?
    ) {
        val canIndent: Boolean get() = indentReason == null
        val canOutdent: Boolean get() = outdentReason == null

        /** Whether this row is worth a control at all — and so whether the card draws one. */
        val anythingAvailable: Boolean get() = canIndent || canOutdent
    }

    /**
     * The row an indent press would name as the new parent: what the reader sees above this one.
     *
     * `null` for the first row on screen and for a row the screen does not draw. Choosing the *drawn* predecessor
     * rather than the stored one is what keeps an indent under a filter honest: hanging a row on a parent the reader
     * cannot see draws no change on their screen, and a control that changes nothing reads as a control that failed.
     */
    fun indentCandidate(row: Task, drawn: List<Task>): Task? {
        val at = drawn.indexOfFirst { it.id == row.id }
        return if (at <= 0) null else drawn[at - 1]
    }

    /**
     * Both questions the menu can answer, asked over the rows the screen drew and the rows the profile holds.
     *
     * The candidate is read off [drawn] — see [indentCandidate] — and the facts rebuilt from [stored]; a screen that
     * already holds both asks [offer] the other overload instead, so neither the row it hangs a task on nor the tree
     * it measures against is computed twice for one emission.
     */
    fun offer(row: Task, drawn: List<Task>, stored: List<Task>): Offer =
        offer(row, indentCandidate(row, drawn), TaskHierarchyPolicy.factsOf(stored))

    /**
     * The same answer from the candidate and the facts a caller has already chosen and built.
     *
     * §6.2's editor names its candidate through [TaskHierarchyPolicy.previousSibling] — the nearest row the profile
     * stores in the same sibling set, which is what its Indent press actually writes — and builds one
     * [TaskHierarchyPolicy.TreeFacts] per emission for the three values it prints. Handing those in, rather than
     * pretending the editor has a block of rows above the task, is what lets the two screens share one offer without
     * the editor inventing a screen it does not have; the candidate itself is the only input that differs, so the
     * refusal, the enabled state and the hide rule cannot drift from the list's.
     */
    fun offer(row: Task, candidate: Task?, facts: TaskHierarchyPolicy.TreeFacts): Offer {
        // A block that cannot be named — the head's own identity doubled, or one inside it — makes either direction
        // unsafe, so it is answered before either rule is consulted rather than by whichever one happens to look
        // first. The repository refuses the same thing at the door of its transaction.
        val unknowable = row.uuid in facts.ambiguous || row.uuid in facts.tainted
        return Offer(
            indentCandidate = candidate,
            indentReason = if (unknowable) HierarchyMoveRefusal.AMBIGUOUS_PARENT
            else TaskHierarchyPolicy.indentRefusal(row, candidate, facts),
            outdentReason = if (unknowable) HierarchyMoveRefusal.AMBIGUOUS_PARENT
            else TaskHierarchyPolicy.outdentRefusal(row, facts)
        )
    }

    @StringRes
    fun indentLabel(reason: HierarchyMoveRefusal?): Int =
        if (isSilent(reason)) R.string.row_indent else reason?.let(::reasonLabel) ?: R.string.row_indent

    @StringRes
    fun outdentLabel(reason: HierarchyMoveRefusal?): Int =
        if (isSilent(reason)) R.string.row_outdent else reason?.let(::reasonLabel) ?: R.string.row_outdent

    /**
     * The sentence for a refusal a reader can be told, or `0` when no sentence exists.
     *
     * `0` is not a title. Only [indentLabel] and [outdentLabel] may ask this question: they turn a refusal with no
     * sentence into the action's own name, and the menu hides that row.
     */
    @StringRes
    fun reasonLabel(reason: HierarchyMoveRefusal): Int = when (reason) {
        HierarchyMoveRefusal.NO_ROW_ABOVE -> R.string.indent_no_row_above
        HierarchyMoveRefusal.DIFFERENT_TOPIC -> R.string.indent_other_topic
        HierarchyMoveRefusal.NO_DEEPER_PARENT -> R.string.indent_already_under_it
        HierarchyMoveRefusal.DEPTH_EXCEEDED -> R.string.indent_too_deep
        HierarchyMoveRefusal.ANCHOR_IN_SUBTREE -> R.string.indent_own_child
        HierarchyMoveRefusal.ALREADY_AT_TOPIC_ROOT -> R.string.outdent_at_topic_root
        HierarchyMoveRefusal.PARENT_NOT_IN_PROFILE -> R.string.outdent_parent_missing
        else -> 0
    }

    /**
     * The popup the row's control opens, with both rows titled, enabled or disabled, and wired to the two handlers —
     * the whole decision in one object so the sentence on a row and the write behind it are the same computation
     * (`GroupHeaderMenu` learned that the hard way; see §8's review).
     *
     * [onIndent] is handed the row *and* the candidate the offer named, because a press that hung the row on anything
     * else would be a write the reader did not look at.
     */
    fun build(
        context: Context,
        anchor: View,
        row: Task,
        drawn: List<Task>,
        stored: List<Task>,
        onIndent: (Task, Task) -> Unit,
        onOutdent: (Task) -> Unit
    ): PopupMenu {
        val offer = offer(row, drawn, stored)
        val popup = PopupMenu(context, anchor)
        popup.menuInflater.inflate(R.menu.menu_task_hierarchy, popup.menu)
        popup.menu.findItem(R.id.action_indent_task).apply {
            title = context.getString(indentLabel(offer.indentReason))
            isEnabled = offer.canIndent
            isVisible = !isSilent(offer.indentReason)
        }
        popup.menu.findItem(R.id.action_outdent_task).apply {
            title = context.getString(outdentLabel(offer.outdentReason))
            isEnabled = offer.canOutdent
            isVisible = !isSilent(offer.outdentReason)
        }
        popup.setOnMenuItemClickListener { item ->
            val above = offer.indentCandidate
            when (item.itemId) {
                R.id.action_indent_task -> if (offer.canIndent && above != null) onIndent(row, above)
                R.id.action_outdent_task -> if (offer.canOutdent) onOutdent(row)
            }
            true
        }
        return popup
    }
}
