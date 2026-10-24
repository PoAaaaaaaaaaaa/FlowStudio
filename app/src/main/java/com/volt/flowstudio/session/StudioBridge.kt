package com.volt.flowstudio.session

import android.content.Context
import android.util.Log
import com.volt.flowstudio.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地桥接：让外部工具（DSH 插件）实时看到并修改正在运行的工程。
 *
 * 为什么是 HTTP + 回环：
 *   - 不用 adb、不用 root，插件在容器里直接 curl 127.0.0.1 就能通；
 *   - 只绑回环地址，局域网里的其他设备连不上；
 *   - 每次请求校验 token，防止本机其它应用乱改用户的工程。
 *
 * 协议很小，全是 JSON：
 *   GET  /state              当前工程全量状态（含传输、轨道、样式、片段）
 *   GET  /project            完整 .flow JSON（可直接存成文件）
 *   PUT  /project            用一段 .flow JSON 整体替换当前工程
 *   GET  /transport          播放状态、位置、BPM
 *   POST /transport/play | /pause | /stop | /seek?tick=
 *   POST /bpm?value=
 *   GET  /tracks             轨道列表与混音参数
 *   POST /tracks             新增轨道（body: {name, inst}）
 *   PATCH /tracks/{i}        改某轨的混音/音色参数
 *   GET  /patterns           样式列表（含音符）
 *   PUT  /patterns/{id}      写入/替换某个样式的音符
 *   POST /clips              （播放列表）新增片段
 *   DELETE /clips/{id}
 *   POST /note/on            {track, pitch, velocity} 试听
 *   POST /note/off           {track}
 */
