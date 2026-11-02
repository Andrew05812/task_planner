package com.taskplanner.ui.keep

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.keep.KeepImportFailure
import com.taskplanner.data.keep.KeepImportRepository
import com.taskplanner.data.keep.KeepImportResult
import com.taskplanner.data.keep.KeepRowStatus
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.User
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.io.File
import java.util.concurrent.Executor

/**
 * The Keep import screen: what a paste turns into on the way to a database, and what it must refuse to be.
 *
 * The parser and the mapping are argued in their own tests; what belongs to this screen is the *sequence* the
 * reader is put through, and every claim below is a rule §6, §11 and §14 of the stage brief wrote down:
 *
 *  * **Nothing is written before the reader has seen the preview.** `Import` is not a button that is always
 *    live; it appears after a parse, and typing again takes it away. A paste the app has not shown is a paste
 *    the reader cannot have agreed to.
 *  * **The preview is the plan, not a picture of the text.** The rows on screen are the rows the importer would
 *    write, in the order it would write them, each labelled with what will happen to it — so "2 topics" and the
 *    list underneath cannot disagree, which is the failure §11 exists to prevent.
 *  * **A refusal is an outcome with a shape.** Conflicts, a missing safety copy and a rolled-back write are three
 *    different answers and the screen shows three different things; one bare "import failed" would hide which of
 *    them the reader has to act on.
 *
 * These run against the real importer on the real storage ([AppFixture]), because the promise being tested is
 * "what the preview promised is what the database got" and a faked importer could not be trusted to keep it.
 */
@RunWith(RobolectricTestRunner::class)
class KeepImportViewModelTest {

    private lateinit var fixture: AppFixture
    private lateinit var viewModel: KeepImportViewModel
    private lateinit var local: User

