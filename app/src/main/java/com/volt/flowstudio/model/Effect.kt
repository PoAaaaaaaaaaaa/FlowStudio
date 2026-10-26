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
    WIDTH("立体声宽度"),

    // ---- 1.38 追加。必须加在末尾：中间插入会让旧工程存档里的
    // 效果类型整体错位（存档里存的是 ordinal），用户的曲子会突然换声音。
    COMPRESSOR2("三段压缩"),
    LIMITER("限幅"),
    DEESSER("齿音消除"),
    TRANSIENT("瞬态塑形"),
    EXCITER("激励");

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
            // ---- 1.38 ----
            COMPRESSOR2 -> 4
            LIMITER -> 3
            DEESSER -> 3
            TRANSIENT -> 3
            EXCITER -> 3
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
        COMPRESSOR2 -> listOf("低频阈值", "中频阈值", "高频阈值", "压缩比").getOrElse(i) { "" }
        LIMITER -> listOf("阈值", "释放", "前视").getOrElse(i) { "" }
        DEESSER -> listOf("频率", "阈值", "深度").getOrElse(i) { "" }
        TRANSIENT -> listOf("起音", "尾音", "灵敏度").getOrElse(i) { "" }
        EXCITER -> listOf("驱动", "频率", "混合").getOrElse(i) { "" }
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
            // ---- 1.38 ----
            // 三段压缩：三个阈值都偏保守（0.45），让默认状态是"轻压"而不是"压死"
            COMPRESSOR2 -> { p[0] = 0.45f; p[1] = 0.45f; p[2] = 0.45f; p[3] = 0.25f }
            // 限幅：阈值 0.85 附近，释放中速，前视给 1/3（约 1.7ms）
            LIMITER -> { p[0] = 0.78f; p[1] = 0.30f; p[2] = 0.33f }
            // 齿音：中心频率略低于 8k（中文齿音最集中的地方），阈值温和
            DEESSER -> { p[0] = 0.62f; p[1] = 0.40f; p[2] = 0.55f }
            // 瞬态：起音略强化、尾音不动 —— 默认是"更结实"而不是"更炸"
            TRANSIENT -> { p[0] = 0.62f; p[1] = 0.50f; p[2] = 0.50f }
            // 激励：驱动与混合都克制，默认只是"提一点空气感"
            EXCITER -> { p[0] = 0.30f; p[1] = 0.35f; p[2] = 0.30f }
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
            // ---- 1.38 ----
            // 压缩与限幅是"处理"不是"效果"，应该全量作用
            COMPRESSOR2, LIMITER -> 1f
            // 齿音消除同理：只在齿音带内衰减，不需要干湿混合
            DEESSER -> 1f
            // 瞬态塑形全量，否则听不出
            TRANSIENT -> 1f
            // 激励器混一点就够，全量会啸叫
            EXCITER -> 0.35f
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
