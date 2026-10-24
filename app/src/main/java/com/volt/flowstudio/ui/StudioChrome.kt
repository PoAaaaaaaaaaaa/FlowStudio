package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.TextView
import kotlin.math.abs

/** 所有页面共用的低对比背景，避免大块纯黑造成视觉断层。 */
class StudioBackground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {
    private var shader: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF171D28.toInt(), Palette.BG, 0xFF10131B.toInt()),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        b.fill.shader = shader
        b.fill.style = android.graphics.Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), b.fill)
        b.fill.shader = null
    }
}

/** 原生可访问按钮，统一圆角、触摸涟漪和最小点击高度。 */
class FlowButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextView(context, attrs) {
    var buttonColor: Int = Palette.PANEL_HI
        set(value) { field = value; updateBackground() }
    var foregroundColor: Int = Palette.TEXT
        set(value) { field = value; setTextColor(value) }

    init {
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(foregroundColor)
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isClickable = true
        isFocusable = true
        updateBackground()
    }

    fun setPrimary(primary: Boolean) {
        buttonColor = if (primary) Palette.ACCENT else Palette.PANEL_HI
        foregroundColor = if (primary) Palette.BG else Palette.TEXT
        setTypeface(typeface, if (primary) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun updateBackground() {
        val shape = GradientDrawable().apply {
            setColor(buttonColor)
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), Palette.withAlpha(Palette.LINE, 190))
        }
        background = RippleDrawable(
            ColorStateList.valueOf(Palette.withAlpha(Color.WHITE, 45)), shape, null
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** 页面标题与上下文工具。只承载操作，不复制已有编辑手势。 */
class EditorToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {
    var title: String = ""
        set(value) { field = value; invalidate() }
    var subtitle: String = ""
        set(value) { field = value; invalidate() }
    var actions: List<Pair<String, () -> Unit>> = emptyList()
        set(value) { field = value; invalidate() }
    var onSwipePage: ((Int) -> Unit)? = null

    private var pressed = -1
    private var downX = 0f
    private var downY = 0f
    private var actionWidth = 0f
    private var actionsLeft = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect(canvas, 0f, 0f, w, h, Palette.SURFACE)
        rect(canvas, dp(10f), dp(8f), dp(13f), h - dp(8f), Palette.ACCENT, dp(1.5f))
        val compact = w < dp(500f)
        val titleWidth = if (compact) dp(86f) else dp(142f)
        label(canvas, clipText(title, titleWidth - dp(20f), sp(12f), true),
            dp(22f), if (compact) h * 0.62f else dp(15f), Palette.TEXT, sp(12f), bold = true)
        if (!compact) {
            label(canvas, clipText(subtitle, titleWidth - dp(20f), sp(8f)),
                dp(22f), h - dp(6f), Palette.TEXT_FAINT, sp(8f))
        }
        actionsLeft = titleWidth
        actionWidth = if (actions.isEmpty()) 0f else (w - titleWidth - dp(6f)) / actions.size
        actions.forEachIndexed { index, action ->
            val left = actionsLeft + index * actionWidth + dp(2f)
            val right = actionsLeft + (index + 1) * actionWidth - dp(2f)
            rect(canvas, left, dp(5f), right, h - dp(5f),
                if (pressed == index) Palette.ACCENT_DIM else Palette.PANEL_HI, dp(7f))
            label(canvas, clipText(action.first, right - left - dp(6f), sp(9.5f)),
                (left + right) / 2f, h / 2f + sp(3.5f),
                if (pressed == index) Palette.TEXT else Palette.TEXT_DIM, sp(9.5f), center = true)
        }
        line(canvas, 0f, h - 0.5f, w, h - 0.5f, Palette.LINE_SOFT, dp(1f))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = if (actionWidth > 0f && event.x >= actionsLeft) {
                    ((event.x - actionsLeft) / actionWidth).toInt().coerceIn(0, actions.lastIndex)
                } else -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (pressed >= 0 && abs(dx) < dp(40f) && abs(dy) < dp(28f)) {
                    actions.getOrNull(pressed)?.second?.invoke()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                } else if (abs(dx) > dp(54f) && abs(dx) > abs(dy) * 1.5f) {
                    onSwipePage?.invoke(if (dx < 0) 1 else -1)
                }
                pressed = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
            }
        }
        return true
    }
}

/** 对话框使用统一容器，保留原生按钮的键盘与无障碍行为。 */
fun panelBackground(context: Context, radiusDp: Float = 18f): GradientDrawable =
    GradientDrawable().apply {
        setColor(Palette.PANEL)
        cornerRadius = radiusDp * context.resources.displayMetrics.density
        setStroke(1, Palette.LINE)
    }

/** 音色、预设、效果类型共用胶囊按钮样式。 */
fun styleChoiceButton(view: TextView, selected: Boolean, accent: Int = Palette.ACCENT) {
    val density = view.resources.displayMetrics.density
    val shape = GradientDrawable().apply {
        setColor(if (selected) accent else Palette.PANEL_HI)
        cornerRadius = 8f * density
        setStroke((0.7f * density).toInt().coerceAtLeast(1),
            if (selected) accent else Palette.LINE_SOFT)
    }
    view.background = RippleDrawable(
        ColorStateList.valueOf(Palette.withAlpha(android.graphics.Color.WHITE, 38)), shape, null
    )
    view.setTextColor(if (selected) Palette.BG else Palette.TEXT_DIM)
    view.minimumHeight = (38 * density).toInt()
    view.gravity = Gravity.CENTER
    view.isClickable = true
}
