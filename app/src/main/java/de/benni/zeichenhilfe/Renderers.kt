package de.benni.zeichenhilfe

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "ZeichenhilfeAR"

private fun floatBuffer(n: Int): FloatBuffer =
    ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

private fun compile(type: Int, src: String): Int {
    val sh = GLES20.glCreateShader(type)
    GLES20.glShaderSource(sh, src)
    GLES20.glCompileShader(sh)
    val ok = IntArray(1)
    GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(sh)
        GLES20.glDeleteShader(sh)
        throw RuntimeException("Shader-Fehler: $log")
    }
    return sh
}

private fun program(vs: String, fs: String): Int {
    val p = GLES20.glCreateProgram()
    GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
    GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
    GLES20.glLinkProgram(p)
    val ok = IntArray(1)
    GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
    if (ok[0] == 0) throw RuntimeException("Programm-Fehler: " + GLES20.glGetProgramInfoLog(p))
    return p
}

/**
 * Kamerabild als bildschirmfüllendes Rechteck (externe OES-Textur von ARCore).
 * Die Eckpunkte werden mit dem Ansichts-Zoom (s, tx, ty) verschoben/skaliert, die Texturkoordinaten bleiben.
 */
class BackgroundRenderer {
    var textureId = -1; private set
    private var prog = 0
    private var aPos = 0
    private var aUv = 0
    private var uZoom = 0
    private var uTex = 0
    private val ndc = floatBuffer(8).apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val uv = floatBuffer(8)

    fun create() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        textureId = t[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        prog = program(
            """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform vec4 uZoom;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos * uZoom.xy + uZoom.zw, 0.0, 1.0);
            }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
            """.trimIndent()
        )
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aUv = GLES20.glGetAttribLocation(prog, "aUv")
        uZoom = GLES20.glGetUniformLocation(prog, "uZoom")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
    }

    fun draw(frame: Frame, s: Float, tx: Float, ty: Float) {
        if (frame.hasDisplayGeometryChanged()) {
            ndc.position(0); uv.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, uv)
            ndc.position(0); uv.position(0)
        }
        if (frame.timestamp == 0L) return            // noch kein Kamerabild
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform4f(uZoom, s, s, tx, ty)
        ndc.position(0); uv.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, ndc)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDepthMask(true)
    }
}

/** Das Foto als flaches, texturiertes Rechteck (halbtransparent). */
class PhotoRenderer {
    private var prog = 0
    private var aPos = 0
    private var aUv = 0
    private var uMvp = 0
    private var uTex = 0
    private var uAlpha = 0
    private var tex = 0
    var hasTexture = false; private set

    // Rechteck in der x-z-Ebene: Bild-oben bei z = −0,5 (Texturzeile 0 = Bildoberkante)
    private val pos = floatBuffer(12).apply {
        put(floatArrayOf(-0.5f, 0f, -0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f, -0.5f, 0.5f, 0f, 0.5f)); position(0)
    }
    private val uvs = floatBuffer(8).apply { put(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)); position(0) }

    fun create() {
        prog = program(
            """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }
            """.trimIndent(),
            """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            void main() { gl_FragColor = texture2D(uTex, vUv) * uAlpha; }   // Textur ist vormultipliziert (Android-Bitmap)
            """.trimIndent()
        )
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aUv = GLES20.glGetAttribLocation(prog, "aUv")
        uMvp = GLES20.glGetUniformLocation(prog, "uMvp")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        hasTexture = false
    }

    /** Neues Foto hochladen (im GL-Thread). Mipmaps (OpenGL ES 3) für ruhige Darstellung, wenn das Foto klein erscheint. */
    fun upload(bmp: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) Log.w(TAG, "Textur-Fehler $err")
        hasTexture = true
    }

    fun draw(mvp: FloatArray, alpha: Float) {
        if (!hasTexture) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        pos.position(0); uvs.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvs)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
