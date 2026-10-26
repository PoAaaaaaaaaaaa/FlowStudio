#!/usr/bin/env node
/**
 * FlowStudio 钢琴引擎：把乐谱渲染成真实音频。
 *
 * 它不是合成器的"一个音色预设"，而是一个能出成品的渲染器：
 * 输入一段乐谱（和弦进行 + 演奏型 + 情绪），输出一个 WAV 文件，
 * 之后用 flowstudio_import_audio 就能把它作为音频片段放进工程。
 *
 * 这样 AI 不只能"写音符让软件合成"，还能"弹出来录下来"——
 * 音色由引擎决定，不依赖手机上的实时合成器，导出的东西在任何播放器里都一样。
 *
 * ---------------------------------------------------------------- 合成原理
 *
 * 模态加法合成（modal additive synthesis），这是物理建模钢琴的经典做法：
 *
 *   1. 一根有刚度的弦，它的分音不是严格整数倍，而是
 *        f_n = n·f0·√(1 + B·n²)
 *      B 是弦刚度系数。低音弦长而粗，B 大（音色更"浑"）；
 *      高音弦短而细，B 小（更接近纯音）。这个非谐性是钢琴区别于
 *      电子琴的第一特征，没有它听起来就是 organ 不是 piano。
 *
 *   2. 每个分音有独立的幅度与衰减：
 *        幅度 ~ n^-1.15 × 击弦位置因子
 *        衰减 ~ exp(-(a + b·n))，高次分音衰减远快于基频
 *      这两条决定了钢琴"起音明亮、迅速变暖"的特性。
 *
 *   3. 击弦位置：钢琴的槌子敲在弦长的 1/7 处，这会在第 7 次谐波
 *      附近产生频谱凹陷。人耳对这个凹陷极其敏感——没有它，
 *      听起来像风琴。
 *
 *   4. 同音多弦：中音区每个音有 2-3 根弦，调律时故意留极小的失谐
 *      （约 0.3-0.8 音分），产生缓慢的拍频。这是钢琴"活"的来源，
 *      也是采样钢琴很难做到自然的地方。
 *
 *   5. 击弦噪声：槌子接触弦的 5-20ms 里有宽带噪声，这是"咔"的质感。
 *
 *   6. 余音与延音踏板：松键后余音不是立刻消失，而是按音高相关的
 *      速率衰减。延音踏板（sustain）让所有音的余音继续叠加。
 *
 * 全部离线渲染，不依赖任何采样文件，输出 16-bit PCM WAV。
 */

import { writeFileSync } from 'node:fs'

const SR = 44100;

// ------------------------------------------------------------------ 乐器模型

/** 钢琴型号参数。不同琴的差异主要在这几个数上。 */
const MODELS = {
  // 三角大钢琴：音域宽、余音长、非谐性中等
  grand: {
    cn: '三角钢琴',
    inharmonicity: 3.0e-4,
    beating: 0.00045,       // 双弦失谐量（相对频率）
    strikePos: 7,           // 击弦点 1/7
    brightBase: 0.22,       // 高次分音基础衰减
    brightSlope: 0.055,
    sustainMul: 1.0,
    hammerNoise: 0.06,
    bodyResonance: 0.12,    // 音板共鸣
  },
  // 立式钢琴：余音短、中频更厚、非谐性略大
  upright: {
    cn: '立式钢琴',
    inharmonicity: 4.2e-4,
    beating: 0.0006,
    strikePos: 6.2,
    brightBase: 0.30,
    brightSlope: 0.075,
    sustainMul: 0.72,
    hammerNoise: 0.09,
    bodyResonance: 0.16,
  },
  // 电钢琴（Rhodes 风格）：只有几个分音、无击弦噪声、带颤音
  rhodes: {
    cn: '电钢琴',
    inharmonicity: 8.0e-5,
    beating: 0.0002,
    strikePos: 12,
    brightBase: 0.42,
    brightSlope: 0.10,
    sustainMul: 1.15,
    hammerNoise: 0.015,
    bodyResonance: 0.05,
    fmRatio: 2.0,
    fmIndex: 1.4,
  },
}

// ------------------------------------------------------------------ 单音渲染

