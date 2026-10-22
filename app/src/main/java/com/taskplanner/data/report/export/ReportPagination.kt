package com.taskplanner.data.report.export

import com.taskplanner.ui.components.ChartGeometry
import kotlin.math.ceil

/**
 * One mark a page is made of.
 *
 * Three, because that is everything a report draws: a string of text, a straight rule, a filled rectangle. A
 * page model that needed a fourth would be a page model that had let a layout decision slip into the drawer —
 * which is the split this stage is built around: [ReportPagination] decides, the surface obeys.
 *
 * A [Text.baseline] is the typographic baseline rather than the top of the line, because that is the coordinate
 * a text engine is given. A [Rect] and a [Rule] are stored by their edges because a chart's marks are areas,
 * and an area whose bottom is a baseline is an area whose height nobody can check.
 */
sealed class ReportDraw {

    data class Text(
        val x: Float,
        val baseline: Float,
        val style: ReportStyle,
        val color: Int,
        val text: String
    ) : ReportDraw()

    data class Rule(
        val left: Float,
        val right: Float,
        val y: Float,
        val color: Int,
        val thickness: Float
    ) : ReportDraw()

    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val color: Int
    ) : ReportDraw()
}

/**
 * One page of a report: the marks on it, and the number it is.
 *
 * The coordinates are absolute points on the sheet from its top-left, which is what lets a test ask "did
 * anything fall off the page" as arithmetic against [ReportPageGeometry] rather than as a picture somebody has
 * to squint at.
 */
data class ReportPage(
    val number: Int,
    val total: Int,
    val draws: List<ReportDraw>
)

/** A report laid out into pages. */
data class PagedReport(val pages: List<ReportPage>, val geometry: ReportPageGeometry) {

    val pageCount: Int get() = pages.size

    /** Every mark of every page, for a reader that only wants to know whether anything was drawn at all. */
    val draws: List<ReportDraw> get() = pages.flatMap { it.draws }
}

/**
 * A document of blocks turned into pages.
 *
 * This is where a report becomes a physical thing, and every promise the stage asks of a PDF is made here: that
 * text stays inside the sheet, that a heading is never the last word on a page, that a table which continues
 * tells the reader what its columns were again, that a row of the reader's own words is not cut in half between
 * two sheets, and that the pages count themselves.
 *
 * Four rules hold it together, because a layout without rules is a loop that produces different paper every
 * run:
 *
 *  * **Everything is measured, nothing is assumed.** A line takes the room its text needs at its own size,
 *    through [ReportMeasurer], so a heading in Cyrillic and a heading in Latin are asked the same question
 *    rather than one of them guessed at.
 *  * **A break is taken only where breaking costs nothing.** A paragraph ends wherever the paper runs out; a
 *    heading, a label and its figure, and a chart do not. A table row is never split unless it is the only
 *    thing that could be — a row taller than a whole sheet is cut between its own lines rather than drawn past
 *    the bottom, which is the last resort and still shows the reader every word of it.
 *  * **A page is never left standing empty.** A break happens only when there is something to put after it, so
 *    a document that ends with a heading gains no second sheet just because the heading asked for company.
 *  * **Numbers come last.** "3 of 7" cannot be known while the third page is being filled, so the footers and
 *    the running header are stamped over the finished pages rather than guessed at as each one began.
 */
object ReportPagination {

    /** The height a bar plot asks for. A request, not a promise: a page that cannot pay shrinks it. */
    private const val PLOT_HEIGHT = 110f

    /** The smallest plot worth drawing; below it the bars are a texture rather than a comparison. */
    private const val MINIMUM_PLOT_HEIGHT = 40f

    private const val SHARE_BAR_HEIGHT = 16f
    private const val CHIP_SIZE = 9f

    /** The room an x label asks for beyond its own width before two of them count as colliding. */
    private const val LABEL_GAP = 8f

    private const val CELL_PADDING = 3f
    private const val VALUE_GAP = 12f
    private const val AXIS_HEIGHT = 10f
    private const val X_LABEL_HEIGHT = 12f
    private const val HAIRLINE = 0.7f
    private const val ELLIPSIS = "…"

