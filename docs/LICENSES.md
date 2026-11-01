# 许可证合规清单

> 最后更新：2026-11-02
>
> **一句话结论**：本仓库只发源码时是合规的；但**用它构建出来的 APK 不能随便公开分发** ——
> 里面有两样东西的授权状况很麻烦（见下面的 🔴）。

## 1. 本仓库自己的代码

| 内容 | 许可证 |
|---|---|
| `app/`（Android 壳）、`payload/*.js`、`tools/`、`build-*.sh` | **MIT**（见根目录 `LICENSE`）|

MIT 与下面任何许可证都兼容：把 MIT 代码放进一个 GPL/AGPL 的整体里是允许的，
只是**整体分发时要满足那个更强的许可证**。

## 2. 构建出的 APK 里到底装了什么

`build-manual.sh` 会把下面这些东西打进 APK（所以「分发 APK」= 分发它们）：

| 组件 | 许可证 | 分发时的义务 |
|---|---|---|
| **Folia 前端（`vite build` 的 dist）** | **AGPL-3.0** | 🔴 必须提供完整对应源码（含你的修改）、附 AGPL 全文、保留版权声明。AGPL 还要求「通过网络交互的用户」也能拿到源码 |
| **`@neteasecloudmusicapienhanced/api`** | **未声明** | 🔴 npm 上 `license` 字段为空 = 默认保留所有权利。**把它打进公开分发的 APK 是最危险的一步** |
| Node.js 运行时 | MIT（内含 V8: BSD-3、OpenSSL: Apache-2.0、ICU: Unicode、zlib: zlib、c-ares/llhttp/nghttp2 等） | 🟡 保留 Node 的 `LICENSE` 与所有第三方 notice |
| `proot` | **GPLv2** | 🟠 分发二进制需一并提供对应源码，或有效的书面获取方式 |
| `libtalloc.so.2`（proot 依赖） | **LGPL-3.0-or-later** | 🟠 需提供源码与许可证全文（动态链接可用「替换库」方式满足） |
| `libc++_shared.so` | Apache-2.0 WITH LLVM-exception | 🟡 附许可证全文 |
| `libsqlite3.so` | Public Domain | 🟢 无义务 |
| `libcares.so` / `libffi.so` | MIT | 🟢 保留声明 |
| `libz.so.1` | zlib License | 🟢 保留声明 |
| `libandroid-shmem.so` | Apache-2.0（以 Termux 包声明为准） | 🟡 附全文 |
| `mksh` | MirOS / BSD 系 | 🟢 保留声明 |
| `openssl.cnf` / `ca.pem` | Apache-2.0 / Mozilla CA 清单 | 🟡 附声明 |

> 注：具体版本以你构建时从 Termux 拷的那份为准；换版本请重新核对。

## 3. 三种做法，按风险从低到高

### 方案 A：只发源码（推荐，当前状态）

- 别人 clone 下来、自己构建、自己用 → 属于**私有复制**，不构成分发，AGPL/GPL 义务都不触发
- 仓库里只有 MIT 代码，README 说清楚「构建产物含有 AGPL 与未授权组件，请勿公开分发」
- ✅ 现在仓库就是这个状态，**合规**

### 方案 B：要发 APK，但合规地发

必须同时做到：

1. **把壳也改成 AGPL-3.0**（与本项目「打包 AGPL 前端」的定位一致，最省心）
2. Release 里附：AGPL 全文、Folia 上游源码地址（含 commit）、**你对前端的修改说明（当前：无修改）**
3. proot / libtalloc：附源码地址与许可证全文，或写一份有效的书面 offer
4. **把 `@neteasecloudmusicapienhanced/api` 从包里拿掉** —— 它没有许可证，
   再分发它没有任何授权依据。可行的替代：首次启动时提示用户自行安装，
   或者干脆不提供这一层（前端只连用户自己的 API 地址）
5. APK 里加一个「开源许可」页面，列出上面所有组件与许可证

### 方案 C：内部构建，不公开分发

- 自己手机上用、给朋友直接装 → 一般视为个人使用
- 一旦上架应用商店 / 公开发 Release / 打包进别人的项目，就回到方案 B 的义务

## 4. 我建议怎么改

1. **仓库保持 MIT + 只发源码**（已经是对的）
2. README 顶部加一句醒目提示：*构建产物包含 AGPL-3.0 与未授权第三方组件，请勿公开分发*
3. 如果哪天真要发 APK：按方案 B 一次做齐（壳改 AGPL、去掉 API 包、附许可证页）

## 5. 常见误解澄清

- ❌「MIT 项目里塞 GPL 代码没关系」→ 分发时整体要按 GPL/AGPL 走，MIT 只是"不额外添麻烦"
- ❌「没写许可证 = 可以随便用」→ 恰恰相反，**没写 = 保留所有权利**
- ❌「我只发 APK 不发源码，所以不用管」→ 分发二进制同样触发 GPL/AGPL 的源码义务
- ✅「我只在自己手机上编译自己用」→ 不算分发，义务不触发
