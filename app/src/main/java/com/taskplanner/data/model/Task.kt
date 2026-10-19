package com.taskplanner.data.model

import android.os.Parcelable
import androidx.room.*
import com.taskplanner.data.local.Converters
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["user_id"]),
        // The calendar looks a profile's tasks up by deadline range inside one window; without this
        // that lookup walks every row the user has.
        Index(value = ["user_id", "due_date"]),
        // A window is bounded by three different columns — deadline, planned start, planned end — and
        // each of them has to be seekable on its own, or the planner is left walking the profile. The
        // end-time index also carries start_time so the lookup for a block that spans the whole window
        // can answer from the index alone. Measured on a 10k-task account: 8.3 ms for the walk, 2.8 ms
        // here, and 2.8 ms again at a thousand tasks — the read stopped growing with the account.
        // Without these two indices the same rewrite is *slower* (10.7 ms), because its lookups then
        // fall back to walking the profile one after another.
        Index(value = ["user_id", "start_time"]),
        Index(value = ["user_id", "end_time", "start_time"]),
        Index(value = ["category_id"]),
        Index(value = ["group_id"]),
        // A series instance is identified by (series, scheduled instant), so materialising the
        // same occurrence twice — after a retry, a restart, or a pull from another device —
        // collides here instead of leaving a duplicate task behind.
        Index(value = ["series_uuid", "occurrence_at"], unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
@TypeConverters(Converters::class)
data class Task(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Stable identity used for cloud sync and imports; independent of the local row id. */
    @ColumnInfo(name = "uuid", defaultValue = "''")
    val uuid: String = "",

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "due_date")
    val dueDate: Long? = null,

    /**
     * When the work is planned to begin, if the user said so.
     *
     * Null is the normal state, not a missing value: a task with a deadline but no stated beginning
     * is scheduled *by its deadline*, and the calendar has to draw it that way. Nothing in this app
     * infers a start from a deadline or a duration from a title, because a block the user never
     * named would turn into an "overlapping meeting" or an "underestimated day" that they then have
     * to decode.
     */
    @ColumnInfo(name = "start_time")
    val startTime: Long? = null,

    /** When the work is planned to be finished. Pairs with [startTime]; both null is the norm. */
    @ColumnInfo(name = "end_time")
    val endTime: Long? = null,

    /**
     * How long the user expects it to take, in minutes — independent of a stated start and end.
     *
     * A task can have an estimate before anyone knows when it will happen ("45 minutes, sometime
     * this week"), which is exactly the case a block on a timeline cannot express. Kept as minutes
     * rather than an instant so it survives being moved across a daylight-saving boundary.
     */
    @ColumnInfo(name = "estimated_minutes")
    val estimatedMinutes: Int? = null,

    @ColumnInfo(name = "priority")
    val priority: Priority = Priority.MEDIUM,

    @ColumnInfo(name = "status")
    val status: TaskStatus = TaskStatus.TODO,

    @ColumnInfo(name = "category_id")
    val categoryId: Long? = null,

    /** Topic group this task belongs to (TaskGroup.uuid). Kept without a DB-level FK so
     *  historical schema versions remain migratable. */
    @ColumnInfo(name = "group_id")
    val groupId: String? = null,

    /** Recurrence series this row is an instance of (RecurrenceRule.uuid), null for plain tasks.
     *  Kept without a DB-level FK so a series row and its instances can arrive in any order. */
    @ColumnInfo(name = "series_uuid")
    val seriesUuid: String? = null,

    /** Scheduled instant this instance was materialised for. Stays put when the user moves the
     *  instance itself, so the series can still tell which occurrence is live. */
    @ColumnInfo(name = "occurrence_at")
    val occurrenceAt: Long? = null,

    @ColumnInfo(name = "archived", defaultValue = "0")
    val archived: Boolean = false,

    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,

    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "order")
    var order: Int = 0,

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
) : Parcelable
