package com.animedown.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final String HOME = "file:///android_asset/home.html";

    WebView web;
    EditText urlBar;
    ProgressBar bar;
    android.widget.Button btnFound;
    FrameLayout root;
    View content, activeBox;
    TextView activeTxt;
    ProgressBar activeProg;
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    WebChromeClient chrome;
    final LinkedHashSet<String> vids = new LinkedHashSet<>();
    final Runnable activeListener = new Runnable() {
        public void run() { updateActive(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dl.load(this);
        setContentView(R.layout.main);
        root = (FrameLayout) findViewById(R.id.root);
        content = findViewById(R.id.content);
        urlBar = (EditText) findViewById(R.id.url);
        bar = (ProgressBar) findViewById(R.id.progress);
        btnFound = (android.widget.Button) findViewById(R.id.btnFound);
        web = (WebView) findViewById(R.id.web);
        activeBox = findViewById(R.id.activeBox);
        activeTxt = (TextView) findViewById(R.id.activeTxt);
        activeProg = (ProgressBar) findViewById(R.id.activeProg);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (AdBlock.isAd(u)) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                if (isMedia(r.getUrl())) {
                    final String uu = u;
                    runOnUiThread(new Runnable() { public void run() { addFound(uu); } });
                }
                return null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                String sch = r.getUrl().getScheme();
                if (AdBlock.isAd(u)) return true;
                return sch != null && !sch.equals("http") && !sch.equals("https") && !sch.equals("file");
            }

            @Override public void onPageStarted(WebView v, String u, Bitmap icon) {
                vids.clear();
                updateFound();
                autoDone = false;
                scanHandler.removeCallbacks(autoTask);
                if (u != null && !u.startsWith("file:")) urlBar.setText(u); else urlBar.setText("");
            }

            @Override public void onPageFinished(WebView v, String u) {
                Store.addHistory(MainActivity.this, v.getTitle(), u);
            }

            @Override public void doUpdateVisitedHistory(WebView v, String u, boolean isReload) {
                saveLast(u);
            }
        });

        chrome = new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v;
                customCb = cb;
                root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                content.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }

            @Override public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                content.setVisibility(View.VISIBLE);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden();
            }
        };
        web.setWebChromeClient(chrome);

        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(final String url, String ua, String cd, String mime, final long len) {
                final String name = URLUtil.guessFileName(url, cd, mime);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Baixar arquivo?")
                        .setMessage(name + (len > 0 ? "\n" + Fmt.size(len) : ""))
                        .setPositiveButton("Baixar", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                addItem(url, name, 0, "", len);
                            }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            }
        });

        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (id == EditorInfo.IME_ACTION_GO || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    go(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.btnGo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { go(urlBar.getText().toString()); }
        });
        findViewById(R.id.btnDirect).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String t = urlBar.getText().toString().trim();
                boolean http = t.matches("(?i)^https?://\\S+$");
                boolean isPage = !http || (t.equals(web.getUrl()) && !isMedia(Uri.parse(t)));
                if (isPage) {
                    if (!vids.isEmpty()) {
                        btnFound.performClick();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Nenhum vídeo detectado ainda. Dê play no vídeo da página, espere carregar e toque aqui de novo. Ou cole o link direto do vídeo na barra.",
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                String n = Uri.parse(t).getLastPathSegment();
                List<String> one = new ArrayList<>();
                one.add(t);
                showQuality(one, n == null ? "video" : n);
            }
        });
        findViewById(R.id.btnList).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDownloads(); }
        });
        findViewById(R.id.btnMenu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(); }
        });
        activeBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDownloads(); }
        });
        btnFound.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<String> l = new ArrayList<>(vids);
                if (l.size() > 15) l = new ArrayList<>(l.subList(0, 15));
                showQuality(l, null);
            }
        });

        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 1);

        if (b == null) {
            if (!handleIntent(getIntent())) loadLastOrHome();
        } else if (web.restoreState(b) == null) {
            loadLastOrHome();
        }
    }

    /** Abre a última página visitada; se não houver, abre a tela inicial. */
    void loadLastOrHome() {
        String last = Store.last(this);
        if (last != null && last.startsWith("http")) web.loadUrl(last);
        else web.loadUrl(HOME);
    }

    /** Guarda a página atual para reabrir nela da próxima vez. */
    void saveLast(String u) {
        if (u == null) return;
        if (u.startsWith("http://") || u.startsWith("https://")) Store.setLast(this, u);
        else if (u.startsWith("file:")) Store.setLast(this, "");
    }

    @Override protected void onResume() {
        super.onResume();
        Dl.listeners.add(activeListener);
        updateActive();
        scanHandler.removeCallbacks(scanTask);
        scanHandler.postDelayed(scanTask, 1500);
    }

    @Override protected void onPause() {
        Dl.listeners.remove(activeListener);
        scanHandler.removeCallbacks(scanTask);
        saveLast(web.getUrl());
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        web.saveState(o);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    void openDownloads() { startActivity(new Intent(this, DownloadsActivity.class)); }

    void updateActive() {
        String s = Dl.summary();
        if (s == null) {
            activeBox.setVisibility(View.GONE);
            return;
        }
        activeBox.setVisibility(View.VISIBLE);
        activeTxt.setText(s + "   ›");
        int pm = Dl.overallPermille();
        if (pm < 0) {
            activeProg.setIndeterminate(true);
        } else {
            activeProg.setIndeterminate(false);
            activeProg.setMax(1000);
            activeProg.setProgress(pm);
        }
    }

    boolean handleIntent(Intent i) {
        if (i == null) return false;
        String t = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) t = i.getStringExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) t = i.getData().toString();
        if (t == null) return false;
        Matcher m = Pattern.compile("https?://\\S+").matcher(t);
        if (!m.find()) return false;
        go(m.group());
        return true;
    }

    void go(String in) {
        in = in.trim();
        if (in.isEmpty()) return;
        String u;
        if (in.matches("(?i)^https?://\\S+$")) u = in;
        else if (!in.contains(" ") && in.contains(".")) u = "https://" + in;
        else u = "https://www.google.com/search?q=" + Uri.encode(in);
        web.loadUrl(u);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        web.requestFocus();
    }

    // ------------------------------------------------ favoritos e histórico

    // ------------------------------------------------ download automático (até 30 MB)

    static final long AUTO_MAX = 30L * 1024 * 1024;
    boolean autoDone;
    final Runnable autoTask = new Runnable() {
        public void run() { autoProbe(); }
    };

    boolean autoOn() { return Store.sp(this).getBoolean("auto", true); }

    void autoProbe() {
        if (!autoOn() || autoDone || vids.isEmpty()) return;
        List<String> urls = new ArrayList<>(vids);
        if (urls.size() > 15) urls = new ArrayList<>(urls.subList(0, 15));
        final Map<String, List<Probe.Opt>> results = new LinkedHashMap<>();
        final int[] pending = {urls.size()};
        final String referer = web.getUrl();
        final String ua = web.getSettings().getUserAgentString();
        for (final String u : urls) {
            final String cookie = CookieManager.getInstance().getCookie(u);
            Probe.pool.execute(new Runnable() {
                public void run() {
                    List<Probe.Opt> r;
                    try {
                        r = Probe.run(new Net(ua, referer, cookie, u, null), u);
                    } catch (Throwable e) {
                        r = new ArrayList<>();
                    }
                    final List<Probe.Opt> rr = r;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            results.put(u, rr);
                            pending[0]--;
                            if (pending[0] == 0) autoPick(results);
                        }
                    });
                }
            });
        }
    }

    /** Pega a melhor qualidade que tenha tamanho conhecido de até 30 MB. */
    void autoPick(Map<String, List<Probe.Opt>> results) {
        if (autoDone || !autoOn()) return;
        List<Probe.Opt> shown = new ArrayList<>();
        rebuildQuality(shown, results, null);
        for (Probe.Opt o : shown) {
            if (o.size > 0 && o.size <= AUTO_MAX) {
                autoDone = true;
                queueOpt(o, null);
                return;
            }
        }
    }

    void showMenu() {
        final String u = web.getUrl();
        final boolean page = u != null && (u.startsWith("http://") || u.startsWith("https://"));
        final boolean fav = page && Store.isFav(this, u);
        List<String> items = new ArrayList<>();
        if (page) items.add(fav ? "★  Remover dos favoritos" : "☆  Adicionar aos favoritos");
        items.add("Favoritos");
        items.add("Histórico");
        items.add("Download automático (até 30 MB): " + (autoOn() ? "ligado ✓" : "desligado"));
        final int off = page ? 1 : 0;
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (page && which == 0) {
                            boolean now = Store.toggleFav(MainActivity.this, web.getTitle(), u);
                            Toast.makeText(MainActivity.this,
                                    now ? "Adicionado aos favoritos" : "Removido dos favoritos",
                                    Toast.LENGTH_SHORT).show();
                        } else if (which - off == 0) {
                            showList(true);
                        } else if (which - off == 1) {
                            showList(false);
                        } else {
                            boolean on = !autoOn();
                            Store.sp(MainActivity.this).edit().putBoolean("auto", on).apply();
                            Toast.makeText(MainActivity.this,
                                    on ? "Download automático ligado (vídeos de até 30 MB)" : "Download automático desligado",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    void showList(final boolean fav) {
        final String key = fav ? Store.FAV : Store.HIST;
        final List<Store.Entry> data = Store.list(this, key);
        if (data.isEmpty()) {
            Toast.makeText(this, fav ? "Nenhum favorito ainda" : "Histórico vazio", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayAdapter<Store.Entry> ad = new ArrayAdapter<Store.Entry>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, data) {
            @Override public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(getContext()).inflate(android.R.layout.simple_list_item_2, p, false);
                Store.Entry e = getItem(i);
                TextView t1 = (TextView) v.findViewById(android.R.id.text1);
                TextView t2 = (TextView) v.findViewById(android.R.id.text2);
                t1.setText(e.title == null || e.title.isEmpty() ? e.url : e.title);
                t1.setSingleLine(true);
                t2.setText(e.url);
                t2.setSingleLine(true);
                return v;
            }
        };
        AlertDialog.Builder bld = new AlertDialog.Builder(this)
                .setTitle(fav ? "Favoritos (segure para remover)" : "Histórico (segure para remover)")
                .setAdapter(ad, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        web.loadUrl(data.get(which).url);
                    }
                })
                .setNegativeButton("Fechar", null);
        if (!fav) {
            bld.setNeutralButton("Limpar tudo", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    Store.clearHistory(MainActivity.this);
                    Toast.makeText(MainActivity.this, "Histórico apagado", Toast.LENGTH_SHORT).show();
                }
            });
        }
        final AlertDialog dlg = bld.create();
        dlg.show();
        dlg.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int pos, long id) {
                data.remove(pos);
                Store.save(MainActivity.this, key, data);
                ad.notifyDataSetChanged();
                if (data.isEmpty()) dlg.dismiss();
                return true;
            }
        });
    }

    static final Pattern MEDIA_EXT = Pattern.compile("(?i)\\.(mp4|m3u8|mkv|webm|mov|m4v)(?=$|[?#&/])");
    static final Pattern RANGE_PARAM = Pattern.compile("([?&])(range|rn|rbuf|alr)=[^&]*");

    static boolean isMedia(Uri u) {
        String s = u.toString();
        if (s.startsWith("blob:") || s.startsWith("data:")) return false;
        String q = u.getQuery() == null ? "" : u.getQuery().toLowerCase();
        String p = u.getPath() == null ? "" : u.getPath().toLowerCase();
        if (p.contains("videoplayback")) return !q.contains("mime=audio") && !q.contains("mime%3daudio");
        if (q.contains("mime=video") || q.contains("mime%3dvideo")) return true;
        return MEDIA_EXT.matcher(s).find();
    }

    /** Tira parâmetros de trecho (range=...) para o mesmo vídeo não aparecer várias vezes. */
    static String normalize(String u) {
        if (u.contains("videoplayback")) {
            u = RANGE_PARAM.matcher(u).replaceAll("$1");
            u = u.replace("?&", "?").replaceAll("&&+", "&").replaceAll("[?&]$", "");
        }
        return u;
    }

    // varre <video>/<source> da página (e iframes do mesmo site) em busca de links
    static final String SCAN = "(function(){var r=[];function add(u){if(u&&/^https?:/i.test(u))r.push(u);}"
            + "function sc(d){try{var v=d.querySelectorAll('video,video source');"
            + "for(var i=0;i<v.length;i++){add(v[i].currentSrc);add(v[i].src);}"
            + "var f=d.querySelectorAll('iframe');"
            + "for(var j=0;j<f.length;j++){try{if(f[j].contentDocument)sc(f[j].contentDocument);}catch(e){}}"
            + "}catch(e){}}sc(document);return JSON.stringify(r);})()";

    final android.os.Handler scanHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    final Runnable scanTask = new Runnable() {
        public void run() {
            String cur = web.getUrl();
            if (cur != null && cur.startsWith("http")) {
                web.evaluateJavascript(SCAN, new android.webkit.ValueCallback<String>() {
                    public void onReceiveValue(String val) {
                        try {
                            if (val == null || val.equals("null")) return;
                            Object o = new org.json.JSONTokener(val).nextValue();
                            if (!(o instanceof String)) return;
                            org.json.JSONArray a = new org.json.JSONArray((String) o);
                            for (int i = 0; i < a.length(); i++) addFound(a.getString(i));
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
            scanHandler.postDelayed(this, 2500);
        }
    };

    void addFound(String u) {
        u = normalize(u);
        if (AdBlock.isAd(u)) return;
        if (vids.add(u)) {
            updateFound();
            if (autoOn() && !autoDone) {
                scanHandler.removeCallbacks(autoTask);
                scanHandler.postDelayed(autoTask, 3000);
            }
        }
        Dl.detected.remove(u);
        Dl.detected.add(u);
        while (Dl.detected.size() > 40) Dl.detected.remove(Dl.detected.iterator().next());
    }

    void updateFound() {
        if (vids.isEmpty()) {
            btnFound.setVisibility(View.GONE);
        } else {
            btnFound.setText("⬇  Baixar vídeo (" + vids.size() + " detectado" + (vids.size() > 1 ? "s" : "") + ")");
            btnFound.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------ qualidades e tamanhos

    void showQuality(final List<String> urls, final String nameOverride) {
        final List<Probe.Opt> shown = new ArrayList<>();
        final Map<String, List<Probe.Opt>> results = new LinkedHashMap<>();
        final int[] pending = {urls.size()};
        final ArrayAdapter<Probe.Opt> ad = new ArrayAdapter<Probe.Opt>(this, R.layout.row_quality, R.id.q_title, shown) {
            @Override public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(getContext()).inflate(R.layout.row_quality, p, false);
                Probe.Opt o = getItem(i);
                ((TextView) v.findViewById(R.id.q_title)).setText(o.label);
                ((TextView) v.findViewById(R.id.q_sub)).setText(o.sub());
                return v;
            }
        };
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Analisando…")
                .setAdapter(ad, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        queueOpt(shown.get(which), nameOverride);
                    }
                })
                .setNegativeButton("Fechar", null)
                .create();
        dlg.show();

        final String referer = web.getUrl();
        final String ua = web.getSettings().getUserAgentString();
        for (final String u : urls) {
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
                    runOnUiThread(new Runnable() {
                        public void run() {
                            results.put(u, rr);
                            pending[0]--;
                            rebuildQuality(shown, results, ad);
                            if (pending[0] > 0) dlg.setTitle("Analisando… (" + pending[0] + ")");
                            else dlg.setTitle(shown.isEmpty() ? "Nenhuma opção encontrada" : "Escolha a qualidade");
                        }
                    });
                }
            });
        }
    }

    static void rebuildQuality(List<Probe.Opt> shown, Map<String, List<Probe.Opt>> results, ArrayAdapter<Probe.Opt> ad) {
        Set<String> variantUrls = new HashSet<>();
        for (List<Probe.Opt> l : results.values()) for (Probe.Opt o : l) if (o.variant) variantUrls.add(o.url);
        LinkedHashMap<String, Probe.Opt> uniq = new LinkedHashMap<>();
        for (List<Probe.Opt> l : results.values()) {
            for (Probe.Opt o : l) {
                if (!o.variant && o.hls && variantUrls.contains(o.url)) continue;
                if (!o.hls && o.size > 0 && o.size < 200 * 1024) continue;
                if (!uniq.containsKey(o.url)) uniq.put(o.url, o);
            }
        }
        shown.clear();
        shown.addAll(uniq.values());
        Collections.sort(shown, new Comparator<Probe.Opt>() {
            public int compare(Probe.Opt a, Probe.Opt b) {
                if (a.height != b.height) return b.height - a.height;
                return Long.compare(b.size, a.size);
            }
        });
        if (ad != null) ad.notifyDataSetChanged();
    }

    void queueOpt(Probe.Opt o, String nameOverride) {
        String base = nameOverride;
        if (base == null || base.trim().isEmpty()) {
            String title = web.getTitle();
            if (title == null || title.trim().isEmpty() || title.startsWith("file:") || title.equals("Anime-Down")) {
                String seg = Uri.parse(o.url).getLastPathSegment();
                title = seg == null ? "video" : seg;
            }
            base = title;
        }
        if (!"Original".equals(o.label)) base += " [" + o.label + "]";
        addItem(o.url, base, o.height, "Original".equals(o.label) ? "" : o.label, o.size);
    }

    void addItem(String url, String name, int height, String quality, long size) {
        String referer = web.getUrl();
        if (referer != null && referer.startsWith("file:")) referer = null;
        Dl.add(this, url, name, referer, web.getSettings().getUserAgentString(),
                CookieManager.getInstance().getCookie(url), height, quality, size);
        Toast.makeText(this, "Download iniciado. Acompanhe na barra abaixo ou no botão ☰", Toast.LENGTH_SHORT).show();
    }

    @Override public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
