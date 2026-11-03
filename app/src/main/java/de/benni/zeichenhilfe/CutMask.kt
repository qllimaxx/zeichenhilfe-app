package de.benni.zeichenhilfe

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Freistellen (ab 0.5) – reiner Kotlin-Code ohne Android, per Unit-Test prüfbar.
 * Autor: Benjamin Dormoros
 *
 * Die Maske liegt in Koordinaten des ORIGINALS (Raster w × h, gleiches Seitenverhältnis wie das Original;
 * 255 = sichtbar, 0 = freigestellt/durchsichtig). Dadurch bleibt sie bei Zuschneiden, Drehen, Spiegeln und
 * Feindrehung automatisch an der richtigen Stelle (die Bearbeitung tastet sie über dieselbe Abbildung ab).
 * Masken sind unveränderlich: jede Änderung erzeugt eine neue MaskData (für Rückgängig/Wiederholen).
 */
class MaskData(val w: Int, val h: Int, val a: ByteArray) {
    fun at(x: Int, y: Int): Int = a[y * w + x].toInt() and 255

    /** Bilinear an normierter Stelle (u, v ∈ 0..1), Ergebnis 0..1; außerhalb des Originals = 0. */
    fun sample(u: Float, v: Float): Float {
        if (u < 0f || v < 0f || u > 1f || v > 1f) return 0f
        val x = min(w - 1f, max(0f, u * w - 0.5f)); val y = min(h - 1f, max(0f, v * h - 0.5f))
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val fx = x - x0; val fy = y - y0
        val r = (at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy
        return r / 255f
    }

    companion object {
        fun full(w: Int, h: Int) = MaskData(w, h, ByteArray(w * h) { -1 })
    }
}

object CutMask {

    /**
     * Lasso: Polygon (normiert, Original-Koordinaten, x0,y0,x1,y1,…) wird geschlossen und gefüllt.
     * Füllregel „ungleich null“ (nonzero): Schleifen und sich kreuzende Linien zählen alle als innen,
     * auch wenn sich eine Schleife mit sich selbst überlappt. Kantenglättung über 4 Teilzeilen je Pixelzeile.
     * [keepInside] = true: außerhalb wird durchsichtig; false: innen wird durchsichtig.
     */
    fun lasso(base: MaskData?, w: Int, h: Int, poly: FloatArray, keepInside: Boolean): MaskData {
        val cov = polygonCoverage(w, h, poly)
        val src = base ?: MaskData.full(w, h)
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val m = src.a[i].toInt() and 255
            val c = cov[i]
            val k = if (keepInside) c else 1f - c
            out[i] = (m * k + 0.5f).toInt().toByte()
        }
        return MaskData(w, h, out)
    }

