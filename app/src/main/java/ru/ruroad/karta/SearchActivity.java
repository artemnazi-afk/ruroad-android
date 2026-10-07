package ru.ruroad.karta;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Поиск объектов ЕГРН.
 * Обычный режим — полноэкранный список (из приложения).
 * Режим из виджета (extra «dialog») — плавающее окно-карточка, визуально как сам виджет:
 * строка поиска → список результатов → карточка объекта; высота окна подстраивается под контент.
 * Состояние (mode/query/results/picked) общее с виджетом — лежит в SharedPreferences.
 */
public class SearchActivity extends Activity {

    static final String PREFS = "widget";
    static final String KEY_LABEL = "label";
    static final String KEY_ADDR = "addr";
    static final String KEY_LAT = "lat";
    static final String KEY_LON = "lon";
    static final String KEY_CAD = "cad";
    static final String EXTRA_DIALOG = "dialog";

    private EditText input;
    private ImageButton clearBtn;
    private ImageButton btn;
    private ListView list;          // обычный режим
    private final ArrayList<NspdClient.Result> results = new ArrayList<>();
    private BaseAdapter adapter;
    private volatile Thread searchThread;
    private boolean fromWidget;

    // --- виджет-режим: окно-карточка ---
    private View dChrome, dBack, dSearchRow, dProgressRow, dListWrap, dCard;
    private TextView dTitle, dStatus, dCad, dQuarter, dBadge;
    private TextView dAddr, dArea, dStatusV, dLandcat, dPerm;
    private View dAddrRow, dAreaRow, dStatusRow, dLandcatRow, dPermRow;
    private Button dMap;
    private ImageButton dRoute, dRouteHead;
    private ListView dList;
    private BaseAdapter dAdapter;
    private JSONArray wResults = new JSONArray();
    private String wMode = "search";
    private String wQuery = "";
    private int wPicked = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        fromWidget = getIntent().getBooleanExtra(EXTRA_DIALOG, false);
        if (fromWidget) setTheme(R.style.Theme_RuRoad_SearchDialog);
        super.onCreate(savedInstanceState);
        setContentView(fromWidget ? R.layout.activity_search_dialog
                                  : R.layout.activity_search);

