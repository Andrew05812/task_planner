package com.taskplanner.data.repository

import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.model.SyncState
import com.taskplanner.data.model.TaskGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GroupRepository @Inject constructor(
    private val groupDao: GroupDao,
    private val sessionRepository: SessionRepository
) {
    fun observeGroups(): Flow<List<TaskGroup>> =
        sessionRepository.currentUser.flatMapLatest { user ->
            if (user == null) flowOf(emptyList()) else groupDao.getGroups(user.id)
        }

    suspend fun getGroupsOnce(): List<TaskGroup> {
        val userId = sessionRepository.currentUserId() ?: return emptyList()
        return groupDao.getGroupsOnce(userId)
    }

    suspend fun createGroup(name: String): TaskGroup? {
        val userId = sessionRepository.currentUserId() ?: return null
        val group = TaskGroup(
            userId = userId,
            name = name.trim(),
            sortOrder = groupDao.nextSortOrder(userId)
        )
        groupDao.upsertGroup(group)
        return group
    }

    suspend fun rename(uuid: String, name: String) = groupDao.rename(uuid, name.trim())

    suspend fun setCollapsed(uuid: String, collapsed: Boolean) = groupDao.setCollapsed(uuid, collapsed)

    /** Removing a topic keeps its tasks: they simply become ungrouped. */
    suspend fun deleteGroup(uuid: String) = groupDao.softDelete(uuid)

    suspend fun upsertRaw(group: TaskGroup) = groupDao.upsertGroup(group)

    suspend fun getByUuid(uuid: String): TaskGroup? = groupDao.getGroupByUuid(uuid)

    companion object {
        fun newGroupUuid(): String = java.util.UUID.randomUUID().toString()
    }
}
