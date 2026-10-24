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
VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"

echo "== 0. 原生库 =="
"$ROOT/tools/build_native.sh"
SO="$ROOT/app/src/main/jniLibs/arm64-v8a/libflowengine.so"

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
java -Xmx3g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$AJ:$OUT/gen:$KT_HOME/kotlin-stdlib-1.9.24.jar" \
  -jvm-target 17 \
  -d "$OUT/classes" \
  "$APP/java" 2>&1 | grep -v "^warning:" || true

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
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
wc -l < "$OUT/classes.txt" | xargs echo "  class 数:"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api $MIN_SDK --lib "$AJ" --output "$OUT/dex" @"$OUT/classes.txt"

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

ls -la "$OUT/FlowStudio-$VERSION_NAME.apk"
echo "== 完成: $OUT/FlowStudio-$VERSION_NAME.apk =="
