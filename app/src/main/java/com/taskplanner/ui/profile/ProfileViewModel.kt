package com.taskplanner.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.SharedPreferences
import android.util.Patterns
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.UserRepository

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val sharedPreferences: SharedPreferences,
    private val userRepository: UserRepository
) : ViewModel() {
    private val _saveResult = MutableLiveData<SaveResult>()
    val saveResult: LiveData<SaveResult> = _saveResult

    private val _profileState = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val profileState: StateFlow<ProfileState> = _profileState

    private val _profiles = MutableStateFlow<List<User>>(emptyList())
    val profiles: StateFlow<List<User>> = _profiles

    init {
        loadCurrentUser()
        loadProfiles()
    }

    internal fun loadCurrentUser() {
        viewModelScope.launch {
            try {
                val currentUser = userRepository.getCurrentUser()
                if (currentUser != null) {
                    _profileState.value = ProfileState.Success(currentUser)
                } else {
                    _profileState.value = ProfileState.NoUser
                }
            } catch (e: Exception) {
                _profileState.value = ProfileState.Error(e.message ?: "Failed to load user profile")
            }
        }
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            try {
                userRepository.getAllUsers().collect { users ->
                    _profiles.value = users
                }
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun createUser(name: String, email: String) {
        viewModelScope.launch {
            try {
                if (email.isNotBlank() && userRepository.isEmailTaken(email)) {
                    _profileState.value = ProfileState.Error("Email is already taken")
                    return@launch
                }

                val initial = name.firstOrNull()?.toString()?.uppercase() ?: ""
                val avatarColor = generateAvatarColor(name)

                val user = User(
                    name = name,
                    email = email,
                    avatarInitial = initial,
                    avatarColor = avatarColor,
                    isCurrent = true
                )
                userRepository.insertUser(user)
                loadCurrentUser()
                loadProfiles()
            } catch (e: Exception) {
                _profileState.value = ProfileState.Error(e.message ?: "Failed to create user")
            }
        }
    }

    fun updateUser(userId: Long, name: String, email: String) {
        viewModelScope.launch {
            try {
                val existingUser = userRepository.getUserById(userId) ?: run {
                    _profileState.value = ProfileState.Error("User not found")
                    return@launch
                }

                if (email.isNotBlank() && userRepository.isEmailTaken(email, userId)) {
                    _profileState.value = ProfileState.Error("Email is already taken")
                    return@launch
                }

                val initial = name.firstOrNull()?.toString()?.uppercase() ?: ""
                val avatarColor = generateAvatarColor(name)

                val updatedUser = existingUser.copy(
                    name = name,
                    email = email,
                    avatarInitial = initial,
                    avatarColor = avatarColor,
                    updatedAt = System.currentTimeMillis()
                )
                userRepository.updateUser(updatedUser)
                loadCurrentUser()
                loadProfiles()
            } catch (e: Exception) {
                _profileState.value = ProfileState.Error(e.message ?: "Failed to update user")
            }
        }
    }

    fun deleteProfile(user: User? = null) {
        viewModelScope.launch {
            try {
                val userToDelete = user ?: userRepository.getCurrentUser()
                if (userToDelete != null) {
                    userRepository.deleteUser(userToDelete)
                    if (user == null) {
                        _profileState.value = ProfileState.NoUser
                    }
                    loadCurrentUser()
                    loadProfiles()
                }
            } catch (e: Exception) {
                _profileState.value = ProfileState.Error(e.message ?: "Failed to delete profile")
            }
        }
    }

    fun switchUser(user: User) {
        viewModelScope.launch {
            try {
                userRepository.setCurrentUser(user)
                loadCurrentUser()
                loadProfiles()
            } catch (e: Exception) {
                _profileState.value = ProfileState.Error(e.message ?: "Failed to switch user")
            }
        }
    }

    fun saveProfile(name: String, email: String) {
        viewModelScope.launch {
            try {
                if (name.isBlank()) {
                    _saveResult.value = SaveResult.Error("Name is required")
                    return@launch
                }

                if (email.isBlank()) {
                    _saveResult.value = SaveResult.Error("Email is required")
                    return@launch
                }

                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    _saveResult.value = SaveResult.Error("Invalid email address")
                    return@launch
                }

                sharedPreferences.edit()
                    .putString(KEY_NAME, name.trim())
                    .putString(KEY_EMAIL, email.trim())
                    .apply()

                _saveResult.value = SaveResult.Success
                loadCurrentUser()
            } catch (e: Exception) {
                _saveResult.value = SaveResult.Error(e.message ?: "Failed to save profile")
            }
        }
    }

    fun resetSaveResult() {
        _saveResult.value = SaveResult.Initial
    }

    private fun generateAvatarColor(name: String): Int {
        val colors = listOf(
            0xFFE91E63.toInt(), // Pink
            0xFF9C27B0.toInt(), // Purple
            0xFF673AB7.toInt(), // Deep Purple
            0xFF3F51B5.toInt(), // Indigo
            0xFF2196F3.toInt(), // Blue
            0xFF03A9F4.toInt(), // Light Blue
            0xFF00BCD4.toInt(), // Cyan
            0xFF009688.toInt(), // Teal
            0xFF4CAF50.toInt(), // Green
            0xFF8BC34A.toInt(), // Light Green
            0xFFCDDC39.toInt(), // Lime
            0xFFFFEB3B.toInt(), // Yellow
            0xFFFFC107.toInt(), // Amber
            0xFFFF9800.toInt(), // Orange
            0xFFFF5722.toInt(), // Deep Orange
            0xFF795548.toInt()  // Brown
        )
        return colors[name.hashCode().absoluteValue % colors.size]
    }

    private val Int.absoluteValue: Int
        get() = if (this < 0) -this else this

    companion object {
        private const val KEY_NAME = "user_name"
        private const val KEY_EMAIL = "user_email"
    }
}

sealed class ProfileState {
    object Loading : ProfileState()
    object NoUser : ProfileState()
    data class Success(val user: User) : ProfileState()
    data class Error(val message: String) : ProfileState()
}

sealed class SaveResult {
    object Initial : SaveResult()
    object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
} 