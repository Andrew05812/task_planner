package com.taskplanner.testing

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import com.taskplanner.data.auth.AuthRepository
import com.taskplanner.data.backup.BackupRepository
import com.taskplanner.data.calendar.CalendarRepository
import com.taskplanner.data.habit.HabitReminderCoordinator
import com.taskplanner.data.local.AchievementDao
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GoalDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.HabitDao
import com.taskplanner.data.local.InsightDao
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.TaskStatisticsDao
import com.taskplanner.data.local.TrackingSessionDao
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.local.WorkSessionDao
import com.taskplanner.data.local.WorkStatisticsDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.User
import com.taskplanner.data.reminder.ReminderNotifier
import com.taskplanner.data.reminder.ReminderScheduler
import com.taskplanner.data.repository.CategoryRepository
import com.taskplanner.data.repository.DashboardRepository
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.taskplanner.data.repository.GoalRepository
import com.taskplanner.data.repository.GroupRepository
import com.taskplanner.data.repository.HabitRepository
import com.taskplanner.data.repository.InsightRepository
import com.taskplanner.data.repository.RecurrenceRepository
import com.taskplanner.data.repository.ReminderRepository
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.data.repository.WorkloadRepository
import com.taskplanner.data.repository.WorkStatisticsRepository
import com.taskplanner.data.repository.WorkTrackingRepository
import com.taskplanner.data.tracking.MonotonicReference
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.data.sync.SyncAppliers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.robolectric.Shadows.shadowOf

/** How long the database threads must stay quiet before a command counts as finished. */
private const val QUIET_MILLIS = 20L

/**
 * One database worker, of the shape production has, with the queue left visible: `newSingleThreadExecutor`
 * hides it behind a wrapper that cannot say whether it still holds work, and a test has to be able to ask.
 */
private class FixtureExecutor(name: String) : ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }
) {
    val isIdle: Boolean get() = activeCount == 0 && queue.isEmpty()
}

/**
 * Real objects, no mocked persistence: an in-memory Room database, the production DAOs, the
 * production repositories and a real SharedPreferences file, so repository, backup and sync tests
 * execute the same code paths as the app.
 */
class AppFixture(private val context: Context) : AutoCloseable {

    // Room answers a query from the thread its Flow collector was resumed on, and it refuses that when
    // the thread is inside a suspending transaction belonging to another coroutine context: committing
    // a transaction dispatches the invalidation, which resumes the collector, which queries — all on the
    // committing thread, which Room then correctly rejects. Production avoids it by having queries and
    // transactions on their own threads, so the harness keeps that shape and leaves the test thread free
    // to pump the looper instead.
    private val queryExecutor = FixtureExecutor("fixture-query-thread")
    private val transactionExecutor = FixtureExecutor("fixture-transaction-thread")

    val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(queryExecutor)
        .setTransactionExecutor(transactionExecutor)
        .build()

    val taskDao: TaskDao = database.taskDao()
    val categoryDao: CategoryDao = database.categoryDao()
    val groupDao: GroupDao = database.groupDao()
    val recurrenceDao: RecurrenceDao = database.recurrenceDao()
    val reminderDao: ReminderDao = database.reminderDao()
    val userDao: UserDao = database.userDao()

    val prefs: SharedPreferences =
        context.getSharedPreferences("fixture-prefs", Context.MODE_PRIVATE)

    /**
     * Runs the session's start-up reload on the thread that builds the fixture instead of on a pool
     * thread: Robolectric keeps SQLite connection handles per thread, so a background query that
     * outlives its test turns into a failure reported against whichever test runs next.
     */
    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    val sessionRepository = SessionRepository(userDao, prefs, sessionScope)

    /** A session that has not seen this storage yet: what an app restart creates. */
    fun newSessionRepository(): SessionRepository = SessionRepository(userDao, prefs, sessionScope)

    /**
     * The tracker's clocks under the test's control. A duration is only reproducible if the instant it
     * was measured from is, and a clock change is one of the cases the feature has to answer for, so
     * the fixture hands the repository a fake rather than the device clock.
     */
    val timeBase = FakeTimeBase(wall = System.currentTimeMillis(), monotonic = 0L)

    /**
     * The real alarm and notification gateways, on Robolectric's shadows of them: reminders are
     * armed and posted for real in these tests, because "the row exists but nothing was scheduled"
     * is exactly the bug a fake would hide. Assertions read them through
     * `shadowOf(alarmManager)` and `shadowOf(notificationManager)`.
     */
    val reminderScheduler = ReminderScheduler(context)
    val reminderNotifier = ReminderNotifier(context)

