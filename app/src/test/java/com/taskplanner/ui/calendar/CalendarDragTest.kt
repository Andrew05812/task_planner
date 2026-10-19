package com.taskplanner.ui.calendar

import android.content.ClipData
import android.content.ClipDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a drag carries between two day cells, and what a drop is allowed to read out of it.
 *
 * The clip is the only thing the drop side is given, so these are the boundary cases: the id this
 * screen started a drag with has to survive the round trip, and anything that did not come from a
 * calendar chip — a clip from another app, a selection with two rows in it, text somebody copied —
 * has to come back as "not a task". A drop that guessed at an id could move an appointment on the
 * strength of a number the user never meant to drag.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarDragTest {

    @Test
    fun `a drag carries exactly the task it started on`() {
        val clip = CalendarDrag.clipOf(42L)

        assertEquals("one item, so a drop cannot take the wrong half of a selection", 1, clip.itemCount)
        assertEquals(42L, CalendarDrag.taskIdOf(clip))
        assertEquals(
            "and nothing but a plain text type leaves this app",
            1,
            clip.description.mimeTypeCount
        )
        assertTrue(
            "a drop cannot be offered a type this screen never started",
            clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
        )
        assertEquals(
            "the id travels as text, because everything else about the task is read back from the database",
            "42",
            clip.getItemAt(0).text.toString()
        )
    }

    @Test
    fun `a clip that is not a task id is not a drop`() {
        val note = ClipData.newPlainText("note", "Buy milk")
        val urls = ClipData(
            "urls",
            arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST),
            ClipData.Item("taskplanner://task/1")
        )
        val twoRows = ClipData(
            "two",
            arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN),
            ClipData.Item("7")
        ).apply { addItem(ClipData.Item("8")) }
        val copiedDigits = ClipData.newPlainText("copied", " order 12 ")
        val negative = ClipData.newPlainText("negative", "-1")

        assertNull(CalendarDrag.taskIdOf(null))
        assertNull(CalendarDrag.taskIdOf(note))
        assertNull(
            "a uri clip could name a task and still not be one the user dragged",
            CalendarDrag.taskIdOf(urls)
        )
        assertNull(
            "a drag that carries more than one row is not the move of one task",
            CalendarDrag.taskIdOf(twoRows)
        )
        assertNull(
            "digits inside copied text are not a row id",
            CalendarDrag.taskIdOf(copiedDigits)
        )
        assertNull(
            "and no row of a table is negative, so that clip is not one this app started",
            CalendarDrag.taskIdOf(negative)
        )
    }
}
