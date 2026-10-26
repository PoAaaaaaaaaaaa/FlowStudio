// FlowStudio —— 乐器音源。六种音色，全部是实时合成，不依赖任何采样文件。
#pragma once

#include <cstdint>
#include <cmath>
#include "flow/dsp.h"

namespace flow {

enum class Instrument : int32_t {
    Synth = 0,
    Sampler = 1,
    Drum = 2,
    Bass = 3,
    Bell = 4,
    EPiano = 5,
    // ---- 1.29 追加。全部实时合成，不引入任何采样文件。----
    // 新增原因：原六种里没有"有明确拨弦/弓弦质感"的音色，
    // 编曲里要写吉他分解、弦乐铺底、管风琴时只能拿 Synth 硬凑。
    Pluck = 6,      // Karplus-Strong 数字波导拨弦（吉他/竖琴/古筝类）
    Strings = 7,    // 失谐锯齿合奏 + 慢起音 + 颤音（弦乐组/铺底）
    Organ = 8       // 加法合成拉杆风琴（Hammond 式 9 根拉杆）
};

// 每个发声单元。一个 Voice 一次只演奏一个音，用完即回收。
// 内存是预分配的，音频线程里绝不分配 —— 这是不掉帧的硬前提。
struct Voice {
    bool active = false;
    bool released = false;
    int32_t pitch = 60;
    float velocity = 1.f;
    int32_t endTick = 0;
    int16_t track = 0;

    /**
     * 样本倒计时：>0 时每个采样减一，到 0 自动释放。
     * 试听（previewNote）用它，因为停止状态下 tick 不会前进，
     * 靠 endTick 判断结束的声部会永远挂着。
     */
    int32_t manualReleaseSamples = 0;

    Instrument inst = Instrument::Synth;

    // 振荡器
    Osc osc1, osc2, osc3;
    double subPhase = 0.0;
    double subInc = 0.0;
    Noise noise;

    // 包络
    Adsr amp, filtEnv, auxEnv;

    // 滤波
    Svf lpf;
    Svf hpf;

    // FM 用的调制器
    double fmPhase = 0.0, fmInc = 0.0;
    double fmRatio = 1.0, fmIndex = 0.0;

    // ---- Karplus-Strong 拨弦（Pluck）----
    // 数字波导：一段延迟线 + 一阶低通反馈。这是 1983 年 Karplus 与 Strong
    // 的原始做法，被整个物理建模领域沿用至今 —— 便宜、稳定、音色像真拨弦。
    static constexpr int kWgSize = 4096;   // 覆盖到 ~11 Hz，远低于最低音
    float wg[kWgSize] = {0};
    int wgPos = 0;
    int wgLen = 100;
    /** 分数延迟补偿量 0..1：环长 = wgLen + wgFrac，音准靠它修正 */
    float wgFrac = 0.f;
    float wgDamp = 0.5f;
    float wgLast = 0.f;
    /** 失谐锯齿合奏（Strings）：三路相位偏移 */
    double ensPhase[3] = {0.0, 0.33, 0.66};
    double ensInc[3] = {0.0, 0.0, 0.0};
    /** 风琴拉杆振幅（9 根，1..8 次谐波 + 亚谐波） */
    float drawbars[9] = {0};

    // 采样播放
    int32_t samplePos = 0;
    int32_t sampleStart = 0, sampleEnd = 0;
    bool sampleLoop = false;
    float samplePitchRatio = 1.f;

    // 每次发声都重新算的常量
    void setup(Instrument i, int32_t pitchIn, float vel, int32_t endTickIn, int16_t trackIn,
               const float* params, float sampleRate);

    // 混入立体声累加缓冲。返回 false 表示这个声部已经结束。
    bool render(float* outL, float* outR, int numFrames, const float* params, float sampleRate);

    void release() { released = true; amp.release(); filtEnv.release(); auxEnv.release(); }
};

}  // namespace flow
