# Folia Android Shell

[![ci](https://github.com/aluo02523-ctrl/folia-android-shell/actions/workflows/ci.yml/badge.svg)](https://github.com/aluo02523-ctrl/folia-android-shell/actions/workflows/ci.yml)

给 [Folia](https://github.com/chthollyphile/folia-major) 做的 Android 壳：

**全屏只有播放器**，运行时（Node.js）内置在 APK 里，装完不需要任何外部终端或电脑。

```
┌─────────────────────────────┐
│                             │
│   WebView ← 127.0.0.1:3000  │   平时只有这个
│                             │
└─────────────────────────────┘
        从屏幕顶边下拉 ↓
┌─────────────────────────────┐
│ ● 启动  停止  清屏        ✕ │
│ [api]  网易云接口层 :4000    │   隐藏的日志面板
│ [web]  静态站      :3000    │
└─────────────────────────────┘
```

## 原理

### 1. 一个 App，一条进程链

```
App 进程 (Java)                     node 进程
  │                                    │
  ├─ MainActivity  WebView ──HTTP──▶  127.0.0.1:3000  静态站（前端 dist）
  │                                    │
  └─ FoliaService  ──exec──▶ node      127.0.0.1:4000  接口层
       （前台服务，守护它）                 ▲
                                    entry.js 派生两个子进程
```

- **入口**：`payload/entry.js` 是进程总管，派生 `api-entry.js` 和 `static.js`，
  两个服务各写一份日志；谁挂了整条链退出，看门狗重拉。
- **App 只负责两件事**：把 WebView 指到 `127.0.0.1:3000`，以及别让 node 被系统杀掉。

### 2. 运行时是「解压出来的」，不是装出来的

APK 的 `assets/` 里带着：

| 内容 | 大小 | 说明 |
|---|---|---|
| `native/bin/node` | 51 MB | Node.js 本体（arm64） |
| `native/lib/*.so` | 44 MB | node 的依赖：ICU、OpenSSL、libc++、sqlite、c-ares… |
| `payload.zip` | 34 MB | 前端 dist + 接口层 + entry.js |

首次启动把它们解压到应用私有目录（共约 130 MB）：

```
files/runtime/bin/node      可执行文件
files/runtime/lib/*.so      动态库
files/app/…                 前端与接口层
files/app/.pids             进程清单（停止时用来清残留）
files/app/logs/*.log        两个服务各自的输出
```

`install-plan.txt` 里记着每个文件的字节数，解压时逐个校验，进度条按实际字节算。

### 3. 四个必须守住的点

**为什么前端要预构建**
前端本来就是 `vite build` 出来的静态文件。带上 vite/esbuild 要多 100 MB，运行期还要编译，
所以只在构建阶段跑一次 `vite build`，APK 里只放 dist。运行时就是一个 120 行的静态服务器。

**为什么 targetSdk 是 28**
Android 10 起，`targetSdk ≥ 29` 的应用**不允许执行自己私有目录里的文件**。
整套运行时（node 在 `files/` 里）依赖这个能力，所以 targetSdk 停在 28。
要提上去，得把 node 改名 `libnode.so` 放进 `lib/arm64-v8a/`，让系统当原生库解压。

**为什么 node 要显式指定 OpenSSL 配置**
这份 node 是 Termux 编译的，默认去读 `/data/data/com.termux/files/usr/etc/tls/openssl.cnf`。
别的 App 读那个路径会被拒（EACCES），OpenSSL 对权限错误是致命错误，node 直接退出。
所以随包带一份 `certs/openssl.cnf`，启动时用 `OPENSSL_CONF` 指过去。

**为什么要清孤儿进程**
主进程被系统强杀时 `entry.js` 来不及收尾，两个子进程会活下来占住端口。
下次启动只看到"端口通"却没有进程句柄，看门狗就永远挂不上。
所以 `entry.js` 把 pid 写进 `.pids`，`FoliaService` 启动前按这份清单清理
（只杀 cmdline 里带本包名的，避免 pid 复用误伤）。

### 4. 怎么知道它出了什么事

Android 的应用私有目录别人读不到。所以关键事件会**再抄一份**到：

```
/sdcard/Android/media/com.nuonuo.folia/diag.log
```

里面有：安装进度、进程启动命令、node 的全部输出（含两个子服务）、WebView 的 JS 控制台、页面加载错误。
应用私有目录里还有 `logs/api.log`、`logs/web.log`、`logs/runtime.log`，面板会一起 tail。

## 目录

```
app/                     Android 壳（纯 Java，零第三方依赖，不用 androidx）
  src/main/java/…/
    MainActivity.java    全屏 WebView + 隐藏面板 + 健康探测
    FoliaService.java    前台服务：起 node、守护、清理残留、看门狗
    EmbeddedRuntime.java 首次解压、版本标记、路径约定
    DiagLog.java         diag.log
    LogTailer.java       增量读日志
    PortProbe.java       回环端口探活
payload/
  entry.js               进程总管：派生 api / web 两个子进程
  api-entry.js           接口层（:4000）
  static.js              静态站（:3000，零依赖）
  lib/find-api.js        接口层目录探测
  test/                  node --test 单测
tools/
  align.py               替代 zipalign（官方版是 x86_64，手机上跑不了）
  make_assets.py         把运行时和网页打成 assets
  test_make_assets.py    清单字节数的回归测试
build.sh                 打包：aapt2 → javac → d8 → 对齐 → 签名（--compile-only 只编译）
version.properties       版本号唯一来源（build.sh 和 gradle 共用）
docs/
  HOW-IT-WORKS.md        更细的进程模型与文件布局
  LICENSES.md            许可证清单与分发义务
  ROADMAP.md             已知限制 / 后续计划
```

## 构建

不需要 Gradle，也不需要电脑 —— 一台装了 Termux 的手机就够：

```bash
# 1. 工具链
pkg install openjdk-17 aapt2 python3 unzip

# 2. 运行时（从 Termux 自己这份复制；必须是 arm64）
mkdir -p ~/folia-payload/native/bin ~/folia-payload/native/lib
cp $PREFIX/bin/node ~/folia-payload/native/bin/
cp $PREFIX/lib/libicu*.so* $PREFIX/lib/libcrypto.so.3 $PREFIX/lib/libssl.so.3 \
   $PREFIX/lib/libc++_shared.so $PREFIX/lib/libcares.so $PREFIX/lib/libffi.so \
   $PREFIX/lib/libsqlite3.so $PREFIX/lib/libz.so.1 ~/folia-payload/native/lib/

# 3. 网页与接口层
mkdir -p ~/folia-payload/app
cp payload/*.js ~/folia-payload/app/
cp -r payload/lib ~/folia-payload/app/
(cd /path/to/folia-major && npx vite build) && cp -r /path/to/folia-major/dist ~/folia-payload/app/web

# 接口层放这里（package 自带的依赖要一起拷）
mkdir -p ~/folia-payload/app/api/node_modules/@neteasecloudmusicapienhanced
cp -r /path/to/folia-major/node_modules/@neteasecloudmusicapienhanced/api \
      ~/folia-payload/app/api/node_modules/@neteasecloudmusicapienhanced/

# 4. 打包
NATIVE_DIR=~/folia-payload/native APP_PAYLOAD_DIR=~/folia-payload/app bash build.sh
# → build/Folia-<版本>-debug.apk
```

只想确认代码还能编译（不需要那 130 MB，CI 就是这么跑的）：

```bash
bash build.sh --compile-only
```

## 本地检查

```bash
bash -n build.sh
node --test payload/test/*.test.js
python3 -m unittest discover -s tools -p 'test_*.py'
```

## 已知限制

| 限制 | 说明 / 解法 |
|---|---|
| targetSdk 固定 28 | 见上文；想提上去要把 node 改名 `libnode.so` 放进 `lib/arm64-v8a/` |
| 首次启动慢 | 要解压约 130 MB，20–40 秒，之后秒开 |
| 只有 arm64 | 运行时是 arm64 的 node 与 `.so` |
| 音源自备 | 接口层是第三方实现，收费/版权曲目可能返回空 |
| 无锁屏控制 | 后台能继续播，但没有 MediaSession 卡片 |

## 关于分发

本仓库**不含**接口层实现，也**不发布任何二进制**：`payload/api/`、`payload/web/` 都在
`.gitignore` 里，只在构建时从本机拷进去；CI 只做编译检查，不产出 APK，也没有 Release 附件。
自己构建、自己用。

## 许可证

本仓库代码（壳 + 构建脚本）用 **MIT**，见 [LICENSE](LICENSE)。

**打包出来的 APK 不是 MIT 作品**：它包含 AGPL-3.0 的前端产物，以及一个未声明许可证的
第三方接口实现。自己构建自己用没问题；要公开分发二进制，请先看 [docs/LICENSES.md](docs/LICENSES.md)。
