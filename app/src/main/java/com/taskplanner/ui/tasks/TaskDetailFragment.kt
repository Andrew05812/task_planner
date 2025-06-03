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
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Priority
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
                    categoryId = categoryId
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

    private fun setFieldsEnabled(enabled: Boolean) {
        binding.taskTitle.isEnabled = enabled
        binding.taskDescription.isEnabled = enabled
        binding.taskDueDate.isEnabled = enabled
        binding.taskPriority.isEnabled = enabled
        binding.taskStatus.isEnabled = enabled
        binding.taskCategory.isEnabled = enabled
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.resetTaskSaveState()
        _binding = null
    }
} 