package com.anthonyla.paperize.service.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.usecase.ApplyWallpaperProfileUseCase
import com.anthonyla.paperize.domain.usecase.ProfileApplyResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

abstract class BaseProfileTileService : TileService() {
    abstract val profileId: Int
    abstract val applyProfileUseCase: ApplyWallpaperProfileUseCase
    abstract val settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()

        serviceScope.launch {
            val result = runCatching { applyProfileUseCase(profileId) }
                .onFailure { Log.e(TAG, "Failed to apply profile $profileId from QS tile", it) }
                .getOrDefault(ProfileApplyResult.Failed)

            if (result == ProfileApplyResult.NeedsLiveWallpaperSelection) {
                runCatching { startActivityAndCollapse(applyProfileUseCase.liveWallpaperSelectionIntent()) }
                    .onFailure { Log.e(TAG, "Failed to open live wallpaper picker", it) }
            }
            refreshTile()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        serviceScope.launch {
            val fallback = "Profile $profileId"
            val name = runCatching {
                settingsRepository.getWallpaperProfile(profileId)?.name?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: fallback

            tile.label = "Apply $fallback"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = name
            }
            tile.icon = Icon.createWithResource(this@BaseProfileTileService, R.drawable.ic_launcher_monochrome)
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    private companion object {
        private const val TAG = "ProfileTileService"
    }
}

@AndroidEntryPoint
class Profile1TileService : BaseProfileTileService() {
    override val profileId: Int = 1

    @Inject
    override lateinit var applyProfileUseCase: ApplyWallpaperProfileUseCase

    @Inject
    override lateinit var settingsRepository: SettingsRepository
}

@AndroidEntryPoint
class Profile2TileService : BaseProfileTileService() {
    override val profileId: Int = 2

    @Inject
    override lateinit var applyProfileUseCase: ApplyWallpaperProfileUseCase

    @Inject
    override lateinit var settingsRepository: SettingsRepository
}

@AndroidEntryPoint
class Profile3TileService : BaseProfileTileService() {
    override val profileId: Int = 3

    @Inject
    override lateinit var applyProfileUseCase: ApplyWallpaperProfileUseCase

    @Inject
    override lateinit var settingsRepository: SettingsRepository
}