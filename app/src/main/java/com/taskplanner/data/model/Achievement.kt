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
 * What a grant counted, so a stored number keeps its meaning after the rule that wrote it changes.
 *
 * The unit belongs on the row rather than being looked up from the rule because §11 asks for the achievement
 * to be remembered, and a memory that reads "100" and has to ask a live rule what 100 means is only a memory
 * while the rule still agrees. A rule that is later retuned from 100 tasks to 100 hours would otherwise
 * rewrite the sentence under the reader's own history.
 */
enum class AchievementUnit {
    /** Finished tasks — a count of rows, not of attempts. */
    TASKS,

    /** Goals carried to their target. */
    GOALS,

    /** Groups (projects) closed out. */
    PROJECTS,

    /** Habits kept, as a count of habits rather than of days. */
    HABITS,

    /** Consecutive scheduled days one habit was kept across. */
    STREAK_DAYS,

    /** Days on which this profile left any work behind, whether or not they were consecutive. */
    ACTIVE_DAYS,

    /** Hours of tracked work. */
    HOURS,

    /** Text this build cannot name, kept rather than guessed at. */
    OTHER;

    companion object {
        /**
         * Unknown unit text lands on [OTHER] rather than on a guess.
         *
         * A row written by a newer build may count something this one has no word for. [OTHER] is what lets the
         * screen state the count without naming what was counted, which is less information rather than false
         * information — and the alternative, reading a grant of 30 days as "30 tasks", is the kind of quiet
         * wrong number this table exists to avoid.
         */
        fun fromName(value: String?): AchievementUnit =
            entries.firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: OTHER
    }
}

/**
 * The four families §11 asks the achievements to be organised into, plus the shelf unknown text is put on.
 *
 * The families are about *what kind of claim* the achievement makes rather than which feature raised it, which
 * is what makes the grouping survive a new rule: a streak of any length is steadiness, a count of any size is
 * volume. A screen sorted by feature would have to be rewritten every time a rule is added, and §11 asks for
 * exactly the opposite.
 *
 * [BEGINNING] is a first time (a task finished, a goal reached, a project closed). [STEADINESS] is a thing kept
 * up across days. [VOLUME] is a quantity reached. [MILESTONE] is a whole arc completed — the goal or habit the
 * profile carried through, rather than the first one it ever carried. [OTHER] exists for a row whose family this
 * build does not know.
 */
enum class AchievementCategory {
    BEGINNING,
    STEADINESS,
    VOLUME,
    MILESTONE,
    OTHER;

    companion object {
        /**
         * Unknown family text lands on [OTHER] rather than on a guess.
         *
         * A row written by a newer build may name a family this one never heard of. Placing it under a real
         * heading would put the reader's own history inside a claim the app cannot support, and dropping it
         * would lose a grant they earned; [OTHER] is the honest shelf.
         */
        fun fromName(value: String?): AchievementCategory =
            entries.firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: OTHER
    }
}

/**
 * One achievement this profile has earned, with the reading it was granted on.
 *
 * The row is a record of a moment rather than a live calculation, which is the whole difference between this
 * table and `insights`. An insight is a reading the rules may stop agreeing with, so it carries `is_current`
 * and a pass retires it. An achievement happened: §11 asks for the date it was earned and for a history, and
 * neither of them can be rebuilt from today's rows once the counter that produced them moves. So the number,
 * its unit, its target and the two sentences the reader were shown are all written down and never derived
 * again.
 *
 * Nothing here is a nudge. There is no `notified_at`, no "unseen" flag and no badge counter, because §11 ends
 * with the instruction not to turn the app into an intrusive game: an achievement is offered on the screen the
 * reader opens, and it does not message them, does not count up how long they have avoided it, and does not
 * expire.
 *
 * The row is written once and never updated, which is also why there is no `updated_at`. Two passes that both
 * find the condition met land on the same unique index below, and the second one is refused rather than
 * re-dated — "не начисляй достижение повторно" is a property of the schema, not a rule a pass has to remember.
 */
@Entity(
    tableName = "achievements",
    indices = [
        Index(value = ["user_id"]),
        // The history is read newest-earned first, so the ordering is sought inside the profile rather than
        // sorted out of every grant it holds.
        Index(value = ["user_id", "earned_at"]),
        Index(value = ["uuid"], unique = true),
        // One grant per achievement per profile, enforced here rather than in the pass: a rule that fired twice
        // in one session, or a pass racing itself, collides on this index before it can date the same
        // achievement a second time.
        Index(value = ["user_id", "achievement_key"], unique = true)
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
data class Achievement(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /**
     * Stable identity used for cloud sync and imports; independent of the local row id.
     *
     * Derived from the account and the achievement key rather than random, so "already granted" is a lookup
     * the pass can do without racing itself, and a second profile on a shared device is not refused by the
     * first one's grant.
     */
    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    /** Which rule earned this, as a stable name: the address the no-repeat index is keyed on. */
    @ColumnInfo(name = "achievement_key")
    val achievementKey: String,

    /** Which of [AchievementCategory] the reader's history is filed under. */
    @ColumnInfo(name = "category")
    val category: AchievementCategory,

    /**
     * The heading as it read the day it was earned.
     *
     * Stored as finished text for the same reason an insight stores its sentences: the history has to keep
     * reading the way it read on that day, and a screen that formatted from the key would rewrite yesterday's
     * grant whenever the app's own wording changed.
     */
    @ColumnInfo(name = "title")
    val title: String,

    /** The condition, spelled out with the number it was met against: "100 tasks completed". */
    @ColumnInfo(name = "description")
    val description: String,

    /** What [progressValue] and [targetValue] count. */
    @ColumnInfo(name = "unit")
    val unit: AchievementUnit,

    /** The threshold the rule required. */
    @ColumnInfo(name = "target_value")
    val targetValue: Long,

    /** What the counter actually read when the grant was made, which may be past the threshold. */
    @ColumnInfo(name = "progress_value")
    val progressValue: Long,

    /** The day the grant was made, as an epoch day cut in the zone the app was reading then. */
    @ColumnInfo(name = "earned_day")
    val earnedDay: Long,

    /** When the condition was met, to the millisecond. */
    @ColumnInfo(name = "earned_at")
    val earnedAt: Long
) {
    companion object {
        /** The one id this profile reaches for this achievement, so two passes cannot land on two rows. */
        fun uuidFor(userId: Long, achievementKey: String): String =
            "achievement-$userId-$achievementKey"
    }
}
