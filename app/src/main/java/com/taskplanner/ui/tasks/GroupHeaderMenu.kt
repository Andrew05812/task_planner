package com.taskplanner.ui.tasks

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import com.taskplanner.R
import com.taskplanner.data.model.TaskGroup

/**
 * The menu a topic's ⋮ opens, and the one decision inside it: what a press on the state row will write.
 *
 * §8 asks the reader to be able to change a topic's completed mark "там, где это соответствует текущему UX" — in the
 * place that fits the current UX. On this screen a topic header already has three gestures: the row folds the topic,
 * a long press selects the topic's rows, and the kebab opens the topic's own actions (rename, delete). The mark is a
 * topic action, so it joins that menu rather than buying a fourth gesture on a 320dp row that has already spent its
 * width — and a checkbox drawn on the header would compete with the fold chevron standing in the same place.
 *
 * The menu is built here, and its click handling installed here, for one reason: the sentence a reader reads and the
 * state the press writes are one decision, and separating them across two files is how a menu comes to say «Mark topic
 * completed» while its handler sets the flag to `false`. The fragment still owns *what* the three actions do — the
 * rename prompt, the delete confirmation — and passes those in as the three handlers; what it cannot now get wrong is
 * which way the state row points. `build` returns the popup the reader's press actually opens, so a test can read that
 * object and perform the action on it rather than testing a copy of its contents — the same shape
 * `TasksHeaderLayoutTest` uses for the screen's other popups.
 */
object GroupHeaderMenu {

    /**
     * The sentence for the state row: the state the topic does *not* currently hold.
     *
     * Two strings rather than one with a negation, because «Отметить тему выполненной» and «Снять отметку о
     * выполнении» govern different cases in Russian and share no usable stem.
     */
    @StringRes
    fun completionLabel(isCompleted: Boolean): Int =
        if (isCompleted) R.string.mark_topic_open else R.string.mark_topic_completed

    /** What a press on that row asks storage for — [completionLabel]'s own fact, so the two cannot drift apart. */
    fun completionTarget(group: TaskGroup): Boolean = !group.isCompleted

    /**
     * The popup the kebab opens: the state row already wears the sentence the topic calls for, and pressing it hands
     * [onCompletion] the state that sentence promised.
     *
     * `androidx.appcompat.widget.PopupMenu`, because that is what the screen's other two popups build — the sort
     * menu and the overflow menu — and the appcompat wrapper is the one that draws a menu against the app's theme
     * rather than the platform's.
     */
    fun build(
        context: Context,
        anchor: View,
        group: TaskGroup,
        onRename: (TaskGroup) -> Unit,
        onCompletion: (Boolean) -> Unit,
        onDelete: (TaskGroup) -> Unit
    ): PopupMenu {
        val popup = PopupMenu(context, anchor)
        popup.menuInflater.inflate(R.menu.menu_group_header, popup.menu)
        popup.menu.findItem(R.id.action_toggle_group_completion)
            .setTitle(completionLabel(group.isCompleted))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename_group -> onRename(group)
                R.id.action_toggle_group_completion -> onCompletion(completionTarget(group))
                R.id.action_delete_group -> onDelete(group)
            }
            true
        }
        return popup
    }
}
