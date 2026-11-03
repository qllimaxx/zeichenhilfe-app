package de.benni.zeichenhilfe

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Ab 0.7: Bekanntes Blatt-Rechteck in das Kamerabild einpassen (reiner Kotlin-Code, per Unit-Test prüfbar).
 * Autor: Benjamin Dormoros
 *
 * Statt „das gemessene Viereck muss ein Rechteck sein“ wird das bekannte Rechteck (L × S) auf der Fläche des Ankers
 * so verschoben und gedreht (cx, cz, t – wie in SheetCorrection), dass seine projizierten Kanten am besten zu den
 * Hell-dunkel-Kanten im Bild (volle Auflösung) passen. Fehler werden in Bildpixeln gemessen, also automatisch
 * passend zum Blickwinkel. Verdeckte Kantenstücke (Hand, Stift, Spiralbindung) werden als Ausreißer verworfen;
 * 3 sichtbare Kanten (z. B. bei einer verdeckten Ecke: 2 ganze + 2 halbe Kanten) reichen.
 */

/** Kamera-/Flächenmodell zum Zeitpunkt der Aufnahme. */
class SheetModel(
    val anchorM: FloatArray,   // Anker → Welt (4×4, spaltenweise)
    val vp: FloatArray,        // Projektion · Ansicht
    val viewW: Int, val viewH: Int,
    val aff: FloatArray,       // Ansicht → Bildpixel: x = a0 + a1·u + a2·v, y = a3 + a4·u + a5·v
    val long: Float, val short: Float
) {
    private val invVp = Mat4.invert(vp)
    private val invAnchor = Mat4.invert(anchorM)

    /** Punkt auf der Fläche (Anker-Koordinaten x, z) → Bildpixel; null wenn hinter der Kamera. */
    fun toImage(x: Float, z: Float, out: FloatArray, o: Int): Boolean {
        val w = Mat4.transform(anchorM, x, 0f, z, 1f)
        val c = Mat4.transform(vp, w[0], w[1], w[2], 1f)
        if (c[3] <= 1e-6f) return false
        val u = ScreenNdc.toPxX(c[0] / c[3], viewW); val v = ScreenNdc.toPxY(c[1] / c[3], viewH)
        out[o] = aff[0] + aff[1] * u + aff[2] * v; out[o + 1] = aff[3] + aff[4] * u + aff[5] * v
        return true
    }

    /** Bildpixel → Punkt auf der Fläche (Anker-Koordinaten x, z), null wenn kein Schnitt. */
    fun toPlane(x: Float, y: Float): FloatArray? {
        val inv = invVp ?: return null; val ia = invAnchor ?: return null
        // Umkehrung der affinen Abbildung Ansicht → Bild
        val det = aff[1] * aff[5] - aff[2] * aff[4]
        if (abs(det) < 1e-9f) return null
        val dx = x - aff[0]; val dy = y - aff[3]
        val u = (aff[5] * dx - aff[2] * dy) / det; val v = (-aff[4] * dx + aff[1] * dy) / det
        val o = floatArrayOf(anchorM[12], anchorM[13], anchorM[14]); val n = floatArrayOf(anchorM[4], anchorM[5], anchorM[6])
        val wpt = RayPlane.intersect(inv, u, v, viewW, viewH, o, n) ?: return null
        val l = Mat4.transform(ia, wpt[0], wpt[1], wpt[2], 1f)
        return floatArrayOf(l[0], l[2])
    }

    /** Ecken des Rechtecks bei Lage (cx, cz, t) in Bildpixeln (8 Werte), null wenn nicht abbildbar. */
    fun corners(cx: Float, cz: Float, t: Float): FloatArray? {
        val co = cos(t); val s = sin(t); val out = FloatArray(8)
        val p = arrayOf(floatArrayOf(-long / 2, -short / 2), floatArrayOf(long / 2, -short / 2), floatArrayOf(long / 2, short / 2), floatArrayOf(-long / 2, short / 2))
        for (i in 0 until 4) {
            val x = co * p[i][0] + s * p[i][1] + cx; val z = -s * p[i][0] + co * p[i][1] + cz
            if (!toImage(x, z, out, 2 * i)) return null
        }
        return out
    }

    /** Winkel zwischen Blickrichtung (zur Blattmitte) und Flächennormale in Grad (0 = senkrecht von oben). */
    fun viewAngleDeg(cx: Float, cz: Float): Float {
        val inv = invVp ?: return 0f
        val p = FloatArray(2); if (!toImage(cx, cz, p, 0)) return 90f
        val c = Mat4.transform(anchorM, cx, 0f, cz, 1f)
        val near = Mat4.transform(inv, 0f, 0f, -1f, 1f)        // Kameraort ≈ Punkt auf der nahen Ebene in Bildmitte
        val e = floatArrayOf(near[0] / near[3], near[1] / near[3], near[2] / near[3])
        val d = floatArrayOf(c[0] - e[0], c[1] - e[1], c[2] - e[2]); val dl = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        val n = floatArrayOf(anchorM[4], anchorM[5], anchorM[6]); val nl = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        val cosv = abs(d[0] * n[0] + d[1] * n[1] + d[2] * n[2]) / (dl * nl)
        return Math.toDegrees(acos(cosv.toDouble().coerceIn(0.0, 1.0))).toFloat()
    }
}

