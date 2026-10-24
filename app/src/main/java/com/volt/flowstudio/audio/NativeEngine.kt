package com.volt.flowstudio.audio

/**
 * 原生引擎的 Kotlin 门面。
 * 所有对时序敏感的东西（回调、混音、调度）都在 C++ 里；这里只是转发与缓存。
 */
class NativeEngine private constructor() {

    companion object {
        @Volatile private var handle: Long = 0L

        val instance: NativeEngine by lazy { NativeEngine() }

        fun loadLibrary(): Boolean = try {
            System.loadLibrary("flowengine")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 原生库是否加载成功。加载失败时所有方法都是安全的空操作。 */
    var available: Boolean = false
        private set

    fun create(): Boolean {
        if (handle != 0L) return true
        available = try {
            handle = nativeCreate(0)
            handle != 0L
        } catch (t: Throwable) {
            false
        }
        return available
    }

    fun destroy() {
        if (handle != 0L) {
            runCatching { nativeDestroy(handle) }
            handle = 0L
            available = false
        }
    }

    private inline fun withHandle(block: (Long) -> Unit) {
        val h = handle
        if (h != 0L) block(h)
    }

    fun prepare(sampleRate: Int, blockSize: Int) = withHandle { nativePrepare(it, sampleRate, blockSize) }
    fun startAudio(): Boolean = handle != 0L && nativeStartAudio(handle)
    fun stopAudio() = withHandle { nativeStopAudio(it) }
    fun isAudioRunning(): Boolean = handle != 0L && nativeIsAudioRunning(handle)
    fun sampleRate(): Float = if (handle != 0L) nativeSampleRate(handle) else 48000f

    fun play() = withHandle { nativePlay(it) }
    fun pause() = withHandle { nativePause(it) }
    fun stop() = withHandle { nativeStop(it) }
    fun setPosition(ticks: Double) = withHandle { nativeSetPosition(it, ticks) }
    fun position(): Double = if (handle != 0L) nativePosition(handle) else 0.0
    fun isPlaying(): Boolean = handle != 0L && nativeIsPlaying(handle)

    fun setBpm(bpm: Float) = withHandle { nativeSetBpm(it, bpm) }
    fun setLoop(on: Boolean, startTick: Double, endTick: Double) =
        withHandle { nativeSetLoop(it, on, startTick, endTick) }
    fun setMetronome(on: Boolean) = withHandle { nativeSetMetronome(it, on) }

    fun setSchedule(flat: IntArray, totalTicks: Int) = withHandle { nativeSetSchedule(it, flat, totalTicks) }

    fun clearTracks() = withHandle { nativeClearTracks(it) }

    fun setChannel(
        index: Int,
        instrumentOrdinal: Int,
        params: FloatArray,
        transpose: Int,
        velocitySense: Float,
        volume: Float,
        pan: Float,
        sendReverb: Float,
        sendDelay: Float,
        mute: Boolean,
        solo: Boolean,
        fxTypes: IntArray,
        fxEnabled: BooleanArray,
        fxMix: FloatArray,
        fxParams: FloatArray
    ) = withHandle {
        nativeSetChannel(
            it, index, instrumentOrdinal, params, transpose, velocitySense,
            volume, pan, sendReverb, sendDelay, mute, solo,
            fxTypes, fxEnabled, fxMix, fxParams
        )
    }

    fun trackLevels(): IntArray =
        if (handle != 0L) nativeTrackLevels(handle) else IntArray(0)

    fun masterPeak(): FloatArray =
        if (handle != 0L) nativeMasterPeak(handle) else floatArrayOf(0f, 0f)

    fun noteOn(track: Int, pitch: Int, velocity: Float, durationTicks: Int) =
        withHandle { nativeNoteOn(it, track, pitch, velocity, durationTicks) }

    fun noteOff(track: Int) = withHandle { nativeNoteOff(it, track) }
    fun allNotesOff() = withHandle { nativeAllNotesOff(it) }

    fun setMaster(volume: Float, limiter: Boolean, softClip: Boolean, gainDb: Float) =
        withHandle { nativeSetMaster(it, volume, limiter, softClip, gainDb) }

    fun setAutomation(meta: IntArray, ticks: IntArray, values: FloatArray) =
        withHandle { nativeSetAutomation(it, meta, ticks, values) }

    fun renderOffline(startTick: Double, endTick: Double, sampleRate: Int): FloatArray? =
        if (handle != 0L) nativeRenderOffline(handle, startTick, endTick, sampleRate) else null

    fun loadSample(data: FloatArray, sampleRate: Int, rootNote: Int) =
        withHandle { nativeLoadSample(it, data, sampleRate, rootNote) }

    fun clearSample() = withHandle { nativeClearSample(it) }

    // ---------------- JNI ----------------
    private external fun nativeCreate(unused: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePrepare(handle: Long, sampleRate: Int, blockSize: Int)
    private external fun nativeStartAudio(handle: Long): Boolean
    private external fun nativeStopAudio(handle: Long)
    private external fun nativeIsAudioRunning(handle: Long): Boolean
    private external fun nativeSampleRate(handle: Long): Float
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeSetPosition(handle: Long, ticks: Double)
    private external fun nativePosition(handle: Long): Double
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeSetBpm(handle: Long, bpm: Float)
    private external fun nativeSetLoop(handle: Long, on: Boolean, start: Double, end: Double)
    private external fun nativeSetMetronome(handle: Long, on: Boolean)
    private external fun nativeSetSchedule(handle: Long, flat: IntArray, totalTicks: Int)
    private external fun nativeClearTracks(handle: Long)
    private external fun nativeSetChannel(
        handle: Long, index: Int, instrument: Int, params: FloatArray,
        transpose: Int, velocitySense: Float, volume: Float, pan: Float,
        sendReverb: Float, sendDelay: Float, mute: Boolean, solo: Boolean,
        fxTypes: IntArray, fxEnabled: BooleanArray, fxMix: FloatArray, fxParams: FloatArray
    )
    private external fun nativeTrackLevels(handle: Long): IntArray
    private external fun nativeMasterPeak(handle: Long): FloatArray
    private external fun nativeNoteOn(handle: Long, track: Int, pitch: Int, velocity: Float, duration: Int)
    private external fun nativeNoteOff(handle: Long, track: Int)
    private external fun nativeAllNotesOff(handle: Long)
    private external fun nativeSetMaster(handle: Long, volume: Float, limiter: Boolean, softClip: Boolean, gainDb: Float)
    private external fun nativeSetAutomation(handle: Long, meta: IntArray, ticks: IntArray, values: FloatArray)
    private external fun nativeRenderOffline(handle: Long, startTick: Double, endTick: Double, sampleRate: Int): FloatArray?
    private external fun nativeLoadSample(handle: Long, data: FloatArray, sampleRate: Int, rootNote: Int)
    private external fun nativeClearSample(handle: Long)
}
