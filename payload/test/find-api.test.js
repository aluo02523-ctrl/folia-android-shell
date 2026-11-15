'use strict';
// 接口层目录的两种打包布局都要能找到。
const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { resolveApiDir } = require('../lib/find-api.js');

function makeRoot(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'folia-api-'));
  for (const rel of files) {
    const full = path.join(root, rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, '// stub');
  }
  return root;
}

test('认整棵 node_modules 布局', () => {
  const root = makeRoot(['api/node_modules/@neteasecloudmusicapienhanced/api/server.js']);
  assert.equal(
    resolveApiDir(root),
    path.join(root, 'api', 'node_modules', '@neteasecloudmusicapienhanced', 'api')
  );
  fs.rmSync(root, { recursive: true, force: true });
});

test('认只拷包本身的布局', () => {
  const root = makeRoot(['api/server.js']);
  assert.equal(resolveApiDir(root), path.join(root, 'api'));
  fs.rmSync(root, { recursive: true, force: true });
});

test('没有 server.js 就返回 null', () => {
  const root = makeRoot(['api/README.md']);
  assert.equal(resolveApiDir(root), null);
  fs.rmSync(root, { recursive: true, force: true });
});
