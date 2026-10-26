package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 设置窗口。
 *
 * 形态参考 /storage/emulated/0/ytbl 那套液态玻璃（ImGui + GLES3）的视觉语言：
 *   - 大圆角卡片 + 半透明表面 + 边缘高光 + 柔和外阴影
 *   - 入场/退场用真实弹簧物理（SpringFloat），不是插值动画
 *   - 开关、滑块都带按下缩放与回弹
 *
 * 但渲染路径换成了 Canvas：那个库整套建立在 ImGui 的 draw list 上，
 * 而这里的每个控件都是原生 View。所以移植的是"手感与配色"，不是它的代码。
 *
 * 弹簧积分直接照抄 ytbl_spring.h 的解析解（含欠阻尼/临界/过阻尼三个分支），
 * 这样手感能做到与参考一致，且逐帧稳定不受掉帧影响。
 */

/** 两色线性插值（ARGB），用于开关轨道的开合过渡。 */
private fun lerpColor(from: Int, to: Int, t: Float): Int {
    val tt = t.coerceIn(0f, 1f)
    val a = (android.graphics.Color.alpha(from) + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * tt).toInt()
    val r = (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * tt).toInt()
    val g = (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * tt).toInt()
    val bl = (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * tt).toInt()
    return android.graphics.Color.argb(a, r, g, bl)
}

// ------------------------------------------------------------------ 弹簧

/**
 * 单自由度弹簧的解析解。
 *
 * 不用 "value += (target - value) * k" 那种近似：它在大 dt（掉帧）时会过冲甚至发散。
 * 这里用解析解：给定初始位移与速度，任意 dt 后都能算出精确位置。
 * 公式与 ytbl_spring.h 完全一致（阻尼比三分支）。
 */
class Spring(private var value: Float, private val damping: Float, private val stiffness: Float) {

    var target: Float = value
        private set
    private var velocity = 0f

    fun snapTo(v: Float) { value = v; target = v; velocity = 0f }
    fun animateTo(v: Float) { target = v }

    val current: Float get() = value

    /** 推进 dt 秒。返回 true 表示仍在动。 */
    fun step(dtIn: Float): Boolean {
        var dt = dtIn
        if (dt <= 0f) return false
        if (dt > 0.064f) dt = 0.064f

        val w0 = sqrt(max(1e-6f, stiffness)).toDouble()
        val zeta = damping.toDouble()
        val y0 = (value - target).toDouble()
        val v0 = velocity.toDouble()
        var y: Double
        var v: Double

        if (zeta < 1.0 - 1e-4) {
            val wd = w0 * sqrt(1.0 - zeta * zeta)
            val env = exp(-zeta * w0 * dt)
            val c = kotlin.math.cos(wd * dt)
            val s = sin(wd * dt)
            val b = (v0 + zeta * w0 * y0) / wd
            val inner = y0 * c + b * s
            y = env * inner
            v = env * (-zeta * w0 * inner + (-y0 * wd * s + b * wd * c))
        } else if (zeta > 1.0 + 1e-4) {
            val s = sqrt(zeta * zeta - 1.0)
            val r1 = -w0 * (zeta - s)
            val r2 = -w0 * (zeta + s)
            val c1 = (v0 - r2 * y0) / (r1 - r2)
            val c2 = y0 - c1
            val e1 = exp(r1 * dt)
            val e2 = exp(r2 * dt)
            y = c1 * e1 + c2 * e2
            v = r1 * c1 * e1 + r2 * c2 * e2
        } else {
            val env = exp(-w0 * dt)
            val c = v0 + w0 * y0
            y = env * (y0 + c * dt)
            v = env * (v0 - w0 * c * dt)
        }

        value = (target + y).toFloat()
        velocity = v.toFloat()

        if (!value.isFinite() || !velocity.isFinite()) {
            value = if (target.isFinite()) target else 0f
            velocity = 0f
        }
        // 收尾：足够接近就吸附，避免无限逼近导致每帧都重绘
        if (abs(value - target) <= 0.0008f && abs(velocity) <= 0.05f) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}

// ------------------------------------------------------------------ 玻璃卡片背景

/**
 * 玻璃表面。
 *
 * 纯 Canvas 做不出真正的背景折射（那需要采样身后的像素），
 * 但把这几层叠起来，观感已经很接近：
 *   1. 外层柔和阴影（模拟景深，让卡片浮起来）
 *   2. 半透明填充 + 顶部到底部的微弱渐变（模拟表面受光）
 *   3. 内描边高光：左上偏白、右下偏暗（模拟玻璃边缘的折射亮带）
 *   4. 顶部一道横向高光带（模拟参考库里 angle=45° 的高光）
 */
class GlassSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var cornerRadius = 0f
    var surfaceAlpha = 214
    var shadowAlpha = 90
    /** 强调色：让设置窗口带一点主题色，不是纯灰玻璃。 */
    var tint = Palette.ACCENT

