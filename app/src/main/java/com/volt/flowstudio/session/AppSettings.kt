package com.volt.flowstudio.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 应用设置：持久化在 SharedPreferences 里。
 *
 * 单独成一个类而不是塞进 StudioSession：设置是"应用级"的，
 * 与当前工程无关 —— 换一首曲子不该把主题和背景清掉。
 */
class AppSettings(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("fs_settings", Context.MODE_PRIVATE)

    /** 自定义背景图的绝对路径（已处理过的那份）。null 表示用默认渐变。 */
    var backgroundPath: String? = prefs.getString(KEY_BG, null)
        internal set(v) { field = v; prefs.edit().putString(KEY_BG, v).apply() }

    /** 背景图是否压暗。开启后内容始终可读。 */
    var dimBackground: Boolean = prefs.getBoolean(KEY_DIM, true)
        internal set(v) { field = v; prefs.edit().putBoolean(KEY_DIM, v).apply() }

    /** 顶部摆件条是否显示。 */
    var showOrnaments: Boolean = prefs.getBoolean(KEY_ORN, true)
        internal set(v) { field = v; prefs.edit().putBoolean(KEY_ORN, v).apply() }

    var metronome: Boolean = prefs.getBoolean(KEY_METRO, false)
        internal set(v) { field = v; prefs.edit().putBoolean(KEY_METRO, v).apply() }

    var keepAwake: Boolean = prefs.getBoolean(KEY_AWAKE, true)
        internal set(v) { field = v; prefs.edit().putBoolean(KEY_AWAKE, v).apply() }

    /** 供 SettingsWindow 直接改完统一落盘（部分字段用 setter 已即时保存）。 */
    fun save() { /* 字段 setter 已各自落盘，这里保留给未来批量写入 */ }

    /**
     * 背景图版本号。
     *
     * 为什么必须有它：`importBackground` 永远写同一个文件名
     * `filesDir/theme/background.jpg`（"更换"的语义就是替换）。
     * 于是用户第二次选图时，`backgroundPath` 这个字符串**和上一次完全一样**，
     * 而加载判据是 `loadedPath != wantPath` —— 两个字符串相等 → 判定"不用重载"
     * → 画面纹丝不动。磁盘上的文件确实换了，内存里那张位图没换。
     *
     * 这就是"设置提示成功、图也存下来了、界面就是不变"的最后一道锁。
     * 现在每次导入都 +1，加载判据里带上它，路径相同也能识别出"内容换了"。
     */
    var backgroundVersion: Int = prefs.getInt(KEY_BG_VER, 0)
        internal set(v) { field = v; prefs.edit().putInt(KEY_BG_VER, v).apply() }

    /** 背景图处理完的信号：让界面重绘。 */
    fun hasBackground(): Boolean {
        val p = backgroundPath
        val exists = p?.let { File(it).isFile } == true
        // 路径在但文件没了（cacheDir 被清/换设备）——日志说明白，不要静默
        if (p != null && !exists) {
            android.util.Log.w("FlowStudio", "背景文件丢失: $p（缓存被清或换机）")
        }
        return exists
    }

    fun loadBackground(): Bitmap? {
        val p = backgroundPath ?: return null
        val f = File(p)
        if (!f.isFile) return null
        val bmp = runCatching { BitmapFactory.decodeFile(p) }.getOrNull()
        if (bmp == null) {
            android.util.Log.w("FlowStudio", "背景解码失败: $p")
        }
        return bmp
    }

    fun clearBackground() {
        backgroundPath?.let { runCatching { File(it).delete() } }
        backgroundPath = null
    }

    companion object {
        private const val KEY_BG = "bg_path"
        private const val KEY_BG_VER = "bg_ver"
        private const val KEY_DIM = "bg_dim"
        private const val KEY_ORN = "ornaments"
        private const val KEY_METRO = "metronome"
        private const val KEY_AWAKE = "keep_awake"

        /** 处理后的背景图固定尺寸上限：再大对观感没有帮助，只费内存。 */
        private const val TARGET_W = 1440
        private const val TARGET_H = 2560

        /**
         * 导入背景图。
         *
         * 1.38 重构：**不再预烘焙**。
         *
         * 旧做法是选图时就把图裁成 1440×2560、去饱和、叠渐变、加光斑，
         * 全部压成一张位图存盘。这带来三个没法修的问题：
         *
         *   1. **比例是写死的**。竖屏 9:16 的裁切到了横屏平板上被 cover 拉伸，
         *      人物会变形或者被切掉半个头。屏幕比例是运行时的属性，
         *      不该在落盘时就被焊死。
         *   2. **参数改不了**。压暗、去饱和、渐变都烘焙进了像素，
         *      用户想"背景再亮一点"只能重新选一次图。设置里的
         *      "背景压暗"开关只能再叠一层，越叠越黑。
         *   3. **每次换屏幕都重新缩放**。换设备、旋转、分屏，
         *      都要把那张 1440×2560 再缩一遍，锯齿就是这样出来的。
         *
         * 现在只做两件事：按设备能承受的上限缩小尺寸（省内存），
         * 存成 JPEG。裁切、去饱和、压暗、渐变、光斑全部交给
         * WallpaperView 在 onDraw 里按**当前实际尺寸**算 ——
         * 那里才知道屏幕到底多宽多高。
         */
        fun importBackground(src: Bitmap, filesDir: File): String? {
            if (src.width < 8 || src.height < 8) return null

            // 只按"内存上限"缩小，不改比例、不裁切。
            // 长边压到 2048 是为了单张位图不超过约 12MB（2048×1152×4）。
            val maxSide = 2048
            val longSide = maxOf(src.width, src.height)
            val scaled = if (longSide > maxSide) {
                val k = maxSide.toFloat() / longSide
                val w = (src.width * k).toInt().coerceAtLeast(8)
                val h = (src.height * k).toInt().coerceAtLeast(8)
                runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrDefault(src)
            } else src

            val dir = File(filesDir, "theme")
            if (!dir.isDirectory && !dir.mkdirs()) return null

            // 序列号：取现有 background-*.jpg 里最大的编号 +1。
            // 路径本身要变化，否则"换图"在加载判据眼里就是"同一张"。
            val seq = (dir.listFiles { f ->
                f.isFile && f.name.startsWith("background-") && f.name.endsWith(".jpg")
            }?.mapNotNull { f ->
                f.name.removePrefix("background-").removeSuffix(".jpg").toIntOrNull()
            }?.maxOrNull() ?: 0) + 1
            val f = File(dir, "background-$seq.jpg")

            val ok = runCatching {
                f.outputStream().use { os ->
                    // 质量 92：原图要留足够信息让运行时去饱和/压暗，
                    // 压太狠会先丢掉暗部细节，之后无论怎么调都回不来。
                    scaled.compress(Bitmap.CompressFormat.JPEG, 92, os)
                }
            }.isSuccess
            if (!ok) {
                runCatching { f.delete() }
                return null
            }
            android.util.Log.i("FlowStudio", "背景已写入 ${f.absolutePath} " +
                "${f.length() / 1024}KB · 源 ${src.width}x${src.height} → 存 ${scaled.width}x${scaled.height}")
            return f.absolutePath
        }

        /** 清掉除 [keep] 之外的所有历史背景图。换图成功后调用。 */
        fun purgeOldBackgrounds(filesDir: File, keep: String?) {
            val dir = File(filesDir, "theme")
            val keepName = keep?.let { File(it).name }
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".jpg") && f.name != keepName) {
                    // 兼容 1.35 及更早的固定文件名 background.jpg
                    runCatching { f.delete() }
                }
            }
        }
    }
}
