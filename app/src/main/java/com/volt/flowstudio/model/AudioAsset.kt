package com.volt.flowstudio.model

import org.json.JSONObject

/**
 * 一段导入到工程里的音频素材。
 *
 * 素材是"文件"，片段是"素材在时间线上的摆放"。同一个素材可以被摆很多次、
 * 裁成不同长度、放到不同轨道 —— 这是 FL 播放列表的基本单位，
 * 也是之前"一条全局伴奏轨"做不到的事。
 *
 * 音频本体存在 filesDir/audio/ 下，这里只记引用与元数据，
 * 工程文件保持几十 KB 而不是几十 MB。
 */
data class AudioAsset(
    var id: Int = 1,
    /** 原始文件名，界面上就显示它 —— FL 里片段标题也是文件名。 */
    var name: String = "",
    /** 相对 filesDir 的路径，如 audio/1234567_drumloop.wav */
    var path: String = "",
    var frames: Int = 0,
    var sampleRate: Int = 48000,
    /** 根音用于变速不变调（暂未启用，保留字段便于后续 warp）。 */
    var rootNote: Int = 60
) {
    val durationSec: Float
        get() = if (sampleRate > 0) frames.toFloat() / sampleRate else 0f

    /** 在当前 BPM 下这段素材占多少 tick。 */
    fun lengthTicks(bpm: Float): Int =
        (durationSec.toDouble() * bpm / 60.0 * Note.PPQ).toInt().coerceAtLeast(Note.STEP)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("path", path)
        put("frames", frames)
        put("sr", sampleRate)
        if (rootNote != 60) put("root", rootNote)
    }

    companion object {
        fun fromJson(o: JSONObject) = AudioAsset(
            id = o.optInt("id", 1),
            name = o.optString("name", ""),
            path = o.optString("path", ""),
            frames = o.optInt("frames", 0),
            sampleRate = o.optInt("sr", 48000),
            rootNote = o.optInt("root", 60)
        )
    }
}