    /**
     * The pages [document] needs on [geometry], measured by [measurer], inked with [palette].
     *
     * There is always at least one page: a report asked for a period nobody worked in is still a document a
     * reader opened, and an empty sheet that says so is honest paper rather than a file that will not open.
     */
    fun paginate(
        document: ReportDocument,
        geometry: ReportPageGeometry = ReportPageGeometry.A4_PORTRAIT,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): PagedReport {
        val groups = document.blocks
            .flatMap { flow(it, geometry, measurer, palette) }
            .flatMap(::groupsOf)
        val packer = Packer(geometry)
        packer.pack(groups)
        val placed = packer.finish()
        return PagedReport(
            pages = placed.mapIndexed { index, draws ->
                ReportPage(
                    number = index + 1,
                    total = placed.size,
                    draws = draws + stamps(document.title, index + 1, placed.size, geometry, measurer, palette)
                )
            },
            geometry = geometry
        )
    }

    // --- blocks to flow -------------------------------------------------------------------------------

    /**
     * One block as the rows it needs.
     *
     * A list rather than one flow because a [ReportBlock.Title] that wraps to two lines and a paragraph of
     * twenty are the same kind of thing here — text that has been measured — while the decision about which of
     * those rows may be separated is made once, by the `atomic` flag of the flow that produced them.
     */
    private fun flow(
        block: ReportBlock,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): List<Flow> = when (block) {
        is ReportBlock.Title ->
            textFlow(block.text, ReportStyle.DocumentTitle, geometry, measurer, palette.accent).atomic(keepWithNext = true)

        is ReportBlock.MetaLine ->
            textFlow(block.text, ReportStyle.MetaLine, geometry, measurer, palette.muted).atomic()

        is ReportBlock.Section ->
            textFlow(block.text, ReportStyle.SectionHeading, geometry, measurer, palette.accent).atomic(keepWithNext = true)

        is ReportBlock.Paragraph ->
            listOf(textFlow(block.text, ReportStyle.Paragraph, geometry, measurer, palette.ink))

        is ReportBlock.Note ->
            listOf(textFlow(block.text, ReportStyle.Note, geometry, measurer, palette.muted))

        is ReportBlock.KeyValue -> listOf(keyValueFlow(block, geometry, measurer, palette))
        is ReportBlock.Table -> listOf(tableFlow(block, geometry, measurer, palette))
        is ReportBlock.Chart -> listOf(chartFlow(block.chart, geometry, measurer, palette))
    }

    private fun textFlow(
        text: String,
        style: ReportStyle,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        color: Int
    ): Flow {
        val spec = ReportTypography.spec(style)
        return Flow(
            header = emptyList(),
            rows = ReportWrapping.wrap(text, measurer, style, geometry.contentWidth).map { line ->
                Row(spec.leading, listOf(ReportDraw.Text(geometry.marginLeft, baseline(spec), style, color, line)))
            },
            spaceBefore = spec.spaceBefore,
            spaceAfter = spec.spaceAfter,
            atomic = false
        )
    }

    private fun Flow.atomic(keepWithNext: Boolean = false): List<Flow> =
        listOf(copy(atomic = true, keepWithNext = keepWithNext))

