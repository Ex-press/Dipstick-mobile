package earth.dipstick.app.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.exp

/**
 * Gyroscope-driven electronic image stabilisation.
 *
 * Integrates angular velocity into a small orientation offset and high-pass filters it, so
 * deliberate panning decays back to centre within [TAU_SECONDS] while shake - which is fast
 * and zero-mean - survives to be cancelled. The offset is converted to an image shift and
 * roll, which the renderer applies as part of its transform.
 *
 * This assumes the camera is reasonably rigidly coupled to the phone. On a flexible or
 * articulated probe the tip lags the handle, so the correction is approximate rather than
 * exact - hence [strength], which lets the operator dial it to what actually helps.
 *
 * Translational shake is deliberately not corrected: without scene depth it is unrecoverable,
 * and integrating accelerometer data twice drifts uselessly within seconds.
 */
class MotionStabilizer(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val isAvailable: Boolean get() = gyroscope != null

    /** 0 disables correction entirely; 1 is the nominal mapping; 2 over-corrects. */
    @Volatile
    var strength: Float = 1.0f

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                offsetX = 0f; offsetY = 0f; offsetZ = 0f
            }
        }

    // Accumulated, decayed rotation offsets in radians, written on the sensor thread and
    // read on the GL thread.
    @Volatile private var offsetX = 0f
    @Volatile private var offsetY = 0f
    @Volatile private var offsetZ = 0f

    /** Smoothed crop margin currently required, in NDC units. GL thread only. */
    private var demand = 0f

    private var lastTimestampNanos = 0L
    private var registered = false

    fun start() {
        val manager = sensorManager ?: return
        val sensor = gyroscope ?: run {
            Log.w(TAG, "no gyroscope on this device; stabilisation unavailable")
            return
        }
        if (registered) return
        // GAME rate is ~50Hz, comfortably above frame rate without burning battery.
        registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        lastTimestampNanos = 0L
        Log.i(TAG, "gyroscope listener registered=$registered")
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        offsetX = 0f; offsetY = 0f; offsetZ = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        if (lastTimestampNanos == 0L) {
            lastTimestampNanos = event.timestamp
            return
        }
        val dt = (event.timestamp - lastTimestampNanos) / 1_000_000_000f
        lastTimestampNanos = event.timestamp
        if (dt <= 0f || dt > 0.25f) return   // dropped batch or first sample after a gap

        if (!enabled) return

        // Integrate angular velocity, then bleed the accumulator toward zero. The bleed is
        // what separates "shake to cancel" from "panning to follow".
        val decay = exp(-dt / TAU_SECONDS)
        offsetX = (offsetX + event.values[0] * dt) * decay
        offsetY = (offsetY + event.values[1] * dt) * decay
        offsetZ = (offsetZ + event.values[2] * dt) * decay
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Writes the current correction into [out] as `[dxNdc, dyNdc, rollDegrees, cropScale]`.
     *
     * The crop is demand-driven rather than fixed. A fixed margin meant switching
     * stabilisation on always upscaled the image - and so always softened it - even with the
     * camera sitting perfectly still. Now the zoom only grows to cover the shake actually
     * present, so a steady shot keeps its full resolution.
     */
    fun readInto(out: FloatArray) {
        if (!enabled) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f
            demand = 0f
            return
        }
        val s = strength
        // A rotation of the camera shifts the image by roughly focal_length * angle. We do
        // not know this camera's focal length, so NDC_PER_RAD encodes a ~90 degree field of
        // view and `strength` absorbs the error.
        val dx = (-offsetY * NDC_PER_RAD * s).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        val dy = (offsetX * NDC_PER_RAD * s).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        val roll = (-offsetZ * RAD_TO_DEG * s).coerceIn(-MAX_ROLL_DEG, MAX_ROLL_DEG)

        // Margin this frame needs: the translation itself, plus what the roll sweeps out.
        val need = maxOf(abs(dx), abs(dy)) + abs(roll) / MAX_ROLL_DEG * ROLL_MARGIN

        // Open up immediately so a sudden jolt is never clipped; close slowly so the zoom
        // does not visibly breathe as the shake dies down.
        demand = if (need > demand) need else demand + (need - demand) * RELEASE_RATE

        out[0] = dx
        out[1] = dy
        out[2] = roll
        out[3] = (1f + demand).coerceIn(1f, 1f + MAX_SHIFT + ROLL_MARGIN)
    }

    companion object {
        private const val TAG = "DipstickStab"

        /** How quickly sustained motion stops being treated as shake. */
        private const val TAU_SECONDS = 0.6f

        private const val NDC_PER_RAD = 1.27f      // half-frame per ~45 degrees
        private const val RAD_TO_DEG = 57.2958f

        /** Must stay within the renderer's crop margin or the frame edge shows. */
        const val MAX_SHIFT = 0.11f
        const val MAX_ROLL_DEG = 6f

        /** Extra margin a full-scale roll correction sweeps out at the frame corners. */
        const val ROLL_MARGIN = 0.05f

        /** Per-frame easing when shake subsides. ~1.5s to settle at 25fps. */
        const val RELEASE_RATE = 0.03f
    }
}
