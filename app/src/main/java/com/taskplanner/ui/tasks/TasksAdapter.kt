package com.taskplanner.ui.tasks

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.taskplanner.R
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.tracking.AndroidTimeBase
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.databinding.ItemGroupHeaderBinding
import com.taskplanner.databinding.ItemTaskBinding
import com.taskplanner.ui.util.WorkTimeFormat
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
    /** The card's single work-tracking control: start, pause or resume, chosen by the stored phase. */
    private val onWorkToggle: (com.taskplanner.data.model.Task) -> Unit,
    private val onToggleGroup: (TaskGroup) -> Unit,
    private val onGroupMenu: (View, TaskGroup) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    /**
     * The instant the cards measure "late" against. Injectable, and defaulted to the device's own clock,
     * because the one moment a deadline changes its mind — 23:59 against 00:01 — has to be nameable in a
     * test, and this repository has already been bitten once by a test that could not pin it.
     */
    private val timeBase: TimeBase = AndroidTimeBase()
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
        private var longPressFired = false
        private var panelWidth = 0f

        private val longPressRunnable = Runnable {
            if (!dragging && current != null) {
                longPressFired = true
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
                    longPressFired = false
                    card.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
                    // The down has to be taken. Nothing else in this row consumes it — the card is
                    // deliberately not clickable, because a click on it means "the gesture this handler
                    // owns", not "a click" — so returning false here left the parent with no touch target
                    // for the child, and the rest of the stream (MOVE and UP) was never delivered: no drag
                    // could start, no tap could land, and only the long press armed above would still
                    // fire. Scrolling is not lost by taking it: RecyclerView still intercepts a vertical
                    // drag and this row then gets ACTION_CANCEL, which the branch below treats as no
                    // gesture here, and a press that starts on one of the card's own buttons never
                    // reaches this line at all, because children are offered the down first.
                    return true
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
                    if (event.actionMasked == MotionEvent.ACTION_UP && longPressFired) {
                        // The press was answered when it was held; lifting the finger off is not a second
                        // request, and without this the row that was just selected would also open.
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

        /**
         * The measured-time line: what the task has cost, the seconds still being counted on it, and the
         * one button that answers for every phase.
         *
         * A completed task gets no button. Its timer was stopped when it was finished, and starting a
         * measurement on a task the user has closed would ask history to hold work nobody did.
         */
        private fun bindWork(item: TasksListItem.TaskRow) {
            val b = binding
            val context = b.root.context
            val work = item.work

            val showsWork = work.isShown && item.task.status != TaskStatus.COMPLETED
            // The control is configured before it is shown or hidden, and it is one shape rather than
            // three: the phase is said by the dot, the ticker and the description, while the button itself
            // stays the timer. Drawing it as a pause triangle would put two identical triangles on one
            // card — `pauseTaskButton` and this — which is the same ambiguity the audit found in the two
            // play triangles this stage removed.
            b.workControlButton.setImageResource(R.drawable.ic_work)
            b.workControlButton.contentDescription = context.getString(
                when (work.phase) {
                    TrackingPhase.RUNNING -> R.string.work_pause
                    TrackingPhase.PAUSED -> R.string.work_resume
                    else -> R.string.work_start
                }
            )
            b.workControlButton.setOnClickListener { current?.task?.let { onWorkToggle(it) } }
            b.workControlButton.isVisible = showsWork
            b.workRow.isVisible = showsWork
            if (!showsWork) return

            // A task nothing has been measured on gets no zero: the line says "work is happening here",
            // and a bare 0 s would say it about every card.
            b.workTime.isVisible = work.closedSeconds > 0
            if (b.workTime.isVisible) {
                val measured = WorkTimeFormat.compact(context, work.closedSeconds)
                b.workTime.text = measured
                b.workTime.contentDescription =
                    context.getString(R.string.work_card_description, measured)
            }
            b.workTicker.isVisible = work.isMeasured
            b.workTicker.text = WorkTimeFormat.ticker(work.liveSeconds ?: 0)
            b.workDot.isVisible = work.phase != TrackingPhase.IDLE
            b.workDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (work.phase == TrackingPhase.RUNNING) R.color.priority_high
                    else R.color.status_paused
                )
            )
            // The dot's two colours are the ones the global work bar already uses for the same two phases
            // (`MainActivity`), so a running stretch reads the same red at the top of the screen and on the
            // card. The names come from the priority palette for a historical reason, not a semantic one:
            // they are the app's two amber/red tokens, and Stage 10 renames them, not this stage.
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
            // Everything the card says about the task is decided once, in `CardPresentation`, from the row
            // and the clock it is given — the adapter only paints the answer. That boundary is the point:
            // the overdue rule, the status wording, the pill's colours and the lifecycle visibility are
            // all spec §4.1 arithmetic, and arithmetic a JVM test can hold still.
            val p = CardPresentation.of(context, item, timeBase.wallMillis())

            b.taskDueDate.text = p.dueLine
            // Late is a word and a colour, never a colour alone: the date line already reads "3 days late".
            b.taskDueDate.setTextColor(
                ContextCompat.getColor(
                    context, if (p.isOverdue) R.color.ink_error else R.color.ink_text_secondary
                )
            )

            b.taskStatus.setText(p.statusLabelRes)
            b.taskStatus.setBackgroundResource(p.pillBackgroundRes)
            b.taskStatus.setTextColor(ContextCompat.getColor(context, p.pillTextRes))

            b.taskCategory.text = item.category?.name ?: context.getString(R.string.no_category)
            // Restored on every bind: a holder whose previous row had no room for the name would otherwise
            // keep it hidden for the rest of the list's life, and the room is re-measured below.
            b.taskCategory.visibility = View.VISIBLE
            // The dot carries the colour; the word never does. That swap is the fix for the audit's
            // illegible categories, where a pale green name on the card measured 1.41:1.
            val category = item.category
            b.categoryDot.isVisible = category != null
            if (category != null) {
                b.categoryDot.backgroundTintList = ColorStateList.valueOf(category.color)
            }
            // The name is the one element on the card allowed to give way, and it gives way by the rule
            // rather than by truncation: below the floor it is gone, and the dot still says "categorised".
            b.cardContent.doOnLayout { content ->
                // Spec §4.1's one threshold, decided on the room that is left — not on the name's own
                // width, because under a `Flow` a child that lacks room is wrapped onto a line of its own,
                // where it always has its full text width and a check against that could never fail. The
                // room is read off the laid-out helper, so it is the column the renderer made rather than
                // the one the arithmetic predicted.
                val resources = content.resources
                val floor = CardPresentation.CATEGORY_NAME_MIN_DP.dp(resources)
                val gap = resources.getDimensionPixelSize(R.dimen.spacing_small)
                val room = b.lineBFlow.width - b.taskStatus.width - b.taskDueDate.width - 2 * gap
                if (b.taskCategory.isVisible && room < floor) b.taskCategory.visibility = View.GONE

                // Line C: the figures give way before the buttons do. A number that has to shrink was never
                // the point of the row; a control that shrinks is a control a thumb misses. The test runs on
                // the last *visible* figure — the ticker is gone on every card that is not being measured,
                // and a check against a hidden child measures a rectangle nobody sees.
                val figures = b.workRow
                if (figures.isVisible) {
                    val last = (0 until figures.childCount).map { figures.getChildAt(it) }
                        .lastOrNull { it.visibility == View.VISIBLE }
                    if (last != null && last.right > figures.width - figures.paddingEnd) {
                        b.workTime.isVisible = false
                        b.workTicker.isVisible = false
                    }
                }
            }

            // The bell says "this task will speak up", and it counts: a screen reader gets the same news
            // the eye does, in the same words.
            b.reminderBadge.isVisible = item.pendingReminders > 0
            if (item.pendingReminders > 0) {
                b.reminderBadge.contentDescription = context.resources.getQuantityString(
                    R.plurals.reminder_count, item.pendingReminders, item.pendingReminders
                )
            }

            b.repeatIcon.isVisible = item.series != null
            // The words the due line used to carry now live on the icon, so moving the marker does not
            // move the information: a reader who asks the icon still hears "weekly".
            p.repeatDescriptionRes?.let { b.repeatIcon.contentDescription = context.getString(it) }

            // LOW draws no rule — but it draws the space for one, so a list of mixed priorities does not
            // shift its titles by 4dp between a HIGH row and a LOW row.
            // Late wins over priority for the rule's colour, which is what spec §4.1's "date text and
            // priority rule in ink_error" asks for: a LOW row that is late still shows a red edge, and a
            // LOW row that is on time shows no edge at all.
            val ruleColor = if (p.isOverdue) R.color.ink_error else p.priorityRuleColorRes
            if (ruleColor == null) {
                b.priorityIndicator.visibility = View.INVISIBLE
            } else {
                b.priorityIndicator.visibility = View.VISIBLE
                // Tinted through the shape rather than painted over it: `setBackgroundColor` replaces the
                // background, and with it the 2dp corners `bg_priority_indicator` carries — so painting the
                // rule would quietly flatten the token it is meant to be using.
                b.priorityIndicator.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, ruleColor)
                )
            }

            b.startTaskButton.visibility = if (p.showStart) View.VISIBLE else View.GONE
            b.pauseTaskButton.visibility = if (p.showPause) View.VISIBLE else View.GONE
            b.resumeTaskButton.visibility = if (p.showResume) View.VISIBLE else View.GONE
            b.completeTaskButton.visibility = if (p.showComplete) View.VISIBLE else View.GONE

            b.startTaskButton.setOnClickListener { current?.task?.let { onStart(it) } }
            b.pauseTaskButton.setOnClickListener { current?.task?.let { onPause(it) } }
            b.resumeTaskButton.setOnClickListener { current?.task?.let { onResume(it) } }
            b.completeTaskButton.setOnClickListener { current?.task?.let { onComplete(it) } }

            bindWork(item)

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

        /** A dp value in this view's own density — the budget is written in dp and measured in px. */
        private fun Int.dp(resources: android.content.res.Resources): Int =
            (this * resources.displayMetrics.density).toInt()
    }
}
