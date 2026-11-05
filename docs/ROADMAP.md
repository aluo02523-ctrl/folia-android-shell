# 路线图 / 已知限制

> 最后更新：2026-11-05

## 已完成

- [x] 全屏 WebView 壳（空状态、下拉呼出终端面板、沉浸式全屏）
- [x] 内置 node 运行时（node + ICU + 依赖 `.so`，解压到应用私有目录）
- [x] 首次启动解压进度、按版本号重解压、字节数校验
- [x] 前台服务守护 + 看门狗（稳定运行后重新计数）
- [x] 孤儿进程清理：`.pids` + 启动前杀残留，避免端口被占、看门狗挂空
- [x] 两个子服务各自写日志（`logs/api.log` / `logs/web.log`），面板一起 tail
- [x] 离线构建链：`aapt2 → javac → d8 → align.py → apksigner`，不依赖 Gradle
- [x] 构建脚本合并成一份 `build.sh`，带 `--compile-only`（CI 不需要 130MB 运行时就能验编译）
- [x] 版本号单一来源 `version.properties`（脚本和 Gradle 共用）
- [x] GitHub Actions：资源/Java 编译、payload 单测、构建工具单测、shellcheck
- [x] 诊断日志 `Android/media/<包名>/diag.log`（安装进度、进程输出、JS 控制台、HTTP 错误）
- [x] 打包时用预构建的静态站点，不再需要 vite / esbuild（省 100 MB+）

## 已知限制

| 限制 | 说明 |
|---|---|
| targetSdk 只能是 28 | 29+ 时 Android 禁止 App 执行自己私有目录里的文件，运行时就起不来 |
| 首次启动慢 | 需要解压约 130 MB 运行时，中端机约 20–40 秒，之后秒开 |
| 只支持 arm64 | 运行时用的是 arm64 的 node 与 `.so` |
| 音源需要自备 | 内置的是第三方接口实现，收费/版权曲目可能返回空 |
| 没有后台播放的锁屏控制 | WebView 音频能继续播，但没有 MediaSession 卡片 |
| App 内的 loopback 与外部隔离 | 同一台机器上别的 App 连不进 `127.0.0.1:3000`，属正常现象 |

## 想做的

1. **MediaSession**：锁屏/耳机线控、通知栏封面与进度
2. **摆脱 targetSdk 28**：node 改名 `lib/arm64-v8a/libnode.so`，让系统当原生库解压
   （顺带省掉 51 MB 的首次拷贝）
3. **减小包体**：把 ICU 换成 small-icu 或按需裁剪，能省 30 MB 左右
4. **断网兜底**：无网络时给出明确提示，而不是空列表
5. **x86_64 / arm32**：给模拟器和老机器留条路（要分别准备对应架构的 node）

## 不打算做

- 内置任何账号、cookie、会员凭据
- 内置音源地址或代理服务
- 修改播放器前端本身（这里只做壳）
