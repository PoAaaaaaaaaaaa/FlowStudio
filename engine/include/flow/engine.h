// FlowStudio —— 引擎主类。音频线程围绕它转，所有状态都在这里。
#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
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
    void setMetronomeVolume(float v) {
        metronomeVolume_.store(clampf(v, 0.f, 1.f), std::memory_order_relaxed);
    }

    // ---- 编排 ----
    void setSchedule(const Schedule& s);
    int32_t scheduleLengthTicks() const { return schedule_.totalTicks; }

    /** 把已装载的 schedule 的游标复位。prepare 之后要重放时用。 */
    void applyScheduleAgain() { noteCursor_.store(0, std::memory_order_release); }

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

    // ---- 诊断探针 ----
    // 用户说"没声音"时，光看代码无法区分是
    //   a) 音频流没起来   b) 起来了但渲染的是静音   c) 渲染有声但设备不出声
    // 这三个数字能把它们分开。
    struct AudioStats {
        uint64_t framesRendered = 0;   // 累计渲染帧数
        float peakEver = 0.f;          // 历史最大绝对峰值
        int callbacks = 0;             // 回调次数
    };
    AudioStats stats() const;
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

    // ---- 音频片段（audio clip）----
    // 这是 FL 的核心语义：一段音频就是时间线上的一个块，摆在某条轨道上，
    // 走那条轨道的音量、声像与插入效果 —— 与合成器的音符共用同一条混音路径。
    // 以前这里是"一条全局伴奏轨"：只能铺一整首、且直接绕过轨道混音进母带。
    // 现在任意轨道都能放任意多段音频，片段可裁可比、可重叠。
    struct AudioBuffer {
        std::vector<float> samples;   // 交织立体声
        int frames = 0;
        int sampleRate = 48000;
    };
    struct AudioClip {
        std::shared_ptr<const AudioBuffer> buffer;
        int track = 0;
        double startTick = 0.0;     // 在时间线上的起始 tick
        int64_t offsetFrames = 0;   // 从素材第几帧起播（左裁剪）
        int64_t lengthFrames = 0;   // 播多少帧；<=0 表示播到素材末尾
        float gain = 1.0f;
    };

    void setAudioClips(const std::vector<AudioClip>& clips);
    void clearAudioClips();
    bool hasAudioClips() const { return std::atomic_load(&audioClips_) != nullptr; }
    int audioClipCount() const;

    // 素材缓冲池：按 id 登记一次，之后改布局只传 id 表 ——
    // 拖动一个片段不该把几十 MB 的波形重新跨 JNI 拷一遍。
    void setAudioBuffer(int32_t id, const float* interleaved, int frames, int sampleRate);
    void clearAudioBuffers();
    bool hasAudioBuffer(int32_t id) const;

    /** 按素材 id 装配片段表；素材已进池子，这里只搬布局，不搬波形。 */
    void setAudioClipsById(const std::vector<int32_t>& bufferIds,
                           const std::vector<int32_t>& tracks,
                           const std::vector<double>& startTicks,
                           const std::vector<int64_t>& offsetFrames,
                           const std::vector<int64_t>& lengthFrames,
                           const std::vector<float>& gains);

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
    void renderAudioClip(const AudioClip& clip, float* busL, float* busR,
                         int frames, double pos, double tps, float bpmNow);

    std::unique_ptr<AudioBackend> backend_;
    std::atomic<bool> audioRunning_{false};

    std::atomic<float> bpm_{120.f};
    std::atomic<double> position_{0.0};
    std::atomic<bool> playing_{false};
    std::atomic<bool> loop_{false};
    std::atomic<double> loopStart_{0.0};
    std::atomic<double> loopEnd_{0.0};
    std::atomic<bool> metronome_{false};
    std::atomic<float> metronomeVolume_{0.6f};

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
    // 主输出缓冲：按 kMaxBlockFrames 一次性开足，之后永不重新分配。
    // 之前 prepare() 里用 assign(n) 会重建容器，如果此时音频线程正在渲染，
    // renderInterleaved 的 `outL_.size() < frames` 守卫会整块跳过 —— 每个回调都静默返回，
    // 表现就是"点了播放没有声音，也不报错"。这是本文件里最危险的一处。
    static constexpr int kMaxBlockFrames = 4096;
    std::vector<float> outL_, outR_;
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

    std::atomic<uint64_t> framesRendered_{0};
    std::atomic<float> peakEver_{0.f};
    std::atomic<int> callbacks_{0};
    Noise masterNoise_;   // 抖动用的噪声（导出时可选）

    float* sampleData_ = nullptr;
    int sampleFrames_ = 0;
    int sampleRateRoot_ = 48000;
    int sampleRoot_ = 60;

    // 音频片段：不可变快照。音频回调原子取得 shared_ptr，保证整块生命周期，
    // 控制线程整体替换 —— 不加锁，也不会出现半新半旧的混音块。
    std::shared_ptr<const std::vector<AudioClip>> audioClips_;

    // 素材池。只在控制线程（JNI 调用）读写，音频线程从不碰这个 map，
    // 它拿到的永远是 clip 里那份已经固定下来的 shared_ptr。
    mutable std::mutex buffersMutex_;
    std::map<int32_t, std::shared_ptr<const AudioBuffer>> audioBuffers_;

    // 每块按轨道分桶后的指针表。容量在 prepare 时预留，回调里只 clear+push_back，
    // 不触发堆分配 —— 音频线程上 malloc 一次就是一记爆音。
    std::vector<const AudioClip*> audioBucket_[kMaxTracks];
};

}  // namespace flow
