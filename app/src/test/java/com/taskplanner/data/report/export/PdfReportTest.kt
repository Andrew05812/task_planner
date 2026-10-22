package com.taskplanner.data.report.export

import com.taskplanner.testing.ReportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer that turns a page of marks into a sheet of paper.
 *
 * [PdfReport] is the thin half of the PDF, and this file is the proof that it is thin: nothing here decides
 * where a line goes or which page it belongs to, so the assertions are about translation — a role of type
 * [ReportStyle] becoming a size and a weight, a rectangle staying inside its plot, a sheet being begun and
 * ended once each. What is *not* tested here is the bytes of a PDF, and cannot be: the platform's document
 * writer is a native renderer, and under Robolectric it accepts every drawing call and hands back a file of no
 * bytes at all. That half is checked on a handset — see `PdfReportInstrumentedTest` — and the pages below are
 * checked against the sheet they were measured for.
 */
class PdfReportTest {

    private val measurer = ReportFixtures.FixedMeasurer()
    private val palette = ReportPalette(
        ink = 0xFF1C1B1F.toInt(),
        accent = 0xFF6750A4.toInt(),
        muted = 0xFF757575.toInt(),
        finished = 0xFF4CAF50.toInt(),
        hairline = 0x33757575
    )

    private fun draw(blocks: List<ReportBlock>, geometry: ReportPageGeometry = ReportPageGeometry.A4_PORTRAIT) =
        ReportFixtures.RecordingSurface().also { surface ->
            PdfReport.draw(
                ReportPagination.paginate(
                    document = ReportDocument("Task Planner report", emptyList(), blocks),
                    geometry = geometry,
                    measurer = measurer,
                    palette = palette
                ),
                surface
            )
        }

    @Test
    fun `every laid out page is drawn on exactly one sheet`() {
        val blocks = List(14) { index -> ReportBlock.Paragraph("строка ${"x".repeat(70)} $index") }
        val paged = ReportPagination.paginate(
            ReportDocument("Task Planner report", emptyList(), blocks),
            ReportPageGeometry(width = 300, height = 400, footerHeight = 20f),
            measurer,
            palette
        )
        val surface = ReportFixtures.RecordingSurface()
        PdfReport.draw(paged, surface)
        assertEquals(paged.pageCount, surface.sheets.size)
        surface.sheets.forEach { sheet ->
            assertEquals(300, sheet.width)
            assertEquals(400, sheet.height)
        }
    }

    @Test
    fun `a role of the typography is drawn at the size that role asks for`() {
        val surface = draw(
            listOf(
                ReportBlock.Title("Отчёт Task Planner"),
                ReportBlock.Section("Сводка"),
                ReportBlock.Paragraph("Семь задач закрыто, двенадцать часов измерено.")
            )
        )
        val drawn = surface.sheets.first().text.associateBy({ it.text }, { it.sizePt to it.bold })
        assertEquals(ReportTypography.spec(ReportStyle.DocumentTitle).size, drawn["Отчёт Task Planner"]!!.first)
        assertTrue("a title is drawn heavier than body text", drawn["Отчёт Task Planner"]!!.second)
        assertTrue("a section heading is drawn heavier than body text", drawn["Сводка"]!!.second)
        val body = drawn.entries.first { it.key.startsWith("Семь") }
        assertEquals(ReportTypography.spec(ReportStyle.Paragraph).size, body.value.first)
        assertEquals("body text is not bold", false, body.value.second)
    }

    @Test
    fun `nothing is drawn past the edge of the sheet it was given`() {
        val surface = draw(
            listOf(
                ReportBlock.Title("Отчёт"),
                ReportBlock.Table(
                    columns = listOf(ReportColumn("Категория", 3f), ReportColumn("Секунды", 1f, ReportAlignment.END)),
                    rows = (1..200).map { listOf("Категория с длинным названием $it", "$it") }
                )
            ),
            geometry = ReportPageGeometry(width = 400, height = 600, footerHeight = 20f)
        )
        assertTrue(surface.sheets.size > 1)
        surface.sheets.forEach { sheet ->
            sheet.text.forEach { text ->
                assertTrue(
                    "'${text.text}' sits below the sheet",
                    text.baseline <= sheet.height + 0.5f && text.baseline >= 0f
                )
                assertTrue("text starts off the left edge", text.x >= 0f)
            }
            sheet.rects.forEach { rect ->
                assertTrue(rect.left >= 0f && rect.right <= sheet.width + 0.5f)
                assertTrue(rect.top >= 0f && rect.bottom <= sheet.height + 0.5f)
            }
        }
    }

    @Test
    fun `an empty stretch of text is never handed to the printer`() {
        val surface = draw(
            listOf(
                ReportBlock.Paragraph("   "),
                ReportBlock.Paragraph("что-то есть"),
                ReportBlock.MetaLine("")
            )
        )
        assertTrue(surface.allText.none { it.isBlank() })
    }

    @Test
    fun `a bar chart's marks stay inside the sheet and its rule is drawn once`() {
        val surface = draw(
            listOf(
                ReportBlock.Chart(
                    ReportChart.Bars(
                        title = "Работа по дням",
                        unit = "секунды в день",
                        columns = (1..31).map { BarColumn("06-%02d".format(it), it * 60L) },
                        color = palette.accent,
                        caption = "Самый насыщенный день — 3 h 6 min."
                    )
                )
            )
        )
        val sheet = surface.sheets.first()
        assertTrue("a chart of thirty-one days should draw more than one bar", sheet.rects.size > 1)
        assertTrue(sheet.rects.all { it.left >= 0f && it.right <= sheet.width && it.bottom <= sheet.height })
        assertEquals(1, sheet.rules.size)
    }

    @Test
    fun `a page that keeps its words when the plot will not fit is still drawn`() {
        // A sheet barely taller than the chart's own title and caption: the plot is the part that gives way, and
        // the words the reader was promised are not.
        val surface = draw(
            listOf(
                ReportBlock.Paragraph("заполнение ${"y".repeat(60)}"),
                ReportBlock.Chart(
                    ReportChart.Bars(
                        title = "Работа по дням",
                        unit = "секунды в день",
                        columns = (1..31).map { BarColumn(null, it * 60L) },
                        color = palette.accent,
                        caption = "Самый насыщенный день — 3 h 6 min."
                    )
                )
            ),
            geometry = ReportPageGeometry(width = 300, height = 260, marginTop = 10f, marginBottom = 6f, footerHeight = 10f)
        )
        assertTrue(
            "the chart's title should still have been drawn",
            surface.allText.any { it.contains("Работа по дням") }
        )
        assertTrue(
            "the caption should still have been drawn",
            surface.allText.any { it.contains("Самый насыщенный") }
        )
    }
}
