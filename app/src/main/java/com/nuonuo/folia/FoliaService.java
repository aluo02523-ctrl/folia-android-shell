package com.nuonuo.folia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 前台服务：proot 进程的"保姆"
 *
 * 为什么必须是前台服务：proot 是所有 guest 进程的父进程（ptrace 挂在那里），
 * 一旦被系统回收，音乐就断了。挂个常驻通知告诉系统"我在干活，别杀我"。
 *
 * 一条 proot 命令同时拉起 guest 里的两个 node 进程：
 *   node /app/api-entry.js  → :4000  网易云 API
 *   node /app/static.js     → :3000  Folia 前端静态站
 * （细节在 app/start.sh 里）
 */
public class FoliaService extends Service {

    private static final String TAG = "FoliaService";
    private static final String CHANNEL_ID = "folia-runtime";
    private static final int NOTIF_ID = 1;

    private Process proc;
    private Thread watchdog;
    private volatile boolean stopping = false;
    private int restarts = 0;
    /** "proot"（正常路线）或 "direct"（兜底：直接跑 node，不经过 proot） */
    private String mode = "direct";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (EmbeddedRuntime.ACTION_STOP.equals(action)) {
            stopping = true;
            killRuntime();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 先挂通知（Android 要求 startForeground 必须在几秒内调用）
        startForeground(NOTIF_ID, buildNotification(isRunning() ? "Folia 运行中" : "Folia 启动中…"));

        if (!EmbeddedRuntime.isInstalled(this)) {
            Log.e(TAG, "还没解压完，不能启动");
            toastLog("运行时没装好，先去主界面等解压完成");
            stopSelf();
            return START_NOT_STICKY;
        }

        stopping = false;
        if (!isRunning()) {
            restarts = 0;
            envProbe();
            launch();
        }
        // 被杀后自动重来（用户主动停止时 stopping=true，不会自动拉起来）
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 划掉任务卡片也别停：这是音乐播放器
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        killRuntime();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 起进程

    /**
     * 环境探针：把「这个 App 到底能不能 exec 私有目录里的程序」这件事写成事实，
     * 免得靠猜。日志会落到 /sdcard/Android/media/<包名>/diag.log。
     */
    private void envProbe() {
        try {
            DiagLog.line(this, "env", "targetSdk=" + getApplicationInfo().targetSdkVersion
                    + " deviceSdk=" + Build.VERSION.SDK_INT
                    + " abi=" + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                    + " uid=" + android.os.Process.myUid());
            DiagLog.line(this, "env", "selinux=" + readSmall("/proc/self/attr/current"));
            DiagLog.line(this, "env", "nativeLibDir=" + getApplicationInfo().nativeLibraryDir);
            probeExec("mksh", new File(EmbeddedRuntime.binDir(this), "mksh").getAbsolutePath(),
                    new String[]{"-c", "echo probe-ok"});
            probeExec("node", new File(EmbeddedRuntime.binDir(this), "node").getAbsolutePath(),
                    new String[]{"-e", "console.log('probe-ok')"});
            probeExec("sh", "/system/bin/sh", new String[]{"-c", "echo probe-ok"});
        } catch (Throwable t) {
            DiagLog.line(this, "env", "探针异常 " + t);
        }
    }

    private void probeExec(String name, String path, String[] args) {
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(path);
            for (String a : args) {
                cmd.add(a);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", EmbeddedRuntime.libDir(this).getAbsolutePath());
            Process p = pb.start();
            String out = readSmall(p.getInputStream(), 400);
            boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            DiagLog.line(this, "probe", name + " rc=" + (finished ? String.valueOf(p.exitValue()) : "timeout")
                    + " out=" + out.replace("\n", " | ").trim());
        } catch (Throwable t) {
            DiagLog.line(this, "probe", name + " FAILED " + t);
        }
    }

    private static String readSmall(String path) {
        try {
            java.io.InputStream in = new java.io.FileInputStream(path);
            return readSmall(in, 400).trim();
        } catch (Throwable t) {
            return "(" + t + ")";
        }
    }

    private static String readSmall(java.io.InputStream in, int max) {
        StringBuilder sb = new StringBuilder();
        try {
            byte[] buf = new byte[512];
            int r;
            while (sb.length() < max && (r = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, r, "UTF-8"));
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private boolean isRunning() {
        if (proc == null) {
            return false;
        }
        try {
            proc.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    private void launch() {
        // 正常路线是 proot（自带 rootfs）。
        // 「直连」只作为兜底：只有在 proot 连续起不来时才会切换过去（见 watch()）。
        // 1.7 的探针已经证明：本机 SELinux 域是 untrusted_app_27，App 能 exec 私有目录里的
        // mksh/node/proot，所以 proot 路线是可行的——之前失败是另外两个原因：
        //   ① exec /system/bin/sh 被拒（已换成随包的 mksh）
        //   ② node 启动时去读 Termux 的 openssl.cnf 被杀 EACCES（见 OPENSSL_CONF）
        if ("direct".equals(mode)) {
            if (launchDirect()) {
                return;
            }
            DiagLog.line(this, "launch", "直连也起不来，改回 proot");
            mode = "proot";
        }
        launchProot();
    }

    /** 直接跑 node + entry.js（不需要 proot / mksh），成功返回 true */
    private boolean launchDirect() {
        // 已经有一份在跑（看门狗重拉、按钮再点、系统重投 intent）→ 别起第二份，
        // 否则第二个进程会在 bind 3000 时 EADDRINUSE 直接崩，连带第一个也被收掉。
        if (PortProbe0.isUp(3000) && PortProbe0.isUp(4000)) {
            DiagLog.line(this, "direct", "已有实例在服务（3000/4000 都通），跳过启动");
            return true;
        }
        File app = EmbeddedRuntime.appDir(this);
        File bin = EmbeddedRuntime.binDir(this);
        File lib = EmbeddedRuntime.libDir(this);
        File entry = new File(app, "entry.js");
        File node = new File(bin, "node");

        if (!node.exists() || !entry.exists()) {
            DiagLog.line(this, "direct", "缺 node 或 entry.js（node=" + node.exists() + " entry=" + entry.exists() + "）");
            return false;
        }
        new File(app, "tmp").mkdirs();
        new File(app, "home").mkdirs();
        new File(app, "logs").mkdirs();

        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add(node.getAbsolutePath());
            cmd.add(entry.getAbsolutePath());
            DiagLog.line(this, "direct", "cmd=" + join(cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(app);
            pb.redirectErrorStream(true);
            java.util.Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
            env.put("HOME", new File(app, "home").getAbsolutePath());
            env.put("TMPDIR", new File(app, "tmp").getAbsolutePath());
            env.put("NODE_ENV", "production");
            env.put("TZ", "Asia/Shanghai");
            env.put("WEB_PORT", "3000");
            env.put("API_PORT", "4000");
            env.put("NODE_OPTIONS", "--max-old-space-size=512");
            File ca = new File(app, "certs/ca.pem");
            if (ca.exists()) {
                env.put("SSL_CERT_FILE", ca.getAbsolutePath());
                env.put("NODE_EXTRA_CA_CERTS", ca.getAbsolutePath());
            }
            // Termux 版 node 把 openssl 配置路径编死成 /data/data/com.termux/files/usr/etc/tls/openssl.cnf，
            // 在别人手机上那个文件存在但读不了 → OpenSSL 报 EACCES 直接让 node 退出（rc=13）。
            // 所以必须显式指到我们随包的这份。
            File opensslConf = new File(app, "certs/openssl.cnf");
            if (opensslConf.exists()) {
                env.put("OPENSSL_CONF", opensslConf.getAbsolutePath());
            }

            proc = pb.start();
            DiagLog.line(this, "direct", "已启动 pid=" + pidOf(proc));
            pumpOutput(proc, new File(EmbeddedRuntime.logDir(this), "proot.log"));
            watch(proc);
            return true;
        } catch (Throwable t) {
            DiagLog.line(this, "direct", "启动异常: " + t);
            return false;
        }
    }

    private void launchProot() {
        File root = EmbeddedRuntime.rootfsDir(this);
        File app = EmbeddedRuntime.appDir(this);
        File bin = EmbeddedRuntime.binDir(this);
        File lib = EmbeddedRuntime.libDir(this);
        File logFile = new File(EmbeddedRuntime.logDir(this), "proot.log");

        List<String> cmd = new ArrayList<String>();
        cmd.add(new File(bin, "proot").getAbsolutePath());
        // 注意：不要加 --link2symlink！
        // 实测（Termux 里同样命令复现）：带这个参数时 proot exec 绑定目录里的程序
        // 会直接失败（App 里报 Permission denied，Termux 里报 No such file），
        // 去掉就正常。我们的 rootfs 是骨架、运行时不需要装包，用不上硬链接转换。
        cmd.add("--kill-on-exit");     // proot 一死，guest 里所有进程跟着死
        cmd.add("-0");                 // 假装自己是 root（guest 里的 uid 0）
        cmd.add("-r");
        cmd.add(root.getAbsolutePath());

        // 这 6 个必须挂：不挂 /system 的话 bionic 的 linker 都找不到（fatal error: loader not found）
        String[] binds = {"/dev", "/proc", "/sys", "/system", "/vendor", "/apex"};
        for (String b : binds) {
            cmd.add("-b");
            cmd.add(b);
        }
        // 自己的东西：/usr/lib 放 .so，/usr/bin 放 node/proot，/app 放网页和脚本
        // lib 目录用「同路径 bind」：这样 Java 这边设的 LD_LIBRARY_PATH（宿主路径）
        // 在 guest 里也能直接命中，不用再在脚本里换一套路径。
        cmd.add("-b");
        cmd.add(lib.getAbsolutePath() + ":" + lib.getAbsolutePath());
        // ★ 不要 bind 到 /usr/bin 或 /bin！实测这两个目标会让 proot 挂载失败
        //   （App 里表现为 exec Permission denied，Termux 里表现为 loader not found）。
        //   /usr/local/bin 是好的。
        cmd.add("-b");
        cmd.add(bin.getAbsolutePath() + ":/usr/local/bin");
        cmd.add("-b");
        cmd.add(app.getAbsolutePath() + ":/app");
        cmd.add("-w");
        cmd.add("/app");

        // 启动前自检：确认三个可执行文件都在、都有 x 位（会打到终端面板上，方便对账）
        for (String n : new String[]{"proot", "node", "mksh"}) {
            preflight(new File(bin, n));
        }
        // ★ 关键：绝对不能 exec /system/bin/sh —— SELinux 的 untrusted_app 域不允许
        //   执行 shell 域的程序，会直接 "Permission denied"。Termux 也是用自己的 mksh。
        //   所以随包带了一个 Termux 编译的 mksh（bionic，靠 /system/bin/linker64 启动，这个是允许的）。
        cmd.add("/usr/local/bin/mksh");
        cmd.add("/app/start.sh");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // proot 自己是 Termux 版（bionic），RUNPATH 指向 Termux 的 lib 目录，
            // 那个目录在我们这里不存在，所以用它自己的 lib 目录覆盖一下。
            // guest 里的进程由 start.sh 重新 export LD_LIBRARY_PATH=/usr/lib。
            pb.environment().put("LD_LIBRARY_PATH", lib.getAbsolutePath());
            File prootTmp = new File(getCacheDir(), "proot-tmp");
            prootTmp.mkdirs();   // 不建的话 proot 会刷 can't canonicalize 警告
            pb.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            pb.environment().put("HOME", new File(app, "home").getAbsolutePath());
            pb.environment().put("FOLIA_LIB", lib.getAbsolutePath());   // start.sh 用它设 LD_LIBRARY_PATH
            pb.redirectErrorStream(true);
            proc = pb.start();
            Log.i(TAG, "proot 已启动 pid=" + pidOf(proc) + " 命令=" + cmd);
            DiagLog.line(this, "launch", "proot pid=" + pidOf(proc));
            DiagLog.line(this, "launch", "cmd=" + join(cmd));
            DiagLog.line(this, "launch", "LD_LIBRARY_PATH=" + lib.getAbsolutePath());
            pumpOutput(proc, logFile);
            watch(proc);
        } catch (Throwable t) {
            Log.e(TAG, "启动 proot 失败", t);
            writeLog(logFile, "\n!! 启动 proot 失败: " + t + "\n");
            stopSelf();
        }
    }


    /** 检查一个可执行文件：存在？有 x 位？没有就修一把，并把结果写进日志 */
    private void preflight(File f) {
        boolean exists = f.exists();
        boolean x = f.canExecute();
        if (exists && !x) {
            try {
                android.system.Os.chmod(f.getAbsolutePath(), 0755);
            } catch (Throwable ignored) {
            }
            x = f.canExecute();
        }
        String line = "· 自检 " + f.getName() + " exists=" + exists + " x=" + x + " size=" + f.length();
        Log.i(TAG, line);
        DiagLog.line(this, "preflight", line);
        if (!x) {
            writeLog(new File(EmbeddedRuntime.logDir(this), "proot.log"), "\n!! " + f.getName() + " 不可执行\n");
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (s.indexOf(' ') >= 0) {
                sb.append('\'').append(s).append('\'');
            } else {
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private static String pidOf(Process p) {
        try {
            return String.valueOf(p.getClass().getMethod("pid").invoke(p));
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 把 proot 的输出（含 guest 里两个 node 的日志）落到 logs/proot.log */
    private void pumpOutput(final Process p, final File logFile) {
        final OutputStream os;
        try {
            os = new FileOutputStream(logFile, false);
        } catch (IOException e) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = p.getInputStream();
                byte[] buf = new byte[8192];
                long lastMirror = 0;
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        os.flush();
                        // 每块都抄一份到 diag.log：进程如果启动即退出，只抄 1 秒一次会丢光
                        DiagLog.append(FoliaService.this, new String(buf, 0, r, "UTF-8"));
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        os.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "proot-out");
        t.setDaemon(true);
        t.start();
    }

    /** 看门狗：proot 意外退出就重拉（最多 3 次，别无限重生） */
    private void watch(final Process p) {
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignored) {
                }
                Log.i(TAG, "proot 退出 code=" + code);
                DiagLog.line(FoliaService.this, "exit", "proot code=" + code + " stopping=" + stopping);
                if (stopping) {
                    return;
                }
                if ("proot".equals(mode) && restarts >= 2) {
                    // proot 反复挂 → 换直连兜底，至少让用户能听歌
                    DiagLog.line(FoliaService.this, "launch", "proot 连挂 " + restarts + " 次，切直连兜底");
                    mode = "direct";
                    restarts = 0;
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ignored) {
                    }
                    if (!stopping) {
                        launch();
                    }
                    return;
                }
                if (restarts < 3) {
                    restarts++;
                    writeLog(new File(EmbeddedRuntime.logDir(FoliaService.this), "proot.log"),
                            "\n!! proot 意外退出（code=" + code + "），第 " + restarts + " 次重拉\n");
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                    if (!stopping) {
                        launch();
                    }
                } else {
                    writeLog(new File(EmbeddedRuntime.logDir(FoliaService.this), "proot.log"),
                            "\n!! proot 反复退出，放弃自动重启。请打开日志面板看原因。\n");
                    stopSelf();
                }
            }
        }, "proot-watch");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void killRuntime() {
        Process p = proc;
        proc = null;
        if (p != null) {
            try {
                p.destroy();     // proot --kill-on-exit 会顺带收掉 guest 里的 node
            } catch (Throwable ignored) {
            }
            try {
                // 给 1 秒体面退出，再强杀
                final Process fp = p;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        try {
                            fp.destroyForcibly();
                        } catch (Throwable ignored) {
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Folia 运行状态", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Folia")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private void toastLog(String s) {
        Log.w(TAG, s);
    }

    private void writeLog(File f, String s) {
        try {
            OutputStream os = new FileOutputStream(f, true);
            os.write(s.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {
        }
    }
}
