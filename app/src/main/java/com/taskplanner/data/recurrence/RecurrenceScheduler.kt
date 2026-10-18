package com.taskplanner.data.recurrence

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps repeating series producing their tasks without the app being opened.
 *
 * Unlike cloud sync this has no network constraint and no cloud gate: materialising the next
 * instance of a series is purely local work, and local-only mode has to keep working.
 */
object RecurrenceScheduler {

    fun ensurePeriodic(context: Context) {
        PeriodicWorkRequestBuilder<RecurrenceRefreshWorker>(12, TimeUnit.HOURS)
            .build()
            .let {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    RecurrenceRefreshWorker.UNIQUE_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    it
                )
            }
    }
}