    /**
     * A label and its figure, kept as one thing.
     *
     * The pair never breaks between the label and the number: a heading at the foot of a sheet with its figure
     * on the next is a summary nobody can read. When the two will not share a line, the figure is wrapped under
     * its label rather than allowed to run past the edge of the paper.
     */
    private fun keyValueFlow(
        block: ReportBlock.KeyValue,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): Flow {
        val labelSpec = ReportTypography.spec(ReportStyle.KeyValueLabel)
        val valueSpec = ReportTypography.spec(ReportStyle.KeyValueValue)
        val right = geometry.width - geometry.marginRight
        val labelLines = ReportWrapping.wrap(block.label, measurer, ReportStyle.KeyValueLabel, geometry.contentWidth)
        val valueLines = ReportWrapping.wrap(block.value, measurer, ReportStyle.KeyValueValue, geometry.contentWidth)
        val together = labelLines.size == 1 && valueLines.size == 1 &&
            measurer.width(block.label, ReportStyle.KeyValueLabel) + VALUE_GAP +
                measurer.width(block.value, ReportStyle.KeyValueValue) <= geometry.contentWidth

        val rows = ArrayList<Row>()
        if (together) {
            rows += Row(
                height = maxOf(labelSpec.leading, valueSpec.leading),
                draws = listOf(
                    ReportDraw.Text(
                        geometry.marginLeft, baseline(labelSpec), ReportStyle.KeyValueLabel, palette.ink, block.label
                    ),
                    ReportDraw.Text(
                        right - measurer.width(block.value, ReportStyle.KeyValueValue),
                        baseline(valueSpec),
                        ReportStyle.KeyValueValue,
                        palette.ink,
                        block.value
                    )
                )
            )
        } else {
            labelLines.forEach { line ->
                rows += Row(
                    labelSpec.leading,
                    listOf(
                        ReportDraw.Text(
                            geometry.marginLeft,
                            baseline(labelSpec),
                            ReportStyle.KeyValueLabel,
                            palette.muted,
                            line
                        )
                    )
                )
            }
            valueLines.forEach { line ->
                rows += Row(
                    valueSpec.leading,
                    listOf(
                        ReportDraw.Text(
                            right - measurer.width(line, ReportStyle.KeyValueValue),
                            baseline(valueSpec),
                            ReportStyle.KeyValueValue,
                            palette.ink,
                            line
                        )
                    )
                )
            }
        }
        return Flow(
            header = emptyList(),
            rows = rows,
            spaceBefore = labelSpec.spaceBefore,
            spaceAfter = labelSpec.spaceAfter,
            atomic = true
        )
    }

    private fun tableFlow(
        block: ReportBlock.Table,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): Flow {
        val boxes = columnBoxes(block.columns, geometry)
        val headerSpec = ReportTypography.spec(ReportStyle.TableHeader)
        val cellSpec = ReportTypography.spec(ReportStyle.TableCell)

        val header = block.columns.mapIndexed { index, column ->
            cellRow(column.title, ReportStyle.TableHeader, index, boxes, block.columns, measurer, palette.accent)
        } + Row(
            height = ReportTypography.TABLE_HEADER_GAP,
            draws = listOf(
                ReportDraw.Rule(
                    geometry.marginLeft,
                    geometry.width - geometry.marginRight,
                    ReportTypography.TABLE_HEADER_GAP / 2f,
                    palette.hairline,
                    HAIRLINE
                )
            )
        )
        val rows = block.rows.map { cells ->
            Row(
                height = cellDepths(cells, boxes, measurer).coerceAtLeast(1) * cellSpec.leading,
                draws = cellDraws(cells, boxes, block.columns, measurer, palette.ink)
            )
        }
        return Flow(
            header = header,
            rows = rows,
            spaceBefore = headerSpec.spaceBefore,
            spaceAfter = cellSpec.spaceAfter + 4f,
            atomic = false
        )
    }

    /** The boxes a table's columns get, normalised so a careless set of weights cannot widen the page. */
    private fun columnBoxes(columns: List<ReportColumn>, geometry: ReportPageGeometry): List<Pair<Float, Float>> {
        if (columns.isEmpty()) return emptyList()
        val weights = columns.map { it.weight.coerceAtLeast(0.01f) }
        val total = weights.sum()
        val boxes = ArrayList<Pair<Float, Float>>(columns.size)
        var cursor = geometry.marginLeft
        weights.forEach { weight ->
            val width = geometry.contentWidth * (weight / total)
            boxes += cursor to cursor + width
            cursor += width
        }
        return boxes
    }

