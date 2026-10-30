package com.taskplanner.ui.archive

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.databinding.ItemArchiveBinding
import com.taskplanner.ui.util.LocalisedDates

/**
 * The rows of the archive, and the only place allowed to say what an archived row is.
 *
 * Stage 8a moved this class out of `ArchiveFragment.kt`, where it was private, for one reason: the completed
 * treatment is decided here — Stage 1 wrote that into the style file on purpose, because a style cannot see
 * the data — and a binder nobody can construct cannot be tested. The fragment's behaviour is unchanged: the
 * same three callbacks, the same date format, the same meta line.
 *
 * What the row asserts, and why it is careful:
 *
 *  * **Its status, in the app's own words.** `TaskDao.getArchivedTasks` filters on `archived = 1` and
 *    `is_deleted = 0` and on no status, so a task can reach this screen while it is still TODO. The row used
 *    to say "Completed …" over every one of them, which is a claim the query does not support, and Stage 8a's
 *    review caught that a mark carrying its word only as a `contentDescription` fixes nothing for a reader who
 *    can see but cannot hear: the state has to be on the card. So the word is the task's own status label —
 *    the same four strings the Tasks card and the category list already use — and it can only ever say what
 *    the record says.
 *  * **Struck through, only when it is finished.** The strike follows `status == COMPLETED`, so an unfinished
 *    archive entry does not wear the look of finished work, and the date line is named for the moment it
 *    actually holds: when the task was archived.
 */
class ArchiveAdapter(
    val onRestore: (Task) -> Unit,
    val onDeleteForever: (Task) -> Unit,
    val onOpenDetail: (Task) -> Unit
) : ListAdapter<ArchiveState.ArchivedTask, ArchiveAdapter.VH>(
    object : DiffUtil.ItemCallback<ArchiveState.ArchivedTask>() {
        override fun areItemsTheSame(
            oldItem: ArchiveState.ArchivedTask,
            newItem: ArchiveState.ArchivedTask
        ) = oldItem.task.id == newItem.task.id

        override fun areContentsTheSame(
            oldItem: ArchiveState.ArchivedTask,
            newItem: ArchiveState.ArchivedTask
        ) = oldItem == newItem
    }
) {
    class VH(val binding: ItemArchiveBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArchiveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val finished = item.task.status == TaskStatus.COMPLETED
        with(holder.binding) {
            archiveTitle.text = item.task.title
            // Set rather than OR alone: a recycled row that last showed a finished task has to be able to
            // come back unstruck.
            archiveTitle.paintFlags = if (finished) {
                archiveTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                archiveTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            // The status vocabulary the rest of the app already uses for these four states
            // (`CardPresentation.statusLabelRes` on the Tasks card, `CategoryTaskAdapter` on the category
            // list). Repeating the `when` here is the fourth copy; the shared helper belongs to a stage that
            // is allowed to change those two files, and this one is not.
            archiveStateMark.setText(statusLabelRes(item.task.status))
            val archived = item.task.archivedAt?.let { LocalisedDates.dateTime(it) } ?: "—"
            val category = item.category?.name ?: root.context.getString(R.string.no_category)
            archiveMeta.text = root.context.getString(R.string.archive_archived_at, archived) +
                " · $category"
            restoreButton.setOnClickListener { onRestore(item.task) }
            deleteForeverButton.setOnClickListener { onDeleteForever(item.task) }
            root.setOnClickListener { onOpenDetail(item.task) }
        }
    }

    private fun statusLabelRes(status: TaskStatus): Int = when (status) {
        TaskStatus.TODO -> R.string.status_todo
        TaskStatus.IN_PROGRESS -> R.string.status_in_progress
        TaskStatus.PAUSED -> R.string.status_paused
        TaskStatus.COMPLETED -> R.string.status_done
    }
}
