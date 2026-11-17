package com.animedown.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;

/** Temas do app: fundo, superfície (cartões/barras) e cor de destaque. */
final class Thm {
    static final String[] NAMES = {"Roxo (padrão)", "Azul", "Verde", "Rosa", "Dourado", "Preto total"};
    // fundo, superfície, destaque
    static final int[][] COLORS = {
            {0xFF0E0E14, 0xFF1B1B28, 0xFFB388FF},
            {0xFF0A1018, 0xFF152030, 0xFF64B5F6},
            {0xFF0A140F, 0xFF14261B, 0xFF69F0AE},
            {0xFF160A10, 0xFF2A1520, 0xFFFF80AB},
            {0xFF12100A, 0xFF241F12, 0xFFFFD54F},
            {0xFF000000, 0xFF121212, 0xFFB388FF}
    };

    static int index(Context c) {
        int i = Store.sp(c).getInt("theme", 0);
        return i < 0 || i >= COLORS.length ? 0 : i;
    }

    static void set(Context c, int i) { Store.sp(c).edit().putInt("theme", i).apply(); }

    static int bg(Context c) { return COLORS[index(c)][0]; }

    static int surface(Context c) { return COLORS[index(c)][1]; }

    static int accent(Context c) { return COLORS[index(c)][2]; }

    /** Texto legível em cima da cor de destaque. */
    static int onAccent(Context c) { return bg(c) == 0xFF000000 ? Color.BLACK : bg(c); }

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static GradientDrawable round(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Aplica fundo da janela e barras do sistema. */
    static void apply(Activity a) {
        a.getWindow().setBackgroundDrawable(new ColorDrawable(bg(a)));
        if (Build.VERSION.SDK_INT >= 21) {
            a.getWindow().setStatusBarColor(bg(a));
            a.getWindow().setNavigationBarColor(bg(a));
        }
    }
}
