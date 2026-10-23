package com.taskplanner.data.auth

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
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
import com.taskplanner.data.local.RecurrenceDao
import com.taskplanner.data.local.ReminderDao
import com.taskplanner.data.local.TaskDao
import com.taskplanner.data.local.UserDao
import com.taskplanner.data.model.Category
import com.taskplanner.data.model.RecurrenceRule
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

    /**
     * The Google account's address already belongs to a profile on this device that is not that account —
     * a local profile, or one linked to a different cloud identity.
     *
     * Joining the two is a decision only the reader can make, and the app already asks for it explicitly
     * ("move this profile onto the Google account"). Signing in therefore stops here rather than writing a
     * second profile over the first: `users.email` is unique, and the statement that would have created one
     * deletes the existing one first, taking with it every goal, habit, session, insight and achievement that
     * cascades off its owner and orphaning its tasks.
     */
    data class EmailOwnedByAnotherProfile(val email: String) : SignInOutcome()

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
    private val recurrenceDao: RecurrenceDao,
    private val reminderDao: ReminderDao,
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
            when (val linked = upsertLocalProfile(fUser)) {
                is ProfileUpsert.Linked -> {
                    sessionRepository.setCurrentUser(linked.user)
                    SignInOutcome.Success(linked.user)
                }

                is ProfileUpsert.AddressTaken -> {
                    // The cloud session has no local profile to stand on, and leaving it half-built would
                    // leave the reader signing in "successfully" into the profile they already had. The
                    // address is released back to the profile that owns it, and the reader is told why.
                    runCatching { firebaseAuth.get().signOut() }
                    SignInOutcome.EmailOwnedByAnotherProfile(linked.email)
                }

                is ProfileUpsert.Refused -> {
                    runCatching { firebaseAuth.get().signOut() }
                    SignInOutcome.Failed(linked.message)
                }
            }
        } catch (e: Exception) {
            SignInOutcome.Failed(e.message ?: "Firebase authentication failed")
        }
    }

    private suspend fun upsertLocalProfile(fUser: FirebaseUser): ProfileUpsert = linkProfile(
        uid = fUser.uid,
        email = fUser.email,
        displayName = fUser.displayName,
        photoUrl = fUser.photoUrl?.toString()
    )

    /**
     * The linking decision, apart from the credential that carries it.
     *
     * Split out because the property worth testing — that a sign-in never destroys the profile that happens to
     * own the same address — is arithmetic over these four values plus the database, and `FirebaseUser` cannot
     * be built in a JVM test. Everything about identity is decided here, so everything about it is testable.
     *
     * UID is the identity anchor; email, name and avatar are display attributes. A profile is created only when
     * neither this uid nor this address is already on the device. The second condition is the one that used to
     * be missing: an address owned by a local profile made `insertUser` collide with the unique index, and the
     * strategy then in force resolved that by deleting the existing row — which thirteen tables answer by
     * deleting everything their owner owns.
     */
    internal suspend fun linkProfile(
        uid: String?,
        email: String?,
        displayName: String?,
        photoUrl: String?
    ): ProfileUpsert {
        uid ?: return ProfileUpsert.Refused("Firebase returned no uid")
        val name = displayName ?: "Google user"
        // users.email is unique and non-null; a Google account without a public email gets
        // an uid-derived placeholder instead of a colliding empty string.
        val address = email?.takeIf { it.isNotBlank() } ?: "google-$uid@firebase.local"
        val initial = name.firstOrNull()?.toString()?.uppercase() ?: "?"
        val existing = userDao.getUserByFirebaseUid(uid)
        // The address is checked whichever branch this takes. The uid branch used to skip it and write the
        // address straight into the row it had found — the same unique index, arriving from the other
        // direction, and an exception escaping past the caller's answer with the cloud session left standing.
        val holder = userDao.getUserByEmail(address)?.takeIf { it.id != existing?.id }
        if (holder != null) return ProfileUpsert.AddressTaken(address)

        if (existing != null) {
            val updated = existing.copy(
                name = name,
                email = address,
                avatarInitial = initial,
                avatarUrl = photoUrl,
                updatedAt = System.currentTimeMillis()
            )
            userDao.updateUser(updated)
            return ProfileUpsert.Linked(updated)
        }

        val user = User(
            email = address,
            name = name,
            avatarInitial = initial,
            firebaseUid = uid,
            avatarUrl = photoUrl,
            isCurrent = true
        )
        return try {
            ProfileUpsert.Linked(user.copy(id = userDao.insertUser(user)))
        } catch (e: SQLiteConstraintException) {
            // Somebody claimed the address between the lookup and this write. Refusing is the whole point of
            // the constraint: the other profile is still intact, and this attempt says so out loud.
            ProfileUpsert.AddressTaken(address)
        }
    }

    /** What linking a Google account to a local profile produced. */
    internal sealed class ProfileUpsert {
        data class Linked(val user: User) : ProfileUpsert()
        data class AddressTaken(val email: String) : ProfileUpsert()
        data class Refused(val message: String) : ProfileUpsert()
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
                val categories = attachCategories(sourceUserId, targetUser.id)
                attachGroups(sourceUserId, targetUser.id)
                // Before the tasks, so an instance the account already knows arrives together with
                // the pattern that produced it rather than as an orphan row.
                attachRules(sourceUserId, targetUser.id)
                attachTasks(sourceUserId, targetUser.id, categories.survivors)
                // Only after the tasks: a source category still has rows pointing at it until they move, and
                // deleting it un-categorises them.
                removeMergedCategories(categories.toRemove)
                attachReminders(sourceUserId, targetUser.id)
            }
        }
    }

    /**
     * Series move as they are: [RecurrenceRule.uuid] is the primary key, so no rule of the signed-in
     * account can already hold this identity, and the row only needs a new owner. The cursor goes
     * with it, which is what keeps a moved series from repeating instances the user finished.
     */
    private suspend fun attachRules(sourceUserId: Long, targetUserId: Long) {
        recurrenceDao.getAllRowsOnce(sourceUserId).forEach { source ->
            recurrenceDao.upsertRule(source.copy(userId = targetUserId))
        }
    }

    /**
     * Lead times move the same way: the (task, offset) unique index means the account cannot already
     * hold the same reminder, so there is nothing to merge and the alarm keeps its own slot.
     */
    private suspend fun attachReminders(sourceUserId: Long, targetUserId: Long) {
        reminderDao.getAllRowsOnce(sourceUserId).forEach { source ->
            reminderDao.update(source.copy(userId = targetUserId))
        }
    }

    /**
     * The categories that merged into the account's own, and the source rows that may be dropped afterwards.
     *
     * The two lists are returned together and the second one is acted on only once the tasks have moved, which
     * is the whole of the point: `tasks.category_id` points at a category `ON DELETE SET NULL`, so removing
     * the local copy of a merged category while its tasks are still sitting in the source profile leaves every
     * one of them with no category — and the map built to re-point them then has nothing left to read.
     */
    private class CategoryMerge(
        val survivors: Map<Long, Long>,
        val toRemove: List<Category>
    )

    private suspend fun attachCategories(sourceUserId: Long, targetUserId: Long): CategoryMerge {
        val merged = LinkedHashMap<Long, Long>()
        val removable = mutableListOf<Category>()
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
                removable += source
            }
        }
        return CategoryMerge(merged, removable)
    }

    /** Drops the local copies of categories that merged into the account's own. See [attachCategories]. */
    private suspend fun removeMergedCategories(merged: List<Category>) {
        for (category in merged) categoryDao.deleteCategory(category)
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