    /**
     * A habit's reminder is a rule rather than a row, so it borrows the task reminders' scheduler and
     * notification bar and derives its own next owed minute from the habit row. Built before the reminder
     * repository because rebuilding the alarms rebuilds both kinds together.
     */
    val habitDao: HabitDao = database.habitDao()
    val habitReminders = HabitReminderCoordinator(
        habitDao, sessionRepository, reminderScheduler, reminderNotifier, timeBase
    )

    val reminderRepository = ReminderRepository(
        reminderDao, taskDao, categoryDao, sessionRepository, reminderScheduler, reminderNotifier,
        habitReminders, timeBase
    )

    // Declared before the task repository on purpose: finishing a task is what advances a series.
    val recurrenceRepository =
        RecurrenceRepository(recurrenceDao, taskDao, sessionRepository, reminderRepository)

    /** Shared by the tracker and the statistics reader, the way the process shares one. */
    val monotonicReference = MonotonicReference(timeBase)

    val trackingSessionDao: TrackingSessionDao = database.trackingSessionDao()
    val workSessionDao: WorkSessionDao = database.workSessionDao()
    val workStatisticsDao: WorkStatisticsDao = database.workStatisticsDao()

    /** The task-side aggregates the dashboard reads, straight from the same storage. */
    val taskStatisticsDao: TaskStatisticsDao = database.taskStatisticsDao()

    val workTrackingRepository = WorkTrackingRepository(
        database, trackingSessionDao, workSessionDao, sessionRepository, timeBase, monotonicReference
    )

    /** The same storage seen by a second process: what a restart or a re-sign-in reads. */
    fun newWorkTrackingRepository(): WorkTrackingRepository = WorkTrackingRepository(
        database, trackingSessionDao, workSessionDao, sessionRepository, timeBase,
        MonotonicReference(timeBase)
    )

    /** The reader's re-measurement, on the same clocks as the writer. */
    val workTicker = WorkTicker(timeBase, monotonicReference)

    val workStatisticsRepository = WorkStatisticsRepository(
        workStatisticsDao, trackingSessionDao, workSessionDao, sessionRepository, timeBase,
        monotonicReference, workTicker
    )

    /** The dashboard's aggregates over the same storage and the same clocks. */
    val dashboardRepository = DashboardRepository(
        taskStatisticsDao, workSessionDao, trackingSessionDao, workStatisticsRepository,
        sessionRepository, timeBase
    )

    /** Goals read the same aggregates the dashboard and the history screen write. */
    val goalDao: GoalDao = database.goalDao()
    val goalRepository = GoalRepository(
        database, goalDao, taskDao, workStatisticsDao, sessionRepository, timeBase, workTicker
    )

    /** A habit's figures come only from its own marks, so nothing else is needed to compute them. */
    val habitRepository = HabitRepository(
        database, habitDao, sessionRepository, timeBase, workTicker, habitReminders
    )

    /**
     * The overload read, on the calendar's own window query and the tracker's own daily series.
     *
     * Built before the insight repository because the insights are handed its figures: a heavy day named by a
     * rule and a heavy day shown by the panel have to be the same day, counted from the same rows.
     */
    val workloadRepository = WorkloadRepository(
        taskDao, workStatisticsDao, sessionRepository, workTicker, timeBase
    )

    /**
     * The notebook of readings, wired after the two repositories it reads through.
     *
     * An insight pass takes its figures from the aggregates the dashboard and the goals and habits screens
     * already use, so the fixture hands it the same repositories rather than a second set of queries — the
     * number a reading states has to be the number the screen beside it states.
     */
    val insightDao: InsightDao = database.insightDao()
    val insightRepository = InsightRepository(
        database, insightDao, workloadRepository, taskStatisticsDao, workStatisticsDao, goalDao, habitDao,
        habitRepository, goalRepository, sessionRepository, timeBase, context
    )

    /**
     * The grants the reader earned: the schema v16 table.
     *
     * The dao is exposed rather than wrapped here because the rules that write it are built in the test that
     * needs them — a grant depends on which reads a rule is allowed to make, and the fixture should not decide
     * that for every test.
     */
    val achievementDao: AchievementDao = database.achievementDao()