        if (fromWidget) {
            // компактное плавающее окно сверху; высота WRAP — раскрывается под контент.
            // Окно живёт в отдельной задаче (NEW_TASK от лаунчера), translucent не даст
            // обоев — поэтому фоном рисуем обои рабочего стола с лёгким затемнением,
            // а тап мимо карточки закрывает окно.
            Window w = getWindow();
            if (w != null) {
                w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
            applyWindowSize();

            final android.view.View root = findViewById(android.R.id.content);
            // лёгкое затемнение поверх системных обоев (они рисуются темой windowShowWallpaper)
            root.setBackground(new ColorDrawable(0x40000000));
            root.setClickable(true);
            root.setOnClickListener(v -> finish());
        }

        input = findViewById(R.id.search_input);
        clearBtn = findViewById(R.id.search_clear);
        btn = findViewById(R.id.search_btn);
        list = findViewById(R.id.search_results);

        if (fromWidget) {
            initDialogViews();
        } else {
            adapter = new BaseAdapter() {
                @Override public int getCount() { return results.size(); }
                @Override public Object getItem(int i) { return results.get(i); }
                @Override public long getItemId(int i) { return i; }

                @Override
                public View getView(int i, View view, android.view.ViewGroup parent) {
                    if (view == null) {
                        view = getLayoutInflater().inflate(R.layout.item_result, parent, false);
                    }
                    NspdClient.Result r = results.get(i);
                    TextView label = view.findViewById(R.id.item_label);
                    TextView addr = view.findViewById(R.id.item_addr);
                    TextView route = view.findViewById(R.id.item_route);
                    label.setText(r.label.isEmpty() ? r.addr : r.label);
                    addr.setText(NspdClient.shortAddress(r.addr));
                    route.setOnClickListener(v -> openRoute(r));
                    return view;
                }
            };
            list.setAdapter(adapter);
            list.setOnItemClickListener((parent, view, position, id) -> pick(results.get(position)));
        }

        btn.setOnClickListener(v -> runSearch());
        input.setOnEditorActionListener((v, actionId, event) -> {
            runSearch();
            return true;
        });

        // крестик очистки: виден только когда поле не пустое
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        clearBtn.setOnClickListener(v -> {
            input.setText("");
            input.requestFocus();
        });

        if (fromWidget) {
            // восстанавливаем общее с виджетом состояние
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            wMode = p.getString(SearchWidget.K_MODE, "search");
            wQuery = p.getString(SearchWidget.K_QUERY, "");
            wResults = WidgetResultsService.readResults(this);
            wPicked = p.getInt(SearchWidget.K_PICKED, -1);
            if (!wQuery.isEmpty()) {
                input.setText(wQuery);
                input.setSelection(wQuery.length());
            }
            showState();
        }

        // автоподстановка запроса из виджета
        String preset = getIntent().getStringExtra("q");
        if (preset != null && !preset.isEmpty() && !fromWidget) {
            input.setText(preset);
            input.setSelection(preset.length());
        }
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    /* ---------------- виджет-режим: окно-карточка ---------------- */

    private void initDialogViews() {
        dChrome = findViewById(R.id.d_chrome);
        dBack = findViewById(R.id.d_back);
        dTitle = findViewById(R.id.d_title);
        dSearchRow = findViewById(R.id.d_search_row);
        dProgressRow = findViewById(R.id.d_progress_row);
        dStatus = findViewById(R.id.search_status);
        dListWrap = findViewById(R.id.d_list);
        dList = findViewById(R.id.d_list);
        dCard = findViewById(R.id.d_card);
        dCad = findViewById(R.id.d_cad);
        dQuarter = findViewById(R.id.d_quarter);
        dBadge = findViewById(R.id.d_badge);
        dAddrRow = findViewById(R.id.d_addr_l);
        dAddr = findViewById(R.id.d_addr);
        dAreaRow = findViewById(R.id.d_area_l);
        dArea = findViewById(R.id.d_area);
        dStatusRow = findViewById(R.id.d_status_l);
        dStatusV = findViewById(R.id.d_status);
        dLandcatRow = findViewById(R.id.d_landcat_l);
        dLandcat = findViewById(R.id.d_landcat);
        dPermRow = findViewById(R.id.d_perm_l);
        dPerm = findViewById(R.id.d_perm);
        dMap = findViewById(R.id.d_map);
        dRoute = findViewById(R.id.d_route);
        dRouteHead = findViewById(R.id.d_route_head);

        dAdapter = new BaseAdapter() {
            @Override public int getCount() { return wResults.length(); }
            @Override public Object getItem(int i) { return wResults.optJSONObject(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View view, android.view.ViewGroup parent) {
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.widget_item, parent, false);
                }
                JSONObject r = wResults.optJSONObject(i);
                if (r != null) {
                    TextView label = view.findViewById(R.id.wi_label);
                    TextView addr = view.findViewById(R.id.wi_addr);
                    TextView cat = view.findViewById(R.id.wi_cat);
                    label.setText(r.optString("cad").isEmpty() ? r.optString("label") : r.optString("cad"));
                    addr.setText(NspdClient.shortAddress(r.optString("addr")));
                    cat.setText(r.optString("cat"));
                }
                return view;
            }
        };
        dList.setAdapter(dAdapter);
        dList.setOnItemClickListener((parent, view, position, id) -> pickInDialog(position));

        dBack.setOnClickListener(v -> {
            wMode = wResults.length() > 0 ? "results" : "search";
            saveWidgetState();
            showState();
        });
        findViewById(R.id.d_reset).setOnClickListener(v -> {
            wMode = "search";
            wQuery = "";
            wResults = new JSONArray();
            wPicked = -1;
            input.setText("");
            saveWidgetState();
            showState();
        });
    }

    /* показать состояние окна: строка / список / карточка; высота под контент */
    private void showState() {
        boolean hasResults = wResults.length() > 0;
        boolean isCard = "card".equals(wMode) && wPicked >= 0 && wPicked < wResults.length();
        boolean dirty = !wQuery.isEmpty() || hasResults || isCard;

        dChrome.setVisibility(dirty ? View.VISIBLE : View.GONE);
        dBack.setVisibility(isCard ? View.VISIBLE : View.INVISIBLE);
        dTitle.setText(isCard ? getString(R.string.widget_card_title) : "");
        dSearchRow.setVisibility(isCard ? View.GONE : View.VISIBLE);
        dListWrap.setVisibility(!isCard && hasResults ? View.VISIBLE : View.GONE);
        dProgressRow.setVisibility(View.GONE);
        dCard.setVisibility(isCard ? View.VISIBLE : View.GONE);

        if (!isCard && hasResults) {
            // высота списка под число результатов: ~66dp на элемент, максимум 4 (дальше скролл)
            int itemH = (int) (getResources().getDisplayMetrics().density * 66);
            int h = Math.min(wResults.length(), 4) * itemH;
            ViewGroup.LayoutParams lp = dList.getLayoutParams();
            lp.height = h;
            dList.setLayoutParams(lp);
            dAdapter.notifyDataSetChanged();
        }
        if (isCard) {
            fillCard(wResults.optJSONObject(wPicked));
        }
        applyWindowSize();
    }

