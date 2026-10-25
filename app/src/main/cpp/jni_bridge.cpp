// FlowStudio —— JNI 桥。Kotlin 侧只看到一个 NativeEngine，所有音频线程都在 C++ 里。
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "flow/engine.h"

#define LOG_TAG "FlowStudioJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace flow;

namespace {

Engine* gEngine = nullptr;
std::mutex gMutex;

inline Engine* eng() { return gEngine; }

void throwIllegal(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, msg);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeCreate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gEngine) {
        delete gEngine;
        gEngine = nullptr;
    }
    gEngine = new Engine();
    LOGI("engine created");
    return (jlong)(intptr_t)gEngine;
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e == gEngine) gEngine = nullptr;
    delete e;
    LOGI("engine destroyed");
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativePrepare(JNIEnv*, jobject, jlong handle,
                                                          jint sampleRate, jint blockSize) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->prepare((float)sampleRate, blockSize);
}

JNIEXPORT jboolean JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeStartAudio(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e) return JNI_FALSE;
    e->startAudio();
    return e->isAudioRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeStopAudio(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->stopAudio();
}

// ---------------------------------------------------------------- 传输

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativePlay(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->play();
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativePause(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->pause();
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeStop(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->stop();
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetPosition(JNIEnv*, jobject, jlong handle,
                                                              jdouble ticks) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->setPositionTicks(ticks);
}

JNIEXPORT jdouble JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativePosition(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    return e ? e->positionTicks() : 0.0;
}

JNIEXPORT jboolean JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeIsPlaying(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    return (e && e->isPlaying()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetBpm(JNIEnv*, jobject, jlong handle, jfloat bpm) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->setBpm(bpm);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetLoop(JNIEnv*, jobject, jlong handle,
                                                          jboolean on, jdouble start, jdouble end) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->setLoop(on == JNI_TRUE, start, end);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetMetronome(JNIEnv*, jobject, jlong handle,
                                                               jboolean on) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->setMetronome(on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetMetronomeVolume(JNIEnv*, jobject, jlong handle,
                                                                     jfloat v) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->setMetronomeVolume(v);
}

// ---------------------------------------------------------------- 编排

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetSchedule(JNIEnv* env, jobject, jlong handle,
                                                              jintArray flat, jint totalTicks) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e || !flat) return;

    const jsize len = env->GetArrayLength(flat);
    if (len % 5 != 0) { throwIllegal(env, "schedule 数组长度必须是 5 的倍数"); return; }

    std::vector<jint> tmp((size_t)len);
    env->GetIntArrayRegion(flat, 0, len, tmp.data());

    Schedule s;
    s.totalTicks = totalTicks;
    s.notes.reserve((size_t)len / 5);
    for (jsize i = 0; i + 4 < len; i += 5) {
        FlatNote fn;
        fn.startTick = tmp[(size_t)i];
        fn.endTick = tmp[(size_t)i + 1];
        fn.pitch = tmp[(size_t)i + 2];
        fn.velocity = tmp[(size_t)i + 3];
        fn.track = (int16_t)(tmp[(size_t)i + 4] & 0x7FFF);
        fn.pattern = 0;
        s.notes.push_back(fn);
    }
    e->setSchedule(s);
    LOGI("schedule set: %d notes, %d ticks", (int)s.notes.size(), totalTicks);
}

// ---------------------------------------------------------------- 通道

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeClearTracks(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->clearTracks();
}

// 通道配置按扁平数组传：每轨 20 个 float + N 个效果槽。
// 效果槽又用一组 int 描述类型与开关、一组 float 描述参数。
JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetChannel(
        JNIEnv* env, jobject, jlong handle,
        jint index, jint instrument,
        jfloatArray params, jint transpose, jfloat velocitySense,
        jfloat volume, jfloat pan, jfloat sendReverb, jfloat sendDelay,
        jboolean mute, jboolean solo,
        jintArray fxTypes, jbooleanArray fxEnabled, jfloatArray fxMix, jfloatArray fxParams) {

    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e || !params || !fxTypes || !fxEnabled || !fxMix || !fxParams) return;

    Channel ch;
    ch.instrument = (Instrument)instrument;
    ch.transpose = transpose;
    ch.velocitySense = velocitySense;
    ch.volume = volume;
    ch.pan = pan;
    ch.sendReverb = sendReverb;
    ch.sendDelay = sendDelay;
    ch.mute = mute == JNI_TRUE;
    ch.solo = solo == JNI_TRUE;

    jfloat pbuf[12];
    if (env->GetArrayLength(params) >= 12) {
        env->GetFloatArrayRegion(params, 0, 12, pbuf);
        std::memcpy(ch.params, pbuf, sizeof(pbuf));
    }

    const jsize fxCount = std::min<jsize>(env->GetArrayLength(fxTypes), Engine::kMaxFx);
    jint types[Engine::kMaxFx] = {0, 0, 0, 0};
    jboolean enabled[Engine::kMaxFx] = {JNI_TRUE, JNI_TRUE, JNI_TRUE, JNI_TRUE};
    jfloat mixes[Engine::kMaxFx] = {0.35f, 0.35f, 0.35f, 0.35f};
    jfloat fxp[Engine::kMaxFx * 8];
    std::memset(fxp, 0, sizeof(fxp));
    for (int i = 0; i < Engine::kMaxFx * 8; i++) fxp[i] = 0.5f;

    if (fxCount > 0) {
        env->GetIntArrayRegion(fxTypes, 0, fxCount, types);
        env->GetBooleanArrayRegion(fxEnabled, 0, fxCount, enabled);
        env->GetFloatArrayRegion(fxMix, 0, fxCount, mixes);
        const jsize pLen = std::min<jsize>(env->GetArrayLength(fxParams), Engine::kMaxFx * 8);
        env->GetFloatArrayRegion(fxParams, 0, pLen, fxp);
    }

    for (int i = 0; i < Engine::kMaxFx; i++) {
        ch.fx[i].type = (i < fxCount) ? (EffectType)types[i] : EffectType::None;
        ch.fx[i].enabled = (i < fxCount) ? (enabled[i] == JNI_TRUE) : false;
        ch.fx[i].mix = (i < fxCount) ? mixes[i] : 0.f;
        for (int k = 0; k < 8; k++) ch.fx[i].p[k] = fxp[i * 8 + k];
    }

    e->setTrackConfig(index, ch);
}

JNIEXPORT jintArray JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeTrackLevels(JNIEnv* env, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    const int count = e ? e->trackCount() : 0;
    jintArray out = env->NewIntArray(count > 0 ? count : 1);
    if (!out) return nullptr;
    if (count > 0 && e) {
        std::vector<jint> levels((size_t)count);
        for (int i = 0; i < count; i++) {
            // 0..1000 的整数，避免每帧传 float 数组带来的 JNI 开销
            levels[(size_t)i] = (jint)(e->trackPeak(i) * 1000.f);
        }
        env->SetIntArrayRegion(out, 0, count, levels.data());
    }
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeMasterPeak(JNIEnv* env, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    jfloatArray out = env->NewFloatArray(2);
    if (!out) return nullptr;
    jfloat vals[2] = {0.f, 0.f};
    if (e) { vals[0] = e->masterPeakL(); vals[1] = e->masterPeakR(); }
    env->SetFloatArrayRegion(out, 0, 2, vals);
    return out;
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeNoteOn(JNIEnv*, jobject, jlong handle,
                                                         jint track, jint pitch,
                                                         jfloat velocity, jint durationTicks) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->setTrackNote(track, pitch, velocity, durationTicks);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeNoteOff(JNIEnv*, jobject, jlong handle, jint track) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->releaseTrackNote(track);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeAllNotesOff(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->allNotesOff();
}

// ---------------------------------------------------------------- 母带与自动化

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetMaster(JNIEnv*, jobject, jlong handle,
                                                            jfloat volume, jboolean limiter,
                                                            jboolean softClip, jfloat gainDb) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->setMaster(volume, limiter == JNI_TRUE, softClip == JNI_TRUE, gainDb);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetAutomation(
        JNIEnv* env, jobject, jlong handle,
        jintArray meta, jintArray ticks, jfloatArray values) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e) return;

    std::vector<AutomationCurve> curves;

    if (meta && ticks && values) {
        const jsize metaLen = env->GetArrayLength(meta);
        const jsize tickLen = env->GetArrayLength(ticks);
        const jsize valLen = env->GetArrayLength(values);
        if (metaLen % 3 == 0 && tickLen == valLen) {
            std::vector<jint> metaBuf((size_t)metaLen);
            std::vector<jint> tickBuf((size_t)tickLen);
            std::vector<jfloat> valBuf((size_t)valLen);
            env->GetIntArrayRegion(meta, 0, metaLen, metaBuf.data());
            env->GetIntArrayRegion(ticks, 0, tickLen, tickBuf.data());
            env->GetFloatArrayRegion(values, 0, valLen, valBuf.data());

            jsize offset = 0;
            for (jsize i = 0; i + 2 < metaLen; i += 3) {
                const jint track = metaBuf[(size_t)i];
                const jint target = metaBuf[(size_t)i + 1];
                const jint count = metaBuf[(size_t)i + 2];
                if (count <= 0 || offset + count > tickLen) break;
                AutomationCurve c;
                c.track = (int16_t)track;
                c.target = (int16_t)target;
                c.tick.reserve((size_t)count);
                c.value.reserve((size_t)count);
                for (jint k = 0; k < count; k++) {
                    c.tick.push_back((float)tickBuf[(size_t)(offset + k)]);
                    c.value.push_back(valBuf[(size_t)(offset + k)]);
                }
                curves.push_back(std::move(c));
                offset += count;
            }
        }
    }
    e->setAutomation(curves);
}

// ---------------------------------------------------------------- 离线导出

JNIEXPORT jfloatArray JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeRenderOffline(
        JNIEnv* env, jobject, jlong handle, jdouble startTick, jdouble endTick, jint sampleRate) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e) return nullptr;

    auto res = e->renderOffline(startTick, endTick, sampleRate, false);
    if (!res.ok) {
        LOGE("renderOffline failed");
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray((jsize)res.interleaved.size());
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, (jsize)res.interleaved.size(), res.interleaved.data());
    LOGI("renderOffline: %d frames @ %d Hz", res.frames, res.sampleRate);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeRenderFrameCount(
        JNIEnv*, jobject, jlong handle, jdouble startTick, jdouble endTick, jint sampleRate) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e) return 0;
    const double tps = (double)e->bpm() * (double)kPPQ / 60.0 / (double)sampleRate;
    if (tps <= 0.0) return 0;
    const double ticks = std::max(0.0, endTick - startTick);
    return (jint)std::ceil(ticks / tps);
}

