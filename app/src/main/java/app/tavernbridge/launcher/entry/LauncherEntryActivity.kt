package app.tavernbridge.launcher.entry

import android.app.Activity
import android.os.Bundle

/** Public launcher entry point; only opens the foreground confirmation flow, never Termux. */
class LauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = LauncherEntryIntents.requestedAction(intent)
        if (action != null && savedInstanceState == null) {
            startActivity(LauncherEntryIntents.forwardToMain(this, action))
        }
        finish()
    }
}
