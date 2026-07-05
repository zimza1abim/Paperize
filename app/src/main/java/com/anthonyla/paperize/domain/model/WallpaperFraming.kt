package com.anthonyla.paperize.domain.model

/**
 * Per-wallpaper framing adjustment applied on top of the selected scaling mode.
 *
 * Offsets are normalized viewport translations, where 1 moves by one full screen width/height.
 */
data class WallpaperFraming(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    val hasCustomFraming: Boolean
        get() = scale != 1f || offsetX != 0f || offsetY != 0f

    fun sanitized(): WallpaperFraming = copy(
        scale = scale.coerceIn(MIN_SCALE, MAX_SCALE),
        offsetX = offsetX.coerceIn(MIN_OFFSET, MAX_OFFSET),
        offsetY = offsetY.coerceIn(MIN_OFFSET, MAX_OFFSET)
    )

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 8f
        const val MIN_OFFSET = -2f
        const val MAX_OFFSET = 2f

        val Default = WallpaperFraming()
    }
}
