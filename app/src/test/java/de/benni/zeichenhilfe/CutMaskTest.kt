package de.benni.zeichenhilfe

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Freistellen: Lasso, Pinsel, Rückgängig, Maske bei Zuschneiden/Drehen/Spiegeln/Feindrehung, Linienbild. */
class CutMaskTest {

    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun a(p: Int) = (p ushr 24) and 255

    @Test fun lassoInnenUndAussen() {
        val w = 200; val h = 100
        // Quadrat links: x 0,1..0,4 / y 0,2..0,8 (normiert)
        val sq = floatArrayOf(0.1f, 0.2f, 0.4f, 0.2f, 0.4f, 0.8f, 0.1f, 0.8f)
        val keep = CutMask.lasso(null, w, h, sq, keepInside = true)
        check(keep.at(50, 50) == 255 && keep.at(150, 50) == 0 && keep.at(5, 5) == 0, "Innen behalten")
        val rem = CutMask.lasso(null, w, h, sq, keepInside = false)
        check(rem.at(50, 50) == 0 && rem.at(150, 50) == 255, "Innen entfernen")
        // Kante weich: Pixel genau auf der Kante x = 0,1·200 = 20 → Spalte 19 außen, 20 innen; halbe Pixel → Zwischenwert
        val half = CutMask.lasso(null, w, h, floatArrayOf(0.1025f, 0.2f, 0.4f, 0.2f, 0.4f, 0.8f, 0.1025f, 0.8f), true)
        check(half.at(20, 50) in 100..155, "Kantenglättung (${half.at(20, 50)})")
        println("Lasso: innen behalten/entfernen richtig, Randpixel anteilig ${half.at(20, 50)}/255, sichtbar ${String.format("%.1f", CutMask.visibleFraction(keep) * 100)} %")
    }

