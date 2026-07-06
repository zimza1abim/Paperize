package com.anthonyla.paperize.service.livewallpaper

import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMediaType
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.service.livewallpaper.gl.GLWallpaperService
import com.anthonyla.paperize.service.livewallpaper.renderer.AnimatedImageUriLoader
import com.anthonyla.paperize.service.livewallpaper.renderer.ContentUriImageLoader
import com.anthonyla.paperize.service.livewallpaper.renderer.EmptyImageLoader
import com.anthonyla.paperize.service.livewallpaper.renderer.ImageLoader
import com.anthonyla.paperize.service.livewallpaper.renderer.PaperizeWallpaperRenderer
import com.anthonyla.paperize.service.livewallpaper.renderer.PaperizeRenderController
import com.anthonyla.paperize.service.livewallpaper.renderer.VideoUriImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.LiveWallpaperStatusManager
import com.anthonyla.paperize.core.util.isValid
import com.anthonyla.paperize.domain.model.Wallpaper
import com.anthonyla.paperize.service.livewallpaper.gl.GLCompatibility
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.widget.Toast
import com.anthonyla.paperize.R

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PaperizeLiveWallpaperEntryPoint {
    fun settingsRepository(): SettingsRepository
    fun wallpaperRepository(): WallpaperRepository
}

@AndroidEntryPoint
class PaperizeLiveWallpaperService : GLWallpaperService(), LifecycleOwner {

    companion object {
        private const val TAG = "PaperizeLiveWallpaper"
        private const val SCREEN_ON_CHANGE_DEBOUNCE_MS = 800L
    }

    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        LiveWallpaperStatusManager.markEngineSeen(applicationContext)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onCreateEngine(): Engine {
        return PaperizeLiveWallpaperEngine()
    }

    inner class PaperizeLiveWallpaperEngine : GLEngine(),
        LifecycleOwner,
        SavedStateRegistryOwner,
        PaperizeRenderController.Callbacks,
        PaperizeWallpaperRenderer.Callbacks {

        private var settingsRepository: SettingsRepository? = null
        private var wallpaperRepository: WallpaperRepository? = null

        private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        private lateinit var renderer: PaperizeWallpaperRenderer
        private lateinit var renderController: PaperizeRenderController
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var currentAlbumId: String? = null
        private var hasShownParallaxWarning = false
        private var reapplyCurrentWallpaperOnce = false
        private var settingsObserverStarted = false

        private val gestureDetector = GestureDetector(
            this@PaperizeLiveWallpaperService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    handleDoubleTap()
                    return true
                }
            }
        )

        private val reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Constants.ACTION_RELOAD_WALLPAPER -> {
                        Log.d(TAG, "Received reload broadcast")
                        reapplyCurrentWallpaperOnce = false
                        renderController.reloadCurrentArtwork(com.anthonyla.paperize.service.livewallpaper.renderer.ReloadImmediate)
                    }
                    Constants.ACTION_REAPPLY_CURRENT_WALLPAPER -> {
                        Log.d(TAG, "Received reapply-current broadcast")
                        reapplyCurrentWallpaperOnce = true
                        renderController.reloadCurrentArtwork(com.anthonyla.paperize.service.livewallpaper.renderer.ReloadImmediate)
                    }
                }
            }
        }

        private var lastScreenOnChangeAt = 0L
        private var screenOffSeen = false
        private var handledCurrentScreenOn = false
        private var pendingScreenOnChange = false

        private val screenEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON -> handleScreenOnChange("screen-on-broadcast")
                    Intent.ACTION_USER_UNLOCKED -> startLiveWallpaperAfterUnlock()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            Log.d(TAG, "Engine created")
            LiveWallpaperStatusManager.markEngineSeen(applicationContext)

            // Enable offset notifications to receive scroll events
            setOffsetNotificationsEnabled(true)

            // Enable touch events for double-tap gesture detection
            setTouchEventsEnabled(true)

            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
