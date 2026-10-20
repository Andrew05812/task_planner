package com.taskplanner.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.model.Goal
import com.taskplanner.data.model.GoalType
import com.taskplanner.data.model.GoalValueChange
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.User
import com.taskplanner.data.model.WorkSession
import com.taskplanner.data.model.WorkSource
import com.taskplanner.data.tracking.WorkTimeMath
import com.taskplanner.testing.AppFixture
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Goals over real storage: the four kinds of aim, the figures that recompute themselves, and the history
 * of the one number the user chose.
 *
 * What is checked here is not that SQLite can count — `WorkStatisticsDaoTest` holds each aggregate up
 * against a hand-folded oracle — but that a goal is *only* those answers, for the window the goal names,
 * and that nothing in this feature stores a figure that could afterwards disagree with them. The three
 * cases §5 calls out (a task completed, a completion cancelled, a row deleted) are therefore tested
 * through the watch a screen holds rather than through a fresh read: the point is that the number moves by
 * itself.
 *
 * Every test anchors its reads on one Wednesday and parks the fixture's clock at midday of it, so a
 * window's two bounds and "is it late yet" mean the same instants in the test and in the repository — and
 * a run that happens near a real midnight cannot disagree with itself.
 */
@RunWith(RobolectricTestRunner::class)
class GoalRepositoryTest {

    private lateinit var fixture: AppFixture
    private val repository get() = fixture.goalRepository
    private val goalDao get() = fixture.goalDao
    private val zone: ZoneId = CalendarMath.systemZone()
    private var userId: Long = -1L

    private val monday: LocalDate = LocalDate.of(2026, 6, 1)
    private val tuesday: LocalDate = LocalDate.of(2026, 6, 2)
    private val today: LocalDate = LocalDate.of(2026, 6, 3)
    private val mayDay: LocalDate = LocalDate.of(2026, 5, 20)

    private fun at(day: LocalDate, hour: Int = 10, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
    }

    /**
     * Signs the profile in and parks the clock at midday Wednesday, so the day the screen reads as
     * "today" is the day the test names.
     */
    private suspend fun seed(): User {
        val user = fixture.signIn()
        userId = user.id
        fixture.timeBase.wall = at(today, 12, 0)
        return user
    }

    private suspend fun task(
        title: String,
        status: TaskStatus = TaskStatus.TODO,
        completedAt: Long? = null,
        forProfile: Long = userId
    ): Task {
        val id = fixture.taskDao.insertTask(
            Task(
                uuid = "task-$title", title = title, description = "", userId = forProfile,
                status = status, completedAt = completedAt,
                createdAt = at(monday, 8, 0), updatedAt = at(today, 8, 0)
            )
        )
        return fixture.taskDao.getTaskById(id)!!
    }

