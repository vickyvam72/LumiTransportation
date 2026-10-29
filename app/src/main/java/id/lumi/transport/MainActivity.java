package id.lumi.transport;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lumi Transportation — kerangka native.
 * UI dan mesin rute berjalan di WebView (assets/www). Kelas ini menyediakan jembatan
 * "LumiNative" untuk: HTTP (geocoding/rute jalan, dengan User-Agent yang jelas),
 * lokasi GPS, membuka aplikasi ojol, dan berbagi teks.
 */
public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 41, REQ_GUIDE = 42, REQ_NOTIF = 43, REQ_PERM = 44;
    private static final String UA = "LumiTransportation/1.2 (Android)";

    private WebView web;
    private android.widget.FrameLayout root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private String pendingLocateId;
    private String pendingShare;
    private boolean pageReady;
    private final Object liveLock = new Object();
    private LiveSocket live;
    private final java.util.ArrayList<String> liveQueue = new java.util.ArrayList<String>();
    private boolean liveFlushPending;

    /** Kirim data bus ke JS paling sering tiap 250 ms (digabung) agar WebView tidak kewalahan. */
    private void scheduleLiveFlush() {
        synchronized (liveLock) { if (liveFlushPending) return; liveFlushPending = true; }
        main.postDelayed(new Runnable() { @Override public void run() {
            StringBuilder sb = new StringBuilder("[");
            synchronized (liveLock) {
                for (int i = 0; i < liveQueue.size(); i++) { if (i > 0) sb.append(','); sb.append('"').append(liveQueue.get(i)).append('"'); }
                liveQueue.clear();
                liveFlushPending = false;
            }
            sb.append(']');
            if (web != null) web.evaluateJavascript("window.LumiLive && LumiLive.__nativeData(" + sb + ")", null);
        }}, 250);
    }
    private String[] pendingGuide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#4DB6F4"));
        w.setNavigationBarColor(Color.parseColor("#FFFFFF"));
        if (Build.VERSION.SDK_INT >= 26) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#EEF6FC"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " LumiTransportation/1.0");
        web.addJavascriptInterface(new Bridge(), "LumiNative");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) { pageReady = true; }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) return false;
                openExternal(url);
                return true;
            }
        });
        root = new android.widget.FrameLayout(this);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        pendingShare = sharedText(getIntent());
        pendingOpen = openTarget(getIntent());
        web.loadUrl("file:///android_asset/www/index.html");
        NavService.listener = new NavService.Listener() {
            @Override public void onGuide(String json) { js("window.__lumiGuide && __lumiGuide(" + json + ")"); }
        };
        try { RouteUpdater.schedule(this); } catch (Exception ignored) { }
        try { RouteWatchJob.schedule(this); } catch (Exception ignored) { }
    }

    private String pendingOpen;
    private static String openTarget(Intent i) {
        if (i == null) return null;
        if (i.getBooleanExtra("openGuide", false)) return "guide";
        if (i.getBooleanExtra("openChanges", false)) return "changes";
        return null;
    }

    /** Android 13+: izin notifikasi (untuk pendampingan & info perubahan rute), diminta sekali. */
    private void askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences sp = getSharedPreferences("lumi", MODE_PRIVATE);
        if (sp.getBoolean("askedNotif", false)) return;
        sp.edit().putBoolean("askedNotif", true).apply();
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
    }

    /** Teks yang dibagikan dari aplikasi lain (mis. Google Maps: "Nama tempat\nhttps://maps.app.goo.gl/…"). */
    private static String sharedText(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return null;
        CharSequence subj = i.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (t == null) return null;
        String s = t.toString();
        if (subj != null && !s.contains(subj)) s = subj + "\n" + s;
        return s;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String o = openTarget(intent);
        if (o != null) {
            if (pageReady) js("window.lumiOpen && lumiOpen(" + JSONObject.quote(o) + ")");
            else pendingOpen = o;
        }
        String s = sharedText(intent);
        if (s == null) return;
        if (pageReady) js("window.lumiShared && window.lumiShared(" + JSONObject.quote(s) + ")");
        else pendingShare = s;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) js("window.__lumiPerm && __lumiPerm()");   // kembali dari pengaturan HP
    }

    private boolean hasLoc() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasNotif() {
        if (Build.VERSION.SDK_INT >= 33) return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm == null || nm.areNotificationsEnabled();
    }
    private boolean hasBattery() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }
    static boolean isChineseOem() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.US);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") || m.contains("oppo") || m.contains("vivo")
                || m.contains("realme") || m.contains("infinix") || m.contains("tecno") || m.contains("itel") || m.contains("oneplus") || m.contains("iqoo");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.lumiBack && window.lumiBack()) ? '1' : '0'", new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (!"\"1\"".equals(value)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        NavService.listener = null;
        synchronized (liveLock) { if (live != null) live.close(); live = null; }
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void js(final String code) {
        main.post(new Runnable() { @Override public void run() { if (web != null) web.evaluateJavascript(code, null); }});
    }

    private void openExternal(String url) {
        try {
            Intent i;
            if (url.startsWith("intent:")) i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            else i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            js("window.lumiToast && lumiToast('Tidak ada aplikasi untuk membuka tautan ini')");
        }
    }

    // ------------------------------------------------------------ lokasi
    private void deliverLocation(String id, Location loc, String err) {
        try {
            JSONObject o = new JSONObject();
            if (loc != null) {
                o.put("lat", loc.getLatitude());
                o.put("lon", loc.getLongitude());
                o.put("acc", loc.getAccuracy());
            } else {
                o.put("error", err == null ? "Lokasi tidak tersedia" : err);
            }
            js("window.__lumiLocate(" + JSONObject.quote(id) + "," + o.toString() + ")");
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("MissingPermission")
    private void locateNow(final String id) {
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { deliverLocation(id, null, "Layanan lokasi tidak tersedia"); return; }
        Location best = null;
        for (String p : lm.getProviders(true)) {
            Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 3 * 60 * 1000) {
            deliverLocation(id, best, null);
            return;
        }
        final Location fallback = best;
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) {
            deliverLocation(id, fallback, "Aktifkan GPS / Lokasi di pengaturan ponsel");
            return;
        }
        final boolean[] done = {false};
        final LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (done[0]) return;
                done[0] = true;
                lm.removeUpdates(this);
                deliverLocation(id, location, null);
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        lm.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
        main.postDelayed(new Runnable() { @Override public void run() {
            if (done[0]) return;
            done[0] = true;
            lm.removeUpdates(listener);
            deliverLocation(id, fallback, "Sinyal lokasi lemah, coba lagi di area terbuka");
        }}, 15000);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERM) { js("window.__lumiPerm && __lumiPerm()"); return; }
        if (code == REQ_GUIDE) {
            boolean ok = false;
            for (int i = 0; i < perms.length; i++)
                if (perms[i].contains("LOCATION") && results[i] == PackageManager.PERMISSION_GRANTED) ok = true;
            if (ok && pendingGuide != null) { NavService.start(this, pendingGuide[0], pendingGuide[1]); }
            else js("window.__lumiGuide && __lumiGuide({error:'Izin lokasi ditolak. Pendampingan butuh akses lokasi.'})");
            pendingGuide = null;
            return;
        }
        if (code == REQ_LOCATION && pendingLocateId != null) {
            String id = pendingLocateId;
            pendingLocateId = null;
            boolean ok = false;
            for (int r : results) ok |= r == PackageManager.PERMISSION_GRANTED;
            if (ok) locateNow(id);
            else deliverLocation(id, null, "Izin lokasi ditolak");
        }
    }

    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    private static final java.util.regex.Pattern[] COORD_PATTERNS = {
            java.util.regex.Pattern.compile("(?:!|%21)3d(-?\\d{1,2}\\.\\d+)(?:!|%21)4d(-?\\d{2,3}\\.\\d+)"),
            java.util.regex.Pattern.compile("@(-?\\d{1,2}\\.\\d{3,}),(-?\\d{2,3}\\.\\d{3,})"),
            java.util.regex.Pattern.compile("(?:center|ll|q|query|markers|destination|sll)=(-?\\d{1,2}\\.\\d{3,})(?:,|%2C)\\+?(-?\\d{2,3}\\.\\d{3,})"),
            java.util.regex.Pattern.compile("\\[null,null,(-?\\d{1,2}\\.\\d{4,}),(-?\\d{2,3}\\.\\d{4,})\\]"),
    };
    // APP_INITIALIZATION_STATE Google Maps: [[[skala, BUJUR, LINTANG], ...
    private static final java.util.regex.Pattern COORD_LONLAT =
            java.util.regex.Pattern.compile("\\[\\[\\[\\d+(?:\\.\\d+)?,(-?\\d{2,3}\\.\\d{4,}),(-?\\d{1,2}\\.\\d{4,})\\]");

    private static boolean inIndonesia(double la, double lo) { return la > -11.5 && la < 6.5 && lo > 94 && lo < 142; }

    /**
     * Koordinat TEMPAT dari URL Google Maps. Hanya pola yang menunjuk tempat itu sendiri:
     * "!3d<lat>!4d<lon>" (data tempat), atau "/place/…/@lat,lon" dan "?q=lat,lon".
     * Koordinat tampilan peta di HTML (sering = perkiraan lokasi pengguna) sengaja diabaikan.
     */
    static String placeCoords(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = COORD_PATTERNS[0].matcher(url);
        String last = null;
        while (m.find()) {   // ambil kemunculan TERAKHIR: itu milik tempat utama di URL Google Maps
            try {
                double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                if (inIndonesia(la, lo)) last = la + "," + lo;
            } catch (Exception ignored) { }
        }
        if (last != null) return last;
        if (url.contains("/place/") || url.contains("/dir/")) {
            m = COORD_PATTERNS[1].matcher(url);
            if (m.find()) {
                double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                if (inIndonesia(la, lo)) return la + "," + lo;
            }
        }
        m = java.util.regex.Pattern.compile("[?&](?:q|query|ll|destination)=(-?\\d{1,2}\\.\\d{3,})(?:,|%2C)\\+?(-?\\d{2,3}\\.\\d{3,})").matcher(url);
        if (m.find()) {
            double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
            if (inIndonesia(la, lo)) return la + "," + lo;
        }
        return null;
    }

    /** (lama) pencarian longgar di teks; tidak dipakai lagi untuk tautan Google Maps. */
    static String findCoords(String text) {
        if (text == null) return null;
        for (java.util.regex.Pattern p : COORD_PATTERNS) {
            java.util.regex.Matcher m = p.matcher(text);
            while (m.find()) {
                try {
                    double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                    if (inIndonesia(la, lo)) return la + "," + lo;
                } catch (Exception ignored) { }
            }
        }
        java.util.regex.Matcher m = COORD_LONLAT.matcher(text);
        while (m.find()) {
            try {
                double lo = Double.parseDouble(m.group(1)), la = Double.parseDouble(m.group(2));
                if (inIndonesia(la, lo)) return la + "," + lo;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> resolvedIds = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
    private static final java.util.regex.Pattern MARKERS =
            java.util.regex.Pattern.compile("markers=(?:[^&\"']*?(?:%7C|\\|))?(-?\\d{1,2}\\.\\d{3,})(?:%2C|,)(-?\\d{2,3}\\.\\d{3,})");

    private android.content.SharedPreferences cache() { return getSharedPreferences("lumi_resolve", MODE_PRIVATE); }

    /** Kirim hasil sekali saja (HTTP dan WebView berlomba; yang pertama menang). */
    private final java.util.concurrent.ConcurrentHashMap<String, String> resolvedSource = new java.util.concurrent.ConcurrentHashMap<String, String>();
    private void deliverResolved(String id, String url, String coords) {
        if (resolvedIds.putIfAbsent(id, Boolean.TRUE) != null) return;
        if (coords != null && resolvedSource.get(id) != null) cache().edit().putString(resolvedSource.remove(id), coords).apply();
        String out = url;
        if (coords != null) out = url + (url.contains("?") ? "&" : "?") + "ll=" + coords;
        js("window.__lumiResolved(" + JSONObject.quote(id) + "," + JSONObject.quote(out) + ")");
    }

    /** Muat tautan di WebView tersembunyi dan tunggu koordinat muncul di URL/halaman (maks. 20 detik). */
    private void probe(final String id, final String url) {
        final WebView w = new WebView(this);
        final boolean[] done = {false};
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        w.getSettings().setUserAgentString(DESKTOP_UA);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String s = req.getUrl().getScheme();
                return !("http".equals(s) || "https".equals(s));   // jangan lompat ke aplikasi lain
            }
        });
        w.getSettings().setBlockNetworkImage(true);   // tidak perlu gambar peta: lebih cepat
        w.getSettings().setLoadsImagesAutomatically(false);
        root.addView(w, new android.widget.FrameLayout.LayoutParams(400, 700));
        w.setTranslationX(-5000);
        final long start = System.currentTimeMillis();
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() { @Override public void run() {
            if (done[0]) return;
            String cur = w.getUrl();
            String c = placeCoords(cur);
            boolean other = resolvedIds.containsKey(id);   // HTTP sudah lebih dulu menemukan
            if (other || c != null || System.currentTimeMillis() - start > 20000) {
                done[0] = true;
                if (!other) deliverResolved(id, cur != null ? cur : url, c);
                root.removeView(w);
                w.destroy();
                return;
            }
            main.postDelayed(tick[0], 250);
        }};
        w.loadUrl(url);
        main.postDelayed(tick[0], 300);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    // ------------------------------------------------------------ bridge
    private class Bridge {
        @JavascriptInterface
        public void http(final String id, final String url) {
            httpH(id, url, null);
        }

        /** HTTP GET dengan header tambahan (JSON objek), mis. Authorization untuk CARTO. */
        @JavascriptInterface
        public void httpH(final String id, final String url, final String headersJson) {
            pool.execute(new Runnable() { @Override public void run() {
                int status = 0;
                String body = "";
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("User-Agent", UA);
                    c.setRequestProperty("Accept-Language", "id,en");
                    c.setRequestProperty("Accept", "application/json");
                    if (headersJson != null && headersJson.length() > 2) {
                        JSONObject h = new JSONObject(headersJson);
                        java.util.Iterator<String> it = h.keys();
                        while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, h.getString(k)); }
                    }
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(15000);
                    status = c.getResponseCode();
                    InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    if (in != null) body = readAll(in);
                } catch (Exception e) {
                    status = 0;
                    body = String.valueOf(e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                js("window.__lumiHttp(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + ")");
            }});
        }

        /** Unduh & proses GTFS resmi Transjakarta di HP. Progres dikirim ke window.__lumiGtfs(status, pesan). */
        @JavascriptInterface
        public void updateGtfs() {
            pool.execute(new Runnable() { @Override public void run() {
                GtfsImporter.Progress prog = new GtfsImporter.Progress() {
                    public void update(String m) { js("window.__lumiGtfs && __lumiGtfs('progress'," + JSONObject.quote(m) + ")"); }
                };
                try {
                    RouteUpdater.Result r = RouteUpdater.run(MainActivity.this, prog, true);
                    js("window.__lumiGtfs && __lumiGtfs('done'," + JSONObject.quote(r.date.substring(0, 10)) + "," + r.changes + ")");
                } catch (Throwable e) {
                    js("window.__lumiGtfs && __lumiGtfs('error'," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }});
        }

        /** Data rute hasil GTFS yang tersimpan (atau null). */
        @JavascriptInterface
        public String getRouteData() {
            try {
                java.io.File f = new java.io.File(getFilesDir(), "tj_gtfs.json");
                if (!f.exists()) return null;
                return readAll(new java.io.FileInputStream(f));
            } catch (Exception e) { return null; }
        }

        @JavascriptInterface
        public void clearRouteData() {
            new java.io.File(getFilesDir(), "tj_gtfs.json").delete();
        }

        /** Lokasi yang dibagikan sebelum halaman siap (dipanggil sekali saat start). */
        @JavascriptInterface
        public String takeShared() {
            String s = pendingShare;
            pendingShare = null;
            return s;
        }

        /** Ikuti redirect (tautan pendek Google Maps) dan kembalikan URL akhir. */
        @JavascriptInterface
        public void resolve(final String id, final String startUrl) {
            String cached = cache().getString(startUrl, null);
            if (cached != null) { deliverResolved(id, startUrl, cached); return; }
            if (placeCoords(startUrl) != null) { deliverResolved(id, startUrl, placeCoords(startUrl)); return; }
            resolvedSource.put(id, startUrl);
            // Jalankan dua cara sekaligus: WebView tersembunyi (langsung mulai) dan HTTP redirect + HTML.
            main.post(new Runnable() { @Override public void run() { probe(id, startUrl); }});
            pool.execute(new Runnable() { @Override public void run() {
                String url = startUrl;
                String found = placeCoords(url);
                try {
                    for (int i = 0; i < 8 && found == null; i++) {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("User-Agent", DESKTOP_UA);
                        c.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8");
                        c.setConnectTimeout(6000);
                        c.setReadTimeout(8000);
                        int code = c.getResponseCode();
                        String loc = c.getHeaderField("Location");
                        if (code >= 300 && code < 400 && loc != null) {
                            c.disconnect();
                            url = new URL(new URL(url), loc).toString();
                            found = placeCoords(url);
                            continue;
                        }
                        // Halaman tempat: gambar pratinjau (og:image staticmap) memuat "markers=lat,lon" milik tempat.
                        if (code == 200 && !resolvedIds.containsKey(id)) {
                            String html = readAll(c.getInputStream());
                            java.util.regex.Matcher m = MARKERS.matcher(html);
                            while (m.find() && found == null) {
                                double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                                if (inIndonesia(la, lo)) found = la + "," + lo;
                            }
                            if (found == null) {
                                m = COORD_PATTERNS[0].matcher(html.length() > 400000 ? html.substring(0, 400000) : html);
                                // "!3d…!4d…" di HTML halaman /place/ = tempat itu sendiri
                                if (url.contains("/place/") && m.find()) {
                                    double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                                    if (inIndonesia(la, lo)) found = la + "," + lo;
                                }
                            }
                        }
                        c.disconnect();
                        break;
                    }
                } catch (Exception ignored) { }
                if (found != null) deliverResolved(id, url, found);
                // bila belum ketemu, WebView tersembunyi yang sudah berjalan akan menyelesaikannya
            }});
        }

        @JavascriptInterface
        public void locate(final String id) {
            main.post(new Runnable() { @Override public void run() {
                if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    pendingLocateId = id;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                } else {
                    locateNow(id);
                }
            }});
        }

        /** Buka aplikasi berdasarkan nama paket; jika tidak terpasang, buka Play Store. */
        @JavascriptInterface
        public void openApp(final String pkg) {
            main.post(new Runnable() { @Override public void run() {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    startActivity(i);
                } else {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
                    } catch (ActivityNotFoundException e) {
                        openExternal("https://play.google.com/store/apps/details?id=" + pkg);
                    }
                }
            }});
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            main.post(new Runnable() { @Override public void run() { openExternal(url); }});
        }

        @JavascriptInterface
        public void share(final String text) {
            main.post(new Runnable() { @Override public void run() {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Bagikan rute"));
            }});
        }

        /** Riwayat perubahan rute (JSON). */
        @JavascriptInterface
        public String getChanges() { return RouteUpdater.changesJson(MainActivity.this); }

        @JavascriptInterface
        public void markChangesSeen() { RouteUpdater.markSeen(MainActivity.this); }

        /** Mulai mode pendampingan. spec = titik-titik rute (lihat GuideTracker). */
        @JavascriptInterface
        public void startGuide(final String spec, final String title) {
            main.post(new Runnable() { @Override public void run() {
                java.util.List<String> need = new java.util.ArrayList<String>();
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    need.add(Manifest.permission.ACCESS_FINE_LOCATION);
                    need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                }
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                    need.add("android.permission.POST_NOTIFICATIONS");
                boolean hasLoc = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (!need.isEmpty() && (!hasLoc || need.contains("android.permission.POST_NOTIFICATIONS"))) {
                    pendingGuide = new String[]{spec, title};
                    if (hasLoc) need.add(Manifest.permission.ACCESS_FINE_LOCATION);   // agar hasil memuat izin lokasi
                    requestPermissions(need.toArray(new String[0]), REQ_GUIDE);
                } else {
                    NavService.start(MainActivity.this, spec, title);
                }
            }});
        }

        @JavascriptInterface
        public void stopGuide() {
            main.post(new Runnable() { @Override public void run() { NavService.stop(MainActivity.this); }});
        }

        /** Status pendampingan terakhir (JSON) bila sedang berjalan, atau null. */
        @JavascriptInterface
        public String guideState() { return NavService.tracker != null ? (NavService.lastJson != null ? NavService.lastJson : "{}") : null; }

        /** Tujuan dari notifikasi (guide/changes), sekali saat halaman siap. */
        @JavascriptInterface
        public String takeOpen() { String o = pendingOpen; pendingOpen = null; return o; }

        /** Status izin untuk layar "Satu langkah lagi". */
        @JavascriptInterface
        public String permState() {
            return "{\"loc\":" + hasLoc() + ",\"notif\":" + hasNotif() + ",\"battery\":" + hasBattery() +
                    ",\"oem\":" + isChineseOem() + ",\"brand\":" + JSONObject.quote(Build.MANUFACTURER) + "}";
        }

        /** Minta izin: loc | notif | battery. */
        @JavascriptInterface
        public void requestPerm(final String kind) {
            main.post(new Runnable() { @Override public void run() {
                try {
                    if ("loc".equals(kind) && Build.VERSION.SDK_INT >= 23) {
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM);
                    } else if ("notif".equals(kind)) {
                        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_PERM);
                        else startActivity(new Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", getPackageName()));
                    } else if ("battery".equals(kind)) {
                        Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                        try { startActivity(i); }
                        catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    }
                } catch (Exception e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                }
            }});
        }

        /** Buka pengaturan "Mulai otomatis" (Xiaomi, Oppo, Vivo, Realme, Infinix, …); cadangan: info aplikasi. */
        @JavascriptInterface
        public void openAutostart() {
            main.post(new Runnable() { @Override public void run() {
                String[][] comps = {
                        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                        {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                        {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                        {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                        {"com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"},
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                        {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
                };
                for (String[] c : comps) {
                    try {
                        Intent i = new Intent();
                        i.setComponent(new android.content.ComponentName(c[0], c[1]));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return;
                    } catch (Exception ignored) { }
                }
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }});
        }

        /** Bagikan gambar rute (PNG base64) beserta teks singkat. */
        @JavascriptInterface
        public void shareImage(final String base64, final String text) {
            pool.execute(new Runnable() { @Override public void run() {
                try {
                    byte[] png = android.util.Base64.decode(base64.substring(base64.indexOf(',') + 1), android.util.Base64.DEFAULT);
                    java.io.File dir = ShareProvider.dir(MainActivity.this);
                    java.io.File[] old = dir.listFiles();
                    if (old != null) for (java.io.File f : old) f.delete();
                    String name = "rute-lumi-" + System.currentTimeMillis() + ".png";
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(new java.io.File(dir, name));
                    fo.write(png);
                    fo.close();
                    final Uri uri = Uri.parse("content://" + ShareProvider.AUTH + "/" + name);
                    main.post(new Runnable() { @Override public void run() {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("image/png");
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
                        i.setClipData(android.content.ClipData.newRawUri("rute", uri));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Bagikan rute"));
                    }});
                } catch (Exception e) {
                    js("window.lumiToast && lumiToast('Gagal membuat gambar rute')");
                }
            }});
        }

        /* ---- posisi bus langsung: WebSocket native (byte MQTT diteruskan ke live.js) ---- */
        @JavascriptInterface
        public void liveOpen(final String url) {
            synchronized (liveLock) {
                if (live != null) live.close();
                liveQueue.clear();
                live = new LiveSocket(url, new LiveSocket.Listener() {
                    public void onOpen() { js("window.LumiLive && LumiLive.__nativeOpen()"); }
                    public void onData(byte[] d) {
                        synchronized (liveLock) { liveQueue.add(android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP)); }
                        scheduleLiveFlush();
                    }
                    public void onClose(String reason) { js("window.LumiLive && LumiLive.__nativeClose(" + JSONObject.quote(reason) + ")"); }
                });
                live.start();
            }
        }

        @JavascriptInterface
        public void liveSend(String b64) {
            LiveSocket l = live;
            if (l != null) l.send(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP));
        }

        /** Buka aplikasi resmi TJ (pelacak bus resmi); bila belum terpasang, buka Play Store. */
        @JavascriptInterface
        public void openTjApp() {
            main.post(new Runnable() { @Override public void run() {
                for (String pkg : new String[]{"com.transjakmobile", "id.co.transjakarta.tije"}) {
                    Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i != null) { startActivity(i); return; }
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.transjakmobile"))); }
                catch (ActivityNotFoundException e) { openExternal("https://play.google.com/store/apps/details?id=com.transjakmobile"); }
            }});
        }

        @JavascriptInterface
        public void liveClose() {
            synchronized (liveLock) { if (live != null) live.close(); live = null; liveQueue.clear(); }
        }

        @JavascriptInterface
        public String version() {
            return "2.2";
        }
    }
}
