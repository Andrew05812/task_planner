package com.taskplanner.ui.report

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.R
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.User
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportPeriodRead
import com.taskplanner.data.report.ReportPeriodRejection
import com.taskplanner.data.report.export.CsvReport
import com.taskplanner.data.report.export.PagedReport
import com.taskplanner.data.report.export.PaintReportMeasurer
import com.taskplanner.data.report.export.ReportExport
import com.taskplanner.data.report.export.ReportExportRepository
import com.taskplanner.data.report.export.ReportFailure
import com.taskplanner.data.report.export.ReportFormat
import com.taskplanner.data.report.export.ReportNaming
import com.taskplanner.data.repository.ReportRepository
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTimeMath
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The five period chips, in the order a reader is asked to choose them.
 *
 * Each one names exactly one variant of [ReportPeriod], so the chip row is a spelling of the period type rather
 * than a sixth kind of report the layers underneath would have to learn about. A chip carries no date of its own:
 * which day, month or year is meant comes from the anchor the screen holds, which is what lets a reader move from
 * a month to the week inside it and stay looking at the same days.
 */
enum class ReportKind { DAY, WEEK, MONTH, YEAR, RANGE }

/**
 * What the screen says about the last file the reader asked for.
 *
 * A sealed type rather than a string, because the four answers behave differently: a saved file is an end in
 * itself, a failure comes with a retry, a cancelled picker must stay silent, and a rejected range has to leave
 * the period exactly where it was. The finished sentence travels inside the fact so the two cannot drift apart,
 * and the screen has no wording of its own to invent.
 */
sealed class ReportFeedback {

    /**
     * Bytes are on disk behind a closed stream, and [size] is the exporter's own count of them.
     *
     * The count rather than a sentence, because the sentence wants the reader's number format and their word for
     * "bytes" — both of which belong to whoever is holding the screen, and neither of which a ViewModel has.
     */
    data class Saved(val size: Long) : ReportFeedback()

    /** The reader closed the picker. There is no sentence for this one, and showing one is the bug. */
    data object Cancelled : ReportFeedback()

    data class Failed(@StringRes val message: Int, val reason: ReportFailure) : ReportFeedback()

    /** The days were named the wrong way round, or the year is outside the calendar. Nothing was guessed. */
    data class PeriodRejected(@StringRes val message: Int) : ReportFeedback()

    /**
     * What to tell the reader, as a resource — or nothing at all.
     *
     * A cancellation is the case §6 names: closing the picker must not raise a false error. The screen has to be
     * able to see that without re-deriving it from the shape of a string, which is why the answer is a missing id
     * rather than an empty one.
     */
    @get:StringRes
    val messageRes: Int? get() = when (this) {
        Cancelled -> null
        is Saved -> R.string.report_saved_format
        is Failed -> message
        is PeriodRejected -> message
    }
}

/** One timezone in the picker: the id a report is counted in, and the line the list shows beside it. */
data class ReportZone(val id: String, val label: String)

/**
 * Everything the report screen shows, in the units the layers underneath hand it.
 *
 * The screen holds two views of one read — the laid-out pages and the csv rows — and the format chip decides
 * which one is drawn and which one the next file is written as. Both are built the moment a report arrives, so
 * switching between them is a switch rather than a second question: §9's "no regeneration per redraw" is
 * structural here instead of a rule the fragment has to remember.
 */
