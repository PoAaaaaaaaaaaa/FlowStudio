package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Pattern
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 钢琴卷帘：左侧键盘 + 右侧网格。支持画、拖、改长度、改力度、框选。
 * 这是整个 app 里交互最复杂的一块。
 */
class PianoRollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var pattern: Pattern? = null
        set(v) { field = v; invalidate() }
    var trackIndex: Int = 0
        set(v) { field = v; invalidate() }

    /** 当前显示的最低音高。 */
    var lowestNote: Int = 36
        set(v) { field = v; invalidate() }

    var playheadTick: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var zoom: Float = 1f
        set(v) { field = v.coerceIn(0.5f, 8f); invalidate() }

    /** 网格吸附：1 = 十六分音符。 */
    var snap: Int = Note.STEP

    var selected: MutableSet<Note> = mutableSetOf()

    var onNotesChanged: (() -> Unit)? = null
    var onPreview: ((pitch: Int, velocity: Int) -> Unit)? = null

    private var keyWidth = 0f
    private val keyHeight get() = dp(16f) * zoom.coerceAtMost(2f)
    private var scrollTicks = 0f

    private enum class Mode { NONE, DRAW, MOVE, RESIZE, VELOCITY, MARQUEE, PAN }
    private var mode = Mode.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var activeNote: Note? = null
    private var origStart = 0
    private var origLen = 0
    private var origPitch = 0
    // 双指手势：平移 + 缩放
    private var twoFinger = false
    private var lastPinchDist = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var marqueeL = 0f
    private var marqueeT = 0f
    private var marqueeR = 0f
    private var marqueeB = 0f

    private val velocityLaneHeight get() = dp(34f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Palette.BG)

        val pat = pattern ?: return
        keyWidth = dp(34f)
        val gridL = keyWidth
        val gridR = w
        val gridB = h - velocityLaneHeight
        val rowH = keyHeight
        val rows = ((gridB) / rowH).toInt().coerceAtLeast(1)


        // ---- 背景行（黑白键）----
        for (r in 0 until rows) {
            val pitch = lowestNote + (rows - 1 - r)
            if (pitch < 0 || pitch > 127) continue
            val y = r * rowH
            val isBlack = isBlackKey(pitch)
            val bg = when {
                isBlack -> 0xFF101219.toInt()
                pitch % 12 == 0 -> 0xFF191C24.toInt()
                else -> Palette.BG
            }
            rect(canvas, gridL, y, gridR, y + rowH, bg)
            // C 音的分隔线画亮一点，方便定位
            if (pitch % 12 == 0) {
                line(canvas, gridL, y + rowH, gridR, y + rowH, Palette.withAlpha(Palette.LINE, 200), dp(1f))
            }
        }

        // ---- 竖向网格线：按拍与小节 ----
        val ppt = pixelsPerTick()
        val beatTicks = Note.PPQ
        val firstTick = (scrollTicks / beatTicks).toInt() * beatTicks
        var t = firstTick
        while (true) {
            val x = gridL + (t - scrollTicks) * ppt
            if (x > gridR) break
            if (x >= gridL - 1) {
                val isBar = t % Note.BAR == 0
                val isBeat = t % (Note.PPQ) == 0
                if (isBar) line(canvas, x, 0f, x, gridB, Palette.LINE, dp(1.3f))
                else if (isBeat) line(canvas, x, 0f, x, gridB, Palette.withAlpha(Palette.LINE, 130), dp(1f))
                else line(canvas, x, 0f, x, gridB, Palette.withAlpha(Palette.LINE_SOFT, 120), dp(1f))
            }
            t += if (ppt * Note.PPQ > dp(60f)) Note.PPQ else Note.STEP * 7
        }

        // ---- 左侧键盘 ----
        rect(canvas, 0f, 0f, keyWidth, gridB, Palette.PANEL)
        for (r in 0 until rows) {
            val pitch = lowestNote + (rows - 1 - r)
            if (pitch < 0 || pitch > 127) continue
            val y = r * rowH
            val isBlack = isBlackKey(pitch)
            if (isBlack) {
                rect(canvas, 0f, y, keyWidth * 0.62f, y + rowH, 0xFF0A0B10.toInt())
            } else if (pitch % 12 == 0) {
                rect(canvas, 0f, y, keyWidth, y + rowH, 0xFFE8EAF0.toInt())
                label(canvas, "C${pitch / 12 - 1}", dp(4f), y + rowH * 0.72f, 0xFF1A1C24.toInt(), sp(8f), bold = true)
            }
            line(canvas, 0f, y + rowH, keyWidth, y + rowH, Palette.LINE_SOFT, dp(0.8f))
        }

        // ---- 音符 ----
        val notes = pat.notesOrNull(trackIndex) ?: mutableListOf()
        val selColor = Palette.ACCENT
        for (n in notes) {
            val r = (lowestNote + rows - 1 - n.pitch)
            if (n.pitch < lowestNote || r < 0 || r >= rows) continue
            val y = r * rowH
            val x0 = gridL + (n.start - scrollTicks) * ppt
            val x1 = x0 + n.length * ppt
            if (x1 < gridL || x0 > gridR) continue

            val isSel = selected.contains(n)
            val velAlpha = 100 + (n.velocity / 127f * 155f).toInt()
            val base = if (isSel) selColor else Palette.withAlpha(selColor, velAlpha)

            val l = max(x0, gridL)
            val rr = min(x1, gridR)
            rect(canvas, l, y + dp(1f), rr, y + rowH - dp(1f), base, dp(2.5f))
            if (isSel) {
                outline(canvas, l, y + dp(1f), rr, y + rowH - dp(1f), Palette.TEXT, dp(2.5f), dp(1.5f))
            }
            // 右边缘的把手，拖它改长度
            if (rr - l > dp(8f)) {
                rect(canvas, rr - dp(3f), y + dp(3f), rr, y + rowH - dp(3f), Palette.withAlpha(Palette.BG, 90))
            }
            if (rr - l > dp(22f)) {
                label(canvas, pitchName(n.pitch), l + dp(3f), y + rowH - dp(4f), Palette.BG, sp(8f), bold = true)
            }
        }

        // ---- 力度条 ----
        val vlTop = gridB
        rect(canvas, gridL, vlTop, gridR, h, 0xFF12141B.toInt())
        line(canvas, gridL, vlTop, gridR, vlTop, Palette.LINE, dp(1f))
        label(canvas, "力度", dp(4f), vlTop + dp(13f), Palette.TEXT_DIM, sp(8.5f), bold = true)
        for (n in notes) {
            val x0 = gridL + (n.start - scrollTicks) * ppt
            if (x0 < gridL - dp(6f) || x0 > gridR) continue
            val hgt = (n.velocity / 127f) * (velocityLaneHeight - dp(8f))
            val c = if (selected.contains(n)) Palette.ACCENT else Palette.withAlpha(Palette.ACCENT, 150)
            rect(canvas, x0, h - dp(4f) - hgt, x0 + max(dp(2.5f), n.length * ppt * 0.7f), h - dp(4f), c)
        }

        // ---- 框选 ----
        if (mode == Mode.MARQUEE) {
            rect(
                canvas, min(marqueeL, marqueeR), min(marqueeT, marqueeB),
                max(marqueeL, marqueeR), max(marqueeT, marqueeB),
                Palette.withAlpha(Palette.ACCENT, 40)
            )
            outline(
                canvas, min(marqueeL, marqueeR), min(marqueeT, marqueeB),
                max(marqueeL, marqueeR), max(marqueeT, marqueeB), Palette.ACCENT, 0f, dp(1f)
            )
        }

        // ---- 播放头 ----
        val px = gridL + (playheadTick - scrollTicks) * ppt
        if (px in gridL..gridR) {
            line(canvas, px, 0f, px, h, Palette.RED, dp(1.5f))
        }

        // ---- 小节标尺 ----
        var barTick = (scrollTicks / Note.BAR).toInt() * Note.BAR
        while (true) {
            val x = gridL + (barTick - scrollTicks) * ppt
            if (x > gridR) break
            if (x >= gridL) {
                rect(canvas, x, gridB - dp(11f), x + dp(20f), gridB, Palette.withAlpha(Palette.BG, 190))
                label(canvas, "${barTick / Note.BAR + 1}", x + dp(3f), gridB - dp(2.5f), Palette.TEXT_FAINT, sp(8f))
            }
            barTick += Note.BAR
        }
    }

    private fun pixelsPerTick(): Float = dp(0.16f) * zoom * 2.2f

    private fun isBlackKey(pitch: Int): Boolean {
        return when (pitch % 12) {
            1, 3, 6, 8, 10 -> true
            else -> false
        }
    }

    private fun pitchName(p: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[p % 12] + (p / 12 - 1)
    }

    /** 屏幕坐标 -> (pitch, tick)。 */
    private fun hitTest(x: Float, y: Float): Pair<Int, Int>? {
        val gridL = keyWidth
        val h = height.toFloat() - velocityLaneHeight
        if (x < gridL || y > h) return null
        val rowH = keyHeight
        val row = (y / rowH).toInt()
        val rows = (h / rowH).toInt().coerceAtLeast(1)
        val pitch = lowestNote + (rows - 1 - row)
        val tick = scrollTicks + (x - gridL) / pixelsPerTick()
        return Pair(pitch.coerceIn(0, 127), tick.toInt().coerceAtLeast(0))
    }

    private fun noteAt(x: Float, y: Float): Pair<Note, Boolean>? {
        val pat = pattern ?: return null
        val notes = pat.notesOrNull(trackIndex) ?: return null
        val gridL = keyWidth
        val ppt = pixelsPerTick()
        val rowH = keyHeight
        val h = height.toFloat() - velocityLaneHeight
        val rows = (h / rowH).toInt().coerceAtLeast(1)
        for (n in notes.asReversed()) {
            val r = (lowestNote + rows - 1 - n.pitch)
            if (r < 0 || r >= rows) continue
            val top = r * rowH
            if (y < top || y > top + rowH) continue
            val x0 = gridL + (n.start - scrollTicks) * ppt
            val x1 = x0 + n.length * ppt
            if (x < x0 || x > x1) continue
            // 右边缘 12dp 内算改长度
            val nearRight = x > x1 - dp(12f) && (x1 - x0) > dp(10f)
            return Pair(n, nearRight)
        }
        return null
    }

    private fun snapTick(t: Int): Int {
        if (snap <= 1) return t
        return (Math.round(t.toFloat() / snap) * snap)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pat = pattern ?: return false
        val notes = pat.notes(trackIndex)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = e.x
                dragStartY = e.y
                dragLastX = e.x
                dragLastY = e.y

                // 力度区
                if (e.y > height - velocityLaneHeight) {
                    val ppt = pixelsPerTick()
                    val tick = (scrollTicks + (e.x - keyWidth) / ppt).toInt()
                    val nearest = notes.minByOrNull { abs(it.start - tick) }
                    if (nearest != null && abs(nearest.start - tick) < Note.STEP) {
                        activeNote = nearest
                        mode = Mode.VELOCITY
                        val v = (((height - e.y) / (velocityLaneHeight - dp(8f))) * 127f).toInt().coerceIn(1, 127)
                        nearest.velocity = v
                        selected.clear()
                        selected.add(nearest)
                        onNotesChanged?.invoke()
                        invalidate()
                    }
                    return true
                }

                // 键盘区试听
                if (e.x < keyWidth) {
                    val ht = hitTest(keyWidth + 1, e.y) ?: return true
                    onPreview?.invoke(ht.first, 100)
                    return true
                }

                val hitNote = noteAt(e.x, e.y)
                if (hitNote != null) {
                    val (n, nearRight) = hitNote
                    activeNote = n
                    origStart = n.start
                    origLen = n.length
                    origPitch = n.pitch
                    if (!selected.contains(n)) {
                        if (!e.isShiftPressed) selected.clear()
                        selected.add(n)
                    }
                    mode = if (nearRight) Mode.RESIZE else Mode.MOVE
                    onPreview?.invoke(n.pitch, n.velocity)
                    invalidate()
                    return true
                }

                // 空白处：画新音符
                val ht = hitTest(e.x, e.y)
                if (ht != null) {
                    val (pitch, rawTick) = ht
                    val tick = snapTick(rawTick)
                    // 落在已有音符上就只选中它
                    val existing = notes.firstOrNull { it.pitch == pitch && it.covers(tick) }
                    if (existing != null) {
                        selected.clear()
                        selected.add(existing)
                        activeNote = existing
                        origStart = existing.start
                        origLen = existing.length
                        origPitch = existing.pitch
                        mode = Mode.MOVE
                        onPreview?.invoke(existing.pitch, existing.velocity)
                        invalidate()
                        return true
                    }
                    val len = if (snap > 1) snap else Note.STEP
                    val n = Note(pitch, tick, len, 100)
                    notes.add(n)
                    notes.sortBy { it.start }
                    selected.clear()
                    selected.add(n)
                    activeNote = n
                    origStart = n.start
                    origLen = n.length
                    origPitch = n.pitch
                    mode = Mode.DRAW
                    onPreview?.invoke(pitch, 100)
                    onNotesChanged?.invoke()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && e.pointerCount >= 2) {
                    val d = dist(e)
                    // 捏合缩放：比例直接作用在 zoom 上，0.5..8 之间
                    if (lastPinchDist > 1f && d > 1f) {
                        val k = d / lastPinchDist
                        if (k.isFinite() && k > 0f) {
                            zoom = (zoom * k).coerceIn(0.5f, 8f)
                        }
                    }
                    lastPinchDist = d

                    // 平移：横向改时间滚动，纵向改音高范围
                    val dx = midX(e) - lastPanX
                    val dy = midY(e) - lastPanY
                    if (dx.isFinite()) {
                        scrollTicks = (scrollTicks - dx / pixelsPerTick()).coerceAtLeast(0f)
                    }
                    if (dy.isFinite()) {
                        val rows = (dy / keyHeight).toInt()
                        if (rows != 0) {
                            lowestNote = (lowestNote + rows).coerceIn(0, 127)
                        }
                    }
                    lastPanX = midX(e)
                    lastPanY = midY(e)
                    invalidate()
                    return true
                }

                val ppt = pixelsPerTick()
                val rowH = keyHeight
                val n = activeNote

                when (mode) {
                    Mode.VELOCITY -> {
                        n?.let {
                            val v = (((height - e.y) / (velocityLaneHeight - dp(8f))) * 127f)
                                .toInt().coerceIn(1, 127)
                            it.velocity = v
                            onNotesChanged?.invoke()
                            invalidate()
                        }
                    }
                    Mode.DRAW -> {
                        n?.let {
                            val newLen = snapTick(((e.x - keyWidth) / ppt + scrollTicks).toInt()) - it.start
                            it.length = max(snap.coerceAtLeast(Note.STEP / 2), newLen)
                            invalidate()
                        }
                    }
                    Mode.MOVE -> {
                        n?.let {
                            val dTicks = ((e.x - dragStartX) / ppt).toInt()
                            val newStart = snapTick(origStart + dTicks).coerceAtLeast(0)
                            val pitchDelta = -((e.y - dragStartY) / rowH).toInt()
                            val newPitch = (origPitch + pitchDelta).coerceIn(0, 127)
                            it.start = newStart
                            it.pitch = newPitch
                            notes.sortBy { it.start }
                            invalidate()
                        }
                    }
                    Mode.RESIZE -> {
                        n?.let {
                            val dTicks = ((e.x - dragStartX) / ppt).toInt()
                            val newLen = snapTick(origLen + dTicks)
                            it.length = max(snap.coerceAtLeast(Note.STEP / 2), newLen)
                            invalidate()
                        }
                    }
                    Mode.MARQUEE -> {
                        marqueeR = e.x
                        marqueeB = e.y
                        invalidate()
                    }
                    else -> {}
                }
                dragLastX = e.x
                dragLastY = e.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFinger = false
                if (mode != Mode.NONE && mode != Mode.MARQUEE) {
                    onNotesChanged?.invoke()
                }
                mode = Mode.NONE
                activeNote = null
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 双指：拖动平移、捏合缩放。这是编曲最常用的手势，必须有。
                if (e.pointerCount >= 2) {
                    mode = Mode.NONE
                    activeNote = null
                    twoFinger = true
                    lastPinchDist = dist(e)
                    lastPanX = midX(e)
                    lastPanY = midY(e)
                }
                return true
            }
        }
        return true
    }

    private fun dist(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.hypot(dx, dy)
    }

    private fun midX(e: MotionEvent): Float =
        if (e.pointerCount < 2) e.x else (e.getX(0) + e.getX(1)) / 2f

    private fun midY(e: MotionEvent): Float =
        if (e.pointerCount < 2) e.y else (e.getY(0) + e.getY(1)) / 2f

    private val MotionEvent.isShiftPressed: Boolean
        get() = (metaState and android.view.KeyEvent.META_SHIFT_ON) != 0

    /** 清空选中。 */
    fun clearSelection() {
        selected.clear()
        invalidate()
    }

    /** 删除所有选中音符。 */
    fun deleteSelected(): Int {
        val pat = pattern ?: return 0
        val notes = pat.notesOrNull(trackIndex) ?: return 0
        val count = notes.count { selected.contains(it) }
        notes.removeAll { selected.contains(it) }
        selected.clear()
        onNotesChanged?.invoke()
        invalidate()
        return count
    }

    /** 量化选中（或全部）音符到吸附网格。 */
    fun quantizeSelected() {
        val pat = pattern ?: return
        val notes = pat.notesOrNull(trackIndex) ?: return
        val target = if (selected.isEmpty()) notes else notes.filter { selected.contains(it) }
        target.forEach { it.start = snapTick(it.start) }
        notes.sortBy { it.start }
        onNotesChanged?.invoke()
        invalidate()
    }

    fun scrollByTicks(ticks: Int) {
        scrollTicks = (scrollTicks + ticks).coerceAtLeast(0f)
        invalidate()
    }

    fun scrollToPlayhead(tick: Int, center: Boolean = true) {
        if (!center) return
        val visible = (width - dp(34f)) / pixelsPerTick()
        if (tick < scrollTicks || tick > scrollTicks + visible * 0.85f) {
            scrollTicks = (tick - visible * 0.35f).coerceAtLeast(0f)
        }
    }

    fun scrollVertically(deltaRows: Int) {
        lowestNote = (lowestNote + deltaRows).coerceIn(0, 127)
        invalidate()
    }
}
