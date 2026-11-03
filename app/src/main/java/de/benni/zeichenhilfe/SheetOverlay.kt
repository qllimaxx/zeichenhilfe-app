package de.benni.zeichenhilfe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Rahmen ums erkannte Blatt (ab 0.3, Design M1): dünne weiße Linie und feine Eckwinkel mit dunklem Saum,
 * damit sie auch auf weißem Papier sichtbar sind. Die Ecken kommen in Bildschirm-Pixeln vom GL-Thread.
 * Autor: Benjamin Dormoros
 */
class SheetOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    @Volatile private var corners: FloatArray? = null

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Color.argb(110, 255, 255, 255) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4.4f * d; color = Color.argb(115, 0, 0, 0)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = Color.WHITE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** Darf aus jedem Thread aufgerufen werden. null = ausblenden. */
    fun update(c: FloatArray?) {
        if (c == null && corners == null) return
        corners = c
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val c = corners ?: return
        path.reset()
        path.moveTo(c[0], c[1]); path.lineTo(c[2], c[3]); path.lineTo(c[4], c[5]); path.lineTo(c[6], c[7]); path.close()
        canvas.drawPath(path, line)
        path.reset()
        for (i in 0 until 4) {
            val x = c[2 * i]; val y = c[2 * i + 1]
            val nx = c[(2 * i + 2) % 8]; val ny = c[(2 * i + 3) % 8]
            val px = c[(2 * i + 6) % 8]; val py = c[(2 * i + 7) % 8]
            val l1 = hypot(nx - x, ny - y); val l2 = hypot(px - x, py - y)
            if (l1 < 1f || l2 < 1f) continue
            val l = min(18f * d, 0.3f * min(l1, l2))
            path.moveTo(x + (nx - x) / l1 * l, y + (ny - y) / l1 * l)
            path.lineTo(x, y)
            path.lineTo(x + (px - x) / l2 * l, y + (py - y) / l2 * l)
        }
        canvas.drawPath(path, halo)
        canvas.drawPath(path, bracket)
    }
}