object EdgeTracker {

    class Result(val ok: Boolean, val cx: Float, val cz: Float, val t: Float, val rmsPx: Float, val edges: Int,
                 val inliers: Int, val viewDeg: Float, val reason: String)

    private const val SAMPLES = 48          // Stichproben je Kante
    private const val MIN_EDGE_FRAC = 0.25f // Kante zählt, wenn so viele Stichproben passen

    /** Bilinear aus Graustufen (Zeilenabstand [stride]); Pixel i hat seine Mitte bei i + 0,5. Außerhalb = −1. */
    private fun px(g: ByteArray, w: Int, h: Int, stride: Int, x0in: Float, y0in: Float): Float {
        val x = x0in - 0.5f; val y = y0in - 0.5f
        if (x < 0f || y < 0f || x > w - 1.001f || y > h - 1.001f) return -1f
        val x0 = x.toInt(); val y0 = y.toInt(); val fx = x - x0; val fy = y - y0
        val i = y0 * stride + x0
        val a = g[i].toInt() and 255; val b = g[i + 1].toInt() and 255; val c = g[i + stride].toInt() and 255; val d = g[i + stride + 1].toInt() and 255
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /**
     * Kantenpunkte entlang der vorhergesagten Rechteck-Kanten suchen: senkrecht zur Kante ±[range] Pixel nach dem
     * stärksten Übergang hell (innen) → dunkel (außen). Liefert Liste (x, y, Kante).
     */
    fun findEdgePoints(g: ByteArray, w: Int, h: Int, stride: Int, c: FloatArray, range: Float, minContrast: Float): ArrayList<FloatArray> {
        val pts = ArrayList<FloatArray>()
        val mx = (c[0] + c[2] + c[4] + c[6]) / 4; val my = (c[1] + c[3] + c[5] + c[7]) / 4
        for (e in 0 until 4) {
            val ax = c[2 * e]; val ay = c[2 * e + 1]; val bx = c[(2 * e + 2) % 8]; val by = c[(2 * e + 3) % 8]
            val len = hypot(bx - ax, by - ay); if (len < 10f) continue
            val tx = (bx - ax) / len; val ty = (by - ay) / len
            var nx = -ty; var ny = tx
            if (((ax + bx) / 2 - mx) * nx + ((ay + by) / 2 - my) * ny < 0) { nx = -nx; ny = -ny }   // nach außen
            val step = 0.5f; val gap = max(1.5f, range * 0.08f)
            for (k in 0 until SAMPLES) {
                val f = 0.07f + 0.86f * k / (SAMPLES - 1)
                val qx = ax + (bx - ax) * f; val qy = ay + (by - ay) * f
                fun resp(s: Float): Float {
                    var inside = 0f; var outside = 0f
                    for (o in -1..1) {
                        val ox = tx * o * 1.5f; val oy = ty * o * 1.5f
                        val a = px(g, w, h, stride, qx + nx * (s - gap) + ox, qy + ny * (s - gap) + oy)
                        val b = px(g, w, h, stride, qx + nx * (s + gap) + ox, qy + ny * (s + gap) + oy)
                        if (a < 0 || b < 0) return -1f
                        inside += a; outside += b
                    }
                    return (inside - outside) / 3f
                }
                var best = 0f; var bestS = 0f; var s = -range
                while (s <= range) { val d = resp(s); if (d > best) { best = d; bestS = s }; s += step }
                if (best >= minContrast) {
                    // Feinlage per Parabel durch die Nachbarn
                    val dm = resp(bestS - step); val dp = resp(bestS + step)
                    val den = dm - 2 * best + dp
                    val off = if (dm >= 0 && dp >= 0 && abs(den) > 1e-3f) (0.5f * (dm - dp) / den).coerceIn(-0.5f, 0.5f) * step else 0f
                    val sf = bestS + off
                    pts.add(floatArrayOf(qx + nx * sf, qy + ny * sf, e.toFloat()))
                }
            }
        }
        return pts
    }

    /** Vorzeichenbehafteter Abstand des Punkts zur Geraden durch die Ecken e, e+1. */
    private fun dist(c: FloatArray, e: Int, x: Float, y: Float): Float {
        val ax = c[2 * e]; val ay = c[2 * e + 1]; val bx = c[(2 * e + 2) % 8]; val by = c[(2 * e + 3) % 8]
        val l = hypot(bx - ax, by - ay); if (l < 1e-6f) return 0f
        return ((bx - ax) * (y - ay) - (by - ay) * (x - ax)) / l
    }

    /**
     * Rechteck an Kantenpunkte anpassen (Gauß-Newton mit Huber-Gewichten), Start = [prior] (cx, cz, t).
     * Liefert (cx, cz, t, rms, Inlier, Kanten) oder null.
     */
    private fun fit(m: SheetModel, pts: List<FloatArray>, prior: FloatArray): FloatArray? {
        var p = prior.copyOf()
        val eps = floatArrayOf(0.0005f, 0.0005f, 0.001f)
        var huber = 3f
        for (it in 0 until 10) {
            val c0 = m.corners(p[0], p[1], p[2]) ?: return null
            val cs = Array(3) { j -> val q = p.copyOf(); q[j] += eps[j]; m.corners(q[0], q[1], q[2]) ?: return null }
            val ata = Array(3) { DoubleArray(3) }; val atb = DoubleArray(3)
            for (pt in pts) {
                val e = pt[2].toInt()
                val r = dist(c0, e, pt[0], pt[1])
                val w = if (abs(r) <= huber) 1.0 else huber / abs(r).toDouble()
                if (abs(r) > huber * 4) continue
                val jr = DoubleArray(3) { j -> ((dist(cs[j], e, pt[0], pt[1]) - r) / eps[j]).toDouble() }
                for (a in 0 until 3) { atb[a] -= w * jr[a] * r; for (b in 0 until 3) ata[a][b] += w * jr[a] * jr[b] }
            }
            for (a in 0 until 3) ata[a][a] += 1e-3 + ata[a][a] * 1e-3
            val d = solve3(ata, atb) ?: return null
            p[0] += d[0].toFloat(); p[1] += d[1].toFloat(); p[2] += d[2].toFloat()
            if (it == 3) huber = 1.5f
            if (abs(d[0]) < 1e-5 && abs(d[1]) < 1e-5 && abs(d[2]) < 1e-5 && it > 4) break
        }
        val c = m.corners(p[0], p[1], p[2]) ?: return null
        var ss = 0.0; var n = 0; val perEdge = IntArray(4)
        for (pt in pts) { val r = dist(c, pt[2].toInt(), pt[0], pt[1]); if (abs(r) <= 2.5f) { ss += r * r; n++; perEdge[pt[2].toInt()]++ } }
        if (n == 0) return null
        val edges = perEdge.count { it >= MIN_EDGE_FRAC * SAMPLES }
        val adj2 = (0 until 4).any { perEdge[it] >= 0.6f * SAMPLES && perEdge[(it + 1) % 4] >= 0.6f * SAMPLES }
        return floatArrayOf(p[0], p[1], p[2], sqrt(ss / n).toFloat(), n.toFloat(), edges.toFloat(), if (adj2) 1f else 0f)
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (c in 0 until 3) {
            var piv = c; for (r in c + 1 until 3) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-12) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in 0 until 3) if (r != c) { val f = m[r][c] / m[c][c]; for (k in c until 4) m[r][k] -= f * m[c][k] }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    /**
     * Blatt mit bekannter Größe suchen, ausgehend von [prior] (cx, cz, t in Anker-Koordinaten).
     * [g] = Graustufen (volle Auflösung), [scale] = Bildpixel pro 1920er-Pixel (für Suchweite/Toleranzen).
     */
    fun track(g: ByteArray, w: Int, h: Int, stride: Int, m: SheetModel, prior: FloatArray, scale: Float = w / 1920f): Result {
        val c0 = m.corners(prior[0], prior[1], prior[2]) ?: return Result(false, 0f, 0f, 0f, 0f, 0, 0, 0f, "Blatt nicht abbildbar")
        val view = m.viewAngleDeg(prior[0], prior[1])
        // 1. grob (große Suchweite), 2. fein (kleine Suchweite um die verbesserte Lage)
        var p = prior
        var res: FloatArray? = null
        var nPts = 0
        for ((pass, range) in listOf(0 to 40f * scale, 1 to 8f * scale)) {
            val c = if (pass == 0) c0 else (m.corners(p[0], p[1], p[2]) ?: break)
            val pts = findEdgePoints(g, w, h, stride, c, max(4f, range), 18f)
            if (pts.size < 20) return Result(false, 0f, 0f, 0f, 0f, 0, pts.size, view, "zu wenig Kanten gefunden")
            res = fit(m, pts, p) ?: return Result(false, 0f, 0f, 0f, 0f, 0, pts.size, view, "Einpassen fehlgeschlagen")
            nPts = pts.size
            p = floatArrayOf(res[0], res[1], res[2])
        }
        val r = res!!
        // Toleranz in Bildpixeln; bei flachem Blick etwas großzügiger
        val rmsMax = (1.2f + 1.2f * min(1f, view / 60f)) * max(0.5f, scale * 1.5f)
        val edges = r[5].toInt()
        val reason = when {
            edges < 3 -> "zu wenig sichtbare Kanten ($edges)"
            r[4] < 0.45f * nPts -> "Kanten passen nicht zum Blatt"
            r[3] > rmsMax -> "passt schlecht (" + String.format(java.util.Locale.GERMANY, "%.1f", r[3]) + " px)"
            hypot(r[0] - prior[0], r[1] - prior[1]) > 0.08f -> "Sprung zu groß"
            else -> ""
        }
        return Result(reason.isEmpty(), r[0], r[1], r[2], r[3], edges, r[4].toInt(), view, reason)
    }

    /** Grobe Lage aus 4 Bildecken (z. B. der Blatt-Erkennung): Mitte und Richtung der langen Seite auf der Fläche. */
    fun coarseFromCorners(m: SheetModel, imgCorners: FloatArray): FloatArray? {
        val l = FloatArray(8)
        for (i in 0 until 4) { val q = m.toPlane(imgCorners[2 * i], imgCorners[2 * i + 1]) ?: return null; l[2 * i] = q[0]; l[2 * i + 1] = q[1] }
        val cx = (l[0] + l[2] + l[4] + l[6]) / 4; val cz = (l[1] + l[3] + l[5] + l[7]) / 4
        val s0 = hypot(l[2] - l[0], l[3] - l[1]) + hypot(l[4] - l[6], l[5] - l[7])
        val s1 = hypot(l[4] - l[2], l[5] - l[3]) + hypot(l[6] - l[0], l[7] - l[1])
        val (ax, az) = if (s0 >= s1) ((l[2] - l[0]) + (l[4] - l[6])) to ((l[3] - l[1]) + (l[5] - l[7]))
                       else ((l[4] - l[2]) + (l[6] - l[0])) to ((l[5] - l[3]) + (l[7] - l[1]))
        var t = atan2(-az, ax)
        while (t > PI / 2) t -= PI.toFloat(); while (t < -PI / 2) t += PI.toFloat()
        return floatArrayOf(cx, cz, t)
    }
}
