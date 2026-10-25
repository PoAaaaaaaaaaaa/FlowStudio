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

/** 统一视觉系统：低亮石墨底、暖琥珀主色、青色仅用于时间与状态。 */
object Palette {
    // 低饱和冷灰底：避免纯黑+高饱和造成的"廉价霓虹感"。
    // 层次靠明度差建立，颜色只做点缀。
    const val BG = 0xFF0A0C10.toInt()
    const val SURFACE = 0xFF0E1117.toInt()
    const val PANEL = 0xFF12161D.toInt()
    const val PANEL_HI = 0xFF1A1F28.toInt()
    const val CARD = 0xFF161B23.toInt()
    const val LINE = 0xFF262C36.toInt()
    const val LINE_SOFT = 0xFF1B2029.toInt()

    // 主强调色：暖沙金，比原来的高饱和橙更克制。
    const val ACCENT = 0xFFC9A227.toInt()
    const val ACCENT_DIM = 0xFF6B5715.toInt()
    // 时间/状态用雾蓝，不再用荧光青。
    const val CYAN = 0xFF6FA8B8.toInt()
    const val GREEN = 0xFF6FA37A.toInt()
    const val RED = 0xFFC2564B.toInt()
    const val YELLOW = 0xFFC9A227.toInt()
    const val PURPLE = 0xFF8C86B8.toInt()

    const val TEXT = 0xFFE4E7EC.toInt()
    const val TEXT_DIM = 0xFF9099A8.toInt()
    const val TEXT_FAINT = 0xFF626B7A.toInt()

    /**
     * 轨道色：全部降饱和、统一明度区间。
     * 之前 12 种高饱和色同时出现，画面像调色盘；
     * 现在只在相邻轨道间保证可区分，整体服从同一套灰调。
     */
    private val TRACK_COLORS = intArrayOf(
        0xFFC9A227.toInt(), // 沙金
        0xFF6FA8B8.toInt(), // 雾蓝
        0xFF8C86B8.toInt(), // 灰紫
        0xFF6FA37A.toInt(), // 苔绿
        0xFFC08552.toInt(), // 陶土
        0xFFC2564B.toInt(), // 砖红
        0xFF7E8CA0.toInt(), // 石板
        0xFFB58BA0.toInt(), // 藕荷
        0xFF8A9A6B.toInt(), // 橄榄
        0xFF9A8C7A.toInt(), // 卡其
        0xFFA87F72.toInt(), // 红棕
        0xFF7D93A8.toInt()  // 钢蓝
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

