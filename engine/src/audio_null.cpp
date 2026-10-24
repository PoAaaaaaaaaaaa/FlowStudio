// FlowStudio —— 宿主平台占位后端。桌面端的单元测试与离线渲染不需要真实声卡。
#include "flow/engine.h"

namespace flow {

std::unique_ptr<Engine::AudioBackend> Engine::AudioBackend::create() {
    return nullptr;   // 宿主上没有设备；Android 版由 audio_android.cpp 覆盖
}

}  // namespace flow
