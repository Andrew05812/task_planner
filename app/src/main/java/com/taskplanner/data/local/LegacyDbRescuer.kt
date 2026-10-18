package com.taskplanner.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * One-time rescue of the app database from legacy locations.
 *
 * Older builds stored taskplanner.db in cacheDir (and an unused variant in
 * externalFilesDir). The cache can be purged by Android at any time, which is
 * one of the root causes of users losing tasks. Before Room first opens the
 * database in its default private location, copy the most valuable legacy
 * database file there — including WAL sidecar files, which must travel with
 * the main file for an open database.
 *
 * Nothing is deleted: the legacy files are left untouched so a failed copy
 * can never destroy user data.
 */
class LegacyDbRescuer(private val context: Context) {

    fun rescueIfNeeded() {
        val target = context.getDatabasePath(DB_NAME)
        if (target.exists()) return

        val candidates = listOf(
            File(context.cacheDir, DB_NAME),
            File(context.getExternalFilesDir(null), DB_NAME),
            File(context.filesDir, DB_NAME)
        ).filter { it.exists() && it.length() > 0 }

        if (candidates.isEmpty()) return

        // Comparing the copies means opening them, and SQLite may checkpoint or discard a WAL
        // sidecar while doing that. That must never happen to the user's only copy, so each
        // candidate is scored on a throwaway duplicate and the winner is copied from the original.
        val scratchRoot = File(context.cacheDir, SCRATCH_DIR).apply { mkdirs() }
        try {
            val scored = candidates.mapIndexed { index, candidate ->
                val dir = File(scratchRoot, "candidate-$index").apply { mkdirs() }
                copyWithSidecars(candidate, File(dir, DB_NAME))
                candidate to probe(File(dir, DB_NAME))
            }.sortedWith(
                compareByDescending<Pair<File, Probe>> { it.second.totalRows }
                    .thenByDescending { it.second.maxUpdatedAt }
                    .thenByDescending { it.second.userVersion }
            )

            scored.firstOrNull()?.let { copyWithSidecars(it.first, target) }
        } finally {
            scratchRoot.deleteRecursively()
        }
    }

    private data class Probe(
        val userVersion: Int,
        val totalRows: Long,
        val maxUpdatedAt: Long
    )

    private fun probe(file: File): Probe {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val version = db.compileStatement("PRAGMA user_version").simpleQueryForLong()
            var rows = 0L
            var maxUpdated = 0L
            for (table in listOf("tasks", "categories", "users")) {
                val count = try {
                    db.compileStatement("SELECT COUNT(*) FROM $table").simpleQueryForLong()
                } catch (_: Exception) {
                    0L
                }
                rows += count
                if (table == "tasks") {
                    maxUpdated = try {
                        db.compileStatement("SELECT COALESCE(MAX(updated_at), 0) FROM tasks")
                            .simpleQueryForLong()
                    } catch (_: Exception) {
                        0L
                    }
                }
            }
            Probe(version.toInt(), rows, maxUpdated)
        } catch (_: Exception) {
            Probe(0, 0, 0)
        } finally {
            db?.close()
        }
    }

    private fun copyWithSidecars(source: File, target: File) {
        target.parentFile?.mkdirs()
        for (suffix in listOf("", WAL_SUFFIX, SHM_SUFFIX)) {
            val src = File(source.path + suffix)
            if (src.exists() && src.length() > 0) {
                val dst = File(target.path + suffix)
                src.inputStream().use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    companion object {
        const val DB_NAME = "taskplanner.db"
        private const val WAL_SUFFIX = "-wal"
        private const val SHM_SUFFIX = "-shm"
        private const val SCRATCH_DIR = "db-rescue-probe"
    }
}
