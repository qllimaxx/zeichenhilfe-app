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

/*
 * Korrektur gegen Verrutschen am Blatt (ab 0.6) – reiner Kotlin-Code, per Unit-Test prüfbar.
 * Autor: Benjamin Dormoros
 *
 * Das eingepasste Foto hängt an einem Anker in der Blattmitte (Blatt-Koordinaten = Anker-Koordinaten, x = lange Seite).
 * Im Modus Zeichnen wird das Blatt selten neu erkannt; seine Lage in Anker-Koordinaten ergibt den Versatz
 * (Verschiebung cx/cz in Metern, Drehung theta wie in PhotoPlacement). Das Foto wird um diesen Versatz nachgeführt:
 * gemittelt über mehrere Erkennungen, Ausreißer verworfen, nur bei klarem Versatz, sanft und ohne Sprünge.
 */
class SheetCorrection(val refLong: Float, val refShort: Float) {

    var enabled = true
    /** Ziel (gemittelter Messwert) und tatsächlich angewendeter Versatz. */
    var targetX = 0f; private set
    var targetZ = 0f; private set
    var targetT = 0f; private set
    var appliedX = 0f; private set
    var appliedZ = 0f; private set
    var appliedT = 0f; private set
    /** Anzahl übernommener Korrekturen (neues Ziel). */
    var count = 0; private set
    /** Letzte gültige Messung: Versatz zum Anker in Metern; −1 = noch keine. */
    var lastOffset = -1f; private set
    /** Letzte Messung gültig (Blatt sicher erkannt)? */
    var lastValid = false; private set
    var lastReason = "–"; private set

    private val win = ArrayDeque<FloatArray>()     // (x, z, t)

    /**
     * Eine Erkennung übernehmen: 4 Blattecken in Anker-Koordinaten (x0, z0, …, x3, z3, der Reihe nach).
     * Liefert true, wenn die Messung gültig war.
     */
    fun measure(c: FloatArray): Boolean {
        val why = validate(c)
        if (why != null) { lastValid = false; lastReason = why; return false }
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4f; val cz = (c[1] + c[3] + c[5] + c[7]) / 4f
        // Richtung der langen Seite (Mittel der beiden langen Seiten), Drehung wie PhotoPlacement: x-Achse → (cos t, −sin t)
        val s0 = hypot(c[2] - c[0], c[3] - c[1]); val s1 = hypot(c[4] - c[2], c[5] - c[3])
        // gegenüberliegende Seiten c0→c1 und c3→c2 sind gleich gerichtet
        val (ax, az) = if (s0 >= s1) ((c[2] - c[0]) + (c[4] - c[6])) to ((c[3] - c[1]) + (c[5] - c[7]))
                       else ((c[4] - c[2]) + (c[6] - c[0])) to ((c[5] - c[3]) + (c[7] - c[1]))
        var t = atan2(-az, ax)
        // Achse hat keine Richtung: auf −90°..90° bringen
        while (t > PI / 2) t -= PI.toFloat()
        while (t < -PI / 2) t += PI.toFloat()
        // fast quadratisches Blatt: lange/kurze Seite nicht unterscheidbar → auf −45°..45° bringen
        if (refLong < refShort * 1.06f) { while (t > PI / 4) t -= (PI / 2).toFloat(); while (t < -PI / 4) t += (PI / 2).toFloat() }
        lastValid = true; lastReason = ""
        lastOffset = hypot(cx, cz)
        win.addLast(floatArrayOf(cx, cz, t)); while (win.size > WINDOW) win.removeFirst()
        if (win.size < MIN_SAMPLES) return true
        // Median je Größe, dann Mittel der Messungen nahe am Median (Ausreißer verwerfen)
        val mx = median(win.map { it[0] }); val mz = median(win.map { it[1] }); val mt = median(win.map { it[2] })
        val inl = win.filter { hypot(it[0] - mx, it[1] - mz) <= OUTLIER_M && abs(it[2] - mt) <= OUTLIER_RAD }
        if (inl.size < MIN_SAMPLES) return true
        val nx = inl.map { it[0] }.average().toFloat(); val nz = inl.map { it[1] }.average().toFloat(); val nt = inl.map { it[2] }.average().toFloat()
        // nur bei klarem Unterschied zum bisherigen Ziel (Totzone gegen Zittern)
        if (hypot(nx - targetX, nz - targetZ) > DEADBAND_M || abs(nt - targetT) > DEADBAND_RAD) {
            targetX = nx; targetZ = nz; targetT = nt; count++
        }
        return true
    }

