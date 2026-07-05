package com.anthonyla.paperize.domain.model

data class LockOverlayPreset(
    val deviceName: String,
    val clockX: Float,
    val clockY: Float,
    val clockScale: Float,
    val widgetY: Float
) {
    fun sanitized(): LockOverlayPreset = copy(
        clockX = clockX.coerceIn(MIN_POSITION, MAX_POSITION),
        clockY = clockY.coerceIn(MIN_POSITION, MAX_POSITION),
        clockScale = clockScale.coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE),
        widgetY = widgetY.coerceIn(MIN_POSITION, MAX_POSITION)
    )

    companion object {
        const val MIN_POSITION = 0f
        const val MAX_POSITION = 1f
        const val MIN_CLOCK_SCALE = 0.7f
        const val MAX_CLOCK_SCALE = 1.5f

        fun galaxyS24Ultra(): LockOverlayPreset = LockOverlayPreset(
            deviceName = "Galaxy S24 Ultra",
            clockX = 0.5f,
            clockY = 0.115f,
            clockScale = 1.0f,
            widgetY = 0.82f
        )

        fun genericGalaxy(deviceName: String): LockOverlayPreset = LockOverlayPreset(
            deviceName = deviceName,
            clockX = 0.5f,
            clockY = 0.12f,
            clockScale = 1.0f,
            widgetY = 0.82f
        )
    }
}
