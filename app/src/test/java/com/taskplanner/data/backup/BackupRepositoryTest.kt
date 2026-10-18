package com.taskplanner.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        backup = BackupRepository(
            context,
            fixture.database,
            fixture.taskDao,
            fixture.categoryDao,
            fixture.groupDao,
            fixture.sessionRepository
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
        assertEquals("1.0", file.formatVersion)
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

        listOf("2.0", "1.1", "abc", "").forEach { version ->
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
}
