# FlowStudio 原生引擎构建脚本。
# 引擎源码放在仓库根的 engine/ 下，与 Kotlin 侧完全解耦：
# 同一份 C++ 在桌面用 g++ 跑单元测试，在 Android 用 NDK 交叉编译成 so。
cmake_minimum_required(VERSION 3.22.1)
project(flowengine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# app/src/main/cpp 的上一级三级就是仓库根
set(FLOW_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(ENGINE_DIR "${FLOW_ROOT}/engine")

if(NOT EXISTS "${ENGINE_DIR}/src/engine.cpp")
    message(FATAL_ERROR "找不到引擎源码：${ENGINE_DIR}")
endif()

add_library(flowengine SHARED
    "${ENGINE_DIR}/src/voice.cpp"
    "${ENGINE_DIR}/src/effect.cpp"
    "${ENGINE_DIR}/src/engine.cpp"
    "${ENGINE_DIR}/src/audio_android.cpp"
    "${ENGINE_DIR}/src/emutls_shim.cpp"
    "${ENGINE_DIR}/src/atomic_shim.s"
    "${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp"
)

target_include_directories(flowengine PRIVATE
    "${ENGINE_DIR}/include"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_compile_options(flowengine PRIVATE
    -O2
    -fvisibility=hidden
    # 关掉 outline atomics：否则 armv8.0 上会生成对
    # __aarch64_swp1_acq_rel 等符号的调用，NDK 没有它们的实现，
    # 结果就是 so 装得上但 dlopen 失败。
    -mno-outline-atomics
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
    -Wno-unused-parameter
)

target_link_libraries(flowengine
    android
    aaudio
    log
    m
)

# 去掉无用段，减小 so 体积
target_link_options(flowengine PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)
