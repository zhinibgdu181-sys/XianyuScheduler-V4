package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root screenshot + ML Kit Chinese OCR fallback for UC WebView pages.
 *
 * 闲鱼币和任务列表在部分版本中由 UC WebView 渲染，UIAutomator 只能看到
 * "WVUCWebView / 首页 / 领 ×1" 之类的极少文本。这个类只在 XML 信息不足时
 * 使用截图 OCR，避免靠盲目固定坐标点击。
 */
final class ScreenOcr {

    private static final long ROOT_TIMEOUT_MS = 8000L;
    private static final long OCR_TIMEOUT_MS = 12000L;

    private ScreenOcr() {
    }

    static Snapshot capture(Context context, String suPath) {
        if (context == null || suPath == null || suPath.trim().isEmpty()) {
            return Snapshot.empty();
        }

        File dir = context.getExternalFilesDir(null);
        if (dir == null) return Snapshot.empty();
        if (!dir.exists() && !dir.mkdirs()) return Snapshot.empty();

        File screenshot = new File(
                dir,
                "xianyu_ocr_" + android.os.Process.myPid() + ".png"
        );

        String path = screenshot.getAbsolutePath();
        String command = "rm -f " + shellQuote(path)
                + "; screencap -p " + shellQuote(path)
                + "; chmod 0644 " + shellQuote(path);

        if (!runRoot(suPath, command)) {
            safeDelete(screenshot);
            return Snapshot.empty();
        }

        Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) {
            safeDelete(screenshot);
            return Snapshot.empty();
        }

        TextRecognizer recognizer = null;
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build()
            );

            Text result = Tasks.await(
                    recognizer.process(image),
                    OCR_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
            );

            List<Item> items = new ArrayList<>();
            if (result != null) {
                for (Text.TextBlock block : result.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        String text = normalize(line.getText());
                        Rect bounds = line.getBoundingBox();
                        if (!text.isEmpty() && bounds != null) {
                            items.add(new Item(text, new Rect(bounds)));
                        }
                    }
                }
            }

            String fullText = result == null ? "" : normalize(result.getText());
            return new Snapshot(
                    fullText,
                    items,
                    bitmap.getWidth(),
                    bitmap.getHeight()
            );

        } catch (Throwable ignored) {
            return Snapshot.empty();
        } finally {
            if (recognizer != null) {
                try {
                    recognizer.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
            }
            safeDelete(screenshot);
        }
    }

    private static boolean runRoot(String suPath, String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(
                    new String[]{suPath, "-c", command}
            );
            boolean finished = process.waitFor(ROOT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                try {
                    process.destroyForcibly();
                } catch (Throwable ignored) {
                }
                return false;
            }
            drain(process.getInputStream());
            drain(process.getErrorStream());
            return process.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void drain(InputStream input) {
        if (input == null) return;
        try {
            byte[] buffer = new byte[4096];
            while (input.read(buffer) >= 0) {
                // discard
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void safeDelete(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }

    static final class Snapshot {
        final String fullText;
        final List<Item> items;
        final int width;
        final int height;

        Snapshot(String fullText, List<Item> items, int width, int height) {
            this.fullText = fullText == null ? "" : fullText;
            this.items = items == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(items));
            this.width = width;
            this.height = height;
        }

        static Snapshot empty() {
            return new Snapshot("", Collections.emptyList(), 0, 0);
        }

        boolean isEmpty() {
            return fullText.isEmpty() && items.isEmpty();
        }

        boolean contains(String token) {
            return token != null && !token.isEmpty() && fullText.contains(token);
        }

        Item findBest(String... tokens) {
            if (tokens == null) return null;

            Item best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Item item : items) {
                if (item == null || item.text.isEmpty()) continue;
                for (String token : tokens) {
                    if (token == null || token.isEmpty()) continue;
                    int score = matchScore(item.text, token);
                    if (score > bestScore) {
                        bestScore = score;
                        best = item;
                    }
                }
            }
            return bestScore > 0 ? best : null;
        }

        private static int matchScore(String text, String token) {
            if (text.equals(token)) return 10000 - text.length();
            if (text.startsWith(token)) return 8000 - text.length();
            if (text.endsWith(token)) return 7500 - text.length();
            if (text.contains(token)) return 6000 - text.length();
            return -1;
        }
    }

    static final class Item {
        final String text;
        final Rect bounds;

        Item(String text, Rect bounds) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : bounds;
        }

        int centerX() {
            return bounds.centerX();
        }

        int centerY() {
            return bounds.centerY();
        }

        String boundsString() {
            return "[" + bounds.left + "," + bounds.top + "]["
                    + bounds.right + "," + bounds.bottom + "]";
        }
    }
}
