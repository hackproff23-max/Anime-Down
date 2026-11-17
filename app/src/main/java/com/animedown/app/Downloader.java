package com.animedown.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Baixa em partes (blocos de arquivo ou segmentos HLS) com várias conexões em paralelo.
 * Cada parte concluída fica salva em disco, então dá para pausar, continuar e até trocar o link
 * sem recomeçar do zero.
 */
final class Downloader implements Runnable {
    static class Piece {
        int idx;
        String url;                 // null = usa it.url (arquivo único)
        long start = -1, end = -1;  // intervalo de bytes (blocos)
        long size = -1;
        String key, iv;
        long seq;
    }

    final Context ctx;
    final Item it;
    final Map<String, byte[]> keys = new ConcurrentHashMap<>();

    Downloader(Context c, Item i) { ctx = c; it = i; }

    @Override public void run() {
        if (!it.running.compareAndSet(false, true)) return;
        File dir = Dl.dir(it);
        try {
            if (it.stop != 0) throw new Net.Stop();
            it.status = Item.RUNNING;
            it.msg = "Preparando…";
            it.speed = 0;
            Dl.changed();
            dir.mkdirs();
            Net net = new Net(it.ua, it.referer, it.cookie, it.url, it);
            List<Piece> pieces = plan(net, dir);
            it.msg = "";
            download(net, pieces, dir);

            it.msg = "Finalizando…";
            Dl.ui();
            String fname = safe(stripExt(it.name)) + "." + it.ext;
            List<File> files = new ArrayList<>();
            for (Piece p : pieces) files.add(pf(dir, p.idx));
            it.outUri = publish(files, fname, mime(it.ext));
            it.name = fname;
            it.msg = "";
            it.status = Item.DONE;
            it.pieceDone = it.pieceTotal;
            if (!it.hls && it.total > 0) it.done = it.total;
            Dl.deleteDir(dir);
        } catch (Net.Stop e) {
            finishStopped(dir);
        } catch (Throwable e) {
            if (it.stop != 0) {
                finishStopped(dir);
            } else {
                it.status = Item.ERROR;
                it.msg = e.getMessage() != null ? e.getMessage() : e.toString();
            }
        } finally {
            it.speed = 0;
            it.running.set(false);
            Dl.changed();
        }
    }

    void finishStopped(File dir) {
        it.msg = "";
        if (it.stop == 2) {
            it.status = Item.CANCELED;
            Dl.deleteDir(dir);
        } else {
            it.status = Item.PAUSED;
        }
    }

    // ---------------------------------------------------------------- plano

    List<Piece> plan(Net net, File dir) throws Exception {
        boolean hls = isHls(it.url);
        if (!hls) {
            HttpURLConnection c = net.open(it.url, "bytes=0-0");
            String ct = "";
            long total = -1;
            boolean ranged = false;
            try {
                ct = c.getContentType();
                ct = ct == null ? "" : ct.toLowerCase();
                if (ct.contains("mpegurl")) {
                    hls = true;
                } else {
                    if (ct.startsWith("text/")) {
                        throw new IOException("O link não retornou um vídeo (veio uma página). Abra o vídeo no navegador do app e use \"Vídeos detectados\".");
                    }
                    if (c.getResponseCode() == 206) {
                        total = Probe.totalFromRange(c.getHeaderField("Content-Range"));
                        ranged = total > 0;
                    } else {
                        total = c.getContentLengthLong();
                    }
                }
            } finally {
                c.disconnect();
            }
            if (!hls) return planHttp(ct, total, ranged, dir);
        }
        return planHls(net, dir);
    }

    List<Piece> planHttp(String ct, long total, boolean ranged, File dir) throws IOException {
        boolean had = hasParts(dir);
        if (had && it.hls) { wipe(dir); had = false; }
        if (had && it.total > 0 && total > 0 && it.total != total) {
            throw new IOException("O novo link é de um arquivo com tamanho diferente (" + Fmt.size(it.total)
                    + " x " + Fmt.size(total) + "). Não dá para continuar o download.");
        }
        it.hls = false;
        it.ext = extFor(it.url, ct);
        List<Piece> ps = new ArrayList<>();
        if (ranged && total > 2L * 1024 * 1024) {
            long bs = had && it.blockSize > 0 ? it.blockSize
                    : Math.max(1L << 20, Math.min(8L << 20, total / (Dl.threads() * 4L)));
            it.blockSize = bs;
            int n = (int) ((total + bs - 1) / bs);
            for (int i = 0; i < n; i++) {
                Piece p = new Piece();
                p.idx = i;
                p.start = i * bs;
                p.end = Math.min(total - 1, p.start + bs - 1);
                p.size = p.end - p.start + 1;
                ps.add(p);
            }
        } else {
            if (had && it.blockSize > 0) {
                throw new IOException("O servidor do novo link não permite continuar de onde parou.");
            }
            it.blockSize = 0;
            Piece p = new Piece();
            p.idx = 0;
            p.size = total > 0 ? total : -1;
            ps.add(p);
        }
        it.total = Math.max(0, total);
        it.pieceTotal = ps.size();
        return ps;
    }

