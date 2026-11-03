package de.benni.zeichenhilfe

import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Bildbearbeitung: Zuschnitt, Drehen, Spiegeln, Feindrehung, Ton, Graustufen, Linienbild, Rechenzeit. */
class PhotoEditTest {

    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 255
    private fun g(p: Int) = (p shr 8) and 255
    private fun b(p: Int) = p and 255
    private fun a(p: Int) = (p ushr 24) and 255

    /** Testbild 4 × 3 mit eindeutigen Farben je Pixel. */
    private fun small(): IntArray = IntArray(12) { argb(255, it * 20, 100, 200 - it * 10) }

    @Test fun drehen90UndSpiegeln() {
        val src = small(); val w = 4; val h = 3
        // rechts drehen: neue Größe 3 × 4; Pixel links oben (0,0) wandert nach rechts oben
        val j = PhotoEdit.Job(src, w, h, EditSettings().rotate90(1), 4096)
        check(j.ow == 3 && j.oh == 4, "Größe nach 90° rechts: ${j.ow}×${j.oh}")
        val o = j.all()
        check(o[0 * 3 + 2] == src[0], "Ecke links oben → rechts oben")
        check(o[3 * 3 + 0] == src[2 * 4 + 3], "Ecke rechts unten → links unten")
        // links drehen: (0,0) → links unten
        val o2 = PhotoEdit.Job(src, w, h, EditSettings().rotate90(-1), 4096).all()
        check(o2[3 * 3 + 0] == src[0], "90° links: links oben → links unten")
        // vier Mal rechts = Original
        var s = EditSettings(); repeat(4) { s = s.rotate90(1) }
        check(PhotoEdit.Job(src, w, h, s, 4096).all().contentEquals(src), "4 × 90° = Original")
        // spiegeln
        val o3 = PhotoEdit.Job(src, w, h, EditSettings().flip(), 4096).all()
        check(o3[0] == src[3] && o3[11] == src[8], "waagrecht gespiegelt")
        println("90° rechts/links, 4×90°, Spiegeln: Größe und Pixel an erwarteter Stelle")
    }

    @Test fun feindrehung() {
        // 101 × 61 Bild: links Schwarz, rechts Weiß (Grenze senkrecht in der Mitte)
        val w = 101; val h = 61
        val src = IntArray(w * h) { i -> if (i % w < 50) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }
        val s = EditSettings(fineDeg = 10f)
        val j = PhotoEdit.Job(src, w, h, s, 4096)
        check(j.ow == w && j.oh == h, "Feindrehung ändert die Größe nicht")
        val o = j.all()
        // im Uhrzeigersinn gedreht: die senkrechte Grenze kippt – oben liegt sie weiter rechts als unten
        fun edgeX(y: Int): Int { for (x in 0 until w) if (r(o[y * w + x]) > 127) return x; return w }
        val top = edgeX(5); val bot = edgeX(h - 6)
        println("Feindrehung 10°: Grenze oben x=$top, unten x=$bot, Zoom ${String.format("%.3f", PhotoEdit.fineZoom(w.toFloat(), h.toFloat(), 10f))}")
        check(top > bot, "Richtung der Drehung")
        // keine leeren Ecken: Zoom so, dass die Ecken im Bild liegen
        val m = FloatArray(2)
        for ((ox, oy) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            for (deg in listOf(-45f, -20f, 7f, 45f)) {
                PhotoEdit.mapToSource(w, h, EditSettings(fineDeg = deg), ox, oy, false, m)
                check(m[0] in -1e-4f..1.0001f && m[1] in -1e-4f..1.0001f, "Ecke ($ox,$oy) bei $deg° im Bild: ${m[0]}, ${m[1]}")
            }
        }
    }

