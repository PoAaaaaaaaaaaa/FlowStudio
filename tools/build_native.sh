#!/usr/bin/env bash
# 编译 C++ 引擎并链接成 libflowengine.so（arm64-v8a）。
# 本机（aarch64 + 无 NDK x86 执行环境）通过 qemu 仿真 NDK 的 x86_64 clang 完成交叉编译。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-/opt/dl/android-ndk-r26d}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
API=26
ABI=aarch64-linux-android
OUT="$ROOT/build"
OBJ="$OUT/obj"
LIB="$ROOT/build/jniLibs/arm64-v8a"

SRC=(
  "$ROOT/engine/src/voice.cpp"
  "$ROOT/engine/src/effect.cpp"
  "$ROOT/engine/src/engine.cpp"
  "$ROOT/engine/src/audio_android.cpp"
  "$ROOT/engine/src/emutls_shim.cpp"
  "$ROOT/app/src/main/cpp/jni_bridge.cpp"
)

mkdir -p "$OBJ" "$LIB"

# 如果本机 arch 与 NDK 预编译 clang 相同，直接跑；否则用 qemu + x86_64 sysroot。
CLANG="$TC/bin/clang++"
RUN=()
if "$CLANG" --version >/dev/null 2>&1; then
  RUN=()
elif command -v qemu-x86_64-static >/dev/null 2>&1 && [ -d /opt/x86root ]; then
  RUN=(qemu-x86_64-static -L /opt/x86root /opt/x86root/lib64/ld-linux-x86-64.so.2
       --library-path /opt/x86root/lib/x86_64-linux-gnu:/opt/x86root/usr/lib/x86_64-linux-gnu:/opt/x86root/usr/lib/gcc/x86_64-linux-gnu/13)
else
  echo "找不到可执行的 clang++，也没有 qemu + /opt/x86root 可选" >&2
  exit 1
fi

CFLAGS=(
  --target=${ABI}26
  --sysroot="$TC/sysroot"
  -resource-dir="$TC/lib/clang/17"
  -std=c++17 -O2 -fPIC -fvisibility=hidden
  -ffunction-sections -fdata-sections
  -mno-outline-atomics
  -I"$ROOT/engine/include" -I"$ROOT/app/src/main/cpp"
)

echo "[native] 编译 ${#SRC[@]} 个文件"
OBJS=()
for f in "${SRC[@]}"; do
  o="$OBJ/$(basename "${f%.cpp}").o"
  "${RUN[@]}" "$CLANG" "${CFLAGS[@]}" -c "$f" -o "$o"
  OBJS+=("$o")
done

# 原子操作兜底汇编：必须单独用 clang 汇编，不能直接丢给 ld.lld
SHIM_SRC="$ROOT/engine/src/atomic_shim.s"
SHIM_OBJ="$OBJ/atomic_shim.o"
"${RUN[@]}" "$CLANG" --target=${ABI}26 --sysroot="$TC/sysroot" \
  -resource-dir="$TC/lib/clang/17" -c "$SHIM_SRC" -o "$SHIM_OBJ"
OBJS+=("$SHIM_OBJ")
echo "   汇编兜底已编译"

echo "[native] 链接 libflowengine.so"
# libatomic.a 必须显式链进来：C++ 的 std::atomic 在 armv8.0 上会生成
# __aarch64_swp1_acq_rel / __aarch64_ldadd8_acq_rel 这类调用，
# 它们不在 libc 里。漏掉它，so 能编出来、能签名、能装，
# 但一 dlopen 就报 "cannot locate symbol __aarch64_swp1_acq_rel"，应用起不来。
CLANG_RT="$TC/lib/clang/17/lib/linux/aarch64"
"${RUN[@]}" "$TC/bin/ld.lld" -shared \
  -o "$LIB/libflowengine.so" \
  "${OBJS[@]}" \
  -L"$TC/sysroot/usr/lib/$ABI/$API" \
  "$TC/sysroot/usr/lib/$ABI/libc++_static.a" \
  "$TC/sysroot/usr/lib/$ABI/libc++abi.a" \
  "$CLANG_RT/libunwind.a" \
  -landroid -laaudio -lOpenSLES -llog -lc -lm -ldl

# 链接完立刻查：所有 __aarch64_* 原子符号必须已被消解，
# 否则这就是一个"装得上、跑不了"的包。
UNDEF_ATOMIC="$("${RUN[@]}" "$TC/bin/llvm-readelf" --dyn-syms "$LIB/libflowengine.so" 2>/dev/null \
  | grep " UND " | grep -o "__aarch64_[A-Za-z0-9_]*" || true)"
if [ -n "$UNDEF_ATOMIC" ]; then
  echo "链接失败：仍有未消解的原子符号（dlopen 会失败，应用起不来）："
  echo "$UNDEF_ATOMIC"
  exit 1
fi
echo "   原子符号全部消解"

ls -la "$LIB/libflowengine.so"
echo "[native] 完成"
