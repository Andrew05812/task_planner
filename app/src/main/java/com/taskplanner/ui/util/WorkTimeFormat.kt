package com.taskplanner.ui.util

import android.content.Context
import com.taskplanner.R
import java.util.Locale
import kotlin.math.abs

/**
 * The two ways a measured duration is shown, and nothing else.
 *
 * A card has a few dp and a history list has seconds to compare, so neither gets the other's format:
 * the compact form names the two largest units ("2 h 15 min", "45 min", "9 s") and the ticker form is
 * the fixed-width clock ("01:24:35") whose digits do not move the layout while a stretch runs.
 */
object WorkTimeFormat {

    /** A duration in words, short enough for a task card. */
    fun compact(context: Context, seconds: Long): String {
        val total = abs(seconds)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> context.getString(R.string.work_time_hours_minutes, hours, minutes)
            hours > 0 -> context.getString(R.string.work_time_hours, hours)
            minutes > 0 -> context.getString(R.string.work_time_minutes, minutes)
            else -> context.getString(R.string.work_time_seconds, total)
        }
    }

    /**
     * The running figure, in `h:mm:ss` with the hours field growing past two digits when a stretch runs
     * that long. Padding the minutes and seconds is what stops the ticker from shifting the text beside
     * it every second.
     *
     * A negative figure is printed as a negative rather than folded to a positive: a measurement that
     * went backwards is a fault worth seeing, and hiding its sign would make it look like time gained.
     */
    fun ticker(seconds: Long): String {
        val total = abs(seconds)
        return String.format(
            Locale.ROOT,
            "%s%d:%02d:%02d",
            if (seconds < 0) "-" else "",
            total / 3600,
            (total % 3600) / 60,
            total % 60
        )
    }

    /** The same figure the database stores, for a screen that shows an estimate next to it. */
    fun compactMinutes(context: Context, minutes: Int): String =
        compact(context, minutes * 60L)
}
