package com.anthonyla.paperize.core.util

import android.content.Context
import android.os.Build
import com.anthonyla.paperize.domain.model.LockOverlayPreset

object LockOverlayPresetManager {
    private const val PREFS_NAME = "lock_overlay_preset"
    private const val KEY_DEVICE_NAME = "deviceName"
    private const val KEY_CLOCK_X = "clockX"
    private const val KEY_CLOCK_Y = "clockY"
    private const val KEY_CLOCK_SCALE = "clockScale"
    private const val KEY_WIDGET_Y = "widgetY"

    fun load(context: Context): LockOverlayPreset {
        val basePreset = defaultForDevice()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CLOCK_X)) return basePreset

        return LockOverlayPreset(
            deviceName = prefs.getString(KEY_DEVICE_NAME, basePreset.deviceName)
                ?: basePreset.deviceName,
            clockX = prefs.getFloat(KEY_CLOCK_X, basePreset.clockX),
            clockY = prefs.getFloat(KEY_CLOCK_Y, basePreset.clockY),
            clockScale = prefs.getFloat(KEY_CLOCK_SCALE, basePreset.clockScale),
            widgetY = prefs.getFloat(KEY_WIDGET_Y, basePreset.widgetY)
        ).sanitized()
    }

    fun save(context: Context, preset: LockOverlayPreset) {
        val sanitized = preset.sanitized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_NAME, sanitized.deviceName)
            .putFloat(KEY_CLOCK_X, sanitized.clockX)
            .putFloat(KEY_CLOCK_Y, sanitized.clockY)
            .putFloat(KEY_CLOCK_SCALE, sanitized.clockScale)
            .putFloat(KEY_WIDGET_Y, sanitized.widgetY)
            .apply()
    }

    fun reset(context: Context): LockOverlayPreset {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        return defaultForDevice()
    }

    private fun defaultForDevice(): LockOverlayPreset {
        val model = Build.MODEL.orEmpty()
        return when {
            model.contains("S24 Ultra", ignoreCase = true) ||
                model.contains("SM-S928", ignoreCase = true) -> LockOverlayPreset.galaxyS24Ultra()
            else -> LockOverlayPreset.genericGalaxy(model.ifBlank { "Galaxy" })
        }
    }
}