    List<Piece> planHls(Net net, File dir) throws Exception {
        boolean had = hasParts(dir);
        if (had && !it.hls) { wipe(dir); had = false; }
        String base = it.url;
        String text = net.text(base);

        if (text.contains("#EXT-X-STREAM-INF")) {
            String best = null;
            long bestBw = -1;
            int bestH = 0, bestDiff = Integer.MAX_VALUE;
            long bw = 0;
            int h = 0;
            boolean has = false;
            for (String raw : text.split("\\r?\\n")) {
                String l = raw.trim();
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    bw = Probe.num(attr(l, "BANDWIDTH"));
                    h = 0;
                    String res = attr(l, "RESOLUTION");
                    if (res != null && res.contains("x")) h = (int) Probe.num(res.substring(res.indexOf('x') + 1));
                    has = true;
                } else if (has && !l.isEmpty() && !l.startsWith("#")) {
                    boolean better;
                    if (it.height > 0 && h > 0) {
                        int diff = Math.abs(h - it.height);
                        better = diff < bestDiff || (diff == bestDiff && bw > bestBw);
                        if (better) bestDiff = diff;
                    } else if (it.height > 0) {
                        better = best == null;
                    } else {
                        better = bw > bestBw;
                    }
                    if (better) { best = l; bestBw = bw; bestH = h; }
                    has = false;
                }
            }
            if (best == null) throw new IOException("Playlist HLS sem variantes");
            if (it.height == 0 && bestH > 0) it.height = bestH;
            base = resolve(base, best);
            text = net.text(base);
        }

