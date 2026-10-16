package earth.dipstick.app.gl

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.opengl.GLES20
import android.util.Log
import earth.dipstick.app.ImageAdjustments
import earth.dipstick.app.net.MjpegServer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders the processed frame into an offscreen framebuffer, reads it back and JPEG-encodes
 * it for [MjpegServer].
 *
 * Starts at the camera's full resolution and steps down a ladder when frames are not
 * actually reaching viewers, then retries the next rung up after a quiet period. One metric
 * - frames genuinely delivered per second - covers both causes of trouble, since a link too
 * slow to carry the frames and a GPU too slow to produce them both show up as under-delivery.
 *
 * Only [glReadPixels] runs on the GL thread; the JPEG compression is handed to a worker, so
 * a 720p stream does not stall the preview or the recorder.
 */
internal class StreamCapture(
    private val server: MjpegServer,
    private val targetFps: Int = 12,
    quality: Int = DEFAULT_QUALITY,
) {
    /**
     * JPEG quality for the outgoing stream, 10..95. Lower costs detail but shrinks each
     * frame, which also makes the resolution ladder less likely to step down on a weak link.
     */
    @Volatile
    var jpegQuality: Int = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        set(value) {
            field = value.coerceIn(MIN_QUALITY, MAX_QUALITY)
        }

    private var fbo = 0
    private var texture = 0
    private var width = 0
    private var height = 0

    private var fullWidth = 0
    private var fullHeight = 0
    private var levelIndex = 0

    private var pixelBuffer: ByteBuffer? = null
    private var bitmap: Bitmap? = null

    private val encoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dipstick-jpeg").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private var lastFrameNanos = 0L
    private var lastEvalNanos = 0L
    private var steadySinceNanos = 0L

    /** The resolution currently being streamed, for logging and UI. */
    var currentWidth = 0
        private set
    var currentHeight = 0
        private set

    /** Sets the source size. The ladder is applied on top of this. */
    fun configure(cameraWidth: Int, cameraHeight: Int) {
        if (cameraWidth <= 0 || cameraHeight <= 0) return
        if (cameraWidth != fullWidth || cameraHeight != fullHeight) {
            // New geometry (camera change or rotation): start optimistic again.
            fullWidth = cameraWidth
            fullHeight = cameraHeight
            levelIndex = 0
            steadySinceNanos = System.nanoTime()
        }
        applyLevel()
    }

    private fun applyLevel() {
        val scale = LEVELS[levelIndex.coerceIn(LEVELS.indices)]
        var w = (fullWidth * scale).toInt()
        var h = (fullHeight * scale).toInt()
        // Keep it sane and even; JPEG does not care but the ladder should not go silly-small.
        if (w < MIN_WIDTH) {
            val k = MIN_WIDTH.toFloat() / w
            w = (w * k).toInt(); h = (h * k).toInt()
        }
        w -= w % 2
        h -= h % 2
        if (w == width && h == height && fbo != 0) return

        releaseGlResources()
        width = w
        height = h
        currentWidth = w
        currentHeight = h

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]

        val texes = IntArray(1)
        GLES20.glGenTextures(1, texes, 0)
        texture = texes[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "framebuffer incomplete: 0x${Integer.toHexString(status)}")
            releaseGlResources()
            return
        }

        pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Log.i(TAG, "stream level $levelIndex -> ${width}x$height @${targetFps}fps")
    }

    /** Call on the GL thread after drawing the preview. */
    fun maybeCapture(
        shader: FrameShader,
        textureId: Int,
        texMatrix: FloatArray,
        mvpMatrix: FloatArray,
        adjustments: ImageAdjustments,
    ) {
        if (fbo == 0 || !server.isRunning) return

        val now = System.nanoTime()
        evaluateBandwidth(now)

        // Slow trickle with no viewers keeps /snapshot.jpg fresh; full rate when watched.
        val fps = if (server.clientCount > 0) targetFps else IDLE_FPS
        if (now - lastFrameNanos < 1_000_000_000L / fps) return

        // A busy encoder means we are already the bottleneck; skipping is the right answer
        // and the resulting under-delivery is what triggers a step down.
        if (!inFlight.compareAndSet(false, true)) return
        lastFrameNanos = now

        val buffer = pixelBuffer
        val bmp = bitmap
        if (buffer == null || bmp == null) {
            inFlight.set(false)
            return
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
        // mvpMatrix already carries the Y flip that makes glReadPixels come out upright.
        shader.draw(textureId, texMatrix, mvpMatrix, adjustments)

        buffer.rewind()
        GLES20.glReadPixels(
            0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        encoder.execute {
            try {
                buffer.rewind()
                bmp.copyPixelsFromBuffer(buffer)
                val out = ByteArrayOutputStream(bmp.width * bmp.height / 8)
                bmp.compress(CompressFormat.JPEG, jpegQuality, out)
                server.offerFrame(out.toByteArray())
            } catch (e: Exception) {
                Log.e(TAG, "jpeg encode failed", e)
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Steps the ladder down when viewers are not keeping up, and retries a rung up after
     * [UPGRADE_AFTER_NANOS] of clean delivery.
     */
    private fun evaluateBandwidth(now: Long) {
        if (lastEvalNanos == 0L) {
            lastEvalNanos = now
            steadySinceNanos = now
            return
        }
        val elapsed = now - lastEvalNanos
        if (elapsed < EVAL_INTERVAL_NANOS) return
        lastEvalNanos = now

        val stats = server.sampleDelivery()
        if (stats.clients == 0) {
            // Nobody watching tells us nothing about the link; do not punish the ladder.
            steadySinceNanos = now
            return
        }

        val seconds = elapsed / 1_000_000_000.0
        val deliveredFps = stats.frames / stats.clients / seconds
        val stallFraction = stats.stallNanos.toDouble() / stats.clients / elapsed

        val struggling = deliveredFps < targetFps * DOWN_FPS_RATIO || stallFraction > DOWN_STALL
        val healthy = deliveredFps >= targetFps * UP_FPS_RATIO && stallFraction < UP_STALL

        when {
            struggling && levelIndex < LEVELS.lastIndex -> {
                levelIndex++
                Log.i(
                    TAG, "down to level $levelIndex " +
                        "(%.1f fps delivered, %.0f%% stalled)".format(deliveredFps, stallFraction * 100)
                )
                applyLevel()
                steadySinceNanos = now
            }

            healthy -> {
                if (levelIndex > 0 && now - steadySinceNanos >= UPGRADE_AFTER_NANOS) {
                    levelIndex--
                    Log.i(TAG, "up to level $levelIndex (%.1f fps delivered)".format(deliveredFps))
                    applyLevel()
                    steadySinceNanos = now
                }
            }

            else -> steadySinceNanos = now   // neither struggling nor clean: hold position
        }
    }

    private fun releaseGlResources() {
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
        // The worker may still hold the old bitmap; drop our reference and let it finish.
        bitmap = null
        pixelBuffer = null
        width = 0
        height = 0
    }

    fun release() {
        releaseGlResources()
        encoder.shutdown()
    }

    internal companion object {
        const val TAG = "DipstickStream"
        const val IDLE_FPS = 2
        const val DEFAULT_QUALITY = 70
        const val MIN_QUALITY = 10
        const val MAX_QUALITY = 95
        const val MIN_WIDTH = 320

        /** Resolution ladder as a fraction of the camera's native size. */
        val LEVELS = floatArrayOf(1.0f, 0.75f, 0.5f, 0.375f, 0.25f)

        const val EVAL_INTERVAL_NANOS = 3_000_000_000L
        const val UPGRADE_AFTER_NANOS = 20_000_000_000L

        const val DOWN_FPS_RATIO = 0.6
        const val DOWN_STALL = 0.5
        const val UP_FPS_RATIO = 0.85
        const val UP_STALL = 0.25
    }
}
