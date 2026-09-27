package ru.ruroad.karta;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;

/**
 * Приложение-карта: WebView-обёртка https://ruroad.pik-sev.ru/karta/
 * Принимает extras: «q» — кадастровый номер или адрес; «lat»/«lon» — координаты центровки.
 */
public class MainActivity extends Activity {

    private static final String TAG = "RuRoad";
    private static final String BASE = "https://ruroad.pik-sev.ru/karta/";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private WebView webView;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setGeolocationEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // НСПД и другие защиты режут WebView-UA (токен "; wv)") — притворяемся обычным Chrome
        String ua = webView.getSettings().getUserAgentString();
        if (ua != null) {
            webView.getSettings().setUserAgentString(ua.replace("; wv)", ")"));
        }
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebViewClient(new KartaWebViewClient());
        webView.setWebChromeClient(new KartaChromeClient());
        webView.addJavascriptInterface(new Bridge(), "RuRoadApp");

        if (savedInstanceState == null) {
            loadFromIntent(getIntent());
        } else {
            webView.restoreState(savedInstanceState);
        }

        UpdateChecker.check(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadFromIntent(intent);
    }

    private void loadFromIntent(Intent intent) {
        String url = BASE + "?av=" + BuildConfig.VERSION_NAME;
        if (intent != null) {
            String q = intent.getStringExtra("q");
            double lat = intent.getDoubleExtra("lat", Double.NaN);
            double lon = intent.getDoubleExtra("lon", Double.NaN);
            if (q != null && !q.trim().isEmpty()) {
                url = BASE + "?q=" + Uri.encode(q.trim()) + "&av=" + BuildConfig.VERSION_NAME;
            } else if (!Double.isNaN(lat) && !Double.isNaN(lon)) {
                url = BASE + "?c=" + lon + "," + lat + "&z=18&av=" + BuildConfig.VERSION_NAME;
            }
        }
        webView.loadUrl(url);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /* ---------- WebView ---------- */

    private class KartaWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            return handleUri(view, uri);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(view, Uri.parse(url));
        }