    private fun cellRow(
        text: String,
        style: ReportStyle,
        index: Int,
        boxes: List<Pair<Float, Float>>,
        columns: List<ReportColumn>,
        measurer: ReportMeasurer,
        color: Int
    ): Row {
        val spec = ReportTypography.spec(style)
        val width = boxes.getOrNull(index)?.let { it.second - it.first } ?: 0f
        val lines = ReportWrapping.wrap(text, measurer, style, width)
        val (left, right) = boxes.getOrElse(index) { 0f to 0f }
        val alignEnd = columns.getOrNull(index)?.align == ReportAlignment.END
        return Row(
            height = maxOf(1, lines.size) * spec.leading,
            draws = lines.mapIndexed { lineIndex, line ->
                ReportDraw.Text(
                    x = if (alignEnd) right - measurer.width(line, style) else left + CELL_PADDING,
                    baseline = lineIndex * spec.leading + baseline(spec),
                    style = style,
                    color = color,
                    text = line
                )
            }
        )
    }

    private fun cellDepths(
        cells: List<String>,
        boxes: List<Pair<Float, Float>>,
        measurer: ReportMeasurer
    ): Int {
        val style = ReportStyle.TableCell
        return cells.mapIndexed { index, cell ->
            val width = boxes.getOrNull(index)?.let { it.second - it.first } ?: 0f
            ReportWrapping.wrap(cell, measurer, style, width).size
        }.maxOfOrNull { it } ?: 1
    }

    private fun cellDraws(
        cells: List<String>,
        boxes: List<Pair<Float, Float>>,
        columns: List<ReportColumn>,
        measurer: ReportMeasurer,
        color: Int
    ): List<ReportDraw> {
        val spec = ReportTypography.spec(ReportStyle.TableCell)
        val draws = ArrayList<ReportDraw>()
        cells.forEachIndexed { index, cell ->
            if (index >= boxes.size) return@forEachIndexed
            val lines = ReportWrapping.wrap(cell, measurer, ReportStyle.TableCell, boxes[index].second - boxes[index].first)
            val (left, right) = boxes[index]
            val alignEnd = columns.getOrNull(index)?.align == ReportAlignment.END
            lines.forEachIndexed { lineIndex, line ->
                draws += ReportDraw.Text(
                    x = if (alignEnd) right - measurer.width(line, ReportStyle.TableCell) else left + CELL_PADDING,
                    baseline = lineIndex * spec.leading + baseline(spec),
                    style = ReportStyle.TableCell,
                    color = color,
                    text = line
                )
            }
        }
        return draws
    }

    /**
     * A chart, as the rows it takes and the marks they carry.
     *
     * The plot is the only part of a report asked to change size for the page it landed on: a table cannot be
     * half a table and a paragraph can always keep talking, but a bar chart drawn three points shorter is still
     * the same chart, because [ChartGeometry.bars] normalises against the tallest value rather than against the
     * box. A chart given less than [MINIMUM_PLOT_HEIGHT] loses its picture and keeps its words, which is the
     * rule about not drawing a chart the data cannot support applied to paper rather than to a query.
     */
    private fun chartFlow(
        chart: ReportChart,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): Flow = when (chart) {
        is ReportChart.Bars -> {
            val values = chart.columns.map { it.value.coerceAtLeast(0L) }
            Flow(
                header = emptyList(),
                rows = barsRows(chart, values, PLOT_HEIGHT, geometry, measurer, palette),
                spaceBefore = ReportTypography.spec(ReportStyle.ChartTitle).spaceBefore,
                spaceAfter = 8f,
                atomic = true,
                shrinkTo = { room ->
                    barsRows(chart, values, plotFitting(room, geometry, measurer, palette, chart), geometry, measurer, palette)
                }
            )
        }

        is ReportChart.Shares -> {
            val rows = sharesRows(chart, geometry, measurer, palette)
            Flow(
                // The bar is what a continuation page has to be given again, or the swatches under it are
                // colours without a total to be colours of.
                header = rows.take(2),
                rows = rows.drop(2),
                spaceBefore = ReportTypography.spec(ReportStyle.ChartTitle).spaceBefore,
                spaceAfter = 8f,
                atomic = false
            )
        }
    }

