package earth.dipstick.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import earth.dipstick.app.ImageAdjustments
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws a camera frame (an external OES texture) onto whatever EGL surface is current,
 * applying [ImageAdjustments] on the way through.
 *
 * One instance is shared by the on-screen preview and the video encoder. They render on
 * the same GL thread into the same EGL context, so the encoder provably gets the identical
 * pixels as the preview - that is the whole point of routing recording through here rather
 * than using the UVC library's own capture path.
 */
internal class FrameShader {

    private val program = GlUtil.linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)

    private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    private val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val uMvp = GLES20.glGetUniformLocation(program, "uMvp")
    private val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val uBlack = GLES20.glGetUniformLocation(program, "uBlack")
    private val uWhite = GLES20.glGetUniformLocation(program, "uWhite")
    private val uGamma = GLES20.glGetUniformLocation(program, "uGamma")
    private val uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
    private val uContrast = GLES20.glGetUniformLocation(program, "uContrast")
    private val uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")

    private val vertexBuffer: FloatBuffer = floatBuffer(
        // x, y
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )

    private val texCoordBuffer: FloatBuffer = floatBuffer(
        // u, v
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    fun draw(
        textureId: Int,
        texMatrix: FloatArray,
        mvpMatrix: FloatArray,
        adjustments: ImageAdjustments,
    ) {
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glUniform1f(uBlack, adjustments.blackLevel)
        GLES20.glUniform1f(uWhite, adjustments.whiteLevel)
        GLES20.glUniform1f(uGamma, adjustments.gamma)
        GLES20.glUniform1f(uBrightness, adjustments.brightness)
        GLES20.glUniform1f(uContrast, adjustments.contrast)
        GLES20.glUniform1f(uSaturation, adjustments.saturation)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        /** Creates the external texture that a SurfaceTexture will stream camera frames into. */
        fun createOesTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val texId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            GlUtil.checkGlError("createOesTexture")
            return texId
        }

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        /**
         * Order matters and mirrors a stills workflow: set the input range first (levels),
         * shape the midtones (gamma), then offset and stretch around mid-grey, then colour.
         * Doing brightness before levels would just clip the range you are trying to recover.
         */
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform float uBlack;
            uniform float uWhite;
            uniform float uGamma;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            void main() {
                vec3 c = texture2D(sTexture, vTexCoord).rgb;
                c = clamp((c - uBlack) / max(uWhite - uBlack, 0.001), 0.0, 1.0);
                c = pow(c, vec3(1.0 / uGamma));
                c = c + uBrightness;
                c = (c - 0.5) * uContrast + 0.5;
                float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = mix(vec3(luma), c, uSaturation);
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}
