package com.volt.flowstudio.session

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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

    /** 素材元数据，按 id。reload 与 import 都往里放，去重时用它核对。 */
    private val meta = ConcurrentHashMap<Int, AudioAsset>()

    /** 内容指纹 → 素材 id。导入时的去重索引。 */
    private val fingerprints = ConcurrentHashMap<String, Int>()

    /** 当前池子里这条素材的指纹（可能由 reload 补算）。空串表示还不知道。 */
    fun fingerprintOf(assetId: Int): String = meta[assetId]?.fingerprint ?: ""

    fun dir(): File = File(context.filesDir, "audio").apply { mkdirs() }

    fun peaksOf(assetId: Int): FloatArray = peaks[assetId] ?: FloatArray(0)

    /**
     * 导入一个音频文件，登记成素材。
     * 走 SAF 拿到的临时文件也会被拷进应用私有目录，之后不再依赖外部权限。
     *
     * 去重：解码后先算内容指纹，池子里已有同指纹的素材就把那条交回去（`reused=true`），
     * 不再落一份新文件、不再占一份引擎缓冲区。这是"每次退出重进多一段音频"的第一道闸。
     */
    fun import(src: File, displayName: String, nextId: Int,
               onDone: (AudioAsset?, Boolean) -> Unit) {
        io.execute {
            val parsed = runCatching { decode(src) }.getOrNull()
            if (parsed == null) {
                onDone(null, false)
                return@execute
            }
            val fp = AudioAsset.fingerprintOf(parsed.samples, parsed.frames)
            val hit = existingByFingerprint(fp)
            if (hit != null) {
                android.util.Log.i(
                    "FlowStudio",
                    "音频去重：$displayName 与素材 #${hit.id}(${hit.name}) 内容相同，直接复用"
                )
                onDone(hit, true)
                return@execute
            }
            // 文件名保留可读性，前缀 id 保证唯一。
            val safe = displayName.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(64)
            val target = File(dir(), "${nextId}_$safe")
            val copied = runCatching {
                src.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            }.isSuccess
            if (!copied) {
                onDone(null, false)
                return@execute
            }
            val asset = AudioAsset(
                id = nextId,
                name = displayName,
                path = target.relativeTo(context.filesDir).path,
                frames = parsed.frames,
                sampleRate = parsed.sampleRate,
                fingerprint = fp
            )
            // 指纹表要在 onDone 之前就写进去：同一批投递里两个相同文件
            // 会由单线程串行处理，第二条必须能看到第一条的结果。
            fingerprints[fp] = nextId
            decoded[nextId] = parsed.samples
            peaks[nextId] = computePeaks(parsed.samples, parsed.frames)
            NativeEngine.instance.setAudioBuffer(nextId, parsed.samples, parsed.frames, parsed.sampleRate)
            meta[nextId] = asset
            onDone(asset, false)
        }
    }

    /** 按指纹找已登记的素材。找不到返回 null。 */
    private fun existingByFingerprint(fp: String): AudioAsset? {
        if (fp.isEmpty()) return null
        val id = fingerprints[fp] ?: return null
        // 素材可能被用户删了但指纹表还没清；以 meta 里有记录为准
        return meta[id]
    }

    /** 工程加载后，把用到的素材重新解码进引擎池。 */
    fun reload(assets: List<AudioAsset>, onDone: (() -> Unit)? = null) {
        io.execute {
            for (a in assets) {
                meta[a.id] = a
                if (a.fingerprint.isNotEmpty()) fingerprints[a.fingerprint] = a.id
                if (decoded.containsKey(a.id)) continue
                val f = File(context.filesDir, a.path)
                if (!f.isFile) continue
                val parsed = runCatching { decode(f) }.getOrNull() ?: continue
                // 老工程没有指纹：顺手补一个，之后去重就能命中
                if (a.fingerprint.isEmpty()) {
                    a.fingerprint = AudioAsset.fingerprintOf(parsed.samples, parsed.frames)
                    if (a.fingerprint.isNotEmpty()) fingerprints[a.fingerprint] = a.id
                }
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
        meta.clear()
        fingerprints.clear()
        NativeEngine.instance.clearAudioClips()
        NativeEngine.instance.clearAudioBuffers()
    }

    /**
     * 波形峰值表。
     *
     * 桶数决定"最多能放大到多细"。1600 桶对一首 3 分钟的歌只有每桶 110ms，
     * 放大到看单个鼓点时就是一段段重复的台阶（用户看到的"波形不跟缩放"里，
     * 一半是绘制范围被截断，另一半就是这里分辨率不够）。
     *
     * 取 4096 桶：3 分钟素材每桶约 44ms，缩放到单小节级别仍有细节；
     * 内存代价是每个素材 16KB 的 float 表，可以忽略。
     */
    private fun computePeaks(data: FloatArray, frames: Int, buckets: Int = 4096): FloatArray {
        if (frames <= 0 || data.size < 2) return FloatArray(0)
        // 极短素材（< 0.5 秒）不需要那么多桶，按实际帧数封顶
        val n = minOf(buckets, maxOf(64, frames)).coerceIn(64, 8192)
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
     * 解码入口：先试 PCM WAV（最快、无中间层），失败再走 MediaCodec。
     *
     * MediaCodec 支持平台解的一切格式：mp3 / m4a / aac / flac / ogg。
     * FL 的素材是 mp3，这层必须有，否则"导音频"就只是空话。
     */
    private fun decode(file: File): Parsed? {
        if (!file.isFile) return null
        val wav = runCatching { decodeWav(file) }.getOrNull()
        if (wav != null) return wav
        return runCatching { decodeMediaCodec(file) }.getOrNull()
    }

    /**
     * 解码 WAV 为交织立体声 float。
     * 单声道复制到两个声道；多声道只取前两路 —— 编曲不需要环绕。
     */
    private fun decodeWav(file: File): Parsed? {
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
    // ---------------------------------------------------------------- MediaCodec 通用解码

    /**
     * 用 MediaExtractor + MediaCodec 解码任意平台支持的音频。
     *
     * 工作方式：Extractor 从容器里取压缩帧，喂给 Codec；Codec 输出 PCM。
     * 输出可能是 16-bit 整型或 float（取决于 KEY_PCM_ENCODING 与设备），
     * 两种都要处理 —— 只处理 16-bit 的话在某些设备上是静音。
     *
     * 采样率与声道数保持源文件的：引擎按时间换算帧号，本来就会
     * 对不同 sampleRate 做线性插值，不需要在解码端重采样。
     * 多于 2 声道只取前两路（编曲不需要环绕）。
     *
     * 这是阻塞调用，必须跑在 io 线程（import/reload 都已经在那）。
     * 上限 10 分钟 / 约 100 MB float，防手滑把整张专辑导进来。
     */
    private fun decodeMediaCodec(file: File): Parsed? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            // 上限保护：10 分钟 @ 48k 立体声 float ≈ 92 MB。
            // 素材池里存的是 FloatArray，超了就是 OOM 而不是"导入失败"。
            val maxFrames = 600 * 48000
            val expectedFrames = if (durationUs > 0) (durationUs / 1_000_000.0 * sampleRate).toInt() else maxFrames
            if (expectedFrames > maxFrames) return null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 先给个合理初容量，之后不够就翻倍 —— 逐帧 add 会慢到没法用。
            var out = FloatArray((expectedFrames.coerceAtMost(maxFrames)).coerceAtLeast(1) * 2)
            var frames = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // ---- 喂输入 ----
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ---- 收输出 ----
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 实际输出格式以这里为准：有的解码器会给不同的采样率/声道
                        val of = codec.outputFormat
                        // sampleRate/channels 变了也不重开 —— 我们按原始 format 记录，
                        // 引擎按 sampleRate 插值，只要这里如实报告就行。
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 等下一轮 */ }
                    else -> if (outIdx >= 0) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        val pcmEnc = codec.outputFormat.getInteger(
                            MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                        val bytesPerSample = if (pcmEnc == android.media.AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        val framesInBuf = info.size / (bytesPerSample * channels)
                        if (framesInBuf > 0) {
                            // 容量不够就翻倍
                            if (frames + framesInBuf > out.size / 2) {
                                var newSize = out.size
                                while (newSize / 2 < frames + framesInBuf) newSize *= 2
                                out = out.copyOf(newSize)
                            }
                            when (pcmEnc) {
                                android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                                    ob.order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { fb ->
                                        var src = 0
                                        for (f in 0 until framesInBuf) {
                                            val l = fb.get(src)
                                            val r = if (channels > 1) fb.get(src + 1) else l
                                            out[frames * 2] = l
                                            out[frames * 2 + 1] = r
                                            frames++
                                            src += channels
                                        }
                                    }
                                }
                                else -> {
                                    var src = 0
                                    for (f in 0 until framesInBuf) {
                                        val l = ob.getShort(src) / 32768f
                                        val r = if (channels > 1) ob.getShort(src + 2) / 32768f else l
                                        out[frames * 2] = l
                                        out[frames * 2 + 1] = r
                                        frames++
                                        src += 2 * channels
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            if (frames <= 0) return null
            return Parsed(out.copyOf(frames * 2), frames, sampleRate)
        } catch (e: Exception) {
            android.util.Log.w("FlowStudio", "MediaCodec 解码失败: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}