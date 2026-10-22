package com.taskplanner.data.report.export

import com.taskplanner.testing.ReportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrapping every page of a report is built out of.
 *
 * The measurer here is [ReportFixtures.FixedMeasurer]: a ruler that says every character is half its size
 * wide, so a test can state a width and know exactly how many letters fit behind it. That is the point of the
 * seam rather than a trick around it — the arithmetic below runs the same way on a handset, where the ruler is
 * the platform's own and the numbers are the ones the glyphs actually have.
 */
class ReportTextTest {

    private val measurer = ReportFixtures.FixedMeasurer()

    /** Body text is 10.5 pt with a 14.5 pt leading, so one character is 5.25 pt wide. */
    private val char = ReportTypography.spec(ReportStyle.Paragraph).size * 0.5f

    private fun wrap(text: String, width: Float, style: ReportStyle = ReportStyle.Paragraph): List<String> =
        ReportWrapping.wrap(text, measurer, style, width)

    @Test
    fun `a line of words is broken where it stops fitting`() {
        val lines = wrap("alpha beta gamma delta", char * 12)
        assertEquals(2, lines.size)
        assertEquals("alpha beta", lines.first())
        assertEquals("gamma delta", lines.last())
    }

    @Test
    fun `a word longer than the column is broken rather than lost`() {
        val long = "категория"
        val lines = wrap(long, char * 4)
        assertEquals(listOf("кате", "гори", "я"), lines)
        assertEquals(long, lines.joinToString(""))
    }

    @Test
    fun `a line break the writer asked for is kept`() {
        val lines = wrap("первая строка\nвторая", char * 200)
        assertEquals(listOf("первая строка", "вторая"), lines)
    }

    @Test
    fun `cyrillic and diacritics are measured like any other letters`() {
        val cyrillic = wrap("ёжик", char * 2)
        assertEquals(listOf("ёж", "ик"), cyrillic)
    }

    @Test
    fun `a blank block takes no line at all`() {
        assertTrue(wrap("   ", char * 100).isEmpty())
        assertTrue(wrap("", char * 100).isEmpty())
    }

    @Test
    fun `a break swallows the space between the words it split`() {
        val lines = wrap("один два", char * 4)
        assertEquals(listOf("один", "два"), lines)
    }

    @Test
    fun `a character wider than the line still gets a line`() {
        // The pathological measure is one that no string can fit, not even one letter: the answer is a line per
        // letter rather than a page that has stopped showing the reader their own title.
        val lines = wrap("ав", char * 0.5f)
        assertEquals(listOf("а", "в"), lines)
    }

    @Test
    fun `a tab is a place to break and not a gap that widens the row`() {
        assertEquals(listOf("раз", "два"), wrap("раз\tдва", char * 3))
    }

    @Test
    fun `nothing is asked for a width that does not exist`() {
        assertTrue(wrap("что-то", 0f).isEmpty())
        assertTrue(wrap("что-то", -1f).isEmpty())
    }
}
