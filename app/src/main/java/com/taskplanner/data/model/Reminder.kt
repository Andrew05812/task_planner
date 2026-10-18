package com.taskplanner.data.model

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.taskplanner.data.local.Converters
import kotlinx.parcelize.Parcelize
import java.util.UUID

/**
 * One lead time on one task: "warn me 15 minutes before this deadline".
 *
 * A reminder is attached to the task's stable [Reminder.taskUuid] rather than to its row id, so the
 * same lead time survives an import, a sync round trip and a device that knows nothing about local
 * ids. [offsetMinutes] is what the user chose; [triggerAt] is that offset measured from the deadline
 * the row had at the time, and [anchorAt] records that deadline so a moved date can be detected
 * instead of leaving the alarm pointing at the old evening.
 *
 * The identity of a reminder is (task, offset): [identity] makes it deterministic, and the unique
 * index repeats it in SQL. So pressing the same chip twice, replaying an import, or pulling the same
 * reminder from another device all collide with the row that already exists rather than producing a
 * second notification for the same minute.
 */
@Parcelize
@Entity(
    tableName = "reminders",
    indices = [
        Index(value = ["user_id"]),
        Index(value = ["trigger_at"]),
        Index(value = ["task_uuid", "offset_minutes"], unique = true)
    ]
    // No DB-level foreign key onto tasks(uuid), matching group_id and series_uuid: a reminder and
    // its task can arrive in any order — an import inserts both, a pull delivers rows one at a time —
    // and the task's row id is not a stable identity anyway. The queries that fire notifications join
    // tasks explicitly, so a reminder whose task is gone, archived or finished never reaches the user.
)
@TypeConverters(Converters::class)
data class Reminder(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    @ColumnInfo(name = "task_uuid")
    val taskUuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    /** Minutes before the deadline; 0 means "at the deadline itself". */
    @ColumnInfo(name = "offset_minutes", defaultValue = "0")
    val offsetMinutes: Int = 0,

    @ColumnInfo(name = "trigger_at")
    val triggerAt: Long,

    /** The deadline [triggerAt] was measured from, so a moved date can be noticed. */
    @ColumnInfo(name = "anchor_at")
    val anchorAt: Long,

    /**
     * Set once the reminder has been handed to the notification bar (or deliberately retired). A
     * second alarm for the same row, or a sweep that runs over the same minute again, sees this and
     * stays quiet. Snoozing clears it.
     */
    @ColumnInfo(name = "notified_at")
    val notifiedAt: Long? = null,

    @ColumnInfo(name = "is_deleted", defaultValue = "0")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable {

    /** A row the user still has coming: not thrown away, not already shown. */
    val isPending: Boolean
        get() = !isDeleted && notifiedAt == null

    /**
     * Stable notification id for this reminder.
     *
     * Derived from the uuid, so the same reminder always replaces its own earlier notification —
     * after a snooze, an app restart, or a sweep that runs twice — and two different reminders of
     * two different tasks never share a slot. Masked to 28 bits so an action can be added to it
     * below without leaving the positive Int range.
     */
    val notificationId: Int
        get() = uuid.hashCode() and 0x0FFFFFFF

    /** One of the four things a notification can be asked to do, in its request code. */
    enum class Action { OPEN, COMPLETE, SNOOZE, EDIT }

    /**
     * Request code of this reminder's PendingIntent for [action]: the pair has to be unique or the
     * system replaces one action's intent with the other's and the button does the wrong thing.
     */
    fun requestCodeFor(action: Action): Int = notificationId * ACTION_STRIDE + action.ordinal

    /**
     * Request code of the alarm itself, in the slot after the four actions. The alarm's intent and
     * the notification's tap intent are both broadcast/activity intents for this reminder, and they
     * must not overwrite each other.
     */
    fun alarmRequestCode(): Int = notificationId * ACTION_STRIDE + Action.values().size

    companion object {
        private const val MINUTE_MILLIS = 60_000L

        /** Actions are packed into the request code with a stride of 8 (4 are used, 4 spare). */
        private const val ACTION_STRIDE = 8

        fun identity(taskUuid: String, offsetMinutes: Int): String = UUID.nameUUIDFromBytes(
            "reminder:$taskUuid@$offsetMinutes".toByteArray(Charsets.UTF_8)
        ).toString()

        fun triggerAt(dueAt: Long, offsetMinutes: Int): Long =
            dueAt - offsetMinutes.coerceAtLeast(0) * MINUTE_MILLIS
    }
}
