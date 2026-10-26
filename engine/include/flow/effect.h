// FlowStudio —— 插入效果器。每类效果一个处理单元，参数 0..1 由这里映射成物理量。
#pragma once

#include <cstdint>
#include "flow/dsp.h"

namespace flow {

enum class EffectType : int32_t {
    None = 0, Filter, Delay, Reverb, Chorus, Dist, Comp, Eq, Bitcrush, Phaser,
    // ---- 1.29 追加。全部是公开算法的直接实现，不依赖任何库。----
    Tremolo = 10,    // 幅度调制（经典电颤，正弦 LFO）
    AutoPan = 11,    // 自动声像（与 Tremolo 反相的立体声版本）
    Saturator = 12,  // 磁带式软饱和（tanh + 轻微的非对称，加二次谐波）
    Gate = 13,       // 噪声门 / 断续门（侧链包络 + 阈值释放）
    Width = 14,      // 立体声宽度（M/S 处理，可到超宽）
    // ---- 1.38 追加。ordinal 必须与 Kotlin 侧 EffectType 严格一致 ----
    // 只加在末尾：中间插入会让旧工程存档里的效果类型整体错位。
    Compressor2 = 15, // 多段压缩（低/中/高三段独立阈值与比率）
    Limiter = 16,     // 砖墙限幅（前视峰值检测 + 瞬时起音）
    DeEsser = 17,     // 齿音消除（6~9kHz 窄带动态衰减）
    Transient = 18,   // 瞬态塑形（分别强化/削弱起音与尾音）
    Exciter = 19      // 激励器（高频谐波再生，让闷的素材变亮）
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

    // ---- 1.29 新增效果的运行时状态 ----
    float tremPhase = 0.f;             // Tremolo / AutoPan 共用 LFO 相位
    float satPrev = 0.f;               // Saturator 的一阶状态（非对称项）
    float gateEnv = 0.f;               // Gate 的包络跟随器
    float gateGain = 1.f;              // Gate 的平滑增益

    // Width 的伪侧信号延迟线（Haas 式加宽，给单声道素材造宽度）
    std::vector<float> widthBufL, widthBufR;
    int widthPos = 0;

    // ---- 1.38 新增效果的运行时状态 ----
    float compBandEnv[3] = {1.f, 1.f, 1.f};  // Compressor2 三段包络
    float limiter2Env = 1.f;                 // Limiter 增益
    float deEssEnv = 1.f;                    // DeEsser 衰减包络
    float transientFast = 0.f;               // Transient 快包络
    float transientSlow = 0.f;               // Transient 慢包络

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
