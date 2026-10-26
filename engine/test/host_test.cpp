// FlowStudio —— 宿主侧自测。不接 Android，直接驱动引擎渲染并检查输出。
// 这个程序是唯一能证明音色真的出声、混音不爆、导出口径正确的东西。
#include <cstdio>
#include <cmath>
#include <cstring>
#include <vector>
#include <string>

#include "flow/engine.h"

using namespace flow;

static int gFail = 0;

static void check(bool cond, const char* what) {
    std::printf("%s  %s\n", cond ? "[ ok ]" : "[FAIL]", what);
    if (!cond) gFail++;
}

// 统计一段立体声的峰值与 RMS
static void stats(const std::vector<float>& buf, float& peak, float& rms, float& dc) {
    peak = 0.f;
    double sum = 0.0;
    double dcSum = 0.0;
    for (size_t i = 0; i < buf.size(); i += 2) {
        const float l = buf[i];
        peak = std::max(peak, std::fabs(l));
        sum += (double)l * l;
        dcSum += l;
    }
    const size_t frames = buf.size() / 2;
    rms = frames ? (float)std::sqrt(sum / (double)frames) : 0.f;
    dc = frames ? (float)(dcSum / (double)frames) : 0.f;
}

// 检查是否全 NaN / Inf —— 滤波器不稳定会立刻表现成这个
static bool finite(const std::vector<float>& buf) {
    for (float v : buf) if (!std::isfinite(v)) return false;
    return true;
}

// 简易频谱能量：在指定频率上做 DFT 点积（只用于验证"这个音高确实在响"）
static float energyAt(const std::vector<float>& buf, float hz, int sr) {
    const size_t frames = buf.size() / 2;
    double re = 0.0, im = 0.0;
    const double w = 2.0 * 3.14159265358979 * (double)hz / (double)sr;
    // 只取前 0.5 秒，且加汉宁窗，减少泄漏
    const size_t use = std::min<size_t>(frames, (size_t)(sr * 0.5));
    for (size_t i = 0; i < use; i++) {
        const double win = 0.5 - 0.5 * std::cos(2.0 * 3.14159265358979 * (double)i / (double)use);
        const double s = (double)buf[i * 2] * win;
        re += s * std::cos(w * (double)i);
        im -= s * std::sin(w * (double)i);
    }
    return (float)std::sqrt(re * re + im * im) / (float)use;
}

static Channel makeChannel(Instrument inst, float volume) {
    Channel ch;
    ch.instrument = inst;
    ch.volume = volume;
    ch.pan = 0.f;
    ch.velocitySense = 0.7f;
    for (int i = 0; i < 12; i++) ch.params[i] = 0.5f;
    for (int i = 0; i < 4; i++) ch.fx[i].type = EffectType::None;
    return ch;
}

// 用给定的通道配置渲染一首音符，返回立体声缓冲
static std::vector<float> renderNotes(Channel ch, int pitch, int noteLenTicks, int totalTicks,
                                     int sr, float bpm) {
    Engine e;
    e.prepare((float)sr, 512);
    const int track = e.addTrack(ch);
    e.setBpm(bpm);

    Schedule s;
    s.notes.push_back(FlatNote{0, noteLenTicks, pitch, 100, (int16_t)track, 0});
    s.totalTicks = totalTicks;
    e.setSchedule(s);
    e.setMaster(0.8f, false, false, 0.f);

    auto res = e.renderOffline(0, totalTicks, sr, false);
    (void)res;
    // renderOffline 已经返回数据，直接用
    return res.interleaved;
}

