package ru.ruroad.karta;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Проверка обновлений через GitHub Releases (ruroad-android).
 * Сравнивает tag релиза (v1.2) с BuildConfig.VERSION_NAME, при новой версии
 * качает APK и предлагает установить.
 */
public final class UpdateChecker {

    private static final String REPO = "artemnazi-afk/ruroad-android";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String UA = "RuRoad-Karta/" + BuildConfig.VERSION_NAME;

    private UpdateChecker() {}

    public static void check(Activity act) {
        new Thread(() -> {
            try {
                JSONObject rel = fetchJson(API);
                String tag = rel.optString("tag_name", "").replaceAll("^[vV]", "");
                String apkUrl = null;
                JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a != null && a.optString("name", "").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (apkUrl == null || !isNewer(tag, BuildConfig.VERSION_NAME)) return;
                String finalTag = tag;
                String finalUrl = apkUrl;
                act.runOnUiThread(() ->
                        new AlertDialog.Builder(act)
                                .setTitle("Доступна версия " + finalTag)
                                .setMessage("У вас " + BuildConfig.VERSION_NAME + ". Обновить?")
                                .setPositiveButton("Обновить", (d, w) -> download(act, finalUrl))
                                .setNegativeButton("Позже", null)
                                .show());
            } catch (Exception e) {
                // нет сети/лимит API — молча пропускаем
            }
        }).start();
    }

    private static boolean isNewer(String remote, String local) {
        try {
            String[] r = remote.split("\\.");
            String[] l = local.split("\\.");
            for (int i = 0; i < Math.max(r.length, l.length); i++) {
                int rv = i < r.length ? Integer.parseInt(r[i].replaceAll("[^0-9]", "")) : 0;
                int lv = i < l.length ? Integer.parseInt(l[i].replaceAll("[^0-9]", "")) : 0;
                if (rv > lv) return true;
                if (rv < lv) return false;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static void download(Activity act, String url) {
        Toast.makeText(act, "Загрузка обновления…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File out = null;
            try {
                File dir = act.getExternalCacheDir();
                if (dir == null) dir = act.getCacheDir();
                out = new File(dir, "update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", UA);
                try (InputStream is = c.getInputStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                c.disconnect();
                Uri uri = ApkProvider.uriFor(act, out);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.runOnUiThread(() -> {
                    try {
                        act.startActivity(i);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(act, "Не найден установщик пакетов", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                if (out != null) out.delete();
                act.runOnUiThread(() ->
                        Toast.makeText(act, "Ошибка загрузки обновления", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static JSONObject fetchJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return new JSONObject(sb.toString());
    }
}
