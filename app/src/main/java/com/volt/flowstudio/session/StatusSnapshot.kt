package com.volt.flowstudio.session

import com.volt.flowstudio.NativeLib
import com.volt.flowstudio.audio.NativeEngine
import com.volt.flowstudio.model.EffectType
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Project
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 全量状态快照。
 *
 * 做的事：把"软件现在是什么样"一次说清——不只是工程内容，
 * 还包括那些**决定有没有声音**的东西：音频流起没起、渲染了多少帧、
 * 峰值多大、用的哪个后端、桥接通不通、导出目录在哪。
 *
 * 为什么需要它：用户说"没声音"，光看工程数据是看不出来的。
 * 工程里音符齐全、轨道齐全、播放位置在走，但音频流根本没启动——
 * 这三件事在旧接口里分不清，只能靠猜。这个快照把它们分开列。
 *
 * 三种粒度，按需取：
 *   summary()  —— 几百字节，够插件判断"该不该继续"，适合高频调用
 *   full()     —— 全部内容含每个音符，适合人工排查或完整同步
 *   health()   —— 只关心"哪里坏了"，给诊断用
 */
class StatusSnapshot(
    private val session: com.volt.flowstudio.session.StudioSession,
    private val context: android.content.Context
) {

    companion object {
        /** 快照格式版本。改了字段结构就加一，插件据此兼容。 */
        const val FORMAT = 2
    }

    // ---------------------------------------------------------------- 摘要

    /**
     * 精简快照。高频调用（比如每帧刷新 UI 或轮询）用这个。
     * 目标：足够判断状态，又不至于让 JSON 序列化成为开销。
     */
    fun summary(): JSONObject {
        val s = session
        val p = s.project
        val engineOn = s.engine.available
        val audioOn = engineOn && s.engine.isAudioRunning()
        val st = if (engineOn) s.engine.stats() else NativeEngine.Stats(0, 0f, 0)

        return JSONObject()
            .put("format", FORMAT)
            .put("revision", s.bridge?.revision ?: 0)
            .put("time", System.currentTimeMillis())

            // ---- 声学状态：这三个字段合起来能判断"为什么没声音" ----
            .put("audio", JSONObject()
                .put("engineAvailable", engineOn)
                .put("streamRunning", audioOn)
                .put("sampleRate", if (engineOn) s.engine.sampleRate().toInt() else 0)
                .put("framesRendered", st.framesRendered)
                .put("peakEver", st.peakEver.toDouble())
                .put("callbacks", st.callbacks)
                // 一句话结论，省得调用方自己拼判断逻辑
                .put("verdict", audioVerdict(engineOn, audioOn, st.framesRendered, st.peakEver)))

            .put("transport", JSONObject()
                .put("playing", s.isPlaying())
                .put("positionTicks", s.positionTicks())
                .put("bar", s.positionTicks() / Note.BAR + 1)
                .put("bpm", p.bpm.toDouble()))

            .put("project", JSONObject()
                .put("name", p.name)
                .put("tracks", p.tracks.size)
                .put("patterns", p.patterns.size)
                .put("clips", p.clips.size)
                .put("lengthBars", p.lengthBars())
                .put("swing", p.swing.toDouble())
                .put("currentPattern", p.currentPattern)
                .put("selectedTrack", p.selectedTrack))

            .put("bridge", JSONObject()
                .put("running", s.bridge?.running ?: false)
                .put("port", s.bridge?.port ?: 0)
                .put("subscribers", s.bridge?.subscriberCount() ?: 0))
    }

    /** 把"为什么没声音"翻译成一句人能直接看懂的话 */
    private fun audioVerdict(engineOn: Boolean, streamRunning: Boolean,
                             frames: Long, peak: Float): String = when {
        !engineOn -> "音频引擎不可用（原生库没加载成功）"
        !streamRunning -> "音频流没启动（点了播放才会启动；启动失败会退回 OpenSL）"
        frames == 0L -> "音频流在跑但一帧都没渲染（回调没被调用）"
        peak < 0.0001f -> "渲染正常但输出全静音（检查轨道是否够响、是否被静音/独奏截断）"
        else -> "正常出声（峰值 ${"%.3f".format(peak)}）"
    }

    // ---------------------------------------------------------------- 全量

    /**
     * 全量快照。含每个样式、每条轨道、每个片段、每个音符。
     * 这是"随时查看软件所有状态"的完整答案。
     */
    fun full(): JSONObject {
        val base = summary()
        val s = session
        val p = s.project
        // 电平表一次取完，避免逐轨道调 JNI
        val levels: IntArray = runCatching {
            if (s.engine.available) s.engine.trackLevels() else IntArray(0)
        }.getOrDefault(IntArray(0))

        base.put("tracks", JSONArray().apply {
            p.tracks.forEachIndexed { i, t ->
                put(JSONObject()
                    .put("index", i)
                    .put("name", t.name)
                    .put("instrument", t.instrument.name)
                    .put("instrumentCn", t.instrument.cn)
                    .put("volume", t.volume.toDouble())
                    .put("pan", t.pan.toDouble())
                    .put("mute", t.mute)
                    .put("solo", t.solo)
                    .put("transpose", t.transpose)
                    .put("sendReverb", t.sendReverb.toDouble())
                    .put("sendDelay", t.sendDelay.toDouble())
                    .put("params", JSONArray().apply { t.params.forEach { put(it.toDouble()) } })
                    .put("effects", JSONArray().apply {
                        t.effects.forEachIndexed { k, e ->
                            if (e.type != EffectType.NONE) {
                                put(JSONObject()
                                    .put("slot", k)
                                    .put("type", e.type.name)
                                    .put("typeCn", e.type.cn)
                                    .put("enabled", e.enabled)
                                    .put("mix", e.mix.toDouble()))
                            }
                        }
                    })
                    .put("level", (levels.getOrElse(i) { 0 }).coerceIn(0, 1000) / 1000.0))
            }
        })

        base.put("patterns", JSONArray().apply {
            p.patterns.forEach { pat ->
                put(JSONObject()
                    .put("id", pat.id)
                    .put("name", pat.name)
                    .put("bars", pat.bars)
                    .put("noteCount", pat.noteCount())
                    .put("tracks", JSONArray().apply {
                        pat.tracks.forEach { (trk, notes) ->
                            if (notes.isEmpty()) return@forEach
                            put(JSONObject()
                                .put("track", trk)
                                .put("count", notes.size)
                                .put("notes", JSONArray().apply {
                                    notes.forEach { n ->
                                        put(JSONObject()
                                            .put("pitch", n.pitch)
                                            .put("start", n.start)
                                            .put("length", n.length)
                                            .put("velocity", n.velocity)
                                            .put("fine", n.fine))
                                    }
                                }))
                        }
                    }))
            }
        })

        base.put("clips", JSONArray().apply {
            p.clips.forEach { c ->
                put(JSONObject()
                    .put("id", c.id)
                    .put("patternId", c.patternId)
                    .put("track", c.track)
                    .put("start", c.start)
                    .put("length", c.length)
                    .put("offset", c.offset))
            }
        })

        base.put("automation", JSONArray().apply {
            p.automations.forEach { a ->
                if (a.points.isEmpty()) return@forEach
                put(JSONObject()
                    .put("track", a.track)
                    .put("target", a.target.name)
                    .put("targetCn", a.target.cn)
                    .put("enabled", a.enabled)
                    .put("points", JSONArray().apply {
                        a.points.forEach { pt ->
                            put(JSONObject().put("tick", pt.tick).put("value", pt.value.toDouble()))
                        }
                    }))
            }
        })

        base.put("loop", JSONObject()
            .put("on", p.loopOn)
            .put("start", p.loopStart)
            .put("end", p.loopEnd))

        base.put("master", JSONObject()
            .put("volume", p.masterVolume)
            .put("limiter", p.masterLimiter)
            .put("softClip", p.masterSoftClip)
            .put("gainDb", p.masterGainDb.toDouble()))

        base.put("files", JSONObject()
            .put("project", s.currentFile?.absolutePath ?: JSONObject.NULL)
            .put("exportDir", s.exportDirHint())
            .put("projectCount", s.listProjects().size))

        return base
    }

    // ---------------------------------------------------------------- 体检

    /**
     * 只回答"哪里坏了"。
     * 每一项都是 {ok: bool, detail: string}，插件可以只挑不 ok 的报给用户。
     */
    fun health(): JSONObject {
        val s = session
        val p = s.project
        val engineOn = s.engine.available
        val audioOn = engineOn && s.engine.isAudioRunning()
        val st = if (engineOn) s.engine.stats() else NativeEngine.Stats(0, 0f, 0)

        val noteTotal = p.patterns.sumOf { it.noteCount() }
        val clipTotal = p.clips.size

        return JSONObject()
            .put("format", FORMAT)
            .put("time", System.currentTimeMillis())
            .put("checks", JSONArray().apply {
                put(check("nativeLib", NativeLib.loaded, NativeLib.status))
                put(check("engine", engineOn,
                    if (engineOn) "引擎已创建" else "引擎没创建，原生库或初始化失败"))
                put(check("audioStream", audioOn,
                    if (audioOn) "音频流运行中 @ ${s.engine.sampleRate().toInt()}Hz"
                    else "音频流未启动（AAudio 和 OpenSL 都没起来）"))
                put(check("render", st.framesRendered > 0,
                    "已渲染 ${st.framesRendered} 帧，${st.callbacks} 次回调"))
                put(check("signal", st.peakEver > 0.0001f,
                    if (st.peakEver > 0.0001f) "峰值 ${"%.3f".format(st.peakEver)}"
                    else "从未输出过有效信号"))
                put(check("tracks", p.tracks.isNotEmpty(), "${p.tracks.size} 条轨道"))
                put(check("notes", noteTotal > 0, "${p.patterns.size} 个样式共 $noteTotal 个音符"))
                put(check("clips", clipTotal > 0, "$clipTotal 个片段"))
                put(check("audibleTracks",
                    p.tracks.any { s.project.audible(p.tracks.indexOf(it)) },
                    if (p.anySolo()) "有轨道独奏，其余被静默" else "没有独奏"))
                put(check("bridge", s.bridge?.running == true,
                    if (s.bridge?.running == true) "端口 ${s.bridge?.port}"
                    else "桥接没起来（插件连不上）"))
                put(check("exportDir",
                    runCatching { File(s.exportDirHint()).canWrite() }.getOrDefault(false) ||
                        s.exportDirHint().startsWith("Music"),
                    "导出目录 ${s.exportDirHint()}"))
            })
            .put("noteCount", noteTotal)
            .put("clipCount", clipTotal)
    }

    private fun check(name: String, ok: Boolean, detail: String): JSONObject =
        JSONObject().put("name", name).put("ok", ok).put("detail", detail)

    /** 人能直接读的一页状态，诊断面板和插件都显示这个 */
    fun humanReadable(): String {
        val s = session
        val p = s.project
        val h = health()
        val checks = h.optJSONArray("checks") ?: JSONArray()

        val sb = StringBuilder()
        sb.appendLine("═══ FlowStudio ${s.versionName()} 状态 ═══")
        sb.appendLine()

        sb.appendLine("【声学链路】")
        for (i in 0 until checks.length()) {
            val c = checks.optJSONObject(i) ?: continue
            val name = c.optString("name")
            if (name !in listOf("nativeLib", "engine", "audioStream", "render", "signal")) continue
            sb.appendLine("  ${if (c.optBoolean("ok")) "✓" else "✗"} $name: ${c.optString("detail")}")
        }
        sb.appendLine()

        sb.appendLine("【工程】")
        sb.appendLine("  《${p.name}》 ${p.lengthBars()} 小节 @ ${p.bpm} BPM")
        sb.appendLine("  ${p.tracks.size} 轨道 · ${p.patterns.size} 样式 · ${p.clips.size} 片段")
        sb.appendLine("  摇摆 ${(p.swing * 100).toInt()}%  母带 ${p.masterVolume}%" +
            "  ${if (p.masterLimiter) "限幅开" else "限幅关"}")
        sb.appendLine()

        sb.appendLine("【轨道】")
        val levels = runCatching {
            if (s.engine.available) s.engine.trackLevels() else IntArray(0)
        }.getOrDefault(IntArray(0))
        p.tracks.forEachIndexed { i, t ->
            val lvl = (levels.getOrElse(i) { 0 }).coerceIn(0, 1000) / 1000f
            val fx = t.effects.filter { it.type != EffectType.NONE }.joinToString("+") { it.type.cn }
            sb.appendLine("  [$i] ${t.name.padEnd(8)} ${t.instrument.cn.padEnd(4)}" +
                " 音${(t.volume * 100).toInt()}%" +
                " 像${(t.pan * 100).toInt()}" +
                (if (t.mute) " 静音" else "") +
                (if (t.solo) " 独奏" else "") +
                (if (fx.isNotEmpty()) " fx:$fx" else "") +
                " 电平${"%.2f".format(lvl)}")
        }
        sb.appendLine()

        sb.appendLine("【样式】")
        p.patterns.forEach { pat ->
            sb.appendLine("  [${pat.id}] ${pat.name} · ${pat.bars} 小节 · ${pat.noteCount()} 音符")
        }
        sb.appendLine()

        sb.appendLine("【其它】")
        sb.appendLine("  桥接: ${if (s.bridge?.running == true) "运行中 :${s.bridge?.port} 订阅${s.bridge?.subscriberCount()}" else "未启动"}")
        sb.appendLine("  导出: ${s.exportDirHint()}")
        sb.appendLine("  项目: ${s.listProjects().size} 个存档")
        return sb.toString()
    }
}
