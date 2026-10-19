package com.taskplanner.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * The text forms the tracking dialogs speak: an instant as `yyyy-MM-dd HH:mm` and a day as `yyyy-MM-dd`.
 *
 * Both are written out in full and parsed by `java.time` rather than picked from three spinners, because
 * the one thing a hand-entered measurement must keep is the instant the user meant: a partly-specified
 * control silently fills in a day, a month, or an hour the user never named, and a correction whose
 * numbers cannot be traced back to what was typed makes the history record useless. The forms are also
 * unambiguous under Robolectric, where a calendar control would need a device to be tested at all.
 *
 * An unparseable text is `null` rather than a best guess: the screen then says which field is wrong and
 * writes nothing.
 */
object WorkInstantInput {

    /**
     * The instant form, asked of `java.time` in strict resolution.
     *
     * `uuuu` rather than `yyyy` because strict mode reads `yyyy` as a year of an era and refuses a date
     * that names no era; the digits it accepts and prints are the same. Strict resolution is the point:
     * the default mode quietly moves `2025-02-29` to the 28th, and a measurement corrected to a day the
     * user never wrote is exactly the best guess this object exists to refuse.
     */
    private val instantFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)

    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    /** A day the user reads rather than types, so the month name follows the device locale. */
    private val longDayFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /** The instant the text names, in [zone], or null when it names none. */
    fun parse(text: CharSequence?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val value = text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(value, instantFormat).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** How the instant [millis] is written back into a field, so editing shows what is stored. */
    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        instantFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** How the current instant of day [millis] reads, for a screen that labels a stretch. */
    fun timeOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    /** The local day the text names, or null when it names none. */
    fun parseDay(text: CharSequence?): LocalDate? {
        val value = text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(value, dayFormat)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** A local day in the form [parseDay] reads back. */
    fun formatDay(day: LocalDate): String = dayFormat.format(day)

    /** A local day the way a period label reads, for a user who needs the month and year. */
    fun formatDayLong(day: LocalDate): String = longDayFormat.format(day)
}
