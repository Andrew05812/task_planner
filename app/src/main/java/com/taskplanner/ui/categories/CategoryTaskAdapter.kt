package com.taskplanner.ui.categories

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.databinding.ItemTaskBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only task row for the category screen: no swipe panel, no drag handle and
 * no selection mode, so the behaviour stays predictable outside the main list.
 */
class CategoryTaskAdapter(
    private val onTaskClick: (TaskWithCategory) -> Unit,
    private val onTaskLongClick: (TaskWithCategory) -> Unit,
    private val onStart: (TaskWithCategory) -> Unit,
    private val onPause: (TaskWithCategory) -> Unit,
    private val onResume: (TaskWithCategory) -> Unit,
    private val onComplete: (TaskWithCategory) -> Unit
) : ListAdapter<TaskWithCategory, CategoryTaskAdapter.Holder>(DIFF) {

    /**
     * The due-date column, read from the language the app is in right now.
     *
     * This was a `companion object` value, which made it one formatter per process: chosen once, it kept
     * answering "Oct 15, 2026" after a reader switched to Русский and the rest of the row went Russian. On the
     * adapter it is built again with every adapter, and the adapter is built again when a language change
     * recreates the window — the same lifetime `ArchiveAdapter` and `TaskDetailFragment` already give theirs.
     * `LocaleStateSourceTest` is the gate that keeps it here.
     */
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemTaskBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TaskWithCategory) {
            val task = item.task
            val context = binding.root.context
            binding.dragHandle.visibility = View.GONE
            binding.selectionCheck.visibility = View.GONE
            binding.taskCard.translationX = 0f

            binding.taskTitle.text = task.title
            binding.taskDescription.text = task.description
            binding.taskDescription.visibility =
                if (task.description.isBlank()) View.GONE else View.VISIBLE
            binding.taskDueDate.text = task.dueDate?.let { dateFormat.format(Date(it)) }
                ?: context.getString(R.string.no_due_date)
            binding.taskCategory.text = item.category?.name
                ?: context.getString(R.string.no_category)
            binding.taskCategory.setTextColor(
                item.category?.color ?: ContextCompat.getColor(context, R.color.material_grey_600)
            )
            binding.taskStatus.text = context.getString(
                when (task.status) {
                    TaskStatus.TODO -> R.string.status_todo
                    TaskStatus.IN_PROGRESS -> R.string.status_in_progress
                    TaskStatus.PAUSED -> R.string.status_paused
                    TaskStatus.COMPLETED -> R.string.status_done
                }
            )
            binding.priorityIndicator.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    when (task.priority) {
                        Priority.LOW -> R.color.priority_low
                        Priority.MEDIUM -> R.color.priority_medium
                        Priority.HIGH -> R.color.priority_high
                    }
                )
            )

            binding.startTaskButton.visibility =
                if (task.status == TaskStatus.TODO) View.VISIBLE else View.GONE
            binding.pauseTaskButton.visibility =
                if (task.status == TaskStatus.IN_PROGRESS) View.VISIBLE else View.GONE
            binding.resumeTaskButton.visibility =
                if (task.status == TaskStatus.PAUSED) View.VISIBLE else View.GONE
            binding.completeTaskButton.visibility =
                if (task.status != TaskStatus.COMPLETED) View.VISIBLE else View.GONE

            binding.startTaskButton.setOnClickListener { onStart(item) }
            binding.pauseTaskButton.setOnClickListener { onPause(item) }
            binding.resumeTaskButton.setOnClickListener { onResume(item) }
            binding.completeTaskButton.setOnClickListener { onComplete(item) }

            binding.taskCard.setOnClickListener { onTaskClick(item) }
            binding.taskCard.setOnLongClickListener {
                onTaskLongClick(item)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TaskWithCategory>() {
            override fun areItemsTheSame(o: TaskWithCategory, n: TaskWithCategory): Boolean =
                o.task.id == n.task.id

            override fun areContentsTheSame(o: TaskWithCategory, n: TaskWithCategory): Boolean =
                o == n
        }
    }
}
