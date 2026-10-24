// FlowStudio —— DSP 原语。纯 C++17，无平台依赖，可在宿主上编译后离线自测。
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <algorithm>

namespace flow {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}
inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }

// 归一化 0..1 映射到 [lo, hi]，带指数弯曲（对数旋钮手感）。
inline float curve(float t, float lo, float hi) {
    t = clampf(t, 0.f, 1.f);
    return lo * std::pow(hi / lo, t);
}

inline float midiToHz(float note, float cents = 0.f) {
    return 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f + cents / 1200.0f);
}

// tanh 快速近似：音色里的饱和与限幅随处要用，跑在音频线程上必须便宜。
inline float fastTanh(float x) {
    if (x < -3.f) return -1.f;
    if (x > 3.f) return 1.f;
    const float x2 = x * x;
    return x * (27.f + x2) / (27.f + 9.f * x2);
}

inline float softClip(float x) {
    if (x > 1.f) return 1.f;
    if (x < -1.f) return -1.f;
    return 1.5f * x - 0.5f * x * x * x;
}

inline float dbToGain(float db) { return std::pow(10.f, db / 20.f); }
inline float gainToDb(float g) { return 20.f * std::log10(std::max(1e-6f, g)); }

// 反渐近线常量，避免 denormal 拖垮 CPU。
constexpr float kDenorm = 1e-20f;

// ---------------------------------------------------------------- 噪声
struct Noise {
    uint32_t s = 0x9E3779B9u;
    explicit Noise(uint32_t seed = 0x9E3779B9u) : s(seed ? seed : 1) {}
    inline float next() {
        s ^= s << 13; s ^= s >> 17; s ^= s << 5;
        return (float)(int32_t)s * 4.6566129e-10f;   // -1..1
    }
    inline float nextUni() { return 0.5f * (next() + 1.f); }
};

// ---------------------------------------------------------------- 平滑器
// 一次极点低通，用于参数去拉链噪声。
struct Smoother {
    float v = 0.f, coef = 0.002f;
    void setTime(float seconds, float sr) {
        coef = 1.f - std::exp(-1.f / std::max(1.f, seconds * sr));
    }
    inline float process(float target) {
        v += (target - v) * coef;
        return v;
    }
    inline void reset(float x) { v = x; }
};

// ---------------------------------------------------------------- ADSR
struct Adsr {
    enum Stage { Idle, Attack, Decay, Sustain, Release };
    Stage stage = Idle;
    float level = 0.f;
    float attackInc = 0.01f, decayCoef = 0.999f, releaseCoef = 0.999f;
    float sustain = 0.7f;
    float sr = 48000.f;
    float lastValue = 0.f;

    void setup(float a, float d, float s, float r, float sampleRate) {
        sr = sampleRate;
        attackInc = 1.f / std::max(1.f, a * sr);
        decayCoef = std::exp(-1.f / std::max(1.f, d * sr));
        releaseCoef = std::exp(-1.f / std::max(1.f, r * sr));
        sustain = clampf(s, 0.f, 1.f);
    }

    inline void trigger() { stage = Attack; }
    inline void release() { if (stage != Idle) stage = Release; }
    inline bool isActive() const { return stage != Idle; }

    inline float process() {
        switch (stage) {
            case Idle:
                level = 0.f;
                break;
            case Attack:
                level += attackInc;
                if (level >= 1.f) { level = 1.f; stage = Decay; }
                break;
            case Decay:
                level = sustain + (level - sustain) * decayCoef;
                if (level - sustain < 1e-4f) { level = sustain; stage = Sustain; }
                break;
            case Sustain:
                level = sustain;
                break;
            case Release:
                level *= releaseCoef;
                if (level < 1e-4f) { level = 0.f; stage = Idle; }
                break;
        }
        lastValue = level;
        return level;
    }
};

// ---------------------------------------------------------------- PolyBLEP 振荡器
struct Osc {
    double phase = 0.0;
    double inc = 0.0;

    enum Wave { Sine = 0, Saw, Square, Tri, Noise, WaveCount };

    inline void setFreq(float hz, float sr) {
        inc = (double)hz / (double)sr;
        if (inc > 0.45) inc = 0.45;
        if (inc < 0.0) inc = 0.0;
    }

