package com.volt.flowstudio.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.volt.flowstudio.model.Automation
import com.volt.flowstudio.model.Clip
import com.volt.flowstudio.model.Effect
import com.volt.flowstudio.model.EffectType
import com.volt.flowstudio.model.Instrument
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Pattern
import com.volt.flowstudio.model.Project
import com.volt.flowstudio.model.Track
import com.volt.flowstudio.session.StudioSession

/**
 * 曲风起步：把一种音乐类型的"编制 + 鼓型 + 和声 + 结构"一次铺进工程。
 *
 * 这不是模板替换——它只在当前工程上追加新轨道与样式，
 * 已有的轨道、工程名和用户手写内容都不动。
 *
 * 曲风数据与插件端 lib/genre.js 保持同一套音乐学约定：
 * 鼓音高 36/38/42，一小节 16 格，4/4。
 */
object GenreStarter {

    data class Blueprint(
        val key: String,
        val name: String,
        val bpm: Int,
        val swing: Float,
        val description: String,
        /** 轨道编制：名称、乐器、编组、音量 */
        val tracks: List<Triple<String, Instrument, String>>,
        val drumPattern: String,
        val chords: List<IntArray>,
        val bars: Int
    )

    private val BLUEPRINTS = listOf(
        Blueprint(
            "house", "House / 4x4", 124, 0f,
            "四踩底鼓、反拍贝斯、长音铺底",
            listOf(
                Triple("Kick", Instrument.DRUM, "drums"),
                Triple("Clap", Instrument.DRUM, "drums"),
                Triple("Hat", Instrument.DRUM, "drums"),
                Triple("Bass", Instrument.BASS, "low"),
                Triple("Pad", Instrument.SYNTH, "harmony"),
                Triple("Lead", Instrument.SYNTH, "lead")
            ),
            "fourOnFloor",
            listOf(intArrayOf(57, 60, 64, 67), intArrayOf(53, 57, 60, 64), intArrayOf(48, 52, 55, 59), intArrayOf(55, 59, 62, 65)),
            8
        ),
        Blueprint(
            "techno", "Techno", 132, 0f,
            "重复、留白、靠滤波推进",
            listOf(
                Triple("Kick", Instrument.DRUM, "drums"),
                Triple("Hat", Instrument.DRUM, "drums"),
                Triple("Rim", Instrument.DRUM, "drums"),
                Triple("Sub Bass", Instrument.BASS, "low"),
                Triple("Drone", Instrument.SYNTH, "texture")
            ),
            "fourOnFloor",
            listOf(intArrayOf(45, 48, 52), intArrayOf(45, 48, 52), intArrayOf(41, 45, 48), intArrayOf(45, 48, 52)),
            8
        ),
        Blueprint(
            "trap", "Trap / Hip-hop", 140, 0f,
            "808 长音、滚动踩镲、暗色钟琴",
            listOf(
                Triple("808", Instrument.DRUM, "drums"),
                Triple("Snare", Instrument.DRUM, "drums"),
                Triple("Hats", Instrument.DRUM, "drums"),
                Triple("Sub", Instrument.BASS, "low"),
                Triple("Keys", Instrument.EPIANO, "harmony"),
                Triple("Bell", Instrument.BELL, "lead")
            ),
            "trap",
            listOf(intArrayOf(48, 51, 55), intArrayOf(44, 48, 51), intArrayOf(39, 43, 46), intArrayOf(46, 50, 53)),
            8
        ),
        Blueprint(
            "lofi", "Lo-fi Hip-hop", 78, 0.3f,
            "半速鼓、行走贝斯、松散的电钢",
            listOf(
                Triple("Kick", Instrument.DRUM, "drums"),
                Triple("Snare", Instrument.DRUM, "drums"),
                Triple("Vinyl Hat", Instrument.DRUM, "drums"),
                Triple("Bass", Instrument.BASS, "low"),
                Triple("Rhodes", Instrument.EPIANO, "harmony")
            ),
            "halftime",
            listOf(intArrayOf(50, 53, 57, 60), intArrayOf(43, 47, 50, 53), intArrayOf(48, 52, 55, 59), intArrayOf(45, 48, 52, 55)),
            8
        ),
        Blueprint(
            "rock", "Rock / 乐队", 120, 0f,
            "底鼓一三、军鼓二四、双吉他铺宽",
            listOf(
                Triple("Drums", Instrument.DRUM, "drums"),
                Triple("Bass", Instrument.BASS, "low"),
                Triple("Guitar L", Instrument.SYNTH, "guitar"),
                Triple("Guitar R", Instrument.SYNTH, "guitar"),
                Triple("Pad", Instrument.SYNTH, "texture")
            ),
            "rock",
            listOf(intArrayOf(52, 56, 59), intArrayOf(45, 49, 52), intArrayOf(47, 51, 54), intArrayOf(52, 56, 59)),
            8
        ),
        Blueprint(
            "metal", "Metal", 168, 0f,
            "密集低音节奏、失真、双踩感",
            listOf(
                Triple("Drums", Instrument.DRUM, "drums"),
                Triple("Rhythm", Instrument.SYNTH, "guitar"),
                Triple("Lead", Instrument.SYNTH, "guitar"),
                Triple("Bass", Instrument.BASS, "low")
            ),
            "metal",
            listOf(intArrayOf(40, 43, 47), intArrayOf(36, 40, 43), intArrayOf(38, 42, 45), intArrayOf(35, 38, 42)),
            8
        ),
        Blueprint(
            "jazz", "Jazz / Swing", 140, 0.55f,
            "Ride 摇摆、Walking bass、七和弦",
            listOf(
                Triple("Ride", Instrument.DRUM, "drums"),
                Triple("Bass", Instrument.BASS, "low"),
                Triple("Piano", Instrument.EPIANO, "harmony"),
                Triple("Trumpet", Instrument.SYNTH, "lead")
            ),
            "jazz",
            listOf(intArrayOf(50, 53, 57, 60), intArrayOf(43, 47, 50, 53), intArrayOf(48, 52, 55, 59), intArrayOf(45, 49, 52, 55)),
            8
        ),
        Blueprint(
            "reggae", "Reggae / Dub", 80, 0.15f,
            "One drop、反拍、延迟空间",
            listOf(
                Triple("One Drop", Instrument.DRUM, "drums"),
                Triple("Bass", Instrument.BASS, "low"),
                Triple("Skank", Instrument.EPIANO, "harmony"),
                Triple("Delay Lead", Instrument.SYNTH, "lead")
            ),
            "reggae",
            listOf(intArrayOf(43, 47, 50), intArrayOf(43, 47, 50), intArrayOf(38, 42, 45), intArrayOf(43, 47, 50)),
            8
        ),
        Blueprint(
            "latin", "Latin / Clave", 104, 0f,
            "Clave 骨架、Montuno 和声",
            listOf(
                Triple("Clave", Instrument.DRUM, "drums"),
                Triple("Toms", Instrument.DRUM, "drums"),
                Triple("Bass", Instrument.BASS, "low"),
                Triple("Piano", Instrument.EPIANO, "harmony"),
                Triple("Flute", Instrument.BELL, "lead")
            ),
            "latin",
            listOf(intArrayOf(45, 48, 52), intArrayOf(50, 53, 57), intArrayOf(40, 44, 47), intArrayOf(45, 48, 52)),
            8
        ),
        Blueprint(
            "ambient", "Ambient / 电影", 72, 0f,
            "长音铺底、大混响、少音符",
            listOf(
                Triple("Pad", Instrument.SYNTH, "texture"),
                Triple("Shimmer", Instrument.BELL, "texture"),
                Triple("Sub", Instrument.BASS, "low"),
                Triple("Texture", Instrument.SYNTH, "texture")
            ),
            "hatsOnly",
            listOf(intArrayOf(48, 52, 55, 59), intArrayOf(45, 48, 52, 55), intArrayOf(41, 45, 48, 52), intArrayOf(43, 47, 50, 54)),
            8
        ),
        Blueprint(
            "pop", "Pop / 流行", 110, 0f,
            "主歌留白、副歌满和声",
            listOf(
                Triple("Drums", Instrument.DRUM, "drums"),
                Triple("Bass", Instrument.BASS, "low"),
                Triple("Chords", Instrument.EPIANO, "harmony"),
                Triple("Lead", Instrument.SYNTH, "lead"),
                Triple("Pad", Instrument.SYNTH, "texture")
            ),
            "ballad",
            listOf(intArrayOf(48, 52, 55), intArrayOf(43, 47, 50), intArrayOf(45, 48, 52), intArrayOf(41, 45, 48)),
            8
        )
    )

