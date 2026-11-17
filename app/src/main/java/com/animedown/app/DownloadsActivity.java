package com.animedown.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DownloadsActivity extends Activity {
    ListView list;
    View empty;
    TextView summary;
    BaseAdapter adapter;
    final Runnable refresh = new Runnable() {
        public void run() {
            adapter.notifyDataSetChanged();
            empty.setVisibility(Dl.items.isEmpty() ? View.VISIBLE : View.GONE);
            String s = Dl.summary();
            summary.setText(s == null ? Dl.items.size() + " item(ns)" : s);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dl.load(this);
        setContentView(R.layout.downloads);
        list = (ListView) findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        summary = (TextView) findViewById(R.id.summary);
        adapter = new BaseAdapter() {
            public int getCount() { return Dl.items.size(); }
            public Object getItem(int i) { return Dl.items.get(Dl.items.size() - 1 - i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.row_item, p, false);
                Item it = (Item) getItem(i);
                ((TextView) v.findViewById(R.id.name)).setText(it.name);
                ProgressBar pb = (ProgressBar) v.findViewById(R.id.prog);
                int pm = Fmt.permille(it);
                if ((it.status == Item.RUNNING || it.status == Item.QUEUED) && pm < 0) {
                    pb.setIndeterminate(true);
                } else {
                    pb.setIndeterminate(false);
                    pb.setMax(1000);
                    pb.setProgress(Math.max(0, pm));
                }
                ((TextView) v.findViewById(R.id.info)).setText(info(it));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) { actions((Item) adapter.getItem(pos)); }
        });
        findViewById(R.id.btnClear).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { Dl.clearFinished(); }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { settings(); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        Dl.listeners.add(refresh);
        Thm.apply(this);
        findViewById(R.id.dlHeader).setBackgroundColor(Thm.bg(this));
        summary.setTextColor(Thm.accent(this));
        refresh.run();
    }

    @Override protected void onPause() {
        Dl.listeners.remove(refresh);
        super.onPause();
    }

    static String info(Item it) {
        int pm = Fmt.permille(it);
        String pct = pm >= 0 ? (pm / 10) + "%" : "";
        String sizes = "";
        if (it.total > 0) sizes = Fmt.size(Math.min(it.done, it.total)) + " / " + (it.hls ? "~" : "") + Fmt.size(it.total);
        else if (it.done > 0) sizes = Fmt.size(it.done);
        switch (it.status) {
            case Item.QUEUED:
                return "Na fila";
            case Item.RUNNING: {
                if (!it.msg.isEmpty()) return it.msg;
                StringBuilder s = new StringBuilder();
                join(s, pct);
                join(s, sizes);
                if (it.speed > 0) join(s, Fmt.speed(it.speed));
                if (it.speed > 0 && it.total > it.done) join(s, Fmt.eta((it.total - it.done) / it.speed) + " restante");
                if (it.hls && it.pieceTotal > 0) s.append("\nParte ").append(it.pieceDone).append(" de ").append(it.pieceTotal);
                return s.toString();
            }
            case Item.PAUSED: {
                StringBuilder s = new StringBuilder("Pausado");
                join(s, pct);
                join(s, sizes);
                s.append("\nToque para continuar");
                return s.toString();
            }
            case Item.DONE:
                return "Concluído  •  " + (it.total > 0 ? Fmt.size(it.total) + "  •  " : "") + "Download/Anime-Down";
            case Item.CANCELED:
                return "Cancelado";
            default: {
                String s = "Erro: " + it.msg;
                if (pm > 0) s += "\n" + pct + " já baixado e guardado. Toque para continuar.";
                return s;
            }
        }
    }

    static void join(StringBuilder s, String part) {
        if (part.isEmpty()) return;
        if (s.length() > 0) s.append("  •  ");
        s.append(part);
    }

    void actions(final Item it) {
        final List<String> o = new ArrayList<>();
        if (it.status == Item.DONE) {
            o.add("Abrir");
            o.add("Transmitir para TV (DLNA)");
        }
        if (it.status == Item.RUNNING || it.status == Item.QUEUED) o.add("Pausar");
        if (it.status == Item.PAUSED || it.status == Item.ERROR) {
            o.add("Continuar");
            o.add("Atualizar link (continua de onde parou)");
        }
        if (it.status == Item.CANCELED) o.add("Baixar de novo");
        if (it.status != Item.DONE && it.status != Item.CANCELED) o.add("Cancelar e apagar parcial");
        o.add("Remover da lista");
        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(o.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String a = o.get(w);
                        if (a.equals("Abrir")) open(it);
                        else if (a.startsWith("Transmitir")) cast(it);
                        else if (a.equals("Pausar")) Dl.pause(it);
                        else if (a.equals("Continuar") || a.equals("Baixar de novo")) Dl.start(it);
                        else if (a.startsWith("Atualizar")) updateLink(it);
                        else if (a.startsWith("Cancelar")) Dl.cancel(it);
                        else Dl.remove(it);
                    }
                }).show();
    }

    // ------------------------------------------------ transmitir para TV (DLNA)

    void cast(final Item it) {
        Toast.makeText(this, "Procurando TVs no Wi-Fi…", Toast.LENGTH_SHORT).show();
        Dlna.discover(this, SettingsActivity.dlnaSeconds(this) * 1000, new Dlna.Callback() {
            public void onResult(final List<Dlna.Device> devs) {
                runOnUiThread(new Runnable() {
                    public void run() { pickTv(it, devs); }
                });
            }
        });
    }

    void pickTv(final Item it, final List<Dlna.Device> devs) {
        if (devs.isEmpty()) {
            Toast.makeText(this,
                    "Nenhuma TV encontrada. Confira se o celular e a TV estão no mesmo Wi-Fi e se o DLNA / compartilhamento de mídia está ligado na TV.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final boolean remember = Store.sp(this).getBoolean("dlna_remember", true);
        String last = Store.sp(this).getString("dlna_last", "");
        if (remember && !last.isEmpty()) {
            for (int i = 0; i < devs.size(); i++) {
                if (devs.get(i).name.equals(last)) {
                    Dlna.Device d = devs.remove(i);
                    devs.add(0, d);
                    if (Store.sp(this).getBoolean("dlna_direct", false)) {
                        startCast(it, d);
                        return;
                    }
                    break;
                }
            }
        }
        String[] names = new String[devs.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = (remember && devs.get(i).name.equals(last) ? "★  " : "") + devs.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("Transmitir para…")
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { startCast(it, devs.get(w)); }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    void startCast(final Item it, final Dlna.Device d) {
        Store.sp(this).edit().putString("dlna_last", d.name).apply();
        Toast.makeText(this, "Enviando para " + d.name + "…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    Dlna.cast(getApplicationContext(), d, Uri.parse(it.outUri), it.name);
                    msg = "Tocando em " + d.name + ". Mantenha o app aberto durante a transmissão.";
                } catch (Exception e) {
                    msg = "Não consegui transmitir: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    public void run() { Toast.makeText(DownloadsActivity.this, m, Toast.LENGTH_LONG).show(); }
                });
            }
        }).start();
    }

    void updateLink(final Item it) {
        final List<String> det = new ArrayList<>(Dl.detected);
        Collections.reverse(det);
        if (det.size() > 15) det.subList(15, det.size()).clear();
        final List<String> labels = new ArrayList<>();
        labels.add("✏  Colar um link novo");
        for (String u : det) {
            Uri p = Uri.parse(u);
            String seg = p.getLastPathSegment();
            labels.add((seg == null ? "video" : seg) + "\n" + p.getHost());
        }
        new AlertDialog.Builder(this)
                .setTitle("Novo link para este download")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (w == 0) pasteLink(it); else apply(it, det.get(w - 1));
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    void pasteLink(final Item it) {
        final EditText et = new EditText(this);
        et.setHint("https://...");
        et.setMaxLines(4);
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                if (t != null && t.toString().trim().matches("(?i)^https?://\\S+$")) et.setText(t.toString().trim());
            }
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("Colar link novo")
                .setView(et)
                .setPositiveButton("Continuar", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = et.getText().toString().trim();
                        if (!t.matches("(?i)^https?://\\S+$")) {
                            Toast.makeText(DownloadsActivity.this, "Link inválido", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        apply(it, t);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    void apply(Item it, String url) {
        Dl.updateLink(it, url);
        Toast.makeText(this, "Link atualizado. Continuando de onde parou.", Toast.LENGTH_SHORT).show();
    }

    void settings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    void open(Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(it.outUri), "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Não consegui abrir. Procure em Downloads/Anime-Down", Toast.LENGTH_LONG).show();
        }
    }
}
