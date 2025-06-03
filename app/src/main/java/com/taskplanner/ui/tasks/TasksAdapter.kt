package com.taskplanner.ui.tasks

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.TaskWithCategory
import com.taskplanner.databinding.ItemTaskBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.ContextCompat

class TasksAdapter(
    private val onTaskClick: (Task) -> Unit,
    private val onTaskStatusChange: (Task) -> Unit,
    private val onTaskDelete: (Task) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<TaskWithCategory, TasksAdapter.TaskViewHolder>(TaskDiffCallback()) {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TaskViewHolder(
        private val binding: ItemTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onTaskClick(getItem(position).task)
                }
            }

            binding.deleteTaskButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onTaskDelete(getItem(position).task)
                }
            }

            binding.startTaskButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val task = getItem(position).task
                    if (task.status == TaskStatus.TODO) {
                        onTaskStatusChange(task.copy(status = TaskStatus.IN_PROGRESS))
                    }
                }
            }

            binding.completeTaskButton.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val task = getItem(position).task
                    if (task.status != TaskStatus.COMPLETED) {
                        onTaskStatusChange(task.copy(status = TaskStatus.COMPLETED))
                    }
                }
            }

            // Add long click listener to start drag
            itemView.setOnLongClickListener {
                onStartDrag(this)
                true
            }
        }

        fun bind(taskWithCategory: TaskWithCategory) {
            val task = taskWithCategory.task
            val category = taskWithCategory.category

            binding.apply {
                taskTitle.text = task.title
                taskDescription.text = task.description
                taskDueDate.text = task.dueDate?.let { dateFormat.format(Date(it)) } ?: "No due date"
                taskPriority.text = when (task.priority) {
                    Priority.LOW -> "Low"
                    Priority.MEDIUM -> "Medium"
                    Priority.HIGH -> "High"
                }
                taskCategory.text = category?.name ?: "No category"

                // Set priority indicator color
                val priorityColorRes = when (task.priority) {
                    Priority.LOW -> com.taskplanner.R.color.priority_low
                    Priority.MEDIUM -> com.taskplanner.R.color.priority_medium
                    Priority.HIGH -> com.taskplanner.R.color.priority_high
                }
                priorityIndicator.setBackgroundColor(
                    ContextCompat.getColor(root.context, priorityColorRes)
                )

                // Update button visibility based on task status
                startTaskButton.visibility = if (task.status == TaskStatus.TODO) View.VISIBLE else View.GONE
                completeTaskButton.visibility = if (task.status != TaskStatus.COMPLETED) View.VISIBLE else View.GONE
            }
        }
    }

    private class TaskDiffCallback : DiffUtil.ItemCallback<TaskWithCategory>() {
        override fun areItemsTheSame(oldItem: TaskWithCategory, newItem: TaskWithCategory): Boolean {
            return oldItem.task.id == newItem.task.id
        }

        override fun areContentsTheSame(oldItem: TaskWithCategory, newItem: TaskWithCategory): Boolean {
            return oldItem == newItem
        }
    }
} 