#!/usr/bin/env bash
# 离线打包：aapt2 → javac → d8 → 对齐 → 签名。不需要 Gradle，不需要联网。
#
#   bash build.sh --compile-only     只编资源 + Java，不需要那 96MB 运行时（CI 用）
#   NATIVE_DIR=... APP_PAYLOAD_DIR=... bash build.sh      出完整 APK
#
# 版本号只认 version.properties。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

MODE="${1:-build}"
case "$MODE" in
    --compile-only) MODE=compile ;;
    -h | --help) sed -n '2,8p' "$0"; exit 0 ;;
    build) ;;
    *) printf '未知参数：%s（--compile-only | --help）\n' "$MODE" >&2; exit 2 ;;
esac

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

prop() {
    local v
    v="$(sed -n "s/^$1=//p" "$ROOT/version.properties" | tr -d ' \r' | head -1)"
    [ -n "$v" ] || die "version.properties 里没有 $1"
    printf '%s' "$v"
}

APP_ID="com.nuonuo.folia"
VER_NAME="$(prop VERSION_NAME)"
VER_CODE="$(prop VERSION_CODE)"
MIN_SDK="$(prop MIN_SDK)"
TARGET_SDK="$(prop TARGET_SDK)"

SRC="$ROOT/app/src/main"
RES_DIR="$SRC/res"
JAVA_DIR="$SRC/java"
MANIFEST="$SRC/AndroidManifest.xml"
OUT="$ROOT/build"
APK_FINAL="$OUT/Folia-${VER_NAME}-debug.apk"

# ------------------------------------------------------------ 工具链

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
[ -d "$SDK" ] || die "找不到 Android SDK：$SDK（用 ANDROID_HOME=... 指定）"

BT_DIR="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
BT_DIR="${BT_DIR%/}"
[ -n "$BT_DIR" ] || die "SDK 里没有 build-tools"

PLATFORM_DIR="$SDK/platforms/android-34"
[ -d "$PLATFORM_DIR" ] || PLATFORM_DIR="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1 || true)"
PLATFORM_DIR="${PLATFORM_DIR%/}"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
[ -f "$ANDROID_JAR" ] || die "缺 android.jar：$ANDROID_JAR"

# aapt2 优先用系统里的（Termux 的 arm64 版），退回 build-tools 自带的那份
AAPT2=""
if command -v aapt2 >/dev/null 2>&1 && aapt2 version >/dev/null 2>&1; then
    AAPT2="$(command -v aapt2)"
elif [ -x "$BT_DIR/aapt2" ] && "$BT_DIR/aapt2" version >/dev/null 2>&1; then
    AAPT2="$BT_DIR/aapt2"
fi
[ -n "$AAPT2" ] || die "没有可用的 aapt2（Termux：pkg install aapt2）"

command -v javac >/dev/null 2>&1 || die "没有 javac（Termux：pkg install openjdk-17）"
command -v keytool >/dev/null 2>&1 || die "没有 keytool"
# Termux 的包叫 python，不一定有 python3 这个名字
PY="$(command -v python3 || command -v python || true)"
[ -n "$PY" ] || die "没有 python（Termux：pkg install python）"

D8_JAR="$BT_DIR/lib/d8.jar"
APKSIGNER_JAR="$BT_DIR/lib/apksigner.jar"

# 给 aapt2 link 用的 android.jar，默认和 javac 同一份。
# Ubuntu 自带的 aapt2 2.19 读不了新格式，那种机器上要指到旧的 api-23。
FRAMEWORK_JAR="${FRAMEWORK_JAR:-$ANDROID_JAR}"
[ -f "$FRAMEWORK_JAR" ] || die "缺 FRAMEWORK_JAR：$FRAMEWORK_JAR"

say "版本          : $VER_NAME ($VER_CODE)  targetSdk=$TARGET_SDK"
say "SDK           : $SDK"
say "build-tools   : $(basename "$BT_DIR")"
say "platform      : $(basename "$PLATFORM_DIR")"
say "aapt2         : $AAPT2"
say "javac         : $(javac -version 2>&1)"
say "模式          : $MODE"

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

