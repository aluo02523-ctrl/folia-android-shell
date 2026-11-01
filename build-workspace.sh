#!/usr/bin/env bash
# ============================================================
# Folia 启动器 · 工作区构建脚本（不依赖 Termux）
#
# 为什么要它：
#   Termux 后台老被系统掐，编译机随时会消失。工作区（Ubuntu proot）装上
#   JDK + aapt 之后可以自己出 APK：工具链全在本地，不联网。
#
# 依赖（都已经准备好）：
#   /workspace/android/android.jar     ← 从手机 platforms/android-36 搬来的
#   /workspace/android/d8.jar          ← 从手机 build-tools/36.0.0/lib 搬来的
#   /workspace/android/apksigner.jar   ← 同上
#   apt install openjdk-17-jdk-headless aapt
#
# 注意：aapt 是 v1（Ubuntu 只有这个），语法和 aapt2 不一样：
#   aapt2 compile/link  →  aapt package -f -M … -S … -I … -J … -F …
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

APP_ID="com.nuonuo.folia"
VER_NAME="1.1"
VER_CODE="4"
MIN_SDK="26"
TARGET_SDK="34"

TOOLS=/workspace/android
ANDROID_JAR="$TOOLS/android.jar"                 # API 36：给 javac 编译用
FRAMEWORK_JAR="/usr/lib/android-sdk/platforms/android-23/android.jar"   # API 23：给 aapt2 链资源用（aapt2 2.19 读不了新格式）
D8_JAR="$TOOLS/d8.jar"
SIGNER_JAR="$TOOLS/apksigner.jar"
OUT="$ROOT/build-ws"
SRC="$ROOT/app/src/main"
APK="$OUT/Folia-${VER_NAME}-debug.apk"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

[ -f "$ANDROID_JAR" ] || die "缺 $ANDROID_JAR（从手机 platforms/android-36 搬过来）"
[ -f "$D8_JAR" ] || die "缺 $D8_JAR"
[ -f "$SIGNER_JAR" ] || die "缺 $SIGNER_JAR"
command -v javac >/dev/null || die "没 javac：apt install openjdk-17-jdk-headless"
AAPT2="$(command -v aapt2 || echo /usr/lib/android-sdk/build-tools/29.0.3/aapt2)"
[ -x "$AAPT2" ] || die "没 aapt2：apt install android-sdk-build-tools"
command -v python3 >/dev/null || die "没 python3"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets"

# 清单里没写 package（为了兼容 Gradle/AGP 8），aapt v1 必须要，这里注入
sed "s|<manifest |<manifest package=\"$APP_ID\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

say "① aapt2 compile（编译资源）"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

# 运行时（proot/node/mksh/.so）和应用（网页+API）两个目录
NATIVE_DIR="${NATIVE_DIR:-/tmp/apk/assets/native}"
APP_PAYLOAD_DIR="${APP_PAYLOAD_DIR:-/tmp/apk/payload}"
[ -d "$NATIVE_DIR" ] || die "找不到运行时目录：$NATIVE_DIR"
[ -d "$APP_PAYLOAD_DIR" ] || die "找不到应用目录：$APP_PAYLOAD_DIR"
say "② 打包 assets（运行时 + 网页 + API）"
python3 "$ROOT/tools/make_assets.py" "$NATIVE_DIR" "$APP_PAYLOAD_DIR" "$OUT/assets"

say "②' aapt2 link（打包资源 + 生成 R.java）"
"$AAPT2" link \
    -o "$OUT/unsigned.apk" \
    -I "$FRAMEWORK_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    -R "$OUT/res.zip" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VER_CODE" \
    --version-name "$VER_NAME" \
    -A "$OUT/assets" \
    --auto-add-overlay

say "③ javac（编译 Java）"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | xargs echo "   源文件:"
javac -encoding UTF-8 -source 8 -target 8 \
      -bootclasspath "$ANDROID_JAR" \
      -classpath "$ANDROID_JAR" \
      -d "$OUT/classes" \
      @"$OUT/sources.txt" 2>&1 | grep -v 'bootstrap class path' || true
[ -f "$OUT/classes/com/nuonuo/folia/MainActivity.class" ] || die "javac 没产出 MainActivity.class"

say "④ d8（class → dex）"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
     --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
     --output "$OUT/dex" @"$OUT/classes.txt"

say "⑤ 塞 dex + 4 字节对齐"
python3 "$ROOT/tools/align.py" "$OUT/unsigned.apk" "$OUT/dex/classes.dex" "$OUT/aligned.apk"

say "⑥ 签名"
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || die "keytool 失败"
fi
rm -f "$APK"
java -jar "$SIGNER_JAR" sign \
     --ks "$KS" --ks-pass pass:android --key-pass pass:android \
     --ks-key-alias androiddebugkey \
     --out "$APK" "$OUT/aligned.apk"

say "⑦ 校验"
java -jar "$SIGNER_JAR" verify --print-certs "$APK" | head -3
ls -lh "$APK"
say "完成 ✅ $APK"
