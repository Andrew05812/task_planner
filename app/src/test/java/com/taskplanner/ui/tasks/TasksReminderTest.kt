package com.taskplanner.ui.tasks

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.testing.AppFixture
import java.util.Date
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The reminder section of the task screen, end to end: what saving the chips stores, what the card
 * shows afterwards, and what the editor is told when the plan cannot actually ring.
 *
 * The alarm manager and the notification manager are Robolectric's shadows, so a test can say "this
 * plan put an alarm on the clock" or "this device will not show it" instead of trusting the row.
 */
@RunWith(RobolectricTestRunner::class)
class TasksReminderTest {

    private lateinit var context: Context
    private lateinit var fixture: AppFixture
    private lateinit var viewModel: TasksViewModel
    private lateinit var alarms: AlarmManager
    private lateinit var notifications: NotificationManager
    private var userId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        alarms = context.getSystemService(AlarmManager::class.java)
        notifications = context.getSystemService(NotificationManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // viewModelScope needs Dispatchers.Main; an inline executor keeps every collector on the
        // test thread instead of waiting for a looper that nothing pumps.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "reminders-ui@example.com").id }
        viewModel = openList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun inFuture(minutes: Int): Date = Date(System.currentTimeMillis() + minutes * MINUTE)

    private fun rows(model: TasksViewModel = viewModel) =
        model.state.value.items.filterIsInstance<TasksListItem.TaskRow>()

    private fun badgeOf(title: String): Int =
        rows().first { it.task.title == title }.pendingReminders

    /** Room notifies flow collectors from a background executor, so the test has to wait for it. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("timed out waiting for $what")
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun armedCount(): Int = shadowOf(alarms).scheduledAlarms.size

    /** A view model on the same storage: what opening the list screen again creates. */
    private fun openList(): TasksViewModel = TasksViewModel(
        fixture.taskRepository,
        fixture.categoryRepository,
        fixture.groupRepository,
        fixture.recurrenceRepository,
        fixture.reminderRepository,
        fixture.sessionRepository
    )

    @Test
    fun `saving a task with lead times puts the bell on its card`() {
        viewModel.saveTask(
            title = "Renew the passport",
            description = "Office closes at four",
            dueDate = inFuture(120),
            reminders = listOf(0, 15)
        )

        awaitUntil("the save to report success") { viewModel.taskSaveState.value == TaskSaveState.Success }
        assertEquals(TaskSaveState.Success, viewModel.taskSaveState.value)
        awaitUntil("the card to badge itself") { rows().size == 1 }
        assertEquals(2, badgeOf("Renew the passport"))
        assertEquals(
            "both minutes are on the clock, so the card is not promising a bell that never rings",
            2,
            armedCount()
        )
        assertEquals(listOf(0, 15), viewModel.reminderOffsets.value)
    }

    @Test
    fun `the same chip twice is stored once`() {
        viewModel.saveTask(
            title = "Call the bank",
            description = "",
            dueDate = inFuture(60),
            reminders = listOf(15, 15)
        )

        awaitUntil("the card to render") { rows().size == 1 }
        assertEquals(1, badgeOf("Call the bank"))
        assertEquals(1, armedCount())
    }

    @Test
    fun `a lead time with no deadline is refused and the task is not touched`() {
        viewModel.saveTask(
            title = "Someday maybe",
            description = "",
            dueDate = null,
            reminders = listOf(15)
        )

        assertEquals(
            "the editor has to ask for a date instead of quietly dropping the chip",
            TaskSaveState.ReminderNeedsDeadline,
            viewModel.taskSaveState.value
        )
        assertEquals(emptyList<String>(), rows().map { it.task.title })
        assertEquals(0, armedCount())
    }

    @Test
    fun `unchecking the last chip takes the bell off the card`() {
        viewModel.saveTask(
            title = "Water the plants",
            description = "",
            dueDate = inFuture(90),
            reminders = listOf(15)
        )
        awaitUntil("the first save to render") { rows().size == 1 }
        val taskId = rows().single().task.id

        viewModel.loadTask(taskId)
        viewModel.saveTask(
            taskId = taskId,
            title = "Water the plants",
            description = "",
            dueDate = inFuture(90),
            reminders = emptyList()
        )

        awaitUntil("the badge to disappear") { badgeOf("Water the plants") == 0 }
        assertEquals(emptyList<Int>(), viewModel.reminderOffsets.value)
        assertEquals(
            "and its alarm is gone as well, not only its row",
            0,
            armedCount()
        )
    }

    @Test
    fun `opening the task again ticks the chips it was saved with`() {
        viewModel.saveTask(
            title = "Take the medicine",
            description = "",
            dueDate = inFuture(300),
            reminders = listOf(1440, 30, 0)
        )
        awaitUntil("the card to render") { rows().size == 1 }
        val taskId = rows().single().task.id

        viewModel.clearCurrentTask()
        assertEquals(emptyList<Int>(), viewModel.reminderOffsets.value)

        viewModel.loadTask(taskId)

        awaitUntil("the editor to load its plan") { viewModel.reminderOffsets.value.isNotEmpty() }
        assertEquals(
            "soonest ring first, exactly what the chips are drawn from",
            listOf(0, 30, 1440),
            viewModel.reminderOffsets.value
        )
    }

    @Test
    fun `finishing a task from the editor leaves nothing armed`() {
        viewModel.saveTask(
            title = "Hand in the report",
            description = "",
            dueDate = inFuture(60),
            status = TaskStatus.COMPLETED,
            reminders = listOf(15)
        )

        awaitUntil("the finished task to leave the list") { rows().isEmpty() }
        assertEquals(
            "a task the user completed must not ring later",
            0,
            armedCount()
        )
        val stored = runBlocking { fixture.taskDao.getAllRowsOnce(userId).single() }
        assertTrue(stored.archived)
        assertEquals(
            "its lead time is kept for the archive, ready for restoring",
            listOf(15),
            runBlocking { fixture.reminderRepository.offsetsFor(stored.uuid) }
        )
    }

    @Test
    fun `the editor knows when the device will not let a reminder ring`() {
        assertTrue(viewModel.canPostNotifications())
        assertTrue(viewModel.canScheduleExactAlarms())

        shadowOf(notifications).setNotificationsEnabled(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(
            "a plan the editor shows must match what the system will actually do",
            viewModel.canPostNotifications()
        )
        assertFalse(viewModel.canScheduleExactAlarms())
    }

    @Test
    fun `moving the date of a reminded task keeps its lead times`() {
        viewModel.saveTask(
            title = "Dentist",
            description = "",
            dueDate = inFuture(120),
            reminders = listOf(15, 60)
        )
        awaitUntil("the card to render") { rows().size == 1 }
        val taskId = rows().single().task.id

        viewModel.loadTask(taskId)
        viewModel.saveTask(
            taskId = taskId,
            title = "Dentist",
            description = "",
            dueDate = inFuture(600),
            reminders = null
        )

        awaitUntil("the badge to stay") { badgeOf("Dentist") == 2 }
        assertEquals(listOf(15, 60), viewModel.reminderOffsets.value)
        val stored = runBlocking { fixture.taskDao.getTaskById(taskId)!! }
        val rows = runBlocking { fixture.reminderDao.getLiveForTask(stored.uuid) }
        rows.forEach { row ->
            assertEquals(
                "every lead time points back from the date the task has now",
                stored.dueDate!! - row.offsetMinutes * MINUTE,
                row.triggerAt
            )
        }
        assertEquals(2, armedCount())
    }

    @Test
    fun `opening the list again puts the alarms the device lost back on the clock`() {
        viewModel.saveTask(
            title = "Pick up the parcel",
            description = "",
            dueDate = inFuture(120),
            reminders = listOf(15)
        )
        awaitUntil("the card to render") { rows().size == 1 }
        val trigger = shadowOf(alarms).scheduledAlarms.single().triggerAtMs

        // The state a reboot or a force-stop leaves behind: the rows are there, the alarms are not.
        runBlocking {
            fixture.reminderDao.getLiveForUser(userId).forEach { fixture.reminderScheduler.cancel(it) }
        }
        assertEquals(0, armedCount())

        openList()

        awaitUntil("the alarm to come back") { armedCount() == 1 }
        assertEquals(
            "re-armed for the minute the row says, not for another one",
            trigger,
            shadowOf(alarms).scheduledAlarms.single().triggerAtMs
        )
    }

    @Test
    fun `a reminder whose minute came while the app was away is delivered when the list opens`() {
        val due = System.currentTimeMillis() - 2 * MINUTE
        val taskId = runBlocking {
            val id = fixture.taskDao.insertTask(
                com.taskplanner.data.model.Task(
                    uuid = "task-late",
                    title = "Missed while the app was closed",
                    description = "",
                    dueDate = due,
                    userId = userId
                )
            )
            val task = fixture.taskDao.getTaskById(id)!!
            fixture.reminderRepository.applyReminders(task, listOf(0))
            id
        }

        openList()

        val reminder = runBlocking { fixture.reminderDao.findByTaskAndOffset("task-late", 0)!! }
        awaitUntil("the late reminder to be announced") {
            shadowOf(notifications).getNotification(reminder.notificationId) != null
        }
        assertTrue(
            "and it is recorded as announced, so no later pass repeats it",
            runBlocking { fixture.reminderDao.findByUuid(reminder.uuid)!!.notifiedAt } != null
        )
        assertEquals(1, rows().count { it.task.id == taskId })
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
