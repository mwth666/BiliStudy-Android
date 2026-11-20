package com.bilistudy.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * StatsStore —— 本地统计存储。
 * 数据结构与浏览器插件保持一致（bili_daily / bili_lessons / bili_checkins / bili_streak / bili_badges），
 * 这样导出的 JSON 可以直接被插件识别，插件导出的 JSON 也能直接导入合并。
 */
public class StatsStore {
    private static final String PREF = "bili_study_stats";
    private static final String ROOT = "data";

    private final Context ctx;
    private JSONObject data;

    public StatsStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    private synchronized void load() {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = sp.getString(ROOT, "");
        try {
            data = raw.length() > 0 ? new JSONObject(raw) : new JSONObject();
        } catch (JSONException e) {
            data = new JSONObject();
        }
        ensure();
    }

    private synchronized void save() {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(ROOT, data.toString()).apply();
    }

    private void ensure() {
        String[] objs = {"bili_daily", "bili_lessons", "bili_checkins", "bili_badges", "imports"};
        for (String k : objs) {
            if (!data.has(k) || data.isNull(k)) {
                try { data.put(k, new JSONObject()); } catch (JSONException ignored) {}
            }
        }
        if (!data.has("bili_streak") || data.isNull("bili_streak")) {
            try {
                data.put("bili_streak", new JSONObject("{\"current\":0,\"longest\":0,\"lastDate\":\"\"}"));
            } catch (JSONException ignored) {}
        }
    }

