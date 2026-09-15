package com.zhinibgdu.xianyu;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent log utility retained under its historical class name to avoid
 * touching every existing TaskExecutor call site.
 */
public final class TaskStatusReceiver {
    private static final String LOG_FILE_NAME = "xianyu_log.txt";
    private static final int MAX_LOG_BYTES = 2 * 1024 * 1024;

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
        } catch (Throwable ignored) {
        }
    }

    public static void writeLog(Context context, String message) {
        writeLog(context, "INFO", "系统日志", message);
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
