package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.recurrence.RecurrenceCalculator
import com.taskplanner.testing.AppFixture
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How a series behaves in real storage: one open instance at a time, the next one appearing only
 * when the current one is finished with, ids that survive a restart or a pull from another device,
 * and no effect on the series when a single instance is edited.
 *
 * The device zone is pinned so the scheduled instants in these tests are the calendar dates the
 * series was built from; the calendar maths itself is covered by RecurrenceCalculatorTest.
 */
@RunWith(RobolectricTestRunner::class)
class RecurrenceRepositoryTest {

    private lateinit var defaultZone: TimeZone
    private lateinit var fixture: AppFixture
    private lateinit var context: Context

    @Before
    fun setUp() {
        defaultZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    @After
    fun tearDown() {
        fixture.close()
        TimeZone.setDefault(defaultZone)
    }

    @Test
    fun `a task becomes the first instance of its series`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Water plants", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")

        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!

        assertEquals(instant("2026-01-05T09:00+01:00[Europe/Berlin]"), rule.anchorAt)
        assertEquals(rule.anchorAt, rule.cursorAt)
        assertEquals("Europe/Berlin", rule.zoneId)
        assertEquals(RepeatUnit.DAY, rule.unit)
        assertTrue(rule.isOpen)

        val instance = fixture.taskDao.getTaskById(task.id)!!
        assertEquals(rule.uuid, instance.seriesUuid)
        assertEquals(rule.cursorAt, instance.occurrenceAt)
        assertEquals("the row keeps the identity sync already knows", task.uuid, instance.uuid)
    }

    @Test
    fun `a task with no due date anchors at the moment it is made recurring`() = runTest {
        val user = fixture.signIn()
        val before = System.currentTimeMillis()
        val task = addTask(user.id, "Stand up", dueAt = null)

        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!

        assertTrue(rule.anchorAt >= before)
        assertEquals(rule.anchorAt, fixture.taskDao.getTaskById(task.id)!!.occurrenceAt)
    }

    @Test
    fun `completing one instance produces the next one and nothing else`() = runTest {
        val user = fixture.signIn()
        val task = addTask(
            user.id, "Take pills",
            dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]",
            categoryId = fixture.addCategory(user.id, "Health")
        )
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        val live = fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!

        fixture.taskRepository.completeAndArchive(live.id)

        assertEquals(
            "finishing the instance is itself what tells the series to produce the next one",
            1,
            openInstances(rule.uuid).size
        )
        assertEquals(
            "and the repair step then has nothing left to create",
            0,
            fixture.recurrenceRepository.ensureOpenInstances(now = live.dueDate!! + DAY)
        )
        val next = openInstances(rule.uuid).single()
        assertEquals(instant("2026-01-06T09:00+01:00[Europe/Berlin]"), next.dueDate!!)
        assertEquals(TaskStatus.TODO, next.status)
        assertFalse(next.archived)
        assertNull(next.startedAt)
        assertEquals("Take pills", next.title)
        assertEquals(live.categoryId, next.categoryId)
        assertEquals(SyncState.PENDING, next.syncState)
        assertEquals(
            RecurrenceCalculator.occurrenceUuid(rule.uuid, next.occurrenceAt!!), next.uuid
        )
        assertEquals(rule.uuid, next.seriesUuid)

        // the finished instance is history, not something the series rewrites
        assertEquals(1, archivedCount(user.id))
        assertEquals(2, instancesOf(rule.uuid).size)
    }

    @Test
    fun `the repair step is idempotent`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Stretch")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!.also { live ->
            fixture.taskRepository.completeAndArchive(live.id)
        }

        fixture.recurrenceRepository.ensureOpenInstances()
        fixture.recurrenceRepository.ensureOpenInstances()
        fixture.recurrenceRepository.ensureOpenInstances()

        assertEquals(2, instancesOf(rule.uuid).size)
        assertEquals(1, openInstances(rule.uuid).size)
    }

    @Test
    fun `a series nobody opened for a week still exposes exactly one instance`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Read", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!

        val created = fixture.recurrenceRepository.ensureOpenInstances(
            now = instant("2026-01-12T09:00+01:00[Europe/Berlin]")
        )

        assertEquals("the overdue instance is still the open one", 0, created)
        assertEquals(listOf("2026-01-05"), openInstances(rule.uuid).map { dateText(it.dueDate!!) })
        assertEquals(rule.cursorAt, fixture.recurrenceDao.findByUuid(rule.uuid)!!.cursorAt)
    }

    @Test
    fun `a deleted instance is replaced by the next scheduled one`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Floss", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        val live = fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!

        fixture.taskRepository.softDeleteTasks(listOf(live.id))
        fixture.recurrenceRepository.ensureOpenInstances()

        val next = openInstances(rule.uuid).single()
        assertEquals(instant("2026-01-06T09:00+01:00[Europe/Berlin]"), next.dueDate!!)
        assertEquals(1, instancesOf(rule.uuid).count { it.isDeleted })
    }

    @Test
    fun `a paused series produces nothing until it is resumed`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Journal", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        val live = fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!

        assertTrue(fixture.recurrenceRepository.setPaused(rule.uuid, true))
        fixture.taskRepository.completeAndArchive(live.id)
        assertEquals(0, fixture.recurrenceRepository.ensureOpenInstances())
        assertEquals(rule.cursorAt, fixture.recurrenceDao.findByUuid(rule.uuid)!!.cursorAt)

        assertTrue(fixture.recurrenceRepository.setPaused(rule.uuid, false))
        assertEquals(
            "resuming catches up on the instance the series owed while it was stopped",
            1,
            openInstances(rule.uuid).size
        )
        assertEquals(0, fixture.recurrenceRepository.ensureOpenInstances())
        assertEquals(1, openInstances(rule.uuid).size)
    }

    @Test
    fun `finishing a series leaves the open instance alone and stops producing`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Rotate the plants", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!

        fixture.recurrenceRepository.finish(rule.uuid)
        fixture.taskRepository.completeAndArchive(
            fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!.id
        )

        assertEquals(0, fixture.recurrenceRepository.ensureOpenInstances())
        assertFalse(fixture.recurrenceDao.findByUuid(rule.uuid)!!.isOpen)
    }

    @Test
    fun `deleting a series deletes what it still owes and keeps the archive`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Weekly report", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.WEEK)!!
        val first = fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!
        fixture.taskRepository.completeAndArchive(first.id)
        fixture.recurrenceRepository.ensureOpenInstances()
        val second = openInstances(rule.uuid).single()

        assertTrue(fixture.recurrenceRepository.deleteSeries(rule.uuid))

        assertEquals(0, openInstances(rule.uuid).size)
        assertEquals("work that was done stays in the archive", 1, archivedCount(user.id))
        assertEquals(listOf(0, 1), instancesOf(rule.uuid).sortedBy { it.id }.map { if (it.isDeleted) 1 else 0 })
        val tombstone = fixture.recurrenceDao.findByUuid(rule.uuid)!!
        assertTrue(tombstone.isDeleted)
        assertEquals(SyncState.DELETING, tombstone.syncState)
    }

    @Test
    fun `editing one instance does not move the series`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Call the dentist", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        val live = fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!

        // "this instance only": moved three days ahead
        fixture.taskRepository.updateTask(
            live.copy(dueDate = instant("2026-01-08T09:00+01:00[Europe/Berlin]"))
        )
        fixture.taskRepository.completeAndArchive(live.id)
        fixture.recurrenceRepository.ensureOpenInstances()

        assertEquals(
            "the series continues from the scheduled instant, not from the moved one",
            instant("2026-01-06T09:00+01:00[Europe/Berlin]"),
            openInstances(rule.uuid).single().dueDate!!
        )
    }

    @Test
    fun `changing the pattern for this and all future re-anchors the series`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Team sync", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!

        val updated = fixture.recurrenceRepository.updateThisAndFuture(
            rule.uuid, RepeatUnit.WEEK, interval = 2, weekDays = listOf(3)
        )!!

        assertEquals(RepeatUnit.WEEK, updated.unit)
        assertEquals(2, updated.interval)
        assertEquals(listOf(3), updated.scheduledWeekDays)
        assertEquals("the live instance is where the new pattern starts", rule.cursorAt, updated.cursorAt)
        assertEquals(1, openInstances(rule.uuid).size)

        fixture.taskRepository.completeAndArchive(
            fixture.taskDao.findSeriesInstance(rule.uuid, updated.cursorAt)!!.id
        )
        fixture.recurrenceRepository.ensureOpenInstances()
        val wednesday = openInstances(rule.uuid).single()
        assertEquals(instant("2026-01-07T09:00+01:00[Europe/Berlin]"), wednesday.dueDate!!)

        fixture.taskRepository.completeAndArchive(wednesday.id)
        fixture.recurrenceRepository.ensureOpenInstances()
        assertEquals(
            "biweekly skips the week in between",
            instant("2026-01-21T09:00+01:00[Europe/Berlin]"),
            openInstances(rule.uuid).single().dueDate!!
        )
    }

    @Test
    fun `a pattern change that would land on an existing instance is refused`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Standup", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        fixture.taskRepository.completeAndArchive(
            fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!.id
        )
        fixture.recurrenceRepository.ensureOpenInstances()

        val refused = fixture.recurrenceRepository.updateThisAndFuture(
            rule.uuid, RepeatUnit.DAY, rebaseAt = instant("2026-01-05T09:00+01:00[Europe/Berlin]")
        )

        assertNull("the first instance is already taken by the archived row", refused)
        val unchanged = fixture.recurrenceDao.findByUuid(rule.uuid)!!
        assertEquals(instant("2026-01-06T09:00+01:00[Europe/Berlin]"), unchanged.cursorAt)
        assertEquals(2, instancesOf(rule.uuid).size)
    }

    @Test
    fun `an instance another device already produced is not created twice`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Back up the phone", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!
        val nextInstant = RecurrenceCalculator.nextOccurrence(rule, rule.cursorAt)!!
        // what a pull from the cloud leaves behind for the same occurrence
        fixture.taskDao.insertTask(
            Task(
                uuid = RecurrenceCalculator.occurrenceUuid(rule.uuid, nextInstant),
                title = "Back up the phone (renamed on the other phone)",
                description = "",
                dueDate = nextInstant,
                userId = user.id,
                seriesUuid = rule.uuid,
                occurrenceAt = nextInstant,
                syncState = SyncState.SYNCED
            )
        )

        fixture.taskRepository.completeAndArchive(
            fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!.id
        )
        val created = fixture.recurrenceRepository.ensureOpenInstances()

        assertEquals(0, created)
        val live = openInstances(rule.uuid).single()
        assertEquals("the row the other device edited wins", nextInstant, live.dueDate!!)
        assertTrue(live.title.contains("renamed"))
        assertEquals(2, instancesOf(rule.uuid).size)
    }

    @Test
    fun `another profile never sees or advances a series`() = runTest {
        val first = fixture.signIn(name = "A", email = "a@example.com")
        val task = addTask(first.id, "My habit", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!

        val second = fixture.userDao.insertUser(User(email = "b@example.com", name = "B"))
        fixture.switchTo(fixture.userDao.getUserById(second)!!)
        val foreign = addTask(second, "Their habit", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val foreignRule = fixture.recurrenceRepository.startSeries(foreign, RepeatUnit.MONTH)!!

        fixture.switchTo(fixture.userDao.getUserById(first.id)!!)
        fixture.taskRepository.completeAndArchive(
            fixture.taskDao.findSeriesInstance(rule.uuid, rule.cursorAt)!!.id
        )
        fixture.recurrenceRepository.ensureOpenInstances()

        assertEquals(2, instancesOf(rule.uuid, first.id).size)
        assertEquals("the other profile's series is untouched", 1, instancesOf(foreignRule.uuid, second).size)
        assertEquals(foreignRule.cursorAt, fixture.recurrenceDao.findByUuid(foreignRule.uuid)!!.cursorAt)
    }

    @Test
    fun `a series instance is an ordinary task in the list`() = runTest {
        val user = fixture.signIn()
        val task = addTask(user.id, "Milk", dueAt = "2026-01-05T09:00+01:00[Europe/Berlin]")
        val rule = fixture.recurrenceRepository.startSeries(task, RepeatUnit.DAY)!!

        val visible = fixture.taskDao.getActiveTasksOnce(user.id).map { it.title }

        assertEquals(listOf("Milk"), visible)
        assertNotNull(fixture.recurrenceRepository.ruleFor(fixture.taskDao.getTaskById(task.id)!!))
        assertEquals(rule.cursorAt, fixture.recurrenceRepository.liveInstance(rule)!!.occurrenceAt)
    }

    @Test
    fun `nobody signed in means no series work at all`() = runTest {
        assertEquals(0, fixture.recurrenceRepository.ensureOpenInstances())
        assertNull(fixture.recurrenceRepository.ruleFor(addTask(1L, "Orphan", dueAt = null)))
    }

    private suspend fun addTask(
        userId: Long,
        title: String,
        dueAt: String? = null,
        categoryId: Long? = null
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title",
                title = title,
                description = "",
                dueDate = dueAt?.let { instant(it) },
                categoryId = categoryId,
                userId = userId
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    private suspend fun archivedCount(userId: Long): Int =
        fixture.taskDao.getAllRowsOnce(userId).count { it.archived && !it.isDeleted }

    private suspend fun instancesOf(seriesUuid: String, userId: Long? = null): List<Task> =
        fixture.taskDao.getAllRowsOnce(userId ?: currentUserId())
            .filter { it.seriesUuid == seriesUuid }

    private suspend fun openInstances(seriesUuid: String): List<Task> =
        instancesOf(seriesUuid).filter { !it.isDeleted && !it.archived && it.status != TaskStatus.COMPLETED }

    private suspend fun currentUserId(): Long = fixture.sessionRepository.currentUserId()!!

    private fun dateText(millis: Long): String = ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.of("Europe/Berlin")
    ).toLocalDate().toString()

    private fun instant(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
