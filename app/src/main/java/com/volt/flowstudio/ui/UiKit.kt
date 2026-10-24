package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** 界面配色。深色 + 橙色主调，跟启动图标一致。 */
object Palette {
    const val BG = 0xFF0D0E12.toInt()
    const val PANEL = 0xFF16181F.toInt()
    const val PANEL_HI = 0xFF1E212A.toInt()
    const val LINE = 0xFF2A2E3A.toInt()
    const val LINE_SOFT = 0xFF202430.toInt()
    const val ACCENT = 0xFFFF8A1F.toInt()
    const val ACCENT_DIM = 0xFF7A4310.toInt()
    const val CYAN = 0xFF35D0E0.toInt()
    const val GREEN = 0xFF4ADE80.toInt()
    const val RED = 0xFFF0453F.toInt()
    const val YELLOW = 0xFFFDE047.toInt()
    const val PURPLE = 0xFFA78BFA.toInt()
    const val TEXT = 0xFFE8EAF0.toInt()
    const val TEXT_DIM = 0xFF8B90A0.toInt()
    const val TEXT_FAINT = 0xFF575D6E.toInt()

    // 每条轨道分配一个可辨识的颜色，混音台和播放列表都用它
    private val TRACK_COLORS = intArrayOf(
        0xFFFF8A1F.toInt(), 0xFF35D0E0.toInt(), 0xFFA78BFA.toInt(), 0xFF4ADE80.toInt(),
        0xFFFDE047.toInt(), 0xFFF0453F.toInt(), 0xFF22D3EE.toInt(), 0xFFFB7185.toInt(),
        0xFF818CF8.toInt(), 0xFF34D399.toInt(), 0xFFFBBF24.toInt(), 0xFFE879F9.toInt()
    )

    fun trackColor(index: Int): Int = TRACK_COLORS[((index % TRACK_COLORS.size) + TRACK_COLORS.size) % TRACK_COLORS.size]

    fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}

/** 绘制用的画笔池，所有自定义 View 共享，避免每次 onDraw 里 new Paint。 */
class Brushes {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    val textBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    val textMono = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
    }
    val rect = RectF()
}

/** 提供 dp 换算与通用绘制。 */
abstract class BaseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    protected val b = Brushes()
    protected val density: Float = resources.displayMetrics.density
    protected val scaled: Float = resources.displayMetrics.scaledDensity

    protected fun dp(v: Float): Float = v * density
    protected fun sp(v: Float): Float = v * scaled

    protected fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float = 1f) {
        b.stroke.color = color
        b.stroke.strokeWidth = w
        c.drawLine(x1, y1, x2, y2, b.stroke)
    }

    protected fun rect(c: Canvas, l: Float, t: Float, r: Float, bt: Float, color: Int, radius: Float = 0f) {
        b.fill.color = color
        b.fill.style = Paint.Style.FILL
        if (radius > 0f) c.drawRoundRect(l, t, r, bt, radius, radius, b.fill)
        else c.drawRect(l, t, r, bt, b.fill)
    }

    protected fun outline(c: Canvas, l: Float, t: Float, r: Float, bt: Float, color: Int, radius: Float = 0f, w: Float = 1f) {
        b.stroke.color = color
        b.stroke.strokeWidth = w
        if (radius > 0f) c.drawRoundRect(l, t, r, bt, radius, radius, b.stroke)
        else c.drawRect(l, t, r, bt, b.stroke)
    }

    protected fun label(
        c: Canvas, s: String, x: Float, y: Float, color: Int,
        size: Float, bold: Boolean = false, center: Boolean = false
    ) {
        val p = if (bold) b.textBold else b.text
        p.color = color
        p.textSize = size
        p.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s, x, y, p)
    }

    protected fun labelRight(c: Canvas, s: String, x: Float, y: Float, color: Int, size: Float, bold: Boolean = false) {
        val p = if (bold) b.textBold else b.text
        p.color = color
        p.textSize = size
        p.textAlign = Paint.Align.RIGHT
        c.drawText(s, x, y, p)
    }

    protected fun clipText(s: String, maxWidth: Float, size: Float, bold: Boolean = false): String {
        val p = if (bold) b.textBold else b.text
        p.textSize = size
        if (p.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && p.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}

/** 触摸辅助：判断点是否在矩形内。 */
fun hit(x: Float, y: Float, l: Float, t: Float, r: Float, bt: Float): Boolean =
    x >= l && x <= r && y >= t && y <= bt

/** 把 0..1 的值转成对数刻度的显示（推子和旋钮更符合听感）。 */
fun logScale(v: Float): Float = if (v <= 0.0001f) 0f else (20f * kotlin.math.log10(v) + 60f) / 60f

/** 把一个窄范围内的拖动映射成 0..1 增量，避免细调时跳变。 */
fun MotionEvent.deltaNorm(prevX: Float, prevY: Float, span: Float): Float {
    val dx = (x - prevX) / span
    val dy = (prevY - y) / span
    return (dx + dy) * 1.6f
}

fun clamp01(v: Float): Float = max(0f, min(1f, v))
