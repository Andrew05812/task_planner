package com.taskplanner.ui.tasks

import androidx.annotation.StringRes
import com.taskplanner.R

/**
 * The lead times the editor offers as one tap.
 *
 * Only the minutes matter to the database — a reminder is stored as an offset, not as a chip — so a
 * custom value that happens to equal a preset reads back as that preset, and the chips a task already
 * owns are exactly the rows the editor has to tick.
 */
enum class ReminderPreset(val offsetMinutes: Int, @StringRes val labelRes: Int) {
    AT_TIME(0, R.string.reminder_at_time),
    MIN_5(5, R.string.reminder_five_min),
    MIN_15(15, R.string.reminder_fifteen_min),
    MIN_30(30, R.string.reminder_thirty_min),
    HOUR_1(60, R.string.reminder_one_hour),
    DAY_1(1440, R.string.reminder_one_day);

    companion object {
        /**
         * The longest lead time accepted. A "reminder" years before a deadline is a different product
         * decision, not a notification, and an unbounded number field is a way to fill the alarm
         * registry with something the user cannot read.
         */
        const val MAX_OFFSET_MINUTES = 7 * 24 * 60

        fun of(offsetMinutes: Int): ReminderPreset? = values().firstOrNull { it.offsetMinutes == offsetMinutes }

        /** Clamps what the user typed into the custom field: negative and absurd values are refused. */
        fun sanitize(offsetMinutes: Int): Int = offsetMinutes.coerceIn(0, MAX_OFFSET_MINUTES)

        /** Distinct, soonest first — the order the reminders actually fire in. */
        fun normalize(offsets: List<Int>): List<Int> = offsets.map { sanitize(it) }.distinct().sorted()
    }
}
