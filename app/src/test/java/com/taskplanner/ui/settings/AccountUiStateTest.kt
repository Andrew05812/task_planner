package com.taskplanner.ui.settings

import com.taskplanner.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The decision table behind the account row of the Settings screen.
 *
 * The stage's requirement is that the screen says *which* of its states it is in — no configuration, configured
 * and signed out, linked but signed out, signed in — and that a reader can tell the difference between a build
 * that cannot sign in and a reader who has not. Four names and one rule is the whole of the feature; the
 * fragment only renders it, and the instrumented test only proves that the render follows the rule. So the rule
 * itself is pinned here, in all twelve combinations of the three facts that feed it.
 *
 * Written after the enum, deliberately: the RED for this screen was the layout and instrumented tests that
 * asserted a hidden button (`signInButton == GONE`), which failed against Stage 8a's rendering and pass against
 * this one. This class is a lock on a decision table, not a failing test that was watched first.
 */
@RunWith(RobolectricTestRunner::class)
class AccountUiStateTest {

    private val linked = User(id = 2, name = "Ivan", email = "ivan@gmail.com", firebaseUid = "uid-1")
    private val localProfile = User(id = 3, name = "Local", email = "local@example.com")

    @Test
    fun `an unconfigured build is state A whatever the session says`() {
        for (sessionActive in listOf(false, true)) {
            for (user in listOf(null, localProfile, linked)) {
                assertEquals(
                    "configured=false, session=$sessionActive, linked=${user?.firebaseUid != null}",
                    AccountUiState.NotConfigured,
                    AccountUiState.of(configured = false, sessionActive = sessionActive, user = user)
                )
            }
        }
    }

    @Test
    fun `a live firebase session is state C even on a profile that carries no link`() {
        assertEquals(
            "the session is the fact the reader acts on, not the row it is stored on",
            AccountUiState.SignedIn,
            AccountUiState.of(configured = true, sessionActive = true, user = localProfile)
        )
    }

    @Test
    fun `a closed session on a linked profile is the fourth state, not the signed-in one`() {
        assertEquals(
            "Stage 13 made sign-out keep the profile selected, so `firebaseUid != null` alone no longer means " +
                "signed in",
            AccountUiState.LinkedSignedOut,
            AccountUiState.of(configured = true, sessionActive = false, user = linked)
        )
    }

    @Test
    fun `a configured build with no session and no link is state B`() {
        for (user in listOf(null, localProfile)) {
            assertEquals(
                "the reader has a profile or none, and either way there is nothing linked",
                AccountUiState.NotSignedIn,
                AccountUiState.of(configured = true, sessionActive = false, user = user)
            )
        }
    }

    @Test
    fun `the four names cover every combination of the three facts`() {
        val seen = mutableListOf<AccountUiState>()
        for (configured in listOf(false, true)) {
            for (sessionActive in listOf(false, true)) {
                for (user in listOf(null, localProfile, linked)) {
                    seen += AccountUiState.of(configured, sessionActive, user)
                }
            }
        }
        // The arithmetic stated, so "twelve" in the class comment is not something a reader has to recount:
        // two configuration values × two session values × three profiles.
        assertEquals("the loop does not visit every combination", 2 * 2 * 3, seen.size)
        assertEquals(
            "a fifth case would be a state the screen has no words for",
            AccountUiState.values().toSet(), seen.toSet()
        )
    }

    /**
     * Every state has words of its own, and every one of them is a real string.
     *
     * The stage's requirement is that a reader can tell the four situations apart on the screen, and the row
     * renders `state.labelRes` with no branching around it — so two states sharing a resource, or a state
     * carrying `0`, would make the screen say the wrong thing while every combination above still resolved to
     * the right name. The layout test checks where the label is drawn; this checks that what is drawn differs.
     */
    @Test
    fun `each state names its own label`() {
        val labels = AccountUiState.values().map { it.labelRes }
        assertTrue(
            "a state with no string resource would render an empty row: $labels",
            labels.none { it == 0 }
        )
        assertEquals(
            "two states share a label, so the screen says one of them for the other: " +
                AccountUiState.values().map { "${it.name}=${it.labelRes}" }.joinToString(),
            labels.toSet().size, labels.size
        )
    }
}
