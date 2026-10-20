package com.taskplanner.data.habit

import com.taskplanner.data.model.Habit

/** The three things a habit reminder's notification can be asked to do, in its request-code slot. */
enum class HabitReminderAction { OPEN, MARK, SNOOZE }

/**
 * The notification and alarm identity of one habit's reminder.
 *
 * A task reminder is a row, so its identity is the row's uuid. A habit reminder is a rule — a minute of
 * the day on a schedule the habit itself carries — and the schema keeps it out of the `reminders` table on
 * purpose: those reads join on `tasks` and retire themselves when a task is archived, and a habit has no
 * deadline for them to hang off. So the identity is derived from the habit's uuid instead, deterministically,
 * and it has the same properties the row-based one has: re-arming a habit puts its alarm back over the one
 * that was already there rather than stacking a second, and the notification replaces itself instead of
 * leaving a pile of yesterday's asks in the shade.
 *
 * Every function here is keyed on the uuid rather than on the row, because a habit that has been deleted no
 * longer has a row and its notification still has to be taken away. The uuid is the key that survives it.
 *
 * One habit owns one slot, and one slot holds one pending wakeup, which is the whole shape of a repeating
 * schedule: at most the next minute is ever armed. Whether the day has already been announced is a fact
 * about the day rather than about the slot, and it is [Habit.remindedOnDay] that carries it, so an alarm,
 * a reboot and the six-hourly sweep can all reach the same rule and only the first of them speaks.
 *
 * The mask and the stride are the ones [com.taskplanner.data.model.Reminder] uses, so a habit's slot and a
 * task reminder's slot are drawn from the same 28-bit space. They cannot be the *same key* — a habit's
 * uuid never appears as a reminder uuid — but two different keys can hash alike, exactly as two reminders
 * of two different tasks already can. That is the risk this app takes everywhere it derives an id from a
 * uuid, and it is taken rather than worked around by a second id scheme the notification manager would
 * not know how to group.
 */
object HabitReminderIdentity {

    /** Actions are packed into the request code with the same stride the task reminders use. */
    private const val ACTION_STRIDE = 8

    /** How many bits of the hash are kept, so a slot plus its stride stays a positive Int. */
    private const val ID_MASK = 0x0FFFFFFF

    fun notificationIdOf(habitUuid: String): Int = habitUuid.hashCode() and ID_MASK

    fun requestCodeOf(habitUuid: String, action: HabitReminderAction): Int =
        notificationIdOf(habitUuid) * ACTION_STRIDE + action.ordinal

    /**
     * The alarm's own slot, after the actions'. The wakeup broadcast and the notification's buttons are
     * all broadcasts to the same receiver, and a shared request code would let one overwrite the other.
     */
    fun alarmRequestCodeOf(habitUuid: String): Int =
        notificationIdOf(habitUuid) * ACTION_STRIDE + HabitReminderAction.entries.size

    /**
     * The link a tapped habit notification carries, in the shape the task link already uses.
     *
     * It names the habit's row id rather than its uuid for the same reason the task link does: local ids
     * are what the screen on this device resolves, and the uuid is what travels between devices.
     */
    fun habitDeepLink(habitId: Long): String = "taskplanner://habit/$habitId"
}
