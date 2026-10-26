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
    private lateinit var piano: PianoRollView
    private lateinit var playlist: PlaylistView
    private lateinit var mixer: MixerView
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
    private var insetTop = 0
    private var insetBottom = 0
    private var keyboardHeight = 0
    private var toolBarHeight = 0
    /** 诊断条出现时内容需要额外下移的高度；由 layoutChrome 统一参与计算。 */
    private var bannerExtra = 0
    private var bannerHeight = 0
    private val REQ_PICK_AUDIO = 4101
    private val REQ_MEDIA_PERM = 4102
    private val REQ_PICK_BACKGROUND = 4103
    private var columnLayout: android.widget.LinearLayout? = null
    private var navBar: android.widget.LinearLayout? = null
    private var navMenuButton: FlowButton? = null
    private var navTitle: TextView? = null
    private var navSubtitle: TextView? = null
    private var navProgress: TextView? = null
    /** 导航栏右侧的装饰条：电平 + 卷盘 + 唱针。见 OrnamentBar。 */
    private var ornament: com.volt.flowstudio.ui.OrnamentBar? = null
    /** 应用设置（背景图、摆件、常亮等）。与工程无关，独立持久化。 */
    private lateinit var settings: com.volt.flowstudio.session.AppSettings
    private var backgroundView: com.volt.flowstudio.ui.StudioBackground? = null
    private var settingsWindow: com.volt.flowstudio.ui.SettingsWindow? = null
    /** 摆件四拍点阵：上一次的拍号，用来检测"进新拍" */
    private var lastOrnamentBeat = -1
    private var navSheet: android.app.Dialog? = null
    private val tabTitles = listOf("编排", "机架", "卷帘", "混音")
    private val tabHints = listOf(
        "片段与伴奏 · 点标尺定位",
        "音符与通道 · 点格子写音",
        "钢琴卷帘 · 双指缩放",
        "混音台 · 推子与效果"
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
        settings = com.volt.flowstudio.session.AppSettings(this)
        applyKeepAwake()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        bindSession()
        showTab(0)
        showBootDiagnostics()
        ui.post(ticker)
        ensureMediaPermission()
    }

    /**
     * 投递目录需要读公共 Music 下别人写进来的文件。
     *
     * 这条权限声明在清单里，但从来没在运行时申请过 —— 结果 granted=false，
     * App 根本列不出 inbox 里的文件。表现就是"投递通道名存实亡"：
     * 文件确实躺在 /sdcard/Music/FlowStudio/inbox，App 眼里却是空的，
     * GET /inbox 也只能回 pending: []。FileObserver 没问题，
     * 是它连目录都读不到。
     *
     * Android 13+ 用 READ_MEDIA_AUDIO，12 及以下用 READ_EXTERNAL_STORAGE。
     */
    private fun ensureMediaPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val perm = if (Build.VERSION.SDK_INT >= 33)
            "android.permission.READ_MEDIA_AUDIO"
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        runCatching { requestPermissions(arrayOf(perm), REQ_MEDIA_PERM) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MEDIA_PERM) return
        // 无论授权与否都补扫一次：授权了这就能把积压文件吃掉，
        // 没授权则至少把"为什么没导入"写进日志，而不是静默无声。
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching { session.rescanInbox() }
        } else {
            android.util.Log.w(
                "FlowStudio",
                "没有音频读取权限，投递目录里的文件将无法自动导入"
            )
        }
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

    override fun onResume() {
        super.onResume()
        // 回前台刷新端口发现文件：App 若换了端口（重启后 8199 被占），
        // 插件读这份文件就能跟上。
        runCatching { session.bridge?.writeBridgeInfo() }
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
        keyboardHeight = dp(if (hDp < 700f) 76 else 92)
        toolBarHeight = dp(if (hDp < 700f) 38 else 42)

        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        val bg = StudioBackground(this)
        backgroundView = bg
        root.addView(bg, matchParams())
        applyBackground()

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
            textSize = 16f
            // 无底色：整屏只保留内容区的色块，chrome 不参与配色竞争。
            buttonColor = android.graphics.Color.TRANSPARENT
            foregroundColor = Palette.TEXT_DIM
            setPadding(0, 0, 0, 0)
            minWidth = dp(36)
            minHeight = dp(36)
            minimumWidth = dp(36)
            minimumHeight = dp(36)
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

        // 右侧摆件：电平 + 磁带卷盘 + 唱针臂。
        // 导航栏中段在宽屏上是纯色，这里放一排会呼吸的装置，
        // 既填了空白又能扫一眼看到有没有声音。不接受触摸，不会误触。
        val deco = com.volt.flowstudio.ui.OrnamentBar(this)
        ornament = deco
        // 加宽以容纳新增的四拍点阵与迷你频谱。窄屏 128、宽屏 156。
        bar.addView(deco, android.widget.LinearLayout.LayoutParams(
            dp(if (portrait) 128 else 156), android.widget.LinearLayout.LayoutParams.MATCH_PARENT
        ))
        deco.visibility = if (settings.showOrnaments) View.VISIBLE else View.GONE

        // 右侧页码：让人知道自己在整个工作流的哪个位置
        navProgress = TextView(this).apply {
            text = "1 / ${tabTitles.size}"
            setTextColor(Palette.TEXT_FAINT)
            textSize = 9f
            setPadding(dp(8), dp(3), dp(10), dp(3))
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
        chromeBottom = transportH

        // ---- 页面 ----
        // 0. 编排：音乐片段 + 伴奏波形的主工作区
        val playlistHolder = FrameLayout(this)
        playlist = PlaylistView(this)
        addToolbar(playlistHolder, "编排时间线", "拖动片段 · 双指平移 · 标尺划循环")
        playlistHolder.addView(playlist, pageBodyParams())
        content.addView(playlistHolder, matchParams())

        // 1. 通道机架
        rack = ChannelRackView(this)
        content.addView(rack, matchParams())

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

        // 4. 混音台
        val mixerHolder = FrameLayout(this)
        mixer = MixerView(this)
        addToolbar(mixerHolder, "混音台", "点通道名调音色 · 点 FX 槽调效果")
        mixerHolder.addView(mixer, pageBodyParams())
        content.addView(mixerHolder, matchParams())

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
            "导入音频" to { pickAudio() },
            "定位" to { session.seek(0); refreshPlayhead(); toast("已回到开头") },
            // 统一走 zoom：三条路径（按钮 / 双指 / 双击）共用同一套边界，
            // 不会出现"按钮放到最大了但还能继续捏合"这种不一致。
            "−" to { playlist.zoomBy(1f / 1.4f) },
            "${kotlin.math.round(playlist.zoom).toInt()}x" to { playlist.zoomReset() },
            "＋" to { playlist.zoomBy(1.4f) }
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
                // 音频流没起来就不要点亮播放键 —— 那是在撒谎。
                if (!session.play()) {
                    transportBar.setPlaying(false)
                    toast("音频输出没能启动（AAudio 与 OpenSL 都失败）。" +
                        "检查是否被其他应用独占，或在「更多 → 诊断」里看详情")
                    return@play
                }
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
        playlist.onClipsChanged = {
            // 音频片段的布局也在片段表里，拖动/裁剪完必须重推引擎，
            // 否则画面动了、声音还在老位置。
            session.pushBpm()
            session.pushAudioClips()
            session.scheduleAutoSave()
        }
        playlist.onSelectTrack = { idx -> session.project.selectedTrack = idx }
        playlist.onLoopChanged = { on, s, e ->
            session.setLoop(on, s, e)
            transportBar.setLoop(on)
        }
        playlist.onAudioTap = { clip -> showClipMenu(clip) }
        // 空点一条空轨道：直接进导入，省掉「先去菜单找导入」这一步。
        playlist.onEmptyTap = { track, tick ->
            val empty = session.project.clips.none {
                it.track == track && tick >= it.start && tick < it.end
            }
            if (empty) {
                session.project.selectedTrack = track
                session.seek(tick)
                pickAudio()
            }
        }

        // ---- 混音台 ----
        mixer.onTrackChanged = { idx -> session.pushTrack(idx); session.scheduleAutoSave() }
        mixer.onMasterChanged = { session.pushMaster(); session.scheduleAutoSave() }
        mixer.onSelectTrack = { idx -> session.project.selectedTrack = idx }
        mixer.onOpenSound = { idx -> openSoundPanel(idx) }
        mixer.onOpenFx = { idx, slot -> openFxPanel(idx, slot) }

        session.onError = { msg -> toast(msg) }
        // 自动保存保持安静，只有手动保存显示结果。
        session.onSaved = { ok -> if (!ok) toast("自动保存失败，请立即手动保存") }
        session.onLoaded = { p -> refreshAll(p) }
        session.onAudioChanged = {
            refreshAll(session.project)
            transportBar.invalidate()
        }
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
        piano.pattern = p.patternAt(p.currentPattern)
        piano.trackIndex = p.selectedTrack
        piano.invalidate()
        playlist.project = p
        playlist.loopOn = p.loopOn
        playlist.loopStart = p.loopStart
        playlist.loopEnd = p.loopEnd
        playlist.selectedTrack = p.selectedTrack
        bindPlaylistAudio()
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
                0 -> configurePlaylistTools(toolbar)
                2 -> configurePianoTools(toolbar)
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
    /** 从左侧打开工作区抽屉，位置与尺寸稳定，不做无关弹跳。 */
    private fun openNavSheet() {
        if (navSheet?.isShowing == true) return
        val entries = tabTitles + listOf("导入音频…", "工程管理…", "操作指南")
        val hints = tabHints + listOf("音频铺到时间线上", "新建 / 打开 / 删除", "手势与工作区说明")
        val panel = NavPanelView(this, entries, hints, currentTab, tabTitles.size) { idx ->
            navSheet?.dismiss()
            when {
                idx < tabTitles.size -> showTab(idx)
                idx == tabTitles.size -> pickAudio()
                idx == tabTitles.size + 1 -> showProjectDialog()
                else -> showGestureGuide()
            }
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
        dialog.setOnDismissListener { navSheet = null }
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
            window.setLayout(dp(288), resources.displayMetrics.heightPixels)
            window.setDimAmount(0.42f)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { windowAnimations = 0 }
        }
        navSheet = dialog
        panel.translationX = -dp(288).toFloat()
        panel.animate().translationX(0f).setDuration(140L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** 切换页面时的最小刷新：只动需要的那几个 View。 */
    private fun lightRefresh(index: Int) {
        val p = session.project
        when (index) {
            0 -> {
                playlist.project = p
                playlist.selectedTrack = p.selectedTrack
                bindPlaylistAudio()
                playlist.invalidate()
            }
            1 -> {
                rack.project = p
                rack.currentPatternIndex = p.currentPattern
                rack.selectedTrack = p.selectedTrack
                rack.invalidate()
            }
            2 -> {
                piano.pattern = p.patternAt(p.currentPattern)
                piano.trackIndex = p.selectedTrack
                piano.invalidate()
            }
            3 -> {
                mixer.project = p
                mixer.selectedTrack = p.selectedTrack
                mixer.invalidate()
            }
        }
    }

    /**
     * 把音频素材的波形与时长交给编排视图。
     *
     * 峰值在导入时就算好并缓存，这里只搬引用 —— 每次刷新重算波形会让
     * 页面切回来时卡一下，而波形本身在素材不变时是常量。
     */
    private fun bindPlaylistAudio() {
        val p = session.project
        val peaks = HashMap<Int, FloatArray>(p.audioAssets.size)
        for (a in p.audioAssets) peaks[a.id] = session.audioStore.peaksOf(a.id)
        playlist.audioPeaks = peaks
        playlist.invalidate()
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

        // 顶部摆件：电平与播放状态。只在这里喂数据，绘制开销都在 OrnamentBar 内部，
        // 且它自己判断"要不要继续动画"——静止时不重绘。
        ornament?.let { o ->
            val pk = session.engine.masterPeak()
            o.peakL = pk.getOrElse(0) { 0f }
            o.peakR = pk.getOrElse(1) { 0f }
            o.playing = session.isPlaying()
            // 当前拍：四拍点阵与脉冲用它
            val barTicks = session.project.barTicks.coerceAtLeast(1)
            val beatTicks = barTicks / 4
            o.beatIndex = if (beatTicks > 0) (pos % barTicks) / beatTicks else 0
            // 进到新的一拍时给一个脉冲（由 OrnamentBar 自己衰减）
            if (session.isPlaying() && o.beatIndex != lastOrnamentBeat) {
                lastOrnamentBeat = o.beatIndex
                o.beatPulse = 1f
            } else {
                o.beatPulse = (o.beatPulse - 0.08f).coerceAtLeast(0f)
            }
        }
        transportBar.positionTicks = pos
        transportBar.barTicks = session.project.barTicks
        transportBar.bpm = session.project.bpm
        transportBar.setPlaying(session.isPlaying())
        when (currentTab) {
            0 -> {
                playlist.playheadTick = pos
                // trackLevels() 返回的是 peak*1000 的整数（见 jni_bridge 里的换算），
                // 这里还原成 0..1 给电平表用。
                val raw = session.engine.trackLevels()
                val norm = FloatArray(raw.size) { (raw[it] / 1000f).coerceIn(0f, 1f) }
                playlist.levels = norm
                if (session.isPlaying()) playlist.scrollToPlayheadIfNeeded(pos)
            }
            1 -> {
                rack.playheadTick = pos
                rack.levels = session.engine.trackLevels()
                rack.invalidate()
            }
            2 -> {
                piano.playheadTick = pos
                piano.scrollToPlayhead(pos)
            }
            3 -> {
                mixer.levels = session.engine.trackLevels()
                val pk = session.engine.masterPeak()
                mixer.masterL = pk.getOrElse(0) { 0f }
                mixer.masterR = pk.getOrElse(1) { 0f }
            }
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

    // ---------------------------------------------------------------- 音频片段

    /**
     * 选择本地音频导入。
     * 用系统文件选择器，不申请存储权限 —— SAF 拿到的是可读 URI，
     * 复制进应用私有目录后就不再依赖外部权限。
     */
    private fun pickAudio() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(intent, REQ_PICK_AUDIO) }
            .onFailure { toast("这台设备没有可用的文件选择器") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // 背景图：单独一条路径，与音频导入完全分开
        if (requestCode == REQ_PICK_BACKGROUND) {
            if (resultCode == RESULT_OK) {
                val uri = data?.data ?: data?.clipData?.getItemAt(0)?.uri
                if (uri != null) handleBackgroundResult(uri)
            }
            return
        }

        if (requestCode != REQ_PICK_AUDIO || resultCode != RESULT_OK) return

        // 支持一次选多个：编曲常常是一整套鼓组 + 人声 + 乐器分轨一起进来。
        val uris = ArrayList<android.net.Uri>()
        data?.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) cd.getItemAt(i)?.uri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return

        toast(if (uris.size == 1) "正在读取音频…" else "正在读取 ${uris.size} 个音频…")
        Thread {
            var okCount = 0
            var lastMessage = ""
            for (uri in uris) {
                val name = queryDisplayName(uri) ?: "audio.wav"
                val tmp = java.io.File(cacheDir, "import-${System.nanoTime()}.wav")
                val copied = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    } != null
                }.getOrDefault(false)
                if (!copied) {
                    lastMessage = "读不到 $name"
                    continue
                }
                val latch = java.util.concurrent.CountDownLatch(1)
                session.importAudio(tmp, name) { success, message ->
                    if (success) okCount++ else lastMessage = message
                    latch.countDown()
                }
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                tmp.delete()
            }
            val finalCount = okCount
            val finalMsg = lastMessage
            runOnUiThread {
                when {
                    finalCount > 0 && finalMsg.isBlank() -> toast("已导入 $finalCount 段音频")
                    finalCount > 0 -> toast("已导入 $finalCount 段 · $finalMsg")
                    else -> toast(finalMsg.ifBlank { "没能导入音频：只支持 PCM WAV" })
                }
                if (finalCount > 0) {
                    refreshAll(session.project)
                    transportBar.invalidate()
                }
            }
        }.start()
    }

    /** 从 content URI 取原始文件名 —— 片段标题就用它，FL 里也是这么显示的。 */
    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** 点一个音频片段：改增益、换轨道、定位、删除。 */
    private fun showClipMenu(clip: com.volt.flowstudio.model.Clip) {
        val asset = session.project.audioAssetById(clip.audioId)
        val name = asset?.name ?: "音频片段"
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }
        wrap.addView(TextView(this).apply {
            text = "$name\n${"%.1f".format(asset?.durationSec ?: 0f)} 秒 · " +
                "${asset?.sampleRate ?: 0} Hz · 轨道 ${clip.track + 1}"
            setTextColor(Palette.TEXT_DIM)
            textSize = 12f
        })

        val gainLabel = TextView(this).apply {
            text = "片段增益 ${(clip.audioGain * 100).toInt()}%"
            setTextColor(Palette.TEXT)
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        wrap.addView(gainLabel)
        wrap.addView(android.widget.SeekBar(this).apply {
            max = 200
            progress = (clip.audioGain * 100).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    clip.audioGain = value / 100f
                    gainLabel.text = "片段增益 $value%"
                    session.pushAudioClips()
                    session.scheduleAutoSave()
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        })

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("音频片段")
            .setView(wrap)
            .setPositiveButton("关闭", null)
            .setNeutralButton("操作…") { _, _ ->
                val actions = arrayOf("移到当前选中轨道", "对齐到播放头", "整段铺满素材", "删除片段")
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("片段操作")
                    .setItems(actions) { _, which ->
                        when (which) {
                            0 -> {
                                clip.track = session.project.selectedTrack
                                    .coerceIn(0, maxOf(0, session.project.tracks.size - 1))
                                toast("已移到轨道 ${clip.track + 1}")
                            }
                            1 -> clip.start = session.positionTicks()
                            2 -> asset?.let {
                                clip.length = it.lengthTicks(session.project.bpm)
                                clip.offset = 0
                            }
                            3 -> {
                                session.removeClip(clip.id)
                                toast("已删除片段")
                            }
                        }
                        session.pushAudioClips()
                        session.scheduleAutoSave()
                        playlist.invalidate()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .show()
    }

    /**
     * 设置窗口。
     *
     * 原来这里是「更多控制」的 AlertDialog 列表，九项里混着三类东西：
     *   1. 用户真的要用的（导入音频、节拍器、导出）
     *   2. 日常高频但该有专门入口的（工程管理、保存、撤销）
     *   3. 诊断信息、操作指南这类"出问题时才看"的
     * 全塞在一个系统列表里，用户找不到东西，开发者选项还占着前排。
     *
     * 现在：分组卡片式设置窗口（外观 / 播放 / 工程 / 诊断），
     * 高频操作留在工具栏与导航抽屉，诊断降到最后一组。
     */
    private fun showMoreDialog() {
        if (settingsWindow == null) {
            settingsWindow = com.volt.flowstudio.ui.SettingsWindow(
                this,
                settings,
                onPickBackground = { pickBackgroundImage() },
                onShowDiagnostics = { showDiagnostics() },
                onShowGuide = { showGestureGuide() },
                onShowDj = { showDjKnowledge() },
                onExport = { showExportDialog() },
                onNotify = { msg -> toast(msg); applyOrnamentVisibility() },
            )
        }
        settingsWindow?.show()
    }

    /** 系统图片选择器：只取图片，不限定来源（相册、文件管理器都行）。 */
    private fun pickBackgroundImage() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, REQ_PICK_BACKGROUND) }
            .onFailure { toast("这台设备没有可用的图片选择器") }
    }

    /**
     * 处理选中的背景图：解码 → 交给 AppSettings 做去饱和/压暗/加渐变压边 → 落盘 → 应用。
     *
     * 全过程在后台线程做（大图解码 + 两次缩放，主线程做会卡住 UI 几百毫秒），
     * 完成后回主线程换图。
     */
    private fun handleBackgroundResult(uri: android.net.Uri) {
        toast("正在处理背景图…")
        Thread {
            val path = runCatching {
                val src = contentResolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                } ?: return@runCatching null
                com.volt.flowstudio.session.AppSettings.importBackground(src, cacheDir)
            }.getOrNull()

            ui.post(Runnable {
                if (path == null) {
                    toast("这张图读不出来，换一张试试")
                    return@Runnable
                }
                settings.backgroundPath = path
                applyBackground()
                settingsWindow?.dismiss()
                settingsWindow = null
                toast("背景已应用")
            })
        }.start()
    }

    /** 把设置里的背景图贴到底层 StudioBackground。没有就回到默认渐变。 */
    private fun applyBackground() {
        val bg = backgroundView ?: return
        bg.dim = settings.dimBackground
        bg.backgroundImage = settings.loadBackground()
    }

    private fun applyKeepAwake() {
        if (settings.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applyOrnamentVisibility() {
        ornament?.visibility = if (settings.showOrnaments) View.VISIBLE else View.GONE
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
                "工作区：点左上角 ☰ 打开列表切换；顶部标题栏显示当前工作区。\n\n" +
                "编排（首页）：横向是时间，纵向是音轨与伴奏。点标尺定位，拖动片段移动，" +
                "拖右缘改长度。没有伴奏时点「+ 导入伴奏音频」选择 WAV。\n\n" +
                "机架：点格子写音，横向刷格子；点通道名调音色；长按通道名管理或删除。\n\n" +
                "卷帘：点空白画音，拖动音符移动，拖右缘改长度；双指缩放和平移。" +
                "上方工具条可切吸附、量化、删除与缩放。\n\n" +
                "混音：推子、声像、静音/独奏；点通道名进音色，点 FX 槽进效果。\n\n" +
                "底部走带：播放/暂停、停止、后退、快进。轻点快进后退跳一拍，长按连续跳小节。\n\n" +
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

