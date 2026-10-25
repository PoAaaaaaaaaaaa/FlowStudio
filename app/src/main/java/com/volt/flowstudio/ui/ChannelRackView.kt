package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.volt.flowstudio.model.Instrument
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Project
import kotlin.math.max
import kotlin.math.min

/**
 * 通道机架 —— FL Studio 的骨架界面。
 *
 * 一屏里同时看到：每条通道的乐器、静音/独奏、音量、声像，以及它在这个样式里的步进。
 * FL 之所以好用，很大程度是因为"选通道 → 点格子"这件事不需要切换界面。
 *
 * 与步进器的区别：步进器是"看这个样式的所有轨道"，机架是"管理通道本身"。
 */
class ChannelRackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var project: Project? = null
        set(v) { field = v; invalidate() }

    var currentPatternIndex: Int = 0
        set(v) { field = v; invalidate() }

    var playheadTick: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var selectedTrack: Int = 0
        set(v) { field = v; invalidate() }

    /** 每行的电平，0..1000 */
    var levels: IntArray = IntArray(0)
        set(v) { field = v; invalidate() }

    var onSelectTrack: ((Int) -> Unit)? = null
    var onToggleStep: ((track: Int, tick: Int) -> Unit)? = null
    var onToggleMute: ((Int) -> Unit)? = null
    var onToggleSolo: ((Int) -> Unit)? = null
    var onVolumeChange: ((Int, Float) -> Unit)? = null
    var onOpenInstrument: ((Int) -> Unit)? = null
    var onOpenFx: ((Int) -> Unit)? = null
    var onPreview: ((Int) -> Unit)? = null
    var onAddTrack: (() -> Unit)? = null
    var onRemoveTrack: ((Int) -> Unit)? = null
    var onTrackActions: ((Int) -> Unit)? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onLongPress(e: MotionEvent) {
            val index = ((e.y - headerH) / rowHeight).toInt()
            if (e.x < colMute && index in (project?.tracks?.indices ?: IntRange.EMPTY)) {
                onTrackActions?.invoke(index)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    })

    private var rowHeight = 0f
    private var headerH = 0f   // 顶部操作栏
    private val addBtnH get() = dp(40f)

    // 每行内部的列位置
    private var colName = 0f
    private var colMute = 0f
    private var colSolo = 0f
    private var colVol = 0f
    private var colSteps = 0f

    private enum class Drag { NONE, VOLUME }
    private var drag = Drag.NONE
    private var dragTrack = -1
    private var dragStartX = 0f
    private var dragStartVol = 0f
    private var stepsPerRow = 16

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Palette.BG)

        val p = project ?: return
        headerH = dp(32f)

        // 列布局：屏宽的分配比例
        colName = w * 0.00f
        val portrait = w < dp(600f)
        // 名称列必须有硬上限：截图里它占了近一半宽度，把步进格压得只剩一条。
        // 步进网格才是主操作区，名称够读即可。
        val nameW = if (portrait) min(w * 0.26f, dp(110f))
        else min(w * 0.20f, dp(190f)).coerceAtLeast(dp(120f))
        colMute = nameW
        colSolo = colMute + dp(if (portrait) 32f else 36f)
        colVol = colSolo + dp(if (portrait) 32f else 36f)
        colSteps = colVol + dp(if (portrait) 70f else 76f)
        val stepsW = (w - colSteps).coerceAtLeast(dp(80f))

        val rows = p.tracks.size
        if (rows == 0) {
            label(canvas, "还没有通道。点下面的「+ 添加通道」", dp(16f), h / 2, Palette.TEXT_DIM, sp(13f))
            drawAddBar(canvas, w, h)
            return
        }

        val availH = h - headerH - addBtnH
        // 行高上限收到 56dp：宽屏上原来的 64dp 会让 8 条轨道刚好溢出一行，
        // 底部「添加通道」就被挤到屏幕之外。
        rowHeight = (availH / rows.coerceAtMost(10)).coerceIn(dp(34f), dp(56f))

        // ---- 顶部：样式选择 + 摇摆 ----
        rect(canvas, 0f, 0f, w, headerH, Palette.SURFACE)
        rect(canvas, 0f, headerH - dp(1f), w, headerH, Palette.LINE_SOFT)
        val pat = p.patternAt(currentPatternIndex)
        label(
            canvas, "样式 ${pat?.name ?: "-"}  ·  ${pat?.bars ?: 0} 小节",
            dp(10f), dp(14f), Palette.ACCENT, sp(10.5f), bold = true
        )
        labelRight(canvas, "点通道名调音色 · 长按管理", w - dp(10f), dp(14f), Palette.TEXT_FAINT, sp(8.5f))
        if (p.swing > 0.001f) {
            label(canvas, "SWING ${(p.swing * 100).toInt()}%", dp(10f), headerH - dp(5f),
                Palette.CYAN, sp(7.5f), bold = true)
        }

        // ---- 每行 ----
        val patNotes = pat
        stepsPerRow = (patNotes?.bars ?: 2) * 16

        for (i in 0 until rows) {
            val top = headerH + i * rowHeight
            val bottom = top + rowHeight
            if (top > h) break
            val t = p.tracks[i] ?: continue
            val color = Palette.trackColor(i)
            val isSel = i == selectedTrack

            // 每行是一张独立通道卡；留白让选择态和触摸边界更明确。
            rect(canvas, dp(2f), top + dp(2f), w - dp(2f), bottom - dp(2f),
                if (isSel) Palette.withAlpha(color, 30) else
                    if (i % 2 == 0) Palette.PANEL else Palette.SURFACE, dp(7f))
            if (isSel) {
                rect(canvas, 0f, top, dp(3f), bottom, color)
            }
            line(canvas, 0f, bottom, w, bottom, Palette.LINE_SOFT, dp(1f))

            // ---- 通道名 + 乐器 ----
            val nameX = colName + dp(10f)
            label(canvas, clipText(t.name, nameW - dp(14f), sp(11f)),
                nameX, top + rowHeight * 0.44f, color, sp(11f), bold = true)
            label(canvas, t.instrument.cn, nameX, top + rowHeight * 0.78f, Palette.TEXT_FAINT, sp(8.5f))

            // 电平条（名字右侧一条细竖线）
            val lvl = (levels.getOrElse(i) { 0 }).coerceIn(0, 1000) / 1000f
            if (lvl > 0.01f) {
                val barL = nameW - dp(5f)
                val barH = (rowHeight - dp(10f)) * lvl
                rect(canvas, barL, bottom - dp(5f) - barH, barL + dp(3f), bottom - dp(5f),
                    if (lvl > 0.9f) Palette.RED else Palette.GREEN, dp(1f))
            }

            // ---- 静音 ----
            val btnTop = top + rowHeight / 2 - dp(11f)
            rect(canvas, colMute + dp(2f), btnTop, colMute + dp(26f), btnTop + dp(22f),
                if (t.mute) Palette.RED else Palette.PANEL_HI, dp(6f))
            label(canvas, "M", colMute + dp(14f), btnTop + dp(15f),
                if (t.mute) Palette.BG else Palette.TEXT_DIM, sp(9f), bold = true, center = true)

            // ---- 独奏 ----
            rect(canvas, colSolo + dp(2f), btnTop, colSolo + dp(26f), btnTop + dp(22f),
                if (t.solo) Palette.YELLOW else Palette.PANEL_HI, dp(6f))
            label(canvas, "S", colSolo + dp(14f), btnTop + dp(15f),
                if (t.solo) Palette.BG else Palette.TEXT_DIM, sp(9f), bold = true, center = true)

            // ---- 音量条 ----
            val volL = colVol + dp(4f)
            val volW = colVol + dp(58f) - volL
            val volY = top + rowHeight / 2
            rect(canvas, volL, volY - dp(2f), volL + volW, volY + dp(2f), Palette.LINE, dp(2f))
            val knobX = volL + volW * (t.volume / 1.5f).coerceIn(0f, 1f)
            rect(canvas, volL, volY - dp(2f), knobX, volY + dp(2f), color, dp(2f))
            canvas.drawCircle(knobX, volY, dp(4f), b.fill.apply { this.color = color })
            label(canvas, "${(t.volume * 100).toInt()}", volL + volW / 2, top + rowHeight - dp(4f),
                Palette.TEXT_FAINT, sp(7f), center = true)

            // ---- 步进格 ----
            val gridL = colSteps
            val stepW = stepsW / stepsPerRow
            val notes = patNotes?.notesOrNull(i)
            for (s in 0 until stepsPerRow) {
                val x = gridL + s * stepW
                val isBeat = s % 4 == 0
                val isBarStart = s % 16 == 0
                val cellT = top + dp(4f)
                val cellB = bottom - dp(4f)
                if (stepW < dp(4f)) break
                val tick = s * Note.STEP
                val hasNote = notes?.any { it.start == tick } == true
                val pad = if (stepW > dp(7f)) dp(1.2f) else dp(0.5f)

                if (hasNote) {
                    rect(canvas, x + pad, cellT, x + stepW - pad, cellB, color, dp(2f))
                } else {
                    val bg = when {
                        isBarStart -> Palette.PANEL_HI
                        isBeat -> Palette.withAlpha(Palette.PANEL_HI, 150)
                        else -> Palette.withAlpha(Palette.PANEL, 120)
                    }
                    rect(canvas, x + pad, cellT, x + stepW - pad, cellB, bg, dp(1.5f))
                }
            }

            // 播放头
            if (patNotes != null) {
                val local = playheadTick % patNotes.lengthTicks
                val px = gridL + (local.toFloat() / Note.STEP) * stepW
                if (px in gridL..(gridL + stepsW)) {
                    line(canvas, px, top, px, bottom, Palette.ACCENT, dp(1.4f))
                }
            }
        }

        drawAddBar(canvas, w, h)
    }

    private fun drawAddBar(canvas: Canvas, w: Float, h: Float) {
        val top = h - addBtnH
        rect(canvas, 0f, top, w, h, Palette.PANEL)
        line(canvas, 0f, top, w, top, Palette.LINE, dp(1f))
        val cx = w / 2
        rect(canvas, cx - dp(76f), top + dp(4f), cx + dp(76f), h - dp(4f),
            Palette.ACCENT, dp(8f))
        label(canvas, "+ 添加通道", cx, top + dp(21f), Palette.BG, sp(10.5f), bold = true, center = true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestures.onTouchEvent(e)
        val p = project ?: return false
        val w = width.toFloat()
        val h = height.toFloat()
        val pat = p.patternAt(currentPatternIndex)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                // 添加通道
                if (e.y > h - addBtnH) {
                    onAddTrack?.invoke()
                    return true
                }

                val idx = ((e.y - headerH) / rowHeight).toInt()
                if (idx !in p.tracks.indices) return true
                val t = p.tracks[idx]

                onSelectTrack?.invoke(idx)

                // 静音
                if (e.x in colMute..(colMute + dp(24f))) {
                    onToggleMute?.invoke(idx)
                    return true
                }
                // 独奏
                if (e.x in colSolo..(colSolo + dp(24f))) {
                    onToggleSolo?.invoke(idx)
                    return true
                }
                // 音量
                if (e.x in colVol..(colVol + dp(60f))) {
                    drag = Drag.VOLUME
                    dragTrack = idx
                    dragStartX = e.x
                    dragStartVol = t.volume
                    updateVolume(e.x, idx)
                    return true
                }
                // 名字区：打开音色
                if (e.x < colMute) {
                    onOpenInstrument?.invoke(idx)
                    return true
                }
                // 步进格
                if (e.x >= colSteps && pat != null) {
                    val stepsW = w - colSteps
                    val stepW = stepsW / stepsPerRow
                    val s = ((e.x - colSteps) / stepW).toInt()
                    if (s in 0 until stepsPerRow) {
                        onToggleStep?.invoke(idx, s * Note.STEP)
                    }
                    return true
                }
                // 空白：试听
                onPreview?.invoke(idx)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.VOLUME && dragTrack >= 0) {
                    updateVolume(e.x, dragTrack)
                } else if (drag == Drag.NONE && e.x >= colSteps) {
                    // 拖动刷格子
                    val idx = ((e.y - headerH) / rowHeight).toInt()
                    if (idx in p.tracks.indices && pat != null) {
                        val stepsW = w - colSteps
                        val stepW = stepsW / stepsPerRow
                        val s = ((e.x - colSteps) / stepW).toInt()
                        if (s in 0 until stepsPerRow) {
                            val tick = s * Note.STEP
                            val has = pat.notesOrNull(idx)?.any { it.start == tick } == true
                            if (!has) onToggleStep?.invoke(idx, tick)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                dragTrack = -1
                return true
            }
        }
        return true
    }

    private fun updateVolume(x: Float, idx: Int) {
        val p = project ?: return
        val t = p.trackAt(idx) ?: return
        val volL = colVol + dp(4f)
        val volW = dp(54f)
        val n = ((x - volL) / volW).coerceIn(0f, 1f)
        t.volume = n * 1.5f
        onVolumeChange?.invoke(idx, t.volume)
        invalidate()
    }
}

