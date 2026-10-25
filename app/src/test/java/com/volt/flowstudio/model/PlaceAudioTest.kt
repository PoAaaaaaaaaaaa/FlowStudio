package com.volt.flowstudio.model

/**
 * placeAudio 避让逻辑的纯 Kotlin 回归。
 *
 * 起因是用户报告："导入音频与已被占的轨道处在同一位置"。
 * 旧实现直接丢在 selectedTrack 的播放头位置，不查重叠 —— 撞上就叠。
 *
 * 这个类不依赖 Android（Project/Clip/Note 都是纯数据类），
 * 用 kotlinc 直接编译运行即可。CI 由静态检查兜底引用它的存在。
 */
object PlaceAudioTest {

    private var failures = 0

    private fun check(ok: Boolean, msg: String) {
        println(if (ok) "[ ok ]  $msg" else "[FAIL]  $msg")
        if (!ok) failures++
    }

    private fun project(): Project = Project().apply {
        bpm = 120f
        tracks.add(Track.preset(Instrument.SYNTH, "A"))
        tracks.add(Track.preset(Instrument.SYNTH, "B"))
        tracks.add(Track.preset(Instrument.SYNTH, "C"))
    }

    private fun asset(): AudioAsset = AudioAsset(
        id = 7, name = "loop.wav", path = "audio/7.wav",
        frames = 48000 * 2, sampleRate = 48000   // 2 秒 = 2 小节 @120BPM
    )

    @JvmStatic
    fun main(args: Array<String>) {
        // 1. 空轨道直接落
        run {
            val p = project()
            val c = p.placeAudio(asset(), track = 0, startTick = 0)
            check(c.track == 0 && c.start == 0, "case1: empty track lands as requested (track=${c.track} start=${c.start})")
            // 2 秒素材 @120BPM = 1 小节 = 1920 tick（一小节 = 4 拍 = 2 秒）
            check(c.length == 1920, "case1: length fills asset (${c.length}, want 1 bar @120bpm)")
        }

        // 2. 请求轨被占 → 换第一条空轨
        run {
            val p = project()
            p.clips.add(Clip(1, patternId = -1, track = 0, start = 0, length = 1920 * 8, audioId = 5))
            val c = p.placeAudio(asset(), track = 0, startTick = 0)
            check(c.track == 1, "case2: falls to first empty track (got track=${c.track})")
        }

        // 3. 所有轨都被占 → 追加新轨
        run {
            val p = project()
            p.tracks.forEachIndexed { i, _ ->
                p.clips.add(Clip(10 + i, patternId = -1, track = i, start = 0, length = 1920 * 8, audioId = 5))
            }
            val before = p.tracks.size
            val c = p.placeAudio(asset(), track = 0, startTick = 0)
            check(p.tracks.size == before + 1, "case3: appends new track (${p.tracks.size} vs $before)")
            check(c.track == before, "case3: clip lands on new track (got ${c.track}, want $before)")
        }

        // 4. 所有轨都被占 → 同轨撞车时顺延到空位
        //    （轨道避让优先于位置避让：轨 0 被占时会先换空轨。
        //      要测位置避让，必须把其它轨也占上。）
        run {
            val p = project()
            p.clips.add(Clip(1, patternId = -1, track = 0, start = 0, length = 1920 * 4, audioId = 5))
            p.clips.add(Clip(2, patternId = -1, track = 1, start = 0, length = 1920 * 8, audioId = 5))
            p.clips.add(Clip(3, patternId = -1, track = 2, start = 0, length = 1920 * 8, audioId = 5))
            val c = p.placeAudio(asset(), track = 0, startTick = 0, allowNewTrack = false)
            check(c.track == 0 && c.start == 1920 * 4,
                "case4: pushes to end of occupied (track=${c.track} start=${c.start})")
        }

        // 5. 中间有空位则填空位
        run {
            val p = project()
            p.clips.add(Clip(1, patternId = -1, track = 0, start = 1920 * 4, length = 1920 * 2, audioId = 5))
            val c = p.placeAudio(asset(), track = 0, startTick = 0, allowNewTrack = false)
            check(c.start == 0, "请求位置空着就用请求位置（前面没被占）")
        }

        // 6. 连续导入不打架（投递一批文件的场景）
        run {
            val p = project()
            p.tracks.clear()
            repeat(2) { i -> p.tracks.add(Track.preset(Instrument.SYNTH, "T$i")) }
            val placed = (1..4).map { p.placeAudio(asset(), track = 0, startTick = 0) }
            val overlapping = placed.indices.any { i ->
                placed.indices.any { j ->
                    i != j && placed[i].track == placed[j].track &&
                        placed[i].start < placed[j].start + placed[j].length &&
                        placed[j].start < placed[i].start + placed[i].length
                }
            }
            check(!overlapping, "case6: 4 imports never overlap")
        }

        println()
        if (failures == 0) println("全部通过  失败 0 项") else println("有失败  失败 $failures 项")
        if (failures != 0) kotlin.system.exitProcess(1)
    }
}
