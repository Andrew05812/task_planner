package com.taskplanner.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.taskplanner.R
import com.taskplanner.data.recurrence.RecurrenceScheduler
import com.taskplanner.data.report.export.ReportExportRepository
import com.taskplanner.data.reminder.ReminderNotifier
import com.taskplanner.data.reminder.ReminderScheduler
import com.taskplanner.data.tracking.TrackingPhase
import com.taskplanner.databinding.ActivityMainBinding
import com.taskplanner.ui.util.WorkTimeFormat
import com.taskplanner.ui.work.ActiveWorkBarViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    /** Only here to publish the reminder channel, which the shade needs before the first notification. */
    @Inject lateinit var reminderNotifier: ReminderNotifier

    /** The measurement in progress, wherever the user is. */
    private lateinit var workBar: ActiveWorkBarViewModel

    /**
     * The exporter, here for one thing: the copies it leaves in the cache for a share.
     *
     * A staged report is a file another app was meant to pick up, and a share that was never finished leaves it
     * behind. The cap that every new export already enforces is the real rule; this is the same sweep for the
     * start of a session, when a reader may have shared, changed their mind, and closed the app since.
     */
    @Inject lateinit var reportExports: ReportExportRepository

    /** The last bar rendered, kept only so the row can say which task a tap should open. */
    private var activeBar: ActiveWorkBarViewModel.Bar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Hilt's activity factory is what builds a @HiltViewModel, and it is only in place after
        // super.onCreate has run.
        workBar = ViewModelProvider(this)[ActiveWorkBarViewModel::class.java]

        // Repeating series keep producing their next task while the app is closed. WorkManager
        // stores the schedule on disk, so this runs once per install and survives a reboot; the
        // KEEP policy makes every later start a no-op.
        RecurrenceScheduler.ensurePeriodic(this)
        // The same safety net for reminders: alarms are the fast path, this sweep is what makes a
        // lost alarm cost a delay instead of a missing notification.
        ReminderScheduler.ensureSweep(this)
        reminderNotifier.ensureChannel()
        // Once per start, not once per screen: it is a directory listing and a delete, so it goes to a worker
        // thread rather than to the one drawing the first frame, and a restored activity does not repeat work the
        // process already did. The sweep is idempotent by construction — it keeps the newest few copies and
        // takes the rest — so running it twice is the same answer as running it once, and nothing with a timer
        // can delete a file a share is still reading.
        if (savedInstanceState == null) sweepStagedReports()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        findViewById<BottomNavigationView>(R.id.bottom_navigation)
            .setupWithNavController(navController)

        setupWorkBar(navController)
    }

    private fun sweepStagedReports() {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { reportExports.pruneStaged() } }
        }
    }

    /**
     * The bar over every screen, fed by the same board the task list reads.
     *
     * It is a way to the task, not a second set of controls: Start, Pause and Stop belong to the task
     * they measure, where the figures explaining them are. The tapped navigation goes through the
     * deep link the reminders already use, so the destination is reached the same way from both.
     */
    private fun setupWorkBar(navController: NavController) {
        binding.workStatusBar.setOnClickListener {
            val taskId = activeBar?.taskId ?: return@setOnClickListener
            navController.navigate(Uri.parse(ReminderNotifier.taskDeepLink(taskId)))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                workBar.bar.collect { state ->
                    activeBar = state
                    binding.workStatusBar.isVisible = state != null
                    if (state == null) return@collect
                    binding.workStatusBarLabel.text = getString(
                        if (state.isRunning) R.string.work_bar_measuring else R.string.work_bar_paused,
                        state.taskTitle
                    )
                    binding.workStatusBarTicker.isVisible = state.isRunning
                    binding.workStatusBarTicker.text = WorkTimeFormat.ticker(state.liveSeconds ?: 0L)
                    binding.workStatusBarDot.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(
                            this@MainActivity,
                            if (state.phase == TrackingPhase.RUNNING) R.color.priority_high
                            else R.color.status_paused
                        )
                    )
                    binding.workStatusBar.contentDescription =
                        getString(R.string.work_bar_open)
                }
            }
        }
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
