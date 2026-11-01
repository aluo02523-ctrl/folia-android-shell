// Folia 静态服务器（零依赖，替代 vite dev server）
// 作用：把打包好的 dist 目录当作网站发出去，端口 3000。
// 为什么不用 vite：vite 需要 esbuild + 一整套开发依赖（100MB+），
// 生产环境根本用不上，一个 60 行的 http 服务器就够了。
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, 'web');
const PORT = Number(process.env.WEB_PORT || 3000);
const HOST = '127.0.0.1';

// 只用得到这几种，够用就行
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.mp3': 'audio/mpeg',
  '.ogg': 'audio/ogg',
  '.wav': 'audio/wav',
  '.flac': 'audio/flac',
  '.m4a': 'audio/mp4',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
};

function send(res, code, body, extra) {
  res.writeHead(code, Object.assign({ 'Content-Type': 'text/plain; charset=utf-8' }, extra || {}));
  res.end(body);
}

function sendFile(req, res, file) {
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) {
      // 单页应用：找不到的路径一律回 index.html（前端路由自己处理）
      if (file !== path.join(ROOT, 'index.html')) {
        return sendFile(req, res, path.join(ROOT, 'index.html'));
      }
      return send(res, 404, '404 ' + file);
    }
    const ext = path.extname(file).toLowerCase();
    const headers = {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Content-Length': st.size,
      // 带 hash 的产物可以长缓存；index.html 和 sw 必须每次问一下
      'Cache-Control': /\/assets\//.test(file)
        ? 'public, max-age=31536000, immutable'
        : 'no-cache',
      // 前端要调 127.0.0.1:4000 的 API，同源策略放行
      'Access-Control-Allow-Origin': '*',
    };
    // 简易 Range 支持（万一有人直接 <audio src> 本机文件）
    const range = req.headers.range;
    if (range && /^bytes=\d*-\d*$/.test(range)) {
      const m = /^bytes=(\d*)-(\d*)$/.exec(range);
      let start = m[1] === '' ? null : parseInt(m[1], 10);
      let end = m[2] === '' ? null : parseInt(m[2], 10);
      if (start === null) {
        start = st.size - Math.min(end === null ? 0 : end, st.size);
        end = st.size - 1;
      } else if (end === null || end >= st.size) {
        end = st.size - 1;
      }
      if (start > end || start >= st.size) {
        return send(res, 416, 'range not satisfiable', { 'Content-Range': 'bytes */' + st.size });
      }
      headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + st.size;
      headers['Content-Length'] = end - start + 1;
      headers['Accept-Ranges'] = 'bytes';
      res.writeHead(206, headers);
      return fs.createReadStream(file, { start, end }).pipe(res);
    }
    res.writeHead(200, headers);
    fs.createReadStream(file).pipe(res);
  });
}

const server = http.createServer((req, res) => {
  let pathname;
  try {
    pathname = decodeURIComponent((req.url || '/').split('?')[0]);
  } catch (e) {
    return send(res, 400, 'bad url');
  }
  if (pathname === '/' || pathname === '') pathname = '/index.html';

  // 防目录穿越：拼完必须还在 ROOT 里
  const file = path.normalize(path.join(ROOT, pathname));
  if (file !== ROOT && !file.startsWith(ROOT + path.sep)) {
    return send(res, 403, 'forbidden');
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return send(res, 405, 'method not allowed');
  }

  // 健康检查，启动脚本/App 轮询用
  if (pathname === '/__health') {
    return send(res, 200, 'ok');
  }
  sendFile(req, res, file);
});

server.listen(PORT, HOST, () => {
  console.log('[static] Folia web on http://' + HOST + ':' + PORT + '  root=' + ROOT);
});
