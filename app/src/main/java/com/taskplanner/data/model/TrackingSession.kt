package com.taskplanner.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.taskplanner.data.local.Converters

/**
 * The state machine of one start–stop run: the thing a user thinks of as "the session I started at 9".
 *
 * The measured time is not here, it is in the [WorkSession] rows that carry this row's uuid. This table
 * exists because a state has to change in place — running, paused, stopped — while the intervals must
 * never be rewritten, and putting both in one row would mean editing history every time the user taps
 * pause. Splitting them is what lets `Stop` on a paused session close the *session* without inventing a
 * zero-length interval to mark the end.
 *
 * `IDLE` is not stored: a profile with no row here has nothing running, which is also what a fresh
 * install, a crash, or a database from before this feature looks like.
 */
@Entity(
    tableName = "tracking_sessions",
    indices = [
        // One RUNNING session per profile — see [runningMarker]. A profile with two timers is not a
        // race the UI lost, it is a corrupted measurement, so it is rejected here instead.
        Index(value = ["running_marker"], unique = true),
        Index(value = ["user_id"]),
        // The task screens ask "is anything being tracked on this task" for every card they draw.
        Index(value = ["user_id", "task_id"]),
        // The history screen's one query: a profile's sessions inside a period, newest first.
        Index(value = ["user_id", "started_at"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
@TypeConverters(Converters::class)
data class TrackingSession(
    /** Stable identity, generated at Start; the [WorkSession.trackingSessionUuid] of its intervals. */
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "task_id")
    val taskId: Long,

    @ColumnInfo(name = "task_uuid")
    val taskUuid: String,

    /**
     * The task's title as it read when the work was measured.
     *
     * History has to stay understandable after the task is renamed, archived or deleted — including
     * physically. Nothing else in the row identifies the work to a person, and a foreign key cannot
     * take the place of it, because the task row is allowed to go away while the measurement stays.
     */
    @ColumnInfo(name = "task_title")
    val taskTitle: String,

    @ColumnInfo(name = "state", defaultValue = "'RUNNING'")
    val state: TrackingState = TrackingState.RUNNING,

    /** When the user first pressed Start. The earliest interval's own start is the same instant. */
    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    /** Set once at Stop. Null while the session can still be resumed. */
    @ColumnInfo(name = "stopped_at")
    val stoppedAt: Long? = null,

    /** The zone [WorkSession.localDay] was resolved in, kept so a day's history can be re-read later. */
    @ColumnInfo(name = "zone_id")
    val zoneId: String = "",

    /**
     * The profile id as text while the session is running, NULL otherwise.
     *
     * Same shape as [WorkSession.openMarker]: the unique index above allows one non-NULL value, and
     * every finished or paused session drops out of it by holding NULL, which SQLite keeps distinct.
     */
    @ColumnInfo(name = "running_marker")
    val runningMarker: String? = null,

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
)
