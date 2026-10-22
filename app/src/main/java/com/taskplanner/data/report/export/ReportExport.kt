package com.taskplanner.data.report.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.taskplanner.BuildConfig
import com.taskplanner.data.report.Report
import com.taskplanner.data.report.ReportPeriod
import com.taskplanner.data.report.ReportWindow
import com.taskplanner.data.repository.ReportRepository
import com.taskplanner.data.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The two kinds of file a report can become.
 *
 * The pair carries everything a caller needs to ask the system for a place to save: a document picker is given
 * a mime type and is offered a name, and both of those belong to the format rather than to the screen that
 * happens to be showing the report. Which is why they are here rather than in the fragment Part 5 will write —
 * a mime type in a layout file is a mime type no test can check.
 */
enum class ReportFormat(val mimeType: String, val extension: String) {
    /** Rows for a spreadsheet or a script: the report as data. */
    CSV("text/csv", "csv"),

    /** Pages for a person: the report as a document. */
    PDF("application/pdf", "pdf")
}

/**
 * The name a report is offered under.
 *
 * ASCII and ISO-ordered, whatever the device's language, because this string survives the report it names: a
 * folder of exports sorts into the order the periods ran if the dates lead, and a filename written in the
 * reader's own script is one that a mail gateway or a shared drive can mangle. The period's own shape decides
 * how much of the date is worth putting down — a month report names its year and month, a day report its whole
 * date, a chosen range both ends — and the timezone is left out because a reader who needs it has it inside the
 * file, where it belongs to the figures rather than to the name.
 */
object ReportNaming {

    fun suggest(window: ReportWindow, format: ReportFormat): String {
        val stem = when (window.period) {
            is ReportPeriod.Day -> window.period.date.toString()
            is ReportPeriod.Week -> window.firstDate.toString()
            is ReportPeriod.Month ->
                "%04d-%02d".format(Locale.ROOT, window.period.month.year, window.period.month.monthValue)
            is ReportPeriod.Year -> window.period.year.toString()
            is ReportPeriod.Range -> "${window.period.from}-${window.period.to}"
        }
        return "taskplanner-report-$stem.${format.extension}"
    }
}

/**
 * A copy of a report the app holds for itself, in its own cache, waiting to be handed over.
 *
 * The file rather than a uri, because the uri is minted at the moment of the share and not before: a grant is
 * tied to the intent that carries it, and a uri handed out early is a right given to nobody. Keeping the two
 * steps apart is also what lets the cache policy — the naming, the cap, the pruning — be checked without a
 * content provider running.
 */
data class StagedReport(val file: File, val bytes: Long)

/** How an export ended: a file the reader can open, a choice they never made, or something that went wrong. */
sealed class ReportExport {

    /** The bytes are written and the stream is closed. [uri] is where the reader's picker put them. */
    data class Saved(val uri: Uri, val bytes: Long) : ReportExport()

    /** The user opened the picker and closed it. Nothing was written, and nothing is wrong. */
    data object Cancelled : ReportExport()

    data class Failed(val reason: ReportFailure, val detail: String? = null) : ReportExport()
}

/**
 * The four ways an export can fail, named because a screen has to say which one happened.
 *
 * "The report could not be saved" is a sentence that leaves the reader doing the same thing whatever the cause,
 * and the four causes want four different things from them: sign in, choose somewhere else, try again, or
 * accept that this device could not draw the page. [EMPTY_FILE] is the one nobody expects and the platform
 * makes necessary — a document writer that hands back no bytes has not failed loudly, and a report that
 * promised success at that moment would be a file the reader finds empty later.
 */
enum class ReportFailure {
    NO_PROFILE,
    LOCATION_UNWRITABLE,
    WRITE_INTERRUPTED,
    EMPTY_FILE
}

/**
 * Writing a report to a file the user chose, and handing one to another app.
 *
 * The mechanism is the one this app already uses for its backup file, and deliberately so: the Storage Access
 * Framework gives the app a uri it cannot resolve and a stream it must close, and a second way of saving a
 * file would be a second set of the four mistakes that matter here — asking for a storage permission the
 * picker makes unnecessary, writing to a path somebody typed, reporting success before the stream is closed,
 * and leaving a half-written file behind as the only sign that anything was attempted.
 *
 * So [save] takes the uri the system's picker returned, works off the main thread, and answers with a
 * [ReportExport] that says which of those four things happened. Nothing in it knows about a screen, a
 * coroutine scope, or a snackbar.
 */
