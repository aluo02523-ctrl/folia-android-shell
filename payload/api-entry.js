'use strict';
// ============================================================
// 网易云 API 的启动包装
//
// 为什么不直接 `node app.js`：原版 app.js 会
//   ① 同步去 npm 查版本（没网就卡住）
//   ② 无条件重新注册匿名 token
// 这两件事在离线/弱网下会把启动拖死。这里自己调用 serveNcmApi，
// 把版本检查关掉，匿名 token 用"有网就更新，没网就算了"的方式做。
// ============================================================

const path = require('path');
const fs = require('fs');
const os = require('os');

const API_DIR = path.join(__dirname, 'api', 'node_modules', '@neteasecloudmusicapienhanced', 'api');
const PORT = Number(process.env.API_PORT || 4000);
const HOST = '127.0.0.1';

function log() {
  console.log.apply(console, ['[api-entry]'].concat(Array.prototype.slice.call(arguments)));
}

async function prepareToken() {
  const tmp = os.tmpdir();
  const tokenFile = path.join(tmp, 'anonymous_token');
  // 临时目录不一定可写（比如 TMPDIR 指到了别人家的目录）——写不了就跳过，
  // 绝不能因此把整个 API 拖死
  try {
    if (!fs.existsSync(tokenFile)) {
      fs.writeFileSync(tokenFile, '', 'utf-8');
    }
  } catch (e) {
    log('临时目录不可写（跳过匿名 token）:', e && e.message);
    return;
  }
  try {
    // generateConfig 会联网注册匿名 token 并设好 global.cnIp；没网就 20 秒后放弃
    const gen = require(path.join(API_DIR, 'generateConfig.js'));
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
  // API 的模块都用相对路径 require，切进去最省心
  process.chdir(API_DIR);
  await prepareToken();

  const server = require(path.join(API_DIR, 'server.js'));
  await server.serveNcmApi({ port: PORT, host: HOST, checkVersion: false });
  log('网易云 API 已就绪 http://' + HOST + ':' + PORT);
})().catch(function (e) {
  console.error('[api-entry] 启动失败:', e && e.stack ? e.stack : e);
  process.exit(1);
});
