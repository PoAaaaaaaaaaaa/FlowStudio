#!/usr/bin/env bash
# so 交付前检查。
# 教训：1.0.1 的 so 链接成功、签名有效、能装上，但 dlopen 报
#   cannot locate symbol "__aarch64_swp1_acq_rel"
# 因为链接时少了 libatomic / outline atomics 没关掉。
# 那次是用户装了才发现。这个脚本把它变成构建期硬失败。
set -euo pipefail

SO="${1:?用法: check_so.sh <libflowengine.so>}"
NDK="${ANDROID_NDK_HOME:-/opt/dl/android-ndk-r26d}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
STUB="$TC/sysroot/usr/lib/aarch64-linux-android/26"

# 本机 arch 可能与 NDK 预编译工具不同，需要就借 qemu
READELF=("$TC/bin/llvm-readelf")
if ! "${READELF[@]}" --version >/dev/null 2>&1; then
  if command -v qemu-x86_64-static >/dev/null 2>&1 && [ -d /opt/x86root ]; then
    READELF=(qemu-x86_64-static -L /opt/x86root /opt/x86root/lib64/ld-linux-x86-64.so.2
             --library-path /opt/x86root/lib/x86_64-linux-gnu:/opt/x86root/usr/lib/x86_64-linux-gnu:/opt/x86root/usr/lib/gcc/x86_64-linux-gnu/13
             "$TC/bin/llvm-readelf")
  else
    echo "无法运行 llvm-readelf"; exit 1
  fi
fi

SYMS="$("${READELF[@]}" --dyn-syms "$SO")"

# 1) 原子符号必须是已定义的（不能再是 UND）
UND_ATOMIC="$(echo "$SYMS" | grep " UND " | grep -o "__aarch64_[A-Za-z0-9_]*" || true)"
if [ -n "$UND_ATOMIC" ]; then
  echo "✗ 这些原子符号还是未定义，dlopen 会失败："
  echo "$UND_ATOMIC"
  exit 1
fi
echo "✓ 原子符号已解析"

# 2) emutls 也必须在 so 内定义（Android libc 不提供）
if echo "$SYMS" | grep " UND " | grep -q "__emutls_get_address"; then
  echo "✗ __emutls_get_address 未定义，dlopen 会失败"
  exit 1
fi
echo "✓ emutls 已内置"

# 3) 整体：所有 UND 符号必须能在系统 stub 或本 so 内找到
python3 - "$SO" "$STUB" <<'PYEOF'
import subprocess, os, sys, glob

so, stub = sys.argv[1], sys.argv[2]
tc = os.environ.get("TC")
NDK="/opt/dl/android-ndk-r26d"
TC=f"{NDK}/toolchains/llvm/prebuilt/linux-x86_64"
READELF=[f"{TC}/bin/llvm-readelf"]
if not os.access(READELF[0], os.X_OK):
    raise SystemExit("llvm-readelf 不可执行")
def run(args):
    try:
        return subprocess.run(READELF+args, capture_output=True, text=True, timeout=120).stdout
    except Exception:
        return None

out = run(["--dyn-syms", so])
if out is None:
    # 借 qemu
    base = ["qemu-x86_64-static","-L","/opt/x86root","/opt/x86root/lib64/ld-linux-x86-64.so.2",
            "--library-path","/opt/x86root/lib/x86_64-linux-gnu:/opt/x86root/usr/lib/x86_64-linux-gnu:/opt/x86root/usr/lib/gcc/x86_64-linux-gnu/13"]
    READELF = base + READELF
    out = subprocess.run(READELF+["--dyn-syms", so], capture_output=True, text=True).stdout

def split(out):
    und, defined = set(), set()
    for line in out.splitlines():
        p = line.split()
        if len(p) < 8: continue
        n = p[-1].split('@')[0]
        if " UND " in line: und.add(n)
        else: defined.add(n)
    return und, defined

und, defined = split(out)
provided = set()
for f in glob.glob(os.path.join(stub, "*.so")):
    o = subprocess.run(READELF+["--dyn-syms", f], capture_output=True, text=True).stdout
    _, d = split(o)
    provided |= d

missing = sorted(s for s in und if s not in provided and s not in defined)
if missing:
    print("✗ 这些符号在运行时解析不到：")
    for m in missing: print("   ", m)
    sys.exit(1)
print(f"✓ 全部 {len(und)} 个未定义符号都能解析（系统库 + 本 so）")
PYEOF
