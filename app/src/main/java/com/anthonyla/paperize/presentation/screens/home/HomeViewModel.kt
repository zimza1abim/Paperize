package com.anthonyla.paperize.presentation.screens.home

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.LiveWallpaperStatusManager
import com.anthonyla.paperize.core.util.isPaperizeLiveWallpaperActive
import com.anthonyla.paperize.domain.model.AlbumSummary
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.usecase.CreateAlbumUseCase
import com.anthonyla.paperize.domain.usecase.GetAlbumSummariesUseCase
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Home screen
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    getAlbumSummariesUseCase: GetAlbumSummariesUseCase,
    private val createAlbumUseCase: CreateAlbumUseCase,
    private val albumRepository: AlbumRepository,
    private val settingsRepository: SettingsRepository,
    private val wallpaperScheduler: WallpaperScheduler,
    private val wallpaperRepository: com.anthonyla.paperize.domain.repository.WallpaperRepository
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val LIVE_WALLPAPER_RECHECK_DELAY_MS = 1_200L
    }

    init {
        // Check if Paperize live wallpaper was replaced/disabled on startup
        checkLiveWallpaperStatus()
    }

    val albums: StateFlow<List<AlbumSummary>> = getAlbumSummariesUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList()
        )

    val scheduleSettings: StateFlow<ScheduleSettings> = settingsRepository.getScheduleSettingsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = ScheduleSettings.default()
        )

    val appSettings = settingsRepository.getAppSettingsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = com.anthonyla.paperize.domain.model.AppSettings.default()
        )

    val wallpaperMode = settingsRepository.getWallpaperModeFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = null
        )

    // Show prompt to select live wallpaper when enabling changer in LIVE mode
    private val _showLiveWallpaperPrompt = MutableStateFlow(false)
    val showLiveWallpaperPrompt: StateFlow<Boolean> = _showLiveWallpaperPrompt
    private var liveWallpaperPromptJob: Job? = null

    fun dismissLiveWallpaperPrompt() {
        liveWallpaperPromptJob?.cancel()
        _showLiveWallpaperPrompt.value = false
    }

    /**
     * Check if Paperize live wallpaper is still active on app startup.
     * Keep saved live-mode settings intact even if Android reports wallpaperInfo late
     * after reboot; Samsung can attach the live wallpaper engine after the app starts.
     */
    private fun checkLiveWallpaperStatus() {
        viewModelScope.launch {
            val mode = settingsRepository.getWallpaperMode()
            val settings = settingsRepository.getScheduleSettings()

            if (mode != com.anthonyla.paperize.core.WallpaperMode.LIVE ||
                settings.liveAlbumId == null ||
                !settings.enableChanger) {
                return@launch
            }

            if (LiveWallpaperStatusManager.isWithinBootGracePeriod()) {
                Log.d(TAG, "Startup check skipped during boot grace period; preserving live wallpaper settings")
                scheduleAlarms(settings, onlyIfNotScheduled = true)
                return@launch
            }

            val isActive = isPaperizeLiveWallpaperActive(context) || recheckLiveWallpaperActive()
            Log.d(TAG, "Startup check: LIVE mode with album selected, confirmedActive=$isActive")

            if (!isActive) {
                Log.w(TAG, "Paperize live wallpaper is not confirmed active; preserving settings and showing selection prompt")
                scheduleAlarms(settings, onlyIfNotScheduled = true)
                maybeShowLiveWallpaperPrompt("startupCheck")
            } else {
                scheduleAlarms(settings, onlyIfNotScheduled = true)
            }
        }
    }

    fun createAlbum(name: String) {
        viewModelScope.launch {
            when (val result = createAlbumUseCase(name)) {
                is com.anthonyla.paperize.core.Result.Success -> { /* Success */ }
                is com.anthonyla.paperize.core.Result.Error -> { 
                    Log.e(TAG, "Error creating album", result.exception)
                }
                is com.anthonyla.paperize.core.Result.Loading -> { /* Loading state not used */ }
            }
        }
    }

    fun renameAlbum(albumId: String, name: String) {
        viewModelScope.launch {
            when (val result = albumRepository.updateAlbumName(albumId, name)) {
                is com.anthonyla.paperize.core.Result.Success -> { /* Success */ }
                is com.anthonyla.paperize.core.Result.Error -> {
                    Log.e(TAG, "Error renaming album", result.exception)
                }
                is com.anthonyla.paperize.core.Result.Loading -> { /* Loading state not used */ }
            }
        }
    }

    fun selectHomeAlbum(album: AlbumSummary?) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions with selectLockAlbum()
            settingsRepository.updateHomeAlbumId(album?.id)

            // Read updated settings after atomic write
            val updated = settingsRepository.getScheduleSettings()

            // If unselecting and no albums left, disable changer and cancel alarms
            if (album == null && updated.lockAlbumId == null) {
                toggleWallpaperChanger(false)
            } else if (album != null && updated.enableChanger) {
                // Check if we have all required albums before triggering wallpaper change
                val homeActive = updated.homeEnabled && updated.homeAlbumId != null
                val lockActive = updated.lockEnabled && updated.lockAlbumId != null
                val hasRequiredAlbums = when {
                    updated.homeEnabled && updated.lockEnabled -> homeActive && lockActive
                    updated.homeEnabled -> homeActive
                    updated.lockEnabled -> lockActive
                    else -> false
                }

                // Only change wallpaper and schedule if all required albums are selected
                if (hasRequiredAlbums) {
                    // In LIVE mode, don't trigger immediate wallpaper change - the live wallpaper service handles it
                    // In STATIC mode, trigger immediate change to update the wallpaper
                    if (wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.STATIC) {
                        val screenType = if (
                            updated.homeAlbumId == updated.lockAlbumId &&
                            updated.homeAlbumId != null &&
                            !updated.separateSchedules &&
                            updated.homeEnabled &&
                            updated.lockEnabled
                        ) {
                            // Same album for both screens and not separately scheduled - use BOTH
                            ScreenType.BOTH
                        } else {
                            ScreenType.HOME
                        }
                        changeWallpaperNow(screenType)
                    }
                    scheduleAlarms(updated)
                } else {
                    // Not all required albums selected - cancel any existing schedules
                    wallpaperScheduler.cancelAllWallpaperChanges()
                }
            } else if (updated.enableChanger) {
                // Album was unselected - check if we still have all required albums
                val homeActive = updated.homeEnabled && updated.homeAlbumId != null
                val lockActive = updated.lockEnabled && updated.lockAlbumId != null
                val hasRequiredAlbums = when {
                    updated.homeEnabled && updated.lockEnabled -> homeActive && lockActive
                    updated.homeEnabled -> homeActive
                    updated.lockEnabled -> lockActive
                    else -> false
                }

                if (hasRequiredAlbums) {
                    scheduleAlarms(updated)
                } else {
                    wallpaperScheduler.cancelAllWallpaperChanges()
                }
            }
        }
    }

    fun selectLockAlbum(album: AlbumSummary?) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions with selectHomeAlbum()
            settingsRepository.updateLockAlbumId(album?.id)

            // Read updated settings after atomic write
            val updated = settingsRepository.getScheduleSettings()

            // If unselecting and no albums left, disable changer and cancel alarms
            if (album == null && updated.homeAlbumId == null) {
                toggleWallpaperChanger(false)
            } else if (album != null && updated.enableChanger) {
                // Check if we have all required albums before triggering wallpaper change
                val homeActive = updated.homeEnabled && updated.homeAlbumId != null
                val lockActive = updated.lockEnabled && updated.lockAlbumId != null
                val hasRequiredAlbums = when {
                    updated.homeEnabled && updated.lockEnabled -> homeActive && lockActive
                    updated.homeEnabled -> homeActive
                    updated.lockEnabled -> lockActive
                    else -> false
                }

                // Only change wallpaper and schedule if all required albums are selected
                if (hasRequiredAlbums) {
                    // In LIVE mode, don't trigger immediate wallpaper change - the live wallpaper service handles it
                    // In STATIC mode, trigger immediate change to update the wallpaper
                    if (wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.STATIC) {
                        val screenType = if (
                            updated.homeAlbumId == updated.lockAlbumId &&
                            updated.lockAlbumId != null &&
                            !updated.separateSchedules &&
                            updated.homeEnabled &&
                            updated.lockEnabled
                        ) {
                            // Same album for both screens and not separately scheduled - use BOTH
                            ScreenType.BOTH
                        } else {
                            ScreenType.LOCK
                        }
                        changeWallpaperNow(screenType)
                    }
                    scheduleAlarms(updated)
                } else {
                    // Not all required albums selected - cancel any existing schedules
                    wallpaperScheduler.cancelAllWallpaperChanges()
                }
            } else if (updated.enableChanger) {
                // Album was unselected - check if we still have all required albums
                val homeActive = updated.homeEnabled && updated.homeAlbumId != null
                val lockActive = updated.lockEnabled && updated.lockAlbumId != null
                val hasRequiredAlbums = when {
                    updated.homeEnabled && updated.lockEnabled -> homeActive && lockActive
                    updated.homeEnabled -> homeActive
                    updated.lockEnabled -> lockActive
                    else -> false
                }

                if (hasRequiredAlbums) {
                    scheduleAlarms(updated)
                } else {
                    wallpaperScheduler.cancelAllWallpaperChanges()
                }
            }
        }
    }

    fun selectLiveAlbum(album: AlbumSummary?) {
        viewModelScope.launch {
            settingsRepository.updateLiveAlbumId(album?.id)

            val updated = settingsRepository.getScheduleSettings()

            // If unselecting, disable changer
            if (album == null) {
                toggleWallpaperChanger(false)
            } else if (updated.enableChanger) {
                // Album selected and changer is enabled - schedule alarms
                scheduleAlarms(updated)
            } else {
                // Album selected but changer is not enabled - enable it
                toggleWallpaperChanger(true)
            }
        }
    }

    fun toggleWallpaperChanger(enabled: Boolean, onlyIfNotScheduled: Boolean = false) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions with album selection updates
            settingsRepository.updateEnableChanger(enabled)

            // Read updated settings after atomic write
            val updated = settingsRepository.getScheduleSettings()

            if (enabled) {
                // Check if we have all required albums before changing wallpaper
                val homeActive = updated.homeEnabled && updated.homeAlbumId != null
                val lockActive = updated.lockEnabled && updated.lockAlbumId != null
                val hasRequiredAlbums = if (wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.STATIC) {
                    when {
                        updated.homeEnabled && updated.lockEnabled -> homeActive && lockActive
                        updated.homeEnabled -> homeActive
                        updated.lockEnabled -> lockActive
                        else -> false
                    }
                } else {
                    updated.liveAlbumId != null
                }

                // Only change wallpaper and schedule if all required albums are selected
                if (hasRequiredAlbums) {
                    // Only change wallpaper now if this is not a "check if scheduled" call
                    // In LIVE mode, skip immediate wallpaper changes - the live wallpaper service handles it
                    if (!onlyIfNotScheduled && wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.STATIC) {
                        val isSynced = homeActive && lockActive &&
                                       updated.homeAlbumId == updated.lockAlbumId &&
                                       !updated.separateSchedules
                        if (isSynced) {
                            changeWallpaperNow(ScreenType.BOTH)
                        } else {
                            if (homeActive) changeWallpaperNow(ScreenType.HOME)
                            if (lockActive) changeWallpaperNow(ScreenType.LOCK)
                        }
                    }
                    scheduleAlarms(updated, onlyIfNotScheduled)

                    // Show live wallpaper selection prompt if in LIVE mode and Paperize is NOT already active
                    if (wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.LIVE) {
                        maybeShowLiveWallpaperPrompt("toggleWallpaperChanger")
                    }
                } else {
                    // Not all required albums selected - cancel any existing schedules
                    wallpaperScheduler.cancelAllWallpaperChanges()
                }
            } else {
                wallpaperScheduler.cancelAllWallpaperChanges()
            }
        }
    }

    fun updateScheduleSettings(settings: ScheduleSettings) {
        viewModelScope.launch {
            // Check if settings have changed before validation
            val currentSettings = settingsRepository.getScheduleSettings()
            val shuffleChanged = currentSettings.shuffleEnabled != settings.shuffleEnabled

            // Check if screen toggles (homeEnabled/lockEnabled) have changed
            val screenToggleChanged = currentSettings.homeEnabled != settings.homeEnabled ||
                                     currentSettings.lockEnabled != settings.lockEnabled

            // If a screen was disabled, clear only that screen's album selection.
            // Clearing both when only one changes would lose the other screen's selection.
            val settingsWithClearedAlbums = if (screenToggleChanged) {
                settings.copy(
                    homeAlbumId = if (!settings.homeEnabled) null else settings.homeAlbumId,
                    lockAlbumId = if (!settings.lockEnabled) null else settings.lockAlbumId
                )
            } else {
                settings
            }

            val validated = settingsWithClearedAlbums.validate()

            // Check what changed
            val schedulingChanged = validated.hasSchedulingChanges(currentSettings)
            val displayChanged = validated.hasDisplayChanges(currentSettings)

            settingsRepository.updateScheduleSettings(validated)

            // If shuffle setting changed, clear all queues to force rebuild with new mode
            if (shuffleChanged) {
                wallpaperRepository.clearAllQueues()
            }

            val homeActive = validated.homeEnabled && validated.homeAlbumId != null
            val lockActive = validated.lockEnabled && validated.lockAlbumId != null

            // Determine if we have the required albums selected
            val hasRequiredAlbums = if (wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.STATIC) {
                when {
                    validated.homeEnabled && validated.lockEnabled -> homeActive && lockActive
                    validated.homeEnabled -> homeActive
                    validated.lockEnabled -> lockActive
                    else -> false
                }
            } else {
                validated.liveAlbumId != null
            }

            // Handle scheduling changes (interval, screen enable/disable, etc.)
            if (validated.enableChanger && hasRequiredAlbums && schedulingChanged) {
                scheduleAlarms(validated)

                // Show live wallpaper selection prompt if in LIVE mode, changer was just enabled, and Paperize is NOT already active
                if (wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.LIVE &&
                    !currentSettings.enableChanger && validated.enableChanger) {
                    maybeShowLiveWallpaperPrompt("updateScheduleSettings")
                }
            } else if (validated.enableChanger && !hasRequiredAlbums) {
                // Changer enabled but required albums not selected - cancel existing schedules
                wallpaperScheduler.cancelAllWallpaperChanges()
            }

            // Handle display changes (scaling)
            // Reapply current wallpaper immediately to show the effect
            // In LIVE mode, skip immediate wallpaper changes - the live wallpaper service handles it
            if (validated.enableChanger && hasRequiredAlbums && displayChanged &&
                wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.STATIC) {
                val isSynced = homeActive && lockActive &&
                               validated.homeAlbumId == validated.lockAlbumId &&
                               !validated.separateSchedules
                if (isSynced) {
                    reapplyEffectsNow(ScreenType.BOTH)
                } else {
                    if (homeActive) reapplyEffectsNow(ScreenType.HOME)
                    if (lockActive) reapplyEffectsNow(ScreenType.LOCK)
                }
            }
        }
    }

    private var pendingSettingsJob: Job? = null

    private fun maybeShowLiveWallpaperPrompt(reason: String) {
        liveWallpaperPromptJob?.cancel()
        liveWallpaperPromptJob = viewModelScope.launch {
            if (LiveWallpaperStatusManager.isWithinBootGracePeriod()) {
                Log.d(TAG, "$reason: prompt suppressed during boot grace period")
                _showLiveWallpaperPrompt.value = false
                return@launch
            }

            if (isPaperizeLiveWallpaperActive(context)) {
                Log.d(TAG, "$reason: Paperize live wallpaper already active; prompt suppressed")
                _showLiveWallpaperPrompt.value = false
                return@launch
            }

            val activeAfterRecheck = recheckLiveWallpaperActive()
            Log.d(TAG, "$reason: activeAfterRecheck=$activeAfterRecheck")
            _showLiveWallpaperPrompt.value = !activeAfterRecheck
        }
    }

    private suspend fun recheckLiveWallpaperActive(): Boolean {
        delay(LIVE_WALLPAPER_RECHECK_DELAY_MS)
        return isPaperizeLiveWallpaperActive(context)
    }

    fun updateScheduleSettingsDebounced(settings: ScheduleSettings) {
        pendingSettingsJob?.cancel()
        pendingSettingsJob = viewModelScope.launch {
            delay(Constants.SETTINGS_DEBOUNCE_MS)
            updateScheduleSettings(settings)
        }
    }

    fun changeWallpaperNow(screenType: ScreenType) {
        val intent = Intent(context, WallpaperChangeService::class.java).apply {
            action = WallpaperChangeService.ACTION_CHANGE_WALLPAPER
            putExtra(WallpaperChangeService.EXTRA_SCREEN_TYPE, screenType.name)
        }
        context.startForegroundService(intent)
    }

    fun reapplyEffectsNow(screenType: ScreenType) {
        val intent = Intent(context, WallpaperChangeService::class.java).apply {
            action = WallpaperChangeService.ACTION_REAPPLY_EFFECTS
            putExtra(WallpaperChangeService.EXTRA_SCREEN_TYPE, screenType.name)
        }
        context.startForegroundService(intent)
    }

    private suspend fun scheduleAlarms(settings: ScheduleSettings, onlyIfNotScheduled: Boolean = false) {
        // Check if we're in LIVE mode
        if (wallpaperMode.value == com.anthonyla.paperize.core.WallpaperMode.LIVE) {
            // LIVE mode: schedule live wallpaper changes
            if (settings.liveAlbumId != null && settings.liveIntervalMinutes > 0) {
                wallpaperScheduler.scheduleWallpaperChange(
                    ScreenType.LIVE,
                    settings.liveIntervalMinutes
                )
            } else {
                wallpaperScheduler.cancelWallpaperChange(ScreenType.LIVE)
            }
            return
        }

        // STATIC mode: existing logic
        val homeActive = settings.homeEnabled && settings.homeAlbumId != null
        val lockActive = settings.lockEnabled && settings.lockAlbumId != null

        // Determine intervals based on active screens and schedule settings
        val homeInterval: Int
        val lockInterval: Int

        if (settings.homeEnabled && settings.lockEnabled) {
            // Both screens enabled - require both albums selected before scheduling
            if (homeActive && lockActive) {
                if (settings.separateSchedules) {
                    // Separate intervals for each screen
                    homeInterval = settings.homeIntervalMinutes
                    lockInterval = settings.lockIntervalMinutes
                } else {
                    // Same interval for both screens
                    homeInterval = settings.homeIntervalMinutes
                    lockInterval = settings.homeIntervalMinutes
                }
            } else {
                // Both enabled but not both selected - don't schedule anything
                homeInterval = 0
                lockInterval = 0
            }
        } else {
            // Only one screen enabled - schedule only if that screen has an album
            homeInterval = if (homeActive) settings.homeIntervalMinutes else 0
            lockInterval = if (lockActive) settings.lockIntervalMinutes else 0
        }

        // Determine if screens should be synchronized (same wallpaper)
        // This happens when: both enabled, same album, not separate schedules
        val shouldSync = settings.homeEnabled && settings.lockEnabled &&
                        settings.homeAlbumId != null &&
                        settings.homeAlbumId == settings.lockAlbumId &&
                        !settings.separateSchedules

        // Schedule using WorkManager (handles cancellation automatically)
        wallpaperScheduler.scheduleWallpaperChanges(
            homeIntervalMinutes = homeInterval,
            lockIntervalMinutes = lockInterval,
            synchronized = shouldSync,
            onlyIfNotScheduled = onlyIfNotScheduled
        )
    }
}
