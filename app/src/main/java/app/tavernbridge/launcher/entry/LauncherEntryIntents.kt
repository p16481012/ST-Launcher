package app.tavernbridge.launcher.entry

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import app.tavernbridge.launcher.MainActivity
import java.util.UUID

object LauncherEntryIntents {
    private const val ACTION_PREFIX = "app.tavernbridge.launcher.entry."
    private const val FORWARDED_ACTION = "app.tavernbridge.launcher.entry.REQUEST"
    private const val EXTRA_ACTION = "launcher_entry_action"
    private const val EXTRA_ID = "launcher_entry_request_id"

    fun entryIntent(context: Context, action: LauncherEntryAction, source: String = "shortcut"): Intent =
        Intent(context, LauncherEntryActivity::class.java).apply {
            this.action = ACTION_PREFIX + action.name
            data = Uri.Builder().scheme("st-launcher-entry").authority(context.packageName)
                .appendPath(source.take(64)).appendPath(action.name).build()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun pendingIntent(context: Context, action: LauncherEntryAction, source: String): PendingIntent =
        PendingIntent.getActivity(
            context, 0, entryIntent(context, action, source),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun requestedAction(intent: Intent?): LauncherEntryAction? =
        LauncherEntryAction.parse(intent?.action?.takeIf { it.startsWith(ACTION_PREFIX) }?.removePrefix(ACTION_PREFIX))

    internal fun forwardToMain(context: Context, action: LauncherEntryAction): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = FORWARDED_ACTION
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_ACTION, action.name)
            putExtra(EXTRA_ID, UUID.randomUUID().toString())
        }

    /** Removes even invalid payloads so lifecycle re-entry cannot replay them. */
    fun consume(intent: Intent?): LauncherEntryRequest? {
        if (intent?.action != FORWARDED_ACTION) return null
        val action: String?
        val id: String?
        try {
            action = intent.getStringExtra(EXTRA_ACTION)
            id = intent.getStringExtra(EXTRA_ID)
        } catch (_: RuntimeException) {
            return null
        } finally {
            // Do not unparcel an untrusted Bundle again while clearing malformed extras.
            intent.replaceExtras(null as Bundle?)
            intent.action = Intent.ACTION_MAIN
        }
        val parsedAction = LauncherEntryAction.parse(action) ?: return null
        if (id == null || !id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) return null
        return LauncherEntryRequest(id, parsedAction)
    }
}
