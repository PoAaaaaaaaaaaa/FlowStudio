#!/usr/bin/env bash
# FlowStudio 本地打包：Kotlin -> dex -> aapt2 -> zipalign -> apksigner
# 不需要 Gradle。CI 上走 gradlew，这里走的路径用于本机验证。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
KT_HOME="${KT_HOME:-/tmp/ktest}"
AJ="${ANDROID_SDK_ROOT:-/opt/android-sdk}/platforms/android-34/android.jar"
BUILD_TOOLS=/usr/lib/android-sdk/build-tools/debian

MIN_SDK=26
API=34
VERSION_NAME="${VERSION_NAME:-1.11}"
VERSION_CODE="${VERSION_CODE:-12}"

echo "== 0. 原生库 =="
"$ROOT/tools/build_native.sh"
SO="$ROOT/build/jniLibs/arm64-v8a/libflowengine.so"

# so 的符号必须在构建期就验完。1.0.1 是装上才发现 dlopen 失败。
"$ROOT/tools/check_so.sh" "$SO"

echo "== 1. 资源编译 =="
rm -rf "$OUT/gen" "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/final"
mkdir -p "$OUT/gen" "$OUT/res" "$OUT/classes" "$OUT/dex" "$OUT/final"

aapt2 compile --dir "$APP/res" -o "$OUT/res/resources.zip"

echo "== 2. 链接资源 + 生成 R.java =="
aapt2 link \
  -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $API \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  -o "$OUT/final/base.apk" \
  "$OUT/res/resources.zip"

echo "== 3. Kotlin 编译 =="
KCP="$KT_HOME/kotlin-compiler-embeddable-1.9.24.jar:$KT_HOME/kotlin-stdlib-1.9.24.jar:$KT_HOME/kotlin-script-runtime-1.9.24.jar:$KT_HOME/kotlin-reflect-1.6.10.jar:$KT_HOME/kotlin-daemon-embeddable-1.9.24.jar:$KT_HOME/trove4j-1.0.20200330.jar:$KT_HOME/annotations-13.0.jar"
# 不能用 `| grep -v warning || true` 把错误吞掉 —— 那样编译失败后还会拿旧 class 继续打包。
# 先把输出存下来，只过滤 warning，出现 error 就立刻退出。
KT_LOG="$OUT/kotlin.log"
set +e
java -Xmx3g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$AJ:$OUT/gen:$KT_HOME/kotlin-stdlib-1.9.24.jar" \
  -jvm-target 17 \
  -d "$OUT/classes" \
  "$APP/java" > "$KT_LOG" 2>&1
KT_RC=$?
set -e
grep -E "^(app|.*): (error|warning):" "$KT_LOG" | grep -v "warning:" | head -20 || true
if [ $KT_RC -ne 0 ] || grep -q "error:" "$KT_LOG"; then
  echo "Kotlin 编译失败，详见 $KT_LOG"
  grep "error:" "$KT_LOG" | head -20
  exit 1
fi

echo "== 4. 编译生成的 R.java =="
mkdir -p "$OUT/classes"
if [ -f "$OUT/gen/com/volt/flowstudio/R.java" ]; then
  javac -nowarn -encoding UTF-8 --release 17 \
    -classpath "$AJ" -d "$OUT/classes" "$OUT/gen/com/volt/flowstudio/R.java"
fi

echo "== 5. dex =="
# 本机的 d8 是 Java 版，需要显式用 java 启动
SDK_DIR="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
D8_JAR=""
for c in "$SDK_DIR/build-tools/$API.0.0/lib/d8.jar" /usr/lib/android-sdk/build-tools/debian/lib/d8.jar; do
  [ -f "$c" ] && D8_JAR="$c" && break
done
if [ -z "$D8_JAR" ]; then echo "找不到 d8.jar"; exit 1; fi
# 关键：kotlin-stdlib 必须一起 dex 进去。
# 编译期它只在 classpath 上，d8 不会自动跟随依赖，
# 漏掉就会在运行时抛 NoClassDefFoundError(kotlin/SynchronizedLazyImpl) —— 正是 1.0.0 闪退的原因。
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
STDLIB="$KT_HOME/kotlin-stdlib-1.9.24.jar"
[ -f "$STDLIB" ] || { echo "缺少 $STDLIB"; exit 1; }

