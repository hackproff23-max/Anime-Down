package com.animedown.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Abre o site saimo-tv dentro do app, como se fosse um aplicativo próprio. */
public class TvActivity extends Activity {
    static final String URL = "https://saimo-tv.pages.dev/#tudo";

    WebView web;
    ProgressBar bar;
    FrameLayout root;
    View content;
    View customView;
    WebChromeClient.CustomViewCallback customCb;

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    TextView barButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFE6E6F2);
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0E14);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        content = col;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0xFF0E0E14);

        TextView back = barButton("\u2190");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onBackPressed(); }
        });
        top.addView(back);

        TextView title = new TextView(this);
        title.setText("TV");
        title.setTextColor(0xFFB388FF);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reload = barButton("\u21BB");
        reload.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { web.reload(); }
        });
        top.addView(reload);

        TextView close = barButton("\u2715");
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        top.addView(close);

        col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.INVISIBLE);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sch = r.getUrl().getScheme();
                return sch != null && !sch.equals("http") && !sch.equals("https");
            }

            @Override public void onPageStarted(WebView v, String u, Bitmap icon) {
                bar.setVisibility(View.VISIBLE);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
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
        });

        if (b == null) web.loadUrl(URL);
        else if (web.restoreState(b) == null) web.loadUrl(URL);
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        web.saveState(o);
    }

    @Override protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override protected void onDestroy() {
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
