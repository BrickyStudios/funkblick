package io.github.brickystudios.funkblick.scan

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.brickystudios.funkblick.MainActivity

/** Quick settings tile: one tap starts the scan (through the app, so Android shows the permission dialogs), another stops it. */
class ScanTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (Funk.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (Funk.running.value) {
            ScanService.stop(this)
            qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
            return
        }
        val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(i)
    }
}
