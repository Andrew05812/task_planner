package com.taskplanner.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalTaskLink
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheck
import com.taskplanner.data.model.HabitCheckChange
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.TrackingState
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.testing.SeededProfile
import com.taskplanner.ui.settings.AccountUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reader's data, across a sign-in — measured on the device, on the database the app actually opens.
 *
 * The JVM equivalent (`AccountAttachTest`) runs on Robolectric's legacy SQLite, which is a real SQLite but not
 * *this* device's: no WAL sidecars under the paths Android manages, no filesystem the OS can purge, and a text
 * layer that measures a character as one pixel. What this class adds over it is the part of the guarantee that
 * only a device can give — the safety copy is written where Android keeps app files, checkpointed out of a live
 * write-ahead log, and **reopened and counted by the SQLite that ships**. If the copy cannot be read back on a
 * device, the whole "this can be undone" promise is a JVM fiction.
 *
 * **The seam, and what it does not replace.** There is no Firebase project in any build of this repository, so
 * no instrumented test can drive the real Google account chooser. What is driven here is everything the
 * credential is used *for*: `linkProfile` (the decision that turns a uid and an address into a local profile)
 * and `attachLocalDataToAccount` (the move). The network half — Credential Manager, the ID token, the
 * Firebase exchange — is exercised only by a person signing in, and this file does not claim otherwise; see
 * `docs/FIREBASE_SETUP.md` for what has to be configured first, and the stage report for the status that
 * follows from it.
 *
 * Every row this test seeds is addressed to a `@example.test` profile, and the runner wipes the app's data
 * directory before a connected run (`app/build.gradle.kts`'s `clearPackageData`), so nothing here can reach a
 * profile a person created.
 */
@RunWith(AndroidJUnit4::class)
class SignInDataPreservationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var sessionScope: CoroutineScope
    private lateinit var session: SessionRepository
    private lateinit var auth: AuthRepository
    private lateinit var safetyCopy: ProfileSafetyCopy
    private var signedIn: SeededProfile.SignedIn? = null

    /** The tables a profile owns, exactly as the shipped schema lists them. */
    private val profileTables = listOf(
        "tasks", "categories", "task_groups", "recurrence_rules", "reminders",
        "tracking_sessions", "work_sessions", "work_interval_corrections",
        "goals", "goal_tasks", "goal_value_changes",
        "habits", "habit_checks", "habit_check_changes",
        "insights", "achievements"
    )

    @Before
    fun openTheAppsOwnDatabase() {
        database = SeededProfile.open(context)
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session = SessionRepository(
            database.userDao(),
            context.getSharedPreferences("instrumented-session", Context.MODE_PRIVATE),
            sessionScope
        )
        safetyCopy = ProfileSafetyCopy(context, database)
        auth = AuthRepository(
            context,
            dagger.Lazy { throw UnsupportedOperationException("no Firebase in an instrumented run") },
            NoCredentialSignInTransport,
            session,
            database.userDao(),
            database.taskDao(),
            database.categoryDao(),
            database.groupDao(),
            database.recurrenceDao(),
            database.reminderDao(),
            database.profileAttachDao(),
            safetyCopy,
            database
        )
    }

    @After
    fun release() {
        runBlocking { signedIn?.let { SeededProfile.takeAway(database, it) } }
        // `takeAway` knows the profile it signed in, and after a handover that profile owns nothing: every row it
        // had is now the *account's*, and the account row itself was created by this test. The account goes the way
        // every fixture does — by the address space it was seeded in — or the next run inherits these rows and
        // reads them as somebody else's residue.
        SeededProfile.sweepLeftovers(database)
        sessionScope.cancel()
        database.close()
    }

    /**
     * Sign in with a Google-shaped identity, move a whole profile onto it, and read everything back — through
     * a *new* session object, which is what a process restart gives the app.
     *
     * The order is the reader's: data first, then the account. The assertions are the reader's too: the same
     * rows, on the profile they can open, with a copy on disk that the shipping SQLite can read.
     */
    @Test
    fun aWholeProfileSurvivesSignInRestartSignOutAndSignInAgain() = runBlocking {
        val address = "preserve-${System.currentTimeMillis()}@example.test"
        val local = SeededProfile.signIn(database, email = address, name = "Preserve Ivan")
            .also { signedIn = it }
        seedWholeProfile(local.seededUserId)
        val before = profileTables.associateWith { count(it, local.seededUserId) }
        assertEquals("every table a profile owns holds a seeded row: $before", 1, before.values.min())

        // 1. The account appears (the seam), the session moves to it, and the profile is handed over.
        //    The account's address is deliberately *not* the local profile's: `users.email` is unique, and a
        //    sign-in whose address is already owned is the case `linkProfile` refuses — asserted on its own in
        //    `aGoogleAddressAlreadyOwnedByALocalProfileRefusesWithoutTouchingIt`.
        val linked = auth.linkProfile(
            uid = "uid-preserve-${System.nanoTime()}", email = "preserve-account@example.test",
            displayName = "Preserve Ivan", photoUrl = null
        )
        val account = (linked as AuthRepository.ProfileUpsert.Linked).user
        session.setCurrentUser(account)
        val outcome = auth.attachLocalDataToAccount(local.seededUserId, account)
        assertTrue(
            "the handover reported ${outcome.javaClass.simpleName} instead of a move",
            outcome is AuthRepository.AttachOutcome.Moved
        )
        val report = (outcome as AuthRepository.AttachOutcome.Moved).report

        // 2. Nothing is stranded, and the links still resolve on the new owner.
        assertEquals("no row was left on the profile that was moved away", 0, countAll(profileTables, local.seededUserId))
        assertEquals("every row arrived", before, profileTables.associateWith { count(it, account.id) })
        assertEquals(
            "the task still points at the category it was filed in", "Home",
            database.taskDao().getAllRowsOnce(account.id).single().categoryId?.let {
                database.categoryDao().getCategoryForUser(account.id, it)?.name
            }
        )
        assertNoBrokenReference()

        // 3. The safety copy is a database the device can open, and it holds the rows that were moved.
        assertTrue("the snapshot is not on disk: ${report.safetyCopy.file}", report.safetyCopy.file.exists())
        val fromCopy = safetyCopy.verifyReadable(report.safetyCopy.file, local.seededUserId)
        assertEquals(
            "the snapshot does not hold what the profile owned before the move", before, fromCopy
        )

        // 4. A process restart: a session that has never seen this storage resolves to the account and its rows.
        val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restarted = SessionRepository(
            database.userDao(),
            context.getSharedPreferences("instrumented-session", Context.MODE_PRIVATE),
            restartedScope
        )
        assertEquals("the restarted app resolves to the signed-in profile", account.id, restarted.currentUserId())
        assertEquals("and its tasks are readable through that session", 1,
            database.taskDao().getAllRowsOnce(restarted.currentUserId()!!).size)
        // `SessionRepository` owns no storage and has nothing to close; the scope is the only live thing it took.
        restartedScope.cancel()

        // 5. Sign out: the cloud session ends, the profile and its data stay reachable.
        auth.signOut()
        assertEquals("signing out left the reader without a profile", account.id, session.currentUserId())
        assertEquals("the rows are still the account's", before, profileTables.associateWith { count(it, account.id) })
        assertNotNull("the profile lost the link that makes a second sign-in a return",
            database.userDao().getUserById(account.id)?.firebaseUid)

        // 6. Sign in again with the same uid: the same profile, not a second copy of the person.
        val again = auth.linkProfile(
            uid = account.firebaseUid, email = account.email,
            displayName = "Preserve Ivan", photoUrl = null
        )
        assertEquals(
            "a second sign-in created another profile instead of returning to this one",
            account.id, (again as AuthRepository.ProfileUpsert.Linked).user.id
        )
        val second = auth.attachLocalDataToAccount(local.seededUserId, account)
        assertTrue(
            "re-attaching an already-empty profile must be a no-op, not a wipe",
            second is AuthRepository.AttachOutcome.AlreadyTheSameProfile ||
                (second as AuthRepository.AttachOutcome.Moved).report.moved.values.sum() == 0
        )
        assertEquals("and the rows are still there", before, profileTables.associateWith { count(it, account.id) })
    }

    /**
     * The sign-in a reader with a Google-named local profile actually meets, measured on the device's SQLite.
     *
     * `users.email` is unique and non-null, and the profile the reader made by hand often carries the very address
     * they now sign in with. The rule is that the address is *not* treated as identity: the link is refused, the
     * cloud session is closed again, and the profile that owns the address is left exactly as it was — because the
     * alternative this repository used to implement was an insert that resolved the collision by deleting the row
     * it collided with, and thirteen tables answer a `users` deletion by cascading. The JVM test of the same rule
     * runs on Robolectric; this one runs on the index that ships.
     */
    @Test
    fun aGoogleAddressAlreadyOwnedByALocalProfileRefusesWithoutTouchingIt() = runBlocking {
        val address = "shared-${System.currentTimeMillis()}@example.test"
        val local = SeededProfile.signIn(database, email = address, name = "Shared Ivan").also { signedIn = it }
        database.taskDao().insertTask(
            Task(uuid = "task-shared", title = "Keep me", description = "", userId = local.seededUserId)
        )
        // Counted rather than assumed to be zero: what this test must prove is that a refused link creates no
        // account profile, and a device that still carried one from a run that was killed mid-way would otherwise
        // read as a defect in the rule.
        val accountsBefore = scalar("SELECT count(*) FROM users WHERE firebase_uid IS NOT NULL")

        val linked = auth.linkProfile(
            uid = "uid-shared-${System.nanoTime()}", email = address,
            displayName = "Shared Ivan", photoUrl = null
        )

        assertTrue(
            "the sign-in answered ${linked.javaClass.simpleName} instead of refusing a taken address",
            linked is AuthRepository.ProfileUpsert.AddressTaken
        )
        assertEquals(
            "and the reader is still on the profile they were using", local.seededUserId, session.currentUserId()
        )
        assertEquals(
            "whose task is untouched, and whose row is not a second copy of the person",
            listOf("Keep me"), database.taskDao().getAllRowsOnce(local.seededUserId).map { it.title }
        )
        assertEquals(
            "no account profile was created alongside it", accountsBefore,
            scalar("SELECT count(*) FROM users WHERE firebase_uid IS NOT NULL")
        )
    }

    /** Settings' state A on a device with no configuration: the row is shown, the button is not usable. */
    @Test
    fun theSignInRowIsVisibleAndDisabledWhenTheBuildHasNoConfiguration() {
        assertEquals(
            "this build must have no cloud configuration for the state-A assertions to mean anything",
            false, CloudConfig.isConfigured
        )
        // The rendered screen is asserted in `Stage8aScreensInstrumentedTest`, which opens Settings through the
        // bottom bar; what belongs here is the rule the screen reads its state from, so a change to either the
        // enum or the configuration gate fails somewhere, and not only in a screenshot.
        val state = AccountUiState.of(configured = CloudConfig.isConfigured, sessionActive = false, user = null)
        assertEquals("an unconfigured build is in state A", AccountUiState.NotConfigured, state)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun seedWholeProfile(userId: Long): String {
        val categoryId = database.categoryDao().insertCategory(
            Category(name = "Home", color = -1, icon = "ic_tag", userId = userId)
        )
        database.groupDao().upsertGroup(TaskGroup(uuid = "group-p", userId = userId, name = "HOUSEWORK"))
        val taskId = database.taskDao().insertTask(
            Task(
                uuid = "task-p", title = "Pay the rent", description = "", userId = userId,
                categoryId = categoryId, groupId = "group-p"
            )
        )
        database.recurrenceDao().upsertRule(
            RecurrenceRule(
                uuid = "rule-p", userId = userId, unit = RepeatUnit.WEEK, interval = 1, weekDays = "1,3",
                anchorAt = 1_000, cursorAt = 2_000
            )
        )
        database.reminderDao().insertOrIgnore(
            Reminder(
                uuid = "rem-p", taskUuid = "task-p", userId = userId, offsetMinutes = 15,
                triggerAt = 5_000, anchorAt = 4_000
            )
        )
        database.trackingSessionDao().insert(
            TrackingSession(
                uuid = "track-p", userId = userId, taskId = taskId, taskUuid = "task-p",
                taskTitle = "Pay the rent", state = TrackingState.COMPLETED, startedAt = 1_000, stoppedAt = 2_000
            )
        )
        database.workSessionDao().insert(
            WorkSession(
                uuid = "interval-p", userId = userId, trackingSessionUuid = "track-p", taskId = taskId,
                taskUuid = "task-p", startedAt = 1_000, endedAt = 2_000, durationSeconds = 60,
                localDay = 20_000, utcOffsetMinutes = 0
            )
        )
        database.workSessionDao().insertCorrection(
            WorkIntervalCorrection(
                uuid = "corr-p", intervalUuid = "interval-p", userId = userId, oldStartedAt = 1_000,
                oldEndedAt = 2_000, newStartedAt = 1_500, newEndedAt = 2_500
            )
        )
        database.goalDao().insertGoal(
            Goal(uuid = "goal-p", userId = userId, title = "Clear the backlog", type = GoalType.TASK_SET,
                targetValue = 3)
        )
        database.goalDao().insertLink(GoalTaskLink(goalUuid = "goal-p", taskUuid = "task-p", userId = userId))
        database.goalDao().insertValueChange(
            GoalValueChange(uuid = "goalchange-p", goalUuid = "goal-p", userId = userId, field = "target_value")
        )
        database.habitDao().insertHabit(Habit(uuid = "habit-p", userId = userId, title = "Read", startDay = 20_000))
        database.habitDao().insertCheck(
            HabitCheck(
                uuid = "check-p", habitUuid = "habit-p", userId = userId, localDay = 20_000,
                utcOffsetMinutes = 0, dayMarker = 20_000
            )
        )
        database.habitDao().insertCheckChange(
            HabitCheckChange(
                uuid = "checkchange-p", checkUuid = "check-p", habitUuid = "habit-p", userId = userId,
                oldLocalDay = 19_999, newLocalDay = 20_000
            )
        )
        database.insightDao().insert(
            Insight(
                uuid = "insight-p", userId = userId, ruleKey = "thursday-heavy", kind = InsightKind.PATTERN,
                title = "Thursdays are heavy", body = "Most of what got finished was finished on Thursdays.",
                basis = "12 finished tasks", periodFromDay = 19_900, periodToDay = 20_000, sampleSize = 12,
                confidence = 3, firstSeenAt = 1_000, lastSeenAt = 2_000
            )
        )
        database.achievementDao().insertOrIgnore(
            Achievement(
                uuid = "award-p", userId = userId, achievementKey = "tasks-10",
                category = AchievementCategory.VOLUME, title = "Ten done", description = "10 tasks completed",
                unit = AchievementUnit.TASKS, targetValue = 10, progressValue = 12, earnedDay = 20_000,
                earnedAt = 2_000
            )
        )
        return "task-p"
    }

    private fun count(table: String, userId: Long): Int =
        scalar("SELECT count(*) FROM $table WHERE user_id = $userId")

    private fun countAll(tables: List<String>, userId: Long): Int = tables.sumOf { count(it, userId) }

    private fun scalar(sql: String): Int =
        database.openHelper.readableDatabase.query(sql).use { c ->
            if (!c.moveToNext()) throw AssertionError("no answer for: $sql")
            c.getInt(0)
        }

    private fun assertNoBrokenReference() {
        val broken = mutableListOf<String>()
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { c ->
            val table = c.getColumnIndexOrThrow("table")
            val parent = c.getColumnIndexOrThrow("parent")
            while (c.moveToNext()) broken += "${c.getString(table)} -> ${c.getString(parent)}"
        }
        assertEquals("foreign_key_check on the device: $broken", emptyList<String>(), broken)
    }

}

/**
 * The credential half of a sign-in, refused on purpose.
 *
 * No instrumented run in this repository has an OAuth Web client id, so `CredentialManager.getCredential`
 * would open a chooser that cannot be answered. This test drives the two things a credential is *for* —
 * `linkProfile` and `attachLocalDataToAccount` — directly, and this transport makes an accidental call into the
 * credential path a failure that names itself rather than a hang.
 */
private object NoCredentialSignInTransport : GoogleSignInTransport {
    override suspend fun signIn(activity: androidx.fragment.app.FragmentActivity): SignInAttempt =
        throw UnsupportedOperationException(
            "no Firebase project is configured for this build; see docs/FIREBASE_SETUP.md"
        )

    override suspend fun endSession(): SessionEnd = SessionEnd.NotConfigured
}
