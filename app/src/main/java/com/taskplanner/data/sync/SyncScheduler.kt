package com.taskplanner.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.taskplanner.data.auth.CloudConfig
import java.util.concurrent.TimeUnit

/**
 * Schedules sync. Every request carries a CONNECTED constraint, so work enqueued while the
 * device is offline is not dropped — WorkManager runs it as soon as connectivity returns.
 */
object SyncScheduler {

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun ensurePeriodic(context: Context) {
        if (!CloudConfig.isConfigured) return
        PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .build()
            .let {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    SyncWorker.UNIQUE_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    it
                )
            }
    }

    fun requestNow(context: Context) {
        if (!CloudConfig.isConfigured) return
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints)
            .build()
            .let {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    SyncWorker.UNIQUE_ONE_TIME,
                    ExistingWorkPolicy.REPLACE,
                    it
                )
            }
    }
}
