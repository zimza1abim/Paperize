package com.anthonyla.paperize.presentation.screens.wallpaper_mode_selection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WallpaperModeSelectionViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val wallpaperScheduler: com.anthonyla.paperize.service.worker.WallpaperScheduler
) : ViewModel() {

    /**
     * Set wallpaper mode while preserving any albums and per-mode selections already configured.
     */
    fun setWallpaperMode(mode: WallpaperMode) {
        viewModelScope.launch {
            // Cancel schedules from the previous mode; saved settings stay intact.
            wallpaperScheduler.cancelAllWallpaperChanges()
            settingsRepository.setWallpaperMode(mode)
        }
    }
}