    private val path = Path()
    private val inner = RectF()
    private var grad: LinearGradient? = null
    private var sheen: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        grad = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Palette.withAlpha(Palette.PANEL_HI, surfaceAlpha),
                Palette.withAlpha(Palette.PANEL, surfaceAlpha),
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        // 顶部高光：从左上往右下淡出，模拟 45° 光源
        sheen = LinearGradient(
            0f, 0f, w.toFloat() * 0.9f, h.toFloat() * 0.5f,
            intArrayOf(
                Palette.withAlpha(0xFFFFFF, 26),
                Palette.withAlpha(0xFFFFFF, 8),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        buildPath(w.toFloat(), h.toFloat())
    }

    private fun buildPath(w: Float, h: Float) {
        path.reset()
        val r = cornerRadius
        path.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (path.isEmpty) buildPath(w, h)

        // 1) 外阴影：分三层高斯近似，比单层模糊更像真实投影
        b.fill.style = Paint.Style.FILL
        for (i in 3 downTo 1) {
            val spread = dp(3f + i * 4f)
            val a = (shadowAlpha / (i * 3)).coerceAtLeast(4)
            b.fill.color = Palette.withAlpha(0x000000, a)
            val rr = cornerRadius + spread * 0.55f
            canvas.drawRoundRect(
                -spread * 0.2f, dp(3f) - spread * 0.35f,
                w + spread * 0.2f, h + spread * 0.75f,
                rr, rr, b.fill
            )
        }

        // 2) 表面
        b.fill.shader = grad
        canvas.drawPath(path, b.fill)
        b.fill.shader = null

        // 3) 顶部斜向高光
        sheen?.let {
            b.fill.shader = it
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(0f, 0f, w, h * 0.55f, b.fill)
            canvas.restore()
            b.fill.shader = null
        }

        // 4) 边缘高光：描两道，上亮下暗
        b.stroke.strokeWidth = dp(1f)
        b.stroke.color = Palette.withAlpha(0xFFFFFF, 34)
        val inset = dp(0.5f)
        inner.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(inner, cornerRadius, cornerRadius, b.stroke)

        // 5) 强调色极淡的底光：让卡片与主题色有联系
        b.stroke.color = Palette.withAlpha(tint, 22)
        b.stroke.strokeWidth = dp(1.2f)
        inner.set(dp(1f), dp(1f), w - dp(1f), h - dp(1f))
        canvas.drawRoundRect(inner, cornerRadius - dp(1f), cornerRadius - dp(1f), b.stroke)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

// ------------------------------------------------------------------ 开关

/**
 * 玻璃开关。轨道 + 滑动圆钮，带按下放大与回弹弹簧。
 * 状态变化通过 onToggle 回调抛出，不做自身状态兜底 —— 单一数据源在调用方。
 */
class GlassToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var checked = false
    var onToggle: ((Boolean) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private val knobSpring = Spring(0f, 0.72f, 260f)
    private val pressSpring = Spring(1f, 1f, 900f)
    private var pressed = false
    private var lastMs = 0L

    init {
        isClickable = true
        isFocusable = true
    }

    /**
     * 设置状态。
     *
     * 注意：**即使 checked 已经是同一个值，也要把弹簧位置对齐**。
     * 旧实现在值相同时直接 return，而 buildRow 是"先手写 checked 再调 setChecked"，
     * 于是弹簧永远停在初始值 0（关闭位置）—— 圆钮画在左边，但 checked 是 true。
     * 表现就是"打开没反应、下次打开又显示没打开"。
     */
    fun setChecked(v: Boolean, animate: Boolean = true) {
        val wasSame = checked == v
        checked = v
        val target = if (v) 1f else 0f
        // 值没变但弹簧位置不对（首次绑定），也要对齐
        if (animate && !wasSame) {
            knobSpring.animateTo(target)
        } else if (!animate || wasSame) {
            knobSpring.snapTo(target)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(46f).toInt(), widthMeasureSpec),
            resolveSize(dp(26f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.016f else ((now - lastMs).coerceIn(0, 64) / 1000f)
        lastMs = now

        val stillKnob = knobSpring.step(dt)
        val stillPress = pressSpring.step(dt)

        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val t = knobSpring.current.coerceIn(0f, 1f)

        // 轨道颜色从"关"的暗灰过渡到"开"的强调色
        val trackColor = lerpColor(Palette.PANEL_HI, Palette.ACCENT, t)
        rect(canvas, 0f, 0f, w, h, trackColor, r)

        // 轨道内描边，给一点点立体感
        outline(canvas, 0f, 0f, w, h, Palette.withAlpha(0xFFFFFF, (18 + 26 * t).toInt()), r, dp(1f))

        // 圆钮：按下时略放大
        val scale = pressSpring.current
        val kr = (r - dp(3f)) * scale
        val kx = r + (w - r * 2f) * t
        val ky = h / 2f

        // 钮的投影
        b.fill.color = Palette.withAlpha(0x000000, 60)
        canvas.drawCircle(kx, ky + dp(1f), kr + dp(0.6f), b.fill)
        // 钮本体
        b.fill.color = if (t > 0.5f) 0xF2FFFFFF.toInt() else Palette.TEXT
        canvas.drawCircle(kx, ky, kr, b.fill)
        // 钮的高光点
        b.fill.color = Palette.withAlpha(0xFFFFFF, 90)
        canvas.drawCircle(kx - kr * 0.28f, ky - kr * 0.3f, kr * 0.34f, b.fill)

        if (stillKnob || stillPress) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                pressSpring.animateTo(0.88f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressSpring.animateTo(1f)
                if (pressed) {
                    pressed = false
                    setChecked(!checked)
                    onToggle?.invoke(checked)
                    onChanged?.invoke()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressSpring.animateTo(1f)
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

// ------------------------------------------------------------------ 滑块

/**
 * 玻璃滑块。拖动时值随手指，松手后用弹簧收到最近的刻度（可不吸附）。
 */
class GlassSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var min = 0f
    var max = 1f
    var value = 0.5f
        private set
    var onValueChange: ((Float) -> Unit)? = null
    /** 吸附刻度；0 表示不吸附。 */
    var snapStep = 0f
    var unit = ""

    private val posSpring = Spring(0.5f, 0.85f, 320f)
    private var dragging = false
    private var lastMs = 0L

    init {
        isClickable = true
    }

    fun setValue(v: Float, animate: Boolean = false) {
        val nv = v.coerceIn(min, max)
        value = nv
        val t = if (max > min) (nv - min) / (max - min) else 0f
        if (animate) posSpring.animateTo(t) else posSpring.snapTo(t)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(160f).toInt(), widthMeasureSpec),
            resolveSize(dp(34f).toInt(), heightMeasureSpec)
        )
    }

    private fun valueAtX(x: Float): Float {
        val trackL = dp(2f)
        val trackR = width - dp(2f)
        val t = ((x - trackL) / max(1f, trackR - trackL)).coerceIn(0f, 1f)
        var v = min + (max - min) * t
        if (snapStep > 0f) {
            v = Math.round(v / snapStep) * snapStep
        }
        return v.coerceIn(min, max)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.016f else ((now - lastMs).coerceIn(0, 64) / 1000f)
        lastMs = now
        val moving = posSpring.step(dt)

        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val trackH = dp(4f)
        val trackL = dp(2f)
        val trackR = w - dp(2f)
        val trackW = trackR - trackL

        val t = posSpring.current.coerceIn(0f, 1f)

        // 轨道底
        rect(canvas, trackL, cy - trackH / 2f, trackR, cy + trackH / 2f,
            Palette.withAlpha(Palette.PANEL_HI, 235), trackH / 2f)
        // 已选段
        if (t > 0.001f) {
            rect(canvas, trackL, cy - trackH / 2f, trackL + trackW * t, cy + trackH / 2f,
                Palette.ACCENT, trackH / 2f)
        }

        // 圆钮
        val kx = trackL + trackW * t
        val kr = dp(if (dragging) 9f else 7.5f)
        b.fill.color = Palette.withAlpha(0x000000, 70)
        canvas.drawCircle(kx, cy + dp(1f), kr + dp(0.4f), b.fill)
        b.fill.color = 0xF4FFFFFF.toInt()
        canvas.drawCircle(kx, cy, kr, b.fill)
        b.fill.color = Palette.withAlpha(Palette.ACCENT, 200)
        canvas.drawCircle(kx, cy, kr * 0.42f, b.fill)

        // 数值
        val label = formatValue(value)
        labelRight(canvas, label, w - dp(2f), cy - dp(9f), Palette.TEXT_FAINT, sp(9f))

        if (moving) postInvalidateOnAnimation()
    }

    private fun formatValue(v: Float): String =
        if (snapStep >= 1f) "${v.toInt()}$unit" else String.format("%.2f%s", v, unit)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                val v = valueAtX(e.x)
                value = v
                posSpring.snapTo(if (max > min) (v - min) / (max - min) else 0f)
                onValueChange?.invoke(v)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val v = valueAtX(e.x)
                if (v != value) {
                    value = v
                    posSpring.snapTo(if (max > min) (v - min) / (max - min) else 0f)
                    onValueChange?.invoke(v)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}

// ------------------------------------------------------------------ 设置窗口

/** 设置项的类型。 */
enum class SettingKind { NAVIGATE, TOGGLE, VALUE, SECTION }

data class SettingRow(
    val title: String,
    val subtitle: String = "",
    val kind: SettingKind = SettingKind.NAVIGATE,
    val icon: String = "",
    /** TOGGLE 的初值 / VALUE 的当前值文本 */
    var boolValue: Boolean = false,
    var valueText: String = "",
    val onToggle: ((Boolean) -> Unit)? = null,
    val onValueText: (() -> String)? = null,
    val onClick: (() -> Unit)? = null,
)

/**
 * 设置面板：可分组的行列表，滚动。
 *
 * 为什么做成"小窗口"而不是全屏：DAW 里调设置时通常还要看着工程 ——
 * 全屏设置会把编排完全遮住。窗口占屏 88% 宽、最高 82% 高，四周留出工程可见。
 */
class SettingsPanel(
    context: Context,
    private val groups: List<Pair<String, List<SettingRow>>>,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private val rows = ArrayList<Pair<SettingRow, View>>()
    private val scroll: ScrollView

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()
    private fun fdp(v: Float): Float = v * density

    init {
        setBackgroundColor(Color.TRANSPARENT)

        val card = FrameLayout(context)
        val glass = GlassSurface(context).apply {
            cornerRadius = fdp(26f)
            surfaceAlpha = 226
            shadowAlpha = 130
        }
        card.addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- 标题条 ----
        val header = FrameLayout(context)
        col.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(58f)))

        val title = android.widget.TextView(context).apply {
            text = "设置"
            setTextColor(Palette.TEXT)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(22f), dp(18f), 0, 0)
        }
        header.addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val sub = android.widget.TextView(context).apply {
            text = "轻点空白处关闭"
            setTextColor(Palette.TEXT_FAINT)
            textSize = 9.5f
            setPadding(dp(22f), dp(42f), 0, 0)
        }
        header.addView(sub, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // 关闭按钮
        val close = FlowButton(context).apply {
            text = "✕"
            textSize = 14f
            buttonColor = Palette.withAlpha(Palette.PANEL_HI, 200)
            foregroundColor = Palette.TEXT_DIM
            setOnClickListener { onClose() }
        }
        header.addView(close, LayoutParams(dp(38f), dp(38f)).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            rightMargin = dp(18f)
        })

        // 分隔线
        val lineView = View(context).apply { setBackgroundColor(Palette.withAlpha(Palette.LINE, 120)) }
        col.addView(lineView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(0.7f)).apply {
            leftMargin = dp(20f); rightMargin = dp(20f)
        })

        // ---- 滚动区 ----
        scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            clipToPadding = false
        }
        col.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), dp(6f), dp(10f), dp(18f))
        }
        scroll.addView(body, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        for ((groupName, list) in groups) {
            body.addView(sectionHeader(groupName))
            for (row in list) {
                val v = buildRow(row)
                rows.add(row to v)
                body.addView(v)
            }
        }

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.alpha = 0f
        card.translationY = dp(14f).toFloat()
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(190L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }

    /** 外部改了某个设置后，让面板上的显示同步。 */
    fun refresh() {
        for ((row, v) in rows) {
            when (row.kind) {
                SettingKind.TOGGLE -> (v as? GlassToggle)?.setChecked(row.boolValue)
                SettingKind.VALUE -> row.onValueText?.let { f ->
                    (v as? android.widget.TextView)?.text = f()
                }
                else -> {}
            }
        }
    }

    private fun sectionHeader(name: String): View {
        val tv = android.widget.TextView(context).apply {
            text = name
            setTextColor(Palette.ACCENT)
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(dp(14f), dp(16f), 0, dp(6f))
        }
        return tv
    }

    private fun buildRow(row: SettingRow): View {
        return when (row.kind) {
            SettingKind.TOGGLE -> {
                val box = rowShell(row)
                val t = GlassToggle(context).apply {
                    // 不要先手写 checked：那会让 setChecked 认为"值没变"而跳过弹簧对齐
                    setChecked(row.boolValue, false)
                    onToggle = { v ->
                        row.boolValue = v
                        row.onToggle?.invoke(v)
                    }
                }
                box.addView(t, LinearLayout.LayoutParams(dp(46f), dp(26f)).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                })
                box
            }

            SettingKind.VALUE -> {
                val box = rowShell(row)
                val tv = android.widget.TextView(context).apply {
                    text = row.onValueText?.invoke() ?: row.valueText
                    setTextColor(Palette.ACCENT)
                    textSize = 12.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                box.addView(tv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL })
                if (row.onClick != null) {
                    box.isClickable = true
                    box.setOnClickListener { row.onClick.invoke(); tv.text = row.onValueText?.invoke() ?: tv.text }
                }
                box
            }

            else -> {
                val box = rowShell(row)
                val arrow = android.widget.TextView(context).apply {
                    text = "›"
                    setTextColor(Palette.TEXT_FAINT)
                    textSize = 20f
                }
                box.addView(arrow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL })
                box.isClickable = true
                box.setOnClickListener { row.onClick?.invoke() }
                box
            }
        }
    }

    /**
     * 一行的通用外壳：左侧文字块（标题 + 副标题），右侧留给控件。
     *
     * 文字块用 weight=1 占满剩余宽度，所以调用方后续 addView 的控件
     * 自然落在右侧。标题过长时省略而不是挤掉右侧控件。
     */
    private fun rowShell(row: SettingRow): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            background = rowBackground()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5f) }
        }

        val textBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val title = android.widget.TextView(context).apply {
            text = row.title
            setTextColor(Palette.TEXT)
            textSize = 13.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textBox.addView(title)

        if (row.subtitle.isNotEmpty()) {
            val sub = android.widget.TextView(context).apply {
                text = row.subtitle
                setTextColor(Palette.TEXT_FAINT)
                textSize = 10f
                maxLines = 2
                setPadding(0, dp(2f).toInt(), 0, 0)
            }
            textBox.addView(sub)
        }

        wrapper.addView(textBox, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply { marginEnd = dp(10f) })

        return wrapper
    }

    private fun rowBackground() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = fdp(14f)
        setColor(Palette.withAlpha(Palette.CARD, 150))
        setStroke(dp(0.7f).toInt(), Palette.withAlpha(Palette.LINE, 90))
    }
}

