package com.animedown.app;

import org.json.JSONException;
import org.json.JSONObject;

final class Item {
    static final int QUEUED = 0, RUNNING = 1, DONE = 2, ERROR = 3, CANCELED = 4;

    String id, url, name, referer = "", ua = "", cookie = "", msg = "", outUri = "";
    int status;
    long done, total;
    boolean hls;
    volatile boolean cancel;
    long lastUi;

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("url", url); o.put("name", name);
        o.put("referer", referer); o.put("ua", ua); o.put("cookie", cookie);
        o.put("msg", msg); o.put("outUri", outUri);
        o.put("status", status); o.put("done", done); o.put("total", total); o.put("hls", hls);
        return o;
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.id = o.optString("id"); i.url = o.optString("url"); i.name = o.optString("name");
        i.referer = o.optString("referer"); i.ua = o.optString("ua"); i.cookie = o.optString("cookie");
        i.msg = o.optString("msg"); i.outUri = o.optString("outUri");
        i.status = o.optInt("status"); i.done = o.optLong("done"); i.total = o.optLong("total");
        i.hls = o.optBoolean("hls");
        return i;
    }
}
