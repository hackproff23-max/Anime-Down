package com.animedown.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class Net {
    static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    static class Stop extends IOException { }

    static class HttpException extends IOException {
        final int code;
        HttpException(int code) { super(msg(code)); this.code = code; }

        static String msg(int c) {
            if (c == 401 || c == 403 || c == 410)
                return "HTTP " + c + ": o link expirou ou foi bloqueado. Toque no item e use \"Atualizar link\".";
            if (c == 404) return "HTTP 404: link não encontrado. Toque no item e use \"Atualizar link\".";
            return "HTTP " + c;
        }

        boolean fatal() { return code >= 400 && code < 500 && code != 408 && code != 429; }
    }

    final String ua, referer, cookie, host;
    final Item it;

    Net(String ua, String referer, String cookie, String origUrl, Item it) {
        this.ua = ua == null || ua.isEmpty() ? DEFAULT_UA : ua;
        this.referer = referer == null ? "" : referer;
        this.cookie = cookie == null ? "" : cookie;
        this.it = it;
        String h = "";
        try { h = new URL(origUrl).getHost(); } catch (Exception ignored) { }
        this.host = h;
    }

    void check() throws Stop {
        if (it != null && it.stop != 0) throw new Stop();
    }

    HttpURLConnection open(String u, String range) throws IOException {
        check();
        String cur = u;
        for (int i = 0; i < 6; i++) {
            URL url = new URL(cur);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Accept", "*/*");
            if (!referer.isEmpty() && !referer.startsWith("file:")) c.setRequestProperty("Referer", referer);
            if (!cookie.isEmpty() && url.getHost().equals(host)) c.setRequestProperty("Cookie", cookie);
            if (range != null) c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirecionamento inválido");
                cur = new URL(url, loc).toString();
                continue;
            }
            if (code >= 200 && code < 300) return c;
            c.disconnect();
            throw new HttpException(code);
        }
        throw new IOException("Redirecionamentos demais");
    }

    byte[] bytes(String u) throws IOException {
        HttpURLConnection c = open(u, null);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) != -1) {
                check();
                o.write(b, 0, n);
            }
            return o.toByteArray();
        }
    }

    String text(String u) throws IOException {
        return new String(bytes(u), "UTF-8");
    }
}
