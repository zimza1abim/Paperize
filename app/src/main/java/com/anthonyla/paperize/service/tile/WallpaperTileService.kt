package com.anthonyla.paperize.service.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings Tile for changing wallpaper.
 */
@AndroidEntryPoint
class WallpaperTileService : TileService() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "WallpaperTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(Tile.STATE_INACTIVE)
    }

    override fun onClick() {
        super.onClick()
        updateTileState(Tile.STATE_ACTIVE)

        serviceScope.launch {
            try {
                val mode = settingsRepository.getWallpaperMode()

                if (mode == WallpaperMode.LIVE) {
                    val intent = Intent(Constants.ACTION_RELOAD_WALLPAPER).apply {
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                } else {
                    val intent = Intent(this@WallpaperTileService, WallpaperChangeService::class.java).apply {
                        action = Constants.ACTION_CHANGE_WALLPAPER
                        putExtra(Constants.EXTRA_SCREEN_TYPE, ScreenType.BOTH.name)
                    }
                    ContextCompat.startForegroundService(this@WallpaperTileService, intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling tile click", e)
            } finally {
                updateTileState(Tile.STATE_INACTIVE)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun updateTileState(state: Int) {
        qsTile?.apply {
            label = getString(R.string.tile_next_wallpaper)
            icon = Icon.createWithResource(this@WallpaperTileService, R.drawable.ic_launcher_monochrome)
            this.state = state
            updateTile()
        }
    }
}