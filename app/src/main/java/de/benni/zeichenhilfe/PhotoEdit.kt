package de.benni.zeichenhilfe

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Foto bearbeiten (ab 0.4) – reiner Kotlin-Code auf Pixel-Arrays (ARGB als Int), ohne Android,
 * damit alles per Unit-Test prüfbar ist. Nicht zerstörend: Das Original bleibt, gerechnet wird
 * immer neu aus Original + Einstellungen.
 * Autor: Benjamin Dormoros
 *
 * Reihenfolge: Original → 90°-Drehung → Spiegeln (waagrecht) → Feindrehung (mit Zoom, damit keine leeren
 * Ecken entstehen) → Zuschnitt → Ton (Helligkeit/Kontrast/Graustufen) → optional Linienbild.
 * Zuschnitt-Rahmen: normiert (0..1) im gedrehten Bild.
 */

enum class CropMode { FREE, ORIGINAL, SQUARE }

data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val w get() = r - l
    val h get() = b - t
    companion object { val FULL = CropRect() }
}

data class EditSettings(
    val rot90: Int = 0,               // 0..3, Vielfache von 90° im Uhrzeigersinn
    val flipH: Boolean = false,
    val fineDeg: Float = 0f,          // −45..45, positiv = im Uhrzeigersinn
    val crop: CropRect = CropRect.FULL,
    val cropMode: CropMode = CropMode.FREE,
    val brightness: Float = 0f,       // −1..1 (0 = unverändert)
    val contrast: Float = 0f,         // −1..1 (0 = unverändert)
    val gray: Boolean = false,
    val lines: Boolean = false,
    val sensitivity: Float = 0.5f,    // 0 = wenige, 1 = viele Linien
    val lineWidth: Int = 1,           // 0 fein, 1 mittel, 2 kräftig
    val transparent: Boolean = false, // nur Linien sichtbar
    val detail: Float = 0.5f,         // ab 0.5: 1 = viele Details, 0 = wenig (stärker geglättet, kurze Stücke weg)
    val mask: MaskData? = null        // ab 0.5: Freistellen-Maske in Original-Koordinaten (null = alles sichtbar)
) {
    val geometryDefault get() = rot90 == 0 && !flipH && fineDeg == 0f
    val cropDefault get() = crop == CropRect.FULL && cropMode == CropMode.FREE
    val toneDefault get() = brightness == 0f && contrast == 0f && !gray
    val linesDefault get() = !lines && sensitivity == 0.5f && lineWidth == 1 && !transparent && detail == 0.5f
    /** Nichts verändert → das Original kann direkt benutzt werden. */
    val isIdentity get() = geometryDefault && crop == CropRect.FULL && toneDefault && !lines && mask == null

    fun resetGeometry() = copy(rot90 = 0, flipH = false, fineDeg = 0f, crop = CropRect.FULL)
    fun resetCrop() = copy(crop = CropRect.FULL, cropMode = CropMode.FREE)
    fun resetTone() = copy(brightness = 0f, contrast = 0f, gray = false)
    fun resetLines() = copy(lines = false, sensitivity = 0.5f, lineWidth = 1, transparent = false, detail = 0.5f)
    fun resetMask() = copy(mask = null)

    /** 90° drehen (+1 = rechts, −1 = links); der Zuschnitt dreht mit. */
    fun rotate90(dir: Int): EditSettings {
        val c = crop
        val nc = if (dir > 0) CropRect(1 - c.b, c.l, 1 - c.t, c.r) else CropRect(c.t, 1 - c.r, c.b, 1 - c.l)
        return copy(rot90 = ((rot90 + dir) % 4 + 4) % 4, crop = nc)
    }

    /** Waagrecht spiegeln; der Zuschnitt spiegelt mit. */
    fun flip(): EditSettings = copy(flipH = !flipH, crop = CropRect(1 - crop.r, crop.t, 1 - crop.l, crop.b))
}

object PhotoEdit {
    const val LINE_INK = 26            // Farbe der Linien (fast schwarz)
    const val WORK_LONG = 1024         // Linien werden immer in dieser Größe gesucht (Vorschau = Ergebnis)

