package com.anthonyla.paperize.service.livewallpaper.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.ScalingType
import com.anthonyla.paperize.domain.model.WallpaperFraming
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Renders GIF and animated WebP drawables into a 2D texture for the live wallpaper pipeline.
 */
class GLAnimatedImageWallpaper(
    context: Context,
    uri: Uri,
    private val targetWidth: Int,
    private val targetHeight: Int,
    scalingType: ScalingType,
    framing: WallpaperFraming = WallpaperFraming.Default
) {
    companion object {
        private const val TAG = "GLAnimatedImage"
    }

    val width: Int = targetWidth.coerceAtLeast(1)
    val height: Int = targetHeight.coerceAtLeast(1)
    val brightnessFactor: Float = 1.0f
    val textureId: Int

    private val drawable = ImageDecoder.decodeDrawable(
        ImageDecoder.createSource(context.contentResolver, uri)
    ) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    private val isAnimated = drawable is AnimatedImageDrawable
    @Volatile
    private var isReleased = false
    private var lastFrameUpdateMs = 0L
    private val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val frameCanvas = Canvas(frameBitmap)
    private val drawableBounds = calculateDrawableBounds(
        drawable.intrinsicWidth.coerceAtLeast(1),
        drawable.intrinsicHeight.coerceAtLeast(1),
        width,
        height,
        scalingType,
        framing.sanitized()
    )

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
        drawable.bounds = drawableBounds
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        textureId = textureIds[0]
        if (textureId == 0) {
            throw RuntimeException("Failed to generate animated image texture ID")
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        drawDrawableToBitmap()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frameBitmap, 0)
        Log.d(TAG, "Animated image texture created: ${width}x${height}, animated=$isAnimated")
    }

    fun updateFrame(): Boolean {
        if (!isAnimated || isReleased) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFrameUpdateMs < Constants.ANIMATED_IMAGE_FRAME_INTERVAL_MS) {
            return false
        }
        lastFrameUpdateMs = now

        drawDrawableToBitmap()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameBitmap)
        return true
    }

    fun setPlaybackVisible(visible: Boolean) {
        if (isReleased || drawable !is AnimatedImageDrawable) return

        if (visible) {
            drawable.start()
        } else {
            drawable.stop()
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)
    }

    fun recycle() {
        isReleased = true
        if (drawable is AnimatedImageDrawable) {
            drawable.stop()
        }
        frameBitmap.recycle()
        GLUtil.deleteTexture(textureId)
    }

    private fun drawDrawableToBitmap() {
        frameCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawable.draw(frameCanvas)
    }

    private fun calculateDrawableBounds(
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        scalingType: ScalingType,
        framing: WallpaperFraming
    ): Rect {
        val scale = when (scalingType) {
            ScalingType.FILL -> maxOf(
                canvasWidth.toFloat() / sourceWidth,
                canvasHeight.toFloat() / sourceHeight
            )
            ScalingType.FIT -> minOf(
                canvasWidth.toFloat() / sourceWidth,
                canvasHeight.toFloat() / sourceHeight
            )
            ScalingType.STRETCH -> {
                val extraX = (canvasWidth * (framing.scale - 1f) / 2f).roundToInt()
                val extraY = (canvasHeight * (framing.scale - 1f) / 2f).roundToInt()
                val shiftX = (framing.offsetX * canvasWidth).roundToInt()
                val shiftY = (framing.offsetY * canvasHeight).roundToInt()
                return Rect(
                    -extraX + shiftX,
                    -extraY + shiftY,
                    canvasWidth + extraX + shiftX,
                    canvasHeight + extraY + shiftY
                )
            }
            ScalingType.NONE -> 1f
        } * framing.scale

        val drawWidth = (sourceWidth * scale).roundToInt()
        val drawHeight = (sourceHeight * scale).roundToInt()
        val left = ((canvasWidth - drawWidth) / 2f + framing.offsetX * canvasWidth).roundToInt()
        val top = ((canvasHeight - drawHeight) / 2f + framing.offsetY * canvasHeight).roundToInt()
        return Rect(left, top, left + drawWidth, top + drawHeight)
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
