package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent

/**
 * 导航面板：汉堡菜单展开后的页面选择器。
 *
 * 替代系统 AlertDialog 列表：系统列表没有图形标记、没有说明、没有选中态，
 * 点开像"设置里的调试菜单"。这里每页一行卡片：
 * 左侧竖条图形、中间标题与说明、右侧当前页对勾。
 *
 * 单独成文件，避免再往公共 UiKit 里堆组件造成依赖混乱。
 */
class NavPanelView(
    context: Context,
    private val titles: List<String>,
    private val hints: List<String>,
    private val selected: Int,
    private val pageCount: Int = titles.size,
    private val onPick: (Int) -> Unit
) : BaseView(context, attrs = null) {

    private var pressed = -1
    private var itemH = 0f
    private var topPad = 0f

    fun desiredHeight(): Int = (dp(26f) + titles.size * dp(56f) + dp(12f)).toInt()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect(canvas, 0f, 0f, w, h, Palette.PANEL)

        topPad = dp(16f)
        label(canvas, "工作区", dp(18f), topPad, Palette.TEXT_FAINT, sp(9f), bold = true)
        line(canvas, dp(18f), topPad + dp(10f), w - dp(18f), topPad + dp(10f),
            Palette.withAlpha(Palette.LINE, 130), dp(0.8f))

        itemH = dp(56f)
        val startY = topPad + dp(18f)

        titles.indices.forEach { i ->
            val top = startY + i * itemH
            // 只有页面项有"当前"状态；导入/指南是动作，不存在选中。
            val isSel = i == selected && i < pageCount
            val isAction = i >= pageCount
            if (isSel || pressed == i) {
                rect(canvas, dp(8f), top + dp(3f), w - dp(8f), top + itemH - dp(3f),
                    if (isSel) Palette.withAlpha(Palette.ACCENT, 26) else Palette.PANEL_HI, dp(9f))
            }
            if (isSel) {
                rect(canvas, dp(8f), top + dp(10f), dp(10.5f), top + itemH - dp(10f),
                    Palette.ACCENT, dp(1.5f))
            }

            // 每页一个可辨识的竖条图形，不依赖图标字体
            val cx = dp(30f)
            val cy = top + itemH / 2f
            val bars = (i % 4) + 2
            for (k in 0 until bars) {
                val ratio = if (k % 2 == 0) 0.55f else 0.95f
                val hh = dp(4f) + dp(5f) * ratio
                val x = cx - dp(9f) + k * dp(4.5f)
                rect(canvas, x, cy - hh, x + dp(2.2f), cy + hh,
                    if (isSel) Palette.ACCENT else Palette.withAlpha(Palette.TEXT_DIM, 170), dp(1f))
            }

            label(canvas, titles[i], dp(52f), top + itemH * 0.42f,
                when {
                    isSel -> Palette.ACCENT
                    isAction -> Palette.CYAN
                    else -> Palette.TEXT
                }, sp(12.5f), bold = isSel)
            label(canvas, hints.getOrElse(i) { "" }, dp(52f), top + itemH * 0.75f,
                Palette.TEXT_FAINT, sp(8.5f))
            if (isAction && i == pageCount) {
                // 动作项之间加一条细分隔，避免和页面混在一起
                line(canvas, dp(52f), top - dp(1f), w - dp(18f), top - dp(1f),
                    Palette.LINE_SOFT, dp(0.8f))
            }

            if (isSel) {
                val px = w - dp(26f)
                val py = top + itemH / 2f
                b.stroke.color = Palette.ACCENT
                b.stroke.strokeWidth = dp(1.8f)
                b.stroke.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(px - dp(6f), py, px - dp(2f), py + dp(4f), b.stroke)
                canvas.drawLine(px - dp(2f), py + dp(4f), px + dp(5f), py - dp(5f), b.stroke)
                b.stroke.strokeCap = Paint.Cap.BUTT
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = cell(e.y); invalidate(); return true }
            MotionEvent.ACTION_MOVE -> {
                if (cell(e.y) != pressed) { pressed = -1; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val idx = cell(e.y)
                if (idx >= 0 && idx == pressed) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPick(idx)
                }
                pressed = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private fun cell(y: Float): Int {
        val startY = topPad + dp(18f)
        if (itemH <= 0f) return -1
        val idx = ((y - startY) / itemH).toInt()
        return if (idx in titles.indices) idx else -1
    }
}
