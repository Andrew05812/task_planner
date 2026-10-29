package com.taskplanner.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import com.taskplanner.testing.AppFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole profile, moved.
 *
 * `attachLocalDataToAccount` was written when the app owned five tables. The schema this branch ships
 * (`app/schemas/com.taskplanner.data.local.AppDatabase/16.json`, version 16) has **sixteen** tables carrying a
 * `user_id`, and the eleven that arrived with time tracking, goals, habits, insights and achievements were in
 * none of the handover's code: it touched tasks, categories, task_groups, recurrence_rules and reminders, and
 * nothing else. Neither did cloud sync (`SyncRepository.kt:105-158` pushes the same five) nor a backup file
 * (`BackupModels.kt:27-33` lists the same five). So a reader who signed in and accepted "move my local data"
 * kept their measured work, their goals, their habit marks, their insights and their achievements only in a
 * profile the app had just stopped showing them — which is the exact outcome this stage exists to make
 * impossible, and `ProfileAttachDao` is where those eleven now move.
 *
 * What each test below asks for is stated as a guarantee a reader would recognise, not as a call into the
 * implementation: everything moved, nothing left behind, nothing lost on the way, a failure leaves both
 * profiles as they were, and the reader is still standing on a profile they can open.
 *
 * The counts are read with raw SQL over every table rather than through the DAOs the handover itself uses.
 * A test that counted moved rows with the same method the code used to move them would pass on a table the
 * handover never visits, which is precisely how the eleven missing ones stayed missing.
 */
@RunWith(RobolectricTestRunner::class)
class AccountAttachTest {

    private lateinit var fixture: AppFixture
    private lateinit var local: User

    /** Every table that carries a `user_id`, read from the shipped schema, not from memory. */
    private val profileTables = listOf(
        "tasks", "categories", "task_groups", "recurrence_rules", "reminders",
        "tracking_sessions", "work_sessions", "work_interval_corrections",
        "goals", "goal_tasks", "goal_value_changes",
        "habits", "habit_checks", "habit_check_changes",
        "insights", "achievements"
    )

    @Before
    fun signInALocalProfile() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        local = runBlocking { fixture.signIn(name = "Local Ivan", email = "ivan@local") }
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // ---------------------------------------------------------------- the seeding

