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

    /**
     * 背景风格：把原来烘焙进像素的那几层搬到运行时来控制。
     *
     * 1.38 重构的核心 —— 旧实现选图时就把去饱和、上下渐变、色染、光斑
     * 全部画进位图存盘，于是：
     *   · 参数不可调（想亮一点只能重选图）
     *   · 比例被焊死成 1440×2560，横屏平板必然错位
     *   · 每次换尺寸都要重缩放，锯齿就是这么来的
     *
     * 现在这些都变成可控字段，onDraw 里按**当前实际尺寸**现算。
     * 默认值与旧视觉一致，用户看不出突变，但从此每一项都能调。
     */
    /** 去饱和程度：1=原色，0=完全灰。0.60 是旧版烘焙时用的值。 */
    var saturation = 0.60f
        set(value) { if (field != value) { field = value.coerceIn(0f, 1f); rebuildFilters(); invalidate() } }

    /** 上下渐变压暗的强度倍率：1=旧版，0=不要渐变。 */
    var vignette = 1f
        set(value) { if (field != value) { field = value.coerceIn(0f, 2f); invalidate() } }

    /** 主题色染色强度：旧版是 alpha 15，这里用 0..1 的比例表达。 */
    var tint = 0.06f
        set(value) { if (field != value) { field = value.coerceIn(0f, 1f); invalidate() } }

    /** 右上角光斑强度：旧版 alpha 0x1E。 */
    var glow = 0.12f
        set(value) { if (field != value) { field = value.coerceIn(0f, 1f); invalidate() } }

    private val imgMatrix = Matrix()
    private val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val gradPaint = Paint()
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 去饱和的 ColorMatrix 过滤器。参数变了要重建。 */
    private var satFilter: android.graphics.ColorFilter? = null

    init {
        rebuildFilters()
    }

    private fun rebuildFilters() {
        val inv = 1f - saturation
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            r + saturation, g, b, 0f, 0f,
            r, g + saturation, b, 0f, 0f,
            r, g, b + saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        satFilter = android.graphics.ColorMatrixColorFilter(cm)
        imgPaint.colorFilter = satFilter
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildMatrix()
    }

    /**
     * cover 策略：等比放大到覆盖画布，居中裁切，不拉伸变形。
     *
     * 因为现在存的是**原图**（不再预裁成固定比例），
     * 这里每次尺寸变化都会重新算一遍 —— 竖屏、横屏、分屏、折叠展开，
     * 每种比例都能得到正确的裁切，而不是把一张焊死的 9:16 图硬拉。
     */
    private fun rebuildMatrix() {
        val bmp = backgroundImage ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = maxOf(w / bmp.width, h / bmp.height)
        imgMatrix.setScale(s, s)
        imgMatrix.postTranslate((w - bmp.width * s) * 0.5f, (h - bmp.height * s) * 0.5f)
        // 强制重建：onDraw 里用 isIdentity 判断会漏掉"已经算过一次但尺寸变了"
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bmp = backgroundImage
        if (bmp == null || bmp.isRecycled) {
            // 无自定义背景：默认渐变（保留原来的光源方向）
            gradPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xFF12151A.toInt(), 0xFF0D0F13.toInt(), 0xFF0A0C0F.toInt()),
                floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, gradPaint)
            gradPaint.shader = null
            return
        }

        // cover 绘制。矩阵在 onSizeChanged 里算好，这里只在必要时补一次。
        if (imgMatrix.isIdentity) rebuildMatrix()
        canvas.drawBitmap(bmp, imgMatrix, imgPaint)

        // ---- 以下全部按当前尺寸现算，不再烘焙 ----

        // 1) 主题色染色：极淡，让背景与软件配色同源
        if (tint > 0.001f) {
            overlayPaint.colorFilter = null
            overlayPaint.shader = null
            // 不用 androidx 的 ColorUtils —— 这个项目刻意零 AndroidX 依赖，
            // 一个 alpha 合成不值得为它引入整个库。
            val alpha = (tint.coerceIn(0f, 1f) * 255f).toInt()
            overlayPaint.color = (alpha shl 24) or (Palette.ACCENT and 0x00FFFFFF)
            canvas.drawRect(0f, 0f, w, h, overlayPaint)
        }

        // 2) 右上角光斑：避免整张图死平
        if (glow > 0.001f) {
            val a = (glow.coerceIn(0f, 1f) * 255f).toInt()
            overlayPaint.shader = RadialGradient(
                w * 0.78f, h * 0.16f, maxOf(w, h) * 0.62f,
                intArrayOf((a shl 24) or 0xFFFFFF, 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            overlayPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawRect(0f, 0f, w, h, overlayPaint)
            overlayPaint.shader = null
        }

        // 3) 上下渐变压暗：保证 chrome 区（页签、走带条）文字可读。
        //    强度由 vignette 倍率控制，0 就是完全不要。
        if (vignette > 0.001f) {
            val k = vignette.coerceIn(0f, 2f)
            val topA = (0x3D * k).toInt().coerceIn(0, 255)
            val botA = (0x55 * k).toInt().coerceIn(0, 255)

            overlayPaint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.18f,
                intArrayOf(topA shl 24, 0x00000000),
                null, Shader.TileMode.CLAMP
            )
            overlayPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawRect(0f, 0f, w, h * 0.18f, overlayPaint)

            overlayPaint.shader = LinearGradient(
                0f, h * 0.76f, 0f, h,
                intArrayOf(0x00000000, botA shl 24),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, h * 0.76f, w, h, overlayPaint)
            overlayPaint.shader = null
        }

        // 4) 统一压暗：壁纸与内容之间唯一的暗化层。
        //    开 = 30%，关 = 10%。这一层必须最后画，否则会盖掉上面的渐变与光斑。
        dimPaint.color = if (dim) 0x4D000000 else 0x1A000000
        canvas.drawRect(0f, 0f, w, h, dimPaint)
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
