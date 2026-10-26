#!/usr/bin/env node
/**
 * 钢琴引擎命令行。
 *
 * 用法：
 *   node cli.mjs score.json out.wav [--model grand|upright|rhodes] [--bpm 120]
 *                                  [--velocity 1.0] [--no-pedal]
 *
 * score.json 可以直接是 perform.js 的输出（含 notes），
 * 也可以手写成 {bpm, notes:[[pitch,startStep,lenStep,vel],...]}。
 */
import { readFileSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { renderScore, writeWav, PIANO_MODELS } from './engine.mjs'

function arg(name, def) {
  const i = process.argv.indexOf('--' + name)
  if (i < 0) return def
  const v = process.argv[i + 1]
  return v && !v.startsWith('--') ? v : true
}

const scorePath = process.argv[2]
const outPath = process.argv[3]

if (!scorePath || !outPath) {
  console.error('用法：node cli.mjs <score.json> <out.wav> [--model grand] [--bpm 120] [--velocity 1] [--no-pedal]')
  console.error('可用琴型：' + Object.entries(PIANO_MODELS).map(([k, v]) => `${k}(${v.cn})`).join(' / '))
  process.exit(2)
}
if (!existsSync(scorePath)) {
  console.error('找不到乐谱文件：' + scorePath)
  process.exit(2)
}

let score
try {
  score = JSON.parse(readFileSync(scorePath, 'utf8'))
} catch (e) {
  console.error('乐谱不是合法 JSON：' + e.message)
  process.exit(2)
}

const model = String(arg('model', score.model || 'grand'))
if (!PIANO_MODELS[model]) {
  console.error(`不认识的琴型 ${model}，可选：${Object.keys(PIANO_MODELS).join(' / ')}`)
  process.exit(2)
}

const t0 = Date.now()
let result
try {
  result = renderScore(score, {
    model,
    bpm: arg('bpm', undefined),
    velocity: arg('velocity', undefined),
    pedal: arg('no-pedal', false) ? false : undefined,
  })
} catch (e) {
  console.error('渲染失败：' + e.message)
  process.exit(1)
}

const out = resolve(outPath)
mkdirSync(dirname(out), { recursive: true })
writeWav(out, result.data)

const ms = Date.now() - t0
const kb = Math.round((result.data.length * 2 + 44) / 1024)
console.log(JSON.stringify({
  ok: true,
  out,
  model: result.model,
  modelCn: result.modelCn,
  noteCount: result.noteCount,
  durationSec: +result.durationSec.toFixed(2),
  sampleRate: result.sampleRate,
  sizeKb: kb,
  renderMs: ms,
  realtimeRatio: +(result.durationSec / (ms / 1000)).toFixed(1),
}, null, 2))
