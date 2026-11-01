#!/system/bin/sh
# ============================================================
# Folia · guest 启动脚本（在 proot 里跑，就像在一台迷你 Linux 里跑）
#
# 这里没有 systemd、没有 init，只有 mksh（安卓的 /system/bin/sh）。
# 所以就是最朴素的"后台起两个 node，然后 wait 住"：
#   node /app/api-entry.js  → :4000  网易云 API
#   node /app/static.js     → :3000  Folia 前端
# 谁挂了就整体退出，让 App 的前台服务把 proot 重拉一遍。
# ============================================================

export LD_LIBRARY_PATH=${FOLIA_LIB:-/usr/local/lib}
export PATH=/usr/local/bin:/system/bin:/system/xbin
export HOME=/app/home
export TMPDIR=/tmp
export TZ=Asia/Shanghai
export NODE_ENV=production
export WEB_PORT=3000
export API_PORT=4000
# 手机内存紧，别让 V8 太贪
export NODE_OPTIONS=--max-old-space-size=512
# TLS 根证书：guest 里没有系统的 CA 包，node 的默认 CA 路径是 Termux 编译进去的，
# 我们这里不存在 → 必须显式指一个（打包时从 Termux 的 ca-certificates 带过来的）
export SSL_CERT_FILE=/app/certs/ca.pem
export NODE_EXTRA_CA_CERTS=/app/certs/ca.pem
# ★ 同一件事的另一半：Termux 编的 node 还会去读 openssl.cnf，默认路径也是 Termux 的
#   /data/data/com.termux/files/usr/etc/tls/openssl.cnf。装在别人手机上时那个文件存在但
#   读不了（EACCES），node 会直接 "OpenSSL configuration error" rc=13 退出。
export OPENSSL_CONF=/app/certs/openssl.cnf

mkdir -p /tmp /app/home /app/logs

echo "[start] $(date '+%Y-%m-%d %H:%M:%S')  node=$(/usr/local/bin/node -v 2>&1)"
echo "[start] rootfs=/  工作中=$PWD"

cd /app || { echo "[start] 进不去 /app"; exit 1; }

/usr/local/bin/node /app/api-entry.js > /app/logs/api.log 2>&1 &
API_PID=$!
/usr/local/bin/node /app/static.js > /app/logs/web.log 2>&1 &
WEB_PID=$!

echo "[start] api pid=$API_PID  web pid=$WEB_PID"

# 等两个进程。注意：Android 的 mksh 里光用 `wait` 不太靠谱（实测会立刻返回），
# 所以改用 kill -0 轮询——谁先死就整体收工，让 App 的服务把 proot 重拉一遍。
while kill -0 $API_PID 2>/dev/null && kill -0 $WEB_PID 2>/dev/null; do
  sleep 5
done
sleep 1
echo "[start] 有服务退出了，脚本收工"
