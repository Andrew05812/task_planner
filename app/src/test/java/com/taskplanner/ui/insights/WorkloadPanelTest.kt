package com.taskplanner.ui.insights

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.insight.DayLoad
import com.taskplanner.data.insight.PriorityLoad
import com.taskplanner.data.insight.WorkloadRead
import com.taskplanner.databinding.ItemWorkloadPanelBinding
import com.taskplanner.ui.components.StackedBarView
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The overload panel: what the days ahead are asked for, and what the screen is allowed to say about it.
 *
 * [WorkloadPanel] owns one thing — the difference between the figures a read carries and the sentences a reader
 * sees — so every check here is about a claim the panel would otherwise be making up. §8 gives the shape of all
 * of them:
 *
 *  * **An estimate is admitted as an estimate.** A total built from deadline estimates has to say so, and a task
 *    that states no length has to be counted even though it contributes no minutes; the two are printed together
 *    because they are two halves of one total that is not what it looks like.
 *  * **A baseline is only a comparison when it was measured.** "0 min on a usual working day" would invite the
 *    reader to weigh a fortnight against a figure nobody observed.
 *  * **The panel reports and does not move work.** A day row is a way to that day, and a panel that cannot take
 *    the reader anywhere draws the same figures without the press affordances rather than pretending.
 *
 * The arithmetic is not re-derived here — [com.taskplanner.data.insight.WorkloadMath] is tested over its own
 * rows — but the *choosing* is: which day is called busiest when two tie, which day gets no bar, which line a
 * quiet window gets, and which fortnight the panel claims to be describing.
 *
 * Dates are asserted as structure rather than as spelled-out text: the panel prints them through the device's
 * own medium and weekday formats, and this suite pins a German locale precisely so that no test may hardcode a
 * US spelling. Where a date string has to be compared, the expected value is built from the same formatter and
 * the check is about which bound went where.
 */
