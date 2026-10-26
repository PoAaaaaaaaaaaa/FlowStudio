package com.volt.flowstudio.model

import org.json.JSONArray
import org.json.JSONObject

/** 插入效果器。ordinal 与 C++ 侧 flow::EffectType 对齐。 */
enum class EffectType(val cn: String) {
    NONE("空"),
    FILTER("滤波"),
    DELAY("延迟"),
    REVERB("混响"),
    CHORUS("合唱"),
    DIST("失真"),
    COMP("压缩"),
    EQ("均衡"),
    BITCRUSH("降位"),
    PHASER("移相"),
    // ---- 1.29 追加，ordinal 必须与 C++ 侧 flow::EffectType 一致 ----
    TREMOLO("电颤"),
    AUTOPAN("自动声像"),
    SATURATOR("饱和"),
    GATE("噪声门"),
    WIDTH("立体声宽度");

    /** 这个效果用几个旋钮，与 C++ 侧 knobCount() 一致。 */
    val knobCount: Int
        get() = when (this) {
            FILTER, CHORUS, DIST, PHASER -> 3
            DELAY, REVERB, COMP -> 4
            EQ -> 6
            BITCRUSH -> 2
            TREMOLO -> 3
            AUTOPAN -> 3
            SATURATOR -> 3
            GATE -> 4
            WIDTH -> 2
            NONE -> 0
        }

    fun knobName(i: Int): String = when (this) {
        FILTER -> listOf("截止", "共振", "类型").getOrElse(i) { "" }
        DELAY -> listOf("时值", "反馈", "音染", "乒乓").getOrElse(i) { "" }
        REVERB -> listOf("房间", "衰减", "预延迟", "宽度").getOrElse(i) { "" }
        CHORUS -> listOf("速率", "深度", "延迟").getOrElse(i) { "" }
        DIST -> listOf("驱动", "音色", "输出").getOrElse(i) { "" }
        COMP -> listOf("阈值", "压缩比", "起音", "释放").getOrElse(i) { "" }
        EQ -> listOf("低频", "中低", "中高", "高频", "低切", "高切").getOrElse(i) { "" }
        BITCRUSH -> listOf("位深", "降采样").getOrElse(i) { "" }
        PHASER -> listOf("速率", "深度", "反馈").getOrElse(i) { "" }
        TREMOLO -> listOf("速率", "深度", "波形").getOrElse(i) { "" }
        AUTOPAN -> listOf("速率", "深度", "形状").getOrElse(i) { "" }
        SATURATOR -> listOf("驱动", "非对称", "输出").getOrElse(i) { "" }
        GATE -> listOf("阈值", "起音", "释放", "深度").getOrElse(i) { "" }
        WIDTH -> listOf("宽度", "低频保持").getOrElse(i) { "" }
        NONE -> ""
    }

    /** 出厂默认值。界面第一次放效果器时直接能用。 */
    fun defaults(): FloatArray {
        val p = FloatArray(8) { 0.5f }
        when (this) {
            FILTER -> { p[0] = 0.62f; p[1] = 0.25f; p[2] = 0f }
            DELAY -> { p[0] = 0.34f; p[1] = 0.42f; p[2] = 0.55f; p[3] = 0.5f }
            REVERB -> { p[0] = 0.55f; p[1] = 0.6f; p[2] = 0.35f; p[3] = 0.5f }
            CHORUS -> { p[0] = 0.4f; p[1] = 0.35f; p[2] = 0.3f }
            DIST -> { p[0] = 0.45f; p[1] = 0.5f; p[2] = 0.6f }
            COMP -> { p[0] = 0.45f; p[1] = 0.6f; p[2] = 0.5f; p[3] = 0.2f }
            EQ -> { p[0] = 0.5f; p[1] = 0.5f; p[2] = 0.5f; p[3] = 0.5f; p[4] = 0.5f; p[5] = 0.5f }
            BITCRUSH -> { p[0] = 0.4f; p[1] = 0.5f }
            PHASER -> { p[0] = 0.4f; p[1] = 0.5f; p[2] = 0.3f }
            TREMOLO -> { p[0] = 0.45f; p[1] = 0.55f; p[2] = 0f }
            AUTOPAN -> { p[0] = 0.35f; p[1] = 0.6f; p[2] = 0f }
            SATURATOR -> { p[0] = 0.35f; p[1] = 0.25f; p[2] = 0.55f }
            GATE -> { p[0] = 0.3f; p[1] = 0.1f; p[2] = 0.35f; p[3] = 0.9f }
            WIDTH -> { p[0] = 0.7f; p[1] = 0.6f }
            NONE -> {}
        }
        return p
    }

    val defaultMix: Float
        get() = when (this) {
            FILTER, COMP, EQ -> 1f
            BITCRUSH -> 0.7f
            DIST, PHASER, SATURATOR -> 0.5f
            CHORUS -> 0.4f
            TREMOLO, AUTOPAN -> 0.55f
            GATE -> 1f
            WIDTH -> 0.8f
            NONE -> 0f
            else -> 0.32f
        }
}

data class Effect(
    var type: EffectType = EffectType.NONE,
    var enabled: Boolean = true,
    var mix: Float = 0.35f,
    var params: FloatArray = FloatArray(8) { 0.5f }
) {
    fun copyEffect() = Effect(type, enabled, mix, params.copyOf())

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("on", enabled)
        put("mix", mix.toDouble())
        put("p", JSONArray(params.map { it.toDouble() }))
    }

    companion object {
        fun fromJson(o: JSONObject): Effect {
            val type = runCatching { EffectType.valueOf(o.optString("type", "NONE")) }
                .getOrDefault(EffectType.NONE)
            val arr = o.optJSONArray("p")
            val p = FloatArray(8) { 0.5f }
            if (arr != null) {
                for (i in 0 until minOf(8, arr.length())) {
                    p[i] = arr.optDouble(i, 0.5).toFloat()
                }
            } else {
                type.defaults().copyInto(p)
            }
            return Effect(
                type = type,
                enabled = o.optBoolean("on", true),
                mix = o.optDouble("mix", type.defaultMix.toDouble()).toFloat(),
                params = p
            )
        }

        fun empty() = Effect()
    }
}
