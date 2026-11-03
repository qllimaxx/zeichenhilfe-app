package de.benni.zeichenhilfe

import kotlin.math.abs

/**
 * Kleine Matrix-Hilfen (4×4, spaltenweise wie bei OpenGL), ohne Android-Abhängigkeit,
 * damit sie auch in Unit-Tests auf dem PC laufen.
 */
object Mat4 {
    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    /** r = a · b */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    /** m · (x, y, z, w) */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float, w: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12] * w,
        m[1] * x + m[5] * y + m[9] * z + m[13] * w,
        m[2] * x + m[6] * y + m[10] * z + m[14] * w,
        m[3] * x + m[7] * y + m[11] * z + m[15] * w
    )

    /** Inverse (allgemein), null wenn nicht umkehrbar. */
    fun invert(m: FloatArray): FloatArray? {
        val inv = DoubleArray(16)
        val a = DoubleArray(16) { m[it].toDouble() }
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (abs(det) < 1e-20) return null
        return FloatArray(16) { (inv[it] / det).toFloat() }
    }
}

/** Bildschirm-Pixel (y nach unten) <-> normierte Gerätekoordinaten NDC (−1..1, y nach oben). */
object ScreenNdc {
    fun toNdcX(x: Float, w: Int) = 2f * x / w - 1f
    fun toNdcY(y: Float, h: Int) = 1f - 2f * y / h
    fun toPxX(nx: Float, w: Int) = (nx + 1f) * 0.5f * w
    fun toPxY(ny: Float, h: Int) = (1f - ny) * 0.5f * h
}

object RayPlane {
    /**
     * Strahl durch einen Bildschirmpunkt (ungezoomte Pixel) mit der Ebene durch [origin] mit Normale [normal]
     * schneiden. invVP = Inverse von Projektion·Ansicht. Liefert Weltpunkt oder null (Strahl parallel/hinter).
     */
    fun intersect(invVP: FloatArray, x: Float, y: Float, w: Int, h: Int, origin: FloatArray, normal: FloatArray): FloatArray? {
        val nx = ScreenNdc.toNdcX(x, w)
        val ny = ScreenNdc.toNdcY(y, h)
        val n4 = Mat4.transform(invVP, nx, ny, -1f, 1f)
        val f4 = Mat4.transform(invVP, nx, ny, 1f, 1f)
        if (n4[3] == 0f || f4[3] == 0f) return null
        val p0 = floatArrayOf(n4[0] / n4[3], n4[1] / n4[3], n4[2] / n4[3])
        val p1 = floatArrayOf(f4[0] / f4[3], f4[1] / f4[3], f4[2] / f4[3])
        val d = floatArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2])
        val den = d[0] * normal[0] + d[1] * normal[1] + d[2] * normal[2]
        if (abs(den) < 1e-9f) return null
        val t = ((origin[0] - p0[0]) * normal[0] + (origin[1] - p0[1]) * normal[1] + (origin[2] - p0[2]) * normal[2]) / den
        if (t < 0f) return null
        return floatArrayOf(p0[0] + t * d[0], p0[1] + t * d[1], p0[2] + t * d[2])
    }

    /** Weltpunkt -> Bildschirm-Pixel (ungezoomt) mit VP = Projektion·Ansicht; null wenn hinter der Kamera. */
    fun project(vp: FloatArray, p: FloatArray, w: Int, h: Int): FloatArray? {
        val c = Mat4.transform(vp, p[0], p[1], p[2], 1f)
        if (c[3] <= 1e-6f) return null
        return floatArrayOf(ScreenNdc.toPxX(c[0] / c[3], w), ScreenNdc.toPxY(c[1] / c[3], h))
    }
}
