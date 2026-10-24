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

# 汇编兜底文件同样需要这个开关：它自己的汇编里如果生成 tl;dr 之外的
# 原子调用，一样会引用到没实现的符号。
set_source_files_properties(
    "${ENGINE_DIR}/src/atomic_shim.s"
    PROPERTIES COMPILE_OPTIONS "-mno-outline-atomics"
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
    # OpenSL ES 是 AAudio 的后备后端。少了它，AAudio 打不开的那些设备
    # 就彻底没有声音，而且链接期只在 CI 上暴露 —— 本地脚本单独链过一遍，
    # CMake 这份漏了，两边不一致。
    OpenSLES
    log
    m
)

# 去掉无用段，减小 so 体积
target_link_options(flowengine PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

# 链接完立刻查：__aarch64_* 原子符号不能是未定义的。
# 1.0.2 那次就是这个自检缺席 —— so 装得上，一 dlopen 就报
#   cannot locate symbol "__aarch64_swp1_acq_rel"
# 用 grep 查 readelf 输出，命中 UND 就构建失败。
find_program(LLVM_READELF NAMES llvm-readelf)
if(LLVM_READELF)
    add_custom_command(TARGET flowengine POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E echo "== 检查原子符号 =="
        COMMAND ${CMAKE_COMMAND}
            -DSO=$<TARGET_FILE:flowengine>
            -DREADELF=${LLVM_READELF}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/check_atomic.cmake"
        VERBATIM
        COMMENT "验证 libflowengine.so 没有未定义的原子符号"
    )
endif()
