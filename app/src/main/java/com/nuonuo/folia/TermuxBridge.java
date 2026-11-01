package com.nuonuo.folia;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * 往 Termux 里投递命令 —— 两条路，按顺序试：
 *
 * 路线 1（正规）：Termux 的 RunCommandService
 *   前提：本 App 在清单里申请了 com.termux.permission.RUN_COMMAND，
 *         且 Termux 的 ~/.termux/termux.properties 里 allow-external-apps=true。
 *   坑：Android 8 以后后台应用启动别的应用的 Service 会被系统拦（抛异常），
 *       所以成功与否必须 try/catch，不能当成一定成功。
 *
 * 路线 2（兜底）：往"任务目录"写一个 .sh 文件
 *   前提：Termux 里跑着一个看门脚本（糯糯任务泵），每 2 秒扫一次目录并执行。
 *   好处：只要存储可写就行，完全不需要 RUN_COMMAND 权限。
 *   目录顺序：/sdcard/moxw/q → /sdcard/folia-spool
 */
public final class TermuxBridge {

    static final String TERMUX_PKG = "com.termux";
    static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    private TermuxBridge() {
    }

    /** 投递一条命令；返回 true 表示"至少有一只手把命令递出去了" */
    public static boolean run(Context ctx, String command) {
        if (runViaService(ctx, command)) {
            return true;
        }
        return runViaSpool(command);
    }

    /** 路线 1：RunCommandService */
    public static boolean runViaService(Context ctx, String command) {
        try {
            Intent i = new Intent();
            i.setClassName(TERMUX_PKG, "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-l", "-c", command});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            ctx.startService(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 路线 2：写脚本文件，交给 Termux 里的看门脚本执行 */
    public static boolean runViaSpool(String command) {
        File sd = Environment.getExternalStorageDirectory();
        File[] candidates = new File[]{
                new File(sd, "moxw/q"),
                new File(sd, "folia-spool"),
        };
        String name = "app-" + System.currentTimeMillis() + ".sh";
        for (File dir : candidates) {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    continue;
                }
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                Writer w = new OutputStreamWriter(fos, "UTF-8");
                w.write("#!/data/data/com.termux/files/usr/bin/bash\n");
                w.write("# 来自 Folia 启动器 App\n");
                w.write(command);
                w.write("\n");
                w.flush();
                w.close();
                fos.close();
                return true;
            } catch (Throwable ignored) {
                // 换下一个目录再试
            }
        }
        return false;
    }
}
