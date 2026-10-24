// FlowStudio —— 音符与 Pattern 的 C++ 侧镜像。Kotlin 通过 JNI 整块推下来，音频线程只读。
#pragma once

#include <cstdint>
#include <vector>
#include <algorithm>

namespace flow {

// PPQ 固定 480，与 Kotlin 侧 Note.PPQ 一致。16 分音符 = 120 tick。
constexpr int kPPQ = 480;
constexpr int kStep = kPPQ / 4;
constexpr int kBar = kPPQ * 4;

struct Note {
    int32_t pitch = 60;
    int32_t start = 0;
    int32_t length = kStep;
    int32_t velocity = 100;
    int32_t end() const { return start + length; }
};

// 播放列表上一段区间内的所有音符，按时间预先拍平。
// 音频线程不做任何查找，只推进索引 —— 这是低延迟的关键。
struct FlatNote {
    int32_t startTick;
    int32_t endTick;
    int32_t pitch;
    int32_t velocity;
    int16_t track;
    int16_t pattern;   // 用于调试与界面高亮
};

// 一次性的播放快照：把整首歌拍平成按 startTick 排序的事件表。
struct Schedule {
    std::vector<FlatNote> notes;
    int32_t totalTicks = 0;

    void sort() {
        std::stable_sort(notes.begin(), notes.end(),
            [](const FlatNote& a, const FlatNote& b) { return a.startTick < b.startTick; });
    }
};

}  // namespace flow
