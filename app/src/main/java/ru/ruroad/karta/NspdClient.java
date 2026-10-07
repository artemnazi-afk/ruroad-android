package ru.ruroad.karta;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Поиск объектов ЕГРН на НСПД (nspd.gov.ru) — порт логики app/src/lib/addrSearch.ts.
 * Кадастровый номер: первый объект с геометрией.
 * Адрес: разбор запроса «[улица] [д|дом] N», фильтр, дедуп, приоритет ЗУ > Здания > прочее > Помещения.
 */
public final class NspdClient {

    public static final class Result {
        public String label = "";
        public String addr = "";
        public String cat = "";
        public String cad = "";
        public String area = "";     // specified_area / area / declared_area
        public String status = "";   // status / common_data_status
        public String landCat = "";  // land_record_category_type
        public String perm = "";     // permitted_use_established_by_document
        public String quarter = "";  // quarter_cad_number
        public double lat = Double.NaN;
        public double lon = Double.NaN;
        public boolean hasGeom = false;
    }

    private NspdClient() {}

    private static final String API =
            "https://nspd.gov.ru/api/geoportal/v2/search/geoportal?thematicSearchId=1&query=%s&CRS=EPSG:4326";

    /* ---------- запрос ---------- */

    /** HTTP 404 от НСПД — объект не найден (отдельно от сетевых ошибок). */
    public static class NotFound extends Exception {}

    public static List<Result> search(String query) throws Exception {        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return new ArrayList<>();
        // кадастровый номер с любыми разделителями → «91:04:006001:368» (логика сайта)
        String norm = normalizeCadQuery(q);
        if (norm != null) q = norm;
        String url = String.format(Locale.US, API, URLEncoder.encode(q, "UTF-8"));
        JSONObject resp = fetchJson(url);
        JSONArray feats = resp.optJSONArray("features");
        if (feats == null) {
            // некоторые ответы кладут features в data
            JSONObject data = resp.optJSONObject("data");
            if (data != null) feats = data.optJSONArray("features");
        }
        List<JSONObject> list = new ArrayList<>();
        if (feats != null) {
            for (int i = 0; i < feats.length(); i++) {
                JSONObject f = feats.optJSONObject(i);
                if (f != null) list.add(f);
            }
        }
        if (isCadQuery(q)) return cadResults(list);
        return addrResults(list, parseAddrQuery(q), true);
    }