    /**
     * One row in every table a profile owns, linked to each other the way the app links them: a task in a
     * category under a topic, with a series, a lead time, a measured interval inside a tracking session and a
     * correction on it, a goal with a linked task and a change, a habit with a mark and a change, an insight
     * and an achievement.
     */
    private suspend fun seedWholeProfile(userId: Long): ProfileRows {
        val category = Category(name = "Home", color = -1, icon = "ic_tag", userId = userId)
        val categoryId = fixture.categoryDao.insertCategory(category)
        val group = TaskGroup(uuid = "group-home", userId = userId, name = "HOUSEWORK")
        fixture.groupDao.upsertGroup(group)
        val taskUuid = "task-1"
        val taskId = fixture.taskDao.insertTask(
            Task(
                uuid = taskUuid, title = "Pay rent", description = "", userId = userId,
                categoryId = categoryId, groupId = group.uuid
            )
        )
        fixture.recurrenceDao.upsertRule(
            RecurrenceRule(
                uuid = "rule-1", userId = userId, unit = RepeatUnit.WEEK, interval = 1,
                weekDays = "1,3", anchorAt = 1_000, cursorAt = 2_000
            )
        )
        fixture.reminderDao.insertOrIgnore(
            Reminder(
                uuid = "rem-1", taskUuid = taskUuid, userId = userId, offsetMinutes = 15,
                triggerAt = 5_000, anchorAt = 4_000
            )
        )
        fixture.trackingSessionDao.insert(
            TrackingSession(
                uuid = "track-1", userId = userId, taskId = taskId, taskUuid = taskUuid,
                taskTitle = "Pay rent", state = TrackingState.COMPLETED, startedAt = 1_000, stoppedAt = 2_000
            )
        )
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = "interval-1", userId = userId, trackingSessionUuid = "track-1", taskId = taskId,
                taskUuid = taskUuid, startedAt = 1_000, endedAt = 2_000, durationSeconds = 60,
                localDay = 20_000, utcOffsetMinutes = 0
            )
        )
        fixture.workSessionDao.insertCorrection(
            WorkIntervalCorrection(
                uuid = "correction-1", intervalUuid = "interval-1", userId = userId,
                oldStartedAt = 1_000, oldEndedAt = 2_000, newStartedAt = 1_500, newEndedAt = 2_500
            )
        )
        fixture.goalDao.insertGoal(
            Goal(
                uuid = "goal-1", userId = userId, title = "Finish the backlog",
                type = GoalType.TASK_SET, targetValue = 3
            )
        )
        fixture.goalDao.insertLink(GoalTaskLink(goalUuid = "goal-1", taskUuid = taskUuid, userId = userId))
        fixture.goalDao.insertValueChange(
            GoalValueChange(uuid = "goalchange-1", goalUuid = "goal-1", userId = userId, field = "target_value")
        )
        fixture.habitDao.insertHabit(
            Habit(uuid = "habit-1", userId = userId, title = "Read", startDay = 20_000)
        )
        fixture.habitDao.insertCheck(
            HabitCheck(
                uuid = "check-1", habitUuid = "habit-1", userId = userId, localDay = 20_000,
                utcOffsetMinutes = 0, dayMarker = 20_000
            )
        )
        fixture.habitDao.insertCheckChange(
            HabitCheckChange(
                uuid = "checkchange-1", checkUuid = "check-1", habitUuid = "habit-1", userId = userId,
                oldLocalDay = 19_999, newLocalDay = 20_000
            )
        )
        fixture.insightDao.insert(
            Insight(
                uuid = "insight-1", userId = userId, ruleKey = "thursday-heavy", kind = InsightKind.PATTERN,
                title = "Thursdays are heavy", body = "Most of what got finished was finished on Thursdays.",
                basis = "12 finished tasks", periodFromDay = 19_900, periodToDay = 20_000,
                sampleSize = 12, confidence = 3, firstSeenAt = 1_000, lastSeenAt = 2_000
            )
        )
        fixture.achievementDao.insertOrIgnore(
            Achievement(
                uuid = "award-1", userId = userId, achievementKey = "tasks-10",
                category = AchievementCategory.VOLUME, title = "Ten done", description = "10 tasks completed",
                unit = AchievementUnit.TASKS, targetValue = 10, progressValue = 12,
                earnedDay = 20_000, earnedAt = 2_000
            )
        )
        return ProfileRows(taskUuid = taskUuid, categoryLocalId = categoryId, groupUuid = group.uuid)
    }

    private class ProfileRows(val taskUuid: String, val categoryLocalId: Long, val groupUuid: String)

    // ---------------------------------------------------------------- the reading

    private fun count(table: String, userId: Long): Int = scalar("SELECT count(*) FROM $table WHERE user_id = $userId")

    /** Which rows of [table] the profile holds, by uuid — for the cases where *which one* is the whole question. */
    private fun uuidsOf(table: String, userId: Long): List<String> =
        strings("SELECT uuid FROM $table WHERE user_id = $userId ORDER BY uuid").sorted()

    private fun countAll(table: String): Int = scalar("SELECT count(*) FROM $table")

    private fun scalar(sql: String): Int =
        fixture.database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (!cursor.moveToNext()) throw AssertionError("the count query returned no row: $sql")
            cursor.getInt(0)
        }

    private fun strings(sql: String): List<String> =
        fixture.database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
        }

    /** The message of [error] and of every cause beneath it, in one string — what a test compares an injected failure to. */
    private fun messagesOf(error: Throwable): String = buildString {
        var current: Throwable? = error
        while (current != null) {
            append(current::class.simpleName).append(": ").append(current.message).append(" | ")
            current = current.cause
        }
    }

    /**
     * The column sets the storage enforces as unique for [table], read out of `sqlite_master`.
     *
     * From the schema rather than from the annotations, because the claim this class's tests rest on is about
     * what the database refuses to write — and only the shipped DDL says that. Autoindexes (which SQLite creates
     * for a primary key or an inline `UNIQUE` column) carry no SQL text here and are left out; [primaryKeyOf]
     * covers the primary key.
     */
    private fun uniqueKeysOf(table: String): List<Set<String>> =
        strings(
            "SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' AND sql IS NOT NULL"
        ).filter { it.contains("UNIQUE", ignoreCase = true) }.map { statement ->
            Regex("\\(([^)]*)\\)").find(statement)
                ?.groupValues?.get(1)
                ?.split(',')
                ?.map { it.trim().trim('`') }
                ?.toSet()
                ?: emptySet()
        }

    /** The primary key of [table], in either form Room writes: a table constraint or an inline column clause. */
    private fun primaryKeyOf(table: String): Set<String> {
        val statement = strings("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'").single()
        Regex("PRIMARY KEY ?\\(([^)]*)\\)").find(statement)?.let { found ->
            return found.groupValues[1].split(',').map { it.trim().trim('`') }.toSet()
        }
        val body = statement.substringAfter('(').substringBeforeLast(')')
        return body.split(',')
            .map { it.trim() }
            .filter { it.contains("PRIMARY KEY", ignoreCase = true) }
            .map { it.split(' ').first().trim('`') }
            .toSet()
    }

    /**
     * Whether one [table] row identity can exist only once in the whole database — which is the same as asking
     * whether two profiles can each hold it. A `uuid` that is the primary key, or covered by a table-wide unique
     * index, says no; a `uuid` with nothing enforcing it, as `tasks` and `categories` have, says yes.
     */
    private fun identityIsTableWide(table: String): Boolean =
        uniqueKeysOf(table).contains(setOf("uuid")) || primaryKeyOf(table) == setOf("uuid")

    private fun currentProfileCount(): Int = scalar("SELECT count(*) FROM users WHERE is_current = 1")

    /** Signs in a Google-shaped profile and makes it the session, the way a successful sign-in does. */
    private suspend fun signInAsGoogleAccount(): User = fixture.signInAs(
        User(email = "ivan@gmail.com", name = "Ivan", firebaseUid = "uid-${System.nanoTime()}")
    )

    // ---------------------------------------------------------------- the guarantees

    @Test
    fun `every table a profile owns arrives on the account it is linked to`() = runTest {
        seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()
        val before = profileTables.associateWith { count(it, local.id) }
        assertEquals(
            "the fixture itself is wrong: some table holds no row of this profile",
            1, before.values.min()
        )

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val notMoved = profileTables.filter { count(it, account.id) == 0 }
        assertEquals(
            "these tables still belong to the profile the reader has just left: $notMoved — the handover " +
                "moves ${profileTables.size - notMoved.size} of ${profileTables.size}",
            emptyList<String>(), notMoved
        )
        // Per table, not in total: an account that gained two tasks and lost two habit marks has a healthy
        // sum and a reader with no marks. Nothing in this fixture collides, so every table's count is exact.
        val differs = profileTables.mapNotNull { table ->
            val now = count(table, account.id)
            if (now == before.getValue(table)) null else "$table ${before.getValue(table)}→$now"
        }
        assertEquals(
            "the account holds a different number of rows than the profile was emptied of: " +
                differs.joinToString(),
            emptyList<String>(), differs
        )
    }

    @Test
    fun `nothing is left owned by a profile nobody is signed into`() = runTest {
        seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val stranded = profileTables.mapNotNull { table ->
            count(table, local.id).takeIf { it > 0 }?.let { table to it }
        }
        assertEquals(
            "rows the handover left behind, addressed by a profile the app no longer shows: $stranded",
            emptyList<Pair<String, Int>>(), stranded
        )
    }

    /**
     * Conservation, table by table, and every link still resolving.
     *
     * A move that deletes one side of a conflict to make room for the other would pass `every table … arrives`
     * (something did arrive) and a total row count would hide it too: two rows lost in `tasks` and two gained in
     * `work_sessions` leaves the sum untouched. So the comparison is per table, on the whole database rather than
     * one profile's slice — nothing may be created, and nothing may disappear — and the table it prints is the
     * same one the stage's report quotes, so a reader of this log sees the numbers a reader of the app sees.
     */
    @Test
    fun `the handover conserves the rows and keeps every link pointing where it did`() = runTest {
        val rows = seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()
        val before = profileTables.associateWith { countAll(it) }

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val after = profileTables.associateWith { countAll(it) }
        val ledger = profileTables.joinToString { table ->
            "$table ${before.getValue(table)}→${after.getValue(table)}"
        }
        println("AccountAttach conservation: $ledger")
        val changed = profileTables.filter { before.getValue(it) != after.getValue(it) }
        assertEquals(
            "a moved row is a moved row, not a rewritten, duplicated or dropped one. Tables whose total moved: " +
                changed.joinToString { "$it ${before.getValue(it)}→${after.getValue(it)}" },
            emptyList<String>(), changed
        )

        val movedTask = fixture.taskDao.getAllRowsOnce(account.id).single()
        assertEquals("Pay rent", movedTask.title)
        assertNotNull("the task still names a category", movedTask.categoryId)
        assertEquals(
            "and it is the moved category, not an unrelated row", "Home",
            fixture.categoryDao.getCategoryForUser(account.id, movedTask.categoryId!!)?.name
        )
        assertEquals(
            "the interval still points at the tracking session it belongs to", "track-1",
            fixture.workSessionDao.getAllForUser(account.id).single().trackingSessionUuid
        )
        assertEquals(
            "the correction still points at the interval it corrects", "interval-1",
            fixture.workSessionDao.getCorrectionsForUser(account.id).single().intervalUuid
        )
        assertEquals(
            "the goal's task link still names the same task uuid", setOf(rows.taskUuid),
            fixture.goalDao.linksOf("goal-1", account.id).map { it.taskUuid }.toSet()
        )
        assertEquals(
            "the habit mark still names the same habit", "habit-1",
            fixture.habitDao.getAllChecksForUser(account.id).single().habitUuid
        )
        assertEquals(
            "the mark's change still names the mark it changed", "check-1",
            fixture.habitDao.getAllChangesForUser(account.id).single().checkUuid
        )
        assertEquals(
            "the reminder still names the task it warns for", setOf(rows.taskUuid),
            fixture.reminderDao.getAllRowsOnce(account.id).map { it.taskUuid }.toSet()
        )
    }

    /**
     * Identity and queue position, table by table.
     *
     * The test above counts rows, and a count is satisfied by a copy: a handover that moved a row by writing a
     * *new* one and deleting the old would leave all sixteen totals standing. What a reader would notice lives
     * elsewhere. A task that arrives under a fresh uuid is a different document to every other device, so the
     * next sync writes a second copy of a task the account already had; and a row that arrives marked `SYNCED`
     * when it was waiting to be pushed is a deletion marker that never travels, so a task the reader deleted
     * comes back. Both are invisible to a count, so this asks two questions of every table: do the same
     * identities arrive, and does each row keep the place it held in the queue.
     *
     * The first question has a deliberate exception, and writing the test is what surfaced it. A row can be
     * created on this device with no identity at all — `Category` carries a blank `uuid` until something names
     * it — and a blank is not an identity to preserve: it is the absence of one, which is why the handover
     * mints a real uuid on the way in rather than moving the blank. So this asserts three things instead of
     * one: every row that *had* an identity arrives with that same one, every row arrives with an identity at
     * all, and no two arriving rows share one.
     */
    @Test
    fun `the moved rows keep their identities and their place in the sync queue`() = runTest {
        seedWholeProfile(local.id)
        // Deliberately not the value the seeder leaves behind. A row that was already `PENDING` would still read
        // `PENDING` after a move that wrote straight over it, and the assertion would be about a default rather
        // than about the move. `DELETING` is also the state that matters most: it is the one a reader cannot
        // re-create by hand.
        fixture.taskDao.setSyncState("task-1", "DELETING")
        val identities = profileTables.associateWith { identitiesOf(it, local.id) }
        val queued = profileTables.filter { hasColumn(it, "sync_state") }
        val queue = queued.associateWith { queueOf(it, local.id) }
        assertTrue(
            "no profile table was found to carry a sync_state column, which means this test's queue loop " +
                "proves nothing. The column was renamed or the table list is wrong: $profileTables",
            queued.isNotEmpty()
        )
        println("AccountAttach queue: ${queued.joinToString()}")

        val account = signInAsGoogleAccount()
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        identities.forEach { (table, before) ->
            val after = identitiesOf(table, account.id)
            val namedBefore = before.filterNot { it.isBlank() }
            // The identities that were on the profile and are on the account — read the other way round, the
            // comparison would hide a row that arrived twice and a row that never arrived.
            val carried = after.filter { it.isNotBlank() && it in namedBefore }
            assertEquals(
                "every row of $table that arrived with an identity keeps exactly that one, once: a uuid a " +
                    "device wrote is how every other device addresses the same record, and a second one is a " +
                    "second record",
                namedBefore, carried
            )
            assertEquals(
                "and no row of $table arrives without one. A blank uuid is minted on the way in rather than " +
                    "moved, because a row nothing can name is a row nothing can update or delete from " +
                    "another device",
                before.size, after.count { it.isNotBlank() }
            )
            assertEquals(
                "the identities of $table on the account are distinct — two rows of one profile answering " +
                    "to one uuid is the collision this handover exists to merge, not to create",
                after.size, after.distinct().size
            )
        }
        queued.forEach { table ->
            assertEquals(
                "$table keeps its place in the queue: this handover moves rows, it does not resolve them. " +
                    "A pending edit that arrives marked as synced is an edit nobody will ever push",
                queue.getValue(table), queueOf(table, account.id)
            )
        }
    }

    /** The rows a profile holds, by whatever the table uses to say which row it is. */
    private fun identitiesOf(table: String, userId: Long): List<String> =
        strings("SELECT ${identityColumn(table)} FROM $table WHERE user_id = $userId").sorted()

    /**
     * `goal_tasks` is the one profile table with no `uuid` — its identity is the pair — and the question is
     * asked of the database rather than answered from the model list, so a migration that gives that table its
     * own identity is noticed here instead of leaving this test comparing the wrong column.
     */
    private fun identityColumn(table: String): String =
        if (hasColumn(table, "uuid")) "uuid" else "goal_uuid || '/' || task_uuid"

    /**
     * The queue positions of [table]'s rows, as a sorted list.
     *
     * Deliberately not paired with the row's identity: the identity is the one thing this handover is allowed to
     * change (a row that arrives with a blank uuid is given one), and pairing the two would make the categories
     * case fail for the reason the test above asserts is correct. The identity assertions carry "which row", and
     * this carries "what state each row was in".
     */
    private fun queueOf(table: String, userId: Long): List<String> =
        strings("SELECT COALESCE(sync_state, 'none') FROM $table WHERE user_id = $userId").sorted()

    /**
     * Whether a table was created with [column], read out of its own `CREATE TABLE`.
     *
     * This is asked of the schema text rather than by running `SELECT column FROM table LIMIT 0` because on the
     * SQLite layer this suite runs on, a missing column answers "Cannot prepare statement, base error code: 1" —
     * a message that names nothing. A probe that cannot tell "no such column" apart from "the database is broken"
     * is worse than no probe: it would have reported `goal_tasks` as a table with no identity to keep.
     */
    private fun hasColumn(table: String, column: String): Boolean = columnNamesOf(table).contains(column)

    /** The first word of each definition in the table's own `CREATE TABLE` — its column names, as SQLite has them. */
    private fun columnNamesOf(table: String): List<String> =
        strings("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'")
            .single()
            .substringAfter('(')
            .substringBeforeLast(')')
            .split(',')
            .map { it.trim().substringBefore(' ').trim('`').trim() }

    @Test
    fun `the database reports no broken reference after the handover`() = runTest {
        seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val broken = mutableListOf<String>()
        fixture.database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            val table = cursor.getColumnIndexOrThrow("table")
            val rowid = cursor.getColumnIndexOrThrow("rowid")
            val parent = cursor.getColumnIndexOrThrow("parent")
            while (cursor.moveToNext()) {
                broken += "${cursor.getString(table)}#${cursor.getLong(rowid)} -> ${cursor.getString(parent)}"
            }
        }
        assertEquals("foreign_key_check after the move: $broken", emptyList<String>(), broken)
    }

    /**
     * Atomicity, measured one table at a time.
     *
     * A trigger that refuses every write to one table is the cheapest honest way to stop the handover at each
     * of its steps: it fails inside the same transaction the real move runs in, on real SQLite, without adding
     * a test-only seam to production code. The claim being tested is the one the reader needs — either every
     * row arrived, or none of them did — and it is asserted for both profiles, because a half-moved profile is
     * exactly the state that costs a person their data.
     */
    @Test
    fun `a failure at any single table leaves both profiles exactly as they were`() = runTest {
        seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()
        val beforeSource = profileTables.associateWith { count(it, local.id) }
        val beforeTarget = profileTables.associateWith { count(it, account.id) }

        for (table in profileTables) {
            val triggers = listOf("UPDATE", "INSERT").map { kind ->
                "attach_fail_${table}_$kind".also { name ->
                    fixture.database.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER $name BEFORE $kind ON $table " +
                            "BEGIN SELECT RAISE(ABORT, 'injected failure at $table'); END"
                    )
                }
            }
            val outcome = runCatching {
                fixture.authRepository.attachLocalDataToAccount(local.id, account)
            }
            triggers.forEach { name ->
                fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $name")
            }

            assertTrue(
                "the injected failure at $table was swallowed: ${outcome.exceptionOrNull()}",
                outcome.isFailure
            )
            // …and that it failed *because of the trigger*, not for some other reason that happens to throw.
            // A refusal from the safety copy, a dispatcher slip, an unrelated constraint: each would make this
            // iteration pass while proving nothing about the transaction's atomicity at that table.
            val report = messagesOf(checkNotNull(outcome.exceptionOrNull()))
            assertTrue(
                "the handover failed at $table for a reason other than the injected trigger, so the iteration " +
                    "proved nothing about atomicity there. Chain: $report",
                report.contains("injected failure at $table")
            )
            val moved = profileTables.filter { count(it, account.id) != beforeTarget.getValue(it) }
            val lost = profileTables.filter { count(it, local.id) != beforeSource.getValue(it) }
            assertEquals("the failed handover moved rows in $table onto the account: $moved", emptyList<String>(), moved)
            assertEquals("the failed handover took rows away from the local profile in $table: $lost", emptyList<String>(), lost)
        }

        // And the same call, with nothing in its way, still completes the move.
        fixture.authRepository.attachLocalDataToAccount(local.id, account)
        assertEquals(
            "after the injected failures the real handover still has to deliver everything",
            profileTables.size, profileTables.count { count(it, account.id) > 0 }
        )
    }

    @Test
    fun `attaching the same profile a second time moves nothing and loses nothing`() = runTest {
        seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()

        fixture.authRepository.attachLocalDataToAccount(local.id, account)
        val once = profileTables.associateWith { count(it, account.id) }
        val totalOnce = profileTables.sumOf { countAll(it) }

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        assertEquals(
            "a second attach duplicated rows", totalOnce, profileTables.sumOf { countAll(it) }
        )
        assertEquals(
            "a second attach changed what the account holds", once,
            profileTables.associateWith { count(it, account.id) }
        )
        assertEquals("and the source is still empty", 0, profileTables.sumOf { count(it, local.id) })
    }

    /**
     * The conflict that is not a uuid conflict.
     *
     * `achievements` is unique on `(user_id, achievement_key)` and `insights` on
     * `(user_id, rule_key, period_from_day, period_to_day)`: two profiles can each legitimately hold the grant
     * or the reading, and moving one onto the other collides with an index rather than with a row identity.
     * The account's own copy stands and the incoming duplicate is dropped — a choice the test names row by row,
     * because "at least one survived" is not a statement anybody can review. What must not happen either way is
     * the reader being left with nothing, or a row stranded on the profile that was moved away.
     */
    @Test
    fun `a business key the account already holds does not destroy either side's record`() = runTest {
        seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()
        // The account already earned the same achievement and already raised the same insight.
        fixture.achievementDao.insertOrIgnore(
            Achievement(
                uuid = "award-account", userId = account.id, achievementKey = "tasks-10",
                category = AchievementCategory.VOLUME, title = "Ten done (account)",
                description = "10 tasks completed", unit = AchievementUnit.TASKS, targetValue = 10,
                progressValue = 11, earnedDay = 19_990, earnedAt = 1_000
            )
        )
        fixture.insightDao.insert(
            Insight(
                uuid = "insight-account", userId = account.id, ruleKey = "thursday-heavy",
                kind = InsightKind.PATTERN, title = "Thursdays are heavy (account)",
                body = "An earlier pass said it first.", basis = "9 finished tasks",
                periodFromDay = 19_900, periodToDay = 20_000, sampleSize = 9, confidence = 2,
                firstSeenAt = 500, lastSeenAt = 900
            )
        )

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        // Which side stands, named — not "at least one row". The account's own copy survives because an
        // achievement and an insight are *derived* records: `AchievementRepository` and `InsightRepository`
        // regenerate them from the reader's data on the next pass, so the dropped incoming row is a reading the
        // app can arrive at again, not a note somebody typed. A derived fact stated twice is one fact, and this is
        // the only place outside a uuid twin where the handover deletes a row — which is why the verified safety
        // copy is taken before it, and why the count that survives is asserted rather than assumed.
        val grants = uuidsOf("achievements", account.id)
        assertEquals(
            "the account's own grant is the one that stands", listOf("award-account"), grants
        )
        assertEquals("the grant the account already had is still there", 1, grants.size)
        val readings = uuidsOf("insights", account.id)
        assertEquals(
            "and so is the account's own reading of the same rule over the same window",
            listOf("insight-account"), readings
        )
        assertTrue(
            "the merge may drop a duplicate, but it may not leave the reader with nothing: the account holds " +
                "${grants.size} achievement row(s) and ${readings.size} insight row(s) after the move",
            grants.isNotEmpty() && readings.isNotEmpty()
        )
        assertEquals(
            "and nothing is stranded on the profile that was moved away", 0,
            count("achievements", local.id) + count("insights", local.id)
        )
    }

    /**
     * A timer that is still counting is the one thing the handover will not move.
     *
     * Two shapes of "still running" live in this schema, and they are not the same column:
     * `tracking_sessions.running_marker` carries the **owner's id** (`WorkTrackingStateMachine.kt:165`), while
     * `work_sessions.open_marker` carries the **interval's own uuid** (`WorkTrackingRepository.kt:520`). In both
     * cases the row is a number on somebody's screen, and moving it would leave the state machine reading one
     * profile's clock while the row answers to another's — a stopped timer the reader never stopped, or a running
     * one no screen can find. Nothing is written, and no safety copy is taken either: the refusal comes first,
     * so a reader who stops the timer and asks again finds the same database they left.
     */
    @Test
    fun `a clock still running on either table stops the handover before anything is written`() = runTest {
        val account = signInAsGoogleAccount()
        val rows = seedWholeProfile(local.id)
        val taskRow = fixture.taskDao.getAllRowsOnce(local.id).single().id
        fixture.switchTo(local)
        fixture.trackingSessionDao.insert(
            TrackingSession(
                uuid = "track-live", userId = local.id, taskId = taskRow, taskUuid = rows.taskUuid,
                taskTitle = "Pay rent", state = TrackingState.RUNNING, startedAt = 3_000,
                runningMarker = local.id.toString()
            )
        )
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = "interval-live", userId = local.id, trackingSessionUuid = "track-live", taskId = taskRow,
                taskUuid = rows.taskUuid, startedAt = 3_000, openMarker = "interval-live",
                localDay = 20_000, utcOffsetMinutes = 0
            )
        )
        val beforeSource = profileTables.associateWith { count(it, local.id) }
        val beforeTarget = profileTables.associateWith { count(it, account.id) }

        fixture.switchTo(account)
        val outcome = runCatching {
            fixture.authRepository.attachLocalDataToAccount(local.id, account)
        }

        val failure = outcome.exceptionOrNull()
        assertTrue(
            "the handover accepted a profile with a running clock: $failure",
            failure is IllegalStateException && (failure.message ?: "").contains("Stop the timer")
        )
        assertEquals(
            "the local profile is exactly as it was", beforeSource,
            profileTables.associateWith { count(it, local.id) }
        )
        assertEquals(
            "the account is exactly as it was", beforeTarget,
            profileTables.associateWith { count(it, account.id) }
        )
        assertEquals(
            "and the two clocks are still standing, unread by anybody who stopped them", 2,
            fixture.trackingSessionDao.getAllForUser(local.id).count { it.runningMarker != null } +
                fixture.workSessionDao.getAllForUser(local.id).count { it.openMarker != null }
        )
        // The refusal precedes the copy: a handover that refused after writing a snapshot would leave a stale
        // claim on the reader's disk every time a timer happened to be running.
        val safety = File(fixture.context.filesDir, "safety").listFiles()?.map { it.name }.orEmpty()
        assertEquals("the refused handover still wrote a safety copy: $safety", emptyList<String>(), safety)
    }

    // ---------------------------------------------------------------- the session

    /**
     * One profile is current, at every moment.
     *
     * This is a regression lock, not a discovery. `linkProfile` used to build the account row with
     * `is_current = true` and left `signInWithGoogle` to move the session afterwards (AuthRepository.kt:93), so
     * between those two writes the database held two current profiles — and `getCurrentUser` is a `LIMIT 1` with
     * no `ORDER BY`, which means the app answered with whichever row SQLite returned. The row is now written
     * `isCurrent = false` (AuthRepository.kt:179) and becomes current only through `setCurrentUser`, which clears
     * the previous holder in the same transaction. Stage 8a was the first code to draw that flag, which is how the
     * window became visible; if the flag ever comes back, this test fails.
     */
    @Test
    fun `linking a google account leaves exactly one current profile`() = runTest {
        seedWholeProfile(local.id)
        assertEquals("the fixture starts with one current profile", 1, currentProfileCount())

        fixture.authRepository.linkProfile(
            uid = "uid-window-${System.nanoTime()}", email = "ivan@gmail.com",
            displayName = "Ivan", photoUrl = null
        )

        assertEquals(
            "a profile is current by two different statements, and the app reads whichever SQLite returns " +
                "first",
            1, currentProfileCount()
        )
    }

    /**
     * Signing out ends the cloud session, not the reader's access to their own work.
     *
     * `SessionRepository.endSession` clears `is_current` on every row *and* removes the
     * `session_last_user_id` repair anchor (SessionRepository.kt:85-90), so after a sign-out the next start-up
     * resolves to nobody: Profile answers `ProfileState.NoUser`, the lists have no owner to read by, and the
     * reader is invited to create a profile again — while every row they own is still in the database. That is
     * data loss from the reader's side of the screen, and it is what this assertion refuses to accept.
     */
    @Test
    fun `signing out leaves the reader on a profile whose data the app can still read`() = runTest {
        val account = signInAsGoogleAccount()
        seedWholeProfile(account.id)

        fixture.authRepository.signOut()

        val stillCurrent = fixture.sessionRepository.currentUserId()
        assertNotNull(
            "after a sign-out the session resolves to nobody, so the app shows an empty account while every " +
                "row is still stored",
            stillCurrent
        )
        assertEquals("and it is the profile that holds the reader's work", account.id, stillCurrent)
        assertEquals(
            "the tasks are reachable through the session the reader was left on", 1,
            fixture.taskDao.getAllRowsOnce(stillCurrent!!).size
        )
        assertEquals("the measured work is too", 1, count("work_sessions", account.id))
    }
    // ---------------------------------------------------------------- the cases a reviewer found

    /**
     * A deleted category is still a row the profile owns.
     *
     * The read this handover makes used to be `getAllCategoriesOnce`, which filters `is_deleted = 0`, while the
     * stranded check counts every row. So a reader who had ever deleted a category could never complete the
     * handover: the tombstone stayed behind, the check failed, and the verified move rolled back — every time,
     * with a message about stranded rows the reader cannot act on. The tombstone also has to *travel*: sync
     * pushes category deletions, and a deletion left on an abandoned profile is a deletion that never reaches the
     * account. Both halves are asserted here — the row arrives, and it arrives still marked as deleted.
     */
    @Test
    fun `a deleted category moves too, and does not lock the handover`() = runTest {
        fixture.addCategory(local.id, "Home")
        val doomed = fixture.categoryDao.insertCategory(
            Category(name = "Old client", color = -1, icon = "ic_tag", userId = local.id)
        )
        fixture.categoryDao.softDelete(doomed, now = System.currentTimeMillis())
        val account = signInAsGoogleAccount()

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val arrived = fixture.categoryDao.getAllRowsOnce(account.id)
        assertEquals(
            "the tombstone arrived with the live rows", listOf("Home", "Old client"),
            arrived.map { it.name }.sorted()
        )
        assertTrue(
            "and it is still a tombstone: a move that re-activated the deletion would bring back a category " +
                "the reader threw away",
            arrived.single { it.name == "Old client" }.isDeleted
        )
        assertEquals("nothing is stranded", 0, count("categories", local.id))
    }

    /**
     * A mark that was taken back is not a duplicate of another mark that was taken back.
     *
     * `habit_checks` is unique on `(habit_uuid, day_marker)`, and `day_marker` is the nullable twin of
     * `localDay`: a mark deleted and made again is two rows, the retired one carrying nothing in that column
     * (Habit.kt:228-235), and SQLite treats two NULLs as distinct, so many retired marks of one habit are legal.
     * A guard that folds NULL into a sentinel collapses them into one key and deletes the reader's mark history.
     */
    @Test
    fun `retired habit marks are not treated as duplicates of each other`() = runTest {
        fixture.habitDao.insertHabit(Habit(uuid = "habit-r", userId = local.id, title = "Run", startDay = 20_000))
        for (day in listOf(19_990L, 19_991L, 19_992L)) {
            fixture.habitDao.insertCheck(
                HabitCheck(
                    uuid = "check-r-$day", habitUuid = "habit-r", userId = local.id, localDay = day,
                    utcOffsetMinutes = 0, dayMarker = day
                )
            )
        }
        fixture.habitDao.retireMark("habit-r", local.id, 19_991L, System.currentTimeMillis())
        fixture.habitDao.retireMark("habit-r", local.id, 19_992L, System.currentTimeMillis())
        assertEquals(
            "two retired marks and one standing", 2,
            fixture.habitDao.getAllChecksForUser(local.id).count { it.dayMarker == null }
        )
        val account = signInAsGoogleAccount()

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        assertEquals(
            "every mark of the habit arrived, retired ones included", 3, count("habit_checks", account.id)
        )
        assertEquals("nothing was left behind", 0, count("habit_checks", local.id))
    }

    /**
     * A topic that has no identity is still a row the reader made, and it still has tasks under it.
     *
     * `task_groups.uuid` is the primary key, so a row whose uuid is blank is one row the database will accept and
     * the tasks that name the blank still resolve to it. The handover used to *give* such a row a fresh uuid — and
     * a new primary key is a new row: the `@Insert(REPLACE)` added the minted one to the account, the blank one
     * stayed owned by the profile being emptied, and the stranded check then refused the whole move, every time,
     * for that reader. The tasks were worse off than before: they still named the blank, which after the mint
     * belonged to no topic at all. Moving the row with the identity it has is the answer that neither invents a key
     * nor loses a record; an unpushable row is the sync stage's problem, and it is on the roadmap.
     */
    @Test
    fun `a topic with no identity arrives with the tasks that name it`() = runTest {
        fixture.groupDao.upsertGroup(TaskGroup(uuid = "", userId = local.id, name = "Untagged work"))
        val categoryId = fixture.categoryDao.insertCategory(
            Category(name = "Home", color = -1, icon = "ic_tag", userId = local.id)
        )
        fixture.taskDao.insertTask(
            Task(
                uuid = "task-blank", title = "Book the courier", description = "", userId = local.id,
                categoryId = categoryId, groupId = ""
            )
        )
        assertEquals(
            "the fixture holds the blank-uuid topic the reader is asking about", listOf(""),
            fixture.groupDao.getAllGroupsOnce(local.id).map { it.uuid }
        )
        val account = signInAsGoogleAccount()

        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val arrived = fixture.taskDao.getAllRowsOnce(account.id).single()
        assertEquals("the task came with its topic unchanged", "", arrived.groupId)
        assertEquals(
            "the topic arrived, with the identity it had", listOf(""),
            fixture.groupDao.getAllGroupsOnce(account.id).map { it.uuid }
        )
        assertEquals(
            "and the profile that was moved away is empty of it", 0, count("task_groups", local.id)
        )
    }

    /**
     * The copy is only a promise while it describes the profile it was taken of.
     *
     * `ProfileSafetyCopy.take` proves a snapshot at one instant; the transaction that acts on it opens later, and
     * anything written in between — a sync pull, a reminder renewal, a screen finishing a save — would be moved
     * by that transaction while sitting in no copy at all. The handover therefore counts the profile again inside
     * the transaction and refuses if the two disagree. Both arms are asserted here through the real call, because
     * a guard that exists but is never reached is the same as no guard: a copy that no longer fits is refused by
     * name, with both numbers in the sentence and nothing written; a copy that still fits is accepted and the
     * move completes.
     */
    @Test
    fun `a handover will not run against a copy that has stopped describing the profile`() = runTest {
        seedWholeProfile(local.id)
        val account = signInAsGoogleAccount()
        // A copy of the profile as it is now. The task added below is the row that this file will not contain,
        // which is the whole scenario: nothing has to be faked, and nothing has to race.
        val proven = fixture.safetyCopy.take(reason = "guard", profileUserId = local.id)
        fixture.addTask(local.id, "Written after the copy")
        fixture.awaitIdle()
        assertEquals("the profile grew by one row after the copy was proved", 2, count("tasks", local.id))

        val failure = runCatching {
            fixture.authRepository.attachLocalDataToAccount(local.id, account) { _, _ -> proven }
        }.exceptionOrNull()
        assertTrue(
            "a profile that grew after its copy was proved must not be moved against that copy: $failure",
            failure is ProfileSafetyCopy.SafetyCopyUnavailable
        )
        val message = requireNotNull(failure?.message)
        assertTrue(
            "the refusal names both counts, so the reader can see what changed: $message",
            message.contains("when the copy was taken") && message.contains("nothing was moved")
        )
        assertEquals("refusing wrote nothing: the profile still holds its two tasks", 2, count("tasks", local.id))
        assertEquals("and the account was given nothing", 0, count("tasks", account.id))

        // The other arm: a copy that still describes the profile is accepted, so the guard is not a wall.
        val outcome = fixture.authRepository.attachLocalDataToAccount(local.id, account) { _, profile ->
            fixture.safetyCopy.take("attach", profile)
        }
        assertTrue(
            "a handover with a true copy must complete, not be refused: $outcome",
            outcome is AuthRepository.AttachOutcome.Moved
        )
        assertEquals(2, count("tasks", account.id))
        assertEquals(0, count("tasks", local.id))
    }

    /**
     * Which row identities two profiles can actually share, read out of the live schema instead of assumed.
     *
     * This is the question every merge rule in `ProfileAttachDao` turns on, and the schema answers it in three
     * different ways:
     *
     *  * `tasks` and `categories` carry **no unique index on `uuid` at all**, so one identity can exist twice —
     *    which is how the same backup imported into two profiles produces two rows with the same uuid. The merge
     *    rules and the re-pointing of the rowid links (`work_sessions.task_id`, `categories.parent_category_id`)
     *    are live code, tested by the two tests above.
     *  * `achievements` and `insights` are unique on a **per-user business key**, so two profiles can each hold
     *    "the same" grant or reading, and the move collides with an index rather than with a row identity. Tested
     *    by `a business key the account already holds does not destroy either side's record`.
     *  * the other eleven tables enforce `uuid` (or the primary key itself) **across the whole table**, so the
     *    second profile cannot write the colliding row in the first place: the insert refuses before a handover is
     *    ever asked to merge it. A test that seeded one would be asserting about a database the app cannot have.
     *
     * The third group is why this test exists, because it decides what the handover is allowed to do. Those tables
     * move with one `UPDATE` each — there is nothing to merge when the colliding row cannot be written. The two
     * exceptions are deliberate and both are stated in `ProfileAttachDao`: a repeated `goal_tasks` pair is one fact
     * twice, so keeping one loses nothing, while two marks of one habit for one day could carry different
     * correction histories — there the plain statement is refused by the index, the transaction rolls back, and the
     * app chooses nothing it cannot justify. This test is what makes that reasoning revisable: change the
     * uniqueness below and it fails, naming the rules that have just become live and need decisions of their own.
     */
    @Test
    fun `the schema says which row identities two profiles can share`() = runTest {
        val tableWide = listOf(
            "habits", "goals", "habit_checks", "work_sessions", "insights", "achievements",
            "task_groups", "recurrence_rules", "reminders", "tracking_sessions",
            "work_interval_corrections", "goal_value_changes", "habit_check_changes"
        )
        val cannotCollide = tableWide.filterNot { identityIsTableWide(it) }
        assertEquals(
            "these tables no longer enforce uuid across the whole table, so two profiles can hold one identity — " +
                "the handover's duplicate rules for them have become live code and need their own tests: " +
                cannotCollide.joinToString { "$it (unique keys: ${uniqueKeysOf(it)})" },
            emptyList<String>(), cannotCollide
        )

        for (table in listOf("tasks", "categories")) {
            assertTrue(
                "$table is the reason the merge exists: it must stay possible for two profiles to hold one uuid " +
                    "(unique keys: ${uniqueKeysOf(table)})",
                uniqueKeysOf(table).none { it == setOf("uuid") } && primaryKeyOf(table) != setOf("uuid")
            )
        }

        assertTrue(
            "achievements stays unique per user, so a grant two profiles each earned really does collide",
            uniqueKeysOf("achievements").contains(setOf("user_id", "achievement_key"))
        )
        assertTrue(
            "and so does a reading of the same rule over the same window",
            uniqueKeysOf("insights").contains(setOf("user_id", "rule_key", "period_from_day", "period_to_day"))
        )

        // The slots, asserted one by one rather than inferred from the merge rules that rely on them. Each of
        // these answers a different question when two profiles arrive with the same pair in it: a table-wide slot
        // cannot be filled twice by anybody, so nothing has to merge it; a per-profile slot can, and that is where
        // the handover's dedup statements live. A migration that moves one of these from table-wide to per-profile
        // turns a plain `UPDATE` into a merge with a decision in it, and this is the assertion that says so.
        val slots = mapOf(
            "reminders" to setOf("task_uuid", "offset_minutes"),
            "tasks" to setOf("series_uuid", "occurrence_at"),
            "tracking_sessions" to setOf("running_marker"),
            "habit_checks" to setOf("habit_uuid", "day_marker"),
            "work_sessions" to setOf("user_id", "open_marker"),
            "insights" to setOf("user_id", "rule_key", "period_from_day", "period_to_day"),
            "achievements" to setOf("user_id", "achievement_key")
        )
        for ((table, columns) in slots) {
            assertTrue(
                "$table no longer has a unique index on $columns (it has ${uniqueKeysOf(table)}), so the rule " +
                    "this class documents for it has to be re-derived",
                uniqueKeysOf(table).contains(columns)
            )
        }
        assertEquals(
            "a goal's task set is keyed by the pair itself, which is why a repeated pair is one fact twice",
            setOf("goal_uuid", "task_uuid"), primaryKeyOf("goal_tasks")
        )
    }

    /**
     * Measured hours are joined to their task by the local row id, so merging a task has to move the hours.
     *
     * `WorkStatisticsDao` groups by `work_sessions.task_id` and joins `tasks.id` (WorkStatisticsDao.kt:113-141),
     * and when two profiles hold the same task uuid the handover deletes one of the two rows. The intervals that
     * pointed at the deleted rowid then join to nothing: the reader's history keeps the row and loses the name of
     * what it was work on.
     */
    @Test
    fun `a merged task takes its measured intervals to the row that survives`() = runTest {
        val shared = "00000000-0000-0000-0000-0000000000e1"
        val account = signInAsGoogleAccount()
        val accountTask = fixture.taskDao.insertTask(
            Task(
                uuid = shared, title = "Pay rent (account)", description = "", userId = account.id,
                updatedAt = 5_000
            )
        )
        fixture.switchTo(local)
        val localTask = fixture.taskDao.insertTask(
            Task(
                uuid = shared, title = "Pay rent (local)", description = "", userId = local.id,
                updatedAt = 1_000
            )
        )
        fixture.trackingSessionDao.insert(
            TrackingSession(
                uuid = "track-m", userId = local.id, taskId = localTask, taskUuid = shared,
                taskTitle = "Pay rent (local)", state = TrackingState.COMPLETED, startedAt = 1_000,
                stoppedAt = 2_000
            )
        )
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = "interval-m", userId = local.id, trackingSessionUuid = "track-m", taskId = localTask,
                taskUuid = shared, startedAt = 1_000, endedAt = 2_000, durationSeconds = 60,
                localDay = 20_000, utcOffsetMinutes = 0
            )
        )

        fixture.switchTo(account)
        val outcome = fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val intervals = fixture.workSessionDao.getAllForUser(account.id)
        assertEquals("the measured interval arrived", 1, intervals.size)
        assertEquals(
            "the report counts the rewiring separately from the arriving, so a reader can tell that two links " +
                "changed their target without that being read as two more records",
            2, (outcome as AuthRepository.AttachOutcome.Moved).report.repointedLinks
        )
        assertEquals(
            "and it names the task row that survived the merge ($accountTask), not the one that was deleted " +
                "($localTask): ${intervals.single().taskId}",
            accountTask, intervals.single().taskId
        )
        assertEquals(
            "the tracking session is re-pointed the same way", accountTask,
            fixture.trackingSessionDao.getAllForUser(account.id).single().taskId
        )
    }

    /**
     * The other arm of the same merge, and the one that can quietly destroy work.
     *
     * `attachTasks` keeps the newer of the two rows that share a uuid. The test above covers the case where that
     * is the account's own copy; here the *incoming* row is newer, so the row the account held is the one
     * deleted. What has to hold in that direction is that the reader's later edit is what stands — the title,
     * the note and the timestamp of the arriving row, under the id of the arriving row — and that the hours
     * measured against it still name a task the statistics join can find. A merge that kept the older copy here
     * would report a successful move and hand the reader back an edit they had already made.
     */
    @Test
    fun `a task newer than the account's own copy is the one that survives`() = runTest {
        val shared = "00000000-0000-0000-0000-0000000000e2"
        val account = signInAsGoogleAccount()
        val accountTask = fixture.taskDao.insertTask(
            Task(
                uuid = shared, title = "Pay rent", description = "the note as it was first written",
                userId = account.id, updatedAt = 1_000
            )
        )
        fixture.switchTo(local)
        val localTask = fixture.taskDao.insertTask(
            Task(
                uuid = shared, title = "Pay the rent to the landlord",
                description = "rewritten on the local profile, afterwards", userId = local.id,
                updatedAt = 9_000
            )
        )
        fixture.trackingSessionDao.insert(
            TrackingSession(
                uuid = "track-n", userId = local.id, taskId = localTask, taskUuid = shared,
                taskTitle = "Pay the rent to the landlord", state = TrackingState.COMPLETED,
                startedAt = 1_000, stoppedAt = 2_000
            )
        )
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = "interval-n", userId = local.id, trackingSessionUuid = "track-n", taskId = localTask,
                taskUuid = shared, startedAt = 1_000, endedAt = 2_000, durationSeconds = 60,
                localDay = 20_000, utcOffsetMinutes = 0
            )
        )

        fixture.switchTo(account)
        val outcome = fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val arrived = fixture.taskDao.getAllRowsOnce(account.id)
        assertEquals("one task with that identity, not two", 1, countAll("tasks"))
        assertEquals(
            "and it is the newer row: the title, the note and the edit time the reader last wrote",
            listOf("Pay the rent to the landlord" to 9_000L),
            arrived.map { it.title to it.updatedAt }
        )
        assertEquals(
            "the survivor is the arriving row ($localTask); the account's older copy ($accountTask) is the row " +
                "that merged away",
            localTask, arrived.single().id
        )
        assertEquals(
            "the measured interval still names the task that survived ($localTask), rather than the row that " +
                "merged away ($accountTask)",
            listOf(localTask), fixture.workSessionDao.getAllForUser(account.id).map { it.taskId }
        )
        assertEquals(
            "and so does the session it was measured inside",
            listOf(localTask), fixture.trackingSessionDao.getAllForUser(account.id).map { it.taskId }
        )
        assertEquals(
            "and the report says one duplicate merged away, not two records moved",
            1, (outcome as AuthRepository.AttachOutcome.Moved).report.mergedDuplicates
        )
    }

    /**
     * A category that merged away must not orphan the categories that hung under it.
     *
     * `categories.parent_category_id` is `ON DELETE SET NULL`, and the handover deletes the local copy of a
     * category whose uuid the account already holds. The children of that copy — already moved, already the
     * account's — then point at nothing, and the tree the reader built flattens.
     */
    @Test
    fun `a merged category keeps its children attached to the category that survives`() = runTest {
        val shared = "00000000-0000-0000-0000-0000000000f1"
        val account = signInAsGoogleAccount()
        val accountParent = fixture.categoryDao.insertCategory(
            Category(uuid = shared, name = "Home (account)", color = -1, icon = "ic_tag", userId = account.id)
        )
        fixture.switchTo(local)
        val localParent = fixture.categoryDao.insertCategory(
            Category(uuid = shared, name = "Home (local)", color = -1, icon = "ic_tag", userId = local.id)
        )
        val child = fixture.categoryDao.insertCategory(
            Category(
                uuid = "child-cat", name = "Garage", color = -1, icon = "ic_tag", userId = local.id,
                parentCategoryId = localParent
            )
        )

        fixture.switchTo(account)
        fixture.authRepository.attachLocalDataToAccount(local.id, account)

        val movedChild = fixture.categoryDao.getCategoryForUser(account.id, child)
            ?: throw AssertionError("the child category did not arrive on the account")
        assertEquals(
            "the child points at the account's own copy of the parent it merged into, not at nothing",
            accountParent, movedChild.parentCategoryId
        )
    }

    /**
     * A sign-out must not be able to leave the app with no profile.
     *
     * `signOut` asks the session who is current. If it answers from the *cache* before `restore()` has run —
     * which is what a cold process looks like — a naive "if there is nobody, end the session" branch clears
     * `is_current` on every row and drops the repair anchor, and the reader is back where Stage 13 started: a
     * full database and an empty app.
     */
    @Test
    fun `signing out with an unresolved session does not clear every profile`() = runTest {
        val account = signInAsGoogleAccount()
        fixture.addTask(account.id, "Still mine")
        // A session object that has not resolved yet: the state a process start leaves behind.
        val cold = fixture.newSessionRepository()

        fixture.authRepository.signOut()

        assertEquals(
            "a sign-out that found no cached session must not have ended the local one",
            account.id, cold.currentUserId()
        )
        assertEquals(
            "the task is reachable through it", listOf("Still mine"),
            fixture.taskDao.getAllRowsOnce(cold.currentUserId()!!).map { it.title }
        )
    }

}
