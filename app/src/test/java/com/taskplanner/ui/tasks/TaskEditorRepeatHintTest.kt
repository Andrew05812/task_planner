package com.taskplanner.ui.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The words the editor puts under the interval field, resolved exactly the way the screen resolves
 * them: through [RepeatPreset.intervalHint] and the application's own resources.
 *
 * This is the line `TaskDetailFragment.applyPreset` runs for the preset the editor opens with, so a
 * preset that cannot answer it takes the whole screen down — which is what happened for every preset
 * that repeats at a fixed rate: `NONE`, `DAILY`, `WEEKLY`, `BIWEEKLY` and `YEARLY` carried no unit
 * label, and "no unit label" used to be spelled `0`, which is not a string resource.
 */
@RunWith(RobolectricTestRunner::class)
class TaskEditorRepeatHintTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun hintOf(preset: RepeatPreset): String? = preset.intervalHint(context::getString)

    @Test
    fun `every preset gives the editor a hint its resources can resolve`() {
        RepeatPreset.values().forEach { preset ->
            hintOf(preset)
        }
    }

    @Test
    fun `a preset that fixes its own interval names no unit at all`() {
        listOf(
            RepeatPreset.NONE,
            RepeatPreset.DAILY,
            RepeatPreset.WEEKLY,
            RepeatPreset.BIWEEKLY,
            RepeatPreset.YEARLY
        ).forEach { preset ->
            assertNull("${preset.name} has no number to label", hintOf(preset))
        }
    }

    @Test
    fun `a preset that asks for a number says what the number counts`() {
        assertEquals("Days between repeats", hintOf(RepeatPreset.EVERY_N_DAYS))
        assertEquals("Weeks between repeats", hintOf(RepeatPreset.EVERY_N_WEEKS))
        assertEquals("Weeks between repeats", hintOf(RepeatPreset.WEEKDAYS))
        assertEquals("Months between repeats", hintOf(RepeatPreset.EVERY_N_MONTHS))
        assertEquals("Years between repeats", hintOf(RepeatPreset.EVERY_N_YEARS))
    }

    /**
     * The invariant that makes the crash unrepresentable: the field is shown exactly where a hint
     * exists, so nothing can ever hide a field while still resolving a label for it.
     */
    @Test
    fun `the interval field is shown exactly where a hint exists`() {
        RepeatPreset.values().forEach { preset ->
            assertEquals(
                "${preset.name}: an editable interval and a unit label are the same fact",
                (hintOf(preset) != null),
                preset.editableInterval
            )
        }
    }

    @Test
    fun `no preset hands the editor a resource id it cannot resolve`() {
        RepeatPreset.values().forEach { preset ->
            val id = preset.unitLabelRes
            assertTrue(
                "${preset.name}: a unit label is either absent or a real resource id",
                id == null || id > 0
            )
        }
    }

    /** Requirement 1 of the brief: no repeat means no unit of time, not a unit of time left blank. */
    @Test
    fun `a task that does not repeat carries no unit and no unit label`() {
        assertNull(RepeatPreset.NONE.unit)
        assertNull(RepeatPreset.NONE.unitLabelRes)
        assertNull(hintOf(RepeatPreset.NONE))
        assertEquals(context.getString(R.string.repeat_none), context.getString(RepeatPreset.NONE.labelRes))
    }

    /** The dropdown and the badge must read as sentences, never as enum names. */
    @Test
    fun `every preset is named to the user in words and not by its enum name`() {
        RepeatPreset.values().forEach { preset ->
            val label = context.getString(preset.labelRes)
            val short = context.getString(preset.shortLabelRes)
            assertTrue("${preset.name}: ${label}", label.isNotBlank() && label != preset.name)
            assertTrue("${preset.name}: ${short}", short.isNotBlank() && short != preset.name)
        }
    }

    /**
     * The preset a stored series opens as must itself be renderable: `of()` decides which hint the
     * editor resolves the moment the screen appears, so a series written by any preset has to be
     * openable by every one of them.
     */
    @Test
    fun `every preset a stored rule opens as is one the editor can render`() {
        RepeatPreset.values().forEach { preset ->
            val unit = preset.unit ?: return@forEach
            val stored = rule(
                unit,
                preset.intervalOf(3),
                if (preset.showsWeekDays) "1,3" else "",
                if (preset.showsDayOfMonth) 15 else 0
            )
            val opened = RepeatPreset.of(stored)
            assertEquals("a ${preset.name} series opens as ${preset.name}", preset, opened)
            assertEquals(
                "and it is labelled the same way the editor labelled it on the way in",
                hintOf(preset),
                hintOf(opened)
            )
        }
    }

    private fun rule(
        unit: RepeatUnit,
        interval: Int,
        weekDays: String,
        dayOfMonth: Int
    ): RecurrenceRule = RecurrenceRule(
        userId = 1,
        unit = unit,
        interval = interval,
        weekDays = weekDays,
        dayOfMonth = dayOfMonth,
        anchorAt = 0,
        cursorAt = 0
    )

    /** The fixed-rate presets keep their own interval whatever the typed field holds. */
    @Test
    fun `a preset with no unit label still resolves its interval from itself`() {
        assertEquals(1, RepeatPreset.NONE.intervalOf(7))
        assertEquals(1, RepeatPreset.DAILY.intervalOf(7))
        assertEquals(1, RepeatPreset.WEEKLY.intervalOf(7))
        assertEquals(2, RepeatPreset.BIWEEKLY.intervalOf(7))
        assertEquals(1, RepeatPreset.YEARLY.intervalOf(7))
        assertEquals(7, RepeatPreset.EVERY_N_DAYS.intervalOf(7))
        assertEquals(1, RepeatPreset.EVERY_N_DAYS.intervalOf(0))
    }
}
