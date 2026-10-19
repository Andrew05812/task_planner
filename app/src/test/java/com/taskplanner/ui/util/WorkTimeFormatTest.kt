package com.taskplanner.ui.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two printed forms of a measured duration.
 *
 * A card and a ticker have to be readable at a glance and stable while they change, and the way they get
 * there is opposite: one names units in words and drops the ones with no room for, the other holds a
 * fixed width so the text beside it does not jump every second. Both are checked against the figures the
 * database holds, including the awkward ones — a stretch under a minute, an hour with no minutes in it,
 * and a run long enough to need three digits of hours.
 */
@RunWith(RobolectricTestRunner::class)
class WorkTimeFormatTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun hoursMinutes(hours: Long, minutes: Long) =
        context.getString(R.string.work_time_hours_minutes, hours, minutes)

    private fun hours(hours: Long) = context.getString(R.string.work_time_hours, hours)

    private fun minutes(minutes: Long) = context.getString(R.string.work_time_minutes, minutes)

    private fun seconds(seconds: Long) = context.getString(R.string.work_time_seconds, seconds)

    private fun compact(value: Long): String = WorkTimeFormat.compact(context, value)

    @Test
    fun `the compact form names the two largest units and nothing smaller`() {
        assertEquals(
            hoursMinutes(2, 15),
            compact(2 * 3600 + 15 * 60)
        )
        assertEquals(
            "seconds are dropped rather than rounded up into an extra minute",
            hoursMinutes(2, 15),
            compact(2 * 3600 + 15 * 60 + 59)
        )
        assertEquals("an hour with no minutes in it does not print a spare 0 min", hours(2), compact(2 * 3600))
        assertEquals(minutes(45), compact(45 * 60))
    }

    @Test
    fun `a stretch under a minute is still shown, because it is measured time`() {
        assertEquals(seconds(9), compact(9))
        assertEquals("a task nothing has been measured on prints no line at all, so zero is never shown",
            seconds(0), compact(0))
    }

    @Test
    fun `an estimate is printed in the same words as measured time`() {
        assertEquals(hoursMinutes(1, 30), WorkTimeFormat.compactMinutes(context, 90))
        assertEquals(minutes(25), WorkTimeFormat.compactMinutes(context, 25))
    }

    @Test
    fun `the ticker pads the minutes and seconds so the line does not move`() {
        assertEquals("0:00:09", WorkTimeFormat.ticker(9))
        assertEquals("0:09:05", WorkTimeFormat.ticker(9 * 60 + 5))
        assertEquals("1:24:35", WorkTimeFormat.ticker(3600 + 24 * 60 + 35))
        assertEquals(
            "a stretch past a day is not folded into something shorter",
            "26:05:01",
            WorkTimeFormat.ticker(26 * 3600 + 5 * 60 + 1)
        )
    }

    @Test
    fun `a negative figure keeps its sign`() {
        assertEquals(
            "a measurement that went backwards is a fault, not a smaller success",
            "-0:01:00",
            WorkTimeFormat.ticker(-60)
        )
    }
}
