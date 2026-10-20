package com.taskplanner.ui.habits

import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.taskplanner.R
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitFrequency
import com.taskplanner.data.repository.HabitWriteResult
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.databinding.FragmentHabitEditorBinding
import com.taskplanner.ui.util.WorkInstantInput
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import kotlinx.coroutines.launch

/**
 * The one form that starts a habit or moves its schedule.
 *
 * The fields are the schedule: how often the habit is asked for, which days or how many times, the window it
 * covers, and the minute of the day to be reminded at. Which days were kept is never on this screen — a mark is
 * something the user asserts about a day, not a field to fill in, and the grid on the habit's own screen is
 * where it is made and corrected.
 *
 * Reading the texts and turning them into a window, a count and a minute is the ViewModel's job. This fragment
 * only puts the words on the fields, carries the user's choices between the chips and the save, and says what
 * the save did.
 */
@AndroidEntryPoint
class HabitEditorFragment : Fragment() {

    private var _binding: FragmentHabitEditorBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HabitEditorViewModel by viewModels()
    private val args: HabitEditorFragmentArgs by navArgs()

    /** The row the form has been filled from, so a later state change cannot overwrite what is being typed. */
    private var prefilledFrom: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHabitEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.load(args.habitId)
        binding.habitEditorFrequencyGroup.setOnCheckedStateChangeListener { _, _ ->
            showFieldsFor(selectedFrequency())
        }
        binding.habitEditorSave.setOnClickListener { save() }
        binding.habitEditorReminder.setOnClickListener { pickTime() }
        binding.habitEditorReminderClear.setOnClickListener { viewModel.clearReminder() }
        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    state.habit?.let { habit -> fillFrom(habit) }
                    binding.habitEditorProgress.isVisible = state.isLoading
                    binding.habitEditorSave.isEnabled = !state.isLoading && !state.isMissing
                    binding.habitEditorReminder.text = getString(
                        if (state.remindMinuteOfDay == null) {
                            R.string.habit_editor_reminder_none
                        } else {
                            R.string.habit_editor_reminder_set
                        },
                        state.remindMinuteOfDay?.let { HabitCard.timeText(it) }.orEmpty()
                    )
                    binding.habitEditorReminderClear.isVisible =
                        state.remindMinuteOfDay != null && state.isRemindable
                    // A habit that has been put away is asked for on no day, so there is no minute to be
                    // reminded at; the block goes rather than sitting there disabled above the save.
                    binding.habitEditorReminderGroup.isVisible = state.isRemindable
                    state.error?.let { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_retry) { viewModel.retry() }
                            .show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    /**
     * Puts the stored row into the fields, once.
     *
     * Once is the whole care here: the state is re-emitted when a write lands, and a form that filled itself
     * again at that moment would throw away what the user had just typed — including a weekday selection they
     * had moved on from the row.
     */
    private fun fillFrom(habit: Habit) {
        if (prefilledFrom == habit.uuid) return
        prefilledFrom = habit.uuid
        binding.habitEditorTitle.setText(habit.title)
        binding.habitEditorFrequencyGroup.check(chipResFor(habit.frequency))
        habit.scheduledWeekdays().forEach { day ->
            binding.habitEditorDaysGroup.findViewById<Chip>(chipResFor(day)).isChecked = true
        }
        habit.timesPerWeek?.let { binding.habitEditorTimes.setText(it.toString()) }
        binding.habitEditorStart.setText(WorkInstantInput.formatDay(WorkTimeMath.toDate(habit.startDay)))
        habit.endDay?.let { binding.habitEditorEnd.setText(WorkInstantInput.formatDay(WorkTimeMath.toDate(it))) }
        showFieldsFor(habit.frequency)
    }

    /**
     * Which fields the chosen frequency actually means.
     *
     * A daily habit has no weekday set and no weekly count, and the repository leaves those columns empty for
     * it. Showing them would invite the user to fill in a schedule the habit does not store, so they go, and
     * with them the label above them.
     */
    private fun showFieldsFor(frequency: HabitFrequency) {
        binding.habitEditorDaysLabel.isVisible = frequency == HabitFrequency.DAYS_OF_WEEK
        binding.habitEditorDaysGroup.isVisible = frequency == HabitFrequency.DAYS_OF_WEEK
        binding.habitEditorTimesLayout.isVisible = frequency == HabitFrequency.TIMES_PER_WEEK
    }

