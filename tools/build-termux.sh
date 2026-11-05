#!/usr/bin/env bash
# Termux 一键打包：补工具 → 备 SDK → 备 payload → 调 build.sh。
#
#   bash tools/build-termux.sh
#   bash tools/build-termux.sh --folia ~/folia-major --out ~/folia-payload
#   bash tools/build-termux.sh --web ~/dist --api ~/有/server.js/的目录
#
# 只读本地文件、只在缺的时候下载 SDK 的 3 个 jar（约 120MB，一次性）。
# 产物留在 build/，脚本不往任何地方上传。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FOLIA="${FOLIA_DIR:-$HOME/folia-major}"
PAYLOAD="${PAYLOAD_DIR:-$HOME/folia-payload}"
WEB_SRC=""
API_SRC=""
SKIP_DEPS=0
SKIP_SDK=0

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m   ! %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --folia) FOLIA="$2"; shift 2 ;;
        --out) PAYLOAD="$2"; shift 2 ;;
        --web) WEB_SRC="$2"; shift 2 ;;
        --api) API_SRC="$2"; shift 2 ;;
        --skip-deps) SKIP_DEPS=1; shift ;;
        --skip-sdk) SKIP_SDK=1; shift ;;
        -h | --help) sed -n '2,9p' "$0"; exit 0 ;;
        *) die "未知参数：$1（--folia --out --web --api --skip-deps --skip-sdk）" ;;
    esac
done

# ------------------------------------------------------------ 环境

[ -n "${PREFIX:-}" ] && [ -d "${PREFIX}" ] && [ -x "${PREFIX}/bin/pkg" ] \
    || die "请在 Termux 里运行（没找到 \$PREFIX/bin/pkg）"

ARCH="$(uname -m)"
[ "$ARCH" = "aarch64" ] || die "只支持 arm64 手机，当前是 $ARCH"

need() { command -v "$1" >/dev/null 2>&1; }
PY="$(command -v python3 || command -v python || true)"

