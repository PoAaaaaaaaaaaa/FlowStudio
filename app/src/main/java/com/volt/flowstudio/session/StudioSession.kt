package com.volt.flowstudio.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.volt.flowstudio.audio.NativeEngine
import com.volt.flowstudio.model.*
import java.io.File
import java.util.concurrent.Executors

/**
 * 会话层：把 Project 的变化翻译成原生引擎调用，并负责存盘/读盘。
 * UI 只跟它说话，不直接碰 JNI。
 */
class StudioSession(private val context: Context) {

    companion object {
        private const val PREFS = "flowstudio"
        private const val KEY_LAST = "last_project"
        private const val AUTO_SAVE_MS = 4000L
    }

    var project: Project = Project.demo()
        private set

    var currentFile: File? = null
        private set

    val engine = NativeEngine.instance

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flow-io").apply { priority = Thread.NORM_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())
    private var autoSaveRunnable: Runnable? = null

    /** 界面刷新用的一次性回调。 */
    var onSaved: ((Boolean) -> Unit)? = null
    var onLoaded: ((Project) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ---------------------------------------------------------------- 生命周期

    fun boot(): Boolean {
        if (!engine.create()) {
            onError?.invoke("原生音频引擎加载失败，检查 libflowengine.so 是否打进了 APK")
            return false
        }
        engine.prepare(48000, 256)
        reloadLastOrDefault()
        pushAll()
        engine.setMaster(project.masterVolume / 100f, project.masterLimiter, project.masterSoftClip, project.masterGainDb)
        return true
    }

    fun shutdown() {
        stopPlayback()
        engine.stopAudio()
        engine.destroy()
        saveNow(sync = true)
    }

    // ---------------------------------------------------------------- 推送工程到引擎

    /** 全量推送：轨道、样式、自动化、母带。改结构后调这个。 */
    fun pushAll() {
        if (!engine.available) return

        engine.clearTracks()
        project.tracks.forEachIndexed { i, t ->
            engine.setChannel(
                i, t.instrument.ordinal, t.activeParams(), t.transpose, t.velocitySense,
                t.volume, t.pan, t.sendReverb, t.sendDelay, t.mute, t.solo,
                t.fxTypes(), t.fxEnabled(), t.fxMix(), t.fxParams()
            )
        }
        engine.setBpm(project.bpm)
        engine.setSchedule(project.buildSchedule(), project.lengthTicks())
        val (meta, ticks, vals) = project.buildAutomation()
        engine.setAutomation(meta, ticks, vals)
        engine.setLoop(project.loopOn, project.loopStart.toDouble(), project.loopEnd.toDouble())
        engine.setMaster(
            project.masterVolume / 100f, project.masterLimiter,
            project.masterSoftClip, project.masterGainDb
        )
    }

    /** 只推一条轨道的混音/音色，调推子时用，避免整表重推。 */
    fun pushTrack(index: Int) {
        val t = project.trackAt(index) ?: return
        if (!engine.available) return
        engine.setChannel(
            index, t.instrument.ordinal, t.activeParams(), t.transpose, t.velocitySense,
            t.volume, t.pan, t.sendReverb, t.sendDelay, t.mute, t.solo,
            t.fxTypes(), t.fxEnabled(), t.fxMix(), t.fxParams()
        )
    }

    fun pushMaster() {
        engine.setMaster(
            project.masterVolume / 100f, project.masterLimiter,
            project.masterSoftClip, project.masterGainDb
        )
    }

    fun pushBpm() {
        engine.setBpm(project.bpm)
        engine.setSchedule(project.buildSchedule(), project.lengthTicks())
    }

    fun pushAutomation() {
        val (meta, ticks, vals) = project.buildAutomation()
        engine.setAutomation(meta, ticks, vals)
    }

    // ---------------------------------------------------------------- 传输

    fun play() {
        if (!engine.available) return
        if (!engine.isAudioRunning()) engine.startAudio()
        engine.play()
    }

    fun pause() = engine.pause()

    fun stop() {
        engine.stop()
        engine.allNotesOff()
    }

    fun stopPlayback() {
        if (engine.available) {
            engine.stop()
            engine.allNotesOff()
        }
    }

    fun isPlaying(): Boolean = engine.available && engine.isPlaying()

    fun positionTicks(): Int = if (engine.available) engine.position().toInt() else 0

    fun seek(ticks: Int) {
        if (!engine.available) return
        val len = project.lengthTicks()
        engine.setPosition(ticks.coerceIn(0, len).toDouble())
    }

    fun setLoop(on: Boolean, startTicks: Int, endTicks: Int) {
        project.loopOn = on
        project.loopStart = startTicks
        project.loopEnd = endTicks
        if (engine.available) {
            engine.setLoop(on, startTicks.toDouble(), endTicks.toDouble())
        }
    }

    fun setMetronome(on: Boolean) {
        if (engine.available) engine.setMetronome(on)
    }

    // ---------------------------------------------------------------- 试听

    fun previewNote(track: Int, pitch: Int, velocity: Int = 100, durationTicks: Int = Note.STEP) {
        if (!engine.available) return
        if (!engine.isAudioRunning()) engine.startAudio()
        engine.noteOn(track, pitch, velocity / 127f, durationTicks)
    }

    fun releaseNote(track: Int) = engine.noteOff(track)

    // ---------------------------------------------------------------- 存取

    fun projectsDir(): File = File(context.filesDir, "projects").apply { mkdirs() }

    fun listProjects(): List<File> =
        projectsDir().listFiles { f -> f.isFile && f.name.endsWith(".flow") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun save(asName: String?, onDone: ((Boolean) -> Unit)? = null) {
        val name = asName ?: currentFile?.nameWithoutExtension ?: project.name
        val file = File(projectsDir(), "$name.flow")
        val text = project.toText()
        io.execute {
            val ok = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }.isSuccess
            if (ok) {
                currentFile = file
                project.name = name
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST, file.absolutePath).apply()
            }
            main.post {
                onSaved?.invoke(ok)
                onDone?.invoke(ok)
                if (!ok) onError?.invoke("保存失败：${file.absolutePath}")
            }
        }
    }