@RunWith(RobolectricTestRunner::class)
class WorkloadPanelTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var themed: Context

    private val wednesday = LocalDate.of(2026, 6, 3)
    private val thursday = LocalDate.of(2026, 6, 4)
    private val friday = LocalDate.of(2026, 6, 5)

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        val app: Context = ApplicationProvider.getApplicationContext()
        // The panel is always inflated by the list inside the activity's theme; without it the Material card
        // style and the ?attr/textAppearance* references do not resolve, which is not how it is used.
        themed = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `the window the panel names is the window the read was counted over`() {
        val read = workload(listOf(DayLoad(thursday.toEpochDay(), 60, 1)))

        assertEquals(
            "fifteen days, because the span is the read's own bounds counted inclusive rather than the word " +
                "the panel would rather print",
            "15 days",
            WorkloadPanel.daysText(themed, read)
        )
        assertEquals(
            "a one-day read says one day, and the plural of fifteen would name a window nobody counted",
            "1 day",
            WorkloadPanel.daysText(themed, read.copy(toDay = read.fromDay))
        )

        val window = WorkloadPanel.windowDates(themed, read)
        val bounds = listOf(read.fromDay, read.toDay).map(::mediumDate)
        assertEquals(
            "the heading states both bounds the figures were cut by, oldest first, in the reader's calendar",
            themed.getString(R.string.workload_window_format, bounds[0], bounds[1]),
            window
        )
        assertNotEquals(
            "and a heading that swapped them would be a different, wrong sentence",
            themed.getString(R.string.workload_window_format, bounds[1], bounds[0]),
            window
        )
    }

    @Test
    fun `a total built from estimates says it is built from estimates`() {
        val stated = workload(listOf(DayLoad(thursday.toEpochDay(), 120, 2)))
        assertNull(
            "minutes the reader typed are a measurement, and an apology for guesses would be the panel " +
                "hedging figures that are not its own to hedge",
            WorkloadPanel.caveat(themed, stated)
        )
        assertFalse("and the line is hidden, not merely empty", panel(stated).workloadCaveat.isVisible)

        assertEquals(
            "one estimated deadline among stated blocks is enough, because the panel states one total",
            "Some of these lengths come from estimates, so the hours are approximate.",
            WorkloadPanel.caveat(
                themed,
                workload(listOf(DayLoad(thursday.toEpochDay(), 120, 2, approximate = true)))
            )
        )
        assertEquals(
            "a task with no length is work in the count of the day and absent from its minutes; a panel that " +
                "printed only the estimate note would leave it invisible",
            "1 task states no length at all.",
            WorkloadPanel.caveat(themed, workload(listOf(DayLoad(thursday.toEpochDay(), 120, 2)), lengthUnknownTasks = 1))
        )
        assertEquals(
            "and the two failures of one figure are said together",
            "Some of these lengths come from estimates, so the hours are approximate. " +
                "3 tasks state no length at all.",
            WorkloadPanel.caveat(
                themed,
                workload(
                    listOf(DayLoad(thursday.toEpochDay(), 120, 2, approximate = true)),
                    lengthUnknownTasks = 3
                )
            )
        )
        assertTrue(
            "the panel prints the sentence it computed",
            panel(
                workload(
                    listOf(DayLoad(thursday.toEpochDay(), 120, 2, approximate = true)),
                    lengthUnknownTasks = 3
                )
            ).workloadCaveat.isVisible
        )
    }

    @Test
    fun `a window with nothing planned is admitted instead of shown as zero`() {
        val empty = panel(workload(emptyList()))

        assertEquals(
            "the quiet window says what it is quiet over, so the reader knows which fortnight is meant",
            "Nothing is planned over the 15 days.",
            empty.workloadTotal.text.toString()
        )
        assertEquals("and no rows are drawn for days that hold nothing", 0, empty.workloadDays.childCount)

        assertEquals(
            "the same line, once there is work, carries the figure and the span it was summed over",
            "2 h 45 min across 15 days",
            panel(workload(listOf(DayLoad(thursday.toEpochDay(), 165, 3)))).workloadTotal.text.toString()
        )
    }

    @Test
    fun `a baseline is a comparison only when the days behind it were measured`() {
        assertEquals(
            "'0 min on a usual working day' would be a figure inviting the reader to weigh the fortnight " +
                "against nothing",
            "No measured work behind these days, so nothing here is a comparison.",
            WorkloadPanel.baseline(themed, workload(listOf(DayLoad(thursday.toEpochDay(), 120, 2))))
        )

        val measured = workload(
            listOf(DayLoad(thursday.toEpochDay(), 120, 2)),
            baselineDailyMinutes = 90,
            measuredDays = 12
        )
        assertEquals(
            "the pace, then how many days it was taken over, because an average of two days is not an " +
                "average of thirty",
            "1 h 30 min on a usual working day, measured over 12 days.",
            WorkloadPanel.baseline(themed, measured)
        )
        assertEquals(
            "and one measured day stays singular, so the reader is not told thirty days were watched",
            "1 h 30 min on a usual working day, measured over 1 day.",
            WorkloadPanel.baseline(themed, measured.copy(measuredDays = 1))
        )
    }

    @Test
    fun `the busiest day is called busiest once, even when two days tie`() {
        val tying = workload(
            listOf(
                DayLoad(thursday.toEpochDay(), 120, 2),
                DayLoad(friday.toEpochDay(), 120, 2),
                DayLoad(saturday().toEpochDay(), 30, 1)
            )
        )
        val binding = panel(tying)

        assertEquals("three days hold work, so three rows are drawn", 3, binding.workloadDays.childCount)
        // The read's own heaviest day is the first of the two that tie, and the panel follows the read rather
        // than comparing minutes of its own, so one window is never called two busiest days.
        val badged = (0 until binding.workloadDays.childCount)
            .filter { row(binding, it).findViewById<View>(R.id.dayBusiest).isVisible }
        assertEquals(
            "exactly one row carries the badge, and it is the row the read itself named",
            listOf(0),
            badged
        )
        assertEquals(thursday.toEpochDay(), tying.heaviestDay?.epochDay)

        val quiet = panel(workload(listOf(DayLoad(thursday.toEpochDay(), 0, 2))))
        assertFalse(
            "a window of days holding tasks but no minutes has no busiest day to point at",
            row(quiet, 0).findViewById<View>(R.id.dayBusiest).isVisible
        )
    }

    @Test
    fun `a day's bar is that day against the busiest one, and a day with no minutes has no bar`() {
        assertEquals(0.5f, WorkloadPanel.fraction(DayLoad(thursday.toEpochDay(), 60, 1), 120)!!, TOLERANCE)
        assertEquals(1.0f, WorkloadPanel.fraction(DayLoad(thursday.toEpochDay(), 120, 1), 120)!!, TOLERANCE)
        assertNull(
            "a day counted at zero minutes gets no bar: an empty track reads as work that stopped, and this " +
                "day was never started",
            WorkloadPanel.fraction(DayLoad(thursday.toEpochDay(), 0, 1), 120)
        )
        assertNull(
            "and with no busiest day in the window there is nothing for a bar to be measured against",
            WorkloadPanel.fraction(DayLoad(thursday.toEpochDay(), 40, 1), 0)
        )

        val binding = panel(
            workload(
                listOf(
                    DayLoad(thursday.toEpochDay(), 120, 2),
                    DayLoad(friday.toEpochDay(), 0, 1)
                )
            )
        )
        assertTrue(row(binding, 0).findViewById<View>(R.id.dayBar).isVisible)
        assertFalse(row(binding, 1).findViewById<View>(R.id.dayBar).isVisible)
        assertEquals(
            "the heaviest day's bar is its own minutes and no gap beside them",
            listOf(120L, 0L),
            rowView<StackedBarView>(binding, 0, R.id.dayBar).currentValues
        )
    }

    @Test
    fun `the priority key explains a bar the reader cannot label`() {
        val binding = panel(
            workload(
                listOf(DayLoad(thursday.toEpochDay(), 120, 3)),
                priorities = PriorityLoad(high = 3, medium = 1, low = 0)
            )
        )
        val legend = binding.workloadPriorityLegend.entries

        assertEquals(
            "the bar is drawn in the order the key reads it",
            listOf(3L, 1L, 0L),
            binding.workloadPriority.currentValues
        )
        assertEquals(
            listOf("High", "Medium", "Low"),
            legend.map { it.label }
        )
        assertEquals(
            "a priority the window does not hold still gets its row, or the reader is left matching an " +
                "unlabelled colour to a guess",
            listOf("3 tasks", "1 task", "0 tasks"),
            legend.map { it.detail }
        )
        assertEquals(
            intArrayOf(
                ContextCompat.getColor(themed, R.color.priority_high),
                ContextCompat.getColor(themed, R.color.priority_medium),
                ContextCompat.getColor(themed, R.color.priority_low)
            ).toList(),
            binding.workloadPriority.currentColors.toList()
        )
        legend.forEach {
            assertNull(
                "the split belongs to the whole window, so no legend row may pretend to open a day",
                it.onClick
            )
        }

        val empty = panel(workload(emptyList()))
        assertFalse(
            "and a bar with nothing in it is not drawn beside a key for nothing",
            empty.workloadPriority.isVisible
        )
        assertEquals(0, empty.workloadPriorityLegend.entries.size)
    }

    @Test
    fun `a day row is a way to that day and nothing else`() {
        val opened = mutableListOf<Long>()
        val navigable = panel(
            workload(
                listOf(DayLoad(thursday.toEpochDay(), 120, 2), DayLoad(friday.toEpochDay(), 60, 1))
            ),
            onOpenDay = { opened.add(it) }
        )

        assertTrue(row(navigable, 0).isClickable)
        assertTrue("and it can be reached without a finger", row(navigable, 0).isFocusable)
        row(navigable, 1).performClick()
        assertEquals("the tap carries the day the row was drawn for", listOf(friday.toEpochDay()), opened)

        val stranded = panel(workload(listOf(DayLoad(thursday.toEpochDay(), 120, 2))), onOpenDay = null)
        assertFalse(
            "a panel with nowhere to send the reader draws the same figures without the press affordances, " +
                "rather than a row that looks tappable and answers with nothing",
            row(stranded, 0).isClickable
        )
        assertFalse(row(stranded, 0).hasOnClickListeners())
    }

    @Test
    fun `a row says which of its figures are minutes and which are a count`() {
        val binding = panel(
            workload(
                listOf(
                    DayLoad(thursday.toEpochDay(), 165, 3, approximate = true),
                    DayLoad(friday.toEpochDay(), 30, 1)
                )
            )
        )

        assertEquals("2 h 45 min", rowView<TextView>(binding, 0, R.id.dayFigure).text.toString())
        assertEquals("30 min", rowView<TextView>(binding, 1, R.id.dayFigure).text.toString())
        assertEquals("3 tasks", rowView<TextView>(binding, 0, R.id.dayTasks).text.toString())
        assertEquals("1 task", rowView<TextView>(binding, 1, R.id.dayTasks).text.toString())
        assertTrue(
            "the estimate marker belongs to the day whose minutes are guesses, not to the window above it",
            rowView<TextView>(binding, 0, R.id.dayApprox).isVisible
        )
        assertFalse(rowView<TextView>(binding, 1, R.id.dayApprox).isVisible)

        val names = (0 until binding.workloadDays.childCount)
            .map { rowView<TextView>(binding, it, R.id.dayName).text.toString() }
        assertEquals(
            "every row names its own day, in the reader's calendar rather than as a number",
            listOf(WorkloadPanel.dayName(thursday.toEpochDay()), WorkloadPanel.dayName(friday.toEpochDay())),
            names
        )
        assertNotEquals("and two rows never say the same day", names[0], names[1])
        assertTrue(
            "the day-of-month the row stands for is in its own heading",
            names[0].contains("4") && names[1].contains("5")
        )
    }

    private fun panel(read: WorkloadRead, onOpenDay: ((Long) -> Unit)? = { }): ItemWorkloadPanelBinding =
        ItemWorkloadPanelBinding.inflate(LayoutInflater.from(themed)).also {
            WorkloadPanel.render(it, read, onOpenDay)
        }

    private fun row(binding: ItemWorkloadPanelBinding, index: Int): View =
        binding.workloadDays.getChildAt(index)

    private fun <T : View> rowView(binding: ItemWorkloadPanelBinding, index: Int, id: Int): T =
        row(binding, index).findViewById(id)

    /** A fortnight's read, the way the screen gets one, with only the figures a test cares about set. */
    private fun workload(
        days: List<DayLoad>,
        priorities: PriorityLoad = PriorityLoad(),
        lengthUnknownTasks: Int = 0,
        baselineDailyMinutes: Int = 0,
        measuredDays: Int = 0
    ): WorkloadRead = WorkloadRead(
        fromDay = wednesday.toEpochDay(),
        toDay = wednesday.plusDays(14).toEpochDay(),
        days = days,
        priorities = priorities,
        lengthUnknownTasks = lengthUnknownTasks,
        baselineDailyMinutes = baselineDailyMinutes,
        measuredDays = measuredDays
    )

    private fun mediumDate(epochDay: Long): String = DateFormat
        .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        .format(
            LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

    /** The day after Friday, for a third row that is neither of the two the tests single out. */
    private fun saturday(): LocalDate = friday.plusDays(1)

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
