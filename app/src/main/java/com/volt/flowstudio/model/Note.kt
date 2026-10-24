package com.volt.flowstudio.model

/** 一个音符。时间单位 tick，PPQ=480，16 分音符=120 tick。 */
data class Note(
    var pitch: Int,
    var start: Int,
    var length: Int,
    var velocity: Int = 100,
    /** 起始微调，单位 tick。摇摆与人性化靠它，不动 start 保持网格干净。 */
    var fine: Int = 0
) {
    val end: Int get() = start + length
    fun covers(t: Int) = t in start until end

    fun copyNote() = Note(pitch, start, length, velocity, fine)

    companion object {
        const val PPQ = 480
        const val STEP = PPQ / 4
        const val BAR = PPQ * 4
    }
}