    private fun barsRows(
        chart: ReportChart.Bars,
        values: List<Long>,
        plotHeight: Float,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): List<Row> {
        val rows = ArrayList<Row>()
        rows += headingRow(chart.title, geometry, measurer, palette)
        rows += Row(
            height = plotHeight + AXIS_HEIGHT + X_LABEL_HEIGHT,
            draws = if (plotHeight < MINIMUM_PLOT_HEIGHT) textRowDraws(chart.unit, geometry, palette)
            else plotDraws(chart, values, plotHeight, geometry, measurer, palette)
        )
        chart.caption?.let { rows += captionRows(it, geometry, measurer, palette) }
        return rows
    }

    private fun plotDraws(
        chart: ReportChart.Bars,
        values: List<Long>,
        plotHeight: Float,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): List<ReportDraw> {
        val draws = ArrayList<ReportDraw>()
        val spec = ReportTypography.spec(ReportStyle.ChartLabel)
        val slot = if (values.isEmpty()) 0f else geometry.contentWidth / values.size
        if (slot > 0f) {
            ChartGeometry.bars(
                values = values,
                plotLeft = 0f,
                plotTop = 0f,
                plotWidth = geometry.contentWidth,
                plotHeight = plotHeight
            ).forEach { bar ->
                if (bar.bounds.isEmpty) return@forEach
                draws += ReportDraw.Rect(
                    geometry.marginLeft + bar.bounds.left,
                    bar.bounds.top,
                    geometry.marginLeft + bar.bounds.right,
                    bar.bounds.bottom,
                    chart.color
                )
            }
        }
        draws += ReportDraw.Rule(
            geometry.marginLeft,
            geometry.width - geometry.marginRight,
            plotHeight,
            palette.hairline,
            HAIRLINE
        )
        draws += ReportDraw.Text(
            geometry.marginLeft,
            spec.spaceBefore + baseline(spec),
            ReportStyle.ChartLabel,
            palette.muted,
            chart.unit
        )
        if (slot > 0f) {
            val widest = chart.columns.mapNotNull { it.label }
                .maxOfOrNull { measurer.width(it, ReportStyle.ChartLabel) } ?: 0f
            val step = maxOf(1, ceil((widest + LABEL_GAP) / slot).toInt())
            chart.columns.forEachIndexed { index, column ->
                val label = column.label ?: return@forEachIndexed
                // Only every step-th date is named. Which ones are chosen from the width the longest of them
                // needs against the room a column has, so no two labels can overlap and none is clipped: a
                // year's report has 365 of them, and a smear of dates is not an axis.
                if (index % step != 0) return@forEachIndexed
                val width = measurer.width(label, ReportStyle.ChartLabel)
                val center = geometry.marginLeft + index * slot + slot / 2f
                draws += ReportDraw.Text(
                    x = (center - width / 2f).coerceIn(
                        geometry.marginLeft,
                        (geometry.width - geometry.marginRight - width).coerceAtLeast(geometry.marginLeft)
                    ),
                    baseline = plotHeight + AXIS_HEIGHT + baseline(spec),
                    style = ReportStyle.ChartLabel,
                    color = palette.muted,
                    text = label
                )
            }
        }
        return draws
    }

