// FlowStudio —— AAudio 实时输出后端。
// 只做一件事：把引擎的渲染函数挂到 AAudio 的数据回调上，回调里零分配。
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <thread>
#include <android/log.h>
#include <atomic>
#include <vector>

#include "flow/engine.h"

#define LOG_TAG "FlowStudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace flow {

namespace {

/**
 * OpenSL ES 后备后端。
 *
 * AAudio 在个别设备的某些状态（路由切换、独占被拒、系统音频服务异常）下会
 * openStream 失败。只靠 AAudio 的话，那些机器上就是彻底没声音，
 * 而且用户完全不知道为什么。这里给一条更老但更稳的路径。
 *
 * 用最简单的 buffer queue（不用低延迟回调模式），
 * 拿一个独立线程定时喂数据 —— 延迟高一些，但能出声远比延迟重要。
 */
struct OpenslBackend : Engine::AudioBackend {
    SLObjectItf engineObj = nullptr;
    SLEngineItf engineItf = nullptr;
    SLObjectItf mixObj = nullptr;
    SLObjectItf playerObj = nullptr;
    SLPlayItf playItf = nullptr;
    SLAndroidSimpleBufferQueueItf queueItf = nullptr;

    Engine* engine = nullptr;
    std::thread feeder;
    std::atomic<bool> running{false};
    int sr = 48000;
    int blockFrames = 512;
    int channels = 2;

    // 两个缓冲轮转，喂给设备的同时引擎填另一个
    std::vector<float> bufs[2];
    int writeIdx = 0;

    static void onQueueFilled(SLAndroidSimpleBufferQueueItf bq, void* ctx) {
        auto* self = static_cast<OpenslBackend*>(ctx);
        self->pump();
    }

    void pump() {
        if (!running.load() || !engine) return;
        const int n = blockFrames;
        float* b = bufs[writeIdx].data();
        engine->renderInterleaved(b, n);
        // 交错转非交错交给 OpenSL 的 PCM 输出（它接受交错 float）
        (*queueItf)->Enqueue(queueItf, b, (SLuint32)(n * channels * sizeof(float)));
        writeIdx = 1 - writeIdx;
    }

    bool start(Engine* eng, float sampleRate, int blockSize) override {
        engine = eng;
        sr = (int)sampleRate;
        blockFrames = blockSize > 0 ? blockSize : 512;

        slCreateEngine(&engineObj, 0, nullptr, 0, nullptr, nullptr);
        if (!engineObj) return false;
        (*engineObj)->Realize(engineObj, SL_BOOLEAN_FALSE);
        (*engineObj)->GetInterface(engineObj, SL_IID_ENGINE, &engineItf);

        (*engineItf)->CreateOutputMix(engineItf, &mixObj, 0, nullptr, nullptr);
        if (!mixObj) return false;
        (*mixObj)->Realize(mixObj, SL_BOOLEAN_FALSE);

        SLDataLocator_AndroidSimpleBufferQueue loc = {
            SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2
        };
        SLDataFormat_PCM fmt = {
            SL_DATAFORMAT_PCM, 2, (SLuint32)sr * 1000,
            SL_PCMSAMPLEFORMAT_FIXED_32, SL_PCMSAMPLEFORMAT_FIXED_32,
            SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
            SL_BYTEORDER_LITTLEENDIAN
        };
        SLDataSource src = { &loc, &fmt };

        SLDataLocator_OutputMix outLoc = { SL_DATALOCATOR_OUTPUTMIX, mixObj };
        SLDataSink sink = { &outLoc, nullptr };

        const SLInterfaceID ids[] = { SL_IID_ANDROIDSIMPLEBUFFERQUEUE };
        const SLboolean req[] = { SL_BOOLEAN_TRUE };
        (*engineItf)->CreateAudioPlayer(engineItf, &playerObj, &src, &sink, 1, ids, req);
        if (!playerObj) return false;
        (*playerObj)->Realize(playerObj, SL_BOOLEAN_FALSE);
        (*playerObj)->GetInterface(playerObj, SL_IID_PLAY, &playItf);
        (*playerObj)->GetInterface(playerObj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &queueItf);

        bufs[0].assign((size_t)blockFrames * channels, 0.f);
        bufs[1].assign((size_t)blockFrames * channels, 0.f);
        writeIdx = 0;
        running.store(true);

        (*queueItf)->RegisterCallback(queueItf, onQueueFilled, this);

        // 先塞两块，让播放立刻有东西吃
        pump();
        pump();

        (*playItf)->SetPlayState(playItf, SL_PLAYSTATE_PLAYING);
        LOGI("OpenSL 后备后端已启动 rate=%d block=%d", sr, blockFrames);
        return true;
    }

    void stop() override {
        running.store(false);
        if (playItf) (*playItf)->SetPlayState(playItf, SL_PLAYSTATE_STOPPED);
        if (playerObj) { (*playerObj)->Destroy(playerObj); playerObj = nullptr; }
        if (mixObj) { (*mixObj)->Destroy(mixObj); mixObj = nullptr; }
        if (engineObj) { (*engineObj)->Destroy(engineObj); engineObj = nullptr; }
    }
};

struct AaudioBackend : Engine::AudioBackend {
    AAudioStream* stream = nullptr;
    Engine* engine = nullptr;
    std::atomic<int> xruns{0};