    /** Größe des gedrehten Bilds (vor dem Zuschnitt) in Quellpixeln. */
    fun turnedSize(sw: Int, sh: Int, s: EditSettings): FloatArray =
        if (s.rot90 % 2 == 1) floatArrayOf(sh.toFloat(), sw.toFloat()) else floatArrayOf(sw.toFloat(), sh.toFloat())

    /** Zoom der Feindrehung, damit keine leeren Ecken entstehen. */
    fun fineZoom(wt: Float, ht: Float, deg: Float): Float {
        val a = Math.toRadians(abs(deg).toDouble()); val c = cos(a).toFloat(); val sn = sin(a).toFloat()
        return c + sn * max(wt / ht, ht / wt)
    }

    /** Ausgabegröße (Pixel), lange Seite höchstens [maxLong]. */
    fun outputSize(sw: Int, sh: Int, s: EditSettings, maxLong: Int, ignoreCrop: Boolean = false): IntArray {
        val t = turnedSize(sw, sh, s)
        val c = if (ignoreCrop) CropRect.FULL else s.crop
        var w = t[0] * c.w; var h = t[1] * c.h
        val k = min(1f, maxLong / max(w, h))
        w *= k; h *= k
        return intArrayOf(max(1, w.roundToInt()), max(1, h.roundToInt()))
    }

