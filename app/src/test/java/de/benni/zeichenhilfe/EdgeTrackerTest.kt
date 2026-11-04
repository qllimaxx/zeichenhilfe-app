package de.benni.zeichenhilfe

import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** 0.7: bekanntes Rechteck schräg (45°/60°) einpassen, Spiralbindung, Hand über einer Ecke, Blatt-Erkennung schräg. */
class EdgeTrackerTest {
    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }
    private val L = 0.21f; private val S = 0.148f        // A5, Anker = Weltursprung, Fläche y = 0
    private val W = 960; private val H = 540              // halbe 1920×1080

    private fun perspective(fovYDeg: Float, aspect: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat(); val n = 0.03f; val fa = 50f
        return floatArrayOf(f / aspect, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, (fa + n) / (n - fa), -1f, 0f, 0f, 2f * fa * n / (n - fa), 0f)
    }
    private fun lookAt(e: FloatArray): FloatArray {
        var fx = -e[0]; var fy = -e[1]; var fz = -e[2]; val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        val ux0 = 0f; val uy0 = 1f; val uz0 = 0f
        var sx = fy * uz0 - fz * uy0; var sy = fz * ux0 - fx * uz0; var sz = fx * uy0 - fy * ux0
        val sl = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl
        val ux = sy * fz - sz * fy; val uy = sz * fx - sx * fz; val uz = sx * fy - sy * fx
        return floatArrayOf(sx, ux, -fx, 0f, sy, uy, -fy, 0f, sz, uz, -fz, 0f,
            -(sx * e[0] + sy * e[1] + sz * e[2]), -(ux * e[0] + uy * e[1] + uz * e[2]), (fx * e[0] + fy * e[1] + fz * e[2]), 1f)
    }
    /** Kamera im Abstand d unter dem Winkel [deg] zur Senkrechten (von vorne, +z). */
    private fun model(deg: Float, d: Float = 0.42f): SheetModel {
        val a = Math.toRadians(deg.toDouble())
        val vp = Mat4.mul(perspective(55f, W.toFloat() / H), lookAt(floatArrayOf(0f, (d * cos(a)).toFloat(), (d * sin(a)).toFloat() + 0.0001f)))
        return SheetModel(Mat4.identity(), vp, W, H, floatArrayOf(0f, 1f, 0f, 0f, 0f, 1f), L, S)
    }

    /** Bild rendern: Blatt bei Lage (cx, cz, t) hell auf dunklem Tisch, optional Spiralbindung und Hand. */
    private fun render(m: SheetModel, cx: Float, cz: Float, t: Float, spiral: Boolean, hand: Boolean, seed: Long, L: Float = this.L, S: Float = this.S): ByteArray {
        val inv = Mat4.invert(m.vp)!!; val r = Random(seed); val g = ByteArray(W * H)
        val co = cos(t); val s = sin(t)
        for (y in 0 until H) for (x in 0 until W) {
            var acc = 0f
            for (sy in 0 until 2) for (sx in 0 until 2) {
                val p = RayPlane.intersect(inv, x + 0.25f + sx * 0.5f, y + 0.25f + sy * 0.5f, W, H, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
                var v = 75f
                if (p != null) {
                    val dx = p[0] - cx; val dz = p[2] - cz
                    val u = co * dx - s * dz; val w = s * dx + co * dz          // in Blatt-Koordinaten
                    if (abs(u) <= L / 2 && abs(w) <= S / 2) v = 212f - 25f * (w / S + 0.5f)   // leichter Helligkeitsverlauf
                    if (spiral && abs(w + S / 2) < 0.006f) {                    // Ringe an der hinteren langen Kante
                        val ring = ((u + L) % 0.008f) - 0.004f
                        if (hypot(ring, w + S / 2) < 0.0032f) v = 45f
                    }
                    if (hand && hypot(u - L / 2, w - S / 2) < 0.035f) v = 110f       // Hand über der vorderen rechten Ecke
                }
                acc += v
            }
            g[y * W + x] = (acc / 4 + r.nextGaussian() * 4).toInt().coerceIn(0, 255).toByte()
        }
        return g
    }

    private fun case(name: String, deg: Float, spiral: Boolean, hand: Boolean, expectOk: Boolean = true) {
        val m = model(deg)
        val tx = 0.004f; val tz = -0.003f; val tt = Math.toRadians(1.2).toFloat()       // wahre Lage
        val g = render(m, tx, tz, tt, spiral, hand, deg.toLong())
        val r = EdgeTracker.track(g, W, H, W, m, floatArrayOf(0f, 0f, 0f))           // Start: alte Lage (Versatz 0)
        val e = hypot(r.cx - tx, r.cz - tz) * 1000; val et = Math.toDegrees((r.t - tt).toDouble())
        println(String.format("%-36s gültig=%s Fehler %.2f mm / %.2f°, Kanten %d, rms %.2f px, Blickwinkel %.0f° %s",
            name, if (r.ok) "ja" else "nein", e, et, r.edges, r.rmsPx, r.viewDeg, r.reason))
        if (expectOk) check(r.ok && e < 0.8 && abs(et) < 0.3, "$name: ${r.reason} Fehler $e mm $et°")
    }

    @Test fun schraegOhneStoerung() { case("schräg 45°", 45f, false, false); case("schräg 60°", 60f, false, false) }
    @Test fun spiralbindung() { case("60° + Spiralbindung", 60f, true, false) }
    @Test fun handUeberEcke() { case("45° + Hand über einer Ecke", 45f, false, true); case("60° + Spirale + Hand", 60f, true, true) }

    @Test fun keinBlattWirdVerworfen() {
        val m = model(50f)
        val g = ByteArray(W * H) { 80 }
        val r = EdgeTracker.track(g, W, H, W, m, floatArrayOf(0f, 0f, 0f))
        check(!r.ok, "ohne Blatt ungültig")
        // falsche Blattgröße (Blatt im Bild ist A6) → passt schlecht
        val g2 = render(m, 0f, 0f, 0f, false, false, 3, 0.148f, 0.105f)
        val r2 = EdgeTracker.track(g2, W, H, W, m, floatArrayOf(0f, 0f, 0f))
        println(String.format("falsche Größe: ok=%s cx=%.1f cz=%.1f mm Kanten %d rms %.2f Inlier %d", r2.ok, r2.cx*1000, r2.cz*1000, r2.edges, r2.rmsPx, r2.inliers))
        check(!r2.ok, "falsche Größe ungültig (${r2.reason})")
        println("Verworfen: kein Blatt („${r.reason}“), falsche Größe („${r2.reason}“)")
    }

    @Test fun blattErkennungSchraeg() {
        // Ausrichten: Erkennung im 480er-Bild bei 60° (stark trapezförmig), dann grobe Lage + Einpassen
        val m = model(60f)
        val g = render(m, 0.01f, 0f, 0.1f, true, false, 7)
        val sc = SheetDetector.downscale(g, W, H, W, 480)
        val d = SheetDetector().detect(sc.gray, sc.w, sc.h)
        check(d.found, "Blatt bei 60° gefunden (${d.reason})")
        val img = FloatArray(8) { d.corners!![it] * sc.factor }
        val coarse = EdgeTracker.coarseFromCorners(m, img)!!
        val r = EdgeTracker.track(g, W, H, W, m, coarse)
        check(r.ok && hypot(r.cx - 0.01f, r.cz) < 0.001f, "danach eingepasst (${r.reason})")
        println(String.format("Erkennung 60° + Spirale: gefunden, grob %.1f mm daneben, eingepasst %.2f mm", hypot(coarse[0] - 0.01f, coarse[1]) * 1000, hypot(r.cx - 0.01f, r.cz) * 1000))
    }

    // ---------- ab 0.8: Teilansicht (Zeichenhaltung), Absicherung gegen „Abflug“ ----------
    private val PW = 640; private val PH = 360
    private fun modelP(deg: Float, ratio: Float): SheetModel {
        // Zeichenhaltung: Handy niedrig VOR dem Block (12 cm hoch, 11 cm vor der Blattmitte), Blick [deg] zur Senkrechten
        val a = Math.toRadians(deg.toDouble())
        val e = floatArrayOf(0f, 0.12f, 0.11f)
        val t = floatArrayOf(0f, (0.12 - 0.3 * cos(a)).toFloat(), (0.11 - 0.3 * sin(a)).toFloat())
        val vp = Mat4.mul(perspective(50f, PW.toFloat() / PH), lookAtTarget(e, t))
        return SheetModel(Mat4.identity(), vp, PW, PH, floatArrayOf(0f, 1f, 0f, 0f, 0f, 1f), L, S, ratio)
    }
    private fun lookAtTarget(e: FloatArray, t: FloatArray): FloatArray {
        val shifted = lookAt(floatArrayOf(e[0] - t[0], e[1] - t[1], e[2] - t[2]))
        return Mat4.mul(shifted, floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -t[0], -t[1], -t[2], 1f))
    }
    private fun renderP(m: SheetModel, cx: Float, cz: Float, seed: Long, spiral: Boolean = true, hand: Boolean = true, pen: Boolean = true): ByteArray {
        val inv = Mat4.invert(m.vp)!!; val r = Random(seed); val g = ByteArray(PW * PH)
        for (y in 0 until PH) for (x in 0 until PW) {
            var acc = 0f
            for (sy in 0 until 2) for (sx in 0 until 2) {
                val p = RayPlane.intersect(inv, x + 0.25f + sx * 0.5f, y + 0.25f + sy * 0.5f, PW, PH, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
                var v = 75f
                if (p != null) {
                    val u = p[0] - cx; val w = p[2] - cz
                    if (abs(u) <= L / 2 && abs(w) <= S / 2) v = 205f
                    if (spiral && abs(w + S / 2) < 0.006f) { val ring = ((u + L) % 0.008f) - 0.004f; if (hypot(ring, w + S / 2) < 0.0032f) v = 45f }   // Spiralbindung hinten
                    if (hand && hypot(u - 0.07f, w - 0.02f) < 0.04f) v = 120f                        // Hand über der rechten Seitenkante
                    if (pen && abs(u + 0.03f - 0.4f * (w + S / 2)) < 0.0012f && w > -S / 2 - 0.01f) v = 40f     // Stift-Strich über die hintere Kante
                }
                acc += v
            }
            g[y * PW + x] = (acc / 4 + r.nextGaussian() * 4).toInt().coerceIn(0, 255).toByte()
        }
        return g
    }

    @Test fun teilansichtOhneSchleichendeVerschiebung() {
        for ((deg, tx, tz) in listOf(Triple(62f, 0f, 0f), Triple(68f, 0.004f, -0.002f))) {
            val m = modelP(deg, 75f / 205f)
            val c = m.corners(tx, tz, 0f)!!
            println("Ecken im Bild: " + c.joinToString { String.format("%.0f", it) })
            check(c[5] > PH && c[7] > PH, "Testaufbau: vordere Ecken liegen unten außerhalb des Bildes")
            val corr = SheetCorrection(L, S)
            var ok = 0; var reasons = ""
            for (f in 0 until 16) {
                val g = renderP(m, tx, tz, 100L + f)
                val r = EdgeTracker.track(g, PW, PH, PW, m, floatArrayOf(0f, 0f, 0f))   // Start immer bei der Lage beim Einpassen
                if (r.ok) { corr.measurePose(r.cx, r.cz, r.t, r.full); ok++ } else { corr.invalid(r.reason); reasons = r.reason }
                repeat(10) { corr.step(33f) }
            }
            val err = hypot(corr.appliedX - tx, corr.appliedZ - tz) * 1000
            println(String.format("Teilansicht %.0f°, Spirale + Hand + Stift: %d/16 gültig, angewendet %.2f / %.2f mm (wahr %.1f / %.1f), Fehler %.2f mm %s",
                deg, ok, corr.appliedX * 1000, corr.appliedZ * 1000, tx * 1000, tz * 1000, err, reasons))
            check(ok >= 8, "Teilansicht $deg°: zu wenige gültige Messungen ($reasons)")
            // Testbild nur 640 px breit: 1 px an der hinteren Kante ≈ 2 mm; in der App (1920 px) etwa ein Drittel davon
            check(err < 1.5, "Teilansicht $deg°: Fehler $err mm (keine schleichende Verschiebung)")
        }
    }
}
