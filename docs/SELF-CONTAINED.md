# Folia 自包含 APK · 设计与实现记录（v1.0）

> 一句话：APK 里塞一个 proot + node + 网页，装完不依赖 Termux，打开就能听歌。

## 1. 体积账（实测）

| 部分 | 未压缩 | 说明 |
|---|---|---|
| `node` (v26.12.1, Termux 版) | 50.99 MB | 大头，没法再砍 |
| 依赖的 .so（9 个） | 44.34 MB | 其中 `libicudata.so.78` 33.1 MB（ICU 数据，node 静态依赖） |
| `proot` + `libtalloc.so.2` + `libandroid-shmem.so` | 0.31 MB | |
| **运行时小计** | **95.6 MB** | 放 `assets/native/` |
| 前端 `dist`（vite build） | 29 MB | 6773 个文件里的大头是 4 个 ICU/词典 chunk |
| 网易云 API + node_modules | 41 MB | 291 个包，`--omit=dev` |
| 根证书 `ca.pem` | 0.2 MB | 从 Termux 的 ca-certificates 拷的 |
| **应用小计（payload.zip）** | **70.8 MB** | zip 后 34.0 MB |
| **APK 最终** | **69.4 MiB（72,753,879 字节）** | assets 被 aapt2 再压一遍 |

装好后占用：`files/runtime` 95.6 MB + `files/app` 70.8 MB ≈ **166 MB**（不含 APK 本身）。

> 想再瘦：把 `libicudata.so.78` 换成 small-icu 版 node 能省 30 MB，但要自己编 node，不值得。

## 2. 目录结构

```
应用私有目录（/data/data/com.nuonuo.folia/files/）
├── runtime/bin/{proot,node}        ← 从 assets/native 拷来，带 x 位
├── runtime/lib/*.so                ← node / proot 的动态库
├── rootfs/                         ← proot 的根（只有目录 + etc 三个文件，几 KB）
├── app/
│   ├── web/                        ← vite build 的 dist
│   ├── api/node_modules/...        ← 网易云 API
│   ├── api-entry.js                ← 起 API（关掉版本检查）
│   ├── static.js                   ← 60 行静态服务器（替代 vite）
│   ├── certs/ca.pem                ← TLS 根证书
│   └── start.sh                    ← guest 里的启动脚本
└── logs/{proot.log,web.log,api.log}
```

**为什么 rootfs 只是个空壳**：proot 会把手机的 `/dev /proc /sys /system /vendor /apex`
挂进 guest。node 是 bionic 程序，动态解释器就是 `/system/bin/linker64`，所以真正需要的
只有「目录结构 + 我们自己的文件」。背一个 Termux bootstrap（120 MB 解压）纯属浪费。

## 3. 启动链路

```
MainActivity（首次启动）
  └─ EmbeddedRuntime.install()     解 assets → files/{runtime,app,rootfs}，带百分比进度
       └─ FoliaService.start()     前台服务（常驻通知）
            └─ ProcessBuilder(proot ...)  ← 唯一的进程
                 └─ /system/bin/sh /app/start.sh
                      ├─ node api-entry.js  → 127.0.0.1:4000
                      └─ node static.js     → 127.0.0.1:3000
WebView → http://127.0.0.1:3000
```

proot 命令行（**少一个 bind 就起不来**）：

```bash
runtime/bin/proot --link2symlink --kill-on-exit -0 \
  -r files/rootfs \
  -b /dev -b /proc -b /sys -b /system -b /vendor -b /apex \
  -b files/runtime/lib:/usr/lib \
  -b files/runtime/bin:/usr/bin \
  -b files/app:/app \
  -w /app /system/bin/sh /app/start.sh
```

- `-b /system` 是命门：没有它，Termux 编出来的 bionic 二进制找不到 `/system/bin/linker64`，
  报 `fatal error: the loader was not found`
- `--kill-on-exit`：proot 一死，guest 里两个 node 跟着死，不会变成僵尸
- `LD_LIBRARY_PATH` 分两层：给 proot 自己的是**宿主机绝对路径**（它的 RUNPATH 指向 Termux 的
  prefix，我们这儿没有），给 guest 进程的由 `start.sh` 重新 `export LD_LIBRARY_PATH=/usr/lib`

## 4. targetSdk 为什么是 28（重要）

Android 10（API 29）起，**targetSdk ≥ 29 的应用不允许 exec 自己数据目录里的文件**（W^X 限制）。
proot 和 node 就住在 `files/runtime/bin/` 里，所以：

