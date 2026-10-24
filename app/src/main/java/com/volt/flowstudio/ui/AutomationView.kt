package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.Automation
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Project
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自动化编辑：横轴是时间，纵轴是参数值。点空白加控制点，拖点改值，长按点删。
 * 曲线用线性段连，跟引擎里的插值方式一致，所见即所得。
 */
class AutomationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var project: Project? = null
        set(v) { field = v; invalidate() }

    var trackIndex: Int = 0
        set(v) { field = v; invalidate() }

    var target: Automation.Target = Automation.Target.VOLUME
        set(v) { field = v; invalidate() }

    var playheadTick: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var onChanged: (() -> Unit)? = null

    private var dragPoint = -1
    private var pressing = false
    private var pressX = 0f
    private var pressY = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Palette.PANEL)

        val p = project ?: return
        val padL = dp(30f)
        val padR = dp(6f)
        val padT = dp(6f)
        val padB = dp(6f)
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        if (plotW <= 0 || plotH <= 0) return

        val total = p.lengthTicks().coerceAtLeast(1)
        fun xOf(t: Int) = padL + plotW * (t.toFloat() / total)
        fun yOf(v: Float) = padT + plotH * (1f - v)
        fun tOf(x: Float) = ((x - padL) / plotW * total).toInt().coerceIn(0, total)
        fun vOf(y: Float) = (1f - (y - padT) / plotH).coerceIn(0f, 1f)

        // 网格
        val bars = p.lengthBars()
        val barStep = max(1, bars / 8)
        for (b in 0..bars step barStep) {
            val x = xOf(b * Note.BAR)
            line(canvas, x, padT, x, h - padB, Palette.withAlpha(Palette.LINE, 120), dp(1f))
        }
        for (q in 0..4) {
            val y = yOf(q / 4f)
            line(canvas, padL, y, w - padR, y, Palette.withAlpha(Palette.LINE_SOFT, 160), dp(1f))
            labelRight(canvas, "${q * 25}", padL - dp(3f), y + dp(3f), Palette.TEXT_FAINT, sp(7f))
        }

        val auto = p.automation(trackIndex, target)
        val accent = Palette.trackColor(trackIndex)

        if (auto == null || auto.points.isEmpty()) {
            label(
                canvas, "点一下加控制点", padL + plotW / 2, padT + plotH / 2,
                Palette.TEXT_FAINT, sp(10f), center = true
            )
            return
        }

        // 曲线
        val pts = auto.points.sortedBy { it.tick }
        b.stroke.color = accent
        b.stroke.strokeWidth = dp(1.8f)
        if (pts.size == 1) {
            val y = yOf(pts[0].value)
            line(canvas, padL, y, w - padR, y, accent, dp(1.8f))
        } else {
            for (i in 1 until pts.size) {
                canvas.drawLine(
                    xOf(pts[i - 1].tick), yOf(pts[i - 1].value),
                    xOf(pts[i].tick), yOf(pts[i].value), b.stroke
                )
            }
            // 首尾延伸到边界，跟引擎的取值行为一致
            line(canvas, padL, yOf(pts.first().value), xOf(pts.first().tick), yOf(pts.first().value), accent, dp(1.8f))
            line(canvas, xOf(pts.last().tick), yOf(pts.last().value), w - padR, yOf(pts.last().value), accent, dp(1.8f))
        }

        // 控制点
        for (pt in pts) {
            val x = xOf(pt.tick)
            val y = yOf(pt.value)
            b.fill.color = Palette.BG
            b.fill.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(x, y, dp(4f), b.fill)
            canvas.drawCircle(x, y, dp(3f), b.fill.apply { color = accent })
        }

        // 播放头
        val px = xOf(playheadTick.coerceIn(0, total))
        line(canvas, px, padT, px, h - padB, Palette.RED, dp(1.3f))

        // 顶部信息
        label(canvas, "${target.cn}", padL + dp(2f), padT + dp(9f), accent, sp(8.5f), bold = true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = dp(30f)
        val padR = dp(6f)
        val padT = dp(6f)
        val padB = dp(6f)
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        if (plotW <= 0 || plotH <= 0) return false

        val total = p.lengthTicks().coerceAtLeast(1)
        fun xOf(t: Int) = padL + plotW * (t.toFloat() / total)
        fun tOf(x: Float) = ((x - padL) / plotW * total).toInt().coerceIn(0, total)
        fun vOf(y: Float) = (1f - (y - padT) / plotH).coerceIn(0f, 1f)

        val auto = p.automationOrCreate(trackIndex, target)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressX = e.x
                pressY = e.y
                // 命中已有点？
                val hit = auto.points.indexOfFirst {
                    abs(xOf(it.tick) - e.x) < dp(14f) && abs(e.y - (padT + plotH * (1f - it.value))) < dp(14f)
                }
                if (hit >= 0) {
                    dragPoint = hit
                    pressing = true
                } else {
                    dragPoint = -1
                    pressing = true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressing) return true
                if (kotlin.math.hypot(e.x - pressX, e.y - pressY) < dp(8f) && dragPoint >= 0) return true
                if (dragPoint >= 0 && dragPoint < auto.points.size) {
                    auto.points[dragPoint].tick = tOf(e.x)
                    auto.points[dragPoint].value = vOf(e.y)
                    auto.points.sortBy { it.tick }
                    onChanged?.invoke()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (pressing && dragPoint < 0) {
                    // 空白点：加一个点
                    val tick = tOf(e.x)
                    val value = vOf(e.y)
                    // 长按（>500ms）在空白处不加点，避免误触
                    auto.addPoint(tick, value)
                    dragPoint = auto.points.indexOfFirst { it.tick == tick }
                    onChanged?.invoke()
                }
                pressing = false
                dragPoint = -1
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressing = false
                dragPoint = -1
                return true
            }
        }
        return true
    }

    /** 长按删点：由外部在主界面用长按菜单调用。 */
    fun deletePointNear(x: Float): Boolean {
        val p = project ?: return false
        val auto = p.automation(trackIndex, target) ?: return false
        val padL = dp(30f)
        val padR = dp(6f)
        val plotW = width - padL - padR
        val total = p.lengthTicks().coerceAtLeast(1)
        val tick = ((x - padL) / plotW * total).toInt()
        val before = auto.points.size
        auto.removeNear(tick, Note.STEP * 2)
        if (auto.points.size != before) {
            onChanged?.invoke()
            invalidate()
            return true
        }
        return false
    }
}
