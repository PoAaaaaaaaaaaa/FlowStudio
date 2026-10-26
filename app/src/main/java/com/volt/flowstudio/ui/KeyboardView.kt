package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.Instrument
import kotlin.math.max
import kotlin.math.min

/**
 * 虚拟键盘：屏幕上弹奏。支持多点、滑奏、力度由纵向位置决定。
 * 这是"手机上做音乐"最直接的输入方式。
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var lowestNote: Int = 48
        set(v) { field = v.coerceIn(0, 120); invalidate() }

    /** 显示多少个半音。手机横屏下 24 个（两个八度）刚好。 */
    var noteCount: Int = 24
        set(v) { field = v.coerceIn(12, 48); invalidate() }

    var isDrum: Boolean = false
        set(v) { field = v; invalidate() }

    var onNoteOn: ((pitch: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((pitch: Int) -> Unit)? = null
    var onOctaveChange: ((Int) -> Unit)? = null

    /** 正在按下的音高 -> 手指 id */
    private val active = mutableMapOf<Int, Int>()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(CanvasBackdrop.fillColor(Palette.BG))

        val whiteKeys = (0 until noteCount).count { !isBlack(it + lowestNote) }
        if (whiteKeys == 0) return

        val whiteW = w / whiteKeys
        var whiteIndex = 0

        // 先画白键
        for (i in 0 until noteCount) {
            val pitch = lowestNote + i
            if (isBlack(pitch)) continue
            val x = whiteIndex * whiteW
            val pressed = active.containsKey(pitch)
            b.fill.color = if (pressed) Palette.ACCENT else 0xFFE6E8EE.toInt()
            b.fill.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(x + 1f, 0f, x + whiteW - 1f, h, b.fill)
            rect(canvas, x + 1f, h - dp(3f), x + whiteW - 1f, h, if (pressed) Palette.ACCENT_DIM else 0xFFB9BDC9.toInt())

            if (whiteW > dp(22f) && pitch % 12 == 0) {
                label(canvas, "C${pitch / 12 - 1}", x + whiteW / 2, h - dp(8f), 0xFF6B7183.toInt(), sp(8f), center = true)
            }
            whiteIndex++
        }

        // 再画黑键（压在白键上层）
        for (i in 0 until noteCount) {
            val pitch = lowestNote + i
            if (!isBlack(pitch)) continue
            // 黑键位置：它前面那个白键的右侧
            val whitesBefore = (0 until i).count { !isBlack(lowestNote + it) }
            val x = whitesBefore * whiteW - whiteW * 0.30f
            val kw = whiteW * 0.60f
            val kh = h * 0.62f
            val pressed = active.containsKey(pitch)
            b.fill.color = if (pressed) Palette.ACCENT else 0xFF14161C.toInt()
            canvas.drawRoundRect(x, 0f, x + kw, kh, dp(2f), dp(2f), b.fill)
            outline(canvas, x, 0f, x + kw, kh, if (pressed) Palette.ACCENT else 0xFF2A2E3A.toInt(), dp(2f), dp(1f))
        }

        // 鼓组时换成打击垫外观
        if (isDrum) {
            canvas.drawColor(CanvasBackdrop.fillColor(Palette.BG))
            val cols = 4
            val rows = 4
            val cw = w / cols
            val ch = h / rows
            val names = arrayOf("Kick", "Rim", "Snare", "Clap", "Tom", "Hat", "OpenHat", "Tom", "Tom", "Cym", "Cym", "Tom", "", "", "", "")
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    val pitch = 36 + idx
                    val pressed = active.containsKey(pitch)
                    val color = Palette.trackColor(idx)
                    rect(
                        canvas, c * cw + dp(3f), r * ch + dp(3f), (c + 1) * cw - dp(3f), (r + 1) * ch - dp(3f),
                        if (pressed) color else Palette.withAlpha(color, 55), dp(4f)
                    )
                    outline(
                        canvas, c * cw + dp(3f), r * ch + dp(3f), (c + 1) * cw - dp(3f), (r + 1) * ch - dp(3f),
                        Palette.withAlpha(color, 200), dp(4f), dp(1f)
                    )
                    val nm = names.getOrElse(idx) { "" }
                    if (nm.isNotEmpty()) {
                        label(canvas, nm, c * cw + cw / 2, r * ch + ch / 2 + dp(3f),
                            if (pressed) Palette.BG else Palette.withAlpha(color, 230), sp(9f), bold = true, center = true)
                    }
                }
            }
        }
    }

    private fun isBlack(pitch: Int): Boolean = when (pitch % 12) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }

    /** 屏幕坐标 -> 音高。 */
    private fun pitchAt(x: Float, y: Float): Int? {
        val w = width.toFloat()
        val h = height.toFloat()

        if (isDrum) {
            val cols = 4
            val rows = 4
            val c = (x / (w / cols)).toInt().coerceIn(0, cols - 1)
            val r = (y / (h / rows)).toInt().coerceIn(0, rows - 1)
            return 36 + r * cols + c
        }

        val whiteKeys = (0 until noteCount).count { !isBlack(it + lowestNote) }
        if (whiteKeys == 0) return null
        val whiteW = w / whiteKeys

        // 先检查黑键
        if (y < h * 0.62f) {
            for (i in 0 until noteCount) {
                val pitch = lowestNote + i
                if (!isBlack(pitch)) continue
                val whitesBefore = (0 until i).count { !isBlack(lowestNote + it) }
                val bx = whitesBefore * whiteW - whiteW * 0.30f
                if (x >= bx && x <= bx + whiteW * 0.60f) return pitch
            }
        }
        // 白键
        for (i in 0 until noteCount) {
            val pitch = lowestNote + i
            if (isBlack(pitch)) continue
            val whitesBefore = (0 until i).count { !isBlack(lowestNote + it) }
            val wx = whitesBefore * whiteW
            if (x >= wx && x <= wx + whiteW) return pitch
        }
        return null
    }

    private fun velocityAt(y: Float): Int {
        // 靠下弹得更用力，模仿真实键盘的手感
        val h = height.toFloat()
        if (h <= 0) return 100
        val k = (y / h).coerceIn(0f, 1f)
        return (55 + k * 72).toInt().coerceIn(1, 127)
    }

    private var lastPitch: Int? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val p = pitchAt(e.getX(idx), e.getY(idx))
                if (p != null) {
                    val v = velocityAt(e.getY(idx))
                    if (!active.containsKey(p)) {
                        active[p] = e.getPointerId(idx)
                        onNoteOn?.invoke(p, v)
                        lastPitch = p
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDrum) return true
                // 滑奏：手指移到下一个键就换音
                for (i in 0 until e.pointerCount) {
                    val p = pitchAt(e.getX(i), e.getY(i)) ?: continue
                    val already = active.containsKey(p)
                    if (!already) {
                        // 松开这个手指原本按的键
                        val pid = e.getPointerId(i)
                        val old = active.entries.firstOrNull { it.value == pid }?.key
                        if (old != null) {
                            active.remove(old)
                            onNoteOff?.invoke(old)
                        }
                        active[p] = pid
                        onNoteOn?.invoke(p, velocityAt(e.getY(i)))
                        lastPitch = p
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                val pid = e.getPointerId(idx)
                val entry = active.entries.firstOrNull { it.value == pid }
                if (entry != null) {
                    active.remove(entry.key)
                    onNoteOff?.invoke(entry.key)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                for (k in active.keys.toList()) onNoteOff?.invoke(k)
                active.clear()
                invalidate()
                return true
            }
        }
        return true
    }

    fun releaseAll() {
        for (k in active.keys.toList()) onNoteOff?.invoke(k)
        active.clear()
        postInvalidate()
    }
}
