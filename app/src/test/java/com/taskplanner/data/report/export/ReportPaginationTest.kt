package com.taskplanner.data.report.export

import com.taskplanner.testing.ReportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a report's sentences land on paper.
 *
 * The pages here are deliberately small — 200 by 300 points, which is about an eighth of the A4 sheet a real
 * report is printed on — because a page break is only interesting when it happens, and a test that has to
 * overflow a whole sheet of A4 to reach one is a test that overflows a whole sheet of A4. The promises are the
 * same at either size: nothing outside the sheet, no lonely heading, a header that comes again, a row that is
 * not cut unless it has to be.
 *
 * The ruler is [ReportFixtures.FixedMeasurer] and the sheet is
 * [ReportFixtures.RecordingSurface], so every assertion below is about a coordinate or a word rather than
 * about a picture.
 */
class ReportPaginationTest {

    private val measurer = ReportFixtures.FixedMeasurer()
    private val palette = ReportPalette(
        ink = 0xFF1C1B1F.toInt(),
        accent = 0xFF6750A4.toInt(),
        muted = 0xFF757575.toInt(),
        finished = 0xFF4CAF50.toInt(),
        hairline = 0x33757575
    )

    private val sheet = ReportPageGeometry(
        width = 200,
        height = 300,
        marginLeft = 20f,
        marginRight = 20f,
        marginTop = 20f,
        marginBottom = 20f,
        footerHeight = 20f
    )

    private fun paginate(vararg blocks: ReportBlock, page: ReportPageGeometry = sheet): PagedReport =
        ReportPagination.paginate(
            document = ReportDocument(title = "Task Planner report", meta = emptyList(), blocks = blocks.toList()),
            geometry = page,
            measurer = measurer,
            palette = palette
        )

    private fun sentence(text: String) = ReportBlock.Paragraph(text)

    @Test
    fun `a report with nothing in it is still a page`() {
        val pages = paginate()
        assertEquals(1, pages.pageCount)
        assertEquals(1, pages.pages.first().number)
    }

    @Test
    fun `every page numbers itself and knows how many there are`() {
        val pages = paginate(*Array(12) { index -> sentence("row $index " + "x".repeat(60)) })
        assertTrue("the document should need more than one sheet", pages.pageCount > 1)
        pages.pages.forEachIndexed { index, page ->
            assertEquals(index + 1, page.number)
            assertEquals(pages.pageCount, page.total)
        }
        pages.pages.forEach { page ->
            val footers = page.draws.filterIsInstance<ReportDraw.Text>().map { it.text }
            assertTrue(
                "page ${page.number} printed no number: ${footers.takeLast(3)}",
                footers.contains("${page.number} / ${page.total}")
            )
        }
    }

    @Test
    fun `nothing is drawn past the bottom of a sheet`() {
        val pages = paginate(
            ReportBlock.Title("Отчёт"),
            sentence("Длинная фраза, которая переносится на следующую строку и на следующую страницу."),
            ReportBlock.Table(
                columns = listOf(ReportColumn("Категория", 3f), ReportColumn("Секунды", 1f, ReportAlignment.END)),
                rows = (1..40).map { index -> listOf("Категория $index", "$index") }
            )
        )
        pages.pages.forEach { page ->
            page.draws.forEach { draw ->
                when (draw) {
                    is ReportDraw.Text -> assertTrue(
                        "text '${draw.text}' sits at ${draw.baseline} on page ${page.number}",
                        draw.baseline <= sheet.height - sheet.marginBottom + 1f
                    )
                    is ReportDraw.Rect -> assertTrue(draw.bottom <= sheet.height - sheet.marginBottom + 1f)
                    is ReportDraw.Rule -> assertTrue(draw.y <= sheet.height - sheet.marginBottom + 1f)
                }
            }
        }
    }

