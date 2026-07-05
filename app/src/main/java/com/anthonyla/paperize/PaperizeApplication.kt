package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.UserManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.service.worker.AlbumRefreshWorker
import com.anthonyla.paperize.core.util.DataResetManager
import com.anthonyla.paperize.core.util.ProfileShortcutManager
import com.anthonyla.paperize.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for Paperize
 *
 * Annotated with @HiltAndroidApp to enable dependency injection
 * Implements Configuration.Provider for WorkManager with Hilt support
 */
@HiltAndroidApp
class PaperizeApplication : Application(), Configuration.Provider {

    companion object {
        private const val STARTUP_REFRESH_PREFS = "startup_refresh"
        private const val LAST_STARTUP_REFRESH_ENQUEUE = "last_album_refresh_enqueue"
        private const val STARTUP_REFRESH_THROTTLE_MS = 12 * 60 * 60 * 1000L
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()

        // Create notification channel (minSdk is 31, so always supported)
        createNotificationChannel()

        // Direct Boot: the live wallpaper service can be created before the user
        // unlocks after reboot. Avoid credential-encrypted storage and WorkManager
        // here, otherwise Android may drop the live wallpaper and fall back.
        if (getSystemService(UserManager::class.java)?.isUserUnlocked == false) {
            return
        }

        // Perform one-time data reset for major version upgrades (e.g., v3 -> v4)
        // Must run before any other initialization that accesses DB/preferences
        DataResetManager.performResetIfNeeded(this)

        updateProfileShortcutsOnStartup()

        // Trigger album refresh on app cold start to validate and update all albums
        refreshAlbumsOnStartup()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun updateProfileShortcutsOnStartup() {
        CoroutineScope(Dispatchers.IO).launch {
            ProfileShortcutManager.updateApplyShortcuts(
                this@PaperizeApplication,
                (1..3).map { settingsRepository.getWallpaperProfile(it) }
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Refresh all albums on app startup to validate and update wallpapers/folders
     *
     * This runs in the background without blocking app startup and ensures:
     * - Invalid wallpapers/folders are removed
     * - New wallpapers are discovered in existing folders
     * - Album covers are up-to-date
     */
    private fun refreshAlbumsOnStartup() {
        val prefs = getSharedPreferences(STARTUP_REFRESH_PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastEnqueue = prefs.getLong(LAST_STARTUP_REFRESH_ENQUEUE, 0L)
        if (now - lastEnqueue < STARTUP_REFRESH_THROTTLE_MS) {
            return
        }
        prefs.edit().putLong(LAST_STARTUP_REFRESH_ENQUEUE, now).apply()

        val workManager = WorkManager.getInstance(this)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val refreshWorkRequest = OneTimeWorkRequestBuilder<AlbumRefreshWorker>()
            .setConstraints(constraints)
            .addTag("startup_refresh")
            .build()

        // Use KEEP policy to avoid duplicate refreshes if app is quickly reopened
        workManager.enqueueUniqueWork(
            "album_refresh_on_startup",
            ExistingWorkPolicy.KEEP,
            refreshWorkRequest
        )
    }
}