// ---------------------------------------------------------------- 采样器

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeLoadSample(JNIEnv* env, jobject, jlong handle,
                                                             jfloatArray data, jint sampleRate,
                                                             jint rootNote) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e || !data) return;
    const jsize len = env->GetArrayLength(data);
    if (len <= 0) return;
    std::vector<jfloat> buf((size_t)len);
    env->GetFloatArrayRegion(data, 0, len, buf.data());
    e->loadSample(buf.data(), (int)len, sampleRate, rootNote);
    LOGI("sample loaded: %d frames", (int)len);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeClearSample(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->clearSample();
}

// ---------------------------------------------------------------- 伴奏音频轨

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetBackingTrack(
        JNIEnv* env, jobject, jlong handle, jfloatArray data, jint frames,
        jint sampleRate, jdouble startTick, jfloat gain) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e || !data) return;
    const jsize len = env->GetArrayLength(data);
    if (len < 2 || frames <= 0) return;
    // Kotlin 侧传的是交织立体声，长度应为 frames*2；不信任就按实际长度截断。
    const int usableFrames = std::min<int>((int)(len / 2), frames);
    std::vector<jfloat> buf((size_t)usableFrames * 2);
    env->GetFloatArrayRegion(data, 0, (jsize)buf.size(), buf.data());
    e->setBackingTrack(buf.data(), usableFrames, sampleRate, startTick, gain);
    LOGI("backing track: %d frames @ %d Hz, start %.0f ticks", usableFrames, sampleRate, startTick);
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeClearBackingTrack(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (e) e->clearBackingTrack();
}

JNIEXPORT void JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSetBackingGain(
        JNIEnv*, jobject, jlong handle, jfloat gain, jboolean mute) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    if (!e) return;
    e->setBackingTrackGain(gain);
    e->setBackingTrackMute(mute);
}

JNIEXPORT jboolean JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeIsAudioRunning(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    return (e && e->isAudioRunning()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeStats(JNIEnv* env, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    jlongArray out = env->NewLongArray(3);
    if (!out) return nullptr;
    jlong v[3] = {0, 0, 0};
    if (e) {
        auto st = e->stats();
        // 把 float 峰值放大成整数传，避免 Java 侧再开一个 float 数组
        v[0] = (jlong)st.framesRendered;
        v[1] = (jlong)(st.peakEver * 1000000.f);
        v[2] = (jlong)st.callbacks;
    }
    env->SetLongArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jfloat JNICALL
Java_com_volt_flowstudio_audio_NativeEngine_nativeSampleRate(JNIEnv*, jobject, jlong handle) {
    auto* e = reinterpret_cast<Engine*>((intptr_t)handle);
    return e ? e->sampleRate() : 48000.f;
}

}  // extern "C"

