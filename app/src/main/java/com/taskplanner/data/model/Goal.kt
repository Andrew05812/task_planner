package com.taskplanner.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.taskplanner.data.local.Converters
import java.util.Locale

/**
 * What a goal counts, and therefore what answers the question "how far is it to done".
 *
 * The four kinds are not four screens: they are one row with one number to reach, differing only in
 * which existing aggregate supplies the count. That is deliberate. A goal whose progress were its own
 * stored figure would have to be updated by every event that changes the work — a task completed, a
 * status cancelled, a row deleted — and every missed event would leave a number the user could be shown
 * and believed. Progress here is always recomputed from `tasks` and `work_sessions` at the moment it is
 * read, so a task that stopped being completed takes its share of the goal with it automatically, and the
 * only figure stored on this row is the one the user chose.
 */
enum class GoalType {
    /** [targetValue] tasks finished in the goal's window, from anywhere in the profile. */
    TASK_COUNT,

    /** [targetValue] of the tasks explicitly linked through `goal_tasks` finished. */
    TASK_SET,

    /** [targetValue] seconds of measured work in the goal's window. */
    TIME_SPENT,

    /**
     * The named set answered by a date: reached when everything it names is finished, late when that has
     * not happened by [Goal.deadlineAt], which this type cannot be saved without.
     *
     * It is a type rather than a flag on another type because the screen has to say something different
     * when the date passes: a count goal that ran out of time is behind, a deadline goal that ran out of
     * time is late, and the two read as different sentences.
     */
    DEADLINE;

    /**
     * Whether this type's figure is answered by tasks the user named, rather than by the whole profile.
     *
     * The screens need the distinction to decide whether a goal has a set to show and add to at all; a
     * count or time goal reads its figure out of everything the account holds, so a picker beside it would
     * offer work that is already counted.
     */
    val namesTasks: Boolean
        get() = this == TASK_SET || this == DEADLINE

    companion object {
        /** A row written by a newer build is read as a count rather than thrown away. */
        fun fromName(value: String?): GoalType =
            entries.firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: TASK_COUNT
    }
}

/**
 * A goal the user set themselves: a figure to reach, optionally by a date, over work that stays in its
 * own tables.
 *
 * A goal owns no tasks. [GoalType.TASK_SET] and [GoalType.DEADLINE] point at tasks through
 * [GoalTaskLink], and the other two point at nothing at all — they count whatever the profile finishes or
 * measures inside a window. Copying a task into the goal would give the profile two rows for one piece of
 * work, each free to drift, and the dashboard already counts the real one.
 *
 * Nothing here stores progress, an achievement date, or a percentage. All of it is derived from the rows
 * the work-tracking and task features already maintain, which is what keeps this table honest after an
 * edit anywhere else in the database.
 */
