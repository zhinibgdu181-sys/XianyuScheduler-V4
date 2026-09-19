package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * V4.67: compact, task-scoped memory of verified human touch habits.
 *
 * This store records only structured gesture statistics. It never stores screenshots,
 * recordings, or raw touch streams. It is an experience source only; callers must
 * perform their own safety validation before replaying any learned behavior.
 */
final class HumanOperationExperienceStore {

    static final String TYPE_TAP = "tap";
    static final String TYPE_SWIPE = "swipe";
    static final String TYPE_WAIT = "wait";

    private static final String PREFS = "xianyu_task_profiles_v48";
    private static final String KEY_PREFIX = "human_op_v467_";
    private static final String INDEX_KEY = KEY_PREFIX + "index";
    private static final int MAX_RECORDS = 6000;
    private static final long TTL_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final long MAX_STORE_BYTES = 5L * 1024L * 1024L;

    private HumanOperationExperienceStore() {}

    static synchronized void recordTap(
            Context context,
            String task,
            String pageKey,
            int targetCx,
            int targetCy,
            int actualX,
            int actualY,
            int width,
            int height) {
        if (context == null || width <= 0 || height <= 0) return;
        int dx = actualX - targetCx;
        int dy = actualY - targetCy;
        String payload = String.format(Locale.US,
                "v1|%s|%s|%d|%d|%d|%d|%d|%d|%d|%d",
                TYPE_TAP, safe(task), safe(pageKey), targetCx, targetCy,
                actualX, actualY, dx, dy, width, height);
        append(context, payload);
    }

    static synchronized void recordSwipe(
            Context context,
            String task,
            String pageKey,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs,
            float pathDistance,
            float angleDeg,
            float avgSpeed,
            int width,
            int height) {
        if (context == null || width <= 0 || height <= 0) return;
        String payload = String.format(Locale.US,
                "v1|%s|%s|%d|%d|%d|%d|%d|%.1f|%.2f|%.1f|%d|%d",
                TYPE_SWIPE, safe(task), safe(pageKey),
                startX, startY, endX, endY, Math.max(0, durationMs),
                Math.max(0f, pathDistance), angleDeg, Math.max(0f, avgSpeed),
                width, height);
        append(context, payload);
    }

    static synchronized void recordWait(
            Context context,
            String task,
            String pageKey,
            long waitMs) {
        if (context == null || waitMs < 0L) return;
        String payload = String.format(Locale.US,
                "v1|%s|%s|%d",
                TYPE_WAIT, safe(task), safe(pageKey), waitMs);
        append(context, payload);
    }

    static synchronized int recordCount(Context context) {
        List<String> records = load(context);
        return records == null ? 0 : records.size();
    }

    static synchronized void compact(Context context) {
        List<String> records = load(context);
        if (records == null || records.isEmpty()) return;
        long now = System.currentTimeMillis();
        ArrayList<String> kept = new ArrayList<>();
        for (String r : records) {
            long at = timestampOf(r);
            if (at > 0L && now - at <= TTL_MS) kept.add(r);
        }
        if (kept.size() > MAX_RECORDS) {
            kept = new ArrayList<>(kept.subList(kept.size() - MAX_RECORDS, kept.size()));
        }
        save(context, kept);
    }

    private static void append(Context context, String payload) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return;
        List<String> records = load(context);
        if (records == null) records = new ArrayList<>();
        records.add(System.currentTimeMillis() + "|" + payload);

        long now = System.currentTimeMillis();
        ArrayList<String> kept = new ArrayList<>();
        for (String r : records) {
            long at = timestampOf(r);
            if (at > 0L && now - at <= TTL_MS) kept.add(r);
        }

        while (kept.size() > MAX_RECORDS || estimateBytes(kept) > MAX_STORE_BYTES) {
            if (kept.isEmpty()) break;
            kept.remove(0);
        }
        save(context, kept);
    }

    private static List<String> load(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return null;
        String encoded = prefs.getString(INDEX_KEY, "");
        if (encoded == null || encoded.isEmpty()) return new ArrayList<>();
        String[] rows = encoded.split("\n");
        ArrayList<String> result = new ArrayList<>(rows.length);
        Collections.addAll(result, rows);
        return result;
    }

    private static void save(Context context, List<String> records) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (String r : records) {
            if (r == null || r.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.replace("\n", " ").replace("\r", " "));
        }
        prefs.edit().putString(INDEX_KEY, sb.toString()).apply();
    }

    private static long timestampOf(String record) {
        if (record == null) return 0L;
        int p = record.indexOf('|');
        if (p <= 0) return 0L;
        try {
            return Long.parseLong(record.substring(0, p));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long estimateBytes(List<String> records) {
        long bytes = 0L;
        for (String r : records) {
            if (r != null) bytes += r.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes > MAX_STORE_BYTES) return bytes;
        }
        return bytes;
    }

    private static SharedPreferences prefs(Context context) {
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "_").replace("\n", " ").replace("\r", " ");
    }
}
