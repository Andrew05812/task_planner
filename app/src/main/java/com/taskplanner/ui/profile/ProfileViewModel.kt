package com.taskplanner.ui.profile

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.R
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.ProfileCreation
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _profileState = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val profileState: StateFlow<ProfileState> = _profileState.asStateFlow()

    private val _profiles = MutableStateFlow<List<User>>(emptyList())
    val profiles: StateFlow<List<User>> = _profiles.asStateFlow()

    init {
        // The session repository is the only owner of "who is current"; this screen
        // just mirrors it, so a process kill or a profile switch can't diverge.
        viewModelScope.launch {
            sessionRepository.currentUser.collect { user ->
                _profileState.value = if (user == null) ProfileState.NoUser
                else ProfileState.Success(user)
            }
        }
        viewModelScope.launch {
            userRepository.getAllUsers().collect { users -> _profiles.value = users }
        }
        loadCurrentUser()
    }

    fun loadCurrentUser() {
        viewModelScope.launch {
            try {
                sessionRepository.restore()
            } catch (e: Exception) {
                Log.w(TAG, "profile could not be read", e)
                _profileState.value = ProfileState.Error(R.string.error_load_profile)
            }
        }
    }

    fun loadProfiles() {
        viewModelScope.launch {
            runCatching { userRepository.getAllUsersOnce() }
                .onSuccess { _profiles.value = it }
        }
    }

    fun createUser(name: String, email: String) {
        viewModelScope.launch {
            try {
                val trimmedEmail = email.trim()
                if (trimmedEmail.isBlank()) {
                    // No address to check against, and the profile gets an unreachable placeholder — which is
                    // the only kind of blank this form accepts.
                    createProfile(User(
                        name = name.trim(),
                        email = "local-${UUID.randomUUID()}@local.invalid",
                        avatarInitial = name.firstOrNull()?.toString()?.uppercase() ?: "?",
                        avatarColor = generateAvatarColor(name)
                    ))
                    return@launch
                }
                // The check stays because it is the friendly answer: it arrives before the write and names the
                // field the reader has to fix. It is not what protects the data — the unique index is, and the
                // repository turns its refusal into the same message for the two-request case this one misses.
                if (userRepository.isEmailTaken(trimmedEmail)) {
                    _profileState.value = ProfileState.Error(R.string.error_email_taken)
                    return@launch
                }
                createProfile(User(
                    name = name.trim(),
                    email = trimmedEmail,
                    avatarInitial = name.firstOrNull()?.toString()?.uppercase() ?: "?",
                    avatarColor = generateAvatarColor(name)
                ))
            } catch (e: Exception) {
                Log.w(TAG, "profile could not be created", e)
                _profileState.value = ProfileState.Error(R.string.error_create_profile)
            }
        }
    }

    /**
     * Writes the profile and moves to it, or says the address is taken.
     *
     * The repository answers with one of those two and never with a third where the existing profile was
     * rewritten, because rewriting one is what used to happen and it deleted the profile that owned the
     * address along with everything cascading off it.
     */
    private suspend fun createProfile(user: User) {
        when (val creation = userRepository.createUser(user)) {
            is ProfileCreation.Created -> sessionRepository.setCurrentUser(creation.user)
            ProfileCreation.EmailTaken ->
                _profileState.value = ProfileState.Error(R.string.error_email_taken)
        }
    }

    fun updateUser(userId: Long, name: String, email: String) {
        viewModelScope.launch {
            try {
                val existingUser = userRepository.getUserById(userId) ?: run {
                    _profileState.value = ProfileState.Error(R.string.error_profile_missing)
                    return@launch
                }
                val trimmedEmail = email.trim()
                if (trimmedEmail.isNotBlank() &&
                    trimmedEmail != existingUser.email &&
                    userRepository.isEmailTaken(trimmedEmail, userId)
                ) {
                    _profileState.value = ProfileState.Error(R.string.error_email_taken)
                    return@launch
                }
                userRepository.updateUser(
                    existingUser.copy(
                        name = name.trim(),
                        email = trimmedEmail.ifBlank { existingUser.email },
                        avatarInitial = name.firstOrNull()?.toString()?.uppercase() ?: "?",
                        avatarColor = generateAvatarColor(name),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                sessionRepository.restore()
            } catch (e: Exception) {
                Log.w(TAG, "profile could not be updated", e)
                _profileState.value = ProfileState.Error(R.string.error_update_profile)
            }
        }
    }

    fun deleteProfile(user: User? = null) {
        viewModelScope.launch {
            try {
                val userToDelete = user ?: sessionRepository.currentUser.value ?: return@launch
                userRepository.deleteUser(userToDelete)
                if (sessionRepository.snapshotUserId() == userToDelete.id) {
                    sessionRepository.endSession()
                }
                sessionRepository.restore()
            } catch (e: Exception) {
                Log.w(TAG, "profile could not be deleted", e)
                _profileState.value = ProfileState.Error(R.string.error_delete_profile)
            }
        }
    }

    fun switchUser(user: User) {
        viewModelScope.launch {
            try {
                sessionRepository.setCurrentUser(user)
            } catch (e: Exception) {
                Log.w(TAG, "profile could not be switched", e)
                _profileState.value = ProfileState.Error(R.string.error_switch_profile)
            }
        }
    }

    private fun generateAvatarColor(name: String): Int {
        val colors = listOf(
            0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(), 0xFF3F51B5.toInt(),
            0xFF2196F3.toInt(), 0xFF03A9F4.toInt(), 0xFF00BCD4.toInt(), 0xFF009688.toInt(),
            0xFF4CAF50.toInt(), 0xFF8BC34A.toInt(), 0xFFCDDC39.toInt(), 0xFFFFEB3B.toInt(),
            0xFFFFC107.toInt(), 0xFFFF9800.toInt(), 0xFFFF5722.toInt(), 0xFF795548.toInt()
        )
        return colors[kotlin.math.abs(name.hashCode()) % colors.size]
    }

    private companion object {
        const val TAG = "ProfileViewModel"
    }
}

sealed class ProfileState {
    object Loading : ProfileState()
    object NoUser : ProfileState()
    data class Success(val user: User) : ProfileState()
    /** The sentence lives in resources; see `CategoriesState.Error` for why the id travels. */
    data class Error(@StringRes val messageRes: Int) : ProfileState()
}
