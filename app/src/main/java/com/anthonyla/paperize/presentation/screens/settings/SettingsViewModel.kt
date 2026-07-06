package com.anthonyla.paperize.presentation.screens.settings

import android.content.Context
import com.anthonyla.paperize.core.constants.Constants

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.util.ProfileShortcutManager
import com.anthonyla.paperize.domain.model.AppSettings
import com.anthonyla.paperize.domain.model.Album
import com.anthonyla.paperize.domain.repository.AlbumRepository
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.model.WallpaperProfileSnapshot
import com.anthonyla.paperize.domain.usecase.ApplyWallpaperProfileUseCase
import com.anthonyla.paperize.domain.usecase.ProfileApplyResult
import com.anthonyla.paperize.domain.usecase.SaveWallpaperProfileUseCase
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Settings screen
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val albumRepository: AlbumRepository,
    private val wallpaperScheduler: WallpaperScheduler,
    private val saveWallpaperProfileUseCase: SaveWallpaperProfileUseCase,
    private val applyWallpaperProfileUseCase: ApplyWallpaperProfileUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    val appSettings: StateFlow<AppSettings?> = settingsRepository.getAppSettingsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,  // Start loading immediately to prevent onboarding flicker
            initialValue = null
        )

    val wallpaperMode: StateFlow<WallpaperMode> = settingsRepository.getWallpaperModeFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = WallpaperMode.STATIC
        )

    private val _profileSlots = MutableStateFlow((1..3).map { ProfileSlotUiState(id = it) })
    val profileSlots: StateFlow<List<ProfileSlotUiState>> = _profileSlots

    private val _profileMessage = MutableStateFlow<String?>(null)
    val profileMessage: StateFlow<String?> = _profileMessage

    val profileSources: StateFlow<List<ProfileSourceUiState>> = albumRepository.getAllAlbums()
        .map { albums -> albums.toProfileSources() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(Constants.FLOW_SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList()
        )

    init {
        refreshProfiles()
    }

    fun refreshProfiles() {
        viewModelScope.launch {
            val albumsById = albumRepository.getAllAlbums().first().associateBy { it.id }
            _profileSlots.value = (1..3).map { id ->
                val snapshot = settingsRepository.getWallpaperProfile(id)
                ProfileSlotUiState(
                    id = id,
                    snapshot = snapshot,
                    detail = snapshot?.toDisplayDetail(albumsById) ?: "Empty"
                )
            }
        }
    }

    fun saveConfiguredProfile(
        profileId: Int,
        profileName: String,
        mode: WallpaperMode,
        homeEnabled: Boolean,
        lockEnabled: Boolean,
        homeSourceKey: String?,
        lockSourceKey: String?,
        liveSourceKey: String?
    ) {
        viewModelScope.launch {
            val base = settingsRepository.getScheduleSettings()
            val homeSource = homeSourceKey?.let(ProfileSourceUiState::fromKey)
            val lockSource = lockSourceKey?.let(ProfileSourceUiState::fromKey)
            val liveSource = liveSourceKey?.let(ProfileSourceUiState::fromKey)

            val configured = when (mode) {
                WallpaperMode.LIVE -> {
                    if (liveSource == null) {
                        _profileMessage.value = "Select a live profile source"
                        return@launch
                    }
                    base.copy(
                        liveAlbumId = liveSource.albumId,
                        liveFolderId = liveSource.folderId
                    )
                }
                WallpaperMode.STATIC -> {
                    val validHome = homeEnabled && homeSource != null
                    val validLock = lockEnabled && lockSource != null
                    if (!validHome && !validLock) {
                        _profileMessage.value = "Select at least one static profile source"
                        return@launch
                    }
                    base.copy(
                        homeEnabled = validHome,
                        lockEnabled = validLock,
                        homeAlbumId = if (validHome) homeSource.albumId else null,
                        homeFolderId = if (validHome) homeSource.folderId else null,
                        lockAlbumId = if (validLock) lockSource.albumId else null,
                        lockFolderId = if (validLock) lockSource.folderId else null
                    )
                }
            }

            val snapshot = WallpaperProfileSnapshot.from(profileId, mode, configured)
                .copy(name = profileName.ifBlank { "Apply Profile $profileId" })
            settingsRepository.saveWallpaperProfile(snapshot)
            updateProfileShortcuts()
            _profileMessage.value = "${snapshot.name} saved"
            refreshProfiles()
        }
    }

    fun saveProfile(profileId: Int) {
        viewModelScope.launch {
            val profileName = profileName(profileId)
            _profileMessage.value = when (saveWallpaperProfileUseCase(profileId)) {
                ProfileApplyResult.Applied -> "$profileName saved"
                else -> "Failed to save $profileName"
            }
            updateProfileShortcuts()
            refreshProfiles()
        }
    }

    private suspend fun updateProfileShortcuts() {
        ProfileShortcutManager.updateApplyShortcuts(
            context = context,
            profiles = (1..3).map { settingsRepository.getWallpaperProfile(it) }
        )
    }

    fun applyProfile(profileId: Int) {
        viewModelScope.launch {
            val profileName = profileName(profileId)
            val result = applyWallpaperProfileUseCase(profileId)
            _profileMessage.value = when (result) {
                ProfileApplyResult.Applied -> {
                    ProfileShortcutManager.requestTileRefresh(context)
                    "$profileName applied"
                }
                ProfileApplyResult.NotFound -> "$profileName is not saved"
                ProfileApplyResult.InvalidProfile -> "$profileName is incomplete"
                ProfileApplyResult.NeedsLiveWallpaperSelection -> "Select Paperize live wallpaper first"
                ProfileApplyResult.DeferredUntilUnlocked -> "Unlock the phone before applying profile"
                ProfileApplyResult.Failed -> "Failed to apply $profileName"
            }
            refreshProfiles()
        }
    }

    private suspend fun profileName(profileId: Int): String {
        return settingsRepository.getWallpaperProfile(profileId)?.name?.takeIf { it.isNotBlank() }
            ?: "Profile $profileId"
    }

    fun consumeProfileMessage() {
        _profileMessage.value = null
    }

    fun updateDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateDarkMode(enabled)
        }
    }

    fun updateDynamicTheming(enabled: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateDynamicTheming(enabled)
        }
    }

    fun updateAnimate(enabled: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateAnimate(enabled)
        }
    }

    fun updateFirstLaunch(isFirstLaunch: Boolean) {
        viewModelScope.launch {
            // Use atomic update to prevent race conditions
            settingsRepository.updateFirstLaunch(isFirstLaunch)
        }
    }

    /**
     * Switch wallpaper mode while preserving album contents and per-mode selections.
     */
    fun switchWallpaperMode(newMode: WallpaperMode) {
        viewModelScope.launch {
            // Cancel schedules for the previous mode; the active screen will reschedule from saved settings.
            wallpaperScheduler.cancelAllWallpaperChanges()
            settingsRepository.setWallpaperMode(newMode)
        }
    }

    /**
     * Reset all app data - settings, albums, wallpapers, folders, queues, and alarms
     * This completely resets the app to initial state as if just installed
     */
    fun resetAllData() {
        viewModelScope.launch {
            // Cancel all scheduled wallpaper changes first
            wallpaperScheduler.cancelAllWallpaperChanges()

            // Delete all albums (cascades to delete all wallpapers, folders, and queues)
            when (val result = albumRepository.deleteAllAlbums()) {
                is com.anthonyla.paperize.core.Result.Success -> { /* Success */ }
                is com.anthonyla.paperize.core.Result.Error -> { 
                    Log.e(TAG, "Error deleting albums during reset", result.exception)
                }
                is com.anthonyla.paperize.core.Result.Loading -> { /* Loading state not used */ }
            }

            // Clear all settings (DataStore) - resets to default values
            // This includes setting firstLaunch back to true for onboarding
            settingsRepository.clearAllSettings()
        }
    }
}

