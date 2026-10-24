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
  -I"$ROOT/engine/include" -I"$ROOT/app/src/main/cpp"
)

echo "[native] 编译 ${#SRC[@]} 个文件"
OBJS=()
for f in "${SRC[@]}"; do
  o="$OBJ/$(basename "${f%.cpp}").o"
  "${RUN[@]}" "$CLANG" "${CFLAGS[@]}" -c "$f" -o "$o"
  OBJS+=("$o")
done

echo "[native] 链接 libflowengine.so"
"${RUN[@]}" "$TC/bin/ld.lld" -shared \
  -o "$LIB/libflowengine.so" \
  "${OBJS[@]}" \
  -L"$TC/sysroot/usr/lib/$ABI/$API" \
  "$TC/sysroot/usr/lib/$ABI/libc++_static.a" \
  "$TC/sysroot/usr/lib/$ABI/libc++abi.a" \
  -landroid -laaudio -llog -lc -lm -ldl

ls -la "$LIB/libflowengine.so"
echo "[native] 完成"
