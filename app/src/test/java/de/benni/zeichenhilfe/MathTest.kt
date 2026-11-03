package de.benni.zeichenhilfe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Prüft die Mathematik ohne Gerät (läuft bei jedem Build in GitHub Actions mit). */
class MathTest {

    private fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        return floatArrayOf(f / aspect, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, (far + near) / (near - far), -1f, 0f, 0f, 2f * far * near / (near - far), 0f)
    }

    /** Ansicht (lookAt): Kamera bei (0; 0,4; 0,3) m schaut schräg nach unten auf die Tischmitte. */
    private fun viewMatrix(): FloatArray {
        val e = floatArrayOf(0f, 0.4f, 0.3f)
        var fx = -e[0]; var fy = -e[1]; var fz = -e[2]
        val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // s = f × up (up = y), u = s × f
        var sx = fy * 0f - fz * 1f; var sy = fz * 0f - fx * 0f; var sz = fx * 1f - fy * 0f
        val sl = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl
        val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx
        return floatArrayOf(
            sx, ux, -fx, 0f,
            sy, uy, -fy, 0f,
            sz, uz, -fz, 0f,
            -(sx * e[0] + sy * e[1] + sz * e[2]), -(ux * e[0] + uy * e[1] + uz * e[2]), (fx * e[0] + fy * e[1] + fz * e[2]), 1f
        )
    }

    @Test
    fun zoomBewegtKamerabildUndFotoGleich() {
        val w = 1080; val h = 2340
        val vp = Mat4.mul(perspective(65f, w.toFloat() / h, 0.03f, 50f), viewMatrix())
        val z = ViewZoom()
        z.pinch(400f, 900f, 450f, 1000f, 2.7f, w, h)
        z.pan(-120f, 80f, w, h)
        assertTrue(z.s > 2.6f)
        val pts = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.08f, 0f, -0.05f), floatArrayOf(-0.1f, 0f, 0.07f))
        for (p in pts) {
            // ungezoomte Lage im Kamerabild
            val c = Mat4.transform(vp, p[0], p[1], p[2], 1f)
            val nx = c[0] / c[3]; val ny = c[1] / c[3]
            // Kamerabild: Eckpunkt bei NDC n wird an s·n + t gezeichnet (Textur bleibt) -> Punkt erscheint dort
            val bgX = z.s * nx + z.tx; val bgY = z.s * ny + z.ty
            // Foto: Zoom-Matrix links an Projektion·Ansicht
            val c2 = Mat4.transform(Mat4.mul(z.clipMatrix(), vp), p[0], p[1], p[2], 1f)
            val fx = c2[0] / c2[3]; val fy = c2[1] / c2[3]
            println("Punkt ${p.toList()}: Kamerabild NDC (${"%.6f".format(bgX)}, ${"%.6f".format(bgY)}) Foto NDC (${"%.6f".format(fx)}, ${"%.6f".format(fy)})")
            assertEquals(bgX, fx, 1e-5f)
            assertEquals(bgY, fy, 1e-5f)
        }
    }

    @Test
    fun pinchHaeltPunktUnterDenFingern() {
        val w = 1080; val h = 2340
        val z = ViewZoom()
        z.pinch(540f, 1170f, 540f, 1170f, 2f, w, h)            // erst 2×
        val before = z.unzoom(700f, 900f, w, h)
        z.pinch(700f, 900f, 700f, 900f, 1.5f, w, h)            // um (700, 900) auf 3×
        val after = z.unzoom(700f, 900f, w, h)
        println("Zoom ${z.s}: Szenenpunkt unter dem Finger vorher (${before[0]}, ${before[1]}), nachher (${after[0]}, ${after[1]})")
        assertEquals(3f, z.s, 1e-5f)
        assertEquals(before[0], after[0], 1e-2f)
        assertEquals(before[1], after[1], 1e-2f)
        val back = z.zoom(after[0], after[1], w, h)
        assertEquals(700f, back[0], 1e-2f); assertEquals(900f, back[1], 1e-2f)
    }

    @Test
    fun zoomGrenzenUndRandbegrenzung() {
        val w = 1000; val h = 2000
        val z = ViewZoom()
        z.pinch(500f, 1000f, 500f, 1000f, 20f, w, h)
        assertEquals(5f, z.s, 1e-6f)
        z.pan(100000f, -100000f, w, h)
        // Kamerabild muss den Bildschirm ganz bedecken: linker Rand ≤ −1, rechter ≥ 1 usw.
        assertTrue(-z.s + z.tx <= -1f + 1e-6f && z.s + z.tx >= 1f - 1e-6f)
        assertTrue(-z.s + z.ty <= -1f + 1e-6f && z.s + z.ty >= 1f - 1e-6f)
        z.pinch(500f, 1000f, 500f, 1000f, 0.01f, w, h)
        assertEquals(1f, z.s, 1e-6f); assertEquals(0f, z.tx, 1e-6f); assertEquals(0f, z.ty, 1e-6f)
    }

    @Test
    fun strahlTrifftFlaeche() {
        val w = 1080; val h = 2340
        val vp = Mat4.mul(perspective(65f, w.toFloat() / h, 0.03f, 50f), viewMatrix())
        val inv = Mat4.invert(vp)!!
        val p = floatArrayOf(0.05f, 0f, -0.03f)
        val px = RayPlane.project(vp, p, w, h)!!
        val back = RayPlane.intersect(inv, px[0], px[1], w, h, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))!!
        println("Weltpunkt ${p.toList()} -> Bildschirm (${px[0]}, ${px[1]}) -> zurück ${back.toList()}")
        for (i in 0 until 3) assertEquals(p[i], back[i], 1e-4f)
    }

    @Test
    fun zweiFingerVerschiebenDrehenSkalieren() {
        val pl = PhotoPlacement().apply { cx = 0.03f; cz = -0.02f; theta = 0.4f; width = 0.2f; aspect = 1.5f }
        val before = listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f).map { (u, v) -> pl.corner(u, v) }
        // Finger: a0 -> a1, b0 -> b1 (Meter auf der Fläche)
        val a0 = floatArrayOf(0f, 0f); val b0 = floatArrayOf(0.1f, 0f)
        val a1 = floatArrayOf(0.02f, 0.01f); val b1 = floatArrayOf(0.02f + 0.15f * cos(0.3f), 0.01f + 0.15f * sin(0.3f))
        pl.twoFinger(a0[0], a0[1], b0[0], b0[1], a1[0], a1[1], b1[0], b1[1])
        val k = 1.5f; val d = 0.3f
        val after = listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f).map { (u, v) -> pl.corner(u, v) }
        val m0 = floatArrayOf(0.05f, 0f); val m1 = floatArrayOf((a1[0] + b1[0]) / 2, (a1[1] + b1[1]) / 2)
        for (i in 0 until 4) {
            val vx = before[i][0] - m0[0]; val vz = before[i][1] - m0[1]
            val ex = m1[0] + k * (cos(d) * vx - sin(d) * vz)
            val ez = m1[1] + k * (sin(d) * vx + cos(d) * vz)
            println("Ecke $i: erwartet (${"%.5f".format(ex)}, ${"%.5f".format(ez)}) berechnet (${"%.5f".format(after[i][0])}, ${"%.5f".format(after[i][1])})")
            assertEquals(ex, after[i][0], 1e-5f)
            assertEquals(ez, after[i][1], 1e-5f)
        }
        assertEquals(0.3f, pl.width, 1e-5f)
        // Seitenverhältnis bleibt (nicht verzerrt)
        val top = hypot(after[1][0] - after[0][0], after[1][1] - after[0][1])
        val side = hypot(after[3][0] - after[0][0], after[3][1] - after[0][1])
        assertEquals(1.5f, top / side, 1e-4f)
    }

    @Test
    fun matrixStimmtMitEckenUeberein() {
        val pl = PhotoPlacement().apply { cx = 0.1f; cz = 0.05f; theta = -0.7f; width = 0.25f; aspect = 0.75f }
        val m = pl.localMatrix(0f)
        for ((u, v) in listOf(-0.5f to -0.5f, 0.5f to 0.5f, 0.5f to -0.5f)) {
            val p = Mat4.transform(m, u, 0f, v, 1f)
            val c = pl.corner(u, v)
            assertEquals(c[0], p[0], 1e-6f); assertEquals(c[1], p[2], 1e-6f)
        }
    }

    @Test
    fun bildUntenZeigtZurKamera() {
        val pl = PhotoPlacement()
        pl.faceCamera(0.3f, 0.4f)          // Kamera liegt in Richtung (0,3; 0,4)
        val bottom = pl.corner(0f, 0.5f)   // Mitte der Bild-Unterkante
        assertEquals(atan2(0.4f, 0.3f), atan2(bottom[1], bottom[0]), 1e-5f)
    }
}
