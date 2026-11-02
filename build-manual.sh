#!/usr/bin/env bash
# ============================================================
# Folia 启动器 · 手工打包脚本（不用 Gradle、不联网）
#
# 为什么要有这个脚本：
#   Termux 里下载 AGP + Gradle 分发包又慢又容易断（Maven 还被掐过 TLS）。
#   这个 App 零第三方依赖，所以整条流程可以只用
#     aapt2（编资源）→ javac（编 Java）→ d8（转 dex）
#     → python 对齐 → apksigner（v2 签名）
#   全部走本地已有的 SDK/JDK，纯离线。
#
# 用法（在 Termux 里）：
#     cd ~/FoliaApp && bash build-manual.sh
#   产物：build/Folia-0.1-debug.apk
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

APP_ID="com.nuonuo.folia"
VER_NAME="1.12"
VER_CODE="22"
MIN_SDK="26"
# ★ targetSdk 必须是 28：Android 10+ 不允许 targetSdk>=29 的应用 exec 私有目录里的
#   可执行文件（node 就在那儿）。Termux 也是这么干的。
TARGET_SDK="28"
OUT="build"
APK_UNSIGNED="$OUT/Folia-unsigned.apk"
APK_ALIGNED="$OUT/Folia-aligned.apk"
APK_FINAL="$OUT/Folia-${VER_NAME}-debug.apk"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------ 0. 找工具
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
[ -d "$SDK" ] || die "找不到 Android SDK：$SDK （用 ANDROID_HOME=... 指定）"

BT_DIR="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)"
PLATFORM_DIR="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$BT_DIR" ] || die "SDK 里没有 build-tools"
[ -n "$PLATFORM_DIR" ] || die "SDK 里没有 platforms/android-*"
BT_DIR="${BT_DIR%/}"
PLATFORM_DIR="${PLATFORM_DIR%/}"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
[ -f "$ANDROID_JAR" ] || die "缺 android.jar：$ANDROID_JAR"

say "SDK 位置      : $SDK"
say "build-tools   : $(basename "$BT_DIR")"
say "platform      : $(basename "$PLATFORM_DIR")"

# aapt2：优先用 Termux 自带的 arm64 版（Google build-tools 里那个是 x86_64 的，手机跑不了）
AAPT2=""
if command -v aapt2 >/dev/null 2>&1; then
    AAPT2="$(command -v aapt2)"
elif [ -x "$BT_DIR/aapt2" ] && "$BT_DIR/aapt2" version >/dev/null 2>&1; then
    AAPT2="$BT_DIR/aapt2"
fi
[ -n "$AAPT2" ] || die "没有可用的 aapt2。在 Termux 里执行： pkg install aapt2"

command -v javac >/dev/null 2>&1 || die "没有 javac。在 Termux 里执行： pkg install openjdk-17"
command -v python3 >/dev/null 2>&1 || die "没有 python3。在 Termux 里执行： pkg install python"

D8_JAR="$BT_DIR/lib/d8.jar"
APKSIGNER_JAR="$BT_DIR/lib/apksigner.jar"
[ -f "$D8_JAR" ] || die "缺 d8.jar：$D8_JAR"
[ -f "$APKSIGNER_JAR" ] || die "缺 apksigner.jar：$APKSIGNER_JAR"

say "aapt2         : $AAPT2"
say "javac         : $(javac -version 2>&1)"
say "d8/apksigner  : $(basename "$BT_DIR")/lib"

# ------------------------------------------------------------ 1. 清场
rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets"

SRC="$ROOT/app/src/main"
RES_DIR="$SRC/res"
JAVA_DIR="$SRC/java"
MANIFEST="$SRC/AndroidManifest.xml"

# ------------------------------------------------------------ 2. 编资源
say "① aapt2 compile（编译资源）"
"$AAPT2" compile --dir "$RES_DIR" -o "$OUT/res/resources.zip"

# 清单里没有 package 属性（为了兼容 Gradle 8），这里动态补一个
sed "s|<manifest |<manifest package=\"$APP_ID\" |" "$MANIFEST" > "$OUT/AndroidManifest.xml"