- 本 App 的 `targetSdk = 28`，和 Termux 的做法一样（Termux 至今也停在 28）
- `minSdk = 26`
- 这不是"绕过检测"，只是这一个平台行为差异，写在代码和文档里明明白白

如果将来要上 targetSdk 34（比如要上架），必须改写：把 proot/node 改名成 `lib*.so`
放进 APK 的 `lib/arm64-v8a/`（PackageManager 会解到可执行目录），其余 .so 靠 bind 进 guest。

## 5. 踩过的坑（按时间顺序）

1. **mksh 的 `wait` 不可靠**：`start.sh` 里 `wait` 会立刻返回，导致脚本以为子进程全死了。
   → 改成 `while kill -0 $PID ...; do sleep 5; done` 轮询。
2. **guest 没有 CA 包**：API 报 `502 unable to get local issuer certificate`。
   Termux 的 node 把 openssl 默认证书路径编成了 `$PREFIX/etc/tls/cert.pem`，我们这儿不存在。
   → 把 Termux 的 `cert.pem` 打进 payload，`export SSL_CERT_FILE=/app/certs/ca.pem`。
3. **vite build 会"假挂"**：手机 36 秒就打完 dist，然后进程卡在联网的 PWA/字体收尾不动。
   → 看到 `✓ built in` 且 dist 完整就可以 kill。
4. **javac 的退出码被管道吃掉**：老 `build-manual.sh` 是 `javac ... | grep ... || true`，
   编不过也能继续，结果打出个缺 `EmbeddedRuntime.class` 的 APK。→ 先落日志再判退出码，
   并逐个校验关键 class 是否产出。
5. **XML 注释里不能出现 `--`**：manifest 注释里写了 `--target-sdk-version`，aapt2 直接
   `not well-formed (invalid token)`。
6. **不要 `pkill -f <包含命令自身的字符串>`**：我把自己那条 `bash -lc` 一起杀了两次，
   用 `pkill -f "folia-payloa[d]"` 这种带括号的写法避免自匹配。

## 6. 怎么重新构建

```bash
# 前置：手机上已有 ~/folia-payload/{native,app}（见下）
cd ~/FoliaApp
bash build-manual.sh
# → build/Folia-1.0-debug.apk
```

`~/folia-payload` 是怎么来的（一次性）：

```bash
# 1) 运行时
mkdir -p ~/folia-payload/native/{bin,lib}
cp $PREFIX/bin/{proot,node} ~/folia-payload/native/bin/
cp -L $PREFIX/lib/{libz.so.1,libcares.so,libsqlite3.so,libffi.so,libcrypto.so.3,libssl.so.3,
                   libicui18n.so.78,libicuuc.so.78,libicudata.so.78,libc++_shared.so,
                   libtalloc.so.2,libandroid-shmem.so} ~/folia-payload/native/lib/

# 2) 前端
cd ~/folia-major && node node_modules/vite/bin/vite.js build --outDir dist   # 卡住就 kill
cp -a dist ~/folia-payload/app/web

# 3) API
cd ~/folia-payload/app && npm init -y && npm i --omit=dev @neteasecloudmusicapienhanced/api@4.41.1
# 或把已经装好的 node_modules 放进 app/api/

# 4) 脚本 + 证书
cp payload/{static.js,api-entry.js,start.sh} ~/folia-payload/app/
mkdir -p ~/folia-payload/app/certs && cp $PREFIX/etc/tls/cert.pem ~/folia-payload/app/certs/ca.pem
```

## 7. 验证记录

| 验证 | 结果 |
|---|---|
| 最小 rootfs（95 MB）里跑 node | `v26.12.1`、ICU 78.3、`fetch` 可用 ✅ |
| 完整 payload 在 proot 里跑 | `web=200`、`api=200`、`/search` 返回真实歌曲 JSON ✅ |
| **从 APK 的 assets 原样解出来**再跑（模拟 App 的安装逻辑） | `web=200`、`api=200`（中文关键词搜索通过）✅ |
| APK 结构 | targetSdk 28、assets/native 14 个文件、assets/payload.zip、install-plan.txt ✅ |
| APK zip 完整性 + sha256 | `aaac2635…10c285`，70 MB ✅ |
| 真机安装 + 断 Termux 播放 | 待用户点安装后验证（见 task-state.md） |
