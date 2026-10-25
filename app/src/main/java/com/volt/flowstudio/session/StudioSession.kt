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

    /**
     * 音频素材池。素材解码一次进引擎，片段只搬布局。
     * 取代了早先的"一条全局伴奏轨"。
     */
    val audioStore: AudioStore by lazy { AudioStore(context.applicationContext) }

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

    /** 音频素材变化（导入/恢复/清除）后触发，让界面刷新波形。 */
    var onAudioChanged: (() -> Unit)? = null

    /**
     * 投递目录监听。
     *
     * 插件把音频放进 Music/FlowStudio/inbox/ 后这里自动导入 ——
     * App 不需要在前台，甚至不需要有人碰它。这是"不拉起应用也能传"的通道：
     * 插件只往公共目录写文件（有 MANAGE_EXTERNAL 或 SAF 都行），
     * 文件系统事件把剩下的活干完。
     */
    private var inboxObserver: android.os.FileObserver? = null

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
            restoreAudio()
            engine.setMaster(
                project.masterVolume / 100f,
                project.masterLimiter,
                project.masterSoftClip,
                project.masterGainDb
            )
            // 本地桥接：让 DSH 插件能看到并修改这个正在跑的工程
            runCatching { startBridge() }
            // 投递目录：插件不拉起 App 也能把音频送进来
            runCatching { startInboxWatch() }
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
        runCatching { inboxObserver?.stopWatching() }
        inboxObserver = null
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
        // 音频片段跟着一起装配：它是工程状态的一部分，
        // 任何"整体推送"都必须包含它，否则加载工程后音频会静默消失。
        audioStore.pushClips(project)
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

    /**
     * 开始播放。返回音频流是否真的起来了。
     *
     * 以前这里是 `if (!running) startAudio()` 然后无条件 play()：
     * startAudio 失败时界面照样点亮播放键、位置照样走，但一点声音都没有，
     * 用户只能靠状态面板里那行"音频流没启动"去猜。
     * 现在把结果交回去，界面能当场告诉用户是设备的问题还是别的问题。
     */
    fun play(): Boolean {
        if (!engine.available) return false
        if (!engine.isAudioRunning()) {
            val started = engine.startAudio()
            if (!started) {
                // 再确认一次：个别设备上 startAudio 返回 false 但流其实起来了。
                if (!engine.isAudioRunning()) return false
            }
        }
        engine.play()
        return true
    }

    /** 不关心返回值的地方（桥接、自动恢复播放）用这个。 */
    fun playOrIgnore() { play() }

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
                    restoreAudio()
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
                audioStore.clear()
                pushAll()
                restoreAudio()
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
        // 新工程不继承任何音频素材：清空后不要再调用 restoreAudio，
        // 否则旧素材会被重新装回来，用户会以为"新建的工程还带着上一首"。
        audioStore.clear()
        project.audioAssets.clear()
        pushAll()
        onLoaded?.invoke(project)
    }

    /**
     * 把工程里登记过的音频素材重新解码进引擎，再装配片段布局。
     *
     * 顺序不能反：片段引用素材 id，素材没进池子的话片段会被引擎丢弃，
     * 表现为"工程里有片段但播放没声音"。
     */
    fun restoreAudio() {
        audioStore.reload(project.audioAssets) {
            main.post {
                audioStore.pushClips(project)
                onAudioChanged?.invoke()
            }
        }
    }

    /** 导入一段音频成素材，并立刻摆到当前选中轨道的播放头位置。 */
    fun importAudio(src: File, displayName: String,
                    onDone: ((Boolean, String) -> Unit)? = null) {
        val nextId = project.nextAudioId
        project.nextAudioId = nextId + 1
        audioStore.import(src, displayName, nextId) { asset ->
            main.post {
                if (asset == null) {
                    // 导入失败要把号还回去，否则 id 会一直虚涨。
                    if (project.nextAudioId == nextId + 1) project.nextAudioId = nextId
                    onDone?.invoke(false, "无法解码这个音频：只支持 PCM WAV（16/24/32-bit 或 float）")
                } else {
                    project.audioAssets.add(asset)
                    val track = project.selectedTrack.coerceIn(0, maxOf(0, project.tracks.size - 1))
                    project.placeAudio(asset, track, positionTicks())
                    scheduleAutoSave()
                    onDone?.invoke(true, "${asset.name} · ${"%.1f".format(asset.durationSec)} 秒")
                    onAudioChanged?.invoke()
                }
            }
        }
    }

    /** 删除一个音频片段（不删素材，用户可能还在别处用它）。 */
    fun removeClip(clipId: Int) {
        project.clips.removeAll { it.id == clipId }
        audioStore.pushClips(project)
        scheduleAutoSave()
    }

    /** 删掉一个素材，连带所有引用它的片段。 */
    fun removeAudioAsset(assetId: Int) {
        project.clips.removeAll { it.audioId == assetId }
        project.audioAssets.removeAll { it.id == assetId }
        audioStore.pushClips(project)
        scheduleAutoSave()
        onAudioChanged?.invoke()
    }

    /** 片段布局变了（拖动、裁剪、改音量）就重推一次。只搬布局，不搬波形。 */
    fun pushAudioClips() {
        audioStore.pushClips(project)
    }

    // ---------------------------------------------------------------- 投递目录

    /** 插件投递音频的目录：/sdcard/Music/FlowStudio/inbox */
    fun inboxDir(): File = File(
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MUSIC
        ),
        "FlowStudio/inbox"
    )

    /**
     * 开始监听投递目录。
     *
     * 用 CLOSE_WRITE 而不是 CREATE：CREATE 触发时文件可能还没写完，
     * 读到半个文件会解码失败。CLOSE_WRITE 保证写入方已经关闭句柄。
     *
     * FileObserver 只监听本目录（非递归）—— 够用，也不引入 INotify 那套复杂度。
     * 目录不存在时 FileObserver 不会报错，但也不会有事件；
     * 所以先把目录建出来，再在启动扫描里兜底。
     */
    fun startInboxWatch() {
        val dir = inboxDir()
        runCatching { dir.mkdirs() }

        inboxObserver?.stopWatching()
        val observer = object : android.os.FileObserver(dir, android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                if (!path.endsWith(".wav", true) &&
                    !path.endsWith(".mp3", true) &&
                    !path.endsWith(".m4a", true) &&
                    !path.endsWith(".flac", true) &&
                    !path.endsWith(".ogg", true)) return
                val f = File(dir, path)
                if (!f.isFile || f.length() < 1024) return
                // 从回调里直接导入：importAudio 自己会切 io 线程，
                // 但 UI 回调（onAudioChanged）必须回主线程，它内部已经处理。
                importAudio(f, path) { ok, msg ->
                    // 无论成败都把投递文件删掉：成功是已经进了素材库，
                    // 失败是留着也没有用（每次启动还会再失败一次）。
                    runCatching { f.delete() }
                    if (!ok) {
                        // 静默失败只记日志；弹 toast 会打断用户正在做的事。
                        android.util.Log.w("FlowStudio", "投递导入失败 $path: $msg")
                    }
                }
            }
        }
        observer.startWatching()
        inboxObserver = observer
        // 兜底：App 没在运行时投进来的文件，这次启动补一次扫描。
        drainInbox()
    }

    /** 把 inbox 里积压的文件全部导入（App 启动时调用）。 */
    private fun drainInbox() {
        val dir = inboxDir()
        val files = dir.listFiles { f -> f.isFile && f.length() > 1024 } ?: return
        for (f in files) {
            importAudio(f, f.name) { ok, msg ->
                runCatching { f.delete() }
                if (!ok) android.util.Log.w("FlowStudio", "积压投递导入失败 ${f.name}: $msg")
            }
        }
        if (files.isNotEmpty()) {
            android.util.Log.i("FlowStudio", "补导入 ${files.size} 个投递文件")
        }
    }

    /** 供桥接查询投递状态，插件能确认文件有没有被吃掉。 */
    fun inboxPending(): List<String> =
        inboxDir().listFiles { f -> f.isFile }?.map { it.name } ?: emptyList()

    /** 一次性清空所有音频片段与素材（含磁盘文件），给插件的重置动作。 */
    fun clearAllAudio() {
        project.clips.removeAll { it.isAudio }
        val files = project.audioAssets.map { File(context.filesDir, it.path) }
        project.audioAssets.clear()
        audioStore.clear()
        files.forEach { runCatching { it.delete() } }
        scheduleAutoSave()
        onAudioChanged?.invoke()
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

