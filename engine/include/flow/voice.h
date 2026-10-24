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
    EPiano = 5
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
