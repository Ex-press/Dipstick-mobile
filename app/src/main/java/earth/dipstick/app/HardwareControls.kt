package earth.dipstick.app

import com.serenegiant.usb.UVCControl

/**
 * The image controls a UVC camera implements in firmware, discovered at runtime.
 *
 * Cheap camera modules vary wildly in what they expose - some offer brightness and nothing
 * else - so every control is probed with its `isXxxEnable()` flag and the UI only shows what
 * this particular camera actually answers to. Anything missing is covered by the GPU shader
 * in [ImageAdjustments].
 */
enum class HwControl(val label: String) {
    BRIGHTNESS("Brightness"),
    CONTRAST("Contrast"),
    GAMMA("Gamma"),
    SATURATION("Saturation"),
    SHARPNESS("Sharpness"),
    GAIN("Gain"),
    HUE("Hue"),
    WHITE_BALANCE("White balance"),
    FOCUS("Focus"),
    ZOOM("Zoom"),
}

class HardwareControls(private val control: UVCControl?) {

    /** The controls this camera reports it can actually do, in display order. */
    val supported: List<HwControl> by lazy {
        if (control == null) emptyList()
        else HwControl.entries.filter { isSupported(it) }
    }

    fun isSupported(which: HwControl): Boolean {
        val c = control ?: return false
        return runCatching {
            when (which) {
                HwControl.BRIGHTNESS -> c.isBrightnessEnable
                HwControl.CONTRAST -> c.isContrastEnable
                HwControl.GAMMA -> c.isGammaEnable
                HwControl.SATURATION -> c.isSaturationEnable
                HwControl.SHARPNESS -> c.isSharpnessEnable
                HwControl.GAIN -> c.isGainEnable
                HwControl.HUE -> c.isHueEnable
                HwControl.WHITE_BALANCE -> c.isWhiteBalanceEnable
                HwControl.FOCUS -> c.isFocusAbsoluteEnable
                HwControl.ZOOM -> c.isZoomAbsoluteEnable
            }
        }.getOrDefault(false)
    }

    /** Current value as 0..100, or -1 if the camera will not tell us. */
    fun getPercent(which: HwControl): Int {
        val c = control ?: return -1
        return runCatching {
            when (which) {
                HwControl.BRIGHTNESS -> c.brightnessPercent
                HwControl.CONTRAST -> c.contrastPercent
                HwControl.GAMMA -> c.gammaPercent
                HwControl.SATURATION -> c.saturationPercent
                HwControl.SHARPNESS -> c.sharpnessPercent
                HwControl.GAIN -> c.gainPercent
                HwControl.HUE -> c.huePercent
                HwControl.WHITE_BALANCE -> c.whiteBalancePercent
                HwControl.FOCUS -> c.focusAbsolutePercent
                HwControl.ZOOM -> c.zoomAbsolutePercent
            }
        }.getOrDefault(-1)
    }

    fun setPercent(which: HwControl, percent: Int) {
        val c = control ?: return
        val v = percent.coerceIn(0, 100)
        runCatching {
            when (which) {
                HwControl.BRIGHTNESS -> c.brightnessPercent = v
                HwControl.CONTRAST -> c.contrastPercent = v
                HwControl.GAMMA -> c.gammaPercent = v
                HwControl.SATURATION -> c.saturationPercent = v
                HwControl.SHARPNESS -> c.sharpnessPercent = v
                HwControl.GAIN -> c.gainPercent = v
                HwControl.HUE -> c.huePercent = v
                HwControl.WHITE_BALANCE -> c.whiteBalancePercent = v
                HwControl.FOCUS -> c.focusAbsolutePercent = v
                HwControl.ZOOM -> c.zoomAbsolutePercent = v
            }
        }
    }

    /** Restores the camera's own default for one control. */
    fun reset(which: HwControl) {
        val c = control ?: return
        runCatching {
            when (which) {
                HwControl.BRIGHTNESS -> c.resetBrightness()
                HwControl.CONTRAST -> c.resetContrast()
                HwControl.GAMMA -> c.resetGamma()
                HwControl.SATURATION -> c.resetSaturation()
                HwControl.SHARPNESS -> c.resetSharpness()
                HwControl.GAIN -> c.resetGain()
                HwControl.HUE -> c.resetHue()
                HwControl.WHITE_BALANCE -> c.resetWhiteBalance()
                HwControl.FOCUS -> c.resetFocusAbsolute()
                HwControl.ZOOM -> c.resetZoomAbsolute()
            }
        }
    }

    fun resetAll() {
        supported.forEach { reset(it) }
        // Hand focus back to the camera; auto is its out-of-the-box behaviour.
        if (isAutoFocusSupported) autoFocus = true
    }

    /**
     * Auto white balance, where available. Worth turning off for a dipstick reading, since
     * the camera will otherwise chase the colour of whatever it is looking at.
     */
    /** True when the lens has a focus motor the host can drive. */
    val isAutoFocusSupported: Boolean
        get() = runCatching { control?.isFocusAutoEnable == true }.getOrDefault(false)

    var autoFocus: Boolean
        get() = runCatching { control?.focusAuto == true }.getOrDefault(false)
        set(value) {
            runCatching { control?.focusAuto = value }
        }

    /**
     * Reads focus state straight back from the camera. If a write does not stick, this says
     * whether auto focus re-took control or the module simply ignores the register.
     */
    fun logFocusState(context: String) {
        val c = control ?: return
        val auto = runCatching { c.isFocusAutoEnable to c.focusAuto }.getOrNull()
        val abs = runCatching { c.focusAbsolute to c.focusAbsolutePercent }.getOrNull()
        val limits = runCatching { c.updateFocusAbsoluteLimit()?.joinToString() }.getOrNull()
        android.util.Log.i(
            "DipstickFocus",
            "$context: autoSupported=${auto?.first} autoOn=${auto?.second} " +
                "raw=${abs?.first} percent=${abs?.second} limits=[$limits]"
        )
    }

    /** Everything this camera claims, for logging what a given module actually implements. */
    fun describe(): String = buildString {
        append("supported=").append(supported.joinToString { it.label })
        append(" autoFocus=").append(isAutoFocusSupported)
        append(" autoWB=").append(isAutoWhiteBalanceSupported)
    }

    val isAutoWhiteBalanceSupported: Boolean
        get() = runCatching { control?.isWhiteBalanceAutoEnable == true }.getOrDefault(false)

    var autoWhiteBalance: Boolean
        get() = runCatching { control?.whiteBalanceAuto == true }.getOrDefault(false)
        set(value) {
            runCatching { control?.whiteBalanceAuto = value }
        }
}
