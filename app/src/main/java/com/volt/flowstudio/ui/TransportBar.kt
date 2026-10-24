package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import com.volt.flowstudio.model.Note
import kotlin.math.abs

/** 自适应走带条。宽屏显示全部控制，窄屏将次要控制收进「更多」。 */
class TransportBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var bpm = 128f
        set(value) { field = value; invalidate() }
    var positionTicks = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    var playing = false
        private set
    var loopOn = false
        private set
    var metronomeOn = false
        private set

    var onPlay: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onBpmChange: ((Float) -> Unit)? = null
    var onBpmTap: (() -> Unit)? = null
    var onLoopToggle: (() -> Unit)? = null
    var onMetronomeToggle: ((Boolean) -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onExport: (() -> Unit)? = null
    var onProject: (() -> Unit)? = null
    var onSwing: (() -> Unit)? = null
    var onMore: (() -> Unit)? = null

    private enum class Control { PLAY, STOP, BPM, LOOP, METRO, SAVE, EXPORT, PROJECT, SWING, MORE }
    private val boxes = linkedMapOf<Control, FloatArray>()
    private var pressed: Control? = null
    private var dragBpm = false
    private var bpmMoved = false
    private var lastY = 0f
    private var compact = false

    fun setPlaying(value: Boolean) { playing = value; invalidate() }
    fun setLoop(value: Boolean) { loopOn = value; invalidate() }
    fun setMetronome(value: Boolean) { metronomeOn = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        compact = w < dp(600f)
        boxes.clear()
        rect(canvas, 0f, 0f, w, h, Palette.SURFACE)
        line(canvas, 0f, h - 0.5f, w, h - 0.5f, Palette.LINE, dp(1f))
        val top = dp(7f)
        val bh = h - dp(14f)
        var x = dp(8f)

        box(Control.PLAY, x, top, dp(if (compact) 44f else 38f), bh)
        drawButton(canvas, Control.PLAY, if (playing) Palette.ACCENT else Palette.PANEL_HI)
        val play = boxes.getValue(Control.PLAY)
        if (playing) {
            rect(canvas, play[0] + playWidth(Control.PLAY) * 0.34f, top + bh * 0.25f,
                play[0] + playWidth(Control.PLAY) * 0.43f, top + bh * 0.75f, Palette.BG, dp(1f))
            rect(canvas, play[0] + playWidth(Control.PLAY) * 0.59f, top + bh * 0.25f,
                play[0] + playWidth(Control.PLAY) * 0.68f, top + bh * 0.75f, Palette.BG, dp(1f))
        } else {
            val path = Path()
            path.moveTo(play[0] + playWidth(Control.PLAY) * 0.39f, top + bh * 0.24f)
            path.lineTo(play[0] + playWidth(Control.PLAY) * 0.39f, top + bh * 0.76f)
            path.lineTo(play[0] + playWidth(Control.PLAY) * 0.72f, top + bh / 2f)
            path.close()
            b.fill.color = if (pressed == Control.PLAY) Palette.TEXT else Palette.ACCENT
            b.fill.style = android.graphics.Paint.Style.FILL
            canvas.drawPath(path, b.fill)
        }
        x = play[2] + dp(6f)

        box(Control.STOP, x, top, dp(if (compact) 40f else 34f), bh)
        drawButton(canvas, Control.STOP, Palette.PANEL_HI)
        val stop = boxes.getValue(Control.STOP)
        rect(canvas, stop[0] + playWidth(Control.STOP) * 0.36f, top + bh * 0.33f,
            stop[0] + playWidth(Control.STOP) * 0.64f, top + bh * 0.67f, Palette.TEXT_DIM, dp(1f))
        x = stop[2] + dp(8f)

        val bar = positionTicks / Note.BAR + 1
        val beat = (positionTicks % Note.BAR) / Note.PPQ + 1
        val step = (positionTicks % Note.PPQ) / Note.STEP + 1
        val tiny = w < dp(430f)
        val positionWidth = if (tiny) 0f else if (compact) dp(68f) else dp(82f)
        if (!tiny) {
            label(canvas, String.format("%03d:%02d:%02d", bar, beat, step), x + dp(3f),
                top + bh * 0.64f, Palette.CYAN, sp(if (compact) 12f else 14f))
            labelRight(canvas, "PLAY", x + positionWidth, top + bh * 0.64f, Palette.TEXT_FAINT, sp(7f))
            x += positionWidth + dp(5f)
        }

        box(Control.BPM, x, top, dp(if (compact) 65f else 70f), bh)
        drawButton(canvas, Control.BPM, Palette.PANEL)
        val bpmBox = boxes.getValue(Control.BPM)
        label(canvas, "BPM ↕", bpmBox[0] + dp(7f), top + dp(10f), Palette.TEXT_FAINT, sp(7.5f))
        label(canvas, String.format("%.1f", bpm), bpmBox[0] + dp(7f), top + bh - dp(5f),
            Palette.ACCENT, sp(13f), bold = true)
        x = bpmBox[2] + dp(6f)

        val exportW = dp(if (compact) 52f else 58f)
        box(Control.EXPORT, w - dp(8f) - exportW, top, exportW, bh)
        drawButton(canvas, Control.EXPORT, Palette.ACCENT)
        centeredLabel(canvas, Control.EXPORT, "导出", Palette.BG, bold = true)
        if (!compact) {
            // 宽度只够核心控制时保留「更多」，不让工程按钮覆盖 BPM。
            if (w < dp(760f)) {
                val right = boxes.getValue(Control.EXPORT)[0] - dp(6f)
                box(Control.MORE, right - dp(48f), top, dp(48f), bh)
                drawButton(canvas, Control.MORE, Palette.PANEL_HI)
                centeredLabel(canvas, Control.MORE, "更多", Palette.TEXT)
                return
            }
            box(Control.SAVE, w - dp(16f) - exportW - dp(48f), top, dp(48f), bh)
            drawButton(canvas, Control.SAVE, Palette.PANEL_HI)
            centeredLabel(canvas, Control.SAVE, "保存", Palette.TEXT)
            box(Control.PROJECT, boxes.getValue(Control.SAVE)[0] - dp(54f), top, dp(48f), bh)
            drawButton(canvas, Control.PROJECT, Palette.PANEL_HI)
            centeredLabel(canvas, Control.PROJECT, "工程", Palette.TEXT)
            box(Control.LOOP, x, top, dp(46f), bh)
            drawButton(canvas, Control.LOOP, if (loopOn) Palette.ACCENT else Palette.PANEL_HI)
            centeredLabel(canvas, Control.LOOP, "循环", if (loopOn) Palette.BG else Palette.TEXT_DIM)
            x = boxes.getValue(Control.LOOP)[2] + dp(6f)
            box(Control.METRO, x, top, dp(46f), bh)
            drawButton(canvas, Control.METRO, if (metronomeOn) Palette.CYAN else Palette.PANEL_HI)
            centeredLabel(canvas, Control.METRO, "节拍", if (metronomeOn) Palette.BG else Palette.TEXT_DIM)
            x = boxes.getValue(Control.METRO)[2] + dp(6f)
            box(Control.SWING, x, top, dp(46f), bh)
            drawButton(canvas, Control.SWING, Palette.PANEL_HI)
            centeredLabel(canvas, Control.SWING, "摇摆", Palette.TEXT_DIM)
        } else {
            val right = boxes.getValue(Control.EXPORT)[0] - dp(6f)
            box(Control.MORE, right - dp(48f), top, dp(48f), bh)
            drawButton(canvas, Control.MORE, Palette.PANEL_HI)
            centeredLabel(canvas, Control.MORE, "更多", Palette.TEXT)
            // 窄屏保留最常用的工程与循环入口。
            val moreLeft = boxes.getValue(Control.MORE)[0]
            box(Control.LOOP, moreLeft - dp(46f), top, dp(40f), bh)
            drawButton(canvas, Control.LOOP, if (loopOn) Palette.ACCENT else Palette.PANEL_HI)
            centeredLabel(canvas, Control.LOOP, "循环", if (loopOn) Palette.BG else Palette.TEXT_DIM)
            box(Control.PROJECT, boxes.getValue(Control.LOOP)[0] - dp(46f), top, dp(40f), bh)
            drawButton(canvas, Control.PROJECT, Palette.PANEL_HI)
            centeredLabel(canvas, Control.PROJECT, "工程", Palette.TEXT)
        }
    }

    private fun box(key: Control, left: Float, top: Float, width: Float, height: Float) {
        boxes[key] = floatArrayOf(left, top, left + width, top + height)
    }

    private fun playWidth(key: Control): Float = boxes.getValue(key).let { it[2] - it[0] }

    private fun drawButton(canvas: Canvas, key: Control, color: Int) {
        val r = boxes.getValue(key)
        rect(canvas, r[0], r[1], r[2], r[3], if (pressed == key) Palette.ACCENT_DIM else color, dp(8f))
        if (key != Control.PLAY && key != Control.EXPORT) {
            outline(canvas, r[0], r[1], r[2], r[3], Palette.withAlpha(Palette.LINE, 140), dp(8f), dp(0.7f))
        }
    }

    private fun centeredLabel(canvas: Canvas, key: Control, text: String, color: Int, bold: Boolean = false) {
        val r = boxes.getValue(key)
        label(canvas, text, (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f + sp(3.5f), color, sp(10f), bold, true)
    }

    private fun at(x: Float, y: Float): Control? = boxes.entries.firstOrNull {
        hit(x, y, it.value[0], it.value[1], it.value[2], it.value[3])
    }?.key

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = at(event.x, event.y)
                dragBpm = pressed == Control.BPM
                bpmMoved = false
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragBpm) {
                    val dy = lastY - event.y
                    if (abs(dy) > 0.5f) bpmMoved = true
                    bpm = (bpm + dy * 0.32f).coerceIn(40f, 260f)
                    lastY = event.y
                    onBpmChange?.invoke(bpm)
                } else if (pressed != at(event.x, event.y)) {
                    pressed = null
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val action = if (pressed == at(event.x, event.y)) pressed else null
                if (dragBpm) {
                    bpm = (Math.round(bpm * 10f) / 10f).coerceIn(40f, 260f)
                    onBpmChange?.invoke(bpm)
                    if (!bpmMoved) onBpmTap?.invoke()
                } else {
                    when (action) {
                        Control.PLAY -> onPlay?.invoke()
                        Control.STOP -> onStop?.invoke()
                        Control.LOOP -> onLoopToggle?.invoke()
                        Control.METRO -> {
                            metronomeOn = !metronomeOn
                            onMetronomeToggle?.invoke(metronomeOn)
                        }
                        Control.SAVE -> onSave?.invoke()
                        Control.EXPORT -> onExport?.invoke()
                        Control.PROJECT -> onProject?.invoke()
                        Control.SWING -> onSwing?.invoke()
                        Control.MORE -> onMore?.invoke()
                        else -> Unit
                    }
                    if (action != null) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                pressed = null
                dragBpm = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                dragBpm = false
                invalidate()
            }
        }
        return true
    }
}