if [ "$MODE" = build ]; then
    [ -f "$D8_JAR" ] || die "缺 d8.jar：$D8_JAR"
    [ -f "$APKSIGNER_JAR" ] || die "缺 apksigner.jar：$APKSIGNER_JAR"

    NATIVE_DIR="${NATIVE_DIR:-$HOME/folia-payload/native}"
    APP_PAYLOAD_DIR="${APP_PAYLOAD_DIR:-$HOME/folia-payload/app}"
    [ -d "$NATIVE_DIR" ] || die "找不到运行时目录：$NATIVE_DIR（用 NATIVE_DIR=... 指定）"
    [ -d "$APP_PAYLOAD_DIR" ] || die "找不到应用目录：$APP_PAYLOAD_DIR（用 APP_PAYLOAD_DIR=... 指定）"

    mkdir -p "$OUT/assets"
    say "① 打包 assets（运行时 + 网页 + 接口层）"
    "$PY" "$ROOT/tools/make_assets.py" "$NATIVE_DIR" "$APP_PAYLOAD_DIR" "$OUT/assets"
fi

# ------------------------------------------------------------ 资源

say "② aapt2 compile"
"$AAPT2" compile --dir "$RES_DIR" -o "$OUT/res.zip"

# 清单里不写 package（AGP 8 不允许），打包时注入
sed "s|<manifest |<manifest package=\"$APP_ID\" |" "$MANIFEST" > "$OUT/AndroidManifest.xml"

say "③ aapt2 link"
LINK_ARGS=(
    -o "$OUT/unsigned.apk"
    -I "$FRAMEWORK_JAR"
    --manifest "$OUT/AndroidManifest.xml"
    -R "$OUT/res.zip"
    --java "$OUT/gen"
    --min-sdk-version "$MIN_SDK"
    --target-sdk-version "$TARGET_SDK"
    --version-code "$VER_CODE"
    --version-name "$VER_NAME"
    --auto-add-overlay
)
if [ "$MODE" = build ]; then
    LINK_ARGS+=(-A "$OUT/assets")
fi
"$AAPT2" link "${LINK_ARGS[@]}"

# ------------------------------------------------------------ Java

say "④ javac"
find "$JAVA_DIR" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | xargs echo "   源文件数:"
if ! javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
    head -40 "$OUT/javac.log"
    die "javac 编译失败（完整日志 $OUT/javac.log）"
fi
[ -f "$OUT/classes/com/nuonuo/folia/MainActivity.class" ] || die "javac 没产出 MainActivity.class"

if [ "$MODE" = compile ]; then
    say "编译通过 ✅（未打包）"
    exit 0
fi

# ------------------------------------------------------------ dex

say "⑤ d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
    --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
    --output "$OUT/dex" @"$OUT/classes.txt"

# ------------------------------------------------------------ 对齐 + 签名

KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    echo "    生成 debug 签名证书..."
    keytool -genkeypair -v \
        -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 \
        || die "keytool 生成证书失败"
fi

# aapt2 写 130MB+ 的 APK 偶发写坏、apksigner 偶发 EOF，都是工具链问题，重来一次就好
say "⑥ 对齐 + 签名"
PACK_OK=0
for attempt in 1 2 3; do
    rm -f "$OUT/aligned.apk" "$APK_FINAL"
    if "$PY" "$ROOT/tools/align.py" "$OUT/unsigned.apk" "$OUT/dex/classes.dex" "$OUT/aligned.apk" > "$OUT/align.log" 2>&1 &&
        java -jar "$APKSIGNER_JAR" sign \
            --ks "$KS" --ks-pass pass:android --key-pass pass:android \
            --ks-key-alias androiddebugkey \
            --out "$APK_FINAL" "$OUT/aligned.apk" > "$OUT/sign.log" 2>&1; then
        PACK_OK=1
        break
    fi
    echo "    第 $attempt 次失败，重来"
    tail -3 "$OUT/align.log" "$OUT/sign.log" 2>/dev/null | sed 's/^/      /'
    sleep 2
done
[ "$PACK_OK" = 1 ] || die "对齐/签名连续失败 3 次，看 $OUT/align.log 和 $OUT/sign.log"

say "⑦ 校验签名"
java -jar "$APKSIGNER_JAR" verify --print-certs "$APK_FINAL" | head -6

ls -lh "$APK_FINAL"
say "完成 ✅  $APK_FINAL"