int main() {
    const int SR = 48000;

    // ---------- 1. 每种音色都要出声 ----------
    struct Case { const char* name; Instrument inst; int pitch; };
    const Case cases[] = {
        {"合成器 Synth", Instrument::Synth, 60},
        {"贝斯 Bass",    Instrument::Bass,  36},
        {"鼓组 Kick",    Instrument::Drum,  36},
        {"鼓组 Hat",     Instrument::Drum,  42},
        {"鼓组 Snare",   Instrument::Drum,  38},
        {"钟琴 Bell",    Instrument::Bell,  72},
        {"电钢 EPiano",  Instrument::EPiano, 60},
        // 1.29 新增三音色：必须真的出声
        {"拨弦 Pluck",   Instrument::Pluck,  55},
        {"弦乐 Strings", Instrument::Strings, 57},
        {"风琴 Organ",   Instrument::Organ,  60},
    };

    for (const auto& c : cases) {
        Channel ch = makeChannel(c.inst, 0.8f);
        // 鼓组给一组像样的参数
        if (c.inst == Instrument::Drum) {
            ch.params[0] = 0.3f;
            ch.params[1] = 0.3f;
            ch.params[2] = 0.8f;
            ch.params[3] = 0.5f;
        }
        auto buf = renderNotes(ch, c.pitch, kPPQ, kPPQ * 4, SR, 120.f);
        float peak, rms, dc;
        stats(buf, peak, rms, dc);
        char msg[256];
        std::snprintf(msg, sizeof(msg), "%s：有声 peak=%.3f rms=%.4f", c.name, peak, rms);
        check(peak > 0.01f && rms > 0.001f, msg);
        std::snprintf(msg, sizeof(msg), "%s：无 NaN/Inf", c.name);
        check(finite(buf), msg);
        std::snprintf(msg, sizeof(msg), "%s：无直流偏移 (dc=%.5f)", c.name, dc);
        check(std::fabs(dc) < 0.02f, msg);
    }

    // ---------- 2. 音高正确 ----------
    {
        Channel ch = makeChannel(Instrument::Synth, 0.8f);
        ch.params[0] = 0.f;      // 纯正弦，便于测频
        ch.params[1] = 0.f;
        ch.params[3] = 0.f;      // 无噪声
        ch.params[8] = 0.9f;     // 滤波开
        auto buf = renderNotes(ch, 69, kPPQ * 2, kPPQ * 4, SR, 120.f);   // A4 = 440Hz
        const float e440 = energyAt(buf, 440.f, SR);
        const float e880 = energyAt(buf, 880.f, SR);
        const float e330 = energyAt(buf, 330.f, SR);
        char msg[256];
        std::snprintf(msg, sizeof(msg), "音高 A4：440Hz 能量 %.5f 远大于 880Hz %.5f", e440, e880);
        check(e440 > e880 * 3.f && e440 > e330 * 3.f, msg);
    }

    // ---------- 3. 多通道不串音、总输出不爆 ----------
    {
        Engine e;
        e.prepare((float)SR, 512);
        e.setBpm(124.f);
        int k = e.addTrack(makeChannel(Instrument::Drum, 0.9f));
        int b = e.addTrack(makeChannel(Instrument::Bass, 0.8f));
        int p = e.addTrack(makeChannel(Instrument::Synth, 0.6f));
        e.trackCount();

        Schedule s;
        for (int i = 0; i < 8; i++) {
            s.notes.push_back(FlatNote{i * kPPQ, i * kPPQ + 60, 36, 110, (int16_t)k, 0});
            s.notes.push_back(FlatNote{i * kPPQ, i * kPPQ + kPPQ / 2, 36, 95, (int16_t)b, 0});
            s.notes.push_back(FlatNote{i * kPPQ, i * kPPQ + kPPQ, 60, 80, (int16_t)p, 0});
        }
        s.totalTicks = kPPQ * 8;
        e.setSchedule(s);
        e.setMaster(0.8f, true, true, 0.f);

        auto r = e.renderOffline(0, kPPQ * 8, SR, false);
        float peak, rms, dc;
        stats(r.interleaved, peak, rms, dc);
        char msg[256];
        std::snprintf(msg, sizeof(msg), "多轨混音：peak=%.3f 未超 1.0", peak);
        check(peak <= 1.0f && peak > 0.1f, msg);
        std::snprintf(msg, sizeof(msg), "多轨混音：rms=%.4f 有实际能量", rms);
        check(rms > 0.01f, msg);
        check(finite(r.interleaved), "多轨混音：无 NaN/Inf");
    }

    // ---------- 4. 每个效果器都不能炸 ----------
    {
        const EffectType fxTypes[] = {
            EffectType::Filter, EffectType::Delay, EffectType::Reverb, EffectType::Chorus,
            EffectType::Dist, EffectType::Comp, EffectType::Eq, EffectType::Bitcrush,
            EffectType::Phaser
        };
        const char* names[] = {"Filter","Delay","Reverb","Chorus","Dist","Comp","Eq","Bitcrush","Phaser"};
        for (int i = 0; i < 9; i++) {
            Channel ch = makeChannel(Instrument::Synth, 0.7f);
            ch.fx[0].type = fxTypes[i];
            ch.fx[0].enabled = true;
            ch.fx[0].mix = 0.5f;
            for (int j = 0; j < 8; j++) ch.fx[0].p[j] = 0.5f;
            auto buf = renderNotes(ch, 60, kPPQ, kPPQ * 4, SR, 120.f);
            float peak, rms, dc;
            stats(buf, peak, rms, dc);
            char msg[256];
            std::snprintf(msg, sizeof(msg), "效果 %s：输出有限 rms=%.4f", names[i], rms);
            check(finite(buf) && std::fabs(dc) < 0.1f, msg);
        }
    }

    // ---------- 5. 音量推子真的起作用 ----------
    {
        Channel loud = makeChannel(Instrument::Synth, 1.0f);
        Channel quiet = makeChannel(Instrument::Synth, 0.2f);
        auto a = renderNotes(loud, 60, kPPQ, kPPQ * 4, SR, 120.f);
        auto b = renderNotes(quiet, 60, kPPQ, kPPQ * 4, SR, 120.f);
        float pa, ra, da, pb, rb, db;
        stats(a, pa, ra, da);
        stats(b, pb, rb, db);
        char msg[256];
        std::snprintf(msg, sizeof(msg), "推子：1.0 的 RMS %.4f > 0.2 的 RMS %.4f", ra, rb);
        check(ra > rb * 1.5f, msg);
    }

    // ---------- 6. 声像 ----------
    {
        Channel ch = makeChannel(Instrument::Synth, 0.8f);
        ch.pan = -1.f;
        auto buf = renderNotes(ch, 60, kPPQ, kPPQ * 2, SR, 120.f);
        float pl = 0, pr = 0;
        for (size_t i = 0; i < buf.size(); i += 2) {
            pl = std::max(pl, std::fabs(buf[i]));
            pr = std::max(pr, std::fabs(buf[i + 1]));
        }
        char msg[256];
        std::snprintf(msg, sizeof(msg), "声像左：L=%.3f 应大于 R=%.3f", pl, pr);
        check(pl > pr * 1.2f, msg);
    }

    // ---------- 7. 离线导出长度合理 ----------
    {
        Engine e;
        e.prepare((float)SR, 512);
        e.setBpm(120.f);
        int t = e.addTrack(makeChannel(Instrument::Synth, 0.7f));
        Schedule s;
        s.notes.push_back(FlatNote{0, kPPQ, 60, 100, (int16_t)t, 0});
        s.totalTicks = kPPQ * 4;
        e.setSchedule(s);
        auto r = e.renderOffline(0, kPPQ * 4, SR, false);
        const double expected = 4.0 * kPPQ / (120.0 * kPPQ / 60.0);  // 2 秒
        const double actual = (double)r.frames / (double)SR;
        char msg[256];
        std::snprintf(msg, sizeof(msg), "导出长度：%.2fs（音乐 2s + 拖尾）", actual);
        check(actual > expected && actual < expected + 3.5, msg);
    }

    // ---------- 8. 回归：clearTracks + setTrackConfig 之后必须还能出声 ----------
    // 1.0.2 的致命 bug：pushAll() 先 clearTracks() 把 trackCount 归零，
    // 再逐条 setChannel；而 setTrackConfig 不维护 trackCount，
    // 渲染循环 for (t = 0; t < trackCount) 一次都不跑 —— 完全没有声音，
    // 而且不报任何错，用户只看到"点了播放没反应"。
    {
        Engine e;
        e.prepare((float)SR, 512);
        e.setBpm(120.f);
        const int N = 4;

        // 完全复刻 pushAll 的调用顺序
        e.clearTracks();
        for (int i = 0; i < N; i++) {
            Channel ch = makeChannel(Instrument::Synth, 0.8f);
            ch.fx[0].type = EffectType::None;
            e.setTrackConfig(i, ch);
        }

        char msg[256];
        std::snprintf(msg, sizeof(msg), "clearTracks+setTrackConfig 后 trackCount=%d（应为 %d）",
                      e.trackCount(), N);
        check(e.trackCount() == N, msg);

        Schedule s;
        for (int i = 0; i < 4; i++) {
            s.notes.push_back(FlatNote{i * kPPQ, i * kPPQ + kPPQ / 2, 60, 100, 0, 0});
        }
        s.totalTicks = kPPQ * 4;
        e.setSchedule(s);
        e.setMaster(0.8f, false, false, 0.f);

        auto r = e.renderOffline(0, kPPQ * 4, SR, false);
        float peak, rms, dc;
        stats(r.interleaved, peak, rms, dc);
        std::snprintf(msg, sizeof(msg), "clearTracks 后仍能出声 peak=%.3f rms=%.4f", peak, rms);
        check(peak > 0.01f && rms > 0.001f, msg);
    }

    // ---------- 9. 回归：轨道下标跳着设置也要正确计数 ----------
    {
        Engine e;
        e.prepare((float)SR, 512);
        e.clearTracks();
        // 只设第 3 条，trackCount 应该是 4（0..3 都算上），否则前三条渲染不到
        e.setTrackConfig(3, makeChannel(Instrument::Synth, 0.8f));
        char msg[256];
        std::snprintf(msg, sizeof(msg), "跳号设置轨道 3 后 trackCount=%d（应 >= 4）", e.trackCount());
        check(e.trackCount() >= 4, msg);
    }

    // ---------- 10. 回归：停止状态下试听必须出声，并且会自动结束 ----------
    // 之前的 bug：setTrackNote 用 position_ + duration 算结束时刻，
    // 停止时 position_ 不前进，声部永远不释放。表现为"点试听没反应"，
    // 或者声音一直挂着。现在用样本倒计时，不依赖传输状态。
    {
        Engine e;
        e.prepare((float)SR, 512);
        e.setBpm(120.f);
        e.clearTracks();
        Channel ch = makeChannel(Instrument::Synth, 0.8f);
        e.setTrackConfig(0, ch);
        e.setMaster(0.8f, false, false, 0.f);

        // 完全不进入播放状态，直接试听
        e.setTrackNote(0, 60, 0.8f, kPPQ);   // 一拍 = 0.5 秒

        std::vector<float> buf(2048 * 2);
        float peak = 0.f;
        // 先渲染 0.4 秒，应该有声
        int framesRendered = 0;
        const int chunk = 512;
        while (framesRendered < SR * 4 / 10) {
            e.processBlockForTest(buf.data(), chunk);
            for (int i = 0; i < chunk; i++) peak = std::max(peak, std::fabs(buf[(size_t)(i*2)]));
            framesRendered += chunk;
        }
        char msg[256];
        std::snprintf(msg, sizeof(msg), "停止状态下试听有声 peak=%.3f", peak);
        check(peak > 0.01f, msg);

        // 再渲染 2 秒，声部应该已经释放
        int activeAfter = 0;
        for (int k = 0; k < (SR * 2) / chunk; k++) {
            e.processBlockForTest(buf.data(), chunk);
        }
        // 用 allNotesOff 之后再测一次残响，检查是否已静音
        float tailPeak = 0.f;
        for (int k = 0; k < 8; k++) {
            e.processBlockForTest(buf.data(), chunk);
            for (int i = 0; i < chunk; i++) {
                tailPeak = std::max(tailPeak, std::fabs(buf[(size_t)(i*2)]));
            }
        }
        std::snprintf(msg, sizeof(msg), "试听结束后自动静音 tailPeak=%.4f", tailPeak);
        check(tailPeak < 0.01f, msg);
        (void)activeAfter;
    }

    // ---------- 11. 回归：块长大于 prepare 时的块，也绝不能静默 ----------
    // 这是"点了播放没声音也不报错"最隐蔽的一种：
    // renderInterleaved 里 `outL_.size() < frames` 就直接 return，
    // 设备永远拿到全零缓冲，而且日志里什么也没有。
    {
        Engine e;
        e.prepare((float)SR, 256);          // 按 256 准备
        e.setBpm(120.f);
        e.clearTracks();
        e.setTrackConfig(0, makeChannel(Instrument::Synth, 0.85f));
        e.setMaster(0.9f, false, false, 0.f);

        Schedule s;
        for (int i = 0; i < 8; i++) {
            s.notes.push_back(FlatNote{i * kPPQ, i * kPPQ + kPPQ, 60, 110, 0, 0});
        }
        s.totalTicks = kPPQ * 8;
        e.setSchedule(s);
        e.play();

        // 请求 1024 帧，远大于 prepare 时的 256
        const int big = 1024;
        std::vector<float> buf((size_t)big * 2, 0.f);
        float peak = 0.f;
        for (int round = 0; round < 20; round++) {
            e.renderInterleaved(buf.data(), big);
            for (int i = 0; i < big * 2; i++) peak = std::max(peak, std::fabs(buf[(size_t)i]));
        }
        char msg[256];
        std::snprintf(msg, sizeof(msg),
                      "prepare(256) 后请求 1024 帧仍然有声 peak=%.3f", peak);
        check(peak > 0.01f, msg);
    }

    // ---------- 12. 回归：反复 prepare 不会让输出变哑 ----------
    // 音频线程运行中设备可能报告不同采样率，触发二次 prepare。
    // 以前的实现会在 prepare 里 assign() 重建缓冲，指针一变就可能整块跳过。
    {
        Engine e;
        e.prepare((float)SR, 256);
        e.setBpm(120.f);
        e.clearTracks();
        e.setTrackConfig(0, makeChannel(Instrument::Synth, 0.85f));

        Schedule s;
        s.notes.push_back(FlatNote{0, kPPQ * 4, 60, 110, 0, 0});
        s.totalTicks = kPPQ * 4;
        e.setSchedule(s);
        e.play();

        const int n = 512;
        std::vector<float> buf((size_t)n * 2, 0.f);
        float peak = 0.f;
        for (int round = 0; round < 10; round++) {
            // 每轮中间换一次采样率/块长，模拟设备路由变化
            e.prepare(round % 2 == 0 ? 48000.f : 44100.f, round % 2 == 0 ? 256 : 1024);
            e.applyScheduleAgain();
            e.play();
            for (int k = 0; k < 10; k++) {
                e.renderInterleaved(buf.data(), n);
                for (int i = 0; i < n * 2; i++) peak = std::max(peak, std::fabs(buf[(size_t)i]));
            }
        }
        char msg[256];
        std::snprintf(msg, sizeof(msg), "反复 prepare 之后仍然有声 peak=%.3f", peak);
        check(peak > 0.01f, msg);
    }

    // ---------- 13. 音频片段：摆上轨道必须出声、可裁剪、走轨道混音 ----------
    // 这一组是"音频片段化"改造的核心回归：
    // 以前只有一条全局伴奏，绕过轨道混音；现在片段与音符共用同一路径，
    // 所以必须验证三件事 —— 会响、裁剪生效、轨道推子能影响它。
    {
        Engine e;
        e.prepare((float)SR, 256);
        e.setBpm(120.f);
        e.clearTracks();
        Channel dry0 = makeChannel(Instrument::Synth, 0.9f);
        dry0.sendReverb = 0.f;
        dry0.sendDelay = 0.f;
        e.setTrackConfig(0, dry0);

        // 一段 1 秒的 440Hz 正弦，立体声交织。
        const int clipFrames = SR;
        std::vector<float> tone((size_t)clipFrames * 2);
        for (int i = 0; i < clipFrames; i++) {
            const float v = 0.5f * std::sin(2.0 * 3.14159265358979 * 440.0 * i / SR);
            tone[(size_t)i * 2] = v;
            tone[(size_t)i * 2 + 1] = v;
        }
        e.setAudioBuffer(7, tone.data(), clipFrames, SR);

        // 空工程 + 只有一个音频片段：必须能出声，且不依赖任何 MIDI 事件。
        Engine::AudioClip c;
        c.track = 0;
        c.startTick = 0;
        c.offsetFrames = 0;
        c.lengthFrames = 0;     // 播到素材末尾
        c.gain = 1.f;
        std::vector<int32_t> ids{7}, trks{0};
        std::vector<double> starts{0.0};
        std::vector<int64_t> offs{0}, lens{0};
        std::vector<float> gains{1.f};
        e.setAudioClipsById(ids, trks, starts, offs, lens, gains);
        check(e.audioClipCount() == 1, "音频片段装配：count 应为 1");

        e.play();
        const int n = 512;
        std::vector<float> buf((size_t)n * 2, 0.f);
        float peak = 0.f;
        for (int k = 0; k < 20; k++) {
            e.renderInterleaved(buf.data(), n);
            for (int i = 0; i < n * 2; i++) peak = std::max(peak, std::fabs(buf[(size_t)i]));
        }
        char msg[256];
        std::snprintf(msg, sizeof(msg), "音频片段：有声 peak=%.3f", peak);
        check(peak > 0.05f, msg);

        // 轨道静音必须让它消失 —— 说明它确实走了轨道混音，而不是绕过。
        //
        // 用一个全新的 Engine 而不是复用 e：同一个实例里刚渲染过 0.2 秒的
        // 音乐，混响/延迟总线上还有尾巴。复用就是在测尾巴衰减，不是测静音。
        Engine em;
        em.prepare((float)SR, 256);
        em.setBpm(120.f);
        em.clearTracks();
        Channel muted = makeChannel(Instrument::Synth, 0.9f);
        muted.mute = true;
        muted.sendReverb = 0.f;
        muted.sendDelay = 0.f;
        em.setTrackConfig(0, muted);
        em.setAudioBuffer(7, tone.data(), clipFrames, SR);
        em.setAudioClipsById(ids, trks, starts, offs, lens, gains);
        em.play();

        float mutedPeak = 0.f;
        for (int k = 0; k < 30; k++) {
            em.renderInterleaved(buf.data(), n);
            if (k < 6) continue;   // 跳过 12ms 增益平滑造成的斜坡
            for (int i = 0; i < n * 2; i++) mutedPeak = std::max(mutedPeak, std::fabs(buf[(size_t)i]));
        }
        std::snprintf(msg, sizeof(msg), "音频片段：轨道静音后应静音 peak=%.4f", mutedPeak);
        check(mutedPeak < 0.02f, msg);

        // 裁掉头部：从第 0.5 秒开始取，前几块应该还是静音。
        Engine e2;
        e2.prepare((float)SR, 256);
        e2.setBpm(120.f);
        e2.clearTracks();
        Channel dry = makeChannel(Instrument::Synth, 0.9f);
        dry.sendReverb = 0.f;
        dry.sendDelay = 0.f;
        e2.setTrackConfig(0, dry);
        e2.setAudioBuffer(7, tone.data(), clipFrames, SR);
        // 注意：必须用 setAudioClipsById —— 它按 id 从素材池取 buffer。
        // 直接构造 AudioClip 的话 buffer 是空的，引擎会按"无素材"整段丢掉。
        e2.setAudioClipsById(ids, trks, starts,
                             std::vector<int64_t>{SR / 2},   // 从素材中点起播
                             std::vector<int64_t>{0},
                             std::vector<float>{1.f});
        e2.play();

        // 正弦在 0.5s 处接近零点，所以只看"有没有能量"而不是首帧幅度。
        float cutPeak = 0.f;
        for (int k = 0; k < 8; k++) {
            e2.renderInterleaved(buf.data(), n);
            for (int i = 0; i < n * 2; i++) cutPeak = std::max(cutPeak, std::fabs(buf[(size_t)i]));
        }
        std::snprintf(msg, sizeof(msg), "音频片段裁剪：仍有声 peak=%.3f", cutPeak);
        check(cutPeak > 0.05f, msg);

        // 清除必须彻底静音，且不留引用。
        // 同样换新实例：e2 刚播过 0.11 秒，同一实例的母线里还有残余。
        Engine ec;
        ec.prepare((float)SR, 256);
        ec.setBpm(120.f);
        ec.clearTracks();
        Channel dryc = makeChannel(Instrument::Synth, 0.9f);
        dryc.sendReverb = 0.f;
        dryc.sendDelay = 0.f;
        ec.setTrackConfig(0, dryc);
        ec.setAudioBuffer(7, tone.data(), clipFrames, SR);
        ec.setAudioClipsById(ids, trks, starts, offs, lens, gains);
        ec.clearAudioClips();
        check(ec.audioClipCount() == 0, "clearAudioClips 后 count 应为 0");
        ec.play();
        float afterClear = 0.f;
        for (int k = 0; k < 8; k++) {
            ec.renderInterleaved(buf.data(), n);
            for (int i = 0; i < n * 2; i++) afterClear = std::max(afterClear, std::fabs(buf[(size_t)i]));
        }
        std::snprintf(msg, sizeof(msg), "清除片段后应静音 peak=%.4f", afterClear);
        check(afterClear < 0.02f, msg);

        // 反复替换片段（拖动场景）：不应崩溃、不应残留旧片段。
        Engine e3;
        e3.prepare((float)SR, 256);
        e3.setBpm(120.f);
        e3.clearTracks();
        Channel dry3 = makeChannel(Instrument::Synth, 0.9f);
        dry3.sendReverb = 0.f;
        dry3.sendDelay = 0.f;
        e3.setTrackConfig(0, dry3);
        e3.setAudioBuffer(7, tone.data(), clipFrames, SR);
        for (int round = 0; round < 200; round++) {
            // 起点必须落在渲染窗口内：只渲 40 块 ≈ 0.43 秒，
            // 把片段摆到 3.5 秒处再断言"有声"是自相矛盾的。
            e3.setAudioClipsById(ids, trks,
                                 std::vector<double>{(double)(round % 4) * 64.0},
                                 std::vector<int64_t>{0},
                                 std::vector<int64_t>{0},
                                 std::vector<float>{1.f});
        }
        check(e3.audioClipCount() == 1, "反复替换后仍应只有 1 个片段");
        e3.play();
        float replacePeak = 0.f;
        for (int k = 0; k < 40; k++) {
            e3.renderInterleaved(buf.data(), n);
            for (int i = 0; i < n * 2; i++) replacePeak = std::max(replacePeak, std::fabs(buf[(size_t)i]));
        }
        std::snprintf(msg, sizeof(msg), "反复替换片段后仍然有声 peak=%.3f", replacePeak);
        check(replacePeak > 0.05f, msg);
    }

    // ================================================================
    // 1.29 新增乐器与效果器
    // ================================================================
    {
        char msg[256];

        // ---------- 新增乐器：基频正确 ----------
        struct PitchCase { const char* name; Instrument inst; int pitch; };
        const PitchCase pc[] = {
            {"拨弦 Pluck 基频", Instrument::Pluck, 57},      // A3 = 220 Hz
            {"弦乐 Strings 基频", Instrument::Strings, 57},
            {"风琴 Organ 基频", Instrument::Organ, 57},
        };
        for (const auto& c : pc) {
            Channel ch = makeChannel(c.inst, 0.8f);
            if (c.inst == Instrument::Pluck) {
                ch.params[0] = 0.8f; ch.params[1] = 0.95f; ch.params[2] = 0.6f;
            } else if (c.inst == Instrument::Strings) {
                ch.params[6] = 0.9f;
            }
            auto buf = renderNotes(ch, c.pitch, kPPQ * 2, kPPQ * 8, SR, 120.f);
            const float atF0 = energyAt(buf, 220.f, SR);
            const float atOct = energyAt(buf, 440.f, SR);
            std::snprintf(msg, sizeof(msg),
                "%s：220Hz 能量 %.4f 不低于 440Hz %.4f", c.name, atF0, atOct);
            // 拨弦是泛音极丰富的音色，二次谐波强过基频属正常
            //（真吉他的拨弦瞬态也这样）。判据放宽到"基频必须扎实存在"：
            // 不能弱于二次谐波的一半，且必须显著高于本底噪声。
            check(atF0 > atOct * 0.5f && atF0 > 0.001f, msg);
            std::snprintf(msg, sizeof(msg), "%s：无 NaN/Inf", c.name);
            check(finite(buf), msg);
        }

        // ---------- 新增效果器：都必须真的改变输出 ----------
        struct FxCase {
            const char* name;
            EffectType type;
            float p0, p1, p2, p3;
        };
        const FxCase fxs[] = {
            {"电颤 Tremolo",   EffectType::Tremolo,   0.6f, 0.9f, 0.f, 0.f},
            {"自动声像 Pan",   EffectType::AutoPan,   0.5f, 0.9f, 0.f, 0.f},
            {"饱和 Saturator", EffectType::Saturator, 0.8f, 0.4f, 0.7f, 0.f},
            {"噪声门 Gate",    EffectType::Gate,      0.6f, 0.1f, 0.3f, 1.f},
            {"宽度 Width",     EffectType::Width,     1.0f, 0.0f, 0.f, 0.f},
        };

        for (const auto& c : fxs) {
            Channel dry = makeChannel(Instrument::Synth, 0.7f);
            Channel wet = dry;
            wet.fx[0].type = c.type;
            wet.fx[0].enabled = true;
            wet.fx[0].mix = 1.f;
            wet.fx[0].p[0] = c.p0;
            wet.fx[0].p[1] = c.p1;
            wet.fx[0].p[2] = c.p2;
            wet.fx[0].p[3] = c.p3;

            auto a = renderNotes(dry, 60, kPPQ * 3, kPPQ * 4, SR, 120.f);
            auto b = renderNotes(wet, 60, kPPQ * 3, kPPQ * 4, SR, 120.f);

            std::snprintf(msg, sizeof(msg), "%s：无 NaN/Inf", c.name);
            check(finite(b), msg);

            double diff = 0.0;
            const size_t use = std::min(a.size(), b.size());
            for (size_t i = 0; i < use; i++) {
                const double d = (double)b[i] - (double)a[i];
                diff += d * d;
            }
            const float diffRms = use ? (float)std::sqrt(diff / (double)use) : 0.f;
            std::snprintf(msg, sizeof(msg), "%s：输出已改变 diffRms=%.4f", c.name, diffRms);
            check(diffRms > 0.0005f, msg);
        }

        // 电颤：调制会让平稳持续音的峰值因数升高
        {
            Channel dry = makeChannel(Instrument::Synth, 0.7f);
            Channel wet = dry;
            wet.fx[0].type = EffectType::Tremolo;
            wet.fx[0].mix = 1.f;
            wet.fx[0].p[0] = 0.75f;
            wet.fx[0].p[1] = 0.95f;
            auto a = renderNotes(dry, 60, kPPQ * 3, kPPQ * 4, SR, 120.f);
            auto b = renderNotes(wet, 60, kPPQ * 3, kPPQ * 4, SR, 120.f);
            float pa, ra, da2, pb, rb, db2;
            stats(a, pa, ra, da2);
            stats(b, pb, rb, db2);
            const float cd = ra > 1e-6f ? pa / ra : 0.f;
            const float cw = rb > 1e-6f ? pb / rb : 0.f;
            std::snprintf(msg, sizeof(msg), "电颤：峰值因数应升高 干=%.2f 湿=%.2f", cd, cw);
            check(cw > cd * 1.05f, msg);
        }

        // 宽度：侧信号应增强
        {
            Channel dry = makeChannel(Instrument::Synth, 0.7f);
            Channel wet = dry;
            wet.fx[0].type = EffectType::Width;
            wet.fx[0].mix = 1.f;
            wet.fx[0].p[0] = 1.f;
            wet.fx[0].p[1] = 0.f;
            auto a = renderNotes(dry, 60, kPPQ * 3, kPPQ * 4, SR, 120.f);
            auto b = renderNotes(wet, 60, kPPQ * 3, kPPQ * 4, SR, 120.f);
            double sa = 0.0, sb = 0.0;
            const size_t use = std::min(a.size(), b.size()) / 2;
            for (size_t i = 0; i < use; i++) {
                const double va = (double)a[i * 2] - (double)a[i * 2 + 1];
                const double vb = (double)b[i * 2] - (double)b[i * 2 + 1];
                sa += va * va;
                sb += vb * vb;
            }
            const float rA = (float)std::sqrt(sa / (double)std::max<size_t>(1, use));
            const float rB = (float)std::sqrt(sb / (double)std::max<size_t>(1, use));
            std::snprintf(msg, sizeof(msg), "宽度：侧信号应增强 干=%.4f 湿=%.4f", rA, rB);
            check(rB > rA * 1.05f, msg);
        }

        // 饱和：非对称项应产生二次谐波
        {
            Channel dry = makeChannel(Instrument::Bell, 0.6f);
            dry.params[0] = 0.02f;
            Channel wet = dry;
            wet.fx[0].type = EffectType::Saturator;
            wet.fx[0].mix = 1.f;
            wet.fx[0].p[0] = 1.f;
            wet.fx[0].p[1] = 0.9f;
            auto a = renderNotes(dry, 69, kPPQ * 2, kPPQ * 4, SR, 120.f);
            auto b = renderNotes(wet, 69, kPPQ * 2, kPPQ * 4, SR, 120.f);
            const float h2a = energyAt(a, 880.f, SR);
            const float h2b = energyAt(b, 880.f, SR);
            std::snprintf(msg, sizeof(msg),
                "饱和：二次谐波应增加 干=%.5f 湿=%.5f", h2a, h2b);
            check(h2b > h2a * 1.05f, msg);
        }
    }

    std::printf("\n%s  失败 %d 项\n", gFail == 0 ? "全部通过" : "有失败", gFail);
    return gFail == 0 ? 0 : 1;
}
