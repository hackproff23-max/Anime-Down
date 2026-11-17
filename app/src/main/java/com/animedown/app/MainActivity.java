package com.animedown.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    WebView web;
    EditText urlBar;
    ProgressBar bar;
    Button btnFound;
    FrameLayout root;
    View content;
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    WebChromeClient chrome;
    final LinkedHashSet<String> vids = new LinkedHashSet<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dl.load(this);
        setContentView(R.layout.main);
        root = (FrameLayout) findViewById(R.id.root);
        content = findViewById(R.id.content);
        urlBar = (EditText) findViewById(R.id.url);
        bar = (ProgressBar) findViewById(R.id.progress);
        btnFound = (Button) findViewById(R.id.btnFound);
        web = (WebView) findViewById(R.id.web);

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
                if (u != null && !u.startsWith("file:")) urlBar.setText(u); else urlBar.setText("");
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
            @Override public void onDownloadStart(final String url, String ua, String cd, String mime, long len) {
                final String name = android.webkit.URLUtil.guessFileName(url, cd, mime);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Baixar arquivo?")
                        .setMessage(name)
                        .setPositiveButton("Baixar", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) { queue(url, name, web.getUrl()); }
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
                if (!t.matches("(?i)^https?://\\S+$")) {
                    Toast.makeText(MainActivity.this, "Cole o link direto do vídeo (http/https) na barra", Toast.LENGTH_LONG).show();
                    return;
                }
                String n = Uri.parse(t).getLastPathSegment();
                queue(t, n == null ? "video" : n, null);
            }
        });
        findViewById(R.id.btnList).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, DownloadsActivity.class)); }
        });
        btnFound.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showFound(); }
        });

        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 1);

        if (b == null && !handleIntent(getIntent())) web.loadUrl("file:///android_asset/home.html");
        else if (b != null) web.restoreState(b);
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        web.saveState(o);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
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
        else u = "https://duckduckgo.com/?q=" + Uri.encode(in);
        web.loadUrl(u);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        web.requestFocus();
    }

    static boolean isMedia(Uri u) {
        String p = u.getPath();
        if (p == null) return false;
        p = p.toLowerCase();
        return p.endsWith(".mp4") || p.endsWith(".m3u8") || p.endsWith(".mkv") || p.endsWith(".webm")
                || p.endsWith(".mov") || p.endsWith(".m4v");
    }

    void addFound(String u) {
        if (vids.add(u)) updateFound();
    }

    void updateFound() {
        if (vids.isEmpty()) {
            btnFound.setVisibility(View.GONE);
        } else {
            btnFound.setText("⬇  Vídeos detectados (" + vids.size() + ")");
            btnFound.setVisibility(View.VISIBLE);
        }
    }

    void showFound() {
        final List<String> list = new ArrayList<>(vids);
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            Uri u = Uri.parse(list.get(i));
            String seg = u.getLastPathSegment();
            labels[i] = (seg == null ? "video" : seg) + "\n" + u.getHost();
        }
        new AlertDialog.Builder(this)
                .setTitle("Escolha o vídeo")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        String u = list.get(which);
                        String title = web.getTitle();
                        String name = (title == null || title.trim().isEmpty() || title.startsWith("file:"))
                                ? String.valueOf(Uri.parse(u).getLastPathSegment()) : title;
                        queue(u, name, web.getUrl());
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    void queue(String url, String name, String referer) {
        String cookie = CookieManager.getInstance().getCookie(url);
        Dl.add(this, url, name, referer, web.getSettings().getUserAgentString(), cookie);
        Toast.makeText(this, "Download iniciado. Veja no botão ☰", Toast.LENGTH_SHORT).show();
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