    inline void reset(double p = 0.0) { phase = p; }

    static inline double blep(double t, double dt) {
        if (t < dt) { t /= dt; return t + t - t * t - 1.0; }
        if (t > 1.0 - dt) { t = (t - 1.0) / dt; return t * t + t + t + 1.0; }
        return 0.0;
    }

    // 返回 -1..1
    inline float tick(int wave) {
        float out = 0.f;
        switch (wave) {
            case Sine:
                out = (float)std::sin(phase * 6.283185307179586);
                break;
            case Saw: {
                double v = 2.0 * phase - 1.0;
                v -= blep(phase, inc);
                out = (float)v;
                break;
            }
            case Square: {
                double v = phase < 0.5 ? 1.0 : -1.0;
                v += blep(phase, inc);
                double p2 = phase + 0.5; if (p2 >= 1.0) p2 -= 1.0;
                v -= blep(p2, inc);
                out = (float)v;
                break;
            }
            case Tri: {
                double v = phase < 0.5 ? (4.0 * phase - 1.0) : (3.0 - 4.0 * phase);
                out = (float)v;
                break;
            }
            default:
                out = 0.f;
                break;
        }
        phase += inc;
        if (phase >= 1.0) phase -= 1.0;
        return out;
    }
};

// ---------------------------------------------------------------- TPT 状态变量滤波器
// Andrew Simper 的 TPT SVF：稳定、共振好听、支持连续扫频。
struct Svf {
    float ic1 = 0.f, ic2 = 0.f;
    float g = 0.f, k = 1.f, a1 = 0.f, a2 = 0.f, a3 = 0.f;

    void set(float cutoffHz, float q, float sr) {
        cutoffHz = clampf(cutoffHz, 20.f, sr * 0.45f);
        const float gg = std::tan(kPi * cutoffHz / sr);
        k = 1.f / std::max(0.5f, q);
        a1 = 1.f / (1.f + gg * (gg + k));
        a2 = gg * a1;
        a3 = gg * a2;
    }

    // mode: 0=low 1=band 2=high 3=notch
    inline float process(float x, int mode) {
        const float v3 = x - ic2;
        const float v1 = a1 * ic1 + a2 * v3;
        const float v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2.f * v1 - ic1;
        ic2 = 2.f * v2 - ic2;
        ic1 += kDenorm; ic2 += kDenorm;
        switch (mode) {
            case 0: return v2;
            case 1: return v1;
            case 2: return x - k * v1 - v2;
            default: return x - k * v1;
        }
    }
    void reset() { ic1 = ic2 = 0.f; }
};

// ---------------------------------------------------------------- 双二阶（EQ 用）
struct Biquad {
    float b0 = 1.f, b1 = 0.f, b2 = 0.f, a1 = 0.f, a2 = 0.f;
    float z1 = 0.f, z2 = 0.f;