@Entity(
    tableName = "goals",
    indices = [
        // The screen lists a profile's open goals; a deleted goal is never on it.
        Index(value = ["user_id", "is_deleted"]),
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
data class Goal(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "type", defaultValue = "'TASK_COUNT'")
    val type: GoalType = GoalType.TASK_COUNT,

    /**
     * The figure the user is aiming at, in the unit [type] implies: tasks for the first two kinds,
     * seconds for [GoalType.TIME_SPENT].
     *
     * A single column rather than one per unit, because the unit is a property of the type and two
     * numbers that must agree with each other is one more way to be inconsistent. Zero is allowed and
     * means "no figure was chosen", which the screen prints as a goal with nothing reached yet rather than
     * as a division by zero.
     */
    @ColumnInfo(name = "target_value")
    val targetValue: Long,

    /** Epoch day the goal's window opens, inclusive. Null means the goal has no window. */
    @ColumnInfo(name = "window_from_day")
    val windowFromDay: Long? = null,

    /** Epoch day the goal's window closes, inclusive. Null means the window is open at the far end. */
    @ColumnInfo(name = "window_to_day")
    val windowToDay: Long? = null,

    /**
     * UTC epoch millis of the date the goal was set for.
     *
     * [GoalType.DEADLINE] cannot be saved without it — that date is the whole question the type asks — and
     * the other three may carry one, in which case it is the date their progress reads as late against.
     * An instant is stored rather than a day because the picker hands over an instant, and the day the
     * screen prints comes from reading it back in the user's zone, the same way every other date in this
     * schema is derived.
     */
    @ColumnInfo(name = "deadline_at")
    val deadlineAt: Long? = null,

    /**
     * Set when the user puts the goal away themselves, null while it is live.
     *
     * It is not the same thing as being reached, and the difference is the point: a goal can be retired
     * because the user decided it no longer matters, and the work it counted stays in the history. A
     * derived "achieved" flag would have to be recomputed on every edit elsewhere; this column records
     * only a decision, which nothing else in the database can undo.
     */
    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,

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
    /** Live on the screen: not deleted, not put away. */
    val isOpen: Boolean get() = !isDeleted && archivedAt == null
}

/**
 * The link between a goal and one task — the row that lets a goal point at work without owning it.
 *
 * Composite key on the two uuids, so the same task can be named by a goal once and only once, and a
 * second attempt to link it is a no-op rather than a duplicate that would make the denominator of a
 * "3 of 5 tasks" figure wrong. There is no foreign key to `tasks`, for the reason every other reference
 * in this schema has none: a task can be deleted, archived out of the profile, or arrive later from
 * another device, and a goal's history should survive all three. A link whose task is gone contributes to
 * the count of tasks asked for and never to the count finished, which is what the screen says out loud.
 */
@Entity(
    tableName = "goal_tasks",
    primaryKeys = ["goal_uuid", "task_uuid"],
    indices = [
        // "Which tasks does this goal ask for", in the order they were added.
        Index(value = ["goal_uuid", "added_at"]),
        // "Which goals mention this task", for the task's own screen.
        Index(value = ["task_uuid"]),
        Index(value = ["user_id"])
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
data class GoalTaskLink(
    @ColumnInfo(name = "goal_uuid")
    val goalUuid: String,

    @ColumnInfo(name = "task_uuid")
    val taskUuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** The order the user put the tasks in, which is the order the goal's own screen prints them. */
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * One recorded change of a goal's own figures.
 *
 * The target of a goal is the one thing on it the user chose, so changing it is a fact worth keeping:
 * "aiming at 20 tasks now" reads differently when the history says the aim started at 40 and was lowered
 * twice. Like the interval corrections, this table is append-only — a later edit adds a row rather than
 * rewriting the one that records the earlier decision.
 *
 * The before-and-after pair is text because the column that changed is not always a number: a window is a
 * pair of days, a deadline is an instant, and a title is a title. Storing them as the words the screen
 * prints keeps the history readable without knowing which type each field had at the time, and nothing
 * downstream does arithmetic on a history row.
 */
@Entity(
    tableName = "goal_value_changes",
    indices = [
        Index(value = ["goal_uuid", "created_at"]),
        Index(value = ["user_id"])
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
data class GoalValueChange(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String = "",

    @ColumnInfo(name = "goal_uuid")
    val goalUuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    /**
     * Which of the goal's own figures moved: one of [FIELD_TITLE], [FIELD_TYPE], [FIELD_TARGET],
     * [FIELD_WINDOW] or [FIELD_DEADLINE].
     */
    @ColumnInfo(name = "field")
    val field: String,

    @ColumnInfo(name = "old_value")
    val oldValue: String? = null,

    @ColumnInfo(name = "new_value")
    val newValue: String? = null,

    /** Why the user changed the aim, in their words; optional, and never invented for them. */
    @ColumnInfo(name = "reason")
    val reason: String? = null,

    @ColumnInfo(name = "sync_state", defaultValue = "'PENDING'")
    val syncState: SyncState = SyncState.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * The fields a row can speak about, named once here so neither the writer nor the screen has to
         * retype a literal — and so a field the editor gains cannot silently become a history row nobody
         * knows how to print.
         *
         * The values are the words stored in `field`, which export and sync carry between devices:
         * constants rather than an enum, because the column stays plain text that a newer build can read
         * without knowing every name an older one wrote.
         */
        const val FIELD_TITLE = "title"
        const val FIELD_TYPE = "type"
        const val FIELD_TARGET = "target"
        const val FIELD_WINDOW = "window"
        const val FIELD_DEADLINE = "deadline"

        /** Every field the history can hold, in the order the editor shows its own fields. */
        val FIELDS: List<String> = listOf(
            FIELD_TITLE, FIELD_TYPE, FIELD_TARGET, FIELD_WINDOW, FIELD_DEADLINE
        )
    }
}