    private val paste = """
        [X] 🟥Finished topic
         [ ] open child
         [X] done child
          [ ] grandchild
        [ ]
        [ ] Second topic
         [ ] repeated item
         [ ] repeated item
    """.trimIndent()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        // viewModelScope needs Dispatchers.Main; an inline executor makes every command finish on the test
        // thread instead of waiting for a looper nobody is pumping.
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        local = runBlocking { fixture.signIn(name = "Reader", email = "reader@example.com") }
        viewModel = KeepImportViewModel(
            KeepImportRepository(
                database = fixture.database,
                groupDao = fixture.groupDao,
                taskDao = fixture.taskDao,
                sessionRepository = fixture.sessionRepository,
                safetyCopy = fixture.safetyCopy
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fixture.close()
    }

    private fun state() = viewModel.state.value
    private fun rows() = state().rows
    private fun count(sql: String): Int = runBlocking { scalar(sql) }

    private fun parse(text: String = paste) {
        viewModel.onTextChanged(text)
        viewModel.parse()
        fixture.awaitIdle()
    }

    // ---------------------------------------------------------------- the gate on the confirm

    @Test
    fun anEmptyScreenOffersNoPreviewAndNoConfirm() {
        assertEquals("", state().text)
        assertNull("nothing has been parsed yet", state().preview)
        assertFalse("and there is nothing to confirm", state().canImport)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun typingAloneDoesNotRunTheParser() {
        viewModel.onTextChanged(paste)

        assertNull("the parser is the reader's button, not every keystroke", state().preview)
        assertFalse(state().canImport)
        assertEquals(paste, state().text)
    }

    @Test
    fun parsingFillsThePreviewAndArmsTheConfirm() {
        parse()

        val preview = checkNotNull(state().preview) { "a parse leaves a preview behind" }
        assertTrue("there is something to write, so there is something to confirm", state().canImport)
        assertEquals(2, preview.document.stats.topics)
        assertEquals("the lone `[ ]` line is counted as a separator, not as a task", 1,
            preview.document.stats.separatorLines)
    }

    @Test
    fun editingTheTextAfterAPreviewTakesTheConfirmAwayAgain() {
        parse()
        assertTrue(state().canImport)

        viewModel.onTextChanged(paste + "\n [ ] a line nobody has previewed yet")

        assertFalse("the preview on screen is no longer a preview of this text", state().canImport)
        assertNull(state().preview)
        assertTrue("and the stale rows go with it", rows().isEmpty())
    }

    @Test
    fun clearingTheFieldClearsThePreviewAndTheRows() {
        parse()

        viewModel.clear()

        assertEquals("", state().text)
        assertNull(state().preview)
        assertTrue(rows().isEmpty())
        assertFalse(state().canImport)
    }

    @Test
    fun aPasteOfNothingPreviewsWithNoRowsAndNoConfirm() {
        parse("[ ]\n[X]\n[ ]")

        assertNotNull("the parse still answers, so the screen can say why it is empty", state().preview)
        assertFalse(state().canImport)
        assertTrue("no rows, because no row would be written", rows().isEmpty())
    }

    // ---------------------------------------------------------------- the tree on screen

    @Test
    fun theRowsAreTheTreeWithItsDepthAndItsOrder() {
        parse()

        assertEquals(
            listOf("🟥Finished topic", "open child", "done child", "grandchild",
                "Second topic", "repeated item", "repeated item"),
            rows().map { it.title }
        )
        assertEquals(listOf(0, 1, 1, 2, 0, 1, 1), rows().map { it.depth })
        assertEquals(listOf(true, false, false, false, true, false, false), rows().map { it.isTopic })
        assertEquals("every row of a first paste is one that will be written", 7,
            rows().count { it.status == KeepRowStatus.WILL_WRITE })
        assertEquals("and the tree keeps the nesting the source wrote: the grandchild hangs off the " +
            "nearest task above it, which is `done child`, not its sibling",
            rows().first { it.title == "done child" }.key,
            rows().first { it.title == "grandchild" }.parentKey)
    }

    @Test
    fun aTopicsOwnCheckboxIsShownBesideTheTopicNotBorrowedFromItsChildren() {
        parse()

        assertTrue("the topic line carried `[X]`", rows().first { it.isTopic }.checked == true)
        assertEquals("the open line under it stayed open", false, rows().first { it.title == "open child" }.checked)
        assertEquals(false, rows().first { it.title == "grandchild" }.checked)
        assertEquals(true, rows().first { it.title == "done child" }.checked)
    }

    @Test
    fun aCollapsedTopicHidesWhatHangsUnderItAndLeavesTheOtherTopicsVisible() {
        parse()
        val topic = rows().first { it.isTopic }
        assertTrue("topics come opened, because a preview of nothing is not a preview", topic.expanded)

        viewModel.toggleExpanded(topic.key)

        assertEquals(
            "the closed topic stays on the list — a preview whose top level can vanish is not a preview — " +
                "and its branch is what went away",
            listOf("🟥Finished topic", "Second topic", "repeated item", "repeated item"),
            rows().map { it.title }
        )
        assertEquals(3, rows().first { it.isTopic }.descendants)
        viewModel.toggleExpanded(topic.key)
        assertEquals(7, rows().size)
    }

    @Test
    fun aLineWithNoCheckboxIsShownAsUnknownRatherThanGuessed() {
        parse("[ ] topic\n this line had no box")

        val row = rows().first { it.title == "this line had no box" }
        assertNull("no box means no answer about whether it is done", row.checked)
        assertEquals(KeepRowStatus.WILL_WRITE, row.status)
        assertEquals("the parse says the same, so the warning count and the row agree",
            1, state().preview!!.document.stats.unknownStatus)
        assertEquals("and the issue is there for the warning list to name the line",
            2, state().preview!!.document.issues.single().line)
    }

    // ---------------------------------------------------------------- the confirm

    @Test
    fun confirmingWritesWhatThePreviewPromisedAndReportsTheCounts() {
        parse()
        val promisedTopics = state().preview!!.plan.groups.size
        val promisedTasks = state().preview!!.plan.tasks.size

        viewModel.confirmImport()
        fixture.awaitIdle()

        val result = state().result
        assertTrue("came back as ${result?.javaClass?.simpleName}", result is KeepImportResult.Imported)
        assertEquals(promisedTopics, (result as KeepImportResult.Imported).topics)
        assertEquals(promisedTasks, result.tasks)
        assertEquals(2, count("SELECT count(*) FROM task_groups WHERE user_id = ${local.id}"))
        assertEquals(5, count("SELECT count(*) FROM tasks WHERE user_id = ${local.id}"))
        assertFalse("and the confirm is spent: the next tap has to come from a new preview", state().canImport)
    }

    @Test
    fun repastingTheSameTextPreviewsItAsAlreadyImportedAndWritesNothingNew() {
        parse()
        viewModel.confirmImport()
        fixture.awaitIdle()
        val after = count("SELECT count(*) FROM tasks WHERE user_id = ${local.id}")

        parse()

        assertEquals("every row of this paste is already here", 0,
            rows().count { it.status == KeepRowStatus.WILL_WRITE })
        assertEquals(7, rows().count { it.status == KeepRowStatus.ALREADY_IMPORTED })
        assertEquals("the rows still name their place in the tree", 7, rows().size)
        assertFalse("and there is nothing left to confirm", state().canImport)
        assertTrue("the parse says so before anybody presses: ${state().result}",
            state().result is KeepImportResult.AlreadyImported)
        assertTrue(state().alreadyImported)

        viewModel.confirmImport()
        fixture.awaitIdle()
        assertEquals("a tap on a spent preview writes nothing", after,
            count("SELECT count(*) FROM tasks WHERE user_id = ${local.id}"))
        assertEquals(2, count("SELECT count(*) FROM task_groups WHERE user_id = ${local.id}"))
    }

    @Test
    fun aUuidHeldByAnotherProfileRefusesTheImportAndWritesNothingOfOurs() {
        parse()
        val hijacked = state().preview!!.plan.tasks.first().uuid
        val other = runBlocking { fixture.signInAs(User(email = "other@example.com", name = "Other")) }
        runBlocking {
            fixture.taskDao.insertTask(
                Task(uuid = hijacked, title = "Not yours", description = "", userId = other.id)
            )
            fixture.switchTo(local)
        }

        parse()

        val result = state().result
        assertTrue("came back as ${result?.javaClass?.simpleName}", result is KeepImportResult.Refused)
        assertEquals(1, (result as KeepImportResult.Refused).conflicts.size)
        assertEquals(hijacked, result.conflicts.single().uuid)
        assertFalse("a refused paste is not confirmable", state().canImport)
        assertEquals("and it wrote no topics of ours either", 0,
            count("SELECT count(*) FROM task_groups WHERE user_id = ${local.id}"))
        assertEquals(0, count("SELECT count(*) FROM tasks WHERE user_id = ${local.id}"))
        assertEquals("the other profile's row is untouched", "Not yours",
            strings("SELECT title FROM tasks WHERE uuid = '$hijacked'").single())
    }

    @Test
    fun aScreenWithNoProfileSaysSoRatherThanPretendingToPreview() {
        runBlocking { fixture.sessionRepository.endSession() }

        parse()

        assertTrue("came back as ${state().result?.javaClass?.simpleName}",
            state().result is KeepImportResult.NoProfile)
        assertNull("and there is nothing to look at or confirm", state().preview)
        assertFalse(state().canImport)
    }

    @Test
    fun aFailedImportKeepsThePreviewAndNamesTheKindThatFailed() {
        parse()
        val blocked = File(fixture.context.filesDir, "safety")
        if (blocked.isDirectory) blocked.deleteRecursively()
        blocked.writeText("not a directory")
        try {
            viewModel.confirmImport()
            fixture.awaitIdle()
        } finally {
            blocked.delete()
        }

        val result = state().result as? KeepImportResult.Failed
        assertNotNull("came back as ${state().result?.javaClass?.simpleName}", result)
        assertEquals(KeepImportFailure.SAFETY_COPY_UNAVAILABLE, result!!.failure)
        assertEquals("nothing was written", 0, count("SELECT count(*) FROM task_groups WHERE user_id = ${local.id}"))
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun scalar(sql: String): Int =
        fixture.database.openHelper.readableDatabase.query(sql).use { cursor ->
            if (!cursor.moveToNext()) throw AssertionError("no row: $sql")
            cursor.getInt(0)
        }

    private fun strings(sql: String): List<String> =
        fixture.database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
        }
}
