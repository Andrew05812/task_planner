package com.taskplanner.data.model

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.taskplanner.data.local.Converters
import kotlinx.parcelize.Parcelize
import java.time.ZoneId
import java.util.UUID

/** What a repetition interval is counted in. Wall-clock arithmetic is done in the series zone. */
enum class RepeatUnit { DAY, WEEK, MONTH, YEAR }

/**
 * A repetition series: the rule plus the cursor of the instance the series currently exposes.
 *
 * Only one instance of a series is materialised as a Task at a time, so a daily series that has
 * been sitting for a year does not leave 365 rows behind; the cursor records which scheduled
 * instant is live. Nothing about the series depends on the local row id, so the same instance
 * computed on two devices carries the same task uuid and sync cannot duplicate it.
 */
@Parcelize
@Entity(
    tableName = "recurrence_rules",
    indices = [Index(value = ["user_id"])],
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
data class RecurrenceRule(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "unit", defaultValue = "'DAY'")
    val unit: RepeatUnit = RepeatUnit.DAY,

    /** Every N units; N <= 0 is treated as 1 rather than being allowed to stall the series. */
    @ColumnInfo(name = "interval", defaultValue = "1")
    val interval: Int = 1,

    /**
     * ISO weekday numbers (Monday = 1 … Sunday = 7) as "1,3,5", for weekly series that repeat on
     * chosen days. Empty means "the weekday of the anchor". Kept as text because Room resolves
     * type converters by erased generic type, and List<String> is already taken.
     */
    @ColumnInfo(name = "week_days", defaultValue = "''")
    val weekDays: String = "",

    /** Day of month for monthly series; 0 means "the anchor's day", clamped to shorter months. */
    @ColumnInfo(name = "day_of_month", defaultValue = "0")
    val dayOfMonth: Int = 0,

    /** First scheduled instant of the series, in epoch millis. */
    @ColumnInfo(name = "anchor_at")
    val anchorAt: Long,

    /** Zone the series keeps wall-clock time in; empty means "the device's current zone". */
    @ColumnInfo(name = "zone_id", defaultValue = "")
    val zoneId: String = "",

    /** Scheduled instant of the instance the series exposes right now. */
    @ColumnInfo(name = "cursor_at")
    val cursorAt: Long,

    @ColumnInfo(name = "paused", defaultValue = "0")
    val paused: Boolean = false,

    /** Set when the series is finished; a finished series never produces another instance. */
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,

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

    val scheduledWeekDays: List<Int>
        get() = weekDays.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .distinct()
            .sorted()

    /** A series only advances when it is neither paused nor finished nor deleted. */
    val isOpen: Boolean
        get() = !paused && endedAt == null && !isDeleted

    /**
     * Zone the series keeps its wall-clock time in. A blank or unreadable value means the rule came
     * from before zones were recorded (or from an import), so it follows the device.
     */
    fun zone(): ZoneId = zoneId
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
}
