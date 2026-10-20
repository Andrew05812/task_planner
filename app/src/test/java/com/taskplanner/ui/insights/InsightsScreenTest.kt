package com.taskplanner.ui.insights

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.insight.DayLoad
import com.taskplanner.data.insight.PriorityLoad
import com.taskplanner.data.insight.WorkloadRead
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightFeedback
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The list the reader actually scrolls, built from rows the repository really wrote.
 *
 * [InsightsAdapter] is the seam between the notebook and the screen, and the failures worth guarding are the
 * ones a reader would take as the app lying about their own profile:
 *
  *  * **A card is its row's words.** The title, body and basis are the sentences the pass that raised the
 *    reading wrote, drawn verbatim; a card that reformatted them would print yesterday's reading in today's
 *    phrasing, which is the exact thing storing the text was meant to prevent. The rows below come from a real
 *    pass over real storage, so the text asserted here is text no test wrote.
 *  * **Drawing a card writes nothing.** The tick a reader sees follows the row, not the finger, so binding a
 *    hundred cards must not record a hundred answers — and a tap on an answer chip must reach the repository
 *    with the row that was tapped and the answer that chip says.
 *  * **A card only promises a way to go when it has one**, in the terms the row stores rather than the terms
 *    the screen would prefer.
 *  * **The panel is part of the list, not a second window above it.** It is the first row, it is drawn from
 *    the same read the cards were derived against, and its day rows are navigation only — §8 forbids the app
 *    moving work on its own, so a panel that could not take the reader anywhere still reports the figures
 *    without pretending to act on them.
 */
