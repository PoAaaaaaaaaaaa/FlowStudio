package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.Project
import kotlin.math.max
import kotlin.math.min

/**
 * 混音台：每轨一条竖推子 + 声像 + 静音/独奏 + 电平表，右侧母带。
 * 推子直接改 engine，松手才落盘。
 */
class MixerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var project: Project? = null
        set(v) { field = v; invalidate() }

    var levels: IntArray = IntArray(0)
        set(v) { field = v; invalidate() }

    var masterL: Float = 0f
    var masterR: Float = 0f

    var selectedTrack: Int = 0
        set(v) { field = v }

    var onTrackChanged: ((Int) -> Unit)? = null
    var onSelectTrack: ((Int) -> Unit)? = null
    var onMasterChanged: (() -> Unit)? = null
    var onOpenFx: ((Int, Int) -> Unit)? = null
    var onOpenSound: ((Int) -> Unit)? = null
    var scrollX = 0f

    private var stripW = 0f
    private var masterW = 0f

    private enum class Drag { NONE, FADER, PAN, MASTER }
    private var drag = Drag.NONE
    private var dragTrack = -1
    private var dragStartY = 0f
    private var dragStartValue = 0f
    private var scrollStartX = 0f
    private var scrollStart = 0f
    private var draggingScroll = false

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Palette.BG)

        val p = project ?: return
        masterW = dp(66f)
        stripW = dp(64f)
        val count = p.tracks.size
        val available = w - masterW
        if (count * stripW > available) {
            stripW = max(dp(44f), available / count.coerceAtMost(6).toFloat())
        }

        val faderTop = dp(46f)
        val faderBottom = h - dp(58f)
        val faderH = faderBottom - faderTop

        for (i in 0 until count) {
            val x0 = i * stripW - scrollX
            val x1 = x0 + stripW
            if (x1 < 0 || x0 > available) continue

            val track = p.trackAt(i) ?: continue
            val color = Palette.trackColor(i)
            val isSel = i == selectedTrack

            // 通道条底
            rect(canvas, x0 + dp(2f), dp(2f), x1 - dp(2f), h - dp(4f), Palette.PANEL, dp(8f))
            if (isSel) {
                outline(canvas, x0 + dp(1f), 0f, x1 - dp(1f), h - dp(2f), color, dp(3f), dp(1.5f))
            }
            // 顶部颜色条
            rect(canvas, x0 + dp(1f), 0f, x1 - dp(1f), dp(3f), color)

            // 轨道名
            label(
                canvas, clipText(track.name, stripW - dp(6f), sp(9f)),
                x0 + stripW / 2, dp(14f), color, sp(9f), bold = true, center = true
            )

            // 乐器名
            label(canvas, track.instrument.cn, x0 + stripW / 2, dp(24f), Palette.TEXT_FAINT, sp(7.5f), center = true)

            // 效果槽指示：4 个小方块，有声就亮
            val slotW = (stripW - dp(10f)) / 4f
            for (s in 0 until 4) {
                val sx = x0 + dp(5f) + s * slotW
                val fx = track.effects.getOrNull(s)
                val on = fx != null && fx.type != com.volt.flowstudio.model.EffectType.NONE
                rect(
                    canvas, sx + dp(1f), dp(30f), sx + slotW - dp(1f), dp(38f),
                    if (on) Palette.withAlpha(color, 210) else Palette.PANEL_HI, dp(1.5f)
                )
            }

            // ---- 推子 ----
            val cx = x0 + stripW * 0.34f
            val trackW = dp(4f)
            val norm = (track.volume / 1.5f).coerceIn(0f, 1f)
            val knobY = faderBottom - faderH * norm

            rect(canvas, cx - trackW / 2, faderTop, cx + trackW / 2, faderBottom, Palette.LINE, trackW / 2)
            rect(canvas, cx - trackW / 2, knobY, cx + trackW / 2, faderBottom, color, trackW / 2)

            val knobW = stripW * 0.46f
            val knobH = dp(13f)
            b.rect.set(cx - knobW / 2, knobY - knobH / 2, cx + knobW / 2, knobY + knobH / 2)
            b.fill.color = Palette.PANEL_HI
            b.fill.style = Paint.Style.FILL
            canvas.drawRoundRect(b.rect, dp(2.5f), dp(2.5f), b.fill)
            outline(canvas, b.rect.left, b.rect.top, b.rect.right, b.rect.bottom, color, dp(2.5f), dp(1.3f))
            line(canvas, b.rect.left + dp(2f), knobY, b.rect.right - dp(2f), knobY, color, dp(1.2f))

            // ---- 电平表 ----
            val meterX = x0 + stripW * 0.78f
            val meterW = dp(5f)
            val lvl = (levels.getOrElse(i) { 0 }).coerceIn(0, 1000) / 1000f
            rect(canvas, meterX, faderTop, meterX + meterW, faderBottom, Palette.withAlpha(Palette.BG, 200), dp(1f))
            if (lvl > 0.001f) {
                val hgt = faderH * lvl
                val c = when {
                    lvl > 0.95f -> Palette.RED
                    lvl > 0.75f -> Palette.YELLOW
                    else -> Palette.GREEN
                }
                rect(canvas, meterX, faderBottom - hgt, meterX + meterW, faderBottom, c, dp(1f))
            }

            // ---- 声像 ----
            val panY = faderBottom + dp(9f)
            val panW = stripW - dp(12f)
            val panL = x0 + dp(6f)
            rect(canvas, panL, panY - dp(1.5f), panL + panW, panY + dp(1.5f), Palette.LINE, dp(1.5f))
            val panX = panL + panW * ((track.pan + 1f) / 2f)
            b.fill.color = color
            canvas.drawCircle(panX, panY, dp(3.5f), b.fill)
            label(canvas, "L", panL, panY + dp(10f), Palette.TEXT_FAINT, sp(6.5f))
            labelRight(canvas, "R", panL + panW, panY + dp(10f), Palette.TEXT_FAINT, sp(6.5f))

            // ---- 静音 / 独奏 ----
            val btnY = faderBottom + dp(24f)
            val btnW = (stripW - dp(10f)) / 2f
            val mRect = x0 + dp(5f) to btnY
            rect(
                canvas, mRect.first, mRect.second, mRect.first + btnW - dp(1.5f), btnY + dp(14f),
                if (track.mute) Palette.RED else Palette.PANEL_HI, dp(2f)
            )
            label(canvas, "M", mRect.first + btnW / 2, btnY + dp(10.5f),
                if (track.mute) Palette.BG else Palette.TEXT_DIM, sp(8.5f), bold = true, center = true)

            val sL = mRect.first + btnW + dp(1.5f)
            rect(
                canvas, sL, btnY, sL + btnW - dp(1.5f), btnY + dp(14f),
                if (track.solo) Palette.YELLOW else Palette.PANEL_HI, dp(2f)
            )
            label(canvas, "S", sL + btnW / 2, btnY + dp(10.5f),
                if (track.solo) Palette.BG else Palette.TEXT_DIM, sp(8.5f), bold = true, center = true)

            // 音量数值
            label(canvas, volDb(track.volume), x0 + stripW / 2, h - dp(4f), Palette.TEXT_DIM, sp(8f), center = true)
        }

        // ---- 母带条 ----
        val mx0 = w - masterW
        rect(canvas, mx0, 0f, w, h, Palette.SURFACE)
        line(canvas, mx0, 0f, mx0, h, Palette.LINE, dp(1f))
        rect(canvas, mx0, 0f, w, dp(3f), Palette.ACCENT)
        label(canvas, "母带", mx0 + masterW / 2, dp(16f), Palette.ACCENT, sp(9.5f), bold = true, center = true)

        val mcx = mx0 + masterW * 0.32f
        val mNorm = (p.masterVolume / 100f).coerceIn(0f, 1f)
        val mKnobY = faderBottom - faderH * mNorm
        rect(canvas, mcx - dp(2f), faderTop, mcx + dp(2f), faderBottom, Palette.LINE, dp(2f))
        rect(canvas, mcx - dp(2f), mKnobY, mcx + dp(2f), faderBottom, Palette.ACCENT, dp(2f))
        val mKnobW = masterW * 0.52f
        b.rect.set(mcx - mKnobW / 2, mKnobY - dp(6.5f), mcx + mKnobW / 2, mKnobY + dp(6.5f))
        b.fill.color = Palette.PANEL_HI
        canvas.drawRoundRect(b.rect, dp(2.5f), dp(2.5f), b.fill)
        outline(canvas, b.rect.left, b.rect.top, b.rect.right, b.rect.bottom, Palette.ACCENT, dp(2.5f), dp(1.3f))

        // 立体声电平
        val meterX = mx0 + masterW * 0.66f
        val mW = dp(6f)
        for (ch in 0 until 2) {
            val off = ch * (mW + dp(2f))
            val lv = (if (ch == 0) masterL else masterR).coerceIn(0f, 1f)
            rect(canvas, meterX + off, faderTop, meterX + off + mW, faderBottom, Palette.withAlpha(Palette.BG, 200), dp(1f))
            if (lv > 0.001f) {
                val hgt = faderH * lv
                val c = when {
                    lv > 0.98f -> Palette.RED
                    lv > 0.8f -> Palette.YELLOW
                    else -> Palette.GREEN
                }
                rect(canvas, meterX + off, faderBottom - hgt, meterX + off + mW, faderBottom, c, dp(1f))
            }
        }

        label(canvas, "${p.masterVolume}", mx0 + masterW / 2, h - dp(16f), Palette.TEXT, sp(9f), bold = true, center = true)
        // 限幅器状态
        val lim = p.masterLimiter
        rect(canvas, mx0 + dp(8f), h - dp(13f), w - dp(8f), h - dp(3f),
            if (lim) Palette.withAlpha(Palette.GREEN, 190) else Palette.PANEL_HI, dp(2f))
        label(canvas, if (lim) "限幅 开" else "限幅 关", mx0 + masterW / 2, h - dp(5.5f),
            if (lim) Palette.BG else Palette.TEXT_DIM, sp(7.5f), bold = true, center = true)
    }

    private fun volDb(v: Float): String {
        if (v <= 0.001f) return "-inf"
        val db = 20f * kotlin.math.log10(v.toDouble())
        return String.format("%.1f", db)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        val w = width.toFloat()
        val h = height.toFloat()
        val faderTop = dp(46f)
        val faderBottom = h - dp(58f)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartY = e.y
                scrollStartX = e.x
                scrollStart = scrollX
                draggingScroll = false

                // 母带区
                if (e.x >= w - masterW) {
                    if (e.y > h - dp(20f)) {
                        p.masterLimiter = !p.masterLimiter
                        onMasterChanged?.invoke()
                        invalidate()
                        return true
                    }
                    drag = Drag.MASTER
                    dragStartValue = p.masterVolume / 100f
                    return true
                }

                val idx = ((e.x + scrollX) / stripW).toInt()
                if (idx !in p.tracks.indices) return true
                val x0 = idx * stripW - scrollX
                val track = p.trackAt(idx) ?: return true
                onSelectTrack?.invoke(idx)

                // 效果槽
                if (e.y in dp(28f)..dp(40f)) {
                    val slotW = (stripW - dp(10f)) / 4f
                    val s = (((e.x - x0) - dp(5f)) / slotW).toInt()
                    if (s in 0 until 4) {
                        onOpenFx?.invoke(idx, s)
                        return true
                    }
                }
                // 名字区 = 打开音色
                if (e.y < dp(28f)) {
                    onOpenSound?.invoke(idx)
                    return true
                }
                // 声像
                val panY = faderBottom + dp(9f)
                if (kotlin.math.abs(e.y - panY) < dp(12f)) {
                    val panL = x0 + dp(6f)
                    val panW = stripW - dp(12f)
                    track.pan = (((e.x - panL) / panW) * 2f - 1f).coerceIn(-1f, 1f)
                    drag = Drag.PAN
                    dragTrack = idx
                    onTrackChanged?.invoke(idx)
                    invalidate()
                    return true
                }
                // 静音 / 独奏
                val btnY = faderBottom + dp(24f)
                if (e.y in btnY..(btnY + dp(14f))) {
                    val btnW = (stripW - dp(10f)) / 2f
                    val local = e.x - x0 - dp(5f)
                    if (local < btnW) {
                        track.mute = !track.mute
                    } else {
                        track.solo = !track.solo
                    }
                    onTrackChanged?.invoke(idx)
                    invalidate()
                    return true
                }

                drag = Drag.FADER
                dragTrack = idx
                dragStartValue = track.volume
                if (e.y in faderTop..faderBottom) {
                    val norm = ((faderBottom - e.y) / (faderBottom - faderTop)).coerceIn(0f, 1f)
                    track.volume = norm * 1.5f
                    onTrackChanged?.invoke(idx)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE && kotlin.math.abs(e.x - scrollStartX) > dp(8f)) draggingScroll = true
                if (draggingScroll) {
                    val maxScroll = max(0f, p.tracks.size * stripW - (w - masterW))
                    scrollX = (scrollStart - (e.x - scrollStartX)).coerceIn(0f, maxScroll)
                    invalidate()
                    return true
                }
                when (drag) {
                    Drag.FADER -> {
                        val t = p.trackAt(dragTrack) ?: return true
                        val delta = (dragStartY - e.y) / max(1f, faderBottom - faderTop) * 1.5f
                        t.volume = (dragStartValue + delta).coerceIn(0f, 1.5f)
                        onTrackChanged?.invoke(dragTrack)
                        invalidate()
                    }
                    Drag.PAN -> {
                        val t = p.trackAt(dragTrack) ?: return true
                        val x0 = dragTrack * stripW - scrollX
                        val panL = x0 + dp(6f)
                        val panW = stripW - dp(12f)
                        t.pan = (((e.x - panL) / panW) * 2f - 1f).coerceIn(-1f, 1f)
                        onTrackChanged?.invoke(dragTrack)
                        invalidate()
                    }
                    Drag.MASTER -> {
                        val delta = (dragStartY - e.y) / max(1f, faderBottom - faderTop)
                        p.masterVolume = (((dragStartValue + delta) * 100f).toInt()).coerceIn(0, 100)
                        onMasterChanged?.invoke()
                        invalidate()
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                draggingScroll = false
                invalidate()
                return true
            }
        }
        return true
    }
}

