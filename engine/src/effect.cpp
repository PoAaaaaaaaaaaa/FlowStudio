// FlowStudio —— 效果器实现。全部原地立体声处理，预分配、音频线程零 malloc。
#include "flow/effect.h"

namespace flow {

namespace {
// Freeverb 在 44.1kHz 下的经典梳状/全通延迟长度，按采样率缩放。
constexpr int kCombTuning[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassTuning[4] = {556, 441, 341, 225};
constexpr float kStereoSpread = 23.f;

inline float expMap(float t, float lo, float hi) {
    return lo * std::pow(hi / lo, clampf(t, 0.f, 1.f));
}
}  // namespace

void EffectState::prepare(float sampleRate) {
    // 延迟/合唱缓冲：2.5 秒足够覆盖 60 BPM 下的全音符反馈
    dlL.resize((int)(sampleRate * 2.5f));
    dlR.resize((int)(sampleRate * 2.5f));

    preL.resize((int)(sampleRate * 0.2f));
    preR.resize((int)(sampleRate * 0.2f));

    const float scale = sampleRate / 44100.f;
    for (int i = 0; i < kCombCount; i++) {
        combL[i].resize((int)(kCombTuning[i] * scale) + 1);
        combR[i].resize((int)((kCombTuning[i] + kStereoSpread) * scale) + 1);
    }
    for (int i = 0; i < kAllpassCount; i++) {
        apL[i].resize((int)(kAllpassTuning[i] * scale) + 1);
        apR[i].resize((int)((kAllpassTuning[i] + kStereoSpread) * scale) + 1);
    }

    reset();
}

void EffectState::reset() {
    filtL.reset(); filtR.reset();
    smoothCutL = smoothCutR = 0.f;
    dlL.clear(); dlR.clear();
    dlyDampL = dlyDampR = 0.f;
    dlyDcL = dlyDcR = dlyDcPrevL = dlyDcPrevR = 0.f;
    toneL.reset(); toneR.reset();
    hpL.reset(); hpR.reset();
    compEnv = 1.f;
    for (int i = 0; i < 6; i++) eq[i].reset();
    crushHoldL = crushHoldR = 0.f;
    crushCounter = 0;
    for (int i = 0; i < 4; i++) { phaserStagesL[i].reset(); phaserStagesR[i].reset(); }
    phaserPhase = 0.f;
    phaserFbL = phaserFbR = 0.f;
    for (int i = 0; i < kCombCount; i++) {
        combL[i].clear(); combR[i].clear();
        combDampL[i] = combDampR[i] = 0.f;
    }
    for (int i = 0; i < kAllpassCount; i++) { apL[i].clear(); apR[i].clear(); }
    preL.clear(); preR.clear();
}

void EffectState::process(float* l, float* r, int n, float sr, float bpm) {
    if (type == EffectType::None || !enabled) return;

    const float wet = clampf(mix, 0.f, 1.f);
    const float dry = 1.f - wet;

    switch (type) {
        // ---------------------------------------------------------- 滤波
        case EffectType::Filter: {
            const int mode = p[2] < 0.25f ? 0 : (p[2] < 0.5f ? 2 : (p[2] < 0.75f ? 1 : 3));
            const float q = 0.55f + p[1] * p[1] * 16.f;
            // 每块插值一次，避免逐样本 set() 里的 tan/cos
            const float target = expMap(p[0], 60.f, 18000.f);
            if (smoothCutL <= 1.f) { smoothCutL = target; smoothCutR = target; }
            smoothCutL += (target - smoothCutL) * 0.25f;
            filtL.set(smoothCutL, q, sr);
            filtR.set(smoothCutR, q, sr);
            for (int i = 0; i < n; i++) {
                const float a = filtL.process(l[i], mode);
                const float b = filtR.process(r[i], mode);
                l[i] = l[i] * dry + a * wet;
                r[i] = r[i] * dry + b * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 延迟（节拍同步）
        case EffectType::Delay: {
            // p[0] 取 1/16..1 音符的离散档位：1/32,1/16d,1/16,1/8t,1/8d,1/8,1/4t,1/4d,1/4,1/2
            static const float kDiv[10] = {0.125f, 0.1875f, 0.25f, 0.3333f, 0.375f,
                                           0.5f, 0.6667f, 0.75f, 1.0f, 2.0f};
            const int divIdx = (int)(clampf(p[0], 0.f, 0.999f) * 10.f);
            const float beatSec = 60.f / std::max(20.f, bpm);
            float timeSec = kDiv[divIdx] * beatSec;
            timeSec = clampf(timeSec, 0.001f, 2.4f);

            const float fb = clampf(p[1], 0.f, 1.f) * 0.94f;
            const float toneHz = 700.f + clampf(p[2], 0.f, 1.f) * 13000.f;
            const bool pingPong = p[3] >= 0.5f;
            const float delaySamples = clampf(timeSec * sr, 4.f, (float)dlL.size() - 4.f);

            toneL.set(toneHz, 0.7f, sr);
            toneR.set(toneHz, 0.7f, sr);

            for (int i = 0; i < n; i++) {
                const float inL = l[i], inR = r[i];
                const float dL = dlL.read(delaySamples);
                const float dR = dlR.read(delaySamples);

                // 反馈路径里的音染 + 直流阻断（低音不会在回路里堆积）
                float fL = toneL.process(dL, 0);
                float fR = toneR.process(dR, 0);
                const float oL = fL - dlyDcPrevL + 0.9998f * dlyDcL;
                dlyDcPrevL = fL; dlyDcL = oL;
                const float oR = fR - dlyDcPrevR + 0.9998f * dlyDcR;
                dlyDcPrevR = fR; dlyDcR = oR;

                if (pingPong) {
                    dlL.write(inL * 0.5f + oR * fb);
                    dlR.write(inR * 0.5f + oL * fb);
                } else {
                    dlL.write(inL * 0.5f + oL * fb);
                    dlR.write(inR * 0.5f + oR * fb);
                }
                l[i] = l[i] * dry + oL * wet;
                r[i] = r[i] * dry + oR * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 混响
        case EffectType::Reverb: {
            const float feedback = 0.70f + clampf(p[0], 0.f, 1.f) * 0.28f;
            const float damp = 0.10f + (1.f - clampf(p[1], 0.f, 1.f)) * 0.75f;
            const float preSamples = clampf(clampf(p[2], 0.f, 1.f) * 0.12f * sr, 1.f, (float)preL.size() - 3.f);
            const float width = 0.35f + clampf(p[3], 0.f, 1.f) * 0.65f;
            const float sr44 = sr / 44100.f;

            for (int i = 0; i < n; i++) {
                const float inL = preL.read(preSamples);
                const float inR = preR.read(preSamples);
                preL.write(l[i]);
                preR.write(r[i]);

                const float mono = (inL + inR) * 0.5f;
                float accL = 0.f, accR = 0.f;

                for (int c = 0; c < kCombCount; c++) {
                    const float lenL = (float)((int)(kCombTuning[c] * sr44) + 1);
                    const float lenR = (float)((int)((kCombTuning[c] + kStereoSpread) * sr44) + 1);
                    const float oL = combL[c].read(lenL);
                    const float oR = combR[c].read(lenR);
                    // 一阶低通阻尼：高频先衰减，听感上像房子而不是金属罐
                    combDampL[c] = oL * (1.f - damp) + combDampL[c] * damp;
                    combDampR[c] = oR * (1.f - damp) + combDampR[c] * damp;
                    combL[c].write(mono + combDampL[c] * feedback);
                    combR[c].write(mono + combDampR[c] * feedback);
                    accL += oL;
                    accR += oR;
                }
                float wl = accL * 0.125f;
                float wr = accR * 0.125f;

                // 全通级：打散梳状峰，去掉金属味
                for (int a = 0; a < kAllpassCount; a++) {
                    const float lenL = (float)((int)(kAllpassTuning[a] * sr44) + 1);
                    const float lenR = (float)((int)((kAllpassTuning[a] + kStereoSpread) * sr44) + 1);
                    const float bufL = apL[a].read(lenL);
                    const float bufR = apR[a].read(lenR);
                    const float outL = bufL - wl;
                    const float outR = bufR - wr;
                    apL[a].write(wl + bufL * 0.5f);
                    apR[a].write(wr + bufR * 0.5f);
                    wl = outL;
                    wr = outR;
                }

                const float mid = (wl + wr) * 0.5f;
                wl = mid + (wl - mid) * width;
                wr = mid + (wr - mid) * width;

                l[i] = l[i] * dry + wl * 2.0f * wet;
                r[i] = r[i] * dry + wr * 2.0f * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 合唱
        case EffectType::Chorus: {
            const float rate = 0.05f + clampf(p[0], 0.f, 1.f) * 3.5f;
            const float depth = clampf(p[1], 0.f, 1.f) * 0.010f;
            const float baseDelay = 0.003f + clampf(p[2], 0.f, 1.f) * 0.018f;
            const float inc = rate / sr;

            for (int i = 0; i < n; i++) {
                chorusPhaseL += inc;
                if (chorusPhaseL >= 1.f) chorusPhaseL -= 1.f;
                chorusPhaseR += inc;
                if (chorusPhaseR >= 1.f) chorusPhaseR -= 1.f;

                const float dLs = clampf((baseDelay + std::sin(chorusPhaseL * kTwoPi) * depth) * sr,
                                         2.f, (float)dlL.size() - 3.f);
                const float dRs = clampf((baseDelay + std::sin(chorusPhaseR * kTwoPi) * depth) * sr,
                                         2.f, (float)dlR.size() - 3.f);

                const float dL = dlL.read(dLs);
                const float dR = dlR.read(dRs);
                dlL.write(l[i]);
                dlR.write(r[i]);

                l[i] = l[i] * dry + (l[i] + dL) * 0.7f * wet;
                r[i] = r[i] * dry + (r[i] + dR) * 0.7f * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 失真
        case EffectType::Dist: {
            const float drive = 1.f + clampf(p[0], 0.f, 1.f) * 40.f;
            const float toneHz = 400.f + clampf(p[1], 0.f, 1.f) * 12000.f;
            const float outGain = 0.30f + clampf(p[2], 0.f, 1.f) * 0.85f;

            toneL.set(toneHz, 0.7f, sr);
            toneR.set(toneHz, 0.7f, sr);
            hpL.highPass(sr, 55.f, 0.707f);
            hpR.highPass(sr, 55.f, 0.707f);

            for (int i = 0; i < n; i++) {
                float a = fastTanh(l[i] * drive);
                float b = fastTanh(r[i] * drive);
                a = hpL.process(toneL.process(a, 0) * outGain);
                b = hpR.process(toneR.process(b, 0) * outGain);
                l[i] = l[i] * dry + a * wet;
                r[i] = r[i] * dry + b * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 压缩
        case EffectType::Comp: {
            const float threshDb = -42.f + clampf(p[0], 0.f, 1.f) * 36.f;
            const float ratio = 1.f + clampf(p[1], 0.f, 1.f) * 15.f;
            const float attackMs = 0.5f + clampf(p[2], 0.f, 1.f) * 120.f;
            const float releaseMs = 12.f + clampf(p[3], 0.f, 1.f) * 700.f;
            const float atkCoef = 1.f - std::exp(-1.f / std::max(1.f, attackMs * 0.001f * sr));
            const float relCoef = 1.f - std::exp(-1.f / std::max(1.f, releaseMs * 0.001f * sr));
            const float makeup = dbToGain((-(threshDb) * (1.f - 1.f / ratio)) * 0.35f);

            for (int i = 0; i < n; i++) {
                const float peak = std::max(std::fabs(l[i]), std::fabs(r[i]));
                const float peakDb = gainToDb(peak);
                const float over = peakDb - threshDb;
                const float targetGainDb = over > 0.f ? -over * (1.f - 1.f / ratio) : 0.f;
                const float targetGain = dbToGain(targetGainDb);
                // 下降快、回升慢：这是压缩器该有的行为
                const float coef = targetGain < compEnv ? atkCoef : relCoef;
                compEnv += (targetGain - compEnv) * coef;
                l[i] = l[i] * dry + l[i] * compEnv * makeup * wet;
                r[i] = r[i] * dry + r[i] * compEnv * makeup * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 均衡
        case EffectType::Eq: {
            // 块级更新系数，不在样本循环里算三角函数
            eq[0].lowShelf(sr, 140.f, (clampf(p[0], 0.f, 1.f) - 0.5f) * 24.f);
            eq[1].peaking(sr, 420.f, (clampf(p[1], 0.f, 1.f) - 0.5f) * 24.f, 0.8f);
            eq[2].peaking(sr, 1800.f, (clampf(p[2], 0.f, 1.f) - 0.5f) * 24.f, 0.8f);
            eq[3].highShelf(sr, 6500.f, (clampf(p[3], 0.f, 1.f) - 0.5f) * 24.f);
            eq[4].highPass(sr, 20.f + clampf(p[4], 0.f, 1.f) * 500.f, 0.707f);
            eq[5].lowPass(sr, 20000.f - clampf(p[5], 0.f, 1.f) * 17000.f, 0.707f);

            for (int i = 0; i < n; i++) {
                float a = l[i], b = r[i];
                for (int k = 0; k < 6; k++) { a = eq[k].process(a); b = eq[k].process(b); }
                l[i] = l[i] * dry + a * wet;
                r[i] = r[i] * dry + b * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 降位
        case EffectType::Bitcrush: {
            const float bits = 16.f - clampf(p[0], 0.f, 1.f) * 14.f;
            const float levels = std::pow(2.f, std::max(1.f, bits)) * 0.5f;
            const int down = 1 + (int)(clampf(p[1], 0.f, 1.f) * 40.f);

            for (int i = 0; i < n; i++) {
                if (crushCounter <= 0) {
                    crushHoldL = std::round(clampf(l[i], -1.f, 1.f) * levels) / levels;
                    crushHoldR = std::round(clampf(r[i], -1.f, 1.f) * levels) / levels;
                    crushCounter = down;
                }
                crushCounter--;
                l[i] = l[i] * dry + crushHoldL * wet;
                r[i] = r[i] * dry + crushHoldR * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 移相
        case EffectType::Phaser: {
            const float rate = 0.05f + clampf(p[0], 0.f, 1.f) * 2.5f;
            const float depth = 0.2f + clampf(p[1], 0.f, 1.f) * 0.8f;
            const float fb = clampf(p[2], 0.f, 1.f) * 0.85f;
            const float inc = rate / sr;

            for (int i = 0; i < n; i++) {
                phaserPhase += inc;
                if (phaserPhase >= 1.f) phaserPhase -= 1.f;
                const float lfo = 0.5f + 0.5f * std::sin(phaserPhase * kTwoPi);
                const float freq = expMap(lfo * depth, 180.f, 7000.f);

                float a = l[i] + phaserFbL * fb;
                float b = r[i] + phaserFbR * fb;
                for (int s = 0; s < 4; s++) {
                    phaserStagesL[s].set(freq * (1.f + s * 0.35f), 1.2f, sr);
                    phaserStagesR[s].set(freq * (1.f + s * 0.35f), 1.2f, sr);
                    a = phaserStagesL[s].process(a, 1);
                    b = phaserStagesR[s].process(b, 1);
                }
                phaserFbL = clampf(a, -4.f, 4.f);
                phaserFbR = clampf(b, -4.f, 4.f);
                l[i] = l[i] * dry + (l[i] + a) * wet;
                r[i] = r[i] * dry + (r[i] + b) * wet;
            }
            break;
        }

        default:
            break;
    }
}

}  // namespace flow
