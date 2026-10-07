package ru.ruroad.karta;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

/** Список результатов поиска внутри виджета (collection widget). */
public class WidgetResultsService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static JSONArray readResults(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(SearchActivity.PREFS, Context.MODE_PRIVATE);
        try {
            return new JSONArray(p.getString(SearchWidget.K_RESULTS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private JSONArray items = new JSONArray();

        Factory(Context c) { ctx = c; }

        @Override public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            items = readResults(ctx);
        }

        @Override public void onDestroy() {}
        @Override public int getCount() { return items.length(); }

        @Override
        public RemoteViews getViewAt(int i) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_item);
            JSONObject r = items.optJSONObject(i);
            if (r != null) {
                String label = r.optString("label");
                String addr = r.optString("addr");
                rv.setTextViewText(R.id.wi_label, label.isEmpty() ? addr : label);
                rv.setTextViewText(R.id.wi_addr, NspdClient.shortAddress(addr));
                rv.setTextViewText(R.id.wi_cat, r.optString("cat"));
                Intent fill = new Intent().putExtra(SearchWidget.EXTRA_IDX, i);
                rv.setOnClickFillInIntent(R.id.widget_item_root, fill);

                // кнопка маршрута у результата — сразу в навигатор
                double lat = r.optDouble("lat", Double.NaN);
                double lon = r.optDouble("lon", Double.NaN);
                boolean hasGeom = !Double.isNaN(lat) && !Double.isNaN(lon);
                if (hasGeom) {
                    String cad = r.optString("cad");
                    rv.setViewVisibility(R.id.wi_route, android.view.View.VISIBLE);
                    Intent fillRoute = new Intent()
                            .putExtra(SearchWidget.EXTRA_IDX, i)
                            .putExtra(SearchWidget.EXTRA_ROUTE, true)
                            .putExtra("lat", lat)
                            .putExtra("lon", lon)
                            .putExtra("label", cad.isEmpty() ? label : cad);
                    rv.setOnClickFillInIntent(R.id.wi_route, fillRoute);
                } else {
                    rv.setViewVisibility(R.id.wi_route, android.view.View.GONE);
                }
            }
            return rv;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return true; }
    }
}
