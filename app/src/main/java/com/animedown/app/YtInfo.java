package com.animedown.app;

import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

/** Links do YouTube (videoplayback) trazem a qualidade (itag) e o tamanho (clen) no próprio endereço. */
final class YtInfo {
    // itag -> {altura, tem áudio (1/0), 60 fps (1/0)}
    static final Map<Integer, int[]> T = new HashMap<>();
    static final int[] AUDIO = {139, 140, 141, 171, 172, 249, 250, 251, 256, 258, 325, 328};

    static void v(int itag, int h, int audio, int fps60) {
        T.put(itag, new int[]{h, audio, fps60});
    }

    static {
        // vídeo + áudio juntos
        v(5, 240, 1, 0); v(6, 270, 1, 0); v(13, 144, 1, 0); v(17, 144, 1, 0); v(18, 360, 1, 0);
        v(22, 720, 1, 0); v(34, 360, 1, 0); v(35, 480, 1, 0); v(36, 240, 1, 0); v(37, 1080, 1, 0);
        v(38, 3072, 1, 0); v(43, 360, 1, 0); v(44, 480, 1, 0); v(45, 720, 1, 0); v(46, 1080, 1, 0);
        // só vídeo (mp4 / avc)
        v(160, 144, 0, 0); v(133, 240, 0, 0); v(134, 360, 0, 0); v(135, 480, 0, 0); v(136, 720, 0, 0);
        v(137, 1080, 0, 0); v(138, 2160, 0, 0); v(264, 1440, 0, 0); v(266, 2160, 0, 0); v(212, 480, 0, 0);
        v(298, 720, 0, 1); v(299, 1080, 0, 1);
        // só vídeo (webm / vp9)
        v(278, 144, 0, 0); v(242, 240, 0, 0); v(243, 360, 0, 0); v(244, 480, 0, 0); v(247, 720, 0, 0);
        v(248, 1080, 0, 0); v(271, 1440, 0, 0); v(313, 2160, 0, 0);
        v(302, 720, 0, 1); v(303, 1080, 0, 1); v(308, 1440, 0, 1); v(315, 2160, 0, 1);
        // só vídeo (av1)
        v(394, 144, 0, 0); v(395, 240, 0, 0); v(396, 360, 0, 0); v(397, 480, 0, 0); v(398, 720, 0, 0);
        v(399, 1080, 0, 0); v(400, 1440, 0, 0); v(401, 2160, 0, 0); v(402, 4320, 0, 0);
        // só vídeo (HDR)
        v(330, 144, 0, 1); v(331, 240, 0, 1); v(332, 360, 0, 1); v(333, 480, 0, 1); v(334, 720, 0, 1);
        v(335, 1080, 0, 1); v(336, 1440, 0, 1); v(337, 2160, 0, 1);
    }

    static boolean isAudio(int itag) {
        for (int a : AUDIO) if (a == itag) return true;
        return false;
    }

    /** Devolve a opção (resolução e tamanho) lida do link, ou null se não for um link do YouTube que dê para ler. */
    static Probe.Opt parse(String url) {
        try {
            Uri u = Uri.parse(url);
            String path = u.getPath() == null ? "" : u.getPath();
            if (!path.contains("videoplayback")) return null;
            String itagS = u.getQueryParameter("itag");
            String clenS = u.getQueryParameter("clen");
            String mime = u.getQueryParameter("mime");
            int itag = -1;
            try {
                if (itagS != null) itag = Integer.parseInt(itagS.trim());
            } catch (Exception ignored) {
            }
            Probe.Opt o = new Probe.Opt();
            o.url = url;
            try {
                if (clenS != null) o.size = Long.parseLong(clenS.trim());
            } catch (Exception ignored) {
            }
            int[] info = T.get(itag);
            if (info != null) {
                o.height = info[0];
                o.noAudio = info[1] == 0;
                o.label = info[0] + "p" + (info[2] == 1 ? "60" : "");
            } else if (isAudio(itag) || (mime != null && mime.startsWith("audio"))) {
                o.label = "\u00c1udio";
            } else {
                return null;
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }
}
