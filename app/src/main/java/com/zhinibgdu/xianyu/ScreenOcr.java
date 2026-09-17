package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.google.android.gms.tasks.Task;
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
    // V4.40: OCR不需要1440p全分辨率。1080宽仍足够识别中文，同时显著减少ML Kit像素量。
    private static final int OCR_ANALYSIS_WIDTH = 1080;

    private ScreenOcr() {
    }

    private static TextRecognizer sharedRecognizer;

    static synchronized void close() {
        if (sharedRecognizer != null) {
            try { sharedRecognizer.close(); } catch (Exception ignored) {}
            sharedRecognizer = null;
        }
    }

    static synchronized Snapshot capture(Context context, String suPath,
                                         RootCommandRunner.Cancellation cancellation) {
        if (context == null || suPath == null || suPath.trim().isEmpty()) return Snapshot.empty();
        File screenshot = null;
        Bitmap original = null;
        Bitmap analysis = null;
        TextRecognizer recognizer = null;
        Task<Text> pending = null;
        try {
            // V4.40: 使用内部cache，避免external/FUSE路径上的额外I/O。
            File dir = context.getCacheDir();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                dir = context.getExternalFilesDir(null);
            }
            if (dir == null || (!dir.exists() && !dir.mkdirs())) return Snapshot.empty();
            screenshot = File.createTempFile("xianyu_ocr_", ".png", dir);
            String path = shellQuote(screenshot.getAbsolutePath());
            if (!RootCommandRunner.run(suPath, "screencap -p " + path + " && chmod 0644 " + path,
                    ROOT_TIMEOUT_MS, cancellation)) return Snapshot.empty();
            original = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
            if (original == null) return Snapshot.empty();

            final int originalWidth = original.getWidth();
            final int originalHeight = original.getHeight();
            if (originalWidth <= 0 || originalHeight <= 0) return Snapshot.empty();

            analysis = original;
            float scale = 1.0f;
            if (originalWidth > OCR_ANALYSIS_WIDTH) {
                int ah = Math.max(1, Math.round((float) originalHeight * OCR_ANALYSIS_WIDTH / originalWidth));
                analysis = Bitmap.createScaledBitmap(original, OCR_ANALYSIS_WIDTH, ah, true);
                scale = OCR_ANALYSIS_WIDTH / (float) originalWidth;
            }

            if (sharedRecognizer == null)
                sharedRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            recognizer = sharedRecognizer;
            pending = recognizer.process(InputImage.fromBitmap(analysis, 0));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OCR_TIMEOUT_MS);
            while (!pending.isComplete()) {
                if (System.nanoTime() >= deadline || (cancellation != null && cancellation.cancelled()))
                    return Snapshot.empty();
                Thread.sleep(60L);
            }
            if (cancellation != null && cancellation.cancelled()) return Snapshot.empty();
            Text result = pending.getResult();
            List<Item> items = new ArrayList<>();
            if (result != null) {
                final float invScale = scale <= 0f ? 1f : 1f / scale;
                for (Text.TextBlock block : result.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        String text = normalize(line.getText());
                        Rect bounds = line.getBoundingBox();
                        if (!text.isEmpty() && bounds != null) {
                            Rect mapped = bounds;
                            if (scale != 1.0f) {
                                mapped = new Rect(
                                        Math.round(bounds.left * invScale),
                                        Math.round(bounds.top * invScale),
                                        Math.round(bounds.right * invScale),
                                        Math.round(bounds.bottom * invScale));
                            } else {
                                mapped = new Rect(bounds);
                            }
                            items.add(new Item(text, mapped));
                        }
                    }
                }
            }
            return new Snapshot(result == null ? "" : normalize(result.getText()), items,
                    originalWidth, originalHeight);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Snapshot.empty();
        } catch (Exception e) {
            return Snapshot.empty();
        } finally {
            final Bitmap finalOriginal = original;
            final Bitmap finalAnalysis = analysis;
            if (pending != null && !pending.isComplete()) {
                // ML Kit可能仍持有analysis bitmap，延迟到任务完成再释放。
                sharedRecognizer = null;
                final TextRecognizer heldRecognizer = recognizer;
                pending.addOnCompleteListener(Runnable::run, done -> {
                    if (finalAnalysis != null && !finalAnalysis.isRecycled()) finalAnalysis.recycle();
                    if (finalOriginal != null && finalOriginal != finalAnalysis && !finalOriginal.isRecycled()) finalOriginal.recycle();
                    if (heldRecognizer != null) heldRecognizer.close();
                });
            } else {
                if (analysis != null && !analysis.isRecycled()) analysis.recycle();
                if (original != null && original != analysis && !original.isRecycled()) original.recycle();
            }
            safeDelete(screenshot);
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
