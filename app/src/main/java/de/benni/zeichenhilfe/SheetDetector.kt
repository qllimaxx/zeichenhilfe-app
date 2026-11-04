package de.benni.zeichenhilfe

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Blatt-Erkennung (ab 0.3) – reiner Kotlin-Code ohne Android, damit per Unit-Test prüfbar.
 * Autor: Benjamin Dormoros
 *
 * Sucht ein helles Viereck (Zeichenblatt) auf dunklerem Untergrund in einem Graustufenbild:
 *  1. leicht glätten (3×3), Schwellwert nach Otsu, Kontrast prüfen
 *  2. größte zusammenhängende helle Fläche, die den Bildrand NICHT berührt (Blatt muss ganz im Bild sein)
 *  3. konvexe Hülle → Viereck mit größter Fläche (4 Ecken)
 *  4. Kanten verfeinern: entlang jeder Seite die stärkste Hell-dunkel-Kante suchen, Gerade ausgleichen
 *     (Ausreißer z. B. durch eine Hand verwerfen), Ecken = Schnittpunkte der Geraden
 *  5. Plausibilität: konvex, Mindestgröße, Winkel, Form, Füllgrad, Kontrast innen/außen
 * Ecken kommen im Uhrzeigersinn (im Bild, y nach unten), beginnend mit der Ecke links oben.
 */
class SheetDetector {

    class Result(
        val found: Boolean,
        /** x0, y0, x1, y1, x2, y2, x3, y3 in Pixeln des Eingabebilds (nur wenn found). */
        val corners: FloatArray?,
        /** Grund, wenn nicht gefunden (deutsch, für die Diagnose). */
        val reason: String,
        val ms: Double
    )

    /** Mindestanteil der Blattfläche am Bild. */
    var minAreaFrac = 0.04f
    /** Mindest-Helligkeitsunterschied Blatt – Untergrund (0..255). */
    var minContrast = 35
    /** ab 0.8: nach detect() – Ränder (1 links, 2 oben, 4 rechts, 8 unten), die die größte helle Fläche berührt,
     *  wenn kein Blatt ganz im Bild war (0 = keine). Und Helligkeit innen/außen des gefundenen Blatts. */
    var lastBorderMask = 0; private set
    var lastInside = 0f; private set
    var lastOutside = 0f; private set

    fun detect(gray: ByteArray, w: Int, h: Int): Result {
        val t0 = System.nanoTime()
        val r = run(gray, w, h)
        val ms = (System.nanoTime() - t0) / 1e6
        return Result(r.first != null, r.first, r.second, ms)
    }

