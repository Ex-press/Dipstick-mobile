package earth.dipstick.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import earth.dipstick.app.databinding.ActivityMainBinding
import earth.dipstick.app.gl.CameraGLView
import earth.dipstick.app.gl.StreamCapture
import earth.dipstick.app.net.MdnsAdvertiser
import earth.dipstick.app.net.MjpegServer
import earth.dipstick.app.sensor.MotionStabilizer
import earth.dipstick.app.teslong.TeslongCamera
import earth.dipstick.app.usb.UsbProbe
import com.serenegiant.usb.Size
import java.util.Locale

class MainActivity : AppCompatActivity(), CameraController.Listener, CameraGLView.Callback,
    TeslongCamera.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: CameraController
    private lateinit var teslong: TeslongCamera

    /** Set once a Teslong network endoscope answers; it and a UVC camera are mutually exclusive. */
    private var usingTeslong = false

    private val handler = Handler(Looper.getMainLooper())

    private var adjustments = ImageAdjustments.NEUTRAL
        set(value) {
            field = value
            binding.glView.adjustments = value
        }

    private var lastRecordingUri: Uri? = null
    private val mjpegServer = MjpegServer()

    private var mdns: MdnsAdvertiser? = null

    private val prefs by lazy { getSharedPreferences("dipstick", MODE_PRIVATE) }

    /** The name viewers type: http://<this>.local:8080 */
    private var streamHostname: String
        get() = prefs.getString(KEY_HOSTNAME, null) ?: defaultHostname()
        set(value) = prefs.edit().putString(KEY_HOSTNAME, value).apply()

    /**
     * A name that is unique per unit, because every device running this app would otherwise
     * claim `dipstick.local` and collide the moment two are on the same network - which is
     * the normal case for a demo table.
     *
     * The suffix is derived from a per-install id, so it is stable across restarts and
     * survives having several identical handsets. Rename it to something memorable with a
     * long-press on the Stream button.
     */
    private fun defaultHostname(): String {
        val existing = prefs.getString(KEY_INSTALL_ID, null)
        val id = existing ?: java.util.UUID.randomUUID().toString().replace("-", "").take(4)
        if (existing == null) prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return "$DEFAULT_HOSTNAME-$id"
    }

    private var streamQuality: Int
        get() = prefs.getInt(KEY_QUALITY, StreamCapture.DEFAULT_QUALITY)
        set(value) = prefs.edit().putInt(KEY_QUALITY, value).apply()

    /** Preview rotation in degrees; the button cycles 0 → 90 → 180 → 270. */
    private var rotation = 0

    /** User intent to record a voiceover. Independent of whether the mic actually opens. */
    private var voiceoverEnabled = false

    private lateinit var stabilizer: MotionStabilizer

    /**
     * Tracked here rather than read back from the GL thread. `glView.startRecording` is
     * queued onto the renderer, so the recorder is not live yet when the tick begins - the
     * old code asked `glView.isRecording`, got false, and killed the timer on its first run.
     */
    private var isRecordingUi = false
    private var recordStartedAt = 0L

    private val recordTicker = object : Runnable {
        override fun run() {
            if (!isRecordingUi) return
            val elapsed = (System.currentTimeMillis() - recordStartedAt) / 1000
            binding.tvRecordTime.text = getString(
                R.string.rec_fmt,
                elapsed / 60, elapsed % 60,
                formatSize(binding.glView.recordedBytes),
            )
            handler.postDelayed(this, 500)
        }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                controller.start()
            } else {
                binding.tvStatus.text = getString(R.string.status_needs_permission)
                toast(getString(R.string.status_needs_permission))
            }
        }

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            setVoiceover(granted)
            if (!granted) toast(getString(R.string.status_needs_mic))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // An inspection is a two-handed job; do not let the screen drop out mid-recording.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.glView.callback = this
        binding.glView.streamJpegQuality = streamQuality
        controller = CameraController(this)

        // Log what is actually on the bus, so an unfamiliar endoscope can be identified.
        UsbProbe.dumpAll(this)
        teslong = TeslongCamera(this, this)

        stabilizer = MotionStabilizer(this)
        binding.glView.stabilizer = stabilizer

        binding.btnCamera.setOnClickListener {
            if (usingTeslong) {
                if (teslong.isOpen) teslong.close() else teslong.open()
            } else {
                if (controller.isCameraOpen) controller.close() else controller.open()
            }
        }

        binding.btnRecord.setOnClickListener { toggleRecording() }

        binding.btnMic.setOnClickListener {
            if (voiceoverEnabled) {
                setVoiceover(false)
            } else if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                setVoiceover(true)
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnRotate.setOnClickListener {
            rotation = (rotation + 90) % 360
            binding.glView.setRotation(rotation)
            applyRotationBadge()
        }

        binding.btnAdjust.setOnClickListener {
            val showing = binding.panelAdjust.visibility == View.VISIBLE
            binding.panelAdjust.visibility = if (showing) View.GONE else View.VISIBLE
            binding.btnAdjust.setBackgroundResource(
                if (showing) R.drawable.btn_90s_white else R.drawable.btn_90s_blue
            )
        }

        binding.btnHq.setOnClickListener {
            if (!controller.supportsHighQuality()) {
                toast(getString(R.string.hq_unavailable))
                return@setOnClickListener
            }
            // Changing format restarts the preview, which the encoder cannot follow.
            if (isRecordingUi) {
                toast(getString(R.string.hq_busy))
                return@setOnClickListener
            }
            controller.setHighQuality(!controller.isHighQuality)
        }

        binding.btnStream.setOnClickListener { toggleStream() }
        binding.btnStream.setOnLongClickListener {
            promptForHostname()
            true
        }
        binding.btnRenameStream.setOnClickListener { promptForHostname() }
        binding.btnShare.setOnClickListener { shareLastRecording() }

        binding.btnFocus.setOnClickListener {
            setManualFocus(binding.panelFocus.visibility != View.VISIBLE)
        }

        binding.sliderFocus.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            applyManualFocus(value.toInt())
        }

        binding.btnStabilize.setOnClickListener {
            if (!stabilizer.isAvailable) {
                toast(getString(R.string.stab_unavailable))
                return@setOnClickListener
            }
            setStabilization(!stabilizer.enabled)
        }

        // Android restores the badge TextViews' own saved state, which would otherwise
        // survive a process restart while these fields reset to defaults - leaving the UI
        // claiming a rotation that is not applied. Drive both from one source of truth.
        rotation = savedInstanceState?.getInt(KEY_ROTATION, 0) ?: 0
        voiceoverEnabled = savedInstanceState?.getBoolean(KEY_VOICEOVER, false) ?: false
        val stabOn = savedInstanceState?.getBoolean(KEY_STABILIZE, false) ?: false
        stabilizer.strength = savedInstanceState?.getFloat(KEY_STAB_STRENGTH, 1f) ?: 1f

        binding.glView.setRotation(rotation)
        applyRotationBadge()
        setVoiceover(voiceoverEnabled)
        setStabilization(stabOn && stabilizer.isAvailable)
        binding.btnStabilize.isEnabled = stabilizer.isAvailable
    }

    private fun setStabilization(on: Boolean) {
        stabilizer.enabled = on
        binding.btnStabilize.setBackgroundResource(
            if (on) R.drawable.btn_90s_blue else R.drawable.btn_90s_white
        )
        if (on) stabilizer.start() else stabilizer.stop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ROTATION, rotation)
        outState.putBoolean(KEY_VOICEOVER, voiceoverEnabled)
        outState.putBoolean(KEY_STABILIZE, stabilizer.enabled)
        outState.putFloat(KEY_STAB_STRENGTH, stabilizer.strength)
    }

    /**
     * The slider reads macro at the top and distance at the bottom, which is how the lens
     * travels. UVC focus values run the other way - smaller is nearer - so the displayed
     * position is inverted before it reaches the camera. Flip [MACRO_AT_TOP] if a future
     * module turns out to be the other way round.
     */
    private fun sliderToFocusPercent(sliderValue: Int): Int =
        if (MACRO_AT_TOP) 100 - sliderValue else sliderValue

    /**
     * Switches between auto and manual focus, keeping the button label honest: it reads AF
     * whenever the camera is doing its own focusing, and MF whenever the operator holds the
     * slider. Closing the panel therefore hands focus back to the camera rather than leaving
     * the lens parked at the last manual value while the button claims otherwise.
     */
    private fun setManualFocus(manual: Boolean) {
        val hw = controller.controls
        binding.panelFocus.visibility = if (manual) View.VISIBLE else View.GONE
        binding.btnFocus.text = getString(if (manual) R.string.action_mf else R.string.action_af)
        binding.btnFocus.setBackgroundResource(
            if (manual) R.drawable.btn_90s_blue else R.drawable.btn_90s_white
        )

        if (manual) {
            if (hw.autoFocus) hw.autoFocus = false
            syncFocusSlider()
        } else if (hw.isAutoFocusSupported) {
            hw.autoFocus = true
            hw.logFocusState("returned to auto")
        }
    }

    private fun applyManualFocus(sliderValue: Int) {
        val hw = controller.controls
        val percent = sliderToFocusPercent(sliderValue)

        // Auto focus overrides whatever we write, so the first touch takes control.
        if (hw.autoFocus) hw.autoFocus = false
        hw.setPercent(HwControl.FOCUS, percent)

        binding.tvFocusValue.text = getString(R.string.focus_value_fmt, percent)
        hw.logFocusState("after set $percent%")
    }

    private fun syncFocusSlider() {
        val hw = controller.controls
        val current = hw.getPercent(HwControl.FOCUS).takeIf { it >= 0 } ?: 50
        // Set the range before the value: a Slider validates on draw and throws if the
        // value falls outside whatever range it currently holds.
        binding.sliderFocus.valueFrom = 0f
        binding.sliderFocus.valueTo = 100f
        val sliderValue = sliderToFocusPercent(current)
        binding.sliderFocus.value = sliderValue.toFloat().coerceIn(0f, 100f)
        binding.tvFocusValue.text = getString(R.string.focus_value_fmt, current)
        hw.logFocusState("panel opened")
    }

    private fun applyRotationBadge() {
        binding.tvRotation.text = getString(R.string.rotation_fmt, rotation)
        binding.tvRotation.visibility = if (rotation == 0) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        if (hasPermission(Manifest.permission.CAMERA)) {
            controller.start()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        detectTeslong()
    }

    override fun onResume() {
        super.onResume()
        android.util.Log.i("DipstickLife", "onResume")
        binding.glView.onResume()
        // The GL context survives a pause, so the surface must be re-offered explicitly.
        binding.glView.redeliverPreviewSurface()
        if (stabilizer.enabled) stabilizer.start()
    }

    override fun onPause() {
        android.util.Log.i("DipstickLife", "onPause")
        // Finish any recording cleanly rather than leaving a truncated MP4 behind.
        if (isRecordingUi) stopRecording()
        stabilizer.stop()        // no point burning the gyro while we are not drawing
        binding.glView.onPause()
        super.onPause()
    }

    override fun onStop() {
        stopStream()
        controller.stop()
        if (usingTeslong) teslong.release()
        super.onStop()
    }

    /** Frame rate of whichever source is running, or null if none is. */
    private fun activeFps(): Int? {
        val fps = if (usingTeslong) {
            teslong.currentMode?.fps
        } else {
            controller.currentSize?.fps
        } ?: return null
        return if (fps > 0) fps else DEFAULT_FPS
    }

    private val cameraIsOpen: Boolean
        get() = if (usingTeslong) teslong.isOpen else controller.isCameraOpen

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // --- CameraGLView.Callback -------------------------------------------------------

    override fun onPreviewSurfaceAvailable(surface: Surface) {
        controller.attachPreviewSurface(surface)
        teslong.attachPreviewSurface(surface)
    }

    override fun onPreviewSurfaceDestroyed() {
        controller.detachPreviewSurface()
        teslong.detachPreviewSurface()
    }

    override fun onRecordingFinished(uri: Uri?) {
        lastRecordingUri = uri
        binding.btnShare.isEnabled = uri != null
        binding.btnRecord.setImageResource(R.drawable.ic_record)
        binding.tvRecordTime.visibility = View.GONE
        binding.btnRotate.isEnabled = cameraIsOpen
        binding.btnHq.isEnabled = cameraIsOpen && !usingTeslong && controller.supportsHighQuality()
        handler.removeCallbacks(recordTicker)

        if (uri != null) {
            Snackbar.make(binding.root, "Saved to camera roll", Snackbar.LENGTH_LONG)
                .setAction(R.string.action_share) { shareLastRecording() }
                .show()
        } else {
            toast("Recording failed")
        }
    }

    // --- CameraController.Listener ---------------------------------------------------

    override fun onDeviceAttached(device: UsbDevice) {
        binding.tvStatus.text = getString(R.string.status_connecting)
    }

    override fun onDeviceDetached() {
        if (isRecordingUi) stopRecording()
        binding.tvStatus.text = getString(R.string.status_detached)
        setCameraUiEnabled(false)
    }

    override fun onCameraOpened(size: Size) {
        val hq = controller.isHighQuality
        val fps = if (size.fps > 0) size.fps else DEFAULT_FPS
        binding.tvStatus.text = getString(
            if (hq) R.string.hq_on else R.string.hq_off, size.width, size.height, fps
        )
        binding.glView.setCameraSize(size.width, size.height)

        // Match the stream's target to what the camera can actually deliver, or the
        // adaptive ladder reads a slow-but-healthy 10fps source as under-delivery.
        binding.glView.streamTargetFps = minOf(STREAM_MAX_FPS, fps)

        binding.btnHq.setBackgroundResource(
            if (hq) R.drawable.btn_90s_blue else R.drawable.btn_90s_white
        )
        setCameraUiEnabled(true)
        buildControlPanel()
    }

    override fun onCameraClosed() {
        binding.tvStatus.text = getString(R.string.status_waiting)
        setCameraUiEnabled(false)
    }

    // --- TeslongCamera.Listener ------------------------------------------------------

    override fun onCameraOpened(width: Int, height: Int, fps: Int) {
        runOnUiThread {
            binding.tvStatus.text = getString(R.string.hq_off, width, height, fps)
            binding.glView.setCameraSize(width, height)
            binding.glView.streamTargetFps = minOf(STREAM_MAX_FPS, fps)
            setCameraUiEnabled(true)
        }
    }

    /**
     * A Teslong endoscope appears as a USB-ethernet adapter rather than a camera, so it is found
     * by asking the network rather than the USB stack. Done off the main thread: it sends UDP.
     */
    private fun detectTeslong() {
        Thread({
            val found = runCatching { teslong.detect() }.getOrDefault(false)
            if (!found) return@Thread
            runOnUiThread {
                usingTeslong = true
                binding.tvStatus.text = getString(R.string.status_connecting)
                teslong.open()
            }
        }, "teslong-detect").start()
    }

    override fun onCameraError(message: String) {
        toast(message)
    }

    override fun onCameraReconnecting() {
        binding.tvStatus.text = getString(R.string.status_reconnecting)
    }

    // --- Recording -------------------------------------------------------------------

    private fun setVoiceover(enabled: Boolean) {
        voiceoverEnabled = enabled
        binding.btnMic.setImageResource(
            if (enabled) R.drawable.ic_mic else R.drawable.ic_mic_off
        )
        binding.btnMic.setBackgroundResource(
            if (enabled) R.drawable.btn_90s_blue else R.drawable.btn_90s_white
        )
    }

    private fun toggleRecording() {
        if (isRecordingUi) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val fps = activeFps()
        if (fps == null) {
            toast("Camera is not running")
            return
        }
        binding.glView.startRecording(fps, voiceoverEnabled)

        isRecordingUi = true
        recordStartedAt = System.currentTimeMillis()
        binding.btnRecord.setImageResource(R.drawable.ic_stop)
        binding.tvRecordTime.text = getString(R.string.rec_fmt, 0L, 0L, "0 KB")
        binding.tvRecordTime.visibility = View.VISIBLE
        // The encoder surface is allocated for the current rotation and format, so freeze
        // both for the duration of the recording.
        binding.btnRotate.isEnabled = false
        binding.btnHq.isEnabled = false
        handler.post(recordTicker)
    }

    private fun stopRecording() {
        isRecordingUi = false
        binding.glView.stopRecording()   // onRecordingFinished resets the rest of the UI
    }

    private fun shareLastRecording() {
        val uri = lastRecordingUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share recording"))
    }

    // --- Streaming -------------------------------------------------------------------

    private fun toggleStream() {
        if (mjpegServer.isRunning) stopStream() else startStream()
    }

    private fun startStream() {
        if (!mjpegServer.start()) {
            toast(getString(R.string.stream_failed))
            return
        }
        binding.glView.startStreaming(mjpegServer)
        binding.btnStream.setBackgroundResource(R.drawable.btn_90s_blue)
        binding.rowStreamUrl.visibility = View.VISIBLE
        showStreamAddress(hostnameReady = false)

        // Publishing the name takes a moment; show the IP immediately and upgrade the label
        // once the responder is actually answering.
        val advertiser = MdnsAdvertiser(this, streamHostname, MjpegServer.DEFAULT_PORT)
        mdns = advertiser
        advertiser.start { ok -> runOnUiThread { showStreamAddress(hostnameReady = ok) } }
    }

    /**
     * Lets the operator pick the name the stream answers to. Restricted to the characters a
     * hostname may legally contain, so the advertised record is always resolvable.
     */
    private fun promptForHostname() {
        val input = android.widget.EditText(this).apply {
            setText(streamHostname)
            setSingleLine()
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.hostname_title)
            .setMessage(R.string.hostname_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val cleaned = input.text.toString()
                    .lowercase(Locale.US)
                    .replace(Regex("[^a-z0-9-]"), "-")
                    .trim('-')
                    .take(63)
                if (cleaned.isEmpty()) {
                    toast(getString(R.string.hostname_invalid))
                    return@setPositiveButton
                }
                streamHostname = cleaned
                // Republish under the new name if we are already streaming.
                if (mjpegServer.isRunning) {
                    mdns?.stop()
                    val advertiser =
                        MdnsAdvertiser(this, cleaned, MjpegServer.DEFAULT_PORT)
                    mdns = advertiser
                    advertiser.start { ok ->
                        runOnUiThread { showStreamAddress(hostnameReady = ok) }
                    }
                }
                toast(getString(R.string.hostname_set, "$cleaned.local"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Shows the name the network actually registered. If mDNS conflict resolution changed
     * it - another unit already had that name - the badge shows the real one and the user
     * is told, rather than being handed a URL that resolves somewhere else or nowhere.
     */
    private fun showStreamAddress(hostnameReady: Boolean) {
        val ip = MjpegServer.localAddresses().firstOrNull()
        val ipLine = if (ip != null) {
            getString(R.string.stream_url_fmt, ip, MjpegServer.DEFAULT_PORT)
        } else {
            "NO WI-FI ADDRESS"
        }
        val advertiser = mdns
        binding.tvStreamUrl.text = if (hostnameReady && advertiser != null) {
            "http://${advertiser.localHostname}:${MjpegServer.DEFAULT_PORT}\n$ipLine"
        } else {
            ipLine
        }

        if (hostnameReady && advertiser != null && advertiser.nameWasChanged) {
            binding.tvStreamUrl.setBackgroundResource(R.drawable.badge_90s_red)
            toast(
                getString(
                    R.string.hostname_taken,
                    advertiser.requestedHostname,
                    advertiser.localHostname,
                )
            )
        } else {
            binding.tvStreamUrl.setBackgroundResource(R.drawable.badge_90s_blue)
            if (hostnameReady && advertiser != null) {
                toast(getString(R.string.hostname_live, advertiser.localHostname))
            }
        }
    }

    private fun stopStream() {
        if (!mjpegServer.isRunning) return
        binding.glView.stopStreaming()
        mjpegServer.stop()
        mdns?.stop()
        mdns = null
        binding.btnStream.setBackgroundResource(R.drawable.btn_90s_white)
        binding.rowStreamUrl.visibility = View.GONE
    }

    // --- Control panel ---------------------------------------------------------------

    private fun setCameraUiEnabled(enabled: Boolean) {
        binding.btnRecord.isEnabled = enabled
        binding.btnAdjust.isEnabled = enabled
        binding.btnRotate.isEnabled = enabled
        binding.btnFocus.isEnabled =
            enabled && !usingTeslong && controller.controls.isSupported(HwControl.FOCUS)
        // Neither manual focus nor the uncompressed-format switch exists on the network cameras.
        binding.btnHq.isEnabled = enabled && !usingTeslong && controller.supportsHighQuality()
        if (!enabled) {
            binding.panelAdjust.visibility = View.GONE
            binding.btnAdjust.setBackgroundResource(R.drawable.btn_90s_white)
            binding.panelFocus.visibility = View.GONE
            binding.btnFocus.text = getString(R.string.action_af)
            binding.btnFocus.setBackgroundResource(R.drawable.btn_90s_white)
        }
    }

    /**
     * Rebuilt whenever a camera opens, because which hardware controls exist is a property
     * of the specific camera, not of the app.
     */
    private fun buildControlPanel() {
        val container = binding.containerControls
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val hw = controller.controls
        if (hw.supported.isNotEmpty()) {
            addSectionHeader(container, inflater, getString(R.string.label_hardware))

            hw.supported.forEach { control ->
                if (control == HwControl.FOCUS) return@forEach   // has its own MF panel
                val current = hw.getPercent(control).takeIf { it >= 0 } ?: 50
                addSlider(
                    container, inflater, control.label,
                    min = 0f, max = 100f, value = current.toFloat(),
                    format = { "${it.toInt()}%" },
                ) { value -> hw.setPercent(control, value.toInt()) }
            }
        } else {
            addSectionHeader(
                container, inflater,
                "${getString(R.string.label_hardware)} — ${getString(R.string.label_unsupported)}"
            )
        }

        addSectionHeader(container, inflater, getString(R.string.label_stream))
        addSlider(
            container, inflater, getString(R.string.label_jpeg_quality),
            StreamCapture.MIN_QUALITY.toFloat(), StreamCapture.MAX_QUALITY.toFloat(),
            streamQuality.toFloat(), { "${it.toInt()}" },
        ) { value ->
            streamQuality = value.toInt()
            binding.glView.streamJpegQuality = value.toInt()
        }

        if (stabilizer.isAvailable) {
            addSectionHeader(container, inflater, getString(R.string.label_stabilizer))
            addSlider(
                container, inflater, getString(R.string.label_stab_strength),
                0f, 2f, stabilizer.strength, { fmt(it) },
            ) { stabilizer.strength = it }
        }

        addSectionHeader(container, inflater, getString(R.string.label_processing))

        addSlider(container, inflater, getString(R.string.label_black_level),
            0f, 0.9f, adjustments.blackLevel, { fmt(it) }) {
            adjustments = adjustments.copy(blackLevel = it)
        }
        addSlider(container, inflater, getString(R.string.label_white_level),
            0.1f, 1f, adjustments.whiteLevel, { fmt(it) }) {
            adjustments = adjustments.copy(whiteLevel = it)
        }
        addSlider(container, inflater, getString(R.string.label_gamma),
            0.2f, 3f, adjustments.gamma, { fmt(it) }) {
            adjustments = adjustments.copy(gamma = it)
        }
        addSlider(container, inflater, getString(R.string.label_brightness),
            -0.5f, 0.5f, adjustments.brightness, { fmt(it) }) {
            adjustments = adjustments.copy(brightness = it)
        }
        addSlider(container, inflater, getString(R.string.label_contrast),
            0f, 2f, adjustments.contrast, { fmt(it) }) {
            adjustments = adjustments.copy(contrast = it)
        }
        addSlider(container, inflater, getString(R.string.label_saturation),
            0f, 2f, adjustments.saturation, { fmt(it) }) {
            adjustments = adjustments.copy(saturation = it)
        }

        val reset = Button(this).apply {
            text = getString(R.string.action_reset)
            isAllCaps = true
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.d_black))
            setBackgroundResource(R.drawable.btn_90s_yellow)
            setOnClickListener {
                adjustments = ImageAdjustments.NEUTRAL
                controller.controls.resetAll()
                buildControlPanel()
            }
        }
        container.addView(reset)
    }

    private fun addSectionHeader(
        container: LinearLayout,
        inflater: LayoutInflater,
        title: String,
    ) {
        val view = inflater.inflate(R.layout.view_section_header, container, false) as TextView
        view.text = title.uppercase(Locale.US)
        container.addView(view)
    }

    private fun addSlider(
        container: LinearLayout,
        inflater: LayoutInflater,
        label: String,
        min: Float,
        max: Float,
        value: Float,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ) {
        val row = inflater.inflate(R.layout.view_control_row, container, false)
        val tvLabel = row.findViewById<TextView>(R.id.tvLabel)
        val tvValue = row.findViewById<TextView>(R.id.tvValue)
        val slider = row.findViewById<Slider>(R.id.slider)

        tvLabel.text = label
        slider.valueFrom = min
        slider.valueTo = max
        slider.value = value.coerceIn(min, max)
        tvValue.text = format(slider.value)

        slider.addOnChangeListener { _, newValue, _ ->
            tvValue.text = format(newValue)
            onChange(newValue)
        }
        container.addView(row)
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(Locale.US, "%d KB", bytes / 1_000L)
        else -> "$bytes B"
    }

    private fun toast(message: String) =
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

    private companion object {
        const val DEFAULT_FPS = 30
        const val STREAM_MAX_FPS = 12
        const val KEY_ROTATION = "rotation"
        const val KEY_VOICEOVER = "voiceover"
        const val KEY_STABILIZE = "stabilize"
        const val KEY_STAB_STRENGTH = "stabStrength"
        const val MACRO_AT_TOP = true

        /** Viewers reach the stream at http://<this>.local:8080 */
        const val DEFAULT_HOSTNAME = "dipstick"
        const val KEY_HOSTNAME = "streamHostname"
        const val KEY_INSTALL_ID = "installId"
        const val KEY_QUALITY = "streamQuality"
    }
}
