package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 顶部音乐摆件条。
 *
 * 上一版放的是磁带卷盘 + 唱针臂：体积小、线条细，缩在导航栏右边一小块，
 * 实际看下来"看不出是什么东西"，只是占位。这一版换成三件**大而认得出**的装置：
 *
 *   1. 声波环（左侧）
 *      一圈同心圆弧，随输出电平向外扩散，弧长随响度变长。
 *      播放时持续起涟漪 —— 三件里最有"生命感"的一个。
 *
 *   2. 均衡柱（中间，18 根）
 *      模仿频谱仪：高度 = 电平 × 该位置的理论包络 × 拍点抖动。
 *      左侧低频高、右侧高频矮但抖得快，这是绝大多数音乐的共同形状。
 *      不做真 FFT（这一层是装饰），真正在跳，但便宜。
 *
 *   3. 黑胶唱片 + 唱臂（右侧，直径吃满整条高度）
 *      俯视的黑胶：同心沟槽、中心标签、一道跟着播放旋转的高光。
 *      唱臂播放时落到盘面、停止时抬起。转速按真实播放推进，不是匀速假转。
 *
 * 三件都按导航栏整高来画，不再是"一小排图标"。仍然不接受触摸：
 * 它是装饰，任何误操作都不该由它引起。
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

    /** 是否在播放：决定唱片转不转、唱臂落不落。 */
    var playing = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** 节拍脉冲 0..1，进拍时置 1 后自行衰减。 */
    var beatPulse = 0f
        set(value) { field = value; invalidate() }

    /** 当前拍 0..3（保留给未来的拍点指示）。 */
    var beatIndex = 0

    /** 播放进度 0..1（保留：唱片转速目前按时间推进，不依赖它）。 */
    var progress = 0f

    private var lastMs = 0L
    private var discAngle = 0f
    private var armProgress = 0f

    /** 每根均衡柱的当前高度，做平滑跟随而不是直接赋值。 */
    private val barLevel = FloatArray(BAR_COUNT) { 0f }

    private var ripplePhase = 0f
    private var rippleAmp = 0f
    private var bgGrad: LinearGradient? = null

    companion object {
        private const val BAR_COUNT = 18
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bgGrad = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Palette.withAlpha(Palette.SURFACE, 0),
                Palette.withAlpha(Palette.SURFACE, 150),
                Palette.withAlpha(Palette.PANEL, 90),
            ),
            floatArrayOf(0f, 0.30f, 1f),
            Shader.TileMode.CLAMP
        )
        lastMs = 0L
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.016f else ((now - lastMs).coerceIn(0, 80) / 1000f)
        lastMs = now

        val w = width.toFloat()
        val h = height.toFloat()
        if (w < dp(40f) || h < dp(14f)) return

        // 整条底层：从左透明过渡到右侧微亮，让三件摆件像贴在一条玻璃带上
        bgGrad?.let {
            b.fill.shader = it
            b.fill.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, w, h, b.fill)
            b.fill.shader = null
        }

        val cy = h * 0.5f
        val amp = ((peakL + peakR) * 0.5f).coerceIn(0f, 1f)

        // 动画推进：只有播放时才动，停着就冻住（省 CPU）
        if (playing) {
            discAngle += dt * 118f          // 约 3 秒一圈
            if (discAngle >= 360f) discAngle -= 360f
            ripplePhase += dt * 0.85f
            if (ripplePhase >= 1f) ripplePhase -= 1f
        }
        rippleAmp += (amp - rippleAmp) * 0.16f

        // ---- 从右往左布局：唱片贴右，均衡柱居中，声波环在左 ----
        val pad = dp(6f)

        // 3) 唱片：直径吃满高度的 88%
        val discR = (h * 0.44f).coerceIn(dp(9f), dp(30f))
        val discCx = w - pad - discR
        drawVinyl(canvas, discCx, cy, discR)

        // 2) 均衡柱：占据唱片左边剩余宽度（最多条宽的 44%）
        val barsRight = discCx - discR - dp(10f)
        val barsLeft = max(dp(6f), barsRight - w * 0.44f)
        if (barsRight - barsLeft > dp(18f)) {
            drawEqBars(canvas, barsLeft, barsRight, cy, h, amp, dt)
        }

        // 1) 声波环：最左侧
        val ringR = (h * 0.40f).coerceIn(dp(8f), dp(26f))
        val ringCx = barsLeft - dp(8f) - ringR
        if (ringCx - ringR > 0f) {
            drawSoundRing(canvas, ringCx, cy, ringR, amp)
        }
    }

    // ------------------------------------------------------------------ 声波环

    private fun drawSoundRing(canvas: Canvas, cx: Float, cy: Float, baseR: Float, amp: Float) {
        // 中心实心点：进拍时更亮
        val beatBright = if (playing) (0.55f + beatPulse * 0.45f) else 0.35f
        b.fill.color = Palette.withAlpha(
            Palette.ACCENT, (200 * beatBright).toInt().coerceIn(60, 235)
        )
        b.fill.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, baseR * 0.30f, b.fill)

        // 中心外光晕
        val glow = RadialGradient(
            cx, cy, baseR * 1.08f,
            intArrayOf(
                Palette.withAlpha(Palette.ACCENT, (70 + 90 * amp).toInt().coerceIn(0, 255)),
                0x00000000,
            ),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        b.fill.shader = glow
        canvas.drawCircle(cx, cy, baseR * 1.08f, b.fill)
        b.fill.shader = null

        // 三道同心弧：半径随相位外扩，弧长随响度变长
        b.stroke.style = Paint.Style.STROKE
        b.stroke.strokeCap = Paint.Cap.ROUND
        for (k in 0 until 3) {
            val phase = (ripplePhase + k * 0.333f) % 1f
            val rr = baseR * (0.52f + phase * 0.52f + amp * 0.10f)
            val alpha = ((1f - phase) * (70 + 130 * rippleAmp)).toInt().coerceIn(0, 220)
            if (alpha <= 4) continue
            b.stroke.color = Palette.withAlpha(Palette.ACCENT, alpha)
            b.stroke.strokeWidth = (dp(1.5f) - k * dp(0.25f)).coerceAtLeast(dp(0.6f))
            val sweep = 90f + 240f * rippleAmp
            canvas.drawArc(
                cx - rr, cy - rr, cx + rr, cy + rr,
                -sweep / 2f - 90f, sweep, false, b.stroke
            )
        }
        b.stroke.strokeCap = Paint.Cap.BUTT
        b.stroke.style = Paint.Style.FILL
    }

    // ------------------------------------------------------------------ 均衡柱

    /**
     * 频谱柱。
     *
     * 目标高度 = 电平 × 理论包络 × 拍点抖动。
     * 抖动用基于拍点与位置的确定性函数，不是每帧纯随机 ——
     * 纯随机看起来是"闪烁的噪点"，不是"音乐在跳"。
     */
    private fun drawEqBars(
        canvas: Canvas, left: Float, right: Float, cy: Float, h: Float,
        amp: Float, dt: Float
    ) {
        val totalW = right - left
        val gap = dp(1.5f)
        val barW = ((totalW - gap * (BAR_COUNT - 1)) / BAR_COUNT).coerceAtLeast(dp(1f))
        val maxH = h * 0.62f
        val baseline = cy + maxH * 0.5f

        for (i in 0 until BAR_COUNT) {
            val t = i.toFloat() / (BAR_COUNT - 1)          // 0=低频 1=高频
            // 理论包络：低频高、中频略降、高频再抬一点（镲与齿音）
            val envelope = (0.95f - 0.55f * t + 0.22f * t * t * t).coerceIn(0.18f, 1f)
            val wobble = 0.72f + 0.28f * sin(
                (beatPulse * 6.28f + i * 0.9f + ripplePhase * 3.1f * (0.5f + t)).toDouble()
            ).toFloat()
            val target = (amp * envelope * wobble).coerceIn(0f, 1f)

            // 平滑跟随：上升快、下降慢（电平表的常见观感）
            val cur = barLevel[i]
            barLevel[i] = if (target > cur) cur + (target - cur) * 0.55f
                          else cur + (target - cur) * (0.10f + dt * 2f)

            val v = barLevel[i]
            if (v < 0.012f) continue

            val bh = (maxH * v).coerceAtLeast(dp(1f))
            val x = left + i * (barW + gap)
            val color = when {
                v > 0.82f -> Palette.RED
                v > 0.60f -> Palette.YELLOW
                v > 0.38f -> Palette.withAlpha(Palette.ACCENT, 235)
                else -> Palette.withAlpha(Palette.ACCENT_DIM, 220)
            }
            rect(canvas, x, baseline - bh, x + barW, baseline, color, barW * 0.34f)
        }

        // 基线：让柱子看起来长在一条线上
        line(canvas, left, baseline + dp(1f), right, baseline + dp(1f),
            Palette.withAlpha(Palette.LINE, 110), dp(0.7f))
    }

    // ------------------------------------------------------------------ 黑胶

    private fun drawVinyl(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 盘体
        b.fill.color = 0xFF0B0D11.toInt()
        b.fill.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, r, b.fill)

        // 沟槽：同心细环，越向外越关键（黑胶的真实特征）
        b.stroke.style = Paint.Style.STROKE
        b.stroke.strokeWidth = dp(0.6f)
        var rr = r * 0.42f
        var k = 0
        while (rr < r * 0.97f) {
            b.stroke.color = Palette.withAlpha(0xFFFFFF, if (k % 2 == 0) 40 else 22)
            canvas.drawCircle(cx, cy, rr, b.stroke)
            rr += r * 0.055f
            k++
        }

        // 旋转高光：一道跟着盘转的亮斑 —— "它在转"直接看得见
        if (playing) {
            val rad = Math.toRadians(discAngle.toDouble())
            val lx = cx + cos(rad).toFloat() * r * 0.70f
            val ly = cy + sin(rad).toFloat() * r * 0.70f
            val g = RadialGradient(
                lx, ly, r * 0.5f,
                intArrayOf(Palette.withAlpha(0xFFFFFF, 46), 0x00000000),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            b.fill.shader = g
            canvas.drawCircle(cx, cy, r * 0.97f, b.fill)
            b.fill.shader = null
        }

        // 中心标签
        b.fill.color = Palette.withAlpha(Palette.ACCENT_DIM, 230)
        canvas.drawCircle(cx, cy, r * 0.36f, b.fill)
        b.fill.color = Palette.withAlpha(Palette.PANEL, 240)
        canvas.drawCircle(cx, cy, r * 0.28f, b.fill)
        b.fill.color = 0xFF06080A.toInt()
        canvas.drawCircle(cx, cy, r * 0.075f, b.fill)
        b.fill.color = Palette.withAlpha(0xFFFFFF, 16)
        canvas.drawCircle(cx - r * 0.10f, cy - r * 0.11f, r * 0.20f, b.fill)

        drawTonearm(canvas, cx, cy, r)
        b.stroke.style = Paint.Style.FILL
    }

    /**
     * 唱臂：支点在唱片右上方（盘外），臂身指向盘心。
     * 角度在"停靠"与"落针"之间平滑过渡。
     */
    private fun drawTonearm(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val pivotX = cx + r * 0.92f
        val pivotY = cy - r * 0.86f

        val target = if (playing) 1f else 0f
        armProgress += (target - armProgress) * 0.10f

        val restAngle = Math.toRadians(118.0).toFloat()
        val playAngle = Math.toRadians(146.0).toFloat()
        val ang = restAngle + (playAngle - restAngle) * armProgress

        val len = r * 1.38f
        val tipX = pivotX + cos(ang) * len
        val tipY = pivotY + sin(ang) * len

        b.stroke.style = Paint.Style.STROKE
        b.stroke.strokeCap = Paint.Cap.ROUND
        b.stroke.color = Palette.withAlpha(Palette.TEXT_DIM, 200)
        b.stroke.strokeWidth = dp(2.2f)
        canvas.drawLine(pivotX, pivotY, tipX, tipY, b.stroke)

        b.fill.color = Palette.withAlpha(Palette.TEXT_DIM, 235)
        b.fill.style = Paint.Style.FILL
        canvas.drawCircle(pivotX, pivotY, dp(2.6f), b.fill)
        b.fill.color = Palette.withAlpha(Palette.PANEL, 255)
        canvas.drawCircle(pivotX, pivotY, dp(1.1f), b.fill)

        // 唱头
        b.stroke.color = if (playing) Palette.ACCENT else Palette.TEXT_FAINT
        b.stroke.strokeWidth = dp(3.4f)
        canvas.drawLine(
            tipX, tipY,
            tipX + cos(ang) * dp(3f), tipY + sin(ang) * dp(3f),
            b.stroke
        )

        b.stroke.strokeCap = Paint.Cap.BUTT
        b.stroke.style = Paint.Style.FILL
    }

    /** 静止且无信号时不请求下一帧：装饰不能和音频线程抢 CPU。 */
    fun needsAnimation(): Boolean =
        playing || rippleAmp > 0.01f || peakL > 0.004f || peakR > 0.004f ||
            abs(armProgress - (if (playing) 1f else 0f)) > 0.01f
}
