package com.anthonyla.paperize.service.livewallpaper.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Plays a video into an external OES texture that can be rendered by OpenGL.
 */
class GLVideoWallpaper(
    context: Context,
    uri: Uri,
    private val requestRender: () -> Unit
) {
    companion object {
        private const val TAG = "GLVideoWallpaper"
    }

    val textureId: Int
    private val surfaceTexture: SurfaceTexture
    private val surface: Surface
    private val mediaPlayer: MediaPlayer

    @Volatile
    private var frameAvailable = false
    @Volatile
    private var isPrepared = false
    @Volatile
    private var isReleased = false
    @Volatile
    private var playWhenReady = true
    @Volatile
    private var firstFrameDelivered = false

    private var onPreparedAvailable: (() -> Unit)? = null
    private var onFirstFrameAvailable: (() -> Unit)? = null
    private var onPlaybackError: (() -> Unit)? = null

    var width: Int = 1
        private set

    var height: Int = 1
        private set

    val brightnessFactor: Float = 1.0f

    private val vertexBuffer: FloatBuffer = createFloatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
    )

    private val texCoordBuffer: FloatBuffer = createFloatBuffer(
        floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
    )

    init {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        textureId = textureIds[0]
        if (textureId == 0) {
            throw RuntimeException("Failed to generate video texture ID")
        }

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        surfaceTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener {
                frameAvailable = true
                if (!firstFrameDelivered) {
                    firstFrameDelivered = true
                    onFirstFrameAvailable?.invoke()
                }
                requestRender()
            }
        }
        surface = Surface(surfaceTexture)

        mediaPlayer = MediaPlayer().apply {
            setSurface(surface)
            disableAudio()
            isLooping = true
            setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                if (videoWidth > 0 && videoHeight > 0) {
                    width = videoWidth
                    height = videoHeight
                    requestRender()
                }
            }
            setOnPreparedListener { player ->
                isPrepared = true
                if (player.videoWidth > 0 && player.videoHeight > 0) {
                    width = player.videoWidth
                    height = player.videoHeight
                }
                player.disableAudio()
                if (playWhenReady && !isReleased) {
                    player.start()
                }
                onPreparedAvailable?.invoke()
                requestRender()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video playback error for $uri: what=$what extra=$extra")
                onPlaybackError?.invoke()
                true
            }
            setDataSource(context, uri)
            prepareAsync()
        }
    }

    fun setOnPreparedAvailableListener(listener: () -> Unit) {
        onPreparedAvailable = listener
        if (isPrepared && !isReleased) {
            listener()
        }
    }

    fun setOnFirstFrameAvailableListener(listener: () -> Unit) {
        onFirstFrameAvailable = listener
        if (firstFrameDelivered && !isReleased) {
            listener()
        }
    }

    fun setOnPlaybackErrorListener(listener: () -> Unit) {
        onPlaybackError = listener
    }

    fun hasFirstFrame(): Boolean = firstFrameDelivered

    fun updateFrame() {
        if (frameAvailable) {
            try {
                surfaceTexture.updateTexImage()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update video texture", e)
            } finally {
                frameAvailable = false
            }
        }
    }

    fun setPlaybackVisible(visible: Boolean) {
        playWhenReady = visible
        if (isReleased || !isPrepared) return

        runCatching {
            if (visible) {
                mediaPlayer.disableAudio()
                if (!mediaPlayer.isPlaying) {
                    mediaPlayer.start()
                    requestRender()
                }
            } else if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to update video playback visibility", e)
        }
    }

    fun draw(
        program: Int,
        aPositionHandle: Int,
        aTexCoordHandle: Int,
        mvpMatrix: FloatArray,
        uMvpMatrixHandle: Int
    ) {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)
        GLES20.glUniformMatrix4fv(uMvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    }

    fun recycle() {
        isReleased = true
        try {
            mediaPlayer.stop()
        } catch (_: Exception) {
        }
        mediaPlayer.reset()
        mediaPlayer.release()
        surface.release()
        surfaceTexture.release()
        GLUtil.deleteTexture(textureId)
    }

    private fun MediaPlayer.disableAudio() {
        setVolume(0f, 0f)
        runCatching {
            trackInfo.forEachIndexed { index, trackInfo ->
                if (trackInfo.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    deselectTrack(index)
                }
            }
        }
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }
    }
}
