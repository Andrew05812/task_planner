package com.taskplanner.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.auth.AuthRepository
import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalTaskLink
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.model.Habit
import com.taskplanner.data.model.HabitCheck
import com.taskplanner.data.model.HabitCheckChange
import com.taskplanner.data.model.Insight
import com.taskplanner.data.model.InsightKind
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TrackingSession
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkIntervalCorrection
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.repository.ProfileCreation
import com.taskplanner.data.repository.UserRepository
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a colliding e-mail is allowed to do to a profile that already owns it.
 *
 * `users.email` carries a unique index and the column is `NOCASE`. Thirteen tables — topics, recurrence
 * series, goals with their links and value history, habits with their checks and check history, tracking and
 * work sessions, interval corrections, insights, achievements — declare that link `ON DELETE CASCADE`. Three
 * more (`tasks`, `categories`, `reminders`) carry a bare `user_id` with no foreign key at all, so a vanished
 * owner does not delete them: it leaves them belonging to nobody, and because the primary key is
 * `autoGenerate` rather than `@AutoIncrement`, the freed row id can be handed to the next profile, which then
 * inherits those orphaned rows as its own.
 *
 * So an insert of a *second* profile must never be a delete of the *first* one. SQLite resolves
 * `INSERT OR REPLACE` on a unique-index conflict by deleting the conflicting row first — which is what the
 * reproduction below measures, table by table, against the real engine Room runs on.
 *
 * Counts are read with plain SQL rather than through the feature DAOs on purpose: the question is whether
 * rows are still in tables, and a query that filters by "live" or "not archived" would answer a different
 * question than the one asked here.
 */
@RunWith(RobolectricTestRunner::class)
class UserIdentityConflictTest {

    private lateinit var fixture: AppFixture
    private lateinit var users: UserRepository

