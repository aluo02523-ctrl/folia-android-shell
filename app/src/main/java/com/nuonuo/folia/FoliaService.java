package com.nuonuo.folia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 前台服务：内置 node 运行时的保姆。
 *
 * node 一被系统回收音乐就断，所以挂常驻通知告诉系统"我在干活"。
 * 只拉一个进程（entry.js），它再派生接口层和静态站；谁挂了整条链退出，这里重拉。
 */
public class FoliaService extends Service {

    private static final String TAG = "FoliaService";
    private static final String CHANNEL_ID = "folia-runtime";
    private static final int NOTIF_ID = 1;
    /** entry.js 写下的进程清单：每行「名字 pid」 */
    private static final String PID_FILE = ".pids";
    private static final int MAX_LOG_BYTES = 1024 * 1024;

    private volatile Process proc;
    private Thread watchdog;
    private volatile boolean stopping = false;
    private volatile long startedAt = 0;
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

        // startForeground 必须在几秒内调用
        startForeground(NOTIF_ID, buildNotification(isRunning() ? "Folia 运行中" : "Folia 启动中…"));

        if (!EmbeddedRuntime.isInstalled(this)) {
            Log.e(TAG, "还没解压完，不能启动");
            DiagLog.line(this, "start", "运行时没装好，拒绝启动");
            stopSelf();
            return START_NOT_STICKY;
        }

        stopping = false;
        if (!isRunning()) {
            restarts = 0;
            envProbe();
            launch();
        }
        // 被杀后自动重来；用户主动停止时 stopping=true，不会自动拉起
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

    /** 把「这个 App 到底能不能 exec 私有目录里的程序」写成事实，不靠猜 */
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
        Process p = proc;
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    private void launch() {
        if (!launchDirect()) {
            DiagLog.line(this, "launch", "启动失败，看上面的 direct 日志");
        }
    }

    /** 启动 node + entry.js，成功返回 true */
    private boolean launchDirect() {
        // 自己那份还活着就别起第二份：第二个会在 bind 3000 时 EADDRINUSE 崩掉
        if (proc != null && isRunning()) {
            DiagLog.line(this, "direct", "已有实例在跑，跳过启动");
            return true;
        }

        // 端口通但没有进程句柄 = 上一条链留下的孤儿。不清理掉的话，
        // 下面的启动会因为端口被占失败，而看门狗也没法挂到任何进程上。
        if (PortProbe.isOpen(3000) || PortProbe.isOpen(4000)) {
            DiagLog.line(this, "direct", "端口有响应但没有进程句柄，清理残留进程");
            killOrphans();
            if (!waitPortsFree(2500)) {
                DiagLog.line(this, "direct", "端口仍被占（可能是别的 App），放弃启动");
                return false;
            }
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
            // 这份 node 把 openssl 配置路径编死成 Termux 的目录，别的 App 读会被拒（EACCES）
            // 而 OpenSSL 对权限错误是致命错误，所以必须显式指到随包的这份。
            File opensslConf = new File(app, "certs/openssl.cnf");
            if (opensslConf.exists()) {
                env.put("OPENSSL_CONF", opensslConf.getAbsolutePath());
            }

            proc = pb.start();
            startedAt = System.currentTimeMillis();
            DiagLog.line(this, "direct", "已启动 pid=" + pidOf(proc));
            pumpOutput(proc, new File(EmbeddedRuntime.logDir(this), "runtime.log"));
            watch(proc);
            return true;
        } catch (Throwable t) {
            DiagLog.line(this, "direct", "启动异常: " + t);
            return false;
        }
    }

    /** 上一轮被强杀会留下子进程；只杀 cmdline 里带本包名的，避免 pid 复用误伤 */
    private void killOrphans() {
        File f = new File(EmbeddedRuntime.appDir(this), PID_FILE);
        if (!f.isFile()) {
            return;
        }
        List<Integer> pids = new ArrayList<Integer>();
        try {
            String text = readSmall(new java.io.FileInputStream(f), 4096);
            for (String line : text.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    int pid = Integer.parseInt(parts[1]);
                    if (pid > 0 && pid != android.os.Process.myPid() && isOurs(pid)) {
                        pids.add(pid);
                        signal(pid, OsConstants.SIGTERM);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            f.delete();
        } catch (Throwable t) {
            DiagLog.line(this, "direct", "读 pid 文件失败: " + t);
        }
        if (pids.isEmpty()) {
            return;
        }
        DiagLog.line(this, "direct", "清理进程 " + pids);
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) {
        }
        for (Integer pid : pids) {
            signal(pid, OsConstants.SIGKILL);
        }
    }

    private boolean isOurs(int pid) {
        String cmd = readSmall("/proc/" + pid + "/cmdline");
        return cmd.contains(getPackageName());
    }

    private static void signal(int pid, int sig) {
        try {
            Os.kill(pid, sig);
        } catch (Throwable ignored) {
        }
    }

    private boolean waitPortsFree(long ms) {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            if (!PortProbe.isOpen(3000) && !PortProbe.isOpen(4000)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
        return !PortProbe.isOpen(3000) && !PortProbe.isOpen(4000);
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

    /** node 的 stdout/stderr 落到 runtime.log，同时抄进 diag.log */
    private void pumpOutput(final Process p, final File logFile) {
        final OutputStream os;
        try {
            // 重拉时别把上一次崩溃前的输出冲掉，那正是要看的
            if (logFile.length() > MAX_LOG_BYTES) {
                logFile.delete();
            }
            os = new FileOutputStream(logFile, true);
            os.write(("\n===== " + new java.util.Date() + " 启动 =====\n").getBytes("UTF-8"));
        } catch (IOException e) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = p.getInputStream();
                byte[] buf = new byte[8192];
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        os.flush();
                        // 进程如果启动即退出，只按秒抄会丢光，所以每块都抄
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

    /** 看门狗：意外退出就重拉，最多 3 次；稳定跑过一分钟再崩就重新计数 */
    private void watch(final Process p) {
        watchdog = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignored) {
                }
                DiagLog.line(FoliaService.this, "exit", "runtime code=" + code + " stopping=" + stopping);
                if (stopping) {
                    return;
                }
                long uptime = System.currentTimeMillis() - startedAt;
                if (uptime > 60000) {
                    restarts = 0;
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
                            "\n!! 运行时反复退出，放弃自动重启。打开日志面板看原因。\n");
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
                p.destroy();     // entry.js 收到 SIGTERM 会先收掉两个子进程
            } catch (Throwable ignored) {
            }
            try {
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
        // 没有句柄说明是上一条链的残留，直接清；有句柄的让 entry.js 自己收尾，
        // 漏掉的会在下次启动时被 launchDirect 清掉
        if (p == null) {
            killOrphans();
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

    private void writeLog(File f, String s) {
        try {
            OutputStream os = new FileOutputStream(f, true);
            os.write(s.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {
        }
    }
}
