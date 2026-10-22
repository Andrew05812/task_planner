package com.taskplanner.ui.settings

import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.BuildConfig
import com.taskplanner.R
import com.taskplanner.data.auth.AuthRepository
import com.taskplanner.data.auth.CloudConfig
import com.taskplanner.data.auth.SignInOutcome
import com.taskplanner.data.backup.BackupRepository
import com.taskplanner.data.backup.BackupSummary
import com.taskplanner.data.backup.BackupUnsupportedVersionException
import com.taskplanner.data.backup.BackupValidationException
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.SessionRepository
import com.taskplanner.data.repository.UserRepository
import com.taskplanner.data.sync.SyncRepository
import com.taskplanner.data.sync.SyncScheduler
import com.taskplanner.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsState(
    val user: User? = null,
    val localProfiles: List<User> = emptyList(),
    val cloudConfigured: Boolean = CloudConfig.isConfigured,
    val versionName: String = BuildConfig.VERSION_NAME,
    val busy: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val backupRepository: BackupRepository,
    private val syncRepository: SyncRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.currentUser.collect { user ->
                _state.value = _state.value.copy(
                    user = user,
                    cloudConfigured = CloudConfig.isConfigured
                )
            }
        }
        viewModelScope.launch {
            syncRepository.status.collect { status ->
                _state.value = _state.value.copy(syncStatus = status)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reloadProfiles() }
    }

    private suspend fun reloadProfiles() {
        runCatching { sessionRepository.restore() }
        runCatching { userRepository.getAllUsersOnce() }
            .onSuccess { profiles ->
                _state.value = _state.value.copy(
                    localProfiles = profiles.filter { it.firebaseUid.isNullOrBlank() }
                )
            }
    }

    fun exportTo(uri: Uri) {
        runBusy(context.getString(R.string.export_success)) {
            val json = backupRepository.buildBackupJson()
            withContext(Dispatchers.IO) { backupRepository.writeBackup(uri, json) }
        }
    }

    /**
     * Reads and validates the chosen file so the screen can show real counts and a
     * readable error before anything is written.
     */
    suspend fun previewImport(uri: Uri): Result<BackupSummary> = runCatching {
        withContext(Dispatchers.IO) {
            backupRepository.summarize(backupRepository.readAndValidate(uri))
        }
    }

    fun importErrorText(error: Throwable?): String = describeImportError(error)

    fun applyImport(uri: Uri, replaceExisting: Boolean) {
        runBusy(context.getString(R.string.import_success)) {
            val file = withContext(Dispatchers.IO) { backupRepository.readAndValidate(uri) }
            backupRepository.import(file, replaceExisting)
            syncRepository.syncNow()
        }
    }

    fun signIn(activity: FragmentActivity) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val outcome = authRepository.signInWithGoogle(activity)
            val message = when (outcome) {
                is SignInOutcome.Success ->
                    context.getString(R.string.signed_in_as, outcome.user.name)
                SignInOutcome.Canceled -> context.getString(R.string.sign_in_canceled)
                SignInOutcome.NotConfigured -> context.getString(R.string.app_locked_message)
                is SignInOutcome.EmailOwnedByAnotherProfile ->
                    context.getString(R.string.sign_in_email_taken)
                is SignInOutcome.Failed ->
                    context.getString(R.string.sign_in_failed, outcome.message)
            }
            _state.value = _state.value.copy(busy = false, message = message)
            if (outcome is SignInOutcome.Success) {
                refresh()
                // A freshly linked profile has a full local queue waiting for the cloud.
                SyncScheduler.requestNow(context)
            }
        }
    }

    fun signOut() {
        // Refresh happens inside the same block: running it earlier would re-read the
        // session before it was cleared and anchor the old user back in.
        runBusy(context.getString(R.string.sign_out_message)) {
            authRepository.signOut()
            reloadProfiles()
        }
    }

    fun syncNow() {
        viewModelScope.launch { syncRepository.syncNow() }
    }

    /** Explicit, opt-in move of a local profile's data into the signed-in account. */
    fun migrateLocalProfileData(sourceUserId: Long) {
        runBusy(context.getString(R.string.migrate_local_data_done)) {
            val target = sessionRepository.resolveUser()
                ?: throw BackupValidationException(context.getString(R.string.error_no_profile_first))
            authRepository.attachLocalDataToAccount(sourceUserId, target)
            syncRepository.syncNow()
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    fun describeSyncStatus(): String = when (val status = _state.value.syncStatus) {
        SyncStatus.CloudDisabled -> context.getString(R.string.sync_state_disabled)
        SyncStatus.NotLinked -> context.getString(R.string.sync_state_not_linked)
        SyncStatus.Idle -> context.getString(R.string.sync_state_idle)
        SyncStatus.Running -> context.getString(R.string.sync_state_running)
        is SyncStatus.Done -> context.getString(
            R.string.sync_state_done,
            status.pushed,
            status.pulled,
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(status.finishedAt))
        )
        is SyncStatus.Failed -> context.getString(R.string.sync_state_failed, status.message)
    }

    private fun describeImportError(error: Throwable?): String = when (error) {
        is BackupUnsupportedVersionException -> context.getString(
            R.string.error_backup_unsupported_version,
            error.version
        )
        is BackupValidationException -> error.message
            ?: context.getString(R.string.error_backup_invalid)
        else -> error?.message ?: context.getString(R.string.error_backup_invalid)
    }

    private fun runBusy(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            try {
                block()
                _state.value = _state.value.copy(busy = false, message = successMessage)
            } catch (e: BackupValidationException) {
                _state.value = _state.value.copy(busy = false, error = describeImportError(e))
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busy = false,
                    error = e.message ?: context.getString(R.string.error_generic)
                )
            }
        }
    }
}
