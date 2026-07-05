package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.WallpaperMode
import kotlinx.serialization.Serializable

@Serializable
data class WallpaperProfileSnapshot(
    val id: Int,
    val name: String = "Profile $id",
    val mode: String,
    val enableChanger: Boolean,
    val separateSchedules: Boolean,
    val shuffleEnabled: Boolean,
    val homeEnabled: Boolean,
    val lockEnabled: Boolean,
    val homeAlbumId: String?,
    val lockAlbumId: String?,
    val homeFolderId: String? = null,
    val lockFolderId: String? = null,
    val liveAlbumId: String?,
    val liveFolderId: String? = null,
    val homeIntervalMinutes: Int,
    val lockIntervalMinutes: Int,
    val liveIntervalMinutes: Int,
    val homeScalingType: String,
    val lockScalingType: String,
    val liveScalingType: String,
    val adaptiveBrightness: Boolean,
    val homeEffects: WallpaperEffectsSnapshot,
    val lockEffects: WallpaperEffectsSnapshot,
    val liveEffects: WallpaperEffectsSnapshot
) {
    fun toMode(): WallpaperMode = WallpaperMode.fromString(mode)

    fun toScheduleSettings(): ScheduleSettings = ScheduleSettings(
        enableChanger = enableChanger,
        separateSchedules = separateSchedules,
        shuffleEnabled = shuffleEnabled,
        homeEnabled = homeEnabled,
        lockEnabled = lockEnabled,
        homeAlbumId = homeAlbumId,
        lockAlbumId = lockAlbumId,
        homeFolderId = homeFolderId,
        lockFolderId = lockFolderId,
        liveAlbumId = liveAlbumId,
        liveFolderId = liveFolderId,
        homeIntervalMinutes = homeIntervalMinutes,
        lockIntervalMinutes = lockIntervalMinutes,
        liveIntervalMinutes = liveIntervalMinutes,
        homeScalingType = ScalingType.fromString(homeScalingType),
        lockScalingType = ScalingType.fromString(lockScalingType),
        liveScalingType = ScalingType.fromString(liveScalingType),
        homeEffects = homeEffects.toWallpaperEffects(),
        lockEffects = lockEffects.toWallpaperEffects(),
        liveEffects = liveEffects.toWallpaperEffects(),
        adaptiveBrightness = adaptiveBrightness
    ).validate()

    companion object {
        fun from(id: Int, mode: WallpaperMode, settings: ScheduleSettings): WallpaperProfileSnapshot {
            return WallpaperProfileSnapshot(
                id = id,
                mode = mode.name,
                enableChanger = settings.enableChanger,
                separateSchedules = settings.separateSchedules,
                shuffleEnabled = settings.shuffleEnabled,
                homeEnabled = settings.homeEnabled,
                lockEnabled = settings.lockEnabled,
                homeAlbumId = settings.homeAlbumId,
                lockAlbumId = settings.lockAlbumId,
                homeFolderId = settings.homeFolderId,
                lockFolderId = settings.lockFolderId,
                liveAlbumId = settings.liveAlbumId,
                liveFolderId = settings.liveFolderId,
                homeIntervalMinutes = settings.homeIntervalMinutes,
                lockIntervalMinutes = settings.lockIntervalMinutes,
                liveIntervalMinutes = settings.liveIntervalMinutes,
                homeScalingType = settings.homeScalingType.name,
                lockScalingType = settings.lockScalingType.name,
                liveScalingType = settings.liveScalingType.name,
                adaptiveBrightness = settings.adaptiveBrightness,
                homeEffects = WallpaperEffectsSnapshot.from(settings.homeEffects),
                lockEffects = WallpaperEffectsSnapshot.from(settings.lockEffects),
                liveEffects = WallpaperEffectsSnapshot.from(settings.liveEffects)
            )
        }
    }
}

@Serializable
data class WallpaperEffectsSnapshot(
    val enableBlur: Boolean,
    val blurPercentage: Int,
    val enableDarken: Boolean,
    val darkenPercentage: Int,
    val enableVignette: Boolean,
    val vignettePercentage: Int,
    val enableGrayscale: Boolean,
    val grayscalePercentage: Int,
    val enableDoubleTap: Boolean,
    val enableChangeOnScreenOff: Boolean,
    val enableChangeOnScreenOn: Boolean,
    val enableParallax: Boolean,
    val parallaxIntensity: Int,
    val crossfadeDurationMs: Int
) {
    fun toWallpaperEffects(): WallpaperEffects = WallpaperEffects(
        enableBlur = enableBlur,
        blurPercentage = blurPercentage,
        enableDarken = enableDarken,
        darkenPercentage = darkenPercentage,
        enableVignette = enableVignette,
        vignettePercentage = vignettePercentage,
        enableGrayscale = enableGrayscale,
        grayscalePercentage = grayscalePercentage,
        enableDoubleTap = enableDoubleTap,
        enableChangeOnScreenOff = enableChangeOnScreenOff,
        enableChangeOnScreenOn = enableChangeOnScreenOn,
        enableParallax = enableParallax,
        parallaxIntensity = parallaxIntensity,
        crossfadeDurationMs = crossfadeDurationMs
    ).validate()

    companion object {
        fun from(effects: WallpaperEffects): WallpaperEffectsSnapshot = WallpaperEffectsSnapshot(
            enableBlur = effects.enableBlur,
            blurPercentage = effects.blurPercentage,
            enableDarken = effects.enableDarken,
            darkenPercentage = effects.darkenPercentage,
            enableVignette = effects.enableVignette,
            vignettePercentage = effects.vignettePercentage,
            enableGrayscale = effects.enableGrayscale,
            grayscalePercentage = effects.grayscalePercentage,
            enableDoubleTap = effects.enableDoubleTap,
            enableChangeOnScreenOff = effects.enableChangeOnScreenOff,
            enableChangeOnScreenOn = effects.enableChangeOnScreenOn,
            enableParallax = effects.enableParallax,
            parallaxIntensity = effects.parallaxIntensity,
            crossfadeDurationMs = effects.crossfadeDurationMs
        )
    }
}