renderer = PaperizeWallpaperRenderer(applicationContext, this)
            renderController = object : PaperizeRenderController(renderer, this@PaperizeLiveWallpaperEngine, engineScope) {
                override suspend fun openDownloadedCurrentArtwork(): ImageLoader = withContext(Dispatchers.IO) {
                    if (!isUserUnlocked()) {
                        Log.d(TAG, "User locked; delaying live wallpaper media load")
                        return@withContext EmptyImageLoader
                    }

                    val repositories = ensureRepositories()
                    val settingsRepository = repositories.first
                    val wallpaperRepository = repositories.second

                    val mode = settingsRepository.getWallpaperMode()
                    val settings = ensureRepositories().first.getScheduleSettings()
                    
                    // Live Wallpaper only operates in LIVE mode
                    // In STATIC mode, the static wallpaper worker handles HOME/LOCK screens separately
                    if (mode != com.anthonyla.paperize.core.WallpaperMode.LIVE) {
                        Log.d(TAG, "App is in STATIC mode, Live Wallpaper not active")
                        return@withContext EmptyImageLoader
                    }
                    
                    val albumId = settings.liveAlbumId

                    if (albumId == null) {
                        Log.w(TAG, "No live album ID set")
                        return@withContext EmptyImageLoader
                    }

                    val reapplyCurrent = reapplyCurrentWallpaperOnce
                    reapplyCurrentWallpaperOnce = false
                    if (reapplyCurrent) {
                        val current = wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.LIVE)
                        if (current != null && current.uri.toUri().isValid(contentResolver)) {
                            Log.d(TAG, "Reapplying current live wallpaper: ${current.logSummary()}")
                            return@withContext createImageLoader(current, settings.liveScalingType)
                        }
                        Log.d(TAG, "No current live wallpaper to reapply; falling back to next queue item")
                    }

                    // Check if queue exists, build it if empty
                    val queueCheck = wallpaperRepository.getNextWallpaperInQueue(albumId, ScreenType.LIVE)
                    if (queueCheck == null) {
                        wallpaperRepository.buildWallpaperQueue(albumId, ScreenType.LIVE, settings.shuffleEnabled, settings.liveFolderId)
                    }
                    
                    var wallpaper: Wallpaper? = null
                    val currentWallpaper = wallpaperRepository.getCurrentWallpaper(albumId, ScreenType.LIVE)
                    var skippedCurrentWallpaper: Wallpaper? = null
                    var maxRetries = Constants.MAX_WALLPAPER_LOAD_RETRIES // Prevent infinite loop
                    var queueRebuildAttempts = 0

                    while (wallpaper == null && maxRetries > 0) {
                        // Atomically get and remove from queue
                        val candidate = wallpaperRepository.getAndDequeueWallpaper(albumId, ScreenType.LIVE)

                        if (candidate == null) {
                            queueRebuildAttempts++
                            if (queueRebuildAttempts > Constants.MAX_QUEUE_REBUILD_ATTEMPTS) {
                                if (skippedCurrentWallpaper != null) {
                                    wallpaper = skippedCurrentWallpaper
                                    break
                                }
                                Log.w(TAG, "No wallpapers in album $albumId after retries")
                                return@withContext EmptyImageLoader
                            }

                            // Rebuild queue
                            wallpaperRepository.buildWallpaperQueue(albumId, ScreenType.LIVE, settings.shuffleEnabled, settings.liveFolderId)
                            continue
                        }

                        // Validate URI
                        val uri = candidate.uri.toUri()
                        if (uri.isValid(contentResolver)) {
                            if (candidate.id == currentWallpaper?.id) {
                                Log.d(TAG, "Skipping current live wallpaper candidate: ${candidate.logSummary()}")
                                skippedCurrentWallpaper = candidate
                                maxRetries--
                                continue
                            }
                            wallpaper = candidate
                        } else {
                            // Skip this cycle — do not permanently delete.
                            // A transient permission or storage issue should not remove it from the album.
                            // AlbumRefreshWorker handles pruning of truly invalid URIs on its daily scan.
                            maxRetries--
                        }
                    }

                    if (wallpaper == null) {
                        wallpaper = skippedCurrentWallpaper
                        if (wallpaper == null) {
                            Log.w(TAG, "No valid wallpaper found after retries")
                            return@withContext EmptyImageLoader
                        }
                        Log.d(TAG, "Only current live wallpaper was available; reusing it: ${wallpaper.logSummary()}")
                    }

                    // Peek at queue to see if it needs refilling (not dequeuing, just checking)
                    val nextInQueue = wallpaperRepository.getNextWallpaperInQueue(albumId, ScreenType.LIVE)
                    if (nextInQueue == null) {
                        wallpaperRepository.buildWallpaperQueue(albumId, ScreenType.LIVE, settings.shuffleEnabled, settings.liveFolderId)
                    }

                    try {
                        wallpaperRepository.setCurrentWallpaper(albumId, ScreenType.LIVE, wallpaper.id)
                        Log.d(TAG, "Selected live wallpaper: ${wallpaper.logSummary()}")
                        createImageLoader(wallpaper, settings.liveScalingType)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating image loader", e)
                        EmptyImageLoader
                    }
                }
            }

            lifecycle.addObserver(renderController)
            setEGLContextClientVersion(Constants.GL_ES_VERSION)
            setEGLConfigChooser(8, 8, 8, 0, 0, 0)
            setRenderer(renderer)
            setRenderMode(RENDERMODE_WHEN_DIRTY)
            requestRender()

            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            if (isUserUnlocked()) {
                startLiveWallpaperAfterUnlock()
            } else {
                Log.d(TAG, "Engine created before user unlock; keeping renderer alive with empty frame")
            }

            // Register broadcast receiver for reload
            val filter = IntentFilter().apply {
                addAction(Constants.ACTION_RELOAD_WALLPAPER)
                addAction(Constants.ACTION_REAPPLY_CURRENT_WALLPAPER)
            }
            ContextCompat.registerReceiver(
                applicationContext,
                reloadReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            // Register screen event receiver for wallpaper changes on screen off/on.
            val screenEventFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }
            ContextCompat.registerReceiver(
                applicationContext,
                screenEventReceiver,
                screenEventFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        private fun isAnimatedImageCandidate(uri: android.net.Uri): Boolean {
            val mimeType = try {
                contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            if (mimeType == "image/gif" || mimeType == "image/webp") {
                return true
            }

            val path = uri.toString().substringBefore('?').lowercase()
            return path.endsWith(".gif") || path.endsWith(".webp")
        }

        private fun createImageLoader(
            wallpaper: Wallpaper,
            scalingType: com.anthonyla.paperize.core.ScalingType
        ): ImageLoader {
            val uri = wallpaper.uri.toUri()
            return when (wallpaper.mediaType) {
                WallpaperMediaType.VIDEO -> VideoUriImageLoader(
                    applicationContext,
                    uri,
                    scalingType,
                    wallpaper.framing
                )
                WallpaperMediaType.IMAGE -> {
                    if (isAnimatedImageCandidate(uri)) {
                        AnimatedImageUriLoader(
                            applicationContext,
                            uri,
                            scalingType,
                            wallpaper.framing
                        )
                    } else {
                        ContentUriImageLoader(
                            contentResolver,
                            uri,
                            scalingType,
                            wallpaper.framing
                        )
                    }
                }
            }
        }

        private fun Wallpaper.logSummary(): String {
            return "id=${id.take(8)}, album=${albumId.take(8)}, mediaType=$mediaType, file=$displayFileName"
        }

        private fun isUserUnlocked(): Boolean {
            return applicationContext.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        }

        private fun ensureRepositories(): Pair<SettingsRepository, WallpaperRepository> {
            val currentSettings = settingsRepository
            val currentWallpapers = wallpaperRepository
            if (currentSettings != null && currentWallpapers != null) {
                return currentSettings to currentWallpapers
            }

            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                PaperizeLiveWallpaperEntryPoint::class.java
            )
            val initializedSettings = entryPoint.settingsRepository()
            val initializedWallpapers = entryPoint.wallpaperRepository()
            settingsRepository = initializedSettings
            wallpaperRepository = initializedWallpapers
            return initializedSettings to initializedWallpapers
        }

        private fun startLiveWallpaperAfterUnlock() {
            if (!isUserUnlocked()) return
            LiveWallpaperStatusManager.markEngineSeen(applicationContext)
            observeSettings()
        }

        private fun observeSettings() {
            if (settingsObserverStarted) return
            settingsObserverStarted = true
            engineScope.launch {
                combine(
                    ensureRepositories().first.getScheduleSettingsFlow(),
                    ensureRepositories().first.getWallpaperModeFlow()
                ) { settings, mode ->
                    Pair(settings, mode)
                }.catch { e ->
                    Log.e(TAG, "Error observing settings", e)
                }.collect { (settings, mode) ->
                    // Only process settings in LIVE mode
                    // In STATIC mode, the static wallpaper worker handles HOME/LOCK screens
                    if (mode != com.anthonyla.paperize.core.WallpaperMode.LIVE) {
                        return@collect
                    }
                    
                    val albumId = settings.liveAlbumId
                    val effects = settings.liveEffects
                    val scalingType = settings.liveScalingType

                    renderer.updateEffects(effects)
                    renderer.updateScalingType(scalingType)
                    
                    // Show Toast warning if parallax is enabled but device has offset issues
                    if (effects.enableParallax && !hasShownParallaxWarning && GLCompatibility.shouldWarnAboutParallax()) {
                        hasShownParallaxWarning = true
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                applicationContext,
                                R.string.parallax_may_not_work,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    
                    // Reload if album changed. The first engine load should reapply the
                    // persisted current wallpaper instead of consuming the next queue item;
                    // live wallpaper engines may be recreated independently of the user's
                    // schedule interval.
                    if (albumId != currentAlbumId) {
                        Log.d(TAG, "Album changed from $currentAlbumId to $albumId, reloading")
                        if (currentAlbumId == null) {
                            reapplyCurrentWallpaperOnce = true
                        } else {
                            reapplyCurrentWallpaperOnce = false
                        }
                        currentAlbumId = albumId
                        renderController.reloadCurrentArtwork(com.anthonyla.paperize.service.livewallpaper.renderer.ReloadImmediate)
                    }
                }
            }
        }


        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            renderController.visible = visible
            super.onVisibilityChanged(visible)
            if (isPreview) {
                Log.d(TAG, "Skipping visibility-triggered change in preview")
                return
            }

            if (visible) {
                LiveWallpaperStatusManager.markEngineSeen(applicationContext)
                renderer.setPlaybackVisible(true)
                val consumedPendingReload = renderController.consumePendingReloadOnVisibleFlag()
                if (consumedPendingReload) {
                    Log.d(TAG, "Pending reload was consumed on visible")
                }
                val trigger = if (pendingScreenOnChange) "pending-screen-on-visible" else "visibility"
                pendingScreenOnChange = false
                handleScreenOnChange(trigger)
            } else {
                renderer.setPlaybackVisible(false)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            Log.d(TAG, "onOffsetsChanged: xOffset=$xOffset, xOffsetStep=$xOffsetStep, xPixelOffset=$xPixelOffset")
            renderer.setNormalOffsetX(xOffset)
        }

        override fun onTouchEvent(event: MotionEvent) {
            try {
                gestureDetector.onTouchEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Error processing touch event", e)
            }
            super.onTouchEvent(event)
        }

        override fun onDestroy() {
            try {
                applicationContext.unregisterReceiver(reloadReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering reload receiver", e)
            }
            try {
                applicationContext.unregisterReceiver(screenEventReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen event receiver", e)
            }

            // Cleanup renderer resources on GL thread
            queueEvent {
                renderer.destroy()
            }

            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            engineScope.cancel()
            super.onDestroy()
        }

        override fun queueEventOnGlThread(event: () -> Unit) {
            queueEvent(event)
        }

        private fun handleDoubleTap() {
            engineScope.launch {
                val settings = ensureRepositories().first.getScheduleSettings()
                
                // Always check liveEffects since double-tap is only available in live wallpaper mode
                val doubleTapEnabled = settings.liveEffects.enableDoubleTap

                if (doubleTapEnabled) {
                    renderController.reloadCurrentArtwork(com.anthonyla.paperize.service.livewallpaper.renderer.ReloadImmediate)
                }
            }
        }

        private fun handleScreenOff() {
            if (!isUserUnlocked()) return

            screenOffSeen = true
            handledCurrentScreenOn = false
            pendingScreenOnChange = false
            Log.d(TAG, "Screen off - keeping current live wallpaper for lock/AOD")
        }

        private fun handleScreenOnChange(trigger: String) {
            if (isPreview) return

            if (!isUserUnlocked()) return

            if (!screenOffSeen) {
                Log.d(TAG, "Screen on change ignored because no preceding screen off was seen ($trigger)")
                return
            }

            if (handledCurrentScreenOn) {
                Log.d(TAG, "Screen on change ignored because this wake cycle was already handled ($trigger)")
                return
            }

            if (!renderController.visible) {
                Log.d(TAG, "Screen on change ignored until live wallpaper becomes visible ($trigger)")
                pendingScreenOnChange = true
                return
            }

            engineScope.launch {
                val settings = ensureRepositories().first.getScheduleSettings()
                if (!settings.liveEffects.enableChangeOnScreenOn) return@launch

                val now = SystemClock.elapsedRealtime()
                if (now - lastScreenOnChangeAt < SCREEN_ON_CHANGE_DEBOUNCE_MS) {
                    Log.d(TAG, "Screen on change ignored due to debounce")
                    return@launch
                }

                lastScreenOnChangeAt = now
                handledCurrentScreenOn = true
                screenOffSeen = false

                Log.d(TAG, "Screen on - changing wallpaper ($trigger)")
                renderController.reloadCurrentArtwork(com.anthonyla.paperize.service.livewallpaper.renderer.ReloadImmediate)
            }
        }
    }
}
