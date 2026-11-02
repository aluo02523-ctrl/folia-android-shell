# 它是怎么跑起来的

这篇只讲机制，不讲历史。

## 一、进程模型

```
┌───────────────────────────────┐  App 进程（Java，com.nuonuo.folia）
│ MainActivity                  │
│   WebView ──┐                 │
│ FoliaService│ 前台服务        │
└─────────────┼─────────────────┘
              │ exec
              ▼
┌───────────────────────────────┐  node 进程（同一个 UID）
│ payload/entry.js              │  进程总管
│   ├─ spawn api-entry.js       │
│   └─ spawn static.js          │
└───────────────────────────────┘
   │                    │
   ▼                    ▼
 127.0.0.1:4000      127.0.0.1:3000
 接口层               静态站（前端 dist）
```

- **为什么要前台服务**：node 一旦被系统回收，音乐就断。常驻通知告诉系统「我在干活」。
- **为什么让 node 当总管**：App 只需要管一个进程；两个子服务谁挂了，总管退出，
  看门狗再把整条链拉起来（最多 3 次，防止无限重启）。
- **进程间只走 HTTP**：App 与 node 之间没有别的通道，WebView 就是一个普通客户端。

## 二、文件布局

APK 里：

```
assets/native/bin/node        Node.js 可执行文件（arm64）
assets/native/lib/*.so        它的动态库
assets/payload.zip            前端 dist + 接口层 + entry.js
assets/install-plan.txt       每个文件的字节数（算进度、做校验）
```

首次启动解压到应用私有目录：

```
files/runtime/bin/node        ← 可执行（权限 0755，用 Os.chmod 设置）
files/runtime/lib/*.so        ← LD_LIBRARY_PATH 指这里
files/app/entry.js            进程总管
files/app/api-entry.js        接口层
files/app/static.js           静态站
files/app/web/                前端 dist
files/app/api/                接口层的依赖
files/app/certs/openssl.cnf   OpenSSL 配置（必须显式指定，见下）
files/app/logs/{api,web}.log  两个子进程的输出
files/app/.installed          解压标记，内容是 versionName
```

**标记文件里存版本号**：升级 APK 后版本号变了就重新解压；不变就跳过（省 30 秒）。

## 三、启动顺序

1. `MainActivity.onCreate` 布置界面，**不发任何请求**
2. 判断 `isInstalled()`：
   - 否 → 解压运行时（带进度条），完成后启动服务
   - 是 → 直接启动服务
3. `FoliaService`：
   - 先探一次 `3000` / `4000`，已经在服务就直接返回（避免重复启动）
   - `ProcessBuilder` 执行 `files/runtime/bin/node files/app/entry.js`
   - 注入环境变量（见下）
   - 把 node 的 stdout/stderr 同时写进 `logs/runtime.log` 和 `diag.log`
4. `entry.js` 派生两个子进程，各自把输出写进 `logs/api.log` / `logs/web.log`
5. `MainActivity` 每 2 秒探一次 `127.0.0.1:3000`；**探通之后才 `loadUrl`**
   （早加载只会拿到连接被拒，然后一直白屏）

## 四、进程环境变量

| 变量 | 值 | 为什么 |
|---|---|---|
| `LD_LIBRARY_PATH` | `files/runtime/lib` | node 的动态库在这里，系统默认搜不到 |
| `OPENSSL_CONF` | `files/app/certs/openssl.cnf` | 这份 node 把配置路径编译死成 Termux 的目录，别的 App 读会被拒 |
| `SSL_CERT_FILE` / `NODE_EXTRA_CA_CERTS` | `files/app/certs/ca.pem` | 同上：TLS 根证书 |
| `HOME` / `TMPDIR` | `files/app/home` / `files/app/tmp` | 可写的私有目录 |
| `WEB_PORT` / `API_PORT` | 3000 / 4000 | 端口约定 |
| `NODE_OPTIONS` | `--max-old-space-size=512` | 手机内存紧，别让 V8 吃太多 |

## 五、出错了怎么看

`/sdcard/Android/media/<包名>/diag.log` 是唯一的对外观察窗口：

```
[..] install start v=1.12 free=46040MB
[..] env targetSdk=28 deviceSdk=37 abi=arm64-v8a uid=10455
[..] direct cmd=…/runtime/bin/node …/app/entry.js
[entry] api pid=… web pid=…
[static] Folia web on http://127.0.0.1:3000
[api-entry] 网易云 API 已就绪 http://127.0.0.1:4000
[web] 端口已通，开始加载页面
[js] [ERROR] …（页面里的 console）
```

界面上的坑也应该先看这里：黑屏八成是 `[web] 加载失败 … ERR_CONNECTION_REFUSED`，
服务没起来八成是 `direct … 启动异常` 或 node 自己的报错。

## 六、几个必须守住的约束

1. **targetSdk = 28**：29+ 会禁止执行私有目录里的文件，整套运行时失效
2. **可执行文件用 `Os.chmod(0755)`**：`File.setExecutable()` 在部分 ROM 上会静默失败
3. **先探端口再 `loadUrl`**：node 从启动到 bind 需要几百毫秒到几秒
4. **启动前查重**：判断 3000/4000 已在服务就跳过，否则第二份会 `EADDRINUSE` 崩掉第一份
5. **`install-plan.txt` 的字节数必须准**：它同时用于进度和完整性校验
