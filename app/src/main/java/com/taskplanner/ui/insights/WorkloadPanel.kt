package com.taskplanner.ui.insights

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.taskplanner.R
import com.taskplanner.data.insight.DayLoad
import com.taskplanner.data.insight.WorkloadRead
import com.taskplanner.databinding.ItemWorkloadPanelBinding
import com.taskplanner.databinding.ItemWorkloadDayBinding
import com.taskplanner.ui.components.ChartLegendView
import com.taskplanner.ui.util.WorkTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The overload panel: what the days ahead are asked for, against the days that were measured.
 *
 * Everything here is printed from the [WorkloadRead] the screen holds open, which is the same object the
 * heavy-day rule is handed — so a day the panel draws as the busiest is the day an insight can name, stated
 * with the same minutes. The panel adds no figure of its own: it formats, it does not add up.
 *
 * Four rules, and §8 is behind three of them:
 *
 *  * **An estimate is called an estimate.** When any day's minutes came from a deadline rather than from hours
 *    the reader typed, the panel says the hours are approximate; when some rows state no length at all, it
 *    says how many, because those tasks are in the count of the work and in none of the minutes.
 *  * **A comparison needs a base.** The daily average is printed only over days the profile was actually
 *    measured on, and a profile with no measured work is told that plainly rather than compared against zero.
 *  * **The window is named by the read, not by the wording.** The number of days is derived from the bounds
 *    the repository was asked for, so a screen cannot claim a fortnight while holding fifteen days.
 *  * **Nothing here moves work.** §8 forbids automatic rescheduling and priority changes, so the panel's only
 *    action is to take the reader to a day and leave the decision there.
 */
object WorkloadPanel {

    /**
     * Draws the window the readings were taken against.
     *
     * [onOpenDay] is the fragment's, not this object's: deciding where a day goes is navigation, and the panel
     * only says which days it is holding. A null argument draws the same figures without press affordances,
     * which is how a panel that cannot take the reader anywhere admits it.
     */
    fun render(
        binding: ItemWorkloadPanelBinding,
        read: WorkloadRead,
        onOpenDay: ((Long) -> Unit)? = null
    ) {
        val context = binding.root.context
        binding.workloadWindow.text = windowDates(context, read)
        binding.workloadTotal.text = if (read.days.isEmpty()) {
            context.getString(R.string.workload_no_load, daysText(context, read))
        } else {
            context.getString(
                R.string.workload_total_format,
                WorkTimeFormat.compactMinutes(context, read.totalMinutes),
                daysText(context, read)
            )
        }
        val caveat = caveat(context, read)
        binding.workloadCaveat.isVisible = caveat != null
        binding.workloadCaveat.text = caveat

        renderPriorities(context, binding, read)
        binding.workloadBaseline.text = baseline(context, read)
        renderDays(context, binding, read, onOpenDay)
    }

    /**
     * The window the read was counted over, in the reader's own days.
     *
     * Both bounds are inclusive, so the span is their difference plus one — and the panel prints that number
     * rather than the word "fortnight", because the figure on screen has to be the one the query was bounded
     * by even if the horizon is ever retuned.
     */
    fun daysText(context: Context, read: WorkloadRead): String {
        val days = (read.toDay - read.fromDay + 1).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return context.resources.getQuantityString(R.plurals.insight_days_span, days, days)
    }

