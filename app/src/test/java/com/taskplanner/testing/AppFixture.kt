package com.taskplanner.testing

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import com.taskplanner.data.auth.AuthRepository
import com.taskplanner.data.backup.BackupRepository
import com.taskplanner.data.calendar.CalendarRepository
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
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
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.taskplanner.data.repository.GroupRepository
import com.taskplanner.data.repository.RecurrenceRepository
import com.taskplanner.data.repository.ReminderRepository
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.repository.TaskRepository
import com.taskplanner.data.repository.WorkStatisticsRepository
import com.taskplanner.data.repository.WorkTrackingRepository
import com.taskplanner.data.tracking.MonotonicReference
import com.taskplanner.data.sync.SyncAppliers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.robolectric.Shadows.shadowOf

/**
 * Real objects, no mocked persistence: an in-memory Room database, the production DAOs, the
 * production repositories and a real SharedPreferences file, so repository, backup and sync tests
 * execute the same code paths as the app.
 */
class AppFixture(private val context: Context) : AutoCloseable {

    // Room normally moves database work onto background threads. Robolectric's SQLite shadow keeps
    // connection handles per thread, so those background jobs either fail with "illegal connection
    // pointer" after close() or, worse, never deliver a Flow update at all. Running everything on
    // the calling thread keeps the harness single-threaded and the emissions synchronous.
    private val directExecutor = Executor { command -> command.run() }

    val database: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(directExecutor)
        .setTransactionExecutor(directExecutor)
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
     * The real alarm and notification gateways, on Robolectric's shadows of them: reminders are
     * armed and posted for real in these tests, because "the row exists but nothing was scheduled"
     * is exactly the bug a fake would hide. Assertions read them through
     * `shadowOf(alarmManager)` and `shadowOf(notificationManager)`.
     */
    val reminderScheduler = ReminderScheduler(context)
    val reminderNotifier = ReminderNotifier(context)
    val reminderRepository = ReminderRepository(
        reminderDao, taskDao, categoryDao, sessionRepository, reminderScheduler, reminderNotifier
    )

    // Declared before the task repository on purpose: finishing a task is what advances a series.
    val recurrenceRepository =
        RecurrenceRepository(recurrenceDao, taskDao, sessionRepository, reminderRepository)

    /**
     * The tracker's clocks under the test's control. A duration is only reproducible if the instant it
     * was measured from is, and a clock change is one of the cases the feature has to answer for, so
     * the fixture hands the repository a fake rather than the device clock.
     */
    val timeBase = FakeTimeBase(wall = System.currentTimeMillis(), monotonic = 0L)

    /** Shared by the tracker and the statistics reader, the way the process shares one. */
    val monotonicReference = MonotonicReference(timeBase)

    val trackingSessionDao: TrackingSessionDao = database.trackingSessionDao()
    val workSessionDao: WorkSessionDao = database.workSessionDao()
    val workStatisticsDao: WorkStatisticsDao = database.workStatisticsDao()

    val workTrackingRepository = WorkTrackingRepository(
        database, trackingSessionDao, workSessionDao, sessionRepository, timeBase, monotonicReference
    )

    /** The same storage seen by a second process: what a restart or a re-sign-in reads. */
    fun newWorkTrackingRepository(): WorkTrackingRepository = WorkTrackingRepository(
        database, trackingSessionDao, workSessionDao, sessionRepository, timeBase,
        MonotonicReference(timeBase)
    )

    val workStatisticsRepository = WorkStatisticsRepository(
        workStatisticsDao, workSessionDao, sessionRepository, timeBase, monotonicReference
    )

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

    /** Signs in a local profile the way the profile screen does. */
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

    override fun close() {
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
