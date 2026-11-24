package app.tavernbridge.launcher

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import app.tavernbridge.launcher.ui.LauncherApp

class MainActivity : ComponentActivity() {
    private val viewModel: LauncherViewModel by viewModels()
    private val refreshWhenForeground = Runnable {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.refreshAfterResume()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onStop() {
        viewModel.onAppBackgrounded()
        super.onStop()
    }
}