    val taskRepository = TaskRepository(
        taskDao, sessionRepository, recurrenceRepository, reminderRepository, workTrackingRepository
    )
    val categoryRepository = CategoryRepository(categoryDao, sessionRepository)
    val groupRepository = GroupRepository(groupDao, sessionRepository)
    val calendarRepository =
        CalendarRepository(taskDao, categoryDao, sessionRepository, reminderRepository)
    val backupRepository = BackupRepository(
        context, database, taskDao, categoryDao, groupDao, recurrenceDao, reminderDao,
        sessionRepository, recurrenceRepository, reminderRepository
    )
    // Firebase is never contacted in these tests: only the local half of AuthRepository runs.
    val authRepository = AuthRepository(
        context,
        dagger.Lazy { throw UnsupportedOperationException("Firebase is not configured in tests") },
        sessionRepository,
        userDao,
        taskDao,
        categoryDao,
        groupDao,
        recurrenceDao,
        reminderDao,
        database
    )
    /** The cloud-side merge rules, wired to this device's database: what a pull hands to Room. */
    val syncAppliers = SyncAppliers(taskDao, categoryDao, groupDao, recurrenceDao, reminderDao)

    /**
     * Signs in a local profile the way the profile screen does.
     *
     * The address is part of the identity, not a label: `users.email` is unique and the insert replaces, so
     * calling this twice on the default email does not add a second profile — it deletes the first one, and
     * every cascade hanging off it takes that profile's rows with it. A test that wants two accounts has to
     * name the second one differently.
     */
    suspend fun signIn(name: String = "Test User", email: String = "test@example.com"): User =
        signInAs(User(email = email, name = name))

    /** Signs in [profile] by inserting it and making it the current one. */
    suspend fun signInAs(profile: User): User {
        val id = userDao.insertUser(profile)
        val user = userDao.getUserById(id)!!
        sessionRepository.setCurrentUser(user)
        return user
    }

    /** Makes [user] the active profile again, the way switching profiles in the app does. */
    suspend fun switchTo(user: User) = sessionRepository.setCurrentUser(user)

    suspend fun addTask(
        userId: Long,
        title: String,
        description: String = "",
        categoryId: Long? = null,
        groupId: String? = null,
        dueAt: Long? = null
    ): Long = taskDao.insertTask(
        Task(
            title = title,
            description = description,
            dueDate = dueAt,
            userId = userId,
            categoryId = categoryId,
            groupId = groupId
        )
    )

    suspend fun addCategory(userId: Long, name: String, color: Int = -16711936): Long =
        categoryDao.insertCategory(
            Category(name = name, color = color, icon = "ic_tag", userId = userId)
        )

    suspend fun addGroup(userId: Long, name: String): TaskGroup =
        TaskGroup(uuid = "group-$name", userId = userId, name = name).also { groupDao.upsertGroup(it) }

    /**
     * A SAF-like uri: the app only ever receives a resolver handle, never a path. Robolectric
     * serves registered streams for such uris, which is what a DocumentsProvider does.
     */
    fun uriWithContent(name: String, content: String): Uri =
        Uri.parse("content://document/$name").also {
            shadowOf(context.contentResolver).registerInputStream(it, ByteArrayInputStream(content.toByteArray()))
        }

    fun capture(name: String): CapturedUri {
        val sink = CapturedUri()
        shadowOf(context.contentResolver).registerOutputStream(sink.uri, sink)
        return sink
    }

    /**
     * Waits until the database threads have run out of work.
     *
     * A screen command is not finished when its call returns any more: Room answers on its own threads,
     * the way it does in the app, and the command's continuation runs there. So a test that reads a
     * stored row or a published figure after such a command first lets those threads settle — otherwise
     * it reads the state the *previous* command left behind and passes or fails for the wrong reason.
     *
     * A short quiet window is required rather than a single idle reading, because the thread that just
     * finished a query can still be handed the next one by the collector it resumed. Returns early once
     * the threads have been idle for QUIET_MILLIS; a timeout is not an error here, it just means the
     * test's own assertions get to say what is wrong.
     */
    fun awaitIdle(timeoutMillis: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var quietSince = -1L
        while (System.currentTimeMillis() < deadline) {
            if (queryExecutor.isIdle && transactionExecutor.isIdle) {
                if (quietSince < 0) quietSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - quietSince >= QUIET_MILLIS) return
            } else {
                quietSince = -1L
            }
            Thread.sleep(1)
        }
    }

    override fun close() {
        // Robolectric keeps a SQLite connection handle per thread. Let both database threads finish the
        // work they already hold before the storage goes away, otherwise a late query is reported
        // against whichever test runs next.
        queryExecutor.shutdown()
        transactionExecutor.shutdown()
        queryExecutor.awaitTermination(5, TimeUnit.SECONDS)
        transactionExecutor.awaitTermination(5, TimeUnit.SECONDS)
        database.close()
    }

    inner class CapturedUri(val uri: Uri = Uri.parse("content://document/capture")) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        val written: String get() = String(buffer.toByteArray(), Charsets.UTF_8)

        override fun write(b: Int) {
            buffer.write(b)
        }
    }
}
