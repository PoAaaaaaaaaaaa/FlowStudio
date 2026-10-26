package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 顶部摆件条：导航栏右侧那一点空位。
 *
 * 为什么要有它：导航栏左侧是菜单 + 标题，右侧只有一个「1 / 4」页码，
 * 中间那一大片在宽屏上是纯色。这块地方不承担功能，放一排会呼吸的小装置
 * 刚好——既填满了空白，又给编辑器一点"设备在运转"的活气。
 *
 * 三个装置（都不接受触摸，纯粹是装饰，不会误触）：
 *   1. 立体声电平：两根细柱，跟着主输出峰值起伏，带峰值保持线。
 *      这是唯一"有用"的一个 —— 扫一眼就知道有没有声音。
 *   2. 唱针臂：播放时缓慢摆向唱片，停止时收回。位置对应播放状态。
 *   3. 磁带卷盘：播放时两个盘按不同半径匀速转，一圈约 3 秒。
 *
 * 全部用 Canvas 直接画，无图片资源、无动画框架、每帧只做几十次绘制调用。
 * 停止播放时不重绘（除了电平），避免白白占 CPU —— 这个 App 是实时音频软件，
 * UI 线程被装饰动画拖慢会直接变成爆音。
 */
class OrnamentBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    /** 主输出峰值 0..1，由 ticker 每帧喂进来。 */
    var peakL = 0f
        set(value) { if (abs(field - value) > 0.004f) { field = value; invalidate() } }
    var peakR = 0f
        set(value) { if (abs(field - value) > 0.004f) { field = value; invalidate() } }

    /** 是否在播放：决定唱针与卷盘动不起来。 */
    var playing = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 节拍脉冲 0..1：每次重拍由 ticker 置 1，然后自己衰减。 */
    var beatPulse = 0f
        set(value) { field = value; invalidate() }

    private val peakHoldL = FloatArray(2) { 0f }
    private val peakHoldR = FloatArray(2) { 0f }

    /** 唱针目标角度与当前角度（弧度），做平滑过渡而不是瞬间跳。 */
    private var armAngle = ARM_REST
    private var reelPhase = 0f
    private var lastFrameMs = 0L
    private var grad: LinearGradient? = null

    companion object {
        /** 唱针停在唱片外侧时的角度。 */
        private const val ARM_REST = -0.62f
        /** 播放时唱针落到唱片上的角度。 */
        private const val ARM_PLAY = -0.18f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        grad = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Palette.withAlpha(Palette.SURFACE, 0),
                Palette.SURFACE,
                Palette.SURFACE
            ),
            floatArrayOf(0f, 0.12f, 1f),
            Shader.TileMode.CLAMP
        )
        lastFrameMs = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs).coerceIn(0, 120) / 1000f)
        lastFrameMs = now

        val w = width.toFloat()
        val h = height.toFloat()
        if (w < dp(60f) || h < dp(12f)) return

        // 底：从透明过渡到 SURFACE，让摆件看起来"长"在导航栏里而不是贴上去
        b.fill.shader = grad
        b.fill.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, b.fill)
        b.fill.shader = null

        val cy = h * 0.5f
        val pad = dp(8f)

        // ------------------------------------------------------------------ 电平
        // 从右往左排：电平贴最右，靠页码；然后卷盘；然后唱针。
        val meterW = dp(30f)
        val meterRight = w - pad
        val meterLeft = meterRight - meterW
        drawMeter(canvas, meterLeft, meterRight, cy, h)

        // 卷盘：两个小圆盘
        val reelR = (h * 0.30f).coerceIn(dp(4f), dp(11f))
        val reelGap = reelR * 2.4f
        val reelRight = meterLeft - dp(14f)
        val reelL1 = reelRight - reelGap * 1.6f
        val reelL2 = reelRight - reelGap * 0.4f

        // 播放时卷盘转，速度与 reelPhase 一起推进；停止时停在当前角度不动。
        if (playing && dt > 0f) {
            reelPhase += dt * 2.1f          // 约 3 秒一圈
            if (reelPhase > 1000f) reelPhase -= 1000f
        }
        drawReel(canvas, reelL1, cy, reelR, reelPhase)
        drawReel(canvas, reelL2, cy, reelR, reelPhase)

        // 两盘之间的磁带弧线（只是示意：上凸的一条浅弧）
        if (reelR > dp(5f)) {
            b.stroke.color = Palette.withAlpha(Palette.LINE, 120)
            b.stroke.strokeWidth = dp(0.8f)
            val path = Path()
            val topY = cy - reelR * 0.55f
            path.moveTo(reelL1, topY)
            path.quadTo((reelL1 + reelL2) * 0.5f, topY - reelR * 0.45f, reelL2, topY)
            canvas.drawPath(path, b.stroke)
        }

        // ------------------------------------------------------------------ 脉冲点阵
        // 四颗小圆点代表四拍，当前拍最亮并向两侧扩散。
        // 这是 DAW 里最省地方又能一眼看出"在第几拍"的元件。
        val dotRight = reelL1 - dp(16f)
        val dotGap = dp(5.4f)
        val dotR = dp(1.6f)
        drawBeatDots(canvas, dotRight, cy - h * 0.13f, dotGap, dotR)

        // ------------------------------------------------------------------ 频谱
        // 极窄的三段频谱（低/中/高），高度跟主输出电平相关。
        // 不做真 FFT：这一层是装饰，实频谱在混音台页面里有。
        drawMiniSpectrum(canvas, dotRight - dotGap * 3.6f - dp(9f), cy, h)

        // ------------------------------------------------------------------ 唱针
        val armRight = reelL1 - dp(14f)
        val armLen = (h * 0.52f).coerceIn(dp(6f), dp(15f))
        val armLeft = armRight - armLen * 1.5f
        if (armRight > armLeft + dp(4f)) {
            // 平滑靠近目标角：播放时落到唱片上，停止时收回。
            // 用固定的插值系数而不是 dt 相关，这样掉帧时也不会跳变。
            val target = if (playing) ARM_PLAY else ARM_REST
            armAngle += (target - armAngle) * 0.12f
            drawTonearm(canvas, armLeft, armRight, cy, armLen)
        }
    }

    /**
     * 立体声电平：两条细柱，底部固定，高度跟峰值。
     * 峰值保持线会在峰值处停留几帧再落下 —— 瞬态一闪而过时这正是想要的。
     */
    private fun drawMeter(canvas: Canvas, left: Float, right: Float, cy: Float, h: Float) {
        val barW = dp(2.4f)
        val gap = dp(2.2f)
        val top = cy - h * 0.26f
        val bottom = cy + h * 0.26f
        val len = bottom - top

        // 两条柱的左边界：右侧柱贴右
        val rX = right - barW
        val lX = rX - gap - barW

        // 轨道（未点亮时的底色）
        rect(canvas, lX, top, lX + barW, bottom, Palette.withAlpha(Palette.LINE, 90), barW * 0.5f)
        rect(canvas, rX, top, rX + barW, bottom, Palette.withAlpha(Palette.LINE, 90), barW * 0.5f)

        // 实际电平
        drawMeterBar(canvas, lX, top, bottom, len, barW, peakL, 0)
        drawMeterBar(canvas, rX, top, bottom, len, barW, peakR, 1)
    }

    private fun drawMeterBar(
        canvas: Canvas, x: Float, top: Float, bottom: Float,
        len: Float, barW: Float, value: Float, side: Int
    ) {
        val v = value.coerceIn(0f, 1f)
        // 峰值保持：上升立刻跟上，下降慢慢落
        val hold = if (side == 0) peakHoldL else peakHoldR
        if (v >= hold[0]) {
            hold[0] = v
            hold[1] = 0f
        } else {
            hold[1] += 1f
            if (hold[1] > 8f) hold[0] = (hold[0] - 0.03f).coerceAtLeast(v)
        }

        if (v > 0.005f) {
            val hh = len * v
            // 颜色按电平分档：绿 → 黄 → 红。这是混音台的通用语言。
            val color = when {
                v > 0.92f -> Palette.RED
                v > 0.72f -> Palette.YELLOW
                else -> Palette.GREEN
            }
            rect(canvas, x, bottom - hh, x + barW, bottom, color, barW * 0.5f)
        }

        // 保持线
        if (hold[0] > 0.02f) {
            val hy = bottom - len * hold[0]
            val c = if (hold[0] > 0.92f) Palette.RED else Palette.withAlpha(Palette.TEXT, 170)
            rect(canvas, x, hy, x + barW, hy + dp(1f), c)
        }
    }

    /** 磁带卷盘：外圈 + 中心轴 + 三根辐条，辐条随相位转。 */
    private fun drawReel(canvas: Canvas, cx: Float, cy: Float, r: Float, phase: Float) {
        // 外圈
        b.stroke.color = Palette.withAlpha(Palette.ACCENT_DIM, 200)
        b.stroke.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, r, b.stroke)

        // 中心轴
        b.fill.color = Palette.withAlpha(Palette.ACCENT, if (playing) 210 else 120)
        b.fill.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r * 0.24f, b.fill)

        // 辐条：只有转起来才画，静止时留白显得"停机"
        if (r > dp(5f)) {
            b.stroke.color = Palette.withAlpha(Palette.ACCENT_DIM, if (playing) 170 else 70)
            b.stroke.strokeWidth = dp(0.8f)
            for (k in 0 until 3) {
                val a = phase + k * 2.0944f
                val x1 = cx + cos(a) * r * 0.28f
                val y1 = cy + sin(a) * r * 0.28f
                val x2 = cx + cos(a) * r * 0.88f
                val y2 = cy + sin(a) * r * 0.88f
                canvas.drawLine(x1, y1, x2, y2, b.stroke)
            }
        }
    }

    /** 唱针臂：一根从左侧枢轴斜向右下的杆 + 末端配重。 */
    private fun drawTonearm(canvas: Canvas, left: Float, right: Float, cy: Float, len: Float) {
        // 枢轴在左边
        val px = left
        val py = cy - len * 0.35f

        b.stroke.color = Palette.withAlpha(Palette.TEXT_DIM, if (playing) 210 else 120)
        b.stroke.strokeWidth = dp(1.4f)
        b.stroke.strokeCap = Paint.Cap.ROUND

        val ex = px + cos(armAngle) * len * 1.5f
        val ey = py + sin(armAngle) * len * 1.5f
        canvas.drawLine(px, py, ex, ey, b.stroke)

        // 枢轴圆点
        b.fill.color = Palette.withAlpha(Palette.TEXT_FAINT, 200)
        b.fill.style = Paint.Style.FILL
        canvas.drawCircle(px, py, dp(1.6f), b.fill)

        // 唱头（末端小方块）
        b.fill.color = Palette.withAlpha(if (playing) Palette.ACCENT else Palette.TEXT_FAINT, 220)
        val hs = dp(1.9f)
        canvas.drawRect(ex - hs, ey - hs, ex + hs, ey + hs, b.fill)

        b.stroke.strokeCap = Paint.Cap.BUTT
    }

    /** 四拍点阵：当前拍实心高亮，其余渐暗；播放时整体有轻微呼吸。 */
    private fun drawBeatDots(canvas: Canvas, rightX: Float, y: Float, gap: Float, r: Float) {
        val cur = (beatIndex % 4).coerceIn(0, 3)
        for (k in 0 until 4) {
            val cx = rightX - (3 - k) * gap
            val isCur = k == cur && playing
            val baseA = if (isCur) 235 else 70
            // 脉冲让当前拍那一颗略大
            val rr = if (isCur) r * (1f + beatPulse * 0.55f) else r
            b.fill.color = Palette.withAlpha(if (isCur) Palette.ACCENT else Palette.TEXT_FAINT, baseA)
            b.fill.style = Paint.Style.FILL
            canvas.drawCircle(cx, y, rr, b.fill)
        }
    }

    /** 三段迷你频谱：低频最粗、高频最细，高度随电平起伏。 */
    private fun drawMiniSpectrum(canvas: Canvas, rightX: Float, cy: Float, h: Float) {
        val barW = dp(1.7f)
        val gap = dp(1.9f)
        val maxH = h * 0.22f
        val lv = listOf(peakL * 1.05f, (peakL + peakR) * 0.44f, peakR * 0.82f)
        for (k in 0 until 3) {
            val x = rightX - (2 - k) * (barW + gap)
            val v = lv[k].coerceIn(0f, 1f)
            // 底部对齐，高度至少留 1.2dp 让"有这条"可见
            val bh = (maxH * v).coerceAtLeast(dp(1.2f))
            val col = when {
                v > 0.9f -> Palette.RED
                v > 0.68f -> Palette.YELLOW
                else -> Palette.ACCENT
            }
            rect(canvas, x, cy + dp(2f) - bh, x + barW, cy + dp(2f),
                Palette.withAlpha(col, 200), barW * 0.5f)
        }
    }

    /** 当前是第几拍（由 ticker 喂），用于四拍点阵。 */
    var beatIndex = 0

    /**
     * 只有播放中、或电平还在变的时候才持续刷新。
     * 静止时不请求下一帧 —— 装饰不该在没人看的时候烧 CPU。
     */
    fun needsAnimation(): Boolean =
        playing || beatPulse > 0.01f || peakL > 0.004f || peakR > 0.004f ||
            peakHoldL[0] > 0.02f || peakHoldR[0] > 0.02f ||
            abs(armAngle - (if (playing) ARM_PLAY else ARM_REST)) > 0.01f
}