    fun all(): List<Blueprint> = BLUEPRINTS

    /**
     * 把蓝图追加进现有工程。返回新增的轨道数。
     *
     * 幂等性说明：同名轨道会被复用而不是重复添加，
     * 所以对同一个工程重复点同一个曲风，不会堆出两套 Kick。
     */
    fun apply(project: Project, bp: Blueprint): Int {
        val addedIndices = mutableListOf<Int>()
        for ((name, inst, group) in bp.tracks) {
            val existing = project.tracks.indexOfFirst { it.name == name && it.group == group }
            if (existing >= 0) {
                addedIndices.add(existing)
                continue
            }
            val idx = project.addTrack(inst, name)
            project.trackAt(idx)?.let { t ->
                t.group = group
                when (group) {
                    "drums" -> t.volume = 0.85f
                    "low" -> t.volume = 0.7f
                    "harmony" -> {
                        t.volume = 0.5f
                        t.sendReverb = 0.3f
                    }
                    "lead" -> {
                        t.volume = 0.5f
                        t.sendDelay = 0.2f
                    }
                    "texture" -> {
                        t.volume = 0.35f
                        t.sendReverb = 0.45f
                    }
                    "guitar" -> {
                        t.volume = 0.5f
                        t.effects[0] = Effect(type = EffectType.DIST, enabled = group == "guitar", mix = 0.45f, params = EffectType.DIST.defaults())
                    }
                }
            }
            addedIndices.add(idx)
        }

        val pattern = Pattern(project.nextPatternId++, "${bp.name} 骨架", bp.bars)
        // 鼓：把鼓型按"轨道名 -> 鼓音高"落到对应鼓轨
        val drumHits = DrumPatterns.of(bp.drumPattern)
        bp.tracks.forEachIndexed { trackIdx, spec ->
            if (spec.second != Instrument.DRUM) return@forEachIndexed
            val targetPitch = DrumPatterns.pitchFor(bp.drumPattern, spec.first)
            val list = pattern.notes(trackIdx)
            drumHits.filter { it.pitch == targetPitch }.forEach { h ->
                list.add(Note(h.pitch, h.step * Note.STEP, Note.STEP, h.velocity))
            }
            list.sortBy { it.start }
        }

        // 和声轨：每小节铺一个和弦
        val harmonyTracks = bp.tracks.withIndex().filter {
            it.value.second == Instrument.SYNTH || it.value.second == Instrument.EPIANO
        }
        harmonyTracks.forEach { (trackIdx, _) ->
            val list = pattern.notes(trackIdx)
            bp.chords.forEachIndexed { bar, chord ->
                chord.forEach { pitch ->
                    list.add(Note(pitch, bar * Note.BAR, Note.BAR - Note.STEP, 82))
                }
            }
        }

        if (pattern.noteCount() == 0) {
            // 没有鼓也没有和声的蓝图不应存在；兜底避免插入一个空样式
            return 0
        }
        project.patterns.add(pattern)
        project.clips.add(Clip(
            id = project.nextClipId++,
            patternId = pattern.id,
            track = addedIndices.firstOrNull() ?: 0,
            start = 0,
            length = bp.bars * Note.BAR
        ))
        // 给低音轨一个简单的根音走向，让骨架能立刻听出调性
        val bassTrack = bp.tracks.indexOfFirst { it.third == "low" }
        if (bassTrack >= 0) {
            val list = pattern.notes(bassTrack)
            bp.chords.forEachIndexed { bar, chord ->
                list.add(Note(chord[0] - 12, bar * Note.BAR, Note.BAR / 2, 108))
            }
            list.sortBy { it.start }
        }
        project.bpm = bp.bpm.toFloat()
        project.swing = bp.swing
        return addedIndices.size
    }

