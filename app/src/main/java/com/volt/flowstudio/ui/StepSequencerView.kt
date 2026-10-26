package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.Instrument
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * 步进器：每轨一排格子，点一下有音、再点没音。
 * 也负责鼓组的键盘触发（点格子右侧的名字试听）。
 */
class StepSequencerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var pattern: Pattern? = null
        set(v) { field = v; invalidate() }

    var trackNames: List<String> = emptyList()
        set(v) { field = v; invalidate() }

    var trackColors: List<Int> = emptyList()
        set(v) { field = v; invalidate() }

    var instrumentOf: ((Int) -> Instrument)? = null

    var playheadTick: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    /** 每轨当前显示的起始音高（鼓组按 36 起， melodic 按音阶）。 */
    var baseNote: Int = 36

    var onToggleStep: ((track: Int, tick: Int) -> Unit)? = null
    var onPreviewTrack: ((track: Int, pitch: Int) -> Unit)? = null
    var onSelectTrack: ((track: Int) -> Unit)? = null

    private var labelWidth = 0f
    private var headerHeight = 0f
    private var rowHeight = 0f
    private val scrollY = 0f

    private val stepsPerBar = 16

    override fun onDraw(canvas: Canvas) {
        val pat = pattern
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(CanvasBackdrop.fillColor(Palette.BG))

        if (pat == null) {
            label(canvas, "没有样式", dp(12f), h / 2, Palette.TEXT_DIM, sp(13f))
            return
        }

        labelWidth = dp(78f)
        headerHeight = dp(22f)
        val totalSteps = pat.bars * stepsPerBar
        val gridW = w - labelWidth
        val stepW = gridW / totalSteps.toFloat()
        val rows = trackNames.size.coerceAtLeast(1)
        rowHeight = ((h - headerHeight) / rows).coerceAtLeast(dp(20f))

        // 步进头部：小节号
        for (bar in 0 until pat.bars) {
            val x = labelWidth + bar * stepsPerBar * stepW
            line(canvas, x, 0f, x, h, Palette.LINE, dp(1f))
            label(canvas, "${bar + 1}", x + dp(4f), headerHeight - dp(7f), Palette.TEXT_FAINT, sp(9.5f))
        }

        // 行
        for (i in 0 until rows) {
            val top = headerHeight + i * rowHeight
            val bottom = top + rowHeight
            val color = trackColors.getOrElse(i) { Palette.ACCENT }

            // 行背景交替
            if (i % 2 == 0) {
                rect(canvas, 0f, top, w, bottom, Palette.withAlpha(Palette.PANEL, 110))
            }

            // 轨道名
            val name = trackNames.getOrElse(i) { "轨 ${i + 1}" }
            label(canvas, clipText(name, labelWidth - dp(10f), sp(10.5f)), dp(6f), top + rowHeight * 0.62f, color, sp(10.5f), bold = true)

            // 每格的方块
            val notes = pat.notesOrNull(i)
            for (s in 0 until totalSteps) {
                val x = labelWidth + s * stepW
                val isBeat = s % 4 == 0
                val isBarStart = s % 16 == 0
                val cellL = x + dp(1.5f)
                val cellR = x + stepW - dp(1.5f)
                val cellT = top + dp(2f)
                val cellB = bottom - dp(2f)
                if (cellR <= cellL) continue

                val tick = s * Note.STEP
                val hasNote = notes?.any { it.start == tick } == true

                if (hasNote) {
                    rect(canvas, cellL, cellT, cellR, cellB, color, dp(2.5f))
                    // 力度用亮度示意：越亮越响
                    val n = notes?.firstOrNull { it.start == tick }
                    if (n != null && n.velocity < 90) {
                        rect(
                            canvas, cellL, cellT, cellR, cellB,
                            Palette.withAlpha(Palette.BG, 60), dp(2.5f)
                        )
                    }
                } else {
                    val bg = when {
                        isBarStart -> Palette.PANEL_HI
                        isBeat -> Palette.withAlpha(Palette.PANEL_HI, 160)
                        else -> Palette.withAlpha(Palette.PANEL, 150)
                    }
                    rect(canvas, cellL, cellT, cellR, cellB, bg, dp(2.5f))
                }
            }
        }

        // 播放头
        if (playheadTick > 0) {
            val barLen = Note.BAR
            val localTick = playheadTick % (pat.bars * barLen)
            val sx = labelWidth + (localTick.toFloat() / Note.STEP) * stepW
            line(canvas, sx, 0f, sx, h, Palette.ACCENT, dp(1.6f))
            rect(canvas, sx - dp(2f), 0f, sx + dp(2f), h, Palette.withAlpha(Palette.ACCENT, 28))
        }

        // 边框
        line(canvas, labelWidth, 0f, labelWidth, h, Palette.LINE, dp(1f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pat = pattern ?: return false
        val totalSteps = pat.bars * stepsPerBar
        val gridW = width - labelWidth
        val stepW = gridW / totalSteps.toFloat()
        if (stepW <= 0f) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x < labelWidth) {
                    // 点名字：选中该轨并试听
                    val row = ((e.y - headerHeight) / rowHeight).toInt()
                    if (row in trackNames.indices) {
                        onSelectTrack?.invoke(row)
                        val pitch = if (instrumentOf?.invoke(row) == Instrument.DRUM) baseNote else baseNote + 24
                        onPreviewTrack?.invoke(row, pitch)
                    }
                    return true
                }
                val row = ((e.y - headerHeight) / rowHeight).toInt()
                if (row < 0 || row >= trackNames.size) return false
                val step = ((e.x - labelWidth) / stepW).toInt()
                if (step !in 0 until totalSteps) return false
                val tick = step * Note.STEP
                onSelectTrack?.invoke(row)
                onToggleStep?.invoke(row, tick)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 拖动刷格子，做鼓 loop 时比一次次点快得多
                if (e.x < labelWidth) return true
                val row = ((e.y - headerHeight) / rowHeight).toInt()
                if (row < 0 || row >= trackNames.size) return true
                val step = ((e.x - labelWidth) / stepW).toInt()
                if (step !in 0 until totalSteps) return true
                val tick = step * Note.STEP
                val has = pat.notesOrNull(row)?.any { it.start == tick } == true
                if (!has) onToggleStep?.invoke(row, tick)
                return true
            }
        }
        return true
    }
}
