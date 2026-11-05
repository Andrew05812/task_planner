package com.taskplanner.data.hierarchy

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.repository.TaskHierarchyRepository
import com.taskplanner.testing.AppFixture
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The one delete command, read at the domain — the orchestration both screens now call instead of each keeping its own.
 *
 * Stage 16 §9 put the two deletes in [TaskHierarchyRepository.deleteTask] and §4 made the tasks screen ask before it
 * wrote, but the *sequence* — read the branch, ask if it holds one, write through the tree rules, then do the
 * follow-ups that are not database state — lived in `TasksViewModel.deleteRows`, where another screen could not reach
 * it. The category screen therefore kept its own single-row delete, and a parent pressed there was retired while its
 * children went on naming it. This class is the promise that the sequence is one thing and belongs to the domain: what
 * the tasks screen did, it still does (its own suites pin that unedited), and what the category screen now does is the
 * same operation with its own words around it.
 *
 * **What is checked here rather than at the screens.** The four answers a batch can produce, and one number that is
 * easy to get wrong: a selection holding both a parent and its own child must retire that child **once**, not report
 * the second naming of it as a row that went missing. And the follow-ups travel with every row that goes, including
 * the reminder rows the transaction retired — a delete that leaves a wakeup armed is a notification for a card nobody
 * can any more, which is the failure the single-row path never had and a lifted orchestration could silently acquire.
 */
@RunWith(RobolectricTestRunner::class)
class TaskDeleteCoordinatorTest {

