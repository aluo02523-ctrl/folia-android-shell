// 注入到页面的桥：把 <audio> 的状态推给 App，并接收 App 发来的控制指令。
//
// 不依赖前端实现任何东西：钩的是 HTMLMediaElement 的事件，
// 曲名/歌手优先读 navigator.mediaSession.metadata（前端如果设了就直接用）。
(function () {
  if (window.__foliaHooked) return;
  window.__foliaHooked = true;
  console.log('[bridge] injected, FoliaBridge=' + (typeof window.FoliaBridge));
  if (!window.FoliaBridge) {
    setTimeout(function () {
      console.log('[bridge] retry, FoliaBridge=' + (typeof window.FoliaBridge));
    }, 1500);
  }

  var el = null;

  function current() {
    if (el && !el.paused) return el;
    var list = document.querySelectorAll('audio, video');
    for (var i = 0; i < list.length; i++) {
      if (!list[i].paused && list[i].src) return list[i];
    }
    return el || list[0] || null;
  }

  function field(v) {
    return String(v == null ? '' : v).replace(/[\u0000-\u001f]/g, ' ');
  }

  function info() {
    var m = current();
    var md = (navigator.mediaSession && navigator.mediaSession.metadata) || null;
    var title = (md && md.title) || document.title || '';
    var artist = (md && md.artist) || '';
    var album = (md && md.album) || '';
    var art = '';
    if (md && md.artwork && md.artwork.length) {
      art = md.artwork[md.artwork.length - 1].src || '';
    }
    if (!art && m) {
      // 退一步：页面里第一张够大的图片，通常是封面
      var imgs = document.querySelectorAll('img');
      for (var i = 0; i < imgs.length; i++) {
        if (imgs[i].naturalWidth >= 100) {
          art = imgs[i].currentSrc || imgs[i].src;
          break;
        }
      }
    }
    var pos = m ? Math.round((m.currentTime || 0) * 1000) : 0;
    var dur = m && isFinite(m.duration) ? Math.round(m.duration * 1000) : 0;
    return [field(title), field(artist), field(album), field(art),
            m && !m.paused ? 1 : 0, pos, dur].join('\u0001');
  }

  var pushFailed = false;

  function report() {
    try {
      if (window.FoliaBridge && typeof window.FoliaBridge.state === 'function') {
        window.FoliaBridge.state(info());
      } else if (!pushFailed) {
        pushFailed = true;
        console.log('[bridge] no state(): ' + Object.keys(window.FoliaBridge || {}).join(','));
      }
    } catch (e) {
      if (!pushFailed) {
        pushFailed = true;
        console.log('[bridge] push failed: ' + e);
      }
    }
  }

  // 给 App 轮询用：不依赖 JS 接口，返回一行状态
  window.__foliaState = info;

  function adopt(e) {
    var t = e.target;
    if (!t || (t.tagName !== 'AUDIO' && t.tagName !== 'VIDEO')) return;
    el = t;
    report();
  }

  ['play', 'pause', 'ended', 'loadedmetadata', 'canplay', 'seeked'].forEach(function (name) {
    document.addEventListener(name, adopt, true);
  });

  setInterval(report, 1000);

  window.__foliaMediaCmd = function (cmd, value) {
    var m = current();
    if (!m) return;
    try {
      if (cmd === 'play') {
        m.play().catch(function () {});
      } else if (cmd === 'pause') {
        m.pause();
      } else if (cmd === 'toggle') {
        if (m.paused) m.play().catch(function () {}); else m.pause();
      } else if (cmd === 'seek') {
        var s = Number(value) / 1000;
        if (isFinite(s) && s >= 0) m.currentTime = s;
      } else if (cmd === 'next' || cmd === 'prev') {
        // 前端不一定有按钮；按常见的选择器/ aria 试探，找不到就算了
        var sel = cmd === 'next'
          ? ['#next', '.next', '[aria-label*="下一"]', '[title*="下一"]', '[aria-label*="next" i]', '[title*="next" i]']
          : ['#prev', '.prev', '[aria-label*="上一"]', '[title*="上一"]', '[aria-label*="previous" i]', '[title*="prev" i]'];
        for (var i = 0; i < sel.length; i++) {
          var b = document.querySelector(sel[i]);
          if (b) { b.click(); return; }
        }
        // 再退一步：发键盘事件，交给前端自己的快捷键处理
        document.dispatchEvent(new KeyboardEvent('keydown', {
          key: cmd === 'next' ? 'ArrowRight' : 'ArrowLeft',
          code: cmd === 'next' ? 'ArrowRight' : 'ArrowLeft',
          bubbles: true
        }));
      }
    } catch (e) {}
  };

  report();
})();
