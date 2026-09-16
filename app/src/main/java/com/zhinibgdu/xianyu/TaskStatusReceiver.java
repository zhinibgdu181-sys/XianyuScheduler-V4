package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent log utility retained under its historical class name to avoid
 * touching every existing TaskExecutor call site.
 *
 * V4.26 additionally keeps a tiny date-scoped SUCCESS history for the "今日"
 * tab. Clearing the text log does not erase today's verified task list.
 */
public final class TaskStatusReceiver {
    private static final String LOG_FILE_NAME = "xianyu_log.txt";
    private static final int MAX_LOG_BYTES = 2 * 1024 * 1024;

    private static final String TODAY_PREFS = "xianyu_today_v426";
    private static final String KEY_DATE = "date";
    private static final String KEY_ENTRIES = "entries";

    private TaskStatusReceiver() {
    }

    public static synchronized void writeLog(
            Context context,
            String result,
            String task,
            String message
    ) {
        try {
            if (context == null) return;
            Context app = context.getApplicationContext();
            File dir = app.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;

            File file = new File(dir, LOG_FILE_NAME);
            trimIfNeeded(file);

            String time = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = time + " [" + safe(result) + "] "
                    + (safe(task).isEmpty() ? "" : safe(task) + ": ")
                    + safe(message) + "\n";

            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            if ("SUCCESS".equalsIgnoreCase(safe(result)) && !safe(task).isEmpty()) {
                recordTodaySuccess(app, task);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void writeLog(Context context, String message) {
        writeLog(context, "INFO", "系统日志", message);
    }

    public static synchronized boolean clearLog(Context context) {
        try {
            if (context == null) return false;
            Context app = context.getApplicationContext();
            File dir = app.getExternalFilesDir(null);
            if (dir == null) return false;
            File file = new File(dir, LOG_FILE_NAME);
            if (!file.exists()) return true;
            return file.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized List<String> getTodayCompletedTasks(Context context) {
        ArrayList<String> out = new ArrayList<>();
        if (context == null) return out;
        try {
            Context app = context.getApplicationContext();
            SharedPreferences p = app.getSharedPreferences(TODAY_PREFS, Context.MODE_PRIVATE);
            String today = dayKey();
            if (!today.equals(p.getString(KEY_DATE, ""))) {
                p.edit().putString(KEY_DATE, today).putString(KEY_ENTRIES, "").apply();
                return out;
            }

            String raw = p.getString(KEY_ENTRIES, "");
            if (raw == null || raw.trim().isEmpty()) return out;
            String[] lines = raw.split("\\n");
            for (String line : lines) {
                if (line != null && !line.trim().isEmpty()) out.add(line.trim());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void recordTodaySuccess(Context app, String task) {
        try {
            SharedPreferences p = app.getSharedPreferences(TODAY_PREFS, Context.MODE_PRIVATE);
            String today = dayKey();
            String storedDate = p.getString(KEY_DATE, "");
            String raw = today.equals(storedDate) ? p.getString(KEY_ENTRIES, "") : "";

            // Keep insertion order while de-duplicating by task title. A task is
            // shown once even if a reward row is re-verified later in the day.
            LinkedHashMap<String, String> byTask = new LinkedHashMap<>();
            if (raw != null && !raw.trim().isEmpty()) {
                for (String line : raw.split("\\n")) {
                    if (line == null || line.trim().isEmpty()) continue;
                    String trimmed = line.trim();
                    int sep = trimmed.indexOf("  ");
                    String key = sep >= 0 ? trimmed.substring(sep + 2).trim() : trimmed;
                    byTask.put(key, trimmed);
                }
            }

            String cleanTask = safe(task).replace('\n', ' ').replace('\r', ' ').trim();
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            byTask.put(cleanTask, stamp + "  " + cleanTask);

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : byTask.entrySet()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(entry.getValue());
            }

            p.edit()
                    .putString(KEY_DATE, today)
                    .putString(KEY_ENTRIES, sb.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private static String dayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    private static void trimIfNeeded(File file) {
        try {
            if (!file.exists() || file.length() <= MAX_LOG_BYTES) return;
            long keep = MAX_LOG_BYTES / 2L;
            byte[] data = new byte[(int) keep];
            int read;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                raf.seek(Math.max(0L, raf.length() - keep));
                read = raf.read(data);
            }
            if (read > 0) {
                try (FileOutputStream out = new FileOutputStream(file, false)) {
                    out.write("===== 日志自动截断 =====\n".getBytes(StandardCharsets.UTF_8));
                    out.write(data, 0, read);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
