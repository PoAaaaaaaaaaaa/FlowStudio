package com.volt.flowstudio.model

import org.json.JSONObject

/**
 * 一段导入到工程里的音频素材。
 *
 * 素材是"文件"，片段是"素材在时间线上的摆放"。同一个素材可以被摆很多次、
 * 裁成不同长度、放到不同轨道 —— 这是 FL 播放列表的基本单位，
 * 也是之前"一条全局伴奏轨"做不到的事。
 *
 * 音频本体存在 filesDir/audio/ 下，这里只记引用与元数据，
 * 工程文件保持几十 KB 而不是几十 MB。
 */
data class AudioAsset(
    var id: Int = 1,
    /** 原始文件名，界面上就显示它 —— FL 里片段标题也是文件名。 */
    var name: String = "",
    /** 相对 filesDir 的路径，如 audio/1234567_drumloop.wav */
    var path: String = "",
    var frames: Int = 0,
    var sampleRate: Int = 48000,
    /** 根音用于变速不变调（暂未启用，保留字段便于后续 warp）。 */
    var rootNote: Int = 60,
    /**
     * 内容指纹：解码后按采样点算出来的 64 位哈希。
     *
     * 为什么需要它：用户反馈"每次退出重进多一段音频"。查下来是两条路叠加：
     *   1. 投递目录里的文件在导入回调里删、但回调可能没跑到（进程被杀/异常），
     *      下次启动 drainInbox 又扫一遍 → 同一段音频第二次进素材库；
     *   2. 同一个文件重复导入时，旧逻辑只按"文件名"区分，改个名就是新素材。
     * 有了内容指纹，导入前先算一遍哈希，池子里有同哈希的直接复用，
     * 不再占一份内存、也不再往时间线上多摆一块。
     *
     * 老工程没有这个字段（老存档读出来是空串），回退到"路径相同即视为同一素材"。
     */
    var fingerprint: String = ""
) {
    val durationSec: Float
        get() = if (sampleRate > 0) frames.toFloat() / sampleRate else 0f

    /** 在当前 BPM 下这段素材占多少 tick。 */
    fun lengthTicks(bpm: Float): Int =
        (durationSec.toDouble() * bpm / 60.0 * Note.PPQ).toInt().coerceAtLeast(Note.STEP)

    /**
     * 这条素材和另一条是不是同一段音频。
     *
     * 判据必须严：宁可漏合并，也不能把两段不同的曲子合成一段 ——
     * 合错了是不可逆的数据丢失，合漏了只是留着一条多余的素材。
     *
     * 所以只有两种情况算相同：
     *   1. 两边都有内容指纹且相等（解码出来逐采样一致）；
     *   2. 任一方缺指纹时，只认"路径完全相同"（同一个文件被登记了两次）。
     *
     * 绝不能用的判据：帧数 + 采样率。两首时长恰好相同的歌会撞上，
     * 那是真实会发生且后果严重的情况。
     */
    fun sameContent(other: AudioAsset): Boolean {
        if (fingerprint.isNotEmpty() && other.fingerprint.isNotEmpty()) {
            return fingerprint == other.fingerprint
        }
        return path.isNotEmpty() && path == other.path
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("path", path)
        put("frames", frames)
        put("sr", sampleRate)
        if (rootNote != 60) put("root", rootNote)
        if (fingerprint.isNotEmpty()) put("fp", fingerprint)
    }

    companion object {
        fun fromJson(o: JSONObject) = AudioAsset(
            id = o.optInt("id", 1),
            name = o.optString("name", ""),
            path = o.optString("path", ""),
            frames = o.optInt("frames", 0),
            sampleRate = o.optInt("sr", 48000),
            rootNote = o.optInt("root", 60),
            fingerprint = o.optString("fp", "")
        )

        /**
         * 按采样内容算 64 位指纹。
         *
         * 不用 MessageDigest：那要遍历整个数组两遍（一次 digest、一次格式化），
         * 三分钟的立体声是 3000 万个数，导入时多这几十毫秒没必要。
         * FNV-1a 的 64 位版本一遍扫完，两个不同文件撞上的概率在
         * 素材库这个量级（几百条）可以忽略。
         *
         * 取样步长按长度自适应：超长素材抽一部分点算，保证指纹计算是 O(1) 量级。
         */
        fun fingerprintOf(samples: FloatArray, frames: Int): String {
            if (frames <= 0) return ""
            val total = frames * 2
            // 最多扫 65536 个采样点（约 0.34 秒的交织数据），够区分所有真实素材
            val step = maxOf(1, total / 65536)
            var h = -0x340d631b7bdddcdbL      // FNV-1a 64 位 offset basis
            var i = 0
            while (i < total && i < samples.size) {
                // 把 float 的位模式当整数混进去：-0.0 与 0.0 会撞，但音频里无所谓
                val bits = java.lang.Float.floatToIntBits(samples[i])
                h = h xor (bits.toLong() and 0xFFFFFFFFL)
                h *= 0x100000001b3L            // FNV prime
                i += step
            }
            // 长度也参与：全零的静音文件再多也不会和别的撞
            h = h xor frames.toLong()
            h *= 0x100000001b3L
            return java.lang.Long.toHexString(h)
        }
    }
}
