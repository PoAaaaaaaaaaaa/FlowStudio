// FlowStudio —— 插入效果器。每类效果一个处理单元，参数 0..1 由这里映射成物理量。
#pragma once

#include <cstdint>
#include "flow/dsp.h"

namespace flow {

enum class EffectType : int32_t {
    None = 0, Filter, Delay, Reverb, Chorus, Dist, Comp, Eq, Bitcrush, Phaser
};

struct EffectState {
    EffectType type = EffectType::None;
    bool enabled = true;
    float mix = 0.35f;
    float p[8] = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};

    // ---- 各效果独占的运行时状态 ----
    Svf filtL, filtR;              // Filter
    float smoothCutL = 0.f, smoothCutR = 0.f;

    DelayLine dlL, dlR;            // Delay / Chorus 共用两条缓冲
    float dlyDampL = 0.f, dlyDampR = 0.f;
    float dlyDcL = 0.f, dlyDcR = 0.f;
    float dlyDcPrevL = 0.f, dlyDcPrevR = 0.f;

    float chorusPhaseL = 0.f, chorusPhaseR = 0.25f;

    Svf toneL, toneR;              // Dist
    Biquad hpL, hpR;

    float compEnv = 1.f;           // Comp

    Biquad eq[6];                  // Eq

    float crushHoldL = 0.f, crushHoldR = 0.f;   // Bitcrush
    int crushCounter = 0;

    Svf phaserStagesL[4], phaserStagesR[4];     // Phaser
    float phaserPhase = 0.f;
    float phaserFbL = 0.f, phaserFbR = 0.f;

    // ---- 混响：Freeverb 结构 ----
    static constexpr int kCombCount = 8;
    static constexpr int kAllpassCount = 4;
    DelayLine combL[kCombCount], combR[kCombCount];
    DelayLine apL[kAllpassCount], apR[kAllpassCount];
    float combDampL[kCombCount] = {0};
    float combDampR[kCombCount] = {0};
    DelayLine preL, preR;

    void prepare(float sampleRate);
    void reset();
    /** bpm 用于延迟的节拍同步；sr 是当前采样率。原地处理立体声。 */
    void process(float* l, float* r, int n, float sampleRate, float bpm);
};

}  // namespace flow
