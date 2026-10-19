package com.taskplanner.ui.tasks

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.repository.WorkStatisticsRepository
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.databinding.FragmentTaskDetailBinding
import com.taskplanner.databinding.ItemWorkIntervalBinding
import com.taskplanner.ui.util.WorkConflictDialog
import com.taskplanner.ui.util.WorkTimeFormat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class TaskDetailFragment : Fragment() {
    private var _binding: FragmentTaskDetailBinding? = null
    private val binding get() = _binding!!
    private val args: TaskDetailFragmentArgs by navArgs()
    private val viewModel: TasksViewModel by viewModels()
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    /** The deadline being edited, as a calendar so the date and the clock are one instant. */
    private var dueCalendar: Calendar? = null

    /** Whether the user chose a time of day; without one the deadline is the start of the day. */
    private var timeChosen = false

    /**
     * When the user says the task happens, as opposed to when it is due. Both are optional and stay
     * null when untouched: a task with no plan line is drawn on its deadline alone, and no minute is
     * ever put on it by this editor.
     */
    private var startCalendar: Calendar? = null
    private var endCalendar: Calendar? = null

    /** Whether each plan minute was picked, so "starts on the 3rd" is not shown as "starts at midnight". */
    private var startTimeChosen = false
    private var endTimeChosen = false

    /** The series of the task on screen, kept so the repeat section can describe what it changes. */
    private var series: RecurrenceRule? = null

    /** The preset the user picked; the text field alone can be edited away by the keyboard. */
    private var preset: RepeatPreset = RepeatPreset.NONE

    /**
     * Custom lead times the editor cannot show, because its single number field holds one of them.
     * They stay in the saved plan instead of disappearing when the task is edited for another reason.
     */
    private var carriedCustomOffsets: List<Int> = emptyList()

    /**
     * The stored task on screen, kept apart from the fields being edited because the tracking block
     * belongs to the task as saved: a task not yet written to the database has nothing measured on it.
     */
    private var detailTask: Task? = null

    /**
     * Asks for notification permission the first time the user actually ticks a reminder — a task
     * with no reminders never needs it, and Android 13 only shows the dialog once per request.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshReminderUi()
            if (!granted) showSnackbar(getString(R.string.reminder_permission_needed))
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTaskDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupButtons()
        observeTaskSaveState()
        observeCurrentTask()
        observeSeries()
        observeReminders()
        observeTaskWork()
        args.taskId.let { taskId ->
            if (taskId > 0) {
                viewModel.loadTask(taskId)
            }
        }
    }

    private fun setupUI() {
        setupDatePicker()
        setupTimePicker()
        setupPlan()
        setupPrioritySpinner()
        setupStatusSpinner()
        setupCategorySpinner()
        setupRepeat()
        setupReminders()
        setupWork()
    }

    private fun setupDatePicker() {
        binding.taskDueDate.setOnClickListener {
            val calendar = dueCalendar ?: Calendar.getInstance()

            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    calendar.set(Calendar.YEAR, year)
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    if (dueCalendar == null) {
                        // A freshly picked day starts at midnight: the app does not invent a minute
                        // for a task the user described as "some day".
                        calendar.set(Calendar.HOUR_OF_DAY, 0)
                        calendar.set(Calendar.MINUTE, 0)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                    }
                    dueCalendar = calendar
                    renderDueFields()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        renderDueFields()
    }

    private fun setupTimePicker() {
        binding.taskDueTime.setOnClickListener {
            val calendar = dueCalendar ?: return@setOnClickListener
            TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    timeChosen = true
                    renderDueFields()
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(requireContext())
            ).show()
        }
    }

    /** The two halves of one deadline: the day line, the clock line, and what reminders may use. */
    private fun renderDueFields() {
        val calendar = dueCalendar
        binding.taskDueDate.setText(
            calendar?.let { dateFormat.format(it.time) } ?: ""
        )
        binding.taskDueTime.setText(
            when {
                calendar == null -> ""
                !timeChosen -> getString(R.string.label_due_time_none)
                else -> DateFormat.getTimeFormat(requireContext()).format(calendar.time)
            }
        )
        binding.taskDueTime.isEnabled = calendar != null
        refreshReminderUi()
    }

    // ---------------------------------------------------------------- plan times

    /**
     * The plan rows: when the task happens, as opposed to when it is due.
     *
     * Each row is the same two halves the deadline uses, because a day and a minute are one instant,
     * and every one of them is optional. The clear button exists because "the plan was taken away" and
     * "there never was a plan" have to be writable states, not accidents of an empty text field.
     */
    private fun setupPlan() {
        binding.planStartDate.setOnClickListener {
            pickPlanDay(startCalendar) { calendar ->
                startCalendar = calendar
                // "Starts on the 3rd" is the beginning of the 3rd — a day the user chose, not a
                // midnight the editor made up.
                startTimeChosen = isTimed(calendar)
                renderPlanFields()
            }
        }
        binding.planStartTime.setOnClickListener {
            pickPlanTime(startCalendar) { calendar ->
                startCalendar = calendar
                startTimeChosen = true
                renderPlanFields()
            }
        }
        binding.planEndDate.setOnClickListener {
            pickPlanDay(endCalendar) { calendar ->
                endCalendar = calendar
                endTimeChosen = isTimed(calendar)
                renderPlanFields()
            }
        }
        binding.planEndTime.setOnClickListener {
            pickPlanTime(endCalendar) { calendar ->
                endCalendar = calendar
                endTimeChosen = true
                renderPlanFields()
            }
        }
        binding.planEstimateMinutes.doAfterTextChanged {
            binding.planEstimateLayout.error = null
            renderPlanFields()
        }
        binding.planClear.setOnClickListener {
            startCalendar = null
            endCalendar = null
            startTimeChosen = false
            endTimeChosen = false
            binding.planEstimateMinutes.setText("")
            renderPlanFields()
        }
        renderPlanFields()
    }

    private fun pickPlanDay(current: Calendar?, onPicked: (Calendar) -> Unit) {
        val calendar = current ?: Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                if (current == null) {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                }
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                onPicked(calendar)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickPlanTime(current: Calendar?, onPicked: (Calendar) -> Unit) {
        val calendar = current ?: return
        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                onPicked(calendar)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            DateFormat.is24HourFormat(requireContext())
        ).show()
    }

    private fun isTimed(calendar: Calendar): Boolean =
        calendar.get(Calendar.HOUR_OF_DAY) != 0 || calendar.get(Calendar.MINUTE) != 0

    private fun renderPlanFields() {
        val timeFormat = DateFormat.getTimeFormat(requireContext())
        val start = startCalendar
        val end = endCalendar

        binding.planStartDate.setText(start?.let { dateFormat.format(it.time) } ?: "")
        binding.planStartTime.setText(
            when {
                start == null -> ""
                !startTimeChosen -> getString(R.string.plan_time_none)
                else -> timeFormat.format(start.time)
            }
        )
        binding.planStartTime.isEnabled = start != null

        binding.planEndDate.setText(end?.let { dateFormat.format(it.time) } ?: "")
        binding.planEndTime.setText(
            when {
                end == null -> ""
                !endTimeChosen -> getString(R.string.plan_time_none)
                else -> timeFormat.format(end.time)
            }
        )
        binding.planEndTime.isEnabled = end != null

        binding.planHint.text = describePlan()
        // The balance line weighs measured time against the estimate in this field, so it moves with it.
        renderWork()
    }

    /**
     * What the calendar will be able to draw from these fields, and what it will not make up.
     *
     * The line says the truth about the empty states too: a task with only a deadline has no length,
     * and saying so here is what keeps the user from looking for a duration the app never claimed.
     */
    private fun describePlan(): String {
        val start = startCalendar
        val end = endCalendar
        val estimate = planEstimate()
        if (start == null && end == null && estimate == null) {
            return getString(R.string.plan_hint_none)
        }
        val due = dueCalendar
        return when {
            start != null && end != null -> getString(
                R.string.plan_hint_block,
                momentLabel(start),
                momentLabel(end)
            )
            start != null && estimate != null -> getString(
                R.string.plan_hint_estimate,
                momentLabel(start),
                estimate
            )
            start != null -> getString(R.string.plan_hint_marker, momentLabel(start))
            due == null -> getString(R.string.error_plan_needs_instant)
            else -> getString(R.string.plan_hint_from_deadline)
        }
    }

    private fun momentLabel(calendar: Calendar): String =
        "${dateFormat.format(calendar.time)} ${DateFormat.getTimeFormat(requireContext()).format(calendar.time)}"

    /** The typed length, or null for "no length given". A blank field is not zero minutes. */
    private fun planEstimate(): Int? =
        binding.planEstimateMinutes.text?.toString()?.trim()?.toIntOrNull()

    /**
     * An estimate is a whole number of minutes or the save is refused. Text that is not a number stays
     * in the field so the user can fix it, instead of becoming a task with no length.
     */
    private fun planValid(): Boolean {
        val text = binding.planEstimateMinutes.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || planEstimate() != null) return true
        binding.planEstimateLayout.error = getString(R.string.error_plan_estimate_not_number)
        return false
    }

    private fun setupPrioritySpinner() {
        val priorities = Priority.values().map { getString(getPriorityStringRes(it)) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, priorities)
        binding.taskPriority.setAdapter(adapter)
        binding.taskPriority.setText(getString(getPriorityStringRes(Priority.MEDIUM)), false)
    }

    private fun setupStatusSpinner() {
        val statuses = TaskStatus.values().map { getString(getStatusStringRes(it)) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, statuses)
        binding.taskStatus.setAdapter(adapter)
        binding.taskStatus.setText(getString(getStatusStringRes(TaskStatus.TODO)), false)
    }

    private fun setupCategorySpinner() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.categories.collect { categories ->
                val categoryNames = categories.map { it.name }
                val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, categoryNames)
                binding.taskCategory.setAdapter(adapter)
            }
        }
    }

    private fun setupRepeat() {
        val presets = RepeatPreset.values()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            presets.map { getString(it.labelRes) }
        )
        binding.repeatPreset.setAdapter(adapter)
        binding.repeatPreset.setText(getString(RepeatPreset.NONE.labelRes), false)
        binding.repeatPreset.setOnItemClickListener { _, _, position, _ ->
            preset = presets[position]
            applyPreset(preset)
        }
        binding.repeatScopeGroup.setOnCheckedChangeListener { _, _ -> refreshRepeatUi() }
        binding.pauseSeriesButton.setOnClickListener { toggleSeriesPaused() }
        binding.finishSeriesButton.setOnClickListener {
            viewModel.finishSeries { showSnackbar(getString(R.string.msg_series_finished)) }
        }
        binding.deleteSeriesButton.setOnClickListener { confirmDeleteSeries() }
        applyPreset(RepeatPreset.NONE)
    }

    /** Only the fields the chosen preset actually uses are shown. */
    private fun applyPreset(preset: RepeatPreset) {
        binding.repeatIntervalLayout.isVisible = preset.editableInterval
        binding.repeatIntervalLayout.hint = getString(preset.unitLabelRes)
        binding.repeatWeekDaysLabel.isVisible = preset.showsWeekDays
        binding.repeatWeekDays.isVisible = preset.showsWeekDays
        binding.repeatDayLayout.isVisible = preset.showsDayOfMonth
        binding.repeatDayHint.isVisible = preset.showsDayOfMonth
        refreshRepeatUi()
    }

    private fun refreshRepeatUi() {
        val rule = series
        val onlyThisInstance = binding.repeatScopeThis.isChecked
        // A series' pattern belongs to the series, so editing "only this task" cannot change it.
        val patternEnabled = rule == null || !onlyThisInstance
        binding.repeatLayout.isEnabled = patternEnabled
        binding.repeatInterval.isEnabled = patternEnabled
        binding.repeatDayOfMonth.isEnabled = patternEnabled
        weekdayChips().forEach { (chip, _) -> chip.isEnabled = patternEnabled }
        binding.repeatScopeGroup.isVisible = rule != null
        binding.seriesStatus.isVisible = rule != null
        binding.seriesControls.isVisible = rule != null
        binding.repeatScopeHint.text = getString(scopeHintRes(rule, onlyThisInstance))
        if (rule != null) {
            binding.seriesStatus.text = getString(
                when {
                    rule.paused -> R.string.series_status_paused
                    rule.endedAt != null -> R.string.series_status_ended
                    else -> R.string.series_status_running
                }
            )
            binding.pauseSeriesButton.text = getString(
                if (rule.paused) R.string.action_resume_series else R.string.action_pause_series
            )
            binding.finishSeriesButton.isEnabled = rule.endedAt == null
        }
    }

    private fun scopeHintRes(rule: RecurrenceRule?, onlyThisInstance: Boolean): Int = when {
        rule == null && preset != RepeatPreset.NONE -> R.string.repeat_scope_hint_start
        rule == null -> R.string.repeat_scope_hint_new
        onlyThisInstance -> R.string.repeat_scope_hint_this
        preset == RepeatPreset.NONE -> R.string.repeat_scope_hint_stops
        else -> R.string.repeat_scope_hint_future
    }

    private fun renderSeries(rule: RecurrenceRule) {
        series = rule
        preset = RepeatPreset.of(rule)
        binding.repeatPreset.setText(getString(preset.labelRes), false)
        binding.repeatInterval.setText(rule.interval.toString())
        binding.repeatDayOfMonth.setText(if (rule.dayOfMonth == 0) "" else rule.dayOfMonth.toString())
        val days = rule.scheduledWeekDays
        weekdayChips().forEach { (chip, day) -> chip.isChecked = day in days }
        applyPreset(preset)
    }

    private fun toggleSeriesPaused() {
        val rule = series ?: return
        viewModel.setSeriesPaused(!rule.paused) { changed ->
            if (changed) {
                showSnackbar(
                    getString(if (rule.paused) R.string.msg_series_resumed else R.string.msg_series_paused)
                )
            }
        }
    }

    private fun confirmDeleteSeries() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_series_title)
            .setMessage(R.string.dialog_delete_series_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteSeries { showSnackbar(getString(R.string.msg_series_deleted)) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** The chips in ISO order (Monday = 1), which is what the rule stores. */
    private fun weekdayChips(): List<Pair<Chip, Int>> = with(binding) {
        listOf(
            dayMon to 1, dayTue to 2, dayWed to 3, dayThu to 4,
            dayFri to 5, daySat to 6, daySun to 7
        )
    }

    private fun selectedWeekDays(): List<Int> =
        weekdayChips().filter { (chip, _) -> chip.isChecked }.map { (_, day) -> day }

    private fun repeatPlan(): RepeatPlan = RepeatPlan(
        preset = preset,
        interval = binding.repeatInterval.text.toString().toIntOrNull() ?: 1,
        weekDays = selectedWeekDays(),
        dayOfMonth = binding.repeatDayOfMonth.text.toString().toIntOrNull() ?: 0,
        appliesToSeries = !binding.repeatScopeThis.isChecked
    )

    // ---------------------------------------------------------------- reminders

    /** The preset chips in firing order, paired with the lead time each one stands for. */
    private fun reminderChips(): List<Pair<Chip, Int>> = with(binding) {
        listOf(
            reminderAtTime to ReminderPreset.AT_TIME.offsetMinutes,
            reminderFiveMin to ReminderPreset.MIN_5.offsetMinutes,
            reminderFifteenMin to ReminderPreset.MIN_15.offsetMinutes,
            reminderThirtyMin to ReminderPreset.MIN_30.offsetMinutes,
            reminderOneHour to ReminderPreset.HOUR_1.offsetMinutes,
            reminderOneDay to ReminderPreset.DAY_1.offsetMinutes
        )
    }

    private fun setupReminders() {
        reminderChips().forEach { (chip, _) ->
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) requestNotificationPermission()
                refreshReminderUi()
            }
        }
        binding.reminderCustom.setOnCheckedChangeListener { _, checked ->
            binding.reminderCustomLayout.isVisible = checked
            if (checked) {
                binding.reminderCustomMinutes.requestFocus()
                requestNotificationPermission()
            }
            refreshReminderUi()
        }
        binding.reminderCustomMinutes.doAfterTextChanged {
            binding.reminderCustomLayout.error = null
            refreshReminderUi()
        }
        refreshReminderUi()
    }

    /**
     * What the reminder section says the user wants, in firing order.
     *
     * The chips are the input, so a custom number that equals a preset is simply that preset; the
     * values carried in from storage keep their place in the plan.
     */
    private fun selectedReminderOffsets(): List<Int> {
        val fromChips = reminderChips().filter { (chip, _) -> chip.isChecked }.map { (_, offset) -> offset }
        val custom = if (binding.reminderCustom.isChecked) {
            binding.reminderCustomMinutes.text.toString().toIntOrNull()?.let { ReminderPreset.sanitize(it) }
        } else {
            null
        }
        return ReminderPreset.normalize(fromChips + listOfNotNull(custom) + carriedCustomOffsets)
    }

    /** Ticks the chips a stored task already owns; anything else goes in the custom field. */
    private fun renderReminders(offsets: List<Int>) {
        val presets = ReminderPreset.values().map { it.offsetMinutes }
        reminderChips().forEach { (chip, offset) -> chip.isChecked = offset in offsets }
        val customs = offsets.filterNot { it in presets }
        binding.reminderCustom.isChecked = customs.isNotEmpty()
        binding.reminderCustomMinutes.setText(customs.firstOrNull()?.toString() ?: "")
        carriedCustomOffsets = customs.drop(1)
        binding.reminderCustomLayout.isVisible = customs.isNotEmpty()
        refreshReminderUi()
    }

    private fun refreshReminderUi() {
        val hasDeadline = dueCalendar != null
        val offsets = selectedReminderOffsets()
        val armed = offsets.isNotEmpty()

        // Without a deadline there is no minute to count back from, so the choices are taken away
        // rather than accepted and silently dropped on save.
        reminderChips().forEach { (chip, _) -> chip.isEnabled = hasDeadline }
        binding.reminderCustom.isEnabled = hasDeadline
        binding.reminderCustomMinutes.isEnabled = hasDeadline && binding.reminderCustom.isChecked

        binding.reminderHint.text = getString(
            when {
                !hasDeadline -> R.string.reminder_hint_needs_date
                // A reminder the user cannot receive is not a reminder; saying so beats arming a
                // row that will stay quiet until they find the switch.
                armed && !viewModel.canPostNotifications() -> R.string.reminder_notifications_off
                armed && !viewModel.canScheduleExactAlarms() -> R.string.reminder_exact_blocked
                else -> R.string.reminder_hint_ready
            }
        )
        binding.reminderStatus.isVisible = hasDeadline && armed
        binding.reminderStatus.text =
            resources.getQuantityString(R.plurals.reminder_count, offsets.size, offsets.size)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        // Only the permission itself opens the dialog. Notifications switched off in Settings is a
        // different state, one the user has to fix there, and the hint above says so.
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun notificationPlanValid(): Boolean {
        if (!binding.reminderCustom.isChecked) return true
        if (binding.reminderCustomMinutes.text.toString().toIntOrNull() != null) return true
        binding.reminderCustomLayout.error = getString(R.string.error_reminder_custom_minutes)
        return false
    }

    // ---------------------------------------------------------------- measured time

    /**
     * The four commands on one row: only the buttons the stored phase allows are put on screen, so
     * "Resume" cannot be tapped on a task that never started and "Start" cannot appear twice.
     */
    private fun setupWork() {
        binding.apply {
            workStartButton.setOnClickListener { workSubject()?.let { viewModel.toggleWork(it) } }
            workPauseButton.setOnClickListener { workSubject()?.let { viewModel.toggleWork(it) } }
            workResumeButton.setOnClickListener { workSubject()?.let { viewModel.toggleWork(it) } }
            workStopButton.setOnClickListener { workSubject()?.let { viewModel.stopWork(it) } }
        }
    }

    /** Only a task that has been saved can be measured; a new task has no row to attach intervals to. */
    private fun workSubject(): Task? = detailTask?.takeIf { it.id > 0 }

    private fun observeTaskWork() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.taskWork.collect { renderWork() }
        }
        // Start answers to the same single timer here as it does on the list screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.workConflict.collect { conflict ->
                    conflict?.let { WorkConflictDialog.show(this@TaskDetailFragment, viewModel, it) }
                }
            }
        }
    }

    /**
     * The tracking block, drawn from the figures the database holds rather than from anything this
     * screen remembers.
     *
     * The stored total and today's part of it are read facts; the running figure is the open stretch
     * re-measured from the start held in the row, and the pause is never in it. Estimate, actual, and
     * the difference between them are three separate lines because §8 asks the screen to keep them
     * apart: a task can be late, over its estimate, and unfinished at the same time.
     */
    private fun renderWork() {
        val task = detailTask
        val saved = task != null && task.id > 0
        binding.workSection.isVisible = saved
        if (!saved || task == null) return

        val view = viewModel.taskWork.value
        val phase = view.phase
        val closed = phase != TrackingPhase.IDLE
        val finished = task.status == TaskStatus.COMPLETED

        binding.workStateDot.isVisible = closed
        binding.workStateDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (phase == TrackingPhase.RUNNING) R.color.priority_high else R.color.status_paused
            )
        )
        binding.workLiveSeconds.isVisible = view.liveSeconds != null
        binding.workLiveSeconds.text = WorkTimeFormat.ticker(view.liveSeconds ?: 0L)
        binding.workStateHint.text = when (phase) {
            TrackingPhase.RUNNING -> getString(
                R.string.work_measuring_since,
                momentLabel(Calendar.getInstance().apply { timeInMillis = view.openStartedAt ?: 0L })
            )
            // The instant the stretch stopped is the end of the last closed interval, not a guess.
            TrackingPhase.PAUSED -> view.intervals.firstOrNull { it.endedAt != null }?.endedAt?.let {
                getString(R.string.work_paused_at, momentLabel(Calendar.getInstance().apply { timeInMillis = it }))
            } ?: getString(R.string.work_paused_unknown)
            else -> getString(R.string.work_not_measured)
        }

        binding.workStartButton.isVisible = !closed && !finished
        binding.workPauseButton.isVisible = phase == TrackingPhase.RUNNING && !finished
        binding.workResumeButton.isVisible = phase == TrackingPhase.PAUSED && !finished
        binding.workStopButton.isVisible = closed && !finished

        val context = requireContext()
        binding.workTotals.text = getString(
            R.string.work_bar_format,
            getString(R.string.work_actual_format, WorkTimeFormat.compact(context, view.totalSeconds)),
            getString(R.string.work_today_format, WorkTimeFormat.compact(context, view.todaySeconds))
        )
        binding.workBalance.text = describeBalance(view)
        renderIntervals(view)
    }

    /** Estimate against measured time, saying plainly when there is no estimate to weigh work against. */
    private fun describeBalance(view: WorkStatisticsRepository.TaskWorkView): String {
        val estimated = planEstimate() ?: detailTask?.estimatedMinutes
            ?: return getString(R.string.work_no_estimate)
        val balance = view.balanceAgainst(estimated)
        val context = requireContext()
        val estimateLabel = getString(
            R.string.work_estimate_format,
            WorkTimeFormat.compactMinutes(context, estimated)
        )
        val remaining = balance.remainingSeconds
        val overrun = balance.overrunSeconds
        return when {
            remaining != null -> getString(
                R.string.work_bar_format,
                estimateLabel,
                getString(R.string.work_remaining_format, WorkTimeFormat.compact(context, remaining))
            )
            overrun != null -> getString(
                R.string.work_bar_format,
                estimateLabel,
                getString(R.string.work_overrun_format, WorkTimeFormat.compact(context, overrun))
            )
            // The work is exactly the length it was guessed to be: saying so is truer than showing a
            // difference of zero.
            else -> getString(
                R.string.work_bar_format,
                estimateLabel,
                getString(R.string.work_on_estimate)
            )
        }
    }

    /**
     * The stretches that make up the total, newest first, each labelled with what it is: a measured
     * interval, one entered by hand, one still running, or one whose length was decided while the
     * device clock was being moved.
     */
    private fun renderIntervals(view: WorkStatisticsRepository.TaskWorkView) {
        val rows = view.intervals
        binding.workIntervalsEmpty.isVisible = rows.isEmpty()
        binding.workIntervals.removeAllViews()
        rows.forEach { row ->
            val labels = listOfNotNull(
                if (row.source == WorkSource.MANUAL.name) getString(R.string.work_interval_manual) else null,
                if (row.clockAnomaly != null) getString(R.string.work_interval_anomaly) else null
            )
            val text = buildString {
                append(
                    getString(
                        R.string.work_interval_row_format,
                        momentLabel(Calendar.getInstance().apply { timeInMillis = row.startedAt }),
                        row.endedAt?.let {
                            momentLabel(Calendar.getInstance().apply { timeInMillis = it })
                        } ?: getString(R.string.work_interval_open),
                        WorkTimeFormat.compact(
                            requireContext(),
                            row.durationSeconds ?: view.liveSeconds ?: 0L
                        )
                    )
                )
                if (labels.isNotEmpty()) {
                    append(" (")
                    append(labels.joinToString(", "))
                    append(")")
                }
            }
            val holder = ItemWorkIntervalBinding.inflate(
                layoutInflater,
                binding.workIntervals,
                false
            )
            holder.root.text = text
            binding.workIntervals.addView(holder.root)
        }
    }

    /**
     * Set when the save that is about to happen finishes a task whose stretch is still being measured.
     *
     * Completion closes the interval and keeps the time already worked in history, which is the right
     * outcome and a change to the user's measurements at the same time, so the screen says it rather
     * than letting a plain "saved" imply nothing happened to the timer.
     */
    private var closingMeasuredTask = false

    private fun saveConfirmationMessage(): String =
        if (closingMeasuredTask) {
            getString(R.string.work_closed_on_complete)
        } else {
            getString(R.string.msg_task_saved)
        }

    private fun getPriorityStringRes(priority: Priority): Int {
        return when (priority) {
            Priority.LOW -> R.string.priority_low
            Priority.MEDIUM -> R.string.priority_medium
            Priority.HIGH -> R.string.priority_high
        }
    }

    private fun getStatusStringRes(status: TaskStatus): Int {
        return when (status) {
            TaskStatus.TODO -> R.string.status_todo
            TaskStatus.IN_PROGRESS -> R.string.status_in_progress
            TaskStatus.PAUSED -> R.string.status_paused
            TaskStatus.COMPLETED -> R.string.status_done
        }
    }

    private fun getPriorityFromString(priorityString: String): Priority {
        return when (priorityString) {
            getString(R.string.priority_low) -> Priority.LOW
            getString(R.string.priority_high) -> Priority.HIGH
            else -> Priority.MEDIUM
        }
    }

    private fun getStatusFromString(statusString: String): TaskStatus {
        return when (statusString) {
            getString(R.string.status_in_progress) -> TaskStatus.IN_PROGRESS
            getString(R.string.status_paused) -> TaskStatus.PAUSED
            getString(R.string.status_done) -> TaskStatus.COMPLETED
            else -> TaskStatus.TODO
        }
    }

    private fun getCategoryFromString(categoryName: String): Long? {
        return viewModel.categories.value.find { it.name == categoryName }?.id
    }

    private fun setupButtons() {
        binding.apply {
            saveTask.setOnClickListener {
                val title = taskTitle.text.toString()
                val description = taskDescription.text.toString()
                val priority = getPriorityFromString(taskPriority.text.toString())
                val status = getStatusFromString(taskStatus.text.toString())
                val categoryId = taskCategory.text.toString().let { categoryName ->
                    if (categoryName.isNotBlank()) getCategoryFromString(categoryName) else null
                }

                taskTitleLayout.error = null
                if (title.isBlank()) {
                    taskTitleLayout.error = getString(R.string.error_title_required)
                    return@setOnClickListener
                }
                if (!notificationPlanValid() || !planValid()) return@setOnClickListener

                closingMeasuredTask = status == TaskStatus.COMPLETED &&
                    workSubject() != null &&
                    viewModel.taskWork.value.phase != TrackingPhase.IDLE
                viewModel.saveTask(
                    taskId = args.taskId,
                    title = title,
                    description = description,
                    dueDate = dueCalendar?.time,
                    priority = priority,
                    status = status,
                    categoryId = categoryId,
                    repeat = repeatPlan(),
                    // Always sent, so unchecking the last chip removes the reminder it stood for.
                    reminders = selectedReminderOffsets(),
                    startTime = startCalendar?.time,
                    endTime = endCalendar?.time,
                    estimatedMinutes = planEstimate()
                )
            }

            cancelButton.setOnClickListener {
                findNavController().navigateUp()
            }
        }
    }

    private fun observeTaskSaveState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.taskSaveState.collect { state ->
                when (state) {
                    is TaskSaveState.Loading -> {
                        binding.saveTask.isEnabled = false
                        binding.progressBar.isVisible = true
                        setFieldsEnabled(false)
                    }
                    is TaskSaveState.Success -> {
                        binding.saveTask.isEnabled = true
                        binding.progressBar.isVisible = false
                        setFieldsEnabled(true)
                        showSnackbar(saveConfirmationMessage())
                        findNavController().navigateUp()
                    }
                    is TaskSaveState.ScheduleConflict -> {
                        binding.saveTask.isEnabled = true
                        binding.progressBar.isVisible = false
                        setFieldsEnabled(true)
                        showSnackbar(getString(R.string.error_schedule_conflict))
                    }
                    is TaskSaveState.ReminderNeedsDeadline -> {
                        binding.saveTask.isEnabled = true
                        binding.progressBar.isVisible = false
                        setFieldsEnabled(true)
                        showSnackbar(getString(R.string.error_reminders_need_deadline))
                    }
                    is TaskSaveState.PlanConflict -> {
                        binding.saveTask.isEnabled = true
                        binding.progressBar.isVisible = false
                        setFieldsEnabled(true)
                        showSnackbar(getString(R.string.error_plan_conflict))
                    }
                    is TaskSaveState.PlanNeedsInstant -> {
                        binding.saveTask.isEnabled = true
                        binding.progressBar.isVisible = false
                        setFieldsEnabled(true)
                        showSnackbar(getString(R.string.error_plan_needs_instant))
                    }
                    is TaskSaveState.Error -> {
                        binding.saveTask.isEnabled = true
                        binding.progressBar.isVisible = false
                        setFieldsEnabled(true)
                        showSnackbar(state.message)
                    }
                    is TaskSaveState.Initial -> {
                        binding.saveTask.isEnabled = true
                        binding.progressBar.isVisible = false
                        setFieldsEnabled(true)
                    }
                }
            }
        }
    }

    private fun observeCurrentTask() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentTask.collect { task ->
                detailTask = task
                renderWork()
                task?.let {
                    binding.apply {
                        taskTitle.setText(it.title)
                        taskDescription.setText(it.description)
                        it.dueDate?.let { millis -> loadDeadline(millis) }
                        loadPlan(it)
                        taskPriority.setText(getString(getPriorityStringRes(it.priority)), false)
                        taskStatus.setText(getString(getStatusStringRes(it.status)), false)
                        it.categoryId?.let { categoryId ->
                            viewModel.categories.value.find { category -> category.id == categoryId }?.let { category ->
                                taskCategory.setText(category.name, false)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads a stored deadline back into the two fields. A saved instant always carries a time, so
     * midnight is shown as "all day" — the one case where the user never picked a minute.
     */
    private fun loadDeadline(millis: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        timeChosen = calendar.get(Calendar.HOUR_OF_DAY) != 0 || calendar.get(Calendar.MINUTE) != 0
        dueCalendar = calendar
        renderDueFields()
    }

    /**
     * Reads the stored plan back into the rows. Fields the task does not have stay empty: a task with
     * no plan line must not look like one with a plan the user forgot about.
     */
    private fun loadPlan(task: Task) {
        task.startTime?.let { millis ->
            val calendar = Calendar.getInstance().apply { timeInMillis = millis }
            startCalendar = calendar
            startTimeChosen = isTimed(calendar)
        } ?: run {
            startCalendar = null
            startTimeChosen = false
        }
        task.endTime?.let { millis ->
            val calendar = Calendar.getInstance().apply { timeInMillis = millis }
            endCalendar = calendar
            endTimeChosen = isTimed(calendar)
        } ?: run {
            endCalendar = null
            endTimeChosen = false
        }
        binding.planEstimateMinutes.setText(task.estimatedMinutes?.toString() ?: "")
        renderPlanFields()
    }

    private fun observeReminders() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.reminderOffsets.collect { offsets -> renderReminders(offsets) }
        }
    }

    private fun observeSeries() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.seriesRule.collect { rule ->
                if (rule == null) {
                    series = null
                    refreshRepeatUi()
                } else {
                    renderSeries(rule)
                    // The radio is the user's choice, not part of the rule, so it survives a reload
                    // of the series and defaults to the safer "this and following".
                    if (binding.repeatScopeGroup.checkedRadioButtonId == View.NO_ID) {
                        binding.repeatScopeFuture.isChecked = true
                    }
                    refreshRepeatUi()
                }
            }
        }
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        binding.taskTitle.isEnabled = enabled
        binding.taskDescription.isEnabled = enabled
        binding.taskDueDate.isEnabled = enabled
        binding.taskDueTime.isEnabled = enabled && dueCalendar != null
        binding.planStartDate.isEnabled = enabled
        binding.planStartTime.isEnabled = enabled && startCalendar != null
        binding.planEndDate.isEnabled = enabled
        binding.planEndTime.isEnabled = enabled && endCalendar != null
        binding.planEstimateMinutes.isEnabled = enabled
        binding.planClear.isEnabled = enabled
        binding.taskPriority.isEnabled = enabled
        binding.taskStatus.isEnabled = enabled
        binding.taskCategory.isEnabled = enabled
        binding.repeatPreset.isEnabled = enabled
        binding.repeatInterval.isEnabled = enabled
        binding.repeatDayOfMonth.isEnabled = enabled
        binding.repeatScopeGroup.isEnabled = enabled
        binding.pauseSeriesButton.isEnabled = enabled
        binding.finishSeriesButton.isEnabled = enabled
        binding.deleteSeriesButton.isEnabled = enabled
        binding.reminderCustomMinutes.isEnabled = enabled && binding.reminderCustom.isChecked
        refreshReminderUi()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.resetTaskSaveState()
        viewModel.clearCurrentTask()
        _binding = null
    }
}
