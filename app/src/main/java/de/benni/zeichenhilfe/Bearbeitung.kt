package de.benni.zeichenhilfe

/*
 * Werkzeuge des Bearbeiten-Bildschirms (ab 0.4 mit Funktion, siehe PhotoEdit.kt und EditScreen.kt).
 * Autor: Benjamin Dormoros
 */

/** Werkzeuge der Bearbeiten-Ansicht. */
enum class Werkzeug(val titel: String) {
    ZUSCHNEIDEN("Zuschneiden"), DREHEN("Drehen"), TON("Ton"), LINIEN("Linien")
}
