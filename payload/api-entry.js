'use strict';
// 接口层（:4000）的启动包装。
//
// 不直接 `node app.js`：原版启动会同步查 npm 版本、无条件重注册匿名 token，
// 弱网下能把启动拖死。这里关掉版本检查，token 改成"有网就更新，没网就算"。

const path = require('path');
const fs = require('fs');
const os = require('os');
const { resolveApiDir } = require('./lib/find-api.js');

const PORT = Number(process.env.API_PORT || 4000);
const HOST = '127.0.0.1';

function log() {
  console.log.apply(console, ['[api-entry]'].concat(Array.prototype.slice.call(arguments)));
}

async function prepareToken(apiDir) {
  const tokenFile = path.join(os.tmpdir(), 'anonymous_token');
  try {
    if (!fs.existsSync(tokenFile)) {
      fs.writeFileSync(tokenFile, '', 'utf-8');
    }
  } catch (e) {
    log('临时目录不可写（跳过匿名 token）:', e && e.message);
    return;
  }
  try {
    const gen = require(path.join(apiDir, 'generateConfig.js'));
    await Promise.race([
      Promise.resolve(gen()).catch(function (e) {
        log('生成配置失败（不影响启动）:', e && e.message);
      }),
      new Promise(function (r) {
        setTimeout(r, 20000);
      }),
    ]);
    log('匿名 token 准备好了');
  } catch (e) {
    log('生成配置异常（不影响启动）:', e && e.message);
  }
}

(async function main() {
  const apiDir = resolveApiDir(__dirname);
  if (!apiDir) {
    console.error(
      '[api-entry] 找不到接口层。期望其中一个：\n  ' +
        path.join(__dirname, 'api', 'node_modules', '@neteasecloudmusicapienhanced', 'api') +
        '\n  ' +
        path.join(__dirname, 'api')
    );
    process.exit(1);
  }
  log('接口层目录:', apiDir);

  // 接口层的模块都用相对路径 require，切进去最省心
  process.chdir(apiDir);
  await prepareToken(apiDir);

  const server = require(path.join(apiDir, 'server.js'));
  await server.serveNcmApi({ port: PORT, host: HOST, checkVersion: false });
  log('网易云 API 已就绪 http://' + HOST + ':' + PORT);
})().catch(function (e) {
  console.error('[api-entry] 启动失败:', e && e.stack ? e.stack : e);
  process.exit(1);
});
