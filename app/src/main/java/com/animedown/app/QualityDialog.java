package com.animedown.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Lista os vídeos detectados com o nome; ao tocar em um, as qualidades aparecem logo abaixo dele. */
final class QualityDialog {
    static class Group {
        String url, name;
        boolean done;
        List<Probe.Opt> opts = new ArrayList<>();
    }

    static final Pattern GENERIC = Pattern.compile(
            "(?i)^(master|playlist|index|main|video|videoplayback|chunklist|manifest|stream|media|[0-9]+|[0-9a-f-]{16,})$");

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static String pageTitle(MainActivity a) {
        String t = a.web.getTitle();
        if (t == null || t.trim().isEmpty() || t.startsWith("file:") || t.equals("Anime-Down")) return null;
        return t.trim();
    }

    /** Nome legível a partir do final do link; devolve null se for um nome genérico (master, index…). */
    static String nameFromUrl(String url) {
        try {
            String seg = Uri.parse(url).getLastPathSegment();
            if (seg == null) return null;
            seg = Uri.decode(seg);
            seg = seg.replaceAll("(?i)\\.(mp4|m3u8|mkv|webm|mov|m4v|ts)$", "");
            if (GENERIC.matcher(seg).matches()) return null;
            String clean = seg.replaceAll("[_.+]+", " ");
            clean = clean.replaceAll("(?i)[ -]*\\b(144|240|360|480|540|720|1080|1440|2160)p\\b", "").trim();
            return clean.length() < 3 ? null : clean;
        } catch (Exception e) {
            return null;
        }
    }

    static View row(Context c, String title, String sub, int subColor, int leftDp, int bg) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 14);
        l.setPadding(dp(c, leftDp), p, p, p);
        if (bg != 0) l.setBackgroundColor(bg);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        l.addView(t);
        TextView s = new TextView(c);
        s.setText(sub);
        s.setTextColor(subColor);
        s.setTextSize(13);
        s.setPadding(0, dp(c, 2), 0, 0);
        l.addView(s);
        return l;
    }

    static void show(final MainActivity a, final List<String> urls, final String nameOverride) {
        final List<Group> groups = new ArrayList<>();
        String title = pageTitle(a);
        for (int i = 0; i < urls.size(); i++) {
            Group g = new Group();
            g.url = urls.get(i);
            String n;
            if (urls.size() == 1 && nameOverride != null && !nameOverride.trim().isEmpty()) n = nameOverride.trim();
            else n = nameFromUrl(g.url);
            if (n == null) {
                n = title != null ? title : "V\u00eddeo";
                if (urls.size() > 1) n += " #" + (i + 1);
            }
            g.name = n;
            groups.add(g);
        }

        final ExpandableListView lv = new ExpandableListView(a);
        lv.setGroupIndicator(null);

        final BaseExpandableListAdapter ad = new BaseExpandableListAdapter() {
            public int getGroupCount() { return groups.size(); }
            public int getChildrenCount(int g) { return groups.get(g).opts.size(); }
            public Object getGroup(int g) { return groups.get(g); }
            public Object getChild(int g, int c) { return groups.get(g).opts.get(c); }
            public long getGroupId(int g) { return g; }
            public long getChildId(int g, int c) { return c; }
            public boolean hasStableIds() { return false; }
            public boolean isChildSelectable(int g, int c) { return true; }

            public View getGroupView(int gp, boolean exp, View v, ViewGroup p) {
                Group g = groups.get(gp);
                boolean multi = g.opts.size() > 1;
                String t = (multi ? (exp ? "\u25BE  " : "\u25B8  ") : "") + g.name;
                String sub;
                int color = 0xFFB388FF;
                if (!g.done) {
                    sub = "analisando\u2026";
                    color = 0xFF9A9AB0;
                } else if (g.opts.isEmpty()) {
                    sub = "nenhuma op\u00e7\u00e3o encontrada";
                    color = 0xFF9A9AB0;
                } else if (!multi) {
                    Probe.Opt o = g.opts.get(0);
                    sub = o.label + "  \u2022  " + o.sub();
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < g.opts.size() && i < 5; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(g.opts.get(i).label);
                    }
                    sub = g.opts.size() + " qualidades: " + sb;
                }
                return row(a, t, sub, color, 16, 0);
            }

            public View getChildView(int gp, int cp, boolean last, View v, ViewGroup p) {
                Probe.Opt o = groups.get(gp).opts.get(cp);
                return row(a, o.label, o.sub(), 0xFFB388FF, 40, 0x1AFFFFFF);
            }
        };
        lv.setAdapter(ad);

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Analisando\u2026")
                .setView(lv)
                .setNegativeButton("Fechar", null)
                .create();

        lv.setOnGroupClickListener(new ExpandableListView.OnGroupClickListener() {
            public boolean onGroupClick(ExpandableListView l, View v, int gp, long id) {
                Group g = groups.get(gp);
                if (g.opts.size() == 1) {
                    a.queueOpt(g.opts.get(0), g.name);
                    dlg.dismiss();
                    return true;
                }
                return g.opts.isEmpty();
            }
        });
        lv.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            public boolean onChildClick(ExpandableListView l, View v, int gp, int cp, long id) {
                Group g = groups.get(gp);
                a.queueOpt(g.opts.get(cp), g.name);
                dlg.dismiss();
                return true;
            }
        });
        dlg.show();

        final int[] pending = {groups.size()};
        final String referer = a.web.getUrl();
        final String ua = a.web.getSettings().getUserAgentString();
        for (final Group g : groups) {
            final String u = g.url;
            final String cookie = CookieManager.getInstance().getCookie(u);
            Probe.pool.execute(new Runnable() {
                public void run() {
                    List<Probe.Opt> r;
                    try {
                        r = Probe.run(new Net(ua, referer, cookie, u, null), u);
                    } catch (Throwable e) {
                        r = new ArrayList<>();
                        Probe.Opt o = new Probe.Opt();
                        o.url = u;
                        o.hls = Downloader.isHls(u);
                        r.add(o);
                    }
                    final List<Probe.Opt> rr = r;
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            Map<String, List<Probe.Opt>> one = new LinkedHashMap<>();
                            one.put(u, rr);
                            List<Probe.Opt> shown = new ArrayList<>();
                            MainActivity.rebuildQuality(shown, one, null);
                            g.opts = shown;
                            g.done = true;
                            pending[0]--;
                            ad.notifyDataSetChanged();
                            if (groups.size() == 1 && shown.size() > 1) lv.expandGroup(0);
                            if (pending[0] > 0) {
                                dlg.setTitle("Analisando\u2026 (" + pending[0] + ")");
                            } else {
                                boolean any = false;
                                for (Group x : groups) if (!x.opts.isEmpty()) any = true;
                                dlg.setTitle(!any ? "Nenhuma op\u00e7\u00e3o encontrada"
                                        : groups.size() > 1 ? "Escolha o v\u00eddeo" : "Escolha a qualidade");
                            }
                        }
                    });
                }
            });
        }
    }
}
