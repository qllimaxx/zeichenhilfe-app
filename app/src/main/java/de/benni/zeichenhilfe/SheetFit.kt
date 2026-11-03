package de.benni.zeichenhilfe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Blatt auf dem Tisch und Einpassen des Fotos (ab 0.3) – reiner Kotlin-Code, per Unit-Test prüfbar.
 * Autor: Benjamin Dormoros
 *
 * Blatt-Koordinaten: Ursprung = Blattmitte, x = entlang der LANGEN Blattseite, y = Flächennormale,
 * z = x × y (entlang der kurzen Seite). Der Anker des eingepassten Fotos hat genau diese Lage,
 * deshalb sind die Foto-Werte (PhotoPlacement) dann zugleich Blatt-Koordinaten.
 */
class Sheet(
    val center: FloatArray,   // Welt, Meter
    val xAxis: FloatArray,    // lange Seite (Einheitsvektor)
    val yAxis: FloatArray,    // Normale
    val zAxis: FloatArray,    // kurze Seite
    val long: Float,          // Meter
    val short: Float
) {
    /** Weltpunkt -> Blatt-Koordinaten (x, z). */
    fun toLocal(p: FloatArray): FloatArray {
        val d = floatArrayOf(p[0] - center[0], p[1] - center[1], p[2] - center[2])
        return floatArrayOf(dot(d, xAxis), dot(d, zAxis))
    }

    /** Drehung als Quaternion (x, y, z, w) – für die ARCore-Pose des Ankers. */
    fun quaternion(): FloatArray = Quat.fromAxes(xAxis, yAxis, zAxis)

    /** Blattgröße als Text „21,0 × 29,7 cm“ (kurz × lang). */
    fun sizeText(): String = cm(short) + " × " + cm(long) + " cm"

    companion object {
        fun cm(m: Float) = String.format(java.util.Locale.GERMANY, "%.1f", m * 100f)
        private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        private fun len(a: FloatArray) = sqrt(dot(a, a))
        private fun norm(a: FloatArray): FloatArray { val l = len(a); return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
        private fun crossV(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        /**
         * Aus 4 Weltecken (der Reihe nach, 12 Werte) und der Flächennormale. Null, wenn entartet.
         */
        fun fromCorners(c: FloatArray, normal: FloatArray): Sheet? {
            val p = List(4) { floatArrayOf(c[3 * it], c[3 * it + 1], c[3 * it + 2]) }
            val n = norm(normal)
            val e0 = sub(p[1], p[0]); val e1 = sub(p[2], p[1]); val e2 = sub(p[3], p[2]); val e3 = sub(p[0], p[3])
            val a = (len(e0) + len(e2)) / 2f; val b = (len(e1) + len(e3)) / 2f
            if (a < 1e-3f || b < 1e-3f) return null
            var dir = if (a >= b) sub(e0, e2) else sub(e1, e3)       // e2 bzw. e3 zeigen entgegen
            dir = sub(dir, floatArrayOf(n[0] * dot(dir, n), n[1] * dot(dir, n), n[2] * dot(dir, n)))   // in die Fläche legen
            if (len(dir) < 1e-6f) return null
            val x = norm(dir); val z = crossV(x, n)
            val ctr = FloatArray(3) { i -> (p[0][i] + p[1][i] + p[2][i] + p[3][i]) / 4f }
            return Sheet(ctr, x, n, z, max(a, b), min(a, b))
        }
    }
}

object Quat {
    /** Quaternion (x, y, z, w) zur Drehmatrix mit den Spalten X, Y, Z. */
    fun fromAxes(x: FloatArray, y: FloatArray, z: FloatArray): FloatArray {
        val m00 = x[0]; val m10 = x[1]; val m20 = x[2]
        val m01 = y[0]; val m11 = y[1]; val m21 = y[2]
        val m02 = z[0]; val m12 = z[1]; val m22 = z[2]
        val tr = m00 + m11 + m22
        val q = FloatArray(4)
        if (tr > 0f) {
            val s = sqrt(tr + 1f) * 2f
            q[3] = 0.25f * s; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            q[3] = (m21 - m12) / s; q[0] = 0.25f * s; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = 0.25f * s; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = 0.25f * s
        }
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return floatArrayOf(q[0] / l, q[1] / l, q[2] / l, q[3] / l)
    }

    /** Vektor v mit Quaternion q drehen. */
    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val (x, y, z, w) = q
        // t = 2 · (q.xyz × v); v' = v + w·t + q.xyz × t
        val tx = 2f * (y * v[2] - z * v[1]); val ty = 2f * (z * v[0] - x * v[2]); val tz = 2f * (x * v[1] - y * v[0])
        return floatArrayOf(v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))
    }
}

/** Lage des Fotos relativ zum Blatt (Blatt-Koordinaten, Meter bzw. Bogenmaß). */
data class PhotoOnSheet(val cx: Float, val cz: Float, val theta: Float, val width: Float, val sheetLong: Float, val sheetShort: Float)

object SheetFit {
    const val MARGIN = 0.01f      // 1 cm Rand

    class Fit(val width: Float, val height: Float, val theta: Float)

    /**
     * Foto mittig aufs Blatt: so groß wie möglich, eigenes Seitenverhältnis ([aspect] = Breite/Höhe),
     * Rand [margin], lange Fotoseite entlang der langen Blattseite (Blatt-x).
     * (camX, camZ) = Richtung zur Kamera in Blatt-Koordinaten: Bild-unten zeigt zur Kamera.
     * Drehung theta wie in PhotoPlacement: Fotobreite zeigt nach (cos θ, −sin θ), Bild-unten nach (sin θ, cos θ).
     */
    fun fit(sheetLong: Float, sheetShort: Float, aspect: Float, margin: Float = MARGIN, camX: Float = 0f, camZ: Float = 1f): Fit? {
        val availL = sheetLong - 2 * margin; val availS = sheetShort - 2 * margin
        if (availL <= 0f || availS <= 0f || aspect <= 0f) return null
        val r = max(aspect, 1f / aspect)               // lange / kurze Fotoseite
        val longSide = min(availL, availS * r)
        val shortSide = longSide / r
        val landscape = aspect >= 1f
        val w = if (landscape) longSide else shortSide
        val h = if (landscape) shortSide else longSide
        // quer: Fotobreite entlang Blatt-x → θ = 0 oder π; hoch: Fotobreite entlang Blatt-z → θ = −π/2 oder π/2
        val base = if (landscape) 0f else (-PI / 2).toFloat()
        val down = floatArrayOf(sin(base), cos(base))
        val theta = if (hypot(camX, camZ) < 1e-6f || down[0] * camX + down[1] * camZ >= 0f) base else base + PI.toFloat()
        return Fit(w, h, theta)
    }
}
