'use strict';
// 找到接口层（@neteasecloudmusicapienhanced/api）到底装在哪。
//
// 打包方式有两种，都认：
//   app/api/node_modules/@neteasecloudmusicapienhanced/api   ← 整棵 node_modules 照搬
//   app/api                                                   ← 只拷包本身
const fs = require('fs');
const path = require('path');

function candidates(root) {
  return [
    path.join(root, 'api', 'node_modules', '@neteasecloudmusicapienhanced', 'api'),
    path.join(root, 'api'),
  ];
}

/** 返回第一个带 server.js 的目录；都没有返回 null */
function resolveApiDir(root) {
  const dirs = candidates(root);
  for (let i = 0; i < dirs.length; i++) {
    if (fs.existsSync(path.join(dirs[i], 'server.js'))) {
      return dirs[i];
    }
  }
  return null;
}

module.exports = { candidates, resolveApiDir };