    inline float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        z1 += kDenorm; z2 += kDenorm;
        return y;
    }

    void lowShelf(float sr, float f, float gainDb, float slope = 0.9f) {
        const float A = std::pow(10.f, gainDb / 40.f);
        const float w = kTwoPi * clampf(f, 20.f, sr * 0.45f) / sr;
        const float cs = std::cos(w), sn = std::sin(w);
        const float alpha = sn / 2.f * std::sqrt((A + 1.f / A) * (1.f / slope - 1.f) + 2.f);
        const float tsa = 2.f * std::sqrt(A) * alpha;
        const float a0 = (A + 1.f) + (A - 1.f) * cs + tsa;
        b0 = A * ((A + 1.f) - (A - 1.f) * cs + tsa) / a0;
        b1 = 2.f * A * ((A - 1.f) - (A + 1.f) * cs) / a0;
        b2 = A * ((A + 1.f) - (A - 1.f) * cs - tsa) / a0;
        a1 = -2.f * ((A - 1.f) + (A + 1.f) * cs) / a0;
        a2 = ((A + 1.f) + (A - 1.f) * cs - tsa) / a0;
    }

    void highShelf(float sr, float f, float gainDb, float slope = 0.9f) {
        const float A = std::pow(10.f, gainDb / 40.f);
        const float w = kTwoPi * clampf(f, 20.f, sr * 0.45f) / sr;
        const float cs = std::cos(w), sn = std::sin(w);
        const float alpha = sn / 2.f * std::sqrt((A + 1.f / A) * (1.f / slope - 1.f) + 2.f);
        const float tsa = 2.f * std::sqrt(A) * alpha;
        const float a0 = (A + 1.f) - (A - 1.f) * cs + tsa;
        b0 = A * ((A + 1.f) + (A - 1.f) * cs + tsa) / a0;
        b1 = -2.f * A * ((A - 1.f) + (A + 1.f) * cs) / a0;
        b2 = A * ((A + 1.f) + (A - 1.f) * cs - tsa) / a0;
        a1 = 2.f * ((A - 1.f) - (A + 1.f) * cs) / a0;
        a2 = ((A + 1.f) - (A - 1.f) * cs - tsa) / a0;
    }

    void peaking(float sr, float f, float gainDb, float q = 1.f) {
        const float A = std::pow(10.f, gainDb / 40.f);
        const float w = kTwoPi * clampf(f, 20.f, sr * 0.45f) / sr;
        const float cs = std::cos(w), sn = std::sin(w);
        const float alpha = sn / (2.f * std::max(0.1f, q));
        const float a0 = 1.f + alpha / A;
        b0 = (1.f + alpha * A) / a0;
        b1 = -2.f * cs / a0;
        b2 = (1.f - alpha * A) / a0;
        a1 = -2.f * cs / a0;
        a2 = (1.f - alpha / A) / a0;
    }

    void highPass(float sr, float f, float q = 0.707f) {
        const float w = kTwoPi * clampf(f, 20.f, sr * 0.45f) / sr;
        const float cs = std::cos(w), sn = std::sin(w);
        const float alpha = sn / (2.f * std::max(0.1f, q));
        const float a0 = 1.f + alpha;
        b0 = (1.f + cs) / 2.f / a0;
        b1 = -(1.f + cs) / a0;
        b2 = (1.f + cs) / 2.f / a0;
        a1 = -2.f * cs / a0;
        a2 = (1.f - alpha) / a0;
    }

    void lowPass(float sr, float f, float q = 0.707f) {
        const float w = kTwoPi * clampf(f, 20.f, sr * 0.45f) / sr;
        const float cs = std::cos(w), sn = std::sin(w);
        const float alpha = sn / (2.f * std::max(0.1f, q));
        const float a0 = 1.f + alpha;
        b0 = (1.f - cs) / 2.f / a0;
        b1 = (1.f - cs) / a0;
        b2 = (1.f - cs) / 2.f / a0;
        a1 = -2.f * cs / a0;
        a2 = (1.f - alpha) / a0;
    }

    void reset() { z1 = z2 = 0.f; }
};

// ---------------------------------------------------------------- 延迟线（分数读取）
class DelayLine {
public:
    void resize(int samples) {
        buf_.assign((size_t)std::max(4, samples), 0.f);
        write_ = 0;
        mask_ = 0;
        // 用 2 的幂做环形缓冲，读取时用位与而不是取模。
        int n = 1;
        while (n < (int)buf_.size()) n <<= 1;
        buf_.assign((size_t)n, 0.f);
        mask_ = n - 1;
    }

    inline void write(float x) {
        buf_[(size_t)write_] = x;
        write_ = (write_ + 1) & mask_;
    }

    inline float read(float delaySamples) const {
        delaySamples = clampf(delaySamples, 1.f, (float)mask_ - 2.f);
        const float idx = (float)write_ - delaySamples;
        int i0 = (int)std::floor(idx);
        const float frac = idx - (float)i0;
        i0 &= mask_;
        const int i1 = (i0 + 1) & mask_;
        return lerpf(buf_[(size_t)i0], buf_[(size_t)i1], frac);
    }

    void clear() { std::fill(buf_.begin(), buf_.end(), 0.f); }
    int size() const { return (int)buf_.size(); }

private:
    std::vector<float> buf_;
    int write_ = 0;
    int mask_ = 0;
};

// ---------------------------------------------------------------- 单声道/立体声小工具
struct Stereo {
    float l = 0.f, r = 0.f;
    inline Stereo& operator+=(const Stereo& o) { l += o.l; r += o.r; return *this; }
};

}  // namespace flow
