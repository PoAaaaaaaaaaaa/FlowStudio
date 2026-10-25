package com.volt.flowstudio.session

import android.content.Context
import com.volt.flowstudio.audio.NativeEngine
import java.io.File
import java.util.concurrent.Executors

/**
 * 伴奏音频轨。
 *
 * 和采样器不同：采样器是把一个音按音高变调演奏，伴奏是按工程时间轴
 * 线性播放整段音频，用来让人声/成品伴奏跟编曲一起播。
 *
 * 数据流：
 *   WAV 文件 → 解码成交织立体声 float → 推给原生引擎 → 播放时与音符一起混音
 *
 * 文件存放在 filesDir/backing/<工程名>.wav，随工程一起被引用，
 * 不写进 .flow 文本（避免工程文件变成几十 MB）。
 */
class BackingTrack(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "backing-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    var fileName: String = ""
        private set
    var frames: Int = 0
        private set
    var sampleRate: Int = 44100
        private set
    var gain: Float = 1f
        private set
    var muted: Boolean = false
        private set
    var startTick: Int = 0
        private set

    val loaded: Boolean get() = frames > 0

    fun dir(): File = File(context.filesDir, "backing").apply { mkdirs() }

    /** 导入一个音频文件并立刻推给引擎。回调在主线程之外的池线程。 */
    fun import(src: File, onDone: ((Boolean, String) -> Unit)? = null) {
        io.execute {
            val parsed = runCatching { decode(src) }.getOrNull()
            if (parsed == null) {
                onDone?.invoke(false, "无法解码这个音频：只支持 PCM WAV（16/24/32-bit 或 float）")
                return@execute
            }
            val target = File(dir(), "backing.wav")
            runCatching { src.copyTo(target, overwrite = true) }
            fileName = target.name
            frames = parsed.frames
            sampleRate = parsed.sampleRate
            pushToEngine()
            onDone?.invoke(true, "${src.name} · ${"%.1f".format(parsed.frames.toDouble() / parsed.sampleRate)} 秒")
        }
    }

    /** 从工程目录恢复上次使用的伴奏。 */
    fun restore(onDone: ((Boolean) -> Unit)? = null) {
        io.execute {
            val f = File(dir(), "backing.wav")
            if (!f.isFile) { onDone?.invoke(false); return@execute }
            val parsed = runCatching { decode(f) }.getOrNull()
            if (parsed == null) { onDone?.invoke(false); return@execute }
            fileName = f.name
            frames = parsed.frames
            sampleRate = parsed.sampleRate
            pushToEngine()
            onDone?.invoke(true)
        }
    }

    fun clear() {
        fileName = ""
        frames = 0
        NativeEngine.instance.clearBackingTrack()
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 1.5f)
        NativeEngine.instance.setBackingGain(gain, muted)
    }

    fun setMuted(value: Boolean) {
        muted = value
        NativeEngine.instance.setBackingGain(gain, muted)
    }

    fun setStartTick(tick: Int) {
        startTick = tick.coerceAtLeast(0)
        if (loaded) pushToEngine()
    }

    private var cached: FloatArray? = null

    private fun pushToEngine() {
        val data = cached ?: return
        NativeEngine.instance.setBackingTrack(data, frames, sampleRate, startTick.toDouble(), gain)
        NativeEngine.instance.setBackingGain(gain, muted)
    }

    private class Parsed(val samples: FloatArray, val frames: Int, val sampleRate: Int)

    /**
     * 解码 WAV 为交织立体声 float。
     * 单声道会复制到两个声道；多声道只取前两路——伴奏轨不需要环绕。
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
            // 左声道
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
                        // 用 Int 组装小端 32 位：第 4 字节必须按无符号参与移位。
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
        cached = out
        return Parsed(out, totalFrames, rate)
    }
}
