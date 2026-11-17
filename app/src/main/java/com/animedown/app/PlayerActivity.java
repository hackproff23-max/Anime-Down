package com.animedown.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.Map;

/** Player nativo (ExoPlayer): buffer grande, HLS com AES-128, Referer/Cookie do site, sem travar como no WebView. */
public class PlayerActivity extends Activity {
    PlayerView pv;
    ExoPlayer player;
    View overlay;
    TextView errTxt;
    String url, name, referer, ua, cookie, quality;
    boolean hls;
    int height;
    long size, pos;
    boolean playWhenReady = true;

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    TextView btn(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        return t;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        url = i.getStringExtra("url");
        if (url == null) { finish(); return; }
        name = i.getStringExtra("name");
        referer = i.getStringExtra("referer");
        ua = i.getStringExtra("ua");
        cookie = i.getStringExtra("cookie");
        quality = i.getStringExtra("quality");
        hls = i.getBooleanExtra("hls", false);
        height = i.getIntExtra("height", 0);
        size = i.getLongExtra("size", -1);
        if (b != null) {
            pos = b.getLong("pos");
            playWhenReady = b.getBoolean("play", true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        pv = new PlayerView(this);
        pv.setUseController(true);
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        pv.setControllerShowTimeoutMs(3500);
        root.addView(pv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(0x99000000);
        overlay = top;

        TextView back = btn("\u2190");
        back.setTextSize(22);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        TextView title = new TextView(this);
        title.setText(name == null ? "V\u00eddeo" : name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView dl = btn("\u2B07  Baixar");
        dl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { download(); }
        });
        top.addView(dl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        errTxt = new TextView(this);
        errTxt.setTextColor(0xFFFFFFFF);
        errTxt.setTextSize(15);
        errTxt.setGravity(Gravity.CENTER);
        errTxt.setBackgroundColor(0xCC000000);
        errTxt.setPadding(dp(20), dp(14), dp(20), dp(14));
        errTxt.setVisibility(View.GONE);
        errTxt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                errTxt.setVisibility(View.GONE);
                if (player != null) {
                    player.prepare();
                    player.play();
                }
            }
        });
        root.addView(errTxt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        pv.setControllerVisibilityListener(new PlayerView.ControllerVisibilityListener() {
            @Override public void onVisibilityChanged(int visibility) { overlay.setVisibility(visibility); }
        });

        setContentView(root);
    }

    void initPlayer() {
        if (player != null || url == null) return;

        Map<String, String> h = new HashMap<>();
        if (referer != null && !referer.isEmpty()) h.put("Referer", referer);
        if (cookie != null && !cookie.isEmpty()) h.put("Cookie", cookie);

        DefaultHttpDataSource.Factory df = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua != null && !ua.isEmpty() ? ua : "Mozilla/5.0")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(h);

        // buffer de at\u00e9 40 s \u00e0 frente (limitado a 40 MB para n\u00e3o estourar a mem\u00f3ria de celular fraco)
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 40000, 2000, 5000)
                .setTargetBufferBytes(40 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();

        DefaultRenderersFactory rf = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, rf)
                .setLoadControl(lc)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(df))
                .build();

        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                errTxt.setText("N\u00e3o foi poss\u00edvel reproduzir (" + e.getErrorCodeName() + ").\nToque aqui para tentar de novo.");
                errTxt.setVisibility(View.VISIBLE);
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) errTxt.setVisibility(View.GONE);
            }
        });

        pv.setPlayer(player);

        MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
        if (hls) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(mb.build(), pos);
        player.setPlayWhenReady(playWhenReady);
        player.prepare();
    }

    void releasePlayer() {
        if (player == null) return;
        pos = player.getCurrentPosition();
        playWhenReady = player.getPlayWhenReady();
        pv.setPlayer(null);
        player.release();
        player = null;
    }

    void download() {
        String base = name == null || name.trim().isEmpty() ? "video" : name;
        String q = quality == null ? "" : quality;
        if (!q.isEmpty()) base += " [" + q + "]";
        Dl.add(this, url, base, referer, ua, cookie, height, q, size);
        Toast.makeText(this, "Download iniciado. Acompanhe na tela principal ou no bot\u00e3o \u2630", Toast.LENGTH_LONG).show();
    }

    @Override protected void onStart() {
        super.onStart();
        initPlayer();
    }

    @Override protected void onStop() {
        releasePlayer();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        if (player != null) {
            pos = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
        }
        o.putLong("pos", pos);
        o.putBoolean("play", playWhenReady);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
