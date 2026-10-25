# FlowStudio

安卓上的移动端音乐工作站。自写的 C++17 实时音频引擎 + 全自绘 Kotlin 界面，零第三方依赖。

APK 约 1 MB（含原生引擎）。装到 arm64 手机上直接能编曲。

## 怎么跑

```bash
./tools/build_native.sh   # 交叉编译 C++ 引擎 -> libflowengine.so
./tools/build_apk.sh      # Kotlin -> dex -> aapt2 -> zipalign -> apksigner
adb install build/FlowStudio-1.0.0.apk
```

或者推到 GitHub，Actions 会自动出包（见 `.github/workflows/build.yml`）。

## 1.15 更新

- 播放/暂停、停止、后退、快进移到底部拇指区；顶部只留页签与工程信息。
- 新增后退/快进：轻点跳一拍，长按连续跳小节（桥接新增 `/transport/nudge`）。
- `/transport/seek` 同时接受 query 与 JSON body，并广播 transport 事件。
- 插件新增 `flowstudio_dj`：过渡方案、搓碟手法、延迟时值、混音链路与频段分工。
- 过渡方案 5 种（硬切/换低音/滤波扫频/延迟淡出/倒转），含小节安排与滤波自动化。
- 搓碟 5 种手法（baby/chirp/flare/transformer/tear）可直接转成采样器音符。
- 底栏改为浮起圆角卡片，播放时有移动光带；页签选中态换成滑动指示条。

## 1.14 更新

- 新增本地桥接协议 v2：`/capabilities` 让插件按实际能力调用，不再按版本号猜。
- 新增 `/batch` 原子批量接口：一次提交最多 200 条编辑，任一条非法就整批拒绝。
- 新增片段结构接口：`/clips`、`/clips/arrange`、`/clips/{id}`，AI 可一次排好整首歌。
- 新增样式管理（创建/改名/改长/删除）、轨道重排、自动化写入与删除。
- 新增 `/undo`：AI 每次写入前自动留快照，手机上可一键撤销。
- 新增 `/selection`：AI 改完后可以把界面切到对应轨道、样式或位置。
- 插件写入后手机界面立即刷新，并在底部显示"AI 已…· 点此撤销"。
- 轨道增加 `group` 编组标记；工程增加调性/音阶提示与可配置拍号字段。
- 新增 11 种曲风起步（House/Techno/Trap/Lo-fi/Rock/Metal/Jazz/Reggae/Latin/Ambient/Pop），
  一次追加对应编制、鼓型、和声与骨架样式，不覆盖现有内容。
- 曲风鼓型从 6 种扩到 13 种（新增 rock/metal/jazz/reggae/latin/afrobeat/trap/drumAndBass）。

## 1.13 更新

- 界面统一为石墨黑与琥珀主色，所有页面加入卡片层级、渐变背景和按压反馈。
- 走带条和页签自适应屏幕宽度，窄屏将次要控制收进“更多”。
- 页签顺序修正为：机架、步进、卷帘、编排、混音、自动化。
- 钢琴卷帘新增吸附、量化、删除、缩放工具栏；编排页支持双指平移与反向划循环。
- 通道机架支持长按管理通道：音色、重命名、删除。
- BPM 支持拖动与精确输入；旋钮支持长按精调、双击复位。
- 混音台支持横向滑动查看多通道，参数区可横向滚动。
- 自动保存保持安静，手动保存有明确反馈，切后台也会立即保存。

## 架构

引擎和界面彻底分离，这是必须的：音频线程一次 GC 停顿就是一声爆音。

```
engine/                      纯 C++17，不依赖 Android，桌面上能直接跑单测
  include/flow/dsp.h         PolyBLEP 振荡器 / TPT 状态变量滤波器 / 双二阶 EQ / 分数延迟线
  include/flow/voice.h       发声单元（六种乐器）
  include/flow/effect.h      10 类插入效果
  include/flow/engine.h      调度、混音总线、母带、离线渲染
  src/voice.cpp              合成内核
  src/effect.cpp             效果器实现
  src/engine.cpp             传输 / 自动化 / 总线
  src/audio_android.cpp      AAudio 后端（低延迟路径，回调零分配）
  src/audio_null.cpp         宿主占位后端
  test/host_test.cpp         桌面自测，验证出声/音高/爆音/NaN/声像/导出长度

app/src/main/java/com/volt/flowstudio/
  audio/NativeEngine.kt      JNI 门面
  model/                     工程 / 轨道 / 样式 / 音符 / 效果 / 自动化
  session/StudioSession.kt   工程状态 -> 引擎调用，存盘读盘，导出 WAV
  ui/                        全部自绘：步进器、钢琴卷帘、播放列表、混音台、自动化、键盘
  MainActivity.kt            五个页签的主界面
```

## 引擎实测

```
$ ./engine/build-host/host_test

[ ok ]  合成器 Synth：有声 peak=0.909 rms=0.1033
[ ok ]  贝斯 Bass：有声 peak=0.380 rms=0.0475
[ ok ]  鼓组 Kick：有声 peak=0.603 rms=0.0740
[ ok ]  钟琴 Bell：有声 peak=0.686 rms=0.1463
[ ok ]  电钢 EPiano：有声 peak=0.515 rms=0.0588
[ ok ]  音高 A4：440Hz 能量 0.11130 远大于 880Hz 0.00000
[ ok ]  多轨混音：peak=0.991 未超 1.0
[ ok ]  效果 Reverb：输出有限 rms=0.3073
[ ok ]  声像左：L=1.000 应大于 R=0.287
[ ok ]  导出长度：4.00s（音乐 2s + 拖尾）

全部通过  失败 0 项
```

## 功能

**编排**
- 步进器：16 分网格，拖动刷格子
- 钢琴卷帘：画、拖、拉长、改力度、量化、框选删除
- 播放列表：片段拖动、跨轨移动、拉长、循环区间
- 样式（Pattern）制，跟 FL 一样解耦轨道与样式

**音源**（全部实时合成，不含采样文件）
- 3 振荡器减法合成器（PolyBLEP 抗混叠）
- 减法贝斯
- 鼓组：8 类打击，底鼓带音高下滑
- FM 钟琴 / FM 电钢（带立体声颤音）
- 采样器（WAV 导入）

**效果**（每轨 4 个插入槽 + 2 条发送总线）
滤波、节拍同步延迟、Freeverb 混响、合唱、失真、压缩、6 段均衡、降位、移相

**混音**
- 每轨：推子、等功率声像、静音、独奏、电平表
- 母带：音量、前馈限幅、软削波、增益
- 自动化：包络曲线，编辑所见即所得

**其它**
- 后台播放（前台服务）
- 导出 16-bit 立体声 WAV，走系统分享
- 工程存档 JSON，自动保存

## 已知边界

- 只打了 `arm64-v8a`。要给 x86_64 模拟器跑，改 `build.gradle.kts` 的 `abiFilters`。
- 采样器只解 8/16/24/32-bit PCM 与 32-bit float 的 WAV。
- 自动化曲线是线性插值，不是贝塞尔。
- 录音（麦克风输入）留了权限和接口，尚未接线。
