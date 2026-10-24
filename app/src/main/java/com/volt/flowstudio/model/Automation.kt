package com.volt.flowstudio.model

import org.json.JSONArray
import org.json.JSONObject

/** 一条自动化包络。target 的编号与 C++ 侧 Engine::AutoTarget 对齐。 */
data class Automation(
    var track: Int = 0,
    var target: Target = Target.VOLUME,
    var enabled: Boolean = true,
    val points: MutableList<Point> = mutableListOf()
) {
    enum class Target(val cn: String) {
        VOLUME("音量"),
        PAN("声像"),
        SEND_REVERB("混响发送"),
        SEND_DELAY("延迟发送"),
        PARAM0("参数 1"),
        PARAM1("参数 2"),
        PARAM2("参数 3"),
        PARAM3("参数 4"),
        FX0P0("效果1 参数1"),
        FX0P1("效果1 参数2"),
        FX1P0("效果2 参数1"),
        FX1P1("效果2 参数2")
    }

    data class Point(var tick: Int, var value: Float)

    fun addPoint(tick: Int, value: Float) {
        val existing = points.indexOfFirst { it.tick == tick }
        val v = value.coerceIn(0f, 1f)
        if (existing >= 0) points[existing].value = v
        else {
            points.add(Point(tick, v))
            points.sortBy { it.tick }
        }
    }

    fun removeNear(tick: Int, tolerance: Int = Note.STEP / 4) {
        val idx = points.indexOfFirst { kotlin.math.abs(it.tick - tick) <= tolerance }
        if (idx >= 0) points.removeAt(idx)
    }

    fun valueAt(tick: Int, fallback: Float): Float {
        if (!enabled || points.isEmpty()) return fallback
        if (points.size == 1) return points[0].value
        if (tick <= points.first().tick) return points.first().value
        for (i in 1 until points.size) {
            val b = points[i]
            if (tick <= b.tick) {
                val a = points[i - 1]
                val span = (b.tick - a.tick).toFloat()
                if (span <= 0.01f) return b.value
                val k = (tick - a.tick) / span
                return a.value + (b.value - a.value) * k
            }
        }
        return points.last().value
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("track", track)
        put("target", target.name)
        put("on", enabled)
        put("ticks", JSONArray(points.map { it.tick }))
        put("values", JSONArray(points.map { it.value.toDouble() }))
    }

    companion object {
        fun fromJson(o: JSONObject): Automation {
            val t = runCatching { Target.valueOf(o.optString("target", "VOLUME")) }
                .getOrDefault(Target.VOLUME)
            val a = Automation(
                track = o.optInt("track", 0),
                target = t,
                enabled = o.optBoolean("on", true)
            )
            val ticks = o.optJSONArray("ticks")
            val vals = o.optJSONArray("values")
            if (ticks != null && vals != null) {
                for (i in 0 until minOf(ticks.length(), vals.length())) {
                    a.points.add(Point(ticks.optInt(i, 0), vals.optDouble(i, 0.0).toFloat()))
                }
                a.points.sortBy { it.tick }
            }
            return a
        }
    }
}
