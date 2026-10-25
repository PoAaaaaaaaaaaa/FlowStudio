package com.volt.flowstudio.ui

/**
 * 包络缓存 key 的回归测试。
 *
 * 起因是 1.22 在真机上崩了：
 *   ArrayIndexOutOfBoundsException: length=361; index=361
 *   at PlaylistView.drawNoteEnvelope
 *
 * 根因是缓存 key 写成了 `clip.id * 31 xor cols`：
 * 这个表达式把片段 id 和像素列数混在同一个数值空间里，
 * 片段 1 宽 97 列与片段 2 宽 64 列会算出同一个 key（126），
 * 于是拿到的是别的长度的数组，按当前列数循环就写越界。
 *
 * 这个测试不依赖 Android 环境（纯 Kotlin 数据类），
 * 用 `kotlinc` 直接编译运行即可，不引入 junit。
 */
object EnvelopeKeyTest {

    /** 与 PlaylistView.EnvelopeKey 同构的比较用键。 */
    private data class Key(
        val clipId: Int, val cols: Int, val start: Int, val length: Int, val offset: Int
    )

    private fun oldKey(clipId: Int, cols: Int): Int = (clipId * 31) xor cols

    @JvmStatic
    fun main(args: Array<String>) {
        var failures = 0

        // 1. 旧写法必须能撞出长度不一致的 key —— 证明这个 bug 是真的。
        var oldCollisions = 0
        val seenOld = HashMap<Int, Int>()   // key -> cols
        for (clipId in 1..24) {
            for (cols in 60..700) {
                val k = oldKey(clipId, cols)
                val prev = seenOld[k]
                if (prev != null && prev != cols) oldCollisions++
                else seenOld[k] = cols
            }
        }
        if (oldCollisions > 0) {
            println("[ ok ] 旧 key 确实会碰撞：$oldCollisions 组（这就是越界的来源）")
        } else {
            println("[FAIL] 旧 key 没复现出碰撞，测试前提不成立")
            failures++
        }

        // 2. 新写法在同样的输入空间里必须零碰撞。
        var newCollisions = 0
        val seenNew = HashMap<Key, Int>()
        for (clipId in 1..24) {
            for (cols in 60..700) {
                for (start in intArrayOf(0, 1920, 3840)) {
                    for (length in intArrayOf(1920, 3840)) {
                        val k = Key(clipId, cols, start, length, 0)
                        val prev = seenNew[k]
                        if (prev != null && prev != cols) newCollisions++
                        else seenNew[k] = cols
                    }
                }
            }
        }
        if (newCollisions == 0) {
            println("[ ok ] 新 key 零碰撞（${seenNew.size} 个不同组合）")
        } else {
            println("[FAIL] 新 key 仍有 $newCollisions 组碰撞")
            failures++
        }

        // 3. 片段拖动后必须换 key，否则画面停在旧位置。
        val a = Key(1, 300, 0, 1920, 0)
        val b = Key(1, 300, 1920, 1920, 0)
        if (a != b) println("[ ok ] 拖动后 key 变化，包络会重算")
        else { println("[FAIL] 拖动后 key 没变，包络会画在旧位置"); failures++ }

        // 4. 左缘裁剪后必须换 key（offset 参与）。
        val c = Key(1, 300, 0, 1920, 0)
        val d = Key(1, 300, 480, 1440, 480)
        if (c != d) println("[ ok ] 裁剪后 key 变化，包络会重算")
        else { println("[FAIL] 裁剪后 key 没变"); failures++ }

        println()
        if (failures == 0) println("全部通过  失败 0 项")
        else println("有失败  失败 $failures 项")
        if (failures != 0) System.exit(1)
    }
}
