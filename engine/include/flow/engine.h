// FlowStudio —— 引擎主类。音频线程围绕它转，所有状态都在这里。
#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

#include "flow/dsp.h"
#include "flow/effect.h"
#include "flow/sequence.h"
#include "flow/voice.h"

namespace flow {

// 一条通道：乐器 + 混音 + 4 个插入效果。
struct Channel {
    Instrument instrument = Instrument::Synth;
    float params[12] = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
                        0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
    int transpose = 0;
    float velocitySense = 0.7f;
    float volume = 0.8f;
    float pan = 0.f;
    float sendReverb = 0.12f;
    float sendDelay = 0.08f;
    bool mute = false;
    bool solo = false;
    EffectState fx[4];

    // 推子平滑，消除拉链噪声
    Smoother gainSm, panLSm, panRSm, revSm, dlySm;
};

// 一条自动化曲线：tick -> 0..1 的值
struct AutomationCurve {
    int16_t track = 0;
    int16_t target = 0;
    bool enabled = true;
    std::vector<float> tick;
    std::vector<float> value;

    float at(double t, float fallback) const {
        if (!enabled || tick.empty()) return fallback;
        if (tick.size() == 1) return value[0];
        if ((float)t <= tick[0]) return value[0];
        for (size_t i = 1; i < tick.size(); i++) {
            if ((float)t <= tick[i]) {
                const float span = tick[i] - tick[i - 1];
                if (span <= 0.01f) return value[i];
                const float k = ((float)t - tick[i - 1]) / span;
                return value[i - 1] + (value[i] - value[i - 1]) * k;
            }
        }
        return value.back();
    }
};

class Engine {
public:
    static constexpr int kMaxVoices = 96;
    static constexpr int kMaxTracks = 24;
    static constexpr int kMaxFx = 4;

    // 自动化目标编号，与 Kotlin 侧 Automation.Target 的 ordinal 对齐
    enum AutoTarget {
        AutoVolume = 0, AutoPan = 1, AutoSendReverb = 2, AutoSendDelay = 3,
        AutoParam0 = 4, AutoParam1 = 5, AutoParam2 = 6, AutoParam3 = 7,
        AutoFx0P0 = 8, AutoFx0P1 = 9, AutoFx1P0 = 10, AutoFx1P1 = 11
    };

    Engine();
    ~Engine();

    // ---- 生命周期 ----
    void prepare(float sampleRate, int maxBlock);
    void startAudio();
    void stopAudio();
    bool isAudioRunning() const { return audioRunning_.load(); }
    float sampleRate() const { return sampleRate_; }

    // ---- 传输 ----
    void play();
    void pause();
    void stop();
    void setPositionTicks(double t);
    double positionTicks() const { return position_.load(std::memory_order_relaxed); }
    bool isPlaying() const { return playing_.load(std::memory_order_relaxed); }
    void setBpm(float bpm) { bpm_.store(clampf(bpm, 20.f, 300.f), std::memory_order_relaxed); }
    float bpm() const { return bpm_.load(std::memory_order_relaxed); }
    void setLoop(bool on, double startTick, double endTick);
    bool loopOn() const { return loop_.load(); }
    void setMetronome(bool on) { metronome_.store(on, std::memory_order_relaxed); }

    // ---- 编排 ----
    void setSchedule(const Schedule& s);
    int32_t scheduleLengthTicks() const { return schedule_.totalTicks; }

    // ---- 通道 ----
    int addTrack(const Channel& ch);
    void clearTracks();
    void setTrackConfig(int index, const Channel& ch);
    Channel getTrackConfig(int index) const;
    int trackCount() const { return trackCount_.load(std::memory_order_acquire); }
    void setTrackNote(int index, int pitch, float velocity, int durationTicks);
    void releaseTrackNote(int index);
    void allNotesOff();

    // ---- 自动化 ----
    void setAutomation(const std::vector<AutomationCurve>& curves);

    // ---- 母带 ----
    void setMaster(float volume01, bool limiter, bool softClip, float gainDb);

    // ---- 实时反馈 ----
    float trackPeak(int index) const;
    float masterPeakL() const { return masterPeakL_.load(std::memory_order_relaxed); }
    float masterPeakR() const { return masterPeakR_.load(std::memory_order_relaxed); }
    void resetPeaks();

    // ---- 离线渲染（导出）----
    struct RenderResult {
        std::vector<float> interleaved;
        int frames = 0;
        int sampleRate = 0;
        bool ok = false;
    };
    RenderResult renderOffline(double startTick, double endTick, int sampleRate, bool useLoop);

    // ---- 采样器 ----
    void loadSample(const float* data, int frames, int sampleRate, int rootNote);
    void clearSample();
    bool hasSample() const { return sampleFrames_ > 0; }

    // ---- 音频回调入口 ----
    // 直接写交织立体声，内部只用预分配缓冲，绝不 malloc —— 这是音频线程的硬要求。
    void renderInterleaved(float* stereoOut, int frames);

    // ---- 测试与调试 ----
    void processBlockForTest(float* stereoOut, int frames);

    // 平台音频后端：由 audio_android.cpp 提供实现
    struct AudioBackend {
        static std::unique_ptr<AudioBackend> create();
        virtual ~AudioBackend() = default;
        virtual bool start(Engine* engine, float sampleRate, int blockSize) = 0;
        virtual void stop() = 0;
    };

private:
    void configureSmoothers(Channel& ch);
    void renderBlockClean(float* outL, float* outR, int frames);
    void triggerNote(int track, int pitch, float velocity, int32_t endTick);
    void releaseNotesAt(int32_t tick);
    double ticksPerSample() const;
    void resetVoices();
    void applyAutomation(int track, double tick, Channel& ch);

    std::unique_ptr<AudioBackend> backend_;
    std::atomic<bool> audioRunning_{false};

    std::atomic<float> bpm_{120.f};
    std::atomic<double> position_{0.0};
    std::atomic<bool> playing_{false};
    std::atomic<bool> loop_{false};
    std::atomic<double> loopStart_{0.0};
    std::atomic<double> loopEnd_{0.0};
    std::atomic<bool> metronome_{false};

    float sampleRate_ = 48000.f;
    int maxBlock_ = 512;

    std::vector<Voice> voices_;
    std::vector<Channel> channels_;
    std::atomic<int> trackCount_{0};

    Schedule schedule_;
    std::atomic<size_t> noteCursor_{0};
    std::vector<AutomationCurve> automation_;

    // 总线缓冲
    std::vector<float> busL_, busR_, revL_, revR_, dlyL_, dlyR_;
    std::vector<float> outL_, outR_;   // 预分配的主输出，回调不分配内存
    EffectState reverbBus_, delayBus_;

    float masterVolume_ = 0.8f;
    bool limiterOn_ = true;
    bool softClipOn_ = true;
    float masterGainDb_ = 0.f;
    Smoother masterSm_;
    float limiterGain_ = 1.f;

    std::atomic<float> masterPeakL_{0.f}, masterPeakR_{0.f};
    std::vector<float> trackPeakVec_;

    int lastBeat_ = -1;
    Noise masterNoise_;   // 抖动用的噪声（导出时可选）

    float* sampleData_ = nullptr;
    int sampleFrames_ = 0;
    int sampleRateRoot_ = 48000;
    int sampleRoot_ = 60;
};

}  // namespace flow
