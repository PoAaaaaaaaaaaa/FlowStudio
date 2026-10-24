package com.volt.flowstudio.model

import org.json.JSONObject

/** 播放列表上的一个片段。 */
data class Clip(
    var id: Int = 1,
    var patternId: Int = 1,
    var track: Int = 0,
    var start: Int = 0,
    var length: Int = Note.BAR * 2,
    var offset: Int = 0,
    var label: String = ""
) {
    val end: Int get() = start + length

    fun copyClip(newId: Int) = Clip(newId, patternId, track, start, length, offset, label)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("pat", patternId); put("trk", track)
        put("start", start); put("len", length); put("off", offset)
        if (label.isNotEmpty()) put("label", label)
    }

    companion object {
        fun fromJson(o: JSONObject) = Clip(
            id = o.optInt("id", 1),
            patternId = o.optInt("pat", 1),
            track = o.optInt("trk", 0),
            start = o.optInt("start", 0),
            length = o.optInt("len", Note.BAR * 2).coerceAtLeast(Note.STEP),
            offset = o.optInt("off", 0),
            label = o.optString("label", "")
        )
    }
}
