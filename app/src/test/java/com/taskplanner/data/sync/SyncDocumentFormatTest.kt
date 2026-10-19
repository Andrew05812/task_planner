package com.taskplanner.data.sync

import com.taskplanner.data.model.Category
import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.RecurrenceRule
import com.taskplanner.data.model.Reminder
import com.taskplanner.data.model.RepeatUnit
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.Task
import com.taskplanner.data.model.TaskGroup
import com.taskplanner.data.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a cloud document is allowed to contain.
 *
 * The repository ships no Firebase project, so no test here talks to the cloud. The wire format is
 * still worth pinning down on its own: it is the contract one device writes and another reads, and
 * the two halves only line up if the field names, the identities and the omissions are fixed. A
 * document that leaked a local row id, a local user id or the sync queue state would either collide
 * with another account's data or tell a second device that it had already pushed something it has
 * not.
 */
class SyncDocumentFormatTest {

    private val hour = 3_600_000L

    private fun task(
        uuid: String = "task-1",
        seriesUuid: String? = null,
        occurrenceAt: Long? = null
    ): Task = Task(
        id = 7L,
        uuid = uuid,
        title = "Write the report",
        description = "two pages",
        dueDate = 1_700_000_000_000L,
        startTime = 1_700_000_000_000L - 2 * hour,
        endTime = 1_700_000_000_000L - hour,
        estimatedMinutes = 45,
        priority = Priority.HIGH,
        status = TaskStatus.IN_PROGRESS,
        categoryId = 3L,
        groupId = "group-1",
        seriesUuid = seriesUuid,
        occurrenceAt = occurrenceAt,
        archivedAt = 1_690_000_000_000L,
        startedAt = 1_689_000_000_000L,
        userId = 42L,
        order = 2,
        syncState = SyncState.PENDING,
        createdAt = 1_680_000_000_000L,
        updatedAt = 1_699_000_000_000L
    )

    private fun rule(
        uuid: String = "series-1",
        unit: RepeatUnit = RepeatUnit.WEEK,
        cursorAt: Long = 1_700_000_000_000L
    ): RecurrenceRule = RecurrenceRule(
        uuid = uuid,
        userId = 42L,
        unit = unit,
        interval = 2,
        weekDays = "2,5",
        dayOfMonth = 15,
        anchorAt = 1_690_000_000_000L,
        zoneId = "Europe/Moscow",
        cursorAt = cursorAt,
        paused = false,
        createdAt = 1_680_000_000_000L,
        updatedAt = 1_699_000_000_000L,
        syncState = SyncState.PENDING
    )

    private fun reminder(
        taskUuid: String = "task-1",
        offsetMinutes: Int = 15
    ): Reminder = Reminder(
        uuid = Reminder.identity(taskUuid, offsetMinutes),
        taskUuid = taskUuid,
        userId = 42L,
        offsetMinutes = offsetMinutes,
        triggerAt = 1_700_000_000_000L - offsetMinutes * 60_000L,
        anchorAt = 1_700_000_000_000L,
        notifiedAt = 1_699_500_000_000L,
        createdAt = 1_680_000_000_000L,
        updatedAt = 1_699_000_000_000L,
        syncState = SyncState.PENDING
    )

    @Test
    fun `a task document carries the planned window and the series link`() {
        val fields = SyncedTask.of(task(seriesUuid = "series-1", occurrenceAt = 1_700_000_000_000L), "category-1").fields()

        assertEquals(1_700_000_000_000L - 2 * hour, fields["start_time"])
        assertEquals(1_700_000_000_000L - hour, fields["end_time"])
        assertEquals(45, fields["estimated_minutes"])
        assertEquals("series-1", fields["series_uuid"])
        assertEquals(1_700_000_000_000L, fields["occurrence_at"])
        assertEquals("category-1", fields["category_uuid"])
        assertEquals("group-1", fields["group_uuid"])
    }

    @Test
    fun `a task written by one device is read back unchanged by another`() {
        val original = SyncedTask.of(task(seriesUuid = "series-1", occurrenceAt = 1_700_000_000_000L), "category-1")

        assertEquals(original, SyncedTask.fromFields(original.fields()))
    }