    static aaudio_data_callback_result_t onData(AAudioStream* s, void* user, void* audioData,
                                                int32_t numFrames) {
        auto* self = static_cast<AaudioBackend*>(user);
        if (!self || !self->engine) return AAUDIO_CALLBACK_RESULT_CONTINUE;

        auto* out = static_cast<float*>(audioData);
        const int channels = AAudioStream_getChannelCount(s);

        if (channels >= 2) {
            // 引擎直接写交织立体声，回调里不分配任何内存。
            // 注意块长：如果设备回调和 prepare() 时的块长不一致，
            // renderInterleaved 会因为内部缓冲不够而整块跳过 —— 表现就是完全没声音。
            // 所以这里按固定上限分段处理，绝不整块丢弃。
            const int kMaxChunk = 512;
            int done = 0;
            while (done < numFrames) {
                const int n = std::min<int>(kMaxChunk, numFrames - done);
                self->engine->renderInterleaved(out + (size_t)done * channels, n);
                done += n;
            }
            if (channels > 2) {
                // 多声道设备：复制左右到其余声道，避免后置声道静音
                for (int i = (int)numFrames - 1; i >= 0; i--) {
                    const float l = out[(size_t)i * channels];
                    const float r = out[(size_t)i * channels + 1];
                    for (int c = 2; c < channels; c++) {
                        out[(size_t)i * channels + c] = (c % 2 == 0) ? l : r;
                    }
                }
            }
        } else {
            // 单声道设备：在栈上处理一小块，取左右平均
            float mono[512 * 2];
            int done = 0;
            while (done < numFrames) {
                const int n = std::min<int>(512, numFrames - done);
                self->engine->renderInterleaved(mono, n);
                for (int i = 0; i < n; i++) {
                    out[done + i] = (mono[i * 2] + mono[i * 2 + 1]) * 0.5f;
                }
                done += n;
            }
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void onError(AAudioStream* s, void* user, aaudio_result_t error) {
        (void)s;
        auto* self = static_cast<AaudioBackend*>(user);
        if (self) self->xruns.fetch_add(1);
        LOGE("AAudio error: %s", AAudio_convertResultToText(error));
    }

    bool start(Engine* eng, float sampleRate, int blockSize) override {
        engine = eng;

        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t r = AAudio_createStreamBuilder(&builder);
        if (r != AAUDIO_OK) {
            LOGE("createStreamBuilder: %s", AAudio_convertResultToText(r));
            return false;
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        // LOW_LATENCY 让系统走 fast mixer；设备不支持时系统自己回退
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSampleRate(builder, (int32_t)sampleRate);
        AAudioStreamBuilder_setFramesPerDataCallback(builder, (int32_t)blockSize);
        AAudioStreamBuilder_setDataCallback(builder, onData, this);
        AAudioStreamBuilder_setErrorCallback(builder, onError, this);

        r = AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);
        if (r != AAUDIO_OK) {
            LOGE("openStream: %s", AAudio_convertResultToText(r));
            stream = nullptr;
            return false;
        }

        const int32_t realRate = AAudioStream_getSampleRate(stream);
        const int32_t realBlock = AAudioStream_getFramesPerDataCallback(stream);
        const int32_t realCap = AAudioStream_getBufferCapacityInFrames(stream);
        LOGI("AAudio ready rate=%d callback=%d capacity=%d", realRate, realBlock, realCap);

        // 采样率和块长都以设备实际返回的为准，因此按它重排缓冲
        // prepare 的块长给足余量：AAudio 的实际回调块长有可能大于
        // getFramesPerDataCallback 的返回值（设备切换路由时会变），
        // 缓冲开小了会让 renderInterleaved 整块跳过，听起来就是没声音。
        const int block = std::max(realBlock > 0 ? realBlock : blockSize, 1024);
        engine->prepare((float)realRate, block);

        r = AAudioStream_requestStart(stream);
        if (r != AAUDIO_OK) {
            LOGE("requestStart: %s", AAudio_convertResultToText(r));
            AAudioStream_close(stream);
            stream = nullptr;
            return false;
        }
        LOGI("AAudio started");
        return true;
    }

    void stop() override {
        if (!stream) return;
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        stream = nullptr;
        LOGI("AAudio stopped xruns=%d", xruns.load());
    }
};

}  // namespace

/**
 * 后端选择：优先 AAudio（低延迟），失败自动回落 OpenSL ES。
 *
 * 之前的写法是"AAudio 打不开就 return false"，于是那些机器上
 * 从头到尾没有声音，用户只看到播放按钮变亮、时间在走。
 * 现在两条路都会试，并把最终用的是哪条写进日志。
 */
struct FallbackBackend : Engine::AudioBackend {
    std::unique_ptr<Engine::AudioBackend> primary;
    std::unique_ptr<Engine::AudioBackend> fallback;
    bool usingFallback = false;

    bool start(Engine* eng, float sampleRate, int blockSize) override {
        primary.reset(new AaudioBackend());
        if (primary->start(eng, sampleRate, blockSize)) {
            LOGI("音频后端: AAudio");
            return true;
        }
        LOGW("AAudio 启动失败，改用 OpenSL ES");
        primary.reset();

        fallback.reset(new OpenslBackend());
        if (fallback->start(eng, sampleRate, blockSize)) {
            usingFallback = true;
            LOGI("音频后端: OpenSL ES（后备）");
            return true;
        }
        LOGE("AAudio 与 OpenSL ES 都失败了，没有可用的音频输出");
        return false;
    }

    void stop() override {
        if (primary) primary->stop();
        if (fallback) fallback->stop();
    }
};

std::unique_ptr<Engine::AudioBackend> Engine::AudioBackend::create() {
    return std::unique_ptr<Engine::AudioBackend>(new FallbackBackend());
}

}  // namespace flow
