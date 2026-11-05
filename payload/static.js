'use strict';
// 静态站（:3000）：把打包好的 dist 发出去。
// 零依赖、不压缩、带 Range 和 SPA 回退。前端自己调 :4000 的接口层。

const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(process.env.WEB_ROOT || path.join(__dirname, 'web'));

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
  fs.stat(file, function (err, st) {
    if (err || !st.isFile()) {
      // 单页应用：找不到的路径一律回 index.html，交给前端路由
      const index = path.join(ROOT, 'index.html');
      if (file !== index) return sendFile(req, res, index);
      return send(res, 404, '404 ' + file);
    }

    const ext = path.extname(file).toLowerCase();
    const headers = {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Content-Length': st.size,
      // 带 hash 的产物长缓存，index.html 和 sw 每次问一下
      'Cache-Control': /\/assets\//.test(file) ? 'public, max-age=31536000, immutable' : 'no-cache',
      'Access-Control-Allow-Origin': '*',
    };

    if (req.method === 'HEAD') {
      res.writeHead(200, headers);
      return res.end();
    }

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
      return fs.createReadStream(file, { start: start, end: end }).pipe(res);
    }

    res.writeHead(200, headers);
    fs.createReadStream(file).pipe(res);
  });
}

function createServer() {
  return http.createServer(function (req, res) {
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
    if (pathname === '/__health') {
      return send(res, 200, 'ok');
    }
    sendFile(req, res, file);
  });
}

if (require.main === module) {
  const PORT = Number(process.env.WEB_PORT || 3000);
  const HOST = '127.0.0.1';
  const server = createServer();
  server.on('error', function (e) {
    console.error('[static] 监听失败:', e && e.message);
    process.exit(1);
  });
  server.listen(PORT, HOST, function () {
    console.log('[static] Folia web on http://' + HOST + ':' + PORT + '  root=' + ROOT);
  });
}

module.exports = { createServer, ROOT };
