package ru.ruroad.karta;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Плавающее окно ввода поиска (из виджета — extra «dialog»).
 * Виджет не может принимать клавиатуру (ограничение RemoteViews), поэтому ввод здесь;
 * результаты сохраняются в prefs и показываются в виджете, окно закрывается.
 * Без виджета (из приложения) — обычный экран поиска со списком.
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
    private ProgressBar progress;
    private TextView status;
    private ListView list;
    private final ArrayList<NspdClient.Result> results = new ArrayList<>();
    private BaseAdapter adapter;
    private volatile Thread searchThread;
    private boolean fromWidget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        fromWidget = getIntent().getBooleanExtra(EXTRA_DIALOG, false);
        if (fromWidget) setTheme(R.style.Theme_RuRoad_SearchDialog);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        if (fromWidget) {
            // компактное плавающее поле ввода сверху, вплотную к виджету
            Window w = getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.06f);
                w.setAttributes(lp);
            }
        }

        input = findViewById(R.id.search_input);
        clearBtn = findViewById(R.id.search_clear);
        btn = findViewById(R.id.search_btn);
        progress = findViewById(R.id.search_progress);
        status = findViewById(R.id.search_status);
        list = findViewById(R.id.search_results);

        if (fromWidget) {
            // из виджета: только поле ввода, результаты уходят в виджет
            list.setVisibility(View.GONE);
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

        // автоподстановка запроса из виджета
        String preset = getIntent().getStringExtra("q");
        if (preset != null && !preset.isEmpty()) {
            input.setText(preset);
            input.setSelection(preset.length());
        }
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void runSearch() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        if (searchThread != null) searchThread.interrupt();
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.searching);
        results.clear();
        if (adapter != null) adapter.notifyDataSetChanged();

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
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (error != null) {
                    status.setText(getString(R.string.search_error) + ": " + error);
                    return;
                }
                if (fromWidget) {
                    // результаты — в виджет, окно закрываем
                    saveResultsForWidget(q, out);
                    finish();
                    return;
                }
                results.addAll(out);
                adapter.notifyDataSetChanged();
                status.setText(results.isEmpty() ? getString(R.string.search_empty)
                        : String.format(Locale.US, "%d", results.size()));
            });
        });
        searchThread.start();
    }

    /* результаты → prefs для виджета */
    private void saveResultsForWidget(String q, List<NspdClient.Result> found) {
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(SearchWidget.K_MODE, "results")
                .putString(SearchWidget.K_QUERY, q)
                .putString(SearchWidget.K_RESULTS, arr.toString())
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
