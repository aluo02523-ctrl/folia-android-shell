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
 * 前台服务：内置 node 运行时的"保姆"
 *
 * 为什么必须是前台服务：node 进程一被系统回收，音乐就断了。
 * 挂个常驻通知告诉系统"我在干活，别杀我"。
 *
 * 一条命令拉起 entry.js，它再派生两个子进程：
 *   node api-entry.js  → :4000  接口层
 *   node static.js     → :3000  前端静态站
 *
 */
public class FoliaService extends Service {

    private static final String TAG = "FoliaService";
    private static final String CHANNEL_ID = "folia-runtime";
    private static final int NOTIF_ID = 1;

    private Process proc;
    private Thread watchdog;
    private volatile boolean stopping = false;
    private int restarts = 0;

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
        // App 直接 exec 运行时里的 node，没有中间层：
        // 少一层进程、少一份 ptrace 开销，也不会踩到「App 能不能 exec 私有目录」的坑
        if (launchDirect()) {
            return;
        }
        DiagLog.line(this, "launch", "直连起不来，看上面的 direct 日志");
    }

    /** 启动 node + entry.js，成功返回 true */
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
            pumpOutput(proc, new File(EmbeddedRuntime.logDir(this), "runtime.log"));
            watch(proc);
            return true;
        } catch (Throwable t) {
            DiagLog.line(this, "direct", "启动异常: " + t);
            return false;
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
            writeLog(new File(EmbeddedRuntime.logDir(this), "runtime.log"), "\n!! " + f.getName() + " 不可执行\n");
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

    /** 把运行时的输出（含两个子进程的日志）落到 logs/runtime.log */
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
        }, "runtime-out");
        t.setDaemon(true);
        t.start();
    }

    /** 看门狗：node 意外退出就重拉（最多 3 次，别无限重生） */
    private void watch(final Process p) {
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignored) {
                }
                Log.i(TAG, "运行时退出 code=" + code);
                DiagLog.line(FoliaService.this, "exit", "runtime code=" + code + " stopping=" + stopping);
                if (stopping) {
                    return;
                }
                if (restarts < 3) {
                    restarts++;
                    writeLog(new File(EmbeddedRuntime.logDir(FoliaService.this), "runtime.log"),
                            "\n!! 运行时意外退出（code=" + code + "），第 " + restarts + " 次重拉\n");
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                    }
                    if (!stopping) {
                        launch();
                    }
                } else {
                    writeLog(new File(EmbeddedRuntime.logDir(FoliaService.this), "runtime.log"),
                            "\n!! 运行时反复退出，放弃自动重启。请打开日志面板看原因。\n");
                    stopSelf();
                }
            }
        }, "runtime-watch");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void killRuntime() {
        Process p = proc;
        proc = null;
        if (p != null) {
            try {
                p.destroy();     // entry.js 会顺带收掉它派生的两个 node
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
