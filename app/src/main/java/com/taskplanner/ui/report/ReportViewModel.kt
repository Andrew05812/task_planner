package com.taskplanner.ui.report

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.taskplanner.data.report.export.ReportFormatting
import com.taskplanner.data.report.export.ReportLayout
import com.taskplanner.data.report.export.ReportNaming
import com.taskplanner.data.report.export.ReportPagination
import com.taskplanner.data.repository.ReportRepository
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTimeMath
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.withContext

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

    /** Bytes are on disk behind a closed stream. [size] is the exporter's own count, never the screen's guess. */
    data class Saved(val size: Long, val message: String) : ReportFeedback()

    /** The reader closed the picker. There is no sentence for this one, and showing one is the bug. */
    data object Cancelled : ReportFeedback()

    data class Failed(val reason: ReportFailure, val message: String) : ReportFeedback()

    /** The days were named the wrong way round, or the year is outside the calendar. Nothing was guessed. */
    data class PeriodRejected(val message: String) : ReportFeedback()

    /**
     * The words for the reader, or none.
     *
     * A cancellation is the case §6 names — closing the picker is not an error — and the screen has to be able
     * to tell it apart from a success without re-deriving the difference from the shape of a message.
     */
    val messageForReader: String? get() = when (this) {
        Cancelled -> null
        is Saved -> message
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
    val zone: ZoneId,

    /** Whether the zone on screen is the device's own, which is what the picker marks as where it started. */
    val followsDeviceZone: Boolean,
    val format: ReportFormat,

    val profileId: Long? = null,
    val profileName: String? = null,

    val report: Report? = null,
    val pages: PagedReport? = null,
    val csvRows: List<List<String>> = emptyList(),
    val csvDelimiter: Char = ',',

    /** A read for the question on screen has not answered yet. */
    val reading: Boolean = true,
    /** The read answered and its pages are being laid out. */
    val previewing: Boolean = false,
    val saving: Boolean = false,
    val staging: Boolean = false,

    /** The Sharesheet request, handed over once: the fragment launches it and the screen forgets it. */
    val share: Intent? = null,
    val feedback: ReportFeedback? = null,
    val readError: String? = null,
    val previewError: String? = null,

    /** Why there is nothing to show when nothing failed: nobody is signed in, so there is no report to read. */
    val emptyMessage: String? = null
) {
    val isLoading: Boolean get() = reading || previewing

    /**
     * Whether a file may be asked for right now.
     *
     * A report has to be on screen, its pages have to have landed so the reader knows what they are saving, and
     * no other hand-over may be in flight. A save started against a half-loaded screen would write a file for a
     * period the reader is about to change.
     */
    val canExport: Boolean get() = report != null && !isLoading && !saving && !staging

    /** How many rows the csv view holds, which is what the "first of these" caption counts against. */
    val csvRowCount: Int get() = csvRows.size
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
    @ApplicationContext private val context: Context,
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
            zone = deviceZone,
            followsDeviceZone = true,
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
        ask(kind = state.kind, period = period, zone = state.zone)
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
                ask(kind = ReportKind.RANGE, period = read.period, zone = _state.value.zone)
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

    /** Asks the current question again, and takes the complaints off the screen while it does. */
    fun retry() {
        _state.update { it.copy(feedback = null, previewError = null) }
        askAgain()
    }

    /**
     * Re-reads for the screen coming back into view, leaving a message the reader has not seen yet alone.
     *
     * A report read before the reader went elsewhere is now a report read some minutes ago, and the work done
     * in the meantime belongs in it.
     */
    fun refresh() {
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

    /** The words for the reader about the last hand-over, or none for a cancellation. */
    fun readerMessage(): String? = _state.value.feedback?.messageForReader

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
        followsDeviceZone: Boolean = _state.value.followsDeviceZone
    ) {
        _state.update {
            it.copy(
                kind = kind,
                period = period,
                zone = zone,
                followsDeviceZone = followsDeviceZone,
                feedback = null
            )
        }
        begin()
        question.update { it.copy(period = period, zone = zone) }
    }

    private fun askAgain() {
        begin()
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
                csvRows = emptyList(),
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
                    csvRows = emptyList(),
                    reading = false,
                    previewing = false,
                    readError = null,
                    emptyMessage = context.getString(R.string.report_empty_no_profile)
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
                    csvRows = emptyList(),
                    reading = false,
                    previewing = false,
                    readError = context.getString(R.string.report_fail_read),
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
            withContext(Dispatchers.Default) {
                val palette = ReportLayout.palette(context)
                val pages = ReportPagination.paginate(
                    document = ReportLayout.build(context, report, profileName, palette),
                    measurer = PaintReportMeasurer(),
                    palette = palette
                )
                Laid(pages, CsvReport.rows(report), CsvReport.defaultDelimiter())
            }
        }
        // A report that is no longer the one on screen has no business being drawn over the one that is.
        if (_state.value.report !== report) return

        val failure = built.exceptionOrNull()
        if (failure != null) {
            _state.update {
                it.copy(previewing = false, previewError = context.getString(R.string.report_fail_preview))
            }
        } else {
            val laid = built.getOrThrow()
            _state.update {
                it.copy(
                    pages = laid.pages,
                    csvRows = laid.rows,
                    csvDelimiter = laid.delimiter,
                    previewing = false,
                    previewError = null
                )
            }
        }
    }

    /** The two views one report was laid out into. */
    private data class Laid(
        val pages: PagedReport,
        val rows: List<List<String>>,
        val delimiter: Char
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

    private fun failed(reason: ReportFailure, message: Int) =
        ReportFeedback.Failed(reason, context.getString(message))

    /**
     * The exporter's answer, in the shape the screen reports it.
     *
     * [ReportExport.Saved] is the only one that may say a file exists, and it says how much of one: the byte
     * count is the exporter's own, taken through the stream as it was written, with its unit printed beside it.
     */
    private fun describe(outcome: ReportExport): ReportFeedback = when (outcome) {
        is ReportExport.Saved -> ReportFeedback.Saved(
            size = outcome.bytes,
            message = context.getString(
                R.string.report_saved_format,
                context.getString(R.string.report_bytes_format, ReportFormatting.count(outcome.bytes))
            )
        )

        ReportExport.Cancelled -> ReportFeedback.Cancelled
        is ReportExport.Failed -> failed(outcome.reason)
    }

    /** One sentence per reason the period type has, because each one asks for a different fix. */
    private fun rejected(reason: ReportPeriodRejection): String = context.getString(
        when (reason) {
            ReportPeriodRejection.ENDS_BEFORE_IT_STARTS -> R.string.report_period_rejected
            ReportPeriodRejection.YEAR_OUT_OF_CALENDAR_RANGE -> R.string.report_year_out_of_range
        }
    )

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
