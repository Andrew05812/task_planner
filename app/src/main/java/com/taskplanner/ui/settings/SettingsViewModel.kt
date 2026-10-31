package com.taskplanner.ui.settings

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskplanner.BuildConfig
import com.taskplanner.R
import com.taskplanner.data.auth.AuthRepository
import com.taskplanner.data.auth.CloudConfig
import com.taskplanner.data.auth.ProfileSafetyCopy
import com.taskplanner.data.auth.ReaderRefusal
import com.taskplanner.data.auth.SessionEnd
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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Which of the account states the Settings screen is in, decided in one place, and named in one place.
 *
 * Stage 8a rendered this from `state.user?.firebaseUid != null` and `CloudConfig.isConfigured` inside the
 * fragment, which collapsed two different facts into one button: "this build has no Firebase" and "this build
 * has Firebase and you have not signed in" looked identical (absent), and after Stage 13 made sign-out keep the
 * profile selected, a *linked* profile that is signed out would have rendered as signed in. Four cases, named,
 * so the screen and the test agree on what each one means.
 *
 * Each carries the id of the sentence that states it, for the same reason: a `when` in the fragment is a mapping
 * nobody can test without a rendered screen, and a state that forgets its own words renders as an empty line —
 * which is the failure the whole enum exists to prevent. `AccountUiStateTest` asserts the four labels are four
 * different strings, so a copied-and-forgotten case fails there instead of on a screen.
 */
enum class AccountUiState(@StringRes val labelRes: Int) {
    /** The build carries no Firebase project or OAuth client: the section is visible, the button is not usable. */
    NotConfigured(R.string.account_state_not_configured),

    /** The build can sign in and the reader has not. */
    NotSignedIn(R.string.account_state_not_signed_in),

    /** The profile is linked to a Google account, and the cloud session is closed (a sign-out leaves it here). */
    LinkedSignedOut(R.string.account_state_linked_signed_out),

    /** A live Firebase session. */
    SignedIn(R.string.account_state_signed_in);

    companion object {

        /**
         * The one rule that answers "which account state is this", from the three facts that decide it.
         *
         * Kept as a function of its inputs rather than read off the repositories, because then the screen, the
         * JVM test and the instrumented test all cite the same sentence — and an instrumented test can name the
         * build's configuration without pretending to open a Firebase session it cannot open.
         */
        fun of(configured: Boolean, sessionActive: Boolean, user: User?): AccountUiState = when {
            !configured -> NotConfigured
            sessionActive -> SignedIn
            user?.firebaseUid != null -> LinkedSignedOut
            else -> NotSignedIn
        }
    }
}

