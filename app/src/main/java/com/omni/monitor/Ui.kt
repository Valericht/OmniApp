package com.omni.monitor

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** targetSdk 35+ memaksa edge-to-edge: beri padding sebesar system bars + keyboard. */
fun Activity.fitSystemBars(root: View) {
    if (Build.VERSION.SDK_INT < 30) return
    window.setDecorFitsSystemWindows(false)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
        val b = insets.getInsets(types)
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsets.CONSUMED
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.color(id: Int): Int = getColor(id)

/** Angka seperti JS: 3.0 -> "3", 3.25 -> "3.3" (telemetri sudah dibulatkan 0,1). */
fun fmt(v: Float, dec: Int = 1): String {
    val k = if (dec == 2) 100f else 10f
    val r = (v * k).roundToInt() / k
    return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
}

fun TextView.setIfChanged(t: CharSequence) {
    if (!TextUtils.equals(text, t)) text = t
}

fun TextView.chip(t: CharSequence, colorRes: Int = R.color.omni_fg) {
    setIfChanged(t)
    setTextColor(context.color(colorRes))
}

/** Ganti latar hanya bila berubah (hindari relayout tiap 100 ms). */
fun View.setBg(res: Int) {
    if (tag != res) {
        setBackgroundResource(res)
        tag = res
    }
}

fun View.enable(on: Boolean) {
    if (isEnabled != on) isEnabled = on
    alpha = if (on) 1f else 0.4f
}

fun sp(block: SpannableStringBuilder.() -> Unit): SpannableStringBuilder =
    SpannableStringBuilder().apply(block)

fun SpannableStringBuilder.put(
    t: CharSequence,
    color: Int? = null,
    bold: Boolean = false,
    size: Float = 1f,
): SpannableStringBuilder {
    val s = length
    append(t)
    val e = length
    if (color != null) setSpan(ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (bold) setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    if (size != 1f) setSpan(RelativeSizeSpan(size), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return this
}

fun Context.tv(t: CharSequence, size: Float = 13f, colorRes: Int = R.color.omni_fg, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color(colorRes))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

/** Daftar "label ... nilai" yang memakai ulang TextView (tanpa membangun ulang tiap telemetri). */
class Kv(private val box: LinearLayout) {
    private val keys = ArrayList<TextView>()
    private val vals = ArrayList<TextView>()

    fun set(rows: List<Pair<CharSequence, CharSequence>>) {
        val ctx = box.context
        while (keys.size < rows.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ctx.dp(2), 0, ctx.dp(2))
            }
            val k = ctx.tv("", 13f, R.color.omni_mut)
            val v = ctx.tv("", 13f, bold = true).apply { gravity = Gravity.END }
            row.addView(k, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            box.addView(row)
            keys.add(k)
            vals.add(v)
        }
        while (keys.size > rows.size) {
            box.removeViewAt(box.childCount - 1)
            keys.removeAt(keys.size - 1)
            vals.removeAt(vals.size - 1)
        }
        rows.forEachIndexed { i, r ->
            keys[i].setIfChanged(r.first)
            vals[i].setIfChanged(r.second)
        }
    }
}

/** EditText yang tidak ditimpa telemetri saat sedang diketik atau sudah diubah pengguna. */
class SyncEdit(val e: EditText) {
    var dirty = false
    private var programmatic = false

    init {
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!programmatic) dirty = true
            }
        })
    }

    fun push(t: String) {
        if (dirty || e.hasFocus() || e.text.toString() == t) return
        programmatic = true
        e.setText(t)
        programmatic = false
    }

    fun clean() {
        dirty = false
    }
}
