package com.animedown.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Favoritos, histórico e última página aberta (guardados em SharedPreferences). */
final class Store {
    static final String FAV = "fav";
    static final String HIST = "hist";
    static final int MAX_HIST = 300;

    static class Entry {
        String title, url;
        long time;
    }

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("browser", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------ última página

    static String last(Context c) {
        return sp(c).getString("last", "");
    }

    static void setLast(Context c, String url) {
        sp(c).edit().putString("last", url == null ? "" : url).apply();
    }

    // ------------------------------------------------ listas

    static List<Entry> list(Context c, String key) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.title = o.optString("t", "");
                e.url = o.optString("u", "");
                e.time = o.optLong("d", 0);
                if (!e.url.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(Context c, String key, List<Entry> l) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : l) {
                JSONObject o = new JSONObject();
                o.put("t", e.title == null ? "" : e.title);
                o.put("u", e.url);
                o.put("d", e.time);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        sp(c).edit().putString(key, a.toString()).apply();
    }

    // ------------------------------------------------ favoritos

    static boolean isFav(Context c, String url) {
        if (url == null) return false;
        for (Entry e : list(c, FAV)) if (e.url.equals(url)) return true;
        return false;
    }

    /** Adiciona ou remove. Retorna true se ficou favoritado. */
    static boolean toggleFav(Context c, String title, String url) {
        List<Entry> l = list(c, FAV);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).url.equals(url)) {
                l.remove(i);
                save(c, FAV, l);
                return false;
            }
        }
        Entry e = new Entry();
        e.title = title == null || title.trim().isEmpty() ? url : title.trim();
        e.url = url;
        e.time = System.currentTimeMillis();
        l.add(0, e);
        save(c, FAV, l);
        return true;
    }

    // ------------------------------------------------ histórico

    static void addHistory(Context c, String title, String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        List<Entry> l = list(c, HIST);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).url.equals(url)) {
                l.remove(i);
                break;
            }
        }
        Entry e = new Entry();
        e.title = title == null || title.trim().isEmpty() ? url : title.trim();
        e.url = url;
        e.time = System.currentTimeMillis();
        l.add(0, e);
        while (l.size() > MAX_HIST) l.remove(l.size() - 1);
        save(c, HIST, l);
    }

    static void clearHistory(Context c) {
        sp(c).edit().remove(HIST).apply();
    }
}
