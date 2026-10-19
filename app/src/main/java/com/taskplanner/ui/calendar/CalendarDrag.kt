package com.taskplanner.ui.calendar

import android.content.ClipData
import android.content.ClipDescription

/**
 * The payload of a drag from one day cell to another.
 *
 * Android hands the drop side a [ClipData], and what a calendar drop needs is one row id, so the id
 * travels as plain text and everything else about the task is read back from the database — a drag
 * never carries a stale copy of a task that was edited while the finger was down.
 *
 * Encoding and decoding live here rather than in the listeners because a drop cannot tell where the
 * drag started: a clip from another app, or a task row that has since been deleted, arrives as
 * something this screen must refuse instead of parse optimistically.
 */
object CalendarDrag {

    /**
     * Machine-facing rather than user-facing: the accessibility label the system files the clip
     * under, never rendered by this app.
     */
    private const val CLIP_LABEL = "com.taskplanner.calendar.TASK"

    /** One plain-text item, so a drop that lands outside this app cannot see anything of a task. */
    val mimeType: String = ClipDescription.MIMETYPE_TEXT_PLAIN

    fun clipOf(taskId: Long): ClipData =
        ClipData(CLIP_LABEL, arrayOf(mimeType), ClipData.Item(taskId.toString()))

    /**
     * The task this drag is carrying, or null when the clip is not one this screen started.
     *
     * A row id is positive and it is one number on its own: text a person copied can hold digits, so
     * a drop that only asked "can this be parsed" would move a task on the strength of something that
     * was never dragged.
     */
    fun taskIdOf(clip: ClipData?): Long? =
        clip?.takeIf { it.itemCount == 1 }
            ?.getItemAt(0)?.text
            ?.toString()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
}
