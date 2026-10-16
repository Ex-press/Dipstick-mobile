package earth.dipstick.app.rec

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import earth.dipstick.app.ImageAdjustments
import earth.dipstick.app.gl.FrameShader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the *processed* camera frames to an MP4, optionally with a microphone voiceover.
 *
 * The encoder's input Surface is wrapped in an EGL surface on the preview's own GL context,
 * so [encodeFrame] re-runs the same [FrameShader] over the same texture. Brightness, levels
 * and rotation are therefore baked into the file rather than being preview-only.
 *
 * Output goes straight into MediaStore under Movies/Dipstick: no storage permission on
 * Android 10+, and a content:// Uri ready for the share sheet the moment recording stops.
 */
internal class VideoRecorder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val withAudio: Boolean,
) {
    private var codec: MediaCodec? = null
    private var gate: MuxerGate? = null
    private var audio: AudioEncoder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT

    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    @Volatile private var startNanos = 0L

    /** The MediaStore row backing this recording. Valid from [start] onwards. */
    var outputUri: Uri? = null
        private set

    var isRecording = false
        private set

    /** True when a microphone track is actually being captured. */
    var hasAudio = false
        private set

    /** Bytes of media committed to the file so far, for the on-screen readout. */
    val bytesWritten: Long get() = gate?.bytesWritten ?: 0L

    /** Must be called on the GL thread, with the preview's EGL context current. */
    fun start(display: EGLDisplay, eglConfig: EGLConfig, sharedContext: EGLContext) {
        check(!isRecording) { "already recording" }

        eglDisplay = display
        eglContext = sharedContext

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(width, height, frameRate))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // One keyframe per second keeps seeking usable in a long inspection recording.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder

        encoderSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, inputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(encoderSurface != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        val (uri, descriptor) = createMediaStoreOutput()
        outputUri = uri
        pfd = descriptor

        val muxer = MediaMuxer(
            descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        // The gate cannot start the muxer until it knows how many tracks to expect, so
        // settle the audio question before any format is published.
        var muxerGate = MuxerGate(muxer, if (withAudio) 2 else 1)
        hasAudio = false

        // Only now start the clock. Everything above - two codecs, a MediaStore insert and
        // a muxer - runs on the GL thread and can take a second on a mid-range phone.
        // Stamping the clock before it made every video timestamp start a second late while
        // audio began at zero, so the tracks disagreed on how long the recording was.
        startNanos = System.nanoTime()

        if (withAudio) {
            val enc = AudioEncoder(muxerGate, startNanos)
            if (enc.start()) {
                audio = enc
                hasAudio = true
            } else {
                // Mic refused (permission, or in use elsewhere). Fall back to a video-only
                // file rather than failing the recording. Safe to swap the gate here: no
                // track has been registered on it yet.
                Log.w(TAG, "microphone unavailable, recording video only")
                muxerGate = MuxerGate(muxer, 1)
            }
        }
        gate = muxerGate

        trackIndex = -1
        isRecording = true
        Log.i(TAG, "recording ${width}x$height @${frameRate}fps audio=$hasAudio -> $uri")
    }

    /**
     * Renders one frame into the encoder. Call on the GL thread straight after drawing the
     * preview; the current EGL surface is saved and restored around the switch.
     */
    fun encodeFrame(
        shader: FrameShader,
        textureId: Int,
        texMatrix: FloatArray,
        mvpMatrix: FloatArray,
        adjustments: ImageAdjustments,
    ) {
        if (!isRecording) return

        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        EGL14.eglMakeCurrent(eglDisplay, encoderSurface, encoderSurface, eglContext)

        // Full-frame, no letterboxing: the surface is already sized for the rotation, so
        // mvpMatrix carries rotation only.
        GLES20.glViewport(0, 0, width, height)
        shader.draw(textureId, texMatrix, mvpMatrix, adjustments)

        // Video and audio share one clock so a voiceover stays in sync.
        val ptsNanos = System.nanoTime() - startNanos
        EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, ptsNanos)
        EGL14.eglSwapBuffers(eglDisplay, encoderSurface)

        EGL14.eglMakeCurrent(eglDisplay, previousDraw, previousRead, eglContext)

        drain(endOfStream = false)
    }

    /** Finishes the file and returns its Uri, or null if nothing was written. */
    fun stop(): Uri? {
        if (!isRecording) return null
        isRecording = false

        // Audio first: it must stop feeding the muxer before the muxer itself stops.
        audio?.stop()
        audio = null

        try {
            codec?.signalEndOfInputStream()
            drain(endOfStream = true)
        } catch (e: Exception) {
            Log.e(TAG, "error finishing encode", e)
        }

        codec?.runCatching { stop() }
        codec?.runCatching { release() }
        codec = null

        gate?.stop()
        gate = null

        pfd?.runCatching { close() }
        pfd = null

        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }

        val uri = outputUri
        if (uri != null) {
            // Clearing IS_PENDING publishes the file to the gallery and to other apps.
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
        Log.i(TAG, "recording stopped -> $uri")
        return uri
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val muxerGate = gate ?: return

        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Without EOS there may simply be no output yet; with EOS we must keep
                    // waiting until the encoder actually flushes its final frames.
                    if (!endOfStream) return
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (trackIndex < 0) trackIndex = muxerGate.addTrack(encoder.outputFormat)
                }

                outIndex < 0 -> Unit // deprecated/unknown status, ignore

                else -> {
                    val encoded = encoder.getOutputBuffer(outIndex)
                        ?: error("encoder output buffer $outIndex was null")

                    // Codec config lives in the track format, not as a sample.
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxerGate.writeSample(trackIndex, encoded, bufferInfo)
                    }

                    encoder.releaseOutputBuffer(outIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun createMediaStoreOutput(): Pair<Uri, ParcelFileDescriptor> {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "dipstick_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Dipstick")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore refused to create a video entry")
        val descriptor = resolver.openFileDescriptor(uri, "rw")
            ?: error("could not open $uri for writing")
        return uri to descriptor
    }

    companion object {
        private const val TAG = "DipstickRec"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10_000L

        /**
         * ~0.12 bits per pixel per frame. Generous enough that the shader's contrast
         * stretching does not turn into blocking, which is the usual failure mode when
         * you push levels on an already-compressed MJPEG source.
         */
        private fun bitrateFor(width: Int, height: Int, fps: Int): Int =
            (width.toLong() * height * fps * 0.12).toInt().coerceIn(2_000_000, 20_000_000)
    }
}
