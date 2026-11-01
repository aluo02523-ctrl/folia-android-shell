# 进度快照 · 2026-11-01 16:4x

## 这一轮做了什么

上一轮（zip 里的 docs/DONE.md）停在「阶段 0 环境验证完成」，卡在两件事：
①Netease API 没装 ②APK 只有设计稿。这一轮把 **APK 主体代码整套写完了**（v0.1 源码可编译形态）。

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 双 Tab（WebView / 终端）、状态条、权限横幅、⋯菜单 |
| `TermuxBridge.java` | 命令投递：Termux RunCommandService + 失败兜底写任务文件 |
| `LogTailer.java` | 500ms 增量 tail 两个日志文件 |
| `PortProbe.java` | HTTP 探测 3000/4000 |
| `build-manual.sh` | 离线手工打包（aapt2→javac→d8→对齐→签名） |
| `tools/align.py` | 替代 zipalign（官方版是 x86_64，手机跑不了）—— 已在本机自测通过 |

## 为什么走"手工打包"

Termux 里 Gradle+AGP 要下 200MB+，Maven 还被掐过 TLS；
这个 App 零第三方依赖，aapt2/javac/d8/apksigner 全在本地 → 干脆绕开 Gradle。

## 还没验证的（必须上手机才知道）

1. `TermuxBridge` 在 Android 17 上能不能真的拉起 Termux 的服务（系统可能拦）
2. `aapt2 link` 出来的 `resources.arsc` 是否已满足 Android 11+ 的"不压缩+对齐"要求
   （`align.py` 会强制 store + 4 字节对齐，理论上没问题）
3. WebView 加载 vite dev server 的兼容性（HMR 用的是 WebSocket，可能需要额外放行）
4. 日志 tail 在中文/多字节被截断时的边界（当前按字节读，理论上会切坏一个汉字，
   实际表现为偶尔一个乱码字符，等真机看到再修）

## 下一步（按顺序）

1. 在 Termux 跑 `bash build-manual.sh`，把它编出来，装上看
2. 装 NeteaseCloudMusicApi → 双服务跑通 → WebView 里能搜歌能播
3. v0.2：MediaSession 锁屏控制、服务自动重启、深色模式

## 通道备忘（重要）

这版 Moxw **没有设备 shell 工具**，且 `/sdcard` 只可写不可读。
所以开发期用回环 HTTP 桥：Termux 里跑 `python3 /sdcard/Download/Moxw/moxw-http.py`
→ 糯糯用 `web_fetch http://127.0.0.1:8899/sh?t=<token>&c=<命令>` 拿到输出。
