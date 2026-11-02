package com.nuonuo.folia;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.BufferedReader;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置运行时（v1.0「自带 Termux」）
 *
 * 老版本要用户自己装 Termux、自己在 Termux 里跑脚本。现在这套东西全部搬进 APK：
 *
 *   应用私有目录/
 *     runtime/bin/node          ← 可执行文件
 *     runtime/lib/*.so          ← node 依赖的动态库
 *     app/                      ← 网页、接口层、启动脚本
 *     app/logs/                 ← web.log / api.log / runtime.log
 *
 * 为什么不把运行时留在 assets 里直接跑：assets 里的东西没法执行，
 * 必须先解压到应用私有目录，再 chmod 0755（用 Os.chmod，File.setExecutable 不可靠）。
 */
public final class EmbeddedRuntime {

    /** 前端静态服务器端口 */
    public static final int PORT_WEB = 3000;
    /** 网易云 API 端口 */
    public static final int PORT_API = 4000;

    public static final String ACTION_START = "com.nuonuo.folia.action.START";
    public static final String ACTION_STOP = "com.nuonuo.folia.action.STOP";

    private static final String PLAN = "install-plan.txt";
    private static final String PAYLOAD = "payload.zip";

    private EmbeddedRuntime() {
    }

    // ---------------------------------------------------------------- 路径

    public static File appDir(Context c) {
        return new File(c.getFilesDir(), "app");
    }

    public static File webDir(Context c) {
        return new File(appDir(c), "web");
    }

    public static File rootfsDir(Context c) {
        return new File(c.getFilesDir(), "rootfs");
    }

    public static File runtimeDir(Context c) {
        return new File(c.getFilesDir(), "runtime");
    }

    public static File binDir(Context c) {
        return new File(runtimeDir(c), "bin");
    }

    public static File libDir(Context c) {
        return new File(runtimeDir(c), "lib");
    }

    public static File logDir(Context c) {
        return new File(c.getFilesDir(), "logs");
    }

    private static File marker(Context c) {
        return new File(appDir(c), ".installed");
    }

    /** 装好没有（解压完成的标记文件） */
    /**
     * 装好了吗？
     *
     * 注意：只判断「标记文件存在」是错的 —— 那样升级 APK 后永远不会重新解压，
     * 新版本里加的 entry.js 之类就永远不会出现在 app 目录里（1.5 就栽在这）。
     * 所以标记文件里存的是「解压时的版本号」，和当前 versionName 不一致就重装。
     */
    public static boolean isInstalled(Context c) {
        File m = marker(c);
        if (!m.isFile()) {
            return false;
        }
        try {
            String want = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            String got = readText(m).trim();
            return want != null && want.equals(got);
        } catch (Throwable t) {
            return true;   // 读不到版本号就别折腾了，当作已装
        }
    }

