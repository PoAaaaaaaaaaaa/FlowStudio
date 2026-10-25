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
    private var aiBanner: FlowButton? = null
    private var currentDialog: android.app.Dialog? = null

    private var currentTab = 0    // 机架 / 步进 / 卷帘 / 编排 / 混音 / 自动化
    private var chromeTop = 0
    private var chromeBottom = 0
    private var rootLayout: FrameLayout? = null
    private var contentView: FrameLayout? = null
    private var tabBarView: TabBar? = null
    private var transportBarView: TransportBar? = null
    private var footerBar: FlowButton? = null
    private var insetTop = 0
    private var insetBottom = 0
    private var keyboardHeight = 0
    private var toolBarHeight = 0
    /** 诊断条出现时内容需要额外下移的高度；由 layoutChrome 统一参与计算。 */
    private var bannerExtra = 0
    private var bannerHeight = 0

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

    override fun onPause() {
        // 来电、切后台或锁屏都先落盘，避免只等 onDestroy 时丢编辑。
        session.saveNow(sync = false)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        session.saveNow(sync = true)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 搭界面

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        root.addView(StudioBackground(this), matchParams())
        // 1024x640dp 这类矮宽屏上，固定 92dp 键盘 + 42dp 工具栏会把卷帘挤扁。
        val screenDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        keyboardHeight = dp(if (screenDp < 700f) 68 else 82)
        toolBarHeight = dp(if (screenDp < 700f) 38 else 42)

        // 结构：inset(状态栏) → 页签 44dp → 内容 → 底栏 60dp → 说明 26dp → inset(导航栏)
        // 所有高度都从 insets 推，避免在 1024x640dp 这类宽屏上叠出一条压短内容的空白。
        transportBar = TransportBar(this).apply { bottomMode = true }
        transportBarView = transportBar
        root.addView(transportBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(60)
        ).apply { gravity = android.view.Gravity.BOTTOM })

        footerBar = FlowButton(this).apply {
            text = "手势与操作指南  ·  FlowStudio 1.16"
            textSize = 9f
            minHeight = dp(26)
            minimumHeight = dp(26)
            setPadding(dp(14), 0, dp(14), 0)
            buttonColor = Palette.BG
            foregroundColor = Palette.TEXT_FAINT
            setOnClickListener { showGestureGuide() }
        }
        root.addView(footerBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(26)
        ).apply { gravity = android.view.Gravity.BOTTOM })

        tabBar = TabBar(this).apply {
            tabs = listOf("机架", "步进", "卷帘", "编排", "混音", "自动化")
        }
        tabBarView = tabBar
        root.addView(tabBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { gravity = android.view.Gravity.TOP })

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
        ).apply { gravity = android.view.Gravity.TOP })

        content = FrameLayout(this)
        contentView = content
        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        rootLayout = root
        // 真实系统栏尺寸在第一次布局前拿不到，用监听器统一算一遍。
        // 项目零第三方依赖，这里直接用平台的 WindowInsets，不引入 androidx。
        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        root.requestApplyInsets()

        // 0. 通道机架（FL 的骨架界面）
        rack = ChannelRackView(this)
        content.addView(rack, matchParams())

        // 1. 步进器：保留独立的大网格编辑页。
        val stepsHolder = FrameLayout(this)
        steps = StepSequencerView(this)
        addToolbar(stepsHolder, "步进音序器", "点按或横向刷过格子")
        stepsHolder.addView(steps, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = toolBarHeight })
        content.addView(stepsHolder, matchParams())

        // 2. 钢琴卷帘 + 键盘 + 明确的编辑工具入口。
        val pianoHolder = FrameLayout(this)
        piano = PianoRollView(this)
        keys = KeyboardView(this).apply { noteCount = 24 }
        addToolbar(pianoHolder, "钢琴卷帘", "双指缩放 · 拖右缘改时长")
        pianoHolder.addView(piano, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = toolBarHeight; bottomMargin = keyboardHeight })
        pianoHolder.addView(keys, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, keyboardHeight
        ).apply { gravity = android.view.Gravity.BOTTOM })
        content.addView(pianoHolder, matchParams())

        // 3. 播放列表。
        val playlistHolder = FrameLayout(this)
        playlist = PlaylistView(this)
        addToolbar(playlistHolder, "编排时间线", "拖动片段 · 双指平移 · 标尺划循环")
        playlistHolder.addView(playlist, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = toolBarHeight })
        content.addView(playlistHolder, matchParams())

        // 4. 混音台。
        val mixerHolder = FrameLayout(this)
        mixer = MixerView(this)
        addToolbar(mixerHolder, "混音台", "点通道名调音色 · 点 FX 槽调效果")
        mixerHolder.addView(mixer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = toolBarHeight })
        content.addView(mixerHolder, matchParams())

        // 5. 自动化。
        val autoHolder = FrameLayout(this)
        auto = AutomationView(this)
        addToolbar(autoHolder, "自动化", "绘制参数随时间变化的曲线")
        autoHolder.addView(auto, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = toolBarHeight })
        content.addView(autoHolder, matchParams())

        setContentView(root)
    }

    /**
     * 由真实系统栏 insets 推出四段高度。
     *
     * 之前固定写死 topMargin=20 / bottomMargin=34，在 1024x640dp 的宽屏上
     * 会与系统导航栏叠加，既压短内容又把底栏推离底边——这是"位置不对"的根因。
     */
    private fun applyInsets(insets: android.view.WindowInsets) {
        val sysTop = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        val sysBottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        insetTop = sysTop
        insetBottom = sysBottom
        layoutChrome()
    }

    private fun layoutChrome() {
        val tabH = dp(44)
        val transportH = dp(60)
        val footerH = dp(26)
        val gap = dp(2)
        chromeTop = insetTop + tabH + gap
        chromeBottom = insetBottom + transportH + footerH + gap

        rootLayout?.let { root ->
            tabBarView?.let { bar ->
                val lp = bar.layoutParams as FrameLayout.LayoutParams
                lp.gravity = android.view.Gravity.TOP
                lp.topMargin = insetTop
                lp.bottomMargin = 0
                bar.layoutParams = lp
            }
            contentView?.let { c ->
                val lp = c.layoutParams as FrameLayout.LayoutParams
                lp.topMargin = chromeTop + bannerExtra
                lp.bottomMargin = chromeBottom
                // 左右留白让内容不贴边，宽屏上观感差别很明显。
                lp.leftMargin = dp(6)
                lp.rightMargin = dp(6)
                c.layoutParams = lp
            }
            transportBarView?.let { t ->
                val lp = t.layoutParams as FrameLayout.LayoutParams
                lp.gravity = android.view.Gravity.BOTTOM
                lp.bottomMargin = insetBottom + footerH
                t.layoutParams = lp
            }
            footerBar?.let { f ->
                val lp = f.layoutParams as FrameLayout.LayoutParams
                lp.gravity = android.view.Gravity.BOTTOM
                lp.bottomMargin = insetBottom
                f.layoutParams = lp
            }
            banner?.let { b ->
                val lp = b.layoutParams as FrameLayout.LayoutParams
                lp.topMargin = insetTop + tabH + dp(2)
                b.layoutParams = lp
            }
        }
    }

    private fun matchParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun addToolbar(holder: FrameLayout, title: String, subtitle: String): EditorToolbar {
        val toolbar = EditorToolbar(this).apply {
            this.title = title
            this.subtitle = subtitle
            onSwipePage = { direction -> showTab((currentTab + direction).coerceIn(0, 5)) }
        }
        holder.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, toolBarHeight
        ))
        return toolbar
    }

    private fun configurePianoTools(toolbar: EditorToolbar) {
        toolbar.actions = listOf(
            "吸附 ${if (piano.snap == com.volt.flowstudio.model.Note.STEP) "1/16" else "1/8"}" to {
                piano.snap = if (piano.snap == com.volt.flowstudio.model.Note.STEP)
                    com.volt.flowstudio.model.Note.STEP * 2 else com.volt.flowstudio.model.Note.STEP
                configurePianoTools(toolbar)
            },
            "量化" to {
                piano.quantizeSelected()
                toast("已量化${if (piano.selected.isEmpty()) "全部" else "选中"}音符")
            },
            "删除" to {
                val count = piano.deleteSelected()
                toast(if (count > 0) "已删除 $count 个音符" else "请先点选要删除的音符")
            },
            "−" to { piano.zoom = piano.zoom / 1.35f },
            "＋" to { piano.zoom = piano.zoom * 1.35f }
        )
    }

    private fun configurePlaylistTools(toolbar: EditorToolbar) {
        toolbar.actions = listOf(
            "循环 ${if (session.project.loopOn) "开" else "关"}" to {
                transportBar.onLoopToggle?.invoke()
                configurePlaylistTools(toolbar)
            },
            "定位" to { session.seek(0); refreshPlayhead(); toast("已回到开头") },
            "缩小" to { playlist.pixelsPerBar = if (playlist.pixelsPerBar <= 0f) dp(42).toFloat() else (playlist.pixelsPerBar / 1.35f).coerceAtLeast(dp(28).toFloat()); playlist.invalidate() },
            "放大" to { playlist.pixelsPerBar = if (playlist.pixelsPerBar <= 0f) dp(120).toFloat() else (playlist.pixelsPerBar * 1.35f).coerceAtMost(dp(360).toFloat()); playlist.invalidate() }
        )
    }

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
        // 后退/快进：轻点一拍，长按连续一小节；夹在工程长度内。
        transportBar.barTicks = session.project.barTicks
        transportBar.onSeekRelative = { delta ->
            val p = session.project
            val len = p.lengthTicks()
            val target = (session.positionTicks() + delta).coerceIn(0, len)
            session.seek(target)
            transportBar.positionTicks = target
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
        transportBar.onMetronomeToggle = { on ->
            session.setMetronome(on)
            transportBar.setMetronome(on)
        }
        transportBar.onSave = {
            session.save(null) { ok -> toast(if (ok) "工程已保存" else "保存失败，请检查存储空间") }
        }
        transportBar.onExport = { showExportDialog() }
        transportBar.onProject = { showProjectDialog() }
        transportBar.onSwing = { showSwingDialog() }
        transportBar.onBpmTap = { showBpmDialog() }
        transportBar.onMore = { showMoreDialog() }

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
        rack.onTrackActions = { idx -> showTrackActions(idx) }

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
        // 自动保存保持安静，只有手动保存显示结果。
        session.onSaved = { ok -> if (!ok) toast("自动保存失败，请立即手动保存") }
        session.onLoaded = { p -> refreshAll(p) }
        // AI/插件写入后立刻刷新当前页面，并在底部显示可撤销提示。
        session.onExternalEdit = { label ->
            refreshAll(session.project)
            showAiEditBar(label)
        }
    }

    /** 外部编辑提示条：明确告诉用户谁改了什么，并提供一键撤销。 */
    private fun showAiEditBar(label: String) {
        aiBanner?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val bar = FlowButton(this).apply {
            text = "AI 已${label} · 点此撤销"
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            buttonColor = Palette.withAlpha(Palette.CYAN, 60)
            foregroundColor = Palette.TEXT
            setOnClickListener {
                val ok = session.undoExternalEdit()
                toast(if (ok) "已撤销 AI 的改动" else "没有可撤销的改动")
                refreshAll(session.project)
                (parent as? android.view.ViewGroup)?.removeView(this)
            }
        }
        aiBanner = bar
        val root = (content.parent as? FrameLayout) ?: return
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(36)
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = chromeBottom + dp(8)
            leftMargin = dp(12)
            rightMargin = dp(12)
        })
        ui.postDelayed({
            if (aiBanner === bar) {
                (bar.parent as? android.view.ViewGroup)?.removeView(bar)
                if (aiBanner === bar) aiBanner = null
            }
        }, 12_000)
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
        if (index !in 0 until content.childCount) return
        currentTab = index
        for (i in 0 until content.childCount) {
            val page = content.getChildAt(i)
            page.animate().cancel()
            page.visibility = if (i == index) View.VISIBLE else View.GONE
            page.alpha = 1f
            page.translationX = 0f
        }
        content.getChildAt(index).animate().alpha(0.65f).alpha(1f).setDuration(130L).start()
        ((content.getChildAt(index) as? FrameLayout)?.getChildAt(0) as? EditorToolbar)?.let { toolbar ->
            when (index) {
                2 -> configurePianoTools(toolbar)
                3 -> configurePlaylistTools(toolbar)
            }
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
        transportBar.barTicks = session.project.barTicks
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
        showStudioDialog(panel, 0.84f, 0.86f)
    }

    private fun openFxPanel(trackIndex: Int, slot: Int) {
        val panel = FxPanel(this, session, trackIndex, slot) { session.pushTrack(trackIndex); session.scheduleAutoSave() }
        showStudioDialog(panel, 0.76f, 0.76f)
    }

    private fun showStudioDialog(panel: View, widthRatio: Float, heightRatio: Float) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * widthRatio).toInt().coerceAtMost(dp(900)),
            (resources.displayMetrics.heightPixels * heightRatio).toInt()
        )
        dialog.window?.setGravity(android.view.Gravity.CENTER)
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            dimAmount = 0.62f
            windowAnimations = android.R.style.Animation_Dialog
        }
    }

    private fun showBpmDialog() {
        val input = android.widget.EditText(this).apply {
            setText(String.format("%.1f", session.project.bpm))
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_FAINT)
            background = panelBackground(this@MainActivity, 10f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            hint = "40.0 – 260.0"
        }
        val wrap = android.widget.FrameLayout(this).apply { setPadding(dp(24), dp(12), dp(24), 0) }
        wrap.addView(input)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("精确 BPM")
            .setMessage("输入 40 到 260；也可在走带条上下拖动。")
            .setView(wrap)
            .setPositiveButton("应用") { _, _ ->
                val value = input.text.toString().toFloatOrNull()
                if (value == null || value !in 40f..260f) {
                    toast("BPM 必须在 40 到 260 之间")
                } else {
                    transportBar.bpm = Math.round(value * 10f) / 10f
                    transportBar.onBpmChange?.invoke(transportBar.bpm)
                    toast("速度 ${transportBar.bpm} BPM")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGenrePicker() {
        val picker = GenrePicker(this) { bp ->
            val before = session.project.tracks.size
            val added = GenreStarter.apply(session.project, bp)
            if (added <= 0) {
                toast("这个曲风没有可用的骨架")
                return@GenrePicker
            }
            session.pushAll()
            session.scheduleAutoSave()
            refreshAll(session.project)
            toast("已追加 ${bp.name} 骨架：${session.project.tracks.size - before} 条新轨道")
            currentDialog?.dismiss()
        }
        currentDialog = android.app.Dialog(this).apply {
            setContentView(android.widget.ScrollView(this@MainActivity).apply {
                addView(picker)
            })
            setCanceledOnTouchOutside(true)
            window?.setBackgroundDrawable(panelBackground(this@MainActivity))
            setOnDismissListener { currentDialog = null }
            show()
            window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.7f).toInt(),
                (resources.displayMetrics.heightPixels * 0.82f).toInt()
            )
        }
    }

    private fun showMoreDialog() {
        val items = arrayOf(
            "保存工程",
            "节拍器 ${if (transportBar.metronomeOn) "· 开" else "· 关"}",
            "摇摆 / Swing",
            "按曲风起步…",
            "DJ 与混音知识",
            "操作指南",
            "撤销 AI 最近一次编辑"
        )
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("更多控制")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> transportBar.onSave?.invoke()
                    1 -> transportBar.onMetronomeToggle?.invoke(!transportBar.metronomeOn)
                    2 -> showSwingDialog()
                    3 -> showGenrePicker()
                    4 -> showDjKnowledge()
                    5 -> showGestureGuide()
                    6 -> {
                        val ok = session.undoExternalEdit()
                        toast(if (ok) "已撤销 AI 的改动" else "没有可撤销的 AI 编辑")
                        refreshAll(session.project)
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 手机端也能查的 DJ / 混音要点；与插件 flowstudio_dj 同源知识。 */
    private fun showDjKnowledge() {
        val text =
            "过渡（从第 9 小节交接）\n" +
            "· 硬切：同调同鼓型时最干净，前段收尾后段正拍进\n" +
            "· 换低音：重叠 4 小节，先撤前段贝斯再让后段底鼓进\n" +
            "· 滤波扫频：后段低通压到 400Hz 提前 4 小节进，逐小节打开\n" +
            "· 延迟淡出：1/4 延迟 + 大反馈，干声先收\n" +
            "· 倒转：段末一拍拍速下滑，留一小节空白再落\n\n" +
            "搓碟（采样器轨，1 小节）\n" +
            "· Baby Scratch：0/-2 半音来回，最基础\n" +
            "· Chirp：推音开推子、回拉关推子\n" +
            "· Flare：推子快速开合，密集断奏\n\n" +
            "混音顺序\n" +
            "  滤波 → 均衡 → 压缩 → 饱和\n" +
            "频段只留一个主角：20–60Hz 给底鼓/808，60–200Hz 给贝斯。\n\n" +
            "常见处方\n" +
            "· 浑浊：铺底 200–400Hz 减 2–3dB\n" +
            "· 鼓没冲击：底鼓与贝斯错开 1 个 16 分\n" +
            "· 主奏被埋：其他轨在该频段轻挖 1.5dB\n\n" +
            "旋转类效果（合唱/移相/失谐）不要加在低频轨上。"
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("DJ 与混音知识")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showGestureGuide() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("手势与操作")
            .setMessage(
                "页签：点按切换，也可左右滑动。\n\n" +
                "机架：点格子写音，横向刷格子；点通道名调音色；长按通道名管理或删除。\n\n" +
                "卷帘：点空白画音，拖动音符移动，拖右缘改长度；双指缩放和平移。上方提供量化、删除和缩放。\n\n" +
                "编排：拖片段移动或跨轨，拖右缘改长度；在标尺上横向划出循环。\n\n" +
                "旋钮：上下或左右拖动，长按进入精调，双击复位，双指拖动也可精调。\n\n" +
                "速度：上下拖动 BPM，轻点数字精确输入。\n\n" +
                "底部控制条：播放/暂停、停止、后退、快进都在这里。\n" +
                "  快进后退轻点跳一拍，长按连续跳小节。\n\n" +
                "工程在编辑后自动保存，切到后台也会保存。"
            )
            .setPositiveButton("开始创作", null)
            .show()
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
        // 诊断条出现后重新走一遍统一布局：内容下移的量取决于它自身高度，
        // 不能写死 46dp —— 文案长度不同，写死必然压到别的元素。
        banner.post {
            bannerHeight = banner.height
            bannerExtra = if (bannerHeight > 0) bannerHeight + dp(6) else 0
            layoutChrome()
        }
    }

    /** 全量状态 + 崩溃日志，支持复制或分享给开发者。 */
    private fun showDiagnostics() {
        val app = application as FlowApp
        val snapshot = com.volt.flowstudio.session.StatusSnapshot(session, this)
        val log = buildString {
            // 先给一页人能直接读的状态，这是"随时查看软件所有状态"的入口
            appendLine(snapshot.humanReadable())
            appendLine()

            if (app.bootError != null) {
                appendLine("== 启动异常 ==")
                appendLine(app.bootError)
                appendLine()
            }
            appendLine("== 崩溃日志 ==")
            appendLine(CrashGuard.fullLog(this@MainActivity))
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

    private fun showTrackActions(idx: Int) {
        val track = session.project.trackAt(idx) ?: return
        session.project.selectedTrack = idx
        refreshAll(session.project)
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(track.name)
            .setItems(arrayOf("音色与参数", "重命名通道", "删除通道")) { _, which ->
                when (which) {
                    0 -> openSoundPanel(idx)
                    1 -> showRenameTrackDialog(idx)
                    2 -> removeTrack(idx)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameTrackDialog(idx: Int) {
        val track = session.project.trackAt(idx) ?: return
        val input = android.widget.EditText(this).apply {
            setText(track.name)
            setSelectAllOnFocus(true)
            maxLines = 1
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("重命名通道")
            .setView(input)
            .setPositiveButton("应用") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("通道名不能为空")
                } else {
                    track.name = name.take(24)
                    session.scheduleAutoSave()
                    refreshAll(session.project)
                    toast("已重命名为 ${track.name}")
                }
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("删除工程")
            .setItems(names) { _, which ->
                val f = files[which]
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("导出 WAV")
            .setSingleChoiceItems(rates, 0) { _, which -> chosen = which }
            .setPositiveButton("开始") { _, _ ->
                val p = session.project
                val sr = rateVals[chosen]
                toast("渲染中…")
                session.exportWav(0, p.lengthTicks(), sr) { file ->
                    if (file != null) {
                        // 明确告诉用户文件在哪 —— 之前的毛病就是"导出成功但找不到"
                        val where = session.exportDirHint()
                        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("导出完成")
                            .setMessage(
                                "文件：${file.name}\n" +
                                "大小：${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB\n" +
                                "位置：$where\n\n" +
                                "可以在「文件管理 → 音频/Music」里找到，也可以用下面的按钮直接分享。"
                            )
                            .setPositiveButton("分享") { _, _ -> session.shareFile(file) }
                            .setNegativeButton("知道了", null)
                            .show()
                    } else {
                        toast("导出失败：引擎没有返回音频数据")
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

