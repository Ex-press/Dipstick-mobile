package earth.dipstick.app.teslong

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import android.view.Surface
import kotlin.concurrent.thread

/**
 * A Teslong network endoscope presented the same way as the UVC camera: it is handed a [Surface]
 * and fills it with frames. Decoded JPEGs are drawn straight into the preview surface, so the
 * shader, recorder, streamer and rotation all work exactly as they do for a USB camera.
 */
class TeslongCamera(context: Context, private val listener: Listener) {

    interface Listener {
        fun onCameraOpened(width: Int, height: Int, fps: Int)
        fun onCameraClosed()
        fun onCameraError(message: String)
    }

    private val link = TeslongLink(context)
    private val control = TeslongControl(link)

    private var video: TeslongVideo? = null
    private var previewSurface: Surface? = null
    private var reusableBitmap: Bitmap? = null

    /** Modes the camera reported, best last. Empty until [detect] succeeds. */
    var supportedModes: List<TeslongControl.Mode> = emptyList()
        private set

    var currentMode: TeslongControl.Mode? = null
        private set

    val isOpen: Boolean get() = video != null

    /**
     * True if a Teslong endoscope is attached and answering. Also fetches its mode list, so a
     * successful detection leaves everything needed to open it.
     */
    fun detect(): Boolean {
        if (!link.resolve()) return false
        link.bindProcess()
        supportedModes = control.getSupportedModes()
        if (supportedModes.isEmpty()) {
            Log.w(TeslongLink.TAG, "ethernet endoscope present but not answering SETCMD")
            return false
        }
        Log.i(TeslongLink.TAG, "Teslong detected, modes: ${supportedModes.joinToString()}")
        return true
    }

    /** Opens at [preferred], or the highest-resolution mode the camera offers. */
    fun open(preferred: TeslongControl.Mode? = null) {
        if (isOpen) return
        if (supportedModes.isEmpty() && !detect()) {
            listener.onCameraError("No Teslong endoscope detected on the USB port.")
            return
        }
        val mode = preferred
            ?: supportedModes.maxByOrNull { it.width.toLong() * it.height }
            ?: run {
                listener.onCameraError("The endoscope reported no usable video modes.")
                return
            }

        thread(name = "teslong-open") {
            if (!control.setResolution(mode.width, mode.height, mode.fps)) {
                Log.w(TeslongLink.TAG, "set_resolution ${mode} refused; continuing anyway")
            }
            currentMode = mode
            startRenderThread()
            val receiver = TeslongVideo(link) { jpeg -> submitFrame(jpeg) }
            video = receiver
            receiver.start()
            listener.onCameraOpened(mode.width, mode.height, mode.fps)
        }
    }

    fun close() {
        video?.stop()
        video = null
        stopRenderThread()
        currentMode = null
        reusableBitmap?.recycle()
        reusableBitmap = null
        listener.onCameraClosed()
    }

    /** Releases the process-wide ethernet binding; call when the endoscope is unplugged. */
    fun release() {
        close()
        link.unbindProcess()
    }

    fun attachPreviewSurface(surface: Surface) {
        previewSurface = surface
    }

    fun detachPreviewSurface() {
        previewSurface = null
    }

    /**
     * Decodes one JPEG and paints it into the preview surface. Decoding happens on the receive
     * thread; at 1600x1200 this is the most expensive step, so the bitmap is reused between
     * frames to keep allocation out of the loop.
     */
    private val frameLock = Object()
    private var pendingFrame: ByteArray? = null
    private var renderThread: Thread? = null
    @Volatile private var rendering = false
    private var framesDropped = 0L

    /**
     * Hands a frame to the render thread, replacing any frame not yet drawn. Decoding must not
     * happen on the receive thread: time spent there is time not reading the socket, and the
     * camera's UDP fragments are lost rather than queued. Dropping a stale frame is always
     * better than falling behind on the wire.
     */
    private fun submitFrame(jpeg: ByteArray) {
        synchronized(frameLock) {
            if (pendingFrame != null) framesDropped++
            pendingFrame = jpeg
            frameLock.notifyAll()
        }
    }

    private fun startRenderThread() {
        if (rendering) return
        rendering = true
        renderThread = thread(name = "teslong-render") {
            while (rendering) {
                val frame = synchronized(frameLock) {
                    while (rendering && pendingFrame == null) {
                        runCatching { frameLock.wait(RENDER_WAIT_MS) }.getOrElse { return@thread }
                    }
                    pendingFrame.also { pendingFrame = null }
                } ?: continue
                renderFrame(frame)
            }
        }
    }

    private fun stopRenderThread() {
        rendering = false
        synchronized(frameLock) { frameLock.notifyAll() }
        renderThread?.join(RENDER_JOIN_MS)
        renderThread = null
        pendingFrame = null
    }

    private var framesDrawn = 0L
    private var lastRateLog = 0L
    private var lastReceivedCount = 0L

    /** Periodic confirmation that frames are decoding and at what rate. */
    private fun logThroughput(width: Int, height: Int) {
        framesDrawn++
        val now = System.currentTimeMillis()
        if (lastRateLog == 0L) { lastRateLog = now; return }
        if (now - lastRateLog < RATE_LOG_INTERVAL_MS) return
        val elapsed = now - lastRateLog
        val drawnFps = framesDrawn * 1000.0 / elapsed
        val received = video?.framesDelivered ?: 0L
        val receivedFps = (received - lastReceivedCount) * 1000.0 / elapsed
        Log.i(
            TeslongLink.TAG,
            "${width}x$height: received %.1f fps, drawn %.1f fps, dropped $framesDropped"
                .format(receivedFps, drawnFps)
        )
        framesDrawn = 0
        framesDropped = 0
        lastReceivedCount = received
        lastRateLog = now
    }

    private fun renderFrame(jpeg: ByteArray) {
        val surface = previewSurface ?: return
        if (!surface.isValid) return

        val options = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = reusableBitmap
            inSampleSize = 1
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        }.getOrElse {
            // A reused bitmap of the wrong size makes the decoder throw; drop it and retry clean.
            reusableBitmap = null
            runCatching {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                    inMutable = true
                })
            }.getOrNull()
        } ?: return
        reusableBitmap = bitmap
        logThroughput(bitmap.width, bitmap.height)

        val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
        runCatching {
            canvas.drawBitmap(
                bitmap,
                null,
                Rect(0, 0, canvas.width, canvas.height),
                null
            )
        }
        runCatching { surface.unlockCanvasAndPost(canvas) }
    }

    private companion object {
        const val RATE_LOG_INTERVAL_MS = 3000L
        const val RENDER_WAIT_MS = 200L
        const val RENDER_JOIN_MS = 1000L
    }
}