    /** Anteil jedes Pixels innerhalb des Polygons (0..1), Polygon normiert. */
    fun polygonCoverage(w: Int, h: Int, poly: FloatArray): FloatArray {
        val n = poly.size / 2
        val cov = FloatArray(w * h)
        if (n < 3) return cov
        val px = FloatArray(n) { poly[2 * it] * w }; val py = FloatArray(n) { poly[2 * it + 1] * h }
        val sub = 4
        val xs = FloatArray(n); val dir = IntArray(n)
        val order = IntArray(n)
        var minY = py.minOrNull()!!; var maxY = py.maxOrNull()!!
        val y0 = max(0, floor(minY).toInt()); val y1 = min(h - 1, ceil(maxY).toInt())
        for (y in y0..y1) for (s in 0 until sub) {
            val sy = y + (s + 0.5f) / sub
            // Schnittpunkte der Teilzeile mit allen Kanten (mit Richtung für die Windungszahl)
            var k = 0
            for (i in 0 until n) {
                val ax = px[i]; val ay = py[i]; val bx = px[(i + 1) % n]; val by = py[(i + 1) % n]
                if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) {
                    xs[k] = ax + (sy - ay) / (by - ay) * (bx - ax); dir[k] = if (by > ay) 1 else -1; k++
                }
            }
            if (k < 2) continue
            for (i in 0 until k) order[i] = i
            // nach x sortieren (Einfügesortierung, k ist klein)
            for (i in 1 until k) { val t = order[i]; var j = i - 1; while (j >= 0 && xs[order[j]] > xs[t]) { order[j + 1] = order[j]; j-- }; order[j + 1] = t }
            var wind = 0
            for (q in 0 until k - 1) {
                wind += dir[order[q]]
                if (wind != 0) addSpan(cov, w, y, xs[order[q]], xs[order[q + 1]], 1f / sub)
            }
        }
        for (i in cov.indices) if (cov[i] > 1f) cov[i] = 1f
        return cov
    }

    /** Waagrechte Strecke [xa, xb) in Zeile y mit Gewicht wt addieren (Randpixel anteilig). */
    private fun addSpan(cov: FloatArray, w: Int, y: Int, xa: Float, xb: Float, wt: Float) {
        val a = max(0f, xa); val b = min(w.toFloat(), xb)
        if (b <= a) return
        val ia = a.toInt(); val ib = min(w - 1, b.toInt())
        val row = y * w
        if (ia == ib) { cov[row + ia] += (b - a) * wt; return }
        cov[row + ia] += (ia + 1 - a) * wt
        for (x in ia + 1 until ib) cov[row + x] += wt
        if (ib < w) cov[row + ib] += (b - ib) * wt
    }

    /**
     * Pinselstrich (Radieren oder Zurückholen). Ein Strich deckt jede Stelle höchstens einmal ab
     * (Maximum der Abdeckung), deshalb wird ein weicher Rand nicht durch überlappende Tupfer immer dunkler.
     */
    class Stroke(val base: MaskData, val erase: Boolean, val hard: Boolean) {
        val w = base.w; val h = base.h
        val cov = FloatArray(w * h)
        val out = base.a.copyOf()
        /** Geänderter Bereich seit dem letzten Abholen (x0, y0, x1, y1 in Pixeln, einschließlich). */
        private var dx0 = Int.MAX_VALUE; private var dy0 = Int.MAX_VALUE; private var dx1 = -1; private var dy1 = -1
        private var lastX = Float.NaN; private var lastY = Float.NaN

        /** Bis zum Punkt (x, y in Masken-Pixeln) malen, Radius r in Masken-Pixeln. */
        fun to(x: Float, y: Float, r: Float) {
            if (lastX.isNaN()) dab(x, y, r)
            else {
                val d = hypot(x - lastX, y - lastY)
                val step = max(0.5f, r * 0.25f)
                val n = max(1, ceil(d / step).toInt())
                for (i in 1..n) dab(lastX + (x - lastX) * i / n, lastY + (y - lastY) * i / n, r)
            }
            lastX = x; lastY = y
        }

        private fun dab(cx: Float, cy: Float, r: Float) {
            val rr = max(0.5f, r)
            val x0 = max(0, floor(cx - rr - 1).toInt()); val x1 = min(w - 1, ceil(cx + rr + 1).toInt())
            val y0 = max(0, floor(cy - rr - 1).toInt()); val y1 = min(h - 1, ceil(cy + rr + 1).toInt())
            if (x0 > x1 || y0 > y1) return
            for (y in y0..y1) for (x in x0..x1) {
                val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
                val c = brush(d, rr, hard)
                if (c <= 0f) continue
                val i = y * w + x
                if (c > cov[i]) {
                    cov[i] = c
                    val m = base.a[i].toInt() and 255
                    val v = if (erase) m * (1f - c) else m + (255 - m) * c
                    out[i] = min(255, max(0, (v + 0.5f).toInt())).toByte()
                }
            }
            dx0 = min(dx0, x0); dy0 = min(dy0, y0); dx1 = max(dx1, x1); dy1 = max(dy1, y1)
        }

        fun takeDirty(): IntArray? {
            if (dx1 < 0) return null
            val r = intArrayOf(dx0, dy0, dx1, dy1)
            dx0 = Int.MAX_VALUE; dy0 = Int.MAX_VALUE; dx1 = -1; dy1 = -1
            return r
        }

        fun result() = MaskData(w, h, out.copyOf())
    }

    /** Abdeckung des Pinsels im Abstand d: hart = scharfer Rand (1 px geglättet), weich = Rand ab 50 % des Radius weich. */
    fun brush(d: Float, r: Float, hard: Boolean): Float =
        if (hard) min(1f, max(0f, r + 0.5f - d))
        else {
            val inner = r * 0.5f
            if (d <= inner) 1f else if (d >= r) 0f else { val t = (d - inner) / (r - inner); 1f - t * t * (3 - 2 * t) }
        }

    /** Masken-Raster zum Original: lange Seite [maxLong] (gleiches Seitenverhältnis). */
    fun gridSize(origW: Int, origH: Int, maxLong: Int = 1024): IntArray {
        val k = min(1f, maxLong.toFloat() / max(origW, origH))
        return intArrayOf(max(1, (origW * k).roundToInt()), max(1, (origH * k).roundToInt()))
    }

    /** Anteil sichtbarer Fläche (0..1) – für Diagnose/Tests. */
    fun visibleFraction(m: MaskData): Float { var s = 0L; for (b in m.a) s += b.toInt() and 255; return s / (255f * m.a.size) }
}

/** Rückgängig / Wiederholen für Masken (höchstens [limit] Schritte). null = keine Maske (alles sichtbar). */
class MaskHistory(start: MaskData?, private val limit: Int = 25) {
    private val past = ArrayList<MaskData?>()
    private val future = ArrayList<MaskData?>()
    var current: MaskData? = start; private set

    fun push(m: MaskData?) {
        past.add(current); if (past.size > limit) past.removeAt(0)
        current = m; future.clear()
    }
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun undo(): MaskData? { if (past.isEmpty()) return current; future.add(current); current = past.removeAt(past.size - 1); return current }
    fun redo(): MaskData? { if (future.isEmpty()) return current; past.add(current); current = future.removeAt(future.size - 1); return current }
}
