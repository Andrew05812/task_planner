package com.taskplanner.ui.tasks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one question `TaskDetailFragment.showStoredCategory` answers before it writes the category field: is
 * this write still allowed?
 *
 * The field is written from two Room flows that nothing orders — the task and the list of categories — so the
 * answer that arrives second is the one that has to fill the field, and a later list change must not. The
 * first version of that rule was a single flag, and it was wrong in the direction that costs the reader most:
 * while the flag was still false, a categories emission that arrived *after* the reader had started choosing
 * overwrote their choice with the stored name. So the rule is stated as a function of what the field holds,
 * and measured here rather than argued in a comment.
 */
class TaskEditorCategoryRestoreTest {

    private val stored = "Диссертация"

    @Test
    fun `an empty field takes the stored category`() {
        assertTrue(categoryMayBeRestored("", stored, alreadyShown = false))
        assertTrue(categoryMayBeRestored(null, stored, alreadyShown = false))
    }

    @Test
    fun `a field the reader has started filling is theirs, not the flows'`() {
        // The case the flag alone missed: nothing has been shown yet, the list arrives late, and the word on
        // the field is one the reader typed or picked seconds ago.
        assertFalse(categoryMayBeRestored("Глава 3", stored, alreadyShown = false))
    }

    @Test
    fun `a field already holding the stored name needs no second write`() {
        assertFalse(categoryMayBeRestored(stored, stored, alreadyShown = true))
    }

    @Test
    fun `the first write happens once, and only once, per task`() {
        assertTrue(categoryMayBeRestored("", stored, alreadyShown = false))
        assertFalse(categoryMayBeRestored("", stored, alreadyShown = true))
    }
}
