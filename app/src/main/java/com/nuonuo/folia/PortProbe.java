package com.nuonuo.folia;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 端口探活：发一个 GET，只要服务器回了任何 HTTP 响应（哪怕是 404）就算活着。
 * 超时设得短（连接 600ms / 读 900ms），因为这是在本机回环上探测，正常几毫秒就回来了。
 */
public final class PortProbe {

    private PortProbe() {
    }

    public static boolean alive(int port) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + "/");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(600);
            c.setReadTimeout(900);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            return code > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