    @Test
    fun `a task that names no plan fields stays unnamed instead of inventing a block`() {
        val plain = SyncedTask.of(task().copy(startTime = null, endTime = null, estimatedMinutes = null), null)

        val restored = SyncedTask.fromFields(plain.fields())
        assertNull(restored?.startTime)
        assertNull(restored?.endTime)
        assertNull(restored?.estimatedMinutes)
        assertNull(restored?.seriesUuid)
        assertNull(restored?.categoryUuid)
    }

    @Test
    fun `a document from before the plan fields existed still reads as a task`() {
        val legacy = SyncedTask.of(task(), "category-1")
            .fields()
            .filterKeys { it !in setOf("start_time", "end_time", "estimated_minutes", "series_uuid", "occurrence_at") }

        val restored = SyncedTask.fromFields(legacy)
        assertEquals("task-1", restored?.uuid)
        assertEquals(1_700_000_000_000L, restored?.dueDate)
        assertNull(restored?.startTime)
        assertNull(restored?.seriesUuid)
    }

    @Test
    fun `nothing that only this device knows travels in a task document`() {
        val fields = SyncedTask.of(task(), "category-1").fields()

        FORBIDDEN_KEYS.forEach { assertFalse("document must not carry $it", fields.containsKey(it)) }
        assertEquals("task-1", fields["uuid"])
        assertEquals(1_699_000_000_000L, fields["updated_at"])
    }

    @Test
    fun `the same private fields are absent from every other document type`() {
        val category = SyncedCategory.of(
            Category(id = 5L, uuid = "category-1", name = "Work", color = 0xFF0000.toInt(), icon = "briefcase", userId = 42L),
            "category-parent"
        )
        val group = SyncedGroup.of(
            TaskGroup(uuid = "group-1", userId = 42L, name = "Sprint", collapsed = true, sortOrder = 3)
        )
        val documents = listOf(
            category.fields(),
            group.fields(),
            SyncedRule.of(rule()).fields(),
            SyncedReminder.of(reminder()).fields()
        )

        documents.forEach { document ->
            FORBIDDEN_KEYS.forEach { assertFalse("document must not carry $it", document.containsKey(it)) }
        }
        assertEquals(category, SyncedCategory.fromFields(category.fields()))
        assertEquals(group, SyncedGroup.fromFields(group.fields()))
        assertEquals("category-parent", category.fields()["parent_uuid"])
    }

    @Test
    fun `a series keeps its cursor so a second device resumes where it left off`() {
        val fields = SyncedRule.of(rule()).fields()

        assertEquals(1_700_000_000_000L, fields["cursor_at"])
        assertEquals(1_690_000_000_000L, fields["anchor_at"])
        assertEquals("WEEK", fields["unit"])
        assertEquals("2,5", fields["week_days"])
        assertEquals(2, fields["interval"])
        assertEquals("Europe/Moscow", fields["zone_id"])

        assertEquals(RepeatUnit.WEEK, SyncedRule.fromFields(fields)?.unit)
        assertEquals(rule().uuid, SyncedRule.fromFields(fields)?.uuid)
        assertEquals(rule().cursorAt, SyncedRule.fromFields(fields)?.cursorAt)
    }

    @Test
    fun `a series read back on another device belongs to that device, not to the one that pushed it`() {
        val remote = SyncedRule.fromFields(SyncedRule.of(rule()).fields())

        assertEquals(rule(), remote?.toRule(42L))
        assertEquals(99L, remote?.toRule(99L)?.userId)
    }

    @Test
    fun `a series unit this build does not know falls back to a daily repetition`() {
        val unknown = SyncedRule.of(rule(unit = RepeatUnit.MONTH)).fields() + ("unit" to "FORTNIGHT")

        assertEquals(RepeatUnit.DAY, SyncedRule.fromFields(unknown)?.unit)
    }

