package com.anthonyla.paperize.domain.model

import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.core.constants.Constants

/**
 * Domain model for Wallpaper Schedule Settings
 */
data class ScheduleSettings(
    val enableChanger: Boolean = false,
    val separateSchedules: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val homeEnabled: Boolean = false,
    val lockEnabled: Boolean = false,
    val homeAlbumId: String? = null,
    val lockAlbumId: String? = null,
    val homeFolderId: String? = null,
    val lockFolderId: String? = null,
    val homeIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val lockIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val liveIntervalMinutes: Int = Constants.DEFAULT_INTERVAL_MINUTES,
    val homeScalingType: ScalingType = ScalingType.FILL,
    val lockScalingType: ScalingType = ScalingType.FILL,
    val homeEffects: WallpaperEffects = WallpaperEffects.none(),
    val lockEffects: WallpaperEffects = WallpaperEffects.none(),
    val liveAlbumId: String? = null,
    val liveFolderId: String? = null,
    val liveScalingType: ScalingType = ScalingType.FILL,
    val liveEffects: WallpaperEffects = WallpaperEffects.none(),
    val adaptiveBrightness: Boolean = false
) {
    /**
     * Validate interval values
     */
    fun validate(): ScheduleSettings = copy(
        homeIntervalMinutes = homeIntervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES),
        lockIntervalMinutes = lockIntervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES),
        liveIntervalMinutes = liveIntervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES),
        homeEffects = homeEffects.validate(),
        lockEffects = lockEffects.validate(),
        liveEffects = liveEffects.validate()
    )

    /**
     * Check if scheduling-related settings changed (not just display settings)
     *
     * Returns true if any of these changed:
     * - Screen enable/disable
     * - Interval timing
     * - Separate schedules toggle
     * - Shuffle (handled separately with queue clearing)
     *
     * Display-only settings (scaling) don't affect scheduling
     */
    fun hasSchedulingChanges(other: ScheduleSettings): Boolean {
        return homeEnabled != other.homeEnabled ||
               lockEnabled != other.lockEnabled ||
               homeIntervalMinutes != other.homeIntervalMinutes ||
               lockIntervalMinutes != other.lockIntervalMinutes ||
               separateSchedules != other.separateSchedules ||
               homeAlbumId != other.homeAlbumId ||
               lockAlbumId != other.lockAlbumId ||
               liveAlbumId != other.liveAlbumId ||
               homeFolderId != other.homeFolderId ||
               lockFolderId != other.lockFolderId ||
               liveFolderId != other.liveFolderId ||
               liveIntervalMinutes != other.liveIntervalMinutes
    }

    /**
     * Check if display settings changed (scaling)
     *
     * When these change, we should reapply the current wallpaper immediately
     * to show the user the effect, but we don't need to reschedule WorkManager
     */
    fun hasDisplayChanges(other: ScheduleSettings): Boolean {
        return homeScalingType != other.homeScalingType ||
               lockScalingType != other.lockScalingType ||
               homeEffects != other.homeEffects ||
               lockEffects != other.lockEffects ||
               liveEffects != other.liveEffects ||
               liveScalingType != other.liveScalingType ||
               adaptiveBrightness != other.adaptiveBrightness
    }

    companion object {
        fun default() = ScheduleSettings()
    }
}