    private fun run(src: ByteArray, w: Int, h: Int): Pair<FloatArray?, String> {
        if (w < 32 || h < 32 || src.size < w * h) return null to "Bild zu klein"
        val g = blur3(src, w, h)

        // ---- 1. Schwellwert (Otsu) ----
        val hist = IntArray(256)
        for (v in g) hist[v]++
        val n = w * h
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i.toDouble() * hist[i]
        var wB = 0.0; var sumB = 0.0; var best = -1.0; var thr = 128
        for (i in 0 until 256) {
            wB += hist[i]; if (wB == 0.0) continue
            val wF = n - wB; if (wF == 0.0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = i }
        }
        var cLo = 0.0; var sLo = 0.0; var cHi = 0.0; var sHi = 0.0
        for (i in 0 until 256) if (i <= thr) { cLo += hist[i]; sLo += i.toDouble() * hist[i] } else { cHi += hist[i]; sHi += i.toDouble() * hist[i] }
        if (cLo == 0.0 || cHi == 0.0) return null to "kein Kontrast"
        val mLo = sLo / cLo; val mHi = sHi / cHi
        if (mHi - mLo < minContrast) return null to "zu wenig Kontrast (${(mHi - mLo).roundToInt()})"

        // ---- 1b. ab 0.7: Schwellwert lokal (Kacheln 4 × 3, je Kachel Otsu, weich verbunden) ----
        // Bei schräger Sicht ist der hintere Teil oft dunkler; eine Kachel ohne klaren Kontrast nimmt den globalen Wert.
        val on = localThreshold(g, w, h, thr)
        // ---- 2. zusammenhängende helle Flächen ----
        val label = IntArray(n)            // 0 = nicht hell/unbesucht, >0 = Fläche
        val stack = IntArray(n)
        var bestLabel = 0; var bestArea = 0
        var borderArea = 0; var borderMask = 0
        lastBorderMask = 0
        var cur = 0
        for (start in 0 until n) {
            if (label[start] != 0 || !on[start]) continue
            cur++
            var sp = 0; stack[sp++] = start; label[start] = cur
            var area = 0; var border = false; var mask = 0
            while (sp > 0) {
                val p = stack[--sp]; area++
                val x = p % w; val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    border = true
                    if (x == 0) mask = mask or 1; if (y == 0) mask = mask or 2; if (x == w - 1) mask = mask or 4; if (y == h - 1) mask = mask or 8
                }
                if (x > 0) { val q = p - 1; if (label[q] == 0 && on[q]) { label[q] = cur; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (label[q] == 0 && on[q]) { label[q] = cur; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (label[q] == 0 && on[q]) { label[q] = cur; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (label[q] == 0 && on[q]) { label[q] = cur; stack[sp++] = q } }
            }
            if (!border && area > bestArea) { bestArea = area; bestLabel = cur }
            if (border && area > borderArea) { borderArea = area; borderMask = mask }
        }
        if (bestLabel == 0 || bestArea < minAreaFrac * n) {
            // Blatt ragt vermutlich aus dem Bild (größte helle Fläche berührt den Rand) – Ränder für einen Hinweis merken
            if (borderArea >= minAreaFrac * n && borderArea < 0.85f * n) lastBorderMask = borderMask
            return null to "kein helles Blatt ganz im Bild"
        }

        // ---- 3. Hülle und Viereck ----
        val pts = ArrayList<FloatArray>()
        for (y in 0 until h) {
            var xl = -1; var xr = -1
            val row = y * w
            for (x in 0 until w) if (label[row + x] == bestLabel) { if (xl < 0) xl = x; xr = x }
            if (xl >= 0) {
                pts.add(floatArrayOf(xl.toFloat(), y.toFloat())); pts.add(floatArrayOf(xl.toFloat(), y + 1f))
                pts.add(floatArrayOf(xr + 1f, y.toFloat())); pts.add(floatArrayOf(xr + 1f, y + 1f))
            }
        }
        val hull = convexHull(pts)
        if (hull.size < 4) return null to "Umriss zu klein"
        val hullArea = polyArea(hull)
        val quad = maxQuad(hull) ?: return null to "kein Viereck"
        val quadArea = polyArea(quad)
        if (quadArea < 0.90 * hullArea) return null to "Umriss ist kein Viereck"
        if (bestArea < 0.80 * quadArea) return null to "Fläche nicht viereckig gefüllt"

        // ---- 4. Kanten verfeinern ----
        val refined = refine(g, w, h, quad, mHi - mLo)
        val q = order(refined)

        // ---- 5. Plausibilität ----
        val why = plausible(q, w, h)
        if (why != null) return null to why
        val ci = insideMean(g, w, h, q); val co = outsideMean(g, w, h, q)
        if (ci - co < minContrast) return null to "Blatt hebt sich nicht ab (${(ci - co).roundToInt()})"
        lastInside = ci.toFloat(); lastOutside = co.toFloat()
        return FloatArray(8) { i -> q[i / 2][i % 2] } to ""
    }

    // ------------------------------------------------------------------------------------
    /** Lokaler Schwellwert: Otsu je Kachel (4 × 3), bilinear zwischen den Kachelmitten; schwacher Kontrast → global. */
    private fun localThreshold(g: IntArray, w: Int, h: Int, global: Int): BooleanArray {
        val tx = 4; val ty = 3
        val t = Array(ty) { FloatArray(tx) }
        for (j in 0 until ty) for (i in 0 until tx) {
            val x0 = i * w / tx; val x1 = (i + 1) * w / tx; val y0 = j * h / ty; val y1 = (j + 1) * h / ty
            val hist = IntArray(256); var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { hist[g[y * w + x]]++; n++ }
            var sumAll = 0.0; for (k in 0 until 256) sumAll += k.toDouble() * hist[k]
            var wB = 0.0; var sumB = 0.0; var best = -1.0; var th = global
            for (k in 0 until 256) {
                wB += hist[k]; if (wB == 0.0) continue
                val wF = n - wB; if (wF == 0.0) break
                sumB += k.toDouble() * hist[k]
                val mB = sumB / wB; val mF = (sumAll - sumB) / wF
                val bt = wB * wF * (mB - mF) * (mB - mF)
                if (bt > best) { best = bt; th = k }
            }
            // Kontrast und Anteile der Kachel prüfen: nur bei klarer Zweiteilung den lokalen Wert nehmen
            var cl = 0.0; var sl = 0.0; var ch = 0.0; var shh = 0.0
            for (k in 0 until 256) if (k <= th) { cl += hist[k]; sl += k.toDouble() * hist[k] } else { ch += hist[k]; shh += k.toDouble() * hist[k] }
            val good = cl > 0.1 * n && ch > 0.1 * n && (shh / ch - sl / cl) >= minContrast
            // nie weit vom globalen Wert weg (sonst würde eine einfarbige Fläche zerfallen)
            t[j][i] = if (good) min(global + 40, max(global - 40, th)).toFloat() else global.toFloat()
        }
        val on = BooleanArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / h * ty - 0.5f).coerceIn(0f, ty - 1f); val j0 = fy.toInt(); val j1 = min(ty - 1, j0 + 1); val ay = fy - j0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / w * tx - 0.5f).coerceIn(0f, tx - 1f); val i0 = fx.toInt(); val i1 = min(tx - 1, i0 + 1); val ax = fx - i0
                val th = (t[j0][i0] * (1 - ax) + t[j0][i1] * ax) * (1 - ay) + (t[j1][i0] * (1 - ax) + t[j1][i1] * ax) * ay
                on[y * w + x] = g[y * w + x] > th
            }
        }
        return on
    }

    private fun blur3(s: ByteArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(w * h); val out = IntArray(w * h)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                val a = s[r + max(0, x - 1)].toInt() and 255; val b = s[r + x].toInt() and 255; val c = s[r + min(w - 1, x + 1)].toInt() and 255
                tmp[r + x] = a + b + c
            }
        }
        for (y in 0 until h) {
            val r0 = max(0, y - 1) * w; val r1 = y * w; val r2 = min(h - 1, y + 1) * w
            for (x in 0 until w) out[r1 + x] = (tmp[r0 + x] + tmp[r1 + x] + tmp[r2 + x] + 4) / 9
        }
        return out
    }

    private fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    /** Konvexe Hülle (monotone Kette). */
    private fun convexHull(p: List<FloatArray>): List<FloatArray> {
        val s = p.sortedWith(compareBy<FloatArray>({ it[0] }, { it[1] }))
        if (s.size < 3) return s
        val lower = ArrayList<FloatArray>()
        for (pt in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0f) lower.removeAt(lower.size - 1); lower.add(pt) }
        val upper = ArrayList<FloatArray>()
        for (pt in s.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0f) upper.removeAt(upper.size - 1); upper.add(pt) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun polyArea(p: List<FloatArray>): Double {
        var a = 0.0
        for (i in p.indices) { val u = p[i]; val v = p[(i + 1) % p.size]; a += u[0].toDouble() * v[1] - v[0].toDouble() * u[1] }
        return abs(a) / 2
    }

    /** Viereck mit (näherungsweise) größter Fläche aus den Hüllpunkten. */
    private fun maxQuad(hull: List<FloatArray>): List<FloatArray>? {
        val m = hull.size
        // Startwerte: weitestes Punktpaar und die beiden am weitesten entfernten Punkte zu beiden Seiten
        var i0 = 0; var i2 = 0; var dBest = -1f
        for (a in 0 until m) for (b in a + 1 until m) {
            val d = hypot(hull[a][0] - hull[b][0], hull[a][1] - hull[b][1]); if (d > dBest) { dBest = d; i0 = a; i2 = b }
        }
        fun farthest(from: Int, to: Int): Int {
            var bi = -1; var bd = -1f; var k = (from + 1) % m
            while (k != to) { val d = abs(cross(hull[i0], hull[i2], hull[k])); if (d > bd) { bd = d; bi = k }; k = (k + 1) % m }
            return bi
        }
        var i1 = farthest(i0, i2); var i3 = farthest(i2, i0)
        if (i1 < 0 || i3 < 0) return null
        val idx = intArrayOf(i0, i1, i2, i3)
        fun area4(a: Int, b: Int, c: Int, d: Int) = polyArea(listOf(hull[a], hull[b], hull[c], hull[d]))
        repeat(10) {
            var changed = false
            for (j in 0 until 4) {
                val prev = idx[(j + 3) % 4]; val next = idx[(j + 1) % 4]
                var bestK = idx[j]; var bestA = area4(idx[0], idx[1], idx[2], idx[3])
                var k = (prev + 1) % m
                while (k != next) {
                    val t = idx.copyOf(); t[j] = k
                    val a = area4(t[0], t[1], t[2], t[3])
                    if (a > bestA + 1e-6) { bestA = a; bestK = k }
                    k = (k + 1) % m
                }
                if (bestK != idx[j]) { idx[j] = bestK; changed = true }
            }
            if (!changed) return@repeat
        }
        return idx.map { hull[it] }
    }

    private fun sample(g: IntArray, w: Int, h: Int, x: Float, y: Float): Float {
        val xc = min(w - 1.001f, max(0f, x - 0.5f)); val yc = min(h - 1.001f, max(0f, y - 0.5f))
        val x0 = xc.toInt(); val y0 = yc.toInt(); val fx = xc - x0; val fy = yc - y0
        val a = g[y0 * w + x0]; val b = g[y0 * w + x0 + 1]; val c = g[(y0 + 1) * w + x0]; val d = g[(y0 + 1) * w + x0 + 1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Kanten an der stärksten Hell-dunkel-Stelle neu ausgleichen; Ecken = Schnittpunkte. */
    private fun refine(g: IntArray, w: Int, h: Int, quad: List<FloatArray>, contrast: Double): List<FloatArray> {
        val cx = quad.sumOf { it[0].toDouble() }.toFloat() / 4; val cy = quad.sumOf { it[1].toDouble() }.toFloat() / 4
        val lines = ArrayList<FloatArray?>()       // je Seite: Punkt (px, py) + Richtung (dx, dy)
        for (s in 0 until 4) {
            val a = quad[s]; val b = quad[(s + 1) % 4]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            if (len < 8f) { lines.add(null); continue }
            val dx = (b[0] - a[0]) / len; val dy = (b[1] - a[1]) / len
            var nx = -dy; var ny = dx
            val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
            if ((mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny }     // Normale nach außen
            val samples = min(60, max(10, (len / 3).toInt()))
            val range = max(4f, min(8f, len * 0.02f))
            val px = ArrayList<Float>(); val py = ArrayList<Float>()
            for (k in 0 until samples) {
                val t = 0.1f + 0.8f * k / (samples - 1)
                val qx = a[0] + (b[0] - a[0]) * t; val qy = a[1] + (b[1] - a[1]) * t
                // Profil entlang der Normalen; gesucht: stärkster Abfall hell (innen) -> dunkel (außen)
                var bestS = 0f; var bestD = 0f; var dm = 0f; var dp = 0f
                var sPos = -range
                while (sPos <= range) {
                    val d = sample(g, w, h, qx + nx * (sPos - 1), qy + ny * (sPos - 1)) - sample(g, w, h, qx + nx * (sPos + 1), qy + ny * (sPos + 1))
                    if (d > bestD) {
                        bestD = d; bestS = sPos
                        dm = sample(g, w, h, qx + nx * (sPos - 1.5f), qy + ny * (sPos - 1.5f)) - sample(g, w, h, qx + nx * (sPos + 0.5f), qy + ny * (sPos + 0.5f))
                        dp = sample(g, w, h, qx + nx * (sPos - 0.5f), qy + ny * (sPos - 0.5f)) - sample(g, w, h, qx + nx * (sPos + 1.5f), qy + ny * (sPos + 1.5f))
                    }
                    sPos += 0.5f
                }
                if (bestD < contrast * 0.5) continue          // keine klare Kante (z. B. Hand darüber)
                val den = dm - 2 * bestD + dp
                val off = if (abs(den) > 1e-3f) min(0.5f, max(-0.5f, 0.5f * (dm - dp) / den)) * 0.5f else 0f
                val sf = bestS + off
                px.add(qx + nx * sf); py.add(qy + ny * sf)
            }
            lines.add(fitLine(px, py))
        }
        val out = ArrayList<FloatArray>()
        for (s in 0 until 4) {
            val l1 = lines[(s + 3) % 4]; val l2 = lines[s]
            val p = if (l1 != null && l2 != null) intersect(l1, l2) else null
            out.add(if (p != null && hypot(p[0] - quad[s][0], p[1] - quad[s][1]) < 8f) p else quad[s])
        }
        return out
    }

    /** Gerade durch Punkte (kleinste Abstandsquadrate), zweimal Ausreißer (> 1 px) entfernen. */
    private fun fitLine(xs: List<Float>, ys: List<Float>): FloatArray? {
        var ix = xs.indices.toMutableList()
        var line: FloatArray? = null
        repeat(3) { round ->
            if (ix.size < 5) return line
            val mx = ix.sumOf { xs[it].toDouble() } / ix.size; val my = ix.sumOf { ys[it].toDouble() } / ix.size
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            for (i in ix) { val a = xs[i] - mx; val b = ys[i] - my; sxx += a * a; sxy += a * b; syy += b * b }
            val ang = 0.5 * atan2(2 * sxy, sxx - syy)
            val dx = kotlin.math.cos(ang); val dy = kotlin.math.sin(ang)
            line = floatArrayOf(mx.toFloat(), my.toFloat(), dx.toFloat(), dy.toFloat())
            if (round < 2) {
                val keep = ix.filter { i -> abs(-(xs[i] - mx) * dy + (ys[i] - my) * dx) <= 1.0 }
                if (keep.size >= 5) ix = keep.toMutableList()
            }
        }
        return line
    }

    private fun intersect(a: FloatArray, b: FloatArray): FloatArray? {
        val den = a[2] * b[3] - a[3] * b[2]
        if (abs(den) < 1e-6f) return null
        val t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / den
        return floatArrayOf(a[0] + a[2] * t, a[1] + a[3] * t)
    }

    /** Uhrzeigersinn (y nach unten), Start = Ecke mit kleinstem x + y. */
    private fun order(q: List<FloatArray>): List<FloatArray> {
        val cx = q.sumOf { it[0].toDouble() } / 4; val cy = q.sumOf { it[1].toDouble() } / 4
        val s = q.sortedBy { atan2(it[1] - cy, it[0] - cx) }
        val st = s.indices.minByOrNull { s[it][0] + s[it][1] } ?: 0
        return List(4) { s[(st + it) % 4] }
    }

    private fun plausible(q: List<FloatArray>, w: Int, h: Int): String? {
        for (p in q) if (p[0] < 1.5f || p[1] < 1.5f || p[0] > w - 1.5f || p[1] > h - 1.5f) return "Ecke außerhalb des Bildes"
        var sign = 0
        for (i in 0 until 4) {
            val c = cross(q[i], q[(i + 1) % 4], q[(i + 2) % 4])
            val sg = if (c > 0) 1 else -1
            if (sign == 0) sign = sg else if (sg != sign) return "nicht konvex"
        }
        if (polyArea(q) < minAreaFrac * w * h) return "zu klein"
        val len = FloatArray(4) { i -> hypot(q[(i + 1) % 4][0] - q[i][0], q[(i + 1) % 4][1] - q[i][1]) }
        for (i in 0 until 4) {
            val a = q[(i + 3) % 4]; val b = q[i]; val c = q[(i + 1) % 4]
            val ux = a[0] - b[0]; val uy = a[1] - b[1]; val vx = c[0] - b[0]; val vy = c[1] - b[1]
            val ang = Math.toDegrees(acos(((ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))).toDouble().coerceIn(-1.0, 1.0)))
            if (ang < 25 || ang > 155) return "Winkel unplausibel (${ang.roundToInt()}°)"
        }
        val r02 = len[0] / len[2]; val r13 = len[1] / len[3]
        if (r02 < 0.25f || r02 > 4f || r13 < 0.25f || r13 > 4f) return "Form unplausibel"
        val a = (len[0] + len[2]) / 2; val b = (len[1] + len[3]) / 2
        if (min(a, b) / max(a, b) < 0.15f) return "zu schmal"
        return null
    }

    private fun insideMean(g: IntArray, w: Int, h: Int, q: List<FloatArray>): Double {
        // Mittelwert in einem kleineren Viereck (70 % zur Mitte hin)
        val cx = q.sumOf { it[0].toDouble() }.toFloat() / 4; val cy = q.sumOf { it[1].toDouble() }.toFloat() / 4
        var s = 0.0; var c = 0
        for (i in 0 until 4) for (k in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]; val t = (k + 0.5f) / 4
            val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
            for (f in floatArrayOf(0.3f, 0.6f, 0.85f)) { s += sample(g, w, h, cx + (x - cx) * f, cy + (y - cy) * f); c++ }
        }
        return s / c
    }

    private fun outsideMean(g: IntArray, w: Int, h: Int, q: List<FloatArray>): Double {
        val cx = q.sumOf { it[0].toDouble() }.toFloat() / 4; val cy = q.sumOf { it[1].toDouble() }.toFloat() / 4
        val vals = ArrayList<Float>()
        for (i in 0 until 4) for (k in 0 until 6) {
            val a = q[i]; val b = q[(i + 1) % 4]; val t = (k + 0.5f) / 6
            val x = a[0] + (b[0] - a[0]) * t; val y = a[1] + (b[1] - a[1]) * t
            val dx = x - cx; val dy = y - cy; val d = sqrt(dx * dx + dy * dy)
            val ox = x + dx / d * 6f; val oy = y + dy / d * 6f
            if (ox >= 0 && oy >= 0 && ox < w && oy < h) vals.add(sample(g, w, h, ox, oy))
        }
        if (vals.isEmpty()) return 255.0
        vals.sort()
        return vals[vals.size / 2].toDouble()     // Median: robust gegen eine Hand außen an der Kante
    }

    companion object {
        /**
         * Graustufen aus einer Bildebene (z. B. Y-Kanal) bilinear auf höchstens [targetW] Pixel Breite verkleinern.
         * Liefert das Bild, Breite, Höhe und den Faktor (Quellpixel je Zielpixel).
         */
        fun downscale(src: ByteArray, srcW: Int, srcH: Int, rowStride: Int, targetW: Int): Scaled {
            if (srcW <= targetW) {
                val out = ByteArray(srcW * srcH)
                for (y in 0 until srcH) System.arraycopy(src, y * rowStride, out, y * srcW, srcW)
                return Scaled(out, srcW, srcH, 1f)
            }
            val f = srcW.toFloat() / targetW
            val tw = targetW; val th = max(1, (srcH / f).toInt())
            val out = ByteArray(tw * th)
            for (y in 0 until th) {
                val sy = min(srcH - 1.001f, max(0f, (y + 0.5f) * f - 0.5f)); val y0 = sy.toInt(); val fy = sy - y0
                for (x in 0 until tw) {
                    val sx = min(srcW - 1.001f, max(0f, (x + 0.5f) * f - 0.5f)); val x0 = sx.toInt(); val fx = sx - x0
                    val i = y0 * rowStride + x0
                    val a = src[i].toInt() and 255; val b = src[i + 1].toInt() and 255
                    val c = src[i + rowStride].toInt() and 255; val d = src[i + rowStride + 1].toInt() and 255
                    out[y * tw + x] = ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy + 0.5f).toInt().toByte()
                }
            }
            return Scaled(out, tw, th, f)
        }
    }

    class Scaled(val gray: ByteArray, val w: Int, val h: Int, val factor: Float)
}