    @Test
    fun `a lead time document is keyed by the task and the minute it warns for`() {
        val fields = SyncedReminder.of(reminder(offsetMinutes = 30)).fields()

        assertEquals(Reminder.identity("task-1", 30), fields["uuid"])
        assertEquals("task-1", fields["task_uuid"])
        assertEquals(30, fields["offset_minutes"])
        assertEquals(1_700_000_000_000L - 30 * 60_000L, fields["trigger_at"])
        assertEquals(1_700_000_000_000L, fields["anchor_at"])
    }

    @Test
    fun `a reminder round trips with the minute it already fired in`() {
        val original = SyncedReminder.of(reminder())

        assertEquals(original, SyncedReminder.fromFields(original.fields()))
        assertEquals(reminder(), original.toReminder(42L))
    }

    @Test
    fun `two devices offering the same warning push one document, not two`() {
        val here = SyncedReminder.of(reminder()).fields()["uuid"]
        val there = SyncedReminder.of(reminder(offsetMinutes = 15)).fields()["uuid"]

        assertEquals(here, there)
    }

    @Test
    fun `a reminder that cannot name its task is never read into the database`() {
        val noTask = SyncedReminder.of(reminder()).fields() - "task_uuid"

        assertNull(SyncedReminder.fromFields(noTask))
    }

    @Test
    fun `a document without a usable identity is dropped whatever type it claims to be`() {
        // A document with no uuid, or with an empty one, has no identity to match a local row
        // against. Reading it in would create an entity that the merge rules, the import and the
        // push queue all treat as belonging to nothing.
        val documents: List<Pair<Map<String, Any?>, (Map<String, Any?>) -> Any?>> = listOf(
            SyncedTask.of(task(), "category-1").fields() to { SyncedTask.fromFields(it) },
            SyncedCategory.of(
                Category(id = 5L, uuid = "category-1", name = "Work", color = 1, icon = "", userId = 42L),
                null
            ).fields() to { SyncedCategory.fromFields(it) },
            SyncedGroup.of(TaskGroup(uuid = "group-1", userId = 42L, name = "Sprint")).fields() to {
                SyncedGroup.fromFields(it)
            },
            SyncedRule.of(rule()).fields() to { SyncedRule.fromFields(it) },
            SyncedReminder.of(reminder()).fields() to { SyncedReminder.fromFields(it) }
        )

        documents.forEach { (document, parse) ->
            assertNull("a document with no uuid is unreadable", parse(document - "uuid"))
            assertNull("an empty uuid is not an identity", parse(document + ("uuid" to "")))
        }
    }

    @Test
    fun `numbers the cloud hands back as a different width still land in the right fields`() {
        // Firestore returns integers as Long; a document written by an older build or another client
        // can put either width in, so nothing may depend on the exact Number type.
        val wide = SyncedTask.of(task(), "category-1").fields() +
            mapOf("estimated_minutes" to 45L, "order" to 2L, "updated_at" to 1_699_000_000_000L)

        val restored = SyncedTask.fromFields(wide)
        assertEquals(45, restored?.estimatedMinutes)
        assertEquals(2, restored?.order)
        assertEquals(1_699_000_000_000L, restored?.updatedAt)

        val ruleWide = SyncedRule.of(rule()).fields() + mapOf("interval" to 2L, "day_of_month" to 15L)
        assertEquals(2, SyncedRule.fromFields(ruleWide)?.interval)
        assertEquals(15, SyncedRule.fromFields(ruleWide)?.dayOfMonth)
    }

    @Test
    fun `a deleted task stays a deleted task through the cloud`() {
        val tombstone = task().copy(isDeleted = true, deletedAt = 1_699_500_000_000L)
        val original = SyncedTask.of(tombstone, null)

        val restored = SyncedTask.fromFields(original.fields())
        assertEquals(true, restored?.isDeleted)
        assertEquals(1_699_500_000_000L, restored?.deletedAt)
    }

    private companion object {
        /**
         * Local-only state. A row id, a numeric user id or the queue position belong to one device:
         * putting them on the wire makes another device believe it shares identities it does not have,
         * or that a push already happened when nothing was pushed.
         */
        val FORBIDDEN_KEYS = setOf("id", "user_id", "sync_state", "category_id", "group_id", "task_id")
    }
}
