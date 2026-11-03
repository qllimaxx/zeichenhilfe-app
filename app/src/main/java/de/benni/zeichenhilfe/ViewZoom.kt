package de.benni.zeichenhilfe

import kotlin.math.max
import kotlin.math.min

/**
 * Digitaler Ansichts-Zoom (wie in der Web-App): wirkt auf die GESAMTE Ansicht.
 * In NDC (−1..1): gezoomt = s · ungezoomt + t. Dieselbe Abbildung wird auf das Kamerabild
 * (Eckpunkte des Hintergrund-Rechtecks) und auf das Foto (links an die Projektionsmatrix
 * multipliziert) angewendet – deshalb bleibt das Foto an seiner Stelle im Kamerabild.
 */
class ViewZoom {
    var s = 1f; private set
    var tx = 0f; private set
    var ty = 0f; private set

    fun reset() { s = 1f; tx = 0f; ty = 0f }

    /** Verschiebung nicht über den Bildrand hinaus (Bild deckt den Bildschirm immer ganz). */
    private fun clamp() {
        s = min(MAX, max(MIN, s))
        val lim = s - 1f
        tx = min(lim, max(-lim, tx))
        ty = min(lim, max(-lim, ty))
    }

    /** 1 Finger: Ausschnitt um (dx, dy) Bildschirm-Pixel verschieben. */
    fun pan(dxPx: Float, dyPx: Float, w: Int, h: Int) {
        tx += 2f * dxPx / w
        ty -= 2f * dyPx / h
        clamp()
    }

    /**
     * 2 Finger: Zoom um [ratio]. Der Punkt der Szene, der vorher unter der Fingermitte lag ([midPrev]),
     * liegt danach unter der neuen Fingermitte ([midCur]) – solange die Randbegrenzung nicht greift.
     */
    fun pinch(midPrevX: Float, midPrevY: Float, midCurX: Float, midCurY: Float, ratio: Float, w: Int, h: Int) {
        val mpx = ScreenNdc.toNdcX(midPrevX, w); val mpy = ScreenNdc.toNdcY(midPrevY, h)
        val mcx = ScreenNdc.toNdcX(midCurX, w); val mcy = ScreenNdc.toNdcY(midCurY, h)
        val ux = (mpx - tx) / s; val uy = (mpy - ty) / s
        s = min(MAX, max(MIN, s * ratio))
        tx = mcx - s * ux
        ty = mcy - s * uy
        clamp()
    }

    /** Gezoomte Bildschirm-Pixel -> ungezoomte Pixel (für Antippen/Gesten auf dem Papier). */
    fun unzoom(xPx: Float, yPx: Float, w: Int, h: Int): FloatArray {
        val nx = (ScreenNdc.toNdcX(xPx, w) - tx) / s
        val ny = (ScreenNdc.toNdcY(yPx, h) - ty) / s
        return floatArrayOf(ScreenNdc.toPxX(nx, w), ScreenNdc.toPxY(ny, h))
    }

    /** Ungezoomte Pixel -> gezoomte Pixel. */
    fun zoom(xPx: Float, yPx: Float, w: Int, h: Int): FloatArray {
        val nx = ScreenNdc.toNdcX(xPx, w) * s + tx
        val ny = ScreenNdc.toNdcY(yPx, h) * s + ty
        return floatArrayOf(ScreenNdc.toPxX(nx, w), ScreenNdc.toPxY(ny, h))
    }

    /** Matrix für den Clip-Raum: (x, y, z, w) -> (s·x + tx·w, s·y + ty·w, z, w). Spaltenweise. */
    fun clipMatrix(): FloatArray = clipMatrix(s, tx, ty)

    companion object {
        const val MIN = 1f
        const val MAX = 5f
        fun clipMatrix(s: Float, tx: Float, ty: Float): FloatArray =
            floatArrayOf(s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f, 0f, tx, ty, 0f, 1f)
    }
}
