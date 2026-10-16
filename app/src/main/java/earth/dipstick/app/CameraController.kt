package earth.dipstick.app

import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.herohan.uvcapp.CameraException
import com.serenegiant.usb.Size

/**
 * Owns the USB/UVC side: device attach, opening the camera, preview format, rotation, and
 * the camera's own firmware image controls.
 *
 * Deliberately knows nothing about rendering. It hands frames to whatever [Surface] the
 * caller supplies, which is how [earth.dipstick.app.gl.CameraGLView] gets to sit in the
 * middle and apply the shader before anything is displayed or recorded.
 */
class CameraController(private val listener: Listener) {

    interface Listener {
        fun onDeviceAttached(device: UsbDevice)
        fun onDeviceDetached()
        fun onCameraOpened(size: Size)
        fun onCameraClosed()
        fun onCameraError(message: String)

        /** A transient open failure; the library will retry by itself. */
        fun onCameraReconnecting()
    }

    private var helper: ICameraHelper? = null
    private var previewSurface: Surface? = null

    /** Format to bind on the next open, set when switching between MJPEG and raw. */
    private var pendingOpenSize: Size? = null

    /** Null until the camera is open. Re-read after every open: limits are per-device. */
    var controls: HardwareControls = HardwareControls(null)
        private set

    var currentSize: Size? = null
        private set

    val isCameraOpen: Boolean get() = helper?.isCameraOpened == true

    /** Whether a UVC device is plugged in right now, regardless of whether it is open. */
    val hasDevice: Boolean get() = !helper?.deviceList.isNullOrEmpty()

    fun start() {
        if (helper != null) return
        helper = CameraHelper().apply { setStateCallback(stateCallback) }
    }

    fun stop() {
        helper?.runCatching { release() }
        helper = null
        currentSize = null
        controls = HardwareControls(null)
    }

    /** Opens the first attached UVC camera. Safe to call when nothing is plugged in. */
    fun open() {
        val h = helper ?: return
        if (h.isCameraOpened) return
        val device = h.deviceList?.firstOrNull()
        if (device == null) {
            // Worth being explicit: on a single-port phone the charging/debug cable and the
            // camera cannot both be attached, which is the usual reason nothing is found.
            listener.onCameraError("No camera detected — is it plugged into the USB-C port?")
            return
        }
        h.selectDevice(device)
    }

    fun close() {
        helper?.runCatching { closeCamera() }
    }

    /**
     * Points the camera at [surface]. Called when the GL surface becomes available, which
     * may be before or after the camera opens, so both paths re-attach.
     */
    fun attachPreviewSurface(surface: Surface) {
        previewSurface = surface
        val h = helper ?: return
        Log.i(TAG, "attachPreviewSurface: valid=${surface.isValid} cameraOpen=${h.isCameraOpened}")
        if (h.isCameraOpened) {
            runCatching { h.addSurface(surface, false) }
                .onSuccess { Log.i(TAG, "addSurface OK (from attach)") }
                .onFailure { Log.e(TAG, "addSurface FAILED (from attach)", it) }
        }
    }

    fun detachPreviewSurface() {
        val surface = previewSurface ?: return
        previewSurface = null
        helper?.runCatching { removeSurface(surface) }
    }

    fun supportedSizes(): List<Size> = helper?.supportedSizeList.orEmpty()

    /** True when the camera is currently streaming an uncompressed (non-MJPEG) format. */
    val isHighQuality: Boolean
        get() = currentSize?.let { it.type != FRAME_TYPE_MJPEG } == true

    /** Whether this camera offers any uncompressed format at all. */
    fun supportsHighQuality(): Boolean =
        supportedSizes().any { it.type != FRAME_TYPE_MJPEG }

    /**
     * Switches between the camera's MJPEG and uncompressed formats, picking the largest
     * frame each offers.
     *
     * Uncompressed avoids the camera's fixed-quality JPEG encoder entirely - it implements
     * no compression-quality control, so this is the only way to improve its output - at the
     * cost of frame rate, since raw 720p saturates USB 2.0 at around 10fps.
     */
    fun setHighQuality(enabled: Boolean): Boolean {
        val sizes = supportedSizes()
        if (sizes.isEmpty()) return false
        val candidates = if (enabled) {
            sizes.filter { it.type != FRAME_TYPE_MJPEG }
        } else {
            sizes.filter { it.type == FRAME_TYPE_MJPEG }
        }
        val target = candidates.maxByOrNull { it.width.toLong() * it.height } ?: return false
        Log.i(TAG, "setHighQuality($enabled) -> ${target.width}x${target.height} " +
            "type=${target.type} fps=${target.fps}")

        // setPreviewSize only moves between resolutions *within* the current format - it
        // silently keeps the old frame type. The format is bound when the camera is opened,
        // so a format change means reopening with the desired Size.
        val h2 = helper ?: return false
        pendingOpenSize = target
        // closeCamera also drops the device connection, so the way back in is selectDevice;
        // onDeviceOpen then binds the pending format via openCamera(Size).
        runCatching { h2.closeCamera() }
        Handler(Looper.getMainLooper()).postDelayed({
            open()
        }, REOPEN_DELAY_MS)
        return true
    }

