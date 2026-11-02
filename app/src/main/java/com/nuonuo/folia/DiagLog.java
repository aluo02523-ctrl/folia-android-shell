package com.nuonuo.folia;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志：往 /sdcard/Android/media/com.nuonuo.folia/diag.log 再抄一份。
 *
 * 为什么需要这个：应用私有目录里的日志只有 App 自己能看，一旦"装上打不开"，
 * 就没法从外面（Termux / adb / 电脑）看它到底死在哪一步。
 * Android/media/<包名> 是唯一一个「App 不用权限就能写、别人也读得到」的地方，
 * 所以把关键事件（解压进度、启动命令行、退出码、运行时的输出）抄一份在这儿。
 *
 * 写失败一律吞掉——诊断日志不能反过来把主流程搞挂。
 */
final class DiagLog {

    private static final Object LOCK = new Object();
    private static final long MAX = 512 * 1024;

    private DiagLog() {
    }

    static File file(Context c) {
        File dir = new File(new File(Environment.getExternalStorageDirectory(), "Android/media"),
                c.getPackageName());
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        return new File(dir, "diag.log");
    }

    static void line(Context c, String tag, String msg) {
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        append(c, "[" + stamp + "] " + tag + " " + msg + "\n");
    }

    static void append(Context c, String text) {
        synchronized (LOCK) {
            try {
                File f = file(c);
                if (f.length() > MAX) {
                    // 超了就砍掉重来，别把手机塞满
                    f.delete();
                }
                OutputStream os = new FileOutputStream(f, true);
                try {
                    os.write(text.getBytes("UTF-8"));
                } finally {
                    os.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
