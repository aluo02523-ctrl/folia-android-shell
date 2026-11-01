# 开源 / 上架 GitHub 的检查清单

## 结论：**值得开，但只开「壳 + 构建脚本」，不开二进制**

真正有价值的源码只有 **296 KB**：

| 内容 | 大小 | 上不上 |
|---|---|---|
| `app/`（Java 源码 + 布局） | ~171 KB | ✅ |
| `build-manual.sh` / `build-workspace.sh` | ~10 KB | ✅ |
| `tools/align.py`（自写 zipalign） | 5 KB | ✅ |
| `tools/make_assets.py` | 5 KB | ✅ |
| `payload/*.js`（entry / api-entry / static） | 24 KB | ✅ |
| `docs/` | 16 KB | ✅ |
| `assets/` + `build-ws/`（node 51MB + ICU 44MB） | **337 MB** | ❌ 绝不进 git |
| `*.apk`（70 MB） | 70 MB | ❌ 用 Releases，或干脆不发 |
| `debug.keystore` | 2.6 KB | ❌ 秘密 |
| `payload/api/`（npm 装的 API + 依赖 65MB） | 65 MB | ❌ 脚本现装 |

`.gitignore` 已经写好挡住上面这些。

## 上之前必须处理的四件事

### 1. 凭据 / 隐私
- [x] `tools/moxw-http.py` 里硬编码的通道 token 已改成**只从环境变量读**
- [ ] 检查 `docs/` 有没有写你自己的手机路径、token、cookie（有就删或打码）
- [ ] 确认历史提交里没有 `*.keystore`

### 2. 许可证
我们的代码建议 **MIT**。但要知道会被打进 APK 的第三方：

| 组件 | 许可证 | 影响 |
|---|---|---|
| Node.js | MIT | 随包分发没问题，保留 license |
| ICU（libicu*.so） | Unicode License | 同上 |
| proot | **GPLv2** | 分发含 proot 的 APK 要一并提供 proot 源码或书面 offer |
| mksh | MirOS / BSD 系 | 宽松 |
| Folia 上游 | 见其 LICENSE | 只打包构建产物，别塞源码 |
| @neteasecloudmusicapienhanced/api | MIT（以 npm 声明为准） | 见第 3 条 |

> 只开源「壳 + 脚本」（不含 APK）基本没有 GPL 传染问题 —— 因为你不分发二进制。

### 3. 网易云 API 的灰色地带
`@neteasecloudmusicapienhanced/api` 是第三方逆向实现，**无官方授权**。
- 仓库本身一般不违规（npm 上就是公开的）
- 做成「开箱即用的听歌 App」并到处传播，容易被投诉
- 建议 README 写明：仅供个人学习，自行准备音源与合法账号，**不预置任何 cookie / 会员凭据**

### 4. 名字诚实一点
比如 `folia-android-shell` ——「给 Folia 做的 Android 壳：全屏 WebView + 内置 node 运行时」。

## README 必须写清的 7 个坑（否则别人 100% 跑不起来）

1. 需要 Termux + `openjdk-17` + `aapt2`（Termux 版）+ `android-sdk`
2. node 二进制要从 Termux 自己那份拷（`$PREFIX/bin/node` + 依赖 `.so`）
3. **targetSdk 必须是 28**（29+ 会被 SELinux 挡住私有目录 exec）
4. **不能 exec `/system/bin/sh`**，要随包带 `mksh`
5. **node 必须设 `OPENSSL_CONF`**，否则 Termux 版 node 去读它自己家的 `openssl.cnf` 被 EACCES 打死（rc=13）
6. proot 的 bind 目标不能用 `/usr/bin`、`/bin`，要用 `/usr/local/bin`
7. WebView 要等端口探测通了再 `loadUrl`，否则 `ERR_CONNECTION_REFUSED` 然后一直黑屏

这 7 条就是整个项目 90% 的坑，写成「踩坑清单」会非常受欢迎。
