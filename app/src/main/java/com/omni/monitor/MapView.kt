package com.omni.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Peta posisi: x ke kanan, y ke atas, titik 0 di tengah (sama dengan drawMap() di monitor.html).
 * Skala otomatis mengikuti bounding waypoint + jejak + pose. Hanya menggambar; data datang dari RobotState.
 */
class MapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var snap: Snap? = null
    var trail: List<FloatArray> = emptyList()

    private val d = resources.displayMetrics.density
    private val cMut = context.getColor(R.color.omni_mut)
    private val cLn = context.getColor(R.color.omni_ln)
    private val cAcc = context.getColor(R.color.omni_acc)
    private val cBad = context.getColor(R.color.omni_bad)
    private val cFg = context.getColor(R.color.omni_fg)
    private val cOk = context.getColor(R.color.omni_ok)
    private val cBg = context.getColor(R.color.omni_bg)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * d
        typeface = Typeface.MONOSPACE
    }
    private val path = Path()
    private val dash25 = DashPathEffect(floatArrayOf(2 * d, 4 * d), 0f)
    private val dash55 = DashPathEffect(floatArrayOf(5 * d, 5 * d), 0f)

    private var cx = 0f
    private var cy = 0f
    private var sc = 1f
    private fun px(v: Float) = cx + v * sc
    private fun py(v: Float) = cy - v * sc

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = cBg
        canvas.drawRect(0f, 0f, w, h, fill)
        val s = snap ?: return

        var ext = 100f
        for (i in 0 until Snap.MAX_WP) if (s.wpValid(i)) ext = max(ext, max(abs(s.wp[i][1]) + 30f, abs(s.wp[i][2]) + 30f))
        for (p in trail) ext = max(ext, max(abs(p[0]) + 30f, abs(p[1]) + 30f))
        ext = max(ext, max(abs(s.x) + 40f, abs(s.y) + 40f))
        sc = min(w, h) / 2f / ext
        cx = w / 2f
        cy = h / 2f

        // grid
        val step = if (ext > 400) 100 else if (ext > 150) 50 else if (ext > 60) 20 else 10
        stroke.strokeWidth = 1f
        stroke.pathEffect = null
        var v = -ceil(ext / step).toInt() * step
        while (v <= ext) {
            stroke.color = if (v % (step * 5) == 0) cMut else cLn
            stroke.alpha = if (v == 0) 255 else 153
            canvas.drawLine(px(v.toFloat()), 0f, px(v.toFloat()), h, stroke)
            canvas.drawLine(0f, py(v.toFloat()), w, py(v.toFloat()), stroke)
            v += step
        }
        stroke.alpha = 255
        text.color = cMut
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("0,0", px(0f) + 4 * d, py(0f) + 13 * d, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("$step cm / kotak", w - 8 * d, h - 8 * d, text)
        text.textAlign = Paint.Align.LEFT

        // jejak
        if (trail.size > 1) {
            path.reset()
            trail.forEachIndexed { k, p -> if (k == 0) path.moveTo(px(p[0]), py(p[1])) else path.lineTo(px(p[0]), py(p[1])) }
            stroke.color = cOk
            stroke.alpha = 178
            stroke.strokeWidth = 2 * d
            canvas.drawPath(path, stroke)
            stroke.alpha = 255
        }

        // rute yang sedang jalan
        if (s.rt in 0..3 && s.rts[s.rt].isNotEmpty()) {
            path.reset()
            var first = true
            for (id in s.rts[s.rt]) {
                if (id !in 1..Snap.MAX_WP || !s.wpValid(id - 1)) continue
                val wp = s.wp[id - 1]
                if (first) { path.moveTo(px(wp[1]), py(wp[2])); first = false } else path.lineTo(px(wp[1]), py(wp[2]))
            }
            stroke.color = cAcc
            stroke.strokeWidth = 1.5f * d
            stroke.pathEffect = dash25
            canvas.drawPath(path, stroke)
            stroke.pathEffect = null
        }

        // waypoint
        stroke.color = cAcc
        stroke.strokeWidth = 2 * d
        for (i in 0 until Snap.MAX_WP) {
            if (!s.wpValid(i)) continue
            val wp = s.wp[i]
            val x = px(wp[1])
            val y = py(wp[2])
            if (s.tgt == i + 1) {
                fill.color = cAcc
                canvas.drawCircle(x, y, 6 * d, fill)
            }
            canvas.drawCircle(x, y, 6 * d, stroke)
            if (s.wpHasYaw(i)) {
                val a = Math.toRadians(wp[3].toDouble())
                canvas.drawLine(x, y, x + (cos(a) * 16 * d).toFloat(), y - (sin(a) * 16 * d).toFloat(), stroke)
            }
            text.color = cFg
            canvas.drawText("WP${i + 1}", x + 9 * d, y + 4 * d, text)
        }

        // garis ke target
        if (s.tgt in 1..Snap.MAX_WP && s.wpValid(s.tgt - 1) && s.np != 2) {
            val wp = s.wp[s.tgt - 1]
            stroke.pathEffect = dash55
            stroke.strokeWidth = 1.5f * d
            canvas.drawLine(px(s.x), py(s.y), px(wp[1]), py(wp[2]), stroke)
            stroke.pathEffect = null
        }

        // rintangan ultrasonik
        val yaw = Math.toRadians(s.yaw.toDouble())
        fill.color = cBad
        for (i in 0..2) {
            val dd = s.d[i]
            if (dd < 0f) continue
            val a = yaw + Math.toRadians(s.sa[i].toDouble())
            val ox = s.x + (cos(a) * (dd + 12)).toFloat()
            val oy = s.y + (sin(a) * (dd + 12)).toFloat()
            canvas.drawCircle(px(ox), py(oy), 4 * d, fill)
        }

        // robot
        canvas.save()
        canvas.translate(px(s.x), py(s.y))
        canvas.rotate(-s.yaw)
        val r = max(10f * d, 12f * sc)
        path.reset()
        path.moveTo(0f, -r)
        path.lineTo(r * 0.7f, r * 0.7f)
        path.lineTo(-r * 0.7f, r * 0.7f)
        path.close()
        fill.color = cFg
        canvas.drawPath(path, fill)
        canvas.restore()
    }
}