# 先验证 stdlib 里确实有关键类，避免拿错包还自以为成功。
# 注意：不能写成 `unzip -l | grep -q`，pipefail 下 grep 提前退出会让 unzip 吃 SIGPIPE
# 返回非零，被误判成"缺类"。这里先把清单落成变量再判断。
STDLIB_LIST="$(unzip -l "$STDLIB")"
case "$STDLIB_LIST" in
  *"kotlin/SynchronizedLazyImpl.class"*) : ;;
  *) echo "stdlib 不完整：缺少 SynchronizedLazyImpl"; exit 1 ;;
esac
case "$STDLIB_LIST" in
  *"kotlin/collections/CollectionsKt.class"*) : ;;
  *) echo "stdlib 不完整：缺少 CollectionsKt"; exit 1 ;;
esac
echo "   stdlib 校验通过"

wc -l < "$OUT/classes.txt" | xargs echo "  应用 class 数:"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api $MIN_SDK --lib "$AJ" --output "$OUT/dex" \
  @"$OUT/classes.txt" "$STDLIB"

echo "== 6. 组装 =="
cp "$OUT/final/base.apk" "$OUT/final/app-unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/final/app-unsigned.apk" classes*.dex )
# 原生库：从构建产物直接打进 APK，不依赖 jniLibs 是否被提交
mkdir -p "$OUT/final/lib/arm64-v8a"
cp "$SO" "$OUT/final/lib/arm64-v8a/libflowengine.so"
( cd "$OUT/final" && zip -q -X -r "app-unsigned.apk" lib/arm64-v8a )
unzip -l "$OUT/final/app-unsigned.apk" | grep -E "libflowengine|classes.dex"

zipalign -f -p 4 "$OUT/final/app-unsigned.apk" "$OUT/final/app-aligned.apk"

echo "== 7. 签名 =="
KS="$ROOT/tools/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass flowstudio -keypass flowstudio \
    -alias flowstudio -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=FlowStudio, OU=Audio, O=Volt, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi
apksigner sign \
  --ks "$KS" --ks-pass pass:flowstudio --key-pass pass:flowstudio \
  --ks-key-alias flowstudio \
  --out "$OUT/FlowStudio-$VERSION_NAME.apk" \
  "$OUT/final/app-aligned.apk"
apksigner verify --print-certs "$OUT/FlowStudio-$VERSION_NAME.apk" | head -5

# ---- 8. 产物自检：这一步是 1.0.0 闪退的教训 ----
# 装机前先确认 APK 里该有的类都在。少一个类就是启动即崩溃，而且只有用户能发现。
APK_OUT="$OUT/FlowStudio-$VERSION_NAME.apk"
CHECK_DIR="$OUT/dexcheck"
rm -rf "$CHECK_DIR"; mkdir -p "$CHECK_DIR"
unzip -o -q "$APK_OUT" 'classes*.dex' -d "$CHECK_DIR"

python3 - "$CHECK_DIR" <<'PYEOF'
import sys, os, pathlib

d = pathlib.Path(sys.argv[1])
blob = b"".join(p.read_bytes() for p in sorted(d.glob("classes*.dex")))
if not blob.startswith(b"dex\n"):
    print("自检失败：没有有效的 dex")
    sys.exit(1)

# 这些类缺任何一个是必崩，而且崩在 Application.onCreate，连界面都进不去
must = [
    b"Lkotlin/SynchronizedLazyImpl;",
    b"Lkotlin/collections/CollectionsKt;",
    b"Lkotlin/jvm/internal/Intrinsics;",
    b"Lcom/volt/flowstudio/FlowApp;",
    b"Lcom/volt/flowstudio/MainActivity;",
    b"Lcom/volt/flowstudio/audio/NativeEngine;",
    b"Lcom/volt/flowstudio/AudioService;",
    b"Lcom/volt/flowstudio/session/StudioSession;",
    b"Lcom/volt/flowstudio/session/ExportedFileProvider;",
]
missing = [m.decode() for m in must if m not in blob]
if missing:
    print("自检失败，dex 缺类：")
    for m in missing:
        print("   ", m)
    sys.exit(1)

print("   dex 自检通过：%d 个 dex 文件，%.0f KB，关键类齐全" % (len(list(d.glob("classes*.dex"))), len(blob) / 1024))
PYEOF

if [ $? -ne 0 ]; then
  echo "产物自检不通过，拒绝交付"
  exit 1
fi

# native 库也要在
unzip -l "$APK_OUT" | grep -q "lib/arm64-v8a/libflowengine.so" || { echo "缺少原生库"; exit 1; }
echo "   原生库就位"

rm -rf "$CHECK_DIR"
ls -la "$APK_OUT"
echo "== 完成: $APK_OUT =="