    /* ============ 日期工具 ============ */
    public static String dayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    public static String dayKey(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(ts));
    }

    private static String shiftDay(String key, int delta) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Calendar c = Calendar.getInstance();
            c.setTime(f.parse(key));
            c.add(Calendar.DAY_OF_MONTH, delta);
            return f.format(c.getTime());
        } catch (Exception e) {
            return key;
        }
    }

    private static List<String> lastDays(int n) {
        List<String> keys = new ArrayList<String>();
        String today = dayKey();
        for (int i = n - 1; i >= 0; i--) keys.add(shiftDay(today, -i));
        return keys;
    }

    /* ============ 上报一次观看 ============ */
    /** 按内容进度上报：seconds 已含倍速；maxPos 为播放位置（秒），duration 为视频总长（秒）。 */
    public synchronized void report(String lessonKey, String title, double seconds,
                                    double maxPos, double duration, long nowTs) {
        ensure();
        try {
            String day = dayKey(nowTs);

            JSONObject daily = data.getJSONObject("bili_daily");
            JSONObject d = daily.optJSONObject(day);
            if (d == null) { d = new JSONObject(); d.put("lessonKeys", new JSONArray()); }
            d.put("seconds", round1(d.optDouble("seconds", 0) + seconds));
            d.put("updatedAt", nowTs);
            JSONArray lks = d.optJSONArray("lessonKeys");
            if (lks == null) { lks = new JSONArray(); d.put("lessonKeys", lks); }
            if (!contains(lks, lessonKey)) lks.put(lessonKey);
            daily.put(day, d);

            JSONObject lessons = data.getJSONObject("bili_lessons");
            JSONObject l = lessons.optJSONObject(lessonKey);
            if (l == null) {
                l = new JSONObject();
                l.put("title", title == null ? "" : title);
                l.put("courseTitle", title == null ? "" : title);
                l.put("duration", duration);
                l.put("watchedSeconds", 0);
                l.put("maxPosition", 0);
                l.put("completed", false);
                l.put("completedAt", 0);
            }
            l.put("watchedSeconds", round1(l.optDouble("watchedSeconds", 0) + seconds));
            l.put("maxPosition", Math.max(l.optDouble("maxPosition", 0), maxPos));
            if (duration > l.optDouble("duration", 0)) l.put("duration", duration);
            if (title != null && title.length() > 0) l.put("title", title);
            l.put("lastWatchedAt", nowTs);
            boolean done = duration > 0 && l.getDouble("maxPosition") / duration >= 0.95;
            if (done && !l.optBoolean("completed", false)) {
                l.put("completed", true);
                l.put("completedAt", nowTs);
            }
            lessons.put(lessonKey, l);

            JSONObject checkins = data.getJSONObject("bili_checkins");
            double todaySec = d.optDouble("seconds", 0);
            if (todaySec >= 60 && !checkins.optBoolean(day, false)) checkins.put(day, true);

            recalcStreak();
            recalcBadges();
            save();
        } catch (JSONException e) {
            // 存储损坏时不要影响采集
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static boolean contains(JSONArray arr, String s) {
        for (int i = 0; i < arr.length(); i++) {
            if (s.equals(arr.optString(i, ""))) return true;
        }
        return false;
    }

    /* ============ 连续天数 ============ */
    private synchronized void recalcStreak() {
        try {
            JSONObject checkins = data.getJSONObject("bili_checkins");
            String anchor = dayKey();
            if (!checkins.optBoolean(anchor, false)) anchor = shiftDay(anchor, -1);
            int cur = 0;
            String k = anchor;
            while (checkins.optBoolean(k, false)) {
                cur++;
                k = shiftDay(k, -1);
            }
            int longest = checkins.optInt("bili_longest", 0);
            if (cur > longest) longest = cur;
            JSONObject streak = data.optJSONObject("bili_streak");
            if (streak == null) { streak = new JSONObject(); data.put("bili_streak", streak); }
            streak.put("current", cur);
            streak.put("longest", longest);
            streak.put("lastDate", anchor);
        } catch (JSONException ignored) {}
    }

    public synchronized JSONObject streak() {
        ensure();
        return data.optJSONObject("bili_streak");
    }

    /* ============ 徽章 ============ */
    private synchronized void recalcBadges() {
        try {
            JSONObject badges = data.getJSONObject("bili_badges");
            JSONObject streak = data.optJSONObject("bili_streak");
            if (streak != null && streak.optInt("current", 0) >= 7 && !badges.has("streak7")) {
                badges.put("streak7", System.currentTimeMillis());
            }
            JSONObject daily = data.getJSONObject("bili_daily");
            double today = daily.optJSONObject(dayKey()) == null
                    ? 0 : daily.optJSONObject(dayKey()).optDouble("seconds", 0);
            if (today >= 7200 && !badges.has("day2h")) badges.put("day2h", System.currentTimeMillis());
            double total = 0;
            Iterator<String> it = daily.keys();
            while (it.hasNext()) total += daily.optJSONObject(it.next()).optDouble("seconds", 0);
            if (total >= 36000 && !badges.has("total10h")) badges.put("total10h", System.currentTimeMillis());
            int done = 0;
            JSONObject lessons = data.getJSONObject("bili_lessons");
            Iterator<String> li = lessons.keys();
            while (li.hasNext()) if (lessons.optJSONObject(li.next()).optBoolean("completed", false)) done++;
            if (done >= 30 && !badges.has("parts30")) badges.put("parts30", System.currentTimeMillis());
        } catch (JSONException ignored) {}
    }

    /* ============ 查询 ============ */
    public synchronized double secondsOf(String day) {
        ensure();
        JSONObject d = data.optJSONObject("bili_daily").optJSONObject(day);
        return d == null ? 0 : d.optDouble("seconds", 0);
    }

    public synchronized double periodSeconds(String period) {
        ensure();
        double sum = 0;
        List<String> keys;
        if ("day".equals(period)) keys = lastDays(1);
        else if ("week".equals(period)) keys = lastDays(7);
        else keys = lastDays(30);
        for (String k : keys) sum += secondsOf(k);
        return sum;
    }

    public synchronized JSONObject courseProgress() {
        ensure();
        JSONObject out = new JSONObject();
        try {
            JSONObject lessons = data.getJSONObject("bili_lessons");
            Iterator<String> it = lessons.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject l = lessons.optJSONObject(k);
                if (l == null) continue;
                String title = l.optString("title", k);
                JSONObject entry = out.optJSONObject(title);
                if (entry == null) {
                    entry = new JSONObject();
                    entry.put("total", 0);
                    entry.put("done", 0);
                    out.put(title, entry);
                }
                entry.put("total", entry.optInt("total") + 1);
                if (l.optBoolean("completed", false)) entry.put("done", entry.optInt("done") + 1);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    public synchronized JSONObject badges() {
        ensure();
        return data.optJSONObject("bili_badges");
    }

    /** 导出成插件认识的 JSON（就是 chrome.storage.local 的那几把钥匙）。 */
    public synchronized String exportJson() {
        ensure();
        JSONObject out = new JSONObject();
        String[] keys = {"bili_daily", "bili_lessons", "bili_checkins", "bili_streak", "bili_badges"};
        try {
            for (String k : keys) out.put(k, data.get(k));
            return out.toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * 导入插件导出的 JSON 并合并。
     * 用内容指纹去重：同一份文件重复导入不会让时长翻倍。
     */
    public synchronized int importJson(String json) {
        if (json == null || json.trim().length() == 0) return 0;
        try {
            JSONObject incoming = new JSONObject(json.trim());
            String fingerprint = md5(json.trim());
            JSONObject imports = data.getJSONObject("imports");
            if (imports.optBoolean(fingerprint, false)) return -1; // 已导入过
            imports.put(fingerprint, true);

            int added = 0;
            added += mergeDaily(incoming.optJSONObject("bili_daily"));
            added += mergeLessons(incoming.optJSONObject("bili_lessons"));
            mergeCheckins(incoming.optJSONObject("bili_checkins"));
            recalcStreak();
            recalcBadges();
            save();
            return added;
        } catch (Exception e) {
            return -2;
        }
    }

    private int mergeDaily(JSONObject incoming) {
        if (incoming == null) return 0;
        try {
            JSONObject daily = data.getJSONObject("bili_daily");
            int n = 0;
            Iterator<String> it = incoming.keys();
            while (it.hasNext()) {
                String day = it.next();
                JSONObject src = incoming.optJSONObject(day);
                if (src == null) continue;
                JSONObject dst = daily.optJSONObject(day);
                if (dst == null) { dst = new JSONObject(); dst.put("lessonKeys", new JSONArray()); }
                dst.put("seconds", round1(dst.optDouble("seconds", 0) + src.optDouble("seconds", 0)));
                JSONArray sk = src.optJSONArray("lessonKeys");
                JSONArray dk = dst.optJSONArray("lessonKeys");
                if (dk == null) { dk = new JSONArray(); dst.put("lessonKeys", dk); }
                if (sk != null) for (int i = 0; i < sk.length(); i++) {
                    String key = sk.optString(i, "");
                    if (key.length() > 0 && !contains(dk, key)) dk.put(key);
                }
                daily.put(day, dst);
                n++;
            }
            return n;
        } catch (JSONException e) { return 0; }
    }

    private int mergeLessons(JSONObject incoming) {
        if (incoming == null) return 0;
        try {
            JSONObject lessons = data.getJSONObject("bili_lessons");
            int n = 0;
            Iterator<String> it = incoming.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject src = incoming.optJSONObject(key);
                if (src == null) continue;
                JSONObject dst = lessons.optJSONObject(key);
                if (dst == null) { lessons.put(key, src); n++; continue; }
                dst.put("watchedSeconds", round1(dst.optDouble("watchedSeconds", 0) + src.optDouble("watchedSeconds", 0)));
                dst.put("maxPosition", Math.max(dst.optDouble("maxPosition", 0), src.optDouble("maxPosition", 0)));
                if (src.optBoolean("completed", false)) {
                    dst.put("completed", true);
                    if (dst.optLong("completedAt", 0) == 0) dst.put("completedAt", src.optLong("completedAt", 0));
                }
                lessons.put(key, dst);
                n++;
            }
            return n;
        } catch (JSONException e) { return 0; }
    }

    private void mergeCheckins(JSONObject incoming) {
        if (incoming == null) return;
        try {
            JSONObject checkins = data.getJSONObject("bili_checkins");
            Iterator<String> it = incoming.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (incoming.optBoolean(k, false)) checkins.put(k, true);
            }
        } catch (JSONException ignored) {}
    }

    public synchronized void clearAll() {
        data = new JSONObject();
        save();
        ensure();
        save();
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
