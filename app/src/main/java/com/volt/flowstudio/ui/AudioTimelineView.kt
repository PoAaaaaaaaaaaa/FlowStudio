package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.session.BackingTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 音频时间线主界面。
 *
 * 布局参照多轨编辑器：左侧固定轨道头（图标 + 名称 + 长度），
 * 右侧是共享同一时间轴的滚动区域，片段画真实波形，顶部刻度尺标小节。
 *
 * 与 PlaylistView 的区别：
 *   PlaylistView 编辑的是 pattern 引用（音乐片段），
 *   这里展示的是真实音频（伴奏/成品/人声），波形来自 BackingTrack.peaks，
 *   所以用户一眼能看出"哪里是鼓、哪里是人声"。
 *
 * 数据源当前只有一条伴奏轨；结构按多轨设计，后续加轨只需往 lanes 里塞。
 */
class AudioTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    private class Lane(
        val title: String,
        val subtitle: String,
        val accent: Int,
        var startTick: Int,
        var lengthTicks: Int,
        var peaks: FloatArray
    )

    private val lanes = mutableListOf<Lane>()

    var playheadTick: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var barTicks: Int = 1920
        set(v) { if (field != v) { field = v.coerceAtLeast(480); invalidate() } }
    var totalTicks: Int = 1920 * 16
        set(v) { if (field != v) { field = v.coerceAtLeast(1920); invalidate() } }

    /** 点击时间轴：回调绝对 tick。 */
    var onSeek: ((Int) -> Unit)? = null
    /** 点击轨道头：打开该轨设置。 */
    var onLaneTap: ((Int) -> Unit)? = null
    /** 长按空白：导入音频。 */
    var onRequestImport: (() -> Unit)? = null

    private var headerW = 0f
    private var rulerH = 0f
    private var rowH = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var pressedLane = -1

    // ---- 阻尼回弹（参考 BouncingJellyView 的手感参数）----
    // 原实现用 3 倍屏高作为阻尼基准、300ms Overshoot 回弹。
    // 这里只把"越界下拉/上推"这一步用在轨道区，避免和横向平移冲突。
    private var edgeStartY = 0f
    private var downX = 0f
    private var edgeOver = 0f
    private val bounceRange = 220f
    private var bouncing = false

    /** 越界量 → 缩放量：位移越大越接近上限，但永远不到 1，避免拉扯感过强。 */
    private fun applyBounce() {
        val k = (edgeOver / bounceRange).coerceIn(0f, 1f)
        val scale = 1f - k * 0.045f
        scaleX = scale
        scaleY = scale
    }

    private fun releaseBounce() {
        if (edgeOver == 0f) return
        bouncing = true
        animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(300L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
            .withEndAction { bouncing = false }
            .start()
        edgeOver = 0f
        invalidate()
    }

    private fun contentW(): Float = max(dp(60f), width - headerW)
    private fun ticksPerScreen(): Int = max(barTicks * 4, totalTicks)
    private fun tickToX(t: Int): Float = headerW + (t.toFloat() / ticksPerScreen()) * contentW() - scrollX
    private fun xToTick(x: Float): Int =
        (((x - headerW + scrollX) / contentW()) * ticksPerScreen()).toInt().coerceAtLeast(0)

    /** 把伴奏刷新进第一条轨道；没有伴奏时保留一条空轨作为提示。 */
    fun bindBacking(backing: BackingTrack) {
        lanes.clear()
        if (backing.loaded) {
            val lenTicks = if (backing.sampleRate > 0) {
                (backing.frames.toDouble() / backing.sampleRate * 2.0 * 480).toInt()
            } else barTicks * 8
            lanes.add(Lane(
                title = backing.fileName.ifBlank { "伴奏" },
                subtitle = "${"%.1f".format(backing.frames.toDouble() / backing.sampleRate.coerceAtLeast(1))} 秒",
                accent = Palette.ACCENT,
                startTick = backing.startTick,
                lengthTicks = lenTicks.coerceAtLeast(barTicks),
                peaks = backing.peaks
            ))
        } else {
            lanes.add(Lane(
                title = "点击导入音频",
                subtitle = "WAV · 与编曲一起播放",
                accent = Palette.TEXT_FAINT,
                startTick = 0,
                lengthTicks = 0,
                peaks = FloatArray(0)
            ))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Palette.BG)

        headerW = dp(if (w < dp(700f)) 120f else 168f)
        rulerH = dp(30f)
        val laneCount = max(1, lanes.size.coerceAtLeast(1))
        rowH = ((h - rulerH) / laneCount).coerceIn(dp(72f), dp(180f))

        drawRuler(canvas, w)
        drawLanes(canvas, w, h)
        drawPlayhead(canvas, h)
    }

    /** 顶部刻度尺：小节号 + 细分刻度，选中区高亮。 */
    private fun drawRuler(canvas: Canvas, w: Float) {
        rect(canvas, 0f, 0f, w, rulerH, Palette.SURFACE)
        line(canvas, 0f, rulerH - 0.5f, w, rulerH - 0.5f, Palette.LINE, dp(1f))

        // 左上角图标区
        rect(canvas, dp(12f), dp(8f), dp(12f) + dp(3f), rulerH - dp(8f), Palette.ACCENT, dp(1.5f))
        rect(canvas, dp(18f), dp(12f), dp(18f) + dp(3f), rulerH - dp(12f), Palette.withAlpha(Palette.ACCENT, 140), dp(1.5f))
        rect(canvas, dp(24f), dp(10f), dp(24f) + dp(3f), rulerH - dp(10f), Palette.withAlpha(Palette.ACCENT, 90), dp(1.5f))

        val barCount = max(1, ticksPerScreen() / barTicks)
        val pxPerBar = contentW() / barCount
        // 空间不足时按 2/4 小节标注，避免数字叠在一起
        val stepBars = when {
            pxPerBar < dp(46f) -> 4
            pxPerBar < dp(80f) -> 2
            else -> 1
        }
        var bar = 0
        while (bar <= barCount) {
            val x = headerW + bar * pxPerBar - scrollX
            if (x in headerW..w) {
                line(canvas, x, rulerH - dp(9f), x, rulerH, Palette.LINE, dp(1f))
                label(canvas, "${bar + 1}", x + dp(4f), rulerH - dp(11f),
                    if (bar % stepBars == 0) Palette.TEXT_DIM else Palette.TEXT_FAINT, sp(8.5f))
            }
            // 小节内的细分刻度
            for (q in 1 until 4) {
                val qx = x + pxPerBar * q / 4f
                if (qx in headerW..w) line(canvas, qx, rulerH - dp(5f), qx, rulerH,
                    Palette.withAlpha(Palette.LINE, 120), dp(0.8f))
            }
            bar += stepBars
        }
        line(canvas, headerW, 0f, headerW, rulerH, Palette.LINE, dp(1f))
    }

    private fun drawLanes(canvas: Canvas, w: Float, h: Float) {
        val count = max(1, lanes.size)
        for (i in 0 until count) {
            val top = rulerH + i * rowH - scrollY
            val bottom = top + rowH
            if (bottom < rulerH || top > h) continue

            // 行背景：交替深浅，选中行略亮
            rect(canvas, 0f, top, w, bottom,
                if (i % 2 == 0) Palette.withAlpha(Palette.PANEL, 110) else Palette.BG)
            line(canvas, 0f, bottom, w, bottom, Palette.LINE_SOFT, dp(1f))

            drawLaneHeader(canvas, i, top, bottom)
            if (i < lanes.size) drawLaneContent(canvas, i, w, top, bottom)
        }

        // 轨道头与内容区之间的分隔
        line(canvas, headerW, rulerH, headerW, h, Palette.LINE, dp(1f))
    }

    private fun drawLaneHeader(canvas: Canvas, index: Int, top: Float, bottom: Float) {
        val lane = lanes.getOrNull(index)
        val accent = lane?.accent ?: Palette.TEXT_FAINT
        rect(canvas, 0f, top, headerW, bottom, Palette.SURFACE)
        if (pressedLane == index) {
            rect(canvas, 0f, top, headerW, bottom, Palette.PANEL_HI)
        }

        // 左侧色条 + 一个简洁的波形图标，模仿截图里的轨道头
        rect(canvas, 0f, top + dp(6f), dp(3f), bottom - dp(6f), accent, dp(1.5f))
        val iconCx = dp(26f)
        val iconCy = (top + bottom) / 2f
        val heights = floatArrayOf(0.30f, 0.62f, 0.42f, 0.78f, 0.36f)
        heights.forEachIndexed { k, ratio ->
            val hh = rowH * 0.30f * ratio
            val x = iconCx - dp(9f) + k * dp(4.5f)
            rect(canvas, x, iconCy - hh, x + dp(2.4f), iconCy + hh,
                Palette.withAlpha(accent, if (k == 3) 230 else 150), dp(1f))
        }

        val textX = dp(48f)
        val title = lane?.title ?: "空轨道"
        label(canvas, clipText(title, headerW - textX - dp(8f), sp(11f), true),
            textX, top + rowH * 0.46f, Palette.TEXT, sp(11f), bold = true)
        lane?.subtitle?.let {
            label(canvas, clipText(it, headerW - textX - dp(8f), sp(8.5f)),
                textX, top + rowH * 0.70f, Palette.TEXT_FAINT, sp(8.5f))
        }
        line(canvas, headerW - dp(0.5f), top, headerW - dp(0.5f), bottom, Palette.LINE, dp(1f))
    }

    private fun drawLaneContent(canvas: Canvas, index: Int, w: Float, top: Float, bottom: Float) {
        val lane = lanes[index]
        // 竖向网格
        val barCount = max(1, ticksPerScreen() / barTicks)
        val pxPerBar = contentW() / barCount
        var bar = 0
        while (bar <= barCount) {
            val x = headerW + bar * pxPerBar - scrollX
            if (x in headerW..w) {
                line(canvas, x, top, x, bottom, Palette.withAlpha(Palette.LINE, 90), dp(0.8f))
            }
            bar++
        }

        if (lane.lengthTicks <= 0) {
            // 空状态：给出可执行动作，而不是一片空白
            label(canvas, "长按此区域导入音频", headerW + dp(16f), (top + bottom) / 2f + sp(4f),
                Palette.TEXT_FAINT, sp(10f))
            return
        }

        val x0 = max(tickToX(lane.startTick), headerW)
        val x1 = min(tickToX(lane.startTick + lane.lengthTicks), w)
        if (x1 <= x0) return
        val padY = dp(8f)
        val t = top + padY
        val blockBottom = bottom - padY

        // 片段底 + 边框（与截图的块状感一致）
        rect(canvas, x0, t, x1, blockBottom, Palette.withAlpha(lane.accent, 34), dp(6f))
        outline(canvas, x0, t, x1, blockBottom, Palette.withAlpha(lane.accent, 150), dp(6f), dp(1f))

        // 顶部标题条
        val titleH = min(sp(13f), (blockBottom - t) * 0.28f)
        if (x1 - x0 > dp(40f)) {
            rect(canvas, x0, t, x1, t + titleH, Palette.withAlpha(lane.accent, 150), dp(6f))
            label(canvas, clipText(lane.title, x1 - x0 - dp(10f), sp(8f)),
                x0 + dp(5f), t + titleH - dp(3f), Palette.BG, sp(8f), bold = true)
        }

        // 真实波形：每个像素列取峰值区间的最大值，上下对称画
        val peaks = lane.peaks
        if (peaks.isNotEmpty()) {
            val innerTop = t + titleH + dp(3f)
            val innerBottom = blockBottom - dp(3f)
            val mid = (innerTop + innerBottom) / 2f
            val half = ((innerBottom - innerTop) / 2f).coerceAtLeast(1f)
            val cols = (x1 - x0).toInt().coerceAtMost(1600)
            if (cols > 0) {
                b.fill.style = Paint.Style.FILL
                b.fill.color = Palette.withAlpha(lane.accent, 210)
                for (c in 0 until cols) {
                    val ratio = c.toFloat() / cols
                    val pi = (ratio * (peaks.size - 1)).toInt().coerceIn(0, peaks.size - 1)
                    val amp = peaks[pi].coerceIn(0f, 1f) * half
                    if (amp < 0.5f) continue
                    val x = x0 + c
                    canvas.drawRect(x, mid - amp, x + 1f, mid + amp, b.fill)
                }
            }
        }
    }

    private fun drawPlayhead(canvas: Canvas, h: Float) {
        val x = tickToX(playheadTick)
        if (x < headerW || x > width) return
        line(canvas, x, 0f, x, h, Palette.withAlpha(Palette.TEXT, 200), dp(1.4f))
        rect(canvas, x - dp(0.5f), 0f, x + dp(0.5f), rulerH, Palette.TEXT, dp(1f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val w = width.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                edgeStartY = e.y
                downX = e.x
                edgeOver = 0f
                if (e.x < headerW) {
                    val idx = ((e.y - rulerH + scrollY) / rowH).toInt()
                    pressedLane = idx
                    invalidate()
                    // 点轨道头：空轨直接进导入，有轨进设置
                    if (idx in lanes.indices && lanes[idx].lengthTicks > 0) onLaneTap?.invoke(idx)
                    else onRequestImport?.invoke()
                    return true
                }
                if (e.y > rulerH) {
                    val idx = ((e.y - rulerH + scrollY) / rowH).toInt()
                    if (idx in lanes.indices && lanes[idx].lengthTicks == 0) {
                        onRequestImport?.invoke()
                        return true
                    }
                }
                onSeek?.invoke(xToTick(e.x))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 只在竖向拖拽时做阻尼；横向主导的手势交还给定位/平移，避免互相打架。
                if (!bouncing) {
                    val dy = e.y - edgeStartY
                    val dx = e.x - downX
                    val vertical = abs(dy) > abs(dx) * 1.4f
                    if (vertical && dy > dp(24f)) {
                        edgeOver = (dy - dp(24f)).coerceAtLeast(0f)
                        applyBounce()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedLane = -1
                releaseBounce()
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 双指平移时间轴
                return true
            }
        }
        return true
    }
}
