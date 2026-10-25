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
    /** 后退/快进：deltaTicks 由控件按拍/小节算好，正数向前。 */
    var onSeekRelative: ((Int) -> Unit)? = null
    /** 长按快进/后退时跳一小节；轻点跳一拍。 */
    var barTicks: Int = Note.BAR
    var onBpmChange: ((Float) -> Unit)? = null
    var onBpmTap: (() -> Unit)? = null
    var onLoopToggle: (() -> Unit)? = null
    var onMetronomeToggle: ((Boolean) -> Unit)? = null
    var onSave: (() -> Unit)? = null
    var onExport: (() -> Unit)? = null
    var onProject: (() -> Unit)? = null
    var onSwing: (() -> Unit)? = null
    var onMore: (() -> Unit)? = null

    private enum class Control {
        PLAY, STOP, REW, FWD, BPM, LOOP, METRO, SAVE, EXPORT, PROJECT, SWING, MORE
    }
    private val boxes = linkedMapOf<Control, FloatArray>()
    private var pressed: Control? = null
    private var dragBpm = false
    private var bpmMoved = false
    private var lastY = 0f
    private var compact = false
    /** 底部主控制条：播放/停止/后退/快进常驻。 */
    var bottomMode = false
    private var repeatSeek: Control? = null
    private var longPressConsumed = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val c = repeatSeek ?: return
            val step = if (c == Control.REW) -barTicks else barTicks
            onSeekRelative?.invoke(step)
            postDelayed(this, 190L)
        }
    }
    private val armLongPress = Runnable {
        val c = pressed
        if (c == Control.REW || c == Control.FWD) {
            longPressConsumed = true
            repeatSeek = c
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            removeCallbacks(repeatRunnable)
            post(repeatRunnable)
        }
    }

    fun setPlaying(value: Boolean) { playing = value; invalidate() }
    fun setLoop(value: Boolean) { loopOn = value; invalidate() }
    fun setMetronome(value: Boolean) { metronomeOn = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        compact = w < dp(600f)
        boxes.clear()
        if (bottomMode) {
            // 底栏不再画成"浮起的卡片"：那层高光加描边会跟内容区抢注意力。
            // 现在它只是一块比画布略亮的横条，靠明度分层，不靠线。
            rect(canvas, 0f, 0f, w, h, Palette.BG)
            rect(canvas, 0f, dp(3f), w, h, Palette.SURFACE, dp(14f))
            if (playing) {
                // 播放态：一条很短的强调线贴在最上沿，只提示"在走"，不闪。
                rect(canvas, dp(16f), dp(3f), dp(16f) + dp(44f), dp(4.4f),
                    Palette.withAlpha(Palette.ACCENT, 190), dp(1f))
            }
        } else {
            rect(canvas, 0f, 0f, w, h, Palette.SURFACE)
            line(canvas, 0f, h - 0.5f, w, h - 0.5f, Palette.LINE, dp(1f))
        }
        if (bottomMode) drawBottom(canvas, w, h) else drawTop(canvas, w, h)
    }

    /**
     * 底部主控制条：四个大按钮居中，位置与 BPM 在左，辅助工具在右。
     * 手感比顶部小按钮好得多——播放/停止是最高频操作，应该在拇指能到的地方。
     */
    private fun drawBottom(canvas: Canvas, w: Float, h: Float) {
        val bh = h - dp(10f)
        val top = dp(5f)
        val mainW = dp(44f)
        val playW = dp(56f)
        val gap = dp(8f)
        val groupW = mainW * 3 + playW + gap * 3

        // 三段式：左信息 / 中按钮组 / 右工具。
        // 窄屏必须让位：leftW 归零，按钮组居中，避免文字压到按钮上。
        val widen = w >= dp(700f)
        val rightW = if (w > dp(560f)) dp(112f) else dp(58f)
        val leftW = if (widen) dp(150f) else 0f
        val centerStart = ((w - groupW) / 2f)
            .coerceAtLeast(leftW + dp(8f))
            .coerceAtMost((w - rightW - groupW - dp(8f)).coerceAtLeast(dp(6f)))
        var gx = centerStart

        box(Control.REW, gx, top, mainW, bh)
        drawButton(canvas, Control.REW, Palette.PANEL_HI)
        drawSeekIcon(canvas, Control.REW, backward = true)
        gx += mainW + gap

        box(Control.PLAY, gx, top, playW, bh)
        // 播放键用"描边 + 图标"，不再整块填色：整块高饱和色块是最抢眼的部分。
        val pr = boxes.getValue(Control.PLAY)
        rect(canvas, pr[0], pr[1], pr[2], pr[3], Palette.PANEL_HI, dp(10f))
        if (playing) {
            // 只有正在播放时才给播放键一圈强调色：整屏唯一的高光在这里。
            outline(canvas, pr[0], pr[1], pr[2], pr[3], Palette.ACCENT, dp(10f), dp(1.3f))
        }
        drawPlayIcon(canvas)
        gx += playW + gap

        box(Control.STOP, gx, top, mainW, bh)
        drawButton(canvas, Control.STOP, Palette.PANEL_HI)
        val st = boxes.getValue(Control.STOP)
        rect(canvas, st[0] + mainW * 0.36f, top + bh * 0.34f,
            st[0] + mainW * 0.64f, top + bh * 0.66f, Palette.TEXT_DIM, dp(1.5f))
        gx += mainW + gap

        box(Control.FWD, gx, top, mainW, bh)
        drawButton(canvas, Control.FWD, Palette.PANEL_HI)
        drawSeekIcon(canvas, Control.FWD, backward = false)

        // 左侧：位置 + BPM + 播放态
        if (widen) {
            val bar = positionTicks / barTicks + 1
            val beat = (positionTicks % barTicks) / Note.PPQ + 1
            val step = (positionTicks % Note.PPQ) / Note.STEP + 1
            label(canvas, String.format("%03d:%02d:%02d", bar, beat, step),
                dp(16f), h * 0.52f, Palette.CYAN, sp(15f))
            val stateText = if (playing) "播放中" else if (positionTicks > 0) "已暂停" else "已停止"
            val stateColor = if (playing) Palette.GREEN else Palette.TEXT_FAINT
            label(canvas, stateText, dp(16f), h - dp(8f), stateColor, sp(8.5f), bold = playing)
            labelRight(canvas, "${String.format("%.1f", bpm)} BPM", dp(146f), h - dp(8f),
                Palette.TEXT_DIM, sp(8.5f))
        }

        // 右侧：更多 / 导出
        val moreW = dp(48f)
        val exportW = dp(54f)
        val rx = w - dp(10f) - moreW
        box(Control.MORE, rx, top, moreW, bh)
        drawButton(canvas, Control.MORE, Palette.PANEL_HI)
        centeredLabel(canvas, Control.MORE, "更多", Palette.TEXT)
        if (w > dp(560f)) {
            box(Control.EXPORT, rx - dp(6f) - exportW, top, exportW, bh)
            drawButton(canvas, Control.EXPORT, Palette.PANEL_HI)
            centeredLabel(canvas, Control.EXPORT, "导出", Palette.TEXT)
        }
    }

    /** 顶部信息条：BPM、位置、循环/节拍、保存、更多；播放控制不在这里。 */
    private fun drawTop(canvas: Canvas, w: Float, h: Float) {
        val top = dp(7f)
        val bh = h - dp(14f)
        var x = dp(8f)

        val bar = positionTicks / barTicks + 1
        val beat = (positionTicks % barTicks) / Note.PPQ + 1
        val step = (positionTicks % Note.PPQ) / Note.STEP + 1
        label(canvas, "FLOW", x, top + bh * 0.45f, Palette.ACCENT, sp(9f), bold = true)
        label(canvas, "STUDIO", x, top + bh * 0.82f, Palette.TEXT_FAINT, sp(7.5f))
        x += dp(42f)
        label(canvas, String.format("%03d:%02d:%02d", bar, beat, step),
            x, top + bh * 0.66f, Palette.CYAN, sp(14f))
        x += dp(78f)

        box(Control.BPM, x, top, dp(70f), bh)
        drawButton(canvas, Control.BPM, Palette.PANEL)
        val bpmBox = boxes.getValue(Control.BPM)
        label(canvas, "BPM ↕", bpmBox[0] + dp(7f), top + dp(10f), Palette.TEXT_FAINT, sp(7.5f))
        label(canvas, String.format("%.1f", bpm), bpmBox[0] + dp(7f), top + bh - dp(5f),
            Palette.ACCENT, sp(13f), bold = true)
        x = bpmBox[2] + dp(6f)

        box(Control.LOOP, x, top, dp(46f), bh)
        drawButton(canvas, Control.LOOP, if (loopOn) Palette.ACCENT else Palette.PANEL_HI)
        centeredLabel(canvas, Control.LOOP, "循环", if (loopOn) Palette.BG else Palette.TEXT_DIM)
        x += dp(50f)
        box(Control.METRO, x, top, dp(46f), bh)
        drawButton(canvas, Control.METRO, if (metronomeOn) Palette.CYAN else Palette.PANEL_HI)
        centeredLabel(canvas, Control.METRO, "节拍", if (metronomeOn) Palette.BG else Palette.TEXT_DIM)
        x += dp(50f)
        box(Control.SWING, x, top, dp(46f), bh)
        drawButton(canvas, Control.SWING, Palette.PANEL_HI)
        centeredLabel(canvas, Control.SWING, "摇摆", Palette.TEXT_DIM)

        val moreW = dp(48f)
        val rx = w - dp(8f) - moreW
        box(Control.MORE, rx, top, moreW, bh)
        drawButton(canvas, Control.MORE, Palette.PANEL_HI)
        centeredLabel(canvas, Control.MORE, "更多", Palette.TEXT)
        if (w > dp(680f)) {
            box(Control.SAVE, rx - dp(6f) - dp(48f), top, dp(48f), bh)
            drawButton(canvas, Control.SAVE, Palette.PANEL_HI)
            centeredLabel(canvas, Control.SAVE, "保存", Palette.TEXT)
            box(Control.PROJECT, boxes.getValue(Control.SAVE)[0] - dp(54f), top, dp(48f), bh)
            drawButton(canvas, Control.PROJECT, Palette.PANEL_HI)
            centeredLabel(canvas, Control.PROJECT, "工程", Palette.TEXT)
        }
    }

    private fun drawPlayIcon(canvas: Canvas) {
        val r = boxes.getValue(Control.PLAY)
        val pw = r[2] - r[0]
        val top = r[1]
        val bh = r[3] - r[1]
        if (playing) {
            rect(canvas, r[0] + pw * 0.34f, top + bh * 0.25f,
                r[0] + pw * 0.44f, top + bh * 0.75f, Palette.BG, dp(1.5f))
            rect(canvas, r[0] + pw * 0.56f, top + bh * 0.25f,
                r[0] + pw * 0.66f, top + bh * 0.75f, Palette.BG, dp(1.5f))
        } else {
            val path = Path()
            path.moveTo(r[0] + pw * 0.38f, top + bh * 0.24f)
            path.lineTo(r[0] + pw * 0.38f, top + bh * 0.76f)
            path.lineTo(r[0] + pw * 0.72f, top + bh / 2f)
            path.close()
            b.fill.color = if (playing) Palette.ACCENT else Palette.TEXT
            b.fill.style = android.graphics.Paint.Style.FILL
            canvas.drawPath(path, b.fill)
        }
    }

    private fun drawSeekIcon(canvas: Canvas, key: Control, backward: Boolean) {
        val r = boxes.getValue(key)
        val cw = r[2] - r[0]
        val top = r[1]
        val bh = r[3] - r[1]
        val mid = top + bh / 2f
        val cx = r[0] + cw / 2f
        val h = bh * 0.22f
        // 两根竖线像「⏪ / ⏩」，方向由 backward 决定
        // 后退：三角形朝左；快进：朝右。竖线始终在三角形后方。
        val dir = if (backward) -1f else 1f
        for (i in 0..1) {
            val baseX = cx + (i * dp(9f)) * dir
            val tipX = baseX + dir * dp(6f)
            val backX = baseX
            val path = Path()
            path.moveTo(backX, mid - h)
            path.lineTo(backX, mid + h)
            path.lineTo(tipX, mid)
            path.close()
            b.fill.color = Palette.TEXT
            b.fill.style = android.graphics.Paint.Style.FILL
            canvas.drawPath(path, b.fill)
        }
    }

    private fun box(key: Control, left: Float, top: Float, width: Float, height: Float) {
        boxes[key] = floatArrayOf(left, top, left + width, top + height)
    }

    private fun playWidth(key: Control): Float = boxes.getValue(key).let { it[2] - it[0] }

    /**
     * 按钮底。常态无描边 —— 描边会让一排按钮看起来像表格。
     * 只有按下时才提亮，反馈靠明度变化而不是边框。
     */
    private fun drawButton(canvas: Canvas, key: Control, color: Int) {
        val r = boxes.getValue(key)
        rect(canvas, r[0], r[1], r[2], r[3],
            if (pressed == key) Palette.PANEL_HI else color, dp(10f))
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
                // 长按后退/快进连续跳小节；短按在抬起时跳一拍。
                longPressConsumed = false
                if (pressed == Control.REW || pressed == Control.FWD) {
                    postDelayed(armLongPress, 420L)
                }
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
                removeCallbacks(repeatRunnable)
                removeCallbacks(armLongPress)
                val wasRepeating = longPressConsumed
                repeatSeek = null
                longPressConsumed = false
                val action = if (pressed == at(event.x, event.y)) pressed else null
                if (action == Control.REW || action == Control.FWD) {
                    if (!wasRepeating) {
                        val step = if (action == Control.REW) -Note.PPQ else Note.PPQ
                        onSeekRelative?.invoke(step)
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    pressed = null
                    invalidate()
                    return true
                }
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
                removeCallbacks(repeatRunnable)
                removeCallbacks(armLongPress)
                repeatSeek = null
                longPressConsumed = false
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
        // 页签整体是一枚悬浮胶囊，与内容之间留出空气感。
        rect(canvas, 0f, 0f, w, h, Palette.BG)
        if (tabs.isEmpty()) return
        val inset = dp(10f)
        val pillTop = dp(6f)
        val pillBottom = h - dp(6f)
        rect(canvas, inset, pillTop, w - inset, pillBottom, Palette.SURFACE, dp(13f))
        outline(canvas, inset, pillTop, w - inset, pillBottom,
            Palette.withAlpha(Palette.LINE, 150), dp(13f), dp(0.8f))

        val usable = w - inset * 2 - dp(8f)
        val tw = usable / tabs.size
        tabs.indices.forEach { index ->
            val x0 = inset + dp(4f) + index * tw
            val active = index == selected
            val pressedNow = pressed == index
            if (active) {
                rect(canvas, x0 + dp(3f), pillTop + dp(4f), x0 + tw - dp(3f), pillBottom - dp(4f),
                    Palette.withAlpha(Palette.ACCENT, 34), dp(10f))
                rect(canvas, x0 + dp(3f), pillTop + dp(4f), x0 + tw - dp(3f), pillTop + dp(5.5f),
                    Palette.ACCENT, dp(1f))
            } else if (pressedNow) {
                rect(canvas, x0 + dp(3f), pillTop + dp(4f), x0 + tw - dp(3f), pillBottom - dp(4f),
                    Palette.PANEL_HI, dp(10f))
            }
            label(canvas, tabs[index], x0 + tw / 2f, h / 2f + sp(4f),
                when {
                    active -> Palette.ACCENT
                    pressedNow -> Palette.TEXT
                    else -> Palette.TEXT_DIM
                }, sp(11f), active, true)
        }
    }

    /** 页签命中区必须与绘制时的胶囊内缩一致，否则点中间会选错。 */
    private fun tabIndexAt(x: Float): Int {
        if (tabs.isEmpty()) return 0
        val inset = dp(10f) + dp(4f)
        val usable = width.toFloat() - inset * 2
        if (usable <= 0f) return 0
        return ((x - inset) / (usable / tabs.size)).toInt().coerceIn(0, tabs.lastIndex)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tabs.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = tabIndexAt(event.x)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > dp(45f) && abs(dx) > abs(dy) * 1.4f) {
                    val target = (selected + if (dx < 0) 1 else -1).coerceIn(0, tabs.lastIndex)
                    if (target != selected) onTabSelected?.invoke(target)
                } else if (abs(dx) < dp(24f) && abs(dy) < dp(24f)) {
                    onTabSelected?.invoke(tabIndexAt(event.x))
                }
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
