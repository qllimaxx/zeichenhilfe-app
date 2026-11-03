package de.benni.zeichenhilfe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Vorschau im Bearbeiten-Bildschirm (ab 0.4, Freistellen ab 0.5).
 * - Bild eingepasst, dahinter ein dezentes Schachbrett (zeigt durchsichtige Stellen).
 * - Freistellen-Maske als eigene Alpha-Bitmap darübergelegt (DST_IN), beim Malen sofort aktualisiert.
 * - Zuschneiden: Rahmen mit Eck- und Kantengriffen, außen abgedunkelt.
 * - Freistellen: Umfahren (Lasso) oder Pinsel, Lupe in der gegenüberliegenden oberen Ecke,
 *   zwei Finger zoomen/verschieben (ein angefangener Strich wird dabei abgebrochen).
 * Autor: Benjamin Dormoros
 */
class EditPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { VIEW, CROP, LASSO, BRUSH }

    /** Rückmeldungen beim Freistellen; Koordinaten in Pixeln des angezeigten Vorschaubilds. */
    interface CutListener {
        fun onStrokeStart(x: Float, y: Float)
        fun onStrokeMove(x: Float, y: Float)
        fun onStrokeEnd()
        fun onStrokeCancel()
        fun onLasso(points: FloatArray)
    }

    private var bmp: Bitmap? = null
    private var cutBmp: Bitmap? = null       // Alpha = sichtbar (nur Alpha-Kanal genutzt)
    var mode = Mode.VIEW
        set(v) { field = v; if (v != Mode.LASSO && v != Mode.BRUSH) resetZoom(); invalidate() }
    /** Zuschnitt-Rahmen (normiert im angezeigten Bild); nur im Modus CROP. */
    var crop = CropRect.FULL
        set(v) { field = v; invalidate() }
    var aspectPx: Float? = null
    var turnedW = 1f
    var turnedH = 1f
    var onCropChange: ((CropRect) -> Unit)? = null
    var onCropEnd: (() -> Unit)? = null
    var cutListener: CutListener? = null
    /** Pinselradius in Bildschirm-Pixeln (für Lupe und Kreis). */
    var brushRadiusPx = 20f

    private val d = resources.displayMetrics.density
    private val imgRect = RectF()
    private val tmp = RectF()
    private val paintBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paintCut = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val checkA = Paint().apply { color = Color.rgb(214, 214, 214) }
    private val checkB = Paint().apply { color = Color.rgb(240, 240, 240) }
    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = Color.WHITE }
    private val thirds = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f * d; color = Color.argb(90, 255, 255, 255) }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f * d; color = Color.argb(140, 0, 0, 0); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.WHITE }
    private val ringDark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = Color.argb(150, 0, 0, 0) }
    private val lupeBg = Paint().apply { color = Color.rgb(11, 11, 12) }
    private val path = Path()
    private val lassoPath = Path()
    private val clip = Path()

    // Zoom/Verschieben der Vorschau (nur Freistellen)
    private var zs = 1f
    private var zx = 0f
    private var zy = 0f
    fun resetZoom() { zs = 1f; zx = 0f; zy = 0f; invalidate() }

    /** Neues Vorschaubild (+ Sichtbarkeits-Bitmap) setzen; liefert die alten zurück (dürfen danach freigegeben werden). */
    fun setImage(b: Bitmap?, cut: Bitmap?): List<Bitmap> {
        val old = listOfNotNull(bmp, cutBmp).filter { it !== b && it !== cut }
        bmp = b; cutBmp = cut; invalidate()
        return old
    }
    val image: Bitmap? get() = bmp
    val cut: Bitmap? get() = cutBmp

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

    /** Bildpixel pro Bildschirmpixel (mit Zoom) – für die Pinselgröße. */
    fun outPxPerScreenPx(): Float { val b = bmp ?: return 1f; if (!layoutImage()) return 1f; return b.width / (imgRect.width() * zs) }

    private fun drawContent(c: Canvas) {
        val b = bmp ?: return
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
        val cb = cutBmp
        if (cb == null) c.drawBitmap(b, null, imgRect, paintBmp)
        else {
            val sc = c.saveLayer(imgRect, null)
            c.drawBitmap(b, null, imgRect, paintBmp)
            c.drawBitmap(cb, null, imgRect, paintCut)
            c.restoreToCount(sc)
        }
    }

    override fun onDraw(c: Canvas) {
        if (bmp == null || !layoutImage()) return
        c.save()
        c.translate(zx, zy); c.scale(zs, zs)
        drawContent(c)
        if (mode == Mode.LASSO && lassoActive) { c.drawPath(lassoPath, halo); c.drawPath(lassoPath, line) }
        c.restore()
        if (mode == Mode.CROP) drawCrop(c)
        if ((mode == Mode.BRUSH && strokeActive) || (mode == Mode.LASSO && lassoActive)) {
            if (mode == Mode.BRUSH) { c.drawCircle(fx, fy, brushRadiusPx, ringDark); c.drawCircle(fx, fy, brushRadiusPx, ring) }
            drawLupe(c)
        }
    }

    /** Runde Lupe (2,5×) in der oberen Ecke gegenüber dem Finger, mit Fadenkreuz und Pinselkreis. */
    private fun drawLupe(c: Canvas) {
        val r = 56f * d; val m = 12f * d; val zoom = 2.5f
        val cx = if (fx < width / 2f) width - m - r else m + r
        val cy = m + r
        c.save()
        clip.reset(); clip.addCircle(cx, cy, r, Path.Direction.CW)
        c.clipPath(clip)
        c.drawRect(cx - r, cy - r, cx + r, cy + r, lupeBg)
        // Fingerpunkt (Bildschirm) → Lupenmitte
        c.translate(cx, cy); c.scale(zoom, zoom); c.translate(-fx, -fy)
        c.translate(zx, zy); c.scale(zs, zs)
        drawContent(c)
        if (mode == Mode.LASSO) { c.drawPath(lassoPath, line) }
        c.restore()
        // Rahmen, Fadenkreuz, Pinselgröße
        c.drawCircle(cx, cy, r, ringDark); c.drawCircle(cx, cy, r, ring)
        val k = 9f * d
        c.drawLine(cx - k, cy, cx + k, cy, ringDark); c.drawLine(cx, cy - k, cx, cy + k, ringDark)
        c.drawLine(cx - k, cy, cx + k, cy, ring); c.drawLine(cx, cy - k, cx, cy + k, ring)
        if (mode == Mode.BRUSH) { val br = min(r - 2 * d, brushRadiusPx * zoom); c.drawCircle(cx, cy, br, ringDark); c.drawCircle(cx, cy, br, ring) }
    }

    private fun drawCrop(c: Canvas) {
        val r = cropPx(tmp)
        c.drawRect(imgRect.left, imgRect.top, imgRect.right, r.top, dim)
        c.drawRect(imgRect.left, r.bottom, imgRect.right, imgRect.bottom, dim)
        c.drawRect(imgRect.left, r.top, r.left, r.bottom, dim)
        c.drawRect(r.right, r.top, imgRect.right, r.bottom, dim)
        for (i in 1..2) {
            val xx = r.left + r.width() * i / 3; val yy = r.top + r.height() * i / 3
            c.drawLine(xx, r.top, xx, r.bottom, thirds); c.drawLine(r.left, yy, r.right, yy, thirds)
        }
        c.drawRect(r, frame)
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

    // ---------- Berührung ----------
    private var active: CropTool.Handle? = null
    private var lastX = 0f
    private var lastY = 0f
    private var fx = 0f              // Finger (Bildschirm) für Lupe/Pinselkreis
    private var fy = 0f
    private var strokeActive = false
    private var lassoActive = false
    private val lassoPts = ArrayList<Float>()
    private var pinch = false
    private var pd0 = 0f
    private var pmx = 0f
    private var pmy = 0f

    /** Bildschirm → Pixel des Vorschaubilds (mit Zoom). */
    private fun toImg(x: Float, y: Float): FloatArray {
        val b = bmp!!
        val sx = (x - zx) / zs; val sy = (y - zy) / zs
        return floatArrayOf((sx - imgRect.left) / imgRect.width() * b.width, (sy - imgRect.top) / imgRect.height() * b.height)
    }
    private fun toView(px: Float, py: Float): FloatArray {
        val b = bmp!!
        return floatArrayOf(imgRect.left + px / b.width * imgRect.width(), imgRect.top + py / b.height * imgRect.height())
    }

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
        if (bmp == null || !layoutImage()) return false
        return when (mode) {
            Mode.CROP -> cropTouch(e)
            Mode.LASSO, Mode.BRUSH -> cutTouch(e)
            Mode.VIEW -> false
        }
    }

    private fun cropTouch(e: MotionEvent): Boolean {
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
                if (hypot(dx, dy) > 0f) { crop = CropTool.drag(crop, h, dx, dy, aspectPx, turnedW, turnedH); onCropChange?.invoke(crop) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (active != null) onCropEnd?.invoke(); active = null }
        }
        return true
    }

    private fun cancelCut() {
        if (strokeActive) { strokeActive = false; cutListener?.onStrokeCancel() }
        if (lassoActive) { lassoActive = false; lassoPts.clear(); lassoPath.reset() }
        invalidate()
    }

    private fun cutTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pinch = false; fx = e.x; fy = e.y
                val p = toImg(e.x, e.y)
                if (mode == Mode.BRUSH) { strokeActive = true; cutListener?.onStrokeStart(p[0], p[1]) }
                else {
                    lassoActive = true; lassoPts.clear(); lassoPts.add(p[0]); lassoPts.add(p[1])
                    val v = toView(p[0], p[1]); lassoPath.reset(); lassoPath.moveTo(v[0], v[1])
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // zweiter Finger: Malen abbrechen, ab jetzt zoomen/verschieben
                cancelCut()
                if (e.pointerCount >= 2) {
                    pinch = true
                    pd0 = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    pmx = (e.getX(0) + e.getX(1)) / 2; pmy = (e.getY(0) + e.getY(1)) / 2
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinch && e.pointerCount >= 2) {
                    val d1 = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    val k = if (pd0 > 1f) d1 / pd0 else 1f
                    val ns = min(8f, max(1f, zs * k)); val kk = ns / zs
                    // Punkt unter der alten Fingermitte bleibt unter der neuen
                    zx = mx - (pmx - zx) * kk; zy = my - (pmy - zy) * kk; zs = ns
                    if (zs <= 1.0001f) { zx = 0f; zy = 0f }
                    pd0 = d1; pmx = mx; pmy = my
                    invalidate()
                } else if (!pinch) {
                    fx = e.x; fy = e.y
                    val p = toImg(e.x, e.y)
                    if (mode == Mode.BRUSH && strokeActive) cutListener?.onStrokeMove(p[0], p[1])
                    else if (lassoActive) {
                        val lx = lassoPts[lassoPts.size - 2]; val ly = lassoPts[lassoPts.size - 1]
                        if (hypot(p[0] - lx, p[1] - ly) > 1.5f) {
                            lassoPts.add(p[0]); lassoPts.add(p[1])
                            val v = toView(p[0], p[1]); lassoPath.lineTo(v[0], v[1])
                        }
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (strokeActive) { strokeActive = false; cutListener?.onStrokeEnd() }
                if (lassoActive) {
                    lassoActive = false
                    // Linie schließt sich automatisch (das Polygon wird beim Füllen geschlossen)
                    if (lassoPts.size >= 6) cutListener?.onLasso(lassoPts.toFloatArray())
                    lassoPts.clear(); lassoPath.reset()
                }
                pinch = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { cancelCut(); pinch = false }
        }
        return true
    }
}
