package com.taskplanner.ui.tasks

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Paint
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
    /**
     * The card's fold control: hide this row's branch, or show it again.
     *
     * It is a view command, not an edit — the row's data does not change, only what the list draws under it — so it
     * takes the task and nothing else, and the screen decides where the state is stored.
     */
    private val onToggleBranch: (com.taskplanner.data.model.Task) -> Unit,
    /**
     * Opens §6's hierarchy menu for one row, anchored on the control that was pressed.
     *
     * The adapter cannot decide what the menu holds: the answer depends on which rows the screen drew in what order,
     * and the view model is the only place holding that. So the control reports the press and hands over the row.
     */
    private val onHierarchy: (View, com.taskplanner.data.model.Task) -> Unit,
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
            bindTopicCompletion(item.group.isCompleted)
            binding.root.setOnClickListener { onToggleGroup(item.group) }
            binding.groupMenu.setOnClickListener { onGroupMenu(it, item.group) }
            binding.root.setOnLongClickListener {
                onGroupHeaderLongPress?.invoke(item.group)
                true
            }
        }

        /**
         * §8's completed mark: the sentence, its glyph, and the strike through the topic's own name.
         *
         * All three are written on both branches, because a recycled header that keeps the previous topic's mark tells
         * the reader a finished story about a topic that is not finished — the same reuse rule [bindBranch] states for
         * the fold control, and the reason the text is set to empty rather than left alone. The glyph is put on in code
         * rather than in the layout for the same reason: a `drawableStart` written in XML would be carried by an open
         * header too — invisible, but present — and no test could tell a mark that is withdrawn from one that is only
         * hidden.
         *
         * Three channels, and colour is the last of them: the words say it, the strike shapes it, and `status_done`
         * only emphasises what the other two already carry. The glyph itself is unnamed, so the sentence is spoken
         * once rather than twice.
         */
        private fun bindTopicCompletion(finished: Boolean) {
            val name = binding.groupName
            val state = binding.groupState
            state.visibility = if (finished) View.VISIBLE else View.GONE
            state.text = if (finished) state.context.getString(R.string.topic_completed) else ""
            state.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (finished) R.drawable.ic_topic_completed else 0, 0, 0, 0
            )
            name.paintFlags = if (finished) {
                name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
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

        /**
         * The title's own padding, taken before the first bind, and the step a nested task is moved by.
         *
         * The indent is written as an absolute padding on every bind — base plus depth times the step — because a
         * recycled card that *added* to whatever the previous row left behind would walk sideways down a list of
         nested tasks, and the reader would take that drift for the shape of their own notebook.
         */
        private val titleBasePadding = binding.taskTitle.paddingStart
        private val indentPerLevel = binding.root.context.resources.getDimensionPixelSize(R.dimen.task_depth_indent)

        private val longPressRunnable = Runnable {
            // The card cannot reach a row the screen refuses to select; the refusal itself lives in the view model,
            // which is the only place that knows which rows are scaffolding.
            if (!dragging && current != null && current?.shownForContext != true) {
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

        /**
         * The fold: the chevron, the number it carries, and the words that say which way the next press goes.
         *
         * Three facts decide what the control looks like, and none of them is computed here. The number is the one
         * the ViewModel measured over the rows the filters left — what a press moves on *this* screen — so a card
         * cannot advertise three rows while a fold two levels up is holding two of them, or while a filter has taken
         * the branch out of view. The direction comes from
         * [com.taskplanner.data.model.Task.childrenCollapsed], the stored column the list already obeyed to put those
         * rows out of sight: a control that remembered its own last press would disagree with the screen it is drawn
         * on. And the mode is read off the row rather than off this adapter's flag, because a `ListAdapter` rebinds
         * only what changed — entering selection mode changes one row's tick mark, and every other card would go on
         * offering a fold under a flag it never re-read.
         *
         * The whole wrapper is the control: it takes the click, the ripple and the accessible name. With the click on
         * the chevron alone, the digit beside it was a dead zone inside the reader's aim — a press there fell through
         * to the card's own touch handler and opened the task the reader was reaching to fold. The digit itself says
         * nothing to a screen reader, because the words it stands for are already in the name.
         */
        private fun bindBranch(item: TasksListItem.TaskRow) {
            val b = binding
            val context = b.root.context
            val branch = item.foldRows
            val collapsed = item.task.childrenCollapsed

            val scaffolding = item.shownForContext
            b.branchFold.visibility = if (branch > 0 && !item.selectionMode && !scaffolding) View.VISIBLE else View.GONE
            b.branchCount.text = branch.toString()
            // The topic header's convention, kept: 90 degrees is "open, the rows are below me", 0 is "closed,
            // the rows are what I am pointing at. Rotation alone would leave a reader who cannot see the screen
            // with no direction at all, which is why the name below changes with it.
            b.branchToggle.rotation = if (collapsed) 0f else 90f
            b.branchFold.contentDescription = context.resources.getQuantityString(
                if (collapsed) R.plurals.branch_expand else R.plurals.branch_collapse, branch, branch
            )
            b.branchFold.setOnClickListener { current?.task?.let { task -> onToggleBranch(task) } }
        }

        fun bind(item: TasksListItem.TaskRow) {
            current = item
            panelWidth = 0f
            slideTo(0f, animate = false)
            val task = item.task
            val b = binding

            // The indent is the depth, and it is set from zero every time rather than added: a recycled card
            // that remembered the previous row's padding would drift sideways down a list of nested tasks.
            b.taskTitle.setPadding(
                titleBasePadding + item.depth * indentPerLevel,
                b.taskTitle.paddingTop,
                b.taskTitle.paddingEnd,
                b.taskTitle.paddingBottom
            )
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

            b.taskCategory.text = if (item.shownForContext) {
                // The word that would stand here is the category the filter was *not* asking for, and a reader who
                // saw it would read the row as a match with a surprising category. The slot says what the row is
                // instead: a sentence, so it reaches a screen reader and a magnified reader as well as an eye.
                context.getString(R.string.row_shown_for_context)
            } else {
                item.category?.name ?: context.getString(R.string.no_category)
            }
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
                        // The line goes with its figures. A lone 8dp dot in a box no wider than it is a mark that
                        // says "work is happening here" about a card that can no longer say how much — and the dot
                        // was the only part of line C left over when the fold control took the room the two figures
                        // had been sharing with it.
                        figures.isVisible = false
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
            bindBranch(item)

            // §6's one control for two verbs. The decision is the render's rather than the adapter's because it
            // depends on which rows the screen drew and in what order, and because a `ListAdapter` rebinds only what
            // changed — so a row's own flag is the only way a control can be *taken away* from a card that is already
            // on screen. A row with nothing available draws nothing: an indent that cannot indent is a button that
            // has learned to do nothing, and §7's fold control is the precedent for hiding rather than grey-out on
            // the card itself (the menu's greyed rows are a different case — they say why).
            b.hierarchyButton.visibility = if (item.canEditHierarchy) View.VISIBLE else View.GONE
            b.hierarchyButton.setOnClickListener {
                current?.task?.let { task -> onHierarchy(b.hierarchyButton, task) }
            }

            b.dragHandle.visibility =
                if (selectionMode || !reorderEnabled || item.shownForContext) View.GONE else View.VISIBLE
            // A scaffolding row shows a tick only when it already holds one: an answer selected before the
            // filter changed keeps its mark and stays untappable-out, and a fresh context row never grows a
            // box a reader can press into a batch they did not ask for.
            b.selectionCheck.visibility =
                if (selectionMode && (!item.shownForContext || item.isSelected)) View.VISIBLE else View.GONE
            b.selectionCheck.setImageResource(
                if (item.isSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked
            )
            b.selectionCheck.alpha = if (item.isSelected) 1f else 0.6f
            // Three channels mark a scaffolding row, because one of them is a colour claim: the card sits back, a
            // shape leads its title, and line B says what the row is. Alpha alone would be the pale-green lesson of
            // Stage 4's audit — a difference only the eye that knows where to look can find.
            b.taskCard.alpha = when {
                item.shownForContext -> 0.78f
                item.isSelected -> 0.92f
                else -> 1f
            }
            b.taskTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (item.shownForContext) R.drawable.ic_context_branch else 0, 0, 0, 0
            )
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