/**
 * Stabilität der Erkennung über mehrere Bilder: „stabil“, wenn die letzten [need] Erkennungen gelungen sind
 * und keine Ecke um mehr als [maxJitterPx] vom Mittel abweicht. Nach [lostAfter] Fehlschlägen gilt das Blatt
 * als verloren. Zu jeder Erkennung kann ein Begleitwert (z. B. die Ecken auf dem Tisch) gemittelt werden.
 */
class SheetTracker(val need: Int = 3, val window: Int = 5, val maxJitterPx: Float = 4f, val lostAfter: Int = 3) {
    private val hist = ArrayDeque<FloatArray>()
    private val payload = ArrayDeque<FloatArray?>()
    private var misses = 0
    var jitterPx = 0f; private set
    var stable = false; private set

    fun reset() { hist.clear(); payload.clear(); misses = 0; stable = false; jitterPx = 0f }

    fun add(corners: FloatArray?, extra: FloatArray? = null) {
        if (corners == null) {
            misses++
            if (misses >= lostAfter) reset()
            return
        }
        misses = 0
        // Sprung (anderes Blatt / grober Fehler): Verlauf neu beginnen
        if (hist.isNotEmpty()) {
            val m = mean()
            var d = 0f
            for (i in 0 until 4) d = max(d, hypot(corners[2 * i] - m[2 * i], corners[2 * i + 1] - m[2 * i + 1]))
            if (d > maxJitterPx * 4) { hist.clear(); payload.clear() }
        }
        hist.addLast(corners); payload.addLast(extra)
        while (hist.size > window) { hist.removeFirst(); payload.removeFirst() }
        val m = mean()
        var j = 0f
        for (c in hist) for (i in 0 until 4) j = max(j, hypot(c[2 * i] - m[2 * i], c[2 * i + 1] - m[2 * i + 1]))
        jitterPx = j
        stable = hist.size >= need && j <= maxJitterPx
    }

    fun mean(): FloatArray {
        val m = FloatArray(8)
        for (c in hist) for (i in 0 until 8) m[i] += c[i] / hist.size
        return m
    }

    /** Mittel der Begleitwerte (nur vorhandene). */
    fun meanExtra(): FloatArray? {
        val l = payload.filterNotNull(); if (l.isEmpty()) return null
        val m = FloatArray(l[0].size)
        for (c in l) for (i in m.indices) m[i] += c[i] / l.size
        return m
    }
}
