package de.benni.zeichenhilfe

import org.junit.Test

/** 0.8: Speichern/Laden des Zustands und der Maske, auch mit beschädigten Daten. */
class PersistTest {
    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }

    @Test fun zustandHinUndZurueck() {
        val s = SavedState(opacity = 0.62f, focusFixed = true, hasPhoto = true, sheetLong = 0.21f, sheetShort = 0.148f, corrEnabled = false,
            edit = EditSettings(rot90 = 3, flipH = true, fineDeg = -7.5f, crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f), cropMode = CropMode.SQUARE,
                brightness = 0.3f, contrast = -0.2f, gray = true, lines = true, sensitivity = 0.7f, lineWidth = 2, transparent = true, detail = 0.2f),
            rel = PhotoOnSheet(0.01f, -0.005f, 1.5708f, 0.19f, 0.21f, 0.148f))
        val d = Persist.decode(Persist.encode(s))
        check(d == s, "gleich nach Laden: $d")
        println("Zustand: Speichern und Laden ergibt denselben Zustand")
    }

    @Test fun beschaedigteDaten() {
        check(Persist.decode(null) == null && Persist.decode("") == null && Persist.decode("Unsinn\n\u0000\u0001") == null, "leer/Unsinn → nichts")
        val d = Persist.decode("version=1\nopacity=7\nrot90=9\ncrop=0.5,0.5,0.4,0.4\nfineDeg=NaN\nrel=1,2\ncropMode=XYZ\nlineWidth=-3\n")!!
        check(d.opacity == 0.5f && d.edit.rot90 == 0 && d.edit.crop == CropRect.FULL && d.edit.fineDeg == 0f && d.rel == null &&
              d.edit.cropMode == CropMode.FREE && d.edit.lineWidth == 1, "kaputte Werte → Standard ($d)")
        // abgeschnittene Datei
        val full = Persist.encode(SavedState(opacity = 0.8f, hasPhoto = true))
        val cut = Persist.decode(full.substring(0, full.length / 2))
        check(cut != null && cut.opacity == 0.8f, "halbe Datei: lesbarer Teil bleibt")
        println("Beschädigt: leer/Unsinn → kein Zustand, unsinnige Werte → Standard, halbe Datei → lesbarer Teil")
    }

    @Test fun maskeMitPruefsumme() {
        val m = CutMask.lasso(null, 300, 200, floatArrayOf(0.1f, 0.1f, 0.6f, 0.2f, 0.5f, 0.9f), true)
        val b = Persist.encodeMask(m)
        val d = Persist.decodeMask(b, 300, 200)!!
        check(d.a.contentEquals(m.a), "Maske gleich")
        check(Persist.decodeMask(b, 301, 200) == null, "falsche Größe → verworfen")
        val broken = b.copyOf(); broken[broken.size / 2] = (broken[broken.size / 2] + 1).toByte()
        check(Persist.decodeMask(broken) == null, "ein Byte kaputt → verworfen")
        check(Persist.decodeMask(b.copyOf(b.size / 2)) == null && Persist.decodeMask(ByteArray(10)) == null, "abgeschnitten/Müll → verworfen")
        println("Maske: ${m.a.size} Byte → ${b.size} Byte gepackt; falsche Größe, kaputtes Byte, abgeschnitten → verworfen")
    }
}
