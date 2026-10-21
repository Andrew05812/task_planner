package com.taskplanner.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.taskplanner.R
import com.taskplanner.data.achievement.AchievementFigures
import com.taskplanner.data.achievement.AchievementRule
import com.taskplanner.data.achievement.AchievementRules
import com.taskplanner.data.achievement.Grant
import com.taskplanner.data.calendar.CalendarMath
import com.taskplanner.data.habit.StreakUnit
import com.taskplanner.data.local.AchievementDao
import com.taskplanner.data.local.AchievementStatsDao
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.GoalDao
import com.taskplanner.data.local.HabitDao
import com.taskplanner.data.model.Achievement
import com.taskplanner.data.model.AchievementCategory
import com.taskplanner.data.model.AchievementUnit
import com.taskplanner.data.tracking.TimeBase
import com.taskplanner.data.tracking.WorkTicker
import com.taskplanner.data.tracking.WorkTimeMath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * What one pass of the achievement rules did, as counts rather than as a log.
 *
 * [considered] is how many rules had a figure to judge at all, and [met] how many of those the profile has
 * reached. The difference between them is what a screen needs when the reader asks why nothing happened: a
 * profile with no habits has not failed the streak achievements, it has no streak to be measured against.
 * [granted] is what this pass wrote, and [alreadyEarned] is the part of [met] it left unwritten — a grant that
 * was on the table before the pass ran, or one that arrived between reading the keys and writing its own row.
 * Either way the §7 guarantee is stated as a number rather than assumed.
 */
data class AchievementPass(
    val considered: Int = 0,
    val met: Int = 0,
    val granted: Int = 0,
    val alreadyEarned: Int = 0
)

/**
 * One achievement as the screen holds it: earned, or still ahead of the reader with the real figure under it.
 *
 * The row is here as it was written when it was granted, wording and all, while a locked achievement carries
 * only resource ids and the number its own rule reads. That asymmetry is deliberate and it is the same one
 * [Insight] keeps: an earned grant is a memory, and a memory has to keep reading the way it read on the day
 * it was earned even after the app's phrasing changes. A locked one is not a memory of anything — it is the
 * condition as the app states it today, against the progress the profile has actually made.
 *
 * [progress] is null when there is nothing to count, which is not the same claim as zero: a profile with no
 * habits has no series to be seven days short of, and the screen says "not started" rather than printing a
 * figure the reader never had the chance to reach.
 */
data class AchievementOverview(
    val key: String,
    val category: AchievementCategory,
    val unit: AchievementUnit,
    val target: Long,
    val titleRes: Int,
    val conditionRes: Int,
    val progress: Long?,
    val earned: Achievement?
) {
    val isEarned: Boolean get() = earned != null
}

