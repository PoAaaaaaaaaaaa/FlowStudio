package com.volt.flowstudio.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
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

    /** 背景图处理完的信号：让界面重绘。 */
    fun hasBackground(): Boolean = backgroundPath?.let { File(it).isFile } == true

    fun loadBackground(): Bitmap? {
        val p = backgroundPath ?: return null
        val f = File(p)
        if (!f.isFile) return null
        return runCatching { BitmapFactory.decodeFile(p) }.getOrNull()
    }

    fun clearBackground() {
        backgroundPath?.let { runCatching { File(it).delete() } }
        backgroundPath = null
    }

    companion object {
        private const val KEY_BG = "bg_path"
        private const val KEY_DIM = "bg_dim"
        private const val KEY_ORN = "ornaments"
        private const val KEY_METRO = "metronome"
        private const val KEY_AWAKE = "keep_awake"

        /** 处理后的背景图固定尺寸上限：再大对观感没有帮助，只费内存。 */
        private const val TARGET_W = 1440
        private const val TARGET_H = 2560

        /**
         * 把用户选的图处理成"贴合本软件"的背景。
         *
         * 为什么必须处理：用户在相册里随手选一张，可能过曝、可能饱和到辣眼、
         * 可能是纯白底 —— 直接铺上去会把整个暗色界面的对比度毁掉。
         * DAW 的背景必须退到最后面：看不清内容比没有背景更糟。
         *
         * 处理顺序（每一步都有理由）：
         *   1. 按中心裁切到目标比例（避免拉伸变形）
         *   2. 缩放到目标尺寸（省内存）
         *   3. 去饱和到只剩 18%：让彩色照片变成灰调，不与轨道色竞争
         *   4. 压暗曲线：整体亮度压到 16%，并把对比度收窄（暗部不死黑）
         *   5. 顶部与底部各叠一层渐变压暗：保证 chrome 区域文字可读
         *   6. 叠一层主题色的极淡染色（8%）：让背景与软件配色同源
         */
        fun processBackground(src: Bitmap, cacheDir: File): Bitmap? {
            if (src.width < 8 || src.height < 8) return null

            val targetRatio = TARGET_W.toFloat() / TARGET_H.toFloat()
            val srcRatio = src.width.toFloat() / src.height.toFloat()

            // 1) 中心裁切
            val cropW: Int
            val cropH: Int
            if (srcRatio > targetRatio) {
                cropH = src.height
                cropW = (src.height * targetRatio).toInt().coerceAtMost(src.width)
            } else {
                cropW = src.width
                cropH = (src.width / targetRatio).toInt().coerceAtMost(src.height)
            }
            val cropX = (src.width - cropW) / 2
            val cropY = (src.height - cropH) / 2

            // 2) 缩放
            val out = Bitmap.createBitmap(TARGET_W, TARGET_H, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)

            // 3+4) 用 ColorMatrix 一次完成去饱和 + 压暗 + 收窄对比
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val cm = android.graphics.ColorMatrix()
            // 亮度权重（人眼感知）：R .213 G .715 B .072
            val sat = 0.18f
            val inv = 1f - sat
            val r = 0.213f * inv
            val g = 0.715f * inv
            val b = 0.072f * inv
            cm.set(floatArrayOf(
                r + sat, g, b, 0f, 0f,
                r, g + sat, b, 0f, 0f,
                r, g, b + sat, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ))
            // 再压亮度与对比：contrast 0.72，brightness 加上后整体落在 0.14-0.30
            val contrast = 0.72f
            val offset = 26f
            val dark = android.graphics.ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ))
            cm.postConcat(dark)
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)

            val srcRect = android.graphics.Rect(cropX, cropY, cropX + cropW, cropY + cropH)
            val dstRect = android.graphics.Rect(0, 0, TARGET_W, TARGET_H)
            c.drawBitmap(src, srcRect, dstRect, paint)

            // 5) 上下的额外压暗：chrome 区域（导航栏、走带条）压得更狠
            val topGrad = LinearGradient(
                0f, 0f, 0f, TARGET_H * 0.22f,
                intArrayOf(0xB4000000.toInt(), 0x00000000),
                null, Shader.TileMode.CLAMP
            )
            paint.reset()
            paint.isAntiAlias = true
            paint.shader = topGrad
            c.drawRect(0f, 0f, TARGET_W.toFloat(), TARGET_H * 0.22f, paint)

            val botGrad = LinearGradient(
                0f, TARGET_H * 0.70f, 0f, TARGET_H.toFloat(),
                intArrayOf(0x00000000, 0xCC000000.toInt()),
                null, Shader.TileMode.CLAMP
            )
            paint.shader = botGrad
            c.drawRect(0f, TARGET_H * 0.70f, TARGET_W.toFloat(), TARGET_H.toFloat(), paint)

            // 6) 主题色染色：极淡，只为了让背景与软件同源
            paint.shader = null
            paint.colorFilter = PorterDuffColorFilter(
                com.volt.flowstudio.ui.Palette.withAlpha(com.volt.flowstudio.ui.Palette.ACCENT, 18),
                PorterDuff.Mode.SRC_OVER
            )
            c.drawRect(0f, 0f, TARGET_W.toFloat(), TARGET_H.toFloat(), paint)

            // 7) 右上角加一处极淡的光斑，避免整张图死平
            paint.colorFilter = null
            val glow = RadialGradient(
                TARGET_W * 0.78f, TARGET_H * 0.16f, TARGET_W * 0.62f,
                intArrayOf(0x14FFFFFF, 0x00FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            paint.shader = glow
            c.drawRect(0f, 0f, TARGET_W.toFloat(), TARGET_H.toFloat(), paint)

            return out
        }

        /** 处理并落盘，返回最终路径。失败返回 null。 */
        fun importBackground(src: Bitmap, cacheDir: File): String? {
            val processed = processBackground(src, cacheDir) ?: return null
            val dir = File(cacheDir, "theme")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            // 用时间戳命名而不是固定名：避免 BitmapFactory 命中旧的 inBitmap 缓存
            val f = File(dir, "bg_${System.currentTimeMillis()}.jpg")
            val ok = runCatching {
                f.outputStream().use { os ->
                    processed.compress(Bitmap.CompressFormat.JPEG, 88, os)
                }
            }.isSuccess
            if (!ok) return null
            // 清掉旧图，只留最新一张
            dir.listFiles()?.forEach { old -> if (old != f) runCatching { old.delete() } }
            return f.absolutePath
        }
    }
}