    @Test
    fun `nothing is drawn outside the side margins`() {
        val pages = paginate(
            sentence("Оченьдлинноесловобезпробеловкотороенелезитвколонку"),
            ReportBlock.Table(
                columns = listOf(ReportColumn("Очень длинное название колонки", 1f), ReportColumn("Фигура", 1f)),
                rows = listOf(listOf("название категории, которое тоже длинное", "12"))
            )
        )
        pages.pages.forEach { page ->
            page.draws.filterIsInstance<ReportDraw.Text>().forEach { text ->
                val width = measurer.width(text.text, text.style)
                assertTrue(
                    "'${text.text}' runs to ${text.x + width}",
                    text.x >= sheet.marginLeft - 0.5f && text.x + width <= sheet.width - sheet.marginRight + 0.5f
                )
            }
        }
    }

    @Test
    fun `a heading is never the last thing on a sheet`() {
        val blocks = ArrayList<ReportBlock>()
        repeat(4) { blocks += sentence("строка ${"y".repeat(80)}") }
        blocks += ReportBlock.Section("Достижения")
        blocks += sentence("Десять достижений были earned.")
        val pages = paginate(*blocks.toTypedArray())

        pages.pages.forEach { page ->
            val texts = page.draws.filterIsInstance<ReportDraw.Text>().map { it.text }
            // A heading that closed a page would be the last word of it, and a reader who turns the sheet
            // finds a section with nothing under it.
            if (texts.contains("Достижения")) {
                assertTrue(
                    "the section heading closed a page",
                    texts.lastIndex > texts.indexOf("Достижения")
                )
            }
        }
    }

    @Test
    fun `a table that continues prints its column titles again`() {
        val pages = paginate(
            ReportBlock.Table(
                columns = listOf(ReportColumn("День", 2f), ReportColumn("Задачи", 1f, ReportAlignment.END)),
                rows = (1..30).map { index -> listOf("2026-06-0$index", "$index") }
            )
        )
        assertTrue(pages.pageCount > 1)
        pages.pages.forEach { page ->
            val texts = page.draws.filterIsInstance<ReportDraw.Text>().map { it.text }
            assertTrue("page ${page.number} lost the column titles", texts.contains("День"))
            assertTrue("page ${page.number} lost the second column title", texts.contains("Задачи"))
        }
    }

    @Test
    fun `a row is not cut in half between two sheets`() {
        val pages = paginate(
            ReportBlock.Table(
                columns = listOf(ReportColumn("Описание", 1f)),
                rows = listOf(
                    listOf("первая"),
                    listOf("вторая строка, которая переносится дважды или трижды на этом узком листе"),
                    listOf("третья")
                )
            )
        )
        val sheetsWithSecond = pages.pages.filter { page ->
            page.draws.filterIsInstance<ReportDraw.Text>().any { it.text.startsWith("вторая") }
        }
        assertEquals(
            "the long row was split between sheets",
            1,
            sheetsWithSecond.size
        )
    }

    @Test
    fun `a row taller than a sheet is sliced rather than lost`() {
        val long = (1..300).joinToString(" ") { "слово$it" }
        val pages = paginate(
            ReportBlock.Table(
                columns = listOf(ReportColumn("Описание", 1f)),
                rows = listOf(listOf(long))
            )
        )
        val words = pages.pages
            .flatMap { page -> page.draws.filterIsInstance<ReportDraw.Text>().map { it.text } }
            .flatMap { line -> line.split(" ") }
            .filter { it.startsWith("слово") }
        // Every word the reader wrote arrives somewhere on the sheet, in the order it was written.
        assertTrue("the row should have needed more than one sheet", pages.pageCount > 1)
        assertEquals((1..300).map { "слово$it" }, words)
    }

