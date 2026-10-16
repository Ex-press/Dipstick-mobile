package earth.dipstick.app.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import earth.dipstick.app.ImageAdjustments
import earth.dipstick.app.net.MjpegServer
import earth.dipstick.app.rec.VideoRecorder
import earth.dipstick.app.sensor.MotionStabilizer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig as EGLConfig10
import javax.microedition.khronos.egl.EGLDisplay as EGLDisplay10
import javax.microedition.khronos.opengles.GL10

/**
 * Shows the UVC camera and owns the whole render path.
 *
 * Frames arrive from the camera into a [SurfaceTexture], are drawn through [FrameShader]
 * with the current [ImageAdjustments], and - while recording - are drawn a second time into
 * the encoder's surface on the same context. Both destinations get identical pixels.
 */
class CameraGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    interface Callback {
        /** The camera can now be pointed at this surface. Called on the main thread. */
        fun onPreviewSurfaceAvailable(surface: Surface)

        /** The GL context went away; the camera must stop rendering into the old surface. */
        fun onPreviewSurfaceDestroyed()

        /** Recording finished. [uri] is a MediaStore video, or null if it failed. */
        fun onRecordingFinished(uri: Uri?)
    }

    var callback: Callback? = null

    /** Applied to preview and recording alike, on the next frame. */
    @Volatile
    var adjustments: ImageAdjustments = ImageAdjustments.NEUTRAL

    /**
     * Supplies the per-frame shake correction. Sampled on the GL thread every frame, so the
     * correction tracks the newest gyro reading rather than the one that existed when the
     * frame was captured.
     */
    @Volatile
    var stabilizer: MotionStabilizer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer = FrameRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(RecordableConfigChooser())
        setRenderer(renderer)
        // The camera, not a clock, decides when there is something new to draw.
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    /**
     * Tells the renderer the camera's native resolution so the SurfaceTexture allocates
     * buffers at the right size and the preview can be letterboxed correctly.
     */
    fun setCameraSize(width: Int, height: Int) {
        queueEvent {
            renderer.setCameraSize(width, height)
        }
    }

    val isRecording: Boolean get() = renderer.isRecording

    /**
     * Re-offers the existing preview surface to the callback.
     *
     * Needed because [preserveEGLContextOnPause] keeps the GL context across a pause, so
     * onSurfaceCreated does not run again on resume and the surface would otherwise never be
     * handed back. Replugging the camera bounces the activity through pause (the USB attach
     * intent), which is exactly when that bites.
     */
    fun redeliverPreviewSurface() {
        queueEvent {
            val sf = renderer.currentSurface ?: return@queueEvent
            mainHandler.post { callback?.onPreviewSurfaceAvailable(sf) }
        }
    }

    /**
     * Rotates the image by [degrees] (0/90/180/270).
     *
     * Done here rather than via the UVC library's own rotation because the library keeps the
     * output buffer at the camera's native size, which squashes a 90-degree-rotated frame
     * into a landscape buffer. Rotating in the shader lets the preview letterbox correctly
     * and the recorder/stream allocate swapped dimensions.
     */
    fun setRotation(degrees: Int) {
        queueEvent { renderer.setRotation(degrees) }
    }

    /** Frames per second the stream aims for; clamped to what the camera can deliver. */
    @Volatile
    var streamTargetFps: Int = 12

    /** JPEG quality of the outgoing stream, 10..95. Applies live. */
    var streamJpegQuality: Int = StreamCapture.DEFAULT_QUALITY
        set(value) {
            field = value.coerceIn(StreamCapture.MIN_QUALITY, StreamCapture.MAX_QUALITY)
            queueEvent { renderer.setStreamQuality(field) }
        }

    /** Begins publishing processed frames to [server]. Cheap while no viewer is connected. */
    fun startStreaming(server: MjpegServer) {
        queueEvent { renderer.startStreaming(server) }
    }

    fun stopStreaming() {
        queueEvent { renderer.stopStreaming() }
    }

    fun startRecording(frameRate: Int, withAudio: Boolean) {
        queueEvent { renderer.startRecording(frameRate, withAudio) }
    }

    /** Bytes committed to the current recording, for the on-screen size readout. */
    val recordedBytes: Long get() = renderer.recordedBytes

    /** True when the in-progress recording actually captured a microphone track. */
    val recordingHasAudio: Boolean get() = renderer.recordingHasAudio

    fun stopRecording() {
        queueEvent {
            val uri = renderer.stopRecording()
            mainHandler.post { callback?.onRecordingFinished(uri) }
        }
    }

    private companion object { const val TAG = "DipstickGL" }

    private inner class FrameRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {

        private var textureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null

        /** Read from the main thread via [redeliverPreviewSurface]. */
        val currentSurface: Surface? get() = surface
        private var shader: FrameShader? = null

        private val texMatrix = FloatArray(16)

        // Destination-specific bases, rebuilt only when geometry changes.
        private val letterboxBase = FloatArray(16)  // fits the image into the view
        private val identityBase = FloatArray(16)   // encoder: surface is already sized
        private val flipBase = FloatArray(16)       // stream: Y flip for glReadPixels

        // Final per-frame transforms: base · stabilise · rotate.
        private val mvpMatrix = FloatArray(16)
        private val encodeMatrix = FloatArray(16)
        private val streamMatrix = FloatArray(16)

        private val stabOffsets = FloatArray(4)   // dx, dy, rollDeg, cropScale

        private var rotationDegrees = 0

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglConfig: EGLConfig? = null

        private var viewWidth = 0
        private var viewHeight = 0
        private var cameraWidth = 0
        private var cameraHeight = 0

        // Read from the main thread for the size readout, written on the GL thread.
        @Volatile private var recorder: VideoRecorder? = null
        private var streamCapture: StreamCapture? = null

        val isRecording: Boolean get() = recorder?.isRecording == true
        val recordedBytes: Long get() = recorder?.bytesWritten ?: 0L
        val recordingHasAudio: Boolean get() = recorder?.hasAudio == true

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            // GLSurfaceView drives EGL through the old EGL10 API, but MediaCodec's input
            // surface needs EGL14. Recover the EGL14 handles for the context we are already
            // running on, matching the config by its EGL_CONFIG_ID.
            eglDisplay = EGL14.eglGetCurrentDisplay()
            eglContext = EGL14.eglGetCurrentContext()
            eglConfig = findCurrentEgl14Config()

            textureId = FrameShader.createOesTexture()
            shader = FrameShader()

            val st = SurfaceTexture(textureId)
            if (cameraWidth > 0 && cameraHeight > 0) {
                st.setDefaultBufferSize(cameraWidth, cameraHeight)
            }
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st

            val sf = Surface(st)
            surface = sf
            Log.i(TAG, "onSurfaceCreated: tex=$textureId surface=$sf valid=${sf.isValid} " +
                    "eglConfig=${eglConfig != null} camera=${cameraWidth}x$cameraHeight")
            mainHandler.post { callback?.onPreviewSurfaceAvailable(sf) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            updateMatrices()
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            val program = shader ?: return

            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // Sample the gyro correction once and use it for every destination, so preview,
            // recording and stream stay identical frame for frame.
            val stab = stabilizer
            if (stab != null && stab.enabled) {
                stab.readInto(stabOffsets)
            } else {
                stabOffsets[0] = 0f; stabOffsets[1] = 0f
                stabOffsets[2] = 0f; stabOffsets[3] = 1f
            }
            val crop = stabOffsets[3]
            compose(letterboxBase, mvpMatrix, stabOffsets[0], stabOffsets[1], stabOffsets[2], crop)
            compose(identityBase, encodeMatrix, stabOffsets[0], stabOffsets[1], stabOffsets[2], crop)
            compose(flipBase, streamMatrix, stabOffsets[0], stabOffsets[1], stabOffsets[2], crop)
            if (drawCount < 3) Log.i(TAG, "onDrawFrame #$drawCount view=${viewWidth}x$viewHeight")
            drawCount++

            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            program.draw(textureId, texMatrix, mvpMatrix, adjustments)

            recorder?.let { active ->
                if (active.isRecording) {
                    active.encodeFrame(
                        program, textureId, texMatrix, encodeMatrix, adjustments
                    )
                }
            }

            streamCapture?.maybeCapture(program, textureId, texMatrix, streamMatrix, adjustments)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            if (frameCount < 3) Log.i(TAG, "onFrameAvailable #$frameCount")
            frameCount++
            requestRender()
        }

        private var frameCount = 0
        private var drawCount = 0

        fun setCameraSize(width: Int, height: Int) {
            Log.i(TAG, "setCameraSize ${width}x$height st=${surfaceTexture != null}")
            cameraWidth = width
            cameraHeight = height
            surfaceTexture?.setDefaultBufferSize(width, height)
            streamCapture?.configure(effectiveWidth, effectiveHeight)
            updateMatrices()
        }

        fun setRotation(degrees: Int) {
            rotationDegrees = ((degrees % 360) + 360) % 360
            Log.i(TAG, "setRotation $rotationDegrees")
            streamCapture?.configure(effectiveWidth, effectiveHeight)
            updateMatrices()
        }

        /** True when the image is turned on its side, so width and height swap. */
        private val isQuarterTurned: Boolean
            get() = rotationDegrees == 90 || rotationDegrees == 270

        private val effectiveWidth: Int
            get() = if (isQuarterTurned) cameraHeight else cameraWidth

        private val effectiveHeight: Int
            get() = if (isQuarterTurned) cameraWidth else cameraHeight

        /**
         * Rebuilds all three transforms. NDC is square, so a plain Z-rotation of the quad
         * maps the source onto a correctly-swapped target with no distortion, provided the
         * destination surface itself has the swapped dimensions.
         */
        private fun updateMatrices() {
            Matrix.setIdentityM(identityBase, 0)

            Matrix.setIdentityM(flipBase, 0)
            Matrix.scaleM(flipBase, 0, 1f, -1f, 1f)

            // Letterbox the *rotated* aspect into the view without cropping or stretching.
            Matrix.setIdentityM(letterboxBase, 0)
            if (viewWidth == 0 || viewHeight == 0 || cameraWidth == 0 || cameraHeight == 0) return

            val viewAspect = viewWidth.toFloat() / viewHeight
            val imageAspect = effectiveWidth.toFloat() / effectiveHeight
            if (imageAspect > viewAspect) {
                Matrix.scaleM(letterboxBase, 0, 1f, viewAspect / imageAspect, 1f)
            } else {
                Matrix.scaleM(letterboxBase, 0, imageAspect / viewAspect, 1f, 1f)
            }
        }

        /**
         * out = base · translate · roll · crop · rotate.
         *
         * Read right to left on a vertex: the source is turned to the chosen rotation, zoomed
         * slightly so the shake shift has somewhere to move into, de-rolled, shifted, and
         * finally mapped into the destination by [base].
         */
        private fun compose(
            base: FloatArray,
            out: FloatArray,
            dx: Float,
            dy: Float,
            rollDegrees: Float,
            crop: Float,
        ) {
            System.arraycopy(base, 0, out, 0, 16)
            if (dx != 0f || dy != 0f) Matrix.translateM(out, 0, dx, dy, 0f)
            if (rollDegrees != 0f) Matrix.rotateM(out, 0, rollDegrees, 0f, 0f, 1f)
            if (crop != 1f) Matrix.scaleM(out, 0, crop, crop, 1f)
            if (rotationDegrees != 0) {
                Matrix.rotateM(out, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
            }
        }

        fun startRecording(frameRate: Int, withAudio: Boolean) {
            if (recorder != null) return
            val config = eglConfig ?: return
            if (cameraWidth == 0 || cameraHeight == 0) return

            // H.264 encoders require even dimensions; UVC sizes always are, but be safe.
            val w = effectiveWidth and 1.inv()
            val h = effectiveHeight and 1.inv()

            recorder = VideoRecorder(
                context.applicationContext, w, h, frameRate, withAudio
            ).apply { start(eglDisplay, config, eglContext) }
        }

        fun setStreamQuality(quality: Int) {
            streamCapture?.jpegQuality = quality
        }

        fun startStreaming(server: MjpegServer) {
            if (streamCapture != null) return
            streamCapture = StreamCapture(
                server, targetFps = streamTargetFps, quality = streamJpegQuality
            ).apply {
                if (cameraWidth > 0 && cameraHeight > 0) {
                    configure(effectiveWidth, effectiveHeight)
                }
            }
        }

        fun stopStreaming() {
            streamCapture?.release()
            streamCapture = null
        }

        fun stopRecording(): Uri? {
            val active = recorder ?: return null
            recorder = null
            return active.stop()
        }

        private fun findCurrentEgl14Config(): EGLConfig? {
            val configId = IntArray(1)
            if (!EGL14.eglQueryContext(
                    eglDisplay, eglContext, EGL14.EGL_CONFIG_ID, configId, 0
                )
            ) return null

            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val found = EGL14.eglChooseConfig(
                eglDisplay,
                intArrayOf(EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE), 0,
                configs, 0, 1, count, 0
            )
            return if (found && count[0] > 0) configs[0] else null
        }
    }

    override fun onPause() {
        // Release the camera's hold on the surface before the context can go away.
        callback?.onPreviewSurfaceDestroyed()
        super.onPause()
    }

    /**
     * Picks an RGBA8888 config that MediaCodec will also accept as an encoder input surface.
     * Falls back to a plain config on the rare device that advertises no recordable one.
     */
    private class RecordableConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay10): EGLConfig10 {
            chooseWith(egl, display, recordable = true)?.let { return it }
            chooseWith(egl, display, recordable = false)?.let { return it }
            error("no suitable EGL config")
        }

        private fun chooseWith(
            egl: EGL10,
            display: EGLDisplay10,
            recordable: Boolean,
        ): EGLConfig10? {
            val attribs = buildList {
                addAll(listOf(EGL10.EGL_RED_SIZE, 8))
                addAll(listOf(EGL10.EGL_GREEN_SIZE, 8))
                addAll(listOf(EGL10.EGL_BLUE_SIZE, 8))
                addAll(listOf(EGL10.EGL_ALPHA_SIZE, 8))
                addAll(listOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT))
                if (recordable) addAll(listOf(EGL_RECORDABLE_ANDROID, 1))
                add(EGL10.EGL_NONE)
            }.toIntArray()

            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, attribs, null, 0, count) || count[0] <= 0) {
                return null
            }
            val configs = arrayOfNulls<EGLConfig10>(count[0])
            if (!egl.eglChooseConfig(display, attribs, configs, count[0], count)) return null
            return configs[0]
        }

        private companion object {
            const val EGL_OPENGL_ES2_BIT = 0x0004
            const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }
}
