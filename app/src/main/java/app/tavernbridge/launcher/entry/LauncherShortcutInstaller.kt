package app.tavernbridge.launcher.entry

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import app.tavernbridge.launcher.R

enum class PinShortcutResult { REQUESTED, UNSUPPORTED, FAILED }

class LauncherShortcutInstaller(private val context: Context) {
    /** REQUESTED means Android received the request, not that the user pinned the shortcut. */
    fun requestPin(action: LauncherEntryAction): PinShortcutResult = try {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (manager?.isRequestPinShortcutSupported != true) {
            PinShortcutResult.UNSUPPORTED
        } else {
            val label = context.getString(when (action) {
                LauncherEntryAction.START -> R.string.entry_start
                LauncherEntryAction.STOP -> R.string.entry_stop
                LauncherEntryAction.OPEN -> R.string.entry_open
            })
            val shortcut = ShortcutInfo.Builder(context, "st-launcher-${action.name.lowercase()}")
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(LauncherEntryIntents.entryIntent(context, action))
                .build()
            if (manager.requestPinShortcut(shortcut, null)) PinShortcutResult.REQUESTED else PinShortcutResult.FAILED
        }
    } catch (_: RuntimeException) {
        PinShortcutResult.FAILED
    }
}
