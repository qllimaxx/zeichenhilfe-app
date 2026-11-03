package de.benni.zeichenhilfe

/**
 * Zustände der Oberfläche (Design M1 „Klar“), ohne Android-Abhängigkeit – damit per Unit-Test prüfbar.
 * Zeiten in Millisekunden (beliebige, fortlaufende Uhr, z. B. SystemClock.uptimeMillis()).
 */
enum class UiMode { ALIGN, DRAW }

/**
 * Hauptknopf unten rechts. Die Beschriftung steht nur hier und in strings.xml – in 0.3 wird
 * PLACE durch FIT („Einpassen“) ersetzt, ohne die Oberfläche anzufassen.
 */
enum class MainAction { PLACE, DONE }

class UiState {
    var mode = UiMode.ALIGN; private set
    var locked = false; private set
    var hasPhoto = false
    var placed = false
    var menuOpen = false; private set
    var infoOpen = false; private set

    private var controlsUntil = 0L     // Zeichnen: Bedienung sichtbar bis
    private var gestureHintUntil = 0L  // Ausrichten: Gesten-Hinweis sichtbar bis
    private var drawHintUntil = 0L     // Zeichnen: einmaliger Hinweis sichtbar bis
    private var zoomPillUntil = 0L     // Zoom-Anzeige sichtbar bis
    private var toastUntil = 0L        // kurzer Hinweis (z. B. „Gesperrt“)
    var toastText = ""; private set
    /** true, sobald der Zeichnen-Hinweis einmal gezeigt wurde (wird von außen gespeichert/geladen). */
    var drawHintSeen = false

    /** Was der Hauptknopf gerade tut. */
    val mainAction: MainAction get() = if (placed) MainAction.DONE else MainAction.PLACE

    fun setMode(m: UiMode, now: Long) {
        if (m == mode) return
        mode = m
        menuOpen = false
        if (m == UiMode.DRAW) {
            controlsUntil = 0L
            if (!drawHintSeen) { drawHintSeen = true; drawHintUntil = now + DRAW_HINT_MS }
        } else {
            locked = false                       // Ausrichten ist nie gesperrt
            if (placed) gestureHintUntil = now + GESTURE_HINT_MS
        }
    }

    /** Foto wurde (neu) auf die Fläche gelegt. */
    fun onPlaced(now: Long) {
        placed = true
        gestureHintUntil = now + GESTURE_HINT_MS
    }

    fun toggleLock(now: Long) {
        if (mode != UiMode.DRAW) return
        locked = !locked
        showToast(if (locked) "Gesperrt – Schloss antippen zum Entsperren" else "Entsperrt", now)
        if (!locked) controlsUntil = now + CONTROLS_MS
    }

    /** Darf eine Geste auf dem Bild (Zoom, Verschieben, Antippen) wirken? */
    fun gesturesAllowed(): Boolean = !(mode == UiMode.DRAW && locked) && !infoOpen

    /** Einfaches Antippen des Bildes. Liefert true, wenn das Antippen „verbraucht“ wurde. */
    fun onTapImage(now: Long): Boolean {
        if (menuOpen) { menuOpen = false; return true }
        if (mode == UiMode.DRAW) {
            if (locked) { showToast("Gesperrt – Schloss antippen zum Entsperren", now); return true }
            controlsUntil = if (controlsVisible(now)) 0L else now + CONTROLS_MS
            return true
        }
        return false     // Ausrichten: Antippen platziert das Foto (macht die Activity)
    }

    /** Jede Bedienung der eingeblendeten Elemente verlängert die Anzeigezeit. */
    fun onControlUsed(now: Long) { if (mode == UiMode.DRAW && controlsUntil > 0L) controlsUntil = now + CONTROLS_MS }

    fun onZoomChanged(now: Long) { zoomPillUntil = now + ZOOM_PILL_MS }

    fun toggleMenu(now: Long) { menuOpen = !menuOpen; onControlUsed(now) }
    fun closeMenu() { menuOpen = false }
    fun setInfoOpen(open: Boolean) { infoOpen = open; if (open) menuOpen = false }

    fun showToast(text: String, now: Long) { toastText = text; toastUntil = now + TOAST_MS }

    // ---------- Sichtbarkeit ----------
    /** Zeichnen: Bedienung (Mehr, Deckkraft, „Ausrichten“) eingeblendet? In Ausrichten immer. */
    fun controlsVisible(now: Long): Boolean = mode == UiMode.ALIGN || (!locked && now < controlsUntil) || menuOpen
    fun lockVisible(): Boolean = mode == UiMode.DRAW
    fun mainButtonVisible(): Boolean = mode == UiMode.ALIGN
    fun backToAlignVisible(now: Long): Boolean = mode == UiMode.DRAW && controlsVisible(now)
    fun opacitySliderVisible(now: Long): Boolean = controlsVisible(now) && !menuOpen
    fun gestureHintVisible(now: Long): Boolean = mode == UiMode.ALIGN && placed && now < gestureHintUntil
    fun drawHintVisible(now: Long): Boolean = mode == UiMode.DRAW && now < drawHintUntil
    fun zoomPillVisible(now: Long, zoom: Float): Boolean = mode == UiMode.DRAW && zoom > 1.001f && now < zoomPillUntil
    fun toastVisible(now: Long): Boolean = now < toastUntil

    /**
     * Status-Pille oben: in Ausrichten immer; in Zeichnen nur, wenn etwas nicht stimmt (Tracking verloren).
     */
    fun statusVisible(trackingOk: Boolean): Boolean = mode == UiMode.ALIGN || !trackingOk

    companion object {
        const val CONTROLS_MS = 3500L
        const val GESTURE_HINT_MS = 4000L
        const val DRAW_HINT_MS = 4500L
        const val ZOOM_PILL_MS = 1200L
        const val TOAST_MS = 2000L

        /** Text der Status-Pille. */
        fun statusText(sessionReady: Boolean, tracking: Boolean, failure: String, planeSeen: Boolean,
                       hasPhoto: Boolean, placed: Boolean): String = when {
            !sessionReady -> "AR startet …"
            !tracking -> "Tracking verloren" + if (failure.isNotEmpty()) " – $failure" else ""
            !planeSeen && !placed -> "Handy langsam über den Tisch bewegen"
            !hasPhoto -> "Fläche erkannt – Foto wählen"
            !placed -> "Fläche erkannt – tippe zum Platzieren"
            else -> "Foto liegt auf dem Tisch"
        }
    }
}
