package de.benni.zeichenhilfe

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Lage des Fotos auf der Fläche, in den Koordinaten des Ankers (Meter).
 * Die Fläche ist die x-z-Ebene des Ankers (y = Flächennormale).
 * cx/cz = Fotomitte, theta = Drehung um die Normale (wie Ry(theta)), width = Breite in Metern.
 * Bildrichtung: Bild-oben liegt bei lokal z = −h/2, Bild-unten bei z = +h/2.
 */
class PhotoPlacement {
    var cx = 0f
    var cz = 0f
    var theta = 0f
    var width = START_WIDTH
    var aspect = 1f      // Breite / Höhe des Fotos
    val height get() = width / aspect

    /** Ry(theta) angewendet auf (X, Z) -> (x, z). */
    private fun rot(x: Float, z: Float, t: Float) = floatArrayOf(cos(t) * x + sin(t) * z, -sin(t) * x + cos(t) * z)

    /** Ecke (u, v) mit u, v ∈ {−0,5; 0,5} in Anker-Koordinaten (x, z). */
    fun corner(u: Float, v: Float): FloatArray {
        val r = rot(u * width, v * height, theta)
        return floatArrayOf(cx + r[0], cz + r[1])
    }

    /** Bild-unten zeigt zur Kamera: Richtung (lx, lz) vom Anker zur Kamera, in Anker-Koordinaten. */
    fun faceCamera(lx: Float, lz: Float) {
        if (hypot(lx, lz) > 1e-4f) theta = atan2(lx, lz)
    }

    /** 1 Finger: verschieben um die Bewegung des Fingerpunkts auf der Fläche. */
    fun drag(x0: Float, z0: Float, x1: Float, z1: Float) {
        cx += x1 - x0
        cz += z1 - z0
    }

    /**
     * 2 Finger: Ähnlichkeitsabbildung, die die alten Fingerpunkte (a0, b0) auf die neuen (a1, b1) bringt
     * (verschieben, drehen, skalieren in echter Größe auf der Fläche). Jeder Fotopunkt p wandert nach
     * m1 + k·R(Δ)·(p − m0); Breite × k; Drehung: theta − Δ (Δ = Winkeländerung, gemessen als atan2(z, x)).
     */
    fun twoFinger(a0x: Float, a0z: Float, b0x: Float, b0z: Float, a1x: Float, a1z: Float, b1x: Float, b1z: Float) {
        val d0 = hypot(b0x - a0x, b0z - a0z)
        val d1 = hypot(b1x - a1x, b1z - a1z)
        if (d0 < 1e-4f || d1 < 1e-4f) return
        var k = d1 / d0
        val newW = min(MAX_WIDTH, max(MIN_WIDTH, width * k))
        k = newW / width
        val delta = atan2(b1z - a1z, b1x - a1x) - atan2(b0z - a0z, b0x - a0x)
        val m0x = (a0x + b0x) / 2f; val m0z = (a0z + b0z) / 2f
        val m1x = (a1x + b1x) / 2f; val m1z = (a1z + b1z) / 2f
        val vx = cx - m0x; val vz = cz - m0z
        val rx = cos(delta) * vx - sin(delta) * vz
        val rz = sin(delta) * vx + cos(delta) * vz
        cx = m1x + k * rx
        cz = m1z + k * rz
        width = newW
        theta -= delta
    }

    /**
     * Matrix Foto-Rechteck (X, Z ∈ −0,5..0,5, y = 0) -> Anker-Koordinaten, spaltenweise.
     * [lift] hebt das Foto minimal über die Fläche.
     */
    fun localMatrix(lift: Float = 0.0015f): FloatArray {
        val c = cos(theta); val s = sin(theta); val w = width; val h = height
        return floatArrayOf(
            c * w, 0f, -s * w, 0f,
            0f, 1f, 0f, 0f,
            s * h, 0f, c * h, 0f,
            cx, lift, cz, 1f
        )
    }

    companion object {
        const val START_WIDTH = 0.20f   // 20 cm
        const val MIN_WIDTH = 0.02f
        const val MAX_WIDTH = 3f
    }
}
