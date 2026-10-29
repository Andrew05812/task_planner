package com.taskplanner.data.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.taskplanner.BuildConfig

/**
 * Firebase is configured at build time through Gradle properties
 * (firebase.projectId / firebase.appId / firebase.apiKey / google.webClientId), not through a
 * google-services.json that could be committed with secrets. See
 * docs/FIREBASE_SETUP.md. Without configuration the app stays fully
 * functional in local-only mode.
 */
object CloudConfig {

    private const val PLACEHOLDER = "NOT_CONFIGURED"

    /**
     * Whether this build can talk to Firebase at all.
     *
     * Two values joined the check in Stage 13 and both were missing before it, in the same way. The API key was
     * left out first, so a build carrying a project id and an app id but no key reported itself as configured:
     * `FirebaseApp.initializeApp` ran with an empty key, Settings offered the button, and the first network call
     * was where the reader found out. The OAuth Web client id was left out for the same reason one step further
     * along — `CredentialManagerSignInTransport` refuses without it, so the screen would have shown state B and
     * the flow would have answered "not configured". A build with three of the four is not a cloud build with a
     * small gap; it is a local build with a secret half-typed, and it should say so before anybody presses.
     */
    val isConfigured: Boolean
        get() = configuredFor(
            BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_APP_ID,
            BuildConfig.FIREBASE_API_KEY,
            BuildConfig.GOOGLE_WEB_CLIENT_ID
        )

    /**
     * [isConfigured] as a function of the four values rather than of `BuildConfig`.
     *
     * Not a test seam in the usual sense — the production path calls this, and nothing calls the four constants
     * directly any more. It is separated because every build this repository produces has the placeholders
     * compiled in, so a rule written against `BuildConfig` can only ever be observed by a test in one of its six
     * states: "local-only" would be the only case a test could check, and the interesting half of the rule — that
     * any single missing value makes the build local — would be unverified prose.
     */
    fun configuredFor(projectId: String, appId: String, apiKey: String, webClientId: String): Boolean =
        listOf(projectId, appId, apiKey, webClientId).none { it.isBlank() || it == PLACEHOLDER }

    val googleWebClientId: String?
        get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.takeIf { it != PLACEHOLDER }

    fun initIfNeeded(context: Context): Boolean {
        if (!isConfigured) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .build()
        FirebaseApp.initializeApp(context, options)
        return true
    }
}
