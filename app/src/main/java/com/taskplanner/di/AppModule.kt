package com.taskplanner.di

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.taskplanner.data.tracking.AndroidTimeBase
import com.taskplanner.data.tracking.TimeBase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("task_planner_prefs", Context.MODE_PRIVATE)
    }

    /**
     * The scope one-off background work runs on: reading the stored session at start-up has to
     * leave the UI thread alone.
     */
    @Provides
    @Singleton
    fun provideBackgroundScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The device's two clocks for the tracker: the stored instant and the unmovable elapsed time.
     * Injected rather than called inline, so a measurement's arbitration between them is something a
     * test can drive instead of something it has to hope the device agrees with.
     */
    @Provides
    @Singleton
    fun provideTimeBase(): TimeBase = AndroidTimeBase()

    /**
     * Only reachable when CloudConfig.isConfigured; guarded call sites keep the
     * app working in local-only mode where FirebaseApp is not initialized.
     */
    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    /** Offline persistence stays on so queued writes survive a killed process. */
    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance().apply {
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .build()
    }
} 