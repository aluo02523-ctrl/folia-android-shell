package com.nuonuo.folia;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 回环端口探活：连得上就算活着。
 *
 * 走裸 TCP 而不是 HTTP：本机回环几毫秒就有结果，也不用为每次探测造一个
 * HttpURLConnection。UI 和看门狗都调这里。
 */
public final class PortProbe {

    private static final int TIMEOUT_MS = 300;

    private PortProbe() {
    }

    public static boolean isOpen(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), TIMEOUT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