    private fun sharesRows(
        chart: ReportChart.Shares,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): List<Row> {
        val rows = ArrayList<Row>()
        rows += headingRow(chart.title, geometry, measurer, palette)
        val segments = ChartGeometry.segments(
            values = chart.parts.map { it.value },
            top = 0f,
            width = geometry.contentWidth,
            height = SHARE_BAR_HEIGHT
        )
        rows += Row(
            height = SHARE_BAR_HEIGHT + 6f,
            draws = segments.map { segment ->
                ReportDraw.Rect(
                    geometry.marginLeft + segment.bounds.left,
                    segment.bounds.top,
                    geometry.marginLeft + segment.bounds.right,
                    segment.bounds.bottom,
                    chart.parts[segment.index].color
                )
            }
        )
        val spec = ReportTypography.spec(ReportStyle.ChartLabel)
        val right = geometry.width - geometry.marginRight
        chart.parts.forEach { part ->
            val value = "${part.value} ${chart.unit}"
            rows += Row(
                height = spec.leading,
                draws = listOf(
                    ReportDraw.Rect(
                        geometry.marginLeft,
                        (spec.leading - CHIP_SIZE) / 2f,
                        geometry.marginLeft + CHIP_SIZE,
                        (spec.leading - CHIP_SIZE) / 2f + CHIP_SIZE,
                        part.color
                    ),
                    ReportDraw.Text(
                        geometry.marginLeft + CHIP_SIZE + 6f,
                        baseline(spec),
                        ReportStyle.ChartLabel,
                        palette.ink,
                        part.label
                    ),
                    ReportDraw.Text(
                        right - measurer.width(value, ReportStyle.ChartLabel),
                        baseline(spec),
                        ReportStyle.ChartLabel,
                        palette.ink,
                        value
                    )
                )
            )
        }
        chart.omitted?.let { rows += captionRows(it, geometry, measurer, palette) }
        return rows
    }

    private fun headingRow(
        title: String,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): Row {
        val spec = ReportTypography.spec(ReportStyle.ChartTitle)
        val lines = ReportWrapping.wrap(title, measurer, ReportStyle.ChartTitle, geometry.contentWidth)
        return Row(
            height = spec.spaceBefore + maxOf(1, lines.size) * spec.leading,
            draws = lines.mapIndexed { index, line ->
                ReportDraw.Text(
                    geometry.marginLeft,
                    spec.spaceBefore + index * spec.leading + baseline(spec),
                    ReportStyle.ChartTitle,
                    palette.ink,
                    line
                )
            }
        )
    }

    private fun captionRows(
        text: String,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): List<Row> =
        ReportWrapping.wrap(text, measurer, ReportStyle.ChartLabel, geometry.contentWidth).map { line ->
            Row(height = ReportTypography.spec(ReportStyle.ChartLabel).leading, draws = textRowDraws(line, geometry, palette, ReportStyle.ChartLabel))
        }

    private fun textRowDraws(
        text: String,
        geometry: ReportPageGeometry,
        palette: ReportPalette,
        style: ReportStyle = ReportStyle.ChartLabel
    ): List<ReportDraw> = listOf(
        ReportDraw.Text(
            geometry.marginLeft,
            baseline(ReportTypography.spec(style)),
            style,
            palette.muted,
            text
        )
    )

    /** The plot height the room a chart has been given can pay for, once its own fixed rows are counted. */
    private fun plotFitting(
        room: Float,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette,
        chart: ReportChart.Bars
    ): Float {
        val fixed = barsRows(chart, emptyList(), 0f, geometry, measurer, palette)
            .sumOf { it.height.toDouble() }
            .toFloat()
        return (room - fixed).coerceIn(0f, PLOT_HEIGHT)
    }

    private fun baseline(spec: TypeSpec): Float = spec.size + (spec.leading - spec.size) / 2f

    // --- flow to groups -------------------------------------------------------------------------------

    /**
     * A flow as the pieces a page break may fall between.
     *
     * [Flow.atomic] decides it: an atomic block is one group that moves as a whole, and everything else is one
     * group per row, each carrying the header it needs if it happens to start a page. Splitting the decision
     * out here is what keeps the packer below free of special cases — it sees only groups, and never has to ask
     * what kind of block one came from.
     */
    private fun groupsOf(flow: Flow): List<Group> {
        if (flow.rows.isEmpty()) return emptyList()
        return if (flow.atomic) {
            listOf(
                Group(
                    rows = flow.header + flow.rows,
                    spaceAbove = flow.spaceBefore,
                    spaceBelow = flow.spaceAfter,
                    keepWithNext = flow.keepWithNext,
                    shrinkTo = flow.shrinkTo
                )
            )
        } else {
            flow.rows.mapIndexed { index, row ->
                Group(
                    rows = if (index == 0) flow.header + row else listOf(row),
                    spaceAbove = if (index == 0) flow.spaceBefore else 0f,
                    spaceBelow = if (index == flow.rows.lastIndex) flow.spaceAfter else 0f,
                    keepWithNext = false,
                    shrinkTo = null
                )
            }
        }
    }