@RunWith(RobolectricTestRunner::class)
class InsightsScreenTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var themed: Context
    private lateinit var fixture: AppFixture
    private lateinit var parent: ViewGroup

    private val opened = mutableListOf<InsightTarget>()
    private val answered = mutableListOf<Pair<String, InsightFeedback>>()
    private val openedDays = mutableListOf<Long>()

    private val zone: ZoneId = CalendarMath.systemZone()
    private val wednesday = LocalDate.of(2026, 6, 3)
    private var userId: Long = -1L

    private val adapter by lazy {
        InsightsAdapter(
            onOpen = { opened.add(it) },
            onAnswer = { insight, feedback -> answered.add(insight.uuid to feedback) },
            onOpenDay = { openedDays.add(it) }
        )
    }

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        val app: Context = ApplicationProvider.getApplicationContext()
        // The list is always inflated by the fragment inside the activity's theme; the Material cards and chip
        // styles do not resolve without it, which is not how the screen is used.
        themed = ContextThemeWrapper(app, R.style.Theme_TaskPlanner)
        parent = FrameLayout(themed)
        fixture = AppFixture(app)
        userId = runBlocking { fixture.signIn(email = "insights-drawn@example.com").id }
        runBlocking { fixture.timeBase.shiftTo(wednesday.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()) }
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `the panel is the first row of the list the cards ride in`() {
        val view = boundRow(read = workload(), insights = listOf(card("first"), card("second")), index = 0)

        assertNotNull(
            "the window the readings were counted against is row zero, so the two scroll together and cannot " +
                "become two accounts of two fortnights",
            view.findViewById<LinearLayout>(R.id.workloadDays)
        )
        assertNull(view.findViewById<TextView>(R.id.insightTitle))

        val second = boundRow(read = workload(), insights = listOf(card("first"), card("second")), index = 1)
        assertNotNull("the readings follow it, in the order the list holds them", second.findViewById<TextView>(R.id.insightTitle))
        assertEquals("first", second.findViewById<TextView>(R.id.insightTitle).text.toString())

        val empty = boundRow(read = workload(), insights = emptyList(), emptyTextRes = R.string.insights_empty_current, index = 1)
        assertEquals(
            "and a profile with nothing to say is admitted in the same list, under the window it stayed quiet about",
            themed.getString(R.string.insights_empty_current),
            empty.findViewById<TextView>(R.id.insightsEmpty).text.toString()
        )
    }

    @Test
    fun `a list before the window has been read holds no panel`() {
        val rows = InsightsRow.of(read = null, insights = listOf(card("only one")))

        assertEquals(
            "a card is a reading of a window, and drawing it above a panel holding nothing would be one " +
                "screen stating two windows at once",
            listOf(InsightsRow.Card::class.java),
            rows.map { it.javaClass }
        )
        assertEquals(
            "the panel returns as soon as the read arrives, and the order is the reader's, not the map's",
            listOf(InsightsRow.Panel::class.java, InsightsRow.Card::class.java, InsightsRow.Empty::class.java),
            InsightsRow.of(workload(), listOf(card("only one")), R.string.insights_empty_current).map { it.javaClass }
        )
    }

    @Test
    fun `a card prints the reading exactly as the pass that raised it wrote it`() {
        // A real pile of overdue high-priority work, a real pass over it, and the rows the notebook kept.
        runBlocking {
            listOf("late one", "late two", "late three").forEach { title ->
                fixture.taskDao.insertTask(
                    Task(
                        title = title, description = "", userId = userId,
                        priority = Priority.HIGH,
                        dueDate = wednesday.minusDays(1).atTime(17, 0).atZone(zone).toInstant().toEpochMilli()
                    )
                )
            }
            fixture.insightRepository.refresh()
        }
        val stored = runBlocking { fixture.insightRepository.observeCurrent().first() }
        assertTrue("the pass had something to say about a pile like this", stored.isNotEmpty())

        stored.forEachIndexed { index, insight ->
            val view = boundRow(read = null, insights = stored, index = index)
            assertEquals(
                "card #$index prints the title the rule stored, not one this screen rephrased",
                insight.title,
                view.findViewById<TextView>(R.id.insightTitle).text.toString()
            )
            assertEquals(insight.body, view.findViewById<TextView>(R.id.insightBody).text.toString())
            assertEquals(insight.basis, view.findViewById<TextView>(R.id.insightBasis).text.toString())
            assertEquals(
                "the badge names the kind of claim §7 asks the screen to state",
                themed.getString(InsightCard.kindLabelRes(insight.kind)),
                view.findViewById<TextView>(R.id.insightKind).text.toString()
            )
            assertTrue(
                "and the framing line carries the window, the confidence and the sample the row computed",
                view.findViewById<TextView>(R.id.insightMeta).text.toString()
                    .contains("${insight.confidence}% sure")
            )
        }
    }

    @Test
    fun `drawing a card ticks the answer the row carries and records nothing`() {
        val judged = card("answered", feedback = InsightFeedback.REJECTED)
        val view = boundRow(read = null, insights = listOf(judged), index = 0)

        assertEquals(
            "a reading the reader already called untrue is drawn answered, not fresh; the tick follows the row",
            R.id.answerRejected,
            checkedChip(view)
        )
        assertEquals(
            "and binding a card is not answering it — a screen that wrote on draw would retire a reading by " +
                "scrolling past it",
            0,
            answered.size
        )
    }

    @Test
    fun `a tap on an answer is the row it was drawn for and the answer that chip says`() {
        val insight = card("unanswered")
        val view = boundRow(read = null, insights = listOf(insight), index = 0)
        assertEquals(
            "nothing is ticked before the reader says anything",
            View.NO_ID,
            checkedChip(view)
        )

        view.findViewById<Chip>(R.id.answerUseful).performClick()

        assertEquals(
            "the tap reaches the repository with the row that was on screen and the answer that chip is",
            listOf(insight.uuid to InsightFeedback.USEFUL),
            answered
        )
    }

    @Test
    fun `a card opens where its own row says, and nowhere when it cannot read that`() {
        val task = card("goes to a task", actionType = InsightAction.TASK, actionRef = "41")
        val view = boundRow(read = null, insights = listOf(task), index = 0)
        val button = view.findViewById<TextView>(R.id.insightAction)
        assertTrue("a resolvable target gets its button", button.isVisible)
        assertEquals("Open the task", button.text.toString())
        button.performClick()
        assertEquals(listOf(InsightTarget.Task(41L)), opened.map { it })

        val day = boundRow(
            read = null,
            insights = listOf(card("goes to a day", actionType = InsightAction.DAY, actionRef = wednesday.toEpochDay().toString())),
            index = 0
        ).findViewById<TextView>(R.id.insightAction)
        day.performClick()
        assertEquals(InsightTarget.Day(wednesday.toEpochDay(), wholeWeek = false), opened.last())

        val week = boundRow(
            read = null,
            insights = listOf(
                card("goes to a week", actionType = InsightAction.CALENDAR_WEEK, actionRef = wednesday.toEpochDay().toString())
            ),
            index = 0
        ).findViewById<TextView>(R.id.insightAction)
        week.performClick()
        assertEquals(InsightTarget.Day(wednesday.toEpochDay(), wholeWeek = true), opened.last())

        listOf(
            card("broken ref", actionType = InsightAction.TASK, actionRef = "late three"),
            card("no action", actionType = null, actionRef = null)
        ).forEach { insight ->
            val stranded = boundRow(read = null, insights = listOf(insight), index = 0)
            assertFalse(
                "${insight.title} has no address the screen can open, so it shows no button rather than one " +
                    "that goes to row zero",
                stranded.findViewById<TextView>(R.id.insightAction).isVisible
            )
        }
    }

    @Test
    fun `the panel's day rows are a way to those days and nothing more`() {
        val read = workload(
            days = listOf(
                DayLoad(wednesday.toEpochDay(), 120, 2),
                DayLoad(wednesday.plusDays(1).toEpochDay(), 60, 1)
            ),
            priorities = PriorityLoad(high = 2, medium = 1)
        )
        val view = boundRow(read = read, insights = emptyList(), index = 0)
        val rows = view.findViewById<LinearLayout>(R.id.workloadDays)

        assertEquals("one row per day the window holds", 2, rows.childCount)
        rows.getChildAt(1).performClick()
        assertEquals(
            "the tap carries the day the row was drawn for, which is all §8 lets the panel do",
            listOf(wednesday.plusDays(1).toEpochDay()),
            openedDays.map { it }
        )
        assertEquals(
            "and nothing on the panel is a control over the plan: the window holds no button at all, so the " +
                "most it can do is take the reader to a day (§8)",
            0,
            buttonsIn(view)
        )
    }

    // ---- the harness -------------------------------------------------------

    /** Submits the rows one state draws, binds the holder at [index] and hands back the view it made. */
    private fun boundRow(
        read: WorkloadRead?,
        insights: List<Insight>,
        emptyTextRes: Int? = null,
        index: Int
    ): View {
        val rows = InsightsRow.of(read, insights, emptyTextRes)
        adapter.submitRows(read, insights, emptyTextRes)
        // The committed list is what is waited for, not its length: two states can hold one card each, and a
        // harness that only counted rows would bind the previous state's card and call it the new one.
        awaitUntil("the list to become $rows") { adapter.currentList == rows }
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(index))
        adapter.bindViewHolder(holder, index)
        return holder.itemView
    }

    private fun checkedChip(view: View): Int =
        view.findViewById<ChipGroup>(R.id.insightAnswers).checkedChipId

    /** Every button the drawn row contains, nested views included — §8 says the answer has to be zero. */
    private fun buttonsIn(view: View): Int = when (view) {
        is ViewGroup -> (0 until view.childCount).sumOf { buttonsIn(view.getChildAt(it)) } +
            if (view is MaterialButton) 1 else 0
        else -> if (view is MaterialButton) 1 else 0
    }

    private fun card(
        title: String,
        kind: InsightKind = InsightKind.FACT,
        feedback: InsightFeedback? = null,
        actionType: InsightAction? = null,
        actionRef: String? = null
    ): Insight {
        val day = wednesday.toEpochDay()
        return Insight(
            uuid = "uuid-$title",
            userId = userId,
            ruleKey = "rule-$title",
            kind = kind,
            title = title,
            body = "3 of the 4 overdue tasks are high-priority, out of 9 still open.",
            basis = "Counted now: 3 high-priority tasks past their deadline, 9 tasks open in total.",
            periodFromDay = day,
            periodToDay = day,
            sampleSize = 9,
            confidence = 80,
            actionType = actionType,
            actionRef = actionRef,
            feedback = feedback,
            firstSeenAt = 1L,
            lastSeenAt = 1L
        )
    }

    private fun workload(
        days: List<DayLoad> = listOf(DayLoad(wednesday.toEpochDay(), 120, 2)),
        priorities: PriorityLoad = PriorityLoad(high = 2)
    ): WorkloadRead = WorkloadRead(
        fromDay = wednesday.toEpochDay(),
        toDay = wednesday.plusDays(14).toEpochDay(),
        days = days,
        priorities = priorities
    )

    /** [ListAdapter] diffs on a background thread, so the rows it commits are waited for rather than assumed. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
