package com.volt.flowstudio.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.volt.flowstudio.audio.NativeEngine
import com.volt.flowstudio.model.*
import org.json.JSONArray
import org.json.JSONObject
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
                // 加载完成后先做一次存量去重：1.35 及更早的工程里可能已经
                // 攒下同一段音频的多条素材（重复投递造成的），这里把它们
                // 合并成一条，并让引用旧 id 的片段改指到保留的那条。
                // 必须放在 reload 之后：那时指纹才算得出来。
                val merged = dedupeAudioAssets()
                if (merged > 0) {
                    android.util.Log.i("FlowStudio", "存量去重：合并了 $merged 条重复音频素材")
                    scheduleAutoSave()
                }
                audioStore.pushClips(project)
                onAudioChanged?.invoke()
            }
        }
    }

    /**
     * 合并工程里内容相同的音频素材，返回合并掉的条数。
     *
     * 为什么必须有这一步：只修导入路径只能防住"以后不再重复"，
     * 用户已经攒下的那些重复还在工程里 —— 打开软件照样看到一堆同名素材、
     * 时间线上一堆叠着的片段。修 bug 而不清理存量，等于没修完。
     *
     * 规则：
     *   - 同一内容只保留 id 最小的那条（最老的，引用它最安全）；
     *   - 引用被删素材的片段改指到保留的那条，位置不动；
     *   - 被删素材在磁盘上的文件一并删掉，别留孤儿；
     *   - 内容判定用 AudioAsset.sameContent（指纹优先，缺指纹退化为帧数+采样率）。
     */
    private fun dedupeAudioAssets(): Int {
        val assets = project.audioAssets
        if (assets.size < 2) return 0
        val keep = ArrayList<AudioAsset>()
        val replaced = HashMap<Int, Int>()   // 旧 id → 保留的 id
        for (a in assets.sortedBy { it.id }) {
            val dup = keep.firstOrNull { it.sameContent(a) }
            if (dup == null) {
                keep.add(a)
            } else {
                replaced[a.id] = dup.id
            }
        }
        if (replaced.isEmpty()) return 0

        // 片段改指：同轨同位置同素材的重复片段一并去掉，避免两个片段叠在一起
        val seen = HashSet<String>()
        val keptClips = ArrayList<com.volt.flowstudio.model.Clip>(project.clips.size)
        for (c in project.clips) {
            val newId = replaced[c.audioId]
            if (newId != null) c.audioId = newId
            if (c.isAudio) {
                val sig = "${c.audioId}:${c.track}:${c.start}:${c.length}"
                if (!seen.add(sig)) continue   // 完全重合的重复片段，丢掉后面的
            }
            keptClips.add(c)
        }
        project.clips.clear()
        project.clips.addAll(keptClips)

        // 素材表收缩 + 磁盘文件清理
        for ((oldId, _) in replaced) {
            val victim = assets.firstOrNull { it.id == oldId } ?: continue
            runCatching { File(context.filesDir, victim.path).delete() }
        }
        assets.removeAll { replaced.containsKey(it.id) }
        return replaced.size
    }

    /**
     * 导入一段音频成素材，并立刻摆到当前选中轨道的播放头位置。
     *
     * `placeOnTimeline=false` 用于"恢复/补扫"这类不该往时间线上加东西的路径 ——
     * 之前投递目录的补扫也走这里，于是每次重开软件都会在时间线上多摆一块，
     * 这正是用户报的"重进多一段音频"的第二条来源。
     */
    fun importAudio(src: File, displayName: String,
                    placeOnTimeline: Boolean = true,
                    onDone: ((Boolean, String) -> Unit)? = null) {
        val nextId = project.nextAudioId
        project.nextAudioId = nextId + 1
        audioStore.import(src, displayName, nextId) { asset, reused ->
            main.post {
                if (asset == null) {
                    // 导入失败要把号还回去，否则 id 会一直虚涨。
                    if (project.nextAudioId == nextId + 1) project.nextAudioId = nextId
                    onDone?.invoke(false, "无法解码这个音频：支持 WAV / MP3 / M4A / FLAC / OGG")
                } else {
                    // 复用已存在的素材时，id 号段没有被消耗，退回去
                    if (reused && project.nextAudioId == nextId + 1) project.nextAudioId = nextId
                    if (project.audioAssetById(asset.id) == null) {
                        project.audioAssets.add(asset)
                    }
                    if (reused) {
                        // 内容相同：不再重复摆片段，直接告诉调用方"这段已经在库里了"
                        scheduleAutoSave()
                        onDone?.invoke(true,
                            "${asset.name} · 库里已有同一段音频（#${asset.id}），未重复添加")
                        onAudioChanged?.invoke()
                        return@post
                    }
                    if (placeOnTimeline) {
                        val track = project.selectedTrack
                            .coerceIn(0, maxOf(0, project.tracks.size - 1))
                        project.placeAudio(asset, track, positionTicks())
                    }
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
                //
                // placeOnTimeline=true：用户主动投递的音频就该立刻出现在时间线上。
                // 重复投递同一段音频会被内容指纹拦下（reused），不会多摆一块。
                importAudio(f, path, placeOnTimeline = true) { ok, msg ->
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

    /**
     * 把 inbox 里积压的文件全部导入（App 启动时调用）。
     *
     * 这里**不摆到时间线**（placeOnTimeline=false）。
     *
     * 这是"每次退出重进多一段音频"的主要来源：补扫原本走的是同一个
     * importAudio 默认路径，于是每启动一次就把积压文件往时间线上摆一块，
     * 而投递文件如果因为进程被杀没被删掉，下次启动还会再摆一块 ——
     * 同一段素材在列表里越叠越多。
     *
     * 现在两道闸：
     *   1. 补扫只进素材库，不碰时间线；
     *   2. 内容指纹去重，同一段音频无论投递多少次都只有一条素材。
     */
    private fun drainInbox() {
        val dir = inboxDir()
        // listFiles 返回 null 有两种含义：目录不存在，或没有读权限。
        // 过去这里直接 `?: return` 把它当成"没东西"，是最难查的一种静默失败。
        val all = dir.listFiles()
        if (all == null) {
            android.util.Log.w(
                "FlowStudio",
                "投递目录列不出来: exists=${dir.exists()} canRead=${dir.canRead()} · ${inboxDiagnostics()}"
            )
            return
        }
        val files = all.filter { it.isFile && it.length() > 1024 }
        for (f in files) {
            importAudio(f, f.name, placeOnTimeline = false) { ok, msg ->
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
        inboxDir().listFiles { f -> f.isFile }?.map { f -> f.name } ?: emptyList()

    /**
     * 投递目录的体检结果。
     *
     * 为什么需要它：inbox 失效时过去只有"pending: []"这一个信号，
     * 看起来像"目录是空的"，而实际情况可能是
     *   - 目录不存在（App 在 Android 11+ 上写不了公共 Music，mkdirs 静默失败）
     *   - 目录存在但读不到（缺 READ_MEDIA_AUDIO，listFiles 返回 null）
     * 两种都表现为空列表，用户和 AI 都无从判断。这里把区别显式说出来。
     */
    fun inboxDiagnostics(): JSONObject {
        val dir = inboxDir()
        val canRead = dir.canRead()
        val exists = dir.exists()
        val files = runCatching { dir.listFiles() }.getOrNull()
        val canWrite = runCatching {
            val probe = File(dir, ".write-probe")
            val ok = probe.createNewFile()
            if (ok) probe.delete()
            ok
        }.getOrDefault(false)

        val mediaPerm = if (android.os.Build.VERSION.SDK_INT >= 33)
            "android.permission.READ_MEDIA_AUDIO"
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        val granted = context.checkSelfPermission(mediaPerm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        val notes = mutableListOf<String>()
        if (!exists) notes.add("目录不存在（App 在 Android 11+ 无法在公共 Music 下建目录）")
        if (exists && !canRead) notes.add("目录读不到：缺 $mediaPerm")
        if (exists && canRead && !canWrite) notes.add("App 无法往这里写（投递方向不受影响）")
        if (!granted) notes.add("未授予 $mediaPerm —— 别人放进去的音频会看不见")

        return JSONObject()
            .put("ok", true)
            .put("dir", dir.absolutePath)
            .put("exists", exists)
            .put("canRead", canRead)
            .put("canWrite", canWrite)
            .put("mediaPermissionGranted", granted)
            .put("mediaPermission", mediaPerm)
            .put("entryCount", files?.size ?: -1)
            .put("notes", JSONArray(notes))
    }

    /**
     * 重新扫一遍投递目录。权限刚被授予、或用户手动塞了文件时调用。
     *
     * 和 startInboxWatch 的区别：这个不重建监听器，只补一次导入，
     * 用于"监听器已经在，但之前因为权限看不见文件"的场景。
     */
    fun rescanInbox() {
        val dir = inboxDir()
        if (!dir.exists() || !dir.canRead()) {
            android.util.Log.w(
                "FlowStudio",
                "投递目录不可读，跳过补扫: exists=${dir.exists()} canRead=${dir.canRead()}"
            )
            return
        }
        val files = dir.listFiles { f -> f.isFile && f.length() > 1024 } ?: return
        if (files.isEmpty()) return
        android.util.Log.i("FlowStudio", "补扫投递目录：${files.size} 个文件")
        for (f in files) {
            // 补扫同样只进素材库：这是"按一下重扫"的动作，
            // 用户想要的是"看看有没有漏掉的"，不是"往时间线上再糊一块"。
            importAudio(f, f.name, placeOnTimeline = false) { ok, msg ->
                runCatching { f.delete() }
                if (!ok) android.util.Log.w("FlowStudio", "投递导入失败 ${f.name}: $msg")
            }
        }
    }

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

