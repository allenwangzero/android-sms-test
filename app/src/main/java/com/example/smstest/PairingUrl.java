package com.example.smstest;

import java.io.IOException;
import java.net.URI;

/** Literal private IPv4 only: no DNS rebinding, credentials or unexpected URL paths. */
public final class PairingUrl {
    private PairingUrl() { }

    public static String validate(String value) throws Exception {
        if (value == null) throw new IOException("配对链接缺少地址");
        URI uri = new URI(value);
        if (!"http".equals(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || uri.getPort() < 1 || uri.getPort() > 65535) {
            throw new IOException("仅支持带端口的局域网 HTTP 地址");
        }
        String host = uri.getHost();
        if (host == null || !host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IOException("电脑地址必须为局域网 IPv4 地址");
        }
        String[] parts = host.split("\\.");
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = Integer.parseInt(parts[i]);
            if (bytes[i] > 255 || !Integer.toString(bytes[i]).equals(parts[i])) throw new IOException("IP 地址无效");
        }
        boolean local = bytes[0] == 10 || (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31)
                || (bytes[0] == 192 && bytes[1] == 168);
        if (!local) throw new IOException("仅允许 10.x、172.16–31.x 或 192.168.x 局域网地址");
        return "http://" + host + ":" + uri.getPort();
    }

}
