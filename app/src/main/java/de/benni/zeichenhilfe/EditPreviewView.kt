package de.benni.zeichenhilfe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Vorschau im Bearbeiten-Bildschirm (ab 0.4): Bild eingepasst, dahinter ein Schachbrett (zeigt durchsichtige
 * Stellen), beim Zuschneiden ein Rahmen mit Eck- und Kantengriffen, außen abgedunkelt.
 * Autor: Benjamin Dormoros
 */
class EditPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var bmp: Bitmap? = null
    /** Zuschnitt-Rahmen (normiert im angezeigten Bild); nur wenn [showCrop]. */
    var crop = CropRect.FULL
        set(v) { field = v; invalidate() }
    var showCrop = false
        set(v) { field = v; invalidate() }
    /** Seitenverhältnis-Vorgabe (Pixel) oder null = frei; Pixelgröße des gedrehten Bilds. */
    var aspectPx: Float? = null
    var turnedW = 1f
    var turnedH = 1f
    var onCropChange: ((CropRect) -> Unit)? = null
    var onCropEnd: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val imgRect = RectF()
    private val tmp = RectF()
    private val paintBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val checkA = Paint().apply { color = Color.rgb(205, 205, 205) }
    private val checkB = Paint().apply { color = Color.rgb(240, 240, 240) }
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.WHITE }
    private val thirds = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * d; color = Color.argb(90, 255, 255, 255) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Neues Vorschaubild setzen; liefert das alte zurück (darf danach freigegeben werden). */
    fun setImage(b: Bitmap?): Bitmap? { val old = bmp; bmp = b; invalidate(); return old }

    private fun layoutImage(): Boolean {
        val b = bmp ?: return false
        val pad = 16f * d
        val aw = width - 2 * pad; val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0) return false
        val k = min(aw / b.width, ah / b.height)
        val w = b.width * k; val h = b.height * k
        imgRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        if (!layoutImage()) return
        // Schachbrett hinter dem Bild
        val sq = 8f * d
        c.save(); c.clipRect(imgRect)
        var y = imgRect.top; var row = 0
        while (y < imgRect.bottom) {
            var x = imgRect.left; var col = row % 2
            while (x < imgRect.right) { c.drawRect(x, y, x + sq, y + sq, if (col % 2 == 0) checkA else checkB); x += sq; col++ }
            y += sq; row++
        }
        c.restore()
        c.drawBitmap(b, null, imgRect, paintBmp)
        if (!showCrop) return
        val r = cropPx(tmp)
        // außen abdunkeln
        c.drawRect(imgRect.left, imgRect.top, imgRect.right, r.top, dim)
        c.drawRect(imgRect.left, r.bottom, imgRect.right, imgRect.bottom, dim)
        c.drawRect(imgRect.left, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, imgRect.right, r.bottom, dim)
        // Drittel-Linien und Rahmen
        for (i in 1..2) {
            val xx = r.left + r.width() * i / 3; val yy = r.top + r.height() * i / 3
            c.drawLine(xx, r.top, xx, r.bottom, thirds); c.drawLine(r.left, yy, r.right, yy, thirds)
        }
        c.drawRect(r, frame)
        // Eckwinkel und Kantengriffe
        val l = min(20f * d, min(r.width(), r.height()) / 3)
        path.reset()
        path.moveTo(r.left, r.top + l); path.lineTo(r.left, r.top); path.lineTo(r.left + l, r.top)
        path.moveTo(r.right - l, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + l)
        path.moveTo(r.right, r.bottom - l); path.lineTo(r.right, r.bottom); path.lineTo(r.right - l, r.bottom)
        path.moveTo(r.left + l, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - l)
        val e = min(12f * d, l / 2)
        val cx = r.centerX(); val cy = r.centerY()
        path.moveTo(cx - e, r.top); path.lineTo(cx + e, r.top)
        path.moveTo(cx - e, r.bottom); path.lineTo(cx + e, r.bottom)
        path.moveTo(r.left, cy - e); path.lineTo(r.left, cy + e)
        path.moveTo(r.right, cy - e); path.lineTo(r.right, cy + e)
        c.drawPath(path, handle)
    }

    private fun cropPx(out: RectF): RectF {
        out.set(imgRect.left + crop.l * imgRect.width(), imgRect.top + crop.t * imgRect.height(),
            imgRect.left + crop.r * imgRect.width(), imgRect.top + crop.b * imgRect.height())
        return out
    }

    private var active: CropTool.Handle? = null
    private var lastX = 0f
    private var lastY = 0f

    /** Welcher Griff liegt unter dem Finger? Berührfläche ca. 48 dp. */
    private fun hit(x: Float, y: Float): CropTool.Handle? {
        val r = cropPx(tmp); val t = 24f * d
        val nearL = abs(x - r.left) < t; val nearR = abs(x - r.right) < t; val nearT = abs(y - r.top) < t; val nearB = abs(y - r.bottom) < t
        val inX = x > r.left - t && x < r.right + t; val inY = y > r.top - t && y < r.bottom + t
        return when {
            nearL && nearT -> CropTool.Handle.TL; nearR && nearT -> CropTool.Handle.TR
            nearR && nearB -> CropTool.Handle.BR; nearL && nearB -> CropTool.Handle.BL
            nearT && inX -> CropTool.Handle.T; nearB && inX -> CropTool.Handle.B
            nearL && inY -> CropTool.Handle.L; nearR && inY -> CropTool.Handle.R
            r.contains(x, y) -> CropTool.Handle.MOVE
            else -> null
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!showCrop || bmp == null || !layoutImage()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = hit(e.x, e.y); lastX = e.x; lastY = e.y
                if (active != null) parent?.requestDisallowInterceptTouchEvent(true)
                return active != null
            }
            MotionEvent.ACTION_MOVE -> {
                val h = active ?: return false
                val dx = (e.x - lastX) / imgRect.width(); val dy = (e.y - lastY) / imgRect.height()
                lastX = e.x; lastY = e.y
                if (hypot(dx, dy) > 0f) {
                    crop = CropTool.drag(crop, h, dx, dy, aspectPx, turnedW, turnedH)
                    onCropChange?.invoke(crop)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (active != null) onCropEnd?.invoke(); active = null }
        }
        return true
    }
}
