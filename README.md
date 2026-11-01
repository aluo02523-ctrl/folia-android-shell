# Folia Android Shell

给 [Folia](https://github.com/chthollyphile/folia-major)（一个动态歌词音乐播放器）做的 Android 壳。

**满屏只有 Folia，一个多余像素都没有。** 内置 node 运行时，装完不依赖任何外部终端。

```
┌─────────────────────────────┐
│                             │
│                             │
│      WebView 127.0.0.1:3000 │   ← 平时只有这个
│                             │
│                             │
└─────────────────────────────┘
   从顶边往下拉 ↓
┌─────────────────────────────┐
│ ● 启动  停止  清屏        ✕ │   ← 藏起来的终端面板
│ [static] Folia web on :3000 │
│ [api-entry] 网易云 API :4000│
└─────────────────────────────┘
```

## 它做了什么

App 里自带一份 node 运行时（node + ICU + 依赖 `.so`，约 96 MB），启动时：

```
App ──exec──▶ files/runtime/bin/node  files/app/entry.js
                  ├── api-entry.js  →  127.0.0.1:4000  网易云 API
                  └── static.js     →  127.0.0.1:3000  Folia 静态站
```

- 前端是**预构建**的 `vite build` 产物（不需要 vite/esbuild，省 100MB+）
- proot + mksh 也随包带着，只作为兜底路线
- 应用目录里的 `diag.log`（`/sdcard/Android/media/<包名>/diag.log`）记录安装进度、
  proot 命令、node 输出、JS 控制台、页面加载错误 —— 黑盒调试全靠它

## 目录

```
app/                     Android 壳（纯 Java，零第三方依赖，不用 androidx）
payload/                 entry.js / api-entry.js / static.js / start.sh
tools/align.py           替代 zipalign（官方的是 x86_64，手机跑不了）
tools/make_assets.py     把运行时和网页打成 assets
build-manual.sh          ⭐ 离线打包：aapt2 → javac → d8 → 对齐 → apksigner
docs/SELF-CONTAINED.md   内置运行时的设计与完整构建流程
docs/ROADMAP.md          已完成 / 已知限制 / 想做的
```

## 怎么构建（Termux，无需电脑）

```bash
# 1. 工具链
pkg install openjdk-17 aapt2 python3 unzip

# 2. 准备运行时（从 Termux 自己这份拷）
mkdir -p ~/folia-payload/native/bin ~/folia-payload/native/lib
cp $PREFIX/bin/node $PREFIX/bin/proot ~/folia-payload/native/bin/
cp $PREFIX/lib/libicu*.so* $PREFIX/lib/libcrypto.so.3 $PREFIX/lib/libssl.so.3 \
   $PREFIX/lib/libc++_shared.so $PREFIX/lib/libcares.so $PREFIX/lib/libffi.so \
   $PREFIX/lib/libsqlite3.so $PREFIX/lib/libz.so.1 $PREFIX/lib/libtalloc.so.2 \
   $PREFIX/lib/libandroid-shmem.so ~/folia-payload/native/lib/
# mksh（Termux 包里就有）
cp $PREFIX/bin/mksh ~/folia-payload/native/bin/

# 3. 应用 payload
mkdir -p ~/folia-payload/app
cp payload/*.js ~/folia-payload/app/
(cd /path/to/folia-major && npx vite build)
cp -r /path/to/folia-major/dist ~/folia-payload/app/web
mkdir -p ~/folia-payload/app/api
cp -r /path/to/folia-major/node_modules/@neteasecloudmusicapienhanced/api/* ~/folia-payload/app/api/

# 4. 打包
NATIVE_DIR=~/folia-payload/native APP_PAYLOAD_DIR=~/folia-payload/app bash build-manual.sh
# → build/Folia-x.y-debug.apk
```

## 踩过的 7 个坑（看完能省两天）

| # | 现象 | 原因 / 修法 |
|---|---|---|
| 1 | `execve("/system/bin/sh"): Permission denied` | SELinux 的 `untrusted_app` 域不许执行 shell 域程序 → **随包带 mksh**，exec 它 |
| 2 | proot `execve("/usr/bin/xxx"): Permission denied` | proot 把宿主目录 bind 到 guest 的 `/usr/bin`、`/bin` 会失败 → 改 bind 到 **`/usr/local/bin`** |
| 3 | 升级 APK 后新文件不出现 | `isInstalled()` 只判断标记文件存在 → 改成**标记文件里存 versionName 并比对** |
| 4 | node 启动即死 `rc=13` | Termux 版 node 把 openssl 配置路径写死成 Termux 的目录，别的 App 读它 EACCES → 设 **`OPENSSL_CONF`** 指向随包的 `certs/openssl.cnf` |
| 5 | `EADDRINUSE 127.0.0.1:3000` | 同一份 entry 被起了两次 → 启动前**先探端口**，已在服务就跳过 |
| 6 | `File.setExecutable()` 不生效 | 部分 ROM 静默失败 → 改用 **`Os.chmod(path, 0755)`** |
| 7 | 页面一直黑屏 `ERR_CONNECTION_REFUSED` | WebView 比 node 先 `loadUrl` → **等端口探测通了再加载** |

另外：**targetSdk 必须是 28**。29+ 时 Android 会禁止 App 执行自己私有目录里的文件，整套运行时就跑不起来。

## 许可证

本仓库的代码（Android 壳 + 构建脚本）用 **MIT**，见 [LICENSE](LICENSE)。

打包成 APK 时会用到的第三方组件（Node.js / ICU / proot / mksh / OpenSSL）以及
**proot 是 GPLv2 带来的义务**，都写在 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 里 ——
只发布源码不涉及 GPL；要发 APK 的话请照着那份说明处理。

音源接口是第三方实现，本仓库不内置任何账号或凭据，仅供个人学习使用。
