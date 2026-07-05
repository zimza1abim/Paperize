package com.anthonyla.paperize.domain.usecase

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.isPaperizeLiveWallpaperActive
import com.anthonyla.paperize.domain.model.WallpaperProfileSnapshot
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SaveWallpaperProfileUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(profileId: Int): ProfileApplyResult {
        if (profileId !in 1..3) return ProfileApplyResult.InvalidProfile
        val mode = settingsRepository.getWallpaperMode()
        val settings = settingsRepository.getScheduleSettings()
        val existingName = settingsRepository.getWallpaperProfile(profileId)?.name
            ?.takeIf { it.isNotBlank() }
            ?: "Apply Profile $profileId"
        val snapshot = WallpaperProfileSnapshot.from(profileId, mode, settings)
            .copy(name = existingName)
        settingsRepository.saveWallpaperProfile(snapshot)
        return ProfileApplyResult.Applied
    }
}

class ApplyWallpaperProfileUseCase @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val wallpaperRepository: WallpaperRepository,
    private val wallpaperScheduler: WallpaperScheduler
) {
    suspend operator fun invoke(profileId: Int): ProfileApplyResult {
        if (profileId !in 1..3) return ProfileApplyResult.InvalidProfile
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
            return ProfileApplyResult.DeferredUntilUnlocked
        }

        val profile = settingsRepository.getWallpaperProfile(profileId)
            ?: return ProfileApplyResult.NotFound
        val targetMode = profile.toMode()
        val targetSettings = profile.toScheduleSettings()

        val validation = validate(profile, targetMode)
        if (validation != null) return validation

        wallpaperScheduler.cancelAllWallpaperChanges()
        settingsRepository.updateWallpaperMode(targetMode)
        settingsRepository.updateScheduleSettings(targetSettings)
        wallpaperRepository.clearAllQueues()

        return when (targetMode) {
            WallpaperMode.LIVE -> applyLiveProfile(targetSettings.liveAlbumId)
            WallpaperMode.STATIC -> applyStaticProfile(targetSettings.homeEnabled, targetSettings.lockEnabled)
        }
    }

    private fun validate(profile: WallpaperProfileSnapshot, mode: WallpaperMode): ProfileApplyResult? {
        return when (mode) {
            WallpaperMode.LIVE -> if (profile.liveAlbumId == null) ProfileApplyResult.InvalidProfile else null
            WallpaperMode.STATIC -> {
                val hasHome = profile.homeEnabled && profile.homeAlbumId != null
                val hasLock = profile.lockEnabled && profile.lockAlbumId != null
                if (!hasHome && !hasLock) ProfileApplyResult.InvalidProfile else null
            }
        }
    }

    private suspend fun applyLiveProfile(liveAlbumId: String?): ProfileApplyResult {
        if (liveAlbumId == null) return ProfileApplyResult.InvalidProfile
        scheduleLiveIfNeeded(liveAlbumId)

        return if (isPaperizeLiveWallpaperActive(context)) {
            context.sendBroadcast(
                Intent(Constants.ACTION_RELOAD_WALLPAPER).setPackage(context.packageName)
            )
            ProfileApplyResult.Applied
        } else {
            ProfileApplyResult.NeedsLiveWallpaperSelection
        }
    }

    private suspend fun applyStaticProfile(homeEnabled: Boolean, lockEnabled: Boolean): ProfileApplyResult {
        val screenType = when {
            homeEnabled && lockEnabled -> ScreenType.BOTH
            homeEnabled -> ScreenType.HOME
            lockEnabled -> ScreenType.LOCK
            else -> return ProfileApplyResult.InvalidProfile
        }

        val intent = Intent(context, WallpaperChangeService::class.java).apply {
            action = Constants.ACTION_CHANGE_WALLPAPER
            putExtra(Constants.EXTRA_SCREEN_TYPE, screenType.name)
        }
        ContextCompat.startForegroundService(context, intent)
        scheduleStaticIfNeeded(homeEnabled, lockEnabled)
        return ProfileApplyResult.Applied
    }

    private suspend fun scheduleLiveIfNeeded(liveAlbumId: String) {
        val settings = settingsRepository.getScheduleSettings()
        if (settings.enableChanger) {
            wallpaperScheduler.scheduleWallpaperChange(ScreenType.LIVE, settings.liveIntervalMinutes)
            wallpaperScheduler.scheduleAlbumRefresh()
        }
    }

    private suspend fun scheduleStaticIfNeeded(homeEnabled: Boolean, lockEnabled: Boolean) {
        val settings = settingsRepository.getScheduleSettings()
        if (!settings.enableChanger) return
        wallpaperScheduler.scheduleWallpaperChanges(
            homeIntervalMinutes = if (homeEnabled) settings.homeIntervalMinutes else 0,
            lockIntervalMinutes = if (lockEnabled) settings.lockIntervalMinutes else 0,
            synchronized = homeEnabled && lockEnabled &&
                settings.homeAlbumId != null &&
                settings.homeAlbumId == settings.lockAlbumId &&
                !settings.separateSchedules
        )
    }

    fun liveWallpaperSelectionIntent(): Intent {
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(
                    context.packageName,
                    "com.anthonyla.paperize.service.livewallpaper.PaperizeLiveWallpaperService"
                )
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

sealed interface ProfileApplyResult {
    data object Applied : ProfileApplyResult
    data object NotFound : ProfileApplyResult
    data object InvalidProfile : ProfileApplyResult
    data object NeedsLiveWallpaperSelection : ProfileApplyResult
    data object DeferredUntilUnlocked : ProfileApplyResult
    data object Failed : ProfileApplyResult
}
