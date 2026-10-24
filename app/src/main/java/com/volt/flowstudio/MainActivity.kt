package com.volt.flowstudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.volt.flowstudio.model.Project
import com.volt.flowstudio.session.StudioSession
import com.volt.flowstudio.ui.*

/**
 * 主界面。整个 UI 用代码搭出来，不用 XML 布局：
 * DAW 的每个像素都要自己算，布局文件反而碍事。
 */
class MainActivity : Activity() {

    private lateinit var session: StudioSession
    private val ui = Handler(Looper.getMainLooper())

    // 视图
    private lateinit var transportBar: TransportBar
    private lateinit var tabBar: TabBar
    private lateinit var content: FrameLayout
    private lateinit var steps: StepSequencerView
    private lateinit var piano: PianoRollView
    private lateinit var playlist: PlaylistView
    private lateinit var mixer: MixerView
    private lateinit var auto: AutomationView
    private lateinit var keys: KeyboardView
    private lateinit var rack: ChannelRackView
    private lateinit var banner: android.widget.TextView

    private var currentTab = 0    // 0=步进 1=钢琴卷帘 2=播放列表 3=混音台 4=自动化

    private val ticker = object : Runnable {
        override fun run() {
            refreshPlayhead()
            ui.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = (application as FlowApp).session
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        bindSession()
        showTab(0)
        showBootDiagnostics()
        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        session.saveNow(sync = true)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 搭界面

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }

        transportBar = TransportBar(this)
        root.addView(transportBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { gravity = android.view.Gravity.TOP })

        tabBar = TabBar(this).apply {
            tabs = listOf("机架", "卷帘", "播放列表", "混音台", "自动化", "钢琴")
        }
        root.addView(tabBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(30)
        ).apply { gravity = android.view.Gravity.TOP; topMargin = dp(44) })

