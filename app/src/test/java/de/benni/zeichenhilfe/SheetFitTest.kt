package de.benni.zeichenhilfe

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Einpassen des Fotos aufs Blatt: Rand, Seitenverhältnis, lange Seite an langer Seite, Drehung. */
class SheetFitTest {

    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }
    private fun near(a: Float, b: Float, eps: Float = 1e-4f) = abs(a - b) < eps

    /** Ausdehnung des eingepassten Fotos entlang Blatt-x (lang) und Blatt-z (kurz). */
    private fun extent(f: SheetFit.Fit): FloatArray {
        val p = PhotoPlacement(); p.width = f.width; p.aspect = f.width / f.height; p.theta = f.theta
        val xs = listOf(-0.5f to -0.5f, 0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f).map { (u, v) -> p.corner(u, v) }
        return floatArrayOf(xs.maxOf { it[0] } - xs.minOf { it[0] }, xs.maxOf { it[1] } - xs.minOf { it[1] })
    }

    private fun case(name: String, sheetLong: Float, sheetShort: Float, aspect: Float) {
        val f = SheetFit.fit(sheetLong, sheetShort, aspect)!!
        val e = extent(f)
        val randLang = (sheetLong - e[0]) / 2 * 100; val randKurz = (sheetShort - e[1]) / 2 * 100
        println(String.format("%-34s Foto %.1f × %.1f cm, Rand lang %.2f cm / kurz %.2f cm, Seitenverhältnis %.4f (soll %.4f), θ = %.0f°",
            name, f.width * 100, f.height * 100, randLang, randKurz, f.width / f.height, aspect, Math.toDegrees(f.theta.toDouble())))
        check(near(f.width / f.height, aspect, 1e-4f), "$name: Seitenverhältnis")
        check(e[0] >= e[1] - 1e-5f, "$name: lange Fotoseite entlang der langen Blattseite")
        check(randLang >= 1f - 1e-3f && randKurz >= 1f - 1e-3f, "$name: Rand mindestens 1 cm")
        check(near(randLang, 1f, 1e-3f) || near(randKurz, 1f, 1e-3f), "$name: so groß wie möglich (ein Rand genau 1 cm)")
    }

    @Test fun fotoHochUndQuerAufBlattHochUndQuer() {
        // A4 – ob das Blatt „hoch“ oder „quer“ liegt, ist in Blatt-Koordinaten gleich (x = lange Seite);
        // geprüft wird zusätzlich ein fast quadratisches Blatt und ein sehr langes Foto.
        case("Foto quer 4:3 auf A4", 0.297f, 0.210f, 4f / 3f)
        case("Foto hoch 3:4 auf A4", 0.297f, 0.210f, 3f / 4f)
        case("Foto quer 16:9 auf A4", 0.297f, 0.210f, 16f / 9f)
        case("Foto hoch 9:16 auf A4", 0.297f, 0.210f, 9f / 16f)
        case("Foto quadratisch auf A4", 0.297f, 0.210f, 1f)
        case("Foto quer 3:2 auf A3", 0.420f, 0.297f, 1.5f)
        case("Foto hoch 2:3 auf 20 × 20 cm", 0.20f, 0.20f, 2f / 3f)
        case("Panorama 3:1 auf A5", 0.210f, 0.148f, 3f)
    }

    @Test fun bildUntenZeigtZurKamera() {
        for (aspect in listOf(1.5f, 0.66f)) for (cam in listOf(0f to 1f, 0f to -1f, 1f to 0f, -1f to 0f)) {
            val f = SheetFit.fit(0.297f, 0.210f, aspect, camX = cam.first, camZ = cam.second)!!
            val downX = sin(f.theta); val downZ = cos(f.theta)
            check(downX * cam.first + downZ * cam.second >= -1e-5f, "Bild-unten zur Kamera (aspect $aspect, cam $cam)")
        }
        check(SheetFit.fit(0.015f, 0.015f, 1f) == null, "Blatt kleiner als 2 cm Rand → kein Einpassen")
    }

    @Test fun blattAusWeltEckenUndQuaternion() {
        // A4, um 30° um die Normale gedreht, 1 m über dem Ursprung
        val a = PI / 6; val ux = cos(a).toFloat(); val uz = sin(a).toFloat()      // lange Seite
        val vx = -uz; val vz = ux                                                  // kurze Seite
        val c = FloatArray(12); var k = 0
        for ((s, t) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
            c[k++] = 0.3f + s * 0.1485f * ux + t * 0.105f * vx; c[k++] = 1f; c[k++] = -0.2f + s * 0.1485f * uz + t * 0.105f * vz
        }
        val sh = Sheet.fromCorners(c, floatArrayOf(0f, 1f, 0f))!!
        println("Blatt aus Weltecken: ${sh.sizeText()}, Mitte (${sh.center.joinToString { String.format("%.3f", it) }})")
        check(near(sh.long, 0.297f) && near(sh.short, 0.210f), "Größe")
        check(abs(abs(sh.xAxis[0] * ux + sh.xAxis[2] * uz) - 1f) < 1e-4f, "lange Achse")
        val q = sh.quaternion()
        val rx = Quat.rotate(q, floatArrayOf(1f, 0f, 0f)); val ry = Quat.rotate(q, floatArrayOf(0f, 1f, 0f)); val rz = Quat.rotate(q, floatArrayOf(0f, 0f, 1f))
        for (i in 0 until 3) check(near(rx[i], sh.xAxis[i]) && near(ry[i], sh.yAxis[i]) && near(rz[i], sh.zAxis[i]), "Quaternion dreht die Achsen richtig")
        // Blatt-Koordinaten einer Ecke
        val l = sh.toLocal(floatArrayOf(c[0], c[1], c[2]))
        check(near(abs(l[0]), 0.1485f) && near(abs(l[1]), 0.105f), "Ecke in Blatt-Koordinaten")
    }
}