    @Test
    fun `a paragraph may end wherever the paper runs out`() {
        val pages = paginate(sentence((1..400).joinToString(" ") { "w$it" }))
        assertTrue(pages.pageCount > 1)
        val first = pages.pages.first().draws.filterIsInstance<ReportDraw.Text>().map { it.text }
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `a chart given a page too short for its plot keeps its words`() {
        val short = ReportPageGeometry(
            width = 300,
            height = 260,
            marginTop = 10f,
            marginBottom = 6f,
            footerHeight = 10f
        )
        val chart = ReportBlock.Chart(
            ReportChart.Bars(
                title = "Work measured, day by day",
                unit = "measured seconds per day",
                columns = (1..40).map { BarColumn("06-0$it", it * 600L) },
                color = palette.accent,
                caption = "The busiest day measured 6 h 40 min."
            )
        )
        val pages = paginate(ReportBlock.Title("Отчёт"), chart, page = short)
        val texts = pages.pages.flatMap { page -> page.draws.filterIsInstance<ReportDraw.Text>().map { it.text } }
        assertTrue("the chart's title should have survived", texts.any { it.contains("Work measured") })
        assertTrue("the caption should have survived", texts.any { it.contains("busiest") })
        pages.pages.forEach { page ->
            page.draws.filterIsInstance<ReportDraw.Rect>().forEach { rect ->
                assertTrue("a bar ran past the sheet", rect.bottom <= short.height + 0.5f)
            }
        }
    }

    @Test
    fun `the second sheet carries the report title in its header`() {
        val pages = paginate(*Array(10) { index -> sentence("row ${"q".repeat(90)} $index") })
        assertTrue(pages.pageCount > 1)
        pages.pages.drop(1).forEach { page ->
            assertTrue(
                "page ${page.number} has no running title",
                page.draws.filterIsInstance<ReportDraw.Text>().any { it.text == "Task Planner report" }
            )
        }
        assertTrue(
            "the first sheet should not repeat a title it already printed in full",
            pages.pages.first().draws.filterIsInstance<ReportDraw.Text>().none { it.text == "Task Planner report" }
        )
    }

    @Test
    fun `no sheet is left standing empty`() {
        val blocks = ArrayList<ReportBlock>()
        repeat(20) { blocks += sentence("строка ${"w".repeat(70)}") }
        blocks += ReportBlock.Section("Последний раздел")
        val pages = paginate(*blocks.toTypedArray())
        pages.pages.forEach { page ->
            assertTrue("page ${page.number} holds nothing", page.draws.size > 1)
        }
    }

    @Test
    fun `the order the reader's words arrived in is the order they are printed in`() {
        val pages = paginate(
            *Array(30) { index -> sentence("метка-$index ${"a".repeat(50)}") }
        )
        val marks = pages.pages
            .flatMap { page -> page.draws.filterIsInstance<ReportDraw.Text>().map { it.text } }
            .filter { it.startsWith("метка-") }
            .map { it.substringBefore(" ") }
        assertEquals((0..29).map { "метка-$it" }, marks)
    }

    @Test
    fun `a key value pair keeps its figure with its label`() {
        val pages = paginate(
            *Array(8) { index -> sentence("строка ${"e".repeat(70)} $index") },
            ReportBlock.KeyValue("Работа измерена", "6 h 20 min"),
            sentence("после")
        )
        val holder = pages.pages.first { page ->
            page.draws.filterIsInstance<ReportDraw.Text>().any { it.text == "Работа измерена" }
        }
        assertTrue(
            "the label arrived without its figure",
            holder.draws.filterIsInstance<ReportDraw.Text>().any { it.text == "6 h 20 min" }
        )
    }

    @Test
    fun `a share chart's parts travel with the bar they belong to`() {
        val pages = paginate(
            *Array(8) { index -> sentence("строка ${"e".repeat(70)} $index") },
            ReportBlock.Chart(
                ReportChart.Shares(
                    title = "Where the measured time went",
                    total = 1_000L,
                    unit = "seconds",
                    parts = (1..8).map { ChartPart("Категория $it", 100L, palette.accent) },
                    omitted = "3 more categories hold 1 h between them."
                )
            ),
            page = ReportPageGeometry(width = 400, height = 700, footerHeight = 20f)
        )
        pages.pages.forEach { page ->
            val texts = page.draws.filterIsInstance<ReportDraw.Text>().map { it.text }
            val hasParts = texts.any { it.startsWith("Категория") }
            if (hasParts) {
                assertTrue(
                    "page ${page.number} lists parts under no title",
                    texts.any { it.contains("Where the measured time went") }
                )
            }
        }
    }
}
