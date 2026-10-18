package com.taskplanner.data.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.taskplanner.BuildConfig

/**
 * Firebase is configured at build time through Gradle properties
 * (firebase.projectId / firebase.appId / google.webClientId), not through a
 * google-services.json that could be committed with secrets. See
 * docs/FIREBASE_SETUP.md. Without configuration the app stays fully
 * functional in local-only mode.
 */
object CloudConfig {

    private const val PLACEHOLDER = "NOT_CONFIGURED"

    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_PROJECT_ID != PLACEHOLDER &&
            BuildConfig.FIREBASE_APP_ID != PLACEHOLDER

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
