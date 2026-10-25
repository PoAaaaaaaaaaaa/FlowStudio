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
    private var tabBar: TabBar? = null
    private lateinit var content: FrameLayout
    private lateinit var steps: StepSequencerView
    private lateinit var piano: PianoRollView
    private lateinit var playlist: PlaylistView
    private lateinit var mixer: MixerView
    private lateinit var auto: AutomationView
    private lateinit var keys: KeyboardView
    private lateinit var rack: ChannelRackView
    private lateinit var timeline: AudioTimelineView
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
    private val REQ_PICK_AUDIO = 4101
    private var columnLayout: android.widget.LinearLayout? = null
    private var navBar: android.widget.LinearLayout? = null
    private var navMenuButton: FlowButton? = null
    private var navTitle: TextView? = null
    private var navSubtitle: TextView? = null
    private var navProgress: TextView? = null
    private var navSheet: android.app.Dialog? = null
    private val tabTitles = listOf("时间线", "机架", "步进", "卷帘", "编排", "混音", "自动化")
    private val tabHints = listOf(
        "音频波形 · 点轨道头设置",
        "点格子写音 · 长按通道管理",
        "16 格步进编辑",
        "钢琴卷帘 · 双指缩放",
        "时间线编排 · 标尺划循环",
        "混音台 · 推子与效果",
        "自动化曲线"
    )

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

    /**
     * 清单声明了 configChanges=orientation|screenSize，旋转不会重建 Activity，
     * 因此必须自己重算一次布局；否则竖屏切横屏后 chrome 高度还是旧值，
     * 底栏和内容会再次错位——这是之前"位置不对"的复发性来源。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        val hDp = dm.heightPixels / dm.density
        val wDp = dm.widthPixels / dm.density
        val compact = wDp >= 760f && hDp <= 700f
        val portrait = hDp > wDp
        keyboardHeight = dp(if (hDp < 700f) 76 else 92)
        toolBarHeight = dp(if (hDp < 700f) 38 else 42)

        // 列内三段高度随屏幕重新分配，内容用 weight 会自动适应。
        columnLayout?.let { c ->
            if (c.childCount >= 4) {
                (c.getChildAt(0).layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                    it.height = dp(if (compact) 42 else if (portrait) 48 else 44)
                    c.getChildAt(0).layoutParams = it
                }
                (c.getChildAt(2).layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                    it.height = dp(if (compact) 52 else if (portrait) 58 else 54)
                    c.getChildAt(2).layoutParams = it
                }
                (c.getChildAt(3).layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                    it.height = dp(if (compact) 20 else 22)
                    c.getChildAt(3).layoutParams = it
                }
            }
        }
        applyPageMetrics()
        rootLayout?.requestApplyInsets()
        tabBar?.invalidate()
        transportBar.invalidate()
    }

    /** 旋转/尺寸变化后，把每个页面容器里的固定边距重新套一遍。 */
    private fun applyPageMetrics() {
        contentView?.let { c ->
            for (i in 0 until c.childCount) {
                val page = c.getChildAt(i) as? FrameLayout ?: continue
                // 只处理确实带工具栏的页；机架页是裸 View，不能套 topMargin。
                val toolbar = page.getChildAt(0) as? EditorToolbar ?: continue
                (toolbar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.height = toolBarHeight
                    toolbar.layoutParams = lp
                }
                for (j in 1 until page.childCount) {
                    val child = page.getChildAt(j)
                    val lp = child.layoutParams as? FrameLayout.LayoutParams ?: continue
                    lp.topMargin = toolBarHeight
                    if (child is KeyboardView) {
                        lp.height = keyboardHeight
                        lp.gravity = android.view.Gravity.BOTTOM
                    } else if (child is PianoRollView) {
                        lp.bottomMargin = keyboardHeight
                    }
                    child.layoutParams = lp
                }
            }
        }
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
        // 结构改为纵向排列：页签 / 内容 / 底栏 / 说明。
        // 之前全部塞进一个 FrameLayout，content 最后添加会盖住页签和底栏，
        // insets 回调没触发时 chromeTop=0，内容直接占满全屏——截图中底栏和
        // 页签完全消失就是这个原因。纵向布局让四段天然不重叠。
        val dm = resources.displayMetrics
        val hDp = dm.heightPixels / dm.density
        val wDp = dm.widthPixels / dm.density
        val compactLandscape = wDp >= 760f && hDp <= 700f
        val portrait = hDp > wDp

        val tabH = dp(if (compactLandscape) 42 else if (portrait) 48 else 44)
        val transportH = dp(if (compactLandscape) 52 else if (portrait) 58 else 54)
        val footerH = dp(if (compactLandscape) 20 else 22)
        keyboardHeight = dp(if (hDp < 700f) 76 else 92)
        toolBarHeight = dp(if (hDp < 700f) 38 else 42)

        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        root.addView(StudioBackground(this), matchParams())

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.TOP })

        // ---- 1. 汉堡导航 ----
        // 之前只是一个「☰」文本按钮 + 一行字，点开是系统列表，既没有层级也没有反馈。
        // 现在：图标带圆形底、当前页名与提示分两行、右侧显示页序进度，
        // 点开是一个带选中标记、图标与说明的自绘面板（NavPanelView）。
        tabBarView = null
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(10), 0)
            setBackgroundColor(Palette.SURFACE)
        }
        navBar = bar

        val menu = android.widget.FrameLayout(this)
        navMenuButton = FlowButton(this).apply {
            text = "☰"
            textSize = 15f
            buttonColor = Palette.PANEL_HI
            foregroundColor = Palette.TEXT
            setPadding(0, 0, 0, 0)
            minWidth = dp(34)
            minHeight = dp(34)
            minimumWidth = dp(34)
            minimumHeight = dp(34)
            setOnClickListener { openNavSheet() }
        }
        menu.addView(navMenuButton, android.widget.FrameLayout.LayoutParams(dp(34), dp(34)))
        bar.addView(menu, android.widget.LinearLayout.LayoutParams(dp(34), dp(34))
            .apply { marginEnd = dp(10) })

        // 标题与提示上下两行，左侧一条强调色竖条作为视觉锚点
        val titleBox = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        navTitle = TextView(this).apply {
            text = tabTitles[0]
            setTextColor(Palette.TEXT)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleBox.addView(navTitle)
        navSubtitle = TextView(this).apply {
            text = tabHints[0]
            setTextColor(Palette.TEXT_FAINT)
            textSize = 9f
        }
        titleBox.addView(navSubtitle)
        bar.addView(titleBox, android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        // 右侧页码：让人知道自己在整个工作流的哪个位置
        navProgress = TextView(this).apply {
            text = "1 / ${tabTitles.size}"
            setTextColor(Palette.TEXT_FAINT)
            textSize = 9f
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setBackgroundColor(Palette.PANEL)
        }
        bar.addView(navProgress, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        column.addView(bar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, tabH
        ))

        // ---- 2. 内容（占满剩余高度）----
        content = FrameLayout(this)
        contentView = content
        column.addView(content, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---- 3. 底部走带条 ----
        transportBar = TransportBar(this).apply { bottomMode = true }
        transportBarView = transportBar
        column.addView(transportBar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, transportH
        ))

        // ---- 4. 说明条 ----
        footerBar = FlowButton(this).apply {
            text = "手势与操作指南  ·  FlowStudio 1.17"
            textSize = 9f
            setPadding(dp(14), 0, dp(14), 0)
            buttonColor = Palette.BG
            foregroundColor = Palette.TEXT_FAINT
            setOnClickListener { showGestureGuide() }
        }
        column.addView(footerBar, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, footerH
        ))

        // ---- 诊断条：覆盖在内容顶部，不参与高度分配 ----
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

        rootLayout = root
        columnLayout = column
        chromeTop = tabH
        chromeBottom = transportH + footerH

        // ---- 页面 ----
        // 0. 音频时间线：主界面。参照多轨编辑器：左轨道头、右波形、上方刻度尺。
        val timelineHolder = FrameLayout(this)
        timeline = AudioTimelineView(this).apply {
            onSeek = { tick -> session.seek(tick); refreshPlayhead() }
            onLaneTap = { showBackingSettings() }
            onRequestImport = { pickBackingAudio() }
        }
        addToolbar(timelineHolder, "音频时间线", "点轨道头设置 · 长按空白导入")
        timelineHolder.addView(timeline, pageBodyParams())
        content.addView(timelineHolder, matchParams())

        // 1. 通道机架
        rack = ChannelRackView(this)
        content.addView(rack, matchParams())

        // 1. 步进器
        val stepsHolder = FrameLayout(this)
        steps = StepSequencerView(this)
        addToolbar(stepsHolder, "步进音序器", "点按或横向刷过格子")
        stepsHolder.addView(steps, pageBodyParams())
        content.addView(stepsHolder, matchParams())

        // 2. 钢琴卷帘 + 键盘
        val pianoHolder = FrameLayout(this)
        piano = PianoRollView(this)
        keys = KeyboardView(this).apply { noteCount = 24 }
        addToolbar(pianoHolder, "钢琴卷帘", "双指缩放 · 拖右缘改时长")
        pianoHolder.addView(piano, pageBodyParams(bottom = keyboardHeight))
        pianoHolder.addView(keys, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, keyboardHeight
        ).apply { gravity = android.view.Gravity.BOTTOM })
        content.addView(pianoHolder, matchParams())

        // 3. 播放列表
        val playlistHolder = FrameLayout(this)
        playlist = PlaylistView(this)
        addToolbar(playlistHolder, "编排时间线", "拖动片段 · 双指平移 · 标尺划循环")
        playlistHolder.addView(playlist, pageBodyParams())
        content.addView(playlistHolder, matchParams())

        // 4. 混音台
        val mixerHolder = FrameLayout(this)
        mixer = MixerView(this)
        addToolbar(mixerHolder, "混音台", "点通道名调音色 · 点 FX 槽调效果")
        mixerHolder.addView(mixer, pageBodyParams())
        content.addView(mixerHolder, matchParams())

        // 5. 自动化
        val autoHolder = FrameLayout(this)
        auto = AutomationView(this)
        addToolbar(autoHolder, "自动化", "绘制参数随时间变化的曲线")
        autoHolder.addView(auto, pageBodyParams())
        content.addView(autoHolder, matchParams())

        setContentView(root)

        // 系统栏：只把整列往内推，不参与高度分配，避免和布局互相打架。
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sysTop = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
            val sysBottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            insetTop = sysTop
            insetBottom = sysBottom
            (column.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = sysTop
                lp.bottomMargin = sysBottom
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                column.layoutParams = lp
            }
            (banner.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = sysTop + tabH
                banner.layoutParams = lp
            }
            insets
        }
        root.requestApplyInsets()
    }

    /** 页面正文：顶部避开工具栏，底部可留出键盘高度。 */
    private fun pageBodyParams(bottom: Int = 0) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    ).apply {
        topMargin = toolBarHeight
        bottomMargin = bottom
    }

    /**
     * 旧的 FrameLayout 绝对定位方案已废弃。
     *
     * 它依赖 insets 回调把 margin 写进各个子 View；回调没触发时 margin 保持 0，
     * content 就会盖住页签与底栏（截图里的现象）。现在改为 LinearLayout 纵向排列，
     * 高度由 weight 决定，系统栏用整列 margin 处理。这里保留空实现只为兼容旧调用点。
     */
    private fun applyInsets(insets: android.view.WindowInsets) {
        val sysTop = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        val sysBottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        insetTop = sysTop
        insetBottom = sysBottom
    }

    private fun matchParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun addToolbar(holder: FrameLayout, title: String, subtitle: String): EditorToolbar {
        val toolbar = EditorToolbar(this).apply {
            this.title = title
            this.subtitle = subtitle
            onSwipePage = { direction -> showTab((currentTab + direction).coerceIn(0, tabTitles.lastIndex)) }
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

        // 页签已由汉堡导航替代，无需再绑定。

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

    /**
     * 切换页面。
     *
     * 之前每次切换都做三件昂贵的多余事：透明度动画、全量 refreshAll、
     * 以及重建工具栏 actions。切换一个页签不该重推整首工程 ——
     * 那会在工程大时造成肉眼可见的卡顿。现在只做可见性切换 + 轻量刷新。
     */
    private fun showTab(index: Int) {
        if (index !in 0 until content.childCount) return
        if (index == currentTab && content.getChildAt(index).visibility == View.VISIBLE) return
        currentTab = index
        content.getChildAt(index).let { page ->
            page.animate().cancel()
            page.alpha = 1f
            page.translationX = 0f
        }
        for (i in 0 until content.childCount) {
            val page = content.getChildAt(i)
            val visible = i == index
            if (page.visibility != (if (visible) View.VISIBLE else View.GONE)) {
                page.animate().cancel()
                page.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
        // 只更新与当前页相关的几条数据，不整首重推。
        lightRefresh(index)
        ((content.getChildAt(index) as? FrameLayout)?.getChildAt(0) as? EditorToolbar)?.let { toolbar ->
            when (index) {
                3 -> configurePianoTools(toolbar)
                4 -> configurePlaylistTools(toolbar)
            }
        }
        navTitle?.text = tabTitles[index]
        navSubtitle?.text = tabHints.getOrElse(index) { "" }
        navProgress?.text = "${index + 1} / ${tabTitles.size}"
    }

    /**
     * 汉堡菜单：带图标的页面选择面板。
     *
     * 入场用 OvershootInterpolator（与参考工程的回弹手感一致）：
     * 从 0.92 缩放到 1，300ms，带轻微过冲。
     */
    private fun openNavSheet() {
        val panel = NavPanelView(this, tabTitles, tabHints, currentTab) { idx ->
            navSheet?.dismiss()
            showTab(idx)
        }
        val dialog = android.app.Dialog(this)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            dimAmount = 0.45f
            windowAnimations = 0
        }
        dialog.setOnDismissListener { navSheet = null }
        navSheet = dialog
        dialog.show()
        dialog.window?.setLayout(dp(300), panel.desiredHeight())
        dialog.window?.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)

        // 回弹入场：缩放 + 轻微位移，比系统淡入更有"点开了"的感觉。
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        panel.alpha = 0f
        panel.translationY = -dp(6).toFloat()
        panel.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(300L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
            .start()
    }

    /** 切换页面时的最小刷新：只动需要的那几个 View。 */
    private fun lightRefresh(index: Int) {
        val p = session.project
        when (index) {
            0 -> {
                timeline.barTicks = p.barTicks
                timeline.totalTicks = p.lengthTicks()
                timeline.bindBacking(session.backing)
                timeline.invalidate()
            }
            1 -> {
                rack.project = p
                rack.currentPatternIndex = p.currentPattern
                rack.selectedTrack = p.selectedTrack
                rack.invalidate()
            }
            2 -> {
                steps.pattern = p.patternAt(p.currentPattern)
                steps.trackNames = p.tracks.map { it.name }
                steps.trackColors = p.tracks.indices.map { Palette.trackColor(it) }
                steps.invalidate()
            }
            3 -> {
                piano.pattern = p.patternAt(p.currentPattern)
                piano.trackIndex = p.selectedTrack
                piano.invalidate()
            }
            4 -> {
                playlist.project = p
                playlist.selectedTrack = p.selectedTrack
                playlist.invalidate()
            }
            5 -> {
                mixer.project = p
                mixer.selectedTrack = p.selectedTrack
                mixer.invalidate()
            }
            6 -> auto.invalidate()
        }
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

    // ---------------------------------------------------------------- 伴奏音频轨

    /**
     * 选择本地音频作为伴奏。
     * 用系统文件选择器，不申请存储权限 —— SAF 拿到的是可读 URI，
     * 复制进应用私有目录后就不再依赖外部权限。
     */
    private fun pickBackingAudio() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        runCatching { startActivityForResult(intent, REQ_PICK_AUDIO) }
            .onFailure { toast("这台设备没有可用的文件选择器") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        toast("正在读取音频…")
        Thread {
            val tmp = java.io.File(cacheDir, "backing-import.wav")
            val ok = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } != null
            }.getOrDefault(false)
            if (!ok) {
                runOnUiThread { toast("读不到这个文件") }
                return@Thread
            }
            session.backing.import(tmp) { success, message ->
                runOnUiThread {
                    toast(if (success) "伴奏已载入：$message" else message)
                    if (success) transportBar.invalidate()
                }
            }
        }.start()
    }

    /** 伴奏的音量、静音与起始位置。 */
    private fun showBackingSettings() {
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }
        val info = TextView(this).apply {
            text = if (session.backing.loaded)
                "已载入：${session.backing.fileName}\n" +
                "${"%.1f".format(session.backing.frames.toDouble() / session.backing.sampleRate)} 秒 · " +
                "${session.backing.sampleRate} Hz"
            else "还没有伴奏。先选「导入伴奏音频」。"
            setTextColor(Palette.TEXT_DIM)
            textSize = 12f
        }
        wrap.addView(info)

        val gainLabel = TextView(this).apply {
            text = "伴奏音量 ${(session.backing.gain * 100).toInt()}%"
            setTextColor(Palette.TEXT)
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        wrap.addView(gainLabel)
        val gain = android.widget.SeekBar(this).apply {
            max = 150
            progress = (session.backing.gain * 100).toInt()
        }
        gain.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                session.backing.setGain(value / 100f)
                gainLabel.text = "伴奏音量 ${value}%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        wrap.addView(gain)

        val mute = android.widget.CheckBox(this).apply {
            text = "静音伴奏"
            isChecked = session.backing.muted
            setTextColor(Palette.TEXT_DIM)
            setOnCheckedChangeListener { _, checked -> session.backing.setMuted(checked) }
        }
        wrap.addView(mute)

        val controls = arrayOf("从当前播放位置对齐", "从头对齐（第 1 小节）", "移除伴奏")
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("伴奏")
            .setView(wrap)
            .setPositiveButton("关闭", null)
            .setNeutralButton("对齐 / 移除…") { _, _ ->
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("伴奏位置")
                    .setItems(controls) { _, which ->
                        when (which) {
                            0 -> {
                                session.backing.setStartTick(session.positionTicks())
                                toast("伴奏已对齐到当前位置")
                            }
                            1 -> {
                                session.backing.setStartTick(0)
                                toast("伴奏已对齐到开头")
                            }
                            2 -> {
                                session.backing.clear()
                                toast("已移除伴奏")
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .show()
    }

    private fun showMoreDialog() {
        val items = arrayOf(
            "保存工程",
            "节拍器 ${if (transportBar.metronomeOn) "· 开" else "· 关"}",
            "摇摆 / Swing",
            "导入伴奏音频…",
            "伴奏设置…",
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
                    3 -> pickBackingAudio()
                    4 -> showBackingSettings()
                    5 -> showGenrePicker()
                    6 -> showDjKnowledge()
                    7 -> showGestureGuide()
                    8 -> {
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
        // 诊断条是覆盖层：贴在页签下方显示，不参与纵向高度分配，
        // 也就不会再出现"改一处高度、另一处被裁掉"的连锁问题。
        (banner.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.topMargin = insetTop + (navBar?.height ?: dp(46))
            banner.layoutParams = lp
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

