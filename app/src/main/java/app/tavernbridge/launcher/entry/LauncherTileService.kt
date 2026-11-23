package app.tavernbridge.launcher.entry

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
                @Suppress("DEPRECATION")
                startActivityAndCollapse(LauncherEntryIntents.entryIntent(this, LauncherEntryAction.OPEN, "tile"))
            }
        }
    }
}
