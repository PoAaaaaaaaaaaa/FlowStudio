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

    std::printf("\n%s  失败 %d 项\n", gFail == 0 ? "全部通过" : "有失败", gFail);
    return gFail == 0 ? 0 : 1;
}
