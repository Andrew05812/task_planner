package com.taskplanner.ui.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.R
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.TaskStatus
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The presentation arithmetic of spec §4.1, with the clock held still.
 *
 * `now` is a parameter precisely so the boundary — 23:59 against 00:01 — can be named in a test. A
 * computation that read `System.currentTimeMillis()` inline cannot be tested at the one moment where it is
 * wrong, which is a failure mode this repository has already met (the midnight flake in
 * `WorkTrackingRepositoryTest`).
 *
 * One case is the one the spec asks for by name: a completed task whose deadline has passed. The
 * dashboard's health counts are a `due_date < now` predicate taken over rows its query already excludes
 * completed ones from (`TaskStatisticsDao.observeHealthCounts`), and the card's predicate carries the same
 * population clause for the same reason — so the two screens agree because they ask one question, not
 * because two rules happen to land alike. A card that asked only the raw column would paint a finished task
 * red and late in the list while the dashboard never counted it, which is the drift spec §0 forbids.
 */
@RunWith(RobolectricTestRunner::class)
class CardPresentationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 2026-10-23 00:00:00.000 local, so every case below is decided against one fixed instant. */
    private val midnight = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 23, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** One row, four knobs. The defaults are the ordinary TODO task with no deadline. */
    private fun present(
        due: Long? = null,
        now: Long = midnight,
        priority: Priority = Priority.MEDIUM,
        status: TaskStatus = TaskStatus.TODO
    ) = CardPresentation.of(
        context,
        TasksListItem.TaskRow(
            task = Task(
                id = 1, uuid = "presentation", title = "Present", description = "", userId = 1,
                status = status, dueDate = due, priority = priority
            ),
            category = null, isSelected = false
        ),
        now
    )

    @Test fun `a task one minute past its deadline is late, and one before it is not`() {
        assertFalse(present(due = midnight + 60_000, now = midnight).isOverdue)
        assertTrue(present(due = midnight - 60_000, now = midnight).isOverdue)
    }

    @Test fun `LOW draws no rule, so absence carries the meaning`() {
        assertNull(present(priority = Priority.LOW).priorityRuleColorRes)
        assertEquals(R.color.ink_error, present(priority = Priority.HIGH).priorityRuleColorRes)
        assertEquals(R.color.ink_warning, present(priority = Priority.MEDIUM).priorityRuleColorRes)
    }

    @Test fun `only the lifecycle button the status calls for is shown`() {
        TaskStatus.values().filter { it != TaskStatus.COMPLETED }.forEach { status ->
            val p = present(status = status)
            assertEquals(
                "$status offers exactly one of start / pause / resume",
                1, listOf(p.showStart, p.showPause, p.showResume).count { it }
            )
            assertTrue("a row the list still holds offers to be finished", p.showComplete)
        }
        val done = present(status = TaskStatus.COMPLETED)
        assertFalse("a finished row has nothing left to finish", done.showComplete)
        assertFalse(listOf(done.showStart, done.showPause, done.showResume).any { it })
    }

    @Test fun `the due line never says a date the row does not carry`() {
        assertEquals(
            "the words the screen already uses, reused rather than rewritten",
            context.getString(R.string.no_due_date), present(due = null).dueLine.toString()
        )
        assertTrue(
            "three days past reads as late, in words",
            present(due = midnight - 3 * DAY, now = midnight).dueLine.contains("3")
        )
        assertTrue(
            "and the word 'late' is in it, so colour is not the only signal",
            present(due = midnight - 3 * DAY, now = midnight).dueLine.contains(
                context.resources.getQuantityString(R.plurals.task_late_days, 3, 3).substringAfter(' ')
            )
        )
    }

    @Test fun `a finished task whose deadline passed is not painted late`() {
        val late = present(due = midnight - DAY, now = midnight, status = TaskStatus.COMPLETED)
        assertFalse("the dashboard never counts it and neither does the card", late.isOverdue)
    }

    @Test fun `today and tomorrow are said in words, not as a date`() {
        assertEquals(
            context.getString(R.string.task_due_today_format, "18:00"),
            present(due = midnight + 18 * 3_600_000, now = midnight).dueLine.toString()
        )
        assertEquals(
            context.getString(R.string.task_due_tomorrow_format, "09:00"),
            present(due = midnight + DAY + 9 * 3_600_000, now = midnight).dueLine.toString()
        )
    }

    @Test fun `a deadline that passed earlier today is late without pretending a day has gone`() {
        val morning = present(due = midnight + 9 * 3_600_000, now = midnight + 18 * 3_600_000)
        assertTrue("it is late", morning.isOverdue)
        val line = morning.dueLine.toString()
        assertFalse("[$line] counts zero days late on a task nine hours late", line.startsWith("0"))
        assertTrue("and it still says the word late", line.contains("late", ignoreCase = true) ||
            line.contains(context.getString(R.string.task_late_today), ignoreCase = true))
    }

    @Test fun `each status gets its own pill, and no two of them share one`() {
        val byStatus = TaskStatus.values().associateWith { present(status = it) }
        byStatus.forEach { (status, p) ->
            val expected = when (status) {
                TaskStatus.TODO -> R.drawable.bg_pill_neutral
                TaskStatus.IN_PROGRESS -> R.drawable.bg_pill_accent
                TaskStatus.PAUSED -> R.drawable.bg_pill_warning
                TaskStatus.COMPLETED -> R.drawable.bg_pill_success
            }
            assertEquals("$status wears its own pill", expected, p.pillBackgroundRes)
            val text = when (status) {
                TaskStatus.TODO -> R.color.ink_text_secondary
                TaskStatus.IN_PROGRESS -> R.color.ink_accent
                TaskStatus.PAUSED -> R.color.ink_warning
                TaskStatus.COMPLETED -> R.color.ink_success
            }
            assertEquals("$status reads in its own colour", text, p.pillTextRes)
        }
        assertEquals("four statuses, four pills", 4, byStatus.values.map { it.pillBackgroundRes }.distinct().size)
        assertEquals("four statuses, four word colours",
            4, byStatus.values.map { it.pillTextRes }.distinct().size)
    }

    /**
     * The words the repeat mark speaks, pinned to the strings rather than to the enum's own getters.
     *
     * `RepeatPreset` holds two labels per rate: `labelRes` is the dropdown's choice name ("Every week") and
     * `shortLabelRes` is the sentence fragment the card's due line used to carry ("repeats weekly"). Naming
     * the ids here is what keeps those two from being swapped — a test that compared
     * `shortLabelRes` against `shortLabelRes` could not tell which word the card says, and the icon's whole
     * claim to exist is that it moved the information rather than renamed it. The two interruptions of a
     * series are pinned in the same table, because a paused series is not a weekly one.
     */
    @Test fun `the repeat icon carries the words the due line used to print`() {
        fun presented(rule: RecurrenceRule) = CardPresentation.of(
            context,
            TasksListItem.TaskRow(
                task = Task(id = 2, uuid = "series", title = "Weekly", description = "", userId = 1,
                    status = TaskStatus.TODO),
                category = null, isSelected = false, series = rule
            ),
            midnight
        )
        val weekly = RecurrenceRule(uuid = "s", userId = 1, unit = RepeatUnit.WEEK, interval = 1,
            weekDays = "", dayOfMonth = 0, anchorAt = midnight, cursorAt = midnight)
        assertEquals("the icon says what the series does, in the due line's own words",
            context.getString(R.string.repeats_weekly),
            context.getString(presented(weekly).repeatDescriptionRes!!))
        assertEquals("a paused series is said as paused, not as weekly",
            context.getString(R.string.series_short_paused),
            context.getString(presented(weekly.copy(paused = true)).repeatDescriptionRes!!))
        assertEquals("and an ended one as ended",
            context.getString(R.string.series_short_ended),
            context.getString(presented(weekly.copy(endedAt = midnight)).repeatDescriptionRes!!))
        assertEquals("and a task with no series has no such words",
            null, present().repeatDescriptionRes)
    }

    /**
     * The pill pair each status wears: its drawable *and* its ink, together.
     *
     * Asserting that a drawable id is merely non-zero would still hold if all four statuses pointed at the
     * same pale neutral — which is exactly the drift the paused row is one edit from, since its background
     * token (`pill_warning`) and its text token (`ink_warning`) are two names that have to be read as a
     * pair. The word is pinned in the same place so a label that no longer matches its colour fails here
     * rather than in a screenshot review.
     */
    @Test fun `every status gets the pill pair the tokens define`() {
        val expected = mapOf(
            TaskStatus.TODO to (R.drawable.bg_pill_neutral to R.color.ink_text_secondary),
            TaskStatus.IN_PROGRESS to (R.drawable.bg_pill_accent to R.color.ink_accent),
            TaskStatus.PAUSED to (R.drawable.bg_pill_warning to R.color.ink_warning),
            TaskStatus.COMPLETED to (R.drawable.bg_pill_success to R.color.ink_success)
        )
        assertEquals("every status is pinned by name", TaskStatus.values().size, expected.size)
        expected.forEach { (status, pair) ->
            val p = present(status = status)
            assertEquals(
                "$status prints its own word",
                context.getString(
                    when (status) {
                        TaskStatus.TODO -> R.string.status_todo
                        TaskStatus.IN_PROGRESS -> R.string.status_in_progress
                        TaskStatus.PAUSED -> R.string.status_paused
                        TaskStatus.COMPLETED -> R.string.status_done
                    }
                ),
                context.getString(p.statusLabelRes)
            )
            assertEquals("$status wears its own pill", pair.first, p.pillBackgroundRes)
            assertEquals("$status reads in its own ink", pair.second, p.pillTextRes)
        }
        assertEquals("four statuses, four surfaces: no two share a pill",
            4, expected.values.map { it.first }.distinct().size)
    }

    /**
     * A date line follows the locale the device is on, not the one that happened to be current when this
     * class was first touched.
     *
     * lint names the failure (`ConstantLocale`): a `SimpleDateFormat` held in a static field goes on
     * formatting in the locale the process started in after the reader moves to another one, so a card
     * would print "Nov 22, 2026" to somebody whose phone says "22 nov. 2026". The formatter has to be
     * built where it is used, which is what this pins — and it is a case the rest of this class cannot
     * see, because it only ever reads one locale.
     */
    @Test fun `a date line follows the locale the device is on`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val french = present(due = midnight + 30 * DAY).dueLine.toString()
            Locale.setDefault(Locale.UK)
            val british = present(due = midnight + 30 * DAY).dueLine.toString()
            assertNotEquals("one deadline, read in two locales, says it two ways: '$french' / '$british'",
                french, british)
            assertTrue("and the second is the locale's own month word: '$british'",
                british.contains("Nov"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private companion object {
        /** Named here rather than imported: the fixture states its own arithmetic. */
        const val DAY = 86_400_000L
    }
}
