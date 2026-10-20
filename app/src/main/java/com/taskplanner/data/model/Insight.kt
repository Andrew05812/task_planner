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
 * How much of what an insight says is already known to have happened.
 *
 * The three are not three tones of one claim; they answer to different evidence, and §7 asks the screen to
 * say which one the reader is being handed:
 *
 *  * [FACT] is a count over rows that exist — three high-priority tasks are past their deadline right now.
 *    Nothing is inferred, and the number can be checked by opening the tasks.
 *  * [PATTERN] is a regularity the rows show over a stretch of time — most of what got finished was finished
 *    on Tuesdays. It needs more than one observation to be worth saying, so every pattern rule carries the
 *    size of the sample it was computed from and stays silent below it.
 *  * [HYPOTHESIS] is a reading of what the rows point at rather than what they hold: Thursday looks heavy
 *    against the weeks the profile has actually worked. Tomorrow is not in the database, and an insight that
 *    pretended it was would be the app inventing a fact.
 */
enum class InsightKind {
    FACT,
    PATTERN,
    HYPOTHESIS;

    companion object {
        /** A row written by a newer build is read as the claim with the least reach, not thrown away. */
        fun fromName(value: String?): InsightKind =
            entries.firstOrNull { it.name == value?.uppercase(Locale.ROOT) } ?: FACT
    }
}

/**
 * What the screen offers beside an insight, as a question about the app rather than about the work.
 *
 * An insight never changes anything of its own: §8 forbids automatic rescheduling and automatic priority
 * changes, and a rule that could not act has no reason to pretend it could. What it *can* do is take the
 * reader to the rows it counted, which is the difference between "Thursday looks heavy" and a screen that
 * says it and leaves the day four taps away. [ref] is the target's local row id or epoch day, as text —
 * insights are this device's reading of this device's rows, so they name this device's addresses.
 */
enum class InsightAction {
    TASK,
    DAY,
    GOAL,
    HABIT,
    CATEGORY,
    CALENDAR_WEEK;

    companion object {
        /** Unknown text reads as no action at all, which is the only safe answer about a target this build cannot name. */
        fun fromName(value: String?): InsightAction? =
            entries.firstOrNull { it.name == value?.uppercase(Locale.ROOT) }
    }
}

/**
 * What the reader did with an insight.
 *
 * Four answers, and they are not four flavours of one: [ACCEPTED] is "this is true and I mean to act",
 * [REJECTED] is "this is not true of me", [HIDDEN] is "true, but not something I want shown", and [USEFUL]
 * is "true and worth knowing" with no action behind it. The distinction matters because §7 asks for the
 * feedback to change what comes back: a rejected rule is being told its reading of the profile is wrong,
 * while a hidden one is only being told the moment is bad — and both of them stop the same row from being
 * offered again for the window it was counted over.
 */
enum class InsightFeedback {
    ACCEPTED,
    REJECTED,
    HIDDEN,
    USEFUL;

    companion object {
        /** Text this build does not know is read as no answer given, which leaves the row offered rather than silently retired. */
        fun fromName(value: String?): InsightFeedback? =
            entries.firstOrNull { it.name == value?.uppercase(Locale.ROOT) }
    }
}

/**
 * One thing the app noticed about the profile, with the evidence that produced it.
 *
 * The row is a reading, not a record of the work: everything here is derived from `tasks`, `work_sessions`,
 * goals and habits at the moment the rules ran, which is why the table carries no progress column, no
 * foreign key into the rows it counted, and no sync state. A second device derives its own reading; the
 * only thing that could not be rebuilt is what the user *said* about it, so [feedback] and [firstSeenAt] are
 * the two fields nothing else on the row can supply — and they are the reason a refreshed insight is updated
 * in place rather than replaced.
 *
 * [title], [body] and [basis] are stored as finished text rather than as codes the screen decodes, because
 * an insight's history has to keep reading the way it read on the day it was raised. A screen that
 * formatted from a key would rewrite yesterday's card whenever its own words changed.
 */
