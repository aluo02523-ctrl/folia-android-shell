package com.nuonuo.folia;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 日志文件"只读增量"读取器。
 *
 * 为什么不用 tail -f：那要开子进程，还得处理杀进程、权限、二进制路径，
 * 在这个 App 里完全没必要 —— 每 500ms 打开文件看长度变了没，更简单也更稳。
 *
 * 两个细节：
 *   ①记住上次读到的位置 pos，只读新增的字节，不会重复刷屏；
 *   ②如果文件变小了（说明被 > 覆盖重写或轮转了），pos 归零重读。
 */
public class LogTailer {

    private static final int MAX_CHUNK = 64 * 1024;

    private final File file;
    private long pos = 0L;

    public LogTailer(File file) {
        this.file = file;
    }

    /** 返回自上次调用以来新增的文本；没有新内容返回 null */
    public String poll() {
        if (file == null || !file.exists()) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            long len = raf.length();
            if (len < pos) {
                pos = 0L;           // 文件被截断
            }
            if (len == pos) {
                return null;        // 没新内容
            }
            raf.seek(pos);
            int want = (int) Math.min(len - pos, MAX_CHUNK);
            byte[] buf = new byte[want];
            int n = raf.read(buf);
            if (n <= 0) {
                return null;
            }
            pos += n;
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 从头重读（清屏后调用） */
    public void reset() {
        pos = 0L;
    }
}