    private fun save() {
        viewModel.save(
            title = binding.habitEditorTitle.text?.toString().orEmpty(),
            frequency = selectedFrequency(),
            daysOfWeek = selectedWeekdays(),
            timesPerWeekText = binding.habitEditorTimes.text?.toString(),
            startDayText = binding.habitEditorStart.text?.toString(),
            endDayText = binding.habitEditorEnd.text?.toString()
        ) { outcome ->
            when (outcome) {
                // A field the form could not read is the one case where nothing at all was written, and the
                // words say how to write the field rather than that the button failed.
                is HabitSaveOutcome.Unreadable -> show(fieldRes(outcome.field), long = true)
                is HabitSaveOutcome.Written -> when (val result = outcome.result) {
                    is HabitWriteResult.Saved -> {
                        show(R.string.habit_saved, long = false)
                        findNavController().popBackStack()
                    }
                    is HabitWriteResult.Rejected -> show(HabitCard.rejectionRes(result.reason), long = true)
                }
            }
        }
    }

    /**
     * The clock that asks for the minute.
     *
     * The dialog answers with an hour and a minute and nothing else, so the answer is put in the ViewModel
     * rather than kept on the widget: a rotation between the picker and the save would otherwise drop the time
     * the user just chose while the button beside it still named it.
     */
    private fun pickTime() {
        val minutes = viewModel.state.value.remindMinuteOfDay
        val hour = (minutes ?: DEFAULT_REMINDER_MINUTE) / 60
        val minute = (minutes ?: DEFAULT_REMINDER_MINUTE) % 60
        TimePickerDialog(
            requireContext(),
            { _, pickedHour, pickedMinute -> viewModel.setReminder(pickedHour * 60 + pickedMinute) },
            hour,
            minute,
            DateFormat.is24HourFormat(requireContext())
        ).show()
    }

    /** The frequency the chips hold, which is the frequency the save writes. */
    private fun selectedFrequency(): HabitFrequency =
        when (binding.habitEditorFrequencyGroup.checkedChipId) {
            R.id.habitEditorFrequencyWeekdays -> HabitFrequency.DAYS_OF_WEEK
            R.id.habitEditorFrequencyTimes -> HabitFrequency.TIMES_PER_WEEK
            else -> HabitFrequency.DAILY
        }

    /** The checked weekdays, in Monday-first order — the order the mask's bits are in. */
    private fun selectedWeekdays(): List<DayOfWeek> =
        DAY_CHIPS.filter { (id, _) -> binding.habitEditorDaysGroup.findViewById<Chip>(id).isChecked }
            .map { (_, day) -> day }

    private fun chipResFor(frequency: HabitFrequency): Int = when (frequency) {
        HabitFrequency.DAYS_OF_WEEK -> R.id.habitEditorFrequencyWeekdays
        HabitFrequency.TIMES_PER_WEEK -> R.id.habitEditorFrequencyTimes
        HabitFrequency.DAILY -> R.id.habitEditorFrequencyDaily
    }

    private fun chipResFor(day: DayOfWeek): Int =
        DAY_CHIPS.firstOrNull { (_, value) -> value == day }?.first ?: R.id.habitEditorDayMon

    /** Which field the form could not read, in the words that tell the user how to write it. */
    @StringRes
    private fun fieldRes(field: HabitEditorField): Int = when (field) {
        HabitEditorField.START_DAY, HabitEditorField.END_DAY -> R.string.habit_bad_day
        HabitEditorField.TIMES_PER_WEEK -> R.string.habit_bad_times
    }

    private fun show(@StringRes message: Int, long: Boolean) {
        Snackbar.make(
            binding.root,
            message,
            if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        ).show()
    }

    private companion object {
        /** The weekday each chip names, in the order the mask's bits are in rather than a locale's. */
        private val DAY_CHIPS = listOf(
            R.id.habitEditorDayMon to DayOfWeek.MONDAY,
            R.id.habitEditorDayTue to DayOfWeek.TUESDAY,
            R.id.habitEditorDayWed to DayOfWeek.WEDNESDAY,
            R.id.habitEditorDayThu to DayOfWeek.THURSDAY,
            R.id.habitEditorDayFri to DayOfWeek.FRIDAY,
            R.id.habitEditorDaySat to DayOfWeek.SATURDAY,
            R.id.habitEditorDaySun to DayOfWeek.SUNDAY
        )

        /** The hour the picker opens on for a habit that has never had a minute: the morning the reminder is
         *  usually asked for. */
        private const val DEFAULT_REMINDER_MINUTE = 8 * 60
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prefilledFrom = null
        _binding = null
    }
}
