package com.taskplanner.ui.tasks

import androidx.annotation.StringRes
import com.taskplanner.R
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit

/**
 * The repeat choices the task editor offers, and how each one maps onto a stored rule.
 *
 * The presets are the vocabulary the user sees; `unit`/`interval`/`weekDays`/`dayOfMonth` are what
 * the rule table stores. Keeping the translation in one place means the dropdown, the series badge
 * in the list and the editor's "what am I about to change" text can never disagree.
 */
enum class RepeatPreset(
    @param:StringRes val labelRes: Int,
    @param:StringRes val shortLabelRes: Int,
    val unit: RepeatUnit?,
    /** Interval the preset stands for on its own; [editableInterval] presets use the field. */
    val fixedInterval: Int = 1,
    val editableInterval: Boolean = false,
    @param:StringRes val unitLabelRes: Int = 0,
    val showsWeekDays: Boolean = false,
    val showsDayOfMonth: Boolean = false
) {
    NONE(R.string.repeat_none, R.string.repeat_none, null),
    DAILY(R.string.repeat_daily, R.string.repeats_daily, RepeatUnit.DAY),
    EVERY_N_DAYS(
        R.string.repeat_every_n_days, R.string.repeats_every_n_days, RepeatUnit.DAY,
        editableInterval = true, unitLabelRes = R.string.repeat_unit_days
    ),
    WEEKLY(R.string.repeat_weekly, R.string.repeats_weekly, RepeatUnit.WEEK),
    BIWEEKLY(R.string.repeat_biweekly, R.string.repeats_biweekly, RepeatUnit.WEEK, fixedInterval = 2),
    EVERY_N_WEEKS(
        R.string.repeat_every_n_weeks, R.string.repeats_every_n_weeks, RepeatUnit.WEEK,
        editableInterval = true, unitLabelRes = R.string.repeat_unit_weeks
    ),
    WEEKDAYS(
        R.string.repeat_weekdays, R.string.repeats_weekdays, RepeatUnit.WEEK,
        editableInterval = true, unitLabelRes = R.string.repeat_unit_weeks, showsWeekDays = true
    ),
    MONTHLY(
        R.string.repeat_monthly, R.string.repeats_monthly, RepeatUnit.MONTH,
        showsDayOfMonth = true
    ),
    EVERY_N_MONTHS(
        R.string.repeat_every_n_months, R.string.repeats_every_n_months, RepeatUnit.MONTH,
        editableInterval = true, unitLabelRes = R.string.repeat_unit_months, showsDayOfMonth = true
    ),
    YEARLY(R.string.repeat_yearly, R.string.repeats_yearly, RepeatUnit.YEAR),
    EVERY_N_YEARS(
        R.string.repeat_every_n_years, R.string.repeats_every_n_years, RepeatUnit.YEAR,
        editableInterval = true, unitLabelRes = R.string.repeat_unit_years
    );

    fun intervalOf(field: Int): Int = if (editableInterval) field.coerceAtLeast(1) else fixedInterval

    /**
     * The words the editor puts under the interval field, resolved through the caller's resources so
     * the preset stays a value and never a `Context`. [string] is `Fragment::getString`, and the id it
     * is handed is a `@StringRes`.
     */
    fun intervalHint(string: (Int) -> String): String = string(unitLabelRes)

    companion object {
        /** The preset a stored series shows as when the editor opens it. */
        fun of(rule: RecurrenceRule?): RepeatPreset = when {
            rule == null -> NONE
            rule.unit == RepeatUnit.DAY -> if (rule.interval == 1) DAILY else EVERY_N_DAYS
            rule.unit == RepeatUnit.WEEK -> when {
                rule.scheduledWeekDays.isNotEmpty() -> WEEKDAYS
                rule.interval == 1 -> WEEKLY
                rule.interval == 2 -> BIWEEKLY
                else -> EVERY_N_WEEKS
            }
            rule.unit == RepeatUnit.MONTH -> if (rule.interval == 1) MONTHLY else EVERY_N_MONTHS
            else -> if (rule.interval == 1) YEARLY else EVERY_N_YEARS
        }
    }
}

/**
 * What the repeat section of the editor says the user wants.
 *
 * [appliesToSeries] is the difference between "only this task" and "this and every task after it":
 * an instance may be moved or reworded on its own because the series tracks its schedule by the
 * slot the row was materialised for, not by the date the user sees.
 */
data class RepeatPlan(
    val preset: RepeatPreset = RepeatPreset.NONE,
    val interval: Int = 1,
    val weekDays: List<Int> = emptyList(),
    val dayOfMonth: Int = 0,
    val appliesToSeries: Boolean = true
) {
    val unit: RepeatUnit? get() = preset.unit

    /** A series that repeats on chosen weekdays keeps its own interval; the rest are fixed. */
    val effectiveInterval: Int get() = preset.intervalOf(interval)

    val effectiveWeekDays: List<Int>
        get() = if (preset.showsWeekDays) weekDays.filter { it in 1..7 }.distinct().sorted() else emptyList()

    /** 0 means "the day the series started on", which is what leaving the field blank says. */
    val effectiveDayOfMonth: Int
        get() = if (preset.showsDayOfMonth) dayOfMonth.coerceIn(0, 31) else 0

    /** True when the plan asks the series to change its pattern. */
    val asksForPattern: Boolean get() = unit != null && appliesToSeries
}
