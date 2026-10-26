package com.volt.flowstudio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet

/**
 * 播放头贴片：用户自选的"玩偶骑剑"图。
 *
 * 为什么由用户选而不是打包进 APK：那张图在 /storage/emulated/0/Pictures/，
 * 容器（构建环境）被 Android 的 media FUSE 按 uid 隔离读不出来，
 * 而应用走 SAF 拿到的 URI 是用户亲自授权的，读写都合法。
 * 于是把"素材"变成"设置"：设置 → 播放头贴图 → 选那张图，从此一直是它。
 *
 * 抠底：图是浅奶油底 (#FAFAEE 附近) 的 JPG/PNG。选择时按"接近四角均色的
 * 像素 → 透明"做色键抠图，边缘 8px 线性羽化防白边。
 * 得到的是带 alpha 的 PNG 存进 filesDir，播放头直接画。
 *
 * 显示几何：剑尖落在播放头位置（时间点），玩偶骑在剑柄上、
 * 头顶对齐标尺底。整张贴片以"剑柄点"为锚 —— 那是图片约 55% 高度处。
 */
class PlayheadSprite(context: Context) {

    var bitmap: Bitmap? = null
        private set

    /** 剑柄锚点在贴片里的归一位置（0..1，相对贴片宽高）。 */
    var anchorNX = 0.5f
        private set
    var anchorNY = 0.55f
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    companion object {
        private const val FILE = "playhead_sprite.png"

        fun storedFile(context: Context) = java.io.File(context.filesDir, FILE)

        fun hasStored(context: Context) = storedFile(context).isFile

        /**
         * 从 SAF URI 读图 → 色键抠底 → 裁内容包围盒 → 存 filesDir。
         * 返回处理报告（诊断用），失败时 error 非空。
         */
        fun importFromUri(context: Context, uri: android.net.Uri): ImportResult {
            return try {
                val src = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return ImportResult(error = "图片解码为空")
                val cut = chromaKey(src) ?: return ImportResult(error = "抠底失败")
                val f = storedFile(context)
                f.outputStream().use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ImportResult(width = cut.width, height = cut.height, path = f.absolutePath)
            } catch (e: Exception) {
                ImportResult(error = e.message ?: "未知错误")
            }
        }

        /**
         * 色键抠底 + 裁包围盒。
         *
         * 阈值按"与四角均色的距离"判定：纯色底图（AI 生成的白/奶油底）距离 <14
         * 判为背景，14~40 线性羽化。对这张玩偶图实测有效；
         * 换别的底色图也成立 —— 锚定的是角落颜色，不是硬编码的色值。
         */
        fun chromaKey(src: Bitmap): Bitmap? {
            val w = src.width
            val h = src.height
            if (w < 8 || h < 8) return null
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)

            // 四角 8x8 均色当底色
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            val s = 8
            for (cy in intArrayOf(0, h - s)) for (cx in intArrayOf(0, w - s)) {
                for (y in cy until cy + s) for (x in cx until cx + s) {
                    val p = px[y * w + x]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
            }
            r /= n; g /= n; b /= n

            val out = IntArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                val pr = (p shr 16) and 0xFF
                val pg = (p shr 8) and 0xFF
                val pb = p and 0xFF
                val d = Math.sqrt(
                    ((pr - r) * (pr - r) + (pg - g) * (pg - g) + (pb - b) * (pb - b)).toDouble()
                )
                val a = when {
                    d < 14 -> 0
                    d < 40 -> (((d - 14) / 26) * 255).toInt()
                    else -> 255
                }
                out[i] = (a shl 24) or (p and 0x00FFFFFF)
            }

            val cut = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            // 裁到 alpha>24 的包围盒
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            for (y in 0 until h) for (x in 0 until w) {
                if ((out[y * w + x] ushr 24) > 24) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX <= minX || maxY <= minY) return cut
            val pad = 4
            val x0 = (minX - pad).coerceAtLeast(0)
            val y0 = (minY - pad).coerceAtLeast(0)
            val x1 = (maxX + pad).coerceAtMost(w - 1)
            val y1 = (maxY + pad).coerceAtMost(h - 1)
            return Bitmap.createBitmap(cut, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        }

        fun load(context: Context): PlayheadSprite {
            val sp = PlayheadSprite(context)
            val f = storedFile(context)
            if (f.isFile) {
                sp.bitmap = BitmapFactory.decodeFile(f.absolutePath)
            }
            return sp
        }
    }

    data class ImportResult(
        val width: Int = 0,
        val height: Int = 0,
        val path: String? = null,
        val error: String? = null,
    )

    /**
     * 画播放头贴片。
     *
     * @param canvas 画布
     * @param px 播放头 x（时间位置）
     * @param rulerBottom 标尺底部 y —— 玩偶头顶贴着这里
     * @param rowAreaHeight 内容区可用高度 —— 剑尖最多伸到这里
     * @param glow 0..1 播放时的脉冲（剑微微发亮）
     */
    fun draw(
        canvas: Canvas,
        px: Float,
        rulerBottom: Float,
        rowAreaHeight: Float,
        glow: Float,
        dp: (Float) -> Float,
    ) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return

        // 目标高度：占据从标尺底往下最多 3 行轨道的高度，且不超过图片清晰缩放下限
        val targetH = (rowAreaHeight * 0.92f).coerceAtMost(dp(120f))
        val targetW = bmp.width * targetH / bmp.height

        // 锚点（剑柄）放在播放头线上；玩偶整体略向右偏，剑身压线
        val anchorX = px
        val anchorY = rulerBottom + targetH * anchorNY

        val left = anchorX - targetW * anchorNX
        val top = anchorY - targetH * anchorNY

        // 播放时的辉光：先铺一层柔和的强调色光晕
        if (glow > 0.01f) {
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    anchorX, anchorY, targetH * 0.55f,
                    intArrayOf(
                        android.graphics.Color.argb((70 * glow).toInt(), 0x7F, 0xC4, 0xFF),
                        0x00000000
                    ),
                    floatArrayOf(0f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(anchorX, anchorY, targetH * 0.55f, glowPaint)
        }

        canvas.drawBitmap(bmp, null, RectF(left, top, left + targetW, top + targetH), paint)

        // 剑尖指示线：从剑柄锚点往下的细线，保证"现在播到哪"在长图上一眼可读
        val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(150, 0x7F, 0xC4, 0xFF)
            strokeWidth = dp(1.2f)
        }
        canvas.drawLine(anchorX, rulerBottom, anchorX, rowAreaHeight, tipPaint)
    }
}
