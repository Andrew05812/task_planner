package com.taskplanner.ui.util

import android.content.DialogInterface
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.taskplanner.R
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.repository.AdjustmentRejection
import com.taskplanner.data.repository.AdjustmentResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * The form every hand-written measurement goes through, and the sentences the screens say about what the
 * repository answered.
 *
 * This object is the last place a typed instant exists as text, so it is the last place a wrong one can be
 * stopped before it reaches the database. The rule under test is that nothing is written on a guess: text
 * that names no instant, a stretch that ends before it starts, and an empty stretch are each turned down
 * here, and the callback that writes runs only on a pair the user actually spelled out. The other half is
 * §13's conflict, which has to arrive as the list of measurements in the way — a dialog naming them is
 * what makes "shown, not settled quietly" true rather than a line in a design document.
 *
 * The anchor is a fragment attached to a real activity because both halves need what a fragment has: a
 * themed context for the dialog and a view for the notice to sit on.
 */
@RunWith(RobolectricTestRunner::class)
class WorkAdjustmentUiTest {

    private lateinit var activity: FragmentActivity
    private lateinit var host: FrameLayout
    private lateinit var anchor: Fragment

    /** A fragment with a view of its own: `requireView` and `layoutInflater` are what the form needs. */
    class AnchorFragment : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View = FrameLayout(inflater.context)
    }

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        // The fragment needs a container it is actually placed in: `add(fragment, tag)` without one gives
        // it a view that is never attached, and a notice or a themed dialog is then asked to sit on nothing.
        host = FrameLayout(activity).apply { id = View.generateViewId() }
        activity.setContentView(host)
        anchor = AnchorFragment()
        activity.supportFragmentManager.beginTransaction().add(host.id, anchor, "anchor").commitNow()
        // A notice asserted later in the test is only worth anything if it is this test's own, so the
        // manager starts each test empty rather than still holding one an earlier test showed.
        dismissNotices()
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun instantOf(day: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun openForm(start: String?, end: String?, reasonVisible: Boolean, previous: String? = null) {
        WorkAdjustmentUi.showIntervalForm(
            anchor = anchor,
            titleRes = R.string.history_correct_title,
            start = start,
            end = end,
            reasonVisible = reasonVisible,
            previous = previous
        ) { _, _, _ -> }
    }

    /**
     * The dialog the screen last put up.
     *
     * `ShadowDialog` rather than `ShadowAlertDialog`: the app builds its dialogs through Material, which
     * produces an AppCompat dialog, and the AppCompat class is not what the alert-dialog shadow tracks.
     */
    private fun dialog(): AlertDialog {
        val shown = ShadowDialog.getLatestDialog()
        assertNotNull("the form asks for the numbers as text, so it has to be on screen", shown)
        return shown as AlertDialog
    }

    private fun field(form: AlertDialog, id: Int): EditText {
        val view = form.findViewById<EditText>(id)
        assertNotNull("the form has no field ${activity.resources.getResourceName(id)}", view)
        return view!!
    }

    private fun viewIn(form: AlertDialog, id: Int): View {
        val view = form.findViewById<View>(id)
        assertNotNull("the form has no view ${activity.resources.getResourceName(id)}", view)
        return view!!
    }

    /** The equivalent of the user pressing Save. */
    private fun submit(form: AlertDialog) {
        form.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        pump()
    }

    /**
     * Lets the main looper run what the screen just posted.
     *
     * A click on a dialog button and a Snackbar are both handed to the looper rather than carried out on
     * the calling thread, and Robolectric's paused looper only runs them when it is pumped. Without this
     * the refusal tests would pass by doing nothing at all, which is the opposite of what they claim.
     */
    private fun pump() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * The words the screen put on a notice, or null when it said nothing.
     *
     * The notice is read rather than merely spotted because which sentence the user gets is the thing
     * under test: "it did not save" and "this stretch is still being measured" are not the same ask. The
     * fragment's own container is skipped, so a check cannot pass on the screen's layout.
     *
     * Asking is not the same as being shown: the notice manager first animates whatever it still holds out
     * of the way, and a paused looper only runs those frames when the clock is moved, so the words are
     * waited for instead of assumed to be there already. The steps stay far shorter than the time a
     * LENGTH_LONG notice spends up, so waiting cannot read a notice's own ending as "the screen was quiet";
     * the attempt limit is what turns a screen that truly says nothing into a failure rather than a hang.
     */
    private fun noticeText(): String? {
        repeat(NOTICE_READ_ATTEMPTS) {
            val text = spokenNotice()
            if (text != null) return text
            shadowOf(Looper.getMainLooper()).idleFor(NOTICE_READ_STEP_MS, TimeUnit.MILLISECONDS)
        }
        return null
    }

    /** The first notice view the activity has been given, or null while the screen still says nothing. */
    private fun spokenNotice(): String? {
        pump()
        return content().asSequence()
            .filter { it !== host }
            .mapNotNull { it.spokenText() }
            .firstOrNull()
    }

    private fun submitForm(
        start: String?,
        end: String?,
        reason: String? = null
    ): Triple<Long?, Long?, String?> {
        var written: Triple<Long?, Long?, String?> = Triple(null, null, null)
        WorkAdjustmentUi.showIntervalForm(
            anchor = anchor,
            titleRes = R.string.history_add_title,
            start = start,
            end = end,
            reasonVisible = true,
            previous = null
        ) { startedAt, endedAt, text -> written = Triple(startedAt, endedAt, text) }
        val form = dialog()
        field(form, R.id.workStartInput).setText(start)
        field(form, R.id.workEndInput).setText(end)
        reason?.let { field(form, R.id.workReasonInput).setText(it) }
        submit(form)
        return written
    }

    /** The views the activity has been handed, which is where a notice is put. */
    private fun content(): List<View> {
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        return (0 until container.childCount).map { container.getChildAt(it) }
    }

    /** The first piece of text inside this view tree, however deeply it is nested. */
    private fun View.spokenText(): String? {
        if (this is TextView && text.isNotEmpty()) return text.toString()
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).spokenText()?.let { return it }
            }
        }
        return null
    }

    /** Every word a view tree shows, so a dialog can be read the way the user reads it. */
    private fun View.utteredText(): String {
        val parts = ArrayList<String>()
        collectText(this, parts)
        return parts.joinToString(" · ")
    }

    private fun collectText(view: View, into: MutableList<String>) {
        if (view is TextView && view.text.isNotEmpty()) into.add(view.text.toString())
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectText(view.getChildAt(index), into)
        }
    }

    /**
     * Takes every notice back off the screen, including the one the notice manager still holds.
     *
     * A Snackbar is not shown by its own view: a process-wide manager keeps one as "the notice on screen",
     * and a paused Robolectric looper never runs the timeout that ends it. Removing the view alone would
     * therefore leave the manager queueing this test's notice behind one nobody can see, which reads as the
     * screen having said nothing. The clock is moved past the longest notice, then the views go, and the
     * pair is repeated until the screen is actually quiet — one round is only enough when a single notice
     * was left behind, and the manager can hand over to another one as soon as it releases the first.
     */
    private fun dismissNotices() {
        val container = activity.findViewById<ViewGroup>(android.R.id.content)
        repeat(NOTICE_DRAIN_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idleFor(NOTICE_SETTLE_MS, TimeUnit.MILLISECONDS)
            for (index in container.childCount - 1 downTo 0) {
                if (container.getChildAt(index) !== host) container.removeViewAt(index)
            }
            if (spokenNotice() == null) return
        }
    }

    private fun interval(
        uuid: String,
        start: Long,
        end: Long?,
        source: WorkSource = WorkSource.TRACKER
    ): WorkSession = WorkSession(
        uuid = uuid,
        userId = 1L,
        trackingSessionUuid = "session-$uuid",
        taskId = 7L,
        taskUuid = "task-7",
        startedAt = start,
        endedAt = end,
        durationSeconds = end?.let { (it - start) / 1_000L },
        localDay = start / MILLIS_PER_DAY,
        utcOffsetMinutes = 0,
        source = source
    )

    @Test
    fun `the form opens on the numbers the row already holds`() {
        val day = LocalDate.of(2026, 6, 18)
        openForm(
            start = WorkInstantInput.format(instantOf(day, 9, 0)),
            end = WorkInstantInput.format(instantOf(day, 10, 15)),
            reasonVisible = true,
            previous = "ended late · 2026-06-18 09:00 – 2026-06-18 09:30"
        )
        val form = dialog()

        assertEquals("2026-06-18 09:00", field(form, R.id.workStartInput).text.toString())
        assertEquals("2026-06-18 10:15", field(form, R.id.workEndInput).text.toString())
        assertTrue(
            "a correction explains itself, so the reason is asked for",
            viewIn(form, R.id.workReasonInput).isVisible
        )
        assertTrue(
            "and what the stretch said before is on the screen",
            viewIn(form, R.id.workPrevious).isVisible
        )
        assertEquals(
            "ended late · 2026-06-18 09:00 – 2026-06-18 09:30",
            (form.findViewById<TextView>(R.id.workPrevious))!!.text.toString()
        )
    }

    @Test
    fun `a new entry is not shown a previous figure it does not have`() {
        openForm(start = null, end = null, reasonVisible = false)
        val form = dialog()

        assertEquals("", field(form, R.id.workStartInput).text.toString())
        assertFalse(
            "a hand-entered stretch has no earlier account to explain",
            viewIn(form, R.id.workPrevious).isVisible
        )
        assertFalse(
            "and no reason to give",
            viewIn(form, R.id.workReasonInput).isVisible
        )
    }

    @Test
    fun `text that names no instant never reaches the writer`() {
        listOf(
            "yesterday morning" to "half a sentence",
            "2026-13-45 09:00" to "a month that does not exist",
            "18.06.2026 9:00" to "the wrong shape",
            "" to "nothing at all"
        ).forEach { (text, why) ->
            val written = submitForm(text, "2026-06-18 10:00")
            assertEquals(
                "$text is $why, so it cannot become a stored instant",
                Triple(null, null, null),
                written
            )

            val reversed = submitForm("2026-06-18 09:00", text)
            assertEquals(
                "and the same holds for the field at the other end",
                Triple(null, null, null),
                reversed
            )
        }
    }

    @Test
    fun `a stretch that ends where or before it began is refused by the form`() {
        val day = LocalDate.of(2026, 6, 18)

        assertEquals(
            "an instant is not a duration",
            Triple(null, null, null),
            submitForm(
                WorkInstantInput.format(instantOf(day, 10, 0)),
                WorkInstantInput.format(instantOf(day, 9, 30))
            )
        )
        assertEquals(
            "and a zero-length one is refused too rather than stored as a measurement",
            Triple(null, null, null),
            submitForm(
                WorkInstantInput.format(instantOf(day, 10, 0)),
                WorkInstantInput.format(instantOf(day, 10, 0))
            )
        )
        assertEquals(
            "the user is told which of the two fields is wrong",
            activity.getString(R.string.history_bad_range),
            noticeText()
        )
    }

    @Test
    fun `the instants handed to the writer are the ones that were typed`() {
        val written = submitForm(
            "2026-06-18 09:00", "2026-06-18 10:15", "left the timer running"
        )

        val day = LocalDate.of(2026, 6, 18)
        assertEquals(instantOf(day, 9, 0), written.first)
        assertEquals(instantOf(day, 10, 15), written.second)
        assertEquals("left the timer running", written.third)
    }

    @Test
    fun `a blank reason is kept as nothing rather than an empty note`() {
        assertEquals(
            "whitespace is not an explanation the history should carry",
            null,
            submitForm("2026-06-18 09:00", "2026-06-18 10:00", "   ").third
        )
    }

    @Test
    fun `a conflict is shown as the measurements standing in the way`() {
        val day = LocalDate.of(2026, 6, 18)
        val blocking = listOf(
            interval("i1", instantOf(day, 9, 0), instantOf(day, 9, 30)),
            interval("i2", instantOf(day, 9, 20), instantOf(day, 10, 0), source = WorkSource.MANUAL)
        )

        WorkAdjustmentUi.report(
            anchor, AdjustmentResult.Overlapping(blocking), R.string.history_corrected
        )

        val shown = dialog()
        val spoken = shown.window!!.decorView.utteredText()
        assertTrue(
            "each row is named by the instants it occupies, so the user can tell them apart",
            spoken.contains("2026-06-18 09:00") && spoken.contains("2026-06-18 09:30")
        )
        assertTrue("and the typed-in one says it was typed in", spoken.contains("entered by hand"))
        assertTrue(
            "the count of blocking measurements and the reason the write stopped are both said — a dialog " +
                "that only lists rows leaves the user guessing whether something already went through",
            spoken.contains(activity.resources.getQuantityString(R.plurals.history_conflict_count, 2, 2)) &&
                spoken.contains(activity.getString(R.string.history_conflict_title))
        )
        assertEquals(
            "the one word the app offers for a conflict is the way out of it",
            activity.getString(R.string.cancel),
            shown.getButton(DialogInterface.BUTTON_NEGATIVE)?.text?.toString()
        )

        // However the dialog is closed, nothing is said as though something had been written: the user is
        // asked to correct one of the listed measurements or name a different stretch, and §13 is satisfied
        // by that refusal rather than by a confirm button here.
        shown.getButton(DialogInterface.BUTTON_POSITIVE)?.performClick()
        pump()
        assertNull("closing it writes nothing and reports nothing", noticeText())
    }

    @Test
    fun `a write that landed and a write that was refused are each said out loud`() {
        listOf(
            AdjustmentResult.Accepted("i1") to R.string.history_corrected,
            AdjustmentResult.Rejected(AdjustmentRejection.STILL_BEING_MEASURED) to R.string.history_still_measured,
            AdjustmentResult.Rejected(AdjustmentRejection.NOT_FOUND) to R.string.history_not_found,
            AdjustmentResult.Rejected(AdjustmentRejection.NO_PROFILE) to R.string.history_no_profile,
            AdjustmentResult.Rejected(AdjustmentRejection.START_NOT_BEFORE_END) to R.string.history_bad_range
        ).forEach { (result, expectedRes) ->
            dismissNotices()
            val dialogsBefore = ShadowDialog.getLatestDialog()
            WorkAdjustmentUi.report(anchor, result, R.string.history_corrected)

            assertEquals(
                "every answer is said in the words of its own reason",
                activity.getString(expectedRes),
                noticeText()
            )
            assertTrue(
                "and none of them is a dialog the user has to dismiss",
                ShadowDialog.getLatestDialog() === dialogsBefore
            )
        }
    }

    @Test
    fun `the instant the form writes is the instant the screen showed`() {
        // What the user reads in a row is what the field is filled with, so editing cannot silently move
        // the work to another day: the round trip through text has to land on the same instant.
        val stored = System.currentTimeMillis() / 60_000L * 60_000L
        val text = WorkInstantInput.format(stored)

        assertEquals(stored, WorkInstantInput.parse(text, zone))
        assertEquals(text, WorkInstantInput.format(stored, zone))
    }

    @Test
    fun `the custom range asks for whole days in the form the screen reads back`() {
        val day = LocalDate.of(2026, 6, 18)

        assertEquals(day, WorkInstantInput.parseDay(WorkInstantInput.formatDay(day)))
        assertEquals("2026-06-18", WorkInstantInput.formatDay(day))
        assertNull("a day with an hour on it is not a day", WorkInstantInput.parseDay("2026-06-18 09:00"))
        assertNull("a leap day in a year that has none names nothing", WorkInstantInput.parseDay("2025-02-29"))
        assertNull("and so does an empty field", WorkInstantInput.parseDay("  "))
        assertTrue(WorkInstantInput.formatDayLong(day).contains("2026"))
    }

    @Test
    fun `a minute of the day is shown without the rest of it`() {
        val day = LocalDate.of(2026, 6, 18)

        assertEquals("09:05", WorkInstantInput.timeOfDay(instantOf(day, 9, 5), zone))
        assertEquals("18:40", WorkInstantInput.timeOfDay(instantOf(day, 18, 40), zone))
    }

    private companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        /** Past a LENGTH_LONG notice and its animation, so the notice manager has let go of it. */
        private const val NOTICE_SETTLE_MS = 10_000L

        /** One frame's worth of patience: long enough to run an animation, short next to a notice's life. */
        private const val NOTICE_READ_STEP_MS = 50L

        /** Two seconds of screen time, which is when a notice that was never asked for is called absent. */
        private const val NOTICE_READ_ATTEMPTS = 40

        /** More hand-overs than the suite can leave queued, so a quiet screen is proven not assumed. */
        private const val NOTICE_DRAIN_ROUNDS = 8
    }
}
