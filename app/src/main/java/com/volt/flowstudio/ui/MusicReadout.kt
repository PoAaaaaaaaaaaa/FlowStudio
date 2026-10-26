package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import kotlin.math.roundToInt

/**
 * 底部音乐数值表。
 *
 * 编曲时最常需要"一眼确认"的数字都集中在这一条里，不必点开任何面板：
 *
 *   小节/拍/格   当前位置（走带时实时跳）
 *   速度         BPM
 *   调          工程设定的调性与音阶
 *   和声        当前小节正在响的和弦（按片段内容推）
 *   长度        总小节数
 *   音轨/音符   规模
 *   声部         正在发声音符数（复音压力）
 *
 * 为什么放"和弦"这一项：编曲时最费神的判断就是"这里该配什么和弦"。
 * 把当前小节实际在响的音推成和弦名，比自己数音符快得多。
 * 推导用音级集合匹配 —— 取该小节出现最多的音高类，与常见和弦模板比对，
 * 命中就给名字，不命中就显示音级（如 "A C E"），绝不瞎猜一个和弦名。
 */
class MusicReadout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : BaseView(context, attrs) {

    /** 当前位置（tick）。 */
    var positionTicks = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    var barTicks = 1920
    var bpm = 120f
    var totalBars = 0
    var trackCount = 0
    var noteCount = 0
    var patternCount = 0

    /** 调性提示，如 "C major"；工程没设时留空。 */
    var keyLabel = ""

    /** 当前小节的和弦名或音级集合。由外部算好填进来。 */
    var chordLabel = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    /** 当前正在发声的音符数（复音压力）。 */
    var voices = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    /** 是否在播放：影响小节数字的高亮。 */
    var playing = false
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(dp(22f).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 底：比走带条再深一档，形成"走带条 + 读数条"的双层结构
        rect(canvas, 0f, 0f, w, h, 0xFF0A0C10.toInt())
        // 顶部一条极细分隔，把两条分开但不抢眼
        line(canvas, 0f, 0f, w, 0f, Palette.withAlpha(Palette.LINE, 70), dp(0.6f))

        val bt = barTicks.coerceAtLeast(1)
        val bar = positionTicks / bt + 1
        val beat = (positionTicks % bt) / (bt / 4).coerceAtLeast(1) + 1
        val step = (positionTicks % ((bt / 4).coerceAtLeast(1))) / (bt / 16).coerceAtLeast(1) + 1

        val cy = h * 0.5f
        val textY = cy + sp(3.2f)
        val padX = dp(10f)
        val fontSize = sp(9f)
        val labelSize = sp(7.2f)

        // 每项 = 标签 + 值。用固定顺序排列，从左到右。
        // 空间不够时优先丢后面的项（和声/声部先让位），位置与速度永远保留。
        val items = ArrayList<Triple<String, String, Int>>(8)
        items.add(Triple("位置", "$bar.$beat.$step", Palette.TEXT))
        items.add(Triple("BPM", if (bpm % 1f == 0f) bpm.toInt().toString()
                              else String.format("%.1f", bpm), Palette.ACCENT))
        if (keyLabel.isNotEmpty()) items.add(Triple("调", keyLabel, Palette.TEXT_DIM))
        if (chordLabel.isNotEmpty()) items.add(Triple("和声", chordLabel, Palette.PURPLE))
        items.add(Triple("长度", "$totalBars 小节", Palette.TEXT_DIM))
        items.add(Triple("通道", "$trackCount", Palette.TEXT_DIM))
        items.add(Triple("音符", "$noteCount", Palette.TEXT_DIM))
        if (voices > 0) items.add(Triple("发声", "$voices", Palette.GREEN))

        // 先量一遍总宽，超了就从尾部丢
        b.text.textSize = fontSize
        b.textBold.textSize = fontSize
        b.text.textSize = labelSize

        fun itemWidth(label: String, value: String): Float {
            b.text.textSize = labelSize
            val lw = b.text.measureText(label)
            b.text.textSize = fontSize
            val vw = b.text.measureText(value)
            return lw + vw + dp(4f) + dp(14f)
        }

        var total = padX
        for ((la, va, _) in items) total += itemWidth(la, va)
        while (total > w - padX && items.size > 2) {
            val removed = items.removeAt(items.size - 1)
            total -= itemWidth(removed.first, removed.second)
        }

        var x = padX
        for ((label, value, color) in items) {
            b.text.textSize = labelSize
            b.text.color = Palette.TEXT_FAINT
            b.text.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawText(label, x, textY - sp(0.6f), b.text)
            val lw = b.text.measureText(label)

            b.textBold.textSize = fontSize
            b.textBold.color = color
            b.textBold.textAlign = android.graphics.Paint.Align.LEFT
            canvas.drawText(value, x + lw + dp(4f), textY, b.textBold)

            x += lw + dp(4f) + b.textBold.measureText(value) + dp(14f)
        }

        // 播放状态：最右侧一个极小的呼吸点，不占文字位
        if (playing) {
            val dotR = dp(1.7f)
            val dx = w - padX - dotR
            b.fill.color = Palette.withAlpha(Palette.GREEN, 220)
            b.fill.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(dx, cy, dotR, b.fill)
        }
    }
}

