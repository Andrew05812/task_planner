package com.taskplanner.ui.util

import com.taskplanner.R
import com.taskplanner.data.repository.WorkPeriod

/**
 * The chip label for a period the repository can name.
 *
 * One function because two screens filter by the same six windows: the history list and the dashboard.
 * Words that live in one of them would drift, and a chip row that called the same range by two names would
 * leave the user guessing which screen had changed rather than which period was showing.
 */
fun WorkPeriod.labelRes(): Int = when (this) {
    WorkPeriod.TODAY -> R.string.history_period_today
    WorkPeriod.YESTERDAY -> R.string.history_period_yesterday
    WorkPeriod.THIS_WEEK -> R.string.history_period_this_week
    WorkPeriod.LAST_WEEK -> R.string.history_period_last_week
    WorkPeriod.THIS_MONTH -> R.string.history_period_this_month
    WorkPeriod.LAST_MONTH -> R.string.history_period_last_month
}
