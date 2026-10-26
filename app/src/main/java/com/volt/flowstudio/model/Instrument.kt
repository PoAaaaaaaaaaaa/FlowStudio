package com.volt.flowstudio.model

/** 乐器枚举。ordinal 与 C++ 侧 flow::Instrument 严格对齐，改顺序必须两边一起改。 */
enum class Instrument(val cn: String) {
    SYNTH("合成器"),
    SAMPLER("采样器"),
    DRUM("鼓组"),
    BASS("贝斯"),
    BELL("钟琴"),
    EPIANO("电钢"),
    // ---- 1.29 追加，ordinal 必须与 C++ 侧 flow::Instrument 一致 ----
    PLUCK("拨弦"),      // Karplus-Strong 波导，吉他/竖琴/古筝
    STRINGS("弦乐"),    // 失谐锯齿合奏 + 颤音
    ORGAN("风琴");      // 加法合成拉杆风琴

    /** 这个乐器默认用哪个音域起手（键盘默认滚到哪个八度）。 */
    val defaultOctave: Int
        get() = when (this) {
            DRUM -> 2
            BASS -> 1
            BELL -> 5
            EPIANO -> 3
            PLUCK -> 3
            STRINGS -> 3
            ORGAN -> 3
            else -> 3
        }

    /** 界面上给每个参数一个中文标签，音色面板直接用。 */
    fun paramLabels(): List<String> = when (this) {
        SYNTH -> listOf("振荡1", "振荡2", "失谐", "噪声", "起音", "衰减", "保持", "释放", "截止", "共振", "包络量", "宽度")
        SAMPLER -> listOf("起点", "终点", "起音", "衰减", "循环", "声像", "感度", "", "", "", "", "")
        DRUM -> listOf("音高", "衰减", "瞬态", "音色", "失真", "", "", "", "", "", "", "")
        BASS -> listOf("波形", "次级", "失谐", "噪声", "起音", "衰减", "保持", "释放", "截止", "共振", "包络量", "")
        BELL -> listOf("比例", "调制", "亮度", "衰减", "", "", "", "", "", "", "", "")
        EPIANO -> listOf("比例", "调制", "锤击", "颤音", "包络", "", "", "", "", "", "", "")
        PLUCK -> listOf("亮度", "衰减", "拨片", "力度感", "起音", "衰减", "保持", "释放", "琴体", "", "", "")
        STRINGS -> listOf("音色", "展开", "失谐", "颤音", "起音", "衰减", "保持", "释放", "截止", "共振", "包络量", "")
        ORGAN -> listOf("亚谐波", "五度", "基音", "八度", "八度2", "八度3", "八度4", "八度5", "八度6", "起音", "释放", "打击")
    }

    /** 参数面板只显示有标签的那些。 */
    fun activeParamCount(): Int = paramLabels().count { it.isNotEmpty() }
}
