package com.volt.flowstudio.model

import org.json.JSONArray
import org.json.JSONObject

/** 整个工程。序列化用 org.json，存档就是一个 .flow 文本文件。 */
class Project {
    var name: String = "新工程"
    var bpm: Float = 128f
    var beatsPerBar: Int = 4

    /** 摇摆量 0..1，0 是正拍。0.5 大约是三连音的摇摆感。 */
    var swing: Float = 0f

    /** 节拍器音量 0..1 */
    var metronomeVolume: Float = 0.6f

    val tracks = mutableListOf<Track>()
    val patterns = mutableListOf<Pattern>()
    val clips = mutableListOf<Clip>()
    val automations = mutableListOf<Automation>()

    var masterVolume: Int = 80            // 0..100
    var masterLimiter: Boolean = true
    var masterSoftClip: Boolean = true
    var masterGainDb: Float = 0f

    var nextPatternId: Int = 1
    var nextClipId: Int = 1

    var currentPattern: Int = 0
    var selectedTrack: Int = 0
    var zoom: Float = 1f

    var loopOn: Boolean = false
    var loopStart: Int = 0
    var loopEnd: Int = Note.BAR * 8

    // ---- 查询 ----
    fun patternAt(index: Int): Pattern? = patterns.getOrNull(index)
    fun patternById(id: Int): Pattern? = patterns.firstOrNull { it.id == id }
    fun patternIndexOf(id: Int): Int = patterns.indexOfFirst { it.id == id }
    fun trackAt(i: Int): Track? = tracks.getOrNull(i)

    fun lengthTicks(): Int = maxOf(Note.BAR * 16, clips.maxOfOrNull { it.end } ?: 0)
    fun lengthBars(): Int = (lengthTicks() + Note.BAR - 1) / Note.BAR

    fun anySolo(): Boolean = tracks.any { it.solo }

    fun audible(trackIndex: Int): Boolean {
        val t = trackAt(trackIndex) ?: return false
        if (t.mute) return false
        return if (anySolo()) t.solo else true
    }

    fun automation(track: Int, target: Automation.Target): Automation? =
        automations.firstOrNull { it.track == track && it.target == target }

    fun automationOrCreate(track: Int, target: Automation.Target): Automation {
        automation(track, target)?.let { return it }
        val a = Automation(track, target)
        automations.add(a)
        return a
    }

    fun newPattern(): Pattern {
        val p = Pattern(nextPatternId++, "样式 ${patterns.size}", 2)
        patterns.add(p)
        return p
    }

    fun addTrack(inst: Instrument, name: String): Int {
        val base = name.ifBlank { inst.cn }
        var candidate = base
        var n = 2
        while (tracks.any { it.name == candidate }) {
            candidate = "$base $n"
            n++
        }
        tracks.add(Track.preset(inst, candidate))
        return tracks.size - 1
    }

    fun maxClipLength(track: Int, startTick: Int, ignoreId: Int = -1): Int {
        var end = Note.BAR * 64
        clips.filter { it.track == track && it.id != ignoreId && it.start >= startTick }
            .forEach { end = minOf(end, it.start) }
        return end - startTick
    }

    fun overlapClip(track: Int, start: Int, end: Int, ignoreId: Int = -1): Clip? =
        clips.firstOrNull { it.track == track && it.id != ignoreId && it.start < end && it.end > start }

    // ---- 播放快照：把整首歌拍平成事件表交给引擎 ----
    fun buildSchedule(): IntArray {
        val out = ArrayList<Int>(1024)
        for (clip in clips) {
            val pat = patternById(clip.patternId) ?: continue
            for ((trackIndex, notes) in pat.tracks) {
                if (trackIndex != clip.track) continue
                for (n in notes) {
                    val absStart = clip.start + n.start - clip.offset
                    val absEnd = absStart + n.length
                    if (absEnd <= clip.start || absStart >= clip.end) continue
                    val clampStart = maxOf(absStart, clip.start)
                    val clampEnd = minOf(absEnd, clip.end)
                    if (clampEnd <= clampStart) continue
                    // 摇摆：把落在奇数个 16 分音符上的音往后推。
                    // 推的量由 swing 决定，最大半个 16 分音符（=三连音的感觉）。
                    val swingShift = if (swing > 0.001f && (clampStart / Note.STEP) % 2 == 1) {
                        (swing * Note.STEP * 0.5f).toInt()
                    } else 0
                    val shifted = clampStart + swingShift + n.fine
                    out.add(maxOf(0, shifted))
                    out.add(clampEnd + swingShift + n.fine)
                    out.add(n.pitch)
                    out.add(n.velocity)
                    out.add(trackIndex)
                }
            }
        }
        val arr = IntArray(out.size)
        for (i in out.indices) arr[i] = out[i]
        return arr
    }

