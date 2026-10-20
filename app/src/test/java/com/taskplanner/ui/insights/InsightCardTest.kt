package com.taskplanner.ui.insights

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightAction
import com.taskplanner.data.model.InsightKind
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The framing a card adds around the three sentences it was handed.
 *
 * [InsightCard] is the one place that decides how a reading is framed, because the reading itself arrives as
 * finished text and is deliberately not touched: a screen that rephrased yesterday's card in today's words
 * would undo the reason the sentences are stored. What is left to get wrong is therefore the framing, and the
 * failures worth guarding are the ones a reader would actually be misled by:
 *
 *  * **A count, a regularity and a guess named by three different words** (§7), because the three are answered
 *    differently and a card that called a hypothesis a fact asks to be believed on evidence it does not have.
 *  * **A window stated as the window it was** — one Thursday printed as one Thursday, not as "Jun 4 – Jun 4",
 *    and a stretch of days printed with both of its bounds rather than only the one that looks tidier.
 *  * **A number never printed without its base**, so "90% sure" always travels with the rows it was computed
 *    from and one row never borrows the plural of nine.
 *  * **An address only made out of a reference that can be read as one** — the card that cannot resolve its
 *    target shows no button instead of opening the row with id 0.
 *
 * The device's own date spelling is not part of any assertion: [dateText] reads the same medium-format
 * instance the card uses, so every check below is about which line the card builds and how its parts are
 * ordered, rather than about how one locale's formatter happens to abbreviate June.
 */
