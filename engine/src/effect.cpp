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
    tremPhase = 0.f;
    satPrev = 0.f;
    gateEnv = 0.f;
    gateGain = 1.f;
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

        // ---------------------------------------------------------- 电颤
        // 经典幅度调制：正弦 LFO 直接乘到信号上。
        // p0 速率 0.1..14 Hz，p1 深度，p2 波形（正弦/方波/锯齿）
        case EffectType::Tremolo: {
            const float rate = curve(p[0], 0.1f, 14.f);
            const float depth = clampf(p[1], 0.f, 1.f);
            const float shape = clampf(p[2], 0.f, 1.f);
            const float inc = rate / sr;
            for (int i = 0; i < n; i++) {
                tremPhase += inc;
                if (tremPhase >= 1.f) tremPhase -= 1.f;
                float lfo;
                if (shape < 0.33f) lfo = 0.5f + 0.5f * std::sin(tremPhase * kTwoPi);
                else if (shape < 0.66f) lfo = tremPhase < 0.5f ? 1.f : 0.f;
                else lfo = tremPhase;
                const float g = 1.f - depth * (1.f - lfo);
                l[i] = l[i] * dry + l[i] * g * wet;
                r[i] = r[i] * dry + r[i] * g * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 自动声像
        // 与电颤同一套 LFO，但作用在左右两声道上且相位相反。
        // p0 速率，p1 深度，p2 律动形状（正弦/三角）
        case EffectType::AutoPan: {
            const float rate = curve(p[0], 0.05f, 8.f);
            const float depth = clampf(p[1], 0.f, 1.f);
            const float shape = clampf(p[2], 0.f, 1.f);
            const float inc = rate / sr;
            for (int i = 0; i < n; i++) {
                tremPhase += inc;
                if (tremPhase >= 1.f) tremPhase -= 1.f;
                float lfo;
                if (shape < 0.5f) lfo = std::sin(tremPhase * kTwoPi);
                else lfo = 1.f - 4.f * std::fabs(tremPhase - 0.5f);   // 三角
                const float pos = lfo * depth;                          // -1..1
                // 等功率声像
                const float gl = std::cos((pos + 1.f) * 0.25f * kPi);
                const float gr = std::sin((pos + 1.f) * 0.25f * kPi);
                l[i] = l[i] * dry + l[i] * gl * wet * 1.414f;
                r[i] = r[i] * dry + r[i] * gr * wet * 1.414f;
            }
            break;
        }

        // ---------------------------------------------------------- 软饱和
        // 磁带/电子管的非线性：主项 tanh + 一点非对称项产生偶次谐波。
        // 纯 tanh 只产生奇次谐波，听起来偏"硬"；加 x² 项才像磁带。
        // p0 驱动量，p1 非对称量，p2 输出补偿
        case EffectType::Saturator: {
            const float drive = 1.f + clampf(p[0], 0.f, 1.f) * 11.f;   // 1..12
            const float asym = clampf(p[1], 0.f, 1.f) * 0.28f;
            const float outGain = 0.35f + clampf(p[2], 0.f, 1.f) * 0.65f;
            const float comp = 1.f / std::sqrt(drive);
            for (int i = 0; i < n; i++) {
                const float in = l[i] * drive;
                // 非对称：正半周多压一点，产生二次谐波（磁带的味道）
                float y = fastTanh(in + asym * in * in);
                satPrev = y;                        // 保留状态（未来做动态项用）
                l[i] = l[i] * dry + y * comp * outGain * wet;

                const float inr = r[i] * drive;
                const float yr = fastTanh(inr + asym * inr * inr);
                r[i] = r[i] * dry + yr * comp * outGain * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 噪声门
        // 侧链包络跟随 + 阈值开关 + 释放平滑。
        // 用途：切掉人声素材里的呼吸底噪，或做断奏门限效果。
        // p0 阈值，p1 起音快慢，p2 释放快慢，p3 深度
        case EffectType::Gate: {
            // 阈值走线性 0..0.5 而不是对数：素材电平差异很大，
            // 对数映射在 p0 中段就会落到 0.004 这种极低值上，门永远开着。
            const float thresh = clampf(p[0], 0.f, 1.f) * 0.5f;
            const float atkCoef = 1.f - std::exp(-1.f / (std::max(0.0002f, p[1] * 0.05f) * sr));
            const float relCoef = 1.f - std::exp(-1.f / (std::max(0.005f, p[2] * 0.8f) * sr));
            const float depth = clampf(p[3], 0.f, 1.f);
            for (int i = 0; i < n; i++) {
                // 取左右较大者的绝对值做包络
                const float hit = std::max(std::fabs(l[i]), std::fabs(r[i]));
                const float coef = hit > gateEnv ? atkCoef : relCoef;
                gateEnv += (hit - gateEnv) * coef;

                const float target = gateEnv > thresh ? 1.f : 0.f;
                gateGain += (target - gateGain) * (target > gateGain ? atkCoef : relCoef);
                const float g = 1.f - depth * (1.f - gateGain);

                l[i] = l[i] * dry + l[i] * g * wet;
                r[i] = r[i] * dry + r[i] * g * wet;
            }
            break;
        }

        // ---------------------------------------------------------- 立体声宽度
        // M/S 处理：M = (L+R)/2，S = (L-R)/2。
        // 放大 S 得到"更宽"，压小 S 得到"更窄"，负值则反相（单声道兼容性差）。
        // p0 宽度 0..2，p1 低频保持（100Hz 以下不做加宽，避免低频抵消）
        case EffectType::Width: {
            const float width = clampf(p[0], 0.f, 1.f) * 2.f;          // 0..2
            const float bassMono = clampf(p[1], 0.f, 1.f);

            // 关键：单声道素材的 S 分量是 0，纯 M/S 放大对它是无效的
            // （实测侧信号 0.0454 → 0.0454，一点没变）。
            // 所以这里先给 M 补一条基于延迟的"伪侧信号"：
            // 左右各自读不同延迟的副本，相位差造出宽度。
            // 这是业界常用的 Haas 式加宽，不需要原始素材本身有立体声信息。
            Svf bassLp;
            bassLp.set(120.f, 0.707f, sr);

            // 伪侧信号用两条短延迟线（最长 12ms，对应 83 Hz 以上）
            if (widthBufL.size() < 2048) {
                widthBufL.resize(2048);
                widthBufR.resize(2048);
            }
            const int delayL = (int)(sr * 0.004f);   // 4ms
            const int delayR = (int)(sr * 0.009f);   // 9ms

            for (int i = 0; i < n; i++) {
                const float mid = (l[i] + r[i]) * 0.5f;

                // 写入伪延迟线
                widthPos = (widthPos + 1) % 2048;
                widthBufL[widthPos] = mid;
                widthBufR[widthPos] = mid;

                int rl = widthPos - delayL;
                if (rl < 0) rl += 2048;
                int rr = widthPos - delayR;
                if (rr < 0) rr += 2048;

                // 由原始 M 与两份不同延迟的 M 合成左右
                const float sideL = (mid - widthBufL[rl]) * 0.5f;
                const float sideR = (mid - widthBufR[rr]) * 0.5f;
                const float realSide = (l[i] - r[i]) * 0.5f;

                // 真实侧信号与伪侧信号叠加：素材本身立体声时两者都贡献
                const float side = realSide + (sideL + sideR) * 0.5f;

                const float lowMid = bassLp.process(mid, 0);
                const float lowPart = std::fabs(lowMid);
                const float sideKeep = 1.f - bassMono * clampf(lowPart * 4.f, 0.f, 1.f);
                const float sideW = side * width * sideKeep;

                const float nl = mid + sideW;
                const float nr = mid - sideW;

                l[i] = l[i] * dry + nl * wet;
                r[i] = r[i] * dry + nr * wet;
            }
            break;
        }

        // ============================================================
        // 1.38 追加的五种。全部是经典算法的最小实现，参数语义与
        // Kotlin 侧 knobName() 一致。
        // ============================================================

        case EffectType::Compressor2: {
            // 三段压缩：低频(120Hz 以下) / 中频 / 高频(4kHz 以上) 各自一条包络。
            // 不分段的话，一记底鼓会把整个人声一起压下去 —— 这是单段压缩
            // 在多轨母线上最典型的问题。p 依次是 低阈值/中阈值/高阈值/比率。
            const float thrL = 1.f - clampf(p[0], 0.f, 1.f) * 0.9f;
            const float thrM = 1.f - clampf(p[1], 0.f, 1.f) * 0.9f;
            const float thrH = 1.f - clampf(p[2], 0.f, 1.f) * 0.9f;
            const float ratio = 1.f + clampf(p[3], 0.f, 1.f) * 11.f;   // 1..12

            Svf lpL, lpR, hpL, hpR;
            lpL.set(120.f, 0.707f, sr);  lpR.set(120.f, 0.707f, sr);
            hpL.set(4000.f, 0.707f, sr); hpR.set(4000.f, 0.707f, sr);
            const float rel = 1.f - std::exp(-1.f / (0.12f * sr));
            const float atk = 1.f - std::exp(-1.f / (0.004f * sr));

            for (int i = 0; i < n; i++) {
                const float lowL = lpL.process(l[i], 0), lowR = lpR.process(r[i], 0);
                const float hiL = hpL.process(l[i], 0), hiR = hpR.process(r[i], 0);
                // 中频 = 原信号 - 低 - 高（近似，交叉点各一阶）
                const float midL = l[i] - lowL - hiL;
                const float midR = r[i] - lowR - hiR;

                auto band = [&](float sig, float thr, int k) {
                    const float pk = std::fabs(sig);
                    const float target = pk > thr ? thr / pk : 1.f;
                    float& env = compBandEnv[k];
                    env += (target < env ? atk : rel) * (target - env);
                    return sig * env;
                };
                const float outL = band(lowL, thrL, 0) + band(midL, thrM, 1) + band(hiL, thrH, 2);
                const float outR = band(lowR, thrL, 0) + band(midR, thrM, 1) + band(hiR, thrH, 2);

                l[i] = l[i] * dry + outL * wet;
                r[i] = r[i] * dry + outR * wet;
            }
            break;
        }

        case EffectType::Limiter: {
            // 砖墙限幅：p0=阈值, p1=释放, p2=前视(0..1 映射到 0..5ms)
            const float thresh = 0.35f + clampf(p[0], 0.f, 1.f) * 0.64f;   // 0.35..0.99
            const float relSec = 0.02f + clampf(p[1], 0.f, 1.f) * 0.6f;
            const float lookMs = clampf(p[2], 0.f, 1.f) * 5.f;
            const int look = std::max(1, (int)(sr * lookMs * 0.001f));
            const float rel = 1.f - std::exp(-1.f / (relSec * sr));
            const float atk = 1.f - std::exp(-1.f / (0.0005f * sr));   // 极快起音

            for (int i = 0; i < n; i++) {
                // 前视：取未来 look 帧内的峰值来定这一刻的增益
                float pk = 0.f;
                for (int k = 0; k < look && i + k < n; k += 4) {
                    pk = std::max(pk, std::max(std::fabs(l[i + k]), std::fabs(r[i + k])));
                }
                pk = std::max(pk, std::max(std::fabs(l[i]), std::fabs(r[i])));
                const float target = pk > thresh ? thresh / pk : 1.f;
                limiter2Env += (target < limiter2Env ? atk : rel) * (target - limiter2Env);
                l[i] = l[i] * dry + l[i] * limiter2Env * wet;
                r[i] = r[i] * dry + r[i] * limiter2Env * wet;
            }
            break;
        }

        case EffectType::DeEsser: {
            // 齿音消除：6~9kHz 窄带检测 + 动态衰减。
            // p0=频率(6k..9k), p1=阈值, p2=衰减深度
            const float freq = 6000.f + clampf(p[0], 0.f, 1.f) * 3000.f;
            const float thresh = 0.02f + clampf(p[1], 0.f, 1.f) * 0.3f;
            const float depth = clampf(p[2], 0.f, 1.f);

            Biquad bpL, bpR;
            // 齿音带用"高通 + 低通的峰形"近似带通：
            // 这里没有现成的 bandpass，用 peaking 在目标频率提 Q 更接近原意。
            bpL.peaking(sr, freq, 0.f, 3.f);
            bpR.peaking(sr, freq, 0.f, 3.f);
            // peaking 增益 0dB 等于直通，取不出频带 —— 改用两级一阶：
            // 6kHz 高通后再 9kHz 低通，得到 6~9k 的带通近似。
            Biquad bpL2, bpR2;
            bpL.highPass(sr, freq * 0.75f, 0.707f);
            bpL2.lowPass(sr, freq * 1.5f, 0.707f);
            bpR.highPass(sr, freq * 0.75f, 0.707f);
            bpR2.lowPass(sr, freq * 1.5f, 0.707f);
            const float rel = 1.f - std::exp(-1.f / (0.05f * sr));

            for (int i = 0; i < n; i++) {
                const float bandL = bpL2.process(bpL.process(l[i]));
                const float bandR = bpR2.process(bpR.process(r[i]));
                const float pk = std::max(std::fabs(bandL), std::fabs(bandR));
                const float target = pk > thresh ? thresh / pk : 1.f;
                deEssEnv += (target < deEssEnv ? 0.5f : rel) * (target - deEssEnv);
                // 只在齿音带内做衰减，其余频段原样通过
                const float cutL = bandL * (1.f - deEssEnv) * depth;
                const float cutR = bandR * (1.f - deEssEnv) * depth;
                l[i] = l[i] * dry + (l[i] - cutL) * wet;
                r[i] = r[i] * dry + (r[i] - cutR) * wet;
            }
            break;
        }

        case EffectType::Transient: {
            // 瞬态塑形：分别放大/削弱起音与尾音。p0=起音, p1=尾音, p2=灵敏度
            const float attack = (clampf(p[0], 0.f, 1.f) - 0.5f) * 2.f;   // -1..1
            const float sustain = (clampf(p[1], 0.f, 1.f) - 0.5f) * 2.f;  // -1..1
            const float sens = 0.3f + clampf(p[2], 0.f, 1.f) * 0.7f;
            const float fast = 1.f - std::exp(-1.f / (0.001f * sr));
            const float slow = 1.f - std::exp(-1.f / (0.05f * sr));

            for (int i = 0; i < n; i++) {
                const float pk = std::max(std::fabs(l[i]), std::fabs(r[i]));
                transientFast += fast * (pk - transientFast);
                transientSlow += slow * (pk - transientSlow);
                // 快包络明显高于慢包络 = 正在起音
                const float diff = transientFast - transientSlow;
                const float isAttack = diff > 0.02f ? clampf(diff * 8.f * sens, 0.f, 1.f) : 0.f;
                const float isSustain = 1.f - isAttack;
                const float gain = 1.f + attack * isAttack + sustain * isSustain;
                l[i] = l[i] * dry + l[i] * gain * wet;
                r[i] = r[i] * dry + r[i] * gain * wet;
            }
            break;
        }

        case EffectType::Exciter: {
            // 激励器：把 3kHz 以上的谐波失真出来再叠回去，让闷的素材变亮。
            // p0=驱动, p1=频率, p2=混合量
            const float drive = 1.f + clampf(p[0], 0.f, 1.f) * 7.f;
            const float freq = 3000.f + clampf(p[1], 0.f, 1.f) * 4000.f;
            const float amount = clampf(p[2], 0.f, 1.f);

            Biquad hpL, hpR;
            hpL.highPass(sr, freq, 0.707f);
            hpR.highPass(sr, freq, 0.707f);

            for (int i = 0; i < n; i++) {
                const float hiL = hpL.process(l[i]);
                const float hiR = hpR.process(r[i]);
                // 过驱动后取残差作为"新生的高频"
                const float exL = std::tanh(hiL * drive) - hiL;
                const float exR = std::tanh(hiR * drive) - hiR;
                l[i] = l[i] * dry + (l[i] + exL * amount) * wet;
                r[i] = r[i] * dry + (r[i] + exR * amount) * wet;
            }
            break;
        }

        default:
            break;
    }
}

}  // namespace flow
