package earth.dipstick.app

/**
 * The GPU-side image adjustments. These are applied in a fragment shader to every frame,
 * so whatever you see in the preview is exactly what lands in the recorded file.
 *
 * Hardware UVC controls (see [CameraController]) are preferred where the camera supports
 * them, because they act on the sensor before quantisation. These are the fallback that
 * always works, and the only way to get true levels/gamma on a camera whose firmware
 * exposes nothing.
 */
data class ImageAdjustments(
    /** Input black point. Pixels at or below this map to 0. 0f..1f */
    val blackLevel: Float = 0f,
    /** Input white point. Pixels at or above this map to 1. 0f..1f */
    val whiteLevel: Float = 1f,
    /** Midtone curve. 1f is linear; >1 lifts shadows. 0.2f..3f */
    val gamma: Float = 1f,
    /** Additive offset after levels. -1f..1f */
    val brightness: Float = 0f,
    /** Multiplier around mid-grey. 1f is neutral. 0f..2f */
    val contrast: Float = 1f,
    /** 0f is greyscale, 1f neutral, 2f heavily saturated. */
    val saturation: Float = 1f,
) {
    /** True when every value is neutral, i.e. the shader is a pass-through. */
    val isNeutral: Boolean
        get() = blackLevel == 0f && whiteLevel == 1f && gamma == 1f &&
                brightness == 0f && contrast == 1f && saturation == 1f

    companion object {
        val NEUTRAL = ImageAdjustments()
    }
}