    private static String readText(File f) throws IOException {
        byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 装的是哪个版本（换版本号会自动重新解压） */
    public static String installedVersion(Context c) {
        File m = marker(c);
        if (!m.isFile()) {
            return "";
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(m), "UTF-8"));
            String line = r.readLine();
            r.close();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 服务控制

    public static void start(Context c) {
        Intent i = new Intent(c, FoliaService.class);
        i.setAction(ACTION_START);
        // 8.0 起后台 startService 会崩；用 startForegroundService（服务里 5 秒内会 startForeground）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, FoliaService.class);
        i.setAction(ACTION_STOP);
        c.startService(i);
    }

    // ---------------------------------------------------------------- 解压安装

    public interface Progress {
        /** percent 0..100，message 给人看的一句话 */
        void onProgress(int percent, String message);
    }

    /**
     * 把 assets 里的东西摊到应用私有目录。必须在后台线程调用（几秒钟起步）。
     * 中途失败会抛异常，异常信息可以直接显示给用户。
     */
    public static void install(Context ctx, Progress cb) throws IOException {
        long t0 = System.currentTimeMillis();
        DiagLog.line(ctx, "install", "start v=" + installedVersion(ctx)
                + " free=" + ctx.getFilesDir().getUsableSpace() / 1048576 + "MB");
        try {
            installInner(ctx, cb, t0);
        } catch (IOException e) {
            DiagLog.line(ctx, "install", "FAILED " + e);
            throw e;
        } catch (RuntimeException e) {
            DiagLog.line(ctx, "install", "FAILED(runtime) " + e);
            throw e;
        }
    }

    private static void installInner(Context ctx, Progress cb, long t0) throws IOException {
        List<String[]> plan = readPlan(ctx);
        long total = 0;
        for (String[] e : plan) {
            total += Long.parseLong(e[1]);
        }
        if (total <= 0) {
            total = 1;
        }

        File app = appDir(ctx);
        File runtime = runtimeDir(ctx);
        File rootfs = rootfsDir(ctx);
        File logs = logDir(ctx);
        if (!logs.isDirectory() && !logs.mkdirs()) {
            throw new IOException("建不了日志目录：" + logs);
        }

        // 1) 可执行文件和 .so
        if (!runtime.isDirectory() && !runtime.mkdirs()) {
            throw new IOException("建不了运行时目录：" + runtime);
        }
        long done = 0;
        for (String[] e : plan) {
            String name = e[0];
            long size = Long.parseLong(e[1]);
            if (PAYLOAD.equals(name)) {
                continue;
            }
            if (name.endsWith("/")) {
                done += size;
                continue;
            }
            File out = new File(runtime, name);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("建不了目录：" + parent);
            }
            copyAsset(ctx, "native/" + name, out, size);
            // 可执行文件必须有 x 位。
            // 注意：File.setExecutable() 在部分 ROM 上会静默失败（只改 owner 位且受 umask 影响），
            // 所以这里直接用 Os.chmod(0755)，失败再退回 setExecutable。
            makeExecutable(out);
            done += size;
            if (cb != null) {
                cb.onProgress(clampPct(done, total), "释放运行时：" + name);
            }
        }

        // 2) 网页 + API + 启动脚本
        unzipAsset(ctx, PAYLOAD, app, cb, done, total);


        // 4) 标记
        String ver = "0";
        try {
            ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        writeText(marker(ctx), ver);

        DiagLog.line(ctx, "install", "done in " + (System.currentTimeMillis() - t0) + "ms");
        if (cb != null) {
            cb.onProgress(100, "完成");
        }
    }

    /** 纯展示用的百分比，100 留给"装完"那一下 */
    private static int clampPct(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        int p = (int) (done * 100 / total);
        if (p < 0) {
            p = 0;
        }
        if (p > 99) {
            p = 99;
        }
        return p;
    }

    /** 读安装清单（由打包脚本生成：每行「相对路径\t字节数」） */
    private static List<String[]> readPlan(Context ctx) throws IOException {
        List<String[]> out = new ArrayList<String[]>();
        InputStream in = ctx.getAssets().open(PLAN);
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length >= 2) {
                out.add(new String[]{parts[0], parts[1]});
            }
        }
        r.close();
        return out;
    }


    /** 给文件加 0755；Os.chmod 比 File.setExecutable 稳 */
    private static void makeExecutable(File f) {
        try {
            Os.chmod(f.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {
        }
        if (!f.canExecute()) {
            try {
                f.setExecutable(true, false);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void copyAsset(Context ctx, String asset, File out, long expect) throws IOException {
        InputStream in = ctx.getAssets().open(asset);
        OutputStream os = new FileOutputStream(out);
        byte[] buf = new byte[64 * 1024];
        long n = 0;
        try {
            int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r);
                n += r;
            }
        } finally {
            try {
                os.flush();
            } catch (Throwable ignored) {
            }
            os.close();
            in.close();
        }
        if (expect > 0 && n != expect) {
            throw new IOException("解出来的大小不对：" + asset + " 期望 " + expect + " 实际 " + n);
        }
    }

    /** 直接读 zip 流解压（assets 里的 zip 没法当文件随机访问） */
    private static void unzipAsset(Context ctx, String asset, File dest, Progress cb,
                                   long doneBefore, long total) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("建不了目录：" + dest);
        }
        InputStream raw = ctx.getAssets().open(asset);
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(raw, 64 * 1024));
        byte[] buf = new byte[64 * 1024];
        long read = 0;
        long lastReport = 0;
        try {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                File out = new File(dest, name);
                // 防 zip slip
                if (!out.getAbsolutePath().startsWith(dest.getAbsolutePath() + File.separator)) {
                    throw new IOException("压缩包里有非法路径：" + name);
                }
                if (e.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) {
                        throw new IOException("建不了目录：" + out);
                    }
                    zin.closeEntry();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("建不了目录：" + parent);
                }
                OutputStream os = new FileOutputStream(out);
                try {
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        read += r;
                        long now = System.currentTimeMillis();
                        if (cb != null && now - lastReport > 150) {
                            lastReport = now;
                            cb.onProgress(clampPct(doneBefore + read, total),
                                    "解压应用文件 " + (read / 1048576) + " MB");
                        }
                    }
                } finally {
                    os.flush();
                    os.close();
                }
                zin.closeEntry();
            }
        } finally {
            zin.close();
        }
    }


    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建不了目录：" + parent);
        }
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(text.getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }
}
