package com.taskplanner.data.local

import com.taskplanner.data.model.Priority
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.TaskStatus
import com.taskplanner.data.model.isCompletedSpelling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Status, priority and sync values are stored as TEXT in every historical database, so the
 * converters are the compatibility layer: a renamed constant or a throwing parse would make
 * already installed tasks unreadable.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `status keeps the constant names written by previous releases`() {
        // Do not rename these: they are the literal values stored on user devices.
        assertEquals("COMPLETED", converters.fromTaskStatus(TaskStatus.COMPLETED))
        assertEquals("TODO", converters.fromTaskStatus(TaskStatus.TODO))
        assertEquals("IN_PROGRESS", converters.fromTaskStatus(TaskStatus.IN_PROGRESS))
        assertEquals("PAUSED", converters.fromTaskStatus(TaskStatus.PAUSED))
    }

    @Test
    fun `every status value reads back unchanged`() {
        TaskStatus.values().forEach { status ->
            assertEquals(status, converters.toTaskStatus(converters.fromTaskStatus(status)))
        }
    }

    @Test
    fun `the done label used by the ui also reads as completed`() {
        assertEquals(TaskStatus.COMPLETED, converters.toTaskStatus("DONE"))

        // The dashboard's SQL and this reader cannot see each other, so the rule they share is this
        // function: a row an old release spelled `DONE`, or an import spelled `done`, has to be a
        // completion for both, or the screen reports finished work as still open. The SQL says the same
        // thing with `UPPER(status)`, which is why the casing below is not a mistake but a requirement.
        assertTrue(isCompletedSpelling("DONE"))
        assertTrue(isCompletedSpelling(converters.fromTaskStatus(TaskStatus.COMPLETED)))
        assertTrue(isCompletedSpelling("done"))
        assertFalse(isCompletedSpelling("IN_PROGRESS"))
        assertFalse(isCompletedSpelling("PAUSED"))
        assertFalse(isCompletedSpelling("COMPLETED_BUT_NOT_REALLY"))
    }

    @Test
    fun `unknown or corrupted status falls back instead of crashing the list`() {
        assertEquals(TaskStatus.TODO, converters.toTaskStatus("SOMETHING_ELSE"))
        assertEquals(TaskStatus.TODO, converters.toTaskStatus(""))
        // hand-edited or exported rows can use any casing
        assertEquals(TaskStatus.IN_PROGRESS, converters.toTaskStatus("in_progress"))
    }

    @Test
    fun `priority round trips and unknown values are safe`() {
        Priority.values().forEach { assertEquals(it, converters.toPriority(converters.fromPriority(it))) }
        assertEquals(Priority.MEDIUM, converters.toPriority("URGENT"))
    }

    @Test
    fun `sync state round trips so the queue survives restarts`() {
        SyncState.values().forEach { assertEquals(it, converters.toSyncState(converters.fromSyncState(it))) }
        assertEquals(SyncState.PENDING, converters.toSyncState("SYNCED_UNKNOWN"))
        // the column default written by MIGRATION_6_7
        assertEquals(SyncState.PENDING, converters.toSyncState(converters.fromSyncState(SyncState.PENDING)))
    }

    @Test
    fun `tags survive a round trip and malformed json never breaks a row`() {
        val tags = listOf("дом", "urgent", "1")
        assertEquals(tags, converters.fromStringToList(converters.fromListToString(tags)))
        assertEquals(emptyList<String>(), converters.fromStringToList("not json at all"))
        assertEquals(emptyList<String>(), converters.fromStringToList(""))
    }

    @Test
    fun `timestamps convert both ways`() {
        val millis = 1_700_000_000_000L
        assertEquals(Date(millis), converters.fromTimestamp(millis))
        assertEquals(millis, converters.dateToTimestamp(Date(millis)))
        assertEquals(null, converters.fromTimestamp(null))
        assertEquals(null, converters.dateToTimestamp(null))
    }
}
