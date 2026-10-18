package com.taskplanner.ui.tasks

import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presets are what the user reads, the rule fields are what is stored. Both directions of that
 * translation are asserted here because a wrong mapping silently changes a person's schedule: an
 * "every two weeks" that stores 1 is twice as many tasks, and a weekday list that is not sorted or
 * deduplicated makes the series produce instances out of order.
 */
class RepeatPresetTest {

    private fun rule(
        unit: RepeatUnit,
        interval: Int = 1,
        weekDays: String = "",
        dayOfMonth: Int = 0
    ): RecurrenceRule = RecurrenceRule(
        userId = 1,
        unit = unit,
        interval = interval,
        weekDays = weekDays,
        dayOfMonth = dayOfMonth,
        anchorAt = 0,
        cursorAt = 0
    )

    @Test
    fun `every preset is recognised again from the rule it produces`() {
        RepeatPreset.values().forEach { preset ->
            val unit = preset.unit ?: return@forEach
            val stored = rule(
                unit,
                preset.intervalOf(3),
                if (preset.showsWeekDays) "1,3" else "",
                if (preset.showsDayOfMonth) 15 else 0
            )
            val recognised = RepeatPreset.of(stored)
            assertEquals(preset, recognised)
            // The interval is what tells the plain preset from the counted one, so it has to survive.
            assertEquals(
                "a series must open in the editor showing the schedule it really runs on",
                stored.interval,
                recognised.intervalOf(stored.interval)
            )
        }
    }

    @Test
    fun `the counted presets keep the number the user typed`() {
        assertEquals(3, RepeatPreset.EVERY_N_DAYS.intervalOf(3))
        assertEquals(3, RepeatPreset.EVERY_N_WEEKS.intervalOf(3))
        assertEquals(3, RepeatPreset.EVERY_N_MONTHS.intervalOf(3))
        assertEquals(3, RepeatPreset.EVERY_N_YEARS.intervalOf(3))
        assertEquals(2, RepeatPreset.BIWEEKLY.intervalOf(9))
        assertEquals(1, RepeatPreset.DAILY.intervalOf(9))
        assertEquals(1, RepeatPreset.MONTHLY.intervalOf(0))
    }

    @Test
    fun `a stored series reads back as its preset`() {
        assertEquals(RepeatPreset.DAILY, RepeatPreset.of(rule(RepeatUnit.DAY)))
        assertEquals(RepeatPreset.EVERY_N_DAYS, RepeatPreset.of(rule(RepeatUnit.DAY, 5)))
        assertEquals(RepeatPreset.WEEKLY, RepeatPreset.of(rule(RepeatUnit.WEEK)))
        assertEquals(RepeatPreset.BIWEEKLY, RepeatPreset.of(rule(RepeatUnit.WEEK, 2)))
        assertEquals(RepeatPreset.EVERY_N_WEEKS, RepeatPreset.of(rule(RepeatUnit.WEEK, 4)))
        assertEquals(RepeatPreset.WEEKDAYS, RepeatPreset.of(rule(RepeatUnit.WEEK, 1, "2,4")))
        assertEquals(RepeatPreset.MONTHLY, RepeatPreset.of(rule(RepeatUnit.MONTH)))
        assertEquals(RepeatPreset.EVERY_N_MONTHS, RepeatPreset.of(rule(RepeatUnit.MONTH, 2)))
        assertEquals(RepeatPreset.YEARLY, RepeatPreset.of(rule(RepeatUnit.YEAR)))
        assertEquals(RepeatPreset.EVERY_N_YEARS, RepeatPreset.of(rule(RepeatUnit.YEAR, 3)))
        assertEquals(RepeatPreset.NONE, RepeatPreset.of(null))
    }

    @Test
    fun `a plan only carries the fields its own preset uses`() {
        val yearly = RepeatPlan(
            preset = RepeatPreset.YEARLY,
            interval = 7,
            weekDays = listOf(1, 2),
            dayOfMonth = 30
        )
        assertEquals(1, yearly.effectiveInterval)
        assertEquals(emptyList<Int>(), yearly.effectiveWeekDays)
        assertEquals(0, yearly.effectiveDayOfMonth)

        val onDays = RepeatPlan(
            preset = RepeatPreset.WEEKDAYS,
            interval = 0,
            weekDays = listOf(5, 1, 5, 9, 0)
        )
        assertEquals(1, onDays.effectiveInterval)
        assertEquals("chosen days are ordered, unique and inside the week", listOf(1, 5), onDays.effectiveWeekDays)

        val monthly = RepeatPlan(preset = RepeatPreset.MONTHLY, dayOfMonth = 45)
        assertEquals(31, monthly.effectiveDayOfMonth)
    }

    @Test
    fun `only a plan that names a pattern and applies to the series asks to change it`() {
        assertTrue(RepeatPlan(preset = RepeatPreset.DAILY).asksForPattern)
        assertEquals(RepeatUnit.DAY, RepeatPlan(preset = RepeatPreset.DAILY).unit)
        assertTrue(
            "changing one instance must never rewrite the series",
            !RepeatPlan(preset = RepeatPreset.DAILY, appliesToSeries = false).asksForPattern
        )
        assertTrue("saying does not repeat is not a pattern change", !RepeatPlan().asksForPattern)
    }
}
