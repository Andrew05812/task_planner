package com.taskplanner.data.auth

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskplanner.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A copy of the whole database, taken before anything irreversible is done to it.
 *
 * The sign-in handover moves rows between profiles, and one of its rules deletes the duplicate it merged
 * away. A deletion inside a transaction that later rolls back is safe; a deletion in a transaction that
 * *commits* is only recoverable if a copy was taken first. This is that copy, and the rule it exists to serve
 * is the one the stage is ordered by: **the local profile is guaranteed first, and the cloud account is
 * attached afterwards.**
 *
 * **Why a file copy and not the app's own backup format.** `BackupRepository` writes five tables — tasks,
 * categories, topics, series and reminders (`BackupModels.kt:27-33`) — because that is what a user-to-user
 * backup has carried since before the app owned time tracking, goals, habits, insights or achievements. A
 * "safety copy" built on it would silently omit eleven kinds of the reader's data, which is the exact failure
 * this file exists to prevent. So the snapshot is the SQLite database itself: every table, every column,
 * including the ones no export has ever touched.
 *
 * **The copy is checked, not assumed.** [take] folds the write-ahead log into the file and refuses to go on if the
 * log says otherwise, proves the running database is intact, counts the profile's rows in every table the handover
 * is responsible for, copies the file, proves the copy byte for byte and page for page, then reopens the copy and
 * counts the same rows in it. A count that does not match, or an integrity check that says anything but `ok`, is a
 * failure that stops the operation before a single row is touched.
 *
 * **What the file contains, and where it may go.** It is the reader's data in plain SQLite, in
 * `filesDir/safety/`. It is never uploaded, never included in an export, and never committed — the
 * repository's `.gitignore` keeps `*.db` out of git, and nothing in this class writes anywhere else. The last
 * few snapshots are kept so a reader who signs in on two days can still recover the first.
 */
