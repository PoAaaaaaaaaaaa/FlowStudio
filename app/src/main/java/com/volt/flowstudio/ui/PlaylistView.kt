package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import com.volt.flowstudio.model.AudioAsset
import com.volt.flowstudio.model.Clip
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Project
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 播放列表（时间线）。
 *
 * 两条轨道语义并存，但外观统一 —— 这是 FL 播放列表的核心观感：
 *   - 音符片段：内容来自样式，画成能量包络；
 *   - 音频片段：内容来自素材，画成真实波形，标题就是文件名。
 *
 * 左侧窄条是轨道头：轨道序号、名称、以及一条垂直电平表。
 * 顶部是标尺，点一下定位、拖一下划循环。
 */
class PlaylistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    var project: Project? = null
        set(v) { field = v; invalidate() }

    var playheadTick: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var selectedTrack: Int = 0
        set(v) { field = v; invalidate() }

    /**
     * 缩放系数。1 = 默认（每屏约 12 小节）。
     * 双指捏合、双击、以及工具栏的 ± 都改它 —— 三条路径共用一套边界。
     */
    var zoom: Float = 1f
        set(v) {
            val nv = v.coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (field == nv) return
            field = nv
            scanCache.clear()   // 列数全变，旧包络直接作废
            invalidate()
        }

    /** 缩放动画目标与锚点（动画由 stepZoom 每帧推进）。 */
    private var zoomTarget = 1f
    private var zoomAnchorTick = 0
    private var zoomAnimating = false

    /** 每小节像素宽；给了确定值就优先用它（工具栏放大缩小走这条）。 */
    var pixelsPerBar: Float = 0f

    /** 素材 id → 波形峰值。由 MainActivity 在刷新时绑定。 */
    var audioPeaks: Map<Int, FloatArray> = emptyMap()

    /** 各轨道实时电平 0..1，驱动左侧电平表。 */
    var levels: FloatArray = FloatArray(0)
        set(v) { field = v; invalidate() }

    var loopOn: Boolean = false
    var loopStart: Int = 0
    var loopEnd: Int = Note.BAR * 8

    var onClipsChanged: (() -> Unit)? = null
    var onSeek: ((Int) -> Unit)? = null
    var onSelectTrack: ((Int) -> Unit)? = null
    var onClipTapped: ((Clip) -> Unit)? = null
    var onAudioTap: ((Clip) -> Unit)? = null
    var onLoopChanged: ((Boolean, Int, Int) -> Unit)? = null
    var onEmptyTap: ((Int, Int) -> Unit)? = null   // 轨道, tick —— 空态导入入口

    private var headerWidth = 0f
    private var rulerHeight = 0f
    private var rowHeight = 0f
    private var scrollX = 0f
    private var scrollY = 0f
    private var contentH = 0f

    private enum class Drag { NONE, MOVE, RESIZE_R, RESIZE_L, SEEK, LOOP, PAN }
    private var drag = Drag.NONE
    private var activeClip: Clip? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var origStart = 0
    private var origLen = 0
    private var origOffset = 0
    private var panX = 0f
    private var panY = 0f
    private var loopAnchor = 0

    // ---- 手势状态 ----
    private var pinchActive = false
    private var pinchStartDist = 0f
    private var pinchStartZoom = 1f
    private var pinchAnchorTick = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false          // 是否已越过拖动阈值
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /**
     * 音符片段的包络缓存。
     *
     * 用 (片段 id, 像素列数, 起点, 长度) 组成复合 key —— 以前是
     * `clip.id * 31 xor cols`，这个表达式会把 id 和列数混在一起：
     * 片段 1 宽 97 列和片段 2 宽 64 列会算出同一个 key（126），
     * 于是拿到的数组是别的长度，循环写越界直接崩。
     *
     * 光换 key 还不够：片段被拖动后列数不变但内容该重算，
     * 所以起点和长度也进 key。数组本身再带长度断言兜底 ——
     * onDraw 里崩一次就是闪退，不能赌 key 一定唯一。
     */
    private data class EnvelopeKey(
        val clipId: Int, val cols: Int, val start: Int, val length: Int, val offset: Int
    )
    companion object {
        /** 缩放边界。再小就看不清片段边界，再大就一分钟要翻十屏。 */
        const val MIN_ZOOM = 0.15f
        const val MAX_ZOOM = 40f

        /**
         * 拖动判定阈值（dp）。
         *
         * 之前按 12dp 算"这是拖动不是点击"，手指稍抖就越过阈值，
         * 于是想点一下片段却把它拖走了 —— 这就是误触的来源。
         * 抬到 18dp：比常见的指尖抖动幅度大一点，又不至于让真拖动变迟钝。
         */
        const val DRAG_SLOP_DP = 18f

        /** 拖右缘改长度时，命中把手的热区宽度。放宽到 22dp 更好点中。 */
        const val HANDLE_DP = 22f
    }

    /** 单击片段后先记下，双击判定结束时才决定要不要打开。 */
    private var pendingOpenClip: com.volt.flowstudio.model.Clip? = null

    /** 播放头贴片（玩偶骑剑）。null 时回退到经典红线+三角。 */
    var playheadSprite: com.volt.flowstudio.ui.PlayheadSprite? = null
    /** 播放中贴片的辉光脉冲 0..1，由外部 ticker 喂 */
    var playheadGlow = 0f
        set(value) { field = value }

    private val scanCache = HashMap<EnvelopeKey, FloatArray>()
    private var scanCacheBarWidth = -1f

    // ---------------------------------------------------------------- 几何

    private fun barWidth(): Float {
        if (pixelsPerBar > 0f) return pixelsPerBar * zoom
        val p = project ?: return dp(120f)
        val bars = p.lengthBars().coerceAtLeast(8)
        val usable = width - headerWidth
        // 一屏大约 12 小节是默认密度；zoom 直接乘上去。
        return max(dp(24f), usable / min(bars, 12).toFloat()) * zoom
    }

    private fun tickToX(t: Int): Float = headerWidth + (t * barWidth() / Note.BAR) - scrollX
    private fun xToTick(x: Float): Int = (((x - headerWidth + scrollX) / barWidth()) * Note.BAR).toInt()

    private fun rowTop(track: Int): Float = rulerHeight + track * rowHeight - scrollY

    private fun snapTick(t: Int): Int =
        Math.round(t.toFloat() / Note.STEP) * Note.STEP

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 缩放动画：每帧推进一次再画，否则画面会"啪"地跳到新密度。
        val stillZooming = if (zoomAnimating) stepZoom() else false

        canvas.drawColor(CanvasBackdrop.fillColor(Palette.BG))

        val p = project ?: return
        headerWidth = dp(if (w < dp(520f)) 54f else 68f)
        rulerHeight = dp(24f)
        val trackCount = p.tracks.size.coerceAtLeast(1)
        rowHeight = ((h - rulerHeight) / min(trackCount, 6).coerceAtLeast(1))
            .coerceIn(dp(34f), dp(72f))
        contentH = rulerHeight + trackCount * rowHeight
        scrollY = scrollY.coerceIn(0f, max(0f, contentH - h))

        val bw = barWidth()

        drawRows(canvas, p, w, h)
        drawGrid(canvas, p, w, h, bw)
        drawRuler(canvas, w, bw)
        drawClips(canvas, p, w, h)
        drawPlayhead(canvas, w, h)

        // 缩放动画还没停：立刻要求下一帧，否则走到新密度会一顿一顿
        if (stillZooming) postInvalidateOnAnimation()
    }

    /** 轨道底纹与轨道头。 */
    private fun drawRows(canvas: Canvas, p: Project, w: Float, h: Float) {
        val trackCount = p.tracks.size.coerceAtLeast(1)
        for (i in 0 until trackCount) {
            val top = rowTop(i)
            if (top > h || top + rowHeight < rulerHeight) continue
            val bottom = top + rowHeight

            // 选中轨稍微提亮；其余靠极细的分隔线分层，不做斑马纹。
            if (i == selectedTrack) {
                rect(canvas, 0f, top, w, bottom, Palette.withAlpha(Palette.ACCENT, 10))
            }
            rect(canvas, 0f, top, headerWidth, bottom, Palette.SURFACE)

            drawTrackHead(canvas, p, i, top, bottom)
            line(canvas, 0f, bottom, w, bottom, Palette.LINE_SOFT, dp(1f))
            line(canvas, headerWidth, top, headerWidth, bottom, Palette.LINE, dp(1f))
        }
    }

    /** 轨道头：序号、名称、垂直电平表。 */
    private fun drawTrackHead(canvas: Canvas, p: Project, i: Int, top: Float, bottom: Float) {
        val hh = bottom - top
        val color = Palette.trackColor(i)
        val selected = i == selectedTrack

        // 左侧强调条：选中时是实色，未选中是暗色。
        rect(canvas, 0f, top + dp(3f), dp(2.5f), bottom - dp(3f),
            if (selected) color else Palette.withAlpha(color, 70), dp(1f))

        val midY = top + hh / 2f
        // 序号贴底，名称居中；行高够时才画序号，避免小行挤成一团。
        if (hh >= dp(46f)) {
            label(canvas, "${i + 1}", dp(8f), top + dp(13f),
                Palette.TEXT_FAINT, sp(8f), bold = true)
            label(canvas,
                clipText(p.trackAt(i)?.name ?: "轨 ${i + 1}", headerWidth - dp(24f), sp(9.5f), selected),
                dp(8f), midY + dp(6f),
                if (selected) Palette.TEXT else Palette.withAlpha(color, 220), sp(9.5f), bold = selected)
        } else {
            label(canvas,
                clipText(p.trackAt(i)?.name ?: "轨 ${i + 1}", headerWidth - dp(24f), sp(9f), selected),
                dp(8f), midY + dp(4f),
                if (selected) Palette.TEXT else Palette.withAlpha(color, 220), sp(9f), bold = selected)
        }

        // 垂直电平表：贴着右缘，一段一段亮起来，像硬件推子旁的 LED 柱。
        val meterX = headerWidth - dp(9f)
        val meterTop = top + dp(8f)
        val meterBottom = bottom - dp(8f)
        val meterH = meterBottom - meterTop
        if (meterH > dp(10f)) {
            val lv = levels.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val segs = max(2, (meterH / dp(6f)).toInt())
            for (s in 0 until segs) {
                val segBottom = meterBottom - s * (meterH / segs)
                val segTop = segBottom - (meterH / segs) * 0.7f
                val lit = (s.toFloat() / segs) < lv
                val c = when {
                    !lit -> Palette.withAlpha(color, 34)
                    s >= segs - 2 -> Palette.RED
                    s >= segs - 4 -> Palette.ACCENT
                    else -> Palette.withAlpha(color, 230)
                }
                rect(canvas, meterX, segTop, meterX + dp(3f), segBottom, c, dp(0.8f))
            }
        }
    }

    /** 拍与小节网格。小节线实、拍线虚。 */
    private fun drawGrid(canvas: Canvas, p: Project, w: Float, h: Float, bw: Float) {
        val firstBar = (scrollX / bw).toInt()
        val visible = ((w - headerWidth + scrollX) / bw).toInt() + 2
        for (b in firstBar..firstBar + visible) {
            val x = headerWidth + b * bw - scrollX
            if (x < headerWidth - 1 || x > w) continue
            line(canvas, x, rulerHeight, x, h, Palette.withAlpha(Palette.LINE, 130), dp(1f))
            // 拍线只在放大后画，否则小节很窄时会糊成一片灰。
            val beats = p.beatsPerBar.coerceIn(1, 16)
            if (bw / beats >= dp(9f)) {
                for (q in 1 until beats) {
                    val qx = x + q * bw / beats
                    if (qx in headerWidth..w) {
                        line(canvas, qx, rulerHeight, qx, h,
                            Palette.withAlpha(Palette.LINE_SOFT, 90), dp(0.8f))
                    }
                }
            }
        }
    }

    /** 顶部标尺：数字跳过密排，只有能放下时才标。 */
    private fun drawRuler(canvas: Canvas, w: Float, bw: Float) {
        rect(canvas, 0f, 0f, w, rulerHeight, Palette.PANEL)
        rect(canvas, 0f, 0f, headerWidth, rulerHeight, Palette.SURFACE)
        line(canvas, 0f, rulerHeight, w, rulerHeight, Palette.LINE, dp(1f))

        val firstBar = (scrollX / bw).toInt()
        val visible = ((w - headerWidth + scrollX) / bw).toInt() + 2
        // 小节太密时隔几个标一个，保证数字不重叠。
        val stride = max(1, kotlin.math.ceil((dp(26f) / bw).toDouble()).toInt())
        for (b in firstBar..firstBar + visible) {
            val x = headerWidth + b * bw - scrollX
            if (x < headerWidth - 1 || x > w) continue
            val showNumber = (b % stride) == 0
            val tickH = if (showNumber) dp(7f) else dp(4f)
            line(canvas, x, rulerHeight - tickH, x, rulerHeight,
                if (showNumber) Palette.TEXT_DIM else Palette.LINE, dp(1f))
            if (showNumber) {
                label(canvas, "${b + 1}", x + dp(3f), rulerHeight - dp(9f),
                    Palette.TEXT_DIM, sp(8.5f), bold = true)
            }
        }
        if (loopOn) {
            val lx = tickToX(loopStart).coerceIn(headerWidth, w)
            val rx = tickToX(loopEnd).coerceIn(headerWidth, w)
            if (rx > lx) {
                rect(canvas, lx, 0f, rx, rulerHeight, Palette.withAlpha(Palette.ACCENT, 46))
                line(canvas, lx, 0f, lx, rulerHeight, Palette.withAlpha(Palette.ACCENT, 170), dp(1.5f))
                line(canvas, rx, 0f, rx, rulerHeight, Palette.withAlpha(Palette.ACCENT, 170), dp(1.5f))
            }
        }
    }

    /** 所有片段。音频与音符共用外框与把手，内容各自画。 */
    private fun drawClips(canvas: Canvas, p: Project, w: Float, h: Float) {
        val bw = barWidth()
        // 缩放后列数全变，缓存整批作废。用手势结束和缩放变化两处一起清。
        if (bw != scanCacheBarWidth) {
            scanCache.clear()
            scanCacheBarWidth = bw
        }

        for (clip in p.clips) {
            val top = rowTop(clip.track)
            if (top > h || top + rowHeight < rulerHeight) continue
            val x0 = tickToX(clip.start)
            val x1 = tickToX(clip.end)
            if (x1 < headerWidth || x0 > w) continue

            val l = max(x0, headerWidth)
            val r = min(x1, w)
            if (r - l < dp(1f)) continue
            val t = top + dp(3f)
            val bt = top + rowHeight - dp(3f)
            val color = if (clip.isAudio) Palette.AUDIO else Palette.trackColor(clip.track)

            // 主体：低透明度填充 + 1px 描边，像 FL 那种"卡片块"。
            rect(canvas, l, t, r, bt, Palette.withAlpha(color, if (clip.isAudio) 56 else 48), dp(4f))
            outline(canvas, l, t, r, bt, Palette.withAlpha(color, 190), dp(4f), dp(1.1f))

            // 顶部标题条：只有够宽才画，否则整块被标题吃掉。
            val titleH = dp(11f)
            val bodyTop = t + titleH + dp(1f)
            if (r - l > dp(30f) && bt - t > dp(20f)) {
                rect(canvas, l, t, r, t + titleH, Palette.withAlpha(color, 205), dp(4f))
                val title = if (clip.isAudio) {
                    p.audioAssetById(clip.audioId)?.name ?: "音频"
                } else {
                    clip.label.ifBlank { p.patternById(clip.patternId)?.name ?: "样式" }
                }
                label(canvas, clipText(title, r - l - dp(8f), sp(8f), true),
                    l + dp(4f), t + dp(8.5f), Palette.BG, sp(8f), bold = true)
            } else {
                // 太窄就只在顶部一窄条里点个色，还能看出位置。
                rect(canvas, l, t, r, t + dp(3f), Palette.withAlpha(color, 205), dp(1.5f))
            }

            if (bt - bodyTop > dp(4f)) {
                if (clip.isAudio) {
                    drawAudioWave(canvas, clip, l, r, bodyTop, bt)
                } else {
                    drawNoteEnvelope(canvas, p, clip, l, r, bodyTop, bt, color)
                }
            }

            // 右缘把手：拖它改长度。
            if (r - l > dp(18f)) {
                rect(canvas, r - dp(3f), t + dp(2f), r - dp(0.5f), bt - dp(2f),
                    Palette.withAlpha(color, 150), dp(1f))
            }
        }
    }

    /** 音频片段：真实波形。峰值已经在导入时算好并缓存。 */
    private fun drawAudioWave(
        canvas: Canvas, clip: Clip, l: Float, r: Float, top: Float, bottom: Float
    ) {
        val asset = project?.audioAssetById(clip.audioId) ?: return
        val peaks = audioPeaks[clip.audioId]
        if (peaks == null || peaks.isEmpty()) {
            // 素材还没解码完：先画一条中线，别留一块空白。
            val mid = (top + bottom) / 2f
            line(canvas, l + dp(2f), mid, r - dp(2f), mid, Palette.withAlpha(Palette.AUDIO, 90), dp(1f))
            return
        }

        val mid = (top + bottom) / 2f
        val half = (bottom - top) / 2f * 0.9f
        if (half <= 0.5f) return

        // 片段可能只截取了素材的一段：offset/length 是 tick，换算成峰值下标。
        val bpm = project?.bpm?.coerceAtLeast(1f) ?: 120f
        val totalTicks = asset.lengthTicks(bpm).coerceAtLeast(1)
        val fromFrac = (clip.offset.toFloat() / totalTicks).coerceIn(0f, 1f)
        val toFrac = ((clip.offset + clip.length).toFloat() / totalTicks).coerceIn(0f, 1f)
        val span = (toFrac - fromFrac).coerceAtLeast(0.001f)

        // 波形必须横跨整个片段的可见宽度。
        //
        // 旧实现把列数钳在 900 并把 x 写成 l + c —— 于是片段宽于 900px 时
        // 波形只画到左边 900 像素就没内容了，缩放放大后看起来"波形没跟上"。
        // 真正的原因是绘制范围被截断，不是峰值数据不够。
        val pxWidth = (r - l).coerceAtLeast(1f)
        // 每根柱子 1.4px 宽（含 0.4px 缝），放大到很近时也不过密
        val step = 1.4f
        val cols = (pxWidth / step).toInt().coerceIn(1, 4000)
        b.fill.style = Paint.Style.FILL
        b.fill.color = Palette.withAlpha(Palette.AUDIO, 235)

        // 片段的左裁剪（offset）也要跟着走，否则缩放后波形与内容错位
        for (c in 0 until cols) {
            val t = c.toFloat() / cols
            val frac = fromFrac + span * t
            val idx = (frac * (peaks.size - 1)).toInt().coerceIn(0, peaks.size - 1)
            val amp = peaks[idx].coerceIn(0f, 1f) * half
            // 极小的振幅也要画：否则安静段落会变成一条空白，
            // 看起来像"这段没内容"。给一个 0.5px 的下限。
            val a = if (amp < 0.5f) 0.5f else amp
            val x = l + pxWidth * t
            canvas.drawRect(x, mid - a, x + step - 0.4f, mid + a, b.fill)
        }
        // 中线压在波形上面：标准 DAW 的观感
        line(canvas, l + dp(2f), mid, maxOf(l + dp(2f), r - dp(2f)), mid,
            Palette.withAlpha(Palette.BG, 110), dp(0.8f))
    }

    /**
     * 音符片段：把样式里的音符画成能量包络。
     *
     * 每列统计落在该时间窗内的音符，按"力度 × 长度贡献"累计，
     * 归一后画成上下对称柱 —— 鼓 loop 是一串脉冲，铺底是一条宽带子，
     * 稀疏旋律是几个孤立峰。跟 FL 的观感一致。
     */
    private fun drawNoteEnvelope(
        canvas: Canvas, p: Project, clip: Clip,
        l: Float, r: Float, top: Float, bottom: Float, color: Int
    ) {
        val w = r - l
        val cols = w.toInt().coerceAtMost(700)
        if (cols <= 0) return
        val pat = p.patternById(clip.patternId) ?: return
        val notes = pat.notesOrNull(clip.track)
        if (notes == null || notes.isEmpty()) return

        val key = EnvelopeKey(clip.id, cols, clip.start, clip.length, clip.offset)
        val energy = scanCache.getOrPut(key) {
            val e = FloatArray(cols)
            val span = (clip.end - clip.start).coerceAtLeast(1)
            for (n in notes) {
                val absStart = clip.start + n.start - clip.offset
                val absEnd = absStart + n.length
                if (absEnd <= clip.start || absStart >= clip.end) continue
                val c0 = ((max(absStart, clip.start) - clip.start).toFloat() / span * cols)
                    .toInt().coerceIn(0, cols - 1)
                val c1 = ((min(absEnd, clip.end) - clip.start).toFloat() / span * cols)
                    .toInt().coerceIn(0, cols - 1)
                val amp = (n.velocity / 127f) *
                    (0.62f + 0.38f * kotlin.math.sqrt(n.length / Note.PPQ.toFloat()))
                for (c in c0..c1) {
                    if (c < 0 || c >= cols) continue   // e 的长度就是 cols
                    val pos = if (c1 > c0) (c - c0).toFloat() / (c1 - c0) else 0f
                    val v = amp * (1f - 0.4f * pos)
                    if (v > e[c]) e[c] = v
                }
            }
            e
        }

        // 兜底：即便 key 撞了或条目过期，也绝不让绘制越界。
        // 少一列包络只是看起来薄一点，崩掉是直接闪退。
        val drawCols = energy.size.coerceAtMost(cols)
        if (drawCols <= 0) return

        var maxE = 0f
        for (i in 0 until drawCols) if (energy[i] > maxE) maxE = energy[i]
        if (maxE < 0.001f) return

        val mid = (top + bottom) / 2f
        val half = (bottom - top) / 2f * 0.92f
        b.fill.style = Paint.Style.FILL
        b.fill.color = Palette.withAlpha(color, 210)
        for (c in 0 until drawCols) {
            val e = energy[c] / maxE
            if (e < 0.02f) continue
            val amp = half * e
            val x = l + c
            canvas.drawRect(x, mid - amp, x + 1.1f, mid + amp, b.fill)
        }
        line(canvas, l + dp(2f), mid, r - dp(2f), mid,
            Palette.withAlpha(Palette.BG, 80), dp(0.8f))
    }

    private fun drawPlayhead(canvas: Canvas, w: Float, h: Float) {
        val px = tickToX(playheadTick)
        if (px < headerWidth || px > w) return

        // 用户设了玩偶贴片：玩偶骑剑当播放头，剑身细线指示精确时间点
        val sp = playheadSprite
        if (sp?.bitmap != null) {
            sp.draw(canvas, px, rulerHeight.toFloat(), h.toFloat(), playheadGlow, ::dp)
            return
        }

        // 默认：红线 + 顶部小三角
        line(canvas, px, 0f, px, h, Palette.RED, dp(1.4f))
        b.fill.color = Palette.RED
        b.fill.style = Paint.Style.FILL
        val p = android.graphics.Path()
        p.moveTo(px - dp(4f), 0f)
        p.lineTo(px + dp(4f), 0f)
        p.lineTo(px, dp(6f))
        p.close()
        canvas.drawPath(p, b.fill)
    }

    // ---------------------------------------------------------------- 交互

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = e.x
                dragStartY = e.y
                downX = e.x
                downY = e.y
                moved = false
                panX = e.x
                panY = e.y

                // 双击缩放。放在所有命中判定之前：
                // 双击是一个独立意图，不该先被"选中片段"截走。
                val now = System.currentTimeMillis()
                val isDoubleTap = now - lastTapTime < 300L &&
                    abs(e.x - lastTapX) < dp(40f) && abs(e.y - lastTapY) < dp(40f)
                lastTapTime = now
                lastTapX = e.x
                lastTapY = e.y

                if (isDoubleTap && e.y > rulerHeight) {
                    // 双击空白 = 缩放视图；双击片段 = 打开它。
                    //
                    // 之前单指点一下片段就立刻弹编辑面板，手指稍微偏一点、
                    // 或者只想选中看看，都会把面板怼到脸上 —— 用户报的"误触"就是这个。
                    // 现在单击只选中并定位，真正的"打开"必须双击。
                    val rowD = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                    val tickD = xToTick(e.x)
                    val hitClip = if (rowD in p.tracks.indices) {
                        p.clips.lastOrNull {
                            it.track == rowD && tickD >= it.start && tickD < it.end
                        }
                    } else null

                    if (hitClip != null) {
                        activeClip = hitClip
                        onSelectTrack?.invoke(rowD)
                        if (hitClip.isAudio) onAudioTap?.invoke(hitClip)
                        else onClipTapped?.invoke(hitClip)
                    } else {
                        // 双击空白：以该位置为中心缩放
                        zoomAround(e.x, if (zoom < 3f) zoom * 2f else 1f)
                    }
                    drag = Drag.NONE
                    pendingOpenClip = null
                    return true
                }

                // 标尺
                if (e.y < rulerHeight) {
                    val tick = xToTick(e.x).coerceAtLeast(0)
                    loopAnchor = tick
                    val nearStart = abs(tickToX(loopStart) - e.x) < dp(14f)
                    val nearEnd = abs(tickToX(loopEnd) - e.x) < dp(14f)
                    if (loopOn && (nearStart || nearEnd)) {
                        drag = Drag.LOOP
                        if (nearStart) loopStart = tick else loopEnd = tick
                    } else {
                        drag = Drag.LOOP
                        loopStart = tick
                        loopEnd = tick
                    }
                    invalidate()
                    return true
                }

                // 轨道头
                if (e.x < headerWidth) {
                    val row = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                    if (row in p.tracks.indices) {
                        onSelectTrack?.invoke(row)
                        invalidate()
                    }
                    return true
                }

                val row = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                if (row !in p.tracks.indices) return true
                val tick = xToTick(e.x)
                val clip = p.clips.lastOrNull {
                    it.track == row && tick >= it.start && tick < it.end
                }

                if (clip != null) {
                    activeClip = clip
                    origStart = clip.start
                    origLen = clip.length
                    origOffset = clip.offset
                    val nearRight = tickToX(clip.end) - e.x < dp(16f)
                    val nearLeft = e.x - tickToX(clip.start) < dp(16f) && !nearRight
                    drag = when {
                        nearRight -> Drag.RESIZE_R
                        // 左缘拖动是"裁头"：起点和素材偏移一起走，内容才不会跳。
                        nearLeft && clip.isAudio -> Drag.RESIZE_L
                        else -> Drag.MOVE
                    }
                    onSelectTrack?.invoke(row)
                    // 只记住"可能是要打开的目标"，等双击判定或抬手后再决定。
                    // 拖动改长度/移动不该弹面板，所以这里不立即回调。
                    pendingOpenClip = clip
                    invalidate()
                } else {
                    drag = Drag.SEEK
                    onSeek?.invoke(tick.coerceAtLeast(0))
                    onSelectTrack?.invoke(row)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 两指落下就进入"平移 + 缩放"模式，取消一切编辑手势。
                // 这一条是防误触的主力：以前两指操作时如果先按到了片段，
                // 片段会跟着手指跑，用户以为自己在缩放，结果把音符拖乱了。
                drag = Drag.PAN
                activeClip = null
                moved = false
                pinchActive = e.pointerCount >= 2
                if (pinchActive) {
                    pinchStartDist = pointerSpan(e)
                    pinchStartZoom = zoom
                    // 记住捏合中心对应的时间点，缩放时让它保持不动
                    pinchAnchorTick = xToTick((e.getX(0) + e.getX(1)) / 2f)
                }
                panX = (e.getX(0) + e.getX(1)) / 2f
                panY = (e.getY(0) + e.getY(1)) / 2f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 捏合缩放优先，不参与任何编辑
                if (pinchActive && e.pointerCount >= 2) {
                    val span = pointerSpan(e)
                    if (pinchStartDist > 1f && span > 1f) {
                        val ratio = span / pinchStartDist
                        // 捏合是连续手势：直接设目标并让动画平滑追上，
                        // 不逐帧硬设 zoom（那样手指一动画面就跳，毫无"跟随"感）。
                        zoomTarget = (pinchStartZoom * ratio).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        zoomAnchorTick = pinchAnchorTick
                        if (!zoomAnimating) {
                            zoomAnimating = true
                            postInvalidateOnAnimation()
                        }
                    }
                    return true
                }

                // 还没越过阈值就什么都不做 —— 手指的微小抖动不该移动片段。
                if (!moved) {
                    if (abs(e.x - downX) < dp(DRAG_SLOP_DP) &&
                        abs(e.y - downY) < dp(DRAG_SLOP_DP)) {
                        return true
                    }
                    moved = true
                    // 只有确定是拖动之后，才从"点击"升级成"平移/改长度"
                    if (drag == Drag.SEEK) drag = Drag.PAN
                }

                when (drag) {
                    Drag.PAN -> {
                        val mx = if (e.pointerCount >= 2) (e.getX(0) + e.getX(1)) / 2f else e.x
                        val my = if (e.pointerCount >= 2) (e.getY(0) + e.getY(1)) / 2f else e.y
                        scrollX = (scrollX - (mx - panX)).coerceAtLeast(0f)
                        scrollY = (scrollY - (my - panY))
                            .coerceIn(0f, max(0f, contentH - height + rulerHeight))
                        panX = mx
                        panY = my
                        invalidate()
                    }
                    Drag.LOOP -> {
                        val tick = xToTick(e.x).coerceAtLeast(0)
                        loopStart = min(loopAnchor, tick)
                        loopEnd = max(loopAnchor, tick)
                        invalidate()
                    }
                    Drag.MOVE -> {
                        val c = activeClip ?: return true
                        val d = snapTick(xToTick(e.x) - xToTick(dragStartX))
                        c.start = (origStart + d).coerceAtLeast(0)
                        c.track = ((e.y - rulerHeight + scrollY) / rowHeight)
                            .toInt().coerceIn(0, p.tracks.size - 1)
                        invalidate()
                    }
                    Drag.RESIZE_R -> {
                        val c = activeClip ?: return true
                        val d = snapTick(xToTick(e.x) - xToTick(dragStartX))
                        c.length = (origLen + d).coerceAtLeast(Note.STEP)
                        invalidate()
                    }
                    Drag.RESIZE_L -> {
                        val c = activeClip ?: return true
                        val d = snapTick(xToTick(e.x) - xToTick(dragStartX))
                        // 左缘拖动时起点跟着走，但长度与素材偏移反向补偿，
                        // 这样"露出来的那部分音频"保持不动 —— 和 FL 的裁剪一致。
                        val newStart = (origStart + d).coerceAtLeast(0)
                        val actual = newStart - origStart
                        val newLen = origLen - actual
                        if (newLen >= Note.STEP) {
                            c.start = newStart
                            c.length = newLen
                            c.offset = (origOffset + actual).coerceAtLeast(0)
                        }
                        invalidate()
                    }
                    else -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasPinch = pinchActive
                pinchActive = false
                if (wasPinch) {
                    // 捏合结束后不要触发"空点导入"或"片段改动"这类副作用
                    drag = Drag.NONE
                    activeClip = null
                    invalidate()
                    return true
                }
                if (drag == Drag.LOOP) {
                    if (loopEnd - loopStart < Note.STEP) loopEnd = loopStart + Note.BAR
                    loopOn = true
                    onLoopChanged?.invoke(true, loopStart, loopEnd)
                }
                if (drag == Drag.MOVE || drag == Drag.RESIZE_R || drag == Drag.RESIZE_L) {
                    scanCache.clear()
                    onClipsChanged?.invoke()
                }
                if (drag == Drag.SEEK && !moved) {
                    // 空处单击：只移动播放头，不再弹导入框。
                    //
                    // 导入音频是低频动作，且此前每次轻点空白轨道都会弹出来，
                    // 在编排页来回看的时候极其烦人。现在导入走工具栏的「+ 音频」，
                    // 空白处单击只定位 —— 这是 FL / Ableton 的一致行为。
                    val row = ((e.y - rulerHeight + scrollY) / rowHeight).toInt()
                    if (row in p.tracks.indices) {
                        onSeek?.invoke(xToTick(e.x).coerceAtLeast(0))
                    }
                }
                drag = Drag.NONE
                activeClip = null
                invalidate()
                return true
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 缩放

    /** 两指之间的距离，用于捏合比例。 */
    private fun pointerSpan(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(1) - e.getX(0)
        val dy = e.getY(1) - e.getY(0)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** 以某个屏幕点的位置为中心缩放（双击用）。 */
    private fun zoomAround(screenX: Float, target: Float) {
        val anchorTick = xToTick(screenX)
        applyZoomAnchored(target, anchorTick)
    }

    /**
     * 缩放，并让 anchorTick 这个时间点停在屏幕上的同一位置。
     *
     * 不做锚定的话，缩放会以左边缘为基准，你想看的那一段会飞出屏幕 ——
     * 这是"能缩放但不好用"的典型症状。
     */
    private fun applyZoomAnchored(target: Float, anchorTick: Int) {
        val clamped = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // 手感：缩放要走动画，不能瞬间跳。
        // 之前 zoom 是直接赋值，双击/捏合放手时画面"啪"地换个密度，
        // 用户报的"瞬间切过去"就是这个。
        zoomTarget = clamped
        zoomAnchorTick = anchorTick
        if (!zoomAnimating) {
            zoomAnimating = true
            postInvalidateOnAnimation()
        }
        scanCache.clear()
    }

    /**
     * 每帧推进缩放动画。
     *
     * 用指数逼近而不是线性插值：缩放手感要"快起步、慢收尾"，
     * 线性会让最后几像素拖很久。系数 0.28 在 60fps 下约 5 帧到 95%，
     * 再配合"足够接近就吸附"避免无限逼近。
     */
    private fun stepZoom(): Boolean {
        val diff = zoomTarget - zoom
        if (abs(diff) < 0.0015f) {
            if (zoom != zoomTarget) {
                applyZoomNow(zoomTarget, zoomAnchorTick)
            }
            zoomAnimating = false
            return false
        }
        val next = zoom + diff * 0.28f
        applyZoomNow(next, zoomAnchorTick)
        return true
    }

    /** 真正把 zoom 写进去，并按 anchorTick 保持画面锚点不动。 */
    private fun applyZoomNow(newZoom: Float, anchorTick: Int) {
        val oldBw = barWidth()
        val anchorScreenX = headerWidth + (anchorTick * oldBw / Note.BAR) - scrollX
        zoom = newZoom
        val newBw = barWidth()
        val desired = headerWidth + (anchorTick * newBw / Note.BAR) - anchorScreenX
        scrollX = desired.coerceAtLeast(0f)
        // 放大后总宽度变化，滚动范围要跟着更新
        scrollX = scrollX.coerceIn(0f, maxScrollX())
        invalidate()
    }

    private fun maxScrollX(): Float {
        val p = project ?: return Float.MAX_VALUE
        val totalW = p.lengthTicks() * barWidth() / Note.BAR
        return max(0f, totalW - (width - headerWidth))
    }

    /** 工具栏用：按倍数缩放，锚定在屏幕中心。 */
    fun zoomBy(factor: Float) {
        applyZoomAnchored(zoom * factor, xToTick(if (width > 0) width / 2f else dp(200f)))
    }

    /** 工具栏用：回到默认密度。 */
    fun zoomReset() {
        applyZoomAnchored(1f, playheadTick)
    }

    fun scrollByX(px: Float) {
        scrollX = (scrollX + px).coerceAtLeast(0f)
        invalidate()
    }

    fun scrollToPlayheadIfNeeded(tick: Int) {
        val x = tickToX(tick)
        val w = width.toFloat()
        if (x > w * 0.85f || x < headerWidth) {
            scrollX = (tick * barWidth() / Note.BAR - (w - headerWidth) * 0.3f).coerceAtLeast(0f)
            invalidate()
        }
    }
}