    @Test fun zuschnittBleibtImBildUndSeitenverhaeltnis() {
        val wt = 400f; val ht = 300f
        var c = CropRect.FULL
        c = CropTool.drag(c, CropTool.Handle.TL, -0.3f, -0.3f, null, wt, ht)
        check(c == CropRect.FULL, "über den Rand ziehen bleibt im Bild")
        c = CropTool.drag(c, CropTool.Handle.BR, -0.5f, -0.2f, null, wt, ht)
        check(abs(c.r - 0.5f) < 1e-5 && abs(c.b - 0.8f) < 1e-5, "frei ziehen")
        c = CropTool.drag(c, CropTool.Handle.MOVE, 0.9f, 0.9f, null, wt, ht)
        check(c.r <= 1f && c.b <= 1f && abs(c.w - 0.5f) < 1e-5, "verschieben bleibt im Bild, Größe gleich")
        c = CropTool.drag(c, CropTool.Handle.L, 0.9f, 0f, null, wt, ht)
        check(c.w >= CropTool.MIN - 1e-6, "Mindestgröße")
        // 1:1
        val sq = CropTool.applyAspect(CropRect.FULL, 1f, wt, ht)
        check(abs(sq.w * wt - sq.h * ht) < 0.5f && sq.l >= 0 && sq.r <= 1, "1:1 in Pixeln: ${sq.w * wt} × ${sq.h * ht}")
        var d = CropTool.drag(sq, CropTool.Handle.TR, 0.2f, -0.1f, 1f, wt, ht)
        check(abs(d.w * wt - d.h * ht) < 0.5f && d.l >= -1e-6 && d.t >= -1e-6 && d.r <= 1.000001f && d.b <= 1.000001f, "1:1 bleibt beim Ziehen, im Bild")
        d = CropTool.drag(d, CropTool.Handle.B, 0f, 0.5f, 1f, wt, ht)
        check(abs(d.w * wt - d.h * ht) < 0.5f && d.b <= 1.000001f && d.r <= 1.000001f, "1:1 an der Kante")
        // Original-Verhältnis
        val orig = CropTool.applyAspect(CropRect(0.1f, 0.1f, 0.5f, 0.9f), wt / ht, wt, ht)
        check(abs((orig.w * wt) / (orig.h * ht) - wt / ht) < 1e-3, "Original-Seitenverhältnis")
        // Ausgabegröße folgt dem Zuschnitt
        val sz = PhotoEdit.outputSize(400, 300, EditSettings(crop = CropRect(0f, 0f, 0.5f, 0.5f)), 4096)
        check(sz[0] == 200 && sz[1] == 150, "Ausgabegröße ${sz[0]}×${sz[1]}")
        // Zuschnitt dreht mit
        val s = EditSettings(crop = CropRect(0f, 0f, 0.5f, 0.5f)).rotate90(1)
        check(s.crop == CropRect(0.5f, 0f, 1f, 0.5f), "Zuschnitt dreht mit: ${s.crop}")
        println("Zuschnitt: bleibt im Bild, Mindestgröße, 1:1 und Original-Seitenverhältnis gehalten, dreht/spiegelt mit")
    }

    @Test fun tonUndGraustufen() {
        val p = argb(255, 10, 128, 250)
        check(PhotoEdit.tone(p, EditSettings()) == p, "Mitte = unverändert")
        for (v in 0..255) check(PhotoEdit.toneChannel(v, 0f, 0f) == v, "Kanal unverändert bei $v")
        val hi = PhotoEdit.tone(p, EditSettings(brightness = 1f, contrast = 1f))
        val lo = PhotoEdit.tone(p, EditSettings(brightness = -1f, contrast = 1f))
        check(r(hi) in 0..255 && b(hi) == 255 && r(lo) == 0, "Grenzen 0..255 eingehalten")
        val flat = PhotoEdit.tone(p, EditSettings(contrast = -1f))
        check(r(flat) == 128 && g(flat) == 128 && b(flat) == 128, "Kontrast −1 = einheitliches Grau")
        check(PhotoEdit.tone(p, EditSettings(brightness = 0.5f)).let { r(it) > r(p) && g(it) > g(p) }, "heller")
        val gr = PhotoEdit.tone(p, EditSettings(gray = true))
        check(r(gr) == g(gr) && g(gr) == b(gr) && a(gr) == 255, "Graustufen")
        check(r(PhotoEdit.tone(argb(255, 255, 255, 255), EditSettings(gray = true))) == 255 &&
              r(PhotoEdit.tone(argb(255, 0, 0, 0), EditSettings(gray = true))) == 0, "Weiß/Schwarz bleiben")
        check(a(PhotoEdit.tone(argb(77, 10, 10, 10), EditSettings(brightness = 1f))) == 77, "Alpha bleibt")
        println("Ton: Mitte unverändert, Grenzen 0..255, Graustufen R=G=B")
    }