@RunWith(RobolectricTestRunner::class)
class InsightCardTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var defaultLocale: Locale
    private lateinit var context: Context

    private val wednesday = LocalDate.of(2026, 6, 3)
    private val thursday = LocalDate.of(2026, 6, 4)

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        defaultLocale = Locale.getDefault()
        // The same pin the rest of the screen tests use: a run from a machine set to a different zone or
        // language has to print the same card as one set to the developer's.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Locale.setDefault(Locale.GERMANY)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultZone)
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `the three kinds of claim are named by three different words`() {
        val labels = InsightKind.entries.map { context.getString(InsightCard.kindLabelRes(it)) }

        assertEquals(
            "a fact is a count, a pattern is a regularity and a hypothesis is a reading of what the rows " +
                "point at; §7 asks the card to say which of the three it is handing the reader",
            listOf("Fact", "Pattern", "Hypothesis"),
            labels
        )
        assertEquals(
            "and the three are not one word in three cases, which would leave the reader to guess",
            3,
            labels.toSet().size
        )
    }

    @Test
    fun `a reading counted on one day names that day once`() {
        val meta = InsightCard.meta(
            context,
            insight(periodFromDay = thursday.toEpochDay(), periodToDay = thursday.toEpochDay())
        )
        val parts = meta.split(context.getString(R.string.insight_card_meta_joiner))

        assertEquals(
            "the window, the confidence and the sample: three parts, and nothing the row did not carry",
            3,
            parts.size
        )
        assertEquals("Counted on ${dateText(thursday)}", parts[0])
        assertFalse(
            "the heavy-day rule counts a single Thursday, and 'Jun 4 – Jun 4' reads like a range nobody " +
                "filled in",
            parts[0].contains("–")
        )
    }

    @Test
    fun `a window over several days states both of its bounds`() {
        val meta = InsightCard.meta(
            context,
            insight(periodFromDay = wednesday.toEpochDay(), periodToDay = thursday.toEpochDay())
        )

        assertEquals(
            "the first day counted and the last one, in that order: a reader who wants to check the sample " +
                "needs to know which days to look at",
            "Counted ${dateText(wednesday)} – ${dateText(thursday)}",
            meta.split(context.getString(R.string.insight_card_meta_joiner)).first()
        )
    }

    @Test
    fun `a confidence always travels with the rows it was computed from`() {
        val parts = InsightCard.meta(context, insight(sampleSize = 9, confidence = 90))
            .split(context.getString(R.string.insight_card_meta_joiner))

        assertEquals("90% sure", parts[1])
        assertEquals("9 rows counted", parts[2])

        assertEquals(
            "one row is not nine, and a card that printed '1 rows counted' would tell the reader the " +
                "sample was never really looked at",
            listOf("90% sure", "1 row counted"),
            InsightCard.meta(context, insight(sampleSize = 1, confidence = 90))
                .split(context.getString(R.string.insight_card_meta_joiner))
                .drop(1)
        )
    }

    @Test
    fun `an action turns the reference it stores into the address it names`() {
        assertEquals(
            InsightTarget.Task(41L),
            InsightCard.targetOf(insight(actionType = InsightAction.TASK, actionRef = "41"))
        )
        assertEquals(
            InsightTarget.Goal(7L),
            InsightCard.targetOf(insight(actionType = InsightAction.GOAL, actionRef = "7"))
        )
        assertEquals(
            InsightTarget.Habit(12L),
            InsightCard.targetOf(insight(actionType = InsightAction.HABIT, actionRef = "12"))
        )
        assertEquals(
            InsightTarget.Category(3L),
            InsightCard.targetOf(insight(actionType = InsightAction.CATEGORY, actionRef = "3"))
        )
        assertEquals(
            InsightTarget.Day(thursday.toEpochDay(), wholeWeek = false),
            InsightCard.targetOf(insight(actionType = InsightAction.DAY, actionRef = thursday.toEpochDay().toString()))
        )
        assertEquals(
            // The week is the same address with a different zoom, which is why it stays a day rather than
            // becoming a calendar screen mode this screen would have to know the name of.
            InsightTarget.Day(thursday.toEpochDay(), wholeWeek = true),
            InsightCard.targetOf(
                insight(actionType = InsightAction.CALENDAR_WEEK, actionRef = thursday.toEpochDay().toString())
            )
        )
    }

    @Test
    fun `a card whose reference cannot be read as an address offers no way to go`() {
        listOf("", "  ", "late three", "41.0", "0x41", null).forEach { ref ->
            assertNull(
                "a reference of \"$ref\" on a task action has no row to open, and navigating to id 0 would be " +
                    "a wrong answer dressed as a working button",
                InsightCard.targetOf(insight(actionType = InsightAction.TASK, actionRef = ref))
            )
        }
        assertNull(
            "a reference without an action is the same nothing: there is no screen to guess",
            InsightCard.targetOf(insight(actionType = null, actionRef = "41"))
        )
        assertNull(
            "and a reading worth knowing that leads nowhere is drawn as exactly that",
            InsightCard.targetOf(insight(actionType = null, actionRef = null))
        )
        assertNull(
            "no action means no label, so the button has nothing it could even say",
            InsightCard.actionLabelRes(null)
        )
    }

    @Test
    fun `every action the row can name has its own sentence on the button`() {
        val labels = InsightAction.entries.map { context.getString(InsightCard.actionLabelRes(it)!!) }

        assertEquals(
            "the reader has to be able to tell which of the four things a tap opens before tapping it",
            listOf(
                "Open the task", "See the day", "Open the goal", "Open the habit", "Open the category",
                "See the week"
            ),
            labels
        )
        assertTrue(
            "and none of them the same word: two identical buttons would be one button the reader cannot aim",
            labels.size == labels.toSet().size
        )
    }

    @Test
    fun `the two empty lists admit opposite reasons`() {
        assertEquals(
            R.string.insights_empty_current,
            InsightCard.emptyTextRes(showHistory = false)
        )
        assertEquals(
            R.string.insights_empty_history,
            InsightCard.emptyTextRes(showHistory = true)
        )
        assertNotSameLike(
            "the live list is empty because the newest pass found nothing to say, while the history is empty " +
                "because nothing was ever retired — printing the first on the second screen would tell a " +
                "reader who hid a card that they never had one",
            context.getString(R.string.insights_empty_current),
            context.getString(R.string.insights_empty_history)
        )
    }

    private fun assertNotSameLike(message: String, unexpected: String, actual: String) {
        assertFalse("$message (both read \"$actual\")", unexpected == actual)
    }

    /** A reading with the framing fields the card prints set, and nothing else that matters here. */
    private fun insight(
        periodFromDay: Long = wednesday.toEpochDay(),
        periodToDay: Long = thursday.toEpochDay(),
        sampleSize: Int = 9,
        confidence: Int = 90,
        actionType: InsightAction? = null,
        actionRef: String? = null
    ): Insight = Insight(
        uuid = Insight.uuidFor(1L, "test-rule", periodFromDay, periodToDay),
        userId = 1L,
        ruleKey = "test-rule",
        kind = InsightKind.FACT,
        title = "High-priority work is past its deadline",
        body = "3 of the 4 overdue tasks are high-priority, out of 9 still open.",
        basis = "Counted now: 3 high-priority tasks past their deadline, 9 tasks open in total.",
        periodFromDay = periodFromDay,
        periodToDay = periodToDay,
        sampleSize = sampleSize,
        confidence = confidence,
        actionType = actionType,
        actionRef = actionRef,
        firstSeenAt = 1L,
        lastSeenAt = 1L
    )

    /** The card's own date spelling, read from the formatter the card uses rather than retyped here. */
    private fun dateText(day: LocalDate): String = DateFormat
        .getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
        .format(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
}