    fun buildAutomation(): Triple<IntArray, IntArray, FloatArray> {
        val meta = ArrayList<Int>()
        val ticks = ArrayList<Int>()
        val vals = ArrayList<Float>()
        for (a in automations) {
            if (a.points.isEmpty()) continue
            meta.add(a.track)
            meta.add(a.target.ordinal)
            meta.add(a.points.size)
            for (p in a.points) {
                ticks.add(p.tick)
                vals.add(p.value)
            }
        }
        return Triple(
            meta.toIntArray(),
            ticks.toIntArray(),
            FloatArray(vals.size) { vals[it] }
        )
    }

    // ---- 序列化 ----
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", 1)
        put("name", name)
        put("bpm", bpm.toDouble())
        put("beatsPerBar", beatsPerBar)
        put("swing", swing.toDouble())
        put("metronomeVolume", metronomeVolume.toDouble())
        put("masterVolume", masterVolume)
        put("masterLimiter", masterLimiter)
        put("masterSoftClip", masterSoftClip)
        put("masterGainDb", masterGainDb.toDouble())
        put("nextPatternId", nextPatternId)
        put("nextClipId", nextClipId)
        put("currentPattern", currentPattern)
        put("selectedTrack", selectedTrack)
        put("zoom", zoom.toDouble())
        put("loopOn", loopOn)
        put("loopStart", loopStart)
        put("loopEnd", loopEnd)
        put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
        put("patterns", JSONArray().apply { patterns.forEach { put(it.toJson()) } })
        put("clips", JSONArray().apply { clips.forEach { put(it.toJson()) } })
        put("automation", JSONArray().apply { automations.forEach { put(it.toJson()) } })
    }

    fun toText(): String = toJson().toString()

    companion object {
        fun fromJson(o: JSONObject): Project {
            val p = Project()
            p.name = o.optString("name", "工程")
            p.bpm = o.optDouble("bpm", 128.0).toFloat().coerceIn(20f, 300f)
            p.beatsPerBar = o.optInt("beatsPerBar", 4)
            p.swing = o.optDouble("swing", 0.0).toFloat().coerceIn(0f, 1f)
            p.metronomeVolume = o.optDouble("metronomeVolume", 0.6).toFloat().coerceIn(0f, 1f)
            p.masterVolume = o.optInt("masterVolume", 80).coerceIn(0, 100)
            p.masterLimiter = o.optBoolean("masterLimiter", true)
            p.masterSoftClip = o.optBoolean("masterSoftClip", true)
            p.masterGainDb = o.optDouble("masterGainDb", 0.0).toFloat()
            p.nextPatternId = o.optInt("nextPatternId", 1)
            p.nextClipId = o.optInt("nextClipId", 1)
            p.currentPattern = o.optInt("currentPattern", 0)
            p.selectedTrack = o.optInt("selectedTrack", 0)
            p.zoom = o.optDouble("zoom", 1.0).toFloat()
            p.loopOn = o.optBoolean("loopOn", false)
            p.loopStart = o.optInt("loopStart", 0)
            p.loopEnd = o.optInt("loopEnd", Note.BAR * 8)

            o.optJSONArray("tracks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { p.tracks.add(Track.fromJson(it)) }
                }
            }
            if (p.tracks.isEmpty()) p.tracks.add(Track.preset(Instrument.SYNTH, "合成器"))

            o.optJSONArray("patterns")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let {
                        val pat = Pattern.fromJson(it)
                        p.patterns.add(pat)
                        p.nextPatternId = maxOf(p.nextPatternId, pat.id + 1)
                    }
                }
            }
            if (p.patterns.isEmpty()) p.newPattern()

            o.optJSONArray("clips")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let {
                        val c = Clip.fromJson(it)
                        p.clips.add(c)
                        p.nextClipId = maxOf(p.nextClipId, c.id + 1)
                    }
                }
            }

            o.optJSONArray("automation")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { p.automations.add(Automation.fromJson(it)) }
                }
            }

            if (p.currentPattern !in p.patterns.indices) p.currentPattern = 0
            if (p.selectedTrack !in p.tracks.indices) p.selectedTrack = 0
            return p
        }

        fun fromText(text: String): Project? = runCatching {
            fromJson(JSONObject(text))
        }.getOrNull()

        /** 出厂示例工程：一个能直接播的 124 BPM house 循环。 */
        fun demo(): Project {
            val p = Project()
            p.name = "Demo House"
            p.bpm = 124f
            p.swing = 0f
            p.tracks.addAll(
                listOf(
                    Track.preset(Instrument.DRUM, "Kick").apply {
                        params[0] = 0.28f; params[1] = 0.28f; params[2] = 0.22f; params[3] = 0.34f
                        volume = 0.95f
                    },
                    Track.preset(Instrument.DRUM, "Clap").apply {
                        params[0] = 0.54f; params[1] = 0.30f; params[2] = 0.90f; params[3] = 0.62f
                        volume = 0.60f; pan = 0.08f
                    },
                    Track.preset(Instrument.DRUM, "Hat").apply {
                        params[0] = 0.86f; params[1] = 0.12f; params[2] = 0.75f; params[3] = 0.90f
                        volume = 0.42f; pan = -0.18f
                    },
                    Track.preset(Instrument.BASS, "Bass").apply { volume = 0.75f },
                    Track.preset(Instrument.SYNTH, "Pad").apply {
                        params[4] = 0.55f; params[7] = 0.55f; params[8] = 0.62f
                        volume = 0.40f; sendReverb = 0.35f
                        effects[0] = Effect(
                            type = EffectType.DELAY,
                            enabled = true,
                            mix = 0.30f,
                            params = EffectType.DELAY.defaults().also { it[0] = 0.5f }
                        )
                    },
                    Track.preset(Instrument.SYNTH, "Lead").apply {
                        volume = 0.55f; sendDelay = 0.22f
                    },
                    Track.preset(Instrument.EPIANO, "Keys").apply {
                        volume = 0.50f; sendReverb = 0.30f
                    }
                )
            )

            // 鼓 A
            val pat1 = Pattern(p.nextPatternId++, "鼓 A", 2)
            val kick = pat1.notes(0)
            for (bar in 0 until 2) {
                val b = bar * Note.BAR
                kick.add(Note(36, b, Note.STEP, 118))
                kick.add(Note(36, b + Note.PPQ, Note.STEP, 96))
                kick.add(Note(36, b + Note.PPQ * 2, Note.STEP, 110))
                kick.add(Note(36, b + Note.PPQ * 3, Note.STEP, 96))
            }
            val clap = pat1.notes(1)
            for (bar in 0 until 2) {
                val b = bar * Note.BAR
                clap.add(Note(39, b + Note.PPQ, Note.STEP, 100))
                clap.add(Note(39, b + Note.PPQ * 3, Note.STEP, 100))
            }
            val hat = pat1.notes(2)
            for (i in 0 until 16) {
                hat.add(Note(42, i * Note.STEP, Note.STEP, if (i % 4 == 2) 88 else 58))
            }
            p.patterns.add(pat1)

            // 贝斯 A
            val pat2 = Pattern(p.nextPatternId++, "贝斯 A", 2)
            val bass = pat2.notes(3)
            val roots = intArrayOf(36, 36, 43, 41)
            for (i in 0 until 8) {
                bass.add(Note(roots[i / 2], i * Note.PPQ, Note.PPQ - 40, if (i % 2 == 0) 112 else 92))
            }
            p.patterns.add(pat2)

            // 和弦
            val pat3 = Pattern(p.nextPatternId++, "和弦", 2)
            val chords = arrayOf(
                intArrayOf(60, 64, 67, 71),
                intArrayOf(57, 60, 64, 67),
                intArrayOf(55, 59, 62, 65),
                intArrayOf(53, 57, 60, 64)
            )
            val pad = pat3.notes(4)
            chords.forEachIndexed { i, chord ->
                chord.forEach { n -> pad.add(Note(n, i * Note.PPQ, Note.PPQ - 30, 78)) }
            }
            val lead = pat3.notes(5)
            intArrayOf(72, 71, 67, 64, 67, 71, 72, 74).forEachIndexed { i, n ->
                lead.add(Note(n, i * (Note.PPQ / 2), Note.PPQ / 2 - 20, 96))
            }
            val keys = pat3.notes(6)
            chords.forEachIndexed { i, chord ->
                chord.forEach { n -> keys.add(Note(n + 12, i * Note.PPQ, Note.PPQ / 2, 62)) }
            }
            p.patterns.add(pat3)

            // 播放列表
            for (rep in 0 until 4) {
                val base = rep * Note.BAR * 2
                for (trk in 0..2) {
                    p.clips.add(Clip(p.nextClipId++, 1, trk, base, Note.BAR * 2, 0))
                }
            }
            for (rep in 2 until 4) {
                val base = rep * Note.BAR * 2
                p.clips.add(Clip(p.nextClipId++, 2, 3, base, Note.BAR * 2, 0))
                for (trk in 4..6) {
                    p.clips.add(Clip(p.nextClipId++, 3, trk, base, Note.BAR * 2, 0))
                }
            }

            // 自动化：Pad 淡入 + Bass 滤波推动
            p.automationOrCreate(4, Automation.Target.VOLUME).apply {
                addPoint(0, 0.15f)
                addPoint(Note.BAR * 2, 0.55f)
                addPoint(Note.BAR * 4, 0.90f)
                addPoint(Note.BAR * 8, 0.60f)
            }
            p.automationOrCreate(3, Automation.Target.PARAM0).apply {
                addPoint(0, 0.20f)
                addPoint(Note.BAR * 4, 0.85f)
            }

            p.loopStart = 0
            p.loopEnd = Note.BAR * 8
            return p
        }
    }
}