    /** 300 × 200: grauer Grund, helles Rechteck in der Mitte. */
    private fun rectImage(w: Int = 300, h: Int = 200): IntArray =
        IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 100..199 && y in 60..139) argb(255, 230, 230, 230) else argb(255, 90, 90, 90) }

    @Test fun linienbild() {
        val w = 300; val h = 200; val src = rectImage(w, h)
        val j = PhotoEdit.Job(src, w, h, EditSettings(lines = true), 4096)
        val o = j.all()
        fun dark(x: Int, y: Int) = r(o[y * w + x]) < 128
        check(dark(100, 100) && dark(199, 100) && dark(150, 60) && dark(150, 139), "Kanten werden gefunden")
        check(!dark(150, 100) && !dark(30, 30) && !dark(250, 180), "Flächen bleiben leer (weiß)")
        check(r(o[100 * w + 150]) == 255 && a(o[100 * w + 150]) == 255, "Fläche ganz weiß und deckend")
        // durchsichtig
        val t = PhotoEdit.Job(src, w, h, EditSettings(lines = true, transparent = true), 4096).all()
        check(a(t[100 * w + 150]) == 0 && a(t[30 * w + 30]) == 0, "durchsichtig: Alpha 0 außerhalb der Linien")
        check(a(t[100 * w + 100]) > 200, "Linie deckend")
        // Linienstärke und Empfindlichkeit
        fun count(s: EditSettings) = PhotoEdit.Job(src, w, h, s, 4096).all().count { r(it) < 128 }
        val fein = count(EditSettings(lines = true, lineWidth = 0)); val mittel = count(EditSettings(lines = true, lineWidth = 1)); val kraeftig = count(EditSettings(lines = true, lineWidth = 2))
        check(fein < mittel && mittel < kraeftig, "Linienstärke fein < mittel < kräftig ($fein/$mittel/$kraeftig)")
        // schwache Kante (Kontrast 25) nur bei hoher Empfindlichkeit
        val weak = IntArray(w * h) { i -> val x = i % w; if (x >= 150) argb(255, 125, 125, 125) else argb(255, 100, 100, 100) }
        val few = PhotoEdit.Job(weak, w, h, EditSettings(lines = true, sensitivity = 0f), 4096).all().count { r(it) < 128 }
        val many = PhotoEdit.Job(weak, w, h, EditSettings(lines = true, sensitivity = 1f), 4096).all().count { r(it) < 128 }
        check(few == 0 && many > 0, "Empfindlichkeit: schwache Kante nur bei „mehr Linien“ ($few / $many)")
        println("Linienbild: Kanten gefunden, Flächen leer, durchsichtig Alpha 0, Pixel dunkel fein/mittel/kräftig $fein/$mittel/$kraeftig, schwache Kante $few→$many")
    }

    @Test fun vorschauUndErgebnisGleicheLinien() {
        // Großes Bild 2000 × 1500 und seine Vorschau 1024 × 768: Linien an derselben (normierten) Stelle
        val w = 2000; val h = 1500
        val big = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 600..1400 && y in 400..1100) argb(255, 230, 230, 230) else argb(255, 80, 80, 80) }
        val pw = 1024; val ph = 768
        val prev = IntArray(pw * ph) { i -> val x = i % pw * w / pw; val y = i / pw * h / ph; big[y * w + x] }
        val s = EditSettings(lines = true)
        val pj = PhotoEdit.Job(prev, pw, ph, s, 1024)
        val fj = PhotoEdit.Job(big, w, h, s, 4096, workSrc = prev, workW = pw, workH = ph)
        val po = pj.all(); val fo = fj.all()
        // Ergebnis auf Vorschaugröße mitteln und Pixel für Pixel vergleichen
        var sum = 0.0; var worstRow = 0.0
        val kx = fj.ow.toFloat() / pw; val ky = fj.oh.toFloat() / ph
        for (y in 0 until ph) {
            var rowSum = 0.0
            for (x in 0 until pw) {
                var acc = 0; var c = 0
                val x0 = (x * kx).toInt(); val x1 = maxOf(x0 + 1, ((x + 1) * kx).toInt()); val y0 = (y * ky).toInt(); val y1 = maxOf(y0 + 1, ((y + 1) * ky).toInt())
                for (yy in y0 until minOf(fj.oh, y1)) for (xx in x0 until minOf(fj.ow, x1)) { acc += r(fo[yy * fj.ow + xx]); c++ }
                val d = abs(acc.toDouble() / c - r(po[y * pw + x])); sum += d; rowSum += d
            }
            worstRow = max(worstRow, rowSum / pw)
        }
        val mean = sum / (pw * ph)
        println(String.format("Vorschau 1024 px und Ergebnis %d×%d: mittlerer Grauwert-Unterschied %.2f (0..255), schlechteste Zeile %.1f", fj.ow, fj.oh, mean, worstRow))
        check(mean < 3.0 && worstRow < 40.0, "Vorschau ≈ Ergebnis")
    }

    @Test fun rechenzeit() {
        fun img(w: Int, h: Int) = IntArray(w * h) { i -> val x = i % w; val y = i / w; argb(255, (x * 255 / w), (y * 255 / h), if ((x / 64 + y / 64) % 2 == 0) 220 else 40) }
        val s1 = EditSettings(fineDeg = 5f, crop = CropRect(0.05f, 0.05f, 0.95f, 0.95f), brightness = 0.1f, contrast = 0.2f)
        val s2 = s1.copy(lines = true)
        val p = img(1024, 768)
        val f = img(4096, 3072)
        fun time(block: () -> Unit): Double { block(); val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1e6 }
        val tp1 = time { PhotoEdit.Job(p, 1024, 768, s1, 1024).all() }
        val tp2 = time { PhotoEdit.Job(p, 1024, 768, s2, 1024).all() }
        // Ergebnis in Streifen (wie in der App), Speicher nur für einen Streifen
        val tf1 = time { val j = PhotoEdit.Job(f, 4096, 3072, s1, 4096); val band = IntArray(j.ow * 256); var y = 0; while (y < j.oh) { val y1 = minOf(j.oh, y + 256); j.rows(y, y1, band); y = y1 } }
        val tf2 = time { val j = PhotoEdit.Job(f, 4096, 3072, s2, 4096, workSrc = p, workW = 1024, workH = 768); val band = IntArray(j.ow * 256); var y = 0; while (y < j.oh) { val y1 = minOf(j.oh, y + 256); j.rows(y, y1, band); y = y1 } }
        println(String.format("Rechenzeit (PC): Vorschau 1024 px %.0f ms, mit Linien %.0f ms · Ergebnis 4096 px %.0f ms, mit Linien %.0f ms", tp1, tp2, tf1, tf2))
    }
}
