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
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    /* JS-патч: fetch() к НСПД сначала гоним через Java-мост (Cronet/Chromium-стек).
       При сбое моста пробуем нативный fetch WebView; если упал и он —
       показываем ИМЕННО ошибку моста (а не безликий TypeError), она диагностичнее. */
    private static final String NSPD_FETCH_PATCH =
            "(function(){" +
            "if(window.__nspdPatched)return;window.__nspdPatched=1;" +
            "var cbMap={},counter=0,lastErr='';" +
            "function nativeTry(cb){" +
            "try{return window.__nspdOfetch(cb.input,cb.init);}catch(e){throw e;}}" +
            "window.__ruroadCb=function(id,ok,body){" +
            "var cb=cbMap[id];if(!cb)return;delete cbMap[id];" +
            "if(ok){lastErr='';try{cb.resolve(new Response(body,{status:200,statusText:'OK',headers:{'Content-Type':'application/json'}}));}catch(e){cb.reject(e);}return;}" +
            "lastErr=body||'bridge failed';" +
            "Promise.resolve().then(function(){return nativeTry(cb);}).then(" +
            "function(r){cb.resolve(r);}," +
            "function(e){cb.reject(new Error(lastErr+(e&&e.message?' | native: '+e.message:'')));});};" +
            "function bridged(url,input,init){" +
            "return new Promise(function(resolve,reject){" +
            "var id='cb'+(++counter);cbMap[id]={resolve:resolve,reject:reject,input:input,init:init};" +
            "try{window.RuRoadApp.httpGet(url,id);}catch(e){delete cbMap[id];reject(e);return;}" +
            "setTimeout(function(){var cb=cbMap[id];if(!cb)return;delete cbMap[id];" +
            "Promise.resolve().then(function(){return nativeTry(cb);}).then(" +
            "function(r){cb.resolve(r);},function(e){cb.reject(new Error((lastErr||'bridge timeout')+(e&&e.message?' | native: '+e.message:'')));});},25000);" +
            "});}" +
            "window.__nspdOfetch=window.fetch.bind(window);" +
            "window.fetch=function(input,init){" +
            "var url=typeof input==='string'?input:((input&&input.url)||'');" +
            "if(url.indexOf('https://nspd.gov.ru/api/')===0)return bridged(url,input,init);" +
            "return window.__nspdOfetch(input,init);};" +
            "})()";

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
        // НСПД и другие защиты режут WebView-метки в UA ("; wv)", "Version/4.0") —
        // оставляем UA максимально похожим на обычный Chrome
        String ua = webView.getSettings().getUserAgentString();
        if (ua != null) {
            webView.getSettings().setUserAgentString(
                    ua.replace("; wv)", ")").replace(" Version/4.0", ""));
        }
        WebView.setWebContentsDebuggingEnabled(true);
        webView.setWebViewClient(new KartaWebViewClient());
        webView.setWebChromeClient(new KartaChromeClient());
        webView.addJavascriptInterface(new Bridge(), "RuRoadApp");

        // при обновлении версии чистим кэш WebView: в нём могли остаться
        // отбитые WAF-ом ответы (597) за прежних сессий
        android.content.SharedPreferences sp = getSharedPreferences("ruroad", MODE_PRIVATE);
        if (!BuildConfig.VERSION_NAME.equals(sp.getString("av", ""))) {
            webView.clearCache(true);
            sp.edit().putString("av", BuildConfig.VERSION_NAME).apply();
        }

        if (savedInstanceState == null) {
            loadFromIntent(getIntent());
        } else {
            webView.restoreState(savedInstanceState);
        }

        UpdateChecker.check(this);

        Toast.makeText(this, "RuRoad Кадастр v" + BuildConfig.VERSION_NAME, Toast.LENGTH_LONG).show();
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

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // после загрузки SPA внедряем перехват fetch() для НСПД
            view.evaluateJavascript(NSPD_FETCH_PATCH, null);
        }

        /* Провайдерский MITM подменяет сертификат НСПД; для НСПД принимаем
           любой сертификат (данные публичные), для остальных — штатно. */
        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
            String url = error.getUrl();
            if (url != null && url.contains("nspd.gov.ru")) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }

        /* WMS-тайлы НСПД идут через <img> в обход fetch-моста, и WebView может не
           отправлять Referer — WAF отвечает 597, слой не рисуется. Перехватываем
           все запросы к nspd.gov.ru/api/ и гоним их через Cronet с Referer. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if ("nspd.gov.ru".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null && uri.getPath().startsWith("/api/")) {
                try {
                    NspdResult r = httpGetBlocking(uri.toString());
                    if (r.code >= 200 && r.code < 300) {
                        String mime = r.contentType != null
                                ? r.contentType.split(";")[0].trim() : "application/octet-stream";
                        java.util.Map<String, String> headers = new java.util.HashMap<>();
                        headers.put("Access-Control-Allow-Origin", "*");
                        // encoding null: тайлы — бинарные (PNG), кодировка текста не нужна
                        return new WebResourceResponse(mime, null, 200, "OK", headers,
                                new java.io.ByteArrayInputStream(r.body));
                    }
                    reportNspdError(uri.getPath(), new Exception("tile HTTP " + r.code));
                } catch (Throwable t) {
                    Log.w(TAG, "intercept nspd failed: " + t);
                    reportNspdError(uri.getPath(), t instanceof Exception ? (Exception) t : new Exception(String.valueOf(t)));
                }
            }
            return null;
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

    /* Синхронный GET к НСПД (терпимый TLS + Referer) для shouldInterceptRequest.
       WebView вызывает перехватчик в фоновом потоке — блокировка допустима.
       Cronet НЕ используем: при провайдерском MITM он не доверяет подменному
       сертификату (ERR_CERT_AUTHORITY_INVALID), а весит 20 МБ. */
    private static class NspdResult {
        final int code;
        final byte[] body;
        final String contentType;
        NspdResult(int code, byte[] body, String contentType) {
            this.code = code; this.body = body; this.contentType = contentType;
        }
    }

    private NspdResult httpGetBlocking(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            HttpsURLConnection hs = (HttpsURLConnection) u.openConnection();
            if (NspdTls.SOCKET_FACTORY != null) {
                hs.setSSLSocketFactory(NspdTls.SOCKET_FACTORY);
                hs.setHostnameVerifier(NspdTls.VERIFIER);
            }
            hs.setConnectTimeout(20000);
            hs.setReadTimeout(20000);
            hs.setRequestProperty("User-Agent", DESKTOP_UA);
            hs.setRequestProperty("Accept", "*/*");
            hs.setRequestProperty("Referer", "https://ruroad.pik-sev.ru/");
            hs.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
            c = hs;
            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            byte[] body = readAllBytes(is);
            String mime = c.getContentType();
            c.disconnect();
            c = null;
            return new NspdResult(code, body, mime);
        } finally {
            if (c != null) c.disconnect();
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
        String msg = "НСПД v" + BuildConfig.VERSION_NAME + ": " + e.getClass().getSimpleName();
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

        /** JS-мост: ручная проверка обновлений APK (кнопка «обновить» в шапке). */
        @JavascriptInterface
        public void checkUpdate() {
            runOnUiThread(() -> UpdateChecker.check(MainActivity.this, true));
        }

        /** JS-мост: GET https://nspd.gov.ru/api/... в фоновом потоке, результат через __ruroadCb.
         *  HttpURLConnection с терпимым TLS (провайдерский MITM) и Referer (требование WAF). */
        @JavascriptInterface
        public void httpGet(final String url, final String cbId) {
            URL u;
            try {
                u = new URL(url);
                if (!"https".equalsIgnoreCase(u.getProtocol())
                        || !"nspd.gov.ru".equalsIgnoreCase(u.getHost())
                        || u.getPath() == null || !u.getPath().startsWith("/api/")) {
                    throw new Exception("bad url");
                }
            } catch (Exception e) {
                cb(cbId, false, "bad url");
                return;
            }
            httpGetPlain(u, cbId);
        }

        private void httpGetPlain(final URL u, final String cbId) {
            new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    HttpsURLConnection hs = (HttpsURLConnection) u.openConnection();
                    if (NspdTls.SOCKET_FACTORY != null) {
                        hs.setSSLSocketFactory(NspdTls.SOCKET_FACTORY);
                        hs.setHostnameVerifier(NspdTls.VERIFIER);
                    }
                    hs.setConnectTimeout(20000);
                    hs.setReadTimeout(20000);
                    hs.setRequestProperty("User-Agent", DESKTOP_UA);
                    hs.setRequestProperty("Accept", "*/*");
                    hs.setRequestProperty("Referer", "https://ruroad.pik-sev.ru/");
                    hs.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
                    c = hs;
                    int code = c.getResponseCode();
                    InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                    String body = new String(readAllBytes(is), java.nio.charset.StandardCharsets.UTF_8);
                    c.disconnect();
                    if (code < 200 || code >= 300) {
                        Log.w(TAG, "nspd bridge HTTP " + code);
                        reportNspdError(u.getPath(), new Exception("HTTP " + code));
                        cb(cbId, false, errText(new Exception("HTTP " + code)));
                        return;
                    }
                    cb(cbId, true, body);
                } catch (Exception e) {
                    Log.w(TAG, "nspd bridge failed: " + e);
                    if (u.getPath().contains("geoportal")) reportNspdError(u.getPath(), e);
                    if (c != null) c.disconnect();
                    cb(cbId, false, errText(e));
                }
            }).start();
        }

        private String errText(Exception e) {
            String err = "v" + BuildConfig.VERSION_NAME + " " + e.getClass().getSimpleName();
            String msg = e.getMessage();
            if (msg != null && !msg.isEmpty()) err += ": " + msg;
            return err;
        }

        private void cb(String cbId, boolean ok, String body) {
            String safeId = cbId == null ? "" : cbId.replaceAll("[^A-Za-z0-9]", "");
            String js = "window.__ruroadCb(" + org.json.JSONObject.quote(safeId) + ","
                    + ok + "," + org.json.JSONObject.quote(body) + ")";
            if (webView != null) {
                webView.post(() -> webView.evaluateJavascript(js, null));
            }
        }
    }
}
