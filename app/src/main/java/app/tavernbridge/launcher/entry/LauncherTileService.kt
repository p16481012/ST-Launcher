package app.tavernbridge.launcher.entry

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.tavernbridge.launcher.R

/** A navigation tile, not a background server controller or an unreliable live-status indicator. */
class LauncherTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = getString(R.string.entry_tile_subtitle)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(LauncherEntryIntents.pendingIntent(this, LauncherEntryAction.OPEN, "tile"))
            } else {
                openLauncherBeforeAndroid14()
            }
        }
    }

    // The PendingIntent overload exists only on API 34+. API 26-33 still need
    // the Intent overload to both launch the Activity and collapse Quick Settings.
    // Keep this target-SDK lint suppression confined to the guarded legacy call.
    // https://developer.android.com/reference/android/service/quicksettings/TileService
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openLauncherBeforeAndroid14() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        startActivityAndCollapse(LauncherEntryIntents.entryIntent(this, LauncherEntryAction.OPEN, "tile"))
    }
}
