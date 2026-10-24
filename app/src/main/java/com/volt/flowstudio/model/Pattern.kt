package com.volt.flowstudio.model

import org.json.JSONArray
import org.json.JSONObject

/** 一个样式（Pattern）。每条轨道在这里存自己那段音符。 */
data class Pattern(
    var id: Int = 1,
    var name: String = "样式",
    var bars: Int = 2,
    /** trackIndex -> 音符表 */
    val tracks: MutableMap<Int, MutableList<Note>> = mutableMapOf()
) {
    val lengthTicks: Int get() = bars * Note.BAR

    fun notes(track: Int): MutableList<Note> = tracks.getOrPut(track) { mutableListOf() }

    fun notesOrNull(track: Int): MutableList<Note>? = tracks[track]

    fun isTrackEmpty(track: Int): Boolean = tracks[track]?.isEmpty() ?: true

    fun isEmpty(): Boolean = tracks.values.all { it.isEmpty() }

    fun noteCount(): Int = tracks.values.sumOf { it.size }

    fun copyPattern(newId: Int): Pattern {
        val p = Pattern(newId, name, bars)
        tracks.forEach { (k, v) -> p.tracks[k] = v.map { it.copyNote() }.toMutableList() }
        return p
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("bars", bars)
        put("tracks", JSONArray().apply {
            tracks.forEach { (trackIndex, notes) ->
                if (notes.isEmpty()) return@forEach
                val enc = IntArray(notes.size * 4)
                notes.forEachIndexed { i, n ->
                    enc[i * 4] = n.pitch
                    enc[i * 4 + 1] = n.start
                    enc[i * 4 + 2] = n.length
                    enc[i * 4 + 3] = n.velocity
                }
                put(JSONObject().apply {
                    put("track", trackIndex)
                    put("n", JSONArray(enc.map { it }))
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Pattern {
            val p = Pattern(
                id = o.optInt("id", 1),
                name = o.optString("name", "样式"),
                bars = o.optInt("bars", 2).coerceIn(1, 64)
            )
            o.optJSONArray("tracks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val trackIndex = t.optInt("track", 0)
                    val encArr = t.optJSONArray("n") ?: continue
                    val list = p.notes(trackIndex)
                    var k = 0
                    while (k + 3 < encArr.length()) {
                        list.add(
                            Note(
                                pitch = encArr.optInt(k, 60),
                                start = encArr.optInt(k + 1, 0),
                                length = encArr.optInt(k + 2, Note.STEP).coerceAtLeast(1),
                                velocity = encArr.optInt(k + 3, 100).coerceIn(1, 127)
                            )
                        )
                        k += 4
                    }
                    list.sortBy { it.start }
                }
            }
            return p
        }
    }
}
