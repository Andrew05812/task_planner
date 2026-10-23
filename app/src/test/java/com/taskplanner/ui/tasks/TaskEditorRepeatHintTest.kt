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
 * The interval field's label, resolved through [RepeatPreset.intervalHint] and the application's own
 * resources — the same function `TaskDetailFragment.applyPreset` calls.
 *
 * The screen itself is not run here; that is `TaskEditorInstrumentedTest`'s job, and it exists because
 * this project had no test that ever executed a Fragment's `onViewCreated`. What this file pins is the
 * model: which presets have a label at all, what each label says, and that no preset can hand the editor
 * an id that is not a string. Together they are what makes the crash unwritable — `getString(0)` threw
 * `Resources$NotFoundException` and took the whole editor down for the six presets that repeat at a
 * fixed rate: `NONE`, `DAILY`, `WEEKLY`, `BIWEEKLY`, `MONTHLY` and `YEARLY`.
 *
 * To see the first test fail, put the sentinel back: change `unitLabelRes` in `RepeatPreset.kt` to
 * `@param:StringRes val unitLabelRes: Int = 0` and make `editableInterval` a declared `false` default
 * again. Both files still compile, and `every preset gives the editor a hint its resources can resolve`
 * throws for `NONE` within the first iteration.
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

    /**
     * The presets whose interval is fixed, and therefore name no unit.
     *
     * Written out one by one rather than derived from `editableInterval`, because the point of the test
     * is that this list is the intended answer: a preset quietly becoming editable, or quietly growing a
     * label it does not use, has to fail here rather than agree with itself.
     */
    @Test
    fun `the presets that fix their own interval name no unit`() {
        val fixedRate = setOf(
            RepeatPreset.NONE,
            RepeatPreset.DAILY,
            RepeatPreset.WEEKLY,
            RepeatPreset.BIWEEKLY,
            RepeatPreset.MONTHLY,
            RepeatPreset.YEARLY
        )
        assertEquals(
            "these are the presets the editor used to crash on",
            fixedRate,
            RepeatPreset.values().filterTo(HashSet()) { !it.editableInterval }
        )
        fixedRate.forEach { preset ->
            assertNull("${preset.name} has no number to label", hintOf(preset))
        }
    }

    /** The counted presets, by name, with the words each one is meant to show. */
    @Test
    fun `the presets that ask for a number say what the number counts`() {
        val counted = setOf(
            RepeatPreset.EVERY_N_DAYS,
            RepeatPreset.EVERY_N_WEEKS,
            RepeatPreset.WEEKDAYS,
            RepeatPreset.EVERY_N_MONTHS,
            RepeatPreset.EVERY_N_YEARS
        )
        assertEquals(
            "and these are the only ones that ask",
            counted,
            RepeatPreset.values().filterTo(HashSet()) { it.editableInterval }
        )
        assertEquals(
            "Every N days counts days",
            context.getString(R.string.repeat_unit_days),
            hintOf(RepeatPreset.EVERY_N_DAYS)
        )
        assertEquals(
            "Every N weeks and chosen weekdays both count weeks",
            context.getString(R.string.repeat_unit_weeks),
            hintOf(RepeatPreset.EVERY_N_WEEKS)
        )
        assertEquals(context.getString(R.string.repeat_unit_weeks), hintOf(RepeatPreset.WEEKDAYS))
        assertEquals(
            "Every N months counts months",
            context.getString(R.string.repeat_unit_months),
            hintOf(RepeatPreset.EVERY_N_MONTHS)
        )
        assertEquals(
            "Every N years counts years",
            context.getString(R.string.repeat_unit_years),
            hintOf(RepeatPreset.EVERY_N_YEARS)
        )
        counted.forEach { preset ->
            assertTrue("${preset.name} is labelled with words", hintOf(preset)?.isNotBlank() == true)
        }
    }

    /**
     * The crash canary: a unit label is either absent or a real resource id.
     *
     * The nullable type forbids forgetting one; this forbids inventing a nonsense one. `0` is the value
     * that used to stand for "none", and it is exactly what `getString` cannot resolve.
     */
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

    /** No repeat means no unit of time, not a unit of time left blank. */
    @Test
    fun `a task that does not repeat carries no unit and no unit label`() {
        assertNull(RepeatPreset.NONE.unit)
        assertNull(RepeatPreset.NONE.unitLabelRes)
        assertNull(hintOf(RepeatPreset.NONE))
        assertEquals(
            "and it is still named to the user in words",
            context.getString(R.string.repeat_none),
            context.getString(RepeatPreset.NONE.labelRes)
        )
    }

    /** The dropdown and the series badge must read as sentences, never as enum names. */
    @Test
    fun `every preset is named to the user in words and not by its enum name`() {
        RepeatPreset.values().forEach { preset ->
            val label = context.getString(preset.labelRes)
            val short = context.getString(preset.shortLabelRes)
            assertTrue("${preset.name}: $label", label.isNotBlank() && label != preset.name)
            assertTrue("${preset.name}: $short", short.isNotBlank() && short != preset.name)
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

    /**
     * The fixed-rate presets keep their own interval whatever the typed field holds — including
     * `MONTHLY`, which hides the interval field while showing the day-of-month one.
     */
    @Test
    fun `a preset with no unit label still resolves its interval from itself`() {
        assertEquals(1, RepeatPreset.NONE.intervalOf(7))
        assertEquals(1, RepeatPreset.DAILY.intervalOf(7))
        assertEquals(1, RepeatPreset.WEEKLY.intervalOf(7))
        assertEquals(2, RepeatPreset.BIWEEKLY.intervalOf(7))
        assertEquals(1, RepeatPreset.MONTHLY.intervalOf(7))
        assertEquals(1, RepeatPreset.YEARLY.intervalOf(7))
        assertEquals(7, RepeatPreset.EVERY_N_DAYS.intervalOf(7))
        assertEquals(1, RepeatPreset.EVERY_N_DAYS.intervalOf(0))
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
}