if [ "$SKIP_DEPS" = 0 ]; then
    say "① 检查工具"
    PKGS=()
    need javac || PKGS+=(openjdk-17)
    need keytool || PKGS+=(openjdk-17)
    need aapt2 || PKGS+=(aapt2)
    need node || PKGS+=(nodejs)
    need curl || PKGS+=(curl)
    [ -n "$PY" ] || PKGS+=(python)
    if [ ${#PKGS[@]} -gt 0 ]; then
        # 去重
        mapfile -t PKGS < <(printf '%s\n' "${PKGS[@]}" | sort -u)
        echo "    需要安装：${PKGS[*]}"
        pkg install -y "${PKGS[@]}"
        PY="$(command -v python3 || command -v python || true)"
    else
        echo "    工具都齐了"
    fi
fi
[ -n "$PY" ] || die "没有 python（pkg install python）"
need javac || die "没有 javac（pkg install openjdk-17）"
need aapt2 || die "没有 aapt2（pkg install aapt2）"
need node || die "没有 node（pkg install nodejs）"

# ------------------------------------------------------------ SDK

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
PLATFORM_DIR="$SDK/platforms/android-34"
BT_DIR="$SDK/build-tools/34.0.0"

# 清单来自 repository2-3.xml；大小用来校验下载完整性
PLATFORM_ZIP="platform-34-ext7_r03.zip"
PLATFORM_SIZE=63180081
BT_ZIP="build-tools_r34-linux.zip"
BT_SIZE=61224257

# 国内直连 dl.google.com 基本不通，默认先走腾讯的镜像
MIRRORS=(
    "${SDK_MIRROR:-https://mirrors.cloud.tencent.com/AndroidSDK}"
    "https://dl.google.com/android/repository"
)

extract_member() { # zip 目标目录 通配模式
    "$PY" - "$1" "$2" "$3" <<'PY'
import fnmatch, sys, zipfile
src, dst, pattern = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zf:
    hits = [n for n in zf.namelist() if fnmatch.fnmatch(n, pattern)]
    if not hits:
        sys.exit("zip 里找不到 " + pattern)
    zf.extract(hits[0], dst)
    print(hits[0])
PY
}

download() { # 文件名 大小 缓存路径
    local file="$1" size="$2" cache="$3" m url
    mkdir -p "$(dirname "$cache")"
    for m in "${MIRRORS[@]}"; do
        if [ -f "$cache" ] && [ "$(wc -c <"$cache" | tr -d ' ')" -eq "$size" ]; then
            echo "    已缓存 $(basename "$file")"
            return 0
        fi
        echo "    下载 $file（$((size / 1048576)) MB）← $m"
        if curl -fL --retry 2 --retry-delay 2 --connect-timeout 20 -C - --progress-bar \
            -o "$cache" "$m/$file"; then
            if [ "$(wc -c <"$cache" | tr -d ' ')" -eq "$size" ]; then
                return 0
            fi
            warn "大小不对（$(wc -c <"$cache" | tr -d ' ') ≠ $size），删掉重来"
            rm -f "$cache"
        fi
        warn "这个源不行，换下一个"
    done
    return 1
}

if [ "$SKIP_SDK" = 0 ] && { [ ! -f "$PLATFORM_DIR/android.jar" ] || [ ! -f "$BT_DIR/lib/d8.jar" ] || [ ! -f "$BT_DIR/lib/apksigner.jar" ]; }; then
    say "② 准备 Android SDK（只要 android.jar / d8.jar / apksigner.jar）"
    CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/folia-sdk"
    TMP="$CACHE/extract"
    rm -rf "$TMP"
    mkdir -p "$TMP"

    if [ ! -f "$PLATFORM_DIR/android.jar" ]; then
        download "$PLATFORM_ZIP" "$PLATFORM_SIZE" "$CACHE/$PLATFORM_ZIP" || die "SDK platform 下载失败（可用 SDK_MIRROR=... 换源）"
        mkdir -p "$PLATFORM_DIR"
        echo "    解开 $(extract_member "$CACHE/$PLATFORM_ZIP" "$TMP" "*/android.jar")"
        mv "$TMP"/android-*/android.jar "$PLATFORM_DIR/android.jar"
    fi

    if [ ! -f "$BT_DIR/lib/d8.jar" ] || [ ! -f "$BT_DIR/lib/apksigner.jar" ]; then
        download "$BT_ZIP" "$BT_SIZE" "$CACHE/$BT_ZIP" || die "SDK build-tools 下载失败（可用 SDK_MIRROR=... 换源）"
        mkdir -p "$BT_DIR/lib"
        extract_member "$CACHE/$BT_ZIP" "$TMP" "*/lib/d8.jar" >/dev/null
        extract_member "$CACHE/$BT_ZIP" "$TMP" "*/lib/apksigner.jar" >/dev/null
        mv "$TMP"/android-*/lib/d8.jar "$BT_DIR/lib/d8.jar"
        mv "$TMP"/android-*/lib/apksigner.jar "$BT_DIR/lib/apksigner.jar"
    fi
    rm -rf "$TMP"
    echo "    $PLATFORM_DIR/android.jar"
    echo "    $BT_DIR/lib/{d8,apksigner}.jar"
else
    say "② 跳过 SDK（已经齐了）"
fi

export ANDROID_HOME="$SDK"

# ------------------------------------------------------------ 运行时

say "③ 备运行时（$PAYLOAD/native）"
NATIVE="$PAYLOAD/native"
mkdir -p "$NATIVE/bin"
# 先清空：上一次构建留下的库（包括重复的 libicu）不然还会被打进去
rm -rf "$NATIVE/lib"
mkdir -p "$NATIVE/lib"
cp -f "$PREFIX/bin/node" "$NATIVE/bin/node"
chmod 755 "$NATIVE/bin/node"
# node 必须是真 ELF，不能是包装脚本
"$PY" -c 'import sys; sys.exit(0 if open(sys.argv[1],"rb").read(4)==b"\x7fELF" else 1)' \
    "$NATIVE/bin/node" || die "$PREFIX/bin/node 不是 ELF 可执行文件"

# 按 DT_NEEDED 递归收依赖：每个 soname 一份，不跟通配符，也不依赖 ldd
"$PY" "$ROOT/tools/collect-libs.py" "$NATIVE/bin/node" "$PREFIX/lib" "$NATIVE/lib" \
    || die "收集 node 依赖失败"

LIBS="$(ls -1 "$NATIVE/lib" | wc -l | tr -d ' ')"
[ "$LIBS" -gt 0 ] || die "一个依赖都没收集到，检查 $PREFIX/lib"
echo "    node $(du -h "$NATIVE/bin/node" | cut -f1)，依赖 $LIBS 个：$(du -sh "$NATIVE/lib" | cut -f1)"
for must in libc++_shared.so libssl.so.3 libcrypto.so.3 libicudata.so.78; do
    [ -e "$NATIVE/lib/$must" ] || warn "没收集到 $must（版本可能不同，看 node 能不能起来）"
done

# ------------------------------------------------------------ 应用

say "④ 备应用（$PAYLOAD/app）"
APP="$PAYLOAD/app"
rm -rf "$APP"
mkdir -p "$APP"
cp "$ROOT"/payload/*.js "$APP/"
cp -r "$ROOT/payload/lib" "$APP/lib"

# 网页：--web 指定的 dist > Folia 仓库里的 dist > 现构建
if [ -n "$WEB_SRC" ]; then
    cp -r "$WEB_SRC/." "$APP/web"
    echo "    web ← $WEB_SRC"
elif [ -d "$FOLIA/dist" ]; then
    cp -r "$FOLIA/dist/." "$APP/web"
    echo "    web ← $FOLIA/dist"
else
    [ -d "$FOLIA" ] || die "找不到 Folia 源码：$FOLIA
    先 git clone https://github.com/chthollyphile/folia-major，或用 --web 指定现成的 dist"
    echo "    在 $FOLIA 里 vite build…"
    if [ ! -d "$FOLIA/node_modules" ]; then
        (cd "$FOLIA" && npm install --no-audit --no-fund) || die "npm install 失败"
    fi
    (cd "$FOLIA" && npx vite build) || die "vite build 失败"
    cp -r "$FOLIA/dist/." "$APP/web"
fi
[ -f "$APP/web/index.html" ] || die "web 目录里没有 index.html，前端没打出来"

# 接口层：按 app/api/node_modules/@neteasecloudmusicapienhanced/api 布局放
if [ -z "$API_SRC" ] && [ -d "$FOLIA/node_modules/@neteasecloudmusicapienhanced/api" ]; then
    API_SRC="$FOLIA/node_modules/@neteasecloudmusicapienhanced/api"
fi
if [ -n "$API_SRC" ] && [ -f "$API_SRC/server.js" ]; then
    API_DST="$APP/api/node_modules/@neteasecloudmusicapienhanced/api"
    mkdir -p "$(dirname "$API_DST")"
    cp -r "$API_SRC" "$API_DST"
    echo "    接口层 ← $API_SRC"
    [ -d "$API_DST/node_modules" ] || warn "接口层没自带 node_modules，起不来时看 logs/api.log"
else
    die "找不到接口层（要有 server.js）：
    在 Folia 仓库里 npm install 之后应该出现在
    $FOLIA/node_modules/@neteasecloudmusicapienhanced/api
    或者用 --api 指定"
fi

# 证书：Termux 自己那份
mkdir -p "$APP/certs"
if [ -f "$PREFIX/etc/tls/openssl.cnf" ]; then
    cp -f "$PREFIX/etc/tls/openssl.cnf" "$APP/certs/openssl.cnf"
else
    warn "没有 $PREFIX/etc/tls/openssl.cnf，node 可能因 OpenSSL 配置读不到而退出"
fi
for ca in "$PREFIX/etc/tls/cert.pem" "$PREFIX/etc/ssl/certs/ca-certificates.crt" "$PREFIX/etc/ca-certificates.crt"; do
    if [ -f "$ca" ]; then
        cp -f "$ca" "$APP/certs/ca.pem"
        echo "    ca ← $ca"
        break
    fi
done
[ -f "$APP/certs/ca.pem" ] || warn "没有根证书，HTTPS 会失败"

echo "    运行时 $(du -sh "$NATIVE" | cut -f1)，应用 $(du -sh "$APP" | cut -f1)"

# ------------------------------------------------------------ 打包

say "⑤ 打包"
NATIVE_DIR="$NATIVE" APP_PAYLOAD_DIR="$APP" bash "$ROOT/build.sh"

APK="$(ls -t "$ROOT"/build/Folia-*.apk 2>/dev/null | head -1 || true)"
if [ -n "$APK" ]; then
    say "完成 ✅ $APK"
    echo
    echo "装到手机：  termux-open '$APK'"
    echo "别把 APK 传到公开仓库（见 README「关于分发」）。"
fi
