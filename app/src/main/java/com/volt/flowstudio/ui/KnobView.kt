package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.EffectType
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 旋钮组件。竖向拖动改值，双击复位，长按进入精细模式。
 * 自绘是因为 Android 原生没有任何像样的旋钮控件。
 */
class KnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var label: String = ""
        set(v) { field = v; invalidate() }

    var value: Float = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var displayValue: String? = null
        set(v) { field = v; invalidate() }

    var accent: Int = Palette.ACCENT
        set(v) { field = v; invalidate() }

    var onValueChange: ((Float) -> Unit)? = null
    var onValueCommitted: ((Float) -> Unit)? = null

    private var lastY = 0f
    private var lastX = 0f
    private var dragging = false
    private var fine = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dp(52f).toInt(), widthMeasureSpec)
        val h = resolveSize(dp(62f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val labelH = sp(11f)
        val radius = kotlin.math.min(w * 0.38f, (h - labelH - dp(6f)) * 0.46f)
        val cy = labelH + dp(3f) + radius

        // 外圈轨道
        val startAngle = 135f
        val sweepTotal = 270f
        b.rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        b.stroke.color = Palette.LINE
        b.stroke.strokeWidth = dp(3f)
        b.stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(b.rect, startAngle, sweepTotal, false, b.stroke)

        // 值弧
        b.rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        b.stroke.color = accent
        b.stroke.strokeWidth = dp(3f)
        canvas.drawArc(b.rect, startAngle, sweepTotal * value, false, b.stroke)
        b.stroke.strokeCap = Paint.Cap.BUTT

        // 中心圆
        b.fill.color = Palette.PANEL_HI
        b.fill.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius * 0.66f, b.fill)

        // 指针
        val angle = Math.toRadians((startAngle + sweepTotal * value).toDouble())
        val inner = radius * 0.30f
        val outer = radius * 0.62f
        line(
            canvas,
            cx + (cos(angle) * inner).toFloat(),
            cy + (sin(angle) * inner).toFloat(),
            cx + (cos(angle) * outer).toFloat(),
            cy + (sin(angle) * outer).toFloat(),
            accent,
            dp(2.2f)
        )

        // 标签
        label(canvas, label, cx, labelH, Palette.TEXT_DIM, sp(9.5f), center = true)
        displayValue?.let {
            label(canvas, it, cx, cy + radius + dp(1f) + sp(9f), Palette.TEXT, sp(9.5f), bold = true, center = true)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = e.y
                lastX = e.x
                dragging = true
                fine = false
                parent?.requestDisallowInterceptTouchEvent(true)
                if (e.pointerCount >= 2) fine = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val sensitivity = if (fine) 0.0018f else 0.006f
                val dy = lastY - e.y
                val dx = e.x - lastX
                value = clamp01(value + (dy + dx) * sensitivity)
                lastY = e.y
                lastX = e.x
                onValueChange?.invoke(value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onValueCommitted?.invoke(value)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** 外部改变时用这个，不触发回调。 */
    fun setSilently(v: Float) {
        value = v.coerceIn(0f, 1f)
    }
}

/** 横向推子，混音台用。 */
class FaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var value: Float = 0.8f
        set(v) { field = v.coerceIn(0f, 1.5f); invalidate() }

    var accent: Int = Palette.ACCENT
        set(v) { field = v; invalidate() }

    var onValueChange: ((Float) -> Unit)? = null
    var onValueCommitted: ((Float) -> Unit)? = null

    private var dragging = false
    private var lastY = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val padding = dp(8f)
        val trackTop = padding
        val trackBottom = h - padding
        val trackH = trackBottom - trackTop
        val trackW = dp(4f)

        // 轨道
        rect(canvas, cx - trackW / 2, trackTop, cx + trackW / 2, trackBottom, Palette.LINE, trackW / 2)

        // 已填充部分
        val norm = (value / 1.5f).coerceIn(0f, 1f)
        val knobY = trackBottom - trackH * norm
        rect(canvas, cx - trackW / 2, knobY, cx + trackW / 2, trackBottom, accent, trackW / 2)

        // 拖柄
        val knobH = dp(14f)
        val knobW = w * 0.72f
        b.rect.set(cx - knobW / 2, knobY - knobH / 2, cx + knobW / 2, knobY + knobH / 2)
        b.fill.color = Palette.PANEL_HI
        b.fill.style = Paint.Style.FILL
        canvas.drawRoundRect(b.rect, dp(3f), dp(3f), b.fill)
        outline(canvas, b.rect.left, b.rect.top, b.rect.right, b.rect.bottom, accent, dp(3f), dp(1.4f))
        line(canvas, cx - knobW / 2 + dp(3f), knobY, cx + knobW / 2 - dp(3f), knobY, accent, dp(1.4f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromY(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                // 相对拖动：位移换算成值的变化，比绝对定位好控
                val delta = (lastY - e.y) / (height.toFloat() - dp(16f)) * 1.5f
                value = (value + delta).coerceIn(0f, 1.5f)
                lastY = e.y
                onValueChange?.invoke(value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onValueCommitted?.invoke(value)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun updateFromY(y: Float) {
        val padding = dp(8f)
        val trackH = height.toFloat() - padding * 2
        if (trackH <= 0f) return
        val norm = ((height - padding - y) / trackH).coerceIn(0f, 1f)
        value = norm * 1.5f
        onValueChange?.invoke(value)
    }
}
