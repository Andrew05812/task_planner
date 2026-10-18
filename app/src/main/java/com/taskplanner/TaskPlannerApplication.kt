package com.taskplanner

import android.app.Application
import com.taskplanner.data.auth.CloudConfig
import com.taskplanner.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TaskPlannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // No-op until Firebase is configured; enables cloud features otherwise.
        CloudConfig.initIfNeeded(this)
        // Both calls return immediately while cloud features are disabled. Their work requests
        // carry a CONNECTED constraint, so passes queued while offline run once the network
        // is back instead of being dropped.
        SyncScheduler.ensurePeriodic(this)
        SyncScheduler.requestNow(this)
    }
}