    // --- packing ---------------------------------------------------------------------------------------

    private class Packer(private val geometry: ReportPageGeometry) {

        private val finished = ArrayList<List<ReportDraw>>()
        private var current = ArrayList<ReportDraw>()
        private var y = geometry.contentTop

        fun pack(groups: List<Group>) {
            groups.forEachIndexed { index, group ->
                val next = groups.getOrNull(index + 1)
                if (group.keepWithNext && next != null && !isEmpty) {
                    // A heading that cannot be followed by so much as the first line of what it heads has been
                    // left to close a page. It travels instead, and the section begins whole.
                    val roomAfter = room() - gapFor(group) - group.height
                    if (roomAfter < next.height) breakPage()
                }
                place(group)
            }
        }

        private fun place(group: Group) {
            val room = room()
            val needed = gapFor(group) + group.height
            if (needed <= room) {
                emit(group.rows, gapFor(group))
                y += group.spaceBelow
                return
            }
            if (!isEmpty) {
                breakPage()
                place(group)
                return
            }
            // An empty page that still cannot hold this group: a chart is asked to give up plot height first,
            // because a shorter chart is still the same chart, and only then is anything cut at all.
            val shrunk = group.shrinkTo?.invoke(room)
            if (shrunk != null && shrunk.sumHeight() <= room) {
                emit(shrunk, 0f)
                y += group.spaceBelow
                return
            }
            spill(sliced(group.rows))
            y += group.spaceBelow
        }

        /**
         * The last resort: rows no page could take whole, placed one slice at a time.
         *
         * Nothing is dropped and nothing is drawn past the bottom of a sheet. A reader who gets a description
         * continued onto the next page has been given their own words; a reader whose words were clipped has
         * been given a shorter report, which is the failure this whole layout exists to avoid.
         */
        private fun spill(rows: List<Row>) {
            rows.forEach { row ->
                if (row.height > room() && !isEmpty) breakPage()
                emit(listOf(row), 0f)
            }
        }

        private fun sliced(rows: List<Row>): List<Row> =
            rows.flatMap { row -> sliceRow(row, geometry.contentHeight) }

        private fun sliceRow(row: Row, maxHeight: Float): List<Row> {
            if (row.height <= maxHeight || maxHeight <= 0f) return listOf(row)
            val pieces = ArrayList<Row>()
            var top = 0f
            while (top < row.height) {
                val bottom = (top + maxHeight).coerceAtMost(row.height)
                val draws = row.draws.mapNotNull { draw ->
                    when (draw) {
                        is ReportDraw.Text ->
                            if (draw.baseline >= top && draw.baseline < bottom)
                                draw.copy(baseline = draw.baseline - top) else null
                        is ReportDraw.Rule ->
                            if (draw.y >= top && draw.y < bottom) draw.copy(y = draw.y - top) else null
                        is ReportDraw.Rect ->
                            if (draw.bottom <= top || draw.top >= bottom) null
                            else draw.copy(top = maxOf(draw.top, top) - top, bottom = minOf(draw.bottom, bottom) - top)
                    }
                }
                pieces += Row(height = bottom - top, draws = draws)
                top = bottom
            }
            return pieces
        }

        private fun emit(rows: List<Row>, gap: Float) {
            y += gap
            rows.forEach { row ->
                val top = y
                row.draws.forEach { current += it.shiftedTo(top) }
                y += row.height
            }
        }

        private fun room(): Float = (geometry.contentBottom - y).coerceAtLeast(0f)

        private fun gapFor(group: Group): Float = if (isEmpty) 0f else group.spaceAbove

        private val isEmpty: Boolean get() = current.isEmpty()

