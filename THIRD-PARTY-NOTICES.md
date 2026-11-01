# 第三方组件说明

**本仓库不包含任何第三方二进制**，只包含源码与构建脚本。
下面这些是「按本仓库的构建脚本打包成 APK 时」会用到的东西，请自行遵守各自的许可证。

| 组件 | 许可证 | 说明 |
|---|---|---|
| [Node.js](https://nodejs.org/) | MIT | 运行时本体（构建时从 Termux 的 `$PREFIX/bin/node` 复制） |
| [ICU](https://icu.unicode.org/) | Unicode License | `libicuuc / libicui18n / libicudata`，node 的国际化依赖 |
| [proot](https://proot-me.github.io/) | **GPLv2** | 兜底路线用的 chroot 工具。**如果你分发包含它的 APK，需要一并提供 proot 源码或书面 offer** |
| [mksh](https://github.com/MirBSD/mksh) | MirOS / BSD 系 | 随包带的 shell（不能 exec `/system/bin/sh`） |
| [OpenSSL](https://www.openssl.org/) | Apache-2.0 | `libssl / libcrypto` 与 `openssl.cnf` |
| [Folia](https://github.com/chthollyphile/folia-major) | 见其仓库 LICENSE | 前端与播放器本体，本仓库只打包它的**构建产物**（`vite build` 的 dist） |
| [@neteasecloudmusicapienhanced/api](https://www.npmjs.com/package/@neteasecloudmusicapienhanced/api) | MIT（以 npm 声明为准） | 第三方接口实现，**本仓库不包含其代码**，由使用者在本地 `npm install` |

## 关于 GPLv2（重要）

`proot` 是 GPLv2。也就是说：

- **只发布本仓库源码** → 不涉及 GPL 义务（你没有分发二进制）
- **发布一个含 proot 的 APK** → 属于「分发二进制」，需要按 GPLv2 提供对应源码，
  或给出有效的书面获取方式。最简单的做法是在 Release 说明里附上
  proot 的源码地址与版本。

顺带一提：本项目**运行时并不依赖 proot**（默认走「直连 node」，proot 只是兜底），
如果你不需要兜底路线，可以在构建时把 proot 和 mksh 从 payload 里去掉，就没有 GPL 组件了。

## 关于音源

网易云接口属于第三方逆向实现，**没有官方授权**。本仓库不内置任何账号、cookie、
会员凭据或音源代理，仅供个人学习研究使用。请自行准备合法账号，并遵守当地法律与服务条款。