        // 诊断条：启动有问题时显示，正常时完全隐藏
        banner = TextView(this).apply {
            setBackgroundColor(0xFF7A1D18.toInt())
            setTextColor(0xFFFFE4E0.toInt())
            textSize = 11f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            setOnClickListener { showDiagnostics() }
        }
        root.addView(banner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.TOP; topMargin = dp(74) })

        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(74) })

        // 0. 通道机架（FL 的骨架界面）
        rack = ChannelRackView(this)
        content.addView(rack, matchParams())

        // 步进器（保留，作为机架的放大版）
        steps = StepSequencerView(this)
        content.addView(steps, matchParams())

        // 钢琴卷帘 + 键盘
        val pianoHolder = FrameLayout(this)
        piano = PianoRollView(this)
        keys = KeyboardView(this).apply { noteCount = 24 }
        pianoHolder.addView(piano, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { bottomMargin = dp(92) })
        pianoHolder.addView(keys, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(92)
        ).apply { gravity = android.view.Gravity.BOTTOM })
        content.addView(pianoHolder, matchParams())

        // 播放列表
        playlist = PlaylistView(this)
        content.addView(playlist, matchParams())

        // 混音台
        mixer = MixerView(this)
        content.addView(mixer, matchParams())

        // 自动化
        auto = AutomationView(this)
        content.addView(auto, matchParams())

        setContentView(root)
    }

    private fun matchParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun bindSession() {
        transportBar.onPlay = play@{
            if (session.isPlaying()) {
                session.pause()
                transportBar.setPlaying(false)
            } else {
                if (session.project.tracks.isEmpty()) return@play
                AudioService.start(this)
                session.play()
                transportBar.setPlaying(true)
            }
        }
        transportBar.onStop = {
            session.stop()
            transportBar.setPlaying(false)
            refreshPlayhead()
        }
        transportBar.onBpmChange = { bpm ->
            session.project.bpm = bpm
            session.pushBpm()
            session.scheduleAutoSave()
        }
        transportBar.onLoopToggle = {
            val p = session.project
            session.setLoop(!p.loopOn, p.loopStart, p.loopEnd)
            playlist.loopOn = p.loopOn
            playlist.loopStart = p.loopStart
            playlist.loopEnd = p.loopEnd
            playlist.invalidate()
            transportBar.setLoop(p.loopOn)
        }
        transportBar.onMetronomeToggle = { on -> session.setMetronome(on) }
        transportBar.onSave = { session.save(null) }
        transportBar.onExport = { showExportDialog() }
        transportBar.onProject = { showProjectDialog() }
        transportBar.onSwing = { showSwingDialog() }

        tabBar.onTabSelected = { showTab(it) }

        // ---- 通道机架 ----
        rack.onSelectTrack = { idx ->
            session.project.selectedTrack = idx
            refreshKeyboardForTrack()
            rack.invalidate()
            steps.invalidate()
        }
        rack.onToggleStep = step@{ track, tick ->
            val p = session.project
            val pat = p.patternAt(p.currentPattern) ?: return@step
            val notes = pat.notes(track)
            val existing = notes.firstOrNull { it.start == tick }
            if (existing != null) {
                notes.remove(existing)
            } else {
                val inst = p.trackAt(track)?.instrument
                val pitch = when (inst) {
                    com.volt.flowstudio.model.Instrument.DRUM -> 36
                    com.volt.flowstudio.model.Instrument.BASS -> 36
                    else -> 60
                }
                notes.add(com.volt.flowstudio.model.Note(pitch, tick, com.volt.flowstudio.model.Note.STEP, 100))
                notes.sortBy { it.start }
                session.previewNote(track, pitch, 100, 160)
            }
            session.pushBpm()
            session.scheduleAutoSave()
            rack.invalidate()
            steps.invalidate()
        }
        rack.onToggleMute = { idx ->
            session.project.trackAt(idx)?.let { it.mute = !it.mute }
            session.pushTrack(idx)
            session.scheduleAutoSave()
            rack.invalidate()
        }
        rack.onToggleSolo = { idx ->
            session.project.trackAt(idx)?.let { it.solo = !it.solo }
            session.pushAll()
            session.scheduleAutoSave()
            rack.invalidate()
        }
        rack.onVolumeChange = { idx, _ ->
            session.pushTrack(idx)
            session.scheduleAutoSave()
        }
        rack.onOpenInstrument = { idx -> openSoundPanel(idx) }
        rack.onPreview = { idx ->
            val inst = session.project.trackAt(idx)?.instrument
            val pitch = if (inst == com.volt.flowstudio.model.Instrument.DRUM) 36 else 60
            session.previewNote(idx, pitch, 100, 480)
        }
        rack.onAddTrack = { showAddTrackDialog() }
        rack.onRemoveTrack = { idx -> removeTrack(idx) }

        // ---- 步进器 ----
        steps.onToggleStep = step@{ track, tick ->
            val p = session.project
            val pat = p.patternAt(p.currentPattern) ?: return@step
            val notes = pat.notes(track)
            val existing = notes.firstOrNull { it.start == tick }
            if (existing != null) {
                notes.remove(existing)
                session.previewNote(track, existing.pitch, 0)
            } else {
                val inst = session.project.trackAt(track)?.instrument
                val pitch = when (inst) {
                    com.volt.flowstudio.model.Instrument.DRUM -> steps.baseNote
                    com.volt.flowstudio.model.Instrument.BASS -> 36
                    else -> 60
                }
                notes.add(com.volt.flowstudio.model.Note(pitch, tick, com.volt.flowstudio.model.Note.STEP, 100))
                notes.sortBy { it.start }
                session.previewNote(track, pitch, 100, 180)
            }
            session.pushBpm()
            session.scheduleAutoSave()
            steps.pattern = pat
            steps.invalidate()
        }
        steps.onPreviewTrack = { track, pitch -> session.previewNote(track, pitch, 100, 300) }
        steps.onSelectTrack = { idx -> session.project.selectedTrack = idx; session.pushTrack(idx) }
        steps.instrumentOf = { idx -> session.project.trackAt(idx)?.instrument ?: com.volt.flowstudio.model.Instrument.SYNTH }

        // ---- 钢琴卷帘 ----
        piano.onNotesChanged = {
            session.pushBpm()
            session.scheduleAutoSave()
        }
        piano.onPreview = { pitch, vel ->
            session.previewNote(session.project.selectedTrack, pitch, vel, com.volt.flowstudio.model.Note.STEP * 2)
        }

        // ---- 键盘 ----
        keys.onNoteOn = { pitch, vel ->
            val track = session.project.selectedTrack
            session.previewNote(track, pitch, vel, com.volt.flowstudio.model.Note.BAR * 2)
        }
        keys.onNoteOff = { pitch ->
            session.releaseNote(session.project.selectedTrack)
        }

        // ---- 播放列表 ----
        playlist.onSeek = { tick -> session.seek(tick); refreshPlayhead() }
        playlist.onClipsChanged = { session.pushBpm(); session.scheduleAutoSave() }
        playlist.onSelectTrack = { idx -> session.project.selectedTrack = idx }
        playlist.onLoopChanged = { on, s, e ->
            session.setLoop(on, s, e)
            transportBar.setLoop(on)
        }

        // ---- 混音台 ----
        mixer.onTrackChanged = { idx -> session.pushTrack(idx); session.scheduleAutoSave() }
        mixer.onMasterChanged = { session.pushMaster(); session.scheduleAutoSave() }
        mixer.onSelectTrack = { idx -> session.project.selectedTrack = idx }
        mixer.onOpenSound = { idx -> openSoundPanel(idx) }
        mixer.onOpenFx = { idx, slot -> openFxPanel(idx, slot) }

        // ---- 自动化 ----
        auto.onChanged = { session.pushAutomation(); session.scheduleAutoSave() }

        session.onError = { msg -> toast(msg) }
        session.onSaved = { ok -> toast(if (ok) "已保存" else "保存失败") }
        session.onLoaded = { p -> refreshAll(p) }
    }

    private fun refreshAll(p: Project) {
        transportBar.bpm = p.bpm
        transportBar.setLoop(p.loopOn)
        rack.project = p
        rack.currentPatternIndex = p.currentPattern
        rack.selectedTrack = p.selectedTrack
        rack.invalidate()
        steps.pattern = p.patternAt(p.currentPattern)
        steps.trackNames = p.tracks.map { it.name }
        steps.trackColors = p.tracks.indices.map { Palette.trackColor(it) }
        steps.invalidate()
        piano.pattern = p.patternAt(p.currentPattern)
        piano.trackIndex = p.selectedTrack
        piano.invalidate()
        playlist.project = p
        playlist.loopOn = p.loopOn
        playlist.loopStart = p.loopStart
        playlist.loopEnd = p.loopEnd
        playlist.selectedTrack = p.selectedTrack
        playlist.invalidate()
        mixer.project = p
        mixer.selectedTrack = p.selectedTrack
        mixer.invalidate()
        refreshKeyboardForTrack()
    }

    private fun showTab(index: Int) {
        currentTab = index
        // 页签顺序变了，确保索引不越界
        if (index !in 0 until content.childCount) return
        for (i in 0 until content.childCount) {
            content.getChildAt(i).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabBar.selected = index
        tabBar.invalidate()
        refreshAll(session.project)
    }

    private fun refreshKeyboardForTrack() {
        val track = session.project.trackAt(session.project.selectedTrack)
        val inst = track?.instrument
        keys.isDrum = inst == com.volt.flowstudio.model.Instrument.DRUM
        keys.lowestNote = when (inst) {
            com.volt.flowstudio.model.Instrument.DRUM -> 36
            com.volt.flowstudio.model.Instrument.BASS -> 24
            com.volt.flowstudio.model.Instrument.BELL -> 60
            else -> 48
        }
        keys.invalidate()
    }

    private fun refreshPlayhead() {
        if (!session.engine.available) return
        val pos = session.positionTicks()
        transportBar.positionTicks = pos
        transportBar.bpm = session.project.bpm
        transportBar.setPlaying(session.isPlaying())
        when (currentTab) {
            0 -> {
                rack.playheadTick = pos
                rack.levels = session.engine.trackLevels()
                rack.invalidate()
            }
            1 -> { steps.playheadTick = pos; }
            2 -> {
                piano.playheadTick = pos
                piano.scrollToPlayhead(pos)
            }
            3 -> {
                playlist.playheadTick = pos
                if (session.isPlaying()) playlist.scrollToPlayheadIfNeeded(pos)
            }
            4 -> {
                mixer.levels = session.engine.trackLevels()
                val pk = session.engine.masterPeak()
                mixer.masterL = pk.getOrElse(0) { 0f }
                mixer.masterR = pk.getOrElse(1) { 0f }
            }
            5 -> auto.playheadTick = pos
        }
    }

    // ---------------------------------------------------------------- 面板

    private fun openSoundPanel(trackIndex: Int) {
        val track = session.project.trackAt(trackIndex) ?: return
        val panel = SoundPanel(this, session, trackIndex) { session.pushTrack(trackIndex); session.scheduleAutoSave() }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.72).toInt(),
            (resources.displayMetrics.heightPixels * 0.82).toInt()
        )
    }

    private fun openFxPanel(trackIndex: Int, slot: Int) {
        val panel = FxPanel(this, session, trackIndex, slot) { session.pushTrack(trackIndex); session.scheduleAutoSave() }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.60).toInt(),
            (resources.displayMetrics.heightPixels * 0.62).toInt()
        )
    }

    /** 一键导出（默认 44100） */
    private fun doExport() {
        val p = session.project
        toast("渲染中…")
        session.exportWav(0, p.lengthTicks(), 44100) { file ->
            if (file != null) {
                toast("已导出 ${file.name}")
                session.shareFile(file)
            } else {
                toast("导出失败")
            }
        }
    }

    /** 启动阶段有问题就把原因顶在界面上，别让用户猜。 */
    private fun showBootDiagnostics() {
        val err = (application as FlowApp).bootError
        if (err.isNullOrBlank()) return
        banner.text = "⚠ $err  （点这里看详情）"
        banner.visibility = View.VISIBLE
        // 诊断条会盖住内容，把内容整体下移
        (content.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = dp(74) + dp(46)
            content.layoutParams = it
        }
    }

    /** 崩溃日志全文，支持复制或分享给开发者。 */
    private fun showDiagnostics() {
        val app = application as FlowApp
        val log = buildString {
            appendLine("== 启动状态 ==")
            appendLine(app.bootError ?: "正常")
            appendLine()
            appendLine("== 原生库 ==")
            appendLine(NativeLib.status)
            appendLine()
            appendLine("== 崩溃日志 ==")
            appendLine(CrashGuard.fullLog(this@MainActivity))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("诊断信息")
            .setMessage(log)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("FlowStudio 诊断", log))
                toast("已复制")
            }
            .setNeutralButton("分享") { _, _ ->
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, log)
                }
                startActivity(android.content.Intent.createChooser(i, "分享诊断信息"))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------------------------------------------------------------- 通道与工程

    /** 添加通道：先选乐器，再给个名字 */
    private fun showAddTrackDialog() {
        val insts = com.volt.flowstudio.model.Instrument.values()
        val names = insts.map { it.cn }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("添加通道")
            .setItems(names) { _, which ->
                val inst = insts[which]
                // 用该乐器第一个预设，避免"新建出来是个哑音色"
                val preset = com.volt.flowstudio.model.Presets.forInstrument(inst).firstOrNull()
                val idx = session.project.addTrack(inst, preset?.name ?: inst.cn)
                if (preset != null) {
                    session.project.trackAt(idx)?.let { com.volt.flowstudio.model.Presets.apply(preset, it) }
                }
                session.pushAll()
                session.scheduleAutoSave()
                refreshAll(session.project)
                toast("已添加 ${inst.cn}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeTrack(idx: Int) {
        val p = session.project
        val t = p.trackAt(idx) ?: return
        if (p.tracks.size <= 1) {
            toast("至少要留一条通道")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("删除通道「${t.name}」？")
            .setMessage("这个通道在播放列表里的片段和所有样式里的音符都会一起删掉。")
            .setPositiveButton("删除") { _, _ ->
                // 删轨道时把相关的东西都清掉，否则会留下指向不存在轨道的片段
                p.patterns.forEach { pat -> pat.tracks.remove(idx) }
                // 比 idx 大的轨道下标整体前移
                p.patterns.forEach { pat ->
                    val moved = mutableMapOf<Int, MutableList<com.volt.flowstudio.model.Note>>()
                    pat.tracks.forEach { (k, v) ->
                        moved[if (k > idx) k - 1 else k] = v
                    }
                    pat.tracks.clear()
                    pat.tracks.putAll(moved)
                }
                p.clips.removeAll { it.track == idx }
                p.clips.forEach { if (it.track > idx) it.track -= 1 }
                p.automations.removeAll { it.track == idx }
                p.automations.forEach { if (it.track > idx) it.track -= 1 }

                p.tracks.removeAt(idx)
                if (p.selectedTrack >= p.tracks.size) p.selectedTrack = (p.tracks.size - 1).coerceAtLeast(0)
                session.pushAll()
                session.scheduleAutoSave()
                refreshAll(p)
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 工程管理：新建、打开、另存、删除 */
    private fun showProjectDialog() {
        val files = session.listProjects()
        val items = mutableListOf<String>()
        items.add("＋ 新建空工程")
        items.add("＋ 新建示例工程")
        items.add("💾 另存为…")
        if (files.isNotEmpty()) items.add("──────────")
        files.forEach { items.add("📂 ${it.nameWithoutExtension}") }

        android.app.AlertDialog.Builder(this)
            .setTitle("工程")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        session.newProject(false)
                        refreshAll(session.project)
                        toast("已新建")
                    }
                    which == 1 -> {
                        session.newProject(true)
                        refreshAll(session.project)
                        toast("已载入示例")
                    }
                    which == 2 -> askSaveAs()
                    items[which].startsWith("📂") -> {
                        val name = items[which].removePrefix("📂 ").trim()
                        val f = files.firstOrNull { it.nameWithoutExtension == name }
                        if (f != null) {
                            session.load(f) { ok -> if (ok) toast("已打开 $name") }
                        }
                    }
                }
            }
            .setNeutralButton("删除工程…") { _, _ -> showDeleteProjectDialog(files) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun askSaveAs() {
        val input = android.widget.EditText(this).apply {
            setText(session.project.name)
            setSelectAllOnFocus(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("另存为")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "未命名" }
                session.save(name) { ok -> toast(if (ok) "已保存 $name" else "保存失败") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteProjectDialog(files: List<java.io.File>) {
        if (files.isEmpty()) {
            toast("没有可删除的工程")
            return
        }
        val names = files.map { it.nameWithoutExtension }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("删除工程")
            .setItems(names) { _, which ->
                val f = files[which]
                android.app.AlertDialog.Builder(this)
                    .setTitle("确认删除「${f.nameWithoutExtension}」？")
                    .setMessage("文件会被删掉，不能恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        val ok = runCatching { f.delete() }.getOrDefault(false)
                        toast(if (ok) "已删除" else "删除失败")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 导出选项：采样率、范围 */
    private fun showExportDialog() {
        val rates = arrayOf("44100 Hz（CD 标准）", "48000 Hz（视频常用）", "22050 Hz（小文件）")
        val rateVals = intArrayOf(44100, 48000, 22050)
        var chosen = 0
        android.app.AlertDialog.Builder(this)
            .setTitle("导出 WAV")
            .setSingleChoiceItems(rates, 0) { _, which -> chosen = which }
            .setPositiveButton("开始") { _, _ ->
                val p = session.project
                val sr = rateVals[chosen]
                toast("渲染中…")
                session.exportWav(0, p.lengthTicks(), sr) { file ->
                    if (file != null) {
                        toast("已导出 ${file.name}")
                        session.shareFile(file)
                    } else {
                        toast("导出失败")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 摇摆量调节，放长按播放条出口太重，这里给个显式入口 */
    private fun showSwingDialog() {
        val p = session.project
        val sb = android.widget.SeekBar(this).apply {
            max = 100
            progress = (p.swing * 100).toInt()
        }
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(android.widget.TextView(this@MainActivity).apply {
                text = "摇摆量：奇数个 16 分音符往后推，0 为正拍"
                setTextColor(Palette.TEXT_DIM)
                textSize = 12f
            })
            addView(sb)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("摇摆 / Swing")
            .setView(wrap)
            .setPositiveButton("应用") { _, _ ->
                p.swing = sb.progress / 100f
                session.pushBpm()
                session.scheduleAutoSave()
                rack.invalidate()
                toast("摇摆 ${sb.progress}%")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // 音频权限在录音功能启用时才要；这里预留
    private fun ensureAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        return false
    }
}