        private fun breakPage() {
            if (isEmpty) return
            finished += current
            current = ArrayList()
            y = geometry.contentTop
        }

        fun finish(): List<List<ReportDraw>> {
            breakPage()
            if (finished.isEmpty()) finished += listOf(current)
            return finished
        }
    }

    /**
     * The page furniture: the number every sheet carries and the title the second and later ones repeat.
     *
     * The running header is the one line of this document allowed to be shortened, and it is shortened with the
     * app's own words rather than a reader's: the title is stated in full on the first page, so an ellipsis on
     * the third hides nothing that is not already on the sheet in the reader's hand.
     */
    private fun stamps(
        title: String,
        number: Int,
        total: Int,
        geometry: ReportPageGeometry,
        measurer: ReportMeasurer,
        palette: ReportPalette
    ): List<ReportDraw> {
        val draws = ArrayList<ReportDraw>()
        val footerSpec = ReportTypography.spec(ReportStyle.ChartLabel)
        val footer = if (total > 1) "$number / $total" else "$number"
        draws += ReportDraw.Text(
            x = geometry.width - geometry.marginRight - measurer.width(footer, ReportStyle.ChartLabel),
            baseline = geometry.height - geometry.marginBottom,
            style = ReportStyle.ChartLabel,
            color = palette.muted,
            text = footer
        )
        if (number > 1) {
            val headerSpec = ReportTypography.spec(ReportStyle.MetaLine)
            val width = measurer.width(title, ReportStyle.MetaLine)
            draws += ReportDraw.Text(
                x = geometry.marginLeft,
                baseline = geometry.marginTop - headerSpec.leading,
                style = ReportStyle.MetaLine,
                color = palette.muted,
                text = if (width <= geometry.contentWidth) title
                else shorten(title, geometry.contentWidth, ReportStyle.MetaLine, measurer)
            )
            draws += ReportDraw.Rule(
                geometry.marginLeft,
                geometry.width - geometry.marginRight,
                geometry.marginTop - headerSpec.leading / 2f,
                palette.hairline,
                HAIRLINE
            )
        }
        return draws
    }

    private fun shorten(text: String, maxWidth: Float, style: ReportStyle, measurer: ReportMeasurer): String {
        if (measurer.width(ELLIPSIS, style) > maxWidth) return ""
        var end = text.length
        while (end > 0 && measurer.width(text.take(end) + ELLIPSIS, style) > maxWidth) end--
        return text.take(end) + ELLIPSIS
    }

    private fun ReportDraw.shiftedTo(top: Float): ReportDraw = when (this) {
        is ReportDraw.Text -> copy(baseline = baseline + top)
        is ReportDraw.Rule -> copy(y = y + top)
        is ReportDraw.Rect -> copy(top = top + this.top, bottom = top + this.bottom)
    }

    private fun List<Row>.sumHeight(): Float = sumOf { it.height.toDouble() }.toFloat()

    /** One line of the flow: the marks it carries, relative to its own top, and the room it takes. */
    internal data class Row(val height: Float, val draws: List<ReportDraw>)

    /**
     * A block's rows, before the packer decides where they land.
     *
     * [header] is the part a continuation page is handed again — a table's column titles, a share chart's bar.
     * [atomic] says whether the rows may be separated from each other. [shrinkTo] is offered to a chart alone,
     * the one block whose own height is negotiable.
     */
    private data class Flow(
        val header: List<Row>,
        val rows: List<Row>,
        val spaceBefore: Float,
        val spaceAfter: Float,
        val atomic: Boolean,
        val keepWithNext: Boolean = false,
        val shrinkTo: ((Float) -> List<Row>)? = null
    )

    /** What the packer places: a set of rows that move together, with the air it asks for above and below. */
    private data class Group(
        val rows: List<Row>,
        val spaceAbove: Float,
        val spaceBelow: Float,
        val keepWithNext: Boolean,
        val shrinkTo: ((Float) -> List<Row>)?
    ) {
        val height: Float get() = rows.sumOf { it.height.toDouble() }.toFloat()
    }
}
