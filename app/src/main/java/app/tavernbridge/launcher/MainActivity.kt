package app.tavernbridge.launcher

import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import app.tavernbridge.launcher.ui.LauncherApp

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()
    // Only while the launcher is visible. A clock tick never scans backups or starts Termux.
    private val reminderClock = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.refreshBackupReminderClock(clockReset = intent.action != Intent.ACTION_TIME_TICK)
        }
    }
    private val refreshWhenForeground = Runnable {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.refreshAfterResume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.restoreSetupReturn(savedInstanceState?.getBoolean("pending-setup-trip") == true)
        enableEdgeToEdge()
        app.tavernbridge.launcher.entry.LauncherEntryIntents.consume(intent)?.let(viewModel::requestEntryAction)
        setContent {
            LauncherApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.tavernbridge.launcher.entry.LauncherEntryIntents.consume(intent)?.let(viewModel::requestEntryAction)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
        ContextCompat.registerReceiver(this, reminderClock, IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPostResume() {
        super.onPostResume()
        // Android can still classify this process as background during ViewModel creation and
        // the first resume callback. Wait until the activity window is visibly resumed before
        // asking Termux to start RunCommandService.
        window.decorView.removeCallbacks(refreshWhenForeground)
        window.decorView.postDelayed(refreshWhenForeground, 350)
    }

    override fun onPause() {
        viewModel.onSetupWindowPaused()
        window.decorView.removeCallbacks(refreshWhenForeground)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("pending-setup-trip", viewModel.pendingSetupReturn())
    }

    override fun onStop() {
        unregisterReceiver(reminderClock)
        viewModel.onAppBackgrounded()
        super.onStop()
    }
}
