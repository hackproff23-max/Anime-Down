package com.animedown.app;

import android.media.MediaMetadataRetriever;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Descobre resolução (e, se faltar, uma estimativa de tamanho) lendo o próprio vídeo, em qualquer site. */
final class MediaInfo {
    static final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "mediainfo");
            t.setDaemon(true);
            return t;
        }
    });

    static class Info {
        int w, h;
        long durMs, bitrate;
    }

    static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    static long lnum(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    static Info read(String url, String ua, String referer, String cookie) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            Map<String, String> hd = new HashMap<>();
            if (ua != null && !ua.isEmpty()) hd.put("User-Agent", ua);
            if (referer != null && referer.startsWith("http")) hd.put("Referer", referer);
            if (cookie != null && !cookie.isEmpty()) hd.put("Cookie", cookie);
            r.setDataSource(url, hd);
            Info i = new Info();
            i.w = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            i.h = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            i.durMs = lnum(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            i.bitrate = lnum(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            return i;
        } catch (Throwable e) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    /** Preenche a resolução (lado menor, como o YouTube: 1920x1080 = 1080p) e, se não houver tamanho, estima pelo bitrate. */
    static void fill(final Net net, final Probe.Opt o) {
        if (o.height > 0 && o.size > 0) return;
        final String url = o.url;
        Future<Info> f = pool.submit(new Callable<Info>() {
            public Info call() { return read(url, net.ua, net.referer, net.cookie); }
        });
        Info i = null;
        try {
            i = f.get(8, TimeUnit.SECONDS);
        } catch (Throwable e) {
            f.cancel(true);
        }
        if (i == null) return;
        int side = Math.min(i.w, i.h);
        if (o.height <= 0 && side > 0) {
            o.height = side;
            o.label = side + "p";
        }
        if (o.size <= 0 && i.bitrate > 0 && i.durMs > 0) {
            o.size = i.bitrate / 8 * i.durMs / 1000;
            o.estimated = true;
        }
    }
}
