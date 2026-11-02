package com.taskplanner.data.keep

import androidx.room.withTransaction
import com.taskplanner.data.auth.ProfileSafetyCopy
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.RowOwner
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.repository.SessionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * What the screen shows before anybody is asked to confirm: the parse, its numbers, and the rows it implies.
 *
 * @param fingerprint [KeepImportIdentity.fingerprintOf] of the exact text the field holds — a name for *this
 *   paste*, carried into the result and quotable in a report or a log without repeating anybody's note. It is
 *   deliberately not part of a row's identity: [KeepImportIdentity] explains why, and the short version is that a
 *   paste whose text differs by one character would otherwise re-import all 382 rows of it.
 * @param document what the text says, including the parser's warnings
 * @param plan what would be written against this profile right now — which is why it is computed, not promised:
 *   the same text previewed before an import and after one produces an empty plan, and the screen has to show that
 */
data class KeepImportPreview(
    val fingerprint: String,
    val document: KeepParsedDocument,
    val plan: KeepImportPlan
)

/**
 * Which kind of thing went wrong, named so the screen can put it in the reader's language.
 *
 * The app does not write sentences about its own failures; [HardcodedFailureTextTest] is the gate that keeps that
 * true, and Stage 14 found the reason it matters: a sentence composed at the moment of failure survives the
 * activity recreation that switching language causes, and is still in the language the reader just left. So this
 * enum is what travels, and the fragment that draws it resolves the words.
 *
 * [detail] beside it is the machine's own note — an exception chain, a row count, a file name. It is useful to a
 * person reading a log and useless to one reading a Snackbar, which is why the two are separate fields.
 */
enum class KeepImportFailure {
    /** The tree the parser produced cannot be written as it stands: deeper than four levels, or a parent nowhere. */
    TREE_REFUSED,

    /** No verified copy of the database could be taken, so the import refused to start. */
    SAFETY_COPY_UNAVAILABLE,

    /** A row the paste wants to write already belongs to somebody the paste is not allowed to overwrite. */
    IDENTITY_TAKEN,

    /** The write itself failed and the transaction rolled back; nothing arrived. */
    WRITE_FAILED
}

/**
 * How an import ended.
 *
 * Every case a reader can act on is its own type, because the screen has to say different things about each:
 * *nothing happened because there was nothing to import* and *nothing happened because your notes are already
 * here* look identical as a row count and mean opposite things.
 *
 * [Failed.detail] and [Refused.conflicts] are the machine's evidence; neither is what the reader is shown as
 * the explanation, and no title of theirs reaches a log through either.
 */
sealed class KeepImportResult {
    /** No profile is signed in, so there is nothing to own the import. */
    object NoProfile : KeepImportResult()

    /** The paste had no titled line in it — blank text, or separators only. */
    object NothingToImport : KeepImportResult()

    /**
     * @param topics and [tasks] the number of rows already present from this same text, which is what lets the
     *   screen say "your 56 topics are already here" instead of "0 imported".
     */
    data class AlreadyImported(val fingerprint: String, val topics: Int, val tasks: Int) : KeepImportResult()
    data class Imported(
        val fingerprint: String,
        val topics: Int,
        val tasks: Int,
        val snapshot: ProfileSafetyCopy.Snapshot
    ) : KeepImportResult()

    data class Refused(val failure: KeepImportFailure, val conflicts: List<KeepImportConflict>) : KeepImportResult()
    data class Failed(val failure: KeepImportFailure, val detail: String) : KeepImportResult()
}

/**
 * Imports a pasted Google Keep export into the signed-in profile.
 *
 * The parser decides what a paste means and the mapping decides which rows it names; this class is the part that
 * is allowed to touch a database, and it is deliberately boring: read the owners, plan, copy, write, verify.
 *
 * ## The order, and why it is this order
 *
 *  1. the current profile — an import has one owner, and it is never chosen from the text;
 *  2. the parse and the derived identities, then the owners of exactly those uuids, then the plan — so an
 *     already-imported paste is recognised before the app takes a copy of a database it is about to leave alone;
 *  3. a verified safety copy through [ProfileSafetyCopy], the Stage 13 mechanism: WAL checkpointed, the source
 *     hashed, the copy re-opened and the profile's rows counted inside it. If that step refuses, nothing is
 *     written; the import that cannot be undone is the one this stage will not perform;
 *  4. one Room transaction: the topics, the tasks, and `PRAGMA foreign_key_check` before the commit;
 *  5. the commit, or none of it.
 *
 * ## What makes a half-written import impossible
 *
 * [GroupDao.insertImportedGroups] and [TaskDao.insertImportedTasks] are `OnConflictStrategy.ABORT` inserts, not
 * the `REPLACE` pair the rest of the app uses. That choice carries two of §13's rules at once: a uuid that another
 * profile holds aborts the transaction instead of deleting their row, and a row that lands *between* the plan and
 * the commit — a pull from cloud, a second paste on another screen — aborts it too. Either way the exception is
 * caught here, Room rolls the transaction back, and the reader gets [KeepImportResult.Failed] with the database
 * exactly as it was and the safety copy still on the disk.
 *
 * ## Privacy
 *
 * The reader's titles go into rows and nowhere else: no log, no exception message, no statistic. The reason in
 * [KeepImportResult.Failed] is the exception chain, which is built from constraint names and line numbers —
 * [KeepImportMapping] and [KeepTextParser] are written to phrase their refusals that way.
 */