    /** Switches resolution. The preview must be bounced for the new format to take effect. */
    fun setPreviewSize(size: Size) {
        val h = helper ?: return
        if (!h.isCameraOpened) return
        runCatching {
            h.stopPreview()
            h.previewSize = size
            h.startPreview()
            currentSize = h.previewSize
            Log.i(
                TAG,
                "setPreviewSize requested ${size.width}x${size.height} type=${size.type} " +
                    "fps=${size.fps} -> reported ${currentSize?.width}x${currentSize?.height} " +
                    "type=${currentSize?.type} fps=${currentSize?.fps}"
            )
        }.onFailure {
            Log.e(TAG, "could not switch to ${size.width}x${size.height}", it)
            listener.onCameraError("This camera refused ${size.width}x${size.height}")
        }
        currentSize?.let(listener::onCameraOpened)
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            Log.i(TAG, "attached: ${device.deviceName} vid=${device.vendorId} pid=${device.productId}")
            listener.onDeviceAttached(device)
            // Requesting the device triggers the USB permission prompt if needed.
            helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            Log.i(TAG, "onDeviceOpen firstOpen=$isFirstOpen pending=${pendingOpenSize?.type}")
            val h = helper ?: return
            val pending = pendingOpenSize
            pendingOpenSize = null
            if (pending != null) h.openCamera(pending) else h.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            val h = helper ?: return
            h.startPreview()

            // Limits are only readable once the camera is streaming.
            controls = HardwareControls(runCatching { h.uvcControl }.getOrNull())
            Log.i(TAG, "UVC controls: ${controls.describe()}")

            // Enumerate every frame the camera advertises, so we know whether stills can go
            // beyond the streaming resolution rather than guessing.
            runCatching {
                val sizes = h.supportedSizeList.orEmpty()
                Log.i(TAG, "supported sizes (${sizes.size}):")
                sizes.forEach { sz ->
                    Log.i(
                        TAG,
                        "   ${sz.width}x${sz.height} type=${sz.type} fps=${sz.fps} " +
                            "fpsList=${sz.fpsList?.joinToString(",")}"
                    )
                }
                val formats = h.supportedFormatList.orEmpty()
                Log.i(TAG, "supported formats (${formats.size}): $formats")
            }.onFailure { Log.e(TAG, "could not enumerate formats", it) }

            val size = h.previewSize
            currentSize = size

            val ps = previewSurface
            Log.i(TAG, "onCameraOpen: size=${size?.width}x${size?.height} surface=${ps} valid=${ps?.isValid}")
            if (ps != null) {
                runCatching { h.addSurface(ps, false) }
                    .onSuccess { Log.i(TAG, "addSurface OK (from onCameraOpen)") }
                    .onFailure { Log.e(TAG, "addSurface FAILED (from onCameraOpen)", it) }
            } else {
                Log.w(TAG, "onCameraOpen: no preview surface yet")
            }

            if (size != null) listener.onCameraOpened(size)
        }

        override fun onCameraClose(device: UsbDevice) {
            Log.i(TAG, "onCameraClose ${device.deviceName}")
            previewSurface?.let { surface -> helper?.runCatching { removeSurface(surface) } }
            controls = HardwareControls(null)
            currentSize = null
            listener.onCameraClosed()
        }

        override fun onDeviceClose(device: UsbDevice) {
            Log.i(TAG, "onDeviceClose ${device.deviceName}")
        }

        override fun onDetach(device: UsbDevice) {
            Log.i(TAG, "onDetach ${device.deviceName}")
            listener.onDeviceDetached()
        }

        override fun onCancel(device: UsbDevice) {
            // Android grants USB device access per app package, so a reinstall under a new
            // package id starts with no grant and this fires until the prompt is accepted.
            Log.w(TAG, "onCancel ${device.deviceName} - USB permission not granted")
            listener.onCameraError(
                "USB permission needed — unplug the camera, plug it back in, and tap OK"
            )
        }

        // The interface gives this a no-op default, so without overriding it any failure
        // during open - which is exactly what a replug would hit - is silently discarded.
        override fun onError(device: UsbDevice?, e: CameraException?) {
            // Replugging re-enumerates the device, and the first open attempt against the
            // old node reliably fails before the library retries against the new one. That
            // is a normal part of reconnecting, not something to alarm the operator with -
            // so it stays in the log and the UI just says what is happening.
            Log.e(TAG, "onError device=${device?.deviceName}", e)
            listener.onCameraReconnecting()
        }
    }

    private companion object {
        const val TAG = "DipstickCam"

        /** libuvc frame-format code for MJPEG; anything else this camera lists is raw. */
        const val FRAME_TYPE_MJPEG = 7

        /** closeCamera is asynchronous; give it a moment before reopening. */
        const val REOPEN_DELAY_MS = 400L
    }
}
