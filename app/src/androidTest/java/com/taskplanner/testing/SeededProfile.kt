package com.taskplanner.testing

import android.content.Context
import androidx.room.Room
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.AppMigrations
import com.taskplanner.data.model.User
import kotlinx.coroutines.runBlocking

/**
 * The profile an instrumented class signs the app into, and the way back out of it.
 *
 * Three facts make this worth a file rather than a few lines in each class:
 *
 *  * **The app keeps its session in a Hilt singleton.** The process reads `users.is_current` the first time a
 *    view model needs it and holds that answer for the rest of its life, so a per-test seed makes every test
 *    after the first write under the *previous* test's profile. A class therefore signs in once, in
 *    `@BeforeClass` — which is what [signIn] and [takeAway] are built for.
 *  * **Deleting the profile does not delete what it owns.** Thirteen tables carry a `user_id` foreign key with
 *    `ON DELETE CASCADE`; `tasks`, `categories` and `reminders` do not — their only foreign keys point at
 *    `categories` and at nothing. A cleanup that only removes the user therefore leaves eleven tasks and
 *    thirty-one categories behind on somebody's emulator, addressed by no profile, reachable by export and
 *    by any count that is not filtered by user. [takeAway] deletes those rows explicitly; the list is asserted
 *    against the shipped schema in `SeededProfileCleanupTest`, so a migration that adds a cascade cannot
 *    silently make this code wrong in the other direction.
 *  * **A run can be killed half way.** This device has ANRed under host load and been force-stopped mid-class,
 *    and a `@AfterClass` that never ran leaves a fake profile signed in — which the next stage's start-up
 *    would then materialise a series for. [sweepLeftovers] is called *before* seeding, so the residue of a
 *    dead run is cleared by the next one instead of being inherited by it.
 *
 * Only `@example.test` addresses are ever touched, and only by the classes that use this file: a profile a
 * person created is never swept, never deleted, and never signed out of except by [takeAway]'s restore.
 */
object SeededProfile {

    /** The app's own database, opened from outside the process's Hilt graph the way the app would. */
    fun open(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppMigrations.ALL)
            .build()

    /**
     * The tables that keep rows even after the profile that owned them is gone — schema 16's foreign keys,
     * read rather than remembered.
     */
    val orphanTables = listOf("tasks", "categories", "reminders")

    /** Which profile was signed in before a class changed it, and whether there was one at all. */
    class SignedIn(
        val seededUserId: Long,
        val displacedUserId: Long,
        val displacedWasCurrent: Boolean
    )

    /**
     * Removes every fixture profile the address space marks as a test's, with the rows it owns, and says how
     * many went. Called from a class's `@BeforeClass`, before it seeds anything of its own.
     */
    fun sweepLeftovers(database: AppDatabase): Int {
        var swept = 0
        runBlocking {
            database.userDao().getAllUsersOnce()
                .filter { it.email.endsWith("@example.test") }
                .forEach { user ->
                    deleteRowsOwnedBy(database, user.id)
                    database.userDao().deleteUser(user)
                    swept++
                }
        }
        // Residue from a run whose cleanup never ran — the rows outlive the profile they were written under,
        // because there is no cascade to take them. Counted and said out loud, never deleted: a row that
        // belongs to no profile cannot be proved to belong to a *fixture*, and guessing with somebody's task
        // list is not this file's business.
        val residue = rowsWithoutAProfile(database)
        if (residue > 0) {
            println(
                "SeededProfile: swept $swept fixture profile(s). $residue rows in $orphanTables belong to no " +
                    "profile at all — left untouched, since ownership cannot be proved from the row alone."
            )
        }
        return swept
    }

    /** Rows in the tables SQLite does not cascade, whose `user_id` matches no profile. */
    fun rowsWithoutAProfile(database: AppDatabase): Int = orphanTables.sumOf { table ->
        var count = 0
        database.openHelper.readableDatabase
            .query("SELECT count(*) AS c FROM $table WHERE user_id NOT IN (SELECT id FROM users)")
            .use { cursor -> if (cursor.moveToNext()) count = cursor.getInt(0) }
        count
    }

    /** Signs the app into a brand-new fixture profile, remembering whom it displaced. */
    fun signIn(database: AppDatabase, email: String, name: String): SignedIn {
        lateinit var outcome: SignedIn
        runBlocking {
            // `users.email` is unique and a profile signed in under an address already used would take the
            // first one's rows over with it, so the address carries this run's own clock.
            val seeded = database.userDao().insertUser(User(email = email, name = name))
            val displaced = database.userDao().getCurrentUser()?.id ?: -1L
            outcome = SignedIn(seeded, displaced, displaced > 0)
            database.userDao().setCurrentUser(database.userDao().getUserById(seeded)!!)
        }
        return outcome
    }

    /**
     * Deletes a fixture profile and everything under it, then puts back the profile it displaced.
     *
     * The restore comes *first* and each step is guarded, because the order is the whole point: a class that
     * throws part way through cleanup used to leave the device signed in as a stranger, and the next suite to
     * run would then write its rows under that fixture. Restoring before deleting means an interrupted cleanup
     * leaves a real person signed in with a few extra rows still to be swept, which [sweepLeftovers] handles
     * on the next run — the failure the other order cannot recover from.
     */
    fun takeAway(database: AppDatabase, signed: SignedIn) {
        runBlocking {
            if (signed.displacedWasCurrent) {
                database.userDao().getUserById(signed.displacedUserId)
                    ?.let { database.userDao().setCurrentUser(it) }
            }
        }
        runBlocking {
            database.userDao().getUserById(signed.seededUserId)?.let { user ->
                deleteRowsOwnedBy(database, user.id)
                database.userDao().deleteUser(user)
            }
        }
        // Nothing is signed in when nobody was before: the fixture's own row carried the flag and it has just
        // been deleted with it. Clearing `is_current` here would be the same trespass the doc above refuses —
        // signing somebody in "to clean up".
    }

    /** The rows in the tables SQLite does not cascade from a profile. */
    private fun deleteRowsOwnedBy(database: AppDatabase, userId: Long) {
        val db = database.openHelper.writableDatabase
        orphanTables.forEach { table ->
            db.delete(table, "user_id = ?", arrayOf(userId.toString()))
        }
    }
}
