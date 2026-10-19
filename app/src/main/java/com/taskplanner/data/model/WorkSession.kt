package com.taskplanner.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.taskplanner.data.local.Converters

/** How an interval came to exist — a running timer or something the user typed in afterwards. */
enum class WorkSource {
    TRACKER,
    MANUAL;

    companion object {
        /** A row written by a newer build, or damaged, is read as a measured interval rather than
         *  thrown away: it is work the user did either way. */
        fun fromName(value: String?): WorkSource =
            entries.firstOrNull { it.name == value?.uppercase(java.util.Locale.ROOT) } ?: TRACKER
    }
}

/**
 * Where a logical tracking session is in its life.
 *
 * `IDLE` is deliberately absent: it is not a stored state but the absence of a session, which is what
 * keeps a crash, a sign-out or an old database from ever being read as "a timer is running".
 */
enum class TrackingState {
    RUNNING,
    PAUSED,
    COMPLETED;

    companion object {
        fun fromName(value: String?): TrackingState =
            entries.firstOrNull { it.name == value?.uppercase(java.util.Locale.ROOT) } ?: RUNNING
    }
}

/**
 * One continuous stretch of measured work — the row the whole time-tracking feature is built out of.
 *
 * A logical session is not a row here: it is the set of [WorkSession]s that share a
 * [trackingSessionUuid]. Starting at 09:00, pausing at 09:30, resuming at 10:00 and stopping at 10:45
 * leaves two rows, 09:00–09:30 and 10:00–10:45, and 75 measured minutes; the break in between is not
 * a row and is never counted. That split is why [endedAt] is nullable and [openMarker] exists, and why
 * nothing here stores "the session's total" — the total is always the sum of the rows, which is what
 * lets the history stay trustworthy after a restart, a sync from another device, or an edit of the task.
 *
 * A closed interval is never rewritten. The only row that changes is the open one, and only when it is
 * paused or stopped.
 */
@Entity(
    tableName = "work_sessions",
    indices = [
        // One open interval per profile — see [WorkSession.openMarker] for why this is a marker
        // column rather than a partial index.
        Index(value = ["user_id", "open_marker"], unique = true),
        // The history screen's own shape: a profile's intervals, newest first.
        Index(value = ["user_id", "started_at"]),
        // Every period aggregate — day, week, month, custom range — is a range over this column.
        Index(value = ["user_id", "local_day"]),
        Index(value = ["user_id", "task_id"]),
        // The intervals of one logical session, in order, for the detail screen and for recovery.
        Index(value = ["tracking_session_uuid"]),
        // Import and sync identify a row by its uuid, never by the device-local id.
        Index(value = ["uuid"], unique = true)
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
data class WorkSession(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Stable identity for sync and import, so a replayed write meets the row it belongs to. */
    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    @ColumnInfo(name = "user_id")
    val userId: Long,

    /** The logical session this interval belongs to; shared by every interval of one start–stop run. */
    @ColumnInfo(name = "tracking_session_uuid")
    val trackingSessionUuid: String,

    @ColumnInfo(name = "task_id")
    val taskId: Long,

    /**
     * The task by its stable identity as well as by row id. There is no foreign key to `tasks` —
     * the same decision [Reminder] makes: history outlives the row it was written for, an `ON DELETE
     * CASCADE` would delete the measurement together with the card, and a sync pull or an import may
     * well deliver the interval before the task.
     */
    @ColumnInfo(name = "task_uuid")
    val taskUuid: String,

    /** UTC epoch millis. Duration is the difference of two of these, never of two local dates. */
    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    /** Null while the interval is still running. */
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,

    /**
     * [endedAt] minus [startedAt] in whole seconds, written when the interval closes and kept from then
     * on. Stored rather than always recomputed because the two can legitimately differ: if the system
     * clock was moved during the interval, [clockAnomaly] says so and this holds the figure the
     * monotonic clock supported.
     */
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Long? = null,

    /**
     * The local calendar day this interval started on, as an epoch day, resolved once with the zone in
     * force at that instant (`java.time`, so daylight saving is already accounted for).
     *
     * Day, week and month statistics group on this column, which keeps aggregation inside SQL instead
     * of loading rows to sum them in Kotlin. It is written at the moment of measurement and never
     * recomputed, because moving a finished afternoon's work into a different day because the user
     * travelled across a time zone would be the wrong answer to the question "when did I do this".
     */
    @ColumnInfo(name = "local_day")
    val localDay: Long,

    /** The offset behind [localDay], kept so a day can be shown and audited the way it was measured. */
    @ColumnInfo(name = "utc_offset_minutes")
    val utcOffsetMinutes: Int,

    @ColumnInfo(name = "source", defaultValue = "'TRACKER'")
    val source: WorkSource = WorkSource.TRACKER,

    /**
     * The session's uuid while this interval is open, NULL once it is closed.
     *
     * It exists so that "one running timer per profile" can be a database rule instead of a UI
     * courtesy: the unique index below rejects a second open interval — a double tap, a parallel
     * request, a second screen — while a closed interval, whose marker is NULL, never collides with
     * anything, because SQLite treats NULLs as distinct inside a unique index. A partial unique index
     * (`WHERE ended_at IS NULL`) would express the same thing more directly, but Room's `@Index` has no
     * `WHERE` clause, and an index written only by a migration would leave the physical schema
     * different from the schema Room exports.
     */
    @ColumnInfo(name = "open_marker")
    val openMarker: String? = null,

    /**
     * Set when the wall clock disagreed with the monotonic clock while an interval was open — the
     * system time was moved, or a reboot happened mid-interval.
     *
     * The alternative is to clamp a negative duration to zero, which would quietly turn a tampered or
     * lost measurement into "no time was spent" and make the history unusable for analytics. The number
     * is corrected *and* recorded as corrected.
     */
    @ColumnInfo(name = "clock_anomaly")
    val clockAnomaly: String? = null,

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
) {
    /** The interval still being measured: no end, so its length is not yet a fact. */
    val isOpen: Boolean get() = endedAt == null
}
