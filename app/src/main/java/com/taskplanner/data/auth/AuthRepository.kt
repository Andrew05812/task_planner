package com.taskplanner.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.FragmentActivity
import androidx.room.withTransaction
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.taskplanner.data.local.AppDatabase
import com.taskplanner.data.local.CategoryDao
import com.taskplanner.data.local.GroupDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.model.User
import com.taskplanner.data.repository.SessionRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class SignInOutcome {
    data class Success(val user: User) : SignInOutcome()
    object Canceled : SignInOutcome()
    object NotConfigured : SignInOutcome()
    data class Failed(val message: String) : SignInOutcome()
}

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseAuth: Lazy<FirebaseAuth>,
    private val sessionRepository: SessionRepository,
    private val userDao: UserDao,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val groupDao: GroupDao,
    private val database: AppDatabase
) {

    val currentUser: FirebaseUser?
        get() = if (CloudConfig.isConfigured) firebaseAuth.get().currentUser else null

    fun isFirebaseSignedIn(): Boolean = currentUser != null

    /**
     * Real Google sign-in: Credential Manager → Google ID token →
     * Firebase Auth → Firebase UID (primary identity, never the email).
     */
    suspend fun signInWithGoogle(activity: FragmentActivity): SignInOutcome {
        val clientId = CloudConfig.googleWebClientId ?: return SignInOutcome.NotConfigured
        if (!CloudConfig.isConfigured) return SignInOutcome.NotConfigured

        val credentialManager = CredentialManager.create(activity)
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(false)
                    .build()
            )
            .build()

        val response = try {
            credentialManager.getCredential(activity, request)
        } catch (e: GetCredentialCancellationException) {
            return SignInOutcome.Canceled
        } catch (e: GetCredentialException) {
            return SignInOutcome.Failed(e.message ?: "Google sign-in failed")
        }

        return try {
            val idToken = GoogleIdTokenCredential
                .createFrom(response.credential.data)
                .idToken
            val result = firebaseAuth.get()
                .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await()
            val fUser = result.user ?: return SignInOutcome.Failed("Firebase returned no user")
            val profile = upsertLocalProfile(fUser)
            sessionRepository.setCurrentUser(profile)
            SignInOutcome.Success(profile)
        } catch (e: Exception) {
            SignInOutcome.Failed(e.message ?: "Firebase authentication failed")
        }
    }

    /** UID is the identity anchor; email/name/avatar are display attributes. */
    private suspend fun upsertLocalProfile(fUser: FirebaseUser): User {
        val existing = fUser.uid?.let { userDao.getUserByFirebaseUid(it) }
        val name = fUser.displayName ?: "Google user"
        // users.email is unique and non-null; a Google account without a public email gets
        // an uid-derived placeholder instead of a colliding empty string.
        val email = fUser.email?.takeIf { it.isNotBlank() }
            ?: "google-${fUser.uid}@firebase.local"
        val initial = name.firstOrNull()?.toString()?.uppercase() ?: "?"
        return if (existing == null) {
            val user = User(
                email = email,
                name = name,
                avatarInitial = initial,
                firebaseUid = fUser.uid,
                avatarUrl = fUser.photoUrl?.toString(),
                isCurrent = true
            )
            val id = userDao.insertUser(user)
            user.copy(id = id)
        } else {
            val updated = existing.copy(
                name = name,
                email = email,
                avatarInitial = initial,
                avatarUrl = fUser.photoUrl?.toString(),
                updatedAt = System.currentTimeMillis()
            )
            userDao.updateUser(updated)
            updated
        }
    }

    /**
     * Explicit migration of a local (pre-Google) profile's data into the signed-in account.
     * Never automatic: the UI must ask the user first.
     *
     * Rows change owner instead of being copied, so their local ids, their uuids and the links
     * between them survive, and a task the account already has is recognised as the same task
     * instead of becoming a second row with a duplicated identity. Everything runs in one
     * transaction, so a failure leaves the two profiles exactly as they were.
     */
    suspend fun attachLocalDataToAccount(sourceUserId: Long, targetUser: User) {
        val activeUserId = sessionRepository.currentUserId()
            ?: throw IllegalStateException("Sign in before moving local data")
        require(activeUserId == targetUser.id) {
            "data may only be moved onto the profile that is currently signed in"
        }
        // Moving a profile onto itself would otherwise drop and re-add every row; refusing the
        // no-op is what separates a move from a wipe.
        if (sourceUserId == targetUser.id) return

        withContext(Dispatchers.IO) {
            database.withTransaction {
                val mergedCategories = attachCategories(sourceUserId, targetUser.id)
                attachGroups(sourceUserId, targetUser.id)
                attachTasks(sourceUserId, targetUser.id, mergedCategories)
            }
        }
    }

    /** Returns the source category ids whose rows the account already had, mapped to the survivor. */
    private suspend fun attachCategories(sourceUserId: Long, targetUserId: Long): Map<Long, Long> {
        val merged = LinkedHashMap<Long, Long>()
        categoryDao.getAllCategoriesOnce(sourceUserId).forEach { source ->
            val withIdentity = if (source.uuid.isNotBlank()) source else source.copy(
                uuid = UUID.randomUUID().toString()
            ).also { categoryDao.setUuid(source.id, it.uuid) }
            val twin = categoryDao.getCategoryByUuid(withIdentity.uuid)
                ?.takeIf { it.userId == targetUserId }
            if (twin == null) {
                categoryDao.updateCategory(withIdentity.copy(userId = targetUserId))
            } else {
                merged[source.id] = twin.id
                categoryDao.deleteCategory(source)
            }
        }
        return merged
    }

    /** Topics are referenced by uuid, so a topic the account already has needs no re-linking. */
    private suspend fun attachGroups(sourceUserId: Long, targetUserId: Long) {
        groupDao.getAllGroupsOnce(sourceUserId).forEach { source ->
            val withIdentity = if (source.uuid.isNotBlank()) source else source.copy(
                uuid = UUID.randomUUID().toString()
            ).also { groupDao.upsertGroup(it) }
            val twin = groupDao.getGroupByUuid(withIdentity.uuid)?.takeIf { it.userId == targetUserId }
            if (twin == null) {
                groupDao.upsertGroup(withIdentity.copy(userId = targetUserId))
            } else {
                groupDao.deleteGroup(source)
            }
        }
    }

    private suspend fun attachTasks(sourceUserId: Long, targetUserId: Long, mergedCategories: Map<Long, Long>) {
        taskDao.getAllRowsOnce(sourceUserId).forEach { source ->
            val withIdentity = if (source.uuid.isNotBlank()) source else source.copy(
                uuid = UUID.randomUUID().toString()
            ).also { taskDao.setUuid(source.id, it.uuid) }
            // findTaskByUuid also sees tombstones, so a deletion marker is matched too.
            val twin = taskDao.findTaskByUuid(withIdentity.uuid)?.takeIf { it.userId == targetUserId }
            val moved = withIdentity.copy(
                userId = targetUserId,
                categoryId = withIdentity.categoryId?.let { mergedCategories[it] ?: it }
            )
            when {
                twin == null -> taskDao.updateTask(moved)
                // The account already has this task: the newer row wins, the older one goes.
                withIdentity.updatedAt > twin.updatedAt -> {
                    taskDao.deleteTask(twin)
                    taskDao.updateTask(moved)
                }

                else -> taskDao.deleteTask(source)
            }
        }
    }

    /** Ends the Google session only; all stored data stays untouched. */
    suspend fun signOut() {
        if (CloudConfig.isConfigured) {
            try {
                firebaseAuth.get().signOut()
                CredentialManager.create(context)
                    .clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {
                // even if cloud sign-out fails, the local session is ended below
            }
        }
        sessionRepository.endSession()
    }
}
