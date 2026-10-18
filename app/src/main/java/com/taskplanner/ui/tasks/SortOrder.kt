package com.taskplanner.ui.tasks

enum class SortOrder {
    /** User-controlled order via drag handle; persisted in the tasks.order column. */
    MANUAL,
    DATE_DESC,
    DATE_ASC,
    PRIORITY_DESC,
    PRIORITY_ASC,
    NAME_ASC,
    NAME_DESC
} 