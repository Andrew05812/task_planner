package com.taskplanner.data.keep

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.User
import com.taskplanner.testing.AppFixture
import java.io.File
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
 * What a Keep import does to a real database, and what it must never do to one.
 *
 * Everything here runs against the same Room storage the app ships with — [AppFixture] is a file-backed database
 * with the production DAOs and the production [com.taskplanner.data.auth.ProfileSafetyCopy] — because the claims
 * being made are the expensive ones: *either every topic and task of the paste arrives, or none of them does*,
 * and *nothing else the reader owns moves*. A faked DAO cannot say either thing.
 *
 * ## The three hazards this stage answers for
 *
 *  * **A half-written import.** A paste of 382 elements is one statement about somebody's notebook, so a failure
 *    injected at either table has to leave both exactly as they were. The failure comes from a SQLite trigger —
 *    the same instrument `AccountAttachTest` uses to measure the sign-in handover — rather than from a test-only
 *    seam added to production code.
 *  * **An overwrite.** [com.taskplanner.data.local.GroupDao.upsertGroup] and [com.taskplanner.data.local.TaskDao.insertTask]
 *    are `OnConflictStrategy.REPLACE` inserts. Reached from an import, a uuid that another profile holds would
 *    mean *their row is deleted and ours lands where it was*, so the import writes through ABORT inserts that
 *    throw instead, and refuses before it ever reaches one.
 *  * **Collateral.** Sixteen tables carry a profile's data and an import writes two of them. The other fourteen
 *    are counted before and after, because "the import only added my notes" is also a claim about the reader's
 *    goals, habits and measured work.
 *
 * ## What this file cannot measure on a JVM
 *
 * The import guards a uuid twice: the plan refuses one that another profile already holds, and the write itself
 * goes through `OnConflictStrategy.ABORT` inserts so a group whose uuid appears *between* the plan and the commit
 * aborts the transaction instead of replacing anything. The first guard is asserted here. The second is asserted
 * here too, but by an injected `BEFORE INSERT` trigger rather than by a concurrent writer, because every write in
 * [AppFixture] is serialised on Room's own executors and nothing can be scheduled to land in that window from a
 * test thread. What the real race would need is a second connection writing beside the app, and this suite does
 * not have one: `KeepImportInstrumentedTest` runs the route on a device but does not run a sync beside it, so the
 * race itself is **not proven anywhere** — only the abort that would catch a group collision is. `tasks.uuid` has
 * no unique index at all, so for tasks the abort cannot fire even in principle; `KeepImportRepository`'s own note
 * says which half of the guard is a constraint and which is a read.
 */
@RunWith(RobolectricTestRunner::class)
class KeepImportTransactionTest {

    private lateinit var fixture: AppFixture
    private lateinit var importer: KeepImportRepository
    private lateinit var reader: User
    private lateinit var other: User

    /**
     * Two topics, a four-level chain, a separator row, a lowercase `[x]` and a title written twice — the cases
     * §7 and §13 name, in a fixture nobody's privacy is in. The line endings are CRLF because that is what a
     * Windows copy of a Keep export actually carries.
     */
    private val twoTopics = """
        [X] 🟥Finished topic
         [ ] open child
         [X] done child
          [ ] grandchild
        [ ]
        [ ] Second topic
         [x] lowercase done
         [ ] repeated item
         [ ] repeated item
    """.trimIndent().replace("\n", "\r\n")

    private val profileTables = listOf(
        "tasks", "categories", "task_groups", "recurrence_rules", "reminders",
        "tracking_sessions", "work_sessions", "work_interval_corrections",
        "goals", "goal_tasks", "goal_value_changes",
        "habits", "habit_checks", "habit_check_changes",
        "insights", "achievements"
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        reader = runBlocking { fixture.signIn(name = "Reader", email = "reader@example.com") }
        other = runBlocking { fixture.signInAs(User(email = "other@example.com", name = "Other")) }
        importer = KeepImportRepository(
            database = fixture.database,
            groupDao = fixture.groupDao,
            taskDao = fixture.taskDao,
            sessionRepository = fixture.sessionRepository,
            safetyCopy = fixture.safetyCopy
        )
        // `signInAs` makes the second profile the current one; the import must not follow it there.
        runBlocking { fixture.switchTo(reader) }
    }

