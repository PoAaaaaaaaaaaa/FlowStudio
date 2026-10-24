package com.volt.flowstudio

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃兜底。
 *
 * 1.0.0 的教训：Application.onCreate 里抛了 NoClassDefFoundError，
 * 用户只看到"打开就闪退"，连个日志都拿不到。这里做两件事：
 *   1) 接管主线程未捕获异常，把完整堆栈写进私有目录的 crash.log；
 *   2) 下次启动时如果上次是崩的，把原因带到界面上，用户能截图发回来。
 *
 * 音频线程的异常不经过这里（它在 native 里），但主线程和所有协程都走这条路径。
 */
object CrashGuard {

    private const val TAG = "FlowStudio"
    private const val FILE_NAME = "crash.log"
    private const val MARKER = "last_crash"

    /** 上一次启动是否是崩溃退出，以及原因摘要。界面读这个。 */
    @Volatile
    var lastCrashSummary: String? = null
        private set

    /** 上一级的处理器，链式调用以保留系统本身的崩溃流程 */
    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        val app = context.applicationContext

        // 先读上次的崩溃记录，再装钩子（否则会被新记录覆盖）
        lastCrashSummary = readLastCrash(app)

        // 必须先抓住当前的处理器，再设新的。顺序反了的话
        // previousHandler 永远是 null，系统那层就断了。
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val name = thread.name ?: ""

            // 辅助线程的崩溃不该带走用户的工程。
            //
            // 真实事故：桥接服务处理一个已经断开的 HTTP 连接时抛
            // SocketException: Broken pipe，从 respond() 冒到线程池，
            // 变成 FATAL EXCEPTION: flow-bridge，整个应用被杀，
            // 用户正在编的曲全没了。
            //
            // 后台线程的死只影响那一条连接，应用必须继续跑。
            if (name.startsWith("flow-bridge") || name.startsWith("flow-io")) {
                Log.e(TAG, "辅助线程 [$name] 崩溃，已隔离，应用继续运行", error)
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                save(app, thread, error)
            } catch (t: Throwable) {
                Log.e(TAG, "写崩溃日志本身失败", t)
            }
            // 主线程的问题仍然交给系统正常处理（不要假装没事）
            previousHandler?.uncaughtException(thread, error)
        }
    }

    /** 直接从包信息读版本。不依赖 BuildConfig —— 本地脚本打包时没有它。 */
    private fun versionNameOf(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun versionCodeOf(context: Context): Long = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun crashFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun markerFile(context: Context): File = File(context.filesDir, MARKER)

    private fun save(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("线程: ${thread.name} (id=${thread.id})")
            pw.println("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            pw.println("版本: ${versionNameOf(context)} (code ${versionCodeOf(context)})")
            pw.println("原生库: ${NativeLib.status}")
            pw.println("--- 堆栈 ---")
            error.printStackTrace(pw)

            // 找出被 Cause 链藏起来的真正原因
            var cause: Throwable? = error
            while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
            if (cause != null && cause !== error) {
                pw.println("--- 根因 ---")
                pw.println("${cause.javaClass.name}: ${cause.message}")
            }
        }

        crashFile(context).writeText(sw.toString())
        markerFile(context).writeText(
            summarize(error)
        )
        Log.e(TAG, "崩溃已记录:\n$sw")
    }

    private fun summarize(error: Throwable): String {
        var cause: Throwable = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        val where = error.stackTrace.firstOrNull()
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "未知位置"
        return "${cause.javaClass.simpleName}: ${cause.message ?: "(无消息)"} @ $where"
    }

    private fun readLastCrash(context: Context): String? {
        val marker = markerFile(context)
        if (!marker.isFile) return null
        val text = runCatching { marker.readText().trim() }.getOrNull().orEmpty()
        // 读一次就清掉，避免每次启动都提示同一个旧崩溃
        marker.delete()
        return text.ifEmpty { null }
    }

    /** 完整日志内容，供"导出诊断信息"用 */
    fun fullLog(context: Context): String =
        runCatching { crashFile(context).readText() }.getOrNull() ?: "(没有崩溃记录)"

    fun clearLog(context: Context) {
        crashFile(context).delete()
        markerFile(context).delete()
    }
}

/**
 * 原生库加载的隔离层。
 *
 * 直接把 System.loadLibrary 放在 object 的初始化块里是很危险的：
 * 加载失败会变成 ExceptionInInitializerError，而它又被 lazy 的 lambda 包一层，
 * 最终表现成 NoClassDefFoundError —— 排查时完全看不到真正原因。
 * 这里显式加载、显式记录，失败也只是一个 false，不会连累整个 Application。
 */
object NativeLib {

    @Volatile
    var loaded: Boolean = false
        private set

    @Volatile
    var status: String = "未加载"
        private set

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun load(context: Context): Boolean {
        if (loaded) return true

        val candidates = mutableListOf<String>()
        try {
            System.loadLibrary("flowengine")
            loaded = true
            status = "已加载 (System.loadLibrary)"
            return true
        } catch (t: Throwable) {
            candidates += "System.loadLibrary 失败: ${t.javaClass.simpleName}: ${t.message}"
        }

        // 回退：直接从 APK 的 native lib 目录 dlopen。
        // 有些定制 ROM 的 linker 名字空间会让 loadLibrary 找不到自带的 so。
        val dirs = buildList {
            add(context.applicationInfo.nativeLibraryDir)
            val abi = Build.SUPPORTED_ABIS.firstOrNull()
            if (abi != null) add(File(context.applicationInfo.sourceDir).parent + "!/lib/$abi")
        }
        for (dir in dirs) {
            if (dir.isNullOrBlank()) continue
            val so = File(dir, "libflowengine.so")
            if (!so.isFile) {
                candidates += "不存在: ${so.absolutePath}"
                continue
            }
            try {
                System.load(so.absolutePath)
                loaded = true
                status = "已加载 (dlopen ${so.absolutePath})"
                return true
            } catch (t: Throwable) {
                candidates += "System.load(${so.absolutePath}) 失败: ${t.javaClass.simpleName}: ${t.message}"
            }
        }

        status = candidates.joinToString(" | ")
        Log.e("FlowStudio", "原生库加载失败: $status")
        return false
    }
}
