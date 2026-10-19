package com.taskplanner.data.backup

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.testing.AppFixture
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Versioned JSON backups, read and written through the Storage Access Framework resolver, against
 * a real Room database. The tests below are what keeps an import from destroying a user's data:
 * relation remapping, duplicate detection, version refusal, and merge/replace semantics.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryTest {

    private lateinit var fixture: AppFixture
    private lateinit var context: Context
    private lateinit var backup: BackupRepository
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        alarmManager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        backup = BackupRepository(
            context,
            fixture.database,
            fixture.taskDao,
            fixture.categoryDao,
            fixture.groupDao,
            fixture.recurrenceDao,
            fixture.reminderDao,
            fixture.sessionRepository,
            fixture.recurrenceRepository,
            fixture.reminderRepository
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private suspend fun sampleBackup(): Pair<Long, BackupFile> {
        val user = fixture.signIn(email = "backup@example.com")
        val categoryId = fixture.addCategory(user.id, "Работа")
        val group = fixture.addGroup(user.id, "ПРОЕКТ")
        fixture.addTask(user.id, "Prepare report", categoryId = categoryId, groupId = group.uuid)
        val doneId = fixture.addTask(user.id, "Old done task", categoryId = categoryId)
        fixture.taskDao.updateTask(
            fixture.taskDao.getTaskById(doneId)!!.copy(
                status = TaskStatus.COMPLETED,
                archived = true,
                archivedAt = 1_700_000_000_000L
            )
        )
        val json = backup.buildBackupJson()
        return user.id to Gson().fromJson(json, BackupFile::class.java)
    }

    @Test
    fun `export contains tasks categories and groups with their relations`() = runTest {
        val (_, file) = sampleBackup()

        assertEquals(2, file.data.tasks.size)
        assertEquals(1, file.data.categories.size)
        assertEquals(1, file.data.groups.size)
        assertEquals("1.1", file.formatVersion)
        assertNotNull(file.data.tasks.first().uuid)
        assertEquals(
            "a task must point at the exported category",
            file.data.categories.single().id,
            file.data.tasks.first { it.title == "Prepare report" }.categoryId
        )
    }

    @Test
    fun `export never carries credentials`() = runTest {
        fixture.signIn(email = "secret@example.com")
        fixture.userDao.setCurrentUser(
            fixture.userDao.getAllUsersOnce().single().copy(firebaseUid = "firebase-uid-123")
        )
        val json = backup.buildBackupJson()

        listOf("firebase-uid-123", "firebaseUid", "password", "token", "apiKey").forEach {
            assertFalse("export must not contain $it", json.contains(it))
        }
    }

    @Test
    fun `writeBackup sends the json through the content resolver`() = runTest {
        val (_, file) = sampleBackup()
        val capture = fixture.capture("export.json")

        backup.writeBackup(capture.uri, Gson().toJson(file))

        assertTrue(capture.written.isNotBlank())
        assertTrue(capture.written.contains("Prepare report"))
    }

    @Test
    fun `replace import restores exactly the exported data`() = runTest {
        val (userId, file) = sampleBackup()
        // the user keeps working and adds/removes things afterwards
        fixture.addTask(userId, "added later")
        fixture.taskDao.softDeleteTasks(listOf(fixture.addTask(userId, "added then removed")))

        backup.import(file, replaceExisting = true)

        val live = fixture.taskDao.getAllTasksOnce(userId)
        assertEquals(listOf("Old done task", "Prepare report"), live.map { it.title }.sorted())
        assertEquals(1, fixture.categoryDao.getAllCategoriesOnce(userId).size)
        assertEquals(1, fixture.groupDao.getAllGroupsOnce(userId).size)
    }

    @Test
    fun `replace import keeps the archive and the task category link`() = runTest {
        val (userId, file) = sampleBackup()
        backup.import(file, replaceExisting = true)

        val restored = fixture.taskDao.getAllTasksOnce(userId).first { it.title == "Prepare report" }
        val category = fixture.categoryDao.getAllCategoriesOnce(userId).single()
        assertEquals(category.id, restored.categoryId)
        assertEquals(category.uuid, fixture.categoryDao.getCategoryById(category.id)?.uuid)

        val archived = fixture.taskDao.getAllTasksOnce(userId).first { it.title == "Old done task" }
        assertTrue("completed task must come back archived", archived.archived)
    }

    @Test
    fun `merge keeps local rows and adds only what is new`() = runTest {
        val (userId, file) = sampleBackup()
        fixture.addTask(userId, "created after export")

        backup.import(file, replaceExisting = false)

        val titles = fixture.taskDao.getAllTasksOnce(userId).map { it.title }
        assertTrue(titles.containsAll(listOf("Prepare report", "Old done task", "created after export")))
        assertEquals(3, titles.size)
    }

    @Test
    fun `importing the same backup twice does not duplicate anything`() = runTest {
        val (userId, file) = sampleBackup()

        backup.import(file, replaceExisting = false)
        backup.import(file, replaceExisting = false)

        assertEquals(2, fixture.taskDao.getAllTasksOnce(userId).size)
        assertEquals(1, fixture.categoryDao.getAllCategoriesOnce(userId).size)
        assertEquals(1, fixture.groupDao.getAllGroupsOnce(userId).size)
    }

    @Test
    fun `merge does not overwrite a local row that shares an id with a backup row`() = runTest {
        val userId = fixture.signIn(email = "collide@example.com").id
        val localCategoryId = fixture.addCategory(userId, "LOCAL")
        fixture.addTask(userId, "local task")

        // a backup made on another device: same category ids, unrelated local rows
        val foreign = BackupFile(
            formatVersion = "1.0",
            appVersion = "test",
            exportedAt = 1,
            data = BackupData(
                tasks = listOf(
                    Task(id = 1, uuid = "remote-task", title = "remote task", description = "",
                        userId = 99, categoryId = 1)
                ),
                categories = listOf(
                    com.taskplanner.data.model.Category(
                        id = 1, uuid = "remote-category", name = "Remote", color = 0,
                        icon = "ic_tag", userId = 99
                    )
                )
            )
        )

        backup.import(foreign, replaceExisting = false)

        assertEquals(
            "the local category must keep its own name",
            "LOCAL",
            fixture.categoryDao.getCategoryById(localCategoryId)?.name
        )
        assertEquals(
            "the local task must still be there",
            listOf("local task"),
            fixture.taskDao.getAllTasksOnce(userId).filter { it.title == "local task" }.map { it.title }
        )
        val importedTask = fixture.taskDao.findTaskByUuid("remote-task")
        assertNotNull(importedTask)
        assertEquals(userId, importedTask!!.userId)
        assertEquals(
            "the imported task must be linked to the newly created local category row",
            fixture.categoryDao.getCategoryByUuid("remote-category")?.id,
            importedTask.categoryId
        )
    }

    @Test
    fun `merge moves a newer version of a known row instead of adding a copy`() = runTest {
        val (userId, file) = sampleBackup()
        val task = file.data.tasks.first { it.title == "Prepare report" }
        val edited = task.copy(title = "Prepare report v2", updatedAt = task.updatedAt + 10_000)
        val updated = file.copy(data = file.data.copy(tasks = file.data.tasks.map {
            if (it.uuid == task.uuid) edited else it
        }))

        backup.import(updated, replaceExisting = false)

        val byUuid = fixture.taskDao.findTaskByUuid(task.uuid)
        assertEquals("Prepare report v2", byUuid?.title)
        assertEquals(2, fixture.taskDao.getAllTasksOnce(userId).size)
    }

    @Test
    fun `an imported row becomes pending so it is uploaded later`() = runTest {
        val (userId, file) = sampleBackup()
        val alreadySynced = file.copy(
            data = file.data.copy(tasks = file.data.tasks.map { it.copy(syncState = SyncState.SYNCED) })
        )

        backup.import(alreadySynced, replaceExisting = true)

        val rows = fixture.taskDao.getAllRowsOnce(userId)
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(SyncState.PENDING, it.syncState) }
    }

    @Test
    fun `a backup with duplicated identifiers is refused`() = runTest {
        val (_, file) = sampleBackup()
        val doubled = file.data.tasks.first()
        val broken = file.copy(
            data = file.data.copy(tasks = file.data.tasks + doubled.copy(title = "copy"))
        )
        val uri = fixture.uriWithContent("duplicated.json", Gson().toJson(broken))

        assertRefused(uri)
    }

    @Test
    fun `a backup whose task points at a missing category is refused`() = runTest {
        val (_, file) = sampleBackup()
        val broken = file.copy(
            data = file.data.copy(
                tasks = file.data.tasks.map { it.copy(categoryId = 4242) },
                groups = emptyList()
            )
        )
        val uri = fixture.uriWithContent("dangling.json", Gson().toJson(broken))

        assertRefused(uri)
    }

    @Test
    fun `a backup whose category points at a missing parent is refused`() = runTest {
        val (_, file) = sampleBackup()
        val broken = file.copy(
            data = file.data.copy(
                categories = file.data.categories.map { it.copy(parentCategoryId = 9999) }
            )
        )
        val uri = fixture.uriWithContent("dangling-parent.json", Gson().toJson(broken))

        assertRefused(uri)
    }

    @Test
    fun `a backup whose task points at a missing group is refused`() = runTest {
        val (_, file) = sampleBackup()
        val broken = file.copy(
            data = file.data.copy(tasks = file.data.tasks.map { it.copy(groupId = "no-such-group") })
        )
        val uri = fixture.uriWithContent("dangling-group.json", Gson().toJson(broken))

        assertRefused(uri)
    }

    @Test
    fun `an unknown format version is refused instead of half-read`() = runTest {
        val (_, file) = sampleBackup()

        listOf("2.0", "1.2", "abc", "").forEach { version ->
            val uri = fixture.uriWithContent("v$version.json", Gson().toJson(file.copy(formatVersion = version)))
            val error = runCatching { backup.readAndValidate(uri) }.exceptionOrNull()
            assertNotNull("format version '$version' must be refused", error)
            assertTrue(
                "format version '$version' must be reported as unsupported",
                error is BackupUnsupportedVersionException
            )
        }
    }

    @Test
    fun `the current format version is accepted`() = runTest {
        val (_, file) = sampleBackup()
        val uri = fixture.uriWithContent("good.json", Gson().toJson(file))

        assertEquals(file.data.tasks.size, backup.readAndValidate(uri).data.tasks.size)
    }

    @Test
    fun `a file that is not a backup is refused`() = runTest {
        val uri = fixture.uriWithContent("notes.txt", "just some text, not json")

        val error = runCatching { backup.readAndValidate(uri) }.exceptionOrNull()
        assertTrue(error is BackupValidationException)
    }

    @Test
    fun `summary separates active tasks from the archive`() = runTest {
        val (_, file) = sampleBackup()

        val summary = backup.summarize(file)

        assertEquals(1, summary.taskCount)
        assertEquals(1, summary.archivedCount)
        assertEquals(1, summary.categoryCount)
        assertEquals(1, summary.groupCount)
    }

    @Test
    fun `a destructive replace writes a safety copy first`() = runTest {
        val (userId, file) = sampleBackup()
        val backupsDir = java.io.File(context.filesDir, "backups")
        assertFalse(backupsDir.listFiles().orEmpty().any { it.name.startsWith("safety_") })

        backup.import(file, replaceExisting = true)

        val safety = backupsDir.listFiles().orEmpty().filter { it.name.startsWith("safety_") }
        assertEquals("exactly one safety copy per destructive import", 1, safety.size)
        assertTrue(safety.single().readText().contains("Prepare report"))
    }

    @Test
    fun `import without a signed in profile changes nothing`() = runTest {
        val (userId, file) = sampleBackup()
        fixture.sessionRepository.endSession()

        val error = runCatching { backup.import(file, replaceExisting = true) }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertNull(fixture.sessionRepository.currentUserId())
        assertEquals("data must stay untouched", 2, fixture.taskDao.getAllRowsOnce(userId).size)
    }

    private fun assertRefused(uri: android.net.Uri) {
        val error = runCatching { backup.readAndValidate(uri) }.exceptionOrNull()
        assertNotNull("the backup should have been refused", error)
        assertTrue(
            "expected a validation refusal, got ${error?.javaClass}",
            error is BackupValidationException
        )
    }

    /**
     * A profile whose single task repeats weekly, carries two lead times and a planned window — the
     * data format 1.1 added. The series and the reminders are made through the real repositories, so
     * what gets exported is what the app actually stores.
     */
    private suspend fun recurringBackup(email: String): Pair<Long, BackupFile> {
        val user = fixture.signIn(email = email)
        val due = System.currentTimeMillis() + 6 * HOUR
        val taskId = fixture.addTask(user.id, "Water plants", dueAt = due)
        fixture.recurrenceRepository.startSeries(
            fixture.taskDao.getTaskById(taskId)!!, RepeatUnit.WEEK, weekDays = listOf(2, 5)
        )
        val planned = fixture.taskDao.getTaskById(taskId)!!.copy(
            startTime = due,
            endTime = due + 45 * MINUTE,
            estimatedMinutes = 45
        )
        fixture.taskDao.updateTask(planned)
        assertTrue(
            "a task with a deadline can be given lead times",
            fixture.reminderRepository.applyReminders(planned, listOf(15, 60))
        )
        return user.id to Gson().fromJson(backup.buildBackupJson(), BackupFile::class.java)
    }

    private fun armedSlots(): Set<Int> =
        shadowOf(alarmManager).scheduledAlarms.map { shadowOf(it.operation).requestCode }.toSet()

    /** Forgets every alarm on the clock: what a device that was switched off looks like. */
    private fun clearAlarms() {
        shadowOf(alarmManager).scheduledAlarms.toList()
            .forEach { alarm -> alarm.operation?.let { alarmManager.cancel(it) } }
    }

    @Test
    fun `export carries the series and the lead times of the profile`() = runTest {
        val (userId, file) = recurringBackup("carry@example.com")

        assertEquals(1, file.data.recurrenceRules.size)
        val rule = file.data.recurrenceRules.single()
        assertEquals(userId, rule.userId)
        assertEquals(RepeatUnit.WEEK, rule.unit)
        assertEquals(listOf(2, 5), rule.scheduledWeekDays)
        assertEquals(2, file.data.reminders.size)
        assertEquals(listOf(15, 60), file.data.reminders.map { it.offsetMinutes }.sorted())
        assertEquals(
            "a lead time travels with the task it counts back from",
            file.data.tasks.single().uuid,
            file.data.reminders.first().taskUuid
        )
    }

    @Test
    fun `a restore brings the series the lead times and the planned window back`() = runTest {
        val (userId, file) = recurringBackup("restore@example.com")

        backup.import(file, replaceExisting = true)

        val stored = fixture.recurrenceDao.getAllRulesOnce(userId).single()
        assertEquals(file.data.recurrenceRules.single().uuid, stored.uuid)
        assertEquals(RepeatUnit.WEEK, stored.unit)
        assertEquals(file.data.recurrenceRules.single().cursorAt, stored.cursorAt)

        val reminders = fixture.reminderDao.getLiveForUser(userId)
        assertEquals(
            listOf(15, 60), reminders.map { it.offsetMinutes }.sorted()
        )
        assertEquals(
            "the alarm must stay on the minute the user chose, not one recomputed from a moved date",
            file.data.reminders.map { it.triggerAt }.sorted(),
            reminders.map { it.triggerAt }.sorted()
        )

        val task = fixture.taskDao.getAllTasksOnce(userId).single()
        assertEquals(stored.uuid, task.seriesUuid)
        assertEquals(file.data.tasks.single().startTime, task.startTime)
        assertEquals(file.data.tasks.single().endTime, task.endTime)
        assertEquals(file.data.tasks.single().estimatedMinutes, task.estimatedMinutes)
    }

    @Test
    fun `an imported lead time goes back on the clock without waiting for a restart`() = runTest {
        val (userId, file) = recurringBackup("arm@example.com")
        backup.import(file, replaceExisting = true)
        clearAlarms()
        assertEquals("the clock is empty again", emptySet<Int>(), armedSlots())

        backup.import(file, replaceExisting = false)

        val stored = fixture.reminderDao.getLiveForUser(userId)
        assertEquals(2, stored.size)
        assertEquals(
            "each restored reminder owns exactly its own alarm slot",
            stored.map { it.alarmRequestCode() }.toSet(),
            armedSlots()
        )
    }

    @Test
    fun `importing the same backup twice adds no second series or lead time`() = runTest {
        val (userId, file) = recurringBackup("twice@example.com")

        backup.import(file, replaceExisting = true)
        backup.import(file, replaceExisting = false)

        assertEquals(1, fixture.recurrenceDao.getAllRowsOnce(userId).size)
        assertEquals(2, fixture.reminderDao.getAllRowsOnce(userId).size)
        assertEquals(
            "an open series must not materialise a second instance on top of the restored one",
            1, fixture.taskDao.getAllRowsOnce(userId).size
        )
    }

    @Test
    fun `a replace import leaves no series or lead time of the data it replaced`() = runTest {
        val (userId, file) = recurringBackup("replaced@example.com")
        // the user keeps working after the export: a second series with its own reminder
        val laterId = fixture.addTask(userId, "Read spec", dueAt = System.currentTimeMillis() + 26 * HOUR)
        fixture.recurrenceRepository.startSeries(
            fixture.taskDao.getTaskById(laterId)!!, RepeatUnit.DAY
        )
        assertTrue(fixture.reminderRepository.applyReminders(
            fixture.taskDao.getTaskById(laterId)!!, listOf(30)
        ))

        backup.import(file, replaceExisting = true)

        assertEquals(1, fixture.recurrenceDao.getAllRowsOnce(userId).size)
        assertEquals(
            "a series that was replaced must not keep producing tasks",
            listOf("Water plants"), fixture.taskDao.getAllTasksOnce(userId).map { it.title }
        )
        assertEquals(
            listOf(15, 60),
            fixture.reminderDao.getLiveForUser(userId).map { it.offsetMinutes }.sorted()
        )
    }

    @Test
    fun `an imported series and lead time become pending so the new device uploads them`() = runTest {
        val (userId, file) = recurringBackup("pending@example.com")
        val alreadySynced = file.copy(
            data = file.data.copy(
                recurrenceRules = file.data.recurrenceRules.map { it.copy(syncState = SyncState.SYNCED) },
                reminders = file.data.reminders.map { it.copy(syncState = SyncState.SYNCED) }
            )
        )

        backup.import(alreadySynced, replaceExisting = true)

        fixture.recurrenceDao.getAllRowsOnce(userId)
            .forEach { assertEquals(SyncState.PENDING, it.syncState) }
        fixture.reminderDao.getAllRowsOnce(userId)
            .forEach { assertEquals(SyncState.PENDING, it.syncState) }
    }

    @Test
    fun `a reminder whose task is missing from the backup is refused`() = runTest {
        val (userId, file) = recurringBackup("orphan@example.com")
        val withoutTask = file.copy(data = file.data.copy(tasks = emptyList()))
        assertRefused(fixture.uriWithContent("orphan.json", Gson().toJson(withoutTask)))

        val withoutIdentity = file.copy(
            data = file.data.copy(
                tasks = file.data.tasks.map { it.copy(uuid = "") },
                reminders = file.data.reminders.map { it.copy(taskUuid = "") }
            )
        )
        assertRefused(fixture.uriWithContent("blank.json", Gson().toJson(withoutIdentity)))

        assertEquals(
            "a refused file must not have changed anything",
            1, fixture.taskDao.getAllRowsOnce(userId).size
        )
    }

    @Test
    fun `a backup with two reminders on the same minute of one task is refused`() = runTest {
        val (_, file) = recurringBackup("doubled@example.com")
        val doubled = file.copy(
            data = file.data.copy(
                reminders = file.data.reminders + file.data.reminders.first().copy(uuid = "second-row")
            )
        )

        assertRefused(fixture.uriWithContent("doubled.json", Gson().toJson(doubled)))
    }

    @Test
    fun `a task of a series the file does not carry comes back as a plain task`() = runTest {
        val (userId, file) = recurringBackup("detached@example.com")
        // A deleted series leaves the instance the user already worked on: refusing that link would
        // reject a file describing the user's own data.
        val withoutRule = file.copy(data = file.data.copy(recurrenceRules = emptyList()))
        val uri = fixture.uriWithContent("no-rule.json", Gson().toJson(withoutRule))

        val read = backup.readAndValidate(uri)
        assertEquals(1, read.data.tasks.size)
        backup.import(read, replaceExisting = true)

        val task = fixture.taskDao.getAllTasksOnce(userId).single()
        assertEquals("Water plants", task.title)
        assertNull(task.seriesUuid)
        assertEquals(0, fixture.recurrenceDao.getAllRowsOnce(userId).size)
    }

    @Test
    fun `a backup written before series and reminders existed still imports`() = runTest {
        val (userId, file) = sampleBackup()
        val legacy = JsonParser.parseString(Gson().toJson(file)).asJsonObject
        legacy.addProperty("formatVersion", "1.0")
        legacy.getAsJsonObject("data").remove("recurrenceRules")
        legacy.getAsJsonObject("data").remove("reminders")
        val uri = fixture.uriWithContent("v1_0.json", legacy.toString())

        val read = backup.readAndValidate(uri)

        assertTrue(read.data.recurrenceRules.isEmpty())
        assertTrue(read.data.reminders.isEmpty())
        assertEquals(0, backup.summarize(read).seriesCount)
        assertEquals(0, backup.summarize(read).reminderCount)

        backup.import(read, replaceExisting = true)

        assertEquals(2, fixture.taskDao.getAllTasksOnce(userId).size)
        assertEquals(0, fixture.recurrenceDao.getAllRowsOnce(userId).size)
        assertEquals(0, fixture.reminderDao.getAllRowsOnce(userId).size)
    }

    @Test
    fun `an import never steals another profile's series or lead times`() = runTest {
        val (ownerId, file) = recurringBackup("owner@example.com")
        val other = fixture.signIn(email = "other@example.com")

        backup.import(file, replaceExisting = false)

        assertEquals(
            "the owner keeps their own series and reminders",
            ownerId, fixture.recurrenceDao.getAllRowsOnce(ownerId).single().userId
        )
        assertEquals(2, fixture.reminderDao.getAllRowsOnce(ownerId).size)
        assertEquals(0, fixture.recurrenceDao.getAllRowsOnce(other.id).size)
        assertEquals(0, fixture.reminderDao.getAllRowsOnce(other.id).size)

        val imported = fixture.taskDao.getAllTasksOnce(other.id).single()
        assertEquals(other.id, imported.userId)
        assertNull("a foreign series must not be linked to this profile", imported.seriesUuid)
    }

    @Test
    fun `a destructive replace only clears the profile it imports into`() = runTest {
        val (ownerId, file) = recurringBackup("keep@example.com")
        val other = fixture.signIn(email = "victim@example.com")
        val plainId = fixture.addTask(other.id, "Unrelated task", dueAt = System.currentTimeMillis() + HOUR)
        fixture.recurrenceRepository.startSeries(
            fixture.taskDao.getTaskById(plainId)!!, RepeatUnit.MONTH
        )

        backup.import(file, replaceExisting = true)

        assertEquals(
            "the other profile's series must survive",
            1, fixture.recurrenceDao.getAllRowsOnce(ownerId).size
        )
        assertEquals(2, fixture.reminderDao.getAllRowsOnce(ownerId).size)
        assertEquals(
            "only the signed-in profile was replaced",
            listOf("Water plants"), fixture.taskDao.getAllTasksOnce(other.id).map { it.title }
        )
        assertEquals(0, fixture.recurrenceDao.getAllRowsOnce(other.id).size)
    }

    private companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
    }
}
