package com.anthonyla.paperize.core.util

import android.content.Context
import com.anthonyla.paperize.domain.model.LockOverlayGuide

object LockOverlayGuideManager {
    private const val PREFS_NAME = "lock_overlay_guide"
    private const val KEY_SCREENSHOT_URI = "screenshot_uri"
    private const val KEY_ALPHA = "alpha"

    fun load(context: Context): LockOverlayGuide {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return LockOverlayGuide(
            screenshotUri = prefs.getString(KEY_SCREENSHOT_URI, null),
            alpha = prefs.getFloat(KEY_ALPHA, LockOverlayGuide.DEFAULT_ALPHA)
        ).sanitized()
    }

    fun save(context: Context, guide: LockOverlayGuide) {
        val sanitized = guide.sanitized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (sanitized.screenshotUri == null) {
                    remove(KEY_SCREENSHOT_URI)
                } else {
                    putString(KEY_SCREENSHOT_URI, sanitized.screenshotUri)
                }
                putFloat(KEY_ALPHA, sanitized.alpha)
            }
            .apply()
    }

    fun clearScreenshot(context: Context): LockOverlayGuide {
        val guide = load(context).copy(screenshotUri = null)
        save(context, guide)
        return guide
    }
}
