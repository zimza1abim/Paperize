package com.anthonyla.paperize.service.livewallpaper.renderer
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.ScalingType

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.anthonyla.paperize.domain.model.WallpaperEffects
import com.anthonyla.paperize.domain.model.WallpaperFraming
import com.anthonyla.paperize.service.livewallpaper.gl.GLAnimatedImageWallpaper
import com.anthonyla.paperize.service.livewallpaper.gl.GLPicture
import com.anthonyla.paperize.service.livewallpaper.gl.GLUtil
import com.anthonyla.paperize.service.livewallpaper.gl.GLVideoWallpaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Main OpenGL renderer for live wallpaper.
 * Handles texture rendering, crossfades, and parallax,
 * crossfade animations, and parallax scrolling.
 *
 * @property context Application context
 * @property callbacks Callbacks for queuing events on GL thread
 */
class PaperizeWallpaperRenderer(
    private val context: Context,
    private val callbacks: Callbacks
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "PaperizeRenderer"
    }

    /**
     * Callbacks for communication with the engine.
     */
    interface Callbacks {
        fun queueEventOnGlThread(event: () -> Unit)
        fun requestRender()
    }

    // Surface dimensions
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    // Display refresh rate (for future optimizations)
    private var displayRefreshRate = 60f

    // Shader programs
    private var effectsProgram = 0
    private var videoEffectsProgram = 0

    // Attribute/uniform locations (for image frame program)
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0
    private var uTextureHandle = 0
    private var uMvpMatrixHandle = 0
    private var uAlphaHandle = 0

    // Attribute/uniform locations (for video OES frame program)
    private var videoPositionHandle = 0
    private var videoTexCoordHandle = 0
    private var videoTextureHandle = 0
    private var videoMvpMatrixHandle = 0
    private var videoAlphaHandle = 0

    // Current wallpaper
    private var currentWallpaper: WallpaperRenderable? = null

    // Crossfade state (time-based)
    private var nextWallpaper: WallpaperRenderable? = null
    private var pendingVideoWallpaper: VideoRenderable? = null
    private var crossfadeProgress = 0f
    private var crossfadeStartTimeNanos = 0L

    // Effects
    @Volatile private var currentEffects = WallpaperEffects()
    @Volatile private var crossfadeDurationMs = Constants.CROSSFADE_DURATION_MS

    // Parallax
    @Volatile private var normalOffsetX = 0.5f

    // Scaling
    @Volatile private var currentScalingType = ScalingType.FILL
    @Volatile private var playbackVisible = true

    // Matrices
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    // Coroutine scope for background loading
    private val loadingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentLoadJob: Job? = null
    private val frameHandler = Handler(Looper.getMainLooper())
    @Volatile private var animatedFrameRequestScheduled = false

    private sealed interface WallpaperRenderable {
        val width: Int
        val height: Int
        val brightnessFactor: Float
        val isVideo: Boolean
        val framing: WallpaperFraming
        fun recycle()
    }

    private class PictureRenderable(
        val picture: GLPicture,
        override val framing: WallpaperFraming
    ) : WallpaperRenderable {
        override val width: Int get() = picture.width
        override val height: Int get() = picture.height
        override val brightnessFactor: Float get() = picture.brightnessFactor
        override val isVideo: Boolean = false
        override fun recycle() = picture.recycle()
    }

    private class VideoRenderable(
        val video: GLVideoWallpaper,
        override val framing: WallpaperFraming
    ) : WallpaperRenderable {
        override val width: Int get() = video.width
        override val height: Int get() = video.height
        override val brightnessFactor: Float get() = video.brightnessFactor
        override val isVideo: Boolean = true
        override fun recycle() = video.recycle()
    }

    private class AnimatedImageRenderable(
        val animatedImage: GLAnimatedImageWallpaper,
        override val framing: WallpaperFraming
    ) : WallpaperRenderable {
        override val width: Int get() = animatedImage.width
        override val height: Int get() = animatedImage.height
        override val brightnessFactor: Float get() = animatedImage.brightnessFactor
        override val isVideo: Boolean = false
        override fun recycle() = animatedImage.recycle()
    }

    private fun WallpaperRenderable.setPlaybackVisible(visible: Boolean) {
        when (this) {
            is VideoRenderable -> video.setPlaybackVisible(visible)
            is AnimatedImageRenderable -> animatedImage.setPlaybackVisible(visible)
            is PictureRenderable -> Unit
        }
    }

    private fun recyclePendingVideoWallpaper() {
        pendingVideoWallpaper?.recycle()
        pendingVideoWallpaper = null
    }

    private fun installWallpaper(
        wallpaper: WallpaperRenderable,
        skipCrossfade: Boolean,
        logMessage: String
    ) {
        if (skipCrossfade) {
            currentWallpaper?.recycle()
            nextWallpaper?.recycle()
            currentWallpaper = wallpaper
            nextWallpaper = null
            crossfadeProgress = 0f
            crossfadeStartTimeNanos = 0L
        } else {
            nextWallpaper?.recycle()
            nextWallpaper = wallpaper
            crossfadeProgress = 0f
            crossfadeStartTimeNanos = System.nanoTime()
        }

        Log.d(TAG, logMessage)
        callbacks.requestRender()
    }

    private fun installPendingVideoWallpaper(
        wallpaper: VideoRenderable,
        skipCrossfade: Boolean,
        logMessage: String
    ) {
        if (pendingVideoWallpaper !== wallpaper) return
        if (!wallpaper.video.hasFirstFrame()) return

        pendingVideoWallpaper = null
        installWallpaper(wallpaper, skipCrossfade, logMessage)
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        Log.d(TAG, "onSurfaceCreated")

        // Set clear color
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // Enable blending for crossfade
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Compile shader programs
        compileShaders()

        Log.d(TAG, "Surface created successfully")
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: ${width}x${height}")

        surfaceWidth = width
        surfaceHeight = height

        GLES20.glViewport(0, 0, width, height)

    }

    override fun onDrawFrame(gl: GL10) {
        // Clear screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Prevent division by zero in shaders if surface dimensions are invalid
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return
        }

        val current = currentWallpaper
        val next = nextWallpaper

        if (current == null && next == null) {
            // No wallpaper to display
            return
        }

        current?.let { updateVideoFrameIfNeeded(it) }
        next?.let { updateVideoFrameIfNeeded(it) }
        current?.let { updateAnimatedFrameIfNeeded(it) }
        next?.let { updateAnimatedFrameIfNeeded(it) }

        // Draw current picture
        current?.let { wallpaper ->
            val alpha = if (next != null) 1.0f - crossfadeProgress else 1.0f
            drawWallpaperWithEffects(wallpaper, alpha)
        }

        // Draw next picture (if crossfading)
        next?.let { wallpaper ->
            // Safety: If crossfade just started but timer wasn't set, set it now
            if (crossfadeStartTimeNanos == 0L) {
                crossfadeStartTimeNanos = System.nanoTime()
            }

            // Draw next picture with its own alpha
            drawWallpaperWithEffects(wallpaper, crossfadeProgress)

            // Update crossfade progress using time-based calculation
            // This ensures consistent animation duration regardless of refresh rate (60Hz, 90Hz, 120Hz, etc.)
            val currentTimeNanos = System.nanoTime()
            val elapsedMs = (currentTimeNanos - crossfadeStartTimeNanos) / 1_000_000f
            val durationMs = crossfadeDurationMs
            crossfadeProgress = if (durationMs <= 0) {
                1f
            } else {
                (elapsedMs / durationMs).coerceIn(0f, 1f)
            }

            if (crossfadeProgress >= 1.0f) {
                // Crossfade complete
                currentWallpaper?.recycle()
                currentWallpaper = wallpaper
                nextWallpaper = null
                crossfadeProgress = 0f
                crossfadeStartTimeNanos = 0L
                Log.d(TAG, "Crossfade complete")
            } else {
                // Continue animating
                callbacks.requestRender()
            }
        }
    }

    /**
     * Draw a picture with full effects pipeline.
     */
    private fun updateVideoFrameIfNeeded(wallpaper: WallpaperRenderable) {
        if (wallpaper is VideoRenderable) {
            wallpaper.video.updateFrame()
        }
    }

    private fun updateAnimatedFrameIfNeeded(wallpaper: WallpaperRenderable) {
        if (wallpaper is AnimatedImageRenderable) {
            wallpaper.animatedImage.updateFrame()
            scheduleAnimatedImageFrame()
        }
    }

    private fun scheduleAnimatedImageFrame() {
        if (!playbackVisible || animatedFrameRequestScheduled) return

        animatedFrameRequestScheduled = true
        frameHandler.postDelayed(
            {
                animatedFrameRequestScheduled = false
                if (playbackVisible) {
                    callbacks.requestRender()
                }
            },
            Constants.ANIMATED_IMAGE_FRAME_INTERVAL_MS
        )
    }

    private fun drawWallpaperWithEffects(wallpaper: WallpaperRenderable, alpha: Float) {
        // Calculate MVP matrix for Center Crop + Parallax
        calculateMvpMatrix(wallpaper, mvpMatrix)

        if (wallpaper is VideoRenderable) {
            drawVideoFrame(wallpaper.video, alpha)
            return
        }

        if (wallpaper is AnimatedImageRenderable) {
            drawAnimatedImageFrame(wallpaper.animatedImage, alpha)
            return
        }

        val picture = (wallpaper as PictureRenderable).picture
        drawImageFrame(picture, alpha)
    }

    private fun drawImageFrame(picture: GLPicture, alpha: Float) {
        GLES20.glUseProgram(effectsProgram)
        GLES20.glUniform1f(uAlphaHandle, alpha)
        picture.draw(effectsProgram, aPositionHandle, aTexCoordHandle, mvpMatrix, uMvpMatrixHandle)
    }

    private fun drawVideoFrame(video: GLVideoWallpaper, alpha: Float) {
        GLES20.glUseProgram(videoEffectsProgram)
        GLES20.glUniform1f(videoAlphaHandle, alpha)
        GLES20.glUniform1i(videoTextureHandle, 0)

        video.draw(
            videoEffectsProgram,
            videoPositionHandle,
            videoTexCoordHandle,
            mvpMatrix,
            videoMvpMatrixHandle
        )
    }

    private fun drawAnimatedImageFrame(animatedImage: GLAnimatedImageWallpaper, alpha: Float) {
        GLES20.glUseProgram(effectsProgram)
        GLES20.glUniform1f(uAlphaHandle, alpha)
        GLES20.glUniform1i(uTextureHandle, 0)

        animatedImage.draw(
            effectsProgram,
            aPositionHandle,
            aTexCoordHandle,
            mvpMatrix,
            uMvpMatrixHandle
        )
    }

    /**
     * Calculate MVP matrix for Center Crop scaling and Parallax.
     */
    private fun calculateMvpMatrix(picture: WallpaperRenderable, matrix: FloatArray) {
        val viewWidth = surfaceWidth.toFloat()
        val viewHeight = surfaceHeight.toFloat()
        val imageWidth = picture.width.toFloat()
        val imageHeight = picture.height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f || imageWidth == 0f || imageHeight == 0f) {
            Matrix.setIdentityM(matrix, 0)
            return
        }

        // 1. Calculate scale based on ScalingType
        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight

        val (finalScaleX, finalScaleY) = when (currentScalingType) {
            ScalingType.FILL -> {
                val scale = kotlin.math.max(scaleX, scaleY)
                Pair(scale, scale)
            }
            ScalingType.FIT -> {
                val scale = kotlin.math.min(scaleX, scaleY)
                Pair(scale, scale)
            }
            ScalingType.STRETCH -> {
                Pair(scaleX, scaleY)
            }
            ScalingType.NONE -> {
                Pair(1f, 1f)
            }
        }

        val framing = picture.framing.sanitized()
        var effectiveScaleX = finalScaleX * framing.scale
        var effectiveScaleY = finalScaleY * framing.scale

        val parallaxEnabled = currentEffects.enableParallax && currentEffects.parallaxIntensity > 0
        val parallaxIntensity = if (parallaxEnabled) currentEffects.parallaxIntensity / 100f else 0f

        // If parallax is enabled, ensure we have enough width to scroll (overscan).
        // If image fits perfectly, apply artificial zoom based on intensity.
        if (parallaxEnabled) {
            val currentWidth = imageWidth * effectiveScaleX
            // Target at least 20% overscan at max intensity
            val minExtraWidth = viewWidth * parallaxIntensity * 0.2f
            
            if ((currentWidth - viewWidth) < minExtraWidth) {
                // Zoom in to create scrollable area
                val targetWidth = viewWidth + minExtraWidth
                // Prevent division by zero
                if (currentWidth > 0) {
                    val zoomFactor = targetWidth / currentWidth
                    effectiveScaleX *= zoomFactor
                    effectiveScaleY *= zoomFactor
                }
            }
        }

        val scaledWidth = imageWidth * effectiveScaleX
        val scaledHeight = imageHeight * effectiveScaleY

        // 2. Calculate parallax offset
        // Available scroll range is the difference between scaled image width and screen width
        val extraWidth = kotlin.math.max(0f, scaledWidth - viewWidth)
        
        // Calculate offset based on scroll position (0.0 = left, 1.0 = right)
        // Center (0.5) is 0 offset
        // Reverting to: `maxParallaxOffset = extraWidth`.
        // And relying on the "Zoom" logic to create that width if needed.
        val maxParallaxOffset = extraWidth
        val parallaxOffset = maxParallaxOffset * (0.5f - normalOffsetX)
        val focusOffsetX = framing.offsetX * viewWidth
        val focusOffsetY = framing.offsetY * viewHeight
        
        // Verbose logging removed to avoid per-frame log spam

        // 3. Construct Matrix
        // We use an orthographic projection matching the screen dimensions
        // Left: -width/2, Right: width/2, Bottom: -height/2, Top: height/2
        // This makes 0,0 the center of the screen
        Matrix.orthoM(projectionMatrix, 0, -viewWidth / 2f, viewWidth / 2f, -viewHeight / 2f, viewHeight / 2f, -1f, 1f)

        // Set view matrix (camera) - identity is fine for 2D
        Matrix.setIdentityM(viewMatrix, 0)

        // Combine Projection * View
        Matrix.multiplyMM(matrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Apply Model transformations
        // Translate for parallax
        Matrix.translateM(matrix, 0, parallaxOffset + focusOffsetX, focusOffsetY, 0f)
        
        // Scale to match image size * crop scale
        // The quad is -1 to 1 (size 2), so we need to scale it to match image dimensions
        // Actually, we want to map the quad (-1..1) to the image size (-w/2..w/2)
        Matrix.scaleM(matrix, 0, scaledWidth / 2f, scaledHeight / 2f, 1f)
    }

    /**
     * Compile all shader programs and cache uniform locations.
     */
    private fun compileShaders() {
        // Image/video frame programs
        effectsProgram = GLUtil.createProgram(GLShaders.VERTEX_SHADER, GLShaders.EFFECTS_FRAGMENT_SHADER)
        videoEffectsProgram = GLUtil.createProgram(GLShaders.VERTEX_SHADER, GLShaders.VIDEO_EFFECTS_FRAGMENT_SHADER)

        // Cache uniform/attribute locations for image frame program
        aPositionHandle = GLES20.glGetAttribLocation(effectsProgram, "a_position")
        aTexCoordHandle = GLES20.glGetAttribLocation(effectsProgram, "a_texCoord")
        uTextureHandle = GLES20.glGetUniformLocation(effectsProgram, "u_texture")
        uMvpMatrixHandle = GLES20.glGetUniformLocation(effectsProgram, "u_mvpMatrix")
        uAlphaHandle = GLES20.glGetUniformLocation(effectsProgram, "u_alpha")

        videoPositionHandle = GLES20.glGetAttribLocation(videoEffectsProgram, "a_position")
        videoTexCoordHandle = GLES20.glGetAttribLocation(videoEffectsProgram, "a_texCoord")
        videoTextureHandle = GLES20.glGetUniformLocation(videoEffectsProgram, "u_texture")
        videoMvpMatrixHandle = GLES20.glGetUniformLocation(videoEffectsProgram, "u_mvpMatrix")
        videoAlphaHandle = GLES20.glGetUniformLocation(videoEffectsProgram, "u_alpha")

        Log.d(TAG, "Shaders compiled and uniforms cached")
    }

    /**
     * Queue a new wallpaper for loading and display.
     * This method can be called from any thread.
     * Will wait for valid surface dimensions before loading.
     *
     * @param imageLoader Image loader to use
     * @param skipCrossfade If true, instantly swap wallpaper without crossfade animation
     */
    fun queueWallpaper(imageLoader: ImageLoader, skipCrossfade: Boolean = false) {
        // Cancel any existing loading job to prevent race conditions
        currentLoadJob?.cancel()

        if (imageLoader is VideoUriImageLoader) {
            callbacks.queueEventOnGlThread {
                uploadVideo(imageLoader, skipCrossfade)
            }
            return
        }

        if (imageLoader is AnimatedImageUriLoader) {
            currentLoadJob = loadingScope.launch {
                try {
                    val (width, height) = waitForSurfaceDimensions()
                    if (!isActive) return@launch
                    callbacks.queueEventOnGlThread {
                        uploadAnimatedImage(imageLoader, width, height, skipCrossfade)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading animated image wallpaper", e)
                }
            }
            return
        }

        currentLoadJob = loadingScope.launch {
            try {
                // Wait for valid surface dimensions (onSurfaceChanged may not have been called yet)
                val (width, height) = waitForSurfaceDimensions()

                Log.d(TAG, "Loading wallpaper... (skipCrossfade=$skipCrossfade)")
                val bitmap = imageLoader.load(width, height)

                if (bitmap != null) {
                    Log.d(TAG, "Wallpaper loaded: ${bitmap.width}x${bitmap.height}")

                    // Check for cancellation before uploading
                    if (!isActive) {
                        Log.d(TAG, "Loading cancelled, recycling bitmap")
                        bitmap.recycle()
                        return@launch
                    }

                    // Upload to GPU on GL thread
                    val framing = (imageLoader as? ContentUriImageLoader)?.framing ?: WallpaperFraming.Default
                    callbacks.queueEventOnGlThread {
                        uploadBitmap(bitmap, 1.0f, framing, skipCrossfade)
                    }
                } else {
                    Log.w(TAG, "Failed to load wallpaper (null bitmap)")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Wallpaper loading cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wallpaper", e)
            }
        }
    }

    /**
     * Wait for valid surface dimensions.
     * Polls until surfaceWidth and surfaceHeight are non-zero or timeout.
     * Falls back to device display metrics if timeout occurs.
     *
     * @return Pair of (width, height)
     */
    private suspend fun waitForSurfaceDimensions(): Pair<Int, Int> {
        val maxWaitMs = Constants.SURFACE_WAIT_TIMEOUT_MS
        val pollIntervalMs = Constants.SURFACE_POLL_INTERVAL_MS
        var waitedMs = 0L

        while (surfaceWidth <= 0 || surfaceHeight <= 0) {
            if (waitedMs >= maxWaitMs) {
                // Use actual device display metrics as fallback instead of hardcoded values
                val displayMetrics = context.resources.displayMetrics
                val fallbackWidth = displayMetrics.widthPixels
                val fallbackHeight = displayMetrics.heightPixels
                Log.w(TAG, "Timeout waiting for surface dimensions, using display metrics: ${fallbackWidth}x${fallbackHeight}")
                return Pair(fallbackWidth, fallbackHeight)
            }
            kotlinx.coroutines.delay(pollIntervalMs)
            waitedMs += pollIntervalMs
        }

        return Pair(surfaceWidth, surfaceHeight)
    }

    /**
     * Upload a bitmap to GPU and start crossfade (or instant swap).
     * Must be called on GL thread.
     *
     * @param bitmap The bitmap to upload
     * @param brightnessFactor The brightness multiplier for this bitmap
     * @param skipCrossfade If true, instantly replace current wallpaper without animation
     */
    private fun uploadBitmap(
        bitmap: Bitmap,
        brightnessFactor: Float,
        framing: WallpaperFraming,
        skipCrossfade: Boolean = false
    ) {
        // Validate bitmap before processing
        if (bitmap.isRecycled) {
            Log.e(TAG, "Cannot upload recycled bitmap")
            return
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            Log.e(TAG, "Cannot upload bitmap with invalid dimensions: ${bitmap.width}x${bitmap.height}")
            bitmap.recycle()
            return
        }
        
        try {
            recyclePendingVideoWallpaper()
            val picture = GLPicture(bitmap, brightnessFactor)
            val wallpaper = PictureRenderable(picture, framing.sanitized())

            // Recycle bitmap (no longer needed after GPU upload)
            bitmap.recycle()

            if (skipCrossfade) {
                // Instant swap - no animation (used for screen-off changes)
                currentWallpaper?.recycle()
                nextWallpaper?.recycle() // Recycle any in-flight crossfade target
                currentWallpaper = wallpaper
                nextWallpaper = null
                crossfadeProgress = 0f
                crossfadeStartTimeNanos = 0L
                Log.d(TAG, "Wallpaper instantly swapped (no crossfade): $wallpaper")
            } else {
                // Start crossfade with time-based animation
                nextWallpaper?.recycle() // Recycle interrupted crossfade target if any
                nextWallpaper = wallpaper
                crossfadeProgress = 0f
                crossfadeStartTimeNanos = System.nanoTime()
                Log.d(TAG, "Wallpaper uploaded to GPU with crossfade: $wallpaper")
            }

            callbacks.requestRender()
        } catch (e: IllegalArgumentException) {
            // GLPicture validation failed (recycled bitmap or invalid dimensions)
            Log.e(TAG, "Failed to create GLPicture: ${e.message}")
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload bitmap to GPU", e)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * Create a looping video wallpaper on the GL thread.
     */
    private fun uploadVideo(videoLoader: VideoUriImageLoader, skipCrossfade: Boolean = false) {
        try {
            recyclePendingVideoWallpaper()

            val video = GLVideoWallpaper(
                context = videoLoader.context,
                uri = videoLoader.uri,
                requestRender = callbacks::requestRender
            )
            val wallpaper = VideoRenderable(video, videoLoader.framing.sanitized())
            pendingVideoWallpaper = wallpaper
            currentScalingType = videoLoader.scalingType

            val installWhenReady = {
                callbacks.queueEventOnGlThread {
                    installPendingVideoWallpaper(
                        wallpaper = wallpaper,
                        skipCrossfade = skipCrossfade,
                        logMessage = if (skipCrossfade) {
                            "Video wallpaper installed after first frame without crossfade: ${videoLoader.uri}"
                        } else {
                            "Video wallpaper queued with crossfade after first frame: ${videoLoader.uri}"
                        }
                    )
                }
            }

            video.setOnFirstFrameAvailableListener(installWhenReady)
            video.setOnPreparedAvailableListener {
                Log.d(TAG, "Video wallpaper prepared, waiting for first frame: ${videoLoader.uri}")
            }
            video.setOnPlaybackErrorListener {
                callbacks.queueEventOnGlThread {
                    if (pendingVideoWallpaper === wallpaper) {
                        Log.w(TAG, "Video playback failed before first frame; keeping current wallpaper: ${videoLoader.uri}")
                        recyclePendingVideoWallpaper()
                        callbacks.requestRender()
                    }
                }
            }
            video.setPlaybackVisible(playbackVisible)

            if (video.hasFirstFrame()) {
                installPendingVideoWallpaper(
                    wallpaper = wallpaper,
                    skipCrossfade = skipCrossfade,
                    logMessage = "Video wallpaper installed immediately with ready frame: ${videoLoader.uri}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload video wallpaper", e)
        }
    }

    private fun uploadAnimatedImage(
        imageLoader: AnimatedImageUriLoader,
        width: Int,
        height: Int,
        skipCrossfade: Boolean = false
    ) {
        try {
            recyclePendingVideoWallpaper()
            val animatedImage = GLAnimatedImageWallpaper(
                context = imageLoader.context,
                uri = imageLoader.uri,
                targetWidth = width,
                targetHeight = height,
                scalingType = imageLoader.scalingType,
                framing = imageLoader.framing
            )
            animatedImage.setPlaybackVisible(playbackVisible)
            val wallpaper = AnimatedImageRenderable(animatedImage, WallpaperFraming.Default)
            currentScalingType = imageLoader.scalingType

            if (skipCrossfade) {
                currentWallpaper?.recycle()
                nextWallpaper?.recycle()
                currentWallpaper = wallpaper
                nextWallpaper = null
                crossfadeProgress = 0f
                crossfadeStartTimeNanos = 0L
                Log.d(TAG, "Animated image wallpaper instantly swapped: ${imageLoader.uri}")
            } else {
                nextWallpaper?.recycle()
                nextWallpaper = wallpaper
                crossfadeProgress = 0f
                crossfadeStartTimeNanos = System.nanoTime()
                Log.d(TAG, "Animated image wallpaper queued with crossfade: ${imageLoader.uri}")
            }

            callbacks.requestRender()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload animated image wallpaper", e)
        }
    }

    /**
     * Update effects settings.
     * Can be called from any thread.
     */
    fun updateEffects(effects: WallpaperEffects) {
        this.currentEffects = effects
        this.crossfadeDurationMs = effects.crossfadeDurationMs.coerceIn(
            Constants.MIN_CROSSFADE_DURATION_MS,
            Constants.MAX_CROSSFADE_DURATION_MS
        )
        Log.d(TAG, "Effects updated: parallax=${effects.enableParallax}/${effects.parallaxIntensity}, crossfade=${crossfadeDurationMs}ms")
        callbacks.requestRender()
    }

    /**
     * Set parallax scroll offset.
     * Can be called from any thread.
     *
     * @param offset Normalized offset (0.0 = left, 1.0 = right)
     */
    fun setNormalOffsetX(offset: Float) {
        if (normalOffsetX != offset) {
            normalOffsetX = offset
            callbacks.requestRender()
        }
    }

    /**
     * Update scaling type.
     * Can be called from any thread.
     */
    fun updateScalingType(scalingType: ScalingType) {
        if (currentScalingType != scalingType) {
            currentScalingType = scalingType
            Log.d(TAG, "Scaling type updated: $scalingType")
            callbacks.requestRender()
        }
    }

    fun setPlaybackVisible(visible: Boolean) {
        playbackVisible = visible
        callbacks.queueEventOnGlThread {
            currentWallpaper?.setPlaybackVisible(visible)
            nextWallpaper?.setPlaybackVisible(visible)
            pendingVideoWallpaper?.setPlaybackVisible(visible)
            if (visible) {
                scheduleAnimatedImageFrame()
                callbacks.requestRender()
            }
        }
    }

    /**
     * Cleanup resources.
     * Must be called on GL thread.
     */
    fun destroy() {
        loadingScope.cancel()
        currentLoadJob?.cancel()
        frameHandler.removeCallbacksAndMessages(null)

        currentWallpaper?.recycle()
        currentWallpaper = null

        nextWallpaper?.recycle()
        nextWallpaper = null

        recyclePendingVideoWallpaper()

        GLUtil.deleteProgram(effectsProgram)
        GLUtil.deleteProgram(videoEffectsProgram)

        Log.d(TAG, "Renderer destroyed")
    }
}
