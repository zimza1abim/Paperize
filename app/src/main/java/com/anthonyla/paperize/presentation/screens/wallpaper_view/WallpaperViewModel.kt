package com.anthonyla.paperize.presentation.screens.wallpaper_view

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.Result
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.domain.model.WallpaperFraming
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WallpaperViewUiState(
    val wallpaper: Wallpaper? = null,
    val framing: WallpaperFraming = WallpaperFraming.Default,
    val wallpaperMode: WallpaperMode? = null,
    val homeScalingType: ScalingType = ScalingType.FILL,
    val lockScalingType: ScalingType = ScalingType.FILL,
    val liveScalingType: ScalingType = ScalingType.FILL,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class WallpaperViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wallpaperRepository: WallpaperRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(WallpaperViewUiState())
    val uiState: StateFlow<WallpaperViewUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.getScheduleSettingsFlow(),
                settingsRepository.getWallpaperModeFlow()
            ) { settings, mode ->
                settings to mode
            }.collect { (settings, mode) ->
                _uiState.update {
                    it.copy(
                        wallpaperMode = mode,
                        homeScalingType = settings.homeScalingType,
                        lockScalingType = settings.lockScalingType,
                        liveScalingType = settings.liveScalingType
                    )
                }
            }
        }
    }

    fun loadWallpaper(uri: String) {
        if (_uiState.value.wallpaper?.uri == uri) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val wallpaper = wallpaperRepository.getWallpaperByUri(uri)
            _uiState.update {
                it.copy(
                    wallpaper = wallpaper,
                    framing = wallpaper?.framing?.sanitized() ?: WallpaperFraming.Default,
                    isLoading = false
                )
            }
        }
    }

    fun updateDraftFraming(framing: WallpaperFraming) {
        _uiState.update { it.copy(framing = framing.sanitized(), errorMessage = null) }
    }

    fun resetFraming() {
        updateDraftFraming(WallpaperFraming.Default)
    }

    fun discardDraftFraming() {
        _uiState.update {
            it.copy(
                framing = it.wallpaper?.framing?.sanitized() ?: WallpaperFraming.Default,
                errorMessage = null
            )
        }
    }

    fun saveFraming(framingOverride: WallpaperFraming? = null) {
        val current = _uiState.value.wallpaper ?: return
        val framing = (framingOverride ?: _uiState.value.framing).sanitized()

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            when (val result = wallpaperRepository.updateWallpaper(current.copy(framing = framing))) {
                is Result.Success -> {
                    context.sendBroadcast(
                        Intent(Constants.ACTION_REAPPLY_CURRENT_WALLPAPER).setPackage(context.packageName)
                    )
                    _uiState.update {
                        it.copy(
                            wallpaper = current.copy(framing = framing),
                            framing = framing,
                            isSaving = false
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = result.message ?: result.exception.message
                        )
                    }
                }
                Result.Loading -> Unit
            }
        }
    }
}
