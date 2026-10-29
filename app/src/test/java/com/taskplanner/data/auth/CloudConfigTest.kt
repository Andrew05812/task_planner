package com.taskplanner.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The gate that decides whether a build can use the cloud at all.
 *
 * Settings renders one of its four account states from this answer, and `CredentialManagerSignInTransport` refuses
 * from it, so the rule is the difference between a reader being offered a sign-in that works and being offered a
 * button that fails at the first network call. Four values are needed for that: a project id, an app id and an
 * API key are what `FirebaseOptions` asks for, and the OAuth **Web** client id is what Credential Manager asks
 * Google for an ID token against. A build carrying three of the four is not a cloud build — it is a local build
 * with a secret half-typed.
 *
 * The rule is stated as a function of the four values rather than read straight off `BuildConfig` for one reason:
 * every key in this repository is fixed at the `NOT_CONFIGURED` placeholder, so a gate written against it could
 * only ever be observed by a test in the one answer the placeholders give — not configured. The production path
 * still reads `BuildConfig` — [CloudConfig.isConfigured] is the only thing the app calls.
 *
 * **That this class can fail was checked, not assumed.** With the fourth value taken out of the gate — the rule as
 * it stood before this test was written — `any one of the four missing is a local build` failed while the other
 * three passed (`4 tests completed, 1 failed`), and all four passed again once the rule was restored. A test
 * written after the code is worth nothing until somebody tries to make it red; the values below are shaped like
 * real ones and are not real ones.
 */
@RunWith(RobolectricTestRunner::class)
class CloudConfigTest {

    private val set = mapOf(
        "projectId" to "demo-project-4b7f1",
        "appId" to "1:1234567890:android:0123456789abcdef0123456789abcdef",
        "apiKey" to "AIzaSyDEMO-key-not-a-real-one",
        "webClientId" to "1234567890-abcdefghijklmnopqrstuvwx.apps.googleusercontent.com"
    )
    private val placeholder = "NOT_CONFIGURED"

    @Test
    fun `all four values present is a cloud build`() {
        assertEquals(
            true,
            CloudConfig.configuredFor(
                projectId = set.getValue("projectId"), appId = set.getValue("appId"),
                apiKey = set.getValue("apiKey"), webClientId = set.getValue("webClientId")
            )
        )
    }

    @Test
    fun `any one of the four missing is a local build`() {
        val full = listOf(
            set.getValue("projectId"), set.getValue("appId"), set.getValue("apiKey"), set.getValue("webClientId")
        )
        for (index in full.indices) {
            val case = full.toMutableList().also { it[index] = placeholder }
            assertEquals(
                "a build with the " + listOf("project id", "app id", "API key", "web client id")[index] +
                    " left as a placeholder must not offer sign-in",
                false,
                CloudConfig.configuredFor(case[0], case[1], case[2], case[3])
            )
        }
    }

    @Test
    fun `an empty value counts as missing`() {
        assertEquals(
            false,
            CloudConfig.configuredFor(set.getValue("projectId"), set.getValue("appId"), "", set.getValue("webClientId"))
        )
        assertEquals(
            false,
            CloudConfig.configuredFor("  ", set.getValue("appId"), set.getValue("apiKey"), set.getValue("webClientId"))
        )
    }

    @Test
    fun `this repository's own build is local-only`() {
        // The claim the README and the roadmap make, measured rather than asserted in prose: no build produced from
        // this tree with no `-P` flags is configured, because all four placeholders are still placeholders.
        assertEquals(false, CloudConfig.isConfigured)
        assertEquals(null, CloudConfig.googleWebClientId)
    }
}
