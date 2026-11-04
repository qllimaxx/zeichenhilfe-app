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
    val long: Float, val short: Float,
    /** ab 0.8: Helligkeit außen/innen an der Blattkante beim Einpassen (≤ 0 = unbekannt). */
    val refRatio: Float = -1f
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
                 val inliers: Int, val viewDeg: Float, val reason: String,
                 /** ab 0.8: ganzes Blatt sicher gesehen (alle 4 Kanten weitgehend im Bild und passend) */
                 val full: Boolean = false)

    private const val SAMPLES = 48          // Stichproben je Kante

    /** Bilinear aus Graustufen (Zeilenabstand [stride]); Pixel i hat seine Mitte bei i + 0,5. Außerhalb = −1. */
    private fun px(g: ByteArray, w: Int, h: Int, stride: Int, x0in: Float, y0in: Float): Float {
        val x = x0in - 0.5f; val y = y0in - 0.5f
        if (x < 0f || y < 0f || x > w - 1.001f || y > h - 1.001f) return -1f
        val x0 = x.toInt(); val y0 = y.toInt(); val fx = x - x0; val fy = y - y0
        val i = y0 * stride + x0
        val a = g[i].toInt() and 255; val b = g[i + 1].toInt() and 255; val c = g[i + stride].toInt() and 255; val d = g[i + stride + 1].toInt() and 255
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Abstand zum Bildrand, ab dem nicht mehr gesucht wird (der Bildrand ist nie eine Blattkante). */
    private const val BORDER = 6f

    /** Kantenpunkte + je Kante die Zahl der Stichproben, die überhaupt im Bild lagen. */
    class Points(val pts: ArrayList<FloatArray>, val visible: IntArray)

    /**
     * Kantenpunkte entlang der vorhergesagten Rechteck-Kanten suchen: senkrecht zur Kante ±[range] Pixel nach dem
     * stärksten Übergang hell (innen) → dunkel (außen). Verworfen werden: Stellen am Bildrand und – wenn [refRatio]
     * bekannt ist – Übergänge, deren Helligkeit außen/innen nicht zum Blatt passt (Hand, Stift, Spiralbindung).
     */
    fun findEdgePoints(g: ByteArray, w: Int, h: Int, stride: Int, c: FloatArray, range: Float, minContrast: Float, refRatio: Float = -1f): Points {
        val pts = ArrayList<FloatArray>(); val vis = IntArray(4)
        val mx = (c[0] + c[2] + c[4] + c[6]) / 4; val my = (c[1] + c[3] + c[5] + c[7]) / 4
        fun inImg(x: Float, y: Float) = x >= BORDER && y >= BORDER && x <= w - BORDER && y <= h - BORDER
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
                if (!inImg(qx, qy)) continue
                vis[e]++
                fun side(s: Float, d: Float): Float {
                    var v = 0f
                    for (o in -1..1) {
                        val x = qx + nx * (s + d) + tx * o * 1.5f; val y = qy + ny * (s + d) + ty * o * 1.5f
                        if (!inImg(x, y)) return -1f
                        v += px(g, w, h, stride, x, y)
                    }
                    return v / 3f
                }
                fun resp(s: Float): Float { val a = side(s, -gap); val b = side(s, gap); return if (a < 0 || b < 0) -1f else a - b }
                var best = 0f; var bestS = 0f; var s = -range
                while (s <= range) { val d = resp(s); if (d > best) { best = d; bestS = s }; s += step }
                if (best < minContrast) continue
                if (refRatio > 0f) {
                    val inside = side(bestS, -gap - 2f); val outside = side(bestS, gap + 2f)
                    if (inside <= 0f || outside < 0f || abs(outside / inside - refRatio) > 0.12f) continue
                }
                // Feinlage per Parabel durch die Nachbarn
                val dm = resp(bestS - step); val dp = resp(bestS + step)
                val den = dm - 2 * best + dp
                val off = if (dm >= 0 && dp >= 0 && abs(den) > 1e-3f) (0.5f * (dm - dp) / den).coerceIn(-0.5f, 0.5f) * step else 0f
                val sf = bestS + off
                pts.add(floatArrayOf(qx + nx * sf, qy + ny * sf, e.toFloat()))
            }
        }
        return Points(pts, vis)
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
    private fun fit(m: SheetModel, pts0: List<FloatArray>, prior: FloatArray, vis: IntArray): FloatArray? {
        var p = prior.copyOf()
        var pts = pts0
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
            if (it == 3) { huber = 1.5f; pts = outermost(m, pts, p) ?: return null }
            if (abs(d[0]) < 1e-5 && abs(d[1]) < 1e-5 && abs(d[2]) < 1e-5 && it > 4) break
        }
        val c = m.corners(p[0], p[1], p[2]) ?: return null
        val cs = Array(3) { j -> val q = p.copyOf(); q[j] += eps[j]; m.corners(q[0], q[1], q[2]) ?: return null }
        var ss = 0.0; var n = 0; val perEdge = IntArray(4)
        val ata = Array(3) { DoubleArray(3) }
        for (pt in pts) {
            val e = pt[2].toInt(); val r = dist(c, e, pt[0], pt[1])
            if (abs(r) <= 2.5f) {
                ss += r * r; n++; perEdge[e]++
                val jr = DoubleArray(3) { j -> ((dist(cs[j], e, pt[0], pt[1]) - r) / eps[j]).toDouble() }
                for (a in 0 until 3) for (b in 0 until 3) ata[a][b] += jr[a] * jr[b]
            }
        }
        if (n < 6) return null
        // Kante zählt, wenn genug ihrer SICHTBAREN Stichproben passen (Teilansicht: vordere Kante darf fehlen)
        val counted = (0 until 4).filter { perEdge[it] >= 8 && perEdge[it] >= 0.15f * vis[it] }
        val full = counted.size == 4 && (0 until 4).all { vis[it] >= 0.8f * SAMPLES }
        val rms = sqrt(ss / n)
        // Unsicherheit der Lage (Kovarianz ≈ rms² · (JᵀJ)⁻¹): rutscht das Rechteck in einer Richtung „frei“, wird verworfen
        val inv = invert3(ata) ?: return null
        val sdX = sqrt(max(0.0, inv[0][0])) * max(rms, 0.3); val sdZ = sqrt(max(0.0, inv[1][1])) * max(rms, 0.3); val sdT = sqrt(max(0.0, inv[2][2])) * max(rms, 0.3)
        return floatArrayOf(p[0], p[1], p[2], rms.toFloat(), n.toFloat(), counted.size.toFloat(), if (full) 1f else 0f,
            sdX.toFloat(), sdZ.toFloat(), sdT.toFloat())
    }

    /**
     * Je Kante nur die äußerste dichte Gruppe von Kantenpunkten behalten: Die echte Blattkante ist der äußerste
     * Übergang hell → dunkel; Spiralbindung, Stift und Hand erzeugen Übergänge WEITER INNEN.
     */
    private fun outermost(m: SheetModel, pts: List<FloatArray>, p: FloatArray): List<FloatArray>? {
        val c = m.corners(p[0], p[1], p[2]) ?: return null
        val mx = (c[0] + c[2] + c[4] + c[6]) / 4; val my = (c[1] + c[3] + c[5] + c[7]) / 4
        val out = ArrayList<FloatArray>()
        for (e in 0 until 4) {
            val sign = if (dist(c, e, mx, my) > 0) -1f else 1f            // außen positiv
            val ep = pts.filter { it[2].toInt() == e }
            if (ep.size < 6) { out.addAll(ep); continue }
            val r = ep.map { sign * dist(c, e, it[0], it[1]) }
            val sorted = r.sorted()
            val outer = sorted[(sorted.size * 0.9f).toInt().coerceAtMost(sorted.size - 1)]
            val keep = ep.filterIndexed { i, _ -> r[i] >= outer - 1.0f && r[i] <= outer + 2.5f }
            out.addAll(if (keep.size >= max(6, (0.15f * ep.size).toInt())) keep else ep)
        }
        return out
    }

    private fun invert3(a: Array<DoubleArray>): Array<DoubleArray>? {
        val r = Array(3) { DoubleArray(3) }
        for (k in 0 until 3) { val e = DoubleArray(3); e[k] = 1.0; val col = solve3(a, e) ?: return null; for (i in 0 until 3) r[i][k] = col[i] }
        return r
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
        for ((pass, range) in listOf(0 to 60f * scale, 1 to 8f * scale)) {
            val c = if (pass == 0) c0 else (m.corners(p[0], p[1], p[2]) ?: break)
            val fp = findEdgePoints(g, w, h, stride, c, max(4f, range), 18f, m.refRatio)
            if (fp.pts.size < 20) return Result(false, 0f, 0f, 0f, 0f, 0, fp.pts.size, view, "zu wenig Kanten gefunden")
            res = fit(m, fp.pts, p, fp.visible) ?: return Result(false, 0f, 0f, 0f, 0f, 0, fp.pts.size, view, "Einpassen fehlgeschlagen")
            nPts = fp.pts.size
            p = floatArrayOf(res[0], res[1], res[2])
        }
        val r = res!!
        // Toleranz in Bildpixeln; bei flachem Blick (bis ca. 70°) großzügiger
        val rmsMax = (1.2f + 1.5f * min(1.2f, view / 60f)) * max(0.5f, scale * 1.5f)
        val edges = r[5].toInt()
        val reason = when {
            edges < 3 -> "zu wenig sichtbare Kanten ($edges)"
            r[4] < 0.45f * nPts -> "Kanten passen nicht zum Blatt"
            r[3] > rmsMax -> "passt schlecht (" + String.format(java.util.Locale.GERMANY, "%.1f", r[3]) + " px)"
            r[7] > 0.0015f || r[8] > 0.0015f || r[9] > 0.005f -> "Lage nicht eindeutig"
            hypot(r[0] - prior[0], r[1] - prior[1]) > 0.08f -> "Sprung zu groß"
            else -> ""
        }
        return Result(reason.isEmpty(), r[0], r[1], r[2], r[3], edges, r[4].toInt(), view, reason, r[6] > 0.5f)
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
