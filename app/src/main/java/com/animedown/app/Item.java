package com.animedown.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

final class Item {
    static final int QUEUED = 0, RUNNING = 1, DONE = 2, ERROR = 3, CANCELED = 4, PAUSED = 5;

    String id, url, name, referer = "", ua = "", cookie = "", msg = "", outUri = "", quality = "", ext = "mp4";
    int status, height;
    long done, total, pieceDone, pieceTotal, blockSize;
    boolean hls;

    // 0 = rodando, 1 = pausar, 2 = cancelar (apaga os dados parciais)
    volatile int stop;
    long speed;
    long lastUi;
    final AtomicBoolean running = new AtomicBoolean(false);

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("url", url); o.put("name", name);
        o.put("referer", referer); o.put("ua", ua); o.put("cookie", cookie);
        o.put("msg", msg); o.put("outUri", outUri); o.put("quality", quality); o.put("ext", ext);
        o.put("status", status); o.put("height", height);
        o.put("done", done); o.put("total", total);
        o.put("pieceDone", pieceDone); o.put("pieceTotal", pieceTotal); o.put("blockSize", blockSize);
        o.put("hls", hls);
        return o;
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.id = o.optString("id"); i.url = o.optString("url"); i.name = o.optString("name");
        i.referer = o.optString("referer"); i.ua = o.optString("ua"); i.cookie = o.optString("cookie");
        i.msg = o.optString("msg"); i.outUri = o.optString("outUri");
        i.quality = o.optString("quality"); i.ext = o.optString("ext", "mp4");
        i.status = o.optInt("status"); i.height = o.optInt("height");
        i.done = o.optLong("done"); i.total = o.optLong("total");
        i.pieceDone = o.optLong("pieceDone"); i.pieceTotal = o.optLong("pieceTotal");
        i.blockSize = o.optLong("blockSize");
        i.hls = o.optBoolean("hls");
        return i;
    }
}
