package com.taskplanner.data.auth

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.taskplanner.data.model.User
import com.taskplanner.testing.AppFixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sign-in state machine, driven across a seam.
 *
 * `AuthRepository.signInWithGoogle` has five outcomes, and until Stage 13 exactly one of them could be reached
 * from a test: `NotConfigured`, because it returns before anything is asked of the network. The other four —
 * the reader dismissing the chooser, Credential Manager or Firebase failing, an address already owned by
 * another profile, and a successful sign-in — all sat behind a Google account and a Firebase project this
 * repository does not and must not carry.
 *
 * So the credential exchange moved behind [GoogleSignInTransport] (its own file says why, and what it does not
 * replace), and this class drives every outcome by scripting what the transport answers. The production path is
 * unchanged: Hilt binds the real `CredentialManagerSignInTransport`, whose body is the code that used to be
 * inline here. **What no test in this file proves is the credential itself** — that Credential Manager returns a
 * usable ID token for a real account, and that Firebase accepts it. That needs a project, and until one exists
 * it is reported as blocked rather than inferred from a green suite.
 *
 * The assertions are about this device, which is the part worth testing: whether a profile was written, whether
 * the session moved, whether an existing profile survived untouched, and whether a refusal released the cloud
 * session instead of leaving the reader signed in to an account with nothing to stand on.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {

    private lateinit var fixture: AppFixture
    private lateinit var local: com.taskplanner.data.model.User

    /** The transport the repository under test is wired to; scripted per test, and it counts what was asked. */
    private class Scripted : GoogleSignInTransport {
        var attempt: SignInAttempt = SignInAttempt.NotConfigured
        var signInAsked: Int = 0
        var sessionsEnded: Int = 0

        /** Set by the test that asks what happens when the cloud refuses to let go. */
        var refuseToEndSession: Boolean = false
        override suspend fun signIn(activity: FragmentActivity): SignInAttempt {
            signInAsked += 1
            return attempt
        }

        override suspend fun endSession(): SessionEnd {
            sessionsEnded += 1
            return if (refuseToEndSession) {
                SessionEnd.Failed("the test transport was told to refuse")
            } else {
                SessionEnd.Closed
            }
        }
    }

    private lateinit var scripted: Scripted
    private lateinit var repository: AuthRepository

    @Before
    fun signInALocalProfileAndWireAFakeCredential() {
        val context: Context = ApplicationProvider.getApplicationContext()
        fixture = AppFixture(context)
        local = kotlinx.coroutines.runBlocking {
            fixture.signIn(name = "Local Ivan", email = "ivan@local")
        }
        scripted = Scripted()
        repository = AuthRepository(
            context,
            dagger.Lazy { throw UnsupportedOperationException("no Firebase in a JVM test") },
            scripted,
            fixture.sessionRepository,
            fixture.userDao,
            fixture.taskDao,
            fixture.categoryDao,
            fixture.groupDao,
            fixture.recurrenceDao,
            fixture.reminderDao,
            fixture.profileAttachDao,
            fixture.safetyCopy,
            fixture.database
        )
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun signedInAs(uid: String, email: String?, name: String? = "Ivan Google") {
        scripted.attempt = SignInAttempt.Authenticated(
            GoogleIdentity(uid = uid, email = email, displayName = name, photoUrl = null)
        )
    }

    @Test
    fun `a successful sign-in links the profile and moves the session to it`() = runTest {
        signedInAs(uid = "uid-success", email = "ivan@gmail.com")
        val outcome = repository.signInWithGoogle(activity())

        assertTrue(
            "expected Success, got ${outcome.javaClass.simpleName}: ${(outcome as? SignInOutcome.Failed)?.message}",
            outcome is SignInOutcome.Success
        )
        val user = (outcome as SignInOutcome.Success).user
        assertEquals("the uid, not the address, is what the profile is anchored on",
            "uid-success", user.firebaseUid)
        assertEquals("the session is the profile that was just linked", user.id, fixture.sessionRepository.currentUserId())
        assertEquals("exactly one profile is current", 1, currentProfileCount())
    }

    @Test
    fun `dismissing the chooser is not a failure and writes nothing`() = runTest {
        val before = profileCount()
        scripted.attempt = SignInAttempt.Canceled

        val outcome = repository.signInWithGoogle(activity())

        assertEquals(SignInOutcome.Canceled, outcome)
        assertEquals("a cancelled sign-in created a profile", before, profileCount())
        assertEquals("and the reader is still on the profile they started on",
            local.id, fixture.sessionRepository.currentUserId())
        assertEquals("nothing was asked of the cloud session", 0, scripted.sessionsEnded)
    }

    @Test
    fun `a Credential Manager error is reported as its own failure, not as a cancellation`() = runTest {
        scripted.attempt = SignInAttempt.Failed("no resolvable activity")

        val outcome = repository.signInWithGoogle(activity())

        assertEquals(
            "the message the transport carried has to reach the screen, or the reader sees a generic error",
            "no resolvable activity", (outcome as SignInOutcome.Failed).message
        )
        assertEquals("and the session is where it was", local.id, fixture.sessionRepository.currentUserId())
    }

    @Test
    fun `a Firebase rejection leaves no half-built profile behind`() = runTest {
        val before = profileCount()
        scripted.attempt = SignInAttempt.Failed("Firebase authentication failed")

        val outcome = repository.signInWithGoogle(activity())

        assertTrue(outcome is SignInOutcome.Failed)
        assertEquals("a rejected exchange must not have written a profile", before, profileCount())
        assertEquals("and must not have moved the session", local.id, fixture.sessionRepository.currentUserId())
    }

    /**
     * The second fact of a failed sign-in.
     *
     * A refusal closes the cloud session it had opened, and whether that closing worked is a fact the reader is
     * owed: a refusal whose sign-out failed leaves a live Firebase session on a device with no profile under it,
     * and "nothing happened" is not what that state deserves to be called. The screen renders the two together
     * (`sign_in_failed_session_open`); this asserts that the repository keeps them apart, so the message can name
     * the one that is still true.
     */
    @Test
    fun `a refusal that could not close the cloud session reports the session as still open`() = runTest {
        scripted.attempt = SignInAttempt.Authenticated(
            GoogleIdentity(uid = "", email = "nobody@gmail.com", displayName = "Nobody", photoUrl = null)
        )
        scripted.refuseToEndSession = true

        val outcome = repository.signInWithGoogle(activity())

        val failure = outcome as SignInOutcome.Failed
        assertEquals(
            "the reason the sign-in stopped is the first thing the reader is told",
            "Firebase returned no usable uid", failure.message
        )
        assertEquals(
            "and the session that would not close is carried alongside it, not swallowed by it",
            "the test transport was told to refuse", failure.sessionProblem
        )
        assertEquals("the attempt to release the session was actually made", 1, scripted.sessionsEnded)
        assertEquals("the reader is still on the profile they started on",
            local.id, fixture.sessionRepository.currentUserId())
    }

    @Test
    fun `a build with no configuration says so before anything is asked`() = runTest {
        scripted.attempt = SignInAttempt.NotConfigured

        val outcome = repository.signInWithGoogle(activity())

        assertEquals(SignInOutcome.NotConfigured, outcome)
        assertEquals(
            "the transport is the thing that knows this build has no client id; the repository must not have " +
                "asked it to open a chooser anyway",
            1, scripted.signInAsked
        )
        assertEquals("and nothing was written", local.id, fixture.sessionRepository.currentUserId())
    }

    @Test
    fun `signing in twice with the same uid returns the same profile instead of a second one`() = runTest {
        signedInAs(uid = "uid-returning", email = "ivan@gmail.com")

        val first = repository.signInWithGoogle(activity()) as SignInOutcome.Success
        val tasksOfFirst = fixture.taskDao.getAllRowsOnce(first.user.id).size
        fixture.taskDao.insertTask(
            com.taskplanner.data.model.Task(
                title = "Between the sign-ins", description = "", userId = first.user.id
            )
        )

        val second = repository.signInWithGoogle(activity()) as SignInOutcome.Success

        assertEquals("the same uid is the same profile", first.user.id, second.user.id)
        assertEquals(
            "and the work done under it is still there — a second row would have started empty",
            tasksOfFirst + 1, fixture.taskDao.getAllRowsOnce(second.user.id).size
        )
        assertEquals("still exactly one profile carries that uid", 1,
            fixture.userDao.getAllUsersOnce().count { it.firebaseUid == "uid-returning" })
    }

    /**
     * The branch that exists because of a data-loss bug.
     *
     * `users.email` is unique, and the strategy that used to resolve that collision deleted the existing row
     * first — which thirteen tables answered by deleting everything its owner owned. An address owned by a
     * local profile is therefore refused, the cloud session is released, and the profile that holds the address
     * is untouched.
     */
    @Test
    fun `an address already owned by another profile is refused and the other profile survives intact`() =
        runTest {
            fixture.taskDao.insertTask(com.taskplanner.data.model.Task(title = "Mine", description = "", userId = local.id))
            signedInAs(uid = "uid-thief", email = local.email)

            val outcome = repository.signInWithGoogle(activity())

            assertEquals(
                "the reader is told which address is in the way, because the fix is theirs to choose",
                SignInOutcome.EmailOwnedByAnotherProfile(local.email, sessionLeftOpen = false), outcome
            )
            assertEquals("the local profile still exists", local.id,
                fixture.userDao.getUserByEmail(local.email)?.id)
            assertEquals("with its task", listOf("Mine"),
                fixture.taskDao.getAllRowsOnce(local.id).map { it.title })
            assertEquals("no profile was created for the cloud identity", 1, profileCount())
            assertEquals("and the cloud session was released rather than left standing",
                1, scripted.sessionsEnded)
            assertEquals("the session never moved", local.id, fixture.sessionRepository.currentUserId())
        }

    /**
     * A Google account with no public address still needs a unique, non-null `users.email`. The placeholder is
     * derived from the uid — the identity — so two such accounts cannot collide, and a later sign-in that *does*
     * publish an address reaches the same row by uid and rewrites the address rather than creating a twin.
     */
    @Test
    fun `an account with no public address gets a placeholder derived from its uid`() = runTest {
        // Two accounts that both have no address, one through a null and one through a blank: the column is
        // unique and non-null, so an empty string would collide with itself and a guess would name the wrong
        // person. The uid is the only thing either of these rows can be derived from.
        val outcomes = listOf("uid-noaddress-a" to null, "uid-noaddress-b" to "").map { (uid, address) ->
            signedInAs(uid = uid, email = address)
            repository.signInWithGoogle(activity()) as SignInOutcome.Success
        }

        assertEquals("google-uid-noaddress-a@firebase.local", outcomes[0].user.email)
        assertEquals("google-uid-noaddress-b@firebase.local", outcomes[1].user.email)
        assertTrue("two accounts, two profiles", outcomes[0].user.id != outcomes[1].user.id)
        assertNotNull("and each keeps its uid as the anchor", outcomes[0].user.firebaseUid)

        // The same account later publishing a real address must not become a second profile.
        val countBefore = profileCount()
        signedInAs(uid = "uid-noaddress-a", email = "now-public@gmail.com")
        val withAddress = repository.signInWithGoogle(activity()) as SignInOutcome.Success

        assertEquals("the same uid is the same profile when an address appears", countBefore, profileCount())
        assertEquals("and the display address is updated on it",
            outcomes[0].user.id, withAddress.user.id)
        assertEquals("now-public@gmail.com", withAddress.user.email)
    }

    /** Signing out ends the cloud session and leaves the local profile selected and readable. */
    /**
     * The link is created directly here, because this build has no configuration and `signInWithGoogle` would
     * stop at `NotConfigured` before reaching it. `AccountAttachTest` and
     * `SignInDataPreservationInstrumentedTest` make the same assertion about a profile that owns rows.
     */
    @Test
    fun `signing out ends the cloud session and keeps the profile reachable`() = runTest {
        val linked = repository.linkProfile("uid-signout", "signout@gmail.com", "Ivan", null)
            as AuthRepository.ProfileUpsert.Linked
        fixture.sessionRepository.setCurrentUser(linked.user)

        repository.signOut()

        assertEquals("the transport was asked to end the session", 1, scripted.sessionsEnded)
        assertEquals("the reader is still on a profile", linked.user.id,
            fixture.sessionRepository.currentUserId())
        assertEquals("and that profile still carries the link", "uid-signout",
            fixture.userDao.getUserById(linked.user.id)?.firebaseUid)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A real `FragmentActivity`, because the parameter is real.
     *
     * `signInWithGoogle` takes a `FragmentActivity` for Credential Manager to attach its chooser to. Passing
     * null and hoping the seam never looks at it would let a future change that *does* look at it fail in a
     * different test, on a different device, for a reason this file would not explain.
     */
    private fun activity(): FragmentActivity =
        org.robolectric.Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    private fun profileCount(): Int = kotlinx.coroutines.runBlocking {
        fixture.userDao.getAllUsersOnce().size
    }

    private fun currentProfileCount(): Int = kotlinx.coroutines.runBlocking {
        fixture.userDao.getAllUsersOnce().count { it.isCurrent }
    }
}
