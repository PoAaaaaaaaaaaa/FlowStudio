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
            tabs = listOf("步进器", "钢琴卷帘", "播放列表", "混音台", "自动化")
        }
        root.addView(tabBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(30)
        ).apply { gravity = android.view.Gravity.TOP; topMargin = dp(44) })

        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(74) })

        // 步进器
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
        transportBar.onExport = { doExport() }

        tabBar.onTabSelected = { showTab(it) }

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
            0 -> { steps.playheadTick = pos; }
            1 -> {
                piano.playheadTick = pos
                piano.scrollToPlayhead(pos)
            }
            2 -> {
                playlist.playheadTick = pos
                if (session.isPlaying()) playlist.scrollToPlayheadIfNeeded(pos)
            }
            3 -> {
                mixer.levels = session.engine.trackLevels()
                val pk = session.engine.masterPeak()
                mixer.masterL = pk.getOrElse(0) { 0f }
                mixer.masterR = pk.getOrElse(1) { 0f }
            }
            4 -> auto.playheadTick = pos
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

    private fun doExport() {
        val p = session.project
        toast("开始渲染…")
        session.exportWav(0, p.lengthTicks()) { file ->
            if (file != null) {
                toast("导出到 ${file.absolutePath}")
                session.shareFile(file)
            } else {
                toast("导出失败")
            }
        }
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