    private void applyWindowSize() {
        Window w = getWindow();
        if (w == null) return;
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.95f);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.06f);
        w.setAttributes(lp);
    }

    private void fillCard(JSONObject r) {
        if (r == null) return;
        String cad = r.optString("cad");
        if (cad.isEmpty()) cad = r.optString("label");
        double lat = r.optDouble("lat", Double.NaN);
        double lon = r.optDouble("lon", Double.NaN);
        boolean hasGeom = !Double.isNaN(lat) && !Double.isNaN(lon);

        dCad.setText(cad.isEmpty() ? r.optString("addr") : cad);
        dQuarter.setText(getString(R.string.quarter_prefix) + r.optString("quarter"));
        dQuarter.setVisibility(r.optString("quarter").isEmpty() ? View.GONE : View.VISIBLE);
        dBadge.setText(r.optString("cat"));
        dBadge.setVisibility(r.optString("cat").isEmpty() ? View.GONE : View.VISIBLE);

        setField(dAddrRow, dAddr, NspdClient.shortAddress(r.optString("addr")));
        String area = r.optString("area");
        if (!area.isEmpty() && area.matches("\\d+(\\.\\d+)?")) area = area + " м²";
        setField(dAreaRow, dArea, area);
        setField(dStatusRow, dStatusV, r.optString("status"));
        setField(dLandcatRow, dLandcat, r.optString("landCat"));
        setField(dPermRow, dPerm, r.optString("perm"));

        final String fCad = cad;
        final double fLat = lat, fLon = lon;
        dMap.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            if (!fCad.isEmpty()) {
                i.putExtra("q", fCad);
            } else if (hasGeom) {
                i.putExtra("lat", fLat);
                i.putExtra("lon", fLon);
            }
            startActivity(i);
        });

        dRoute.setVisibility(hasGeom ? View.VISIBLE : View.GONE);
        dRouteHead.setVisibility(hasGeom ? View.VISIBLE : View.GONE);
        if (hasGeom) {
            String label = fCad.isEmpty() ? r.optString("addr") : fCad;
            Uri uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                    fLat, fLon, fLat, fLon, Uri.encode(label)));
            View.OnClickListener go = v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                catch (Exception e) { Toast.makeText(this, R.string.no_app_for_route, Toast.LENGTH_SHORT).show(); }
            };
            dRoute.setOnClickListener(go);
            dRouteHead.setOnClickListener(go);
        }
    }

    private void setField(View label, TextView value, String v) {
        label.setVisibility(v.isEmpty() ? View.GONE : View.VISIBLE);
        value.setVisibility(v.isEmpty() ? View.GONE : View.VISIBLE);
        if (!v.isEmpty()) value.setText(v);
    }

    /* выбор результата в окне — карточка прямо здесь */
    private void pickInDialog(int position) {
        hideKeyboard();
        wPicked = position;
        wMode = "card";
        saveWidgetState();
        showState();
    }

    private void saveWidgetState() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(SearchWidget.K_MODE, wMode)
                .putString(SearchWidget.K_QUERY, wQuery)
                .putString(SearchWidget.K_RESULTS, wResults.toString())
                .putInt(SearchWidget.K_PICKED, wPicked)
                .apply();
        SearchWidget.updateAll(this);
    }

    @Override
    public void onBackPressed() {
        if (fromWidget && "card".equals(wMode)) {
            wMode = wResults.length() > 0 ? "results" : "search";
            saveWidgetState();
            showState();
            return;
        }
        super.onBackPressed();
    }

    /* ---------------- поиск ---------------- */

    private void runSearch() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        if (searchThread != null) searchThread.interrupt();
        if (fromWidget && dProgressRow != null) {
            dProgressRow.setVisibility(View.VISIBLE);
            dStatus.setText(R.string.searching);
        } else {
            TextView st = findViewById(R.id.search_status);
            if (st != null) st.setText(R.string.searching);
        }
        results.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        android.util.Log.i("RuRoad", "search start q='" + q + "' fromWidget=" + fromWidget);

        searchThread = new Thread(() -> {
            List<NspdClient.Result> found;
            String err = null;
            try {
                found = NspdClient.search(q);
            } catch (Exception e) {
                found = new ArrayList<>();
                err = e.getMessage();
            }
            final List<NspdClient.Result> out = found;
            final String error = err;
            android.util.Log.i("RuRoad", "search done: " + out.size() + " results, err=" + error);
            runOnUiThread(() -> {
                if (fromWidget) {
                    if (error != null) {
                        if (dProgressRow != null) dProgressRow.setVisibility(View.VISIBLE);
                        if (dStatus != null) dStatus.setText(getString(R.string.search_error) + ": " + error);
                        return;
                    }
                    // результаты — в общее состояние (виджет + окно), окно раскрывается списком
                    wQuery = q;
                    wMode = "results";
                    wPicked = -1;
                    wResults = toJson(out);
                    saveWidgetState();
                    showState();
                    return;
                }
                if (error != null) {
                    TextView st = findViewById(R.id.search_status);
                    if (st != null) st.setText(getString(R.string.search_error) + ": " + error);
                    return;
                }
                results.addAll(out);
                adapter.notifyDataSetChanged();
                TextView st = findViewById(R.id.search_status);
                if (st != null) st.setText(results.isEmpty() ? getString(R.string.search_empty)
                        : String.format(Locale.US, "%d", results.size()));
            });
        });
        searchThread.start();
    }

    private static JSONArray toJson(List<NspdClient.Result> found) {
        JSONArray arr = new JSONArray();
        for (NspdClient.Result r : found) {
            JSONObject o = new JSONObject();
            try {
                o.put("label", r.label);
                o.put("addr", r.addr);
                o.put("cat", r.cat);
                o.put("cad", r.cad);
                o.put("area", r.area);
                o.put("status", r.status);
                o.put("landCat", r.landCat);
                o.put("perm", r.perm);
                o.put("quarter", r.quarter);
                if (r.hasGeom) {
                    o.put("lat", r.lat);
                    o.put("lon", r.lon);
                }
            } catch (Exception ignored) {}
            arr.put(o);
        }
        return arr;
    }

    /* результаты → prefs для виджета */
    private void saveResultsForWidget(String q, List<NspdClient.Result> found) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(SearchWidget.K_MODE, "results")
                .putString(SearchWidget.K_QUERY, q)
                .putString(SearchWidget.K_RESULTS, toJson(found).toString())
                .putInt(SearchWidget.K_PICKED, -1)
                .apply();
        SearchWidget.updateAll(this);
    }

    private void pick(NspdClient.Result r) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        p.edit()
                .putString(KEY_LABEL, r.label)
                .putString(KEY_ADDR, r.addr)
                .putString(KEY_CAD, r.cad)
                .putString(KEY_LAT, Double.isNaN(r.lat) ? "" : String.valueOf(r.lat))
                .putString(KEY_LON, Double.isNaN(r.lon) ? "" : String.valueOf(r.lon))
                .apply();
        SearchWidget.updateAll(this);

        Intent i = new Intent(this, MainActivity.class);
        if (!r.cad.isEmpty()) {
            i.putExtra("q", r.cad);
        } else if (!r.label.isEmpty() && NspdClient.isCadQuery(r.label)) {
            i.putExtra("q", r.label);
        } else if (r.hasGeom) {
            i.putExtra("lat", r.lat);
            i.putExtra("lon", r.lon);
        } else {
            i.putExtra("q", r.addr);
        }
        startActivity(i);
        finish();
    }

    private void openRoute(NspdClient.Result r) {
        if (!r.hasGeom) {
            Toast.makeText(this, R.string.no_app_for_route, Toast.LENGTH_SHORT).show();
            return;
        }
        String label = r.label.isEmpty() ? r.addr : r.label;
        Uri uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)",
                r.lat, r.lon, r.lat, r.lon, Uri.encode(label)));
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_app_for_route, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }
}
