package com.taskplanner.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.FragmentActivity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/**
 * What a Google account said it is, apart from the credential that said it.
 *
 * The four values are the whole interface between "a network exchange happened" and "a local profile is
 * linked". `uid` is the identity; the other three are display data that may be absent, may change, and must
 * never be used to decide which person this is.
 */
data class GoogleIdentity(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?
)

/**
 * Whether a cloud session was actually closed.
 *
 * `signOut` promises the reader two things at once — the account is closed, the profile and its data stay — and
 * the first of them is a network-layer fact the app does not control. Reporting "signed out" after Firebase
 * refused to close the session would be the decorative kind of success this stage was told not to produce.
 */
sealed class SessionEnd {
    object Closed : SessionEnd()

    /** Nothing was configured, so there was no cloud session to close. Not a failure. */
    object NotConfigured : SessionEnd()

    /** A live session could not be closed. The reason is what the reader is told. */
    data class Failed(val message: String) : SessionEnd()
}

/** The four ways the credential half of a sign-in can end. */
sealed class SignInAttempt {
    /** This build has no OAuth Web client id or no Firebase project; nothing was asked of the network. */
    object NotConfigured : SignInAttempt()

    /** The reader dismissed the account chooser. Not a failure, and reported as its own thing. */
    object Canceled : SignInAttempt()

    /** A real error: no network, a misconfigured project, a Firebase rejection. */
    data class Failed(val message: String) : SignInAttempt()

    /** A Firebase session exists, and this is who it belongs to. */
    data class Authenticated(val identity: GoogleIdentity) : SignInAttempt()
}

/**
 * The credential exchange, as a boundary the app can be tested across.
 *
 * `AuthRepository.signInWithGoogle` used to perform the exchange inline: Credential Manager, the ID token, the
 * Firebase credential, the user. That is one method whose interesting branches — cancelled, no network,
 * Firebase refused, no configuration — all sit behind a Google account and a Firebase project, and this
 * repository has neither. So the exchange moved here, unchanged in behaviour, and what `AuthRepository` keeps
 * is the part that is *about this device*: the linking decision, the session, and the data handover.
 *
 * This is a seam for tests, not a substitute for the production path. The binding Hilt installs
 * ([CredentialManagerSignInTransport]) is the only implementation the app ever runs; a test installs a fake to
 * drive the state machine, and no assertion in this repository claims that the credential itself was exercised
 * — see `docs/AUTH_SYNC_ENGINEERING.md` §7, which lists that as the one row nothing proves.
 */
interface GoogleSignInTransport {
    suspend fun signIn(activity: FragmentActivity): SignInAttempt

    /**
     * Ends the credential state on this device, and reports whether it ended.
     * Safe to call when nothing is configured — that answers [SessionEnd.NotConfigured], not a failure.
     */
    suspend fun endSession(): SessionEnd
}

@Singleton
class CredentialManagerSignInTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseAuth: Lazy<FirebaseAuth>
) : GoogleSignInTransport {

    override suspend fun signIn(activity: FragmentActivity): SignInAttempt {
        val clientId = CloudConfig.googleWebClientId ?: return SignInAttempt.NotConfigured
        if (!CloudConfig.isConfigured) return SignInAttempt.NotConfigured

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
            return SignInAttempt.Canceled
        } catch (e: GetCredentialException) {
            return SignInAttempt.Failed(e.message ?: "Google sign-in failed")
        }

        return try {
            val idToken = GoogleIdTokenCredential
                .createFrom(response.credential.data)
                .idToken
            val result = firebaseAuth.get()
                .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await()
            val user = result.user ?: return SignInAttempt.Failed("Firebase returned no user")
            val uid = user.uid
            if (uid.isNullOrBlank()) return SignInAttempt.Failed("Firebase returned no uid")
            SignInAttempt.Authenticated(
                GoogleIdentity(
                    uid = uid,
                    email = user.email,
                    displayName = user.displayName,
                    photoUrl = user.photoUrl?.toString()
                )
            )
        } catch (e: CancellationException) {
            // The caller's coroutine was cancelled — a destroyed screen, a process going away. Turning that into
            // "Sign-in failed: Job is cancelled" would report a user error for a lifecycle event, and would
            // swallow the cancellation the framework is asking for.
            throw e
        } catch (e: FirebaseAuthException) {
            SignInAttempt.Failed(e.message ?: "Firebase rejected the Google credential")
        } catch (e: IllegalArgumentException) {
            // `GoogleIdTokenCredential.createFrom` answers this for a credential bundle it cannot parse, which is
            // a configuration problem worth naming rather than an NPE wearing its message.
            SignInAttempt.Failed(e.message ?: "The Google credential could not be read")
        }
    }

    /**
     * Closes the cloud session, and says whether it closed.
     *
     * The answer matters because two callers depend on it to make a promise true. `signOut` tells the reader they
     * are signed out of the account; a `signOut()` that threw inside Firebase while leaving a live session would
     * make that sentence false, and the next `isFirebaseSignedIn()` read would contradict the screen.
     * `signInWithGoogle`'s "this address belongs to another profile" branch releases the session it just opened,
     * and if the release failed the reader is signed in to an account with no local profile standing under it —
     * the exact half-built state the branch exists to prevent. Both now say so out loud instead of assuming.
     */
    override suspend fun endSession(): SessionEnd {
        if (!CloudConfig.isConfigured) return SessionEnd.NotConfigured
        // The two halves are tried apart and reported apart, because they fail apart. `FirebaseAuth.signOut()` is
        // local and effectively cannot fail; `clearCredentialState` talks to the credential store and can. A
        // single try around both therefore said "the cloud session could not be closed" for the case where the
        // session *had* closed and only the stored credential survived — and the next start of the app, which
        // reads `firebaseAuth.currentUser`, would have contradicted the sentence the reader had just been told.
        var firebaseClosed = false
        var problem: String? = null
        try {
            firebaseAuth.get().signOut()
            firebaseClosed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "the Firebase session did not close: ${e.message ?: e.javaClass.simpleName}"
        }
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val which = if (firebaseClosed) {
                "the Firebase session closed, but the stored Google credential could not be cleared"
            } else {
                "the stored Google credential could not be cleared"
            }
            problem = listOfNotNull(problem, "$which: ${e.message ?: e.javaClass.simpleName}")
                .joinToString("; ")
        }
        // A sign-out that cannot reach the credential store leaves the local profile exactly where it was, which
        // is the state the reader is already in; what it must not do is report success.
        return problem?.let { SessionEnd.Failed(it) } ?: SessionEnd.Closed
    }
}
