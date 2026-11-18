package com.omni.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Grafik RPM 3 roda, 30 detik terakhir (300 titik), garis tipis = target, batas ±500. Data dari ring buffer RobotState. */
class RpmChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    companion object {
        private const val LIM = 500f
        private val LEVELS = floatArrayOf(-500f, -250f, 0f, 250f, 500f)
    }

    var state: RobotState? = null

    private val d = resources.displayMetrics.density
    private val colors = intArrayOf(
        context.getColor(R.color.omni_motor1),
        context.getColor(R.color.omni_motor2),
        context.getColor(R.color.omni_motor3),
    )
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66888888
        strokeWidth = 1f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * d
        typeface = Typeface.MONOSPACE
        color = context.getColor(R.color.omni_mut)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val dashed = DashPathEffect(floatArrayOf(3 * d, 5 * d), 0f)

    private fun y(v: Float, h: Float) = h / 2f - v / LIM * (h / 2f - 12 * d)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (v in LEVELS) {
            grid.pathEffect = if (v != 0f) dashed else null
            canvas.drawLine(0f, y(v, h), w, y(v, h), grid)
            canvas.drawText((if (v > 0) "+" else "") + v.toInt(), 4 * d, y(v, h) - 3 * d, label)
        }
        val st = state ?: return
        val n = st.histCount
        if (n < 2) return
        val dx = w / RobotState.HIST
        for (i in 0..2) {
            trace(canvas, st, i, true, dx, h, colors[i] and 0x00FFFFFF or 0x66000000, 1.5f * d)
            trace(canvas, st, i, false, dx, h, colors[i], 2.5f * d)
        }
    }

    private fun trace(c: Canvas, st: RobotState, i: Int, target: Boolean, dx: Float, h: Float, col: Int, wd: Float) {
        path.reset()
        for (k in 0 until st.histCount) {
            val v = if (target) st.spAt(i, k) else st.rpmAt(i, k)
            if (k == 0) path.moveTo(0f, y(v, h)) else path.lineTo(k * dx, y(v, h))
        }
        line.color = col
        line.strokeWidth = wd
        c.drawPath(path, line)
    }
}

/**
 * Bar tipis. centered: mengisi dari tengah ke kiri/kanan (RPM bertanda, frac -1..1);
 * tidak centered: mengisi dari kiri (0..1), dipakai untuk kepercayaan encoder.
 */
class CenterBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.omni_ln) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var frac = 0f
    private var centered = true

    fun set(frac: Float, color: Int, centered: Boolean) {
        val f = frac.coerceIn(if (centered) -1f else 0f, 1f)
        if (f == this.frac && color == bar.color && centered == this.centered) return
        this.frac = f
        this.centered = centered
        bar.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        if (frac == 0f) return
        if (centered) {
            val half = w / 2f
            val len = min(abs(frac), 1f) * half
            if (frac > 0) rect.set(half, 0f, half + len, h) else rect.set(half - len, 0f, half, h)
        } else {
            rect.set(0f, 0f, frac * w, h)
        }
        canvas.drawRoundRect(rect, r, r, bar)
    }
}