data class SettingsState(
    val user: User? = null,
    val localProfiles: List<User> = emptyList(),
    val cloudConfigured: Boolean = CloudConfig.isConfigured,
    /** The cloud session on its own, because since Stage 13 a signed-out reader can still be on a linked profile. */
    val firebaseSignedIn: Boolean = false,
    // The state a screen shows before anything has been read must be the state the build is actually in.
    // Defaulting this to `NotConfigured` made a configured build open by saying "Not configured", disabling a
    // button that works, until the first database read answered — a sentence that is false for as long as it
    // takes to become true is exactly what this screen was rebuilt to stop saying.
    val accountState: AccountUiState =
        if (CloudConfig.isConfigured) AccountUiState.NotSignedIn else AccountUiState.NotConfigured,
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
                _state.value = publish(user, _state.value.cloudConfigured)
            }
        }
        viewModelScope.launch {
            syncRepository.status.collect { status ->
                _state.value = _state.value.copy(syncStatus = status)
            }
        }
        refresh()
    }

    /**
     * The one place the account state is decided, from the three facts that answer it.
     *
     * `authRepository.isFirebaseSignedIn()` is asked here rather than in the fragment because it is a read of
     * live cloud state, and a screen should not be the thing that knows how to ask it.
     *
     * The read is guarded because it reaches into a Firebase that may not have initialised — `FirebaseAuth`
     * answers through a `Lazy` that can raise `IllegalStateException` on a build whose four configuration values
     * are present but wrong. The failure is reported as *no session* rather than as a session, which is the
     * direction that cannot lie to the reader: the screen then offers sign-in, and the sign-in path asks the same
     * object a second time and shows its own error if it fails again. The alternative — a fifth state named for
     * "cannot tell" — would be a row of the screen that says nothing the reader can act on.
     */
    private fun publish(user: User?, configured: Boolean): SettingsState {
        val signedIn = configured && runCatching { authRepository.isFirebaseSignedIn() }.getOrDefault(false)
        val accountState = AccountUiState.of(configured, signedIn, user)
        return _state.value.copy(
            user = user,
            cloudConfigured = configured,
            firebaseSignedIn = signedIn,
            accountState = accountState
        )
    }

    fun refresh() {
        viewModelScope.launch {
            reloadProfiles()
            _state.value = publish(sessionRepository.resolveUser(), CloudConfig.isConfigured)
        }
    }

    /**
     * The profiles this device holds, read from disk, for the migration block.
     *
     * Both reads used to end in `runCatching` with no failure branch, which made "the database could not be
     * asked" indistinguishable from "there is nothing to ask about": `localProfiles` kept whatever it had — an
     * empty list on a screen just opened — and the whole "move local data" section is gated on that list being
     * non-empty, so the feature disappeared without saying anything. A read that failed now says so, and the
     * section goes quiet only when the read answered that there is nothing to move.
     */
    private suspend fun reloadProfiles() {
        runCatching { sessionRepository.restore() }
            .onFailure {
                _state.value = _state.value.copy(
                    error = context.getString(R.string.error_session_unread, it.message ?: "")
                )
            }
        runCatching { userRepository.getAllUsersOnce() }
            .onSuccess { profiles ->
                _state.value = _state.value.copy(
                    localProfiles = profiles.filter { it.firebaseUid.isNullOrBlank() }
                )
            }
            .onFailure {
                _state.value = _state.value.copy(
                    error = context.getString(R.string.error_profiles_unread, it.message ?: "")
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

    /**
     * Runs the sign-in and renders whichever of its five outcomes came back.
     *
     * The call is guarded because everything after the credential is local work — the profile insert, the session
     * move — and an exception from there used to leave `viewModelScope` through an uncaught coroutine, which is a
     * crash, and a crash with a live Firebase session behind it is the half-built state the `AddressTaken` branch
     * below exists to prevent. The session is closed and the reason is shown instead.
     */
    fun signIn(activity: FragmentActivity) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            val outcome = try {
                authRepository.signInWithGoogle(activity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The same accounting the two refusal branches inside `signInWithGoogle` do. This catch exists
                // because a throw after the credential escaped the coroutine as a crash, and a crash is a poor
                // way to hide the fact that a Firebase session is now standing on this device with no profile
                // under it — so the session is closed, and if closing it failed, the reader is told that too
                // rather than left to discover it on the next start.
                val ended = authRepository.signOut()
                SignInOutcome.Failed(
                    detail = context.getString(R.string.error_generic),
                    sessionProblem = (ended as? SessionEnd.Failed)?.message
                )
            }
            val message = when (outcome) {
                is SignInOutcome.Success ->
                    context.getString(R.string.signed_in_as, outcome.user.name)
                SignInOutcome.Canceled -> context.getString(R.string.sign_in_canceled)
                SignInOutcome.NotConfigured -> context.getString(R.string.app_locked_message)
                is SignInOutcome.EmailOwnedByAnotherProfile -> context.getString(
                    if (outcome.sessionLeftOpen) R.string.sign_in_email_taken_session_open
                    else R.string.sign_in_email_taken,
                    outcome.email
                )
                is SignInOutcome.Failed -> context.getString(
                    // The plain sentence names one thing; a failure that also could not close the session names
                    // both, because the second is what the reader has to go and check.
                    if (outcome.sessionProblem == null) R.string.sign_in_failed
                    else R.string.sign_in_failed_session_open,
                    outcome.detail,
                    outcome.sessionProblem.orEmpty()
                )
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
        // The re-publish happens inside the same block rather than as a separate `refresh()` afterwards:
        // `authRepository.signOut()` ends the cloud session and leaves the profile selected, and a reader must not
        // see the row flip to "signed in" from a state the screen read before the session actually closed.
        runBusy(context.getString(R.string.sign_out_message)) {
            val ended = authRepository.signOut()
            if (ended is SessionEnd.Failed) {
                // "You will stay signed out" is two promises, and the cloud one just failed. Saying it anyway
                // would leave a reader believing a session is closed when the app could not close it — so this
                // branch reports the failure instead, and the local half stays exactly as safe as it was.
                throw BackupValidationException(
                    R.string.sign_out_session_left_open,
                    formatArgs = arrayOf(ended.message.orEmpty())
                )
            }
            reloadProfiles()
            // The reader is still on the profile they signed out *of*; only the cloud session has closed.
            _state.value = publish(sessionRepository.resolveUser(), CloudConfig.isConfigured)
        }
    }

    fun syncNow() {
        viewModelScope.launch { syncRepository.syncNow() }
    }

    /**
     * Explicit, opt-in move of a local profile's data into the signed-in account.
     *
     * The message carries the numbers rather than a fixed sentence, because the guarantee the screen promises is
     * about records: how many arrived, how many were the same record the account already held, how many links had
     * to be rewired to the row that survived, and how many the safety copy is holding. A reader who is told "done"
     * cannot check anything; a reader who is told "moved 51, merged 2, re-pointed 3, copy holds 51" can compare it
     * against what they had — which is the whole point of showing numbers at all.
     *
     * A failure says what it cost. The handover's own refusals are written to be read ("stop the timer"), but the
     * same Snackbar can carry a WAL checkpoint's three integers or a `UNIQUE constraint failed` string, and a
     * reader can do nothing with either; those are wrapped in the sentence that *is* actionable — nothing was
     * moved — with the detail kept behind it, where a person hunting the cause can still find it.
     */
    fun migrateLocalProfileData(sourceUserId: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, message = null)
            try {
                val target = sessionRepository.resolveUser()
                    ?: throw BackupValidationException(R.string.error_no_profile_first)
                val outcome = authRepository.attachLocalDataToAccount(sourceUserId, target)
                val text = when (outcome) {
                    is AuthRepository.AttachOutcome.Moved -> context.getString(
                        R.string.migrate_local_data_moved_format,
                        outcome.report.moved.values.sum(),
                        outcome.report.mergedDuplicates,
                        outcome.report.repointedLinks,
                        outcome.report.safetyCopy.rowCount
                    )

                    AuthRepository.AttachOutcome.AlreadyTheSameProfile ->
                        context.getString(R.string.migrate_local_data_nothing_to_move)
                }
                // The move is over, committed and checked by the time this line runs, so the reader is told so
                // *before* anything else is attempted. The sync pass used to sit between the two: a throw from it
                // — `SyncRepository.syncNow` resolves the session outside its own try, and this coroutine runs on
                // the dispatcher where that resolution can fail — was caught below by a handler whose sentence
                // means "nothing was moved", which is the opposite of the truth, and it threw the only numbers the
                // reader could have checked the move against. A failing sync is its own news, said separately.
                _state.value = _state.value.copy(busy = false, message = text)
                val syncFailure = runCatching { syncRepository.syncNow() }
                    .exceptionOrNull()
                    ?.let { it.message ?: it.javaClass.simpleName }
                if (syncFailure != null) {
                    _state.value = _state.value.copy(
                        error = context.getString(R.string.migrate_local_data_sync_failed, syncFailure)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = describeHandoverFailure(e))
            }
        }
    }

    /** The handover's failure, in the order a reader can act on. See [migrateLocalProfileData]. */
    private fun describeHandoverFailure(error: Throwable): String = when (error) {
        // A refusal the app wrote for a reader: the resource is the whole answer.
        is ReaderRefusal -> context.getString(error.messageRes)
        is BackupValidationException -> context.getString(
            error.messageRes,
            *error.formatArgs
        )
        is ProfileSafetyCopy.SafetyCopyUnavailable -> context.getString(
            R.string.error_safety_copy_unavailable, error.message ?: ""
        )
        is SQLiteConstraintException -> context.getString(
            R.string.error_database_refused_move, error.message ?: ""
        )
        // Nothing named: a storage engine's sentence, which no reader can act on. The screen says
        // the one thing that is true and useful -- nothing was moved -- and the log keeps the text
        // that can actually diagnose it.
        else -> {
            Log.w(TAG, "the handover refused", error)
            context.getString(R.string.error_handover_failed)
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
        // A sync failure with no readable detail says only what the app already knows: it did not
        // go through. The Firebase text behind it, when there is one, is appended because a reader
        // who is asked to retry deserves to know what was retried.
        is SyncStatus.Failed -> status.detail?.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.sync_state_failed, it) }
            ?: context.getString(R.string.error_sync_failed)
    }

    /**
     * The reader's sentence about a backup the app refused.
     *
     * A refusal the repository could name arrives as a resource and is read here, in the language
     * this screen is drawn in. Anything else -- a JSON parser's complaint, a content resolver's --
     * is machine text about a file the reader cannot fix, so it gets the app's own sentence and the
     * log keeps the detail. The previous shape printed the parser's English inside a Russian screen.
     */
    private fun describeImportError(error: Throwable?): String = when (error) {
        is BackupValidationException -> context.getString(
            error.messageRes,
            *error.formatArgs
        )
        else -> {
            Log.w(TAG, "a backup the app refused", error)
            context.getString(R.string.error_backup_invalid)
        }
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
                // A refusal the app could not name still gets the sentence the app owns; the exception's own
                // words are machine text about a storage engine, and they go where machine text is read.
                Log.w(TAG, "a settings command refused", e)
                _state.value = _state.value.copy(
                    busy = false,
                    error = context.getString(R.string.error_generic)
                )
            }
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
