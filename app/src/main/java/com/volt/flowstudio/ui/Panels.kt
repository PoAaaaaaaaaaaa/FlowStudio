package com.volt.flowstudio.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.volt.flowstudio.model.Effect
import com.volt.flowstudio.model.EffectType
import com.volt.flowstudio.model.Instrument
import com.volt.flowstudio.model.Track
import com.volt.flowstudio.session.StudioSession

/**
 * 音色面板：换乐器 + 调参数。旋钮与引擎参数一一对应。
 */
class SoundPanel(
    context: Context,
    private val session: StudioSession,
    private val trackIndex: Int,
    private val onChanged: () -> Unit
) : LinearLayout(context) {

    private var instRow: LinearLayout = LinearLayout(context)
    private var paramRow: LinearLayout = LinearLayout(context)
    private var presetRow: LinearLayout = LinearLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.PANEL)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        instRow = LinearLayout(context)
        paramRow = LinearLayout(context)
        setup()
    }

    private fun setup() {
        val track = session.project.trackAt(trackIndex)
        if (track == null) {
            addView(TextView(context).apply { text = "轨道不存在"; setTextColor(Palette.RED) })
            instRow = LinearLayout(context)
            paramRow = LinearLayout(context)
            return
        }
        val t0: Track = track

        addView(TextView(context).apply {
            text = "${t0.name} · 音色"
            setTextColor(Palette.TEXT)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        // 乐器选择
        instRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(instRow)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        Instrument.values().forEach { inst ->
            val btn = TextView(context).apply {
                text = inst.cn
                textSize = 11f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    val t = session.project.trackAt(trackIndex) ?: return@setOnClickListener
                    t.instrument = inst
                    // 换乐器后用该乐器的出厂参数，避免落到"听起来是坏的"参数组合
                    Track.preset(inst, t.name).params.copyInto(t.params)
                    paintInstrumentButtons(inst)
                    rebuildPresets(presetRow, inst)
                    rebuildKnobs()
                    session.pushTrack(trackIndex)
                    session.scheduleAutoSave()
                    onChanged()
                }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(4)
            instRow.addView(btn, lp)
        }
        paintInstrumentButtons(t0.instrument)

        // ---- 预设库 ----
        // FL 之所以好用，一半在于"选个名字就有能用的音色"。
        val presetTitle = TextView(context).apply {
            text = "预设"
            setTextColor(Palette.TEXT_FAINT)
            textSize = 10f
            setPadding(0, dp(8), 0, dp(2))
        }
        addView(presetTitle)

        presetRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(presetRow)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        rebuildPresets(presetRow, t0.instrument)

        // 参数旋钮
        paramRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        addView(
            ScrollView(context).apply { addView(paramRow) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // 试听
        addView(
            TextView(context).apply {
                text = "▶ 试听"
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setTextColor(Palette.BG)
                setBackgroundColor(Palette.withAlpha(Palette.ACCENT, 220))
                setOnClickListener {
                    val t = session.project.trackAt(trackIndex) ?: return@setOnClickListener
                    val pitch = if (t.instrument == Instrument.DRUM) 36 else 60
                    session.previewNote(trackIndex, pitch, 110, 480)
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        rebuildKnobs()
    }

    private fun paintInstrumentButtons(selected: Instrument) {
        for (i in 0 until instRow.childCount) {
            val v = instRow.getChildAt(i) as? TextView ?: continue
            val inst = Instrument.values().getOrNull(i) ?: continue
            val on = inst == selected
            v.setTextColor(if (on) Palette.BG else Palette.TEXT_DIM)
            v.setBackgroundColor(if (on) Palette.ACCENT else Palette.PANEL_HI)
        }
    }

    private fun rebuildKnobs() {
        paramRow.removeAllViews()
        val t = session.project.trackAt(trackIndex) ?: return
        val accent = Palette.trackColor(trackIndex)

        t.instrument.paramLabels().forEachIndexed { i, name ->
            if (name.isEmpty()) return@forEachIndexed
            val knob = KnobView(context).apply {
                label = name
                this.accent = accent
                setSilently(t.params.getOrElse(i) { 0.5f })
                displayValue = fmt(t.instrument, i, t.params.getOrElse(i) { 0.5f })
                onValueChange = cb@{ v ->
                    val tt = session.project.trackAt(trackIndex) ?: return@cb
                    tt.params[i] = v
                    displayValue = fmt(tt.instrument, i, v)
                    session.pushTrack(trackIndex)
                }
                onValueCommitted = committed@{
                    session.scheduleAutoSave()
                    onChanged()
                }
            }
            paramRow.addView(knob, LayoutParams(dp(58), dp(74)).apply { marginEnd = dp(2) })
        }

        // 移调
        paramRow.addView(
            KnobView(context).apply {
                label = "移调"
                this.accent = Palette.CYAN
                setSilently(((t.transpose + 24) / 48f).coerceIn(0f, 1f))
                displayValue = signed(t.transpose)
                onValueChange = cb@{ v ->
                    val tt = session.project.trackAt(trackIndex) ?: return@cb
                    tt.transpose = Math.round(v * 48f - 24f).toInt()
                    displayValue = signed(tt.transpose)
                    session.pushTrack(trackIndex)
                }
                onValueCommitted = committed@{ session.scheduleAutoSave(); onChanged() }
            },
            LayoutParams(dp(58), dp(74)).apply { marginEnd = dp(2) }
        )

        // 力度灵敏度
        paramRow.addView(
            KnobView(context).apply {
                label = "力度感"
                this.accent = Palette.CYAN
                setSilently(t.velocitySense)
                displayValue = "${(t.velocitySense * 100).toInt()}%"
                onValueChange = cb@{ v ->
                    val tt = session.project.trackAt(trackIndex) ?: return@cb
                    tt.velocitySense = v
                    displayValue = "${(v * 100).toInt()}%"
                    session.pushTrack(trackIndex)
                }
                onValueCommitted = committed@{ session.scheduleAutoSave(); onChanged() }
            },
            LayoutParams(dp(58), dp(74))
        )
    }

    /** 某乐器的预设按钮。点一下整套参数 + 效果一键套上。 */
    private fun rebuildPresets(row: LinearLayout, inst: com.volt.flowstudio.model.Instrument) {
        row.removeAllViews()
        val list = com.volt.flowstudio.model.Presets.forInstrument(inst)
        if (list.isEmpty()) {
            row.addView(TextView(context).apply {
                text = "（这个乐器没有预设）"
                setTextColor(Palette.TEXT_FAINT)
                textSize = 10f
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        for (preset in list) {
            val btn = TextView(context).apply {
                text = preset.name
                textSize = 10.5f
                setPadding(dp(9), dp(6), dp(9), dp(6))
                setTextColor(Palette.TEXT)
                setBackgroundColor(Palette.PANEL_HI)
                setOnClickListener {
                    val t = session.project.trackAt(trackIndex) ?: return@setOnClickListener
                    com.volt.flowstudio.model.Presets.apply(preset, t)
                    rebuildKnobs()
                    paintInstrumentButtons(preset.inst)
                    session.pushTrack(trackIndex)
                    session.scheduleAutoSave()
                    onChanged()
                    android.widget.Toast.makeText(
                        context, "已套用「${preset.name}」", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(4)
            row.addView(btn, lp)
        }
    }

    private fun signed(v: Int): String = if (v > 0) "+$v" else "$v"

    /** 把 0..1 归一值翻译成人能读的物理量。 */
    private fun fmt(inst: Instrument, i: Int, v: Float): String {
        val name = inst.paramLabels().getOrElse(i) { "" }
        return when {
            name.contains("起音") || name.contains("衰减") || name.contains("释放") ->
                if (v < 0.02f) "0ms" else "${(v * v * 3000 + 1).toInt()}ms"
            name.contains("截止") -> "${(80 * Math.pow(225.0, v.toDouble())).toInt()}Hz"
            name.contains("共振") -> String.format("%.1f", 0.6f + v * v * 14f)
            name.contains("失谐") -> "${((v - 0.5f) * 60).toInt()}c"
            name.contains("波形") || name.contains("振荡") -> {
                val w = (v * 5).toInt().coerceIn(0, 4)
                arrayOf("正弦", "锯齿", "方波", "三角", "噪声")[w]
            }
            else -> "${(v * 100).toInt()}%"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

/**
 * 效果器面板：选类型 + 调参数，只看当前槽位。
 */
class FxPanel(
    context: Context,
    private val session: StudioSession,
    private val trackIndex: Int,
    private val slot: Int,
    private val onChanged: () -> Unit
) : LinearLayout(context) {

    private val typeRow: LinearLayout
    private val knobRow: LinearLayout
    private val titleView: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.PANEL)
        setPadding(dp(10), dp(8), dp(10), dp(8))

        titleView = TextView(context).apply {
            setTextColor(Palette.TEXT)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        addView(titleView)

        typeRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(typeRow)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        knobRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        addView(
            ScrollView(context).apply { addView(knobRow) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        rebuild()
    }

    private fun rebuild() {
        val track = session.project.trackAt(trackIndex)
        if (track == null) {
            titleView.text = "轨道不存在"
            return
        }
        val fx = track.effects.getOrNull(slot) ?: return
        titleView.text = "${track.name} · 效果 ${slot + 1} · ${fx.type.cn}"

        // 类型按钮
        typeRow.removeAllViews()
        EffectType.values().forEach { et ->
            val on = et == fx.type
            val btn = TextView(context).apply {
                text = et.cn
                textSize = 11f
                setPadding(dp(9), dp(6), dp(9), dp(6))
                setTextColor(if (on) Palette.BG else Palette.TEXT_DIM)
                setBackgroundColor(if (on) Palette.ACCENT else Palette.PANEL_HI)
                setOnClickListener {
                    val t = session.project.trackAt(trackIndex) ?: return@setOnClickListener
                    val cur = t.effects.getOrNull(slot) ?: return@setOnClickListener
                    cur.type = et
                    cur.params = et.defaults()
                    cur.mix = et.defaultMix
                    session.pushTrack(trackIndex)
                    session.scheduleAutoSave()
                    onChanged()
                    rebuild()
                }
            }
            typeRow.addView(btn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            })
        }

        // 参数旋钮
        knobRow.removeAllViews()
        if (fx.type == EffectType.NONE) return

        for (i in 0 until fx.type.knobCount) {
            val knob = KnobView(context).apply {
                label = fx.type.knobName(i)
                accent = Palette.trackColor(trackIndex)
                setSilently(fx.params.getOrElse(i) { 0.5f })
                displayValue = fmt(fx.type, i, fx.params.getOrElse(i) { 0.5f })
                onValueChange = cb@{ v ->
                    val t = session.project.trackAt(trackIndex) ?: return@cb
                    val e = t.effects.getOrNull(slot) ?: return@cb
                    e.params[i] = v
                    displayValue = fmt(e.type, i, v)
                    session.pushTrack(trackIndex)
                }
                onValueCommitted = committed@{ session.scheduleAutoSave(); onChanged() }
            }
            knobRow.addView(knob, LayoutParams(dp(62), dp(78)).apply { marginEnd = dp(2) })
        }

        // 干湿比
        knobRow.addView(
            KnobView(context).apply {
                label = "干湿"
                accent = Palette.GREEN
                setSilently(fx.mix)
                displayValue = "${(fx.mix * 100).toInt()}%"
                onValueChange = cb@{ v ->
                    val t = session.project.trackAt(trackIndex) ?: return@cb
                    val e = t.effects.getOrNull(slot) ?: return@cb
                    e.mix = v
                    displayValue = "${(v * 100).toInt()}%"
                    session.pushTrack(trackIndex)
                }
                onValueCommitted = committed@{ session.scheduleAutoSave(); onChanged() }
            },
            LayoutParams(dp(62), dp(78)).apply { marginEnd = dp(2) }
        )

        // 旁通开关
        knobRow.addView(
            TextView(context).apply {
                text = if (fx.enabled) "已开" else "旁通"
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setTextColor(if (fx.enabled) Palette.BG else Palette.TEXT_DIM)
                setBackgroundColor(if (fx.enabled) Palette.GREEN else Palette.PANEL_HI)
                setOnClickListener {
                    val t = session.project.trackAt(trackIndex) ?: return@setOnClickListener
                    val e = t.effects.getOrNull(slot) ?: return@setOnClickListener
                    e.enabled = !e.enabled
                    session.pushTrack(trackIndex)
                    session.scheduleAutoSave()
                    onChanged()
                    rebuild()
                }
            },
            LayoutParams(dp(48), dp(34)).apply { topMargin = dp(22) }
        )
    }

    private fun fmt(type: EffectType, i: Int, v: Float): String {
        return when (type.knobName(i)) {
            "时值" -> {
                val divs = arrayOf("1/32", "1/16.", "1/16", "1/8T", "1/8.", "1/8", "1/4T", "1/4.", "1/4", "1/2")
                divs[(v * 10).toInt().coerceIn(0, 9)]
            }
            "截止" -> "${(80 * Math.pow(225.0, v.toDouble())).toInt()}Hz"
            "共振" -> String.format("%.1f", 0.55f + v * v * 16f)
            "阈值" -> "${(-42 + v * 36).toInt()}dB"
            "压缩比" -> String.format("%.1f:1", 1f + v * 15f)
            "起音" -> "${(0.5f + v * 120).toInt()}ms"
            "释放" -> "${(12 + v * 700).toInt()}ms"
            "位深" -> "${(16 - v * 14).toInt()}bit"
            "低频", "中低", "中高", "高频" -> String.format("%+.1fdB", (v - 0.5f) * 24f)
            "乒乓" -> if (v > 0.5f) "开" else "关"
            "类型" -> arrayOf("低通", "高通", "带通", "陷波")[(v * 4).toInt().coerceIn(0, 3)]
            else -> "${(v * 100).toInt()}%"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