// ------------------------------------------------------------------ 和弦推导

/**
 * 从一组音高反推和弦名。
 *
 * 做法：把音高折成音级集合（pitch class set），与和弦模板比对。
 * 这是音乐信息检索里的标准做法（模板匹配），足够处理常见三和弦与七和弦。
 *
 * 刻意不做的事：
 *   - 不猜转位（没有低音信息时猜转位只能靠猜）
 *   - 不硬给名字：匹配不上就返回音级列表本身（"A C E" 比一个错的和弦名有用）
 *   - 不处理无调性片段（那些本来就没有和弦名）
 */
object ChordDetect {

    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** 和弦模板：名称后缀 → 相对根音的半音集合 */
    private val TEMPLATES: List<Pair<String, IntArray>> = listOf(
        "" to intArrayOf(0, 4, 7),                 // 大三
        "m" to intArrayOf(0, 3, 7),                // 小三
        "7" to intArrayOf(0, 4, 7, 10),            // 属七
        "maj7" to intArrayOf(0, 4, 7, 11),
        "m7" to intArrayOf(0, 3, 7, 10),
        "dim" to intArrayOf(0, 3, 6),
        "dim7" to intArrayOf(0, 3, 6, 9),
        "sus2" to intArrayOf(0, 2, 7),
        "sus4" to intArrayOf(0, 5, 7),
        "m7b5" to intArrayOf(0, 3, 6, 10),
        "6" to intArrayOf(0, 4, 7, 9),
        "m6" to intArrayOf(0, 3, 7, 9),
        "add9" to intArrayOf(0, 4, 7, 2),
        "9" to intArrayOf(0, 4, 7, 10, 2),
        "maj9" to intArrayOf(0, 4, 7, 11, 2),
        "m9" to intArrayOf(0, 3, 7, 10, 2),
    )

    /**
     * @param midiPitches 该时间段内的音高（可重复，重复表示权重）
     * @return 和弦名；匹配不上时返回音级列表；空输入返回 ""
     */
    fun detect(midiPitches: List<Int>): String {
        if (midiPitches.isEmpty()) return ""

        // 音级直方图
        val hist = IntArray(12)
        for (p in midiPitches) hist[((p % 12) + 12) % 12]++

        val present = (0 until 12).filter { hist[it] > 0 }
        if (present.isEmpty()) return ""

        // 只有 1-2 个音级时不可能是三和弦，直接给音级
        if (present.size < 3) {
            return present.joinToString(" ") { NAMES[it] }
        }

        // 遍历根音 × 模板，找"完整包含且最少多余音"的匹配
        var best: Triple<Int, String, Int>? = null   // root, suffix, score
        for (root in present) {
            for ((suffix, ivs) in TEMPLATES) {
                val want = ivs.map { ((root + it) % 12 + 12) % 12 }.toSet()
                // 模板的每个音都必须在场
                if (!present.containsAll(want)) continue
                // 多余音越少越好（避免把 add9 当成 maj）
                val extra = present.size - want.size
                // 优先级：模板音越多（更具体）越好，多余音越少越好
                val score = want.size * 10 - extra * 12
                val cur = best
                if (cur == null || score > cur.third) {
                    best = Triple(root, suffix, score)
                }
            }
        }

        val b = best ?: return present.joinToString(" ") { NAMES[it] }
        return NAMES[b.first] + b.second
    }

    /** 按小节边界切开音符，逐小节推导。返回 (小节号 -> 和弦)。 */
    fun detectByBar(
        notes: List<Triple<Int, Int, Int>>,   // (pitch, startTick, endTick)
        barTicks: Int,
        maxBar: Int,
    ): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        if (barTicks <= 0) return out
        for (bar in 0 until maxBar) {
            val from = bar * barTicks
            val to = from + barTicks
            val inBar = ArrayList<Int>()
            for ((pitch, s, e) in notes) {
                // 音符与这一小节有交叠就算
                if (s < to && e > from) inBar.add(pitch)
            }
            val label = detect(inBar)
            if (label.isNotEmpty()) out[bar] = label
        }
        return out
    }
}
