package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.Note

/** 顶部走带条：播放/停止、BPM、小节:拍:格、循环、节拍器、保存、导出。 */
class TransportBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var bpm: Float = 128f
        set(v) { field = v; invalidate() }

    var positionTicks: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var playing: Boolean = false
        private set
    var loopOn: Boolean = false
        private set
    var metronomeOn: Boolean = false
        private set

    var onPlay: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onBpmChange: ((Float) -> Unit)? = null
    var onLoopToggle: (() -> Unit)? = null
    var onMetronomeToggle: ((Boolean) -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onExport: (() -> Unit)? = null
    var onProject: (() -> Unit)? = null
    var onSwing: (() -> Unit)? = null

    private var dragBpm = false
    private var lastY = 0f

    // 按钮布局（在 onDraw 里算好，触摸时复用）
    private var playRect = FloatArray(4)
    private var stopRect = FloatArray(4)
    private var bpmRect = FloatArray(4)
    private var loopRect = FloatArray(4)
    private var metroRect = FloatArray(4)
    private var saveRect = FloatArray(4)
    private var exportRect = FloatArray(4)
    private var projectRect = FloatArray(4)
    private var swingRect = FloatArray(4)

    fun setPlaying(v: Boolean) { playing = v; invalidate() }
    fun setLoop(v: Boolean) { loopOn = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect(canvas, 0f, 0f, w, h, Palette.PANEL)
        line(canvas, 0f, h - 0.5f, w, h - 0.5f, Palette.LINE, dp(1f))

        val btnH = h - dp(14f)
        val top = dp(7f)
        var x = dp(6f)

        // 播放
        playRect = floatArrayOf(x, top, x + dp(34f), top + btnH)
        rect(canvas, playRect[0], playRect[1], playRect[2], playRect[3],
            if (playing) Palette.ACCENT else Palette.PANEL_HI, dp(4f))
        if (playing) {
            // 暂停图标
            rect(canvas, playRect[0] + dp(12f), top + dp(7f), playRect[0] + dp(15.5f), top + btnH - dp(7f), Palette.BG, dp(1f))
            rect(canvas, playRect[0] + dp(19f), top + dp(7f), playRect[0] + dp(22.5f), top + btnH - dp(7f), Palette.BG, dp(1f))
        } else {
            val p = android.graphics.Path()
            p.moveTo(playRect[0] + dp(13f), top + dp(7f))
            p.lineTo(playRect[0] + dp(13f), top + btnH - dp(7f))
            p.lineTo(playRect[0] + dp(25f), top + btnH / 2)
            p.close()
            b.fill.color = Palette.ACCENT
            b.fill.style = android.graphics.Paint.Style.FILL
            canvas.drawPath(p, b.fill)
        }
        x += dp(38f)

        // 停止
        stopRect = floatArrayOf(x, top, x + dp(30f), top + btnH)
        rect(canvas, stopRect[0], stopRect[1], stopRect[2], stopRect[3], Palette.PANEL_HI, dp(4f))
        rect(canvas, stopRect[0] + dp(11f), top + dp(8f), stopRect[0] + dp(19f), top + btnH - dp(8f), Palette.TEXT_DIM, dp(1.5f))
        x += dp(36f)

        // 位置显示
        val bar = positionTicks / Note.BAR + 1
        val beat = (positionTicks % Note.BAR) / Note.PPQ + 1
        val step = ((positionTicks % Note.PPQ) / Note.STEP) + 1
        val posText = String.format("%03d:%02d:%02d", bar, beat, step)
        b.textMono.color = Palette.CYAN
        b.textMono.textSize = sp(13f)
        b.textMono.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawText(posText, x + dp(4f), top + btnH * 0.68f, b.textMono)
        x += dp(84f)

        // BPM
        bpmRect = floatArrayOf(x, top, x + dp(62f), top + btnH)
        rect(canvas, bpmRect[0], bpmRect[1], bpmRect[2], bpmRect[3], Palette.PANEL_HI, dp(4f))
        label(canvas, "BPM", bpmRect[0] + dp(5f), top + dp(10f), Palette.TEXT_FAINT, sp(7.5f))
        label(canvas, String.format("%.1f", bpm), bpmRect[0] + dp(5f), top + btnH - dp(6f), Palette.ACCENT, sp(13f), bold = true)
        x += dp(66f)

        // 循环
        loopRect = floatArrayOf(x, top, x + dp(40f), top + btnH)
        rect(canvas, loopRect[0], loopRect[1], loopRect[2], loopRect[3],
            if (loopOn) Palette.withAlpha(Palette.ACCENT, 200) else Palette.PANEL_HI, dp(4f))
        label(canvas, "循环", loopRect[0] + dp(20f), top + btnH / 2 + dp(4f),
            if (loopOn) Palette.BG else Palette.TEXT_DIM, sp(9.5f), bold = true, center = true)
        x += dp(44f)

        // 节拍器
        metroRect = floatArrayOf(x, top, x + dp(40f), top + btnH)
        rect(canvas, metroRect[0], metroRect[1], metroRect[2], metroRect[3],
            if (metronomeOn) Palette.withAlpha(Palette.CYAN, 200) else Palette.PANEL_HI, dp(4f))
        label(canvas, "节拍", metroRect[0] + dp(20f), top + btnH / 2 + dp(4f),
            if (metronomeOn) Palette.BG else Palette.TEXT_DIM, sp(9.5f), bold = true, center = true)
        x += dp(44f)

        // 右侧：保存 / 导出
        val rightX = w - dp(6f)
        exportRect = floatArrayOf(rightX - dp(52f), top, rightX, top + btnH)
        rect(canvas, exportRect[0], exportRect[1], exportRect[2], exportRect[3], Palette.withAlpha(Palette.GREEN, 190), dp(4f))
        label(canvas, "导出", exportRect[0] + dp(26f), top + btnH / 2 + dp(4f), Palette.BG, sp(9.5f), bold = true, center = true)

        saveRect = floatArrayOf(rightX - dp(52f) - dp(46f), top, rightX - dp(52f) - dp(6f), top + btnH)
        rect(canvas, saveRect[0], saveRect[1], saveRect[2], saveRect[3], Palette.PANEL_HI, dp(4f))
        label(canvas, "保存", saveRect[0] + dp(20f), top + btnH / 2 + dp(4f), Palette.TEXT_DIM, sp(9.5f), bold = true, center = true)

        // 工程管理
        projectRect = floatArrayOf(saveRect[0] - dp(46f), top, saveRect[0] - dp(6f), top + btnH)
        rect(canvas, projectRect[0], projectRect[1], projectRect[2], projectRect[3], Palette.PANEL_HI, dp(4f))
        label(canvas, "工程", projectRect[0] + dp(20f), top + btnH / 2 + dp(4f), Palette.TEXT_DIM, sp(9.5f), bold = true, center = true)

        // 摇摆
        swingRect = floatArrayOf(metroRect[2] + dp(2f), top, metroRect[2] + dp(40f), top + btnH)
        rect(canvas, swingRect[0], swingRect[1], swingRect[2], swingRect[3], Palette.PANEL_HI, dp(4f))
        label(canvas, "摇摆", swingRect[0] + dp(19f), top + btnH / 2 + dp(4f), Palette.TEXT_DIM, sp(9.5f), bold = true, center = true)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = e.y
                if (hit(e.x, e.y, playRect[0], playRect[1], playRect[2], playRect[3])) { onPlay?.invoke(); return true }
                if (hit(e.x, e.y, stopRect[0], stopRect[1], stopRect[2], stopRect[3])) { onStop?.invoke(); return true }
                if (hit(e.x, e.y, loopRect[0], loopRect[1], loopRect[2], loopRect[3])) { onLoopToggle?.invoke(); return true }
                if (hit(e.x, e.y, metroRect[0], metroRect[1], metroRect[2], metroRect[3])) {
                    metronomeOn = !metronomeOn
                    onMetronomeToggle?.invoke(metronomeOn)
                    invalidate()
                    return true
                }
                if (hit(e.x, e.y, saveRect[0], saveRect[1], saveRect[2], saveRect[3])) { onSave?.invoke(); return true }
                if (hit(e.x, e.y, projectRect[0], projectRect[1], projectRect[2], projectRect[3])) { onProject?.invoke(); return true }
                if (hit(e.x, e.y, swingRect[0], swingRect[1], swingRect[2], swingRect[3])) { onSwing?.invoke(); return true }
                if (hit(e.x, e.y, exportRect[0], exportRect[1], exportRect[2], exportRect[3])) { onExport?.invoke(); return true }
                if (hit(e.x, e.y, bpmRect[0], bpmRect[1], bpmRect[2], bpmRect[3])) {
                    dragBpm = true
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragBpm) {
                    val dy = lastY - e.y
                    bpm = (bpm + dy * 0.35f).coerceIn(40f, 260f)
                    lastY = e.y
                    onBpmChange?.invoke(bpm)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragBpm) {
                    // 吸附到 0.1，避免存盘里出现 128.39999
                    bpm = (Math.round(bpm * 10f) / 10f).coerceIn(40f, 260f)
                    onBpmChange?.invoke(bpm)
                    dragBpm = false
                    invalidate()
                }
            }
        }
        return true
    }
}

/** 顶部标签栏。 */
class TabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var tabs: List<String> = emptyList()
        set(v) { field = v; invalidate() }

    var selected: Int = 0
        set(v) { field = v; invalidate() }

    var onTabSelected: ((Int) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect(canvas, 0f, 0f, w, h, Palette.BG)
        if (tabs.isEmpty()) return
        val tw = w / tabs.size
        for (i in tabs.indices) {
            val x0 = i * tw
            val isSel = i == selected
            if (isSel) {
                rect(canvas, x0 + dp(10f), h - dp(2.5f), x0 + tw - dp(10f), h, Palette.ACCENT, dp(1.5f))
            }
            label(
                canvas, tabs[i], x0 + tw / 2, h / 2 + dp(4f),
                if (isSel) Palette.ACCENT else Palette.TEXT_DIM,
                sp(10.5f), bold = isSel, center = true
            )
        }
        line(canvas, 0f, 0f, w, 0f, Palette.LINE, dp(1f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && tabs.isNotEmpty()) {
            val tw = width.toFloat() / tabs.size
            val idx = (e.x / tw).toInt().coerceIn(0, tabs.size - 1)
            selected = idx
            onTabSelected?.invoke(idx)
            invalidate()
            return true
        }
        return true
    }
}
