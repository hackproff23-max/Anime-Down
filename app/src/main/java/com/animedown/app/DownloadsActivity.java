package com.animedown.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DownloadsActivity extends Activity {
    ListView list;
    View empty;
    BaseAdapter adapter;
    final Runnable refresh = new Runnable() {
        public void run() {
            adapter.notifyDataSetChanged();
            empty.setVisibility(Dl.items.isEmpty() ? View.VISIBLE : View.GONE);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Dl.load(this);
        setContentView(R.layout.downloads);
        list = (ListView) findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        adapter = new BaseAdapter() {
            public int getCount() { return Dl.items.size(); }
            public Object getItem(int i) { return Dl.items.get(Dl.items.size() - 1 - i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.row_item, p, false);
                Item it = (Item) getItem(i);
                ((TextView) v.findViewById(R.id.name)).setText(it.name);
                ProgressBar pb = (ProgressBar) v.findViewById(R.id.prog);
                if (it.status == Item.RUNNING && it.total > 0) {
                    pb.setIndeterminate(false);
                    pb.setMax(1000);
                    pb.setProgress((int) (it.done * 1000 / it.total));
                } else if (it.status == Item.RUNNING || it.status == Item.QUEUED) {
                    pb.setIndeterminate(true);
                } else {
                    pb.setIndeterminate(false);
                    pb.setMax(1000);
                    pb.setProgress(it.status == Item.DONE ? 1000 : 0);
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
    }

    @Override protected void onResume() {
        super.onResume();
        Dl.listeners.add(refresh);
        refresh.run();
    }

    @Override protected void onPause() {
        Dl.listeners.remove(refresh);
        super.onPause();
    }

    static String info(Item it) {
        switch (it.status) {
            case Item.QUEUED: return "Na fila";
            case Item.RUNNING:
                if (it.hls) return "Baixando segmento " + it.done + " de " + it.total;
                if (it.total > 0) return size(it.done) + " / " + size(it.total) + "  (" + (it.done * 100 / it.total) + "%)";
                return size(it.done);
            case Item.DONE: return "Concluído • Download/Anime-Down";
            case Item.CANCELED: return "Cancelado";
            default: return "Erro: " + it.msg;
        }
    }

    static String size(long b) {
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    void actions(final Item it) {
        final List<String> o = new ArrayList<>();
        if (it.status == Item.DONE) o.add("Abrir");
        if (it.status == Item.RUNNING || it.status == Item.QUEUED) o.add("Cancelar");
        if (it.status == Item.ERROR || it.status == Item.CANCELED) o.add("Tentar de novo");
        o.add("Remover da lista");
        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(o.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String a = o.get(w);
                        if (a.equals("Abrir")) open(it);
                        else if (a.equals("Cancelar")) Dl.cancel(it);
                        else if (a.equals("Tentar de novo")) Dl.start(it);
                        else Dl.remove(it);
                    }
                }).show();
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
