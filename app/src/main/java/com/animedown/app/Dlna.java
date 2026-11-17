package com.animedown.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transmite um vídeo para TVs/aparelhos DLNA (UPnP AV) da rede Wi-Fi. */
final class Dlna {
    static class Device {
        String name, controlUrl, serviceType;
        @Override public String toString() { return name; }
    }

    interface Callback {
        void onResult(List<Device> devices);
    }

    // ------------------------------------------------ descoberta (SSDP)

    static void discover(final Context ctx, final int ms, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                List<Device> out = new ArrayList<>();
                WifiManager.MulticastLock lock = null;
                MulticastSocket sock = null;
                try {
                    WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) {
                        lock = wm.createMulticastLock("animedown-dlna");
                        lock.setReferenceCounted(false);
                        lock.acquire();
                    }
                    sock = new MulticastSocket();
                    sock.setSoTimeout(400);
                    InetAddress group = InetAddress.getByName("239.255.255.250");
                    String[] targets = {
                            "urn:schemas-upnp-org:device:MediaRenderer:1",
                            "urn:schemas-upnp-org:service:AVTransport:1"
                    };
                    Set<String> locations = new LinkedHashSet<>();
                    long end = System.currentTimeMillis() + ms;
                    long nextSend = 0;
                    byte[] buf = new byte[4096];
                    while (System.currentTimeMillis() < end) {
                        if (System.currentTimeMillis() >= nextSend) {
                            for (String st : targets) {
                                String m = "M-SEARCH * HTTP/1.1\r\n"
                                        + "HOST: 239.255.255.250:1900\r\n"
                                        + "MAN: \"ssdp:discover\"\r\n"
                                        + "MX: 2\r\n"
                                        + "ST: " + st + "\r\n\r\n";
                                byte[] b = m.getBytes("UTF-8");
                                sock.send(new DatagramPacket(b, b.length, group, 1900));
                            }
                            nextSend = System.currentTimeMillis() + 1500;
                        }
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            sock.receive(p);
                            String r = new String(p.getData(), 0, p.getLength(), "UTF-8");
                            for (String line : r.split("\r\n")) {
                                if (line.toLowerCase().startsWith("location:")) {
                                    locations.add(line.substring(9).trim());
                                }
                            }
                        } catch (SocketTimeoutException ignored) {
                        }
                    }
                    Set<String> seen = new LinkedHashSet<>();
                    for (String loc : locations) {
                        try {
                            Device d = describe(loc);
                            if (d != null && seen.add(d.controlUrl)) out.add(d);
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (sock != null) sock.close();
                    if (lock != null && lock.isHeld()) lock.release();
                }
                cb.onResult(out);
            }
        }).start();
    }

    static Device describe(String location) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(location).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            char[] cb = new char[4096];
            int n;
            while ((n = br.read(cb)) > 0 && sb.length() < 400000) sb.append(cb, 0, n);
        } finally {
            c.disconnect();
        }
        String xml = sb.toString();
        Matcher nm = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.DOTALL).matcher(xml);
        String name = nm.find() ? unesc(nm.group(1).trim()) : "TV";

        Matcher sm = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL).matcher(xml);
        while (sm.find()) {
            String block = sm.group(1);
            Matcher tm = Pattern.compile("<serviceType>(.*?)</serviceType>", Pattern.DOTALL).matcher(block);
            Matcher um = Pattern.compile("<controlURL>(.*?)</controlURL>", Pattern.DOTALL).matcher(block);
            if (tm.find() && um.find() && tm.group(1).contains("AVTransport")) {
                Device d = new Device();
                d.name = name;
                d.serviceType = tm.group(1).trim();
                d.controlUrl = new URL(new URL(location), um.group(1).trim()).toString();
                return d;
            }
        }
        return null;
    }

    // ------------------------------------------------ transmitir

    /** Serve o arquivo por HTTP no celular e manda a TV tocar. */
    static void cast(Context ctx, Device d, Uri file, String title) throws Exception {
        String url = serve(ctx, file);
        String mime = mimeOf(ctx.getContentResolver(), file);
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + esc(title == null ? "Anime-Down" : title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mime + ":DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">"
                + esc(url) + "</res></item></DIDL-Lite>";
        try {
            soap(d, "Stop", "<InstanceID>0</InstanceID>");
        } catch (Exception ignored) {
        }
        soap(d, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>" + esc(url)
                + "</CurrentURI><CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
        soap(d, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    static void soap(Device d, String action, String args) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + d.serviceType + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        byte[] b = body.getBytes("UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(d.controlUrl).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPACTION", "\"" + d.serviceType + "#" + action + "\"");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.close();
            int code = c.getResponseCode();
            if (code >= 300) throw new IOException("a TV recusou (" + action + ", código " + code + ")");
        } finally {
            c.disconnect();
        }
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    // ------------------------------------------------ servidor HTTP local

    private static ServerSocket server;
    private static Context appCtx;
    private static final Map<String, Uri> files = new ConcurrentHashMap<>();

    static synchronized String serve(Context ctx, Uri uri) throws IOException {
        appCtx = ctx.getApplicationContext();
        String ip = localIp();
        if (ip == null) throw new IOException("conecte o celular ao Wi-Fi");
        if (server == null || server.isClosed()) {
            server = new ServerSocket(0);
            final ServerSocket ss = server;
            new Thread(new Runnable() {
                public void run() {
                    while (!ss.isClosed()) {
                        try {
                            final Socket s = ss.accept();
                            new Thread(new Runnable() {
                                public void run() { handle(s); }
                            }).start();
                        } catch (IOException e) {
                            break;
                        }
                    }
                }
            }).start();
        }
        String id = Integer.toHexString(uri.toString().hashCode());
        files.put(id, uri);
        String mime = mimeOf(appCtx.getContentResolver(), uri);
        String ext = mime.contains("matroska") ? ".mkv" : mime.contains("webm") ? ".webm" : ".mp4";
        return "http://" + ip + ":" + server.getLocalPort() + "/v/" + id + ext;
    }

    static String mimeOf(ContentResolver cr, Uri uri) {
        String t = null;
        try {
            t = cr.getType(uri);
        } catch (Exception ignored) {
        }
        return t != null && t.startsWith("video/") ? t : "video/mp4";
    }

    static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        if (ni.getName().startsWith("wlan")) return a.getHostAddress();
                        if (fallback == null) fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String line = br.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            long rs = -1, re = -1;
            String h;
            while ((h = br.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase().startsWith("range:")) {
                    Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(h);
                    if (m.find()) {
                        rs = m.group(1).isEmpty() ? -1 : Long.parseLong(m.group(1));
                        re = m.group(2).isEmpty() ? -1 : Long.parseLong(m.group(2));
                    }
                }
            }
            OutputStream out = s.getOutputStream();
            Matcher pm = Pattern.compile("^/v/([0-9a-f]+)").matcher(path);
            Uri uri = pm.find() ? files.get(pm.group(1)) : null;
            if (uri == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                out.flush();
                return;
            }
            ContentResolver cr = appCtx.getContentResolver();
            ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
            long len = pfd.getStatSize();
            pfd.close();

            long start = 0, end = len - 1;
            boolean partial = false;
            if (rs >= 0) {
                start = rs;
                if (re >= 0 && re < len) end = re;
                partial = true;
            } else if (re >= 0) {
                start = Math.max(0, len - re);
                partial = true;
            }
            if (start >= len) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                out.flush();
                return;
            }
            long n = end - start + 1;
            StringBuilder sb = new StringBuilder();
            sb.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(mimeOf(cr, uri)).append("\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Content-Length: ").append(n).append("\r\n");
            if (partial) sb.append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(len).append("\r\n");
            sb.append("transferMode.dlna.org: Streaming\r\n");
            sb.append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes("UTF-8"));

            if (!method.equals("HEAD")) {
                InputStream is = cr.openInputStream(uri);
                try {
                    long skipped = 0;
                    while (skipped < start) {
                        long k = is.skip(start - skipped);
                        if (k <= 0) {
                            if (is.read() < 0) break;
                            k = 1;
                        }
                        skipped += k;
                    }
                    byte[] buf = new byte[65536];
                    long left = n;
                    while (left > 0) {
                        int r = is.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) break;
                        out.write(buf, 0, r);
                        left -= r;
                    }
                } finally {
                    is.close();
                }
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