    @Test fun sichKreuzendeLinie() {
        val w = 200; val h = 200
        // Acht (zwei Schleifen, die sich in der Mitte kreuzen): beide Schleifen gelten als innen
        val fig8 = floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.9f)
        val m = CutMask.lasso(null, w, h, fig8, true)
        check(m.at(40, 100) > 200 && m.at(160, 100) > 200, "beide Hälften der Acht innen")
        check(m.at(100, 20) < 50 && m.at(100, 180) < 50, "zwischen den Schleifen außen")
        // Doppelt umfahren (zweimal im Kreis, überlappt sich): kein Loch (Regel „ungleich null“)
        val n = 60; val twice = FloatArray(4 * n)
        for (i in 0 until 2 * n) { val t = 2 * Math.PI * i / n; twice[2 * i] = (0.5 + 0.3 * cos(t)).toFloat(); twice[2 * i + 1] = (0.5 + 0.3 * sin(t)).toFloat() }
        val m2 = CutMask.lasso(null, w, h, twice, true)
        check(m2.at(100, 100) == 255, "doppelt umfahren: Mitte bleibt innen (kein Loch)")
        println("Kreuzende Linie: beide Schleifen der Acht innen, doppelt umfahrener Kreis ohne Loch")
    }

    @Test fun pinselHartWeichRadierenZurueckholen() {
        val base = MaskData.full(100, 100)
        val hard = CutMask.Stroke(base, erase = true, hard = true)
        hard.to(20f, 50f, 8f); hard.to(80f, 50f, 8f)
        val h1 = hard.result()
        check(h1.at(50, 50) == 0 && h1.at(50, 62) == 255 && h1.at(10, 10) == 255, "harter Pinsel radiert entlang der Linie")
        val edgeHard = h1.at(50, 57)
        val soft = CutMask.Stroke(base, erase = true, hard = false)
        soft.to(20f, 50f, 8f); soft.to(80f, 50f, 8f)
        val s1 = soft.result()
        val edgeSoft = s1.at(50, 55)
        check(s1.at(50, 50) == 0 && edgeSoft in 30..230, "weicher Rand ($edgeSoft)")
        // Überlappende Tupfer werden nicht dunkler als ein Tupfer (Maximum je Strich)
        val once = CutMask.Stroke(base, true, false); once.to(50f, 50f, 8f)
        val many = CutMask.Stroke(base, true, false); repeat(20) { many.to(50f, 50f, 8f) }
        check(once.result().at(50, 55) == many.result().at(50, 55), "weicher Pinsel stapelt sich nicht")
        // Zurückholen
        val back = CutMask.Stroke(h1, erase = false, hard = true); back.to(50f, 50f, 6f)
        val b1 = back.result()
        check(b1.at(50, 50) == 255 && b1.at(25, 50) == 0, "Zurückholen nur unter dem Pinsel")
        println("Pinsel: hart Rand $edgeHard, weich Rand $edgeSoft, Zurückholen stellt wieder her")
    }

    @Test fun rueckgaengigWiederholen() {
        val h = MaskHistory(null)
        val m1 = CutMask.lasso(null, 10, 10, floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f), true)
        val m2 = CutMask.lasso(m1, 10, 10, floatArrayOf(0f, 0f, 0.2f, 0f, 0.2f, 1f, 0f, 1f), false)
        h.push(m1); h.push(m2)
        check(h.current === m2 && h.canUndo && !h.canRedo, "Stand")
        check(h.undo() === m1 && h.undo() == null && !h.canUndo, "zweimal rückgängig")
        check(h.redo() === m1 && h.redo() === m2 && !h.canRedo, "zweimal wiederholen")
        h.undo(); h.push(null)
        check(h.current == null && !h.canRedo, "neuer Schritt löscht Wiederholen")
        println("Rückgängig/Wiederholen: mehrere Schritte, neuer Schritt verwirft die Wiederholen-Liste")
    }

    /** 200 × 100 Bild, Maske: nur das linke obere Viertel sichtbar. */
    private fun setup(): Triple<IntArray, MaskData, Int> {
        val w = 200; val h = 100
        val src = IntArray(w * h) { argb(255, 200, 100, 50) }
        val m = CutMask.lasso(null, 100, 50, floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f), true)
        return Triple(src, m, w)
    }

    @Test fun maskeBleibtAnDerRichtigenStelle() {
        val (src, m, w) = setup(); val h = 100
        fun alphaAt(s: EditSettings, u: Float, v: Float): Int {
            val j = PhotoEdit.Job(src, w, h, s, 4096); val o = j.all()
            return a(o[(v * j.oh).toInt().coerceIn(0, j.oh - 1) * j.ow + (u * j.ow).toInt().coerceIn(0, j.ow - 1)])
        }
        val s0 = EditSettings(mask = m)
        check(alphaAt(s0, 0.25f, 0.25f) == 255 && alphaAt(s0, 0.75f, 0.75f) == 0 && alphaAt(s0, 0.75f, 0.25f) == 0, "ohne Geometrie")
        // 90° rechts: links oben wandert nach rechts oben
        val r = s0.rotate90(1)
        check(alphaAt(r, 0.75f, 0.25f) == 255 && alphaAt(r, 0.25f, 0.25f) == 0 && alphaAt(r, 0.75f, 0.75f) == 0, "nach 90° rechts")
        // Spiegeln: links oben → rechts oben
        val f = s0.flip()
        check(alphaAt(f, 0.75f, 0.25f) == 255 && alphaAt(f, 0.25f, 0.25f) == 0, "nach Spiegeln")
        // Zuschneiden auf linke Hälfte: sichtbarer Teil ist jetzt die obere Hälfte des Ausschnitts
        val c = s0.copy(crop = CropRect(0f, 0f, 0.5f, 1f))
        check(alphaAt(c, 0.5f, 0.25f) == 255 && alphaAt(c, 0.5f, 0.75f) == 0, "nach Zuschneiden")
        // Feindrehung 10°: Mitte des sichtbaren Viertels bleibt sichtbar, gegenüberliegendes Viertel leer
        val fd = s0.copy(fineDeg = 10f)
        check(alphaAt(fd, 0.25f, 0.25f) == 255 && alphaAt(fd, 0.75f, 0.75f) == 0, "nach Feindrehung")
        println("Maske: richtig nach 90°, Spiegeln, Zuschneiden und Feindrehung")
    }

    @Test fun linienbildLeerInFreigestelltenBereichen() {
        val w = 300; val h = 200
        val src = IntArray(w * h) { i -> val x = i % w; val y = i / w; if ((x / 30 + y / 30) % 2 == 0) argb(255, 230, 230, 230) else argb(255, 40, 40, 40) }
        val m = CutMask.lasso(null, 150, 100, floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 1f, 0f, 1f), true)   // nur linke Hälfte
        for (transp in listOf(false, true)) {
            val o = PhotoEdit.Job(src, w, h, EditSettings(lines = true, transparent = transp, mask = m), 4096).all()
            var rightVisible = 0; var leftLines = 0
            for (y in 0 until h) for (x in 0 until w) {
                val p = o[y * w + x]
                if (x >= 155 && a(p) > 0) rightVisible++
                if (x < 145 && a(p) > 200 && ((p shr 16) and 255) < 128) leftLines++
            }
            check(rightVisible == 0, "freigestellter Bereich leer, Alpha 0 (durchsichtig=$transp): $rightVisible")
            check(leftLines > 100, "links bleiben Linien ($leftLines)")
        }
        println("Linienbild mit Maske: freigestellte Hälfte überall Alpha 0, sichtbare Hälfte mit Linien")
    }

    @Test fun detailsWenigAmUnruhigenBild() {
        // unruhiges Bild: großer heller Kreis (Hauptumriss) auf Rauschen + vielen kleinen Flecken (Laub/Gras)
        val w = 600; val h = 400; val r = java.util.Random(3)
        val v = FloatArray(w * h) { (85 + r.nextGaussian() * 10).toFloat() }
        // 1500 kleine helle und dunkle Flecken (Radius 2–4 px) = Laub/Gras
        repeat(1500) {
            val cx = r.nextInt(w); val cy = r.nextInt(h); val rad = 2 + r.nextInt(3); val add = if (r.nextBoolean()) 75f else -45f
            for (yy in cy - rad..cy + rad) for (xx in cx - rad..cx + rad)
                if (xx in 0 until w && yy in 0 until h && (xx - cx) * (xx - cx) + (yy - cy) * (yy - cy) <= rad * rad) v[yy * w + xx] += add
        }
        val src = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var c = v[y * w + x].toDouble()
            if (Math.hypot(x - 300.0, y - 200.0) < 120) c = 215 + r.nextGaussian() * 6     // Hauptumriss: großer heller Kreis
            val k = c.toInt().coerceIn(0, 255); src[y * w + x] = argb(255, k, k, k)
        }
        fun count(d: Float): Pair<Int, Int> {
            val o = PhotoEdit.Job(src, w, h, EditSettings(lines = true, detail = d), 4096).all()
            var all = 0; var ring = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (((o[y * w + x] shr 16) and 255) < 128) {
                    all++
                    if (abs(Math.hypot(x - 300.0, y - 200.0) - 120) < 6) ring++
                }
            }
            return all to ring
        }
        val t0 = System.nanoTime()
        val viel = count(1f); val t1 = System.nanoTime(); val mitte = count(0.5f); val wenig = count(0f); val t2 = System.nanoTime()
        val ringLen = (2 * Math.PI * 120).toInt()
        println("Details viel/mittel/wenig: Linienpixel ${viel.first} / ${mitte.first} / ${wenig.first}, auf dem Hauptumriss ${viel.second} / ${mitte.second} / ${wenig.second} (Umfang ≈ $ringLen px); Zeit viel ${(t1 - t0) / 1_000_000} ms, mittel+wenig ${(t2 - t1) / 1_000_000} ms")
        check(wenig.first < viel.first / 3, "„wenig“ hat deutlich weniger Linienpixel")
        check(wenig.second > ringLen, "Hauptumriss bleibt bei „wenig“ geschlossen sichtbar")
    }

    @Test fun rechenzeitFreistellen() {
        val g = CutMask.gridSize(4096, 3072)          // Maske 1024 × 768
        val n = 400; val poly = FloatArray(2 * n)
        for (i in 0 until n) { val t = 2 * Math.PI * i / n; val rr = 0.3 + 0.08 * sin(7 * t); poly[2 * i] = (0.5 + rr * cos(t)).toFloat(); poly[2 * i + 1] = (0.5 + rr * sin(t) * 1.2).toFloat() }
        CutMask.lasso(null, g[0], g[1], poly, true)
        var t0 = System.nanoTime(); val m = CutMask.lasso(null, g[0], g[1], poly, true); val tl = (System.nanoTime() - t0) / 1e6
        t0 = System.nanoTime()
        val st = CutMask.Stroke(m, true, false)
        for (k in 0..100) st.to(100f + k * 8f, 300f + 100 * sin(k / 10.0).toFloat(), 20f)
        val tb = (System.nanoTime() - t0) / 1e6
        val src = IntArray(1024 * 768) { (255 shl 24) or 0x808080 }
        val s = EditSettings(mask = m, fineDeg = 3f)
        t0 = System.nanoTime(); val j5 = PhotoEdit.Job(src, 1024, 768, s, 512); j5.applyCut = false; j5.all(); val a5 = ByteArray(j5.ow * j5.oh); j5.cutRows(m, 0, j5.oh, a5); val tp5 = (System.nanoTime() - t0) / 1e6
        t0 = System.nanoTime(); val j1 = PhotoEdit.Job(src, 1024, 768, s, 1024); j1.applyCut = false; j1.all(); val a1 = ByteArray(j1.ow * j1.oh); j1.cutRows(m, 0, j1.oh, a1); val tp1 = (System.nanoTime() - t0) / 1e6
        println(String.format("Rechenzeit (PC): Lasso 1024×768 %.1f ms, Pinselstrich (100 Abschnitte, r=20) %.1f ms, Vorschau mit Maske 512 px %.0f ms / 1024 px %.0f ms", tl, tb, tp5, tp1))
    }
}
