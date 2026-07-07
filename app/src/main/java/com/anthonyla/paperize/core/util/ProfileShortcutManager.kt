package com.anthonyla.paperize.core.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.service.quicksettings.TileService
import android.util.Log
import com.anthonyla.paperize.R
import com.anthonyla.paperize.domain.model.WallpaperProfileSnapshot
import com.anthonyla.paperize.presentation.profile.ApplyProfileActivity
import com.anthonyla.paperize.service.tile.Profile1TileService
import com.anthonyla.paperize.service.tile.Profile2TileService
import com.anthonyla.paperize.service.tile.Profile3TileService

object ProfileShortcutManager {
    private const val TAG = "ProfileShortcutManager"

    fun updateApplyShortcuts(
        context: Context,
        profiles: List<WallpaperProfileSnapshot?>
    ) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = (1..3).map { id ->
            val label = profiles.getOrNull(id - 1)?.name
                ?.takeIf { it.isNotBlank() }
                ?: "Apply Profile $id"
            ShortcutInfo.Builder(context, "apply_profile_$id")
                .setShortLabel(label.take(20).ifBlank { "Profile $id" })
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("paperize://profile/apply?id=$id")
                        setClass(context, ApplyProfileActivity::class.java)
                    }
                )
                .build()
        }
        runCatching { manager.dynamicShortcuts = shortcuts }
            .onFailure { Log.w(TAG, "Unable to update profile shortcuts", it) }
        requestTileRefresh(context)
    }

    fun requestTileRefresh(context: Context) {
        val appContext = context.applicationContext
        listOf(
            Profile1TileService::class.java,
            Profile2TileService::class.java,
            Profile3TileService::class.java
        ).forEach { serviceClass ->
            runCatching {
                TileService.requestListeningState(appContext, ComponentName(appContext, serviceClass))
            }.onFailure { Log.w(TAG, "Unable to request profile tile refresh", it) }
        }
    }
}