data class ReportState(
    val kind: ReportKind,
    val period: ReportPeriod,

    /**
     * The day the question is anchored on: what a chip means when it says "the week", "the month", "the year".
     *
     * Carried beside the period rather than derived from it, because three of the five periods have thrown the
     * day away — a month does not remember which day of it the reader was standing on — and a screen that
     * reopened the picker on the first of the month would move the reader to a date they never chose.
     */
    val anchor: LocalDate,
    val zone: ZoneId,

    /** Whether the zone on screen is the device's own, which is what the picker marks as where it started. */
    val followsDeviceZone: Boolean,

    /**
     * The zone as the screen states it: its id beside the offset it is standing at today.
     *
     * Spelled here rather than in the fragment because the offset is asked of `java.time`, and a screen that
     * worked out which hour a calendar is at would be a screen that could get it wrong in a different way from
     * the report.
     */
    val zoneLabel: String,
    val format: ReportFormat,

    /**
     * How big the sheets are drawn, as a level rather than a scale.
     *
     * A page fitted to the phone is the page the file is, and that is the right answer on most screens; it is
     * also the wrong one on a small one, where a 10 pt line becomes five pixels. Two steps up are offered because
     * past that the reader is scrolling a document instead of reading it.
     */
    val zoom: Int = ReportPageFit.MIN_ZOOM,

    val profileId: Long? = null,
    val profileName: String? = null,

    val report: Report? = null,
    val pages: PagedReport? = null,

    /**
     * The csv file's own lines, the byte-order mark taken off and the line endings split.
     *
     * The preview shows these and nothing else, which is what makes it a preview rather than a second document:
     * a field with a comma or a line break inside it reads here exactly as it reads in the file, because these
     * are the bytes the exporter writes, taken back apart for the screen. A list of the *values* would have
     * needed its own quoting rule to display, and two quoting rules in one app is how a preview ends up
     * promising something the file does not contain.
     */
    val csvLines: List<String> = emptyList(),

    /** A read for the question on screen has not answered yet. */
    val reading: Boolean = true,
    /** The read answered and its pages are being laid out. */
    val previewing: Boolean = false,
    val saving: Boolean = false,
    val staging: Boolean = false,

    /** The Sharesheet request, handed over once: the fragment launches it and the screen forgets it. */
    val share: Intent? = null,
    val feedback: ReportFeedback? = null,

    @StringRes val readError: Int? = null,
    @StringRes val previewError: Int? = null,

    /** Why there is nothing to show when nothing failed: nobody is signed in, so there is no report to read. */
    @StringRes val emptyMessage: Int? = null
) {
    val isLoading: Boolean get() = reading || previewing

    /**
     * Whether a file may be asked for right now.
     *
     * Two answers have to be on screen for it: the report, which is what the file will hold, and the pages laid
     * out from that same report, which is what the reader pressed Save looking at. While a layout is running the
     * second belongs to the report before it, and a file written then would disagree with the screen it came
     * from. No other hand-over may be in flight either.
     *
     * A refresh of the question already on screen leaves both answers where they are, so the two buttons stay
     * live through it: the document the reader is looking at is still the one they may have.
     */
    val canExport: Boolean get() = report != null && pages != null && !previewing && !saving && !staging

    /** How many lines the csv view holds, which is what the "first of these" caption counts against. */
    val csvRowCount: Int get() = csvLines.size

    /**
     * Whether the screen may ask for a bigger sheet.
     *
     * The buttons that do this are disabled at the ends of the range rather than left to do nothing, because a
     * control that answers a press with silence is indistinguishable from one that has broken.
     */
    val canZoomIn: Boolean get() = zoom < ReportPageFit.MAX_ZOOM
    val canZoomOut: Boolean get() = zoom > ReportPageFit.MIN_ZOOM

    /**
     * Whether the screen is holding sheets rather than rows.
     *
     * The zoom belongs to a sheet: sideways scroll, the page gap, a bigger drawing of the same document. While
     * the rows are on screen there is nothing for those controls to act on, and a control that answers a press
     * with silence is indistinguishable from one that has broken — so they are put away with the pages they
     * belong to, and come back when the reader asks for the document again.
     */
    val showingPages: Boolean get() = format == ReportFormat.PDF && pages != null

    /**
     * The lines the csv view prints: the file's own, from the beginning, capped at what a preview may hold.
     *
     * Empty while the sheets are on screen, so the fragment shows one of the two and never both at once.
     */
    val csvPreview: List<String> get() =
        if (format == ReportFormat.CSV) csvLines.take(CSV_ROWS_SHOWN) else emptyList()

    /**
     * Whether the preview is the whole file rather than its beginning.
     *
     * The note under the preview has to say which of the two the reader is looking at, and it cannot work that
     * out from a list that has already been cut — hence the cap and the answer living in the same place.
     */
    val csvRowsAllShown: Boolean get() = csvLines.size <= CSV_ROWS_SHOWN

    companion object {

        /**
         * How many csv lines the screen prints.
         *
         * A long report's rows run into the hundreds, and a screen that built all of them into one span of text
         * would spend longer laying out the preview than reading the report was. The cap is stated here rather
         * than in the fragment because the note above the rows counts against it.
         */
        const val CSV_ROWS_SHOWN = 120
    }
}

