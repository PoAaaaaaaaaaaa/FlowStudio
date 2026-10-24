package com.volt.flowstudio

import android.content.Context
import com.volt.flowstudio.model.Effect
import com.volt.flowstudio.audio.NativeEngine
import java.io.File

/** 读取私有目录里的采样，给采样器轨道用。 */
class SampleBank(private val context: Context) {
    private var data: FloatArray? = null
    var name: String = ""
        private set
    var rootNote: Int = 60
        private set
    var sampleRate: Int = 44100
        private set

    val hasSample: Boolean get() = (data?.size ?: 0) > 0

    fun dir(context: Context): File = File(context.filesDir, "samples").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name } ?: emptyList()

    /**
     * 把 WAV 解码成单声道 float 并推到原生侧。
     * 只支持 16/24/32-bit PCM 与 32-bit float，够覆盖常见导出格式。
     */
    fun load(file: File): Boolean {
        val bytes = file.readBytes()
        val parsed = parseWav(bytes) ?: return false
        data = parsed.samples
        sampleRate = parsed.sampleRate
        rootNote = 60
        name = file.nameWithoutExtension
        NativeEngine.instance.loadSample(parsed.samples, parsed.sampleRate, rootNote)
        return true
    }

    fun clear() {
        data = null
        name = ""
        NativeEngine.instance.clearSample()
    }

    private class Parsed(val samples: FloatArray, val sampleRate: Int)

    private fun parseWav(b: ByteArray): Parsed? {
        if (b.size < 44) return null
        if (b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte() ||
            b[2] != 'F'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        if (b[8] != 'W'.code.toByte() || b[9] != 'A'.code.toByte() ||
            b[10] != 'V'.code.toByte() || b[11] != 'E'.code.toByte()) return null

        var pos = 12
        var channels = 1
        var bits = 16
        var sampleRate = 44100
        var fmtSeen = false
        var dataOff = -1
        var dataLen = 0

        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(b, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > b.size) return null
                    val format = le16(b, body)
                    channels = le16(b, body + 2).coerceAtLeast(1)
                    sampleRate = le32(b, body + 4).coerceAtLeast(8000)
                    bits = le16(b, body + 14)
                    // format 1 = PCM, 3 = IEEE float, 0xFFFE = extensible（按 bits 判断）
                    if (format != 1 && format != 3 && format != 0xFFFE) return null
                    fmtSeen = true
                }
                "data" -> {
                    dataOff = body
                    dataLen = size.coerceAtMost(b.size - body)
                }
            }
            if (size <= 0) break
            pos = body + size + (size and 1)
        }
        if (!fmtSeen || dataOff < 0 || dataLen <= 0) return null

        val bytesPerSample = (bits / 8).coerceAtLeast(1)
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return null
        val frames = dataLen / frameBytes
        if (frames <= 0) return null

        val out = FloatArray(frames)
        var i = dataOff
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += when (bits) {
                    16 -> ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort() / 32768f
                    24 -> {
                        val v = (b[i].toInt() and 0xFF) or
                                ((b[i + 1].toInt() and 0xFF) shl 8) or
                                (b[i + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    32 -> Float.fromBits(
                        (b[i].toInt() and 0xFF) or
                        ((b[i + 1].toInt() and 0xFF) shl 8) or
                        ((b[i + 2].toInt() and 0xFF) shl 16) or
                        (b[i + 3].toInt() shl 24)
                    )
                    8 -> ((b[i].toInt() and 0xFF) - 128) / 128f
                    else -> 0f
                }
                i += bytesPerSample
            }
            out[f] = acc / channels.toFloat()
        }
        // 去掉采样里可能带的直流，否则采样器的音色会偏
        var dc = 0.0
        for (v in out) dc += v
        val mean = (dc / out.size).toFloat()
        if (kotlin.math.abs(mean) > 0.001f) {
            for (k in out.indices) out[k] -= mean
        }
        // 归一化到 -1..1，超过 1 的硬削会出现刺耳的破音
        var peak = 0f
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
        if (peak > 1f) {
            val k = 1f / peak
            for (idx in out.indices) out[idx] *= k
        }
        return Parsed(out, sampleRate)
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)
}