/**
 * 设置窗口控制器：把 SettingRow 组装成窗口，处理出入场。
 *
 * 独立成一个类而不是塞进 MainActivity：设置项会持续增长，
 * 挂在 Activity 里很快就会变成一团。这里只依赖 Context 与回调。
 */
/**
 * 设置的"实际应用"回调。
 *
 * 为什么要有它：1.31 的设置面板只把值写进 SharedPreferences，
 * 没有任何一处把它读回去生效 —— 于是"打开开关"什么都不会发生。
 * 每个开关必须在改变后立刻调用对应的 apply 动作，
 * 并**重新读回真实状态**来刷新界面（而不是相信刚才点的那个值）。
 */
interface SettingsApplier {
    fun applyDimBackground(value: Boolean)
    fun applyOrnaments(value: Boolean)
    fun applyMetronome(value: Boolean)
    fun applyKeepAwake(value: Boolean)
    /** 重新读回某项的真实状态，用于把界面同步成实际生效的样子。 */
    fun actualDimBackground(): Boolean
    fun actualOrnaments(): Boolean
    fun actualMetronome(): Boolean
    fun actualKeepAwake(): Boolean
}

class SettingsWindow(
    private val context: Context,
    private val settings: com.volt.flowstudio.session.AppSettings,
    private val applier: SettingsApplier,
    private val onPickBackground: () -> Unit,
    private val onShowDiagnostics: () -> Unit,
    private val onShowGuide: () -> Unit,
    private val onShowDj: () -> Unit,
    private val onExport: () -> Unit,
    private val onNotify: (String) -> Unit,
) {

    private var dialog: android.app.Dialog? = null
    private var panel: SettingsPanel? = null

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        panel = null
    }

    fun show() {
        if (dialog?.isShowing == true) return

        val groups = listOf(
            "外观" to listOf(
                SettingRow(
                    title = "自定义背景图",
                    subtitle = if (settings.backgroundPath != null) "已设置 · 轻点更换或清除" else "选一张图，自动套用本软件的暗色调",
                    kind = SettingKind.VALUE,
                    valueText = if (settings.backgroundPath != null) "更换" else "选择",
                    onClick = onPickBackground,
                ),
                SettingRow(
                    title = "背景压暗",
                    subtitle = "让工程内容始终清晰可读",
                    kind = SettingKind.TOGGLE,
                    boolValue = applier.actualDimBackground(),
                    onToggle = { v ->
                        settings.dimBackground = v
                        applier.applyDimBackground(v)
                        onNotify("背景压暗 ${if (applier.actualDimBackground()) "开" else "关"}")
                    },
                ),
                SettingRow(
                    title = "顶部摆件",
                    subtitle = "电平、卷盘与唱针动画",
                    kind = SettingKind.TOGGLE,
                    boolValue = applier.actualOrnaments(),
                    onToggle = { v ->
                        settings.showOrnaments = v
                        applier.applyOrnaments(v)
                    },
                ),
            ),
            "播放" to listOf(
                SettingRow(
                    title = "节拍器",
                    kind = SettingKind.TOGGLE,
                    boolValue = applier.actualMetronome(),
                    onToggle = { v ->
                        settings.metronome = v
                        applier.applyMetronome(v)
                    },
                ),
                SettingRow(
                    title = "播放时保持屏幕常亮",
                    kind = SettingKind.TOGGLE,
                    boolValue = applier.actualKeepAwake(),
                    onToggle = { v ->
                        settings.keepAwake = v
                        applier.applyKeepAwake(v)
                    },
                ),
            ),
            "工程" to listOf(
                SettingRow(
                    title = "导出音频",
                    subtitle = "把当前编排渲染成 WAV",
                    onClick = onExport,
                ),
                SettingRow(
                    title = "操作指南",
                    subtitle = "手势、工作区与常用操作",
                    onClick = onShowGuide,
                ),
                SettingRow(
                    title = "DJ 与混音知识",
                    subtitle = "过渡、搓碟与频段分工",
                    onClick = onShowDj,
                ),
            ),
            "诊断" to listOf(
                SettingRow(
                    title = "诊断信息",
                    subtitle = "音频引擎状态与崩溃日志，只在排查问题时需要",
                    onClick = onShowDiagnostics,
                ),
            ),
        )

        val p = SettingsPanel(context, groups) { dismiss() }
        panel = p

        val d = android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        d.setContentView(p)
        d.setCanceledOnTouchOutside(true)
        d.setCancelable(true)
        d.setOnDismissListener { dialog = null; panel = null }
        d.show()

        d.window?.let { win ->
            win.setBackgroundDrawableResource(android.R.color.transparent)
            val dm = context.resources.displayMetrics
            win.setLayout(
                minOf((dm.widthPixels * 0.88f).toInt(), dp(560f)),
                (dm.heightPixels * 0.82f).toInt()
            )
            win.setGravity(android.view.Gravity.CENTER)
            win.attributes = win.attributes.apply {
                dimAmount = 0.5f
                windowAnimations = 0
            }
        }
        dialog = d
    }

    private fun dp(v: Float): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** dp 的浮点版本，供需要 Float 的 API 使用。 */
    private fun fdp(v: Float): Float = v * context.resources.displayMetrics.density
}
