// FlowStudio —— 发声单元实现。六种音色的合成内核。
#include "flow/voice.h"

namespace flow {

namespace {

// 参数便捷映射：0..1 归一化 -> 物理量。全部走指数曲线，旋钮手感才跟手。
inline float pAttack(float p)  { return 0.001f * std::pow(2000.f, p); }        // 1ms .. 2s
inline float pDecay(float p)   { return 0.015f * std::pow(200.f, p); }         // 15ms .. 3s
inline float pRelease(float p) { return 0.015f * std::pow(266.f, p); }         // 15ms .. 4s
inline float pCutoff(float p)  { return 80.f * std::pow(225.f, p); }           // 80Hz .. 18kHz
inline float pQ(float p)       { return 0.6f + p * p * 14.f; }                 // 0.6 .. 14.6

inline int waveOf(float p) {
    int w = (int)(p * (float)Osc::WaveCount);
    return w < 0 ? 0 : (w >= Osc::WaveCount ? Osc::WaveCount - 1 : w);
}

// 鼓组按音高分类。这是 FL 里踩鼓键盘的常规布局。
enum DrumType { Kick = 0, Snare, Clap, HatClosed, HatOpen, Tom, Rim, Cymbal };

inline DrumType drumTypeOf(int pitch) {
    if (pitch <= 36) return Kick;
    if (pitch == 37) return Rim;
    if (pitch <= 38) return Snare;
    if (pitch == 39) return Clap;
    if (pitch == 40 || pitch == 41) return Tom;
    if (pitch == 42) return HatClosed;
    if (pitch == 43 || pitch == 44) return Tom;
    if (pitch == 45 || pitch == 47) return Tom;
    if (pitch == 46) return HatOpen;
    if (pitch == 48 || pitch == 50) return Tom;
    return Cymbal;
}

}  // namespace

void Voice::setup(Instrument i, int32_t pitchIn, float vel, int32_t endTickIn, int16_t trackIn,
                  const float* p, float sampleRate) {
    inst = i;
    pitch = pitchIn;
    velocity = clampf(vel, 0.01f, 1.f);
    endTick = endTickIn;
    track = trackIn;
    active = true;
    released = false;

    noise = Noise((uint32_t)(pitch * 2654435761u) ^ (uint32_t)(trackIn * 40503u) ^ 0x9E3779B9u);

    const float sr = sampleRate;
    const float cents = p[2 * 0 + 0];  // 占位，具体在下方各自计算

    (void)cents;

    switch (i) {
        case Instrument::Synth: {
            const int w1 = waveOf(p[0]);
            const int w2 = waveOf(p[1]);
            const float detuneCents = (p[2] - 0.5f) * 60.f;   // -30..+30 cent
            const float f = midiToHz((float)pitch);
            osc1.setFreq(f, sr);
            osc1.reset((double)(pitch % 7) * 0.137);          // 每个音相位错开，避免同相叠加爆音
            if (w1 == Osc::Noise) osc1.inc = 0.0;
            osc2.setFreq(f * std::pow(2.f, detuneCents / 1200.f), sr);
            osc2.reset((double)((pitch * 3) % 11) * 0.0871);
            if (w2 == Osc::Noise) osc2.inc = 0.0;
            // 保存波形到 osc 的私有约定：借用 subInc 的高位存不了，改用全局小表
            subInc = 0.0;                                      // synth 不用 sub
            // 波形编号塞进 fmRatio（同 Voice 内不同乐器复用字段，避免额外成员）
            fmRatio = (double)w1 + (double)w2 * 16.0;

            amp.setup(pAttack(p[4]), pDecay(p[5]), p[6], pRelease(p[7]), sr);
            filtEnv.setup(0.001f, pDecay(p[5]) * 0.8f + 0.02f, 0.f, pRelease(p[7]), sr);
            amp.trigger();
            filtEnv.trigger();

            const float cut = pCutoff(p[8]);
            lpf.set(cut, pQ(p[9]), sr);
            hpf.set(30.f, 0.707f, sr);
            auxEnv.level = p[10];                              // 滤波包络深度
            break;
        }
        case Instrument::Bass: {
            const int w1 = waveOf(p[0]);
            const float f = midiToHz((float)pitch - 12.f);
            osc1.setFreq(f, sr);
            osc1.reset(0.0);
            const float detuneCents = (p[2] - 0.5f) * 16.f;
            osc2.setFreq(f * std::pow(2.f, detuneCents / 1200.f), sr);
            osc2.reset(0.25);
            fmRatio = (double)w1 + (double)waveOf(p[1]) * 16.0;

            amp.setup(pAttack(p[4]), pDecay(p[5]), p[6], pRelease(p[7]), sr);
            filtEnv.setup(0.001f, 0.18f + p[5] * 0.4f, 0.f, pRelease(p[7]), sr);
            amp.trigger();
            filtEnv.trigger();

            lpf.set(pCutoff(p[8]), pQ(p[9]), sr);
            hpf.set(24.f, 0.707f, sr);
            auxEnv.level = p[10];
            break;
        }
        case Instrument::Drum: {
            const DrumType dt = drumTypeOf(pitch);
            const float baseHz = 40.f + p[0] * 120.f;          // 底鼓基频
            float decay;
            switch (dt) {
                case Kick:      decay = 0.05f + p[1] * 0.55f; break;
                case Snare:     decay = 0.06f + p[1] * 0.30f; break;
                case Clap:      decay = 0.08f + p[1] * 0.35f; break;
                case HatClosed: decay = 0.012f + p[1] * 0.06f; break;
                case HatOpen:   decay = 0.14f + p[1] * 0.5f; break;
                case Tom:       decay = 0.10f + p[1] * 0.45f; break;
                case Rim:       decay = 0.02f + p[1] * 0.05f; break;
                default:        decay = 0.3f + p[1] * 1.2f; break;
            }
            amp.setup(0.0005f, decay, 0.f, decay * 0.6f, sr);
            amp.trigger();

            // 鼓音高由 p[0] 与音符共同决定
            float f = baseHz;
            switch (dt) {
                case Kick: f = 38.f + p[0] * 90.f; break;
                case Snare: f = 150.f + p[0] * 130.f; break;
                case Clap: f = 900.f + p[0] * 700.f; break;
                case HatClosed:
                case HatOpen: f = 7000.f + p[0] * 5000.f; break;
                case Tom: f = midiToHz((float)pitch); break;
                case Rim: f = 1700.f + p[0] * 600.f; break;
                default: f = 5200.f + p[0] * 4000.f; break;
            }
            osc1.setFreq(f, sr);
            osc1.reset(0.0);
            // 底鼓的起始高音：让音高在几十毫秒里滑下来，这是 punch 的来源
            subInc = (double)(f * 3.2f);
            lpf.set(clampf(2000.f + p[3] * 12000.f, 100.f, sr * 0.45f), 0.9f + p[3] * 3.f, sr);
            hpf.set(dt == HatClosed || dt == HatOpen ? 6000.f : 20.f, 0.707f, sr);
            auxEnv.level = (float)dt;                          // 鼓类型存这里供 render 用
            break;
        }
        case Instrument::Bell: {
            const float f = midiToHz((float)pitch);
            osc1.setFreq(f, sr);
            osc1.reset(0.0);
            fmInc = (double)(f * (1.f + p[0] * 7.f));          // ratio 1..8
            fmPhase = 0.0;
            fmIndex = 0.5f + p[1] * 9.f;

            amp.setup(0.001f, 0.5f + p[3] * 2.5f, 0.f, 0.4f + p[3] * 2.f, sr);
            amp.trigger();
            auxEnv.setup(0.001f, 0.25f + p[0] * 0.5f, 0.f, 0.3f, sr);
            auxEnv.trigger();
            lpf.set(1200.f + p[2] * 12000.f, 0.8f, sr);
            break;
        }
        case Instrument::EPiano: {
            const float f = midiToHz((float)pitch);
            osc1.setFreq(f, sr);
            osc1.reset(0.0);
            fmInc = (double)(f * (1.0 + p[0] * 3.0));
            fmPhase = 0.0;
            fmIndex = 0.2f + p[1] * 6.f;

            amp.setup(0.001f, 0.35f + p[4] * 1.8f, 0.12f, 0.20f + p[3] * 0.9f, sr);
            amp.trigger();
            auxEnv.setup(0.001f, 0.05f + p[2] * 0.12f, 0.f, 0.1f, sr);
            auxEnv.trigger();
            lpf.set(900.f + p[2] * 6000.f, 0.9f, sr);

            // 立体声颤音：左右相位差 90 度，电钢标志性的晃动
            subInc = (double)(4.5 + p[3] * 5.0);                // LFO Hz
            break;
        }
        // ---------------------------------------------------- Karplus-Strong 拨弦
        // 激励是一段与周期等长的噪声（拨片/指甲的瞬态），
        // 之后靠延迟线 + 低通反馈把能量一圈圈衰减掉，泛音自然比基频快。
        // 参数：p0 亮度（低通系数）、p1 衰减（反馈量）、p2 拨片硬度（激励滤波）、
        //       p3 起音力度敏感、p4..p7 ADSR、p8 音色微调（body 共振）
        case Instrument::Pluck: {
            const float f = midiToHz((float)pitch);
            // 延迟长度要按"整数部分 + 小数部分"拆开：
            // 只取整数会让音准偏差最高达半个采样，低音区（周期长）能差几十音分。
            // 小数部分在 render 里用线性插值补偿（分数延迟）。
            const float exact = (float)sr / std::max(20.f, f);
            int len = (int)exact;
            wgFrac = exact - (float)len;          // 0..1
            if (len < 4) { len = 4; wgFrac = 0.f; }
            if (len >= kWgSize - 8) { len = kWgSize - 8; wgFrac = 0.f; }
            wgLen = len;
            wgPos = 0;
            // 归零整个波导（只在 setup 时做，不在 render 里做）
            for (int k = 0; k < kWgSize; k++) wg[k] = 0.f;
            // 激励：形状决定音色。
            //
            // 原始 Karplus-Strong 用纯白噪声，结果是"每个泛音一样响"，
            // 听起来像弹棉花 —— 而且高频噪声把基频盖住了（实测 440 Hz
            // 比 220 Hz 还强）。真实拨弦的激励是扫过弦的一段力脉冲：
            // 频谱以 1/f 下降。所以这里用"积分噪声"（棕噪声）当底，
            // 再按硬度混入少量白噪声保留拨片的颗粒感。
            const float hardness = clampf(p[2], 0.f, 1.f);
            float prev = 0.f;
            float brown = 0.f;
            for (int k = 0; k < wgLen; k++) {
                const float n = noise.next();
                // 布朗噪声：白噪声经一阶低通积分，频谱自然向低频倾斜
                brown = brown * 0.92f + n * 0.08f;
                // 硬度高 = 多保留白噪声 = 更亮的拨片瞬态
                prev = lerpf(brown * 3.2f, n, hardness * 0.55f);
                // 起振时乘一个半窗，避免第一个样本是硬阶跃（会爆一下）
                const float edge = (k < 8) ? (float)k / 8.f
                                            : 1.f;
                wg[k] = prev * edge;
            }
            wgLast = 0.f;
            wgDamp = 0.5f + p[0] * 0.48f;     // 越接近 1 越亮、衰减越慢
            amp.setup(pAttack(p[4]), pDecay(p[5]), p[6], pRelease(p[7]), sr);
            amp.trigger();
            // 拨弦的音量随力度变化明显，这里额外放大力度差
            break;
        }

        // ---------------------------------------------------- 弦乐合奏
        // 三路锯齿按 ±8 cent 失谐叠加，是弦乐组"厚"的来源；
        // 每一路相位错开，避免同时起振造成的相位堆叠。
        case Instrument::Strings: {
            const float f = midiToHz((float)pitch);
            const float detune = (p[2] - 0.5f) * 24.f;       // ±12 cent
            for (int v = 0; v < 3; v++) {
                const float cents = detune + (v - 1) * (4.f + p[1] * 10.f);
                ensInc[v] = (double)(f * std::pow(2.f, cents / 1200.f)) / (double)sr;
                ensPhase[v] = (double)v * 0.333 + (double)(pitch % 5) * 0.113;
            }
            // 弦乐起音天然慢；p[4] 只是微调，基础值不再从 1ms 起
            amp.setup(0.06f + pAttack(p[4]) * 0.5f, pDecay(p[5]), p[6],
                      0.15f + pRelease(p[7]), sr);
            amp.trigger();
            // 颤音速率存在 subInc，深度在 render 里由 p[3] 取
            subInc = (double)(4.2 + p[3] * 3.8);
            lpf.set(pCutoff(p[8]), pQ(p[9]), sr);
            hpf.set(90.f, 0.707f, sr);
            auxEnv.level = p[10];
            break;
        }

        // ---------------------------------------------------- 拉杆风琴
        // Hammond 的 9 根拉杆就是 9 个正弦谐波的振幅。
        // 拉杆编号与谐波（相对基频的倍率）对应如下，这是标准做法。
        case Instrument::Organ: {
            const float f = midiToHz((float)pitch);
            static const float kDrawRatio[9] = {
                0.5f, 1.5f, 1.f, 2.f, 3.f, 4.f, 5.f, 6.f, 8.f
            };
            for (int d = 0; d < 9; d++) {
                // 每根拉杆一个参数位，p[0..8] 直接就是 9 根拉杆的拉出量
                drawbars[d] = clampf(p[d], 0.f, 1.f);
                (void)kDrawRatio[d];
            }
            osc1.setFreq(f, sr);
            osc1.reset(0.0);
            // 风琴没有衰减：按键一直响，包络是方形的（这是它的性格）
            amp.setup(0.004f + p[9] * 0.05f, 0.01f, 1.f, 0.03f + p[10] * 0.15f, sr);
            amp.trigger();
            // 打击泛音（Hammond 的 percussion）借 auxEnv 做一次快速衰减
            auxEnv.setup(0.001f, 0.08f + p[11] * 0.25f, 0.f, 0.05f, sr);
            auxEnv.trigger();
            break;
        }

        case Instrument::Sampler: {
            samplePos = sampleStart;
            // 采样根音固定 60，按半音变速
            samplePitchRatio = std::pow(2.f, (float)(pitch - 60) / 12.f);
            amp.setup(0.001f + p[2] * 0.3f, pDecay(p[3]), 1.f, pRelease(0.4f), sr);
            amp.trigger();
            break;
        }
    }
}

bool Voice::render(float* outL, float* outR, int n, const float* p, float sr) {
    if (!active) return false;

    // 试听音符的倒计时：到点就释放，不依赖传输位置。
    if (manualReleaseSamples > 0 && !released) {
        manualReleaseSamples -= n;
        if (manualReleaseSamples <= 0) {
            manualReleaseSamples = 0;
            release();
        }
    }

    const float velGain = velocity;
    const float invSr = 1.f / sr;

    switch (inst) {
        case Instrument::Synth: {
            const int w1 = (int)fmRatio & 15;
            const int w2 = ((int)fmRatio >> 4) & 15;
            const float noiseAmt = p[3] * 0.5f;
            const float width = 0.35f + p[11] * 0.65f;
            const float envDepth = auxEnv.level;
            const float baseCut = pCutoff(p[8]);
            const float q = pQ(p[9]);

            for (int i = 0; i < n; i++) {
                float a = amp.process();
                float fe = filtEnv.process();
                if (!amp.isActive() && released) { active = false; break; }

                float s1 = osc1.tick(w1);
                float s2 = osc2.tick(w2);
                float sig = 0.5f * (s1 + s2) + noise.next() * noiseAmt;

                // 滤波包络抬升截止频率，最多 6 个八度
                float cut = baseCut * std::pow(2.f, fe * envDepth * 6.f);
                lpf.set(clampf(cut, 40.f, sr * 0.45f), q, sr);
                sig = lpf.process(sig, 0);
                sig = hpf.process(sig, 2);

                sig *= a * velGain;
                outL[i] += sig * (0.5f + 0.5f * width);
                outR[i] += sig * (0.5f + 0.5f * width);
            }
            break;
        }

        case Instrument::Bass: {
            const int w1 = (int)fmRatio & 15;
            const int w2 = ((int)fmRatio >> 4) & 15;
            const float envDepth = auxEnv.level;
            const float baseCut = pCutoff(p[8]);
            const float q = pQ(p[9]);
            for (int i = 0; i < n; i++) {
                float a = amp.process();
                float fe = filtEnv.process();
                if (!amp.isActive() && released) { active = false; break; }

                float s = 0.65f * osc1.tick(w1) + 0.35f * osc2.tick(w2);
                float cut = baseCut * std::pow(2.f, fe * envDepth * 5.f);
                lpf.set(clampf(cut, 30.f, sr * 0.45f), q, sr);
                s = lpf.process(s, 0);
                s = fastTanh(s * 1.4f) * 0.7f;                 // 贝斯要有一点推力
                s *= a * velGain;
                outL[i] += s;
                outR[i] += s;
            }
            break;
        }

        case Instrument::Drum: {
            const DrumType dt = (DrumType)(int)(auxEnv.level + 0.5f);
            const float drive = 1.f + p[4] * 6.f;
            const float noiseAmt = p[2];
            const float noiseDecayCoef = std::exp(-1.f / (0.008f * sr));
            float noiseEnv = 1.f;
            const float pitchDrop = (dt == Kick) ? 1.f : 0.f;
            const float targetInc = osc1.inc;

            for (int i = 0; i < n; i++) {
                float a = amp.process();
                if (!amp.isActive()) { active = false; break; }

                float body;
                switch (dt) {
                    case Kick: {
                        // 音高快速下滑 —— 这是底鼓的"冲击"感所在
                        if (pitchDrop && subInc > targetInc) {
                            subInc = targetInc + (subInc - targetInc) * std::exp(-1.f / (0.02f * sr));
                            osc1.inc = subInc;
                        } else {
                            osc1.inc = targetInc;
                        }
                        body = osc1.tick(Osc::Sine);
                        body += noise.next() * noiseAmt * 0.5f * noiseEnv;
                        noiseEnv *= noiseDecayCoef;
                        body = fastTanh(body * drive) * 0.85f;
                        break;
                    }
                    case Snare: {
                        float tone = osc1.tick(Osc::Tri) * 0.5f;
                        float nz = noise.next();
                        float hp = hpf.process(nz, 2);
                        body = tone * 0.4f + hp * 0.9f;
                        body = fastTanh(body * drive);
                        break;
                    }
                    case Clap: {
                        // 三次快速重触发模拟拍手的多指离散
                        float nz = noise.next();
                        float bp = lpf.process(nz, 1);
                        float cluster = std::fmod((float)i, 1400.f) / 1400.f;
                        float burst = (cluster < 0.06f || (cluster > 0.10f && cluster < 0.14f)) ? 1.f : 0.35f;
                        body = bp * 1.6f * burst;
                        break;
                    }
                    case HatClosed:
                    case HatOpen: {
                        float nz = noise.next();
                        float hp = hpf.process(nz, 2);
                        hp = hpf.process(hp, 2);
                        body = hp * 0.8f;
                        break;
                    }
                    case Tom: {
                        float s = osc1.tick(Osc::Sine);
                        s += noise.next() * noiseAmt * 0.08f;
                        body = fastTanh(s * drive * 0.8f);
                        break;
                    }
                    case Rim: {
                        float s = osc1.tick(Osc::Square) * 0.5f + noise.next() * 0.5f;
                        body = lpf.process(s, 1) * 1.2f;
                        break;
                    }
                    default: {
                        float nz = noise.next();
                        body = hpf.process(nz, 2) * 0.7f;
                        break;
                    }
                }

                body *= a * velGain;
                outL[i] += body;
                outR[i] += body;
            }
            break;
        }

        case Instrument::Bell: {
            const float idx = fmIndex;
            for (int i = 0; i < n; i++) {
                float a = amp.process();
                float m = auxEnv.process();
                if (!amp.isActive() && released) { active = false; break; }

                float modSig = std::sin((float)fmPhase * 6.28318530718f) * idx * m;
                float carrier = std::sin((float)osc1.phase * 6.28318530718f + modSig);
                osc1.phase += osc1.inc;
                if (osc1.phase >= 1.0) osc1.phase -= 1.0;
                fmPhase += fmInc;
                if (fmPhase >= 1.0) fmPhase -= 1.0;

                float s = lpf.process(carrier, 0) * a * velGain;
                outL[i] += s;
                outR[i] += s;
            }
            break;
        }

        case Instrument::EPiano: {
            const float idx = fmIndex;
            const float lfoHz = (float)subInc;
            const float lfoDepth = 0.15f + 0.35f * p[3];
            double lfo = (double)(pitch % 5) * 0.6;
            for (int i = 0; i < n; i++) {
                float a = amp.process();
                float ham = auxEnv.process();
                if (!amp.isActive() && released) { active = false; break; }

                lfo += (double)lfoHz * (double)invSr;
                if (lfo >= 1.0) lfo -= 1.0;

                float modSig = std::sin((float)fmPhase * 6.28318530718f) * (idx * a + 0.2f);
                float carrier = std::sin((float)osc1.phase * 6.28318530718f + modSig);
                osc1.phase += osc1.inc;
                if (osc1.phase >= 1.0) osc1.phase -= 1.0;
                fmPhase += fmInc;
                if (fmPhase >= 1.0) fmPhase -= 1.0;

                float s = carrier * 0.85f + ham * noise.next() * 0.12f;
                s = lpf.process(s, 0) * a * velGain;

                // 颤音：左右反相
                float trem = 1.f - lfoDepth * 0.5f * (1.f - std::cos((float)lfo * kTwoPi));
                outL[i] += s * trem;
                outR[i] += s * (2.f - trem);
            }
            break;
        }

        // ---------------------------------------------------- Karplus-Strong 拨弦
        case Instrument::Pluck: {
            const float damp = wgDamp;
            // 波导反馈里的低通：这是"泛音先衰减、基频后衰减"的物理来源。
            const float fb = 0.996f + p[1] * 0.0039f;    // 反馈量 0.996..0.9999

            // Karplus-Strong 的正确写法：
            //   输出 = 环内当前样本
            //   写回 = (当前样本 + 前一输出) 的一阶低通 × 反馈量
            // 环长恰好是 wgLen 个样本，所以周期 = wgLen / sr，
            // 基频 = sr / wgLen。之前的实现把读与写落在同一个格子上，
            // 延迟退化成 1 个样本 —— 于是能量都在高频，
            // 测出来 220 Hz 比 440 Hz 还弱。
            for (int i = 0; i < n; i++) {
                float a = amp.process();
                if (!amp.isActive() && released) { active = false; break; }

                // 分数延迟读取：在环长 len + frac 上取样本。
                // 直接取整数格点会产生系统性音准误差。
                int i1 = wgPos;
                int i0 = i1 - 1;
                if (i0 < 0) i0 += wgLen;
                const float cur = wg[i1] * (1.f - wgFrac) + wg[i0] * wgFrac;

                // 一阶低通决定"泛音比基频衰减得快多少"：damp 越大越亮
                const float filtered = cur * (1.f - damp * 0.5f)
                                     + wgLast * (damp * 0.5f);
                wgLast = cur;
                wg[wgPos] = filtered * fb;
                wgPos++;
                if (wgPos >= wgLen) wgPos = 0;

                outL[i] += cur * a * velGain * 1.8f;
                outR[i] += cur * a * velGain * 1.8f;
            }
            break;
        }

        // ---------------------------------------------------- 弦乐合奏
        case Instrument::Strings: {
            const float vibHz = (float)subInc;
            const float vibDepth = 0.002f + p[3] * 0.010f;   // ±0.2%..1.2% 音高
            const float envDepth = auxEnv.level;
            const float baseCut = pCutoff(p[8]);
            const float q = pQ(p[9]);
            double lfo = (double)(pitch % 7) * 0.14;
            const double invSr = 1.0 / (double)sr;

            for (int i = 0; i < n; i++) {
                float a = amp.process();
                if (!amp.isActive() && released) { active = false; break; }

                lfo += (double)vibHz * invSr;
                if (lfo >= 1.0) lfo -= 1.0;
                const double vib = 1.0 + (double)vibDepth * std::sin(lfo * 6.283185307179586);

                // 三路锯齿，PolyBLEP 抗混叠
                double sum = 0;
                for (int v = 0; v < 3; v++) {
                    const double ph = ensPhase[v];
                    double saw = 2.0 * ph - 1.0;
                    // 简化的 blep：此处用一阶修正即可，三路叠加后混叠不可闻
                    const double dt = ensInc[v] * vib;
                    if (ph < dt) { const double t = ph / std::max(1e-9, dt); saw -= t + t - t * t - 1.0; }
                    else if (ph > 1.0 - dt) { const double t = (ph - 1.0) / std::max(1e-9, dt); saw -= t * t + t + t + 1.0; }
                    sum += saw;
                    ensPhase[v] += ensInc[v] * vib;
                    if (ensPhase[v] >= 1.0) ensPhase[v] -= 1.0;
                }
                float sig = (float)(sum / 3.0);

                const float cut = baseCut * std::pow(2.f, a * envDepth * 2.f);
                lpf.set(clampf(cut, 120.f, sr * 0.42f), q, sr);
                sig = lpf.process(sig, 0);
                sig = hpf.process(sig, 2);
                sig *= a * velGain * 0.7f;

                // 弦乐略有左右展开：三路失谐本身就有立体感
                outL[i] += sig * (1.f + (float)std::sin(lfo * 6.283185307179586) * 0.12f);
                outR[i] += sig * (1.f - (float)std::sin(lfo * 6.283185307179586) * 0.12f);
            }
            break;
        }

        // ---------------------------------------------------- 拉杆风琴
        case Instrument::Organ: {
            static const float kRatio[9] = {
                0.5f, 1.5f, 1.f, 2.f, 3.f, 4.f, 5.f, 6.f, 8.f
            };
            const float baseInc = (float)osc1.inc;
            const float percAmt = 0.35f;   // 打击泛音固定强度
            lpf.set(6000.f, 0.7f, sr);

            for (int i = 0; i < n; i++) {
                float a = amp.process();
                float perc = auxEnv.process();
                if (!amp.isActive() && released) { active = false; break; }

                // 9 根拉杆 = 9 个谐波正弦，逐个累加。
                // 这是加法合成最直白的做法，Hammond 就是这么发声的。
                float sig = 0.f;
                for (int d = 0; d < 9; d++) {
                    const float w = drawbars[d];
                    if (w < 0.01f) continue;
                    const float ratio = kRatio[d];
                    // 第 0 根是亚谐波（0.5x），单独处理相位推进
                    sig += w * std::sin((float)(osc1.phase * kRatio[d] * ratio) * kTwoPi);
                }
                // 一次相位推进，所有谐波都从同一个相位派生 —— 严格锁相，
                // 这正是风琴音色"干净但厚"的原因。
                osc1.phase += osc1.inc;
                if (osc1.phase >= 1.0) osc1.phase -= 1.0;

                // 打击泛音：只在起音瞬间有，给音头一点"咔"
                sig += perc * percAmt * std::sin((float)(osc1.phase * 3.0) * kTwoPi);

                sig = lpf.process(sig, 0) * a * velGain * 0.32f;
                outL[i] += sig;
                outR[i] += sig;
            }
            break;
        }

        case Instrument::Sampler: {
            // sampleBank 由 Engine 注入；这里只处理指针推进与包络
            extern const float* g_sampleBank;
            extern int g_sampleFrames;
            extern int g_sampleRate;
            extern int g_sampleRoot;
            (void)g_sampleRoot;
            for (int i = 0; i < n; i++) {
                float a = amp.process();
                if (!amp.isActive() && released) { active = false; break; }

                float s = 0.f;
                if (g_sampleBank && g_sampleFrames > 0) {
                    if (samplePos < g_sampleFrames) {
                        int i0 = samplePos;
                        int i1 = i0 + 1 < g_sampleFrames ? i0 + 1 : i0;
                        float frac = (float)samplePos - (float)i0;
                        s = lerpf(g_sampleBank[i0], g_sampleBank[i1], frac);
                    }
                    float step = samplePitchRatio * (float)g_sampleRate * invSr;
                    samplePos = (int32_t)((float)samplePos + step + 1e-6f);
                    if (sampleLoop && samplePos >= sampleEnd) samplePos = sampleStart;
                    else if (samplePos >= g_sampleFrames) { samplePos = g_sampleFrames; }
                }
                s *= a * velGain;
                outL[i] += s;
                outR[i] += s;
            }
            break;
        }
    }

    return active;
}

}  // namespace flow
