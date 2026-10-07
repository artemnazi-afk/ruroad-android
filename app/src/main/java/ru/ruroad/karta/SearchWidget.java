package ru.ruroad.karta;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Виджет: ввод — плавающее окно (RemoteViews не принимает клавиатуру),
 * результаты и карточка объекта — в самом виджете.
 * Строка навигации: назад (из карточки) / сброс (крестик).
 */
public class SearchWidget extends AppWidgetProvider {

    static final String ACTION_PICK  = "ru.ruroad.karta.WIDGET_PICK";
    static final String ACTION_BACK  = "ru.ruroad.karta.WIDGET_BACK";
    static final String ACTION_RESET = "ru.ruroad.karta.WIDGET_RESET";
    static final String EXTRA_IDX = "idx";
    static final String EXTRA_ROUTE = "route";

    static final String K_MODE = "mode";       // search | results | card
    static final String K_QUERY = "query";
    static final String K_RESULTS = "results"; // JSON array
    static final String K_PICKED = "picked";

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName cn = new ComponentName(ctx, SearchWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids.length == 0) return;
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
        new SearchWidget().onUpdate(ctx, mgr, ids);
    }

    @Override
    public void onReceive(Context ctx, Intent i) {
        super.onReceive(ctx, i);
        String a = i.getAction();
        if (a == null) return;
        SharedPreferences p = ctx.getSharedPreferences(SearchActivity.PREFS, Context.MODE_PRIVATE);
        boolean changed = true;
        switch (a) {
            case ACTION_PICK:
                if (i.getBooleanExtra(EXTRA_ROUTE, false)) {
                    // кнопка маршрута у результата — сразу навигатор, состояние не меняем
                    openRoute(ctx, i);
                    return;
                }
                p.edit().putInt(K_PICKED, i.getIntExtra(EXTRA_IDX, -1))
                        .putString(K_MODE, "card").apply();
                break;
            case ACTION_BACK:
                p.edit().putString(K_MODE,
                        WidgetResultsService.readResults(ctx).length() > 0 ? "results" : "search").apply();
                break;
            case ACTION_RESET:
                p.edit().putString(K_MODE, "search").putString(K_QUERY, "")
                        .putString(K_RESULTS, "[]").putInt(K_PICKED, -1).apply();
                break;
            default:
                changed = false;
        }
        if (changed) updateAll(ctx);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        SharedPreferences p = ctx.getSharedPreferences(SearchActivity.PREFS, Context.MODE_PRIVATE);
        String query = p.getString(K_QUERY, "");
        android.util.Log.i("RuRoad", "widget onUpdate: query='" + query + "' widgets=" + ids.length);

        for (int id : ids) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_search);

            // виджет — только строка ввода 4×1: результаты и карточка живут в плавающем окне
            // (динамическая высота виджета невозможна — RemoteViews не даёт менять размер под контент)
            Intent search = new Intent(ctx, SearchActivity.class);
            search.putExtra(SearchActivity.EXTRA_DIALOG, true);
            search.putExtra("q", query);
            PendingIntent searchPi = PendingIntent.getActivity(ctx, 0, search,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            rv.setOnClickPendingIntent(R.id.widget_field, searchPi);
            rv.setOnClickPendingIntent(R.id.widget_find, searchPi);

            // размер текста под ширину поля: autosize в RemoteViews не работает,
            // подбираем программно — от 22sp вниз, пока текст не влезет
            String text = query.isEmpty() ? ctx.getString(R.string.widget_hint) : query;
            android.os.Bundle opts = mgr.getAppWidgetOptions(id);
            float fieldDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) - 88f;
            float size = 22f;
            while (size > 11f && text.length() * size * 0.58f > fieldDp) size -= 1f;
            rv.setTextViewTextSize(R.id.widget_field, android.util.TypedValue.COMPLEX_UNIT_SP, size);
            rv.setTextViewText(R.id.widget_field, text);
            rv.setTextColor(R.id.widget_field, query.isEmpty() ? 0xFF8B93A3 : 0xFFE8EAF0);

            mgr.updateAppWidget(id, rv);
        }
    }

    private static PendingIntent broadcast(Context ctx, int rc, String action) {
        Intent i = new Intent(ctx, SearchWidget.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, rc, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /* маршрут из списка результата — сразу в навигатор */
    private static void openRoute(Context ctx, Intent i) {
        double lat = i.getDoubleExtra("lat", Double.NaN);
        double lon = i.getDoubleExtra("lon", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;
        String label = i.getStringExtra("label");
        if (label == null) label = "";
        Uri uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                lat, lon, lat, lon, Uri.encode(label)));
        Intent route = new Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(route); } catch (Exception ignored) {}
    }

    /* карточка объекта — поля как в карточке на карте */
    private static void fillCard(Context ctx, RemoteViews rv, JSONObject r) {
        String cad = r.optString("cad");
        if (cad.isEmpty()) cad = r.optString("label");
        double lat = r.optDouble("lat", Double.NaN);
        double lon = r.optDouble("lon", Double.NaN);
        boolean hasGeom = !Double.isNaN(lat) && !Double.isNaN(lon);

        rv.setTextViewText(R.id.wc_cad, cad.isEmpty() ? r.optString("addr") : cad);
        rv.setTextViewText(R.id.wc_quarter,
                ctx.getString(R.string.quarter_prefix) + r.optString("quarter"));
        rv.setViewVisibility(R.id.wc_quarter,
                r.optString("quarter").isEmpty() ? View.GONE : View.VISIBLE);
        rv.setTextViewText(R.id.wc_badge, r.optString("cat"));
        rv.setViewVisibility(R.id.wc_badge,
                r.optString("cat").isEmpty() ? View.GONE : View.VISIBLE);

        setField(rv, R.id.wc_row_addr, R.id.wc_addr, NspdClient.shortAddress(r.optString("addr")));
        String area = r.optString("area");
        if (!area.isEmpty() && area.matches("\\d+(\\.\\d+)?")) area = area + " м²";
        setField(rv, R.id.wc_row_area, R.id.wc_area, area);
        setField(rv, R.id.wc_row_status, R.id.wc_status, r.optString("status"));
        setField(rv, R.id.wc_row_landcat, R.id.wc_landcat, r.optString("landCat"));
        setField(rv, R.id.wc_row_perm, R.id.wc_perm, r.optString("perm"));

        // «На карте»
        Intent map = new Intent(ctx, MainActivity.class);
        if (!cad.isEmpty()) {
            map.putExtra("q", cad);
        } else if (hasGeom) {
            map.putExtra("lat", lat);
            map.putExtra("lon", lon);
        }
        PendingIntent mapPi = PendingIntent.getActivity(ctx, 1, map,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.wc_map, mapPi);

        // маршрут (значок как в карте)
        rv.setViewVisibility(R.id.wc_route, hasGeom ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.wc_route_head, hasGeom ? View.VISIBLE : View.GONE);
        if (hasGeom) {
            String label = cad.isEmpty() ? r.optString("addr") : cad;
            Uri uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                    lat, lon, lat, lon, Uri.encode(label)));
            Intent route = new Intent(Intent.ACTION_VIEW, uri);
            PendingIntent routePi = PendingIntent.getActivity(ctx, 2, route,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            rv.setOnClickPendingIntent(R.id.wc_route, routePi);
            rv.setOnClickPendingIntent(R.id.wc_route_head, routePi);
        }
    }

    private static void setField(RemoteViews rv, int rowId, int valId, String v) {
        rv.setViewVisibility(rowId, v.isEmpty() ? View.GONE : View.VISIBLE);
        if (!v.isEmpty()) rv.setTextViewText(valId, v);
    }
}
