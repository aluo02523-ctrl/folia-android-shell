'use strict';
// 进程总管：起 api-entry.js(:4000) 和 static.js(:3000)。
// 两个服务各自一个进程、各自一份日志；谁挂了就把另一个收掉整体退出，
// 由 Java 侧看门狗重拉整条链。pid 写进 .pids，供 App 停止时清理。

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const APP_DIR = __dirname;
const LOG_DIR = path.join(APP_DIR, 'logs');
const PID_FILE = path.join(APP_DIR, '.pids');
const SERVICES = [
  { name: 'api', script: 'api-entry.js' },
  { name: 'web', script: 'static.js' },
];

const children = new Map();
let stopping = false;

function log() {
  console.log.apply(console, ['[entry]'].concat(Array.prototype.slice.call(arguments)));
}

function writePids() {
  try {
    const lines = ['entry ' + process.pid];
    children.forEach(function (child, name) {
      if (child.pid) lines.push(name + ' ' + child.pid);
    });
    fs.writeFileSync(PID_FILE, lines.join('\n') + '\n');
  } catch (e) {
    log('写 pid 文件失败:', e && e.message);
  }
}

function killAll(signal) {
  children.forEach(function (child) {
    try {
      child.kill(signal);
    } catch (e) {
      // 已经没了
    }
  });
}

function shutdown(code) {
  if (stopping) return;
  stopping = true;
  killAll('SIGTERM');
  setTimeout(function () {
    killAll('SIGKILL');
    process.exit(code);
  }, 1200).unref();
}

function start(service) {
  const out = fs.createWriteStream(path.join(LOG_DIR, service.name + '.log'), { flags: 'w' });
  const child = spawn(process.execPath, [path.join(APP_DIR, service.script)], {
    cwd: APP_DIR,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  children.set(service.name, child);
  writePids();

  function relay(chunk) {
    out.write(chunk);
    process.stdout.write(chunk);
  }
  child.stdout.on('data', relay);
  child.stderr.on('data', relay);

  child.on('error', function (e) {
    log(service.name, '启动失败:', e && e.message);
  });
  child.on('exit', function (code, signal) {
    out.end();
    children.delete(service.name);
    writePids();
    if (stopping) return;
    log(service.name + ' 退出 code=' + code + ' signal=' + signal + '，收掉全部');
    shutdown(1);
  });
}

process.on('SIGTERM', function () {
  log('收到 SIGTERM，收掉两个子进程');
  shutdown(0);
});
process.on('SIGINT', function () {
  shutdown(0);
});
process.on('exit', function () {
  killAll('SIGKILL');
  try {
    fs.unlinkSync(PID_FILE);
  } catch (e) {
    // 没写过就算了
  }
});

fs.mkdirSync(LOG_DIR, { recursive: true });
SERVICES.forEach(start);
log('api(:4000) 与 web(:3000) 已拉起');
