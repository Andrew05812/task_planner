package com.taskplanner.data.recurrence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskplanner.data.repository.RecurrenceRepository
import com.taskplanner.data.sync.SyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Brings the series of the signed-in profile up to date in the background.
 *
 * A series whose due date passes while the app is closed still owes the user one task, and the
 * repair must not depend on anybody opening the screen. The work is idempotent, so running it on a
 * schedule, at start-up and after a sync is harmless.
 */
class RecurrenceRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RecurrenceEntryPoint {
        fun recurrenceRepository(): RecurrenceRepository
    }

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            RecurrenceEntryPoint::class.java
        ).recurrenceRepository()

        return try {
            val created = repository.ensureOpenInstances()
            // A newly materialised instance is a local change other devices should learn about
            // without waiting for the next periodic sync pass.
            if (created > 0) SyncScheduler.requestNow(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "taskplanner-recurrence-periodic"
    }
}