/**
 * 渲染一个音，返回 { data, len }。
 *
 * @param {object} model 钢琴型号参数
 * @param {number} midi 音高
 * @param {number} velocity 0..1
 * @param {number} durSec 按住多久（秒）
 * @param {boolean} pedal 是否踩延音踏板（余音是否被保留）
 * @param {number} damping 0..1，松键后的额外衰减（踏板松开时用）
 */
export function renderNote(model, midi, velocity, durSec, pedal, damping = 0.35) {
  const f0 = 440 * Math.pow(2, (midi - 69) / 12)

  // 力度曲线：真钢琴的力度到响度是非线性的，弱奏暗、强奏亮
  const vel = Math.pow(Math.max(0.01, Math.min(1, velocity)), 1.35)
  // 强奏时高次分音更多 —— 这是"亮"的物理来源，不是简单调音量
  const brightness = 0.55 + 0.85 * vel

  const B = model.inharmonicity
  const maxPartials = Math.min(28, Math.floor((SR * 0.45) / f0))
  const parts = []

  for (let n = 1; n <= maxPartials; n++) {
    const fn = f0 * n * Math.sqrt(1 + B * n * n)
    if (fn > SR * 0.45) break

    // 击弦位置凹陷
    const strike = Math.abs(Math.sin(Math.PI * n / model.strikePos))
    // 分音幅度：1/n^1.15 × 击弦因子 × 亮度增强
    let amp = Math.pow(n, -1.15) * strike
    // 高次分音在弱奏时被压得更狠
    if (n > 1) amp *= Math.pow(brightness, (n - 1) * 0.55)

    // 分音衰减率：高次分音衰减快得多
    // 低音弦的衰减整体更慢（f0 低 → 单位时间的振荡少 → 能量耗散慢）
    const pitchFactor = Math.pow(220 / f0, 0.42)
    const decayRate = (model.brightBase + model.brightSlope * n) * pitchFactor
    const decay = Math.exp(-decayRate * 0.32)

    parts.push({ fn, amp, decay })
  }

  // 余音长度：低音长、高音短
  const pitchDecay = Math.exp(-1.5 * (midi - 21) / 87)
  const tailSec = (0.45 + 1.35 * pitchDecay) * model.sustainMul
  const totalSec = durSec + (pedal ? tailSec : tailSec * 0.42)
  const len = Math.max(64, Math.floor(SR * totalSec))
  const out = new Float32Array(len)

  // ---- 加法合成 ----
  for (const p of parts) {
    // 两根弦（中高音区实际有 2-3 根）：微失谐产生拍频
    const inc1 = 2 * Math.PI * p.fn / SR
    const inc2 = 2 * Math.PI * p.fn * (1 + model.beating) / SR

    const ds1 = Math.sin(inc1), dc1 = Math.cos(inc1)
    const ds2 = Math.sin(inc2), dc2 = Math.cos(inc2)
    let sr1 = 0, cr1 = 1, sr2 = 0, cr2 = 1

    const a0 = p.amp * vel * 0.20
    let a1 = a0
    let a2 = a0 * 0.58
    // 每样本衰减系数
    const d1 = Math.pow(p.decay, 1 / SR)
    const d2 = Math.pow(p.decay * 0.988, 1 / SR)

    for (let i = 0; i < len; i++) {
      out[i] += sr1 * a1 + sr2 * a2
      // 复数旋转递推（比每样本调 Math.sin 快一个数量级）
      const ns1 = sr1 * dc1 + cr1 * ds1
      cr1 = cr1 * dc1 - sr1 * ds1
      sr1 = ns1
      const ns2 = sr2 * dc2 + cr2 * ds2
      cr2 = cr2 * dc2 - sr2 * ds2
      sr2 = ns2
      a1 *= d1
      a2 *= d2
    }
  }

  // ---- 电钢琴的 FM 层 ----
  if (model.fmRatio) {
    const carrier = f0
    const mod = f0 * model.fmRatio
    let pc = 0, pm = 0
    const ic = 2 * Math.PI * carrier / SR
    const im = 2 * Math.PI * mod / SR
    const idx = model.fmIndex * vel
    const drift = Math.pow(Math.exp(-0.9 * (300 / carrier) * 0.3), 1 / SR)
    let dr = 1
    for (let i = 0; i < len; i++) {
      const sig = Math.sin(pc + Math.sin(pm) * idx * dr)
      out[i] += sig * vel * 0.10
      pc += ic; pm += im
      if (pc > 6.28318) pc -= 6.28318
      if (pm > 6.28318) pm -= 6.28318
      dr *= drift
    }
  }

  // ---- 击弦噪声 ----
  const nlen = Math.min(len, Math.floor(SR * 0.035))
  if (model.hammerNoise > 0.001) {
    let seed = ((midi * 2654435761) ^ Math.floor(velocity * 1000)) >>> 0
    let prev = 0
    const noiseAmp = model.hammerNoise * vel
    for (let i = 0; i < nlen; i++) {
      seed ^= seed << 13; seed >>>= 0
      seed ^= seed >> 17
      seed ^= seed << 5; seed >>>= 0
      const nz = (seed / 4294967296) * 2 - 1
      const hp = nz - prev
      prev = nz
      const env = Math.exp(-i / (SR * 0.005)) * noiseAmp
      out[i] += hp * env
    }
  }

  // ---- 音板共鸣：一段很短的梳状延迟，让低中频更"厚" ----
  if (model.bodyResonance > 0.01) {
    const dSamples = Math.floor(SR * (0.7 / f0) * 0.5) + 3
    if (dSamples < len) {
      const g = model.bodyResonance
      for (let i = dSamples; i < len; i++) {
        out[i] += out[i - dSamples] * g
      }
    }
  }

  // ---- 包络 ----
  // 起音 1.5ms（真钢琴的槌击极快），然后按音高相关的曲线衰减
  const attackN = Math.floor(SR * 0.0015)
  const releaseStart = Math.floor(SR * durSec)
  const bodyDecay = Math.pow(0.55 + 0.45 * pitchDecay, 1 / SR)

  let body = 1
  for (let i = 0; i < len; i++) {
    let env = body
    if (i < attackN) env *= i / attackN

    // 松键后额外衰减（踏板踩着时几乎不衰减）
    if (i > releaseStart) {
      const afterSec = (i - releaseStart) / SR
      const rel = pedal ? 0.08 : (0.5 + damping * 2.2)
      env *= Math.exp(-rel * afterSec)
    }
    out[i] *= env
    body *= bodyDecay
  }

  return { data: out, len, tailSec }
}

