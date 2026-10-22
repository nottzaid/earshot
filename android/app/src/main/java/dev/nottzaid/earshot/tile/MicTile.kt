package dev.nottzaid.earshot.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.nottzaid.earshot.MainActivity
import dev.nottzaid.earshot.R
import dev.nottzaid.earshot.service.Earshot
import dev.nottzaid.earshot.service.MicService
import dev.nottzaid.earshot.service.Phase
import dev.nottzaid.earshot.settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Quick Settings: mute/unmute while running; opens the app when off (a mic service can't start from the shade). */
class MicTile : TileService() {

    override fun onStartListening() = render()

    override fun onClick() {
        if (Earshot.runtime.value.phase == Phase.Off) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        } else {
            MicService.toggleMute(this)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val rt = Earshot.runtime.value
        // Tile callbacks are rare and the store is in memory after first read.
        val muted = runBlocking { settings.prefs.first().muted }
        val live = rt.phase != Phase.Off
        tile.state = when {
            !live -> Tile.STATE_INACTIVE
            muted -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.icon = Icon.createWithResource(this, if (live && muted) R.drawable.ic_stat_muted else R.drawable.ic_stat_mic)
        tile.label = getString(R.string.app_name)
        tile.subtitle = MicService.describe(this, rt, muted).first
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, MicTile::class.java))
        }
    }
}