        List<Piece> segs = new ArrayList<>();
        String key = null, iv = null, map = null;
        long seq = 0;
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = Math.max(0, Probe.num(l.substring(l.indexOf(':') + 1)));
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
                Piece p = new Piece();
                p.url = resolve(base, l);
                p.key = key;
                p.iv = iv;
                p.seq = seq++;
                segs.add(p);
            }
        }
        if (segs.isEmpty()) throw new IOException("Playlist HLS vazia");

        List<Piece> ps = new ArrayList<>();
        if (map != null) {
            Piece m = new Piece();
            m.url = map;
            ps.add(m);
        }
        ps.addAll(segs);
        for (int i = 0; i < ps.size(); i++) ps.get(i).idx = i;

        if (had && it.pieceTotal > 0 && it.pieceTotal != ps.size()) {
            throw new IOException("A playlist nova tem " + ps.size() + " partes e a antiga tinha " + it.pieceTotal
                    + ". Não dá para continuar; cancele e baixe de novo.");
        }
        it.hls = true;
        it.ext = map != null ? "mp4" : "ts";
        it.pieceTotal = ps.size();
        return ps;
    }

    // ------------------------------------------------------------- download

    void download(final Net net, final List<Piece> pieces, final File dir) throws Exception {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".tmp")) f.delete();

        long have = 0;
        int doneCount = 0;
        final ConcurrentLinkedQueue<Piece> q = new ConcurrentLinkedQueue<>();
        for (Piece p : pieces) {
            File f = pf(dir, p.idx);
            if (f.exists()) { have += f.length(); doneCount++; } else q.add(p);
        }
        final int total = pieces.size();
        final AtomicLong live = new AtomicLong(have);
        final AtomicLong fin = new AtomicLong(have);
        final AtomicInteger cnt = new AtomicInteger(doneCount);
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicReference<Throwable> err = new AtomicReference<>();
        it.done = have;
        it.pieceDone = doneCount;
        it.pieceTotal = total;

        int th = Dl.threads();
        if (it.hls) th = Math.min(32, th * 2);
        th = Math.max(1, Math.min(th, q.size()));
        ExecutorService ex = Executors.newFixedThreadPool(th);
        for (int t = 0; t < th; t++) {
            ex.submit(new Runnable() {
                public void run() {
                    Piece p;
                    while (!abort.get() && err.get() == null && it.stop == 0 && (p = q.poll()) != null) {
                        try {
                            fetch(net, p, dir, live, fin, abort);
                            cnt.incrementAndGet();
                        } catch (Throwable e) {
                            err.compareAndSet(null, e);
                            return;
                        }
                    }
                }
            });
        }

        long last = SystemClock.elapsedRealtime(), lastBytes = live.get(), lastSave = last;
        try {
            while (cnt.get() < total && err.get() == null && it.stop == 0) {
                Thread.sleep(300);
                long now = SystemClock.elapsedRealtime();
                long b = live.get();
                long inst = Math.max(0, (b - lastBytes) * 1000 / Math.max(1, now - last));
                it.speed = it.speed == 0 ? inst : (it.speed * 6 + inst * 4) / 10;
                last = now;
                lastBytes = b;
                it.done = b;
                int c = cnt.get();
                it.pieceDone = c;
                if (it.hls && c >= 2) it.total = fin.get() * total / c;
                if (now - lastSave > 3000) { lastSave = now; Dl.save(); }
                Dl.ui();
            }
        } finally {
            abort.set(true);
            ex.shutdownNow();
            try { ex.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            File[] left = dir.listFiles();
            if (left != null) for (File f : left) if (f.getName().endsWith(".tmp")) f.delete();
        }
        it.done = live.get();
        it.pieceDone = cnt.get();
        if (it.stop != 0) throw new Net.Stop();
        Throwable e = err.get();
        if (e != null) {
            if (e instanceof Exception) throw (Exception) e;
            throw new RuntimeException(e);
        }
    }

    void fetch(Net net, Piece p, File dir, AtomicLong live, AtomicLong fin, AtomicBoolean abort) throws Exception {
        File out = pf(dir, p.idx);
        File tmp = new File(dir, "p_" + p.idx + ".tmp");
        for (int attempt = 0; ; attempt++) {
            long counted = 0;
            try {
                String u = p.url != null ? p.url : it.url;
                HttpURLConnection c = net.open(u, p.start >= 0 ? "bytes=" + p.start + "-" + p.end : null);
                if (p.start >= 0 && c.getResponseCode() == 200) {
                    c.disconnect();
                    throw new IOException("O servidor ignorou o pedido de trecho (Range)");
                }
                long written = 0;
                byte[] b = new byte[65536];
                int n;
                if (p.key != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        while ((n = in.read(b)) != -1) {
                            if (abort.get()) throw new Net.Stop();
                            net.check();
                            bo.write(b, 0, n);
                            live.addAndGet(n);
                            counted += n;
                        }
                    }
                    byte[] k = keys.get(p.key);
                    if (k == null) { k = net.bytes(p.key); keys.put(p.key, k); }
                    byte[] d = decrypt(bo.toByteArray(), k, p);
                    try (OutputStream o = new FileOutputStream(tmp)) { o.write(d); }
                    live.addAndGet(d.length - counted);
                    counted = d.length;
                    written = d.length;
                } else {
                    try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
                        while ((n = in.read(b)) != -1) {
                            if (abort.get()) throw new Net.Stop();
                            net.check();
                            o.write(b, 0, n);
                            live.addAndGet(n);
                            counted += n;
                            written += n;
                        }
                    }
                }
                if (p.size >= 0 && written != p.size) {
                    throw new IOException("Download incompleto (" + written + " de " + p.size + " bytes)");
                }
                if (!tmp.renameTo(out)) throw new IOException("Falha ao gravar a parte " + p.idx);
                fin.addAndGet(written);
                return;
            } catch (Net.Stop s) {
                live.addAndGet(-counted);
                tmp.delete();
                throw s;
            } catch (IOException e) {
                live.addAndGet(-counted);
                tmp.delete();
                if (it.stop != 0 || abort.get()) throw new Net.Stop();
                boolean fatal = e instanceof Net.HttpException && ((Net.HttpException) e).fatal();
                if (fatal || attempt >= 3) throw e;
                Thread.sleep(600L * (attempt + 1));
            }
        }
    }

    static byte[] decrypt(byte[] d, byte[] k, Piece s) throws Exception {
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

    // ------------------------------------------------- salvar em Downloads

    String publish(List<File> files, String fname, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, fname);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Anime-Down");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("Não foi possível criar o arquivo em Downloads");
            try (OutputStream o = r.openOutputStream(u)) {
                concat(files, o);
            } catch (Exception e) {
                r.delete(u, null, null);
                throw e;
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
        try (OutputStream o = new FileOutputStream(out)) {
            concat(files, o);
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

    static void concat(List<File> files, OutputStream o) throws IOException {
        byte[] b = new byte[262144];
        for (File f : files) {
            try (InputStream i = new FileInputStream(f)) {
                int n;
                while ((n = i.read(b)) != -1) o.write(b, 0, n);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    static File pf(File dir, int idx) { return new File(dir, "p_" + idx); }

    static boolean hasParts(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return false;
        for (File f : fs) if (f.getName().startsWith("p_") && !f.getName().endsWith(".tmp")) return true;
        return false;
    }

    static void wipe(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    static boolean isHls(String url) {
        String p = Uri.parse(url).getPath();
        return p != null && p.toLowerCase().contains(".m3u8");
    }

    static String attr(String line, String name) {
        Matcher m = Pattern.compile("(?:^|[:,])" + name + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    static String resolve(String base, String rel) throws IOException {
        return new URL(new URL(base), rel).toString();
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
