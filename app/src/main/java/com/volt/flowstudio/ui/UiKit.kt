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

/**
 * 统一视觉系统。
 *
 * 参考 FL Studio Mobile 的工作区：深灰底、极低对比的分隔、
 * 内容本身（波形、片段、电平）负责提供色彩，界面框架尽量隐形。
 *
 * 三条原则：
 *   1. 层次用明度差建立，不靠描边。整屏只有一个真正的高光：正在播放的那条。
 *   2. 强调色只出现在"当前状态"上（选中轨、播放头、正在编辑的片段），
 *      不做装饰性用色。
 *   3. 音频片段用一种固定蓝，音符片段用轨道色 —— 一眼分得清哪块是录音。
 */
object Palette {
    // 背景层：从最深的画布到最亮的面板，四档就够，再多就分不清了。
    const val BG = 0xFF0D0F13.toInt()          // 画布
    const val SURFACE = 0xFF14171C.toInt()     // 顶栏/底栏
    const val PANEL = 0xFF1A1E25.toInt()       // 面板
    const val PANEL_HI = 0xFF232831.toInt()    // 悬停/内嵌控件
    const val CARD = 0xFF1E232B.toInt()        // 卡片

    // 分隔线：极细，只用来分区，不抢注意力。
    const val LINE = 0xFF2C333E.toInt()
    const val LINE_SOFT = 0xFF1F242B.toInt()

    /**
     * 主强调色。偏冷的浅青，用于选中与播放状态。
     * 不用饱和橙/红：那些在暗底上视觉重量太大，看久了眼睛累。
     */
    const val ACCENT = 0xFF5BA8C4.toInt()
    const val ACCENT_DIM = 0xFF2C5566.toInt()

    // 语义色
    const val CYAN = 0xFF5BA8C4.toInt()
    const val GREEN = 0xFF6FA37A.toInt()
    const val RED = 0xFFC2564B.toInt()
    const val YELLOW = 0xFFC9A227.toInt()
    const val PURPLE = 0xFF8C86B8.toInt()

    /**
     * 音频片段专用色。与轨道色分开是有意的：
     * 音符片段按轨道着色，音频片段统一一个颜色 —— 扫一眼就知道
     * "这几块是成品/录音，那几块是编曲"。FL 里音频片段也是独立配色。
     */
    const val AUDIO = 0xFF3D8FB5.toInt()

    // 文字三档：主、次、弱。不允许出现第四档灰。
    const val TEXT = 0xFFE6EAF0.toInt()
    const val TEXT_DIM = 0xFF98A2B0.toInt()
    const val TEXT_FAINT = 0xFF646E7C.toInt()

    /**
     * 轨道色：统一明度、统一饱和度区间，只在色相上区分。
     *
     * 之前 12 种高饱和色同时出现，画面像调色盘；现在的做法是
     * 全部压到同一亮度带（约 55-65%），相邻轨道色相拉开。
     * 这样八条轨道并排时是一组"同一个色系里的不同颜色"，而不是霓虹灯。
     */
    private val TRACK_COLORS = intArrayOf(
        0xFF5BA8C4.toInt(), // 青
        0xFF7C9EC9.toInt(), // 蓝
        0xFF8C86B8.toInt(), // 紫
        0xFFB07CA0.toInt(), // 品
        0xFFC08552.toInt(), // 陶
        0xFFC9A227.toInt(), // 沙金
        0xFF8A9A6B.toInt(), // 橄榄
        0xFF6FA37A.toInt(), // 苔绿
        0xFF4FA3A0.toInt(), // 蓝绿
        0xFF9A8C7A.toInt(), // 卡其
        0xFFA87F72.toInt(), // 红棕
        0xFF7D93A8.toInt()  // 钢蓝
    )

    fun trackColor(index: Int): Int =
        TRACK_COLORS[((index % TRACK_COLORS.size) + TRACK_COLORS.size) % TRACK_COLORS.size]

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

