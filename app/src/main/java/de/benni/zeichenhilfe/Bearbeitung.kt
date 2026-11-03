package de.benni.zeichenhilfe

/*
 * Gerüst für den Bearbeiten-Bildschirm (Design M1). In 0.2 NICHT angezeigt und ohne Funktion.
 * Gehört zu res/layout/bearbeiten.xml. Autor: Benjamin Dormoros
 */

/** Werkzeuge der späteren Bearbeiten-Ansicht. */
enum class Werkzeug(val titel: String) {
    ZUSCHNEIDEN("Zuschneiden"), DREHEN("Drehen"), TON("Ton"), LINIEN("Linien")
}

/** Einstellungen, die später auf das Foto angewendet werden (noch ohne Wirkung). */
data class FotoBearbeitung(
    val drehungGrad: Int = 0,          // 0, 90, 180, 270
    val helligkeit: Float = 0f,        // -1..1
    val kontrast: Float = 0f,          // -1..1
    val nurLinien: Boolean = false,
    val linienStaerke: Float = 0.5f    // 0..1
)
