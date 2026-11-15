'use strict';
// 静态站的边界行为：穿越、Range、SPA 回退、方法限制。
const { test, before, after } = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'folia-web-'));
fs.mkdirSync(path.join(ROOT, 'assets'));
fs.writeFileSync(path.join(ROOT, 'index.html'), '<h1>folia</h1>');
fs.writeFileSync(path.join(ROOT, 'assets', 'app.js'), 'console.log(1)');
fs.writeFileSync(path.join(ROOT, 'assets', 'blob.bin'), '0123456789');
// ROOT 在模块加载时读环境变量，所以必须在 require 之前设好
process.env.WEB_ROOT = ROOT;

const { createServer } = require('../static.js');

let server;
let port;

before(async () => {
  server = createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  port = server.address().port;
});

after(() => {
  server.close();
  fs.rmSync(ROOT, { recursive: true, force: true });
});

function request(pathname, options) {
  const opt = options || {};
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: '127.0.0.1',
        port: port,
        path: pathname,
        method: opt.method || 'GET',
        headers: opt.headers || {},
      },
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () =>
          resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString() })
        );
      }
    );
    req.on('error', reject);
    req.end();
  });
}

test('/__health 直接回 ok', async () => {
  const r = await request('/__health');
  assert.equal(r.status, 200);
  assert.equal(r.body, 'ok');
});

test('/ 回 index.html 且不缓存', async () => {
  const r = await request('/');
  assert.equal(r.status, 200);
  assert.match(r.body, /folia/);
  assert.equal(r.headers['cache-control'], 'no-cache');
});

test('assets 下的产物长缓存', async () => {
  const r = await request('/assets/app.js');
  assert.equal(r.status, 200);
  assert.equal(r.headers['content-type'], 'text/javascript; charset=utf-8');
  assert.match(r.headers['cache-control'], /immutable/);
});

test('找不到的路径回 index.html（SPA 回退）', async () => {
  const r = await request('/song/123456');
  assert.equal(r.status, 200);
  assert.match(r.body, /folia/);
});

test('只认 GET / HEAD', async () => {
  const r = await request('/', { method: 'POST' });
  assert.equal(r.status, 405);
});

test('HEAD 不返回 body', async () => {
  const r = await request('/', { method: 'HEAD' });
  assert.equal(r.status, 200);
  assert.equal(r.body, '');
  assert.ok(Number(r.headers['content-length']) > 0);
});

test('目录穿越必须 403', async () => {
  const r = await request('/%2e%2e/package.json');
  assert.equal(r.status, 403);
});

test('Range 返回 206 和正确片段', async () => {
  const r = await request('/assets/blob.bin', { headers: { Range: 'bytes=2-4' } });
  assert.equal(r.status, 206);
  assert.equal(r.body, '234');
  assert.equal(r.headers['content-range'], 'bytes 2-4/10');
  assert.equal(r.headers['content-length'], '3');
});

test('Range 越界返回 416', async () => {
  const r = await request('/assets/blob.bin', { headers: { Range: 'bytes=99-' } });
  assert.equal(r.status, 416);
  assert.equal(r.headers['content-range'], 'bytes */10');
});
