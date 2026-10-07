package ru.ruroad.karta;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.util.Locale;

/**
 * Виджет 4×2: поле поиска → плавающее окно поиска; после выбора результата — карточка
 * с кнопками «На карте» и «Маршрут».
 */
public class SearchWidget extends AppWidgetProvider {

    static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        ComponentName cn = new ComponentName(ctx, SearchWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids.length == 0) return;
        new SearchWidget().onUpdate(ctx, mgr, ids);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        SharedPreferences p = ctx.getSharedPreferences(SearchActivity.PREFS, Context.MODE_PRIVATE);
        String label = p.getString(SearchActivity.KEY_LABEL, "");
        String addr = p.getString(SearchActivity.KEY_ADDR, "");
        String cad = p.getString(SearchActivity.KEY_CAD, "");
        String latS = p.getString(SearchActivity.KEY_LAT, "");
        String lonS = p.getString(SearchActivity.KEY_LON, "");
        boolean hasResult = !label.isEmpty() && !latS.isEmpty() && !lonS.isEmpty();
        double lat = hasResult ? Double.parseDouble(latS) : Double.NaN;
        double lon = hasResult ? Double.parseDouble(lonS) : Double.NaN;

        for (int id : ids) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_search);

            // поиск из виджета — плавающее окно, не на весь экран
            Intent search = new Intent(ctx, SearchActivity.class);
            search.putExtra(SearchActivity.EXTRA_DIALOG, true);
            PendingIntent searchPi = PendingIntent.getActivity(ctx, 0, search,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            rv.setOnClickPendingIntent(R.id.widget_field, searchPi);
            rv.setOnClickPendingIntent(R.id.widget_search_btn, searchPi);

            if (hasResult) {
                rv.setViewVisibility(R.id.widget_search_mode, android.view.View.GONE);
                rv.setViewVisibility(R.id.widget_result_mode, android.view.View.VISIBLE);
                rv.setTextViewText(R.id.widget_result_label, label);
                rv.setTextViewText(R.id.widget_result_addr, NspdClient.shortAddress(addr));

                Intent map = new Intent(ctx, MainActivity.class);
                if (!cad.isEmpty()) {
                    map.putExtra("q", cad);
                } else {
                    map.putExtra("lat", lat);
                    map.putExtra("lon", lon);
                }
                PendingIntent mapPi = PendingIntent.getActivity(ctx, 1, map,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                rv.setOnClickPendingIntent(R.id.widget_map_btn, mapPi);

                String navLabel = label.isEmpty() ? addr : label;
                Uri uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                        lat, lon, lat, lon, Uri.encode(navLabel)));
                Intent route = new Intent(Intent.ACTION_VIEW, uri);
                PendingIntent routePi = PendingIntent.getActivity(ctx, 2, route,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                rv.setOnClickPendingIntent(R.id.widget_route_btn, routePi);
            } else {
                rv.setViewVisibility(R.id.widget_search_mode, android.view.View.VISIBLE);
                rv.setViewVisibility(R.id.widget_result_mode, android.view.View.GONE);
            }

            mgr.updateAppWidget(id, rv);
        }
    }
}