@Singleton
class KeepImportRepository @Inject constructor(
    private val database: AppDatabase,
    private val groupDao: GroupDao,
    private val taskDao: TaskDao,
    private val sessionRepository: SessionRepository,
    private val safetyCopy: ProfileSafetyCopy
) {

    /**
     * Parse, derive, and report — without writing anything.
     *
     * Returns null when no profile is signed in: there is nothing to preview an import *into*, and a screen that
     * offered to confirm one would fail at the last step anyway.
     */
    suspend fun preview(text: String): KeepImportPreview? {
        val userId = sessionRepository.currentUserId() ?: return null
        val document = KeepTextParser.parse(text)
        val fingerprint = KeepImportIdentity.fingerprintOf(text)
        return KeepImportPreview(fingerprint, document, planFor(document, userId))
    }

    /** The whole §14 sequence: guards first, copy, then one transaction whose failure leaves nothing behind. */
    suspend fun import(text: String): KeepImportResult {
        val userId = sessionRepository.currentUserId() ?: return KeepImportResult.NoProfile
        val document = KeepTextParser.parse(text)
        if (document.stats.topics + document.stats.tasks == 0) return KeepImportResult.NothingToImport
        val fingerprint = KeepImportIdentity.fingerprintOf(text)

        val plan = try {
            planFor(document, userId)
        } catch (failure: Exception) {
            return KeepImportResult.Failed(KeepImportFailure.TREE_REFUSED, reasonOf(failure))
        }
        if (plan.conflicts.isNotEmpty()) {
            return KeepImportResult.Refused(KeepImportFailure.IDENTITY_TAKEN, plan.conflicts)
        }
        if (plan.alreadyImported) {
            return KeepImportResult.AlreadyImported(fingerprint, document.stats.topics, document.stats.tasks)
        }
        if (plan.groups.isEmpty() && plan.tasks.isEmpty()) return KeepImportResult.NothingToImport

        val snapshot = try {
            safetyCopy.take(SAFETY_REASON, userId)
        } catch (failure: Exception) {
            return KeepImportResult.Failed(KeepImportFailure.SAFETY_COPY_UNAVAILABLE, reasonOf(failure))
        }

        return try {
            database.withTransaction {
                groupDao.insertImportedGroups(plan.groups)
                taskDao.insertImportedTasks(plan.tasks)
                // Read on the transaction's own connection, so it sees rows the commit has not made public yet.
                val broken = foreignKeyViolations()
                if (broken.isNotEmpty()) {
                    throw IllegalStateException("foreign keys after the writes, before the commit: $broken")
                }
            }
            KeepImportResult.Imported(fingerprint, plan.groups.size, plan.tasks.size, snapshot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Room has already rolled the transaction back on the way out. The snapshot's name and row count ride
            // along as the machine's note, because a reader who is told "nothing arrived" next asks "and can I get
            // back what I had" — the copy on the disk is the answer, and this is what points at it.
            KeepImportResult.Failed(
                KeepImportFailure.WRITE_FAILED,
                "${reasonOf(failure)} | snapshot=${snapshot.file.name} rows=${snapshot.rowCount}"
            )
        }
    }

    /** The identities this paste names, the owners of those identities, and the plan that follows from both. */
    private suspend fun planFor(document: KeepParsedDocument, userId: Long): KeepImportPlan {
        val candidates = KeepImportMapping.candidates(document, userId)
        val topicUuids = candidates.filter { it.isTopic }.map { it.uuid }
        val taskUuids = candidates.filterNot { it.isTopic }.map { it.uuid }
        return KeepImportMapping.plan(
            document = document,
            userId = userId,
            existingGroups = ownersOf(topicUuids) { groupDao.getGroupOwners(it) },
            existingTasks = ownersOf(taskUuids) { taskDao.getTaskOwners(it) },
            firstGroupSortOrder = groupDao.nextSortOrder(userId),
            firstTaskOrder = taskDao.nextOrder(userId)
        )
    }

    /**
     * Who owns these uuids, in bounded queries.
     *
     * Chunked because SQLite counts the `?` placeholders against its own variable limit (999 on the builds this
     * app ships on), and a paste of 382 lines asks the question about 382 identities at once. Reading every row of
     * the table instead would answer it in one query and load a ten-thousand-task account to do it.
     */
    private suspend fun ownersOf(
        uuids: List<String>,
        lookup: suspend (List<String>) -> List<RowOwner>
    ): Map<String, Long> {
        if (uuids.isEmpty()) return emptyMap()
        return uuids.chunked(OWNER_CHUNK).flatMap { lookup(it) }.associate { it.uuid to it.userId }
    }

    private fun foreignKeyViolations(): List<String> =
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            buildList {
                val table = cursor.getColumnIndexOrThrow("table")
                val rowid = cursor.getColumnIndexOrThrow("rowid")
                val parent = cursor.getColumnIndexOrThrow("parent")
                while (cursor.moveToNext()) {
                    add("${cursor.getString(table)}#${cursor.getLong(rowid)} -> ${cursor.getString(parent)}")
                }
            }
        }

    /** The exception and its causes, joined — the only text about a failure this app puts in front of a reader. */
    private fun reasonOf(failure: Throwable): String {
        val chain = buildList {
            var current: Throwable? = failure
            while (current != null && size < CHAIN_LIMIT) {
                add("${current.javaClass.simpleName}: ${current.message}")
                current = current.cause
            }
        }
        return chain.joinToString(" <- ")
    }

    private companion object {
        const val OWNER_CHUNK = 100
        const val CHAIN_LIMIT = 5
        const val SAFETY_REASON = "keep-import"
    }
}
