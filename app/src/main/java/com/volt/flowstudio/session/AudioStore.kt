package com.volt.flowstudio.session

import android.content.Context
import com.volt.flowstudio.audio.NativeEngine
import com.volt.flowstudio.model.AudioAsset
import com.volt.flowstudio.model.Clip
import com.volt.flowstudio.model.Note
import com.volt.flowstudio.model.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 音频素材池 + 片段装配。
 *
 * 关键设计：素材进池子只传一次波形给引擎，之后拖动片段只改布局。
 * 一首三分钟的歌是约 30 MB 的 float，如果每次拖动都跨一次 JNI，
 * 界面会直接卡死。所以：
 *
 *   素材（AudioAsset）→ 解码一次 → setAudioBuffer(id, ...) 进引擎池
 *   片段（Clip）      → 只传 [id, track, start, offset, length, gain] 六个字段
 *
 * 峰值（peaks）也只在导入时算一次，供时间线画波形用，不进引擎。
 */
class AudioStore(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "audio-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** 已经解码并推给引擎的素材。key = asset.id */
    private val decoded = ConcurrentHashMap<Int, FloatArray>()

    /** 每个素材的波形峰值，绘制用。降采样到固定桶数。 */
    private val peaks = ConcurrentHashMap<Int, FloatArray>()

    fun dir(): File = File(context.filesDir, "audio").apply { mkdirs() }

    fun peaksOf(assetId: Int): FloatArray = peaks[assetId] ?: FloatArray(0)

    /**
     * 导入一个音频文件，登记成素材。
     * 走 SAF 拿到的临时文件也会被拷进应用私有目录，之后不再依赖外部权限。
     */
    fun import(src: File, displayName: String, nextId: Int,
               onDone: (AudioAsset?) -> Unit) {
        io.execute {
            val parsed = runCatching { decode(src) }.getOrNull()
            if (parsed == null) {
                onDone(null)
                return@execute
            }
            // 文件名保留可读性，前缀 id 保证唯一。
            val safe = displayName.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(64)
            val target = File(dir(), "${nextId}_$safe")
            val copied = runCatching {
                src.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            }.isSuccess
            if (!copied) {
                onDone(null)
                return@execute
            }
            val asset = AudioAsset(
                id = nextId,
                name = displayName,
                path = target.relativeTo(context.filesDir).path,
                frames = parsed.frames,
                sampleRate = parsed.sampleRate
            )
            decoded[nextId] = parsed.samples
            peaks[nextId] = computePeaks(parsed.samples, parsed.frames)
            NativeEngine.instance.setAudioBuffer(nextId, parsed.samples, parsed.frames, parsed.sampleRate)
            onDone(asset)
        }
    }

    /** 工程加载后，把用到的素材重新解码进引擎池。 */
    fun reload(assets: List<AudioAsset>, onDone: (() -> Unit)? = null) {
        io.execute {
            for (a in assets) {
                if (decoded.containsKey(a.id)) continue
                val f = File(context.filesDir, a.path)
                if (!f.isFile) continue
                val parsed = runCatching { decode(f) }.getOrNull() ?: continue
                decoded[a.id] = parsed.samples
                peaks[a.id] = computePeaks(parsed.samples, parsed.frames)
                NativeEngine.instance.setAudioBuffer(a.id, parsed.samples, parsed.frames, parsed.sampleRate)
            }
            onDone?.invoke()
        }
    }

    /** 把工程里所有音频片段装配给引擎。只搬布局，不搬波形。 */
    fun pushClips(project: Project) {
        val audio = project.clips.filter { it.isAudio }
        if (audio.isEmpty()) {
            NativeEngine.instance.clearAudioClips()
            return
        }
        val bpm = project.bpm.coerceAtLeast(1f)
        val ids = IntArray(audio.size)
        val tracks = IntArray(audio.size)
        val starts = DoubleArray(audio.size)
        val offsets = LongArray(audio.size)
        val lengths = LongArray(audio.size)
        val gains = FloatArray(audio.size)

        for ((i, c) in audio.withIndex()) {
            val asset = project.audioAssetById(c.audioId)
            ids[i] = c.audioId
            tracks[i] = c.track
            starts[i] = c.start.toDouble()
            gains[i] = c.audioGain
            // 片段在时间线上被裁掉的头，要换算成素材里的帧偏移。
            // tick → 秒 → 帧，与引擎里那段换算必须一致，否则裁剪会漂。
            val skipSec = c.offset.toDouble() / Note.PPQ * (60.0 / bpm)
            offsets[i] = (skipSec * (asset?.sampleRate ?: 48000)).toLong().coerceAtLeast(0L)
            val playSec = c.length.toDouble() / Note.PPQ * (60.0 / bpm)
            lengths[i] = if (asset != null && asset.frames > 0) {
                val want = (playSec * asset.sampleRate).toLong()
                val avail = (asset.frames - offsets[i]).coerceAtLeast(0L)
                minOf(want, avail).coerceAtLeast(0L)
            } else 0L
        }
        NativeEngine.instance.setAudioClips(ids, tracks, starts, offsets, lengths, gains)
    }

    fun clear() {
        decoded.clear()
        peaks.clear()
        NativeEngine.instance.clearAudioClips()
        NativeEngine.instance.clearAudioBuffers()
    }

    private fun computePeaks(data: FloatArray, frames: Int, buckets: Int = 1600): FloatArray {
        if (frames <= 0 || data.size < 2) return FloatArray(0)
        val n = buckets.coerceIn(64, 6000)
        val out = FloatArray(n)
        val per = (frames.toDouble() / n).coerceAtLeast(1.0)
        for (i in 0 until n) {
            val start = (i * per).toInt().coerceIn(0, frames - 1)
            val end = ((i + 1) * per).toInt().coerceIn(start + 1, frames)
            var peak = 0f
            var f = start
            while (f < end) {
                val l = kotlin.math.abs(data[f * 2])
                val r = kotlin.math.abs(data[f * 2 + 1])
                if (l > peak) peak = l
                if (r > peak) peak = r
                f++
            }
            out[i] = peak.coerceIn(0f, 1f)
        }
        return out
    }

    private class Parsed(val samples: FloatArray, val frames: Int, val sampleRate: Int)

    /**
     * 解码 WAV 为交织立体声 float。
     * 单声道复制到两个声道；多声道只取前两路 —— 编曲不需要环绕。
     */
    private fun decode(file: File): Parsed? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        fun tag(off: Int) = String(ByteArray(4) { bytes[off + it] }, Charsets.US_ASCII)
        if (tag(0) != "RIFF" || tag(8) != "WAVE") return null

        var pos = 12
        var channels = 2
        var bits = 16
        var rate = 44100
        var fmt = 1
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(pos)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            val body = pos + 8
            if (id == "fmt " && size >= 16) {
                fmt = (bytes[body].toInt() and 0xFF) or ((bytes[body + 1].toInt() and 0xFF) shl 8)
                channels = ((bytes[body + 2].toInt() and 0xFF) or ((bytes[body + 3].toInt() and 0xFF) shl 8))
                    .coerceIn(1, 8)
                rate = (bytes[body + 4].toInt() and 0xFF) or
                        ((bytes[body + 5].toInt() and 0xFF) shl 8) or
                        ((bytes[body + 6].toInt() and 0xFF) shl 16) or
                        ((bytes[body + 7].toInt() and 0xFF) shl 24)
                bits = ((bytes[body + 14].toInt() and 0xFF) or ((bytes[body + 15].toInt() and 0xFF) shl 8))
            } else if (id == "data") {
                dataOff = body
                dataLen = minOf(size, bytes.size - body)
            }
            pos = body + size + (size and 1)
            if (dataOff >= 0 && pos >= bytes.size) break
        }
        if (dataOff < 0 || dataLen <= 0 || rate <= 0) return null
        if (fmt != 1 && fmt != 3) return null

        val bytesPerSample = bits / 8
        if (bytesPerSample <= 0) return null
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return null
        val totalFrames = dataLen / frameBytes
        if (totalFrames <= 0) return null

        val out = FloatArray(totalFrames * 2)
        var read = dataOff
        for (f in 0 until totalFrames) {
            var l = 0f
            var r = 0f
            for (ch in 0 until channels) {
                val v = when (bits) {
                    16 -> (((bytes[read].toInt() and 0xFF) or (bytes[read + 1].toInt() shl 8)).toShort()
                        .toFloat() / 32768f)
                    24 -> {
                        // 小端三字节 → 有符号 Int。必须先把高字节移位再 or，
                        // 直接 shl 16 会把符号位丢在上一位，得到错误的幅度。
                        val raw = (bytes[read].toInt() and 0xFF) or
                                ((bytes[read + 1].toInt() and 0xFF) shl 8) or
                                ((bytes[read + 2].toInt() and 0xFF) shl 16)
                        // 24 位补码扩展到 32 位：保留低 24 位并向高位做符号填充。
                        ((raw shl 8) shr 8).toFloat() / 8388608f
                    }
                    32 -> {
                        // 第 4 字节必须按无符号参与移位，否则符号会串到错误位置。
                        val raw = (bytes[read].toInt() and 0xFF) or
                                ((bytes[read + 1].toInt() and 0xFF) shl 8) or
                                ((bytes[read + 2].toInt() and 0xFF) shl 16) or
                                ((bytes[read + 3].toInt() and 0xFF) shl 24)
                        if (fmt == 3) Float.fromBits(raw) else raw.toFloat() / 2147483648f
                    }
                    else -> 0f
                }
                if (ch == 0) l = v
                if (ch == 1) r = v
                if (channels == 1) r = v
                read += bytesPerSample
            }
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
        return Parsed(out, totalFrames, rate)
    }
}
