package com.animedown.app;

import android.net.Uri;

final class AdBlock {
    private static final String[] HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "adnxs.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com", "juicyads.com",
        "adsterra.com", "highperformanceformat.com", "profitablegatecpm.com", "effectivegatecpm.com",
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com", "onclickads.net", "clickadu.com",
        "trafficjunky.com", "trafficstars.com", "tsyndicate.com", "histats.com", "adcash.com",
        "a-ads.com", "ad-maven.com", "admaven.com", "bidvertiser.com", "hilltopads.net", "monetag.com",
        "pushwhy.com", "richpush.co", "adskeeper.com", "yllix.com", "popunder.net", "ero-advertising.com",
        "ads.yahoo.com", "amazon-adsystem.com", "criteo.com", "pubmatic.com", "rubiconproject.com",
        "scorecardresearch.com", "quantserve.com", "ad.doubleclick.net", "juicyads.me", "acscdn.com"
    };

    static boolean isAd(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase();
            for (String a : HOSTS) {
                if (h.equals(a) || h.endsWith("." + a)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