@Singleton
class ReportExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportRepository: ReportRepository,
    private val sessionRepository: SessionRepository
) {

    /**
     * The report for [period] in [zone], written into [uri] as [format].
     *
     * A null [uri] is the picker's own answer to a cancelled dialog, and it is answered here rather than left
     * for every caller to remember: the difference between "the user changed their mind" and "the file could
     * not be written" is the difference between a quiet screen and an error nobody made.
     */
    suspend fun save(period: ReportPeriod, zone: ZoneId, uri: Uri?, format: ReportFormat): ReportExport {
        if (uri == null) return ReportExport.Cancelled
        val report = reportRepository.report(period, zone) ?: return ReportExport.Failed(ReportFailure.NO_PROFILE)
        return save(report, uri, format)
    }

    /** An already-built report, written where the reader pointed. */
    suspend fun save(report: Report, uri: Uri, format: ReportFormat): ReportExport =
        withContext(Dispatchers.IO) {
            val stream = try {
                context.contentResolver.openOutputStream(uri, WRITE_MODE)
            } catch (e: SecurityException) {
                return@withContext ReportExport.Failed(ReportFailure.LOCATION_UNWRITABLE, e.message)
            } catch (e: FileNotFoundException) {
                // The place the reader picked is not there, or does not take this kind of file. Nothing has
                // been written, so the honest answer is about the location rather than about the writing.
                return@withContext ReportExport.Failed(ReportFailure.LOCATION_UNWRITABLE, e.message)
            } catch (e: IOException) {
                return@withContext ReportExport.Failed(ReportFailure.WRITE_INTERRUPTED, e.message)
            } catch (e: IllegalArgumentException) {
                return@withContext ReportExport.Failed(ReportFailure.LOCATION_UNWRITABLE, e.message)
            }
            if (stream == null) return@withContext ReportExport.Failed(ReportFailure.LOCATION_UNWRITABLE)

            try {
                stream.use { opened ->
                    val bytes = write(report, opened, format)
                    if (bytes == 0L) ReportExport.Failed(ReportFailure.EMPTY_FILE)
                    else ReportExport.Saved(uri, bytes)
                }
            } catch (e: IOException) {
                ReportExport.Failed(ReportFailure.WRITE_INTERRUPTED, e.message)
            } catch (e: IllegalStateException) {
                // The platform's document writer answers with this rather than with a message of its own when
                // it could not build a page at all, which is a failure to report and not a crash to rethrow.
                ReportExport.Failed(ReportFailure.EMPTY_FILE, e.message)
            }
        }

    /** The bytes of one report, in the order the format wants them. */
    private suspend fun write(report: Report, out: java.io.OutputStream, format: ReportFormat): Long = when (format) {
        ReportFormat.CSV -> {
            val bytes = CsvReport.bytes(report)
            out.write(bytes)
            out.flush()
            bytes.size.toLong()
        }

        ReportFormat.PDF -> {
            val surface = PdfDocumentSurface()
            PdfReport.draw(paginate(report), surface)
            surface.finish(out)
        }
    }

    /**
     * A copy of the report in the app's own cache, as the file and the bytes it holds.
     *
     * Sharing has to be a copy because a uri is a door rather than a hand-over: the picker's uri belongs to the
     * document provider that minted it and says nothing about what a receiving app may read, so the only way to
     * send a file to somebody else's app is to hold one the app's own [FileProvider] can grant over. That grant
     * is temporary by construction — [shareIntent] sets the flag that limits it to this one intent — and it is
     * pointed at one directory, whose path rule in `file_paths.xml` reaches the exports and nothing else. The
     * database, the preferences and the rest of the app's files are not under it, which is what §6.3's
     * "не предоставляй доступ к внутренней БД" asks of a share button.
     *
     * The file is not deleted when the share is handed over, and that is a decision rather than an oversight:
     * the receiving app reads on its own schedule, and a copy taken away while it is still opening is a share
     * that fails. The directory is capped instead — the newest [MAX_STAGED] exports are kept, older ones go —
     * on every stage, and [pruneStaged] offers the same sweep to a caller that wants it at start-up. Which
     * caller arrives with the report screen in Part 5; nothing is asked of the app's own start-up until then.
     */
    suspend fun stage(report: Report, format: ReportFormat): Result<StagedReport> =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, STAGE_DIR).apply { mkdirs() }
            val target = File(directory, ReportNaming.suggest(report.window, format))
            try {
                val bytes = target.outputStream().use { out -> write(report, out, format) }
                if (bytes == 0L) {
                    target.delete()
                    Result.failure(IOException("the export produced no bytes"))
                } else {
                    // Pruned after the write rather than before it, so the rule the reader is entitled to is
                    // the one the code actually keeps: however many exports have been staged, no more than the
                    // newest few are on the cache when this call returns.
                    prune(directory)
                    Result.success(StagedReport(target, bytes))
                }
            } catch (e: IOException) {
                target.delete()
                Result.failure(e)
            } catch (e: IllegalStateException) {
                // The document writer's own answer to a page it could not build. Staged or saved, an export
                // that produced nothing is a failure to report, never a file to promise.
                target.delete()
                Result.failure(IOException(e.message ?: "the document writer produced no file"))
            }
        }

    /**
     * The content uri a share is built on: the staged file, seen through this app's own provider.
     *
     * It is a `content://` uri by construction — that is all a [FileProvider] can give out — and it is taken
     * when the intent is made rather than when the file was written, so the read right it carries lasts exactly
     * as long as the hand-over it travels with.
     */
    fun shareUri(staged: StagedReport): Uri = uriFor(staged.file)

    /**
     * The Sharesheet request for a staged file.
     *
     * `FLAG_GRANT_READ_URI_PERMISSION`, because the uri alone would only be a name the receiving app cannot
     * open; the chooser wrapper, because §6.3 asks for the system's sheet rather than for this app's opinion
     * about which apps deserve the file; and no promise on the other side of either, because handing a file to
     * the sheet is the end of what this app can see.
     */
    fun shareIntent(uri: Uri, format: ReportFormat): Intent =
        Intent(Intent.ACTION_SEND)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .let { Intent.createChooser(it, context.getString(com.taskplanner.R.string.action_share)) }

    /** The content uri of a file this app's own provider can hand over. Never a `file://` uri. */
    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, file)

    /** Anything the reader never came back for, taken out before the next export lands beside it. */
    fun pruneStaged() {
        val directory = File(context.cacheDir, STAGE_DIR)
        if (directory.isDirectory) prune(directory)
    }

    private fun prune(directory: File) {
        val files = directory.listFiles() ?: return
        files.filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .drop(MAX_STAGED)
            .forEach { it.delete() }
    }

    private suspend fun paginate(report: Report): PagedReport {
        val palette = ReportLayout.palette(context)
        return ReportPagination.paginate(
            document = ReportLayout.build(context, report, profileName(), palette),
            measurer = PaintReportMeasurer(),
            palette = palette
        )
    }

    /**
     * The profile the report is about, for the line under its title.
     *
     * Asked for here, at the one place that also knows which profile the repository read, rather than deep
     * inside the layout or the page: those two are given the name they are given, and a document that reached
     * for the current profile by itself would be a document that could print one reader's figures under
     * another reader's name.
     */
    private suspend fun profileName(): String? = sessionRepository.currentUser.value?.name

    companion object {
        /** The authority `file_paths.xml` and the manifest agree on. */
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.fileprovider"

        /** `wt`: write and truncate, the mode the backup export already uses. A re-save replaces the file. */
        private const val WRITE_MODE = "wt"
        private const val STAGE_DIR = "report_exports"
        /**
         * How many staged exports the cache is allowed to hold.
         *
         * The cap is the whole policy, and it is deliberately not a timer: a file handed to another app
         * stays readable until five newer exports have pushed it out, which is long enough for the recipient
         * that opened it a minute later and short enough that the cache cannot fill up. An age limit would
         * have deleted a file somebody was still opening.
         */
        private const val MAX_STAGED = 5
    }
}