    private static JSONObject fetchJson(String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                URL u = new URL(url);
                if ("https".equalsIgnoreCase(u.getProtocol())) {
                    javax.net.ssl.HttpsURLConnection hs = (javax.net.ssl.HttpsURLConnection) u.openConnection();
                    if (NspdTls.SOCKET_FACTORY != null) {
                        hs.setSSLSocketFactory(NspdTls.SOCKET_FACTORY);
                        hs.setHostnameVerifier(NspdTls.VERIFIER);
                    }
                    c = hs;
                } else {
                    c = (HttpURLConnection) u.openConnection();
                }
                c.setRequestMethod("GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36");
                c.setRequestProperty("Accept", "application/json");
                // WAF НСПД пропускает только запросы с Referer (без него — HTTP 597)
                c.setRequestProperty("Referer", "https://ruroad.pik-sev.ru/");
                c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
                int code = c.getResponseCode();
                InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String body = readAll(is);
                if (code >= 200 && code < 300) return new JSONObject(body);
                if (code == 404) throw new NotFound(); // не найдено — ретрай бессмысленен
                last = new Exception("HTTP " + code);
            } catch (Exception e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new Exception("request failed");
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /* ---------- кадастровый номер ---------- */

    public static boolean isCadQuery(String q) {
        return Pattern.compile("^\\d+\\s*:\\s*\\d+").matcher(q.trim()).find();
    }

    /**
     * Нормализация кадастрового номера с «неправильными» разделителями
     * (порт normalizeCadQuery из app/src/lib/addrSearch.ts):
     * «91^04^006001^368», «91 04 006001 368», «91-04-006001-368» → «91:04:006001:368».
     * Возвращает null, если строка не похожа на номер (буквы или одна группа цифр) —
     * тогда это адресный запрос и трогать его не нужно.
     */
    public static String normalizeCadQuery(String qRaw) {
        String t = qRaw == null ? "" : qRaw.trim();
        if (t.isEmpty()) return null;
        if (Pattern.compile("[a-zа-яё]", Pattern.CASE_INSENSITIVE).matcher(t).find()) return null;
        if (!Character.isDigit(t.charAt(0))) return null;
        String[] parts = t.split("[^0-9]+");
        List<String> groups = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) groups.add(p);
        }
        if (groups.size() < 2) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) sb.append(':');
            sb.append(groups.get(i));
        }
        return sb.toString();
    }

    private static List<Result> cadResults(List<JSONObject> feats) {
        List<Result> out = new ArrayList<>();
        for (JSONObject f : feats) {
            JSONObject props = f.optJSONObject("properties");
            if (props == null) continue;
            Result r = toResult(f, props, null);
            out.add(r);
            if (r.hasGeom) break; // первый с геометрией — дальше не нужно
        }
        return out;
    }

    /* ---------- адрес: порт addrSearch.ts ---------- */

    static final class AddrQuery {
        String houseNum = "";
        boolean explicitD = false;
        String streetPart = "";
    }

    /** «[часть улицы] [д|дом] N». «фед 21» — houseNum=21, explicitD=false; «фед д 21» — explicitD=true. */
    static AddrQuery parseAddrQuery(String qRaw) {
        AddrQuery aq = new AddrQuery();
        String q = qRaw.trim();
        String[] toks = q.split("\\s+");
        aq.streetPart = q;
        if (toks.length < 2) return aq;
        String last = toks[toks.length - 1];
        Matcher mNum = Pattern.compile("^(?:(?:д|дом)\\.?)?(\\d+)([А-ЯA-Z])?$", Pattern.CASE_INSENSITIVE).matcher(last);
        if (!mNum.matches()) return aq;
        aq.houseNum = mNum.group(1);
        String prevTok = toks.length >= 2 ? toks[toks.length - 2] : "";
        aq.explicitD = Pattern.compile("^(?:д|дом)\\.?$", Pattern.CASE_INSENSITIVE).matcher(prevTok).matches()
                || Pattern.compile("^(?:д|дом)\\.?\\d", Pattern.CASE_INSENSITIVE).matcher(last).find();
        StringBuilder st = new StringBuilder();
        for (int i = 0; i < toks.length - 1; i++) {
            if (i > 0) st.append(' ');
            st.append(toks[i]);
        }
        aq.streetPart = st.toString().replaceAll("\\s+(?:д|дом)\\.?$", "").trim();
        if (Pattern.compile("^(?:д|дом)\\.?$", Pattern.CASE_INSENSITIVE).matcher(aq.streetPart).matches()) {
            aq.explicitD = true;
            aq.streetPart = "";
        }
        return aq;
    }

    /** bare «21» — любой компонент-номер, содержащий 21; «д 21» — только с явным словом «дом». */
    interface HouseMatcher { boolean match(String addr); }

    static HouseMatcher makeHouseMatch(AddrQuery aq) {
        if (aq.houseNum.isEmpty()) return null;
        final String num = aq.houseNum;
        final Pattern numRE = Pattern.compile("^" + num + "[а-яa-z]*$", Pattern.CASE_INSENSITIVE);
        final Pattern tailRE = Pattern.compile("(?:^|[^0-9])" + num + "[а-яa-z]*$", Pattern.CASE_INSENSITIVE);
        final boolean explicitD = aq.explicitD;
        return addr -> {
            for (String comp : addr.split(",")) {
                String norm = comp.trim().toLowerCase(Locale.ROOT).replaceAll("[.\\s\\-\\/№]+", "");
                if (norm.isEmpty()) continue;
                if (norm.startsWith("кв") || norm.startsWith("комн") || norm.startsWith("помещ") || norm.startsWith("офис")) continue;
                if (explicitD) {
                    Matcher m = Pattern.compile("^д(ом|о)?(.*)$").matcher(norm);
                    if (m.matches() && numRE.matcher(m.group(2)).matches()) return true;
                } else {
                    String rest = norm.replaceFirst("^(д(ом|о)?|зу|уч(асток)?)", "");
                    if (numRE.matcher(rest).matches() || tailRE.matcher(norm).find()) return true;
                }
            }
            return false;
        };
    }

    private static final Pattern STREET_RE = Pattern.compile(
            "(?:^|,\\s*)(?:улица|ул|переулок|пер|тупик|туп|шоссе|ш|проспект|просп|проезд|пр|бульвар|бул|набережная|наб|площадь|пл|аллея|микрорайон|мкр)\\.?\\s*([^,]+)",
            Pattern.CASE_INSENSITIVE);

    private static List<Result> addrResults(List<JSONObject> feats, AddrQuery aq, boolean withPrem) {
        HouseMatcher houseMatch = makeHouseMatch(aq);
        String st = aq.streetPart.toLowerCase(Locale.ROOT);
        Map<String, Result> map = new LinkedHashMap<>();
        for (JSONObject f : feats) {
            JSONObject props = f.optJSONObject("properties");
            if (props == null) continue;
            JSONObject o = props.optJSONObject("options");
            String addr = o != null ? o.optString("readable_address", "") : "";
            if (addr.isEmpty()) addr = props.optString("label", "");
            if (addr.isEmpty()) continue;
            String cat = props.optString("categoryName", "");
            if (!withPrem && cat.toLowerCase(Locale.ROOT).contains("помещени")) continue;
            if (houseMatch != null && !houseMatch.match(addr)) continue;
            if (st.length() >= 2) {
                String low = addr.toLowerCase(Locale.ROOT);
                if (!low.contains(st)) {
                    Matcher mSt = STREET_RE.matcher(addr);
                    String name = mSt.find() ? mSt.group(1).trim().toLowerCase(Locale.ROOT) : "";
                    boolean streetOk = !name.isEmpty()
                            && (name.startsWith(st) || startsWithAnyWord(name, st));
                    if (!streetOk) continue;
                }
            }
            JSONObject r2 = new JSONObject();
            try { r2.put("properties", props); r2.put("geometry", f.optJSONObject("geometry")); } catch (Exception ignored) {}
            Result r = toResult(r2, props, f.optJSONObject("geometry"));
            String key = !r.label.isEmpty() ? "cad:" + r.label : "addr:" + r.addr;
            if (!map.containsKey(key)) map.put(key, r);
        }
        List<Result> out = new ArrayList<>(map.values());
        out.sort((a, b) -> Integer.compare(prio(a), prio(b)));
        return out;
    }

    private static boolean startsWithAnyWord(String name, String st) {
        for (String w : name.split("\\s+")) {
            if (w.startsWith(st)) return true;
        }
        return false;
    }

    private static int prio(Result it) {
        int base = it.label.isEmpty() ? 10 : 0;
        String c = it.cat.toLowerCase(Locale.ROOT);
        if (c.contains("земельн")) base += 0;
        else if (c.contains("здани")) base += 2;
        else if (c.contains("помещени")) base += 6;
        else base += 4;
        return base;
    }

    /* ---------- преобразование ---------- */

    private static Result toResult(JSONObject f, JSONObject props, JSONObject geomOpt) {
        Result r = new Result();
        JSONObject o = props.optJSONObject("options");
        r.label = props.optString("label", "");
        if (r.label.isEmpty() && o != null) r.label = o.optString("cad_number", "");
        r.addr = o != null ? o.optString("readable_address", "") : "";
        if (r.addr.isEmpty()) r.addr = r.label;
        r.cat = props.optString("categoryName", "");
        r.cad = o != null ? o.optString("cad_number", "") : "";
        if (o != null) {
            r.area = firstStr(o, "specified_area", "area", "declared_area");
            r.status = firstStr(o, "status", "common_data_status");
            r.landCat = o.optString("land_record_category_type", "");
            r.perm = o.optString("permitted_use_established_by_document", "");
            r.quarter = o.optString("quarter_cad_number", "");
        }
        if (r.quarter.isEmpty() && !r.cad.isEmpty()) {
            String[] g = r.cad.split(":");
            if (g.length >= 3) r.quarter = g[0] + ":" + g[1] + ":" + g[2];
        }
        JSONObject geom = geomOpt != null ? geomOpt : f.optJSONObject("geometry");
        double[] ll = geomCenterLatLng(geom);
        if (ll != null) {
            r.lat = ll[0];
            r.lon = ll[1];
            r.hasGeom = true;
        }
        return r;
    }

    private static String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v != null && v != JSONObject.NULL) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    /* ---------- геометрия ---------- */

    /** центроид в [lat, lon]; координаты НСПД обычно в 3857 */
    static double[] geomCenterLatLng(JSONObject geom) {
        if (geom == null) return null;
        Object coords = geom.opt("coordinates");
        if (coords == null) return null;
        List<double[]> pts = new ArrayList<>();
        walk(coords, pts);
        if (pts.isEmpty()) return null;
        double x = 0, y = 0;
        for (double[] p : pts) { x += p[0]; y += p[1]; }
        x /= pts.size();
        y /= pts.size();
        if (Math.abs(x) > 1000 || Math.abs(y) > 1000) return mercToLatLon(x, y);
        return new double[]{y, x};
    }

    private static void walk(Object c, List<double[]> pts) {
        if (c instanceof JSONArray) {
            JSONArray arr = (JSONArray) c;
            if (arr.length() >= 2 && arr.opt(0) instanceof Number && arr.opt(1) instanceof Number) {
                pts.add(new double[]{arr.optDouble(0), arr.optDouble(1)});
            } else {
                for (int i = 0; i < arr.length(); i++) walk(arr.opt(i), pts);
            }
        }
    }

    private static double[] mercToLatLon(double x, double y) {
        double R = 6378137;
        double lon = (x / R) * (180 / Math.PI);
        double lat = (2 * Math.atan(Math.exp(y / R)) - Math.PI / 2) * (180 / Math.PI);
        return new double[]{lat, lon};
    }

    /* ---------- короткий адрес (без страны/Севастополя/округов) ---------- */

    public static String shortAddress(String addr) {
        if (addr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : addr.split(",")) {
            String x = part.trim();
            if (x.isEmpty()) continue;
            if (Pattern.compile("^российская федерация", Pattern.CASE_INSENSITIVE).matcher(x).find()) continue;
            if (Pattern.compile("^г\\.?\\s*севастополь", Pattern.CASE_INSENSITIVE).matcher(x).find()) continue;
            if (Pattern.compile("^(вн\\.тер\\.г\\.|внутригородн|муниципальн|нахимовский|балаклавский|гагаринский|ленинский|корабел)?.*(округ|тер\\. г|территори)",
                    Pattern.CASE_INSENSITIVE).matcher(x).matches()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(x);
        }
        return sb.toString();
    }
}
