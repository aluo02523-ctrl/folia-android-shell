package com.nuonuo.folia;

import java.net.HttpURLConnection;
import java.net.URL;

/** 程序内部用的小探针：某个端口现在有没有人在服务 */
final class PortProbe0 {

    private PortProbe0() {
    }

    static boolean isUp(int port) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://127.0.0.1:" + port + "/");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(400);
            c.setReadTimeout(600);
            c.setRequestMethod("GET");
            return c.getResponseCode() > 0;
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
