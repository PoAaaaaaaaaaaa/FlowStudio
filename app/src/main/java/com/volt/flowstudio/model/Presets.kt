package com.volt.flowstudio.model

/**
 * 音色预设库。
 *
 * FL Studio Mobile 好用的一半原因在这里：不需要懂合成器，选一个名字就有能用的音色。
 * 每个预设是一组参数 + 效果链 + 混音建议，直接套到轨道上。
 *
 * 参数含义见 Instrument.paramLabels()。
 */
object Presets {

    data class Preset(
        val name: String,
        val inst: Instrument,
        val params: FloatArray,
        val transpose: Int = 0,
        val velocitySense: Float = 0.7f,
        val volume: Float = 0.8f,
        val sendReverb: Float = 0.12f,
        val sendDelay: Float = 0.08f,
        val effects: List<Effect> = emptyList()
    )

    private fun p(vararg v: Float): FloatArray {
        val a = FloatArray(12) { 0.5f }
        v.forEachIndexed { i, f -> if (i < 12) a[i] = f }
        return a
    }

    private fun fx(type: EffectType, mix: Float, vararg params: Float): Effect {
        val base = type.defaults()
        params.forEachIndexed { i, f -> if (i < 8) base[i] = f }
        return Effect(type = type, enabled = true, mix = mix, params = base)
    }

    /** 全部预设，按乐器分组。 */
    val all: List<Preset> = listOf(
        // ------------------------------------------------ 合成器
        Preset("温暖铺底", Instrument.SYNTH,
            p(0.5f, 0.5f, 0.58f, 0.08f, 0.62f, 0.5f, 0.75f, 0.62f, 0.55f, 0.12f, 0.35f, 0.85f),
            volume = 0.45f, sendReverb = 0.32f,
            effects = listOf(fx(EffectType.CHORUS, 0.35f, 0.25f, 0.4f, 0.35f))),
        Preset("明亮主音", Instrument.SYNTH,
            p(0.25f, 0.25f, 0.52f, 0.02f, 0.05f, 0.35f, 0.55f, 0.25f, 0.85f, 0.4f, 0.5f, 0.6f),
            volume = 0.55f, sendDelay = 0.2f),
        Preset("肥厚锯齿", Instrument.SYNTH,
            p(0.25f, 0.25f, 0.72f, 0.05f, 0.12f, 0.4f, 0.7f, 0.35f, 0.72f, 0.3f, 0.45f, 1.0f),
            volume = 0.5f),
        Preset("呼吸Pad", Instrument.SYNTH,
            p(0.5f, 0.25f, 0.5f, 0.25f, 0.75f, 0.6f, 0.85f, 0.8f, 0.45f, 0.15f, 0.2f, 0.9f),
            volume = 0.4f, sendReverb = 0.45f),
        Preset("拨弦", Instrument.SYNTH,
            p(0.25f, 0.25f, 0.5f, 0f, 0.001f, 0.22f, 0f, 0.18f, 0.78f, 0.35f, 0.6f, 0.4f),
            volume = 0.55f, sendDelay = 0.15f),

        // ------------------------------------------------ 贝斯
        Preset("厚实Sub", Instrument.BASS,
            p(0.5f, 0.5f, 0.05f, 0f, 0.01f, 0.55f, 0.9f, 0.15f, 0.22f, 0.2f, 0.3f),
            volume = 0.85f),
        Preset("酸性贝斯", Instrument.BASS,
            p(0.85f, 0.5f, 0.08f, 0f, 0.001f, 0.3f, 0.1f, 0.1f, 0.35f, 0.75f, 0.9f),
            volume = 0.75f,
            effects = listOf(fx(EffectType.DIST, 0.3f, 0.35f, 0.6f, 0.5f))),
        Preset("弹跳贝斯", Instrument.BASS,
            p(0.25f, 0.25f, 0.15f, 0.05f, 0.001f, 0.25f, 0.4f, 0.12f, 0.55f, 0.5f, 0.6f),
            volume = 0.8f),
        Preset("FM 低音", Instrument.BASS,
            p(0.5f, 0.5f, 0.1f, 0f, 0.005f, 0.4f, 0.6f, 0.2f, 0.4f, 0.35f, 0.5f),
            volume = 0.78f),

        // ------------------------------------------------ 鼓
        Preset("House 鼓", Instrument.DRUM,
            p(0.3f, 0.3f, 0.5f, 0.45f, 0.3f), volume = 0.9f),
        Preset("Techno 鼓", Instrument.DRUM,
            p(0.22f, 0.22f, 0.45f, 0.35f, 0.5f), volume = 0.92f,
            effects = listOf(fx(EffectType.COMP, 1f, 0.35f, 0.8f, 0.2f, 0.3f))),
        Preset("Lo-Fi 鼓", Instrument.DRUM,
            p(0.45f, 0.4f, 0.6f, 0.25f, 0.55f), volume = 0.8f,
            effects = listOf(fx(EffectType.BITCRUSH, 0.4f, 0.45f, 0.55f))),
        Preset("硬派鼓", Instrument.DRUM,
            p(0.18f, 0.2f, 0.7f, 0.55f, 0.75f), volume = 0.88f,
            effects = listOf(fx(EffectType.DIST, 0.25f, 0.5f, 0.5f, 0.6f))),

        // ------------------------------------------------ 钟琴
        Preset("清亮钟琴", Instrument.BELL,
            p(0.35f, 0.4f, 0.7f, 0.5f), volume = 0.5f, sendReverb = 0.3f),
        Preset("金属音", Instrument.BELL,
            p(0.7f, 0.75f, 0.55f, 0.65f), volume = 0.45f, sendReverb = 0.4f),
        Preset("八音盒", Instrument.BELL,
            p(0.2f, 0.25f, 0.8f, 0.35f), volume = 0.48f, sendReverb = 0.25f),

        // ------------------------------------------------ 电钢
        Preset("经典电钢", Instrument.EPIANO,
            p(0.45f, 0.4f, 0.5f, 0.35f, 0.4f), volume = 0.6f, sendReverb = 0.2f),
        Preset("沙哑电钢", Instrument.EPIANO,
            p(0.6f, 0.6f, 0.45f, 0.25f, 0.5f), volume = 0.55f, sendReverb = 0.28f,
            effects = listOf(fx(EffectType.CHORUS, 0.3f, 0.3f, 0.4f, 0.3f))),
        Preset("明亮电钢", Instrument.EPIANO,
            p(0.3f, 0.3f, 0.65f, 0.4f, 0.3f), volume = 0.6f, sendReverb = 0.18f)
    )

    /** 某乐器的所有预设 */
    fun forInstrument(inst: Instrument): List<Preset> = all.filter { it.inst == inst }

    /** 把预设套到轨道上（保留名字与混音平衡感） */
    fun apply(preset: Preset, track: Track) {
        track.instrument = preset.inst
        preset.params.copyInto(track.params)
        track.transpose = preset.transpose
        track.velocitySense = preset.velocitySense
        track.volume = preset.volume
        track.sendReverb = preset.sendReverb
        track.sendDelay = preset.sendDelay

        for (i in 0 until 4) {
            track.effects[i] = preset.effects.getOrNull(i)?.copyEffect() ?: Effect.empty()
        }
    }
}
