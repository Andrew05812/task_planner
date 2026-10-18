package com.taskplanner.ui.util

import android.content.Context
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object EditTextDialog {

    fun prompt(
        context: Context,
        title: String,
        hint: String,
        prefill: String = "",
        positive: String,
        onResult: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            this.hint = hint
            setText(prefill)
            setSelection(prefill.length)
            maxLines = 1
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(positive) { _, _ -> onResult(input.text.toString()) }
            .setNegativeButton(com.taskplanner.R.string.cancel, null)
            .show()
    }
}
