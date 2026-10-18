package com.taskplanner.data.model

enum class TaskStatus {
    TODO,
    IN_PROGRESS,

    /**
     * Execution was started and then paused. Added in Stage 1; old rows never
     * contain this value, so existing databases stay readable.
     */
    PAUSED,

    /**
     * Completed ("DONE") task. The constant name COMPLETED is kept because
     * historical databases store this exact string in the status column.
     */
    COMPLETED
}