/**
 * The screen's question, and the reason it is being asked again.
 *
 * A read is only meaningful as a whole question: a report for June in Tokyo, answered to a screen that has
 * meanwhile moved to March in Berlin, is two answers to no question at all. [attempt] is what makes the same
 * question worth asking a second time after a failure.
 */
private data class Question(
    val period: ReportPeriod,
    val zone: ZoneId,
    val attempt: Int
)

/**
 * Asking for a report, choosing when it starts and ends and in which calendar, and handing the result over.
 *
 * The screen owns three decisions and no arithmetic. It decides which [ReportPeriod] is asked about, which zone
 * that period is resolved in, and which of the two file kinds a save or a share produces. Everything else
 * arrives already answered: the figures from the report repository, the document from Part 4's layout, the pages
 * from its pagination, the rows from its csv writer, the bytes from its exporter, and the file name from
 * [ReportNaming]. Nothing here counts a task, adds up a duration, works out a streak or repairs a missing value,
 * because a screen that did would be a second implementation of figures that already have one, and the two would
 * disagree in front of the reader.
 *
 * The layout pass runs on [Dispatchers.Default] and the write on the exporter's own dispatcher, so the main
 * thread only ever redraws what the state already holds.
 *
 * **The profile is part of the question.** The read goes through [ReportRepository.reportFor] with the id of the
 * profile whose name is printed above the figures, rather than through the live `observe` flow, which resolves
 * the account inside itself. That is a deliberate trade: with the live flow the name would be read at a
 * different moment than the figures were, and an account switched between the two would leave one profile's
 * numbers under another profile's name until the subscription caught up. A report is a document the reader opens,
 * moves through and exports, so it is re-read when the question changes, when the profile changes, when the
 * reader retries, and when the screen comes back into view — which [refresh] is for.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    private val exportRepository: ReportExportRepository,
    private val sessionRepository: SessionRepository,
    private val timeBase: TimeBase
) : ViewModel() {

    private val deviceZone = CalendarMath.systemZone()

    /** The day the reader is standing on: what "this week", "this month" and "this year" are measured from. */
    private val anchor = MutableStateFlow(today(deviceZone))

    private val question = MutableStateFlow(
        Question(ReportPeriod.Month(YearMonth.from(anchor.value)), deviceZone, 0)
    )

    private val _state = MutableStateFlow(
        ReportState(
            kind = ReportKind.MONTH,
            period = question.value.period,
            anchor = anchor.value,
            zone = deviceZone,
            followsDeviceZone = true,
            zoneLabel = zoneLabel(deviceZone.id),
            format = ReportFormat.PDF
        )
    )
    val state: StateFlow<ReportState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(question, sessionRepository.currentUser) { asked, user -> asked to user }
                .distinctUntilChanged()
                .collectLatest { (asked, user) -> read(asked, user) }
        }
    }

    // -- the question -------------------------------------------------------------------------------

    /**
     * Moves the report to another of the five kinds, keeping the days the reader is looking at.
     *
     * A chip is not a reset: sent from the third of June to a weekly report, the reader means the week that
     * holds the third. Being returned to today by a chip they did not ask to be returned to is §9's "потеря
     * периода".
     */
    fun chooseKind(kind: ReportKind) {
        val period = periodFor(kind, anchor.value) ?: return
        ask(kind = kind, period = period, zone = _state.value.zone)
    }

    /** Names the day the report is anchored on: a day, the day inside a week, the month, or the year. */
    fun chooseDate(date: LocalDate) {
        anchor.value = date
        val state = _state.value
        val period = periodFor(state.kind, date) ?: return
        ask(kind = state.kind, period = period, zone = state.zone, anchor = date)
    }

    /**
     * The range the reader chose, both ends included.
     *
     * A reversed pair is refused by [ReportPeriod.range], and the refusal is what the screen says while the
     * period on screen does not move: a report quietly re-ordered into the window nobody asked for prints
     * figures that look reasonable and answer a different question.
     */
    fun chooseRange(from: LocalDate, to: LocalDate) {
        when (val read = ReportPeriod.range(from, to)) {
            is ReportPeriodRead.Accepted -> {
                anchor.value = from
                ask(
                    kind = ReportKind.RANGE,
                    period = read.period,
                    zone = _state.value.zone,
                    anchor = from
                )
            }

            is ReportPeriodRead.Rejected -> _state.update {
                it.copy(feedback = ReportFeedback.PeriodRejected(rejected(read.reason)))
            }
        }
    }

    /** Counts the same period in another zone, whose calendar decides where the period's days begin and end. */
    fun chooseZone(zone: ZoneId) {
        val state = _state.value
        ask(
            kind = state.kind,
            period = state.period,
            zone = zone,
            followsDeviceZone = zone == deviceZone
        )
    }

    /** Gives the device's own timezone back, which is where every report starts. */
    fun useDeviceZone() {
        val state = _state.value
        ask(kind = state.kind, period = state.period, zone = deviceZone, followsDeviceZone = true)
    }

    /**
     * Which of the two files the reader wants, and which view of the report is drawn.
     *
     * Nothing is re-read and nothing is re-laid-out, because both views of this report were built when it
     * arrived — a format chip is the cheapest thing on the screen. The mime type and the suggested name follow
     * from the [ReportFormat] the exporter owns.
     */
    fun chooseFormat(format: ReportFormat) {
        _state.update { it.copy(format = format) }
    }

    /**
     * Bigger sheets, or smaller ones.
     *
     * Nothing is re-read and nothing is re-laid-out: the pages are the document's and the level only decides how
     * large they are drawn, which is why the buttons are enabled at the ends of the range instead of pressing
     * into a silence the reader has to interpret.
     */
    fun zoomIn() {
        _state.update { it.copy(zoom = ReportPageFit.clamp(it.zoom + 1)) }
    }

    fun zoomOut() {
        _state.update { it.copy(zoom = ReportPageFit.clamp(it.zoom - 1)) }
    }

    /** Asks the current question again, and takes the complaints off the screen while it does. */
    fun retry() {
        _state.update { it.copy(feedback = null, previewError = null) }
        askAgain()
    }

    /**
     * Re-reads for the screen coming back into view, leaving a message the reader has not seen yet alone.
     *
     * A report read before the reader went elsewhere is now a report read some minutes ago, and the work done in
     * the meantime belongs in it.
     *
     * Not while a hand-over is in flight. The screen is resumed both when the reader returns and when the picker
     * hands the file back, so a refresh asked for at that moment would be a second question started against the
     * very report being written — and the answer to a save is one the screen has to wait for rather than talk
     * over. The next return asks again.
     */
    fun refresh() {
        val state = _state.value
        if (state.saving || state.staging) return
        askAgain()
    }

    // -- the hand-overs -----------------------------------------------------------------------------

    /**
     * The name the picker is offered.
     *
     * Asked of the exporter's own [ReportNaming] rather than assembled here, so the file the reader is offered
     * is named the way every other report of this period is named — and there is no name to offer for a report
     * the screen does not have yet.
     */
    fun suggestedName(): String? = _state.value.report?.let { ReportNaming.suggest(it.window, _state.value.format) }

    /**
     * Writing the report on screen into the place the reader pointed at.
     *
     * The report object rather than the period and the zone, because the file has to hold what the reader was
     * looking at when Save was pressed. A re-read at this moment could answer about a period the chips no longer
     * show, or about a profile that changed while the picker was open, and either would be a file that disagrees
     * with the screen it came from.
     *
     * Success is whatever the exporter reports after its stream is closed, which is the only moment bytes are
     * really on disk. Nothing here claims a file before that answer arrives.
     */
    fun saveTo(uri: Uri?) {
        if (uri == null) {
            // The picker's own answer to being closed: no file, no error, nothing to show.
            _state.update { it.copy(saving = false, feedback = ReportFeedback.Cancelled) }
            return
        }
        val state = _state.value
        val report = state.report
        if (report == null) {
            _state.update { it.copy(feedback = failed(ReportFailure.NO_PROFILE)) }
            return
        }
        if (state.saving) return

        _state.update {
            it.copy(saving = true, feedback = null, readError = null, previewError = null)
        }
        viewModelScope.launch {
            val outcome = exportRepository.save(report, uri, state.format)
            _state.update { it.copy(saving = false, feedback = describe(outcome)) }
        }
    }

    /**
     * Staging a copy and building the Sharesheet request for it.
     *
     * Two steps, because a share is only as good as the file behind the uri: the copy is written first and the
     * hand-over is offered only once it exists. The staged file is left in the cache afterwards — the receiving
     * app reads on its own schedule, and a copy taken away while it is being opened is a share that fails —
     * under the cap [ReportExportRepository] already keeps.
     */
    fun share() {
        val state = _state.value
        val report = state.report
        if (report == null) {
            _state.update { it.copy(feedback = failed(ReportFailure.NO_PROFILE)) }
            return
        }
        if (state.staging || state.saving) return

        _state.update { it.copy(staging = true, feedback = null, share = null) }
        viewModelScope.launch {
            // Two steps, two sentences. A copy that could not be written and a copy this app's own provider
            // cannot point at are different complaints, and the second one is not a story about the file being
            // missing — saying so would send the reader to retry something that had already worked.
            val staged = guarded { exportRepository.stage(report, state.format).getOrThrow() }
            val copy = staged.getOrNull()
            if (copy == null) {
                // The exporter's own four reasons are a taxonomy of saving to a place the reader chose; a copy
                // this app never handed out has no location to blame, and the sentence the reader needs is the
                // one about the copy. WRITE_INTERRUPTED is the reason that says nothing else was promised.
                _state.update {
                    it.copy(
                        staging = false,
                        share = null,
                        feedback = failed(ReportFailure.WRITE_INTERRUPTED, R.string.report_fail_staging)
                    )
                }
                return@launch
            }

            val handed = guarded {
                exportRepository.shareIntent(exportRepository.uriFor(copy.file), state.format)
            }
            val intent = handed.getOrNull()
            if (intent == null) {
                _state.update {
                    it.copy(
                        staging = false,
                        share = null,
                        feedback = failed(ReportFailure.WRITE_INTERRUPTED, R.string.report_fail_handover)
                    )
                }
            } else {
                _state.update { it.copy(staging = false, feedback = null, share = intent) }
            }
        }
    }

    /** The sheet has been launched: it is a one-shot, and launching it twice is the bug §9 names. */
    fun shareDelivered() {
        _state.update { it.copy(share = null) }
    }

    /** The message has been shown. */
    fun feedbackShown() {
        _state.update { it.copy(feedback = null) }
    }

    /**
     * What the last hand-over has to say, as a resource the screen turns into words.
     *
     * Nothing, for a cancellation: §6 is explicit that closing the picker is not an error, and a screen with a
     * message field to fill has to be able to be told not to fill it.
     */
    @StringRes fun readerMessageRes(): Int? = _state.value.feedback?.messageRes

    /**
     * The zones the picker offers: the device's own first, the rest in their own order.
     *
     * Every id `java.time` knows is on the list — that is the long list §3 asks a search box for, and a handful
     * of favourites would leave a reader living anywhere else with no correct calendar to choose. The device
     * zone is offered even when it cannot match the query, because a picker that hides its own default is a
     * picker that cannot be got back out of.
     */
    fun zoneOptions(query: String): List<ReportZone> {
        val device = ReportZone(deviceZone.id, zoneLabel(deviceZone.id))
        val matches = ZoneId.getAvailableZoneIds().asSequence()
            .filter { it != deviceZone.id && it.contains(query, ignoreCase = true) }
            .map { ReportZone(it, zoneLabel(it)) }
            .sortedBy { it.id }
            .toList()
        return listOf(device) + matches
    }

    // -- the read and the layout --------------------------------------------------------------------

    private fun ask(
        kind: ReportKind,
        period: ReportPeriod,
        zone: ZoneId,
        anchor: LocalDate = this.anchor.value,
        followsDeviceZone: Boolean = _state.value.followsDeviceZone
    ) {
        _state.update {
            it.copy(
                kind = kind,
                period = period,
                anchor = anchor,
                zone = zone,
                zoneLabel = zoneLabel(zone.id),
                followsDeviceZone = followsDeviceZone,
                feedback = null
            )
        }
        begin()
        question.update { it.copy(period = period, zone = zone) }
    }

    private fun askAgain() {
        // A refresh is not a new question. The period, the zone and the profile have not moved, so the figures
        // already on screen are not mislabelled — they are only a few minutes old — and they stay until the newer
        // ones arrive. Putting them away would blink the document out for a reader who only stepped into the
        // picker and back, would grey the two hand-overs at the moment they are most likely to be pressed, and
        // would leave a save handed over to a screen that no longer held the report it was writing.
        _state.update {
            it.copy(
                reading = true,
                previewing = false,
                readError = null,
                previewError = null,
                emptyMessage = null
            )
        }
        question.update { it.copy(attempt = it.attempt + 1) }
    }

    /**
     * Puts the previous answer away and shows the spinner.
     *
     * A figure from the old question under a chip naming the new one is a mislabelled number, and the screen has
     * a progress bar whose whole meaning is "this is on its way" — the same deliberate flicker the dashboard
     * accepts for the same reason.
     */
    private fun begin() {
        _state.update {
            it.copy(
                report = null,
                pages = null,
                csvLines = emptyList(),
                reading = true,
                previewing = false,
                emptyMessage = null,
                readError = null
            )
        }
    }

    private suspend fun read(asked: Question, user: User?) {
        // Another account is another question whatever the chips say, so the answer that belonged to the
        // previous one goes before the new read starts. Without this the figures of the profile the reader has
        // just left would stay on screen, under the name of the one they signed in as, for as long as the read
        // takes — and a stale number is worse than a missing one because it looks like it still holds.
        if (user?.id != _state.value.profileId) begin()

        if (user == null) {
            // Nobody is signed in. That is an answer about the account rather than a failure to read one, so it
            // goes in the field the screen shows as an empty report and not in the one that offers a retry.
            _state.update {
                it.copy(
                    profileId = null,
                    profileName = null,
                    report = null,
                    pages = null,
                    csvLines = emptyList(),
                    reading = false,
                    previewing = false,
                    readError = null,
                    emptyMessage = R.string.report_empty_no_profile
                )
            }
            return
        }

        val result = guarded { reportRepository.reportFor(user.id, asked.period, asked.zone) }

        // The question moved on while this was in flight, so the answer is about a report the screen no longer
        // shows. Dropping it is not losing data: the subscription is already running the one that answers what
        // the reader is looking at now.
        val current = question.value
        if (current.period != asked.period || current.zone != asked.zone) return
        if (sessionRepository.currentUser.value?.id != user.id) return

        val failure = result.exceptionOrNull()
        if (failure != null) {
            _state.update {
                it.copy(
                    profileId = user.id,
                    profileName = user.name,
                    report = null,
                    pages = null,
                    csvLines = emptyList(),
                    reading = false,
                    previewing = false,
                    readError = R.string.report_fail_read,
                    emptyMessage = null
                )
            }
            return
        }

        val report = result.getOrThrow()
        _state.update {
            it.copy(
                profileId = user.id,
                profileName = user.name,
                report = report,
                reading = false,
                previewing = true,
                readError = null,
                emptyMessage = null
            )
        }
        layOut(report, user.name)
    }

    /**
     * Both views of one report, off the main thread and in one pass.
     *
     * The pages come from the document the layout builds and the pagination breaks, measured by the same
     * [PaintReportMeasurer] that writes the pdf — so what the reader sees is the file they are about to save,
     * page for page, rather than a screen's approximation of it. The rows are the csv writer's own, handed over
     * untouched: a preview that re-formatted them would be a second csv file, and the one inside the file is the
     * one that counts.
     */
    private suspend fun layOut(report: Report, profileName: String?) {
        val built = guarded {
            val pages = exportRepository.pages(report, profileName)
            Laid(pages, csvLinesOf(report))
        }
        // A report that is no longer the one on screen has no business being drawn over the one that is.
        if (_state.value.report !== report) return

        val failure = built.exceptionOrNull()
        if (failure != null) {
            _state.update {
                it.copy(previewing = false, previewError = R.string.report_fail_preview)
            }
        } else {
            val laid = built.getOrThrow()
            _state.update {
                it.copy(
                    pages = laid.pages,
                    csvLines = laid.lines,
                    previewing = false,
                    previewError = null
                )
            }
        }
    }

    /** The two views one report was laid out into. */
    private data class Laid(
        val pages: PagedReport,
        val lines: List<String>
    )

    /**
     * [block], as a result — except when the screen stopped asking for it.
     *
     * A cancelled read is not a failed read. Swallowing the cancellation would let a question the reader has
     * already moved away from report itself as "could not be read" over the answer that arrived after it, so the
     * one exception type is handed straight back to the coroutine that asked for this work.
     */
    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // -- words ---------------------------------------------------------------------------------------

    private fun failed(reason: ReportFailure): ReportFeedback.Failed = failed(
        reason,
        when (reason) {
            ReportFailure.NO_PROFILE -> R.string.report_fail_no_profile
            ReportFailure.LOCATION_UNWRITABLE -> R.string.report_fail_unwritable
            ReportFailure.WRITE_INTERRUPTED -> R.string.report_fail_interrupted
            ReportFailure.EMPTY_FILE -> R.string.report_fail_empty
        }
    )

    private fun failed(reason: ReportFailure, @StringRes message: Int) =
        ReportFeedback.Failed(message, reason)

    /**
     * The exporter's answer, in the shape the screen reports it.
     *
     * [ReportExport.Saved] is the only one that may say a file exists, and it says how much of one: the byte
     * count is the exporter's own, taken through the stream as it was written, with its unit printed beside it.
     */
    private fun describe(outcome: ReportExport): ReportFeedback = when (outcome) {
        is ReportExport.Saved -> ReportFeedback.Saved(size = outcome.bytes)
        ReportExport.Cancelled -> ReportFeedback.Cancelled
        is ReportExport.Failed -> failed(outcome.reason)
    }

    /** One sentence per reason the period type has, because each one asks for a different fix. */
    @StringRes private fun rejected(reason: ReportPeriodRejection): Int =
        when (reason) {
            ReportPeriodRejection.ENDS_BEFORE_IT_STARTS -> R.string.report_period_rejected
            ReportPeriodRejection.YEAR_OUT_OF_CALENDAR_RANGE -> R.string.report_year_out_of_range
        }

    /**
     * The zone's id beside the offset it is standing at right now.
     *
     * The offset is asked of the zone rather than assumed, which is the whole reason a report is counted in a
     * zone instead of an offset: the same zone answers a different hour in December than in June, and a list of
     * fixed offsets would let a reader choose the wrong one for half the year without noticing.
     */
    private fun zoneLabel(id: String): String {
        val zoneId = ZoneId.of(id)
        val offset = ZonedDateTime.now(zoneId).offset
        // `Z` is how java.time spells no offset at all, which is not a sentence a reader can compare against a
        // clock on the wall; every other offset already prints signed, so only that one case needs a word.
        val signed = if (offset.totalSeconds == 0) "UTC" else "UTC$offset"
        return "$id · $signed"
    }

    private fun today(zone: ZoneId): LocalDate =
        LocalDate.ofEpochDay(WorkTimeMath.epochDayOf(timeBase.wallMillis(), zone))

    /**
     * The csv file's own lines, taken from the writer rather than worked out again.
     *
     * Asked of [CsvReport.lines] rather than of its rows, because the rows are only half of a csv file: which
     * fields carry quotes is what makes the file the document the reader saves. And asked as lines rather than by
     * splitting the finished bytes, because a quoted field holding a line break is one row of the file and would
     * become two lines of the preview — a screen that misstates how many rows it is showing is worse than one
     * that shows none.
     */
    private fun csvLinesOf(report: Report): List<String> = CsvReport.lines(report)

    private companion object {

        /**
         * The period a chip means for the day the reader is standing on, or nothing when the calendar cannot
         * name it — a year outside what `java.time` builds has no report to ask for.
         *
         * A range defaults to the whole month the anchor sits in, because that is the one span a reader who has
         * named a day but no second day has actually asked about; the two pickers beside it are then free to
         * narrow it.
         */
        fun periodFor(kind: ReportKind, anchor: LocalDate): ReportPeriod? = when (kind) {
            ReportKind.DAY -> ReportPeriod.Day(anchor)
            ReportKind.WEEK -> ReportPeriod.weekOf(anchor)
            ReportKind.MONTH -> ReportPeriod.Month(YearMonth.from(anchor))
            ReportKind.YEAR -> (ReportPeriod.year(anchor.year) as? ReportPeriodRead.Accepted)?.period
            ReportKind.RANGE -> (
                ReportPeriod.range(anchor.withDayOfMonth(1), anchor.withDayOfMonth(anchor.lengthOfMonth()))
                    as? ReportPeriodRead.Accepted
                )?.period
        }
    }
}
