package com.taskplanner.ui.tasks

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.databinding.ItemGroupHeaderBinding
import com.taskplanner.databinding.ItemTaskBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class TasksAdapter(
    private val onTaskClick: (com.taskplanner.data.model.Task) -> Unit,
    private val onLongPressTask: (com.taskplanner.data.model.Task) -> Unit,
    private val onToggleSelection: (com.taskplanner.data.model.Task) -> Unit,
    private val onEdit: (com.taskplanner.data.model.Task) -> Unit,
    private val onDeleteRequest: (com.taskplanner.data.model.Task) -> Unit,
    private val onStart: (com.taskplanner.data.model.Task) -> Unit,
    private val onPause: (com.taskplanner.data.model.Task) -> Unit,
    private val onResume: (com.taskplanner.data.model.Task) -> Unit,
    private val onComplete: (com.taskplanner.data.model.Task) -> Unit,
    private val onToggleGroup: (TaskGroup) -> Unit,
    private val onGroupMenu: (View, TaskGroup) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<TasksListItem, RecyclerView.ViewHolder>(DIFF) {

    var selectionMode = false
    var reorderEnabled = false

    private var openHolder: TaskViewHolder? = null

    fun closeOpenPanel() {
        openHolder?.slideTo(0f, animate = true)
        openHolder = null
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TasksListItem.GroupHeader -> VIEW_TYPE_HEADER
        is TasksListItem.TaskRow -> VIEW_TYPE_TASK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(ItemGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            TaskViewHolder(ItemTaskBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TasksListItem.GroupHeader -> (holder as HeaderViewHolder).bind(item)
            is TasksListItem.TaskRow -> (holder as TaskViewHolder).bind(item)
            else -> Unit
        }
    }

    inner class HeaderViewHolder(private val binding: ItemGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TasksListItem.GroupHeader) {
            binding.groupName.text = item.group.name
            binding.groupCount.text = item.taskCount.toString()
            binding.groupChevron.rotation = if (item.group.collapsed) 0f else 90f
            binding.root.setOnClickListener { onToggleGroup(item.group) }
            binding.groupMenu.setOnClickListener { onGroupMenu(it, item.group) }
            binding.root.setOnLongClickListener {
                onGroupHeaderLongPress?.invoke(item.group)
                true
            }
        }
    }

    var onGroupHeaderLongPress: ((TaskGroup) -> Unit)? = null

    inner class TaskViewHolder(private val binding: ItemTaskBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var current: TasksListItem.TaskRow? = null
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var panelWidth = 0f

        private val longPressRunnable = Runnable {
            if (!dragging && current != null) {
                current?.task?.let { onLongPressTask(it) }
            }
        }

        init {
            binding.taskCard.setOnTouchListener { _, event -> handleTouch(event) }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && reorderEnabled && !selectionMode) {
                    onStartDrag(this)
                    true
                } else {
                    false
                }
            }
            binding.editTaskButton.setOnClickListener {
                current?.task?.let { task ->
                    onEdit(task)
                    closeSelf()
                }
            }
            binding.deleteTaskButton.setOnClickListener {
                current?.task?.let { task ->
                    onDeleteRequest(task)
                    closeSelf()
                }
            }
            binding.selectionCheck.setOnClickListener {
                current?.task?.let { onToggleSelection(it) }
            }
        }

        private fun closeSelf() {
            slideTo(0f, animate = true)
            if (openHolder == this) openHolder = null
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun handleTouch(event: MotionEvent): Boolean {
            val card = binding.taskCard
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragging = false
                    card.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
                    return false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && abs(dx) > itemView.touchSlop && abs(dx) > abs(dy) * 1.5f) {
                        dragging = true
                    }
                    if (dragging) {
                        card.removeCallbacks(longPressRunnable)
                        (itemView.parent as? RecyclerView)?.requestDisallowInterceptTouchEvent(true)
                        if (panelWidth <= 0f) panelWidth = binding.actionPanel.width.toFloat()
                        val base = if (openHolder == this) -panelWidth else 0f
                        card.translationX =
                            (base + dx).coerceIn(-panelWidth.coerceAtLeast(1f) - MAX_OVERSCROLL, 0f)
                        return true
                    }
                    return false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    card.removeCallbacks(longPressRunnable)
                    if (dragging) {
                        if (panelWidth <= 0f) panelWidth = binding.actionPanel.width.toFloat()
                        val shouldOpen = card.translationX < -panelWidth / 2f
                        if (shouldOpen) {
                            openHolder?.closeSelf()
                            openHolder = this
                            slideTo(-panelWidth, animate = true)
                        } else {
                            slideTo(0f, animate = true)
                            if (openHolder == this) openHolder = null
                        }
                        return true
                    }
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (openHolder == this) {
                            // tap on the open card closes it instead of following the click
                            closeSelf()
                            return true
                        }
                        current?.task?.let { task ->
                            if (selectionMode) onToggleSelection(task) else onTaskClick(task)
                        }
                    }
                    return false
                }

                else -> return false
            }
        }

        fun slideTo(x: Float, animate: Boolean) {
            if (animate) {
                binding.taskCard.animate().translationX(x).setDuration(SLIDE_MS).start()
            } else {
                binding.taskCard.translationX = x
            }
        }

        fun bind(item: TasksListItem.TaskRow) {
            current = item
            panelWidth = 0f
            slideTo(0f, animate = false)
            val task = item.task
            val b = binding

            b.taskTitle.text = task.title
            b.taskDescription.text = task.description
            b.taskDescription.visibility =
                if (task.description.isBlank()) View.GONE else View.VISIBLE
            val context = b.root.context
            val dueText = task.dueDate?.let { dateFormat.format(Date(it)) }
                ?: context.getString(R.string.no_due_date)
            val seriesLabel = item.series?.let { rule ->
                context.getString(
                    when {
                        rule.paused -> R.string.series_short_paused
                        rule.endedAt != null -> R.string.series_short_ended
                        else -> RepeatPreset.of(rule).shortLabelRes
                    }
                )
            }
            b.taskDueDate.text = if (seriesLabel == null) {
                dueText
            } else {
                context.getString(R.string.task_due_repeats_format, dueText, seriesLabel)
            }

            b.taskCategory.text = item.category?.name
                ?: b.root.context.getString(R.string.no_category)
            item.category?.let {
                b.taskCategory.setTextColor(it.color)
            } ?: b.taskCategory.setTextColor(
                ContextCompat.getColor(b.root.context, R.color.material_grey_600)
            )

            b.taskStatus.text = b.root.context.getString(
                when (task.status) {
                    TaskStatus.TODO -> R.string.status_todo
                    TaskStatus.IN_PROGRESS -> R.string.status_in_progress
                    TaskStatus.PAUSED -> R.string.status_paused
                    TaskStatus.COMPLETED -> R.string.status_done
                }
            )

            // The bell says "this task will speak up", so a screen reader gets the same news.
            b.reminderBadge.isVisible = item.pendingReminders > 0
            if (item.pendingReminders > 0) {
                b.reminderBadge.contentDescription = context.resources.getQuantityString(
                    R.plurals.reminder_count, item.pendingReminders, item.pendingReminders
                )
            }

            val priorityColor = when (task.priority) {
                com.taskplanner.data.model.Priority.LOW -> R.color.priority_low
                com.taskplanner.data.model.Priority.MEDIUM -> R.color.priority_medium
                com.taskplanner.data.model.Priority.HIGH -> R.color.priority_high
            }
            b.priorityIndicator.setBackgroundColor(
                ContextCompat.getColor(b.root.context, priorityColor)
            )

            b.startTaskButton.visibility = if (task.status == TaskStatus.TODO) View.VISIBLE else View.GONE
            b.pauseTaskButton.visibility = if (task.status == TaskStatus.IN_PROGRESS) View.VISIBLE else View.GONE
            b.resumeTaskButton.visibility = if (task.status == TaskStatus.PAUSED) View.VISIBLE else View.GONE
            b.completeTaskButton.visibility = if (task.status != TaskStatus.COMPLETED) View.VISIBLE else View.GONE

            b.startTaskButton.setOnClickListener { current?.task?.let { onStart(it) } }
            b.pauseTaskButton.setOnClickListener { current?.task?.let { onPause(it) } }
            b.resumeTaskButton.setOnClickListener { current?.task?.let { onResume(it) } }
            b.completeTaskButton.setOnClickListener { current?.task?.let { onComplete(it) } }

            b.dragHandle.visibility = if (selectionMode || !reorderEnabled) View.GONE else View.VISIBLE
            b.selectionCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            b.selectionCheck.setImageResource(
                if (item.isSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked
            )
            b.selectionCheck.alpha = if (item.isSelected) 1f else 0.6f
            b.taskCard.alpha = if (item.isSelected) 0.92f else 1f
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_TASK = 1
        private const val LONG_PRESS_TIMEOUT = 400L
        private const val SLIDE_MS = 180L
        private const val MAX_OVERSCROLL = 24f
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<TasksListItem>() {
            override fun areItemsTheSame(o: TasksListItem, n: TasksListItem): Boolean =
                when {
                    o is TasksListItem.TaskRow && n is TasksListItem.TaskRow -> o.task.id == n.task.id
                    o is TasksListItem.GroupHeader && n is TasksListItem.GroupHeader ->
                        o.group.uuid == n.group.uuid
                    else -> false
                }

            override fun areContentsTheSame(o: TasksListItem, n: TasksListItem): Boolean = o == n
        }

        private val View.touchSlop: Float
            get() = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
}