    @After
    fun tearDown() = fixture.close()

    // ---------------------------------------------------------------- the shape that lands

    @Test
    fun everyRootLineArrivesAsItsOwnTopicAndEveryIndentedLineAsATaskOfIt() = runBlocking {
        val outcome = importer.import(twoTopics)

        assertTrue("the import reported ${outcome.javaClass.simpleName}", outcome is KeepImportResult.Imported)
        assertEquals(listOf("🟥Finished topic", "Second topic"),
            strings("SELECT name FROM task_groups WHERE user_id = ${reader.id} ORDER BY sort_order"))
        assertEquals(
            "seven titled lines in the order the paste wrote them — and the lone `[ ]` separator is not one of them",
            listOf("open child", "done child", "grandchild", "lowercase done", "repeated item", "repeated item"),
            strings("SELECT title FROM tasks WHERE user_id = ${reader.id} ORDER BY `order`")
        )
        assertEquals("two topics, not one group holding everything", 2,
            scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
    }

    @Test
    fun theTreeArrivesWithItsParentLinksAndItsTopic() = runBlocking {
        importer.import(twoTopics)

        val rows = taskRows()
        val byTitle = rows.associateBy { it.title }
        val topicUuids = strings("SELECT uuid FROM task_groups WHERE user_id = ${reader.id}").toSet()

        val topic = byTitle.getValue("open child").groupId!!
        assertTrue("a topic's direct child hangs from the topic", topic in topicUuids)
        assertNull("and has no task above it", byTitle.getValue("open child").parentUuid)
        assertEquals("a line indented under a task names that task",
            byTitle.getValue("done child").uuid, byTitle.getValue("grandchild").parentUuid)
        assertEquals("the whole subtree belongs to one topic", topic, byTitle.getValue("grandchild").groupId)
        assertEquals("a title written twice is two rows", 2, rows.count { it.title == "repeated item" })
        assertEquals("with two different identities", 2,
            rows.filter { it.title == "repeated item" }.map { it.uuid }.distinct().size)
    }

    @Test
    fun completionArrivesAsTheSourceWroteItAndNothingIsInvented() = runBlocking {
        importer.import(twoTopics)

        assertEquals(listOf("done child", "lowercase done"),
            strings("SELECT title FROM tasks WHERE user_id = ${reader.id} AND status = 'COMPLETED' ORDER BY `order`"))
        assertEquals("the export says nothing about when a thing was finished, so `completed_at` stays null " +
            "rather than recording the moment of the paste", 0,
            scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND completed_at IS NOT NULL"))
        assertEquals("the topic's own box is kept, and kept for the topic alone",
            1, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id} AND is_completed = 1"))
        assertEquals("no date, no time, no category, no series, no estimate, no reminder came from a title", 0,
            scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND (due_date IS NOT NULL OR " +
                "start_time IS NOT NULL OR end_time IS NOT NULL OR category_id IS NOT NULL OR " +
                "series_uuid IS NOT NULL OR occurrence_at IS NOT NULL OR estimated_minutes IS NOT NULL OR " +
                "archived = 1 OR is_deleted = 1)"))
        assertEquals("and every row is unsynced, so the sync pass will carry it", 0,
            scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND sync_state != 'PENDING'"))
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    fun aSecondPasteOfTheSameTextWritesNothing() = runBlocking {
        importer.import(twoTopics)
        val groups = scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}")
        val tasks = scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}")

        val again = importer.import(twoTopics)

        assertTrue("the second paste reported ${again.javaClass.simpleName}", again is KeepImportResult.AlreadyImported)
        assertEquals(groups, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(tasks, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
        assertEquals("and no second copy arrived under a different identity either", tasks,
            scalar("SELECT count(DISTINCT uuid) FROM tasks WHERE user_id = ${reader.id}"))
    }

    @Test
    fun anEditedPasteAddsOnlyTheLinesThatChanged() = runBlocking {
        importer.import(twoTopics)
        val before = storedTitles()

        importer.import(twoTopics.replace("repeated item", "repeated item, reworded"))

        val after = storedTitles()
        assertEquals("the two copies of the reworded line are new, and nothing else is", before.size + 2, after.size)
        assertEquals(
            "the lines the reader did not touch kept their identities and their rows",
            before.filterNot { it.contains("repeated") }.sorted(),
            after.filterNot { it.contains("repeated") }.sorted()
        )
    }

    // ---------------------------------------------------------------- the safety copy

    @Test
    fun aVerifiedCopyOfTheWholeDatabaseExistsBeforeTheWrite() = runBlocking {
        val before = scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}")

        val outcome = importer.import(twoTopics)

        val snapshot = (outcome as KeepImportResult.Imported).snapshot
        assertTrue("the copy is a file: ${snapshot.file}", snapshot.file.exists() && snapshot.file.length() > 0)
        assertEquals("the snapshot counts the state *before* the import — the state a reader would need back if " +
            "the import went wrong", before, snapshot.rowsOfProfile.getValue("tasks"))
        assertEquals(profileTables.sorted(), snapshot.rowsOfProfile.keys.sorted())
        assertEquals("keep-import", snapshot.reason)
    }

    @Test
    fun anImportThatCannotTakeItsCopyWritesNothing() = runBlocking {
        // The refusal is produced by production code rather than injected into it: `ProfileSafetyCopy` writes its
        // snapshot into the app's own `filesDir/safety/`, and a regular file sitting where that directory belongs
        // makes the copy fail the way a full or read-only storage would. The directory is this test sandbox's own,
        // and it is put back, so a neighbouring test that takes a real copy still finds one.
        val safety = File(fixture.context.filesDir, "safety")
        if (safety.isDirectory) safety.deleteRecursively()
        safety.writeText("not a directory")
        val outcome = try {
            importer.import(twoTopics)
        } finally {
            safety.delete()
        }

        val failure = outcome as? KeepImportResult.Failed
        assertNotNull("a refused copy came back as ${outcome.javaClass.simpleName}", failure)
        assertEquals(KeepImportFailure.SAFETY_COPY_UNAVAILABLE, failure!!.failure)
        assertTrue("the note says the copy is what stopped it: ${failure.detail}",
            failure.detail.contains("safety copy"))
        assertEquals(0, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
    }

    @Test
    fun a_failure_inside_the_task_write_rolls_the_topics_back_too() = runBlocking {
        injectTrigger("tasks")
        val outcome = try {
            importer.import(twoTopics)
        } finally {
            dropTriggers()
        }

        assertTrue("the injected failure was swallowed: $outcome", outcome is KeepImportResult.Failed)
        val failure = outcome as KeepImportResult.Failed
        assertEquals(KeepImportFailure.WRITE_FAILED, failure.failure)
        assertTrue(
            "the import failed for a reason other than the injected trigger, so this proved nothing about " +
                "atomicity: ${failure.detail}",
            failure.detail.contains("injected failure in tasks")
        )
        assertTrue("and the note names the copy a reader would go back to: ${failure.detail}",
            failure.detail.contains("snapshot="))
        assertEquals("the topics that were already inserted went back with the transaction",
            0, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
        assertEquals("foreign keys after a rolled-back import", emptyList<String>(), brokenForeignKeys())
    }

    @Test
    fun a_failure_inside_the_topic_write_leaves_no_task_behind_it() = runBlocking {
        injectTrigger("task_groups")
        val outcome = try {
            importer.import(twoTopics)
        } finally {
            dropTriggers()
        }

        assertTrue("the injected failure was swallowed: $outcome", outcome is KeepImportResult.Failed)
        assertEquals(0, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
    }

    @Test
    fun a_real_importLeavesTheDatabaseInternallyClean() = runBlocking {
        importer.import(twoTopics)

        assertEquals(emptyList<String>(), brokenForeignKeys())
        assertEquals("ok", fixture.database.openHelper.readableDatabase.query("PRAGMA integrity_check")
            .use { cursor -> if (cursor.moveToNext()) cursor.getString(0) else "no row" })
    }

    // ---------------------------------------------------------------- collisions

    @Test
    fun aTopicUuidAnotherProfileHoldsIsRefusedAndThatRowIsLeftAlone() = runBlocking {
        val planned = importer.preview(twoTopics) ?: error("the fixture has a session")
        val hijacked = planned.plan.groups.first().uuid
        val theirs = TaskGroup(uuid = hijacked, userId = other.id, name = "Somebody else's topic")
        fixture.groupDao.upsertGroup(theirs)

        val outcome = importer.import(twoTopics)

        assertTrue("a stolen identity came back as ${outcome.javaClass.simpleName}",
            outcome is KeepImportResult.Refused)
        val refused = outcome as KeepImportResult.Refused
        assertEquals(KeepImportFailure.IDENTITY_TAKEN, refused.failure)
        assertEquals(hijacked, refused.conflicts.single().uuid)
        val stillTheirs = groupRow(hijacked)
        assertEquals("the other profile's row kept its name", "Somebody else's topic", stillTheirs.name)
        assertEquals(other.id, stillTheirs.userId)
        assertEquals("and the refusal wrote nothing of ours anywhere",
            0, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
    }

    @Test
    fun aTaskUuidAnotherProfileHoldsIsRefusedAndThatRowIsLeftAlone() = runBlocking {
        val planned = importer.preview(twoTopics) ?: error("the fixture has a session")
        val hijacked = planned.plan.tasks.first().uuid
        fixture.taskDao.insertTask(
            Task(title = "Somebody else's task", description = "", userId = other.id, uuid = hijacked)
        )

        val outcome = importer.import(twoTopics)

        val refused = outcome as? KeepImportResult.Refused
        assertNotNull("a stolen identity came back as ${outcome.javaClass.simpleName}", refused)
        assertEquals(KeepImportFailure.IDENTITY_TAKEN, refused!!.failure)
        assertEquals("Somebody else's task", strings("SELECT title FROM tasks WHERE uuid = '$hijacked'").single())
        assertEquals(other.id.toInt(), scalar("SELECT user_id FROM tasks WHERE uuid = '$hijacked'"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
        assertEquals("and no topic of the refused paste was left behind to dangle",
            0, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
    }

    @Test
    fun aPasteThatOnlyPartiallyCollidesRefusesTheWholeThing() = runBlocking {
        // One topic of the paste is already the reader's own, from an earlier import; the other uuid belongs to
        // somebody else. A "skip the conflict and import the rest" import would half-fill a notebook.
        importer.import("[ ] First topic\n [ ] one")
        val foreign = importer.preview(twoTopics)!!.plan.groups.last().uuid
        fixture.groupDao.upsertGroup(TaskGroup(uuid = foreign, userId = other.id, name = "Not yours"))

        val outcome = importer.import(twoTopics)

        assertTrue("came back as ${outcome.javaClass.simpleName}", outcome is KeepImportResult.Refused)
        assertEquals("the topic that was already the reader's stayed as it was",
            1, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND title != 'one'"))
    }

    // ---------------------------------------------------------------- collateral

    @Test
    fun onlyTheTwoTablesAnImportOwnsChange() = runBlocking {
        fixture.categoryDao.insertCategory(Category(name = "Work", color = -1, icon = "ic_tag", userId = reader.id))
        fixture.addTask(reader.id, "The reader's own", groupId = null)
        fixture.addGroup(other.id, "Theirs")
        val before = profileTables.associateWith { table -> scalar("SELECT count(*) FROM $table") }

        importer.import(twoTopics)

        val after = profileTables.associateWith { table -> scalar("SELECT count(*) FROM $table") }
        assertEquals("everything except the topics and the tasks is exactly as it was",
            profileTables.filter { it != "task_groups" && it != "tasks" },
            profileTables.filter { before.getValue(it) == after.getValue(it) })
        assertTrue(before.getValue("tasks") < after.getValue("tasks"))
        assertTrue(before.getValue("task_groups") < after.getValue("task_groups"))
        assertEquals("another profile's rows are not this import's business",
            1, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${other.id}"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${other.id}"))
    }

    @Test
    fun anImportMergesIntoTheReadersListAndDoesNotReorderIt() = runBlocking {
        val own = fixture.addGroup(reader.id, "The reader's topic")
        fixture.taskDao.insertTask(Task(title = "The reader's task", description = "", userId = reader.id,
            groupId = own.uuid, order = 0))

        importer.import(twoTopics)

        val groups = strings("SELECT name FROM task_groups WHERE user_id = ${reader.id} ORDER BY sort_order")
        assertEquals("the reader's own topic is still first — an import appends, it does not arrange",
            "The reader's topic", groups.first())
        assertEquals(listOf("🟥Finished topic", "Second topic"), groups.drop(1))
        assertEquals("the reader's task keeps its place in the manual list",
            0, taskRowOf("The reader's task").order)
        assertTrue("and the imported ones start after it",
            taskRows().filter { it.title != "The reader's task" }.all { it.order > 0 })
    }

    // ---------------------------------------------------------------- the guards that come first

    @Test
    fun nothingIsImportedForAProfileThatIsNotSignedIn() = runBlocking {
        fixture.sessionRepository.endSession()

        val outcome = importer.import(twoTopics)

        assertTrue("came back as ${outcome.javaClass.simpleName}", outcome is KeepImportResult.NoProfile)
        assertEquals(0, scalar("SELECT count(*) FROM task_groups"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks"))
        assertNull("and the screen is not handed a preview to confirm", importer.preview(twoTopics))
    }

    @Test
    fun aPasteWithNothingInTheWritesNothingRatherThanCreatingAnEmptyTopic() = runBlocking {
        for (empty in listOf("", "   ", "\n\n", "[ ]\n[ ]\n[X]", "\uFEFF\r\n[ ]\r\n")) {
            val outcome = importer.import(empty)
            assertTrue("«${empty.take(12)}» came back as ${outcome.javaClass.simpleName}",
                outcome is KeepImportResult.NothingToImport)
        }
        assertEquals(0, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(0, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
        val separatorsOnly = importer.preview("[ ]\n[X]\n[ ]")!!
        assertEquals("a preview of separators-only text is empty before anybody is asked to confirm",
            0, separatorsOnly.plan.groups.size + separatorsOnly.plan.tasks.size)
        assertEquals("and text that does hold a topic is previewed as holding it",
            1, importer.preview("[ ]\n[X] some topic")!!.plan.groups.size)
    }

    @Test
    fun thePreviewPromisesTheNumbersTheImportDelivers() = runBlocking {
        val preview = importer.preview(twoTopics) ?: error("the fixture has a session")

        val outcome = importer.import(twoTopics) as KeepImportResult.Imported

        assertEquals(preview.plan.groups.size, outcome.topics)
        assertEquals(preview.plan.tasks.size, outcome.tasks)
        assertEquals(2, outcome.topics)
        assertEquals(6, outcome.tasks)
        assertEquals("the rows were derived from the fingerprint the preview named",
            preview.fingerprint, outcome.fingerprint)
        assertEquals("the document behind the plan describes the paste: two topics, six tasks, one separator",
            2, preview.document.stats.topics)
        assertEquals(6, preview.document.stats.tasks)
        assertEquals(1, preview.document.stats.separatorLines)
    }

    @Test
    fun aCorpusShapedLikeTheRealExportArrivesWhole() = runBlocking {
        // The depersonalized fixture — 439 lines, 56 topics, 382 titled elements. This is the claim the whole
        // stage is built on: not one topic, task, check mark, order or nesting level lost between the paste and
        // the database, on the shape of the file the user actually has.
        val corpus = corpusText()

        val outcome = importer.import(corpus) as KeepImportResult.Imported

        assertEquals(56, outcome.topics)
        assertEquals(326, outcome.tasks)
        assertEquals(56, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id}"))
        assertEquals(326, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id}"))
        assertEquals("34 topics carried their own `[X]`",
            34, scalar("SELECT count(*) FROM task_groups WHERE user_id = ${reader.id} AND is_completed = 1"))
        assertEquals("262 tasks arrived completed, 64 open",
            262, scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND status = 'COMPLETED'"))
        assertEquals("not one of them lost its topic", 0,
            scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND group_id IS NULL"))
        // 37, not 56: the corpus has 56 topics and 19 of them hold no line at all, so only the other 37 own a
        // row. What is being asserted is the opposite of the mistake this stage exists to stop — one group
        // holding 326 tasks — and `KeepCorpusContractTest` pins the 37/19 split to the source file.
        assertEquals("and no topic swallowed the whole paste", 37,
            scalar("SELECT count(DISTINCT group_id) FROM tasks WHERE user_id = ${reader.id}"))
        assertEquals("no row carries a completion time the export never stated", 0,
            scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND completed_at IS NOT NULL"))
        assertEquals("no empty separator reached the database as a task", 0,
            scalar("SELECT count(*) FROM tasks WHERE user_id = ${reader.id} AND TRIM(title) = ''"))
    }

    /** The same file [KeepCorpusContractTest] reads, for the case where the shape has to survive a real write. */
    private fun corpusText(): String {
        val classpath = javaClass.classLoader.getResourceAsStream(CORPUS_RESOURCE)
            ?: throw AssertionError("the corpus fixture is not on the test classpath")
        return classpath.readBytes().toString(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- helpers

    private class StoredTask(
        val uuid: String,
        val title: String,
        val groupId: String?,
        val parentUuid: String?,
        val order: Int
    )

    private suspend fun storedTitles(): List<String> = taskRows().map { it.title }

    private suspend fun taskRows(): List<StoredTask> {
        val rows = mutableListOf<StoredTask>()
        fixture.database.openHelper.readableDatabase.query(
            "SELECT uuid, title, group_id, parent_uuid, `order` FROM tasks WHERE user_id = ${reader.id}"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += StoredTask(
                    uuid = cursor.getString(0),
                    title = cursor.getString(1),
                    groupId = if (cursor.isNull(2)) null else cursor.getString(2),
                    parentUuid = if (cursor.isNull(3)) null else cursor.getString(3),
                    order = cursor.getInt(4)
                )
            }
        }
        return rows
    }

    private suspend fun taskRowOf(title: String): StoredTask =
        taskRows().firstOrNull { it.title == title } ?: error("no task titled $title")

    private class StoredGroup(val uuid: String, val name: String, val userId: Long, val completed: Boolean)

    private fun groupRow(uuid: String): StoredGroup =
        fixture.database.openHelper.readableDatabase.query(
            "SELECT name, user_id, is_completed FROM task_groups WHERE uuid = '$uuid'"
        ).use { cursor ->
            if (!cursor.moveToNext()) error("no group row for $uuid")
            StoredGroup(uuid, cursor.getString(0), cursor.getLong(1), cursor.getInt(2) == 1)
        }

    private fun scalar(sql: String): Int =
        fixture.database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (!cursor.moveToNext()) throw AssertionError("the count query returned no row: $sql")
            cursor.getInt(0)
        }

    private fun strings(sql: String): List<String> =
        fixture.database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
        }

    private fun brokenForeignKeys(): List<String> =
        fixture.database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            buildList {
                val table = cursor.getColumnIndexOrThrow("table")
                val rowid = cursor.getColumnIndexOrThrow("rowid")
                val parent = cursor.getColumnIndexOrThrow("parent")
                while (cursor.moveToNext()) {
                    add("${cursor.getString(table)}#${cursor.getLong(rowid)} -> ${cursor.getString(parent)}")
                }
            }
        }

    private fun injectTrigger(table: String) {
        fixture.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER keep_fail_${table}_INSERT BEFORE INSERT ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'injected failure in $table'); END"
        )
    }

    private fun dropTriggers() {
        listOf("tasks", "task_groups").forEach { table ->
            fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS keep_fail_${table}_INSERT")
        }
    }

    private companion object {
        const val CORPUS_RESOURCE = "keep/keep-corpus-shaped-like-the-original.txt"
    }
}
