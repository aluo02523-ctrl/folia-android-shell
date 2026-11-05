package com.nuonuo.folia;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 全屏壳：只放一个 WebView，其余全部藏起来。
 *
 * - 沉浸式，不画标题栏、状态栏、导航栏
 * - 日志面板从屏幕顶边下拉（或点一下顶边）才出现，服务起来后自动收起
 * - 零第三方依赖、Java 8、不用 Fragment
 */
public class MainActivity extends Activity implements View.OnClickListener {

    public static final String URL_FOLIA = "http://127.0.0.1:3000";
    /** 兜底地址：有的系统对 127.0.0.1 的隔离和 localhost 不一样 */
    public static final String URL_FOLIA_LOCALHOST = "http://localhost:3000";

    private static final int COLOR_OK = 0xFF34C759;
    private static final int COLOR_WARN = 0xFFF5A623;
    private static final int COLOR_ERR = 0xFFF87171;

    private WebView webView;
    private View topEdge;
    private View installOverlay;
    private android.widget.ProgressBar installBar;
    private TextView installText;
    private LinearLayout terminalOverlay;
    private TextView terminalText;
    private TextView stateDot;
    private View emptyState;
    private TextView emptyTitle;

    private LogTailer webTailer;
    private LogTailer apiTailer;
    private LogTailer runtimeTailer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder(16384);

    private boolean terminalOpen = false;
    /** 点了「启动」→ 等服务就绪后自动收起面板 */
    private boolean autoCloseWhenReady = false;
    private boolean webAlive = false;
    private boolean apiAlive = false;
    private int tick = 0;

    /** 页面是否成功加载过（切后台回来判断要不要补救） */
    private boolean pageLoaded = false;
    /** 已经发出 loadUrl、还没收到 onPageFinished */
    private boolean loadingPage = false;
    /** 最近一次"主框架"加载失败：服务没起、或 WebView 渲染进程被杀 */
    private boolean lastLoadFailed = false;
    /** 恢复重试计数，别死循环 */
    private int recoverTries = 0;
    /** 已经替用户重拉过一次运行时 */
    private boolean autoStartAsked = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            drainLogs();
            if (tick % 4 == 0) {
                probePortsAsync();
            }
            tick++;
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = (WebView) findViewById(R.id.webView);
        topEdge = findViewById(R.id.topEdge);
        terminalOverlay = (LinearLayout) findViewById(R.id.terminalOverlay);
        terminalText = (TextView) findViewById(R.id.terminalText);
        stateDot = (TextView) findViewById(R.id.stateDot);
        installOverlay = findViewById(R.id.installOverlay);
        installBar = (android.widget.ProgressBar) findViewById(R.id.installBar);
        installText = (TextView) findViewById(R.id.installText);

        emptyState = findViewById(R.id.emptyState);
        emptyTitle = (TextView) findViewById(R.id.emptyTitle);
        findViewById(R.id.btnEmptyStart).setOnClickListener(this);
        findViewById(R.id.btnEmptyLog).setOnClickListener(this);
        findViewById(R.id.btnStart).setOnClickListener(this);
        findViewById(R.id.btnStop).setOnClickListener(this);
        findViewById(R.id.btnClear).setOnClickListener(this);
        findViewById(R.id.btnClose).setOnClickListener(this);

        setupTopEdge();
        goImmersive();

        File appDir = EmbeddedRuntime.appDir(this);
        webTailer = new LogTailer(new File(appDir, "logs/web.log"));
        apiTailer = new LogTailer(new File(appDir, "logs/api.log"));
        runtimeTailer = new LogTailer(new File(EmbeddedRuntime.logDir(this), "runtime.log"));

        appendLog("· Folia 启动器 v" + appVersion() + "（自带运行时）\n");
        appendLog("· 运行时: " + EmbeddedRuntime.runtimeDir(this).getAbsolutePath() + "\n");
        appendLog("· 应用:   " + appDir.getAbsolutePath() + "\n");

        setupWebView();
        ui.post(ticker);

