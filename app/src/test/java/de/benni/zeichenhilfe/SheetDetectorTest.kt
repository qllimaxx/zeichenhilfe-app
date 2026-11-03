package de.benni.zeichenhilfe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Blatt-Erkennung mit erzeugten Testbildern (480 × 360 bzw. 360 × 480 Pixel, wie das verkleinerte Kamerabild).
 * Gibt je Fall aus: gefunden ja/nein, größte Eckenabweichung in Pixeln, Rechenzeit.
 */
class SheetDetectorTest {

    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }

    /** Testbild: Untergrund + Blatt (4×4-Überabtastung für weiche Kanten) + Extras. */
    private class Img(val w: Int, val h: Int, bg: Int) {
        val v = FloatArray(w * h) { bg.toFloat() }
        fun quad(c: FloatArray, value: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                var cov = 0
                for (sy in 0 until 4) for (sx in 0 until 4) if (inside(c, x + (sx + 0.5f) / 4, y + (sy + 0.5f) / 4)) cov++
                if (cov > 0) v[y * w + x] = v[y * w + x] * (1 - cov / 16f) + value * cov / 16f
            }
        }
        fun gradient(dx: Float, dy: Float) { for (y in 0 until h) for (x in 0 until w) v[y * w + x] += dx * (x - w / 2f) / w + dy * (y - h / 2f) / h }
        fun noise(sigma: Float, seed: Long) { val r = Random(seed); for (i in v.indices) v[i] += (r.nextGaussian() * sigma).toFloat() }
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, value: Int) {
            for (y in 0 until h) for (x in 0 until w) {
                val px = x + 0.5f; val py = y + 0.5f
                val dx = x1 - x0; val dy = y1 - y0; val t = (((px - x0) * dx + (py - y0) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
                if (hypot(px - (x0 + t * dx), py - (y0 + t * dy)) < width / 2) v[y * w + x] = value.toFloat()
            }
        }
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, value: Int) {
            for (y in 0 until h) for (x in 0 until w) { val a = (x + 0.5f - cx) / rx; val b = (y + 0.5f - cy) / ry; if (a * a + b * b <= 1f) v[y * w + x] = value.toFloat() }
        }
        /** Unschärfe (unscharfes Kamerabild): Kastenfilter mit Radius r, zweimal. */
        fun defocus(r: Int) {
            repeat(2) {
                val t = v.copyOf()
                for (y in 0 until h) for (x in 0 until w) { var s = 0f; var c = 0; for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { s += t[y * w + xx]; c++ } }; v[y * w + x] = s / c }
                val u = v.copyOf()
                for (y in 0 until h) for (x in 0 until w) { var s = 0f; var c = 0; for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { s += u[yy * w + x]; c++ } }; v[y * w + x] = s / c }
            }
        }
        fun bytes() = ByteArray(w * h) { min(255f, max(0f, v[it])).toInt().toByte() }
        companion object {
            fun inside(c: FloatArray, x: Float, y: Float): Boolean {
                var pos = 0; var neg = 0
                for (i in 0 until 4) {
                    val ax = c[2 * i]; val ay = c[2 * i + 1]; val bx = c[(2 * i + 2) % 8]; val by = c[(2 * i + 3) % 8]
                    val cr = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                    if (cr > 0) pos++ else if (cr < 0) neg++
                }
                return pos == 0 || neg == 0
            }
        }
    }

    private fun rotRect(cx: Float, cy: Float, w: Float, h: Float, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val p = listOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2)
        return FloatArray(8) { i -> val (x, y) = p[i / 2]; if (i % 2 == 0) cx + c * x - s * y else cy + s * x + c * y }
    }

    /** Größte Abweichung zwischen erwarteten und gefundenen Ecken (Zuordnung: nächste Ecke). */
    private fun err(expected: FloatArray, got: FloatArray): Float {
        var e = 0f
        for (i in 0 until 4) {
            var best = Float.MAX_VALUE
            for (j in 0 until 4) best = min(best, hypot(expected[2 * i] - got[2 * j], expected[2 * i + 1] - got[2 * j + 1]))
            e = max(e, best)
        }
        return e
    }

    private fun run(name: String, img: Img, expected: FloatArray?, maxErr: Float = 1.5f): SheetDetector.Result {
        val d = SheetDetector()
        d.detect(img.bytes(), img.w, img.h)          // Aufwärmen (JIT), damit die Zeit realistischer ist
        val r = d.detect(img.bytes(), img.w, img.h)
        val e = if (r.found && expected != null) err(expected, r.corners!!) else Float.NaN
        println(String.format("%-38s gefunden=%-4s Ecken-Abweichung=%5s px  Zeit=%5.1f ms  %s", name, if (r.found) "ja" else "nein",
            if (e.isNaN()) "–" else String.format("%.2f", e), r.ms, r.reason))
        if (expected != null) {
            check(r.found, "$name: nicht gefunden (${r.reason})")
            check(e <= maxErr, "$name: Abweichung $e px")
            // Reihenfolge: im Uhrzeigersinn, Start links oben
            val c = r.corners!!
            var area = 0f
            for (i in 0 until 4) area += c[2 * i] * c[(2 * i + 3) % 8] - c[(2 * i + 2) % 8] * c[2 * i + 1]
            check(area > 0, "$name: Reihenfolge nicht im Uhrzeigersinn")
        } else check(!r.found, "$name: fälschlich gefunden")
        return r
    }

    @Test fun geradesBlatt() {
        val c = rotRect(240f, 180f, 280f, 198f, 0f)
        val i = Img(480, 360, 70); i.quad(c, 215); run("gerade", i, c)
    }

    @Test fun gedrehtesBlatt() {
        val c = rotRect(240f, 180f, 260f, 184f, 23f)
        val i = Img(480, 360, 70); i.quad(c, 215); run("gedreht 23°", i, c)
        val c2 = rotRect(180f, 240f, 200f, 283f, -40f)
        val i2 = Img(360, 480, 60); i2.quad(c2, 220); run("hoch, gedreht −40°", i2, c2)
    }

    @Test fun perspektivischSchraeg() {
        val c = floatArrayOf(150f, 70f, 330f, 64f, 400f, 300f, 90f, 310f)     // oben schmaler (Trapez)
        val i = Img(480, 360, 80); i.quad(c, 210); run("perspektivisch schräg", i, c)
    }

    @Test fun rauschenUndHelligkeitsverlauf() {
        val c = rotRect(250f, 170f, 270f, 190f, 8f)
        val i = Img(480, 360, 75); i.quad(c, 205); i.gradient(60f, 30f); i.noise(8f, 1); run("Rauschen σ8 + Verlauf 60", i, c, 2.0f)
    }

    @Test fun zeichenstricheAufDemBlatt() {
        val c = rotRect(240f, 180f, 280f, 198f, 5f)
        val i = Img(480, 360, 70); i.quad(c, 215)
        i.line(150f, 150f, 330f, 130f, 2f, 60); i.line(160f, 230f, 250f, 120f, 1.5f, 50); i.line(250f, 120f, 320f, 240f, 1.5f, 50)
        i.ellipse(300f, 160f, 18f, 18f, 90)
        i.noise(4f, 2); run("Zeichenstriche auf dem Blatt", i, c)
    }

    @Test fun handUeberEinerKante() {
        val c = rotRect(240f, 170f, 280f, 198f, 3f)
        val i = Img(480, 360, 70); i.quad(c, 215)
        i.ellipse(260f, 275f, 45f, 60f, 95)      // Hand über der unteren Kante, ragt nach außen
        i.line(260f, 300f, 330f, 360f, 40f, 95)  // Arm bis zum Bildrand
        i.noise(4f, 3); run("Hand über der unteren Kante", i, c, 2.0f)
    }

    @Test fun hellerUntergrundNichtErkannt() {
        val c = rotRect(240f, 180f, 280f, 198f, 5f)
        val i = Img(480, 360, 200); i.quad(c, 222); i.noise(5f, 4); run("heller Untergrund", i, null)
    }

    @Test fun blattTeilweiseAusDemBildNichtErkannt() {
        val c = rotRect(400f, 180f, 280f, 198f, 5f)    // rechte Hälfte außerhalb
        val i = Img(480, 360, 70); i.quad(c, 215); i.noise(4f, 5); run("Blatt teilweise außerhalb", i, null)
    }

    @Test fun lilaSchneidematteMitRaster() {
        // Schneidematte ≈ mittleres Grau im Y-Kanal, feine hellere Rasterlinien
        val c = rotRect(240f, 180f, 270f, 190f, -6f)
        val i = Img(480, 360, 105)
        var x = 6f; while (x < 480) { i.line(x, 0f, x, 360f, 1f, 135); x += 24f }
        var y = 6f; while (y < 360) { i.line(0f, y, 480f, y, 1f, 135); y += 24f }
        i.quad(c, 225); i.noise(5f, 6); run("Schneidematte mit Raster (nachgebildet)", i, c, 2.0f)
    }

    @Test fun unscharfUndLichtfleckAmRand() {
        val c = rotRect(250f, 185f, 270f, 190f, 11f)
        val i = Img(480, 360, 70); i.quad(c, 215); i.defocus(2); i.noise(4f, 10); run("unscharf (Kastenfilter r=2, 2×)", i, c, 2.0f)
        val i2 = Img(480, 360, 70); i2.quad(c, 215); i2.ellipse(0f, 0f, 90f, 70f, 240); i2.noise(4f, 11); run("heller Lichtfleck in der Bildecke", i2, c, 2.0f)
    }

    @Test fun stabilitaetUeberMehrereBilder() {
        val t = SheetTracker()
        val base = rotRect(240f, 180f, 280f, 198f, 5f)
        val r = Random(7)
        for (k in 0 until 5) t.add(FloatArray(8) { base[it] + (r.nextGaussian() * 0.8).toFloat() })
        println(String.format("Stabilität: Zittern %.2f px, stabil=%s", t.jitterPx, t.stable))
        check(t.stable && t.jitterPx < 4f, "ruhige Ecken = stabil")
        t.add(FloatArray(8) { base[it] + if (it == 0) 9f else 0f })
        check(!t.stable, "eine Ecke springt 9 px → nicht stabil")
        t.add(null); t.add(null); t.add(null)
        check(!t.stable, "3 Fehlschläge → verloren")
    }

    // ---------- Ende-zu-Ende: Kamera schräg über dem Tisch, Blatt 21 × 29,7 cm ----------
    private fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        return floatArrayOf(f / aspect, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, (far + near) / (near - far), -1f, 0f, 0f, 2f * far * near / (near - far), 0f)
    }
    private fun lookAt(e: FloatArray, c: FloatArray): FloatArray {
        var fx = c[0] - e[0]; var fy = c[1] - e[1]; var fz = c[2] - e[2]
        val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // up = (0, 0, −1): Bild-oben zeigt vom Betrachter weg (Handy über dem Blatt)
        val upx = 0f; val upy = 0f; val upz = -1f
        var sx = fy * upz - fz * upy; var sy = fz * upx - fx * upz; var sz = fx * upy - fy * upx
        val sl = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl
        val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx
        return floatArrayOf(sx, ux, -fx, 0f, sy, uy, -fy, 0f, sz, uz, -fz, 0f,
            -(sx * e[0] + sy * e[1] + sz * e[2]), -(ux * e[0] + uy * e[1] + uz * e[2]), (fx * e[0] + fy * e[1] + fz * e[2]), 1f)
    }

    @Test fun endeZuEndeBlattgroesseInZentimetern() {
        val w = 360; val h = 480
        val vp = Mat4.mul(perspective(62f, w.toFloat() / h, 0.03f, 50f), lookAt(floatArrayOf(0.02f, 0.42f, 0.12f), floatArrayOf(0f, 0f, 0f)))
        val inv = Mat4.invert(vp)!!
        // Blatt A4 auf dem Tisch (y = 0), um 12° gedreht
        val a = Math.toRadians(12.0); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        fun onSheet(x: Float, z: Float): Boolean { val u = ca * x + sa * z; val v = -sa * x + ca * z; return kotlin.math.abs(u) <= 0.105f && kotlin.math.abs(v) <= 0.1485f }
        val img = Img(w, h, 70)
        val o = floatArrayOf(0f, 0f, 0f); val n = floatArrayOf(0f, 1f, 0f)
        for (y in 0 until h) for (x in 0 until w) {
            var cov = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val p = RayPlane.intersect(inv, x + (sx + 0.5f) / 3, y + (sy + 0.5f) / 3, w, h, o, n)
                if (p != null && onSheet(p[0], p[2])) cov++
            }
            img.v[y * w + x] = 70f + (215f - 70f) * cov / 9f
        }
        img.noise(4f, 9)
        val r = SheetDetector().detect(img.bytes(), w, h)
        check(r.found, "Ende-zu-Ende: nicht gefunden (${r.reason})")
        val c = r.corners!!
        val world = FloatArray(12)
        for (i in 0 until 4) { val p = RayPlane.intersect(inv, c[2 * i], c[2 * i + 1], w, h, o, n)!!; System.arraycopy(p, 0, world, 3 * i, 3) }
        val s = Sheet.fromCorners(world, n)!!
        val eShort = (s.short - 0.210f) * 1000f; val eLong = (s.long - 0.297f) * 1000f
        println(String.format("Ende-zu-Ende A4: erkannt %s (Soll 21,0 × 29,7 cm), Abweichung %.1f mm / %.1f mm, Mitte (%.1f; %.1f) mm",
            s.sizeText(), eShort, eLong, s.center[0] * 1000f, s.center[2] * 1000f))
        check(kotlin.math.abs(eShort) < 3f && kotlin.math.abs(eLong) < 3f, "Größe auf ±3 mm")
        // lange Achse liegt entlang der langen Blattseite (Richtung (−sin12°, cos12°) in x-z bzw. Gegenrichtung)
        val dotLong = kotlin.math.abs(s.xAxis[0] * (-sa) + s.xAxis[2] * ca)
        check(dotLong > 0.999f, "lange Achse richtig ($dotLong)")
    }
}
