package com.anthonyla.paperize.service.tile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.ProfileShortcutManager
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.usecase.ApplyWallpaperProfileUseCase
import com.anthonyla.paperize.domain.usecase.ProfileApplyResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

abstract class BaseProfileTileService : TileService() {
    abstract val profileId: Int
    abstract val applyProfileUseCase: ApplyWallpaperProfileUseCase
    abstract val settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isProfileReceiverRegistered = false
    private val profileAppliedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PROFILE_APPLIED) {
                refreshTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        registerProfileReceiver()
        refreshTile()
    }

    override fun onStopListening() {
        unregisterProfileReceiver()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { applyProfileFromTile() }
        } else {
            applyProfileFromTile()
        }
    }

    private fun applyProfileFromTile() {
        updateTileState(Tile.STATE_ACTIVE)
        serviceScope.launch {
            val profileName = getProfileName()
            val lastAppliedProfileId = runCatching {
                settingsRepository.getLastAppliedProfileId()
            }.getOrNull()

            if (lastAppliedProfileId == profileId) {
                settingsRepository.updateLastAppliedProfileId(null)
                sendBroadcast(Intent(Constants.ACTION_PROFILE_APPLIED).setPackage(packageName))
                ProfileShortcutManager.requestTileRefresh(this@BaseProfileTileService)
                Log.d(TAG, "Profile $profileId toggled off from QS tile")
                showToast("$profileName off")
                refreshTile()
                return@launch
            }

            val result = runCatching { applyProfileUseCase(profileId) }
                .onFailure { Log.e(TAG, "Failed to apply profile $profileId from QS tile", it) }
                .getOrDefault(ProfileApplyResult.Failed)

            when (result) {
                ProfileApplyResult.Applied -> {
                    Log.d(TAG, "Profile $profileId applied from QS tile")
                    showToast("$profileName applied")
                    ProfileShortcutManager.requestTileRefresh(this@BaseProfileTileService)
                }
                ProfileApplyResult.NeedsLiveWallpaperSelection -> {
                    showToast("Select Paperize live wallpaper")
                    runCatching {
                        startActivityAndCollapseCompat(applyProfileUseCase.liveWallpaperSelectionIntent())
                    }.onFailure { Log.e(TAG, "Failed to open live wallpaper picker", it) }
                }
                ProfileApplyResult.NotFound -> showToast("$profileName is not saved")
                ProfileApplyResult.InvalidProfile -> showToast("$profileName is incomplete")
                ProfileApplyResult.DeferredUntilUnlocked -> showToast("Unlock the phone before applying profile")
                ProfileApplyResult.Failed -> showToast("Failed to apply $profileName")
            }
            refreshTile()
        }
    }

    override fun onDestroy() {
        unregisterProfileReceiver()
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        serviceScope.launch {
            val fallback = "Profile $profileId"
            val profile = runCatching {
                settingsRepository.getWallpaperProfile(profileId)
            }.getOrNull()
            val lastAppliedProfileId = runCatching {
                settingsRepository.getLastAppliedProfileId()
            }.getOrNull()
            val name = profile?.name?.takeIf { it.isNotBlank() } ?: fallback

            withContext(Dispatchers.Main) {
                tile.label = name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (profile == null) "Not saved" else fallback
                }
                tile.icon = Icon.createWithResource(this@BaseProfileTileService, R.drawable.ic_launcher_monochrome)
                tile.state = when {
                    profile == null -> Tile.STATE_UNAVAILABLE
                    lastAppliedProfileId == profileId -> Tile.STATE_ACTIVE
                    else -> Tile.STATE_INACTIVE
                }
                Log.d(TAG, "Profile $profileId tile state=${tile.state}, lastApplied=$lastAppliedProfileId")
                tile.updateTile()
            }
        }
    }

    private fun updateTileState(state: Int) {
        mainHandler.post {
            qsTile?.apply {
                this.state = state
                updateTile()
            }
        }
    }

    private fun startActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                profileId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun getProfileName(): String {
        return runCatching {
            settingsRepository.getWallpaperProfile(profileId)?.name?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: "Profile $profileId"
    }

    private fun registerProfileReceiver() {
        if (isProfileReceiverRegistered) return
        val filter = IntentFilter(Constants.ACTION_PROFILE_APPLIED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(profileAppliedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(profileAppliedReceiver, filter)
        }
        isProfileReceiverRegistered = true
    }

    private fun unregisterProfileReceiver() {
        if (!isProfileReceiverRegistered) return
        runCatching { unregisterReceiver(profileAppliedReceiver) }
        isProfileReceiverRegistered = false
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
