package com.animedown.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class Downloader implements Runnable {
    static class Canceled extends IOException { }

    static class Seg { String url, key, iv; long seq; }

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    final Context ctx;
    final Item it;

    Downloader(Context c, Item i) { ctx = c; it = i; }

    @Override public void run() {
        File tmp = new File(ctx.getFilesDir(), it.id + ".part");
        try {
            if (it.cancel) throw new Canceled();
            it.status = Item.RUNNING;
            it.msg = "";
            it.done = 0;
            it.total = 0;
            Dl.changed();
            String ext = isHls(it.url) ? hls(tmp) : http(tmp);
            if (it.cancel) throw new Canceled();
            it.name = safe(stripExt(it.name)) + "." + ext;
            it.outUri = publish(tmp, it.name, mime(ext));
            it.status = Item.DONE;
        } catch (Canceled e) {
            it.status = Item.CANCELED;
        } catch (Throwable e) {
            if (it.cancel) {
                it.status = Item.CANCELED;
            } else {
                it.status = Item.ERROR;
                it.msg = e.getMessage() != null ? e.getMessage() : e.toString();
            }
        } finally {
            tmp.delete();
            Dl.changed();
        }
    }

    // ---------- HTTP ----------

    String http(File tmp) throws Exception {
        HttpURLConnection c = open(it.url);
        try {
            String ct = c.getContentType();
            ct = ct == null ? "" : ct.toLowerCase();
            if (ct.contains("mpegurl")) {
                c.disconnect();
                return hls(tmp);
            }
            if (ct.startsWith("text/")) {
                throw new IOException("O link não retornou um vídeo (veio uma página). Abra o vídeo no navegador do app e use \"Vídeos detectados\".");
            }
            long len = c.getContentLengthLong();
            it.total = len > 0 ? len : 0;
            it.hls = false;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (it.cancel) throw new Canceled();
                    out.write(b, 0, n);
                    it.done += n;
                    Dl.progress(it);
                }
            }
            return extFor(it.url, ct);
        } finally {
            c.disconnect();
        }
    }

    HttpURLConnection open(String u) throws IOException {
        String cur = u;
        String origHost = new URL(it.url).getHost();
        for (int i = 0; i < 6; i++) {
            URL url = new URL(cur);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", it.ua.isEmpty() ? DEFAULT_UA : it.ua);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (!it.referer.isEmpty()) c.setRequestProperty("Referer", it.referer);
            if (!it.cookie.isEmpty() && url.getHost().equals(origHost)) c.setRequestProperty("Cookie", it.cookie);
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
            throw new IOException("HTTP " + code);
        }
        throw new IOException("Redirecionamentos demais");
    }

    byte[] bytes(String u) throws IOException {
        HttpURLConnection c = open(u);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) != -1) {
                if (it.cancel) throw new Canceled();
                o.write(b, 0, n);
            }
            return o.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    // ---------- HLS (.m3u8) ----------

    String hls(File tmp) throws Exception {
        String base = it.url;
        String text = new String(bytes(base), "UTF-8");
        if (text.contains("#EXT-X-STREAM-INF")) {
            String best = null;
            long bw = -1, cur = -1;
            for (String raw : text.split("\\r?\\n")) {
                String l = raw.trim();
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    try { cur = Long.parseLong(attr(l, "BANDWIDTH")); } catch (Exception e) { cur = 0; }
                } else if (!l.isEmpty() && !l.startsWith("#") && cur >= 0) {
                    if (cur > bw) { bw = cur; best = l; }
                    cur = -1;
                }
            }
            if (best == null) throw new IOException("Playlist HLS sem variantes");
            base = resolve(base, best);
            text = new String(bytes(base), "UTF-8");
        }

        List<Seg> segs = new ArrayList<>();
        String key = null, iv = null, map = null;
        long seq = 0;
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { seq = Long.parseLong(l.substring(l.indexOf(':') + 1).trim()); } catch (Exception ignored) { }
            } else if (l.startsWith("#EXT-X-KEY:")) {
                String m = attr(l, "METHOD");
                if ("NONE".equals(m)) {
                    key = null; iv = null;
                } else if ("AES-128".equals(m)) {
                    String ku = attr(l, "URI");
                    if (ku == null) throw new IOException("Chave HLS sem URI");
                    key = resolve(base, ku);
                    iv = attr(l, "IV");
                } else {
                    throw new IOException("Vídeo com proteção/criptografia não suportada (" + m + ")");
                }
            } else if (l.startsWith("#EXT-X-MAP:")) {
                String mu = attr(l, "URI");
                if (mu != null) map = resolve(base, mu);
            } else if (!l.startsWith("#")) {
                Seg s = new Seg();
                s.url = resolve(base, l);
                s.key = key;
                s.iv = iv;
                s.seq = seq;
                seq++;
                segs.add(s);
            }
        }
        if (segs.isEmpty()) throw new IOException("Playlist HLS vazia");

        it.hls = true;
        it.total = segs.size();
        it.done = 0;
        Map<String, byte[]> keys = new HashMap<>();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            if (map != null) out.write(bytes(map));
            for (Seg s : segs) {
                byte[] d = null;
                IOException last = null;
                for (int t = 0; t < 3 && d == null; t++) {
                    if (it.cancel) throw new Canceled();
                    try {
                        d = bytes(s.url);
                    } catch (Canceled c) {
                        throw c;
                    } catch (IOException e) {
                        last = e;
                    }
                }
                if (d == null) throw last;
                if (s.key != null) {
                    byte[] k = keys.get(s.key);
                    if (k == null) { k = bytes(s.key); keys.put(s.key, k); }
                    d = decrypt(d, k, s);
                }
                out.write(d);
                it.done++;
                Dl.progress(it);
            }
        }
        return map != null ? "mp4" : "ts";
    }

    static byte[] decrypt(byte[] d, byte[] k, Seg s) throws Exception {
        byte[] iv = new byte[16];
        if (s.iv != null) {
            String h = s.iv.startsWith("0x") || s.iv.startsWith("0X") ? s.iv.substring(2) : s.iv;
            while (h.length() < 32) h = "0" + h;
            for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        } else {
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (s.seq >>> (8 * i));
        }
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(iv));
        return c.doFinal(d);
    }

    static String attr(String line, String name) {
        Matcher m = Pattern.compile("(?:^|[:,])" + name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    static String resolve(String base, String rel) throws IOException {
        return new URL(new URL(base), rel).toString();
    }

    // ---------- Salvar em Downloads/Anime-Down ----------

    String publish(File f, String fname, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fname);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Anime-Down");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("Não foi possível criar o arquivo em Downloads");
            try (OutputStream o = r.openOutputStream(u); InputStream i = new FileInputStream(f)) {
                copy(i, o);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            r.update(u, v, null, null);
            return u.toString();
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Anime-Down");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Sem permissão para gravar em Downloads");
        int dot = fname.lastIndexOf('.');
        String b = dot > 0 ? fname.substring(0, dot) : fname;
        String e = dot > 0 ? fname.substring(dot) : "";
        File out = new File(dir, fname);
        int n = 1;
        while (out.exists()) out = new File(dir, b + " (" + (n++) + ")" + e);
        try (OutputStream o = new FileOutputStream(out); InputStream i = new FileInputStream(f)) {
            copy(i, o);
        }
        final Uri[] res = new Uri[1];
        final CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(ctx, new String[]{out.getAbsolutePath()}, new String[]{mime},
                new MediaScannerConnection.OnScanCompletedListener() {
                    public void onScanCompleted(String path, Uri uri) { res[0] = uri; latch.countDown(); }
                });
        latch.await(5, TimeUnit.SECONDS);
        return res[0] != null ? res[0].toString() : Uri.fromFile(out).toString();
    }

    static void copy(InputStream i, OutputStream o) throws IOException {
        byte[] b = new byte[65536];
        int n;
        while ((n = i.read(b)) != -1) o.write(b, 0, n);
    }

    // ---------- helpers ----------

    static boolean isHls(String url) {
        String p = Uri.parse(url).getPath();
        return p != null && p.toLowerCase().contains(".m3u8");
    }

    static String extFor(String url, String ct) {
        String p = Uri.parse(url).getPath();
        if (p != null) {
            p = p.toLowerCase();
            for (String e : new String[]{"mp4", "mkv", "webm", "mov", "avi", "flv", "ts", "m4v"}) {
                if (p.endsWith("." + e)) return e;
            }
        }
        if (ct.contains("webm")) return "webm";
        if (ct.contains("matroska")) return "mkv";
        if (ct.contains("mp2t")) return "ts";
        return "mp4";
    }

    static String mime(String ext) {
        switch (ext) {
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "ts": return "video/mp2t";
            case "mov": return "video/quicktime";
            case "avi": return "video/x-msvideo";
            case "flv": return "video/x-flv";
            default: return "video/mp4";
        }
    }

    static String stripExt(String n) {
        return n.replaceAll("(?i)\\.(mp4|mkv|webm|mov|avi|flv|ts|m4v|m3u8)$", "");
    }

    static String safe(String n) {
        n = n.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (n.length() > 100) n = n.substring(0, 100).trim();
        return n.isEmpty() ? "video" : n;
    }
}
