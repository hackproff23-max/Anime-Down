package com.animedown.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Dl {
    static final List<Item> items = new ArrayList<>();
    static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    static final LinkedHashSet<String> detected = new LinkedHashSet<>();
    static final Handler main = new Handler(Looper.getMainLooper());
    static final ExecutorService pool = Executors.newFixedThreadPool(3);
    static Context app;
    static boolean loaded;

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        app = c.getApplicationContext();
        System.setProperty("http.maxConnections", "64");
        try {
            JSONArray a = new JSONArray(app.getSharedPreferences("dl", 0).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Item it = Item.fromJson(a.getJSONObject(i));
                if (it.status == Item.RUNNING || it.status == Item.QUEUED) {
                    it.status = Item.PAUSED;
                    it.msg = "Interrompido";
                }
                items.add(it);
            }
        } catch (Exception ignored) {
        }
    }

    static synchronized void save() {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            try { a.put(it.toJson()); } catch (Exception ignored) { }
        }
        app.getSharedPreferences("dl", 0).edit().putString("items", a.toString()).apply();
    }

    static void changed() { save(); ui(); }

    static void ui() {
        main.post(new Runnable() {
            public void run() {
                for (Runnable r : listeners) r.run();
            }
        });
    }

    static int threads() { return app.getSharedPreferences("dl", 0).getInt("threads", 8); }

    static void setThreads(int n) { app.getSharedPreferences("dl", 0).edit().putInt("threads", n).apply(); }

    static File dir(Item it) { return new File(app.getFilesDir(), "parts/" + it.id); }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteDir(f);
            f.delete();
        }
        d.delete();
    }

    static synchronized Item add(Context c, String url, String name, String referer, String ua, String cookie,
                                 int height, String quality, long size) {
        load(c);
        Item it = new Item();
        it.id = UUID.randomUUID().toString();
        it.url = url;
        it.name = name;
        it.referer = referer == null ? "" : referer;
        it.ua = ua == null ? "" : ua;
        it.cookie = cookie == null ? "" : cookie;
        it.height = height;
        it.quality = quality == null ? "" : quality;
        it.total = size > 0 ? size : 0;
        items.add(it);
        start(it);
        return it;
    }

    /** Inicia, continua (de onde parou) ou tenta de novo. */
    static synchronized void start(Item it) {
        if (it.status == Item.CANCELED) {
            it.done = 0; it.pieceDone = 0; it.pieceTotal = 0; it.blockSize = 0;
        }
        it.stop = 0;
        it.status = Item.QUEUED;
        it.msg = "";
        it.speed = 0;
        save();
        ui();
        Intent i = new Intent(app, DownloadService.class);
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
        pool.execute(new Downloader(app, it));
    }

    static void pause(Item it) {
        it.stop = 1;
        if (it.status == Item.QUEUED) it.status = Item.PAUSED;
        changed();
    }

    static void cancel(Item it) {
        it.stop = 2;
        if (it.status == Item.QUEUED) { it.status = Item.CANCELED; deleteDir(dir(it)); }
        changed();
    }

    static synchronized void remove(Item it) {
        it.stop = 2;
        items.remove(it);
        if (!it.running.get()) deleteDir(dir(it));
        save();
        ui();
    }

    /** Troca o link e continua o download de onde parou. */
    static synchronized void updateLink(Item it, String url) {
        it.url = url;
        try {
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) it.cookie = ck;
        } catch (Exception ignored) {
        }
        start(it);
    }

    static synchronized void clearFinished() {
        for (Iterator<Item> i = items.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (it.status == Item.DONE || it.status == Item.CANCELED) i.remove();
        }
        save();
        ui();
    }

    static synchronized int active() {
        int n = 0;
        for (Item it : items) if (it.status == Item.QUEUED || it.status == Item.RUNNING) n++;
        return n;
    }

    static synchronized long totalSpeed() {
        long s = 0;
        for (Item it : items) if (it.status == Item.RUNNING) s += it.speed;
        return s;
    }

    /** Texto resumo dos downloads ativos, ou null se não houver. */
    static synchronized String summary() {
        int n = 0;
        long sp = 0, d = 0, t = 0;
        for (Item it : items) {
            if (it.status == Item.QUEUED || it.status == Item.RUNNING) n++;
            if (it.status == Item.RUNNING) {
                sp += it.speed;
                if (it.total > 0) { d += Math.min(it.done, it.total); t += it.total; }
            }
        }
        if (n == 0) return null;
        String s = "⬇ " + n + " baixando";
        if (t > 0) s += "  •  " + (d * 100 / t) + "%";
        if (sp > 0) s += "  •  " + Fmt.speed(sp);
        return s;
    }

    static synchronized int overallPermille() {
        long d = 0, t = 0;
        for (Item it : items) {
            if (it.status == Item.RUNNING && it.total > 0) { d += Math.min(it.done, it.total); t += it.total; }
        }
        return t > 0 ? (int) (d * 1000 / t) : -1;
    }

    static void progress(Item it) {
        long n = SystemClock.uptimeMillis();
        if (n - it.lastUi < 300) return;
        it.lastUi = n;
        ui();
    }
}