@Entity(
    tableName = "insights",
    indices = [
        Index(value = ["user_id"]),
        // The list is offered newest-observed first, and the history is read behind it; both of them ask
        // this of one profile, so the ordering has to be sought rather than sorted out of every row.
        Index(value = ["user_id", "last_seen_at"]),
        Index(value = ["uuid"], unique = true),
        // "No repeats" written as a database rule: one rule, counted over one window, is one row. A pass
        // that found the same thing again refreshes that row; a duplicate would have to collide here first.
        Index(
            value = ["user_id", "rule_key", "period_from_day", "period_to_day"],
            unique = true
        )
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
data class Insight(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /**
     * Derived from the rule and the window rather than random.
     *
     * Two passes over the same window have to reach the same row — that is the whole of §7's "no repeats" —
     * and an id computed from the key makes it so without a lookup that could race with itself.
     */
    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "user_id")
    val userId: Long,

    /** Which rule raised this, as a stable name: it is what a rejected insight retires on the next pass. */
    @ColumnInfo(name = "rule_key")
    val ruleKey: String,

    /** Whether the claim is a count, a regularity, or a reading of what the rows point at. */
    @ColumnInfo(name = "kind")
    val kind: InsightKind,

    @ColumnInfo(name = "title")
    val title: String,

    /** The claim in the reader's own terms, one or two sentences long. */
    @ColumnInfo(name = "body")
    val body: String,

    /**
     * The evidence, spelled out with its numbers: "9 of 14 finished tasks landed before 18:00".
     *
     * §7 asks every recommendation to carry the ground it stands on rather than a bare mood, and an
     * unverifiable "you seem busy" is exactly what the requirement was written against.
     */
    @ColumnInfo(name = "basis")
    val basis: String,

    /** The window the rule counted over, as epoch days — inclusive bounds, the same pair the figures use. */
    @ColumnInfo(name = "period_from_day")
    val periodFromDay: Long,

    @ColumnInfo(name = "period_to_day")
    val periodToDay: Long,

    /** How many rows the rule actually looked at, which is what its minimum sample is measured against. */
    @ColumnInfo(name = "sample_size")
    val sampleSize: Int,

    /**
     * How far the evidence reaches, in whole percent.
     *
     * Computed by the rule from its own sample rather than guessed at, and capped below 100 because a
     * reading of a handful of rows is never a certainty. A rule that could not say this number would have
     * to print one anyway, which is how an app ends up sounding more sure of a profile than the profile is.
     */
    @ColumnInfo(name = "confidence")
    val confidence: Int,

    /** The screen's jump target, or null when the insight is worth knowing and leads nowhere. */
    @ColumnInfo(name = "action_type")
    val actionType: InsightAction? = null,

    /** The target's local id or epoch day, as text, in the sense [InsightAction] names. */
    @ColumnInfo(name = "action_ref")
    val actionRef: String? = null,

    /** When this rule first said the thing, which a refresh never overwrites. */
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,

    /** The last pass that still found it true. */
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,

    /**
     * Whether the newest pass still stands behind this reading.
     *
     * The pair [lastSeenAt] cannot form on its own: "when was this said" and "is it being said now" are two
     * questions, and a screen that answered the second from the first would have to know when the newest pass
     * ran — a clock the row does not carry. A rule that stops firing turns this off and the row becomes
     * history; a rule that fires again turns it back on without losing the words the reader first saw.
     */
    @ColumnInfo(name = "is_current", defaultValue = "1")
    val isCurrent: Boolean = true,

    /** What the reader said, or null while they have not said anything. */
    @ColumnInfo(name = "feedback")
    val feedback: InsightFeedback? = null,

    @ColumnInfo(name = "feedback_at")
    val feedbackAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Whether the live list may show this.
     *
     * A rejected or hidden reading is not offered again — §7 asks the feedback to change what comes back,
     * and a card that reappeared after "not true of me" teaches the reader that the buttons do nothing.
     * Accepted and useful are both keepers: one is a thing the reader means to do, the other a thing they
     * found worth knowing, and neither is a request to stop saying it.
     */
    val isOffered: Boolean
        get() = feedback != InsightFeedback.REJECTED && feedback != InsightFeedback.HIDDEN

    companion object {
        /** The one id this rule reaches for this window, so two passes cannot land on two rows. */
        fun uuidFor(ruleKey: String, periodFromDay: Long, periodToDay: Long): String =
            "insight-$ruleKey-$periodFromDay-$periodToDay"
    }
}