    /**
     * The §8 caveat, or null when every minute in the window was measured.
     *
     * The two halves are said together because they are two failures of the same figure: an estimated length
     * is a number the reader did not type, and a task with no length at all is work in the count of the day and
     * absent from its minutes. A panel that printed only the first would leave the second invisible — the total
     * would look complete while quietly omitting tasks.
     */
    fun caveat(context: Context, read: WorkloadRead): String? {
        val parts = listOfNotNull(
            if (read.approximate) context.getString(R.string.workload_approximate_note) else null,
            read.lengthUnknownTasks.takeIf { it > 0 }?.let {
                context.resources.getQuantityString(R.plurals.workload_length_unknown, it, it)
            }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(context.getString(R.string.workload_caveat_joiner))
    }

    /**
     * The pace behind the window, or the reason there is none.
     *
     * The average comes from the read rather than being recomputed here, and no measured days prints as the
     * absence it is: "0 min on a usual day" would invite the reader to compare the window against a figure that
     * was never observed.
     */
    fun baseline(context: Context, read: WorkloadRead): String = if (read.measuredDays == 0) {
        context.getString(R.string.workload_no_baseline)
    } else {
        context.getString(
            R.string.workload_baseline_format,
            WorkTimeFormat.compactMinutes(context, read.baselineDailyMinutes),
            context.resources.getQuantityString(
                R.plurals.insight_days_span,
                read.measuredDays,
                read.measuredDays
            )
        )
    }

    /**
     * The share of the busiest day this day holds, for the bar beside its row. Null means no bar.
     *
     * Days are compared with the busiest day of the window rather than with the baseline because the panel
     * shows both figures at once: a row measured against the day above it is a row the reader can check
     * without arithmetic, and a day with no minutes has no bar at all rather than an empty track that reads as
     * work that stopped.
     */
    fun fraction(day: DayLoad, heaviestMinutes: Int): Float? {
        if (heaviestMinutes <= 0 || day.minutes <= 0) return null
        return (day.minutes.toFloat() / heaviestMinutes.toFloat()).coerceIn(0f, 1f)
    }

    /** The row's own day in the reader's calendar: "Thu 4 Jun". */
    fun dayName(epochDay: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        .format(
            LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

    /** The date the window was counted from, for the heading that has to say which fortnight it means. */
    fun windowDates(context: Context, read: WorkloadRead): String {
        val format = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        return context.getString(
            R.string.workload_window_format,
            format.format(instantOf(read.fromDay)),
            format.format(instantOf(read.toDay))
        )
    }

    private fun instantOf(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun renderPriorities(context: Context, binding: ItemWorkloadPanelBinding, read: WorkloadRead) {
        val priorities = read.priorities
        binding.workloadPriority.isVisible = priorities.total > 0
        if (priorities.total == 0) {
            binding.workloadPriorityLegend.setEntries(emptyList())
            return
        }
        binding.workloadPriority.setSegments(
            listOf(priorities.high.toLong(), priorities.medium.toLong(), priorities.low.toLong()),
            intArrayOf(
                ContextCompat.getColor(context, R.color.priority_high),
                ContextCompat.getColor(context, R.color.priority_medium),
                ContextCompat.getColor(context, R.color.priority_low)
            )
        )
        // A zero count still gets its legend row: the bar above is drawn in three fixed colours, and a key that
        // lists only the priorities the window happens to hold would leave a reader matching an unlabelled
        // colour to a guess.
        binding.workloadPriorityLegend.setEntries(
            listOf(
                legendEntry(context, R.string.priority_high, priorities.high, R.color.priority_high),
                legendEntry(context, R.string.priority_medium, priorities.medium, R.color.priority_medium),
                legendEntry(context, R.string.priority_low, priorities.low, R.color.priority_low)
            )
        )
    }

    private fun legendEntry(context: Context, labelRes: Int, count: Int, colorRes: Int) =
        ChartLegendView.Entry(
            label = context.getString(labelRes),
            detail = context.resources.getQuantityString(R.plurals.insight_goal_tasks, count, count),
            color = ContextCompat.getColor(context, colorRes),
            // The split counts rows across the whole window, so it belongs to no single day: the legend is a
            // key here, and the only tap target the panel offers is a named day.
            onClick = null
        )

    private fun renderDays(
        context: Context,
        binding: ItemWorkloadPanelBinding,
        read: WorkloadRead,
        onOpenDay: ((Long) -> Unit)?
    ) {
        val container = binding.workloadDays
        container.removeAllViews()
        // A window with no rows is silent here, because the total above already says nothing is planned over
        // it; a second line saying the same thing would only make the reader wonder which of the two was true.
        val heaviest = read.heaviestDay?.minutes ?: 0
        read.days.forEach { day ->
            val row = ItemWorkloadDayBinding.inflate(LayoutInflater.from(context), container, false)
            row.dayName.text = dayName(day.epochDay)
            row.dayFigure.text = WorkTimeFormat.compactMinutes(context, day.minutes)
            row.dayTasks.text =
                context.resources.getQuantityString(R.plurals.insight_goal_tasks, day.tasks, day.tasks)
            row.dayApprox.isVisible = day.approximate
            // The badge follows the read's own heaviest day rather than a minutes comparison, so two days that
            // tie are not both called the busiest one.
            row.dayBusiest.isVisible = heaviest > 0 && read.heaviestDay?.epochDay == day.epochDay
            val fraction = fraction(day, heaviest)
            row.dayBar.isVisible = fraction != null
            if (fraction != null) {
                row.dayBar.setSegments(
                    listOf(day.minutes.toLong(), (heaviest - day.minutes).toLong()),
                    intArrayOf(
                        ContextCompat.getColor(context, R.color.chart_bar),
                        ContextCompat.getColor(context, R.color.chart_track)
                    )
                )
            }
            // A day row is a way to that day and nothing else: the panel reports what the plan asks for, and
            // moving work off a crowded day stays the reader's decision (§8).
            row.root.isClickable = onOpenDay != null
            row.root.isFocusable = onOpenDay != null
            onOpenDay?.let { open -> row.root.setOnClickListener { open(day.epochDay) } }
            container.addView(row.root, rowGap(context, container))
        }
    }

    /** The gap above a day row; the first row sits tight against the heading above it. */
    private fun rowGap(context: Context, container: LinearLayout): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = if (container.childCount == 0) {
                0
            } else {
                context.resources.getDimensionPixelSize(R.dimen.spacing_xs)
            }
        }
}