    /** 供界面展示的鼓型说明 */
    object DrumPatterns {
        data class Hit(val pitch: Int, val step: Int, val velocity: Int)

        fun of(name: String): List<Hit> = when (name) {
            "fourOnFloor" -> buildList {
                for (i in 0 until 4) add(Hit(36, i * 4, 115))
                add(Hit(39, 4, 100)); add(Hit(39, 12, 100))
                for (i in 0 until 16 step 2) add(Hit(42, i, if (i % 4 == 2) 80 else 55))
            }
            "trap" -> buildList {
                add(Hit(36, 0, 122)); add(Hit(36, 7, 108)); add(Hit(36, 10, 112))
                add(Hit(38, 8, 110))
                for (i in 0 until 16) add(Hit(42, i, if (i % 4 == 0) 78 else 52))
            }
            "halftime" -> buildList {
                add(Hit(36, 0, 120)); add(Hit(36, 10, 105)); add(Hit(38, 8, 115))
                for (i in 0 until 16 step 4) add(Hit(42, i, 58))
            }
            "rock" -> buildList {
                add(Hit(36, 0, 118)); add(Hit(36, 8, 112))
                add(Hit(38, 4, 112)); add(Hit(38, 12, 114))
                for (i in 0 until 16 step 2) add(Hit(42, i, if (i % 4 == 0) 72 else 52))
            }
            "metal" -> buildList {
                for (i in 0 until 16 step 2) add(Hit(36, i, if (i % 4 == 0) 122 else 104))
                add(Hit(38, 4, 118)); add(Hit(38, 12, 118))
                for (i in 0 until 16) add(Hit(42, i, 55))
            }
            "jazz" -> buildList {
                add(Hit(36, 0, 82)); add(Hit(36, 10, 70))
                add(Hit(38, 6, 78)); add(Hit(38, 14, 64))
                for (st in listOf(0, 3, 6, 9, 12, 15)) add(Hit(49, st, 70))
            }
            "reggae" -> buildList {
                add(Hit(36, 8, 112)); add(Hit(38, 8, 104))
                add(Hit(37, 4, 72)); add(Hit(37, 12, 70))
                for (i in listOf(2, 6, 10, 14)) add(Hit(42, i, 68))
            }
            "latin" -> buildList {
                for (st in listOf(0, 3, 6, 10, 12)) add(Hit(37, st, 88))
                add(Hit(36, 4, 100)); add(Hit(36, 12, 100))
                for (i in 0 until 16 step 2) add(Hit(42, i, 48))
            }
            "ballad" -> buildList {
                add(Hit(36, 0, 110)); add(Hit(36, 6, 95))
                add(Hit(38, 8, 108)); add(Hit(37, 12, 70))
                for (i in 0 until 16 step 4) add(Hit(42, i, 50))
            }
            "hatsOnly" -> buildList {
                for (i in 0 until 16) add(Hit(42, i, if (i % 4 == 0) 70 else 42))
            }
            else -> emptyList()
        }

