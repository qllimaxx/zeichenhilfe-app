package de.benni.zeichenhilfe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Schmaler, senkrechter Regler (Design M1): dünne Spur, weißer Knopf. Oben = 100 %, unten = 0 %.
 * Berührfläche so breit wie die View (mind. 48 dp), sichtbar ist nur die Spur.
 */
class VerticalSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Wert 0..1 */
    var value = 0.5f
        set(v) { field = min(1f, max(0f, v)); invalidate() }

    var onChange: ((Float) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(80, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(4f * d, 0f, 1f * d, Color.argb(110, 0, 0, 0)) }
    private val rect = RectF()

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // für den weichen Schatten des Knopfs

    private val pad get() = 14f * d                    // Abstand der Spur vom oberen/unteren Rand

    /** y-Lage des Knopfs zum Wert. */
    fun knobY(): Float = pad + (1f - value) * (height - 2 * pad)

    override fun onDraw(c: Canvas) {
        val cx = width - 12f * d
        val w = 2f * d
        rect.set(cx - w, pad, cx + w, height - pad)
        c.drawRoundRect(rect, w, w, track)
        val ky = knobY()
        rect.set(cx - w, ky, cx + w, height - pad)
        c.drawRoundRect(rect, w, w, fill)
        c.drawCircle(cx, ky, 10f * d, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onStart?.invoke(); update(e.y) }
            MotionEvent.ACTION_MOVE -> update(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { update(e.y); onStop?.invoke() }
        }
        return true
    }

    private fun update(y: Float) {
        val h = max(1f, height - 2 * pad)
        value = 1f - (y - pad) / h
        onChange?.invoke(value)
    }
}