    private lateinit var fixture: AppFixture
    private lateinit var app: Context
    private lateinit var coordinator: TaskDeleteCoordinator
    private var userId: Long = 0

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(app)
        Dispatchers.setMain(Executor { it.run() }.asCoroutineDispatcher())
        userId = runBlocking { fixture.signIn(email = "coordinator@example.com").id }
        coordinator = TaskDeleteCoordinator(fixture.taskHierarchyRepository, fixture.taskRepository)
    }

    @After
    fun tearDown() {
        fixture.close()
        Dispatchers.resetMain()
    }

    private fun alarms() = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms

    private fun row(
        title: String,
        parent: String? = null,
        position: Int,
        due: Long? = null,
        group: String? = null
    ): Task = runBlocking {
        val id = fixture.taskDao.insertTask(
            Task(
                title = title, description = "", uuid = title, userId = userId, parentUuid = parent,
                groupId = group, order = position, dueDate = due, syncState = SyncState.SYNCED
            )
        )
        fixture.taskDao.getTaskById(id)!!
    }

    private fun live(): List<Task> = runBlocking { fixture.taskDao.getRowsInStoredOrder(userId) }

    private fun allRows(): List<Task> = runBlocking { fixture.taskDao.getAllRowsOnce(userId) }

    private fun delete(ids: List<Long>, policy: HierarchyDeletePolicy? = null): DeleteOutcome =
        runBlocking { coordinator.delete(ids, policy) }

    @Test
    fun a_set_holding_a_branch_asks_before_it_writes_and_writes_nothing_until_the_answer_comes() {
        val parent = row("parent", position = 0)
        row("child", parent = "parent", position = 1)
        val before = live().map { listOf(it.title, it.parentUuid, it.syncState) }

        val outcome = delete(listOf(parent.id))

        assertEquals("the answer is one question about the whole set", listOf(BranchAsk(1, 1, 2)), outcome.asks)
        assertTrue("nothing went", outcome.tombstones.isEmpty())
        assertTrue("and the command says it was called off before it started", outcome.askedBeforeWriting)
        assertEquals("the tree is exactly what it was", before, live().map { listOf(it.title, it.parentUuid, it.syncState) })
    }

    @Test
    fun a_policy_answers_the_question_and_the_whole_set_goes_from_one_call() {
        val parent = row("parent", position = 0)
        val child = row("child", parent = "parent", position = 1)
        row("errands", position = 2)

        val outcome = delete(listOf(parent.id, child.id), HierarchyDeletePolicy.DELETE_SUBTREE)

        assertNull("a child the branch already took is not reported as a row that went missing", outcome.refusal)
        assertEquals("the set holds nothing to ask about", emptyList<BranchAsk>(), outcome.asks)
        assertEquals(
            "every row is named once, though the reader selected the parent and its own child",
            listOf("child", "parent"),
            outcome.tombstones.map { it.uuid }.sorted()
        )
        assertEquals("and the row beside them is the only one left to read", listOf("errands"), live().map { it.title })
    }

    @Test
    fun a_refusal_stops_its_own_row_and_not_the_rows_alongside_it() {
        val timed = row("measured", position = 0)
        val leaf = row("standalone", position = 1)
        runBlocking {
            fixture.trackingSessionDao.insert(
                com.taskplanner.data.model.TrackingSession(
                    uuid = "open-coordinator-session",
                    userId = userId,
                    taskId = timed.id,
                    taskUuid = timed.uuid,
                    taskTitle = timed.title,
                    state = com.taskplanner.data.model.TrackingState.RUNNING,
                    startedAt = fixture.timeBase.wall,
                    runningMarker = userId.toString()
                )
            )
        }

        val outcome = delete(listOf(timed.id, leaf.id), HierarchyDeletePolicy.DELETE_SUBTREE)

        assertEquals("the measured row is the one the reader has to deal with", HierarchyDeleteRefusal.MEASUREMENT_RUNNING, outcome.refusal)
        assertEquals("the row beside it went anyway, because it was asked for", listOf(leaf.id), outcome.tombstones.map { it.id })
        assertEquals("and the tree kept the row nobody could retire", listOf("measured"), live().map { it.title })
    }

    @Test
    fun every_row_that_goes_takes_its_live_reminders_with_it_and_takes_its_wakeup_off_the_clock() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val due = fixture.timeBase.wall + 86_400_000L
        val head = row("parent", position = 0, due = due)
        val child = row("child", parent = "parent", position = 1, due = due)
        runBlocking {
            fixture.reminderRepository.applyReminders(head, listOf(30))
            fixture.reminderRepository.applyReminders(child, listOf(15))
        }
        assertEquals("two rows armed, two wakeups on the clock", 2, alarms().size)

        val outcome = delete(listOf(head.id), HierarchyDeletePolicy.DELETE_SUBTREE)

        assertEquals("the branch went", listOf("child", "parent"), outcome.tombstones.map { it.uuid }.sorted())
        assertEquals(
            "and nothing is left armed for a card the reader deleted",
            emptyList<String>(),
            runBlocking { fixture.reminderDao.getLiveForTask(head.uuid) }.map { it.taskUuid }
        )
        assertEquals("the child's reminder row went with its parent's", emptyList<String>(), runBlocking { fixture.reminderDao.getLiveForTask(child.uuid) }.map { it.taskUuid })
        assertEquals("and the clock was told, which is the half that is not database state", 0, alarms().size)
    }

    @Test
    fun promoting_through_the_coordinator_rehangs_the_children_on_what_held_the_head() {
        val topic = runBlocking { fixture.addGroup(userId, "grp-co") }
        row("project", position = 0, group = topic.uuid)
        val head = row("backend", parent = "project", position = 1, group = topic.uuid)
        row("api", parent = "backend", position = 2, group = topic.uuid)

        val outcome = delete(listOf(head.id), HierarchyDeletePolicy.PROMOTE_CHILDREN)

        assertEquals("one row went", listOf("backend"), outcome.tombstones.map { it.uuid })
        assertEquals("and its child hangs from the project now", "project", allRows().first { it.uuid == "api" }.parentUuid)
        assertEquals("in the same topic it was in", topic.uuid, allRows().first { it.uuid == "api" }.groupId)
    }
}
