package com.taskplanner.data.repository

import android.content.SharedPreferences
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central owner of "who is logged in right now".
 *
 * The users table (is_current) is the source of truth; SharedPreferences keeps
 * the last known current user id as a repair anchor for the case where a crash
 * left is_current unset in the DB. There is exactly one code path that changes
 * the current user, serialized through a mutex, which removes profile-switching
 * races and the old "userId falls back to 0 / tasks saved to nobody" problem.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val userDao: UserDao,
    private val prefs: SharedPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val switchMutex = Mutex()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    init {
        scope.launch { restore() }
    }

    /** Reloads the session from disk; safe to call at any time (app start, process kill, after sync). */
    suspend fun restore() = switchMutex.withLock {
        var user = userDao.getCurrentUser()
        if (user == null) {
            val rememberedId = prefs.getLong(KEY_LAST_USER_ID, -1L)
            if (rememberedId != -1L) {
                userDao.getUserById(rememberedId)?.let { orphan ->
                    userDao.setCurrentUser(orphan)
                    user = userDao.getUserById(rememberedId)
                }
            }
        }
        val resolved = user
        if (resolved != null) {
            prefs.edit().putLong(KEY_LAST_USER_ID, resolved.id).apply()
        }
        _currentUser.value = resolved
    }

    /** Suspends until the session has been resolved (may be null = no user yet). */
    suspend fun awaitResolved() {
        if (_currentUser.value == null) {
            // One refresh attempt; restore() writes the resolved value even when null.
            if (!resolvedOnce) {
                restore()
                resolvedOnce = true
            }
        }
    }

    @Volatile
    private var resolvedOnce = false

    suspend fun setCurrentUser(user: User) = switchMutex.withLock {
        userDao.setCurrentUser(user)
        prefs.edit().putLong(KEY_LAST_USER_ID, user.id).apply()
        _currentUser.value = userDao.getCurrentUser()
        resolvedOnce = true
    }

    /**
     * Ends the local session without touching stored tasks/categories/groups.
     * Sign out must never delete user data.
     */
    suspend fun endSession() = switchMutex.withLock {
        userDao.clearCurrentUser()
        prefs.edit().remove(KEY_LAST_USER_ID).apply()
        _currentUser.value = null
        resolvedOnce = true
    }

    suspend fun currentUserId(): Long? = resolveUser()?.id

    /** Returns the session user, loading it from disk once if nothing is cached yet. */
    suspend fun resolveUser(): User? {
        _currentUser.value?.let { return it }
        restore()
        return _currentUser.value
    }

    fun snapshotUserId(): Long? = _currentUser.value?.id

    companion object {
        private const val KEY_LAST_USER_ID = "session_last_user_id"
    }
}