@Singleton
class ProfileSafetyCopy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase
) {

    /** A snapshot that was written, reopened and counted. Nothing hands one out before that has happened. */
    data class Snapshot(
        val file: File,
        val takenAt: Long,
        val reason: String,
        /** Table → rows the profile owned at the instant of the copy; the copy itself is proved by hash. */
        val rowsOfProfile: Map<String, Int>
    ) {
        val rowCount: Int get() = rowsOfProfile.values.sum()
    }

    /** Raised instead of starting a move the app cannot undo. */
    class SafetyCopyUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Room's own handle, used as the `SupportSQLiteDatabase` it is.
     *
     * It is *not* an `android.database.sqlite.SQLiteDatabase`: `openHelper.writableDatabase` answers androidx
     * sqlite's `FrameworkSQLiteDatabase` wrapper, which is internal to that library and cannot be named here, and
     * casting the handle to the platform type throws `ClassCastException` the first time anything reads through it.
     * Reading the live database through a second, differently-typed handle would also mean the counts below are not
     * the same reads the app itself makes, which is the one thing a verification must not do.
     */
    private val liveDatabase: SupportSQLiteDatabase
        get() = database.openHelper.writableDatabase

    /**
     * Copies the live database and proves the copy is a database the app could restore.
     *
     * The order is the guarantee, and each step answers a different question:
     *
     * 1. *does the file hold every committed row?* [checkpoint] folds the write-ahead log in and refuses if the
     *    log reports frames the file does not have;
     * 2. *is the running database sound?* `integrity_check`, because a copy of a broken database is a broken copy;
     * 3. *what does this profile own right now?* [countProfile] on the live connection, before the bytes move;
     * 4. *is the copy the file?* SHA-256 of the source before, of the source after, and of the copy, all three
     *    equal;
     * 5. *is the copy a whole file?* its own header: magic string, page size, page count against length;
     * 6. *can it be opened, and does it hold those rows?* [verifyReadable] reopens it as a separate database, runs
     *    `integrity_check` over it and counts the same rows again — and the live database is counted a second time
     *    to make sure nothing arrived between step 3 and the copy.
     *
     * The two live counts are not ceremony. A hash of the main file cannot see a commit that went into the
     * write-ahead log after [checkpoint] ran, because such a commit leaves those bytes untouched — so
     * `before == after` proves only that *the file* did not change while it was being read, never that the
     * database did not change. Re-counting the profile catches the case the hash cannot: a row committed in
     * between is in the live count and not in the copy, and the snapshot is rejected rather than trusted.
     *
     * A rejected snapshot is renamed to `.rejected`, not deleted. The failure it reports is the one thing a
     * developer needs to see, and a check that destroys its own evidence on every retry cannot be diagnosed.
     * The reader's data is not more exposed by that: the file was written by this app, in this app's private
     * directory, and the newest three snapshots are all that are kept either way.
     *
     * [profileUserId] is the profile whose rows the copy must be able to produce. Counting a profile rather than
     * the whole file is deliberate — a snapshot that lost the reader's goals and gained somebody's index
     * statistics would still look "the same size".
     */
    suspend fun take(reason: String, profileUserId: Long): Snapshot {
        val name = database.openHelper.databaseName
        // Room keeps the string it was given: an absolute path when the database was built from a `File`, the bare
        // name when it was built from a name — which is what production does. `getDatabasePath` resolves the
        // second form; the first is already where it lives.
        val source = if (File(name).isAbsolute) File(name) else context.getDatabasePath(name)
        if (!source.exists()) {
            throw SafetyCopyUnavailable(
                "the app's database ($name) is not a file on disk, so no copy can be taken"
            )
        }

        checkpoint()
        assertIntact(supportReader(liveDatabase))
        val rows = countProfile(profileUserId, on = supportReader(liveDatabase))

        val stamp = System.currentTimeMillis()
        val directory = File(context.filesDir, SAFETY_DIR).apply { mkdirs() }
        val target = File(directory, "profile-$reason-$stamp.db")
        val before = digest(source)
        runCatching { source.copyTo(target, overwrite = true) }
            .onFailure {
                target.delete()
                throw SafetyCopyUnavailable("the safety copy could not be written: ${it.message}", it)
            }
        val after = digest(source)
        val copy = digest(target)
        if (before != after) {
            throw SafetyCopyUnavailable(
                "the database file changed while the copy was being taken; nothing was written as a snapshot" +
                    residueSuffix(reject(target, stamp))
            )
        }
        if (before != copy) {
            throw SafetyCopyUnavailable(
                "the copy does not match the database byte for byte (${before.take(12)} vs ${copy.take(12)})" +
                    residueSuffix(reject(target, stamp))
            )
        }
        assertCompleteSqliteFileOrReject(target, stamp)
        val inCopy = runCatching { verifyReadable(target, profileUserId) }
            .getOrElse { error ->
                throw SafetyCopyUnavailable(
                    "the copy could not be read back as a database${residueSuffix(reject(target, stamp))}: " +
                        error.message,
                    error
                )
            }
        val stillThere = countProfile(profileUserId, on = supportReader(liveDatabase))
        if (stillThere != rows) {
            throw SafetyCopyUnavailable(
                "the profile changed while the copy was being taken; the snapshot is a moment that no longer " +
                    "exists and nothing was moved against it. Tables that differ: " +
                    rows.filter { stillThere[it.key] != it.value }
                        .entries.joinToString { "${it.key}=${it.value}→${stillThere[it.key]}" } +
                    residueSuffix(reject(target, stamp))
            )
        }
        if (inCopy != rows) {
            throw SafetyCopyUnavailable(
                "the snapshot holds a different number of this profile's rows than the database does; " +
                    "the tables that disagree are " +
                    rows.filter { inCopy[it.key] != it.value }
                        .entries.joinToString { "${it.key}=${it.value}→${inCopy[it.key]}" } +
                    residueSuffix(reject(target, stamp))
            )
        }
        pruneOldSnapshots(directory)
        return Snapshot(file = target, takenAt = stamp, reason = reason, rowsOfProfile = rows)
    }

    /** SHA-256 of a file, streamed. The equality of two of these is the whole of the completeness argument. */
    private fun digest(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .let { md ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

    /**
     * The header check, with the cleanup the check exists to trigger.
     *
     * A copy that stops halfway is a short file, and a short file left in the folder under a snapshot's name is
     * worse than no file at all — so a refusal here rejects the copy first and carries what that cost in its own
     * message.
     */
    private fun assertCompleteSqliteFileOrReject(file: File, stamp: Long) {
        try {
            assertCompleteSqliteFile(file)
        } catch (e: SafetyCopyUnavailable) {
            throw SafetyCopyUnavailable(e.message + residueSuffix(reject(file, stamp)), e)
        }
    }

    /** What a rejected copy left behind, as a clause, or nothing when the folder is clean. */
    private fun residueSuffix(note: String?): String = note?.let { "; $it" }.orEmpty()

    /**
     * Reads the header the copy must carry, without opening a second database connection.
     *
     * A SQLite file begins with its magic string, then a big-endian page size and a page count. Those three facts
     * prove the copy is a whole database of the right length. They are not a substitute for [verifyReadable],
     * which is the check that the copy can actually be queried — but they cost nothing, they run before anything
     * is opened, and they name the failure precisely: a copy that stopped halfway is a short file, and a short
     * file is a different problem from a database that will not open.
     */
    private fun assertCompleteSqliteFile(file: File) {
        val header = ByteArray(100)
        file.inputStream().use { input ->
            if (input.read(header) < header.size) {
                throw SafetyCopyUnavailable("the copy is shorter than a SQLite header (${file.length()} bytes)")
            }
        }
        val magic = "SQLite format 3 "
        if (String(header, 0, 16, Charsets.US_ASCII) != magic) {
            throw SafetyCopyUnavailable("the copy does not begin with a SQLite header")
        }
        val pageSize = ((header[16].toInt() and 0xFF) shl 8 or (header[17].toInt() and 0xFF))
            .let { if (it == 1) 65536 else it }
        val pageCount = ((header[28].toInt() and 0xFF) shl 24) or ((header[29].toInt() and 0xFF) shl 16) or
            ((header[30].toInt() and 0xFF) shl 8) or (header[31].toInt() and 0xFF)
        val expected = pageCount.toLong() * pageSize
        if (file.length() != expected) {
            throw SafetyCopyUnavailable(
                "the copy holds $pageCount page(s) of $pageSize bytes, which is $expected bytes, " +
                    "but the file is ${file.length()}"
            )
        }
    }

    /**
     * Opens the snapshot again and counts the profile's rows in it — the proof that a copy could actually be
     * restored, not just copied.
     *
     * Called from [take], so no handover starts on a snapshot that has not been read back, and called directly by
     * the device test on a snapshot taken earlier, because the question "can this file be opened on the SQLite
     * that ships with Android, days later" is a different question from "was this file complete when it was
     * written".
     *
     * Opened read-write rather than read-only, on purpose: the copy carries the source's WAL header and has no
     * `-shm` beside it, and SQLite wants to build the wal-index before it will answer a query — something a
     * read-only handle cannot do. Nothing here writes a byte the copy did not already have; the queries are
     * `PRAGMA integrity_check` and `SELECT COUNT(*)`, and the only SQLite-side change an open can make to a
     * database with no log is to fold in a log that is not there.
     */
    fun verifyReadable(file: File, profileUserId: Long): Map<String, Int> {
        if (!file.exists()) throw SafetyCopyUnavailable("the snapshot ${file.name} is not on disk")
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            assertIntact(platformReader(db))
            countProfile(profileUserId, on = platformReader(db))
        } catch (e: SafetyCopyUnavailable) {
            throw e
        } catch (e: Exception) {
            throw SafetyCopyUnavailable("the snapshot could not be reopened: ${e.message}", e)
        } finally {
            runCatching { db?.close() }
        }
    }

    /** Fails loudly if the copy is not a database this app could open. */
    private fun assertIntact(db: SnapshotReader) {
        db.cursor("PRAGMA integrity_check").use { cursor ->
            val first = cursor.moveToNext()
            val verdict = if (first) cursor.getString(0) else "(no answer)"
            if (!first || !verdict.equals("ok", ignoreCase = true)) {
                throw SafetyCopyUnavailable("the copy failed integrity_check: $verdict")
            }
        }
    }

    /**
     * Rows of one profile in every table a handover can move. The list is the schema's, not a shorter one:
     * a snapshot that quietly covered five of sixteen tables is the bug this class was written for.
     */
    private fun countProfile(userId: Long, on: SnapshotReader): Map<String, Int> =
        PROFILE_TABLES.associateWith { table ->
            on.cursor("SELECT COUNT(*) FROM $table WHERE user_id = ?", arrayOf(userId.toString()))
                .use { cursor -> if (cursor.moveToNext()) cursor.getInt(0) else -1 }
        }

    /**
     * The two database handles this class reads through are not the same type: Room hands out a
     * `SupportSQLiteDatabase`, and a file reopened for verification is an `android.database.sqlite.SQLiteDatabase`
     * — and androidx.sqlite's wrapper between them is internal, so it cannot be constructed here. One small
     * adapter is cheaper than pretending the two are interchangeable, and it keeps the *same* queries running
     * on both sides, which is the whole point: a comparison of a live count against a differently-read copy
     * count proves nothing.
     */
    private interface SnapshotReader {
        fun cursor(sql: String, args: Array<String>? = null): android.database.Cursor
    }

    private fun supportReader(db: SupportSQLiteDatabase): SnapshotReader =
        object : SnapshotReader {
            override fun cursor(sql: String, args: Array<String>?): android.database.Cursor =
                if (args == null) db.query(sql) else db.query(sql, args)
        }

    private fun platformReader(db: SQLiteDatabase): SnapshotReader =
        object : SnapshotReader {
            override fun cursor(sql: String, args: Array<String>?): android.database.Cursor =
                db.rawQuery(sql, args)
        }

    /**
     * Keeps a snapshot that failed, under a name that says so — and removes it if it cannot be renamed.
     *
     * Deleting it outright would be deleting the only evidence of the mismatch, and every retry would produce a
     * new file with a new timestamp and the same unexplained refusal. But the opposite failure is worse: a copy
     * that failed a check and is left sitting in the folder under its original `profile-*.db` name is
     * indistinguishable, to anything that reads the folder later, from a copy that passed — including by this
     * class's own prune, which counts files, not verdicts. So the rename is tried, and if the file system will
     * not move it the file is deleted; if even that fails, the caller is given a sentence to append to the
     * refusal, naming where the unapproved copy is, because a check that cannot clean up after itself has to say
     * so rather than be quiet.
     *
     * Returns what the reader should be told about the residue, or null when there is none worth mentioning.
     */
    private fun reject(target: File, stamp: Long): String? {
        if (runCatching { target.renameTo(File(target.parentFile, "rejected-$stamp.db")) }.getOrDefault(false)) {
            return null
        }
        return if (runCatching { target.delete() }.getOrDefault(false)) {
            "the unverified copy was removed rather than left to look like an approved one"
        } else {
            "an unverified copy could not be renamed or deleted and is still at ${target.absolutePath}; " +
                "it must not be treated as a snapshot"
        }
    }

    /**
     * Folds the write-ahead log into the database file, and refuses to continue if it did not.
     *
     * `PRAGMA wal_checkpoint(TRUNCATE)` answers `(busy, log, checkpointed)`, and the guarantee that matters is in
     * the third number, not the second: [SQLite's own definition](https://www.sqlite.org/pragma.html#pragma_wal_checkpoint)
     * is that `log` is the number of frames in the log *when the pragma began* and `checkpointed` is the number
     * that had been copied into the database file. A checkpoint that moved everything is a checkpoint the file
     * copy will contain, whether or not the log was then reset to zero — so the refusal is "a frame is still
     * unwritten", stated as `checkpointed < log`, and "someone else holds the log", stated as `busy != 0`.
     *
     * Requiring the log to reach zero instead — which is what this method did — reads a *side effect* of one
     * implementation as the guarantee. It holds on the SQLite that ships with Android, where a successful
     * TRUNCATE answers `(0, 0, 0)`, and it does not hold under Robolectric's legacy SQLite layer, where the
     * frames are folded in and reported (`(0, 10, 10)` on a database with two rows) while the log itself is never
     * reset: the measured copy reopens there and shows both rows. Refusing on that reading would block a real
     * handover for a difference in how the checkpoint is reported, which is the opposite of what this class is for.
     */
    private fun checkpoint() {
        liveDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (!cursor.moveToNext()) {
                throw SafetyCopyUnavailable("the write-ahead log checkpoint returned no answer")
            }
            val busy = cursor.getInt(0)
            val log = if (cursor.columnCount > 1) cursor.getInt(1) else 0
            val written = if (cursor.columnCount > 2) cursor.getInt(2) else log
            if (busy != 0 || written < log) {
                throw SafetyCopyUnavailable(
                    "the write-ahead log still holds frames the file does not have " +
                        "(busy=$busy log=$log checkpointed=$written); no safe copy can be taken"
                )
            }
        }
    }

    /**
     * Keeps the newest [KEEP_SNAPSHOTS] snapshots — rejected attempts included, since they are the same kind of
     * file holding the same kind of data — and takes a dropped snapshot's `-wal` / `-shm` sidecars with it.
     *
     * Two details carry the weight, and both were wrong the first time this was written.
     *
     * **The keep-set is the `.db` files.** A sidecar is part of a snapshot, not another snapshot: counting
     * `profile-x.db-wal` against the limit would let two logs and one copy fill all three slots and drop a third
     * live copy instead of the stale one. So the filter asks for names *ending* in `.db`, and the sidecars are
     * removed by derivation from whatever is dropped.
     *
     * **The extension goes after the stem.** `substringBefore(".db")` answers `profile-x-1793`, and appending `""`
     * to that is the name of a file that does not exist — `File.delete()` returns `false` and says nothing more, so
     * the prune quietly kept every copy it was written to remove. Measured before the fix, in a folder holding five
     * snapshots and a planted sidecar pair beside each: fifteen files left after two rounds of "pruning", and the
     * test named all fifteen.
     *
     * A file this app cannot delete stays on disk, and the handover still proceeds: the alternative is refusing a
     * move the reader asked for because some older copy is locked, which would put the copy's mechanics above the
     * reason it exists.
     */
    private fun pruneOldSnapshots(directory: File) {
        directory.listFiles { file ->
            (file.name.startsWith("profile-") || file.name.startsWith("rejected-")) &&
                file.name.endsWith(".db")
        }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_SNAPSHOTS)
            ?.forEach { stale ->
                val stem = stale.name.removeSuffix(".db")
                listOf(".db", ".db-wal", ".db-shm").forEach { extension ->
                    File(directory, "$stem$extension").delete()
                }
            }
    }

    companion object {
        private const val SAFETY_DIR = "safety"
        private const val KEEP_SNAPSHOTS = 3

        /** Every table carrying a `user_id`, from the shipped schema (AppDatabase version 16). */
        val PROFILE_TABLES: List<String> = listOf(
            "tasks", "categories", "task_groups", "recurrence_rules", "reminders",
            "tracking_sessions", "work_sessions", "work_interval_corrections",
            "goals", "goal_tasks", "goal_value_changes",
            "habits", "habit_checks", "habit_check_changes",
            "insights", "achievements"
        )
    }
}