data class ProfileSlotUiState(
    val id: Int,
    val snapshot: WallpaperProfileSnapshot? = null,
    val detail: String = "Empty"
) {
    val isSaved: Boolean get() = snapshot != null
    val modeLabel: String get() = snapshot?.mode ?: "Empty"
}

private fun WallpaperProfileSnapshot.toDisplayDetail(
    albumsById: Map<String, com.anthonyla.paperize.domain.model.Album>
): String {
    fun albumLine(label: String, albumId: String?): String {
        val album = albumId?.let(albumsById::get)
            ?: return "$label: Missing album"
        val folderText = if (album.folders.isEmpty()) {
            "no folders"
        } else {
            album.folders.joinToString(limit = 2) { folder ->
                "${folder.displayName} ${folder.wallpaperCount}"
            } + if (album.folders.size > 2) " +${album.folders.size - 2}" else ""
        }
        return "$label: ${album.name} (${album.totalWallpaperCount} items, $folderText)"
    }

    return when (mode) {
        "LIVE" -> albumLine("Live", liveAlbumId)
        else -> buildList {
            if (homeEnabled) add(albumLine("Home", homeAlbumId))
            if (lockEnabled) add(albumLine("Lock", lockAlbumId))
            if (isEmpty()) add("No target screen enabled")
        }.joinToString("\n")
    }
}





data class ProfileSourceUiState(
    val key: String,
    val label: String,
    val albumId: String,
    val folderId: String? = null
) {
    companion object {
        fun fromKey(key: String): ProfileSourceUiState? {
            val parts = key.split("|")
            return when (parts.firstOrNull()) {
                "album" -> parts.getOrNull(1)?.let { albumId ->
                    ProfileSourceUiState(key = key, label = albumId, albumId = albumId)
                }
                "folder" -> {
                    val albumId = parts.getOrNull(1)
                    val folderId = parts.getOrNull(2)
                    if (albumId != null && folderId != null) {
                        ProfileSourceUiState(key = key, label = folderId, albumId = albumId, folderId = folderId)
                    } else null
                }
                else -> null
            }
        }
    }
}

private fun List<Album>.toProfileSources(): List<ProfileSourceUiState> = flatMap { album ->
    buildList {
        add(
            ProfileSourceUiState(
                key = "album|${album.id}",
                label = "Album: ${album.name} (${album.totalWallpaperCount} items)",
                albumId = album.id
            )
        )
        album.folders.forEach { folder ->
            add(
                ProfileSourceUiState(
                    key = "folder|${album.id}|${folder.id}",
                    label = "Folder: ${album.name} / ${folder.displayName} (${folder.wallpaperCount} items)",
                    albumId = album.id,
                    folderId = folder.id
                )
            )
        }
    }
}