/** 可点选、可左右滑动的页签。手指抬起后才切换，避免滑动时误触。 */
class TabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {
    var tabs: List<String> = emptyList()
        set(value) { field = value; invalidate() }
    var selected = 0
        set(value) { field = value; invalidate() }
    var onTabSelected: ((Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var pressed = -1

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect(canvas, 0f, 0f, w, h, Palette.SURFACE)
        if (tabs.isEmpty()) return
        val tw = w / tabs.size
        tabs.indices.forEach { index ->
            val x0 = index * tw
            val active = index == selected
            if (active || pressed == index) {
                rect(canvas, x0 + dp(5f), dp(4f), x0 + tw - dp(5f), h - dp(4f),
                    if (active) Palette.withAlpha(Palette.ACCENT, 30) else Palette.PANEL_HI, dp(8f))
                if (active) rect(canvas, x0 + dp(14f), h - dp(2f), x0 + tw - dp(14f), h, Palette.ACCENT, dp(1f))
            }
            label(canvas, tabs[index], x0 + tw / 2f, h / 2f + sp(4f),
                if (active) Palette.ACCENT else Palette.TEXT_DIM, sp(11f), active, true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tabs.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = (event.x / (width.toFloat() / tabs.size)).toInt().coerceIn(0, tabs.lastIndex)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > dp(45f) && abs(dx) > abs(dy) * 1.4f) {
                    val target = (selected + if (dx < 0) 1 else -1).coerceIn(0, tabs.lastIndex)
                    if (target != selected) onTabSelected?.invoke(target)
                } else if (abs(dx) < dp(24f) && abs(dy) < dp(24f)) {
                    val index = (event.x / (width.toFloat() / tabs.size)).toInt().coerceIn(0, tabs.lastIndex)
                    onTabSelected?.invoke(index)
                }
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
