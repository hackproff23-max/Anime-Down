package com.animedown.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Dl {
    static final List<Item> items = new ArrayList<>();
    static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    static final Handler main = new Handler(Looper.getMainLooper());
    static final ExecutorService pool = Executors.newFixedThreadPool(2);
    static Context app;
    static boolean loaded;

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        app = c.getApplicationContext();
        try {
            JSONArray a = new JSONArray(app.getSharedPreferences("dl", 0).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Item it = Item.fromJson(a.getJSONObject(i));
                if (it.status == Item.RUNNING || it.status == Item.QUEUED) {
                    it.status = Item.ERROR;
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

    static void progress(Item it) {
        long n = SystemClock.uptimeMillis();
        if (n - it.lastUi < 400) return;
        it.lastUi = n;
        ui();
    }

    static synchronized Item add(Context c, String url, String name, String referer, String ua, String cookie) {
        load(c);
        Item it = new Item();
        it.id = UUID.randomUUID().toString();
        it.url = url;
        it.name = name;
        it.referer = referer == null ? "" : referer;
        it.ua = ua == null ? "" : ua;
        it.cookie = cookie == null ? "" : cookie;
        items.add(it);
        start(it);
        return it;
    }

    static synchronized void start(Item it) {
        it.cancel = false;
        it.status = Item.QUEUED;
        it.done = 0;
        it.total = 0;
        it.msg = "";
        save();
        ui();
        Intent i = new Intent(app, DownloadService.class);
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
        pool.execute(new Downloader(app, it));
    }

    static void cancel(Item it) { it.cancel = true; }

    static synchronized void remove(Item it) {
        it.cancel = true;
        items.remove(it);
        save();
        ui();
    }

    static synchronized void clearFinished() {
        for (Iterator<Item> i = items.iterator(); i.hasNext(); ) {
            Item it = i.next();
            if (it.status == Item.DONE || it.status == Item.ERROR || it.status == Item.CANCELED) i.remove();
        }
        save();
        ui();
    }

    static synchronized int active() {
        int n = 0;
        for (Item it : items) if (it.status == Item.QUEUED || it.status == Item.RUNNING) n++;
        return n;
    }

    static synchronized Item firstRunning() {
        for (Item it : items) if (it.status == Item.RUNNING) return it;
        return null;
    }
}
