package com.taskplanner.ui.calendar

import com.taskplanner.data.calendar.CalendarAggregator.DayBlock
import com.taskplanner.data.calendar.CalendarAggregator.MINUTES_PER_DAY

/**
 * Where the day view puts its blocks: which minute they sit at, how tall they may be drawn, and which
 * tasks have to share the width because they compete for the same minutes.
 *
 * Kept as arithmetic on minutes rather than as view code for the reason that decides whether the day
 * view can be trusted: a block's height is a claim about the user's time. A task the user gave a
 * start and an end owns the stretch between them; a task that only has a start, or only a deadline,
 * owns the single minute it points at and is drawn as a marker line, because the alternative — a
 * block of some default length — would put minutes in their day that they never planned.
 *
 * Collisions are answered by grouping, not by measured pixels. A [Cluster] is a run of blocks that
 * share time, and the column gives it one row split into as many lanes as the collisions need; the
 * lanes are then the layout's own weights. That is what makes the first drawing of a day correct: a
 * lane whose width had to be computed from the column's width could only be right after the column
 * had been measured once, which would show the user a stack of colliding tasks before it showed them
 * side by side.
 */
object CalendarDayLayout {

    /** A block's span for layout: a marker covers the one minute it announces. */
    fun upperOf(block: DayBlock): Int =
        (block.endMinute ?: block.startMinute + 1).coerceAtMost(MINUTES_PER_DAY)

    /**
     * One run of blocks that collide with each other, split into the lanes they need.
     *
     * [startMinute] and [endMinute] are the cluster's own edges on the day's minute line, so the
     * column can put the whole cluster where it belongs and leave the quiet stretches of the day
     * empty. Lanes are ordered left to right, and the earliest start is always in the leftmost one:
     * a 09:00 meeting is not pushed aside by a deadline someone typed later.
     */
    data class Cluster(
        val startMinute: Int,
        val endMinute: Int,
        val lanes: List<List<DayBlock>>
    ) {
        /** Every block of this cluster, in the order the column reads them. */
        val blocks: List<DayBlock> get() = lanes.flatten()
    }

    /**
     * The day's blocks gathered into clusters.
     *
     * Blocks are visited by start minute, so the greedy lane search always sees the earliest task
     * first, and a block that merely ends where the next one begins starts a new cluster: sharing a
     * boundary is a schedule, not a collision.
     */
    fun clusters(blocks: List<DayBlock>): List<Cluster> {
        val ordered = blocks.sortedWith(compareBy({ it.startMinute }, { -upperOf(it) }, { it.task.id }))
        val clusters = mutableListOf<MutableList<DayBlock>>()
        var clusterEnd = Int.MIN_VALUE
        ordered.forEach { block ->
            val open = clusters.lastOrNull()?.takeIf { block.startMinute < clusterEnd }
            if (open == null) {
                clusters.add(mutableListOf(block))
                clusterEnd = upperOf(block)
            } else {
                open.add(block)
                clusterEnd = maxOf(clusterEnd, upperOf(block))
            }
        }

        return clusters.map { cluster ->
            val lanes = mutableListOf<MutableList<DayBlock>>()
            val laneEnds = mutableListOf<Int>()
            cluster.forEach { block ->
                val free = laneEnds.indexOfFirst { it <= block.startMinute }
                if (free < 0) {
                    lanes.add(mutableListOf(block))
                    laneEnds.add(upperOf(block))
                } else {
                    lanes[free].add(block)
                    laneEnds[free] = upperOf(block)
                }
            }
            Cluster(
                startMinute = cluster.first().startMinute,
                endMinute = cluster.maxOf { upperOf(it) },
                lanes = lanes
            )
        }
    }

    /** The offset from the top of the column for a wall-clock minute, in a column whose hour is [hourHeightPx]. */
    fun minuteToPx(minute: Int, hourHeightPx: Int): Int = minute * hourHeightPx / 60

    /** How far below the top of its cluster a block begins. */
    fun offsetPx(block: DayBlock, cluster: Cluster, hourHeightPx: Int): Int =
        minuteToPx(block.startMinute - cluster.startMinute, hourHeightPx)

    /**
     * The height a block may be drawn at: the stretch the user described, and never a guess at one.
     *
     * A marker's height is zero because the marker view sizes itself to its own rule and label;
     * giving it a share of the hour would be inventing a length by the back door. One minute is the
     * floor for a real block, so a task that starts and ends in the same minute is still something
     * the eye can find.
     */
    fun heightPx(block: DayBlock, hourHeightPx: Int): Int =
        if (block.endMinute == null) {
            0
        } else {
            minuteToPx(upperOf(block) - block.startMinute, hourHeightPx)
                .coerceAtLeast(minuteToPx(1, hourHeightPx))
        }

    /** The whole column's height, the part of the day the gridlines label. */
    fun columnHeightPx(hourHeightPx: Int): Int = MINUTES_PER_DAY * hourHeightPx / 60
}
