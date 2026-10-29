package com.taskplanner.data.auth

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.fragment.app.FragmentActivity
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.ProfileAttachDao
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.SessionRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class SignInOutcome {
    data class Success(val user: User) : SignInOutcome()
    object Canceled : SignInOutcome()
    object NotConfigured : SignInOutcome()

    /**
     * The Google account's address already belongs to a profile on this device that is not that account —
     * a local profile, or one linked to a different cloud identity.
     *
     * Joining the two is a decision only the reader can make, and the app already asks for it explicitly
     * ("move this profile onto the Google account"). Signing in therefore stops here rather than writing a
     * second profile over the first: `users.email` is unique, and the statement that would have created one
     * deletes the existing one first, taking with it every goal, habit, session, insight and achievement that
     * cascades off its owner and orphaning its tasks.
     */
    data class EmailOwnedByAnotherProfile(val email: String, val sessionLeftOpen: Boolean) : SignInOutcome()

    /**
     * A sign-in that did not happen.
     *
     * [sessionProblem] is not decoration: when the attempt got far enough to open a cloud session and the local
     * half then failed, whether that session was closed again is a second fact the reader has to be told — the
     * device is then signed in to Google with no profile under it, which the next start will resolve differently
     * from a clean failure.
     */
    data class Failed(val message: String, val sessionProblem: String? = null) : SignInOutcome()
}

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseAuth: Lazy<FirebaseAuth>,
    private val signInTransport: GoogleSignInTransport,
    private val sessionRepository: SessionRepository,
    private val userDao: UserDao,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val groupDao: GroupDao,
    private val recurrenceDao: RecurrenceDao,
    private val reminderDao: ReminderDao,
    private val profileAttachDao: ProfileAttachDao,
    private val safetyCopy: ProfileSafetyCopy,
    private val database: AppDatabase
) {

    val currentUser: FirebaseUser?
        get() = if (CloudConfig.isConfigured) firebaseAuth.get().currentUser else null

    fun isFirebaseSignedIn(): Boolean = currentUser != null

    /**
     * Real Google sign-in, as the state machine the screen renders.
     *
     * The credential itself — Credential Manager, the Google ID token, the Firebase exchange — lives behind
     * [GoogleSignInTransport], because every one of its failure branches needs a Google account and a Firebase
     * project to reach. What is decided here is everything that happens to *this device* afterwards: which
     * profile the identity maps to, whether the session may move to it, and what a refusal leaves behind.
     */
    suspend fun signInWithGoogle(activity: FragmentActivity): SignInOutcome =
        when (val attempt = signInTransport.signIn(activity)) {
            SignInAttempt.NotConfigured -> SignInOutcome.NotConfigured
            SignInAttempt.Canceled -> SignInOutcome.Canceled
            is SignInAttempt.Failed -> SignInOutcome.Failed(attempt.message)
            is SignInAttempt.Authenticated -> when (
                val linked = linkProfile(
                    uid = attempt.identity.uid,
                    email = attempt.identity.email,
                    displayName = attempt.identity.displayName,
                    photoUrl = attempt.identity.photoUrl
                )
            ) {
                is ProfileUpsert.Linked -> {
                    sessionRepository.setCurrentUser(linked.user)
                    SignInOutcome.Success(linked.user)
                }

                is ProfileUpsert.AddressTaken -> {
                    // The cloud session has no local profile to stand on, and leaving it half-built would
                    // leave the reader signing in "successfully" into the profile they already had. The
                    // address is released back to the profile that owns it, and the reader is told why — and
                    // told again if the release failed, because then there *is* a live cloud session on this
                    // device with no profile under it, which is not something to say "nothing happened" about.
                    val ended = signInTransport.endSession()
                    SignInOutcome.EmailOwnedByAnotherProfile(
                        email = linked.email,
                        sessionLeftOpen = ended is SessionEnd.Failed
                    )
                }

                is ProfileUpsert.Refused -> {
                    // The same accounting as the branch above, for the same reason: this path has just closed a
                    // cloud session, and whether that worked is a fact the reader is owed rather than a detail.
                    val ended = signInTransport.endSession()
                    SignInOutcome.Failed(
                        linked.message,
                        sessionProblem = (ended as? SessionEnd.Failed)?.message
                    )
                }
            }
        }

    /**
     * The linking decision, apart from the credential that carries it.
     *
     * Split out because the property worth testing — that a sign-in never destroys the profile that happens to
     * own the same address — is arithmetic over these four values plus the database, and `FirebaseUser` cannot
     * be built in a JVM test. Everything about identity is decided here, so everything about it is testable.
     *
     * UID is the identity anchor; email, name and avatar are display attributes. A profile is created only when
     * neither this uid nor this address is already on the device. The second condition is the one that used to
     * be missing: an address owned by a local profile made `insertUser` collide with the unique index, and the
     * strategy then in force resolved that by deleting the existing row — which thirteen tables answer by
     * deleting everything their owner owns.
     */
    internal suspend fun linkProfile(
        uid: String?,
        email: String?,
        displayName: String?,
        photoUrl: String?
    ): ProfileUpsert {
        // A blank uid is as unusable as a missing one: it would create a profile that no later sign-in can be
        // matched to, because `getUserByFirebaseUid("")` answers whichever row got there first.
        if (uid.isNullOrBlank()) return ProfileUpsert.Refused("Firebase returned no usable uid")
        val name = displayName ?: "Google user"
        val reported = email?.takeIf { it.isNotBlank() }
        // users.email is unique and non-null, so an account that came back without an address gets a placeholder
        // derived from its uid rather than a colliding empty string. What it must not do is *replace* an address a
        // profile already carries: Google omits the email when the scope was revoked or the response is partial,
        // and writing the placeholder over a real address would trade an identity the reader chose for a synthetic
        // one they never did. The placeholder is for the row that has nothing; a row that has something keeps it.
        val address = reported ?: "google-$uid@firebase.local"
        val initial = name.firstOrNull()?.toString()?.uppercase() ?: "?"
        // One transaction, because everything below is read-then-write and `users.firebase_uid` carries no unique
        // index — the shipped schema has exactly one index on this table, `index_users_email` (16.json), and
        // `getUserByFirebaseUid` is a `LIMIT 1` with no `ORDER BY`. Read outside a write, two passes of this
        // method for one uid could each look, see nobody, and insert, leaving the same cloud identity on two
        // profiles; from then on `getUserByFirebaseUid` answers whichever row SQLite reaches first, and the
        // handover would be pointed at a profile chosen by rowid. Inside the transaction the second reader waits
        // for the first to commit and finds its row. Adding the missing unique index instead would need a schema
        // version and a migration over data that may already contain a pair, which is Stage 17's kind of change,
        // not a sign-in's.
        var result: ProfileUpsert = ProfileUpsert.Refused("linkProfile did not run")
        database.withTransaction {
            val existing = userDao.getUserByFirebaseUid(uid)
            // The address is checked whichever branch this takes. The uid branch used to skip it and write the
            // address straight into the row it had found — the same unique index, arriving from the other
            // direction, and an exception escaping past the caller's answer with the cloud session left standing.
            val holder = userDao.getUserByEmail(address)?.takeIf { it.id != existing?.id }
            if (holder != null) {
                result = ProfileUpsert.AddressTaken(address)
                return@withTransaction
            }

            if (existing != null) {
                val updated = existing.copy(
                    name = name,
                    email = reported ?: existing.email,
                    avatarInitial = initial,
                    avatarUrl = photoUrl,
                    updatedAt = System.currentTimeMillis()
                )
                userDao.updateUser(updated)
                result = ProfileUpsert.Linked(updated)
                return@withTransaction
            }

            val user = User(
                email = address,
                name = name,
                avatarInitial = initial,
                firebaseUid = uid,
                avatarUrl = photoUrl,
                // Not current. The row is made current by `sessionRepository.setCurrentUser` below the caller's
                // one code path for the question, which clears the previous holder in the same transaction.
                // Setting the flag here too meant two rows answered `is_current = 1` between these two statements,
                // and `getCurrentUser` is a `LIMIT 1` with no `ORDER BY` — so which profile the app was working in
                // during that window was SQLite's choice. Stage 8a was the first code to draw the flag, which is
                // how the window became a visible defect rather than a theoretical one.
                isCurrent = false
            )
            result = try {
                ProfileUpsert.Linked(user.copy(id = userDao.insertUser(user)))
            } catch (e: SQLiteConstraintException) {
                // Somebody claimed the address between the lookup and this write. Refusing is the whole point of
                // the constraint: the other profile is still intact, and this attempt says so out loud.
                ProfileUpsert.AddressTaken(address)
            }
        }
        return result
    }

    /** What linking a Google account to a local profile produced. */
    internal sealed class ProfileUpsert {
        data class Linked(val user: User) : ProfileUpsert()
        data class AddressTaken(val email: String) : ProfileUpsert()
        data class Refused(val message: String) : ProfileUpsert()
    }

    /**
     * What a finished handover did, in the numbers the reader is shown and the tests assert.
     *
     * The snapshot is in here rather than only on disk because the guarantee the reader was given — "your data
     * is safe, this can be undone" — is worth nothing if nobody can point at the copy that makes it true.
     */
    data class AttachReport(
        val sourceUserId: Long,
        val targetUserId: Long,
        val safetyCopy: ProfileSafetyCopy.Snapshot,
        /** Table → rows that arrived on the account. */
        val moved: Map<String, Int>,
        /**
         * Rows that folded into a row the account already held.
         *
         * Which of the two copies goes depends on the record, not on where it came from: for a task or category
         * identity two profiles can both hold, the newer `updated_at` stands and the older is dropped — so the
         * dropped row is the *incoming* one when the account's copy is newer, and the account's own copy when the
         * reader edited the local one afterwards (`attachTasks`). For an achievement or an insight the account's
         * insight the account's copy always stands, because both are derived records the app regenerates. What
         * the number never does is hide a deletion: every row in it is in the verified copy taken before the
         * transaction opened, and the same copy holds the account's rows that were overwritten.
         */
        val mergedDuplicates: Int,
        /**
         * Links that had to change their target because the row they named merged away — a measured interval or a
         * tracking session whose task arrived twice, a category's children. They are not rows that moved (the row
         * they sit on is counted in [moved] whichever way), so they are counted apart: a reader comparing this
         * report against what they had should be able to tell "nothing was lost" from "something was rewired".
         */
        val repointedLinks: Int
    )

    /**
     * Explicit migration of a local (pre-Google) profile's data into the signed-in account.
     * Never automatic: the UI must ask the user first.
     *
     * Rows change owner instead of being copied, so their local ids, their uuids and the links
     * between them survive, and a task the account already has is recognised as the same task
     * instead of becoming a second row with a duplicated identity. Everything runs in one
     * transaction, so a failure leaves the two profiles exactly as they were.
     *
     * Two things happen before that transaction opens, and each of them exists because the move is the most
     * destructive thing a signed-in reader can ask for:
     *
     *  1. **A refusal, not a guess, where two live facts would collide.** A timer still running on the local
     *     profile cannot be re-tagged onto an account — `tracking_sessions.running_marker` holds the owner's id and
     *     says one clock per profile, and an interval that is open is a number still counting on somebody's screen.
     *     The reader is told to stop it rather than have it stopped for them, and nothing is written.
     *  2. **A verified safety copy of the whole database.** The merge drops the duplicate of any row the account
     *     already holds, and a dropped row is only recoverable if it was copied first. The copy is re-opened and
     *     counted before a single statement runs; if it does not prove to hold this profile's rows, the handover
     *     refuses to start. That ordering is the stage's own rule: the local profile is guaranteed first, the
     *     cloud account is attached second.
     *
     * A third check closes the transaction: sixteen tables carry a `user_id`, and the handover is only finished
     * when none of them still names the profile the reader has just left. Anything stranded is an exception, which
     * rolls the whole move back — a half-moved profile is the failure this method exists to avoid, and "it reported
     * success" is not evidence that it did not happen.
     *
     * All of it runs on [Dispatchers.IO]. The pre-flight used to run on the caller's thread, which for the only
     * caller in the app is `viewModelScope`'s main dispatcher: three SHA-256 passes over the whole database, two
     * `integrity_check`s and a file copy on the UI thread is an ANR on any database a reader has actually used.
     */
    suspend fun attachLocalDataToAccount(sourceUserId: Long, targetUser: User): AttachOutcome =
        attachLocalDataToAccount(sourceUserId, targetUser) { reason, profile -> safetyCopy.take(reason, profile) }

    /**
     * The handover, with the copy-taking step named.
     *
     * [takeCopy] exists for one reason: the ordering this method promises — take the copy, then check that the
     * copy still describes the profile, then write — is the promise, and nothing outside can reach the middle of
     * it to prove the check runs where it says it does. Production has one answer for that parameter, the default
     * the two-argument call above passes, and a test that hands in a copy of its own can show the guard refusing
     * a handover whose snapshot has stopped being true.
     */
    internal suspend fun attachLocalDataToAccount(
        sourceUserId: Long,
        targetUser: User,
        takeCopy: suspend (String, Long) -> ProfileSafetyCopy.Snapshot
    ): AttachOutcome {
        val activeUserId = sessionRepository.currentUserId()
            ?: throw IllegalStateException("Sign in before moving local data")
        require(activeUserId == targetUser.id) {
            "data may only be moved onto the profile that is currently signed in"
        }
        // Moving a profile onto itself would otherwise drop and re-add every row; refusing the
        // no-op is what separates a move from a wipe. Nothing is written, so nothing needs copying.
        if (sourceUserId == targetUser.id) return AttachOutcome.AlreadyTheSameProfile

        val moved = LinkedHashMap<String, Int>()
        var merged = 0
        var repointed = 0
        // Assigned inside the IO block below; the report is built outside it, because a snapshot the caller cannot
        // point at is not a guarantee anyone can check.
        var snapshot: ProfileSafetyCopy.Snapshot? = null
        withContext(Dispatchers.IO) {
            val running = profileAttachDao.openIntervalsOf(sourceUserId)
            if (running > 0) {
                throw IllegalStateException(
                    "Stop the timer that is still running on this profile before moving its data"
                )
            }

            snapshot = takeCopy("attach", sourceUserId)
            database.withTransaction {
                // The copy was proved at an instant, and an instant is not a promise: a sync pass, a reminder
                // renewal or a screen saving a row can all commit between the last count in `take` and this
                // transaction. Anything written after the copy would be moved by this transaction and absent from
                // the file the reader was told makes the move undoable, so the profile is counted again — one
                // statement over the same sixteen tables — and the handover refuses rather than proceed against a
                // copy that has quietly stopped being complete. A refusal loses nothing: nothing has been written
                // yet, and the reader is asked to try again.
                val verified = checkNotNull(snapshot)
                assertCopyStillCovers(sourceUserId, verified)
                val now = System.currentTimeMillis()
                val categories = attachCategories(sourceUserId, targetUser.id)
                moved["categories"] = categories.arrived
                merged += categories.mergedAway
                moved["task_groups"] = attachGroups(sourceUserId, targetUser.id)
                // Before the tasks, so an instance the account already knows arrives together with
                // the pattern that produced it rather than as an orphan row.
                moved["recurrence_rules"] = attachRules(sourceUserId, targetUser.id)
                val tasks = attachTasks(sourceUserId, targetUser.id, categories.survivors)
                moved["tasks"] = tasks.arrived
                merged += tasks.lost.size
                // Only after the tasks: a source category still has rows pointing at it until they move, and
                // deleting it un-categorises them.
                removeMergedCategories(categories.toRemove, targetUser.id, now)
                moved["reminders"] = attachReminders(sourceUserId, targetUser.id)

                // The eleven tables the handover used to stop short of. Most need one statement each: their uuid
                // is unique across the table, so no row of the account can be "the same record" and there is
                // nothing to merge. The rest are slots rather than records, and each is answered in proportion to
                // what a merge would cost — see `ProfileAttachDao` for which can collide and which cannot.
                moved["tracking_sessions"] = profileAttachDao.moveTrackingSessions(sourceUserId, targetUser.id, now)
                moved["work_sessions"] = profileAttachDao.moveWorkSessions(sourceUserId, targetUser.id, now)
                // The hours a reader measured are joined to their task by the local row id, so a task that merged
                // into the account's own copy takes those hours with it unless they are re-pointed to the row that
                // survived. After the move, and not before: until the intervals arrive they are still rows of the
                // profile being emptied, and a re-point that searches the *account's* rows finds nothing and
                // reports success.
                tasks.lost.forEach { (gone, survivor) ->
                    repointed += profileAttachDao.repointWorkSessions(targetUser.id, survivor, gone, now)
                    repointed += profileAttachDao.repointTrackingSessions(targetUser.id, survivor, gone, now)
                }
                moved["work_interval_corrections"] =
                    profileAttachDao.moveIntervalCorrections(sourceUserId, targetUser.id, now)
                moved["goals"] = profileAttachDao.moveGoals(sourceUserId, targetUser.id, now)
                moved["goal_value_changes"] = profileAttachDao.moveGoalValueChanges(sourceUserId, targetUser.id, now)
                moved["habits"] = profileAttachDao.moveHabits(sourceUserId, targetUser.id, now)
                moved["habit_check_changes"] = profileAttachDao.moveHabitCheckChanges(sourceUserId, targetUser.id, now)
                moved["goal_tasks"] = profileAttachDao.moveGoalTaskLinks(sourceUserId, targetUser.id)
                merged += profileAttachDao.dropDuplicateGoalTaskLinks(sourceUserId, targetUser.id)
                // Marks move with one statement: the schema makes a mark of one habit for one day impossible to
                // hold twice, and if it ever were possible the plain UPDATE would be refused by the index and the
                // transaction would roll back — see ProfileAttachDao's note on why merging here would be the wrong
                // answer to a state the app cannot explain.
                moved["habit_checks"] = profileAttachDao.moveHabitChecks(sourceUserId, targetUser.id, now)
                moved["insights"] = profileAttachDao.moveInsights(sourceUserId, targetUser.id, now)
                merged += profileAttachDao.dropDuplicateInsights(sourceUserId, targetUser.id)
                moved["achievements"] = profileAttachDao.moveAchievements(sourceUserId, targetUser.id)
                merged += profileAttachDao.dropDuplicateAchievements(sourceUserId, targetUser.id)

                val stranded = profileAttachDao.rowsStillOwnedBy(sourceUserId)
                if (stranded != 0) {
                    // Which tables, not just how many. A message that says "1 row is left behind" describes a
                    // fact the reader cannot look at; the table name is what turns "the move was refused" into
                    // "here is the record to look for", and it is what made the blank-topic case below findable
                    // at all when it first failed.
                    throw IllegalStateException(
                        "the handover left $stranded row(s) owned by profile $sourceUserId in " +
                            "${profileAttachDao.strandedTablesOf(sourceUserId)}; nothing was committed"
                    )
                }
            }
        }
        return AttachOutcome.Moved(
            AttachReport(
                sourceUserId = sourceUserId,
                targetUserId = targetUser.id,
                safetyCopy = checkNotNull(snapshot) {
                    "the handover completed without a snapshot, which its own guard makes impossible"
                },
                moved = moved,
                mergedDuplicates = merged,
                repointedLinks = repointed
            )
        )
    }

    /** The two ways a handover request ends without an error: everything moved, or there was nothing to do. */
    sealed class AttachOutcome {
        data class Moved(val report: AttachReport) : AttachOutcome()
        object AlreadyTheSameProfile : AttachOutcome()
    }

    /**
     * Series move as they are: [RecurrenceRule.uuid] is the primary key, so no rule of the signed-in
     * account can already hold this identity, and the row only needs a new owner. The cursor goes
     * with it, which is what keeps a moved series from repeating instances the user finished.
     *
     * @return the rows that arrived on the account, for the report the reader is shown.
     */
    private suspend fun attachRules(sourceUserId: Long, targetUserId: Long): Int {
        val rows = recurrenceDao.getAllRowsOnce(sourceUserId)
        rows.forEach { source ->
            recurrenceDao.upsertRule(source.copy(userId = targetUserId))
        }
        return rows.size
    }

    /**
     * Lead times move the same way: [Reminder.uuid] is the primary key and the `(task_uuid, offset_minutes)`
     * index spans the whole table, so the account cannot already hold the same warning and there is nothing to
     * merge. See the note on those two indexes in `ProfileAttachDao` — this is the case the handover used to
     * count and refuse, which the DDL shows cannot arise.
     *
     * @return the rows that arrived on the account.
     */
    private suspend fun attachReminders(sourceUserId: Long, targetUserId: Long): Int {
        val rows = reminderDao.getAllRowsOnce(sourceUserId)
        rows.forEach { source ->
            reminderDao.update(source.copy(userId = targetUserId))
        }
        return rows.size
    }

    /**
     * The categories that merged into the account's own, and the source rows that may be dropped afterwards.
     *
     * The two lists are returned together and the second one is acted on only once the tasks have moved, which
     * is the whole of the point: `tasks.category_id` points at a category `ON DELETE SET NULL`, so removing
     * the local copy of a merged category while its tasks are still sitting in the source profile leaves every
     * one of them with no category — and the map built to re-point them then has nothing left to read.
     *
     * The read is `getAllRowsOnce`, tombstones included. `getAllCategoriesOnce` filters `is_deleted = 0`, and a
     * handover that skipped a deleted category would leave it owned by the profile the reader has just left —
     * which, since the stranded check counts every row, is not a row left behind but a move that can never be
     * completed by anyone who has ever deleted a category. The deletion also has to travel: sync pushes category
     * tombstones, and one parked on an abandoned profile is a deletion that never reaches the account.
     */
    private class CategoryMerge(
        val survivors: Map<Long, Long>,
        val toRemove: List<Category>,
        /** Categories that arrived as new rows on the account. */
        val arrived: Int,
        /** Source copies dropped because the account already held that identity. */
        val mergedAway: Int
    )

    private suspend fun attachCategories(sourceUserId: Long, targetUserId: Long): CategoryMerge {
        val merged = LinkedHashMap<Long, Long>()
        val removable = mutableListOf<Category>()
        var arrived = 0
        categoryDao.getAllRowsOnce(sourceUserId).forEach { source ->
            val withIdentity = if (source.uuid.isNotBlank()) source else source.copy(
                uuid = UUID.randomUUID().toString()
            ).also { categoryDao.setUuid(source.id, it.uuid) }
            // Scoped to the account. `getCategoryByUuid` answers in rowid order with no owner filter, and the
            // local profile's rows are always older than the account's, so read that way the twin is invisible,
            // the local copy is re-tagged beside the account's own, and the reader gets one category twice.
            val twin = profileAttachDao.getCategoryOfUser(withIdentity.uuid, targetUserId)
            if (twin == null) {
                categoryDao.updateCategory(withIdentity.copy(userId = targetUserId))
                arrived++
            } else {
                merged[source.id] = twin.id
                removable += source
            }
        }
        return CategoryMerge(merged, removable, arrived, removable.size)
    }

    /**
     * Drops the local copies of categories that merged into the account's own. See [attachCategories].
     *
     * Each child of a copy being dropped is moved onto the surviving category first, because
     * `categories.parent_category_id` is `ON DELETE SET NULL` and the delete would otherwise flatten the tree the
     * reader built — a loss no foreign-key check can see, since the child row survives with a null parent.
     */
    private suspend fun removeMergedCategories(
        merged: List<Category>,
        targetUserId: Long,
        now: Long
    ) {
        for (category in merged) {
            val survivor = profileAttachDao.getCategoryOfUser(category.uuid, targetUserId)
                ?: throw IllegalStateException(
                    "the account's copy of category ${category.uuid} is gone, so ${category.name} has nothing to " +
                        "merge into; the handover stopped rather than orphan the categories and tasks under it"
                )
            profileAttachDao.repointCategoryChildren(targetUserId, survivor.id, category.id, now)
            categoryDao.deleteCategory(category)
        }
    }

    /**
     * The copy is only a guarantee while it describes the profile it was taken of.
     *
     * [ProfileSafetyCopy.take] counts the profile twice around its own copy and refuses if the two differ, but
     * nothing can stop a row being committed between the moment it returns and the moment this transaction
     * opens — a sync pull, a reminder renewal, a screen finishing a save. Such a row would be moved by this
     * transaction and would not be in the file the reader was told makes the move undoable, so the copy would
     * silently stop being the thing the whole ordering rests on. The totals are therefore compared again, once,
     * inside the transaction: [ProfileSafetyCopy.Snapshot.rowCount] is the sum of the same sixteen tables that
     * [ProfileAttachDao.rowsStillOwnedBy] counts for the same owner, so one number answers for all of them.
     *
     * The answer to a mismatch is a refusal before any write, which loses nothing: the profile keeps every row it
     * has, the copy that no longer fits is left where it is, and a retry takes a fresh one.
     */
    internal suspend fun assertCopyStillCovers(
        sourceUserId: Long,
        snapshot: ProfileSafetyCopy.Snapshot
    ) {
        val nowOwned = profileAttachDao.rowsStillOwnedBy(sourceUserId)
        if (nowOwned != snapshot.rowCount) {
            throw ProfileSafetyCopy.SafetyCopyUnavailable(
                "the profile changed after its copy was verified ($nowOwned row(s) now, ${snapshot.rowCount} " +
                    "when the copy was taken); nothing was moved"
            )
        }
    }

    /**
     * Topics move as they are: [TaskGroup.uuid] is the primary key, so one topic row can only have one owner and
     * there is nothing for a merge to decide. Tasks reference topics by that uuid, which is why no re-pointing
     * follows.
     *
     * A topic whose uuid is blank travels with the blank it has, and that is deliberate. This branch used to
     * mint a uuid for such a row, and minting is the wrong verb twice over: the new identity belongs to a new
     * primary key, so the insert added a second row and left the original owned by the profile being emptied —
     * which the stranded check then refused, permanently, for that reader; and `tasks.group_id` still named the
     * blank, so the topic's own tasks were orphaned by the very statement that gave it an identity. An
     * identity-less row is a real problem, but it is a *sync* problem (a document with no id cannot be pushed)
     * and the handover fixing it by inventing one is exactly the quiet re-parenting this stage refuses
     * elsewhere. It is on the roadmap as the sync stage's to answer.
     *
     * @return the rows that arrived on the account.
     */
    private suspend fun attachGroups(sourceUserId: Long, targetUserId: Long): Int {
        val rows = groupDao.getAllGroupsOnce(sourceUserId)
        rows.forEach { source ->
            groupDao.upsertGroup(source.copy(userId = targetUserId))
        }
        return rows.size
    }

    /**
     * The rows the task merge deleted, and the rows that took their place.
     *
     * A uuid twin is one task held by two profiles, and the newer copy wins: the loser's row is deleted. That is
     * invisible to the task list and not invisible to the measured work, which joins on `tasks.id`
     * (`WorkStatisticsDao.kt:113-141`) — an interval that named the loser keeps its row and loses its task. The
     * map is `deleted rowid → surviving rowid`, in both directions, because either side can be the one that goes.
     */
    private class TaskMerge(
        val lost: Map<Long, Long>,
        /** Tasks that arrived as new rows on the account: everything except the twins the merge dropped. */
        val arrived: Int
    )

    private suspend fun attachTasks(
        sourceUserId: Long,
        targetUserId: Long,
        mergedCategories: Map<Long, Long>
    ): TaskMerge {
        val lost = LinkedHashMap<Long, Long>()
        var arrived = 0
        taskDao.getAllRowsOnce(sourceUserId).forEach { source ->
            val withIdentity = if (source.uuid.isNotBlank()) source else source.copy(
                uuid = UUID.randomUUID().toString()
            ).also { taskDao.setUuid(source.id, it.uuid) }
            // Scoped to the account, and it has to be. `TaskDao.findTaskByUuid` is
            // `WHERE uuid = :uuid LIMIT 1` with no owner and no order, and the scan answers in rowid order — which
            // is the *local* row, because the local profile predates the account's. Read that way the twin is never
            // seen, this row is re-tagged beside the account's copy of the same task, and the reader gets one task
            // twice with nothing left stranded to fail the check. Tombstones are included, so a deletion marker is
            // matched too.
            val twin = profileAttachDao.getTaskOfUser(withIdentity.uuid, targetUserId)
            val moved = withIdentity.copy(
                userId = targetUserId,
                categoryId = withIdentity.categoryId?.let { mergedCategories[it] ?: it }
            )
            when {
                twin == null -> {
                    taskDao.updateTask(moved)
                    arrived++
                }
                // The account already has this task: the newer row wins, the older one goes.
                withIdentity.updatedAt > twin.updatedAt -> {
                    taskDao.deleteTask(twin)
                    taskDao.updateTask(moved)
                    lost[twin.id] = moved.id
                    arrived++
                }

                else -> {
                    taskDao.deleteTask(source)
                    lost[source.id] = twin.id
                }
            }
        }
        return TaskMerge(lost, arrived)
    }

    /**
     * Ends the Google and Firebase session. The reader stays on the profile they were using, and this says whether
     * the cloud half actually closed.
     *
     * The local profile keeps its rows, its `firebase_uid` link and its place as the current profile. What it
     * loses is the session: `SyncRepository.syncNow` still sees a uid on the row, so it goes as far as Firestore
     * and comes back with a failure rather than answering `NotLinked` — which is honest about the attempt but not
     * the clearest thing to show a reader who just signed out. Making sync say "no session" before it reaches for
     * the network is the sync stage's job, and it is on the open list in the plan for that reason; what is not an
     * option is the alternative this method used to offer.
     *
     * That alternative was `SessionRepository.endSession()`, which clears `is_current` on every row *and* removes
     * the `session_last_user_id` repair anchor. The next start-up then resolved to nobody: the Profile screen
     * answered `NoUser`, every list lost the owner it reads by, and the reader was invited to create a profile
     * again while all of their data sat in the database underneath. Signing out of an account must not be a way
     * to lose access to a profile, so the session is left standing and only the cloud half is closed.
     *
     * The link is deliberately not cleared. A reader who signs in again with the same Google account reaches
     * `getUserByFirebaseUid` and is handed back this profile — that is what makes a second sign-in a return
     * rather than a second copy of the same person.
     */
    suspend fun signOut(): SessionEnd {
        val ended = signInTransport.endSession()
        // Nothing else. The profile the reader was on stays on: `SessionRepository.endSession()` clears
        // `is_current` on *every* row and removes the `session_last_user_id` repair anchor with it, so an
        // unqualified call here — or one guarded on the *cached* user, which is null in a process that has not
        // finished `restore()` yet — reproduces the exact state this stage exists to remove: a full database and
        // an empty app. The session is resolved from disk rather than from the cache so that a cold process
        // cannot be mistaken for "nobody is signed in".
        sessionRepository.resolveUser()
        return ended
    }
}