    /** Angewendeten Versatz sanft zum Ziel führen (je Bild aufrufen), höchstens [MAX_SPEED_M]/s. */
    fun step(dtMs: Float) {
        val tx = if (enabled) targetX else 0f; val tz = if (enabled) targetZ else 0f; val tt = if (enabled) targetT else 0f
        val k = min(1f, max(0f, dtMs / TAU_MS))
        var dx = (tx - appliedX) * k; var dz = (tz - appliedZ) * k; var dt = (tt - appliedT) * k
        val maxD = MAX_SPEED_M * dtMs / 1000f; val maxR = MAX_SPEED_RAD * dtMs / 1000f
        val d = hypot(dx, dz); if (d > maxD) { dx *= maxD / d; dz *= maxD / d }
        if (abs(dt) > maxR) dt = if (dt > 0) maxR else -maxR
        appliedX += dx; appliedZ += dz; appliedT += dt
    }

    fun reset() { win.clear(); targetX = 0f; targetZ = 0f; targetT = 0f; appliedX = 0f; appliedZ = 0f; appliedT = 0f; count = 0; lastOffset = -1f; lastValid = false; lastReason = "–" }

    /**
     * Ist das gemessene Viereck sicher das ganze, unverdeckte Blatt? Rechteck mit der bekannten Größe:
     * jede Seite ±max(3 mm, 2 %), Ecken 90° ± 2,5°. Eine verdeckte Ecke (Hand/Stift) fällt hier durch.
     */
    fun validate(c: FloatArray): String? {
        val len = FloatArray(4) { i -> hypot(c[(2 * i + 2) % 8] - c[2 * i], c[(2 * i + 3) % 8] - c[2 * i + 1]) }
        val longFirst = len[0] + len[2] >= len[1] + len[3]
        for (i in 0 until 4) {
            val ref = if ((i % 2 == 0) == longFirst) refLong else refShort
            if (abs(len[i] - ref) > max(0.003f, 0.02f * ref)) return "Größe passt nicht (verdeckt?)"
        }
        for (i in 0 until 4) {
            val ax = c[(2 * i + 6) % 8] - c[2 * i]; val az = c[(2 * i + 7) % 8] - c[2 * i + 1]
            val bx = c[(2 * i + 2) % 8] - c[2 * i]; val bz = c[(2 * i + 3) % 8] - c[2 * i + 1]
            val cosv = (ax * bx + az * bz) / (hypot(ax, az) * hypot(bx, bz))
            val ang = Math.toDegrees(acos(cosv.toDouble().coerceIn(-1.0, 1.0)))
            if (abs(ang - 90) > 2.5) return "kein Rechteck (verdeckt?)"
        }
        return null
    }

    /** Matrix des angewendeten Versatzes (Anker-Koordinaten, spaltenweise): Verschiebung · Ry(theta). */
    fun matrix(): FloatArray {
        val co = cos(appliedT); val s = sin(appliedT)
        return floatArrayOf(co, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, co, 0f, appliedX, 0f, appliedZ, 1f)
    }

    /** Anker-Punkt (x, z) → nachgeführte Blatt-Koordinaten (Umkehrung von [matrix]). */
    fun toSheet(x: Float, z: Float): FloatArray {
        val dx = x - appliedX; val dz = z - appliedZ
        val co = cos(-appliedT); val s = sin(-appliedT)
        return floatArrayOf(co * dx + s * dz, -s * dx + co * dz)
    }

    /** Blatt-Koordinaten (x, z) → Anker-Punkt. */
    fun toAnchor(x: Float, z: Float): FloatArray {
        val co = cos(appliedT); val s = sin(appliedT)
        return floatArrayOf(co * x + s * z + appliedX, -s * x + co * z + appliedZ)
    }

    private fun median(v: List<Float>): Float { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f }

    companion object {
        const val WINDOW = 5
        const val MIN_SAMPLES = 3
        const val OUTLIER_M = 0.004f                     // 4 mm vom Median
        const val OUTLIER_RAD = (1.5 * PI / 180).toFloat()
        const val DEADBAND_M = 0.001f                    // 1 mm
        const val DEADBAND_RAD = (0.3 * PI / 180).toFloat()
        const val TAU_MS = 400f
        const val MAX_SPEED_M = 0.03f                    // 3 cm/s
        const val MAX_SPEED_RAD = (8 * PI / 180).toFloat()
    }
}