# ------------------------------------------------------------ 1.5 装 assets
# 运行时（node/.so）和应用（网页+API）从这两个目录来，默认是手机上的试验目录
NATIVE_DIR="${NATIVE_DIR:-$HOME/folia-payload/native}"
APP_PAYLOAD_DIR="${APP_PAYLOAD_DIR:-$HOME/folia-payload/app}"
[ -d "$NATIVE_DIR" ] || die "找不到运行时目录：$NATIVE_DIR（用 NATIVE_DIR=... 指定）"
[ -d "$APP_PAYLOAD_DIR" ] || die "找不到应用目录：$APP_PAYLOAD_DIR（用 APP_PAYLOAD_DIR=... 指定）"
say "①.5 打包 assets（运行时 + 网页 + API）"
python3 "$ROOT/tools/make_assets.py" "$NATIVE_DIR" "$APP_PAYLOAD_DIR" "$OUT/assets"

say "② aapt2 link（打包资源 + 生成 R.java）"
"$AAPT2" link \
    -o "$APK_UNSIGNED" \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    -R "$OUT/res/resources.zip" \
    -A "$OUT/assets" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VER_CODE" \
    --version-name "$VER_NAME" \
    --auto-add-overlay

# ------------------------------------------------------------ 3. 编 Java
say "③ javac（编译 Java 源码）"
find "$JAVA_DIR" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | xargs echo "   源文件数:"
# 注意：以前这里用 `javac ... | grep ...` 把退出码吃掉了，编译报错也能一路往下走，
# 结果打了个缺类的 APK（装上去才崩）。现在先落日志再判退出码。
if ! javac -encoding UTF-8 \
      -source 8 -target 8 \
      -bootclasspath "$ANDROID_JAR" \
      -classpath "$ANDROID_JAR" \
      -d "$OUT/classes" \
      @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
    grep -v 'bootstrap class path' "$OUT/javac.log" | head -40
    die "javac 编译失败（完整日志 $OUT/javac.log）"
fi
grep -v 'bootstrap class path' "$OUT/javac.log" | grep -i 'warning\|note' | head -5 || true
[ -f "$OUT/classes/com/nuonuo/folia/MainActivity.class" ] || die "javac 没产出 MainActivity.class"
for c in EmbeddedRuntime FoliaService LogTailer PortProbe TermuxBridge; do
    [ -f "$OUT/classes/com/nuonuo/folia/$c.class" ] || die "缺 $c.class（javac 漏了？）"
done

# ------------------------------------------------------------ 4. 转 dex
say "④ d8（class → dex）"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
     --release --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
     --output "$OUT/dex" @"$OUT/classes.txt"

# ------------------------------------------------------------ 5. 塞进 APK
KS="$ROOT/debug.keystore"
if [ ! -f "$KS" ]; then
    echo "    生成 debug 签名证书..."
    keytool -genkeypair -v \
        -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 \
        || die "keytool 生成证书失败"
fi

# ⑤⑥ 要重试：这台手机上 aapt2 写 130MB+ 的 APK 偶发写坏（条目名里某个字节变 0x00），
# apksigner 偶发读到 Unexpected EOF。都不是代码问题，重来一次就好。实测两次都遇到过。
say "⑤ 把 classes.dex 塞进 APK + 4 字节对齐"
say "⑥ apksigner 签名（v1+v2+v3）"
PACK_OK=0
for attempt in 1 2 3; do
    rm -f "$APK_ALIGNED" "$APK_FINAL"
    if python3 "$ROOT/tools/align.py" "$APK_UNSIGNED" "$OUT/dex/classes.dex" "$APK_ALIGNED" > "$OUT/align.log" 2>&1 \
       && java -jar "$APKSIGNER_JAR" sign \
            --ks "$KS" --ks-pass pass:android --key-pass pass:android \
            --ks-key-alias androiddebugkey \
            --out "$APK_FINAL" "$APK_ALIGNED" > "$OUT/sign.log" 2>&1; then
        PACK_OK=1
        break
    fi
    echo "    第 $attempt 次打包/签名失败，重来（偶发的，不是你的问题）"
    tail -3 "$OUT/align.log" 2>/dev/null | sed 's/^/      /'
    tail -3 "$OUT/sign.log" 2>/dev/null | sed 's/^/      /'
    sleep 2
done
[ "$PACK_OK" = 1 ] || die "对齐/签名连续失败 3 次，看 $OUT/align.log 和 $OUT/sign.log"

say "⑦ 校验签名"
java -jar "$APKSIGNER_JAR" verify --print-certs "$APK_FINAL" | head -6

ls -lh "$APK_FINAL"
say "完成 ✅  产物：$ROOT/$APK_FINAL"
echo
echo "装到手机上："
echo "  termux-open '$ROOT/$APK_FINAL'      # 弹安装器手动点"
echo "  # 或者用 Shizuku： rish -c \"pm install -r '$ROOT/$APK_FINAL'\""
