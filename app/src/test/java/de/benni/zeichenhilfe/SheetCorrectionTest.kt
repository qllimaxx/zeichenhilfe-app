package de.benni.zeichenhilfe

import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Nachführung am Blatt: Gültigkeit, Mittelung, Ausreißer, Totzone, echtes Verschieben, sanftes Folgen. */
class SheetCorrectionTest {
    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }
    private val L = 0.21f; private val S = 0.148f      // A5

    /** Blattecken in Anker-Koordinaten bei Versatz (x, z) und Drehung t (wie PhotoPlacement). */
    private fun sheet(x: Float, z: Float, t: Float, noise: Float = 0f, r: Random? = null): FloatArray {
        val p = listOf(-L / 2 to -S / 2, L / 2 to -S / 2, L / 2 to S / 2, -L / 2 to S / 2)
        val o = FloatArray(8)
        p.forEachIndexed { i, (u, v) ->
            val n1 = if (r != null) (r.nextGaussian() * noise).toFloat() else 0f; val n2 = if (r != null) (r.nextGaussian() * noise).toFloat() else 0f
            o[2 * i] = cos(t) * u + sin(t) * v + x + n1; o[2 * i + 1] = -sin(t) * u + cos(t) * v + z + n2
        }
        return o
    }
    private fun settle(c: SheetCorrection, ms: Int = 5000) { repeat(ms / 33) { c.step(33f) } }

    @Test fun gueltigkeitUndVerdeckung() {
        val c = SheetCorrection(L, S)
        check(c.validate(sheet(0.01f, 0f, 0.05f)) == null, "ganzes Blatt gültig")
        val cut = sheet(0f, 0f, 0f); cut[4] -= 0.02f; cut[5] -= 0.015f        // Ecke durch Hand abgeschnitten
        check(c.validate(cut) != null, "verdeckte Ecke ungültig")
        check(c.validate(sheet(0f, 0f, 0f).map { it * 1.1f }.toFloatArray()) != null, "falsche Größe ungültig")
        check(!c.measure(cut) && !c.lastValid, "ungültige Messung wird nicht übernommen")
        println("Gültigkeit: ganzes Blatt ja, abgeschnittene Ecke/falsche Größe nein")
    }

    @Test fun mittelungTotzoneUndAusreisser() {
        val c = SheetCorrection(L, S); val r = Random(1)
        repeat(10) { c.measure(sheet(0.0005f, 0f, 0f, 0.0004f, r)) }
        settle(c)
        check(c.count == 0 && hypot(c.appliedX, c.appliedZ) < 1e-4f, "0,5 mm Versatz + Rauschen: keine Korrektur (Totzone)")
        repeat(6) { c.measure(sheet(0.004f, -0.002f, 0.01f, 0.0004f, r)) }
        c.measure(sheet(0.05f, 0.03f, 0.2f))                                   // einzelner Ausreißer
        settle(c)
        check(abs(c.appliedX - 0.004f) < 0.0008f && abs(c.appliedZ + 0.002f) < 0.0008f && abs(c.appliedT - 0.01f) < 0.004f,
            "4,5 mm Versatz übernommen, Ausreißer verworfen (${c.appliedX}, ${c.appliedZ}, ${c.appliedT})")
        println(String.format("Mittelung: 0,5 mm ignoriert, 4,5 mm übernommen (%.1f / %.1f mm), Ausreißer 58 mm verworfen", c.appliedX * 1000, c.appliedZ * 1000))
    }

    @Test fun blattWirklichVerschobenUndSanft() {
        val c = SheetCorrection(L, S)
        repeat(5) { c.measure(sheet(0f, 0f, 0f)) }
        repeat(5) { c.measure(sheet(0.05f, 0f, 0f)) }                          // Blatt um 5 cm verschoben
        // keine Sprünge: je Bild (33 ms) höchstens 3 cm/s · 33 ms ≈ 1 mm
        var maxStep = 0f; var last = c.appliedX
        repeat(150) { c.step(33f); maxStep = maxOf(maxStep, abs(c.appliedX - last)); last = c.appliedX }
        check(abs(c.appliedX - 0.05f) < 0.001f, "Foto folgt dem verschobenen Blatt (${c.appliedX})")
        check(maxStep <= 0.0011f, "kein Sprung (größter Schritt ${maxStep * 1000} mm)")
        // 180°-Mehrdeutigkeit: um 179° gedrehtes Blatt = fast keine Drehung
        val d = SheetCorrection(L, S); repeat(5) { d.measure(sheet(0f, 0f, Math.toRadians(179.0).toFloat())) }
        check(abs(d.targetT) < 0.03f, "Achse ohne Richtung (${d.targetT})")
        // Umrechnung Anker ↔ nachgeführtes Blatt
        val p = c.toAnchor(0.03f, -0.02f); val q = c.toSheet(p[0], p[1])
        check(abs(q[0] - 0.03f) < 1e-5f && abs(q[1] + 0.02f) < 1e-5f, "Umrechnung hin und zurück")
        // aus: angewendeter Versatz geht sanft auf 0
        c.enabled = false; settle(c)
        check(abs(c.appliedX) < 1e-4f, "Korrektur aus → zurück auf 0")
        println(String.format("Echtes Verschieben 5 cm: Foto folgt, größter Schritt je Bild %.2f mm; 179° → %.1f°", maxStep * 1000, Math.toDegrees(d.targetT.toDouble())))
    }

    @Test fun grenzwertUndSchleichendeVerschiebung() {
        val c = SheetCorrection(L, S)
        check(!c.measurePose(0.02f, 0f, 0f, full = false), "20 mm ohne ganzes Blatt: verworfen")
        check(c.measurePose(0.02f, 0f, 0f, full = true), "20 mm mit ganzem Blatt: erlaubt")
        // schleichend: alle 5 Messungen ruhig 2,5 mm weiter in dieselbe Richtung, nie ganzes Blatt
        val d = SheetCorrection(L, S); var x = 0f; var stopped = -1
        for (i in 0 until 40) { if (i % 5 == 0) x += 0.0025f; d.measurePose(x, 0f, 0f); repeat(10) { d.step(33f) }; if (d.frozen && stopped < 0) stopped = i }
        check(d.frozen && stopped in 0..39, "schleichende Verschiebung wird angehalten")
        check(d.appliedX < SheetCorrection.LIMIT_M + 1e-4f, "nie über den Grenzwert (${d.appliedX})")
        // ganzes Blatt hebt die Sperre auf
        d.measurePose(0.003f, 0f, 0f, full = true)
        check(!d.frozen, "ganzes Blatt hebt Sperre auf")
        // unruhige Messungen (± 2 mm) werden nicht übernommen
        val e = SheetCorrection(L, S); val r = Random(5)
        repeat(10) { e.measurePose(0.003f + (r.nextGaussian() * 0.002).toFloat(), 0f, 0f) }
        check(e.count <= 1, "unruhige Messungen kaum übernommen (${e.count})")
        println(String.format("Absicherung: 20 mm nur mit ganzem Blatt, schleichend angehalten nach %d Messungen bei %.1f mm, unruhig → Ziel %.1f mm, Korrekturen %d",
            stopped + 1, d.appliedX * 1000, e.targetX * 1000, e.count))
    }
}
