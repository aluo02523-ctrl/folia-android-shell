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

## 为什么不带 proot？

早先的设计里 proot 是「兜底路线」：万一 App 不能直接 exec 自己的 node，就套一层 proot。
但实测下来它**从来没成功过**（SELinux 的 `untrusted_app` 域会拦住 proot 去 exec 第二个程序），
真正让 App 跑起来的始终是「直连 node」这条路。

所以 v1.12 把 proot 整条路线删了：

- 少 0.5 MB 二进制，少三个组件（proot / libtalloc / mksh）
- **顺带甩掉 GPLv2 与 LGPL-3.0 的源码义务**（见 `docs/LICENSES.md`）
- 兼容性没有任何下降 —— 因为它本来就没工作过

如果你在自己的设备上连直连都跑不起来（多见于把 targetSdk 提到 29+ 的改版），
正确解法不是找回 proot，而是把 `node` 改名成 `libnode.so` 放进 APK 的 `lib/arm64-v8a/`，
让系统把它当原生库解压 —— Android 保证那个目录可执行，SELinux 也不会拦。
老版本（含 proot）在 git 历史与 `v1.11.0` 那个 release 里都还查得到。

## 踩过的 7 个坑（看完能省两天）

| # | 现象 | 原因 / 修法 |
|---|---|---|
| 1 | `execve("/system/bin/sh"): Permission denied` | SELinux 的 `untrusted_app` 域不许执行 shell 域程序 → **随包带 mksh**，exec 它 |
| 2 | proot `execve("/usr/bin/xxx"): Permission denied` | proot 把宿主目录 bind 到 guest 的 `/usr/bin`、`/bin` 会失败。**后来整条 proot 路线都删了**（见 `docs/LICENSES.md`），这条当历史记录留着 |
| 3 | 升级 APK 后新文件不出现 | `isInstalled()` 只判断标记文件存在 → 改成**标记文件里存 versionName 并比对** |
| 4 | node 启动即死 `rc=13` | Termux 版 node 把 openssl 配置路径写死成 Termux 的目录，别的 App 读它 EACCES → 设 **`OPENSSL_CONF`** 指向随包的 `certs/openssl.cnf` |
| 5 | `EADDRINUSE 127.0.0.1:3000` | 同一份 entry 被起了两次 → 启动前**先探端口**，已在服务就跳过 |
| 6 | `File.setExecutable()` 不生效 | 部分 ROM 静默失败 → 改用 **`Os.chmod(path, 0755)`** |
| 7 | 页面一直黑屏 `ERR_CONNECTION_REFUSED` | WebView 比 node 先 `loadUrl` → **等端口探测通了再加载** |

另外：**targetSdk 必须是 28**。29+ 时 Android 会禁止 App 执行自己私有目录里的文件，整套运行时就跑不起来。

## 许可证与合规 ⚠️

本仓库的代码（Android 壳 + 构建脚本）用 **MIT**，见 [LICENSE](LICENSE)。

**但请注意：用本仓库构建出来的 APK 不是 MIT 作品。** 它里面还装着：

- **Folia 前端（dist）—— AGPL-3.0**：打包即分发 AGPL 作品，需提供完整源码与许可证全文

（proot 那条路线已经删掉了，GPLv2 / LGPL 组件不再进包）
- **网易云接口实现 —— 未声明许可证**（npm 上 `license` 字段为空）：再分发没有任何授权依据
- Node.js 及其一堆第三方（MIT / Apache-2.0 / BSD / Unicode…）

所以：**自己构建自己用没问题；要公开发布 APK 请先看 [docs/LICENSES.md](docs/LICENSES.md)。**
那份文档列了每个组件、对应义务，以及「只发源码 / 合规发 APK / 内部自用」三种做法。