    /**
     * Normierter Ausgabepunkt (ox, oy ∈ 0..1) → normierter Quellpunkt (u, v) im Original.
     * Liefert in [out] u, v (kann minimal außerhalb 0..1 liegen; wird beim Abtasten begrenzt).
     */
    fun mapToSource(sw: Int, sh: Int, s: EditSettings, ox: Float, oy: Float, ignoreCrop: Boolean, out: FloatArray) {
        val c = if (ignoreCrop) CropRect.FULL else s.crop
        val t = turnedSize(sw, sh, s)
        // Zuschnitt
        val cx = c.l + ox * c.w; val cy = c.t + oy * c.h
        // Feindrehung rückwärts (um die Mitte, in Pixeln, mit Zoom)
        var rx = cx; var ry = cy
        if (s.fineDeg != 0f) {
            val a = Math.toRadians(s.fineDeg.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            val z = fineZoom(t[0], t[1], s.fineDeg)
            val px = (cx - 0.5f) * t[0]; val py = (cy - 0.5f) * t[1]
            val qx = (ca * px + sa * py) / z; val qy = (-sa * px + ca * py) / z
            rx = qx / t[0] + 0.5f; ry = qy / t[1] + 0.5f
        }
        // Spiegeln rückwärts
        if (s.flipH) rx = 1f - rx
        // 90°-Drehung rückwärts (je Schritt im Uhrzeigersinn: Quelle (u, v) → (1 − v, u))
        var u = rx; var v = ry
        repeat(s.rot90) { val nu = v; val nv = 1f - u; u = nu; v = nv }
        out[0] = u; out[1] = v
    }

    // ---------- Ton ----------
    fun toneChannel(v: Int, brightness: Float, contrast: Float): Int {
        val f = if (contrast >= 0f) 1f + 2f * contrast else 1f + contrast      // 0 .. 3
        val x = (v - 127.5f) * f + 127.5f + brightness * 110f
        return min(255, max(0, x.roundToInt()))
    }

    fun luma(r: Int, g: Int, b: Int) = (77 * r + 150 * g + 29 * b + 128) shr 8

    /** Ton auf ein Pixel (Alpha bleibt). */
    fun tone(p: Int, s: EditSettings): Int {
        if (s.toneDefault) return p
        var r = (p shr 16) and 255; var g = (p shr 8) and 255; var b = p and 255
        if (s.gray) { val l = luma(r, g, b); r = l; g = l; b = l }
        if (s.brightness != 0f || s.contrast != 0f) {
            r = toneChannel(r, s.brightness, s.contrast); g = toneChannel(g, s.brightness, s.contrast); b = toneChannel(b, s.brightness, s.contrast)
        }
        return (p and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    // ---------- Abtasten ----------
    /** Bilinear abtasten (u, v normiert); außerhalb wird an den Rand geklemmt. */
    fun sample(src: IntArray, sw: Int, sh: Int, u: Float, v: Float): Int {
        val x = min(sw - 1f, max(0f, u * sw - 0.5f)); val y = min(sh - 1f, max(0f, v * sh - 0.5f))
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(sw - 1, x0 + 1); val y1 = min(sh - 1, y0 + 1)
        val fx = x - x0; val fy = y - y0
        val p00 = src[y0 * sw + x0]; val p10 = src[y0 * sw + x1]; val p01 = src[y1 * sw + x0]; val p11 = src[y1 * sw + x1]
        val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
        val a = (((p00 ushr 24) * w00 + (p10 ushr 24) * w10 + (p01 ushr 24) * w01 + (p11 ushr 24) * w11) + 0.5f).toInt()
        val r = ((((p00 shr 16) and 255) * w00 + ((p10 shr 16) and 255) * w10 + ((p01 shr 16) and 255) * w01 + ((p11 shr 16) and 255) * w11) + 0.5f).toInt()
        val g = ((((p00 shr 8) and 255) * w00 + ((p10 shr 8) and 255) * w10 + ((p01 shr 8) and 255) * w01 + ((p11 shr 8) and 255) * w11) + 0.5f).toInt()
        val b = (((p00 and 255) * w00 + (p10 and 255) * w10 + (p01 and 255) * w01 + (p11 and 255) * w11) + 0.5f).toInt()
        return (min(255, a) shl 24) or (min(255, r) shl 16) or (min(255, g) shl 8) or min(255, b)
    }

    /**
     * Die ganze Abbildung Ausgabe → Quelle ist affin: u = u0 + ux·ox + uy·oy (ebenso v).
     * Einmal berechnen statt je Pixel.
     */
    fun affine(sw: Int, sh: Int, s: EditSettings, ignoreCrop: Boolean): FloatArray {
        val m0 = FloatArray(2); val m1 = FloatArray(2); val m2 = FloatArray(2)
        mapToSource(sw, sh, s, 0f, 0f, ignoreCrop, m0); mapToSource(sw, sh, s, 1f, 0f, ignoreCrop, m1); mapToSource(sw, sh, s, 0f, 1f, ignoreCrop, m2)
        return floatArrayOf(m0[0], m1[0] - m0[0], m2[0] - m0[0], m0[1], m1[1] - m0[1], m2[1] - m0[1])
    }

    /** Geometrie + Ton (ohne Linien) für die Zeilen [y0, y1) in [out]. */
    fun renderBase(src: IntArray, sw: Int, sh: Int, s: EditSettings, ow: Int, oh: Int, ignoreCrop: Boolean, y0: Int, y1: Int, out: IntArray) {
        val simple = s.geometryDefault && (ignoreCrop || s.crop == CropRect.FULL) && ow == sw && oh == sh
        val af = affine(sw, sh, s, ignoreCrop)
        val doTone = !s.toneDefault
        for (y in y0 until y1) {
            val oy = (y + 0.5f) / oh
            val ub = af[0] + af[2] * oy; val vb = af[3] + af[5] * oy
            val row = (y - y0) * ow
            for (x in 0 until ow) {
                val p = if (simple) src[y * sw + x] else {
                    val ox = (x + 0.5f) / ow
                    sample(src, sw, sh, ub + af[1] * ox, vb + af[4] * ox)
                }
                out[row + x] = if (doTone) tone(p, s) else p
            }
        }
    }

    // ---------- Linienbild ----------
    /**
     * Kantenstärke → Linienmaske 0..1 (Graustufen → Glättung → Sobel → Schwellwert → Verdicken).
     * [img] ist das bereits gedrehte/zugeschnittene/getönte Bild in Arbeitsgröße.
     */
    fun lineMask(img: IntArray, w: Int, h: Int, sensitivity: Float, lineWidth: Int, detail: Float = 1f): FloatArray {
        // Kanten in allen drei Farbkanälen suchen (auch Farbgrenzen mit gleicher Helligkeit, z. B. Sonne vor Himmel)
        val sens = min(1f, max(0f, sensitivity))
        val det = min(1f, max(0f, detail))
        val br = ((1f - det) * 4f * max(w, h) / WORK_LONG + 0.5f).toInt()     // zusätzlicher Glättungsradius in Pixeln
        // Kantenstärke (0..255) ab der eine Linie entsteht; bei wenig Details zusätzlich etwas höher
        val thr = (40f - 34f * sens) * (1f + 0.7f * (1f - det))
        val soft = max(3f, thr * 0.5f)
        val best = FloatArray(w * h); val bgx = FloatArray(w * h); val bgy = FloatArray(w * h)
        // Abstand der Differenz passend zur Glättung (sonst würden geglättete Umrisse zu schwach)
        val sp = max(1, br)
        for (ch in 0 until 3) {
            val shift = 16 - 8 * ch
            var a = FloatArray(w * h) { i -> ((img[i] shr shift) and 255).toFloat() }
            // Glättung 3×3 (Binomial 1-2-1), zweimal
            repeat(2) {
                val t = FloatArray(w * h); val o = FloatArray(w * h)
                for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; t[i] = (a[y * w + max(0, x - 1)] + 2 * a[i] + a[y * w + min(w - 1, x + 1)]) / 4f }
                for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; o[i] = (t[max(0, y - 1) * w + x] + 2 * t[i] + t[min(h - 1, y + 1) * w + x]) / 4f }
                a = o
            }
            // ab 0.5 „Details“: bei wenig Details zusätzlich stark glätten (Kastenfilter, zweimal ≈ Gauß)
            if (br > 0) repeat(2) { a = boxBlur(a, w, h, br) }
            for (y in 0 until h) for (x in 0 until w) {
                val gx: Float; val gy: Float
                if (sp == 1) {   // Sobel
                    val xm = max(0, x - 1); val xp = min(w - 1, x + 1); val ym = max(0, y - 1) * w; val yp = min(h - 1, y + 1) * w; val yc = y * w
                    gx = ((a[ym + xp] + 2 * a[yc + xp] + a[yp + xp]) - (a[ym + xm] + 2 * a[yc + xm] + a[yp + xm])) / 4f
                    gy = ((a[yp + xm] + 2 * a[yp + x] + a[yp + xp]) - (a[ym + xm] + 2 * a[ym + x] + a[ym + xp])) / 4f
                } else {         // Differenz über ±sp Pixel (Stufe einer geglätteten Kante ≈ volle Höhe)
                    gx = a[y * w + min(w - 1, x + sp)] - a[y * w + max(0, x - sp)]
                    gy = a[min(h - 1, y + sp) * w + x] - a[max(0, y - sp) * w + x]
                }
                val mag = sqrt(gx * gx + gy * gy)
                val i = y * w + x
                if (mag > best[i]) { best[i] = mag; bgx[i] = gx; bgy[i] = gy }
            }
        }
        // Linien auf die Mitte der Kante ausdünnen (Nicht-Maximum-Unterdrückung entlang der Kantenrichtung)
        val m = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val g = best[i]
            if (g <= thr) continue
            val ax = abs(bgx[i]); val ay = abs(bgy[i])
            val (dx, dy) = when {
                ax > 2.414f * ay -> 1 to 0
                ay > 2.414f * ax -> 0 to 1
                (bgx[i] > 0) == (bgy[i] > 0) -> 1 to 1
                else -> 1 to -1
            }
            val x1 = x + dx; val y1 = y + dy; val x2 = x - dx; val y2 = y - dy
            val n1 = if (x1 in 0 until w && y1 in 0 until h) best[y1 * w + x1] else 0f
            val n2 = if (x2 in 0 until w && y2 in 0 until h) best[y2 * w + x2] else 0f
            if (g >= n1 && g >= n2) m[i] = sqrt(min(1f, (g - thr) / soft))
        }
        // ab 0.5: kurze, vereinzelte Linienstückchen entfernen (Mindestlänge an „Details“ gekoppelt)
        val minLen = ((1f - det) * (1f - det) * 160f * max(w, h) / WORK_LONG).toInt()
        if (minLen > 1) removeSmall(m, w, h, minLen)
        // Verdicken (Maximum in einer Scheibe)
        // Linien sind jetzt 1 Pixel breit: fein = Radius 1, mittel = 2, kräftig = 3 (bei 1024 px Arbeitsgröße)
        val r = lineWidth.coerceIn(0, 2) + 1
        val o = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var mx = 0f
            for (dy in -r..r) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                for (dx in -r..r) { val xx = x + dx; if (xx < 0 || xx >= w || dx * dx + dy * dy > r * r + r) continue
                    val v = m[yy * w + xx]; if (v > mx) mx = v } }
            o[y * w + x] = mx
        }
        return o
    }

    /** Kastenfilter mit Radius r (waagrecht + senkrecht, gleitende Summe). */
    fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(w * h); val o = FloatArray(w * h); val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w; var s = 0f
            for (k in -r..r) s += a[row + min(w - 1, max(0, k))]
            for (x in 0 until w) {
                t[row + x] = s / n
                s += a[row + min(w - 1, x + r + 1)] - a[row + max(0, x - r)]
            }
        }
        for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += t[min(h - 1, max(0, k)) * w + x]
            for (y in 0 until h) {
                o[y * w + x] = s / n
                s += t[min(h - 1, y + r + 1) * w + x] - t[max(0, y - r) * w + x]
            }
        }
        return o
    }

    /** Zusammenhängende Linienstücke (8er-Nachbarschaft) mit weniger als [minPx] Pixeln löschen. */
    fun removeSmall(m: FloatArray, w: Int, h: Int, minPx: Int) {
        val lab = IntArray(w * h); val stack = IntArray(w * h); val comp = IntArray(w * h)
        var id = 0
        for (st in 0 until w * h) {
            if (lab[st] != 0 || m[st] <= 0.01f) continue
            id++
            var sp = 0; var cn = 0
            stack[sp++] = st; lab[st] = id
            while (sp > 0) {
                val p = stack[--sp]; comp[cn++] = p
                val x = p % w; val y = p / w
                for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= w) continue
                        val q = yy * w + xx
                        if (lab[q] == 0 && m[q] > 0.01f) { lab[q] = id; stack[sp++] = q } } }
            }
            if (cn < minPx) for (i in 0 until cn) m[comp[i]] = 0f
        }
    }

    /** Linienpixel aus Maskenwert. */
    fun linePixel(mask: Float, transparent: Boolean): Int {
        val k = min(1f, max(0f, mask))
        return if (transparent) {
            val al = (k * 255f + 0.5f).toInt()
            // vormultipliziert passt nicht in Int-ARGB von Bitmap.setPixels (nicht vormultipliziert) → Farbe normal
            (al shl 24) or (LINE_INK shl 16) or (LINE_INK shl 8) or LINE_INK
        } else {
            val v = (255f - k * (255 - LINE_INK) + 0.5f).toInt()
            (255 shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    /** Bilinear aus der Maske (normiert). */
    fun sampleMask(m: FloatArray, w: Int, h: Int, u: Float, v: Float): Float {
        val x = min(w - 1f, max(0f, u * w - 0.5f)); val y = min(h - 1f, max(0f, v * h - 0.5f))
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val fx = x - x0; val fy = y - y0
        return (m[y0 * w + x0] * (1 - fx) + m[y0 * w + x1] * fx) * (1 - fy) + (m[y1 * w + x0] * (1 - fx) + m[y1 * w + x1] * fx) * fy
    }

    /**
     * Vollständige Bearbeitung zeilenweise (spart Speicher bei großen Bildern).
     * [workSrc] = Original verkleinert (lange Seite ≤ 1024) für die Liniensuche; Vorschau und Endergebnis
     * finden die Linien daher in derselben Größe und sehen gleich aus.
     */
    class Job(val src: IntArray, val sw: Int, val sh: Int, val s: EditSettings, maxLong: Int, val ignoreCrop: Boolean = false,
              workSrc: IntArray? = null, workW: Int = 0, workH: Int = 0) {
        val size = outputSize(sw, sh, s, maxLong, ignoreCrop)
        val ow = size[0]; val oh = size[1]
        private var mask: FloatArray? = null
        private var mw = 0; private var mh = 0
        init {
            if (s.lines) {
                val ws = workSrc ?: src; val ww = if (workSrc != null) workW else sw; val wh = if (workSrc != null) workH else sh
                val wsz = outputSize(ww, wh, s, WORK_LONG, ignoreCrop)
                mw = wsz[0]; mh = wsz[1]
                val base = IntArray(mw * mh)
                renderBase(ws, ww, wh, s, mw, mh, ignoreCrop, 0, mh, base)
                mask = lineMask(base, mw, mh, s.sensitivity, s.lineWidth, s.detail)
            }
        }

        /** Freistellen-Maske anwenden (Endergebnis); die Vorschau setzt false und nutzt [cutRows]. */
        var applyCut = true
        private val af = affine(sw, sh, s, ignoreCrop)

        fun rows(y0: Int, y1: Int, out: IntArray) {
            val mk = mask
            if (mk == null) renderBase(src, sw, sh, s, ow, oh, ignoreCrop, y0, y1, out)
            else for (y in y0 until y1) for (x in 0 until ow)
                out[(y - y0) * ow + x] = linePixel(sampleMask(mk, mw, mh, (x + 0.5f) / ow, (y + 0.5f) / oh), s.transparent)
            val cut = s.mask
            if (applyCut && cut != null) {
                for (y in y0 until y1) {
                    val oy = (y + 0.5f) / oh
                    for (x in 0 until ow) {
                        val ox = (x + 0.5f) / ow
                        val k = cut.sample(af[0] + af[1] * ox + af[2] * oy, af[3] + af[4] * ox + af[5] * oy)
                        val i = (y - y0) * ow + x
                        if (k < 1f) { val p = out[i]; val al = (((p ushr 24) * k) + 0.5f).toInt(); out[i] = (al shl 24) or (p and 0xFFFFFF) }
                    }
                }
            }
        }

        /** Sichtbarkeit (0..255) je Ausgabepixel aus der Freistellen-Maske, Zeilen [y0, y1). */
        fun cutRows(cut: MaskData?, y0: Int, y1: Int, out: ByteArray, x0: Int = 0, x1: Int = ow) {
            for (y in y0 until y1) {
                val oy = (y + 0.5f) / oh
                for (x in x0 until x1) {
                    val ox = (x + 0.5f) / ow
                    val k = if (cut == null) 1f else cut.sample(af[0] + af[1] * ox + af[2] * oy, af[3] + af[4] * ox + af[5] * oy)
                    out[y * ow + x] = (k * 255f + 0.5f).toInt().toByte()
                }
            }
        }

        /** Ausgabepunkt (Pixel) → Masken-Pixel des Originals. */
        fun toMask(x: Float, y: Float, mw2: Int, mh2: Int): FloatArray {
            val ox = x / ow; val oy = y / oh
            return floatArrayOf((af[0] + af[1] * ox + af[2] * oy) * mw2, (af[3] + af[4] * ox + af[5] * oy) * mh2)
        }

        /** Masken-Pixel je Ausgabepixel (Maßstab, für die Pinselgröße). */
        fun maskPerOutPx(mw2: Int, mh2: Int): Float = kotlin.math.hypot(af[1] * mw2 / ow, af[4] * mh2 / ow)

        fun all(): IntArray { val o = IntArray(ow * oh); rows(0, oh, o); return o }
    }
}

/**
 * Zuschnitt-Rahmen ziehen (normiert 0..1 im gedrehten Bild). [aspectPx] = gewünschtes Seitenverhältnis
 * Breite/Höhe in PIXELN (null = frei), [wt]/[ht] = Pixelgröße des gedrehten Bilds.
 */
object CropTool {
    enum class Handle { TL, T, TR, R, BR, B, BL, L, MOVE }
    const val MIN = 0.06f      // kleinste Seite (normiert)

    fun targetAspect(mode: CropMode, wt: Float, ht: Float): Float? = when (mode) {
        CropMode.FREE -> null
        CropMode.ORIGINAL -> wt / ht
        CropMode.SQUARE -> 1f
    }

    /** Größter Rahmen mit Seitenverhältnis um die Mitte des bisherigen Rahmens, ganz im Bild. */
    fun applyAspect(c: CropRect, aspectPx: Float?, wt: Float, ht: Float): CropRect {
        if (aspectPx == null) return c
        val an = aspectPx * ht / wt                // normiertes Verhältnis w/h
        var w = c.w; var h = c.h
        if (w / h > an) w = h * an else h = w / an
        val cx = (c.l + c.r) / 2; val cy = (c.t + c.b) / 2
        return fit(CropRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))
    }

    /** Rahmen ins Bild schieben (Größe bleibt, solange sie passt). */
    fun fit(c: CropRect): CropRect {
        val w = min(1f, c.w); val h = min(1f, c.h)
        val l = min(1f - w, max(0f, c.l)); val t = min(1f - h, max(0f, c.t))
        return CropRect(l, t, l + w, t + h)
    }

    fun drag(c: CropRect, hd: Handle, dx: Float, dy: Float, aspectPx: Float?, wt: Float, ht: Float): CropRect {
        if (hd == Handle.MOVE) return fit(CropRect(c.l + dx, c.t + dy, c.r + dx, c.b + dy))
        var l = c.l; var t = c.t; var r = c.r; var b = c.b
        when (hd) {
            Handle.TL -> { l += dx; t += dy }; Handle.T -> t += dy; Handle.TR -> { r += dx; t += dy }; Handle.R -> r += dx
            Handle.BR -> { r += dx; b += dy }; Handle.B -> b += dy; Handle.BL -> { l += dx; b += dy }; Handle.L -> l += dx
            else -> {}
        }
        // Grenzen und Mindestgröße (die gegenüberliegende Seite bleibt stehen)
        l = max(0f, min(l, r - MIN)); r = min(1f, max(r, l + MIN)); t = max(0f, min(t, b - MIN)); b = min(1f, max(b, t + MIN))
        if (aspectPx == null) return CropRect(l, t, r, b)
        // festes Seitenverhältnis: an der festen Ecke/Seite verankert anpassen
        val an = aspectPx * ht / wt
        var w = r - l; var h = b - t
        when (hd) {
            Handle.T, Handle.B -> w = h * an
            Handle.L, Handle.R -> h = w / an
            else -> if (w / h > an) h = w / an else w = h * an
        }
        // nicht größer als das Bild von der Verankerung aus
        val anchorX = when (hd) { Handle.TL, Handle.BL, Handle.L -> c.r; Handle.TR, Handle.BR, Handle.R -> c.l; else -> (c.l + c.r) / 2 }
        val anchorY = when (hd) { Handle.TL, Handle.TR, Handle.T -> c.b; Handle.BL, Handle.BR, Handle.B -> c.t; else -> (c.t + c.b) / 2 }
        val maxW = when (hd) { Handle.TL, Handle.BL, Handle.L -> anchorX; Handle.TR, Handle.BR, Handle.R -> 1f - anchorX; else -> 2 * min(anchorX, 1f - anchorX) }
        val maxH = when (hd) { Handle.TL, Handle.TR, Handle.T -> anchorY; Handle.BL, Handle.BR, Handle.B -> 1f - anchorY; else -> 2 * min(anchorY, 1f - anchorY) }
        if (w > maxW) { w = maxW; h = w / an }
        if (h > maxH) { h = maxH; w = h * an }
        val nl = when (hd) { Handle.TL, Handle.BL, Handle.L -> anchorX - w; Handle.TR, Handle.BR, Handle.R -> anchorX; else -> anchorX - w / 2 }
        val nt = when (hd) { Handle.TL, Handle.TR, Handle.T -> anchorY - h; Handle.BL, Handle.BR, Handle.B -> anchorY; else -> anchorY - h / 2 }
        return CropRect(nl, nt, nl + w, nt + h)
    }
}
