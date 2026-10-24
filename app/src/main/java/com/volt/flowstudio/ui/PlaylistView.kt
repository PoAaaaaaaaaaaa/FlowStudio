package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.Clip
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Project
import kotlin.math.max
import kotlin.math.min

/**
 * 播放列表：横向是时间，纵向是轨道，片段可以拖、拉长、拖复制。
 * 顶部标尺点一下可以定位播放头，拖可以划循环区间。
 */
class PlaylistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var project: Project? = null
        set(v) { field = v; invalidate() }

    var playheadTick: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var selectedTrack: Int = 0
        set(v) { field = v; invalidate() }

    var pixelsPerBar: Float = 0f     // 0 = 自动铺满

    var loopOn: Boolean = false
    var loopStart: Int = 0
    var loopEnd: Int = Note.BAR * 8

    var onClipsChanged: (() -> Unit)? = null
    var onSeek: ((Int) -> Unit)? = null
    var onSelectTrack: ((Int) -> Unit)? = null
    var onClipTapped: ((Clip) -> Unit)? = null
    var onLoopChanged: ((Boolean, Int, Int) -> Unit)? = null

    private var headerWidth = 0f
    private var rulerHeight = 0f
    private var rowHeight = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    private enum class Drag { NONE, MOVE, RESIZE_R, SEEK, LOOP, PAN }
    private var drag = Drag.NONE
    private var activeClip: Clip? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var origStart = 0
    private var origLen = 0
    private var origTrack = 0
    private var lastClipTapTime = 0L
    private var lastClipTapped: Clip? = null
    private var panX = 0f
    private var panY = 0f
    private var loopAnchor = 0

    private fun barWidth(): Float {
        if (pixelsPerBar > 0f) return pixelsPerBar
        val p = project ?: return dp(120f)
        val bars = p.lengthBars().coerceAtLeast(8)
        val usable = width - headerWidth - dp(8f)
        return max(dp(56f), usable / bars.coerceAtMost(16).toFloat())
    }

    private fun tickToX(t: Int): Float = headerWidth + (t * barWidth() / Note.BAR) - scrollX
    private fun xToTick(x: Float): Int = (((x - headerWidth + scrollX) / barWidth()) * Note.BAR).toInt()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Palette.BG)

        val p = project ?: return
        headerWidth = dp(64f)
        rulerHeight = dp(26f)
        val tracks = p.tracks.size.coerceAtLeast(1)
        rowHeight = ((h - rulerHeight) / min(tracks, 8).coerceAtLeast(1)).coerceAtLeast(dp(26f))

        val bw = barWidth()

        // ---- 轨道行背景 ----
        for (i in 0 until tracks) {
            val top = rulerHeight + i * rowHeight - scrollY
            if (top > h || top + rowHeight < rulerHeight) continue
            val color = Palette.trackColor(i)
            if (i == selectedTrack) {
                rect(canvas, 0f, top, w, top + rowHeight, Palette.withAlpha(color, 22))
            } else if (i % 2 == 0) {
                rect(canvas, 0f, top, w, top + rowHeight, Palette.withAlpha(Palette.PANEL, 90))
            }
            line(canvas, 0f, top + rowHeight, w, top + rowHeight, Palette.LINE_SOFT, dp(1f))

            // 轨道头
            rect(canvas, 0f, top, headerWidth, top + rowHeight, Palette.PANEL)
            val name = p.trackAt(i)?.name ?: "轨 ${i + 1}"
            label(
                canvas, clipText(name, headerWidth - dp(10f), sp(9.5f)),
                dp(4f), top + rowHeight * 0.62f, color, sp(9.5f), bold = i == selectedTrack
            )
            line(canvas, headerWidth, top, headerWidth, top + rowHeight, Palette.LINE, dp(1f))
        }

        // ---- 竖向时间线 ----
        val barsVisible = ((w - headerWidth + scrollX) / bw).toInt() + 2
        val firstBar = (scrollX / bw).toInt()
        for (b in firstBar..firstBar + barsVisible) {
            val x = headerWidth + b * bw - scrollX
            if (x < headerWidth - 1 || x > w) continue
            line(canvas, x, rulerHeight, x, h, Palette.withAlpha(Palette.LINE, 150), dp(1f))
            // 四分格弱线
            for (q in 1 until 4) {
                val qx = x + q * bw / 4f
                if (qx in headerWidth..w) {
                    line(canvas, qx, rulerHeight, qx, h, Palette.withAlpha(Palette.LINE_SOFT, 110), dp(0.8f))
                }
            }
        }

        // ---- 标尺 ----
        rect(canvas, 0f, 0f, w, rulerHeight, Palette.PANEL)
        for (b in firstBar..firstBar + barsVisible) {
            val x = headerWidth + b * bw - scrollX
            if (x < headerWidth - 1 || x > w) continue
            label(canvas, "${b + 1}", x + dp(3f), rulerHeight - dp(5f), Palette.TEXT_DIM, sp(8.5f), bold = true)
            line(canvas, x, rulerHeight - dp(4f), x, rulerHeight, Palette.LINE, dp(1f))
        }

        // ---- 循环区间 ----
        if (loopOn) {
            val lx = tickToX(loopStart)
            val rx = tickToX(loopEnd)
            rect(
                canvas, max(lx, headerWidth), 0f, min(rx, w), rulerHeight,
                Palette.withAlpha(Palette.ACCENT, 70)
            )
            if (lx in headerWidth..w) line(canvas, lx, 0f, lx, h, Palette.withAlpha(Palette.ACCENT, 120), dp(1.5f))
            if (rx in headerWidth..w) line(canvas, rx, 0f, rx, h, Palette.withAlpha(Palette.ACCENT, 120), dp(1.5f))
        }

        // ---- 片段 ----
        for (clip in p.clips) {
            val top = rulerHeight + clip.track * rowHeight - scrollY
            if (top > h || top + rowHeight < rulerHeight) continue
            val x0 = tickToX(clip.start)
            val x1 = tickToX(clip.end)
            if (x1 < headerWidth || x0 > w) continue

            val color = Palette.trackColor(clip.track)
            val l = max(x0, headerWidth)
            val r = min(x1, w)
            val t = top + dp(2f)
            val bt = top + rowHeight - dp(2f)

            // 片段本体：用 pattern 里的音符画一层迷你条，一眼看出内容
            rect(canvas, l, t, r, bt, Palette.withAlpha(color, 62), dp(6f))
            outline(canvas, l, t, r, bt, Palette.withAlpha(color, 200), dp(6f), dp(1.2f))

            // 片段内容：画成波形包络，而不是几条横线。
            // FL 的片段一眼能看出"这段是密的还是疏的"，靠的就是这个。
            // 我们按时间切片统计该片段的音符能量（数量 × 力度 × 时长），
            // 归一后画成上下对称的包络。
            val pat = p.patternById(clip.patternId)
            if (pat != null) {
                val notes = pat.notesOrNull(clip.track)
                if (notes != null && notes.isNotEmpty()) {
                    drawWaveform(canvas, clip, notes, l, r, t, bt, color)
                }
            }

            // Pattern 名
            if (r - l > dp(28f)) {
                val title = pat?.name ?: "?"
                rect(canvas, l, t, min(l + dp(52f), r), t + dp(11f), Palette.withAlpha(color, 210), dp(3f))
                label(canvas, clipText(title, min(dp(50f), r - l), sp(8f)), l + dp(3f), t + dp(8.5f), Palette.BG, sp(8f), bold = true)
            }

            // 右边缘把手
            rect(canvas, r - dp(3.5f), t + dp(2f), r, bt - dp(2f), Palette.withAlpha(Palette.BG, 120))
        }

        // ---- 播放头 ----
        val px = tickToX(playheadTick)
        if (px in headerWidth..w) {
            line(canvas, px, 0f, px, h, Palette.RED, dp(1.6f))
            b.fill.color = Palette.RED
            b.fill.style = Paint.Style.FILL
            canvas.drawCircle(px, dp(4f), dp(3.5f), b.fill)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = e.x
                dragStartY = e.y
                panX = e.x
                panY = e.y

                // 标尺：划循环 / 定位
                if (e.y < rulerHeight) {
                    val tick = xToTick(e.x).coerceAtLeast(0)
                    loopAnchor = tick
                    val nearStart = kotlin.math.abs(tickToX(loopStart) - e.x) < dp(14f)
                    val nearEnd = kotlin.math.abs(tickToX(loopEnd) - e.x) < dp(14f)
                    if (loopOn && (nearStart || nearEnd)) {
                        drag = Drag.LOOP
                        loopStart = if (nearStart) tick else loopStart
                        loopEnd = if (nearEnd) tick else loopEnd
                    } else {
                        drag = Drag.LOOP
                        loopStart = tick
                        loopEnd = tick
                    }
                    invalidate()
                    return true
                }

                // 轨道头
                if (e.x < headerWidth) {
                    val row = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                    if (row in p.tracks.indices) {
                        onSelectTrack?.invoke(row)
                        invalidate()
                    }
                    return true
                }

                // 找片段
                val row = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                if (row !in p.tracks.indices) return true
                val tick = xToTick(e.x)
                val clip = p.clips.lastOrNull { it.track == row && tick >= it.start && tick < it.end }

                if (clip != null) {
                    activeClip = clip
                    origStart = clip.start
                    origLen = clip.length
                    origTrack = clip.track
                    drag = if (tickToX(clip.end) - e.x < dp(16f)) Drag.RESIZE_R else Drag.MOVE
                    onSelectTrack?.invoke(row)
                    onClipTapped?.invoke(clip)
                    invalidate()
                } else {
                    // 空白处：定位播放头
                    drag = Drag.SEEK
                    onSeek?.invoke(xToTick(e.x).coerceAtLeast(0))
                    onSelectTrack?.invoke(row)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                drag = Drag.PAN
                activeClip = null
                panX = (e.getX(0) + e.getX(1)) / 2f
                panY = (e.getY(0) + e.getY(1)) / 2f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.SEEK && kotlin.math.abs(e.x - dragStartX) > dp(12f)) drag = Drag.PAN
                when (drag) {
                    Drag.PAN -> {
                        val mx = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x
                        val my = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y
                        scrollX = (scrollX - (mx - panX)).coerceAtLeast(0f)
                        val contentH = p.tracks.size.coerceAtLeast(1) * rowHeight
                        scrollY = (scrollY - (my - panY)).coerceIn(0f, max(0f, contentH - height + rulerHeight))
                        panX = mx
                        panY = my
                        invalidate()
                    }
                    Drag.SEEK -> Unit
                    Drag.LOOP -> {
                        val tick = xToTick(e.x).coerceAtLeast(0)
                        loopStart = min(loopAnchor, tick)
                        loopEnd = max(loopAnchor, tick)
                        invalidate()
                    }
                    Drag.MOVE -> {
                        val c = activeClip ?: return true
                        val dTicks = xToTick(e.x) - xToTick(dragStartX)
                        val newStart = (origStart + dTicks).coerceAtLeast(0)
                        val newRow = ((e.y - rulerHeight + scrollY) / rowHeight).toInt().coerceIn(0, p.tracks.size - 1)
                        // 吸附到十六分
                        c.start = (Math.round(newStart.toFloat() / Note.STEP) * Note.STEP).coerceAtLeast(0)
                        c.track = newRow
                        invalidate()
                    }
                    Drag.RESIZE_R -> {
                        val c = activeClip ?: return true
                        val dTicks = xToTick(e.x) - xToTick(dragStartX)
                        val newLen = (origLen + dTicks).coerceAtLeast(Note.STEP * 2)
                        c.length = Math.round(newLen.toFloat() / Note.STEP) * Note.STEP
                        invalidate()
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag == Drag.LOOP) {
                    if (loopEnd - loopStart < Note.STEP) loopEnd = loopStart + Note.BAR
                    loopOn = true
                    onLoopChanged?.invoke(true, loopStart, loopEnd)
                }
                if (drag == Drag.MOVE || drag == Drag.RESIZE_R) {
                    onClipsChanged?.invoke()
                }
                drag = Drag.NONE
                activeClip = null
                invalidate()
                return true
            }
        }
        return true
    }

    /**
     * 把片段里的音符画成波形包络。
     *
     * 每个像素列统计落在这段时间里的音符：数量、力度、持续长度。
     * 三者相乘近似"这一列的响度"，归一后画成上下对称的柱。
     * 结果是：鼓 loop 看起来是一串均匀的脉冲，和弦铺底是一条厚实的带子，
     * 稀疏的旋律是几个孤立的峰 —— 跟 FL 里的观感一致。
     */
    private fun drawWaveform(
        canvas: Canvas,
        clip: Clip,
        notes: List<Note>,
        l: Float, r: Float, top: Float, bottom: Float,
        color: Int
    ) {
        val w = r - l
        if (w <= 2f) return

        val cols = w.toInt().coerceAtMost(600)
        if (cols <= 0) return
        val energy = FloatArray(cols)

        val clipStartTick = clip.start
        val clipEndTick = clip.end
        val innerH = (bottom - top) - dp(6f)
        if (innerH <= 0f) return

        for (n in notes) {
            // 音符在时间线上的绝对区间
            val nAbsStart = clipStartTick + n.start - clip.offset
            val nAbsEnd = nAbsStart + n.length
            if (nAbsEnd <= clipStartTick || nAbsStart >= clipEndTick) continue

            // 映射到像素列
            val c0 = ((maxOf(nAbsStart, clipStartTick) - clipStartTick).toFloat() /
                    (clipEndTick - clipStartTick) * cols).toInt().coerceIn(0, cols - 1)
            val c1 = ((minOf(nAbsEnd, clipEndTick) - clipStartTick).toFloat() /
                    (clipEndTick - clipStartTick) * cols).toInt().coerceIn(0, cols - 1)

            // 单音的"响度"：力度归一，长度的贡献开方衰减（长音不该压过重音）
            val amp = (n.velocity / 127f) * (0.6f + 0.4f * kotlin.math.sqrt(n.length / Note.PPQ.toFloat()))
            for (c in c0..c1) {
                // 起音处更强，尾音渐弱 —— 模拟真实波形包络
                val posInNote = if (c1 > c0) (c - c0).toFloat() / (c1 - c0) else 0f
                val decay = 1f - 0.45f * posInNote
                val v = amp * decay
                if (v > energy[c]) energy[c] = v
            }
        }

        // 归一
        var maxE = 0f
        for (v in energy) if (v > maxE) maxE = v
        if (maxE < 0.001f) return

        val mid = (top + bottom) / 2f
        val halfH = innerH / 2f

        b.fill.color = Palette.withAlpha(color, 235)
        b.fill.style = Paint.Style.FILL

        for (c in 0 until cols) {
            val e = energy[c] / maxE
            if (e < 0.01f) continue
            val h = halfH * e
            val x = l + c.toFloat()
            // 上下对称画，像波形
            canvas.drawRect(x, mid - h, x + 1.2f, mid + h, b.fill)
        }

        // 中线
        line(canvas, l, mid, r, mid, Palette.withAlpha(Palette.BG, 70), dp(0.8f))
    }

    fun scrollByX(px: Float) {
        scrollX = (scrollX + px).coerceAtLeast(0f)
        invalidate()
    }

    fun scrollToPlayheadIfNeeded(tick: Int) {
        val x = tickToX(tick)
        val w = width.toFloat()
        if (x > w * 0.85f || x < headerWidth) {
            scrollX = (tick * barWidth() / Note.BAR - (w - headerWidth) * 0.3f).coerceAtLeast(0f)
            invalidate()
        }
    }
}