        /**
         * 把"某个鼓轨对应的音色"映射到 MIDI 音高。
         * App 的鼓组按音高决定音色（36 底鼓、38 军鼓、39 拍手、42 闭镲、46 开镲、49 吊镲）。
         */
        fun pitchFor(pattern: String, trackName: String): Int = when {
            trackName.contains("Kick", true) || trackName.contains("808", true) -> 36
            trackName.contains("Snare", true) -> 38
            trackName.contains("Clap", true) -> 39
            trackName.contains("Hat", true) -> 42
            trackName.contains("Ride", true) -> 49
            trackName.contains("Clave", true) || trackName.contains("Rim", true) -> 37
            trackName.contains("Tom", true) -> 45
            trackName.contains("Drop", true) -> 38
            else -> 36
        }
    }
}

/**
 * 曲风选择面板。用可读的说明替代一串英文 key，
 * 选择后由 MainActivity 负责推引擎和刷新界面。
 */
class GenrePicker(
    context: Context,
    private val onPick: (GenreStarter.Blueprint) -> Unit
) : LinearLayout(context) {
    private val listView = LinearLayout(context)

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(8))
        addView(TextView(context).apply {
            text = "选择曲风后会追加一套轨道与骨架样式，现有内容不受影响。"
            setTextColor(Palette.TEXT_FAINT)
            textSize = 11f
        })
        listView.orientation = VERTICAL
        addView(listView)
        GenreStarter.all().forEach { bp ->
            val row = TextView(context).apply {
                text = "${bp.name}  ·  ${bp.bpm} BPM\n${bp.description}"
                textSize = 13f
                setTextColor(Palette.TEXT)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minHeight = dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = panelBackground(context, 10f)
                setOnClickListener { onPick(bp) }
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
