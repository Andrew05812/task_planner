package com.taskplanner.ui.tasks

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
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
    private var selectedDate: Date? = null

    /** The series of the task on screen, kept so the repeat section can describe what it changes. */
    private var series: RecurrenceRule? = null

    /** The preset the user picked; the text field alone can be edited away by the keyboard. */
    private var preset: RepeatPreset = RepeatPreset.NONE

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
        args.taskId.let { taskId ->
            if (taskId > 0) {
                viewModel.loadTask(taskId)
            }
        }
    }

    private fun setupUI() {
        setupDatePicker()
        setupPrioritySpinner()
        setupStatusSpinner()
        setupCategorySpinner()
        setupRepeat()
    }

    private fun setupDatePicker() {
        binding.taskDueDate.setOnClickListener {
            val calendar = Calendar.getInstance()
            selectedDate?.let { calendar.time = it }

            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth)
                    selectedDate = calendar.time
                    binding.taskDueDate.setText(dateFormat.format(selectedDate!!))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
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

                viewModel.saveTask(
                    taskId = args.taskId,
                    title = title,
                    description = description,
                    dueDate = selectedDate,
                    priority = priority,
                    status = status,
                    categoryId = categoryId,
                    repeat = repeatPlan()
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
                        it.dueDate?.let { date ->
                            selectedDate = Date(date)
                            taskDueDate.setText(dateFormat.format(selectedDate!!))
                        }
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
