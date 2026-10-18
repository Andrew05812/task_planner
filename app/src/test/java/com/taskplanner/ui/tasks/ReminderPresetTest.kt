package com.taskplanner.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The editor's quick picks and the sanity limits around the free-text field.
 *
 * A lead time is the only thing a reminder stores besides its task, so these two rules matter more
 * than they look: the presets have to survive a round trip through the database (a "5 min before"
 * that comes back as 6 would ring at the wrong minute), and whatever the user types has to stay
 * inside a range that still means "before" rather than "years ago".
 */
class ReminderPresetTest {

    @Test
    fun `the presets cover the minutes the specification asks for`() {
        assertEquals(
            listOf(0, 5, 15, 30, 60, 1440),
            ReminderPreset.values().map { it.offsetMinutes }
        )
        assertEquals(
            "every chip needs its own label",
            ReminderPreset.values().size,
            ReminderPreset.values().map { it.labelRes }.distinct().size
        )
    }

    @Test
    fun `a stored lead time is recognised again as the chip it came from`() {
        ReminderPreset.values().forEach { preset ->
            assertEquals(preset, ReminderPreset.of(preset.offsetMinutes))
        }
        assertNull("a custom value is not a preset", ReminderPreset.of(7))
    }

    @Test
    fun `the custom field cannot ask for a lead time that is not a reminder`() {
        assertEquals(0, ReminderPreset.sanitize(-120))
        assertEquals(15, ReminderPreset.sanitize(15))
        assertEquals(ReminderPreset.MAX_OFFSET_MINUTES, ReminderPreset.sanitize(999_999))
        assertEquals(
            "the longest lead time is a week before the deadline",
            7 * 24 * 60,
            ReminderPreset.MAX_OFFSET_MINUTES
        )
    }

    @Test
    fun `the plan is stored deduplicated and in firing order`() {
        assertEquals(
            listOf(0, 15, 1440),
            ReminderPreset.normalize(listOf(1440, 15, 0, 15))
        )
        assertEquals(
            "a custom number equal to a preset is that preset, not a second reminder",
            listOf(60),
            ReminderPreset.normalize(listOf(60, 60))
        )
        assertEquals(emptyList<Int>(), ReminderPreset.normalize(emptyList()))
        assertEquals(
            "a nonsense entry is clamped, never dropped silently into the past",
            listOf(0, 30),
            ReminderPreset.normalize(listOf(30, -5))
        )
    }

    @Test
    fun `clamping happens before deduplication so nonsense cannot reach storage`() {
        assertEquals(
            "a negative lead time becomes the deadline itself, an absurd one the longest week",
            listOf(0, ReminderPreset.MAX_OFFSET_MINUTES),
            ReminderPreset.normalize(listOf(-10, 999_999_999, 0))
        )
    }
}
