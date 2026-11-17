package com.animedown.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Tela de configurações: downloads, aparência, DLNA e créditos. */
public class SettingsActivity extends Activity {
    LinearLayout box;
    ScrollView scroll;
    View dlnaSection;
    TextView connVal, themeVal, timeVal, lastVal;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dl.load(this);
        Thm.apply(this);
        build();
        if ("dlna".equals(getIntent().getStringExtra("section"))) {
            scroll.post(new Runnable() {
                public void run() { scroll.scrollTo(0, dlnaSection.getTop()); }
            });
        }
    }

    // ------------------------------------------------ montagem da tela

    void build() {
        scroll = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Thm.dp(this, 16);
        box.setPadding(p, p, p, p * 2);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        // cabeçalho
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_anime);
        head.addView(logo, new LinearLayout.LayoutParams(Thm.dp(this, 64), Thm.dp(this, 64)));
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.setPadding(Thm.dp(this, 14), 0, 0, 0);
        ht.addView(text("Configurações", 22, Color_WHITE(), true));
        ht.addView(text("Anime-Down", 13, Thm.accent(this), false));
        head.addView(ht);
        box.addView(head);

        // ---- downloads
        box.addView(section("DOWNLOADS"));
        box.addView(seekRow());
        connVal = valueText();
        box.addView(row("Conexões por download", "Partes baixadas em paralelo em cada arquivo", connVal, new View.OnClickListener() {
            public void onClick(View v) { pickConnections(); }
        }));
        box.addView(switchRow("Download automático (até 30 MB)", "Baixa sozinho vídeos pequenos assim que detectados",
                Store.sp(this).getBoolean("auto", true), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        Store.sp(SettingsActivity.this).edit().putBoolean("auto", on).apply();
                    }
                }));

        // ---- aparência
        box.addView(section("APARÊNCIA"));
        themeVal = valueText();
        box.addView(row("Tema do app", "Cores do navegador, downloads e configurações", themeVal, new View.OnClickListener() {
            public void onClick(View v) { pickTheme(); }
        }));

        // ---- DLNA
        dlnaSection = section("TRANSMITIR PARA TV (DLNA)");
        box.addView(dlnaSection);
        timeVal = valueText();
        box.addView(row("Tempo de busca por TVs", "Quanto tempo procurar aparelhos no Wi-Fi", timeVal, new View.OnClickListener() {
            public void onClick(View v) { pickTime(); }
        }));
        box.addView(switchRow("Lembrar a última TV", "Mostra a última TV usada no topo da lista",
                Store.sp(this).getBoolean("dlna_remember", true), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        Store.sp(SettingsActivity.this).edit().putBoolean("dlna_remember", on).apply();
                        refresh();
                    }
                }));
        box.addView(switchRow("Transmitir direto para a última TV", "Pula a lista quando a última TV for encontrada",
                Store.sp(this).getBoolean("dlna_direct", false), new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton v, boolean on) {
                        Store.sp(SettingsActivity.this).edit().putBoolean("dlna_direct", on).apply();
                    }
                }));
        lastVal = valueText();
        box.addView(row("Última TV usada", "Toque para esquecer", lastVal, new View.OnClickListener() {
            public void onClick(View v) {
                Store.sp(SettingsActivity.this).edit().remove("dlna_last").apply();
                refresh();
                Toast.makeText(SettingsActivity.this, "Última TV esquecida", Toast.LENGTH_SHORT).show();
            }
        }));
        box.addView(button("Procurar TVs agora", new View.OnClickListener() {
            public void onClick(View v) { findTvs(); }
        }));
        TextView tip = text("Dicas: celular e TV no mesmo Wi-Fi, DLNA / compartilhamento de mídia ligado na TV e o app aberto durante a transmissão.",
                12, 0xFF9A9AB0, false);
        tip.setPadding(Thm.dp(this, 4), Thm.dp(this, 8), Thm.dp(this, 4), 0);
        box.addView(tip);

        // ---- sobre
        box.addView(section("SOBRE"));
        LinearLayout about = card();
        about.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout logos = new LinearLayout(this);
        logos.setGravity(Gravity.CENTER);
        ImageView l1 = new ImageView(this);
        l1.setImageResource(R.drawable.wolf_shadow);
        logos.addView(l1, new LinearLayout.LayoutParams(Thm.dp(this, 96), Thm.dp(this, 96)));
        TextView plus = text("  +  ", 22, 0xFF9A9AB0, true);
        logos.addView(plus);
        ImageView l2 = new ImageView(this);
        l2.setImageResource(R.drawable.claude_mark);
        logos.addView(l2, new LinearLayout.LayoutParams(Thm.dp(this, 72), Thm.dp(this, 72)));
        about.addView(logos);
        TextView dev = text("Desenvolvido por Claude e Fabricio R", 15, 0xFFFFFFFF, true);
        dev.setGravity(Gravity.CENTER);
        dev.setPadding(0, Thm.dp(this, 10), 0, 0);
        about.addView(dev);
        box.addView(about);

        refresh();
    }

    void refresh() {
        connVal.setText(Dl.threads() + "");
        themeVal.setText(Thm.NAMES[Thm.index(this)]);
        timeVal.setText(dlnaSeconds(this) + " s");
        String last = Store.sp(this).getString("dlna_last", "");
        lastVal.setText(last.isEmpty() ? "nenhuma" : last);
    }

    // ------------------------------------------------ itens

    View seekRow() {
        LinearLayout c = card();
        final TextView val = valueText();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text("Downloads simultâneos", 15, 0xFFFFFFFF, true));
        t.addView(text("Quantos vídeos baixam ao mesmo tempo (1 a 10). Os outros ficam na fila.", 12, 0xFF9A9AB0, false));
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(val);
        c.addView(top);
        SeekBar sb = new SeekBar(this);
        sb.setMax(9);
        sb.setProgress(Dl.maxActive() - 1);
        val.setText(Dl.maxActive() + "");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { val.setText((p + 1) + ""); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { Dl.setMaxActive(s.getProgress() + 1); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Thm.dp(this, 8);
        c.addView(sb, lp);
        return c;
    }

    View row(String title, String sub, TextView value, View.OnClickListener l) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 15, 0xFFFFFFFF, true));
        t.addView(text(sub, 12, 0xFF9A9AB0, false));
        c.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(value);
        c.setOnClickListener(l);
        return c;
    }

    View switchRow(String title, String sub, boolean on, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout c = card();
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(title, 15, 0xFFFFFFFF, true));
        t.addView(text(sub, 12, 0xFF9A9AB0, false));
        c.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(l);
        c.addView(sw);
        return c;
    }

    View button(String label, View.OnClickListener l) {
        TextView b = text(label, 15, Thm.onAccent(this), true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Thm.round(Thm.accent(this), Thm.dp(this, 14)));
        int p = Thm.dp(this, 14);
        b.setPadding(p, p, p, p);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Thm.dp(this, 8);
        b.setLayoutParams(lp);
        return b;
    }

    LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Thm.round(Thm.surface(this), Thm.dp(this, 14)));
        int p = Thm.dp(this, 14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Thm.dp(this, 8);
        c.setLayoutParams(lp);
        return c;
    }

    View section(String s) {
        TextView t = text(s, 12, Thm.accent(this), true);
        t.setLetterSpacing(0.1f);
        t.setPadding(Thm.dp(this, 4), Thm.dp(this, 22), 0, 0);
        return t;
    }

    TextView valueText() {
        TextView t = text("", 15, Thm.accent(this), true);
        t.setPadding(Thm.dp(this, 12), 0, 0, 0);
        return t;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static int Color_WHITE() { return 0xFFFFFFFF; }

    // ------------------------------------------------ escolhas

    void pickConnections() {
        final int[] opts = {4, 8, 16, 32};
        String[] labels = new String[opts.length];
        int cur = Dl.threads();
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] + " conexões" + (opts[i] == cur ? "  (atual)" : "") + (opts[i] == 8 ? "  •  padrão" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Conexões por download")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Dl.setThreads(opts[w]);
                        refresh();
                        Toast.makeText(SettingsActivity.this, "Vale para os próximos downloads e ao continuar", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    void pickTheme() {
        String[] labels = new String[Thm.NAMES.length];
        int cur = Thm.index(this);
        for (int i = 0; i < labels.length; i++) labels[i] = Thm.NAMES[i] + (i == cur ? "  ✓" : "");
        new AlertDialog.Builder(this)
                .setTitle("Tema do app")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Thm.set(SettingsActivity.this, w);
                        recreate();
                    }
                }).show();
    }

    static int dlnaSeconds(android.content.Context c) {
        return Math.max(2, Math.min(15, Store.sp(c).getInt("dlna_secs", 4)));
    }

    void pickTime() {
        final int[] opts = {3, 4, 6, 8, 10};
        String[] labels = new String[opts.length];
        int cur = dlnaSeconds(this);
        for (int i = 0; i < opts.length; i++) labels[i] = opts[i] + " segundos" + (opts[i] == cur ? "  ✓" : "") + (opts[i] == 4 ? "  •  padrão" : "");
        new AlertDialog.Builder(this)
                .setTitle("Tempo de busca por TVs")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Store.sp(SettingsActivity.this).edit().putInt("dlna_secs", opts[w]).apply();
                        refresh();
                    }
                }).show();
    }

    void findTvs() {
        Toast.makeText(this, "Procurando TVs no Wi-Fi…", Toast.LENGTH_SHORT).show();
        Dlna.discover(this, dlnaSeconds(this) * 1000, new Dlna.Callback() {
            public void onResult(final List<Dlna.Device> devs) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (devs.isEmpty()) {
                            new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("Nenhuma TV encontrada")
                                    .setMessage("Confira se o celular e a TV estão no mesmo Wi-Fi e se o DLNA / compartilhamento de mídia está ligado na TV.\n\nDiagnóstico:\n" + Dlna.lastLog)
                                    .setPositiveButton("OK", null).show();
                            return;
                        }
                        String[] names = new String[devs.size()];
                        for (int i = 0; i < names.length; i++) names[i] = devs.get(i).name;
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle(devs.size() + (devs.size() == 1 ? " TV encontrada" : " TVs encontradas"))
                                .setItems(names, null)
                                .setPositiveButton("OK", null).show();
                    }
                });
            }
        });
    }
}
