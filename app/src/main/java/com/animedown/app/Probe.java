package com.animedown.app;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descobre as qualidades disponíveis e o tamanho de cada uma. */
final class Probe {
    static final ExecutorService pool = Executors.newFixedThreadPool(4);

    static class Opt {
        String label = "Original", url;
        long size = -1, bw;
        boolean estimated, hls, variant;
        int height;

        String sub() {
            String s = size > 0 ? (estimated ? "~" : "") + Fmt.size(size) : "tamanho desconhecido";
            return s + (hls ? "  •  HLS" : "");
        }
    }

    static List<Opt> run(final Net net, String url) throws Exception {
        List<Opt> out = new ArrayList<>();
        boolean hls = Downloader.isHls(url);
        if (!hls) {
            HttpURLConnection c = net.open(url, "bytes=0-0");
            try {
                String ct = c.getContentType();
                ct = ct == null ? "" : ct.toLowerCase();
                if (ct.contains("mpegurl")) {
                    hls = true;
                } else {
                    if (ct.startsWith("text/")) return out;
                    long size = -1;
                    if (c.getResponseCode() == 206) {
                        size = totalFromRange(c.getHeaderField("Content-Range"));
                    } else {
                        size = c.getContentLengthLong();
                    }
                    Opt o = new Opt();
                    o.url = url;
                    o.size = size;
                    o.height = guessHeight(url);
                    o.label = o.height > 0 ? o.height + "p" : "Original";
                    out.add(o);
                    return out;
                }
            } finally {
                c.disconnect();
            }
        }

        String text = net.text(url);
        if (text.contains("#EXT-X-STREAM-INF")) {
            final List<Opt> vs = new ArrayList<>();
            long bw = 0;
            int h = 0;
            boolean has = false;
            for (String raw : text.split("\\r?\\n")) {
                String l = raw.trim();
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    bw = num(Downloader.attr(l, "AVERAGE-BANDWIDTH"));
                    if (bw <= 0) bw = num(Downloader.attr(l, "BANDWIDTH"));
                    h = 0;
                    String res = Downloader.attr(l, "RESOLUTION");
                    if (res != null && res.contains("x")) h = (int) num(res.substring(res.indexOf('x') + 1));
                    has = true;
                } else if (has && !l.isEmpty() && !l.startsWith("#")) {
                    Opt o = new Opt();
                    o.url = Downloader.resolve(url, l);
                    o.hls = true;
                    o.variant = true;
                    o.estimated = true;
                    o.bw = bw;
                    o.height = h;
                    o.label = h > 0 ? h + "p" : (bw > 0 ? (bw / 1000) + " kbps" : "Qualidade");
                    vs.add(o);
                    has = false;
                }
            }
            if (vs.isEmpty()) return out;
            ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, Math.min(4, vs.size())));
            List<Future<Double>> fs = new ArrayList<>();
            for (final Opt o : vs) {
                fs.add(ex.submit(new Callable<Double>() {
                    public Double call() {
                        try { return duration(net, o.url); } catch (Exception e) { return -1.0; }
                    }
                }));
            }
            for (int i = 0; i < vs.size(); i++) {
                double d = fs.get(i).get();
                Opt o = vs.get(i);
                if (o.bw > 0 && d > 0) o.size = (long) (o.bw * d / 8);
            }
            ex.shutdown();
            out.addAll(vs);
        } else {
            int count = 0;
            String first = null;
            for (String raw : text.split("\\r?\\n")) {
                String l = raw.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                count++;
                if (first == null) first = Downloader.resolve(url, l);
            }
            Opt o = new Opt();
            o.url = url;
            o.hls = true;
            o.estimated = true;
            if (first != null) {
                try {
                    HttpURLConnection c = net.open(first, "bytes=0-0");
                    long sz = c.getResponseCode() == 206 ? totalFromRange(c.getHeaderField("Content-Range")) : c.getContentLengthLong();
                    c.disconnect();
                    if (sz > 0) o.size = sz * count;
                } catch (Exception ignored) {
                }
            }
            out.add(o);
        }
        return out;
    }

    static double duration(Net net, String url) throws Exception {
        String text = net.text(url);
        double d = 0;
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (l.startsWith("#EXTINF:")) {
                String v = l.substring(8);
                int c = v.indexOf(',');
                if (c >= 0) v = v.substring(0, c);
                try { d += Double.parseDouble(v.trim()); } catch (Exception ignored) { }
            }
        }
        return d;
    }

    static long totalFromRange(String cr) {
        if (cr == null || cr.indexOf('/') < 0) return -1;
        return num(cr.substring(cr.indexOf('/') + 1).trim());
    }

    static long num(String s) {
        if (s == null) return -1;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    static int guessHeight(String url) {
        Matcher m = Pattern.compile("(?i)(?<![0-9a-z])(144|240|360|480|540|720|1080|1440|2160)p(?![0-9a-z])").matcher(url);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("[/_-](240|360|480|720|1080)(?=[/_.-])").matcher(url);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }
}
