package de.benni.zeichenhilfe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft Zustände und Zeitsteuerung der Oberfläche (läuft in GitHub Actions mit). */
class UiStateTest {

    private fun check(b: Boolean, what: String) { if (!b) throw AssertionError(what) }

    @Test
    fun hauptknopfJeNachLage() {
        val u = UiState()
        check(u.mainAction == MainAction.PLACE, "ohne Foto: Platzieren")
        u.hasPhoto = true
        check(u.mainAction == MainAction.PLACE, "Foto gewählt, nicht platziert: Platzieren")
        u.onPlaced(0)
        check(u.mainAction == MainAction.DONE, "platziert: Fertig")
        println("Hauptknopf: ohne Foto PLACE, mit Foto PLACE, platziert ${u.mainAction}")
    }

    @Test
    fun zeichnenBlendetBedienungAusUndAntippenZeigtSieKurz() {
        val u = UiState(); u.hasPhoto = true; u.onPlaced(0)
        check(u.controlsVisible(0) && u.mainButtonVisible(), "Ausrichten: Bedienung sichtbar")
        u.setMode(UiMode.DRAW, 1000)
        check(!u.controlsVisible(1001), "Zeichnen: Bedienung aus")
        check(u.lockVisible(), "Sperre sichtbar")
        check(!u.mainButtonVisible(), "Hauptknopf weg")
        check(u.onTapImage(2000), "Antippen verbraucht")
        check(u.controlsVisible(2000 + 3000) && u.backToAlignVisible(5000), "3 s nach Antippen noch sichtbar")
        check(!u.controlsVisible(2000 + UiState.CONTROLS_MS + 1), "danach wieder aus")
        // Bedienen verlängert
        u.onTapImage(10_000); u.onControlUsed(12_000)
        check(u.controlsVisible(12_000 + 3000), "Bedienen verlängert die Anzeige")
        // erneutes Antippen blendet sofort aus
        u.onTapImage(13_000)
        check(!u.controlsVisible(13_001), "zweites Antippen blendet aus")
        println("Zeichnen: Bedienung nach Antippen ${UiState.CONTROLS_MS} ms sichtbar, zweites Antippen blendet aus")
    }

    @Test
    fun sperreBlockiertGesten() {
        val u = UiState()
        u.toggleLock(0)
        check(!u.locked, "in Ausrichten keine Sperre")
        u.setMode(UiMode.DRAW, 0)
        u.toggleLock(100)
        check(u.locked && !u.gesturesAllowed(), "gesperrt: keine Gesten")
        check(u.onTapImage(200) && !u.controlsVisible(200), "Antippen zeigt keine Bedienung, nur Hinweis")
        check(u.toastVisible(300) && u.toastText.startsWith("Gesperrt"), "Hinweis Gesperrt")
        u.toggleLock(400)
        check(!u.locked && u.gesturesAllowed(), "entsperrt")
        u.toggleLock(500)
        u.setMode(UiMode.ALIGN, 600)
        check(!u.locked, "Wechsel zu Ausrichten hebt die Sperre auf")
        println("Sperre: Gesten aus, Antippen nur Hinweis, Ausrichten entsperrt")
    }

    @Test
    fun zeichnenHinweisNurBeimErstenMal() {
        val u = UiState()
        u.setMode(UiMode.DRAW, 0)
        check(u.drawHintVisible(1000), "erstes Mal: Hinweis")
        check(!u.drawHintVisible(UiState.DRAW_HINT_MS + 1), "verschwindet")
        u.setMode(UiMode.ALIGN, 6000); u.setMode(UiMode.DRAW, 7000)
        check(!u.drawHintVisible(7100), "zweites Mal: kein Hinweis")
        val v = UiState(); v.drawHintSeen = true; v.setMode(UiMode.DRAW, 0)
        check(!v.drawHintVisible(10), "gespeichert gesehen: kein Hinweis")
    }

    @Test
    fun zoomAnzeigeUndGestenHinweis() {
        val u = UiState(); u.hasPhoto = true
        u.onPlaced(0)
        check(u.gestureHintVisible(3000) && !u.gestureHintVisible(UiState.GESTURE_HINT_MS + 1), "Gesten-Hinweis 4 s")
        u.setMode(UiMode.DRAW, 5000)
        u.onZoomChanged(6000)
        check(u.zoomPillVisible(6500, 2f), "Zoom-Pille beim Zoomen")
        check(!u.zoomPillVisible(6000 + UiState.ZOOM_PILL_MS + 1, 2f), "kurz danach weg")
        check(!u.zoomPillVisible(6500, 1f), "bei 1× keine Pille")
    }

    @Test
    fun mehrMenueUndStatus() {
        val u = UiState()
        u.toggleMenu(0)
        check(u.menuOpen && !u.opacitySliderVisible(0), "Menü offen: Deckkraft-Regler weg")
        check(u.onTapImage(10) && !u.menuOpen, "Antippen schließt das Menü")
        u.setInfoOpen(true)
        check(!u.gesturesAllowed(), "Info offen: keine Bildgesten")
        assertEquals(true, u.statusVisible(true))
        u.setInfoOpen(false); u.setMode(UiMode.DRAW, 0)
        check(!u.statusVisible(true) && u.statusVisible(false), "Zeichnen: Status nur bei Tracking-Verlust")
        val t = UiState.statusText(true, false, "zu dunkel", true, true, true)
        assertTrue(t == "Tracking verloren – zu dunkel")
        assertTrue(UiState.statusText(true, true, "", false, false, false) == "Handy langsam über den Tisch bewegen")
        assertTrue(UiState.statusText(true, true, "", true, true, false) == "Fläche erkannt – tippe zum Platzieren")
    }
}
