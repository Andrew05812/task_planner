package com.taskplanner.data.model

/**
 * One group (project) whose every live task the reader finished.
 *
 * Two columns and nothing else, because the achievement rule that asks for it wants a count and a name: the
 * first project closed is stated by title, and a row carrying the group's own fields would be a second copy of
 * `task_groups` free to disagree with the list the reader opens.
 */
data class CompletedGroup(
    val groupUuid: String,
    val name: String?
)