        // 第一次打开：先把随包带的 node、动态库、网页摊到应用私有目录
        if (!EmbeddedRuntime.isInstalled(this)) {
            startInstall();
        } else {
            ensureRunning();
        }
    }

    // ---------------------------------------------------------------- WebView

    /** 只有本机的两个 host 算"自己人"，其余一律丢给系统浏览器 */
    private static boolean isLocalUrl(String url) {
        if (url == null) {
            return false;
        }
        Uri u = Uri.parse(url);
        if (!"http".equals(u.getScheme())) {
            return false;
        }
        String host = u.getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    private void setupWebView() {
        // 注意：这里故意不 loadUrl。
        // node 从被启动到 bind 3000 需要几十毫秒到几秒，早加载只会拿到
        // ERR_CONNECTION_REFUSED，然后页面一直黑着。等探测到端口通了再加载。
        configureWebView(webView);
    }

    /**
     * 给一个 WebView 装上设置和回调。
     * 抽出来是因为渲染进程被系统回收后要重建一个新的 WebView（见 rebuildWebView）。
     */
    private void configureWebView(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // 音乐播放器必须
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // 认 host，不认前缀：http://127.0.0.1.evil.com 也以 http://127.0.0.1 开头
                if (isLocalUrl(url)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                DiagLog.line(MainActivity.this, "web", "开始加载 " + url + " (WebView " + android.webkit.WebSettings.getDefaultUserAgent(MainActivity.this).replaceAll(".*Chrome/([0-9.]+).*", "$1") + ")");
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest req, android.webkit.WebResourceResponse r) {
                DiagLog.line(MainActivity.this, "web", "HTTP " + r.getStatusCode() + " "
                        + req.getUrl() + (req.isForMainFrame() ? " (主框架)" : ""));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageLoaded = true;
                loadingPage = false;
                lastLoadFailed = false;
                recoverTries = 0;
                if (terminalOpen) {
                    appendLog("· 页面加载完成\n");
                }
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                DiagLog.line(MainActivity.this, "web", "加载失败 " + err.getErrorCode() + " "
                        + err.getDescription() + " " + req.getUrl());
                // 只关心主框架失败（子资源失败不该触发重载）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && req.isForMainFrame()) {
                    lastLoadFailed = true;
                    pageLoaded = false;
                    loadingPage = false;
                    if (terminalOpen) {
                        appendLog("! 页面加载失败：" + err.getDescription() + "\n");
                    }
                    scheduleRecover();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                // WebView 的渲染进程被系统回收（低内存时很常见）→ 自愈，别让用户看白屏
                pageLoaded = false;
                lastLoadFailed = true;
                if (terminalOpen) {
                    appendLog("! WebView 渲染进程被系统回收，重建中…\n");
                }
                rebuildWebView();
                scheduleRecover();
                return true;   // 返回 true 表示"我自己处理了"，系统不会再杀掉整个 Activity
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                DiagLog.line(MainActivity.this, "js", "[" + m.messageLevel() + "] " + m.message()
                        + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }
        });
        w.setBackgroundColor(0xFF000000);
    }

    /**
     * 渲染进程被回收后重建 WebView。
     * 直接把旧的拔掉换新的，比试图"唤醒"死掉的渲染进程可靠。
     */
    private void rebuildWebView() {
        try {
            ViewGroup parent = (ViewGroup) findViewById(R.id.root);
            if (webView != null) {
                if (webView.getParent() == parent) {
                    parent.removeView(webView);
                }
                webView.destroy();
            }
            WebView fresh = new WebView(this);
            fresh.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            parent.addView(fresh, 0);
            webView = fresh;
            configureWebView(webView);
            webView.loadUrl(URL_FOLIA);
        } catch (Throwable t) {
            if (terminalOpen) {
                appendLog("! 重建 WebView 失败: " + t + "\n");
            }
        }
    }

    /** 页面没加载成功时的自愈：端口通了就重载，没通就把内置运行时拉起来 */
    private final Runnable recover = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || !lastLoadFailed || pageLoaded) {
                return;
            }
            if (recoverTries > 60) {
                return;                    // 试够了就停，别一直转
            }
            recoverTries++;
            if (PortProbe.isOpen(3000)) {
                lastLoadFailed = false;
                // 前两次用 127.0.0.1，再失败就换 localhost 试试（有些 ROM 只隔离其中一个）
                String url = recoverTries <= 2 ? URL_FOLIA : URL_FOLIA_LOCALHOST;
                DiagLog.line(MainActivity.this, "probe", "端口通了，重新加载 " + url);
                webView.loadUrl(url);
                return;
            }
            if (!autoStartAsked && EmbeddedRuntime.isInstalled(MainActivity.this)) {
                autoStartAsked = true;
                EmbeddedRuntime.start(MainActivity.this);
                appendLog("· 服务没起来，已重新拉起内置运行时\n");
            }
            ui.postDelayed(recover, 1500);
        }
    };

    private void scheduleRecover() {
        ui.removeCallbacks(recover);
        ui.postDelayed(recover, 600);
    }

    /** 顶边 24dp 的隐形条：点一下 或 往下拉，都能呼出终端 */
    private void setupTopEdge() {
        topEdge.setOnTouchListener(new View.OnTouchListener() {
            private float startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dy = e.getRawY() - startY;
                        // 往下拉超过 40dp，或者就是轻轻点一下 → 都算"想看终端"
                        if (dy > 40 * getResources().getDisplayMetrics().density || dy <= 12) {
                            openTerminal();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    // ---------------------------------------------------------------- 沉浸式

    private void goImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void showSystemBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ---------------------------------------------------------------- 面板开关

    private void openTerminal() {
        if (terminalOpen) {
            return;
        }
        terminalOpen = true;
        terminalOverlay.setVisibility(View.VISIBLE);
        showSystemBars();          // 面板里能看清、方便点按钮
        appendLog("· 终端面板（点 ✕ 收起）\n");
    }

    private void closeTerminal() {
        if (!terminalOpen) {
            return;
        }
        terminalOpen = false;
        terminalOverlay.setVisibility(View.GONE);
        goImmersive();
    }

    // ---------------------------------------------------------------- 日志

    private void drainLogs() {
        String a = webTailer == null ? null : webTailer.poll();
        String b = apiTailer == null ? null : apiTailer.poll();
        String c = runtimeTailer == null ? null : runtimeTailer.poll();
        if (c != null) {
            appendLog(c);
        }
        if (a != null) {
            appendLog(a);
        }
        if (b != null) {
            appendLog(b);
        }
    }

    private void appendLog(String chunk) {
        if (chunk == null || chunk.length() == 0) {
            return;
        }
        logBuf.append(chunk);
        if (logBuf.length() > 40000) {
            logBuf.delete(0, logBuf.length() - 24000);
        }
        if (terminalText == null) {
            return;
        }
        terminalText.setText(logBuf);
        final ScrollView sc = (ScrollView) findViewById(R.id.logScroll);
        if (sc != null) {
            sc.post(new Runnable() {
                @Override
                public void run() {
                    sc.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    // ---------------------------------------------------------------- 状态探测

    private void probePortsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean web = PortProbe.isOpen(3000);
                final boolean api = PortProbe.isOpen(4000);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyStatus(web, api);
                    }
                });
            }
        }, "probe").start();
    }

    private void applyStatus(boolean web, boolean api) {
        boolean wasWeb = webAlive;
        boolean changed = web != webAlive || api != apiAlive;
        webAlive = web;
        apiAlive = api;

        if (stateDot != null) {
            if (web && api) {
                stateDot.setTextColor(COLOR_OK);
                stateDot.setText("● 运行中");
            } else if (web || api) {
                stateDot.setTextColor(COLOR_WARN);
                stateDot.setText("● 部分");
            } else {
                stateDot.setTextColor(COLOR_ERR);
                stateDot.setText("● 已停止");
            }
        }

        if (changed) {
            DiagLog.line(this, "probe", "Java 侧探测 3000=" + web + " 4000=" + api);
        }
        // 端口通了、页面还没成功加载过 → 加载一次（用 loadingPage 防止重复触发）
        if (web && !pageLoaded && !loadingPage) {
            loadingPage = true;
            DiagLog.line(this, "web", "端口已通，开始加载页面");
            webView.loadUrl(URL_FOLIA);
        }
        // 页面没起来 → 显示可点的空状态，别让用户对着一片黑
        if (emptyState != null) {
            boolean show = !web;
            emptyState.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && emptyTitle != null) {
                // 还在启动中 / 已经失败过，文案不一样
                emptyTitle.setText(lastLoadFailed ? R.string.empty_title : R.string.empty_starting);
            }
        }

        // 刚起来 + 是我点的启动 → 自动收回面板，回到全屏 Folia
        if (!wasWeb && web && terminalOpen && autoCloseWhenReady) {
            autoCloseWhenReady = false;
            appendLog("· 就绪，收面板\n");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    closeTerminal();
                    webView.loadUrl(URL_FOLIA);
                }
            }, 800);
        }
    }

    // ---------------------------------------------------------------- 交互

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnEmptyStart) {
            // 重启内置服务，然后重新拉页面
            startService(new Intent(this, FoliaService.class));
            if (emptyState != null) {
                emptyState.setVisibility(View.GONE);
            }
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    webView.loadUrl(URL_FOLIA);
                }
            }, 1500);
            toast("正在启动");
        } else if (id == R.id.btnEmptyLog) {
            openTerminal();
        } else if (id == R.id.btnClose) {
            closeTerminal();
        } else if (id == R.id.btnStart) {
            autoCloseWhenReady = true;
            startServices();
        } else if (id == R.id.btnStop) {
            autoCloseWhenReady = false;
            stopServices();
        } else if (id == R.id.btnClear) {
            logBuf.setLength(0);
            terminalText.setText("");
        }
    }

    private void startServices() {
        if (!EmbeddedRuntime.isInstalled(this)) {
            toast("运行时还没装好，稍等一下");
            return;
        }
        appendLog("\n$ 启动内置运行时（node）\n");
        EmbeddedRuntime.start(this);
        toast("正在启动 Folia…");
    }

    private void stopServices() {
        appendLog("\n$ 停止内置运行时\n");
        EmbeddedRuntime.stop(this);
        toast("已停止");
    }

    // ---------------------------------------------------------------- 首次解压 / 启动

    /** 第一次打开：把 APK 里的运行时摊开（要写 140MB 左右，后台线程干） */
    private void startInstall() {
        if (installOverlay == null) {
            return;
        }
        installOverlay.setVisibility(View.VISIBLE);
        installOverlay.setOnClickListener(null);
        installText.setText("首次启动：正在展开内置运行时…\n（约 130MB，请别退出）");
        installBar.setProgress(0);
        appendLog("· 首次启动：解压内置运行时\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EmbeddedRuntime.install(MainActivity.this, new EmbeddedRuntime.Progress() {
                        @Override
                        public void onProgress(final int percent, final String message) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (installBar != null) {
                                        installBar.setProgress(percent);
                                    }
                                    if (installText != null) {
                                        installText.setText(percent + "%  " + message);
                                    }
                                }
                            });
                        }
                    });
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            installOverlay.setVisibility(View.GONE);
                            appendLog("· 解压完成，开始启动服务\n");
                            ensureRunning();
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                            installText.setText("解压失败：" + msg + "\n\n点这里重试");
                            appendLog("! 解压失败: " + msg + "\n");
                            installOverlay.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    startInstall();
                                }
                            });
                        }
                    });
                }
            }
        }, "install").start();
    }

    /** 拉起前台服务（运行时在里面跑） */
    private void ensureRunning() {
        appendLog("· 启动内置运行时（node）\n");
        EmbeddedRuntime.start(this);
    }

    // ---------------------------------------------------------------- 权限 / 环境


    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0.2";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        if (!terminalOpen) {
            goImmersive();
        }
        // 切后台再回来必须配对唤醒：只 onPause 不 onResume，画面会冻在半渲染状态
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            webView.invalidate();
        }
        probePortsAsync();
        // 之前加载失败过（服务被杀 / 渲染进程被回收）→ 立刻走自愈流程
        if (!pageLoaded) {
            scheduleRecover();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 下拉状态栏再回来时，系统会把状态栏放出来，这里再收一次
        if (hasFocus && !terminalOpen) {
            goImmersive();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 故意不调 webView.onPause()：这是音乐播放器，切后台要继续放
    }

    @Override
    public void onBackPressed() {
        if (terminalOpen) {
            closeTerminal();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