    @Before
    fun setUp() {
        fixture = AppFixture(ApplicationProvider.getApplicationContext())
        users = UserRepository(fixture.userDao)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    // -- the reproduction ----------------------------------------------------------------------------

    /**
     * §4's scenario in one test: profile A owns a row in every table that hangs off a user; profile B is
     * inserted with A's address and a different primary key; A and everything A owns must still be there.
     *
     * The assertion is about the data rather than about a return value because the damage used to be invisible
     * to the caller: `INSERT OR REPLACE` answered with the id of the row it wrote and said nothing about the
     * row — and the thirteen tables' worth of rows behind it — that it removed on the way.
     *
     * The refusal itself is asserted too, and only as the shape of safety: the statement that hits the index
     * is expected to fail, not to succeed by deleting what was in front of it.
     */
    @Test
    fun `a colliding e-mail leaves the existing profile and every row it owns intact`() {
        val owner = runBlocking { profileWithOneRowInEveryTable("anna@mail.ru", "Анна") }
        val before = runBlocking { rowsOf(owner) }
        assertTrue(
            "the seed has to own a row in every table or the test proves nothing: $before",
            before.values.all { it > 0 }
        )

        assertCollidingInsertIsRefused("anna@mail.ru")

        val survivor = runBlocking { fixture.userDao.getUserById(owner) }
        val after = runBlocking { rowsOf(owner) }
        assertNotNull("the profile that already had this e-mail is still there", survivor)
        assertEquals("and it is still the same profile, not a replacement", owner, survivor?.id)
        assertEquals("its name was not overwritten by a stranger's", "Анна", survivor?.name)
        assertEquals("nothing that belonged to it was taken with it", before, after)
        assertEquals(
            "and the address still names exactly one profile",
            1, runBlocking { profilesOwning("anna@mail.ru") }
        )
    }

    /**
     * The same collision spelled differently.
     *
     * The column is `NOCASE`, so `ANNA@MAIL.RU` is not a second address that happens to look like the first:
     * it is the same unique key as far as SQLite is concerned, and it must meet the same refusal.
     */
    @Test
    fun `a colliding e-mail of a different case is the same collision`() {
        val owner = runBlocking { profileWithOneRowInEveryTable("anna@mail.ru", "Анна") }
        val before = runBlocking { rowsOf(owner) }

        assertCollidingInsertIsRefused("ANNA@MAIL.RU")

        assertNotNull(
            "the profile is still the one that was there. rows afterwards: ${runBlocking { rowsOf(owner) }}",
            runBlocking { fixture.userDao.getUserById(owner) }
        )
        assertEquals("and its data is intact", before, runBlocking { rowsOf(owner) })
    }

    /**
     * A safe answer to a conflict must not turn into a refusal to register anybody.
     *
     * The first test in this file is only worth writing if this one keeps passing afterwards.
     */
    @Test
    fun `a second profile with an address of its own is still created`() {
        val first = runBlocking { profileWithOneRowInEveryTable("anna@mail.ru", "Анна") }
        val before = runBlocking { rowsOf(first) }

        val second = runBlocking {
            fixture.userDao.insertUser(User(email = "boris@mail.ru", name = "Борис"))
        }

        assertTrue("a new address yields a new profile", second > 0L)
        assertTrue("and it is a different row", second != first)
        assertEquals("Борис", runBlocking { fixture.userDao.getUserById(second) }?.name)
        assertEquals("the first profile kept everything it had", before, runBlocking { rowsOf(first) })
    }

    /**
     * The collision refused at the DAO is the collision the repository turns into an answer.
     *
     * Checked twice on purpose, at two layers: the index is what protects the data, and the repository is what
     * stops an exception from becoming the product's behaviour. A caller that only ever saw `Created` would
     * hand a profile id of `-1` to the session and put a reader into a profile that does not exist.
     */
    @Test
    fun `the repository answers a taken address instead of throwing it`() {
        val owner = runBlocking { profileWithOneRowInEveryTable("anna@mail.ru", "Анна") }
        val before = runBlocking { rowsOf(owner) }

        val creation = runBlocking {
            users.createUser(User(email = "anna@mail.ru", name = "Someone else"))
        }

        assertEquals("the caller is told the address is taken", ProfileCreation.EmailTaken, creation)
        assertNotNull("and the profile that owns it is untouched", runBlocking {
            fixture.userDao.getUserById(owner)
        })
        assertEquals("with all of its rows", before, runBlocking { rowsOf(owner) })

        val fresh = runBlocking {
            users.createUser(User(email = "boris@mail.ru", name = "Борис"))
        }
        assertTrue("a free address still creates", fresh is ProfileCreation.Created)
        assertTrue(
            "and the created profile is the one the caller was handed",
            runBlocking { fixture.userDao.getUserById((fresh as ProfileCreation.Created).user.id) } != null
        )
    }

    /**
     * Two requests for the same address, at once.
     *
     * A check-then-insert pair lets both through; the index lets exactly one be written. The room runs its
     * queries on one executor, so this exercises the ordering rather than pretending to a real race — what it
     * proves is that whichever request lands second, the first one's profile and rows are the survivors.
     */
    @Test
    fun `two requests for one address leave one profile and no damage`() {
        val first = runBlocking {
            users.createUser(User(email = "anna@mail.ru", name = "Первый"))
        }
        val second = runBlocking {
            users.createUser(User(email = "anna@mail.ru", name = "Второй"))
        }

        assertTrue("the first got a profile", first is ProfileCreation.Created)
        assertEquals("the second got an answer about the address", ProfileCreation.EmailTaken, second)
        val owners = runBlocking { profilesOwning("anna@mail.ru") }
        assertEquals("and exactly one profile owns it", 1, owners)
        assertEquals(
            "it is the first one, with the first one's name",
            "Первый",
            runBlocking { fixture.userDao.getUserById((first as ProfileCreation.Created).user.id) }?.name
        )
    }

    /**
     * The path that used to destroy a profile without anybody typing anything wrong.
     *
     * A local profile owns `anna@mail.ru`. The reader then signs in with the Google account that has that
     * address — a different identity, with a uid this device has never seen. Matching by uid finds nothing, so
     * the code went straight to inserting a profile, and the insert collided with the unique index. What
     * happened next was SQLite doing `INSERT OR REPLACE` honestly: the local profile was deleted, its goals,
     * habits, sessions, insights and achievements followed it by cascade, and its tasks stayed behind owned by
     * nobody.
     *
     * The answer now is a refusal the reader can act on, because joining two identities is their decision —
     * and the app already asks for it explicitly, one screen away.
     */
    @Test
    fun `a google account over a local profile of the same address refuses instead of replacing it`() {
        val owner = runBlocking { profileWithOneRowInEveryTable("anna@mail.ru", "Анна") }
        val before = runBlocking { rowsOf(owner) }

        val outcome = runBlocking {
            fixture.authRepository.linkProfile(
                uid = "google-uid-1", email = "anna@mail.ru", displayName = "Анна К.", photoUrl = null
            )
        }

        assertTrue(
            "the sign-in says the address belongs to another profile: $outcome",
            outcome is AuthRepository.ProfileUpsert.AddressTaken
        )
        assertNotNull(
            "and the profile that had it is still there",
            runBlocking { fixture.userDao.getUserById(owner) }
        )
        assertEquals("with every row it owned", before, runBlocking { rowsOf(owner) })
        assertNull(
            "nothing was written under the google uid",
            runBlocking { fixture.userDao.getUserByFirebaseUid("google-uid-1") }
        )
    }

    @Test
    fun `the same google account signing in twice produces one profile`() {
        val first = runBlocking {
            fixture.authRepository.linkProfile("google-uid-2", "boris@mail.ru", "Борис", null)
        }
        val second = runBlocking {
            fixture.authRepository.linkProfile("google-uid-2", "boris@mail.ru", "Борис К.", null)
        }

        assertTrue("the first sign-in created a profile", first is AuthRepository.ProfileUpsert.Linked)
        assertTrue("the second linked to it rather than colliding with it",
            second is AuthRepository.ProfileUpsert.Linked)
        assertEquals(
            "and it is the same profile both times",
            (first as AuthRepository.ProfileUpsert.Linked).user.id,
            (second as AuthRepository.ProfileUpsert.Linked).user.id
        )
        assertEquals("one row carries that uid", 1, runBlocking {
            fixture.userDao.getAllUsersOnce().count { it.firebaseUid == "google-uid-2" }
        })
        assertEquals(
            "the display name followed the account, not a new row",
            "Борис К.",
            runBlocking { fixture.userDao.getUserByFirebaseUid("google-uid-2") }?.name
        )
    }

    @Test
    fun `google accounts with no public address do not collide with each other`() {
        val one = runBlocking { fixture.authRepository.linkProfile("uid-a", null, "Аноним", null) }
        val two = runBlocking { fixture.authRepository.linkProfile("uid-b", "", "Аноним", null) }

        assertTrue("the first got a profile", one is AuthRepository.ProfileUpsert.Linked)
        assertTrue("so did the second, on its own placeholder", two is AuthRepository.ProfileUpsert.Linked)
        assertTrue(
            "and they are two different rows",
            (one as AuthRepository.ProfileUpsert.Linked).user.id !=
                (two as AuthRepository.ProfileUpsert.Linked).user.id
        )
    }

    // -- the steps -----------------------------------------------------------------------------------

    /**
     * Attempts to insert a second profile under an address that is already taken, and insists that the
     * refusal is a constraint failure.
     *
     * Any other outcome is the defect: a statement that *succeeds* here has succeeded by removing the row that
     * was in its way.
     */
    private fun assertCollidingInsertIsRefused(email: String) {
        val attempt = runCatching {
            runBlocking { fixture.userDao.insertUser(User(email = email, name = "Someone else")) }
        }
        val failure = attempt.exceptionOrNull()
        assertTrue(
            "a colliding insert must be refused by the unique index rather than resolved by deleting the " +
                "profile it collided with. it answered with: $attempt",
            failure is SQLiteConstraintException
        )
    }

    // -- the seed ------------------------------------------------------------------------------------

    /**
     * One profile with one row in every table that hangs off it: the thirteen that cascade and the three that
     * carry a bare `user_id`. A test that seeded only tasks would watch the cascading tables be emptied and
     * still call the run a pass.
     */
    private suspend fun profileWithOneRowInEveryTable(email: String, name: String): Long {
        val users = fixture.userDao
        val id = users.insertUser(User(email = email, name = name))
        check(id > 0L) { "the seed profile was not inserted" }

        val category = fixture.categoryDao.insertCategory(
            Category(name = "Диссертация", color = -0xB0C4, icon = "ic_tag", userId = id)
        )
        val task = fixture.taskDao.insertTask(
            Task(title = "Глава 1", description = "", userId = id, categoryId = category)
        )
        fixture.reminderDao.insertOrIgnore(
            Reminder(uuid = "reminder-1", taskUuid = "task-1", userId = id, triggerAt = 1L, anchorAt = 1L)
        )
        fixture.groupDao.upsertGroup(TaskGroup(uuid = "group-1", userId = id, name = "Тема"))
        fixture.recurrenceDao.upsertRule(
            RecurrenceRule(
                uuid = "rule-1", userId = id, unit = RepeatUnit.DAY, interval = 1,
                weekDays = "", dayOfMonth = 0, anchorAt = 1L, zoneId = "UTC", cursorAt = 1L
            )
        )
        fixture.goalDao.insertGoal(
            Goal(uuid = "goal-1", userId = id, title = "Защититься", targetValue = 10L)
        )
        fixture.goalDao.insertLink(
            GoalTaskLink(goalUuid = "goal-1", taskUuid = "task-1", userId = id)
        )
        fixture.goalDao.insertValueChange(
            GoalValueChange(
                uuid = "goal-change-1", goalUuid = "goal-1", userId = id,
                field = "target", oldValue = "1", newValue = "10"
            )
        )
        fixture.habitDao.insertHabit(
            Habit(uuid = "habit-1", userId = id, title = "Писать каждый день", startDay = 1L)
        )
        fixture.habitDao.insertCheck(
            HabitCheck(
                uuid = "check-1", habitUuid = "habit-1", userId = id,
                localDay = 1L, utcOffsetMinutes = 0
            )
        )
        fixture.habitDao.insertCheckChange(
            HabitCheckChange(
                uuid = "check-change-1", checkUuid = "check-1", habitUuid = "habit-1",
                userId = id, oldLocalDay = 1L, newLocalDay = 2L
            )
        )
        fixture.trackingSessionDao.insert(
            TrackingSession(
                uuid = "tracking-1", userId = id, taskId = task, taskUuid = "task-1",
                taskTitle = "Глава 1", startedAt = 1_000L
            )
        )
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = "interval-1", userId = id, trackingSessionUuid = "tracking-1",
                taskId = task, taskUuid = "task-1", startedAt = 1_000L,
                localDay = 1L, utcOffsetMinutes = 0
            )
        )
        fixture.workSessionDao.insertCorrection(
            WorkIntervalCorrection(
                uuid = "correction-1", intervalUuid = "interval-1", userId = id,
                oldStartedAt = 1_000L, newStartedAt = 2_000L
            )
        )
        fixture.insightDao.insert(
            Insight(
                uuid = "insight-1", userId = id, ruleKey = "load", kind = InsightKind.FACT,
                title = "Нагрузка", body = "Текст", basis = "10 days measured",
                periodFromDay = 1L, periodToDay = 10L, sampleSize = 10, confidence = 90,
                firstSeenAt = 1L, lastSeenAt = 1L
            )
        )
        fixture.achievementDao.insertOrIgnore(
            Achievement(
                uuid = "grant-1", userId = id, achievementKey = "first_task", ruleVersion = 1,
                category = AchievementCategory.BEGINNING, title = "Первая задача",
                description = "Одна закончена", unit = AchievementUnit.TASKS,
                targetValue = 1L, progressValue = 1L, earnedDay = 1L, earnedAt = 1L
            )
        )
        return id
    }

    /** How many profiles claim this address. The index says one; the point of asking is to see it. */
    private suspend fun profilesOwning(email: String): Int =
        fixture.userDao.getAllUsersOnce().count { it.email.equals(email, ignoreCase = true) }

    /** How many rows each owned table still holds for this profile. */
    private suspend fun rowsOf(userId: Long): Map<String, Int> {
        val database = fixture.database.openHelper.writableDatabase
        return OWNED_TABLES.associate { (table, column) ->
            table to database.query("SELECT COUNT(*) FROM $table WHERE $column = ?", arrayOf(userId)).use {
                if (it.moveToFirst()) it.getInt(0) else -1
            }
        }
    }

    private companion object {

        /** Every table that names its owner, cascading ones and bare `user_id` ones alike. */
        val OWNED_TABLES = listOf(
            "tasks" to "user_id",
            "categories" to "user_id",
            "reminders" to "user_id",
            "task_groups" to "user_id",
            "recurrence_rules" to "user_id",
            "goals" to "user_id",
            "goal_tasks" to "user_id",
            "goal_value_changes" to "user_id",
            "habits" to "user_id",
            "habit_checks" to "user_id",
            "habit_check_changes" to "user_id",
            "tracking_sessions" to "user_id",
            "work_sessions" to "user_id",
            "work_interval_corrections" to "user_id",
            "insights" to "user_id",
            "achievements" to "user_id"
        )
    }
}
