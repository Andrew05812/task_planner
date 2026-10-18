package com.taskplanner.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskplanner.data.sync.SyncRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Runs a sync pass in the background. Resolves its dependency through a Hilt EntryPoint
 * instead of hilt-work, so no extra worker-factory configuration is needed.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun syncRepository(): SyncRepository
    }

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            SyncEntryPoint::class.java
        ).syncRepository()

        return try {
            when (val status = repository.syncNow()) {
                is SyncStatus.Failed -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                // No cloud / no linked profile: nothing to do, not an error.
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "taskplanner-sync-periodic"
        const val UNIQUE_ONE_TIME = "taskplanner-sync-immediate"
        private const val MAX_ATTEMPTS = 5
    }
}
