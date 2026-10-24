// FlowStudio —— AAudio 实时输出后端。
// 只做一件事：把引擎的渲染函数挂到 AAudio 的数据回调上，回调里零分配。
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <atomic>
#include <vector>

#include "flow/engine.h"

#define LOG_TAG "FlowStudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace flow {

namespace {

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
            // 引擎直接写交织立体声，回调里不分配任何内存
            self->engine->renderInterleaved(out, (int)numFrames);
            if (channels > 2) {
                // 多声道设备：复制左右到其余声道，避免后置声道静音
                for (int i = (int)numFrames - 1; i >= 0; i--) {
                    const float l = out[i * channels];
                    const float r = out[i * channels + 1];
                    for (int c = 2; c < channels; c++) {
                        out[i * channels + c] = (c % 2 == 0) ? l : r;
                    }
                }
            }
        } else {
            // 单声道设备：在栈上处理一小块，取左右平均
            float mono[256];
            int done = 0;
            while (done < numFrames) {
                const int n = std::min<int>(256, numFrames - done);
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
        const int block = realBlock > 0 ? realBlock : blockSize;
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

std::unique_ptr<Engine::AudioBackend> Engine::AudioBackend::create() {
    return std::unique_ptr<Engine::AudioBackend>(new AaudioBackend());
}

}  // namespace flow
