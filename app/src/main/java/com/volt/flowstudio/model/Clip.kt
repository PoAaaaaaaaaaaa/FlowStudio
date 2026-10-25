package com.volt.flowstudio.model

import org.json.JSONObject

/**
 * 播放列表上的一个片段。
 *
 * 两种形态，共用同一套布局与拖动逻辑：
 *   - 样式片段：`patternId >= 0`，内容是该样式的音符；
 *   - 音频片段：`audioId > 0`，内容是一段采样音频，走整条轨道的混音。
 *
 * 早先音频只有"一条全局伴奏轨"这一个位置，没法像 FL 那样把同一段鼓
 * 摆到不同位置、裁成不同长度。现在音频就是片段，与音符片段同权。
 */
data class Clip(
    var id: Int = 1,
    /** 音频片段为 -1；否则指向某个样式。 */
    var patternId: Int = 1,
    var track: Int = 0,
    var start: Int = 0,
    var length: Int = Note.BAR * 2,
    var offset: Int = 0,
    var label: String = "",
    /** 音频素材 id，0 表示这是样式片段。 */
    var audioId: Int = 0,
    /** 音频片段自身的增益（0..2），与轨道推子是相乘关系。 */
    var audioGain: Float = 1f
) {
    val end: Int get() = start + length

    /** 音频片段没有音符，绘制与校验都走另一条路。 */
    val isAudio: Boolean get() = audioId > 0

    fun copyClip(newId: Int) = Clip(
        newId, patternId, track, start, length, offset, label, audioId, audioGain
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("pat", patternId); put("trk", track)
        put("start", start); put("len", length); put("off", offset)
        if (label.isNotEmpty()) put("label", label)
        if (audioId > 0) {
            put("audio", audioId)
            if (audioGain != 1f) put("ag", audioGain.toDouble())
        }
    }

    companion object {
        fun fromJson(o: JSONObject) = Clip(
            id = o.optInt("id", 1),
            patternId = o.optInt("pat", 1),
            track = o.optInt("trk", 0),
            start = o.optInt("start", 0),
            length = o.optInt("len", Note.BAR * 2).coerceAtLeast(Note.STEP),
            offset = o.optInt("off", 0),
            label = o.optString("label", ""),
            audioId = o.optInt("audio", 0),
            audioGain = o.optDouble("ag", 1.0).toFloat().coerceIn(0f, 2f)
        )
    }
}
