package com.volt.flowstudio.model

import org.json.JSONArray
import org.json.JSONObject

/** 一条轨道。混音参数 + 音色 + 4 个插入效果。 */
data class Track(
    var name: String = "轨道",
    var instrument: Instrument = Instrument.SYNTH,
    var volume: Float = 0.8f,
    var pan: Float = 0f,
    var sendReverb: Float = 0.12f,
    var sendDelay: Float = 0.08f,
    var mute: Boolean = false,
    var solo: Boolean = false,
    var transpose: Int = 0,
    var velocitySense: Float = 0.7f,
    var params: FloatArray = FloatArray(12) { 0.5f },
    val effects: MutableList<Effect> = MutableList(4) { Effect.empty() }
) {
    fun copyTrack(): Track = Track(
        name, instrument, volume, pan, sendReverb, sendDelay, mute, solo,
        transpose, velocitySense, params.copyOf(),
        effects.map { it.copyEffect() }.toMutableList()
    )

    fun activeParams(): FloatArray = params.copyOf()

    fun fxTypes(): IntArray = IntArray(4) { effects.getOrNull(it)?.type?.ordinal ?: 0 }
    fun fxEnabled(): BooleanArray = BooleanArray(4) { effects.getOrNull(it)?.enabled ?: false }
    fun fxMix(): FloatArray = FloatArray(4) { effects.getOrNull(it)?.mix ?: 0f }
    fun fxParams(): FloatArray {
        val out = FloatArray(32) { 0.5f }
        for (i in 0 until 4) {
            val e = effects.getOrNull(i) ?: continue
            for (k in 0 until 8) out[i * 8 + k] = e.params[k]
        }
        return out
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("inst", instrument.name)
        put("vol", volume.toDouble())
        put("pan", pan.toDouble())
        put("sRev", sendReverb.toDouble())
        put("sDly", sendDelay.toDouble())
        put("mute", mute)
        put("solo", solo)
        put("transpose", transpose)
        put("velSense", velocitySense.toDouble())
        put("params", JSONArray(params.map { it.toDouble() }))
        put("inserts", JSONArray().apply { effects.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Track {
            val inst = runCatching { Instrument.valueOf(o.optString("inst", "SYNTH")) }
                .getOrDefault(Instrument.SYNTH)
            val p = FloatArray(12) { 0.5f }
            o.optJSONArray("params")?.let { arr ->
                for (i in 0 until minOf(12, arr.length())) p[i] = arr.optDouble(i, 0.5).toFloat()
            }
            val fx = MutableList(4) { Effect.empty() }
            o.optJSONArray("inserts")?.let { arr ->
                for (i in 0 until minOf(4, arr.length())) {
                    arr.optJSONObject(i)?.let { fx[i] = Effect.fromJson(it) }
                }
            }
            return Track(
                name = o.optString("name", "轨道"),
                instrument = inst,
                volume = o.optDouble("vol", 0.8).toFloat(),
                pan = o.optDouble("pan", 0.0).toFloat(),
                sendReverb = o.optDouble("sRev", 0.12).toFloat(),
                sendDelay = o.optDouble("sDly", 0.08).toFloat(),
                mute = o.optBoolean("mute", false),
                solo = o.optBoolean("solo", false),
                transpose = o.optInt("transpose", 0),
                velocitySense = o.optDouble("velSense", 0.7).toFloat(),
                params = p,
                effects = fx
            )
        }

        /** 新建轨道时按乐器给一组能听的出厂音色。 */
        fun preset(inst: Instrument, name: String): Track {
            val t = Track(name = name, instrument = inst)
            val p = FloatArray(12) { 0.5f }
            when (inst) {
                Instrument.SYNTH -> {
                    p[0] = 0.5f; p[1] = 0.5f; p[2] = 0.55f; p[3] = 0.2f
                    p[4] = 0.25f; p[5] = 0.45f; p[6] = 0.6f; p[7] = 0.3f
                    p[8] = 0.7f; p[9] = 0.25f; p[10] = 0.4f; p[11] = 0.5f
                }
                Instrument.BASS -> {
                    p[0] = 0.7f; p[2] = 0.12f; p[4] = 0.02f; p[5] = 0.5f
                    p[6] = 0.35f; p[7] = 0.12f; p[8] = 0.3f; p[9] = 0.45f; p[10] = 0.75f
                }
                Instrument.DRUM -> {
                    p[0] = 0.5f; p[1] = 0.35f; p[2] = 0.6f; p[3] = 0.4f; p[4] = 0.35f
                }
                Instrument.BELL -> {
                    p[0] = 0.5f; p[1] = 0.5f; p[2] = 0.5f; p[3] = 0.5f
                }
                Instrument.EPIANO -> {
                    p[0] = 0.5f; p[1] = 0.4f; p[2] = 0.5f; p[3] = 0.5f; p[4] = 0.3f
                }
                Instrument.SAMPLER -> {
                    p[0] = 0.0f; p[1] = 1.0f; p[2] = 0.02f; p[3] = 0.8f
                    p[4] = 0f; p[5] = 0.5f; p[6] = 0.5f
                }
            }
            t.params = p
            return t
        }
    }
}