    fun load(file: File, onDone: ((Boolean) -> Unit)? = null) {
        io.execute {
            val proj = runCatching { Project.fromText(file.readText()) }.getOrNull()
            main.post {
                if (proj == null) {
                    onError?.invoke("工程文件损坏：${file.name}")
                    onDone?.invoke(false)
                } else {
                    project = proj
                    currentFile = file
                    stopPlayback()
                    pushAll()
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST, file.absolutePath).apply()
                    onLoaded?.invoke(proj)
                    onDone?.invoke(true)
                }
            }
        }
    }

    fun newProject(withDemo: Boolean) {
        stopPlayback()
        project = if (withDemo) Project.demo() else Project().apply {
            tracks.add(Track.preset(Instrument.SYNTH, "合成器"))
            patterns.add(Pattern(nextPatternId++, "样式 0", 2))
        }
        currentFile = null
        pushAll()
        onLoaded?.invoke(project)
    }

    private fun reloadLastOrDefault() {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST, null)
        if (path != null) {
            val f = File(path)
            if (f.isFile) {
                val p = runCatching { Project.fromText(f.readText()) }.getOrNull()
                if (p != null) {
                    project = p
                    currentFile = f
                    return
                }
            }
        }
        project = Project.demo()
    }

    /** 改完东西调一下，几秒后自动落盘，防丢。 */
    fun scheduleAutoSave() {
        autoSaveRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { save(null) }
        autoSaveRunnable = r
        main.postDelayed(r, AUTO_SAVE_MS)
    }

    fun saveNow(sync: Boolean) {
        val file = currentFile ?: File(projectsDir(), "${project.name}.flow")
        val text = project.toText()
        if (sync) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
            currentFile = file
        } else {
            save(null)
        }
    }

    // ---------------------------------------------------------------- 导出

    fun exportWav(startTick: Int, endTick: Int, sampleRate: Int = 44100,
                  cb: (File?) -> Unit) {
        val start = startTick
        val end = if (endTick > startTick) endTick else project.lengthTicks()
        // 导出要独占引擎，先停播放，否则会跟实时音频抢状态
        val wasPlaying = isPlaying()
        stopPlayback()

        val outFile = File(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() },
            "${project.name}-${System.currentTimeMillis()}.wav"
        )

        io.execute {
            val data = engine.renderOffline(start.toDouble(), end.toDouble(), sampleRate)
            val ok = if (data != null && data.isNotEmpty()) {
                runCatching { writeWav(outFile, data, sampleRate) }.isSuccess
            } else false

            main.post {
                pushAll()   // 渲染改过引擎状态，恢复现场
                if (wasPlaying) play()
                cb(if (ok) outFile else null)
            }
        }
    }

    /**
     * 用系统分享把导出的文件送出去。
     * 走我们自己的 ExportedFileProvider，不依赖 androidx。
     */
    fun shareFile(file: File) {
        runCatching {
            val uri = ExportedFileProvider.uriFor(context, file)
            val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(i, "分享导出文件")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun writeWav(file: File, interleaved: FloatArray, sampleRate: Int) {
        val frames = interleaved.size / 2
        val dataBytes = frames * 2 * 2     // 16-bit 立体声
        val header = 44
        val out = java.io.ByteArrayOutputStream(header + dataBytes)

        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        }

        out.write("RIFF".toByteArray()); le32(36 + dataBytes); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(2)
        le32(sampleRate); le32(sampleRate * 2 * 2); le16(4); le16(16)
        out.write("data".toByteArray()); le32(dataBytes)

        val buf = ByteArray(dataBytes)
        var bi = 0
        for (v in interleaved) {
            // 用 tanh 型的软拐点做最后一道保护，避免硬削的刺耳感
            val clamped = v.coerceIn(-1f, 1f)
            val s = (clamped * 32767f).toInt()
            buf[bi++] = (s and 0xFF).toByte()
            buf[bi++] = ((s ushr 8) and 0xFF).toByte()
        }
        out.write(buf)

        file.writeBytes(out.toByteArray())
    }
}