// ------------------------------------------------------------------ 乐谱 -> 音频

/**
 * 乐谱结构：
 * {
 *   bpm: 120,
 *   timeSig: [4, 4],
 *   velocity: 1.0,          // 整体力度
 *   model: 'grand',
 *   pedal: true,            // 延音踏板
 *   notes: [[pitch, startStep, lenStep, velocity], ...]   // 16分音符格
 * }
 *
 * 也接受 flowstudio 的样式格式（notes 是 {轨道: [...]} 的对象），
 * 会自动取所有轨道合并 —— 钢琴一件乐器本来就能同时弹多个声部。
 */
export function renderScore(score, opts = {}) {
  const modelKey = opts.model || score.model || 'grand'
  const model = MODELS[modelKey]
  if (!model) {
    throw new Error(`不认识的钢琴型号 ${modelKey}，可选：${Object.keys(MODELS).join(' / ')}`)
  }

  const bpm = Math.max(20, Math.min(300, Number(opts.bpm || score.bpm || 120)))
  const stepsPerSec = (bpm / 60) * 4          // 每秒多少个 16 分音符
  const velScale = Math.max(0.1, Math.min(1.6, Number(opts.velocity ?? score.velocity ?? 1)))
  const pedal = opts.pedal ?? score.pedal ?? true

  // 收集音符（支持扁平数组与按轨道分组两种形状）
  let list = []
  const raw = score.notes
  if (Array.isArray(raw)) {
    list = raw
  } else if (raw && typeof raw === 'object') {
    for (const arr of Object.values(raw)) {
      if (Array.isArray(arr)) list = list.concat(arr)
    }
  }
  if (!list.length) throw new Error('乐谱里没有音符')

  // 解析成 {midi, startSec, durSec, vel}
  const evts = []
  let maxEndStep = 0
  for (const n of list) {
    let pitch, start, len, vel
    if (Array.isArray(n)) {
      [pitch, start, len, vel] = n
    } else if (n && typeof n === 'object') {
      pitch = n.pitch ?? n.note
      start = n.start ?? n.step ?? 0
      len = n.length ?? n.len ?? 1
      vel = n.velocity ?? n.vel ?? 100
    } else continue

    if (!Number.isFinite(pitch) || !Number.isFinite(start)) continue
    const startSec = start / stepsPerSec
    const durSec = Math.max(0.05, (Number(len) || 1) / stepsPerSec)
    const v = Math.max(0.02, Math.min(1, (Number(vel) || 100) / 127)) * velScale
    evts.push({ midi: Math.round(pitch), startSec, durSec, vel: v })
    maxEndStep = Math.max(maxEndStep, start + (Number(len) || 1))
  }
  if (!evts.length) throw new Error('乐谱里没有可解析的音符')

  const totalSec = maxEndStep / stepsPerSec
  // 总长度 = 最后一音的结束 + 最长余音
  const longestTail = 2.2 * model.sustainMul
  const outLen = Math.floor(SR * (totalSec + longestTail))
  const master = new Float32Array(outLen)

  // 逐个音渲染并叠加。单音渲染很快（约 30ms），一首 8 小节平均 100 个音
  // 全部串行也就 3 秒左右 —— 比启动一个音频插件还快，不值得做多进程。
  let rendered = 0
  for (const e of evts) {
    const { data } = renderNote(model, e.midi, e.vel, e.durSec, pedal)
    const off = Math.floor(e.startSec * SR)
    const n = Math.min(data.length, outLen - off)
    for (let i = 0; i < n; i++) master[off + i] += data[i]
    rendered++
  }

  // ---- 母线处理：软限幅 + 归一 ----
  // 钢琴多个音叠加很容易超过 1.0，直接截断会爆音。
  // 用 tanh 软饱和，只在接近满刻度时压缩，正常段落完全透明。
  let peak = 0
  for (let i = 0; i < outLen; i++) {
    const v = master[i]
    if (Math.abs(v) > peak) peak = Math.abs(v)
  }

  const drive = peak > 0.95 ? (1 / peak) * 0.96 : 1
  for (let i = 0; i < outLen; i++) {
    let v = master[i] * drive
    // tanh 近似软限幅
    if (v > 0.7 || v < -0.7) {
      const sign = v < 0 ? -1 : 1
      const a = Math.abs(v)
      v = sign * (0.7 + (a - 0.7) / (1 + (a - 0.7) * 2.2))
    }
    master[i] = v
  }

  return {
    data: master,
    sampleRate: SR,
    durationSec: outLen / SR,
    noteCount: rendered,
    model: modelKey,
    modelCn: model.cn,
    bpm,
    peak: peak,
  }
}

// ------------------------------------------------------------------ WAV 输出

/** 写成 16-bit PCM 单声道 WAV。 */
export function writeWav(path, data, sampleRate = SR) {
  const n = data.length
  const buf = Buffer.alloc(44 + n * 2)
  buf.write('RIFF', 0)
  buf.writeUInt32LE(36 + n * 2, 4)
  buf.write('WAVE', 8)
  buf.write('fmt ', 12)
  buf.writeUInt32LE(16, 16)
  buf.writeUInt16LE(1, 20)          // PCM
  buf.writeUInt16LE(1, 22)          // 单声道
  buf.writeUInt32LE(sampleRate, 24)
  buf.writeUInt32LE(sampleRate * 2, 28)
  buf.writeUInt16LE(2, 32)
  buf.writeUInt16LE(16, 34)
  buf.write('data', 36)
  buf.writeUInt32LE(n * 2, 40)

  let off = 44
  for (let i = 0; i < n; i++) {
    let v = data[i]
    if (v > 1) v = 1
    if (v < -1) v = -1
    buf.writeInt16LE(Math.round(v * 32767), off)
    off += 2
  }
  writeFileSync(path, buf)
  return path
}

export const PIANO_MODELS = MODELS
export { SR as SAMPLE_RATE }
