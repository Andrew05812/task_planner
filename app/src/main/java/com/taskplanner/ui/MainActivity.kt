package com.taskplanner.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.taskplanner.R
import com.taskplanner.data.recurrence.RecurrenceScheduler
import com.taskplanner.data.reminder.ReminderNotifier
import com.taskplanner.data.reminder.ReminderScheduler
import com.taskplanner.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    /** Only here to publish the reminder channel, which the shade needs before the first notification. */
    @Inject lateinit var reminderNotifier: ReminderNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Repeating series keep producing their next task while the app is closed. WorkManager
        // stores the schedule on disk, so this runs once per install and survives a reboot; the
        // KEEP policy makes every later start a no-op.
        RecurrenceScheduler.ensurePeriodic(this)
        // The same safety net for reminders: alarms are the fast path, this sweep is what makes a
        // lost alarm cost a delay instead of a missing notification.
        ReminderScheduler.ensureSweep(this)
        reminderNotifier.ensureChannel()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        findViewById<BottomNavigationView>(R.id.bottom_navigation)
            .setupWithNavController(navController)
    }

    /**
     * A reminder tapped while the app is already open has to move this screen to that task, not leave
     * the user where they were. singleTop delivers the deep link here instead of starting a second
     * instance, so the navigation state the user built up survives the tap.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        intent?.let { navHostFragment?.navController?.handleDeepLink(it) }
    }
}
