package com.anthonyla.paperize.core.util

import android.content.Context
import android.os.SystemClock

object LiveWallpaperStatusManager {
    private const val PREFS_NAME = "live_wallpaper_status"
    private const val KEY_LAST_ENGINE_SEEN_ELAPSED_MS = "last_engine_seen_elapsed_ms"
    private const val RECENT_ENGINE_WINDOW_MS = 30 * 60 * 1000L
    private const val BOOT_GRACE_WINDOW_MS = 5 * 60 * 1000L

    private fun prefsContext(context: Context): Context {
        return context.applicationContext.createDeviceProtectedStorageContext()
    }

    fun markEngineSeen(context: Context) {
        prefsContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ENGINE_SEEN_ELAPSED_MS, SystemClock.elapsedRealtime())
            .apply()
    }

    fun wasEngineSeenRecently(context: Context): Boolean {
        val lastSeen = prefsContext(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_ENGINE_SEEN_ELAPSED_MS, 0L)
        if (lastSeen <= 0L) return false

        val elapsed = SystemClock.elapsedRealtime() - lastSeen
        return elapsed in 0L..RECENT_ENGINE_WINDOW_MS
    }

    fun isWithinBootGracePeriod(): Boolean {
        return SystemClock.elapsedRealtime() in 0L..BOOT_GRACE_WINDOW_MS
    }
}