/**
 * The achievements the reader has earned, and the figures they were earned on.
 *
 * Three decisions shape the class, and every one of them is a §7 requirement rather than a taste:
 *
 *  * **The repository reads, the rules decide, the strings finish the sentence** — the same split the insights
 *    are built on. [AchievementRules] answers "how far has this profile come" and nothing else; the threshold
 *    is applied here, once, for every rule, so a new achievement cannot get "is it earned yet" wrong and two
 *    rules cannot disagree about what reaching means. The wording is rendered from `res/values/strings.xml`
 *    after the decision, from the rule's two resource ids and its unit, so adding a rule adds no branch.
 *  * **A grant is given once, and never again.** A pass reads the keys already on the table and skips them,
 *    and what it does write goes in through `INSERT OR IGNORE` against the unique index on
 *    `(user_id, achievement_key)` — the database, not the loop, holds the promise when two passes race. No
 *    statement in [AchievementDao] updates or deletes a row, so a grant cannot be re-dated, re-worded, or
 *    taken back by a pass that measures less than the one that wrote it.
 *  * **Nothing here writes to the work.** An achievement is a reading of what the reader did, so this class
 *    issues no update against `tasks`, `work_sessions`, `goals` or `habits` — only reads, and writes to its
 *    own table. The counters themselves are chosen so that a cancelled task cannot pay out: finished work is
 *    counted by the instant the task was completed, which is the instant the app sets back to null when the
 *    reader takes the completion away.
 *
 * There is no notification in here, and no unseen badge. §7 asks that the app not become a nagging game:
 * the screen shows what was earned when the reader opens it, and a grant that nobody has looked at yet is
 * worth exactly as much as one that has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AchievementRepository @Inject constructor(
    private val database: AppDatabase,
    private val achievementDao: AchievementDao,
    private val achievementStatsDao: AchievementStatsDao,
    private val goalDao: GoalDao,
    private val habitDao: HabitDao,
    private val goalRepository: GoalRepository,
    private val habitRepository: HabitRepository,
    private val sessionRepository: SessionRepository,
    private val workTicker: WorkTicker,
    private val timeBase: TimeBase,
    @ApplicationContext private val context: Context
) {

    /**
     * Runs the rules over the profile and writes down what the reader has earned that they did not have before.
     *
     * The figures are read outside the transaction and only the grants inside it, exactly as an insight pass
     * does: a lifetime aggregate is not something to hold a write lock through, and a pass that read and wrote
     * as one statement would make the user's own writes queue behind the counting.
     *
     * A signed-out profile is not an error but a nothing — no rows belong to anybody, so there is nothing to
     * count and nothing to grant.
     */
    suspend fun refresh(): AchievementPass {
        val userId = sessionRepository.currentUserId() ?: return AchievementPass()
        val zone = CalendarMath.systemZone()
        val now = timeBase.wallMillis()
        val today = WorkTimeMath.epochDayOf(now, zone)
        val figures = collectFigures(userId, today, zone)
        val alreadyEarned = achievementDao.earnedKeys(userId).toSet()

        var considered = 0
        var met = 0
        val pending = mutableListOf<Pair<AchievementRule, Grant>>()
        AchievementRules.ALL.forEach { rule ->
            val counter = rule.counter(figures) ?: return@forEach
            considered++
            if (counter < rule.target) return@forEach
            met++
            if (rule.key in alreadyEarned) return@forEach
            pending += rule to Grant(
                key = rule.key,
                category = rule.category,
                unit = rule.unit,
                ruleVersion = rule.version,
                target = rule.target,
                progress = counter,
                earnedDay = today,
                earnedAt = now
            )
        }

        var granted = 0
        var skipped = met - pending.size
        // The sentences are put together before the lock is taken, not inside it. Reading a string resource is
        // not a database read, but it is work the write lock has no reason to cover, and this is the one place
        // the pass holds that lock at all — every other read here happens outside it, for the same reason the
        // figures do.
        val rows = pending.map { (rule, grant) -> rowOf(userId, rule, grant, wordingOf(rule, grant.progress)) }
        database.withTransaction {
            rows.forEach { row ->
                // The ignore is the race, not the common case: the key was not in `alreadyEarned` a moment
                // ago, and if the unique index refused the row, another pass granted it first and said the
                // same thing about the same figure.
                if (achievementDao.insertOrIgnore(row) > 0L) {
                    granted++
                } else {
                    skipped++
                }
            }
        }
        return AchievementPass(
            considered = considered,
            met = met,
            granted = granted,
            alreadyEarned = skipped
        )
    }

    /** The grants this profile earned, newest first, empty while no profile is signed in. */
    fun observeAchievements(): Flow<List<Achievement>> = forProfile { achievementDao.observeEarned(it) }

    /**
     * Every achievement the app knows, earned or not, with the figure standing behind each one.
     *
     * This is the read the achievements screen is built on: §7 asks for the conditions and the visual
     * grouping as well as the history, and a list of grants alone cannot show what is still ahead of the
     * reader. The re-read is anchored on the day rather than on the clock — the day is the one thing that
     * changes a figure without a write (a habit's series crosses into a new asked day at midnight), and the
     * same [WorkTicker] that re-anchors a habit card re-anchors this. A new grant is itself a write to the
     * table the first Flow listens to, so a pass that hands something out reaches the screen in the same frame
     * it reached the database.
     *
     * The figures are collected once per emission and shared by every rule, so a screen holding the whole
     * catalogue runs one set of aggregates rather than one per card.
     */
    fun observeOverview(zone: ZoneId = CalendarMath.systemZone()): Flow<List<AchievementOverview>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            val userId = user?.id ?: return@flatMapLatest flowOf(emptyList())
            combine(achievementDao.observeEarned(userId), workTicker.localDays(zone)) { earned, onDay ->
                val figures = collectFigures(userId, onDay.toEpochDay(), zone)
                val byKey = earned.associateBy { it.achievementKey }
                AchievementRules.ALL.map { rule ->
                    AchievementOverview(
                        key = rule.key,
                        category = rule.category,
                        unit = rule.unit,
                        target = rule.target,
                        titleRes = rule.titleRes,
                        conditionRes = rule.conditionRes,
                        progress = rule.counter(figures),
                        earned = byKey[rule.key]
                    )
                }
            }
        }

    // -- the figures the rules are handed -------------------------------------------------------------

    /**
     * The lifetime counters, read once per pass.
     *
     * Two of them are not aggregates of this table's own dao, and both are read through the repository that
     * owns the arithmetic: whether a goal was reached and how long a habit's series ran are questions the goals
     * and habits screens already answer, out of each goal's type and each habit's schedule. Re-stating them
     * here would put a second definition of "reached" and of "kept" in the app, free to disagree with the
     * screen that owns them the first time one of those definitions changed. What the achievements ask differs
     * in one way only: the period, which for a grant is the reader's whole life rather than the four weeks a
     * habit card prints.
     *
     * One coupling comes with reading through another screen's repository, and it is worth naming rather than
     * leaving for the next reader to trip over: [com.taskplanner.data.repository.GoalRepository.progressOf] and
     * [com.taskplanner.data.repository.HabitRepository.figuresOf] resolve the signed-in account from the session
     * themselves rather than taking the [userId] this pass was given. In the ordinary run they agree, because
     * the pass asks for the current user's own figures — which is the only way this method is reached. They do
     * not agree if the reader switches account between this method's own read and those two, and this pass does
     * not close that window: it is the same session race §11's account-aware work has to settle, and a caller
     * that wanted another profile's history would have to thread the account through explicitly anyway.
     */
    private suspend fun collectFigures(userId: Long, today: Long, zone: ZoneId): AchievementFigures =
        AchievementFigures(
            tasksEverCompleted = achievementStatsDao.completedTasksEver(userId),
            goalsEverReached = goalsEverReached(userId, LocalDate.ofEpochDay(today), zone),
            projectsEverCompleted = achievementStatsDao.completedGroupCount(userId),
            habitBestStreaks = habitBestStreaks(userId, today, zone),
            habitMarksEver = achievementStatsDao.habitMarks(userId),
            activeDays = achievementStatsDao.activeDays(userId),
            sessionsEverMeasured = achievementStatsDao.measuredSessions(userId),
            trackedHours = achievementStatsDao.measuredSeconds(userId) / SECONDS_PER_HOUR
        )

    /**
     * Goals carried to their target, including the ones since archived or retired.
     *
     * Tombstones are skipped and archived goals are not, because a goal the reader put away after reaching it
     * was still reached, while a goal deleted out of the account was never one they set. The read is the
     * profile's own goal list rather than a window over its work rows: goals are things a person typed in one
     * at a time, and the aggregate each one needs is the same one its card already runs.
     */
    private suspend fun goalsEverReached(userId: Long, onDay: LocalDate, zone: ZoneId): Long =
        goalDao.getAllForUser(userId)
            .count { goal -> !goal.isDeleted && goalRepository.progressOf(goal, onDay, zone).reached }
            .toLong()

    /**
     * Each habit's longest run of kept asked days, read over the habit's whole life.
     *
     * The window starts at the day the habit began and ends at the day being measured, with no cap in between.
     * Nothing before the start day can answer a question about this habit, and everything after it can — which
     * is the property §7.1's "never grant the same achievement twice" is built on. A run measured over a window
     * would be a window figure wearing a lifetime name: a reader who imported a year-old account, or whose
     * thirty-day series ended thirteen months ago, would be handed a history that contradicts the habit's own
     * detail screen, whose grid is read over the whole life and still shows those thirty days.
     *
     * That is the one place where this figure and a habit card part company on purpose. The card answers a
     * question the screen asks — kept over the last four weeks — and [com.taskplanner.ui.habits.HabitCard] names
     * that period in its own words so its figure cannot be read as a whole life; the achievement answers a
     * question about a thing the reader did, and a thing done does not stop having been done when it leaves a
     * window. Both readings come from the same [com.taskplanner.data.habit.HabitMath.streak] over the same
     * marks, so they agree about the arithmetic and differ only about the period they were asked for.
     *
     * Habits the reader archived are in, and ones they deleted out of the account are not. An archived habit was
     * kept while it was open, so its series is a fact the achievements read; a deleted one is a tombstone the
     * same way a deleted task is, and no work was carried through it.
     *
     * A habit whose frequency makes it count weeks rather than days contributes nothing. "Seven days in a
     * row" is a claim about days, and a run of seven kept weeks is a different claim, stated on that habit's
     * own card in its own unit rather than converted into one it never made.
     */
    private suspend fun habitBestStreaks(userId: Long, today: Long, zone: ZoneId): List<Int> =
        habitDao.getOpenHabits(userId).mapNotNull { habit ->
            val streak = habitRepository.figuresOf(habit, habit.startDay, today, zone).streak
            streak.best.takeIf { streak.unit == StreakUnit.DAYS }
        }

    private fun forProfile(read: (Long) -> Flow<List<Achievement>>): Flow<List<Achievement>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            val userId = user?.id
            if (userId == null) flowOf(emptyList()) else read(userId)
        }

    // -- the sentence a grant becomes -----------------------------------------------------------------

    private data class Wording(val title: String, val description: String)

    /**
     * The achievement in the reader's language, from the figure that met it.
     *
     * One branch for the whole set: a title the rule names, and a description built the same way for every
     * achievement — the figure, in the plural its unit selects, set inside one shared sentence. A new rule
     * that counts tasks, goals, projects, habits, days or hours needs no new wording code at all, which is
     * what lets §7's new achievement be a new object rather than a new release of the screen.
     */
    private fun wordingOf(rule: AchievementRule, progress: Long): Wording = Wording(
        title = context.getString(rule.titleRes),
        description = context.getString(
            R.string.achievement_basis,
            quantity(unitPlural(rule.unit), progress)
        )
    )

    /** The noun this achievement counted, so a stored number keeps saying what it is a number of. */
    private fun unitPlural(unit: AchievementUnit): Int = when (unit) {
        AchievementUnit.TASKS -> R.plurals.achievement_unit_tasks
        AchievementUnit.GOALS -> R.plurals.achievement_unit_goals
        AchievementUnit.PROJECTS -> R.plurals.achievement_unit_projects
        AchievementUnit.HABITS -> R.plurals.achievement_unit_habits
        AchievementUnit.MARKS -> R.plurals.achievement_unit_marks
        AchievementUnit.SESSIONS -> R.plurals.achievement_unit_sessions
        AchievementUnit.STREAK_DAYS -> R.plurals.achievement_unit_streak_days
        AchievementUnit.ACTIVE_DAYS -> R.plurals.achievement_unit_active_days
        AchievementUnit.HOURS -> R.plurals.achievement_unit_hours
        AchievementUnit.OTHER -> R.plurals.achievement_unit_other
    }

    private fun quantity(resId: Int, count: Long): String {
        val whole = count.toInt()
        return context.resources.getQuantityString(resId, whole, whole)
    }

    /**
     * The row a grant becomes: the figure, the wording, and the day and instant it was reached at.
     *
     * The uuid comes from the account and the key alone, so a second pass over the same achievement reaches
     * the same row and the unique index is what refuses it, rather than a lookup that could race with itself.
     */
    private fun rowOf(userId: Long, rule: AchievementRule, grant: Grant, wording: Wording): Achievement =
        Achievement(
            uuid = grant.uuid(userId),
            userId = userId,
            achievementKey = rule.key,
            ruleVersion = grant.ruleVersion,
            category = grant.category,
            title = wording.title,
            description = wording.description,
            unit = grant.unit,
            targetValue = grant.target,
            progressValue = grant.progress,
            earnedDay = grant.earnedDay,
            earnedAt = grant.earnedAt
        )

    private companion object {
        private const val SECONDS_PER_HOUR = 3_600L
    }
}
