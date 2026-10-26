package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
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

/**
 * 自定义背景层（1.33 重构）。
 *
 * 旧实现的问题链：把图画在最底层 View 上，指望上层内容自己半透明让位。
 * 但 chrome（页签/工具栏/走带条/读数条）全是实色，内容区又叠着多层半透明底，
 * 真机上看背景几乎为 0；调了几轮 alpha 都是隔靴搔痒。
 *
 * 新架构（WallpaperView + 内容帽）：
 *
 *   root(透明)
 *   ├── WallpaperView      ← 背景图唯一绘制处，铺满整屏，不参与任何内容布局
 *   └── column
 *       ├── TabBar         ← 画"帽"：半透明底，让壁纸从页签缝隙透出来
 *       ├── content(页面)   ← 各页面自己决定给不给底（见 CanvasBackdrop）
 *       ├── TransportBar   ← 同上
 *       └── MusicReadout   ← 同上
 *
 * 关键变化：chrome 不再画实色，而是画一层"半透明帽"。
 * 这样壁纸在全屏范围内连续可见，chrome 区域也能看到它，
 * 同时帽的压暗保证文字可读。壁纸与内容彻底解耦：
 * 壁纸只管画图，内容只管自己的半透明底，两边不再互相猜。
 */
class WallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.view.View(context, attrs) {

    var backgroundImage: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            rebuildMatrix()
            invalidate()
        }

    /** 当前已加载图对应的源路径（换图检测用）。 */
    var loadedPath: String? = null

    /**
     * 当前已加载图对应的版本号。
     *
     * 路径可能不变而内容变了（文件名固定时"更换"就是覆盖同一路径），
     * 只比路径会漏掉这种情况。版本号由 AppSettings 每次导入 +1，
     * 是"这张位图是不是最新那张"的唯一可靠判据。
     */
    var loadedVersion: Int = -1

    /** 是否额外压暗（设置里的"背景压暗"）。 */
    var dim = true
        set(value) { if (field != value) { field = value; invalidate() } }

    private val imgMatrix = Matrix()
    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val gradPaint = Paint()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildMatrix()
    }

    /** cover 策略：等比放大到覆盖画布，居中裁切，不拉伸变形。 */
    private fun rebuildMatrix() {
        val bmp = backgroundImage ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = maxOf(w / bmp.width, h / bmp.height)
        imgMatrix.setScale(s, s)
        imgMatrix.postTranslate((w - bmp.width * s) * 0.5f, (h - bmp.height * s) * 0.5f)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = backgroundImage
        if (bmp == null || bmp.isRecycled) {
            // 无自定义背景：默认渐变（保留原来的光源方向）
            gradPaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0xFF12151A.toInt(), 0xFF0D0F13.toInt(), 0xFF0A0C0F.toInt()),
                floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradPaint)
            gradPaint.shader = null
            return
        }

        if (imgMatrix.isIdentity) rebuildMatrix()
        canvas.drawBitmap(bmp, imgMatrix, imgPaint)

        // 单一压暗层：这是壁纸与内容之间唯一的暗化。
        // 开 = 30%，关 = 10%。之前"图内压暗 + 层内压暗 + 内容底压暗"三层相乘
        // 把 50% 的图压到 9%，用户看到的就是全黑 —— 参数怎么调都不对，
        // 因为错的是结构。现在只有这一层。
        dimPaint.color = if (dim) 0x4D000000 else 0x1A000000
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
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
        // 半透明帽：壁纸从工具栏底下透出（1.33 引入、1.34 恢复时被冲掉，重打）
        rect(canvas, 0f, 0f, w, h, 0xD116191F.toInt())
        val compact = w < dp(500f)
        val titleWidth = if (compact) dp(84f) else dp(138f)
        label(canvas, clipText(title, titleWidth - dp(16f), sp(11.5f), true),
            dp(12f), if (compact) h * 0.63f else dp(15f), Palette.TEXT, sp(11.5f), bold = true)
        if (!compact) {
            label(canvas, clipText(subtitle, titleWidth - dp(16f), sp(8f)),
                dp(12f), h - dp(6f), Palette.TEXT_FAINT, sp(8f))
        }
        actionsLeft = titleWidth
        actionWidth = if (actions.isEmpty()) 0f else (w - titleWidth - dp(8f)) / actions.size
        actions.forEachIndexed { index, action ->
            val left = actionsLeft + index * actionWidth + dp(2f)
            val right = actionsLeft + (index + 1) * actionWidth - dp(2f)
            // 动作按钮做成"沉在背景里"的胶囊：只有按下时才浮起来。
            // 常态下不要有可见描边，否则顶栏会变成一排方块的阵列。
            val active = pressed == index
            rect(canvas, left, dp(6f), right, h - dp(6f),
                if (active) Palette.PANEL_HI else Palette.withAlpha(Palette.PANEL, 150), dp(6f))
            label(canvas, clipText(action.first, right - left - dp(6f), sp(9f)),
                (left + right) / 2f, h / 2f + sp(3.2f),
                if (active) Palette.TEXT else Palette.TEXT_DIM, sp(9f), center = true)
        }
        // 底边只留一条极暗的线：工具栏与内容的分界靠明度，不靠线。
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
