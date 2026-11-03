package de.benni.zeichenhilfe

/*
 * Werkzeuge des Bearbeiten-Bildschirms (siehe PhotoEdit.kt, CutMask.kt und EditScreen.kt).
 * Reihenfolge = Werkzeugreihe. Autor: Benjamin Dormoros
 */

/** Werkzeuge der Bearbeiten-Ansicht (Freistellen ab 0.5). */
enum class Werkzeug(val titel: String) {
    ZUSCHNEIDEN("Zuschneiden"), FREISTELLEN("Freistellen"), DREHEN("Drehen"), TON("Ton"), LINIEN("Linien")
}

/** Freistellen: Art des Werkzeugs (ab 0.5). */
enum class CutTool { LASSO, ERASE, RESTORE }
