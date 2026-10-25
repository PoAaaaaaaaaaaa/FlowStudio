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
    /** 未保存工程的临时稳定键；新建时换一个，保证伴奏不会串到下一个工程。 */
    private var unsavedKey: String = "unsaved-" + System.nanoTime().toString(16)
        private set

    val engine = NativeEngine.instance

    /** 伴奏音频轨：与编曲一起播放，不参与音符调度。 */
    val backing: BackingTrack by lazy { BackingTrack(context.applicationContext) }

    /** 与外部工具（DSH 插件）通信的本地服务 */
    var bridge: StudioBridge? = null
        private set

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flow-io").apply { priority = Thread.NORM_PRIORITY }
    }
    private val main = Handler(Looper.getMainLooper())
    private var autoSaveRunnable: Runnable? = null

    /** 界面刷新用的一次性回调。 */
    var onSaved: ((Boolean) -> Unit)? = null
    var onLoaded: ((Project) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** 外部（插件/桥接）改完工程后触发，让界面立即重绘，而不是等用户切页签。 */
    var onExternalEdit: ((String) -> Unit)? = null

    /** 伴奏状态变化（导入/恢复/清除/调参）后触发，让界面刷新波形。 */
    var onBackingChanged: (() -> Unit)? = null

    /** 最近一次 AI 写入前的工程快照，供一键撤销。 */
    private var undoSnapshot: String? = null
    private var undoLabel: String = ""

    fun hasUndo(): Boolean = undoSnapshot != null
    fun undoLabel(): String = undoLabel

    fun rememberSnapshot(label: String) {
        undoSnapshot = project.toText()
        undoLabel = label
    }

    /** 撤销最近一次外部写入；返回是否成功。 */
    fun undoExternalEdit(): Boolean {
        val snap = undoSnapshot ?: return false
        val p = Project.fromText(snap) ?: return false
        project = p
        pushAll()
        undoSnapshot = null
        onLoaded?.invoke(p)
        scheduleAutoSave()
        return true
    }

    /** 通知界面：外部写完了，刷新一次。 */
    fun notifyExternalEdit(label: String) {
        onExternalEdit?.invoke(label)
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 启动会话。返回 false 表示音频引擎不可用，但界面依然可以打开：
     * 编辑、存档、导出都照常，只是出不了声。
     * 1.0.0 在这里直接崩掉了整个应用，这次不允许再发生。
     */
    fun boot(): Boolean {
        if (!engine.create()) {
            onError?.invoke("音频引擎不可用，界面仍可使用（编辑与存档正常，播放无声）")
            runCatching { reloadLastOrDefault() }
            // 引擎坏了也让插件能连上，至少能读工程、生成新工程
            runCatching { startBridge() }
            return false
        }
        return try {
            engine.prepare(48000, 256)
            reloadLastOrDefault()
            pushAll()
            restoreBacking()
            engine.setMaster(
                project.masterVolume / 100f,
                project.masterLimiter,
                project.masterSoftClip,
                project.masterGainDb
            )
            // 本地桥接：让 DSH 插件能看到并修改这个正在跑的工程
            runCatching { startBridge() }
            true
        } catch (t: Throwable) {
            android.util.Log.e("FlowStudio", "会话启动失败", t)
            onError?.invoke("会话启动失败：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun startBridge() {
        if (bridge?.running == true) return
        val b = StudioBridge(context.applicationContext, this)
        if (b.start()) {
            bridge = b
            android.util.Log.i("FlowStudio", "桥接端口 ${b.port}")
        } else {
            android.util.Log.w("FlowStudio", "桥接启动失败")
        }
    }

    fun shutdown() {
        runCatching { bridge?.stopServer() }
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
        val target = ticks.coerceIn(0, len)
        engine.setPosition(target.toDouble())
        runCatching {
            bridge?.broadcast("seek", org.json.JSONObject().put("positionTicks", target))
        }
    }

    fun setLoop(on: Boolean, startTicks: Int, endTicks: Int) {
        project.loopOn = on
        project.loopStart = startTicks
        project.loopEnd = endTicks
        if (engine.available) {
            engine.setLoop(on, startTicks.toDouble(), endTicks.toDouble())
        }
    }

    fun setMetronome(on: Boolean, volume: Float = project.metronomeVolume) {
        project.metronomeVolume = volume.coerceIn(0f, 1f)
        if (engine.available) {
            engine.setMetronome(on)
            engine.setMetronomeVolume(project.metronomeVolume)
        }
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
                    restoreBacking()
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST, file.absolutePath).apply()
                    onLoaded?.invoke(proj)
                    onDone?.invoke(true)
                }
            }
        }
    }

    /** 外部（桥接/插件）整体替换工程。保持传输位置，避免听起来突然跳。 */
    /**
     * 整体替换工程。
     *
     * 必须切回主线程做，原因有两个：
     *   1. onLoaded 回调里是 UI 刷新（refreshAll 直接改 View），
     *      非主线程调用会抛 CalledFromWrongThreadException；
     *   2. play() 会启动音频流，涉及 AudioTrack/AAudio 的系统回调注册，
     *      在桥接线程上做会和服务线程互相等，表现为请求一直不返回。
     *
     * 桥接那边是"同步等结果"的，所以这里用主线程 + 超时等待，
     * 超时也要返回，不能让 HTTP 请求永远挂着。
     */
    fun replaceProject(incoming: Project, onDone: ((Boolean) -> Unit)? = null) {
        val body = {
            runCatching {
                val wasPlaying = isPlaying()
                stopPlayback()
                project = incoming
                currentFile = null
                backing.reset()
                pushAll()
                restoreBacking()
                onLoaded?.invoke(project)
                if (wasPlaying) play()
                scheduleAutoSave()
                true
            }.getOrElse {
                android.util.Log.e("FlowStudio", "替换工程失败", it)
                false
            }
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            onDone?.invoke(body())
            return
        }

        // 桥接线程：投递到主线程并等待，最多 6 秒
        val latch = java.util.concurrent.CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val posted = main.post {
            try {
                ok.set(body())
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            onDone?.invoke(false)
            return
        }
        val finished = latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            android.util.Log.w("FlowStudio", "替换工程超时（主线程繁忙）")
        }
        onDone?.invoke(finished && ok.get())
    }

    /** 供桥接服务显示在 /ping 里 */
    fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /** 桥接服务要读的上下文 */
    fun appContext(): android.content.Context = context

    fun newProject(withDemo: Boolean) {
        stopPlayback()
        project = if (withDemo) Project.demo() else Project().apply {
            tracks.add(Track.preset(Instrument.SYNTH, "合成器"))
            patterns.add(Pattern(nextPatternId++, "样式 0", 2))
        }
        currentFile = null
        unsavedKey = "unsaved-" + System.nanoTime().toString(16)
        // 新工程不继承任何伴奏：reset 后不要再调用 restoreBacking，
        // 否则同名文件会被重新加载，用户会以为"新建的工程还带着上一首"。
        backing.reset()
        pushAll()
        onLoaded?.invoke(project)
    }

    /** 统一同步工程的伴奏文件与播放参数，防止界面、存档与引擎状态分裂。 */
    fun restoreBacking() {
        val p = project
        backing.applySettings(p.backingStartTick, p.backingGain, p.backingMuted)
        // 旧工程没有 backingFile 时不主动恢复同名残留，避免同名工程串音。
        if (p.backingFile.isBlank()) {
            backing.reset()
            return
        }
        backing.restore(projectKey()) { ok ->
            if (ok) main.post { onBackingChanged?.invoke() }
        }
    }

    /**
     * 稳定的工程键：优先用工程文件，未保存时退回工程名。
     * 不能直接用显示名，否则用户改一次名伴奏就丢失。
     */
    private fun projectKey(): String =
        currentFile?.nameWithoutExtension ?: unsavedKey

    fun importBacking(src: File, onDone: ((Boolean, String) -> Unit)? = null) {
        backing.import(projectKey(), src) { ok, message ->
            if (ok) {
                project.backingFile = backing.fileName
                project.backingGain = backing.gain
                project.backingStartTick = backing.startTick
                scheduleAutoSave()
            }
            main.post {
                if (ok) onBackingChanged?.invoke()
                onDone?.invoke(ok, message)
            }
        }
    }

    fun clearBacking() {
        backing.clear()
        project.backingFile = ""
        scheduleAutoSave()
        onBackingChanged?.invoke()
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

    /**
     * 用户改过东西的统一出口：几秒后自动落盘，并让桥接的 revision +1。
     * 插件靠 revision 判断它手上的快照是否已经过期，
     * 避免 AI 把用户刚手动调好的东西覆盖掉。
     */
    fun noteUserEdit() {
        bridge?.bumpRevision()
    }

    /** 改完东西调一下，几秒后自动落盘，防丢。 */
    fun scheduleAutoSave() {
        bridge?.bumpRevision()
        // 用户手动改动也推给订阅者：插件必须知道"我手上的快照过期了"。
        // 这是"实时更新软件状态"里最难的一半 —— 插件主动查很简单，
        // 让软件主动告诉插件才叫实时。
        runCatching {
            bridge?.broadcast("edit", org.json.JSONObject()
                .put("name", project.name)
                .put("bpm", project.bpm.toDouble())
                .put("tracks", project.tracks.size)
                .put("patternCount", project.patterns.size)
                .put("clipCount", project.clips.size))
        }
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

        // 导出位置：优先写公共 Music 目录，用户能在文件管理器和音乐播放器里直接看到。
        // 之前只写 getExternalFilesDir()，那是应用私有外部目录，
        // 文件管理器默认不显示，用户以为"没生成文件"。
        val fileName = "${project.name.replace(Regex("[\\\\/:*?\"<>|]"), "_")}-${
            java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        }.wav"

        val outFile = chooseExportFile(fileName)

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

    /**
     * 选一个用户能找到的导出位置。
     *
     * 顺序：
     *   1. 公共 Music/FlowStudio —— 文件管理器、音乐播放器都能看到
     *   2. 应用外部私有目录 —— 没有存储权限时的退路，至少文件真的存在
     *   3. 内部私有目录 —— 最后兜底
     *
     * 每一层都要能真正创建成功，拿不到就往下走。
     */
    private fun chooseExportFile(fileName: String): File {
        val candidates = listOfNotNull(
            // 公共音乐目录（Android 10+ 直接可写，无需权限）
            runCatching {
                File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC
                    ),
                    "FlowStudio"
                )
            }.getOrNull(),
            // 应用外部私有目录
            runCatching { File(context.getExternalFilesDir(null), "exports") }.getOrNull(),
            // 内部目录
            File(context.filesDir, "exports")
        )

        for (dir in candidates) {
            val ok = runCatching {
                dir.mkdirs()
                dir.isDirectory && dir.canWrite()
            }.getOrDefault(false)
            if (ok) {
                return File(dir, fileName)
            }
            android.util.Log.w("FlowStudio", "导出目录不可用: ${dir.absolutePath}")
        }
        // 全都不行也得给个路径，让上层报错而不是静默失败
        return File(context.filesDir, "exports/$fileName")
    }

    /** 导出目录的展示路径，界面上告诉用户去哪找 */
    fun exportDirHint(): String {
        val music = runCatching {
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC
                ),
                "FlowStudio"
            )
        }.getOrNull()
        if (music != null && music.isDirectory) return "Music/FlowStudio"
        val ext = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (ext != null) return "Android/data/${context.packageName}/files/exports"
        return "应用私有目录"
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

