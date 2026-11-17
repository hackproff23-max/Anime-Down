package com.animedown.app;

import java.util.Locale;

final class Fmt {
    static String size(long b) {
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    static String speed(long bps) { return size(bps) + "/s"; }

    static String eta(long s) {
        if (s < 0) return "";
        long h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x)
                     : String.format(Locale.US, "%d:%02d", m, x);
    }

    /** Progresso de 0 a 1000, ou -1 se ainda não dá para saber. */
    static int permille(Item it) {
        if (it.status == Item.DONE) return 1000;
        if (it.hls) {
            if (it.pieceTotal > 0) return (int) Math.min(1000, it.pieceDone * 1000 / it.pieceTotal);
            return -1;
        }
        if (it.total > 0) return (int) Math.min(1000, it.done * 1000 / it.total);
        return -1;
    }
}
