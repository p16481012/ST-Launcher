package app.tavernbridge.launcher.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

class TermuxCommandExecutor(private val context: Context) {
    private val requestCodes = AtomicInteger(1000)

    fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TermuxContract.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasRunCommandPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, TermuxContract.PERMISSION_RUN_COMMAND) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun executeBash(
        callbackId: String,
        command: String,
        label: String,
        description: String,
    ) {
        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            putExtra(TermuxContract.EXTRA_CALLBACK_ID, callbackId)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(
            context,
            requestCodes.incrementAndGet(),
            callbackIntent,
            pendingFlags,
        )

        val runIntent = Intent(TermuxContract.ACTION_RUN_COMMAND).apply {
            component = ComponentName(TermuxContract.PACKAGE, TermuxContract.SERVICE_CLASS)
            putExtra(TermuxContract.EXTRA_COMMAND_PATH, TermuxContract.BASH_PATH)
            putExtra(TermuxContract.EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(TermuxContract.EXTRA_WORKDIR, TermuxContract.HOME_PATH)
            putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            putExtra(TermuxContract.EXTRA_COMMAND_LABEL, label)
            putExtra(TermuxContract.EXTRA_COMMAND_DESCRIPTION, description)
            putExtra(TermuxContract.EXTRA_PENDING_INTENT, callback)
        }
        startTermuxService(runIntent)
    }

    fun executeLongRunningBash(
        command: String,
        label: String,
        description: String,
    ) {
        val runIntent = Intent(TermuxContract.ACTION_RUN_COMMAND).apply {
            component = ComponentName(TermuxContract.PACKAGE, TermuxContract.SERVICE_CLASS)
            putExtra(TermuxContract.EXTRA_COMMAND_PATH, TermuxContract.BASH_PATH)
            putExtra(TermuxContract.EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(TermuxContract.EXTRA_WORKDIR, TermuxContract.HOME_PATH)
            putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            putExtra(TermuxContract.EXTRA_COMMAND_LABEL, label)
            putExtra(TermuxContract.EXTRA_COMMAND_DESCRIPTION, description)
        }
        startTermuxService(runIntent)
    }

    fun executeForegroundBash(command: String, label: String, description: String) {
        val runIntent = Intent(TermuxContract.ACTION_RUN_COMMAND).apply {
            component = ComponentName(TermuxContract.PACKAGE, TermuxContract.SERVICE_CLASS)
            putExtra(TermuxContract.EXTRA_COMMAND_PATH, TermuxContract.BASH_PATH)
            putExtra(TermuxContract.EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(TermuxContract.EXTRA_WORKDIR, TermuxContract.HOME_PATH)
            putExtra(TermuxContract.EXTRA_BACKGROUND, false)
            putExtra(TermuxContract.EXTRA_COMMAND_LABEL, label)
            putExtra(TermuxContract.EXTRA_COMMAND_DESCRIPTION, description)
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(TermuxContract.PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launchIntent != null) context.startActivity(launchIntent)

        // Termux rejects a foreground terminal session when its activity is still in the
        // background on Android 10+. Open Termux first, then create the session after the
        // activity has had enough time to become visible.
        Handler(Looper.getMainLooper()).postDelayed(
            { startTermuxService(runIntent) },
            1_000,
        )
    }

    private fun startTermuxService(intent: Intent) {
        try {
            context.startService(intent)
        } catch (error: IllegalStateException) {
            val blockedInBackground = error.message?.contains("background", ignoreCase = true) == true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && blockedInBackground) {
                // Some Android builds refuse to wake an idle Termux with startService(), even
                // when the launcher activity is visible. Termux maintainers recommend retrying
                // RUN_COMMAND as a foreground-service start on affected devices.
                ContextCompat.startForegroundService(context, intent)
            } else {
                throw error
            }
        }
    }
}