        /* НСПД: забираем API-запросы на уровне Java — без CORS и с десктопным UA.
           TLS-валидатор терпимый: провайдеры РФ перехватывают nspd.gov.ru
           (подменный корень телефону не доверен), данные НСПД публичные.
           Если запрос всё равно не удался — вернём null, WebView сделает обычный запрос. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null || !"nspd.gov.ru".equalsIgnoreCase(uri.getHost())) return null;
            String path = uri.getPath();
            if (path == null || !path.startsWith("/api/")) return null;
            HttpURLConnection c = null;
            try {
                URL url = new URL(uri.toString());
                if ("https".equalsIgnoreCase(url.getProtocol())) {
                    HttpsURLConnection hs = (HttpsURLConnection) url.openConnection();
                    hs.setSSLSocketFactory(NspdTls.SOCKET_FACTORY);
                    hs.setHostnameVerifier(NspdTls.VERIFIER);
                    c = hs;
                } else {
                    c = (HttpURLConnection) url.openConnection();
                }
                c.setConnectTimeout(20000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", DESKTOP_UA);
                c.setRequestProperty("Accept", "*/*");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    Log.w(TAG, "nspd intercept HTTP " + code + " " + path);
                    c.disconnect();
                    return null;
                }
                String mime = "application/octet-stream";
                String charset = null;
                String ct = c.getContentType();
                if (ct != null) {
                    String[] parts = ct.split(";");
                    mime = parts[0].trim();
                    for (String p : parts) {
                        p = p.trim();
                        if (p.startsWith("charset=")) charset = p.substring(8).trim();
                    }
                }
                byte[] data = readAllBytes(c.getInputStream());
                c.disconnect();
                return new WebResourceResponse(mime, charset, new ByteArrayInputStream(data));
            } catch (Exception e) {
                Log.w(TAG, "nspd intercept failed: " + e);
                reportNspdError(path, e);
                if (c != null) c.disconnect();
                return null;
            }
        }

        private boolean handleUri(WebView view, Uri uri) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();

            // наша карта — грузим внутри
            if (("https".equals(scheme) || "http".equals(scheme))
                    && (host.equals("ruroad.pik-sev.ru") || host.equals("nspd.gov.ru"))) {
                return false;
            }
            // blob: — картограмма/выгрузка, генерируется клиентом: забираем через JS-мост
            if ("blob".equals(scheme)) {
                grabBlob(uri.toString());
                return true;
            }
            // data: — отдаём содержимое сразу
            if ("data".equals(scheme)) {
                saveDataUrl(uri.toString());
                return true;
            }
            // внешние приложения: навигация, звонки и т.д.
            if ("geo".equals(scheme) || "intent".equals(scheme) || "yandexmaps".equals(scheme)
                    || "tel".equals(scheme) || "mailto".equals(scheme)
                    || ("https".equals(scheme) || "http".equals(scheme))) {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    Log.w(TAG, "no handler for " + uri);
                }
                return true;
            }
            return false;
        }
    }

    /* Диагностика НСПД: тост с причиной сбоя (не чаще раза в 30 сек),
       чтобы по скриншоту было видно, что именно мешает запросу. */
    private long lastNspdToast;
    private void reportNspdError(String path, Exception e) {
        if (path == null || !path.contains("geoportal")) return;
        long now = System.currentTimeMillis();
        if (now - lastNspdToast < 30000) return;
        lastNspdToast = now;
        String msg = e.getClass().getSimpleName();
        runOnUiThread(() ->
                Toast.makeText(this, "НСПД: " + msg, Toast.LENGTH_LONG).show());
    }

    private class KartaChromeClient extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            geoOrigin = origin;
            geoCallback = callback;
            String[] perms = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(perms, 42);
            } else {
                callback.invoke(origin, true, false);
            }
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42 && geoCallback != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, granted, false);
            geoCallback = null;
        }
    }

    /* ---------- Скачивание картограммы (blob/data) ---------- */

    private void grabBlob(String blobUrl) {
        String js =
                "(function(){" +
                "var u=" + JSONObjectQuote(blobUrl) + ";" +
                "fetch(u).then(function(r){return r.blob();}).then(function(b){" +
                "var f=new FileReader();" +
                "f.onload=function(){" +
                "var d=f.result;var i=d.indexOf(',');" +
                "window.RuRoadApp.save('karta','application/octet-stream',d.substring(i+1));" +
                "};f.readAsDataURL(b);" +
                "}).catch(function(e){});" +
                "})()";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private static String JSONObjectQuote(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\'' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        return sb.append('\'').toString();
    }

    private void saveDataUrl(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return;
            String meta = dataUrl.substring(0, comma);
            String b64 = dataUrl.substring(comma + 1);
            String mime = "application/octet-stream";
            int semi = meta.indexOf(';');
            int colon = meta.indexOf(':');
            if (colon >= 0 && semi > colon) mime = meta.substring(colon + 1, semi);
            String name = guessName(mime);
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            saveBytes(name, mime, bytes);
        } catch (Exception e) {
            Log.e(TAG, "data url failed", e);
        }
    }

    private static String guessName(String mime) {
        if (mime.contains("pdf")) return "karta.pdf";
        if (mime.contains("word") || mime.contains("docx")) return "karta.docx";
        if (mime.contains("image/png")) return "karta.png";
        if (mime.contains("image")) return "karta.jpg";
        return "karta.bin";
    }

    private void saveBytes(String name, String mime, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("insert failed");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(bytes);
                }
            } else {
                File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scan.setData(Uri.fromFile(f));
                sendBroadcast(scan);
            }
            Toast.makeText(this, R.string.saved_to_downloads, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------- JS-мост ---------- */

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    class Bridge {
        @JavascriptInterface
        public void save(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String n = (name == null || name.isEmpty()) ? guessName(mime) : name;
                runOnUiThread(() -> saveBytes(n, mime, bytes));
            } catch (Exception e) {
                Log.e(TAG, "bridge save failed", e);
            }
        }
    }
}