    /** A closed stretch, measured on [day] — the only kind a time goal is allowed to count. */
    private suspend fun work(uuid: String, on: Task, day: LocalDate, seconds: Long) =
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = uuid,
                userId = on.userId,
                trackingSessionUuid = "session-$uuid",
                taskId = on.id,
                taskUuid = on.uuid,
                startedAt = at(day),
                endedAt = at(day) + seconds * 1_000L,
                durationSeconds = seconds,
                localDay = day.toEpochDay(),
                utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(at(day)),
                source = WorkSource.TRACKER
            )
        )

    /** A stretch still being measured: it has a start, no end, and no length anybody could add. */
    private suspend fun openWork(uuid: String, on: Task, day: LocalDate) =
        fixture.workSessionDao.insert(
            WorkSession(
                uuid = uuid,
                userId = on.userId,
                trackingSessionUuid = "session-$uuid",
                taskId = on.id,
                taskUuid = on.uuid,
                startedAt = at(day),
                endedAt = null,
                durationSeconds = null,
                openMarker = uuid,
                localDay = day.toEpochDay(),
                utcOffsetMinutes = WorkTimeMath.utcOffsetMinutesOf(at(day)),
                source = WorkSource.TRACKER
            )
        )

    /**
     * Marks [task] finished by the app's own path, then puts the completion instant on [day].
     *
     * `archiveTask` stamps `completed_at` with the device clock, which is correct in production and lands
     * in October for a test whose window is a June week, so a test that cares *when* a completion happened
     * moves the instant itself after the status has landed. The write such a test then issues is still an
     * ordinary update of the `tasks` row, which is what the goal's aggregate is watched on.
     */
    private suspend fun finishedOn(task: Task, day: LocalDate) {
        fixture.taskRepository.completeAndArchive(task.id)
        val stored = fixture.taskDao.getTaskById(task.id)!!
        fixture.taskDao.updateTask(stored.copy(completedAt = at(day, 9, 0)))
    }

    private suspend fun saved(result: GoalWriteResult): String = when (result) {
        is GoalWriteResult.Saved -> result.goalUuid
        is GoalWriteResult.Rejected -> throw AssertionError("expected a saved goal, got ${result.reason}")
    }

    private fun rejected(result: GoalWriteResult): GoalRejection = when (result) {
        is GoalWriteResult.Saved -> throw AssertionError("expected a refusal, got a Saved goal")
        is GoalWriteResult.Rejected -> result.reason
    }

    /** Creates a goal and hands back the stored row, so a test reads figures off what is on disk. */
    private suspend fun goal(
        title: String = "aim",
        type: GoalType = GoalType.TASK_COUNT,
        target: Long = 0L,
        from: LocalDate? = null,
        to: LocalDate? = null,
        deadlineAt: Long? = null,
        tasks: List<String> = emptyList()
    ): Goal {
        val uuid = saved(
            repository.create(
                title = title,
                type = type,
                targetValue = target,
                windowFromDay = from?.toEpochDay(),
                windowToDay = to?.toEpochDay(),
                deadlineAt = deadlineAt,
                taskUuids = tasks
            )
        )
        return repository.goal(uuid)!!
    }

    /** The whole edit, with one field free to differ from what [goal] stored. */
    private suspend fun edit(
        stored: Goal,
        title: String = stored.title,
        type: GoalType = stored.type,
        target: Long = stored.targetValue,
        from: LocalDate? = stored.windowFromDay?.let { LocalDate.ofEpochDay(it) },
        to: LocalDate? = stored.windowToDay?.let { LocalDate.ofEpochDay(it) },
        deadlineAt: Long? = stored.deadlineAt,
        reason: String? = null
    ): GoalWriteResult = repository.edit(
        goalUuid = stored.uuid,
        title = title,
        type = type,
        targetValue = target,
        windowFromDay = from?.toEpochDay(),
        windowToDay = to?.toEpochDay(),
        deadlineAt = deadlineAt,
        reason = reason
    )

    /**
     * The frames one watch of [source] drew around [write], up to and including the one that matches a
     * plain read of the same storage.
     *
     * The write is issued only after the watch has answered once, which is what makes this the proof it
     * claims to be: the figure moved because the screen was watching the aggregates, not because anything
     * re-subscribed or polled. A write can move more than one table — completing a task touches the task
     * row and the tracking rows — so the watch may draw several frames, and a test names the first and the
     * last rather than counting the ones in between. [expected] is asked of the storage directly, so a
     * watch that stopped drawing cannot pass by agreeing with itself.
     */
    private suspend fun <T : Any> rebuiltBy(
        source: Flow<T>,
        write: suspend () -> Unit,
        expected: suspend () -> T
    ): List<T> = coroutineScope {
        val frames = CopyOnWriteArrayList<T>()
        val watching = launch { source.collect { frames.add(it) } }
        withTimeout(TIMEOUT_MILLIS) { while (frames.isEmpty()) delay(POLL_MILLIS) }
        write()
        val wanted = expected()
        withTimeout(TIMEOUT_MILLIS) { while (frames.lastOrNull() != wanted) delay(POLL_MILLIS) }
        watching.cancel()
        frames.toList()
    }

    /** The first figure of the profile's one open goal, as the screen's own read gives it. */
    private suspend fun countedOf(uuid: String): Long =
        repository.progressOf(repository.goal(uuid)!!, onDay = today, zone = zone).counted

    // ------------------------------------------------------------ the four kinds of aim

    @Test
    fun `a count goal counts the tasks finished inside its window and no others`() = runBlocking {
        seed()
        task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        task("plants", TaskStatus.COMPLETED, completedAt = at(mayDay, 9, 0))
        // A row an import spelled completed but gave no instant for: nothing can be said about when it
        // happened, so it belongs to no window and is counted by none.
        task("legacy", TaskStatus.COMPLETED, completedAt = null)
        task("mail")

        val week = repository.progressOf(
            goal(title = "three a week", target = 3L, from = monday, to = today),
            onDay = today,
            zone = zone
        )
        assertEquals(1L, week.counted)
        assertEquals(3L, week.target)
        assertFalse(week.reached)
        assertEquals(2L, week.remaining)
        assertEquals(1f / 3f, week.fraction!!, 1e-6f)

        // The same row with both ends open reaches the whole account, which is the only difference the
        // type is made of: same column, same rule, different bounds.
        val ever = repository.progressOf(
            week.goal.copy(windowFromDay = null, windowToDay = null), onDay = today, zone = zone
        )
        assertEquals(2L, ever.counted)
    }

    @Test
    fun `a time goal counts closed stretches inside its window and never the one still running`() =
        runBlocking {
            seed()
            val report = task("report")
            val plants = task("plants")
            work("i1", report, monday, 1_800L)
            work("i2", report, monday, 1_200L)
            // Wednesday is the window's inclusive end, so the work measured on it has to be in the figure.
            work("i3", plants, today, 3_600L)
            work("i4", report, mayDay, 60L)
            openWork("i5", report, today)

            val progress = repository.progressOf(
                goal(
                    title = "two hours",
                    type = GoalType.TIME_SPENT,
                    target = 7_200L,
                    from = monday,
                    to = today
                ),
                onDay = today,
                zone = zone
            )

            // 30 + 20 minutes on Monday and an hour on Wednesday; May's minute is outside the window and
            // the open stretch has no length to add.
            assertEquals(6_600L, progress.counted)
            assertEquals(7_200L, progress.target)
            assertEquals(600L, progress.remaining)
            assertFalse("an unfinished stretch is not measured work", progress.reached)
        }

    @Test
    fun `a set goal is measured by the tasks it names and a row that is gone stays in the denominator`() =
        runBlocking {
            seed()
            val report = task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
            val plants = task("plants", TaskStatus.COMPLETED, completedAt = at(mayDay, 9, 0))
            val mail = task("mail")

            val set = goal(
                title = "these three",
                type = GoalType.TASK_SET,
                tasks = listOf(report.uuid, plants.uuid, mail.uuid)
            )
            val progress = repository.progressOf(set, onDay = today, zone = zone)
            // No figure was typed, so "2 of 3" is drawn from the set itself rather than from a number.
            assertEquals(2L, progress.counted)
            assertEquals(3, progress.linked)
            assertEquals(2, progress.completedLinks)
            assertEquals(3L, progress.target)
            assertEquals(0, progress.missing)

            // Dropping a row physically takes the task away, not the intention: the user still asked for
            // three things, and the screen has to be able to say that one of them is no longer there.
            fixture.taskRepository.hardDeleteTasks(listOf(mail.id))
            val stored = repository.goal(set.uuid)!!
            val after = repository.progressOf(stored, onDay = today, zone = zone)
            assertEquals(2L, after.counted)
            assertEquals(3, after.linked)
            assertEquals(1, after.missing)
            assertEquals(3L, after.target)

            // Unlinking is the only thing that shrinks the denominator: removing the name takes both the
            // request and its missing-row note away, and nothing else does.
            repository.unlink(set.uuid, plants.uuid)
            val trimmed = repository.progressOf(stored, onDay = today, zone = zone)
            assertEquals("one name removed, of the three asked for", 2, trimmed.linked)
            assertEquals(2L, trimmed.target)
            assertEquals(1L, trimmed.counted)
            assertEquals("the physically gone task is still missing from the set", 1, trimmed.missing)
        }

    @Test
    fun `a deadline goal is late the day after its date and reached when everything it names is done`() =
        runBlocking {
            seed()
            val report = task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
            val mail = task("mail")

            val deadline = goal(
                title = "before Tuesday",
                type = GoalType.DEADLINE,
                deadlineAt = at(tuesday, 18, 0),
                tasks = listOf(report.uuid, mail.uuid)
            )
            val behind = repository.progressOf(deadline, onDay = today, zone = zone)
            assertEquals(1L, behind.counted)
            assertEquals(2L, behind.target)
            assertFalse(behind.reached)
            assertTrue("Tuesday is behind us and the set is not finished", behind.late)

            // The same goal, finished: reaching it stops the lateness by itself, because the story now
            // says the work got done rather than that it is still outstanding.
            fixture.taskRepository.completeAndArchive(mail.id)
            val done = repository.progressOf(repository.goal(deadline.uuid)!!, onDay = today, zone = zone)
            assertEquals(2L, done.counted)
            assertTrue(done.reached)
            assertFalse(done.late)
        }

    @Test
    fun `a date still in front of the screen's today is not late whatever hour it is`() = runBlocking {
        seed()
        task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val mail = task("mail")

        // "Due Wednesday" is not late at 23:00 on Wednesday: the comparison is on the day, and the day is
        // the thing the user reads on the card.
        val dueToday = goal(
            title = "by tonight",
            type = GoalType.DEADLINE,
            deadlineAt = at(today, 23, 0),
            tasks = listOf(mail.uuid)
        )
        assertFalse(repository.progressOf(dueToday, onDay = today, zone = zone).late)
    }

    @Test
    fun `a goal with no figure aimed at is not achieved and has no share to draw`() = runBlocking {
        seed()
        task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))

        val count = repository.progressOf(goal(target = 0L), onDay = today, zone = zone)
        assertEquals(1L, count.counted)
        assertEquals(0L, count.target)
        assertFalse("a goal nobody set a figure for is not finished work", count.reached)
        assertNull(count.fraction)
        assertNull(count.remaining)

        // A set goal with no links has nothing to divide by either, so it reads the same way rather than
        // as a card already achieved.
        val empty = repository.progressOf(
            goal(title = "empty set", type = GoalType.TASK_SET), onDay = today, zone = zone
        )
        assertEquals(0L, empty.target)
        assertFalse(empty.reached)
        assertNull(empty.fraction)
    }

    // ------------------------------------------------------------ borrowing tasks, once each

    @Test
    fun `naming the same task twice asks for it once, in the order the user put it`() = runBlocking {
        seed()
        val first = task("first")
        val second = task("second")
        val third = task("third")
        val set = goal(title = "the trio", type = GoalType.TASK_SET)

        // One parked clock, three taps: `added_at` still has to carry an order, or the screen lists the
        // set in whatever sequence SQLite reached the rows.
        listOf(first, second, third).forEach { repository.link(set.uuid, it.uuid) }
        assertEquals(listOf(first.uuid, second.uuid, third.uuid), repository.linkedTaskUuids(set.uuid))

        repository.link(set.uuid, first.uuid)
        val progress = repository.progressOf(repository.goal(set.uuid)!!, onDay = today, zone = zone)
        assertEquals("the same task named twice is one request", 3, progress.linked)
        assertEquals(3L, progress.target)
    }

    @Test
    fun `a goal cannot name another profile's task`() = runBlocking {
        val mine = seed()
        val own = task("mine-task")
        val theirs = fixture.signIn(name = "Other", email = "other@example.com")
        val foreign = task("theirs-task", forProfile = theirs.id)

        // Back to the first profile: `foreign` is a row that exists, is open and is not theirs. A goal that
        // silently counted it would be wrong in exactly the way the screen cannot see, so the whole create
        // is refused before its first row is written.
        fixture.switchTo(mine)
        assertEquals(
            GoalRejection.TASK_NOT_IN_PROFILE,
            rejected(
                repository.create(
                    title = "borrowed", type = GoalType.TASK_SET, targetValue = 0L,
                    taskUuids = listOf(foreign.uuid)
                )
            )
        )
        assertEquals(
            "a goal refused for its set writes no row at all",
            emptyList<Any>(),
            goalDao.getAllForUser(mine.id)
        )

        // A create that got past the check cannot be followed by a link that does not.
        val set = goal(title = "mine", type = GoalType.TASK_SET, tasks = listOf(own.uuid))
        assertEquals(GoalRejection.TASK_NOT_IN_PROFILE, rejected(repository.link(set.uuid, foreign.uuid)))
        assertEquals(listOf(own.uuid), repository.linkedTaskUuids(set.uuid))
    }

    @Test
    fun `two profiles holding the same task uuid each name their own row`() = runBlocking {
        val mine = seed()
        // `tasks.uuid` carries no unique index, because an import can put one uuid in two accounts. A
        // goal's set is joined by uuid, so the profile has to be part of that join or one account's
        // completion would be counted as another's.
        val shared = "task-shared"
        fixture.taskDao.insertTask(
            Task(
                uuid = shared, title = "mine", description = "", userId = mine.id,
                status = TaskStatus.COMPLETED, completedAt = at(today, 9, 0),
                createdAt = at(monday), updatedAt = at(today)
            )
        )
        val other = fixture.signIn(name = "Other", email = "other@example.com")
        fixture.taskDao.insertTask(
            Task(
                uuid = shared, title = "theirs", description = "", userId = other.id,
                status = TaskStatus.TODO, createdAt = at(monday), updatedAt = at(today)
            )
        )
        val uuid = saved(
            repository.create(title = "their set", type = GoalType.TASK_SET, taskUuids = listOf(shared))
        )

        val progress = repository.progressOf(repository.goal(uuid)!!, onDay = today, zone = zone)
        assertEquals(1, progress.linked)
        assertEquals("the other account's finished row is not this goal's progress", 0, progress.completedLinks)

        fixture.switchTo(mine)
        val mineUuid = saved(
            repository.create(title = "my set", type = GoalType.TASK_SET, taskUuids = listOf(shared))
        )
        val mineProgress = repository.progressOf(repository.goal(mineUuid)!!, onDay = today, zone = zone)
        assertEquals(1, mineProgress.completedLinks)
        assertEquals("one row's completion moves one goal, not both", 0L, progress.counted)
    }

    // ------------------------------------------------------------ the figures that move themselves

    @Test
    fun `completing a task moves the count goal's figure with no reload`() = runBlocking {
        seed()
        val mail = task("mail")
        val uuid = goal(title = "three a week", target = 3L, from = monday, to = today).uuid

        val frames = rebuiltBy(
            repository.observeOpenGoals(zone).map { it.single().progress.counted },
            write = { finishedOn(mail, today) },
            expected = { countedOf(uuid) }
        )

        assertEquals(0L, frames.first())
        assertEquals("the watch drew the completion, nothing re-read it", 1L, frames.last())
    }

    @Test
    fun `cancelling a completion takes its share of the goal back`() = runBlocking {
        seed()
        val report = task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val uuid = goal(title = "one this week", target = 1L, from = monday, to = today).uuid

        val frames = rebuiltBy(
            repository.observeOpenGoals(zone).map { it.single().progress.reached },
            // Restoring the task is the app's own way back to open work: status and completion instant
            // both move, and the goal has to follow them rather than keep the number it showed.
            write = { fixture.taskRepository.restoreTask(report.id) },
            expected = { repository.progressOf(repository.goal(uuid)!!, onDay = today, zone = zone).reached }
        )

        assertTrue(frames.first())
        assertFalse("a goal does not stay achieved because it once was", frames.last())
        assertEquals(0L, countedOf(uuid))
    }

    @Test
    fun `deleting a task takes its share of the goal with it`() = runBlocking {
        seed()
        val report = task("report")
        val plants = task("plants")
        fixture.taskRepository.completeAndArchive(report.id)
        fixture.taskRepository.completeAndArchive(plants.id)
        val set = goal(title = "these two", type = GoalType.TASK_SET, tasks = listOf(report.uuid, plants.uuid))

        val frames = rebuiltBy(
            repository.observeOpenGoals(zone).map { it.single().progress.completedLinks },
            write = { fixture.taskRepository.deleteTask(report) },
            expected = {
                repository.progressOf(repository.goal(set.uuid)!!, onDay = today, zone = zone).completedLinks
            }
        )

        assertEquals(2, frames.first())
        assertEquals("a tombstone is not a finished task", 1, frames.last())
        val after = repository.progressOf(repository.goal(set.uuid)!!, onDay = today, zone = zone)
        assertEquals("the aim still asks for both things", 2, after.linked)
        assertEquals("the row is only hidden, so nothing of the set is missing", 0, after.missing)
    }

    @Test
    fun `a time goal's figure moves when a stretch closes`() = runBlocking {
        seed()
        val report = task("report")
        val uuid = goal(
            title = "an hour", type = GoalType.TIME_SPENT, target = 3_600L, from = monday, to = today
        ).uuid

        val frames = rebuiltBy(
            repository.observeOpenGoals(zone).map { it.single().progress.counted },
            write = { work("i-new", report, today, 900L) },
            expected = { countedOf(uuid) }
        )

        assertEquals(0L, frames.first())
        assertEquals(900L, frames.last())
        assertEquals(
            2_700L,
            repository.progressOf(repository.goal(uuid)!!, onDay = today, zone = zone).remaining
        )
    }

    @Test
    fun `a window that holds one goal's completion may not hold another's`() = runBlocking {
        seed()
        val report = task("report")
        val thisWeek = goal(title = "this week", target = 5L, from = monday, to = today).uuid
        val mayOnly = goal(title = "in May", target = 5L, from = mayDay, to = mayDay.plusDays(3)).uuid

        val frames = rebuiltBy(
            repository.observeOpenGoals(zone).map { goals ->
                goals.associate { it.progress.goal.uuid to it.progress.counted }
            },
            write = { finishedOn(report, today) },
            expected = { mapOf(thisWeek to countedOf(thisWeek), mayOnly to countedOf(mayOnly)) }
        )

        assertEquals(mapOf(thisWeek to 0L, mayOnly to 0L), frames.first())
        // One write, one screen, two windows: the June goal moved and the May goal stayed where it was,
        // which is why every goal is asked of its own aggregate rather than the screen as a whole.
        assertEquals(mapOf(thisWeek to 1L, mayOnly to 0L), frames.last())
    }

    @Test
    fun `a goal that gains a name reports the new denominator to the watch`() = runBlocking {
        seed()
        val report = task("report")
        val mail = task("mail")
        val uuid = goal(title = "aim", type = GoalType.TASK_SET, tasks = listOf(report.uuid)).uuid

        val frames = rebuiltBy(
            repository.observeOpenGoals(zone).map { it.single().progress.linked },
            write = { repository.link(uuid, mail.uuid) },
            expected = {
                repository.progressOf(repository.goal(uuid)!!, onDay = today, zone = zone).linked
            }
        )

        assertEquals(1, frames.first())
        assertEquals("the card's own denominator moved with the link", 2, frames.last())
    }

    @Test
    fun `a screen with no goals is told there are none`() = runBlocking {
        seed()
        assertEquals(emptyList<Any>(), repository.observeOpenGoals(zone).first())
        assertEquals(emptyList<Any>(), repository.observeArchivedGoals(zone).first())

        // …and still an empty list once the last one goes, rather than a watch left waiting for a frame.
        val uuid = goal(title = "one aim").uuid
        assertEquals(1, repository.observeOpenGoals(zone).first().size)
        repository.remove(uuid)
        assertEquals(emptyList<Any>(), repository.observeOpenGoals(zone).first())
    }

    @Test
    fun `the open list carries each goal's own figures and only this profile's rows`() = runBlocking {
        val mine = seed()
        task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val week = goal(title = "my week", target = 3L, from = monday, to = today)
        val set = goal(title = "my set", type = GoalType.TASK_SET, target = 2L, tasks = listOf(task("mail").uuid))

        fixture.signIn(name = "Second", email = "second@example.com")
        goal(title = "their aim", target = 9L)

        fixture.switchTo(mine)
        val open = repository.observeOpenGoals(zone).first().associate { it.goal.title to it.progress }
        assertEquals(setOf("my week", "my set"), open.keys)
        assertEquals(1L, open.getValue("my week").counted)
        assertEquals(3L, open.getValue("my week").target)
        assertEquals(0L, open.getValue("my set").counted)
        assertEquals(1, open.getValue("my set").linked)
        assertEquals(set.uuid, open.getValue("my set").goal.uuid)
        assertEquals(week.uuid, open.getValue("my week").goal.uuid)
    }

    // ------------------------------------------------------------ the history of the chosen figure

    @Test
    fun `editing records only the field that moved`() = runBlocking {
        seed()
        val stored = goal(title = "three a week", target = 10L, from = monday, to = today)

        repository.edit(
            goalUuid = stored.uuid,
            title = "three a week",
            type = stored.type,
            targetValue = 20L,
            windowFromDay = monday.toEpochDay(),
            windowToDay = today.toEpochDay(),
            deadlineAt = null
        )

        val history = repository.valueChangesOf(stored.uuid)
        assertEquals(1, history.size)
        assertEquals(GoalValueChange.FIELD_TARGET, history.single().field)
        assertEquals("10", history.single().oldValue)
        assertEquals("20", history.single().newValue)
        assertEquals(20L, repository.goal(stored.uuid)!!.targetValue)
    }

    @Test
    fun `saving the same figures again writes no history row`() = runBlocking {
        seed()
        val stored = goal(title = "three a week", target = 10L, from = monday, to = today)
        edit(stored, target = 20L)

        val before = repository.valueChangesOf(stored.uuid).size
        edit(stored.copy(targetValue = 20L), reason = "just pressed save")
        assertEquals(
            "a history padded with no-ops cannot be read",
            before,
            repository.valueChangesOf(stored.uuid).size
        )
    }

    @Test
    fun `changing the type is history even when the figure stayed`() = runBlocking {
        seed()
        val stored = goal(title = "aim", type = GoalType.TIME_SPENT, target = 42L)
        edit(stored, type = GoalType.TASK_COUNT, reason = "hours were the wrong measure")

        // The number means something else now, which is exactly why the row belongs in the history: a
        // goal that counted 42 hours and now counts 42 finishes changed its aim while changing nothing the
        // editor would call a figure.
        val change = repository.valueChangesOf(stored.uuid).single()
        assertEquals(GoalValueChange.FIELD_TYPE, change.field)
        assertEquals(GoalType.TIME_SPENT.name, change.oldValue)
        assertEquals(GoalType.TASK_COUNT.name, change.newValue)
        assertEquals("hours were the wrong measure", change.reason)
    }

    @Test
    fun `a window and a date are stored as the days the screen prints`() = runBlocking {
        seed()
        val stored = goal(title = "aim", target = 1L, from = monday, to = today)

        edit(stored, from = null, to = today, deadlineAt = at(tuesday, 18, 0))

        val byField = repository.valueChangesOf(stored.uuid).associate { it.field to it }
        assertEquals("$monday..$today", byField.getValue(GoalValueChange.FIELD_WINDOW).oldValue)
        assertEquals("open..$today", byField.getValue(GoalValueChange.FIELD_WINDOW).newValue)
        assertNull("the goal had no date before this edit", byField.getValue(GoalValueChange.FIELD_DEADLINE).oldValue)
        assertEquals(tuesday.toString(), byField.getValue(GoalValueChange.FIELD_DEADLINE).newValue)
    }

    @Test
    fun `one edit that moves the whole row writes a row per field and carries the reason on each`() =
        runBlocking {
            seed()
            val stored = goal(title = "old name", target = 10L, from = monday, to = today)

            repository.edit(
                goalUuid = stored.uuid,
                title = "new name",
                type = GoalType.TASK_SET,
                targetValue = 4L,
                windowFromDay = tuesday.toEpochDay(),
                windowToDay = today.toEpochDay(),
                deadlineAt = at(today, 20, 0),
                reason = "the week shrank"
            )

            val rows = repository.valueChangesOf(stored.uuid)
            assertEquals(
                listOf(
                    GoalValueChange.FIELD_TITLE,
                    GoalValueChange.FIELD_TYPE,
                    GoalValueChange.FIELD_TARGET,
                    GoalValueChange.FIELD_WINDOW,
                    GoalValueChange.FIELD_DEADLINE
                ),
                rows.map { it.field }
            )
            assertTrue(rows.all { it.reason == "the week shrank" })
            assertEquals("old name", rows.first().oldValue)
            assertEquals("new name", rows.first().newValue)

            // A later edit with no reason of its own does not borrow the first one's. The clock moves
            // because two edits in one millisecond would have no order for the story to be read in.
            fixture.timeBase.advance(60_000L)
            edit(
                stored.copy(
                    title = "new name", type = GoalType.TASK_SET, targetValue = 4L,
                    windowFromDay = tuesday.toEpochDay(), windowToDay = today.toEpochDay(),
                    deadlineAt = at(today, 20, 0)
                ),
                target = 6L
            )

            val after = repository.valueChangesOf(stored.uuid)
            assertEquals(6, after.size)
            assertTrue("the first edit's reason stayed on the rows it wrote", after.take(5).all { it.reason == "the week shrank" })
            val last = after.last()
            assertEquals(GoalValueChange.FIELD_TARGET, last.field)
            assertEquals("4", last.oldValue)
            assertEquals("6", last.newValue)
            assertNull("nothing was invented for the second edit", last.reason)
        }

    @Test
    fun `a reason belongs to the edit that gave it and not to the one after`() = runBlocking {
        seed()
        val stored = goal(title = "aim", target = 10L)
        edit(stored, target = 20L, reason = "the week shrank")
        fixture.timeBase.advance(60_000L)
        edit(stored.copy(targetValue = 20L), target = 30L)

        val rows = repository.valueChangesOf(stored.uuid)
        assertEquals(2, rows.size)
        assertEquals("the week shrank", rows.first().reason)
        assertNull("nothing was invented for the second edit", rows.last().reason)
        // The story reads as a chain: the figure the user started with, the one the first edit left, and
        // the one the second edit asked for.
        assertEquals("10", rows.first().oldValue)
        assertEquals("20", rows.first().newValue)
        assertEquals("20", rows.last().oldValue)
        assertEquals("30", rows.last().newValue)
    }

    @Test
    fun `a goal cannot be reached or changed by the profile that does not own it`() = runBlocking {
        val mine = seed()
        val own = task("report")
        val stored = goal(title = "mine", target = 10L, tasks = listOf(own.uuid))
        edit(stored, target = 20L)

        val other = fixture.signIn(name = "Other", email = "other@example.com")
        val theirs = goal(
            title = "theirs", target = 1L, tasks = listOf(task("mail", forProfile = other.id).uuid)
        )

        assertEquals(emptyList<Any>(), repository.valueChangesOf(stored.uuid))
        assertNull(repository.goal(stored.uuid))
        assertEquals(
            GoalRejection.NOT_FOUND,
            rejected(edit(stored, title = "renamed", target = 99L))
        )
        assertEquals(GoalRejection.NOT_FOUND, rejected(repository.link(stored.uuid, theirs.uuid)))
        assertEquals(GoalRejection.NOT_FOUND, rejected(repository.archive(stored.uuid)))
        assertEquals(GoalRejection.NOT_FOUND, rejected(repository.remove(stored.uuid)))
        assertEquals(listOf(theirs.uuid), repository.observeOpenGoals(zone).first().map { it.goal.uuid })
        assertEquals(emptyList<Any>(), repository.observeTasksOf(stored.uuid).first())

        // The owner's row is exactly as it was left, story included.
        fixture.switchTo(mine)
        assertEquals(20L, repository.goal(stored.uuid)!!.targetValue)
        assertEquals(listOf(GoalValueChange.FIELD_TARGET), repository.valueChangesOf(stored.uuid).map { it.field })
        assertEquals(listOf(own.uuid), repository.linkedTaskUuids(stored.uuid))
    }

    // ------------------------------------------------------------ refusals, archiving, deletion

    @Test
    fun `an impossible goal is refused and nothing is written`() = runBlocking {
        seed()

        assertEquals(GoalRejection.BLANK_TITLE, rejected(repository.create("  ", GoalType.TASK_COUNT, 1L)))
        assertEquals(GoalRejection.NEGATIVE_TARGET, rejected(repository.create("aim", GoalType.TASK_COUNT, -1L)))
        assertEquals(
            GoalRejection.WINDOW_ENDS_BEFORE_START,
            rejected(
                repository.create(
                    "aim", GoalType.TASK_COUNT, 1L, today.toEpochDay(), monday.toEpochDay()
                )
            )
        )
        assertEquals(
            GoalRejection.DEADLINE_WITHOUT_A_DATE,
            rejected(repository.create("aim", GoalType.DEADLINE, 1L))
        )
        assertEquals(
            GoalRejection.NOT_FOUND,
            rejected(repository.edit("no-such-goal", "aim", GoalType.TASK_COUNT, 1L, null, null, null))
        )
        assertEquals(emptyList<Any>(), goalDao.getAllForUser(userId))

        // A refused edit leaves the row's own figures alone rather than writing the half that passed.
        val stored = goal(title = "kept", target = 7L)
        assertEquals(GoalRejection.NEGATIVE_TARGET, rejected(edit(stored, target = -3L)))
        assertEquals(7L, repository.goal(stored.uuid)!!.targetValue)
        assertEquals(emptyList<Any>(), repository.valueChangesOf(stored.uuid))
    }

    @Test
    fun `with no profile signed in nothing is written and every figure is zero`() = runBlocking {
        // No sign-in at all: the state a screen reaches before the first profile exists, and the one a
        // sign-out leaves behind.
        assertEquals(GoalRejection.NO_PROFILE, rejected(repository.create("aim", GoalType.TASK_COUNT, 1L)))
        assertEquals(
            GoalRejection.NO_PROFILE,
            rejected(repository.edit("any", "aim", GoalType.TASK_COUNT, 1L, null, null, null))
        )
        assertEquals(GoalRejection.NO_PROFILE, rejected(repository.link("any", "any-task")))
        assertEquals(GoalRejection.NO_PROFILE, rejected(repository.unlink("any", "any-task")))
        assertEquals(GoalRejection.NO_PROFILE, rejected(repository.archive("any")))
        assertEquals(GoalRejection.NO_PROFILE, rejected(repository.remove("any")))

        val progress = repository.progressOf(Goal(uuid = "x", userId = 0L, title = "aim", targetValue = 4L))
        assertEquals(0L, progress.counted)
        assertEquals(4L, progress.target)
        assertFalse(progress.reached)
        assertEquals(emptyList<Any>(), repository.observeOpenGoals(zone).first())
        assertEquals(emptyList<Any>(), repository.linkedTaskUuids("any"))
        assertEquals(emptyList<Any>(), repository.valueChangesOf("any"))
        assertEquals(emptyList<Any>(), repository.goalsNaming("any-task"))
    }

    @Test
    fun `an archived goal leaves the open list with its links and history intact`() = runBlocking {
        seed()
        val report = task("report", TaskStatus.COMPLETED, completedAt = at(today, 9, 0))
        val set = goal(title = "aim", type = GoalType.TASK_SET, target = 2L, tasks = listOf(report.uuid))
        edit(set, target = 3L)

        repository.archive(set.uuid)
        assertEquals(emptyList<Any>(), repository.observeOpenGoals(zone).first().map { it.goal.uuid })
        val archived = repository.observeArchivedGoals(zone).first().single()
        assertEquals(set.uuid, archived.goal.uuid)
        assertNotNull(archived.goal.archivedAt)
        // Retirement is a decision about the card, not about the work: the figures, the set it named and
        // the story of its target all survive it.
        assertEquals(3L, archived.progress.target)
        assertEquals(1, archived.progress.completedLinks)
        assertEquals(listOf(report.uuid), repository.linkedTaskUuids(set.uuid))
        assertEquals(1, repository.valueChangesOf(set.uuid).size)

        repository.unarchive(set.uuid)
        assertEquals(listOf(set.uuid), repository.observeOpenGoals(zone).first().map { it.goal.uuid })
        assertNull(repository.goal(set.uuid)!!.archivedAt)
    }

    @Test
    fun `the task screen sees the open goals that name it and none of the retired ones`() = runBlocking {
        seed()
        val report = task("report")
        task("mail")
        val naming = goal(title = "names report", type = GoalType.TASK_SET, tasks = listOf(report.uuid))
        goal(title = "names mail", type = GoalType.TASK_SET, tasks = listOf("task-mail"))
        goal(title = "a count goal", target = 5L)

        assertEquals(listOf(naming.uuid), repository.goalsNaming(report.uuid).map { it.uuid })
        assertTrue("a goal that names nothing claims no task", repository.goalsNaming("task-nobody").isEmpty())

        repository.archive(naming.uuid)
        assertEquals(emptyList<Any>(), repository.goalsNaming(report.uuid))
    }

    @Test
    fun `a deleted goal stops being editable and leaves its links where they are`() = runBlocking {
        seed()
        val report = task("report")
        val set = goal(title = "aim", type = GoalType.TASK_SET, tasks = listOf(report.uuid))
        edit(set, target = 5L)

        repository.remove(set.uuid)
        assertNull(repository.goal(set.uuid))
        assertEquals(
            GoalRejection.NOT_FOUND,
            rejected(repository.edit(set.uuid, "x", GoalType.TASK_SET, 1L, null, null, null))
        )
        assertEquals(GoalRejection.NOT_FOUND, rejected(repository.link(set.uuid, report.uuid)))
        assertEquals(GoalRejection.NOT_FOUND, rejected(repository.archive(set.uuid)))
        assertEquals(emptyList<Any>(), repository.observeOpenGoals(zone).first())

        // The rows the user never asked to lose: a restore or an import that brings the goal back finds
        // the set it named and the story its target had.
        assertEquals(listOf(report.uuid), goalDao.linksOf(set.uuid, userId).map { it.taskUuid })
        assertEquals(1, goalDao.valueChangesOf(set.uuid, userId).size)
    }

    companion object {
        private const val TIMEOUT_MILLIS = 5_000L
        private const val POLL_MILLIS = 10L
    }
}
