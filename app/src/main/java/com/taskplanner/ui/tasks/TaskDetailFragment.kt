package com.taskplanner.ui.tasks

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.databinding.FragmentTaskDetailBinding
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
        args.taskId.let { taskId ->
            if (taskId > 0) {
                viewModel.loadTask(taskId)
            }
        }
    }

    private fun setupUI() {
        setupDatePicker()
        setupTimePicker()
        setupPrioritySpinner()
        setupStatusSpinner()
        setupCategorySpinner()
        setupRepeat()
        setupReminders()
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
                if (!notificationPlanValid()) return@setOnClickListener

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
                    reminders = selectedReminderOffsets()
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
                        showSnackbar(getString(R.string.msg_task_saved))
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
                task?.let {
                    binding.apply {
                        taskTitle.setText(it.title)
                        taskDescription.setText(it.description)
                        it.dueDate?.let { millis -> loadDeadline(millis) }
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
