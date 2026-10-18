package com.taskplanner.data.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.taskplanner.data.repository.ReminderRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * The safety net under the alarms: re-arms what the database says is pending and delivers anything
 * whose minute passed while the process was asleep.
 *
 * Nothing here is the primary path — alarms and the boot/clock broadcasts are — so the work is allowed
 * to be late. What it guarantees is that a device which missed a broadcast, an app that was force
 * stopped, or an alarm the system dropped still reaches the user, because the rows and their instants
 * are the truth and this pass rebuilds the alarms from them.
 */
class ReminderSweepWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderSweepEntryPoint {
        fun reminderRepository(): ReminderRepository
    }

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(
            applicationContext,
            ReminderSweepEntryPoint::class.java
        ).reminderRepository()

        return try {
            repository.rescheduleAll()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "taskplanner-reminders-periodic"
    }
}
