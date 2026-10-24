// FlowStudio —— 引擎主实现：传输、调度、混音总线、母带、离线渲染。
#include "flow/engine.h"

#include <algorithm>
#include <cstring>

namespace flow {

const float* g_sampleBank = nullptr;
int g_sampleFrames = 0;
int g_sampleRate = 48000;
int g_sampleRoot = 60;

// ================================================================ 构造

Engine::Engine() {
    voices_.resize(kMaxVoices);
    channels_.resize(kMaxTracks);
    for (auto& ch : channels_) {
        for (auto& fx : ch.fx) {
            fx.type = EffectType::None;
            fx.enabled = true;
        }
    }
    trackPeakVec_.assign(kMaxTracks, 0.f);
}

Engine::~Engine() {
    stopAudio();
    if (sampleData_) {
        delete[] sampleData_;
        sampleData_ = nullptr;
        g_sampleBank = nullptr;
    }
}

void Engine::prepare(float sampleRate, int maxBlock) {
    sampleRate_ = sampleRate;
    maxBlock_ = std::max(64, maxBlock);

    // 所有总线缓冲一次开足到 kMaxBlockFrames，之后永不重建。
    //
    // 这里的尺寸必须和 renderInterleaved 的分段上限一致。
    // 之前按 maxBlock_ 分配（比如 256），而渲染按 4096 循环写，
    // 越界写会直接段错误 —— 或者更糟：踩坏别的字段但不崩，
    // 表现成一堆莫名其妙的问题。
    const size_t n = (size_t)kMaxBlockFrames;
    if (busL_.size() < n) {
        busL_.assign(n, 0.f);
        busR_.assign(n, 0.f);
        revL_.assign(n, 0.f);
        revR_.assign(n, 0.f);
        dlyL_.assign(n, 0.f);
        dlyR_.assign(n, 0.f);
    }

    // 主输出缓冲：与总线同尺寸，也是一次性开足。
    // 这一段之前漏掉了 —— outL_ 一直是空的，
    // renderInterleaved 里 outL_.data() 拿到 nullptr，写进去就是段错误。
    if (outL_.size() < n) {
        outL_.assign(n, 0.f);
        outR_.assign(n, 0.f);
    }

    reverbBus_.type = EffectType::Reverb;
    reverbBus_.enabled = true;
    reverbBus_.mix = 1.f;
    reverbBus_.p[0] = 0.62f;
    reverbBus_.p[1] = 0.55f;
    reverbBus_.p[2] = 0.18f;
    reverbBus_.p[3] = 0.85f;
    reverbBus_.prepare(sampleRate);

    delayBus_.type = EffectType::Delay;
    delayBus_.enabled = true;
    delayBus_.mix = 1.f;
    delayBus_.p[0] = 0.52f;
    delayBus_.p[1] = 0.40f;
    delayBus_.p[2] = 0.55f;
    delayBus_.p[3] = 1.f;
    delayBus_.prepare(sampleRate);

    for (auto& ch : channels_) {
        for (auto& fx : ch.fx) if (fx.type != EffectType::None) fx.prepare(sampleRate);
        configureSmoothers(ch);
    }
    masterSm_.setTime(0.015f, sampleRate);
    masterSm_.reset(0.8f);

    resetVoices();
}

void Engine::configureSmoothers(Channel& ch) {
    const float panAngle = (clampf(ch.pan, -1.f, 1.f) + 1.f) * 0.25f * kPi;
    ch.gainSm.reset(ch.volume);
    ch.panLSm.reset(std::cos(panAngle));
    ch.panRSm.reset(std::sin(panAngle));
    ch.revSm.reset(ch.sendReverb);
    ch.dlySm.reset(ch.sendDelay);
    ch.gainSm.setTime(0.012f, sampleRate_);
    ch.panLSm.setTime(0.012f, sampleRate_);
    ch.panRSm.setTime(0.012f, sampleRate_);
    ch.revSm.setTime(0.030f, sampleRate_);
    ch.dlySm.setTime(0.030f, sampleRate_);
}

// ================================================================ 音频设备

void Engine::startAudio() {
    if (audioRunning_.load()) return;
    backend_ = AudioBackend::create();
    if (!backend_) return;
    if (backend_->start(this, sampleRate_, maxBlock_)) audioRunning_.store(true);
}

void Engine::stopAudio() {
    if (!audioRunning_.exchange(false)) return;
    if (backend_) { backend_->stop(); backend_.reset(); }
}

// ================================================================ 传输

void Engine::play() {
    const double len = schedule_.totalTicks > 0 ? (double)schedule_.totalTicks : (double)kBar * 4;
    if (position_.load() >= len) {
        position_.store(0.0);
        noteCursor_.store(0, std::memory_order_release);
        lastBeat_ = -1;
    }
    playing_.store(true);
}

void Engine::pause() { playing_.store(false); }

void Engine::stop() {
    playing_.store(false);
    position_.store(0.0);
    noteCursor_.store(0, std::memory_order_release);
    lastBeat_ = -1;
    resetVoices();
}

void Engine::setPositionTicks(double t) {
    t = std::max(0.0, t);
    position_.store(t);
    size_t i = 0;
    while (i < schedule_.notes.size() && (double)schedule_.notes[i].startTick < t) i++;
    noteCursor_.store(i, std::memory_order_release);
    resetVoices();
    lastBeat_ = -1;
}

void Engine::setSchedule(const Schedule& s) {
    schedule_ = s;
    schedule_.sort();
    noteCursor_.store(0, std::memory_order_release);
}

void Engine::setLoop(bool on, double startTick, double endTick) {
    loop_.store(on);
    loopStart_.store(std::max(0.0, startTick));
    loopEnd_.store(std::max(startTick + kPPQ, endTick));
}

// ================================================================ 通道

int Engine::addTrack(const Channel& ch) {
    const int idx = trackCount_.load(std::memory_order_relaxed);
    if (idx >= kMaxTracks) return -1;
    channels_[(size_t)idx] = ch;
    for (auto& fx : channels_[(size_t)idx].fx) {
        if (fx.type != EffectType::None) fx.prepare(sampleRate_);
    }
    configureSmoothers(channels_[(size_t)idx]);
    trackCount_.store(idx + 1, std::memory_order_release);
    return idx;
}

void Engine::clearTracks() {
    resetVoices();
    trackCount_.store(0, std::memory_order_release);
    for (auto& ch : channels_) {
        for (auto& fx : ch.fx) { fx.type = EffectType::None; fx.reset(); }
    }
}

void Engine::setTrackConfig(int index, const Channel& ch) {
    if (index < 0 || index >= kMaxTracks) return;
    Channel& dst = channels_[(size_t)index];

    // 关键：setTrackConfig 必须自己维护 trackCount_。
    // 渲染循环是 for (t = 0; t < trackCount; ++t)，pushAll() 会先 clearTracks()
    // 把它归零，然后逐条 setChannel。如果这里不抬高计数，
    // 循环一次都不执行 —— 引擎渲染的是彻底的沉默，
    // 表现为"点了播放但一点声音都没有"，而且没有任何报错。
    {
        const int needed = index + 1;
        if (trackCount_.load(std::memory_order_relaxed) < needed) {
            trackCount_.store(needed, std::memory_order_release);
        }
    }

    dst.instrument = ch.instrument;
    std::memcpy(dst.params, ch.params, sizeof(dst.params));
    dst.transpose = ch.transpose;
    dst.velocitySense = ch.velocitySense;
    dst.volume = ch.volume;
    dst.pan = ch.pan;
    dst.sendReverb = ch.sendReverb;
    dst.sendDelay = ch.sendDelay;
    dst.mute = ch.mute;
    dst.solo = ch.solo;

    for (int i = 0; i < kMaxFx; i++) {
        const bool typeChanged = dst.fx[i].type != ch.fx[i].type;
        dst.fx[i].type = ch.fx[i].type;
        dst.fx[i].enabled = ch.fx[i].enabled;
        dst.fx[i].mix = ch.fx[i].mix;
        std::memcpy(dst.fx[i].p, ch.fx[i].p, sizeof(dst.fx[i].p));
        if (typeChanged) dst.fx[i].prepare(sampleRate_);
    }
}

Channel Engine::getTrackConfig(int index) const {
    if (index < 0 || index >= kMaxTracks) return Channel{};
    return channels_[(size_t)index];
}

void Engine::setTrackNote(int index, int pitch, float velocity, int durationTicks) {
    if (index < 0 || index >= kMaxTracks) return;
    if (durationTicks <= 0) durationTicks = kStep;

    // 试听的音符不能靠 endTick 释放：停止状态下 position_ 不前进，
    // endTick 永远到不了，声部会一直挂着。
    // 这里用一个"按样本倒计时"的独立机制，它不依赖传输状态。
    const int32_t endTick = (int32_t)position_.load() + durationTicks;
    triggerNote(index, pitch, velocity, endTick);

    // 找到刚触发的那个声部，给它挂上真实的时间限制
    for (auto& v : voices_) {
        if (v.active && !v.released && v.track == index &&
            v.pitch == pitch + channels_[(size_t)index].transpose) {
            const float sr = sampleRate_;
            v.manualReleaseSamples = (int32_t)((float)std::max(1, durationTicks) /
                (float)std::max(1.0, (double)bpm_.load() * kPPQ / 60.0) * sr);
            break;
        }
    }
}

void Engine::releaseTrackNote(int index) {
    for (auto& v : voices_) {
        if (v.active && v.track == index && !v.released) v.release();
    }
}

void Engine::allNotesOff() {
    for (auto& v : voices_) if (v.active) v.release();
}

void Engine::setAutomation(const std::vector<AutomationCurve>& curves) {
    for (const auto& c : curves) {
        for (size_t i = 1; i < c.tick.size(); i++) {
            if (c.tick[i] < c.tick[i - 1]) {
                // 曲线必须按 tick 单调，否则插值会乱
                return;
            }
        }
    }
    automation_ = curves;
}

void Engine::setMaster(float volume01, bool limiter, bool softClip, float gainDb) {
    masterVolume_ = clampf(volume01, 0.f, 1.5f);
    limiterOn_ = limiter;
    softClipOn_ = softClip;
    masterGainDb_ = gainDb;
}

Engine::AudioStats Engine::stats() const {
    AudioStats st;
    st.framesRendered = framesRendered_.load(std::memory_order_relaxed);
    st.peakEver = peakEver_.load(std::memory_order_relaxed);
    st.callbacks = callbacks_.load(std::memory_order_relaxed);
    return st;
}

float Engine::trackPeak(int index) const {
    if (index < 0 || index >= (int)trackPeakVec_.size()) return 0.f;
    return trackPeakVec_[(size_t)index];
}

void Engine::resetPeaks() {
    for (auto& p : trackPeakVec_) p = 0.f;
    masterPeakL_.store(0.f);
    masterPeakR_.store(0.f);
}

// ================================================================ 声部

void Engine::resetVoices() {
    for (auto& v : voices_) v.active = false;
}

void Engine::triggerNote(int track, int pitch, float velocity, int32_t endTick) {
    if (track < 0 || track >= kMaxTracks) return;

    Voice* slot = nullptr;
    for (auto& v : voices_) {
        if (!v.active) { slot = &v; break; }
    }
    if (!slot) {
        // 抢释放阶段最安静的声部，听感损失最小
        for (auto& v : voices_) {
            if (v.released && (!slot || v.amp.level < slot->amp.level)) slot = &v;
        }
    }
    if (!slot) slot = &voices_[0];

    const Channel& ch = channels_[(size_t)track];
    const float vel = lerpf(1.f, clampf(velocity, 0.f, 1.f), clampf(ch.velocitySense, 0.f, 1.f));
    slot->setup(ch.instrument, pitch + ch.transpose, vel, endTick, (int16_t)track,
                ch.params, sampleRate_);
}

void Engine::releaseNotesAt(int32_t tick) {
    for (auto& v : voices_) {
        if (v.active && !v.released && v.endTick <= tick) v.release();
    }
}

double Engine::ticksPerSample() const {
    return (double)bpm_.load(std::memory_order_relaxed) * (double)kPPQ / 60.0 / (double)sampleRate_;
}

// ================================================================ 自动化

void Engine::applyAutomation(int track, double tick, Channel& ch) {
    float autos[12];
    for (int i = 0; i < 12; i++) autos[i] = -1.f;   // -1 = 没被自动化接管

    for (const auto& c : automation_) {
        if (c.track == track && c.target >= 0 && c.target < 12 && !c.tick.empty()) {
            autos[c.target] = c.at(tick, -1.f);
        }
    }

    if (autos[AutoVolume] >= 0.f) ch.volume = autos[AutoVolume] * 1.2f;
    if (autos[AutoPan] >= 0.f) ch.pan = autos[AutoPan] * 2.f - 1.f;
    if (autos[AutoSendReverb] >= 0.f) ch.sendReverb = autos[AutoSendReverb];
    if (autos[AutoSendDelay] >= 0.f) ch.sendDelay = autos[AutoSendDelay];
    if (autos[AutoParam0] >= 0.f) ch.params[0] = autos[AutoParam0];
    if (autos[AutoParam1] >= 0.f) ch.params[1] = autos[AutoParam1];
    if (autos[AutoParam2] >= 0.f) ch.params[2] = autos[AutoParam2];
    if (autos[AutoParam3] >= 0.f) ch.params[3] = autos[AutoParam3];
    if (autos[AutoFx0P0] >= 0.f && ch.fx[0].type != EffectType::None) ch.fx[0].p[0] = autos[AutoFx0P0];
    if (autos[AutoFx0P1] >= 0.f && ch.fx[0].type != EffectType::None) ch.fx[0].p[1] = autos[AutoFx0P1];
    if (autos[AutoFx1P0] >= 0.f && ch.fx[1].type != EffectType::None) ch.fx[1].p[0] = autos[AutoFx1P0];
    if (autos[AutoFx1P1] >= 0.f && ch.fx[1].type != EffectType::None) ch.fx[1].p[1] = autos[AutoFx1P1];
}

// ================================================================ 核心渲染

void Engine::renderBlockClean(float* outL, float* outR, int frames) {
    // 不再用 maxBlock_ 截断：调用方（renderInterleaved）已经按 kMaxBlockFrames
    // 分好段了，这里再截一次会让实际输出的帧数比请求的少，
    // 后面那段就是旧数据或者静音。块长以调用方为准。
    if (frames <= 0) return;
    if (frames > kMaxBlockFrames) frames = kMaxBlockFrames;
    // 缓冲尺寸必须够。宁可截断也不能越界写 —— 越界不会崩在当场，
    // 而是悄悄改坏相邻字段，后面报出来的问题跟真正原因毫无关系。
    const size_t need = (size_t)frames;
    if (busL_.size() < need || outL_.size() < need || revL_.size() < need) {
        frames = (int)std::min({busL_.size(), outL_.size(), revL_.size()});
        if (frames <= 0) return;
    }

    std::memset(revL_.data(), 0, sizeof(float) * (size_t)frames);
    std::memset(revR_.data(), 0, sizeof(float) * (size_t)frames);
    std::memset(dlyL_.data(), 0, sizeof(float) * (size_t)frames);
    std::memset(dlyR_.data(), 0, sizeof(float) * (size_t)frames);
    std::memset(outL, 0, sizeof(float) * (size_t)frames);
    std::memset(outR, 0, sizeof(float) * (size_t)frames);

    const double tps = ticksPerSample();
    const bool isPlaying = playing_.load(std::memory_order_relaxed);
    const float bpmNow = bpm_.load(std::memory_order_relaxed);
    const int trackCount = trackCount_.load(std::memory_order_acquire);
    double pos = position_.load(std::memory_order_relaxed);

    // ---- 1. 推进时间，触发音符 ----
    if (isPlaying) {
        double endPos = pos + tps * (double)frames;

        if (loop_.load(std::memory_order_relaxed)) {
            const double ls = loopStart_.load();
            const double le = loopEnd_.load();
            if (endPos >= le) {
                pos = ls;
                setPositionTicks(ls);
                endPos = pos + tps * (double)frames;
            }
        }

        size_t cursor = noteCursor_.load(std::memory_order_relaxed);
        while (cursor < schedule_.notes.size()) {
            const FlatNote& fn = schedule_.notes[cursor];
            if ((double)fn.startTick >= endPos) break;
            if ((double)fn.startTick >= pos) {
                triggerNote(fn.track, fn.pitch, (float)fn.velocity / 127.f, fn.endTick);
            }
            cursor++;
        }
        noteCursor_.store(cursor, std::memory_order_relaxed);

        releaseNotesAt((int32_t)endPos);

        // 节拍器：每拍一响，重拍音高更高
        if (metronome_.load(std::memory_order_relaxed)) {
            const int beat = (int)(pos / (double)kPPQ);
            if (beat != lastBeat_) {
                lastBeat_ = beat;
                const bool downbeat = (beat % 4 == 0);
                Voice* slot = nullptr;
                for (auto& v : voices_) { if (!v.active) { slot = &v; break; } }
                if (slot) {
                    float mp[12];
                    for (int i = 0; i < 12; i++) mp[i] = 0.5f;
                    mp[0] = downbeat ? 0.92f : 0.78f;
                    mp[1] = 0.04f;
                    mp[2] = 0.30f;
                    const float mv = metronomeVolume_.load(std::memory_order_relaxed);
                    slot->setup(Instrument::Drum, 42,
                                (downbeat ? 0.55f : 0.34f) * mv,
                                (int32_t)(pos + kStep), (int16_t)-1, mp, sampleRate_);
                }
            }
        }

        position_.store(pos + tps * (double)frames, std::memory_order_relaxed);
    }

    // ---- 2. 逐通道合成 ----
    bool anySolo = false;
    for (int i = 0; i < trackCount; i++) {
        if (channels_[(size_t)i].solo) { anySolo = true; break; }
    }

    for (int t = 0; t < trackCount; t++) {
        Channel& ch = channels_[(size_t)t];

        if (!isPlaying) {
            // 停止状态下仍要处理残响尾巴，否则松开按键会咔一声断掉
        }

        applyAutomation(t, pos, ch);

        bool hasVoice = false;
        for (const auto& v : voices_) {
            if (v.active && v.track == t) { hasVoice = true; break; }
        }
        if (!hasVoice) {
            if (t < (int)trackPeakVec_.size()) trackPeakVec_[(size_t)t] *= 0.80f;
            continue;
        }

        std::memset(busL_.data(), 0, sizeof(float) * (size_t)frames);
        std::memset(busR_.data(), 0, sizeof(float) * (size_t)frames);

        for (auto& v : voices_) {
            if (!v.active || v.track != t) continue;
            if (!v.render(busL_.data(), busR_.data(), frames, ch.params, sampleRate_)) {
                v.active = false;
            }
        }

        const bool audible = !ch.mute && (!anySolo || ch.solo);
        const float targetPan = clampf(ch.pan, -1.f, 1.f);
        const float targetGain = audible ? clampf(ch.volume, 0.f, 1.5f) : 0.f;
        const float panAngle = (targetPan + 1.f) * 0.25f * kPi;
        const float tgtL = std::cos(panAngle);
        const float tgtR = std::sin(panAngle);

        float peak = 0.f;
        for (int i = 0; i < frames; i++) {
            const float g = ch.gainSm.process(targetGain);
            const float lg = ch.panLSm.process(tgtL);
            const float rg = ch.panRSm.process(tgtR);
            // 注意这里的 2.0 而不是 1.4142：
            // 等功率声像(cos/sin)在居中时两条声道各得 0.707，
            // 乘 1.414 只补偿到 1.0，多轨相加必然过载。
            // 真正的解法不是把每条推爆，而是给总线留余量 ——
            // 见下方 mixHeadroom。这里只用 1.0 保持单轨的正确电平。
            const float a = busL_[(size_t)i] * g * lg;
            const float b = busR_[(size_t)i] * g * rg;
            busL_[(size_t)i] = a;
            busR_[(size_t)i] = b;
            const float pa = std::max(std::fabs(a), std::fabs(b));
            if (pa > peak) peak = pa;
        }

        for (auto& fx : ch.fx) {
            if (fx.type != EffectType::None && fx.enabled) {
                fx.process(busL_.data(), busR_.data(), frames, sampleRate_, bpmNow);
            }
        }

        // 推总 + 送两条发送总线
        for (int i = 0; i < frames; i++) {
            const float a = busL_[(size_t)i];
            const float b = busR_[(size_t)i];
            outL[i] += a;
            outR[i] += b;
            const float sr = ch.revSm.process(clampf(ch.sendReverb, 0.f, 1.f));
            const float sd = ch.dlySm.process(clampf(ch.sendDelay, 0.f, 1.f));
            revL_[(size_t)i] += a * sr;
            revR_[(size_t)i] += b * sr;
            dlyL_[(size_t)i] += a * sd;
            dlyR_[(size_t)i] += b * sd;
        }

        if (t < (int)trackPeakVec_.size()) {
            float& slot = trackPeakVec_[(size_t)t];
            slot = std::max(peak, slot * 0.82f);
        }
    }

    // ---- 3. 发送效果 ----
    reverbBus_.process(revL_.data(), revR_.data(), frames, sampleRate_, bpmNow);
    delayBus_.process(dlyL_.data(), dlyR_.data(), frames, sampleRate_, bpmNow);

    // ---- 4. 总线余量 ----
    // 多轨叠加的电平不是线性增长的：N 条不相关信号相加，
    // 幅度按 sqrt(N) 增长，功率按 N 增长。
    // 所以按 sqrt(活跃轨道数) 做补偿，既不过压也不塌陷。
    //
    // 关键：这只是"防止总线上限爆掉"，不能替代混音判断。
    // 保留 6dB 余量给瞬态，让限幅器只在真正需要时轻触。
    float busTrim = 1.f;
    {
        int activeTracks = 0;
        for (int t = 0; t < trackCount; t++) {
            const Channel& c = channels_[(size_t)t];
            if (!c.mute && (!anySolo || c.solo)) activeTracks++;
        }
        if (activeTracks > 1) {
            // sqrt 补偿，再留 6dB：sqrt(7)=2.65，取 1/sqrt(7)≈0.378，再乘 0.5
            busTrim = 1.f / std::sqrt((float)activeTracks);
            // 多留一点余量：鼓的瞬态是峰值的来源，
            // 平均电平看着不高，一记底鼓就能顶到头。
            // softClip 已经是线性到 0.66，不再偷偷放大信号，
            // 所以这里不需要额外压太多。留 3dB 余量即可。
            busTrim *= 0.85f;
            busTrim = std::max(busTrim, 0.34f);
        }
    }

    // ---- 5. 母带 ----
    const float mg = dbToGain(masterGainDb_);
    float peakL = 0.f, peakR = 0.f;
    for (int i = 0; i < frames; i++) {
        float a = (outL[i] + revL_[(size_t)i] * 0.55f + dlyL_[(size_t)i] * 0.45f) * busTrim;
        float b = (outR[i] + revR_[(size_t)i] * 0.55f + dlyR_[(size_t)i] * 0.45f) * busTrim;

        const float vol = masterSm_.process(masterVolume_);
        a *= vol * mg;
        b *= vol * mg;

        if (limiterOn_) {
            // 前馈限幅。
            //
            // 之前的实现有两个问题：
            //   1. 阈值 0.92 太高，几乎每拍都触发 → 每秒峰值全是 0.99，动态被压平
            //   2. 恢复系数 0.0008 太慢，一旦压下去要好几秒才回来
            // 结果是一首有起伏的歌听起来像一堵墙。
            //
            // 现在：阈值抬到 0.98（只在真的快爆时才动），
            // 起音快、释放按 150ms 时间常数走，压完立刻松手。
            const float peakNow = std::max(std::fabs(a), std::fabs(b));
            // 0.93 是"听起来不刺耳"的分界：
            // 更高会让瞬态削平，更低则一直在压。
            const float thresh = 0.93f;
            const float target = peakNow > thresh ? thresh / peakNow : 1.f;
            if (target < limiterGain_) {
                // 起音：立即跟上，宁可有极短失真也不要爆音
                limiterGain_ = target;
            } else {
                // 释放：150ms 时间常数，跟随实际采样率
                const float rel = 1.f - std::exp(-1.f / (0.08f * sampleRate_));
                limiterGain_ += (1.f - limiterGain_) * rel;
                if (limiterGain_ > 1.f) limiterGain_ = 1.f;
            }
            a *= limiterGain_;
            b *= limiterGain_;
        }

        if (softClipOn_) {
            a = softClip(a);
            b = softClip(b);
        }

        a = clampf(a, -1.f, 1.f);
        b = clampf(b, -1.f, 1.f);
        outL[i] = a;
        outR[i] = b;

        const float pa = std::fabs(a);
        const float pb = std::fabs(b);
        if (pa > peakL) peakL = pa;
        if (pb > peakR) peakR = pb;
    }

    // 诊断计数
    framesRendered_.fetch_add((uint64_t)frames, std::memory_order_relaxed);
    callbacks_.fetch_add(1, std::memory_order_relaxed);
    {
        const float now = std::max(peakL, peakR);
        float prev = peakEver_.load(std::memory_order_relaxed);
        if (now > prev) peakEver_.store(now, std::memory_order_relaxed);
    }

    masterPeakL_.store(std::max(peakL, masterPeakL_.load(std::memory_order_relaxed) * 0.85f),
                       std::memory_order_relaxed);
    masterPeakR_.store(std::max(peakR, masterPeakR_.load(std::memory_order_relaxed) * 0.85f),
                       std::memory_order_relaxed);
}

void Engine::renderInterleaved(float* stereoOut, int frames) {
    if (frames <= 0 || !stereoOut) return;

    // 分段渲染，绝不整块丢弃。
    // 块长比缓冲大时（设备切换路由会改块长），以前是直接 return ——
    // 一个静音块都不输出，而且没有任何日志。现在按上限切段处理。
    int done = 0;
    while (done < frames) {
        const int n = std::min<int>(kMaxBlockFrames, frames - done);
        renderBlockClean(outL_.data(), outR_.data(), n);
        float* dst = stereoOut + (size_t)done * 2;
        for (int i = 0; i < n; i++) {
            dst[i * 2] = outL_[(size_t)i];
            dst[i * 2 + 1] = outR_[(size_t)i];
        }
        done += n;
    }
}

void Engine::processBlockForTest(float* stereoOut, int frames) {
    // 测试路径允许分配；实时路径必须走 renderInterleaved
    std::vector<float> l((size_t)frames), r((size_t)frames);
    renderBlockClean(l.data(), r.data(), frames);
    for (int i = 0; i < frames; i++) {
        stereoOut[i * 2] = l[(size_t)i];
        stereoOut[i * 2 + 1] = r[(size_t)i];
    }
}

// ================================================================ 离线渲染

Engine::RenderResult Engine::renderOffline(double startTick, double endTick, int sr, bool useLoop) {
    RenderResult result;

    const float oldSr = sampleRate_;
    const int oldBlock = maxBlock_;
    const double oldPos = position_.load();
    const bool oldPlaying = playing_.load();
    const bool oldLoop = loop_.load();
    const size_t oldCursor = noteCursor_.load();

    // 离线渲染用独立的采样率，需要把滤波器/延迟全部按新采样率重建
    prepare((float)sr, 512);
    setPositionTicks(std::max(0.0, startTick));
    playing_.store(true);
    loop_.store(false);

    const double tps = ticksPerSample();
    const double totalTicks = std::max((double)kBar, endTick - startTick);
    const int64_t totalFrames = (int64_t)std::ceil(totalTicks / tps);
    // 多渲 2 秒让混响/延迟尾巴自然衰减，这是导出质量的差别所在
    const int64_t tailFrames = (int64_t)(sr * 2.0);
    result.interleaved.resize((size_t)((totalFrames + tailFrames) * 2), 0.f);
    result.sampleRate = sr;

    std::vector<float> l(512), r(512);
    int64_t written = 0;
    const int64_t limit = totalFrames + tailFrames;

    while (written < limit) {
        const int chunk = (int)std::min<int64_t>(512, limit - written);
        renderBlockClean(l.data(), r.data(), chunk);

        // 尾巴部分：拖尾结束后静音，逐步淡出避免结尾硬切
        if (written >= totalFrames) {
            const int64_t tailPos = written - totalFrames;
            const float fade = tailPos > tailFrames - (int64_t)(sr * 0.2)
                ? clampf((float)(limit - written) / (float)(sr * 0.2), 0.f, 1.f)
                : 1.f;
            for (int i = 0; i < chunk; i++) {
                l[(size_t)i] *= fade;
                r[(size_t)i] *= fade;
            }
        }

        for (int i = 0; i < chunk; i++) {
            result.interleaved[(size_t)((written + i) * 2)] = l[(size_t)i];
            result.interleaved[(size_t)((written + i) * 2 + 1)] = r[(size_t)i];
        }
        written += chunk;

        // 尾巴已经足够安静就提前结束
        if (written >= totalFrames && written % (int64_t)sr == 0) {
            float mx = 0.f;
            for (int i = 0; i < chunk; i++) {
                mx = std::max(mx, std::max(std::fabs(l[(size_t)i]), std::fabs(r[(size_t)i])));
            }
            if (mx < 1e-4f && written > totalFrames + (int64_t)(sr * 0.5)) break;
        }
    }

    result.frames = written;
    result.ok = written > 0;

    // 恢复现场
    playing_.store(false);
    loop_.store(oldLoop);
    prepare(oldSr, oldBlock);
    setPositionTicks(oldPos);
    playing_.store(oldPlaying);
    loop_.store(oldLoop);
    noteCursor_.store(oldCursor);
    allNotesOff();

    return result;
}

// ================================================================ 采样

void Engine::loadSample(const float* data, int frames, int sr, int rootNote) {
    if (!data || frames <= 0) return;
    if (sampleData_) { delete[] sampleData_; sampleData_ = nullptr; }
    sampleData_ = new float[(size_t)frames];
    std::memcpy(sampleData_, data, sizeof(float) * (size_t)frames);
    sampleFrames_ = frames;
    sampleRateRoot_ = sr;
    sampleRoot_ = rootNote;
    g_sampleBank = sampleData_;
    g_sampleFrames = frames;
    g_sampleRate = sr;
    g_sampleRoot = rootNote;
}

void Engine::clearSample() {
    if (sampleData_) { delete[] sampleData_; sampleData_ = nullptr; }
    sampleFrames_ = 0;
    g_sampleBank = nullptr;
    g_sampleFrames = 0;
}

}  // namespace flow
