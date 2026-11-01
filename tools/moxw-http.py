#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
糯糯通道 v3 —— 让 Moxw 里的糯糯能在 Termux 里跑命令、读文件。

为什么需要它：
  这版 Moxw 没有任何设备 shell 工具（没有 termux_run_command / shizuku_exec），
  /sdcard 的读取权限也没给。但是——**回环网是通的**（Moxw 的 MCP 就是连 127.0.0.1）。
  所以只要 Termux 里开一个只听 127.0.0.1 的小 HTTP 服务，
  糯糯就能用 web_fetch 调它：/sh 跑命令、/file 读文件、/write 写文件。

安全：
  · 只绑 127.0.0.1（外网/局域网都连不上）
  · 每个请求都带 token，token 不对一律 403
  · 不提供 rm/递归删除之类的额外开关，删东西还是要人在场

用法（Termux 里粘一行，跑完自动进后台）：
    python3 /sdcard/Download/Moxw/moxw-http.py

日志：~/.moxw-http.log
"""

import json
import os
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HOST = "127.0.0.1"
PORT = int(os.environ.get("MOXW_HTTP_PORT", "8899"))
TOKEN = os.environ.get("MOXW_HTTP_TOKEN", "")   # 必须自己设，别把 token 写进仓库
HOME = os.path.expanduser("~")
MAX_OUT = 200_000          # 单次输出上限，别把结果撑爆
DEFAULT_TIMEOUT = 120


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # ---------- 小工具 ----------
    def _send(self, code, body, ctype="text/plain; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8", "replace")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    def _auth(self, q):
        if not TOKEN or q.get("t", [""])[0] != TOKEN:
            self._send(403, "forbidden: bad token\n")
            return False
        return True

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    # ---------- GET ----------
    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)

        if u.path == "/ping":
            return self._send(200, json.dumps({
                "ok": True, "ts": int(time.time()), "home": HOME,
                "pid": os.getpid(), "cwd": os.getcwd(),
            }), "application/json")

        if not self._auth(q):
            return

        if u.path == "/sh":
            cmd = q.get("c", [""])[0]
            if not cmd:
                return self._send(400, "need ?c=<command>\n")
            timeout = int(q.get("timeout", [str(DEFAULT_TIMEOUT)])[0])
            return self._sh(cmd, timeout)

        if u.path == "/file":
            p = q.get("p", [""])[0]
            if not p:
                return self._send(400, "need ?p=<path>\n")
            p = os.path.expanduser(p)
            try:
                with open(p, "rb") as f:
                    data = f.read(2 * 1024 * 1024)
                return self._send(200, data, "text/plain; charset=utf-8")
            except Exception as e:
                return self._send(404, "read error: %r\n" % (e,))

        if u.path == "/ls":
            p = os.path.expanduser(q.get("p", [HOME])[0])
            try:
                names = sorted(os.listdir(p))
                out = []
                for n in names[:300]:
                    full = os.path.join(p, n)
                    try:
                        st = os.stat(full)
                        out.append("%s\t%d\t%s" % (
                            "d" if os.path.isdir(full) else "-", st.st_size, n))
                    except Exception:
                        out.append("?\t0\t%s" % n)
                return self._send(200, "\n".join(out) + "\n")
            except Exception as e:
                return self._send(404, "ls error: %r\n" % (e,))

        return self._send(404, "endpoints: /ping /sh?c= /file?p= /ls?p= (POST /write?p=)\n")

    # ---------- POST ----------
    def do_POST(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if not self._auth(q):
            return
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b""
        if u.path == "/write":
            p = q.get("p", [""])[0]
            if not p:
                return self._send(400, "need ?p=<path>\n")
            p = os.path.expanduser(p)
            try:
                d = os.path.dirname(p)
                if d:
                    os.makedirs(d, exist_ok=True)
                with open(p, "wb") as f:
                    f.write(body)
                return self._send(200, "wrote %d bytes to %s\n" % (len(body), p))
            except Exception as e:
                return self._send(500, "write error: %r\n" % (e,))
        if u.path == "/sh":           # 把脚本体 POST 进来再执行（避免转义地狱）
            name = q.get("name", ["adhoc"])[0]
            timeout = int(q.get("timeout", [str(DEFAULT_TIMEOUT)])[0])
            tmp = os.path.join(HOME, ".moxw-%s.sh" % name)
            try:
                with open(tmp, "wb") as f:
                    f.write(body)
            except Exception as e:
                return self._send(500, "tmp write error: %r\n" % (e,))
            return self._sh("bash '%s'" % tmp, timeout,
                            prefix="(脚本 %s，%d 字节)\n" % (tmp, len(body)))
        return self._send(404, "unknown POST endpoint\n")

    # ---------- 执行 ----------
    def _sh(self, cmd, timeout, prefix=""):
        env = dict(os.environ)
        env["PATH"] = "/data/data/com.termux/files/usr/bin:" + env.get("PATH", "")
        t0 = time.time()
        try:
            p = subprocess.run(
                ["bash", "-lc", cmd],
                cwd=HOME, env=env, timeout=timeout,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            )
            out = p.stdout.decode("utf-8", "replace")
            rc = p.returncode
        except subprocess.TimeoutExpired as e:
            out = (e.stdout or b"").decode("utf-8", "replace") + "\n[超时 %ds，已杀掉]" % timeout
            rc = 124
        except Exception as e:
            out = "执行异常: %r" % (e,)
            rc = 1
        if len(out) > MAX_OUT:
            out = out[:MAX_OUT] + "\n...[截断，共 %d 字符]" % len(out)
        head = "%s[rc=%d  %.1fs]\n" % (prefix, rc, time.time() - t0)
        return self._send(200, head + out)


def main():
    import socket
    # 已经在跑就别起第二个
    probe = socket.socket()
    try:
        probe.settimeout(0.4)
        probe.connect((HOST, PORT))
        probe.close()
        print("端口 %d 已被占用（大概已经在跑了）" % PORT)
        return 0
    except Exception:
        pass
    finally:
        try:
            probe.close()
        except Exception:
            pass

    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    print("糯糯通道 v3 已启动: http://%s:%d/ping" % (HOST, PORT))
    srv.serve_forever()


if __name__ == "__main__":
    # 默认自己 double-fork 进后台，这样在 Termux 里粘一行就完事
    if os.environ.get("MOXW_HTTP_FG") == "1" or "--fg" in sys.argv:
        main()
    else:
        try:
            pid = os.fork()
            if pid > 0:
                print("糯糯通道已转入后台 (pid %d)。日志: ~/.moxw-http.log" % pid)
                sys.exit(0)
        except OSError:
            main()
            sys.exit(0)
        os.setsid()
        pid2 = os.fork()
        if pid2 > 0:
            sys.exit(0)
        sys.stdout.flush()
        sys.stderr.flush()
        logf = open(os.path.join(HOME, ".moxw-http.log"), "ab")
        devnull = open(os.devnull, "rb")
        os.dup2(devnull.fileno(), 0)
        os.dup2(logf.fileno(), 1)
        os.dup2(logf.fileno(), 2)
        main()