class StudioBridge(
    private val context: Context,
    private val session: StudioSession
) {

    companion object {
        private const val TAG = "FlowBridge"
        const val DEFAULT_PORT = 8199
        private const val TOKEN_FILE = "bridge_token"
    }

    @Volatile
    var running = false
        private set

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    /**
     * 工程变更计数。用户每次在手机上改动都会 +1，
     * 插件拿到的 revision 跟自己的不一样，就知道"我读到的是旧的"。
     * 这是避免 AI 拿过期快照覆盖用户手动改动的最小机制。
     */
    @Volatile
    var revision: Long = 0L
        private set

    fun bumpRevision() { revision++ }

    private var server: ServerSocket? = null
    private val stop = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "flow-bridge").apply { isDaemon = true }
    }

    /** 访问令牌。首次启动生成，写在私有目录里，插件通过同一个文件系统读到。 */
    val token: String by lazy {
        val f = File(context.filesDir, TOKEN_FILE)
        if (f.isFile) {
            runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.length >= 16 } ?: generateToken(f)
        } else generateToken(f)
    }

    private fun generateToken(f: File): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        val t = bytes.joinToString("") { "%02x".format(it) }
        runCatching { f.writeText(t) }
        return t
    }

    fun start(preferredPort: Int = DEFAULT_PORT): Boolean {
        if (running) return true
        stop.set(false)
        var bound: ServerSocket? = null
        var p = preferredPort
        // 端口被占就往后找，最多试 10 个
        for (attempt in 0 until 10) {
            try {
                bound = ServerSocket(p, 8, InetAddress.getByName("127.0.0.1"))
                break
            } catch (t: Throwable) {
                Log.w(TAG, "端口 $p 不可用: ${t.message}")
                p++
            }
        }
        if (bound == null) {
            Log.e(TAG, "找不到可用端口")
            return false
        }
        server = bound
        port = bound.localPort
        running = true

        pool.execute {
            Log.i(TAG, "桥接已启动 127.0.0.1:$port")
            while (!stop.get()) {
                try {
                    val client = bound.accept()
                    pool.execute { handle(client) }
                } catch (t: Throwable) {
                    if (!stop.get()) Log.w(TAG, "accept 失败: ${t.message}")
                }
            }
        }
        return true
    }

    fun stopServer() {
        stop.set(true)
        running = false
        runCatching { server?.close() }
        server = null
    }

    // ---------------------------------------------------------------- 请求处理

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 8000
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val pathWithQuery = parts[1]

            var contentLength = 0
            var authHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    if (k == "authorization") authHeader = v
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val out = s.getOutputStream()
            try {
                val (status, payload) = route(method, pathWithQuery, body, authHeader)
                respond(out, status, payload)
            } catch (t: Throwable) {
                Log.e(TAG, "处理 ${method} $pathWithQuery 出错", t)
                respond(out, 500, JSONObject().put("error", t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun respond(out: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 500 -> "Internal Server Error"
        else -> "OK"
    }

    // ---------------------------------------------------------------- 路由

    private fun route(
        method: String,
        pathWithQuery: String,
        body: String,
        auth: String?
    ): Pair<Int, JSONObject> {
        val qIdx = pathWithQuery.indexOf('?')
        val path = if (qIdx >= 0) pathWithQuery.substring(0, qIdx) else pathWithQuery
        val query = if (qIdx >= 0) parseQuery(pathWithQuery.substring(qIdx + 1)) else emptyMap()

        // 健康检查不需要令牌，方便插件先探活
        if (path == "/ping") {
            return 200 to JSONObject()
                .put("ok", true)
                .put("app", "FlowStudio")
                .put("version", session.versionName())
        }

        if (!checkAuth(auth)) {
            return 401 to JSONObject().put("error", "缺少或错误的 Authorization")
        }

        return try {
            when {
                method == "GET" && path == "/state" -> 200 to stateJson()
                method == "GET" && path == "/project" -> 200 to JSONObject(session.project.toText())
                method == "PUT" && path == "/project" -> handlePutProject(body, query)
                method == "GET" && path == "/transport" -> 200 to transportJson()
                method == "POST" && path == "/transport/play" -> {
                    session.play(); 200 to transportJson()
                }
                method == "POST" && path == "/transport/pause" -> {
                    session.pause(); 200 to transportJson()
                }
                method == "POST" && path == "/transport/stop" -> {
                    session.stop(); 200 to transportJson()
                }
                method == "POST" && path == "/transport/seek" -> {
                    val tick = query["tick"]?.toIntOrNull() ?: 0
                    session.seek(tick)
                    200 to transportJson()
                }
                method == "POST" && path == "/bpm" -> {
                    val v = query["value"]?.toFloatOrNull()
                        ?: runCatching { JSONObject(body).optDouble("value").toFloat() }.getOrNull()
                        ?: return 400 to JSONObject().put("error", "缺少 value")
                    session.project.bpm = v.coerceIn(20f, 300f)
                    session.pushBpm()
                    200 to JSONObject().put("bpm", session.project.bpm)
                }
                method == "GET" && path == "/tracks" -> 200 to tracksJson()
                method == "POST" && path == "/tracks" -> handleAddTrack(body)
                method == "PATCH" && path.startsWith("/tracks/") ->
                    handlePatchTrack(path.removePrefix("/tracks/").toIntOrNull(), body)
                method == "GET" && path == "/patterns" -> 200 to patternsJson()
                method == "PUT" && path.startsWith("/patterns/") ->
                    handlePutPattern(path.removePrefix("/patterns/").toIntOrNull(), body)
                // 增量编辑：只动一条轨道的音符，不用传整首
                method == "POST" && path == "/pattern/edit" -> handlePatternEdit(body)
                // 追加音符
                method == "POST" && path == "/notes/add" -> handleNotesAdd(body)
                // 删音符
                method == "POST" && path == "/notes/remove" -> handleNotesRemove(body)
                // 移调/力度等批量变形
                method == "POST" && path == "/notes/transform" -> handleNotesTransform(body)
                // 试听某个音符（不写入，只发声）
                method == "POST" && path == "/audition" -> handleAudition(body)
                method == "POST" && path == "/clips" -> handleAddClip(body)
                method == "DELETE" && path.startsWith("/clips/") ->
                    handleDeleteClip(path.removePrefix("/clips/").toIntOrNull())
                method == "POST" && path == "/note/on" -> handleNoteOn(body)
                method == "POST" && path == "/note/off" -> handleNoteOff(body)
                method == "POST" && path == "/save" -> {
                    session.save(null) { ok ->
                        Log.i(TAG, "保存: $ok")
                    }
                    200 to JSONObject().put("saving", true)
                }
                method == "GET" && path == "/projects" -> 200 to projectsJson()
                method == "POST" && path == "/projects/open" -> handleOpenProject(body)
                else -> 404 to JSONObject().put("error", "没有这个接口: $method $path")
            }
        } catch (t: Throwable) {
            500 to JSONObject().put("error", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun checkAuth(header: String?): Boolean {
        if (header == null) return false
        val provided = header.removePrefix("Bearer ").removePrefix("bearer ").trim()
        if (provided.length != token.length) return false
        // 定长比较，别用 == 泄露长度信息
        var diff = 0
        for (i in provided.indices) diff = diff or (provided[i].code xor token[i].code)
        return diff == 0
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null
            else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()

    // ---------------------------------------------------------------- 数据组装

    private fun stateJson(): JSONObject {
        val p = session.project
        return JSONObject()
            .put("ok", true)
            .put("revision", revision)
            .put("name", p.name)
            .put("bpm", p.bpm.toDouble())
            .put("transport", transportJson())
            .put("lengthBars", p.lengthBars())
            .put("lengthTicks", p.lengthTicks())
            .put("trackCount", p.tracks.size)
            .put("patternCount", p.patterns.size)
            .put("clipCount", p.clips.size)
            .put("currentPattern", p.currentPattern)
            .put("selectedTrack", p.selectedTrack)
            .put("loop", JSONObject()
                .put("on", p.loopOn)
                .put("start", p.loopStart)
                .put("end", p.loopEnd))
            .put("master", JSONObject()
                .put("volume", p.masterVolume)
                .put("limiter", p.masterLimiter))
            .put("tracks", JSONArray().apply {
                p.tracks.forEachIndexed { i, t ->
                    put(JSONObject()
                        .put("index", i)
                        .put("name", t.name)
                        .put("instrument", t.instrument.name)
                        .put("instrumentCn", t.instrument.cn)
                        .put("volume", t.volume.toDouble())
                        .put("pan", t.pan.toDouble())
                        .put("mute", t.mute)
                        .put("solo", t.solo)
                        .put("effects", JSONArray().apply {
                            t.effects.forEach { e ->
                                if (e.type != EffectType.NONE) put(e.type.name)
                            }
                        }))
                }
            })
            .put("patterns", JSONArray().apply {
                p.patterns.forEach { pat ->
                    put(JSONObject()
                        .put("id", pat.id)
                        .put("name", pat.name)
                        .put("bars", pat.bars)
                        .put("noteCount", pat.noteCount())
                        .put("tracks", JSONArray().apply {
                            pat.tracks.forEach { (trk, notes) ->
                                if (notes.isNotEmpty()) {
                                    put(JSONObject().put("track", trk).put("notes", notes.size))
                                }
                            }
                        }))
                }
            })
    }

    private fun transportJson(): JSONObject = JSONObject()
        .put("playing", session.isPlaying())
        .put("positionTicks", session.positionTicks())
        .put("positionBar", session.positionTicks() / Note.BAR + 1)
        .put("positionBeat", (session.positionTicks() % Note.BAR) / Note.PPQ + 1)
        .put("bpm", session.project.bpm.toDouble())

    private fun tracksJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("tracks", JSONArray().apply {
            session.project.tracks.forEachIndexed { i, t ->
                put(JSONObject(t.toJson().toString()).put("index", i))
            }
        })

    private fun patternsJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("patterns", JSONArray().apply {
            session.project.patterns.forEach { put(it.toJson()) }
        })

    private fun projectsJson(): JSONObject = JSONObject()
        .put("ok", true)
        .put("dir", session.projectsDir().absolutePath)
        .put("projects", JSONArray().apply {
            session.listProjects().forEach { f ->
                put(JSONObject()
                    .put("name", f.nameWithoutExtension)
                    .put("path", f.absolutePath)
                    .put("size", f.length())
                    .put("modified", f.lastModified()))
            }
        })

    // ---------------------------------------------------------------- 写操作

    private fun handlePutProject(body: String, query: Map<String, String>): Pair<Int, JSONObject> {
        if (body.isBlank()) return 400 to JSONObject().put("error", "body 为空")

        // 乐观锁：插件带上它读到的 revision，如果用户在此期间改过东西，
        // 我们就拒绝覆盖。否则 AI 会把用户刚手动调好的东西无声地冲掉。
        val expect = query["revision"]?.toLongOrNull()
        if (expect != null && expect != revision) {
            return 409 to JSONObject()
                .put("error", "工程已被改动（你看到的是 revision $expect，当前是 $revision）。" +
                        "请重新读取状态后再写。")
                .put("revision", revision)
        }

        val loaded = Project.fromText(body)
            ?: return 400 to JSONObject().put("error", "不是合法的工程 JSON")
        // 基本体检，别把明显坏掉的东西塞给引擎
        if (loaded.tracks.isEmpty()) {
            return 400 to JSONObject().put("error", "工程里没有轨道")
        }
        if (loaded.patterns.isEmpty()) {
            return 400 to JSONObject().put("error", "工程里没有样式")
        }
        session.replaceProject(loaded)
        bumpRevision()
        return 200 to JSONObject()
            .put("ok", true)
            .put("revision", revision)
            .put("name", loaded.name)
            .put("tracks", loaded.tracks.size)
            .put("patterns", loaded.patterns.size)
            .put("clips", loaded.clips.size)
            .put("lengthBars", loaded.lengthBars())
    }

    private fun handleAddTrack(body: String): Pair<Int, JSONObject> {
        val o = if (body.isBlank()) JSONObject() else JSONObject(body)
        val instName = o.optString("inst", "SYNTH").uppercase()
        val inst = runCatching { Instrument.valueOf(instName) }.getOrElse {
            return 400 to JSONObject().put("error", "不认识的乐器 $instName")
        }
        val name = o.optString("name", inst.cn)
        val idx = session.project.addTrack(inst, name)
        session.pushAll()
        session.scheduleAutoSave()
        return 200 to JSONObject().put("ok", true).put("index", idx).put("name", name)
    }

    private fun handlePatchTrack(index: Int?, body: String): Pair<Int, JSONObject> {
        if (index == null) return 400 to JSONObject().put("error", "轨道下标不合法")
        val t = session.project.trackAt(index)
            ?: return 404 to JSONObject().put("error", "没有第 $index 条轨道")
        if (body.isBlank()) return 400 to JSONObject().put("error", "body 为空")
        val o = JSONObject(body)

        if (o.has("name")) t.name = o.optString("name", t.name)
        if (o.has("volume")) t.volume = o.optDouble("volume", t.volume.toDouble()).toFloat().coerceIn(0f, 1.5f)
        if (o.has("pan")) t.pan = o.optDouble("pan", t.pan.toDouble()).toFloat().coerceIn(-1f, 1f)
        if (o.has("mute")) t.mute = o.optBoolean("mute", t.mute)
        if (o.has("solo")) t.solo = o.optBoolean("solo", t.solo)
        if (o.has("sendReverb")) t.sendReverb = o.optDouble("sendReverb").toFloat().coerceIn(0f, 1f)
        if (o.has("sendDelay")) t.sendDelay = o.optDouble("sendDelay").toFloat().coerceIn(0f, 1f)
        if (o.has("instrument")) {
            val inst = runCatching { Instrument.valueOf(o.optString("instrument").uppercase()) }.getOrNull()
                ?: return 400 to JSONObject().put("error", "不认识的乐器")
            t.instrument = inst
        }
        if (o.has("params")) {
            val arr = o.optJSONArray("params") ?: return 400 to JSONObject().put("error", "params 必须是数组")
            for (i in 0 until minOf(12, arr.length())) {
                t.params[i] = arr.optDouble(i, t.params[i].toDouble()).toFloat().coerceIn(0f, 1f)
            }
        }
        if (o.has("effects")) {
            val arr = o.optJSONArray("effects")
            if (arr != null) {
                for (i in 0 until minOf(4, arr.length())) {
                    val eo = arr.optJSONObject(i) ?: continue
                    t.effects[i] = Effect.fromJson(eo)
                }
            }
        }

        session.pushTrack(index)
        session.scheduleAutoSave()
        return 200 to JSONObject().put("ok", true).put("index", index)
    }

    private fun handlePutPattern(id: Int?, body: String): Pair<Int, JSONObject> {
        if (id == null) return 400 to JSONObject().put("error", "样式 id 不合法")
        if (body.isBlank()) return 400 to JSONObject().put("error", "body 为空")
        val o = JSONObject(body)

        val existingIdx = session.project.patterns.indexOfFirst { it.id == id }
        val pat = Pattern.fromJson(o)
        pat.id = id

        if (existingIdx >= 0) {
            session.project.patterns[existingIdx] = pat
        } else {
            session.project.patterns.add(pat)
            session.project.nextPatternId = maxOf(session.project.nextPatternId, id + 1)
        }
        session.pushAll()
        session.scheduleAutoSave()
        return 200 to JSONObject()
            .put("ok", true)
            .put("id", pat.id)
            .put("name", pat.name)
            .put("notes", pat.noteCount())
    }

    /**
     * 在某个样式的某条轨道上直接写音符（整体替换这一条轨道）。
     * 这是插件最常用的编辑入口：改鼓的一条轨，不需要把整首歌传一遍。
     */
    private fun handlePatternEdit(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val patId = o.optInt("patternId", -1)
        val trackIdx = o.optInt("track", -1)
        val notesArr = o.optJSONArray("notes")
            ?: return 400 to JSONObject().put("error", "需要 notes 数组")

        val pat = session.project.patternById(patId)
            ?: return 404 to JSONObject().put("error", "没有 id=$patId 的样式")
        if (trackIdx !in session.project.tracks.indices) {
            return 400 to JSONObject().put("error", "track $trackIdx 越界")
        }
        if (o.has("bars")) {
            pat.bars = o.optInt("bars", pat.bars).coerceIn(1, 64)
        }

        val list = pat.notes(trackIdx)
        list.clear()
        for (i in 0 until notesArr.length()) {
            val n = notesArr.optJSONObject(i) ?: continue
            list.add(
                Note(
                    pitch = n.optInt("pitch", 60).coerceIn(0, 127),
                    start = n.optInt("start", 0).coerceAtLeast(0),
                    length = n.optInt("length", Note.STEP).coerceAtLeast(1),
                    velocity = n.optInt("velocity", 100).coerceIn(1, 127)
                )
            )
        }
        list.sortBy { it.start }

        session.pushBpm()
        session.scheduleAutoSave()
        bumpRevision()
        return 200 to JSONObject()
            .put("ok", true)
            .put("revision", revision)
            .put("patternId", patId)
            .put("track", trackIdx)
            .put("notes", list.size)
    }

    /** 往已有样式里追加音符，不影响原有内容 */
    private fun handleNotesAdd(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val patId = o.optInt("patternId", -1)
        val trackIdx = o.optInt("track", -1)
        val arr = o.optJSONArray("notes")
            ?: return 400 to JSONObject().put("error", "需要 notes 数组")

        val pat = session.project.patternById(patId)
            ?: return 404 to JSONObject().put("error", "没有 id=$patId 的样式")
        if (trackIdx !in session.project.tracks.indices) {
            return 400 to JSONObject().put("error", "track $trackIdx 越界")
        }
        val list = pat.notes(trackIdx)
        var added = 0
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            list.add(
                Note(
                    pitch = n.optInt("pitch", 60).coerceIn(0, 127),
                    start = n.optInt("start", 0).coerceAtLeast(0),
                    length = n.optInt("length", Note.STEP).coerceAtLeast(1),
                    velocity = n.optInt("velocity", 100).coerceIn(1, 127)
                )
            )
            added++
        }
        list.sortBy { it.start }
        session.pushBpm()
        session.scheduleAutoSave()
        bumpRevision()
        return 200 to JSONObject().put("ok", true).put("added", added).put("revision", revision)
    }

    /** 按条件删音符 */
    private fun handleNotesRemove(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val patId = o.optInt("patternId", -1)
        val pat = session.project.patternById(patId)
            ?: return 404 to JSONObject().put("error", "没有 id=$patId 的样式")

        val trackIdx = o.optInt("track", -1)
        val targets = if (trackIdx >= 0) {
            pat.notesOrNull(trackIdx)?.let { listOf(it) } ?: emptyList()
        } else {
            pat.tracks.values.toList()
        }
        if (targets.isEmpty()) {
            return 200 to JSONObject().put("ok", true).put("removed", 0).put("revision", revision)
        }

        val all = o.optBoolean("all", false)
        val pitchLo = o.optInt("pitchMin", 0)
        val pitchHi = o.optInt("pitchMax", 127)
        val startFrom = o.optInt("startFrom", 0)
        val startTo = o.optInt("startTo", Int.MAX_VALUE)

        var removed = 0
        for (list in targets) {
            if (all) {
                removed += list.size
                list.clear()
            } else {
                val before = list.size
                list.removeAll {
                    it.pitch in pitchLo..pitchHi && it.start >= startFrom && it.start < startTo
                }
                removed += before - list.size
            }
        }
        session.pushBpm()
        session.scheduleAutoSave()
        bumpRevision()
        return 200 to JSONObject().put("ok", true).put("removed", removed).put("revision", revision)
    }

    /**
     * 批量变形：移调、改力度、量化、随机人性化。
     * 这些是"改一下感觉"的操作，AI 最常用，也最需要原子地做完。
     */
    private fun handleNotesTransform(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val patId = o.optInt("patternId", -1)
        val pat = session.project.patternById(patId)
            ?: return 404 to JSONObject().put("error", "没有 id=$patId 的样式")

        val trackIdx = o.optInt("track", -1)
        val lists = if (trackIdx >= 0) {
            listOfNotNull(pat.notesOrNull(trackIdx))
        } else {
            pat.tracks.values.toList()
        }
        if (lists.isEmpty()) {
            return 200 to JSONObject().put("ok", true).put("touched", 0).put("revision", revision)
        }

        val transpose = o.optInt("transpose", 0)
        val velocityScale = o.optDouble("velocityScale", 1.0).toFloat()
        val velocitySet = if (o.has("velocity")) o.optInt("velocity", 100) else null
        val quantize = o.optInt("quantize", 0)
        val humanize = o.optDouble("humanize", 0.0).toFloat()

        var touched = 0
        val rnd = java.util.Random()
        for (list in lists) {
            for (n in list) {
                if (transpose != 0) n.pitch = (n.pitch + transpose).coerceIn(0, 127)
                if (velocitySet != null) n.velocity = velocitySet.coerceIn(1, 127)
                else if (velocityScale != 1f) {
                    n.velocity = (n.velocity * velocityScale).toInt().coerceIn(1, 127)
                }
                if (quantize > 0) {
                    n.start = Math.round(n.start.toFloat() / quantize) * quantize
                }
                if (humanize > 0f) {
                    val jitter = ((rnd.nextFloat() - 0.5f) * 2f * humanize * Note.STEP).toInt()
                    n.fine = (n.fine + jitter).coerceIn(-Note.STEP, Note.STEP)
                }
                touched++
            }
            list.sortBy { it.start }
        }
        session.pushBpm()
        session.scheduleAutoSave()
        bumpRevision()
        return 200 to JSONObject().put("ok", true).put("touched", touched).put("revision", revision)
    }

    /** 试听一个音符，不写入工程。AI 写完可以先让我听一下再决定。 */
    private fun handleAudition(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val patId = o.optInt("patternId", -1)
        val trackIdx = o.optInt("track", session.project.selectedTrack)

        if (patId >= 0) {
            // 试听整个样式的某条轨道：从头播一遍
            val pat = session.project.patternById(patId)
                ?: return 404 to JSONObject().put("error", "没有 id=$patId 的样式")
            val notes = pat.notesOrNull(trackIdx)
            if (notes.isNullOrEmpty()) {
                return 200 to JSONObject().put("ok", true).put("played", 0)
            }
            // 用第一条音符的音高起头，快速给个听觉反馈
            session.previewNote(trackIdx, notes.first().pitch, notes.first().velocity,
                (pat.lengthTicks).coerceAtMost(Note.BAR))
            return 200 to JSONObject().put("ok", true).put("played", notes.size)
        }

        val pitch = o.optInt("pitch", 60)
        val vel = o.optInt("velocity", 100)
        val dur = o.optInt("duration", Note.STEP * 4)
        session.previewNote(trackIdx, pitch, vel, dur)
        return 200 to JSONObject().put("ok", true).put("pitch", pitch)
    }

    private fun handleAddClip(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val patId = o.optInt("patternId", -1)
        if (session.project.patternById(patId) == null) {
            return 400 to JSONObject().put("error", "patternId $patId 不存在")
        }
        val track = o.optInt("track", 0)
        if (track !in session.project.tracks.indices) {
            return 400 to JSONObject().put("error", "track $track 越界")
        }
        val p = session.project
        val c = Clip(
            id = p.nextClipId++,
            patternId = patId,
            track = track,
            start = o.optInt("start", 0),
            length = o.optInt("length", Note.BAR * 2),
            offset = o.optInt("offset", 0)
        )
        p.clips.add(c)
        session.pushBpm()
        session.scheduleAutoSave()
        return 200 to JSONObject().put("ok", true).put("id", c.id)
    }

    private fun handleDeleteClip(id: Int?): Pair<Int, JSONObject> {
        if (id == null) return 400 to JSONObject().put("error", "片段 id 不合法")
        val removed = session.project.clips.removeAll { it.id == id }
        if (!removed) return 404 to JSONObject().put("error", "没有 id=$id 的片段")
        session.pushBpm()
        session.scheduleAutoSave()
        return 200 to JSONObject().put("ok", true)
    }

    private fun handleNoteOn(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val track = o.optInt("track", session.project.selectedTrack)
        val pitch = o.optInt("pitch", 60)
        val vel = o.optInt("velocity", 100)
        val dur = o.optInt("duration", Note.STEP * 4)
        session.previewNote(track, pitch, vel, dur)
        return 200 to JSONObject().put("ok", true)
    }

    private fun handleNoteOff(body: String): Pair<Int, JSONObject> {
        val track = if (body.isBlank()) session.project.selectedTrack
        else JSONObject(body).optInt("track", session.project.selectedTrack)
        session.releaseNote(track)
        return 200 to JSONObject().put("ok", true)
    }

    private fun handleOpenProject(body: String): Pair<Int, JSONObject> {
        val o = JSONObject(body)
        val name = o.optString("name", "")
        val file = session.listProjects().firstOrNull { it.nameWithoutExtension == name }
            ?: return 404 to JSONObject().put("error", "没有名为 $name 的工程")
        session.load(file) { ok -> Log.i(TAG, "打开工程 $name: $ok") }
        return 200 to JSONObject().put("ok", true).put("loading", name)
    }
}
