package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V4.22 视觉小游戏模块："消了还想消"水果配对。
 *
 * 规则：点击水果会进入下方坑位；两个相同水果自动消除；坑位最多3个。
 * 安全策略：只点击高置信度的完整同类对 A->A，一次只处理一对。
 * V4.22 安全约束：只允许点击水果对象本身。禁止点击“打乱”“消除”
 * “解锁”“使用”等任何游戏功能按钮。找不到高置信度对子时直接安全停止。
 *
 * 这个实现不依赖 OpenCV。它把截图缩放到约720px宽，利用蓝色背景分割、
 * 连通域、归一化图块颜色/形状相似度寻找重复水果。
 */
final class FruitGameSolver {

    enum Result {
        COMPLETED,
        SAFE_STOP,
        NOT_FRUIT_GAME,
        ABORTED
    }

    interface Host {
        boolean tap(int x, int y, String reason);
        boolean sleep(long minMs, long maxMs);
        boolean aborted();
        void log(String message);
        ScreenOcr.Snapshot ocr(String reason);
    }

    private static final int ANALYSIS_WIDTH = 720;
    private static final int GRID = 32;
    private static final long SCREENSHOT_TIMEOUT_MS = 4200L;
    private static final long MAX_ROUND_MS = 7L * 60L * 1000L;
    private static final int MAX_PAIR_ACTIONS = 130;

    // Conservative thresholds measured against the supplied real game screenshot.
    // Exact repeated sprites score around 0.98~0.99. We intentionally reject
    // uncertain pairs because the tray only has three positions.
    private static final double MIN_PAIR_SCORE = 0.974;
    private static final double MAX_RGB_MAD = 0.060;
    private static final double MIN_HIST_COS = 0.935;
    private static final double MIN_SHAPE_IOU = 0.78;

    private static final Pattern REMAINING_PATTERN =
            Pattern.compile("剩余\\s*([0-9]{1,4})");
    private static final Pattern PERCENT_PATTERN =
            Pattern.compile("([0-9]{1,3})\\s*%");

    private FruitGameSolver() {
    }

    static Result solveOneRound(
            Context context,
            String suPath,
            Host host
    ) {
        if (context == null || suPath == null || suPath.isEmpty() || host == null) {
            return Result.SAFE_STOP;
        }

        if (!host.sleep(420L, 720L)) return Result.ABORTED;
        ScreenOcr.Snapshot firstOcr = host.ocr("水果游戏V4.22/进入确认");
        if (host.aborted()) return Result.ABORTED;

        String firstText = normalize(firstOcr == null ? "" : firstOcr.fullText);
        if (!looksLikeFruitGame(firstText)) {
            host.log("[游戏V4.22] 当前页面不是水果配对游戏，停止视觉求解");
            return Result.NOT_FRUIT_GAME;
        }

        int remaining = parseRemaining(firstText);
        int progress = parsePercent(firstText);
        host.log("[游戏V4.22] ✅ 识别水果游戏"
                + (remaining >= 0 ? " / 剩余=" + remaining : "")
                + (progress >= 0 ? " / 进度=" + progress + "%" : ""));

        long started = SystemClock.elapsedRealtime();
        int pairActions = 0;
        int consecutiveCaptureFail = 0;

        while (!host.aborted()
                && pairActions < MAX_PAIR_ACTIONS
                && SystemClock.elapsedRealtime() - started < MAX_ROUND_MS) {

            GameFrame frame = captureFrame(context, suPath, host);
            if (frame == null) {
                consecutiveCaptureFail++;
                host.log("[游戏V4.22] 截图失败 " + consecutiveCaptureFail + "/3");
                if (consecutiveCaptureFail >= 3) return Result.SAFE_STOP;
                if (!host.sleep(250L, 420L)) return Result.ABORTED;
                continue;
            }
            consecutiveCaptureFail = 0;

            List<FruitObject> objects = detectFruitObjects(frame);
            PairChoice pair = chooseBestPair(objects);

            host.log("[游戏V4.22] 当前检测水果=" + objects.size()
                    + (pair == null ? " / 无高置信对子" :
                    " / 最佳对子=" + format(pair.score)
                            + " rgb=" + format(pair.rgbSimilarity)
                            + " hist=" + format(pair.histCos)
                            + " shape=" + format(pair.shapeIou)));

            if (pair == null) {
                safeRecycle(frame.bitmap);

                ScreenOcr.Snapshot checkpoint = host.ocr("水果游戏V4.22/无对子检查");
                if (host.aborted()) return Result.ABORTED;
                String text = normalize(checkpoint == null ? "" : checkpoint.fullText);
                if (isRoundCompleted(text)) {
                    host.log("[游戏V4.22] ✅ 已检测到一关完成状态");
                    return Result.COMPLETED;
                }

                // V4.22 hard rule from real-device feedback: never touch game
                // function controls such as shuffle/eliminate/unlock/use. Only
                // fruit sprites themselves may be tapped by the solver.
                host.log("[游戏V4.22] 无高置信对子；禁止点击打乱/消除/解锁/使用等功能按钮，安全停止");
                return Result.SAFE_STOP;
            }

            int ax = mapX(frame, pair.a.centerX);
            int ay = mapY(frame, pair.a.centerY);
            int bx = mapX(frame, pair.b.centerX);
            int by = mapY(frame, pair.b.centerY);
            safeRecycle(frame.bitmap);

            host.log("[游戏V4.22] 配对点击 A=(" + ax + "," + ay + ")"
                    + " B=(" + bx + "," + by + ") / score=" + format(pair.score));

            // First fruit occupies at most one tray slot.
            if (!host.tap(ax, ay, "水果游戏-配对A")) {
                return host.aborted() ? Result.ABORTED : Result.SAFE_STOP;
            }
            if (!host.sleep(125L, 215L)) return Result.ABORTED;

            // If B cannot be clicked we stop immediately: this avoids filling a
            // third slot after an incomplete pair.
            if (!host.tap(bx, by, "水果游戏-配对B")) {
                host.log("[游戏V4.22] 第二个水果点击失败；为保护3槽坑位立即停止");
                return host.aborted() ? Result.ABORTED : Result.SAFE_STOP;
            }
            pairActions++;
            if (!host.sleep(380L, 620L)) return Result.ABORTED;

            // OCR is intentionally sparse; screenshot vision handles most pairs.
            // Every few pairs verify that the game is still progressing / ended.
            if (pairActions == 1 || pairActions % 6 == 0) {
                ScreenOcr.Snapshot checkpoint = host.ocr(
                        "水果游戏V4.22/进度检查#" + pairActions);
                if (host.aborted()) return Result.ABORTED;
                String text = normalize(checkpoint == null ? "" : checkpoint.fullText);

                int nowRemaining = parseRemaining(text);
                int nowProgress = parsePercent(text);
                host.log("[游戏V4.22] 进度检查 pair=" + pairActions
                        + (nowRemaining >= 0 ? " / 剩余=" + nowRemaining : "")
                        + (nowProgress >= 0 ? " / " + nowProgress + "%" : ""));

                if (isRoundCompleted(text)) {
                    host.log("[游戏V4.22] ✅ 第1关完成");
                    return Result.COMPLETED;
                }

                // If we unexpectedly left the fruit game, do not continue tapping.
                if (!text.isEmpty() && !looksLikeFruitGame(text)) {
                    if (looksLikeTaskPanel(text)) {
                        host.log("[游戏V4.22] 已自动返回任务面板，按完成流程交给外层验证");
                        return Result.COMPLETED;
                    }
                    host.log("[游戏V4.22] 页面已离开水果游戏，停止继续点击");
                    return Result.SAFE_STOP;
                }

                if (nowRemaining >= 0) remaining = nowRemaining;
                if (nowProgress >= 0) progress = nowProgress;
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[游戏V4.22] 达到本轮安全上限，停止自动点击");
        return Result.SAFE_STOP;
    }

    static boolean looksLikeFruitGame(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        boolean controls = t.contains("消除") && t.contains("打乱");
        boolean stage = t.contains("第1关") || t.contains("剩余") || t.contains("解锁");
        return controls && stage;
    }

    static boolean looksLikeMahjongPairGame(String text) {
        String t = normalize(text);
        return t.contains("点击麻将对")
                || (t.contains("麻将对") && t.contains("水平相邻"));
    }

    private static boolean isRoundCompleted(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        int remaining = parseRemaining(t);
        if (remaining == 0) return true;
        return t.contains("第2关")
                || t.contains("下一关")
                || t.contains("闯关成功")
                || t.contains("通关成功")
                || t.contains("恭喜过关")
                || t.contains("过关成功")
                || t.contains("领取奖励");
    }

    private static boolean looksLikeTaskPanel(String text) {
        String t = normalize(text);
        return t.contains("得骰子赚闲鱼币")
                || (t.contains("闲鱼币") && (t.contains("去完成") || t.contains("领取奖励")));
    }

    private static int parseRemaining(String text) {
        Matcher m = REMAINING_PATTERN.matcher(normalize(text));
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int parsePercent(String text) {
        Matcher m = PERCENT_PATTERN.matcher(normalize(text));
        int best = -1;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v <= 100) best = v;
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static GameFrame captureFrame(Context context, String suPath, Host host) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return null;
        if (!dir.exists() && !dir.mkdirs()) return null;

        File file = new File(dir, "xianyu_fruit_v418_" + android.os.Process.myPid() + ".png");
        String path = file.getAbsolutePath();
        String cmd = "rm -f " + shellQuote(path)
                + "; screencap -p " + shellQuote(path)
                + "; chmod 0644 " + shellQuote(path);

        if (!runRoot(suPath, cmd, SCREENSHOT_TIMEOUT_MS, host)) {
            safeDelete(file);
            return null;
        }
        if (host.aborted()) {
            safeDelete(file);
            return null;
        }

        Bitmap original = BitmapFactory.decodeFile(path);
        safeDelete(file);
        if (original == null) return null;

        int ow = original.getWidth();
        int oh = original.getHeight();
        if (ow <= 0 || oh <= 0) {
            safeRecycle(original);
            return null;
        }

        Bitmap analysis = original;
        if (ow > ANALYSIS_WIDTH) {
            int ah = Math.max(1, Math.round((float) oh * ANALYSIS_WIDTH / ow));
            analysis = Bitmap.createScaledBitmap(original, ANALYSIS_WIDTH, ah, true);
            if (analysis != original) safeRecycle(original);
        }

        return new GameFrame(analysis, ow, oh);
    }

    private static List<FruitObject> detectFruitObjects(GameFrame frame) {
        Bitmap bitmap = frame.bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int roiTop = clamp(Math.round(height * 0.07f), 0, height - 1);
        int roiBottom = clamp(Math.round(height * 0.64f), roiTop + 1, height);
        int roiHeight = roiBottom - roiTop;

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        boolean[] mask = new boolean[width * roiHeight];
        for (int y = 0; y < roiHeight; y++) {
            int py = y + roiTop;
            int row = py * width;
            int mr = y * width;
            for (int x = 0; x < width; x++) {
                int c = pixels[row + x];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                mask[mr + x] = isFruitForeground(r, g, b);
            }
        }

        // 3x3 opening then closing. This mirrors the prototype tested on the
        // supplied screenshot and keeps the ~75-85px fruit sprites separated.
        mask = dilate3(erode3(mask, width, roiHeight), width, roiHeight);
        mask = erode3(dilate3(mask, width, roiHeight), width, roiHeight);

        int[] labels = new int[mask.length];
        int[] queue = new int[mask.length];
        int nextLabel = 0;
        List<Component> components = new ArrayList<>();

        int minBox = Math.max(44, Math.round(width * 0.085f));
        int maxBox = Math.max(minBox + 1, Math.round(width * 0.148f));

        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || labels[i] != 0) continue;
            nextLabel++;
            int head = 0, tail = 0;
            queue[tail++] = i;
            labels[i] = nextLabel;
            int area = 0;
            int minX = width, maxX = 0, minY = roiHeight, maxY = 0;

            while (head < tail) {
                int p = queue[head++];
                int y = p / width;
                int x = p - y * width;
                area++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= roiHeight) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        if (nx < 0 || nx >= width) continue;
                        int np = ny * width + nx;
                        if (mask[np] && labels[np] == 0) {
                            labels[np] = nextLabel;
                            queue[tail++] = np;
                        }
                    }
                }
            }

            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;
            if (bw < minBox || bw > maxBox || bh < minBox || bh > maxBox) continue;
            double fill = area / (double) (bw * bh);
            if (fill < 0.43 || fill > 0.92) continue;

            // Exclude top-right menu/close controls and the left reward ribbon.
            int absoluteTop = minY + roiTop;
            if (absoluteTop < Math.round(height * 0.075f)
                    && minX > Math.round(width * 0.63f)) continue;
            if (minX < Math.round(width * 0.055f)
                    && bw < Math.round(width * 0.10f)) continue;

            components.add(new Component(
                    nextLabel, minX, minY, maxX, maxY, area));
        }

        List<FruitObject> result = new ArrayList<>();
        for (Component c : components) {
            FruitObject object = buildDescriptor(
                    c, labels, pixels, width, height, roiTop, roiHeight);
            if (object != null) result.add(object);
        }
        return result;
    }

    private static FruitObject buildDescriptor(
            Component c,
            int[] labels,
            int[] pixels,
            int width,
            int height,
            int roiTop,
            int roiHeight
    ) {
        int bw = c.maxX - c.minX + 1;
        int bh = c.maxY - c.minY + 1;
        if (bw <= 0 || bh <= 0) return null;

        float[] rgb = new float[GRID * GRID * 3];
        boolean[] shape = new boolean[GRID * GRID];
        float[] hist = new float[72]; // 12 hue x 6 saturation bins

        int foregroundSamples = 0;
        for (int gy = 0; gy < GRID; gy++) {
            int sy = c.minY + clamp((int) (((gy + 0.5) * bh) / GRID), 0, bh - 1);
            for (int gx = 0; gx < GRID; gx++) {
                int sx = c.minX + clamp((int) (((gx + 0.5) * bw) / GRID), 0, bw - 1);
                int gi = gy * GRID + gx;
                int labelIndex = sy * width + sx;
                if (sy < 0 || sy >= roiHeight || labelIndex < 0 || labelIndex >= labels.length) {
                    continue;
                }
                if (labels[labelIndex] != c.label) continue;

                shape[gi] = true;
                int absoluteY = sy + roiTop;
                if (absoluteY < 0 || absoluteY >= height) continue;
                int color = pixels[absoluteY * width + sx];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                int base = gi * 3;
                rgb[base] = r / 255f;
                rgb[base + 1] = g / 255f;
                rgb[base + 2] = b / 255f;

                float[] hsv = rgbToHsv(r, g, b);
                int hBin = clamp((int) (hsv[0] / 360f * 12f), 0, 11);
                int sBin = clamp((int) (hsv[1] * 6f), 0, 5);
                hist[hBin * 6 + sBin] += 1f;
                foregroundSamples++;
            }
        }

        if (foregroundSamples < GRID * GRID * 0.30) return null;
        normalizeL2(hist);

        float centerX = (c.minX + c.maxX) * 0.5f;
        float centerY = roiTop + (c.minY + c.maxY) * 0.5f;
        return new FruitObject(centerX, centerY, rgb, shape, hist);
    }

    private static PairChoice chooseBestPair(List<FruitObject> objects) {
        if (objects == null || objects.size() < 2) return null;
        PairChoice best = null;

        for (int i = 0; i < objects.size(); i++) {
            FruitObject a = objects.get(i);
            for (int j = i + 1; j < objects.size(); j++) {
                FruitObject b = objects.get(j);
                Similarity s = similarity(a, b);
                if (s.rgbMad > MAX_RGB_MAD
                        || s.histCos < MIN_HIST_COS
                        || s.shapeIou < MIN_SHAPE_IOU
                        || s.score < MIN_PAIR_SCORE) {
                    continue;
                }
                if (best == null || s.score > best.score) {
                    best = new PairChoice(a, b, s.score,
                            1.0 - s.rgbMad, s.histCos, s.shapeIou);
                }
            }
        }
        return best;
    }

    private static Similarity similarity(FruitObject a, FruitObject b) {
        int union = 0;
        int intersection = 0;
        double abs = 0.0;
        int rgbTerms = 0;

        for (int i = 0; i < a.shape.length; i++) {
            boolean af = a.shape[i];
            boolean bf = b.shape[i];
            if (af || bf) {
                union++;
                if (af && bf) intersection++;
                int base = i * 3;
                for (int k = 0; k < 3; k++) {
                    float av = af ? a.rgb[base + k] : 0f;
                    float bv = bf ? b.rgb[base + k] : 0f;
                    abs += Math.abs(av - bv);
                    rgbTerms++;
                }
            }
        }

        double mad = rgbTerms == 0 ? 1.0 : abs / rgbTerms;
        double iou = union == 0 ? 0.0 : intersection / (double) union;
        double histCos = 0.0;
        for (int i = 0; i < a.hist.length; i++) {
            histCos += a.hist[i] * b.hist[i];
        }
        histCos = clamp01(histCos);
        double colorSimilarity = clamp01(1.0 - mad);
        double score = 0.58 * colorSimilarity + 0.27 * histCos + 0.15 * iou;
        return new Similarity(score, mad, histCos, iou);
    }

    private static boolean isFruitForeground(int r, int g, int b) {
        // Allocation-free RGB->HSV for the full-frame segmentation pass.
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float sat = max == 0f ? 0f : delta / max;
        float hue;
        if (delta == 0f) {
            hue = 0f;
        } else if (max == rf) {
            hue = 60f * (((gf - bf) / delta) % 6f);
        } else if (max == gf) {
            hue = 60f * (((bf - rf) / delta) + 2f);
        } else {
            hue = 60f * (((rf - gf) / delta) + 4f);
        }
        if (hue < 0f) hue += 360f;

        // Game background is cyan/sky-blue (~165°..215°) with medium/high V.
        // Fruit sprites are saturated colors outside that band; very dark pixels
        // are also retained for outlines/shadows.
        boolean saturatedNonBlue = sat > 0.18f && (hue < 165f || hue > 215f);
        boolean darkDetail = max < 0.47f;
        return saturatedNonBlue || darkDetail;
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;
        float h;
        if (delta == 0f) {
            h = 0f;
        } else if (max == rf) {
            h = 60f * (((gf - bf) / delta) % 6f);
        } else if (max == gf) {
            h = 60f * (((bf - rf) / delta) + 2f);
        } else {
            h = 60f * (((rf - gf) / delta) + 4f);
        }
        if (h < 0f) h += 360f;
        float s = max == 0f ? 0f : delta / max;
        return new float[]{h, s, max};
    }

    private static boolean[] erode3(boolean[] src, int w, int h) {
        boolean[] out = new boolean[src.length];
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                boolean ok = true;
                for (int dy = -1; dy <= 1 && ok; dy++) {
                    int rr = row + dy * w;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (!src[rr + x + dx]) {
                            ok = false;
                            break;
                        }
                    }
                }
                out[row + x] = ok;
            }
        }
        return out;
    }

    private static boolean[] dilate3(boolean[] src, int w, int h) {
        boolean[] out = new boolean[src.length];
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                boolean hit = false;
                for (int dy = -1; dy <= 1 && !hit; dy++) {
                    int rr = row + dy * w;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (src[rr + x + dx]) {
                            hit = true;
                            break;
                        }
                    }
                }
                out[row + x] = hit;
            }
        }
        return out;
    }

    private static int mapX(GameFrame frame, float analysisX) {
        return clamp(Math.round(analysisX * frame.originalWidth / frame.bitmap.getWidth()),
                1, frame.originalWidth - 2);
    }

    private static int mapY(GameFrame frame, float analysisY) {
        return clamp(Math.round(analysisY * frame.originalHeight / frame.bitmap.getHeight()),
                1, frame.originalHeight - 2);
    }

    private static void normalizeL2(float[] values) {
        double sum = 0.0;
        for (float v : values) sum += v * v;
        if (sum <= 0.0) return;
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < values.length; i++) values[i] *= inv;
    }

    private static boolean runRoot(
            String suPath,
            String command,
            long timeoutMs,
            Host host
    ) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{suPath, "-c", command});
            long end = SystemClock.elapsedRealtime() + Math.max(400L, timeoutMs);
            while (SystemClock.elapsedRealtime() < end) {
                if (host.aborted()) {
                    try { process.destroyForcibly(); } catch (Throwable ignored) {}
                    return false;
                }
                if (process.waitFor(100L, TimeUnit.MILLISECONDS)) {
                    drain(process.getInputStream());
                    drain(process.getErrorStream());
                    return process.exitValue() == 0;
                }
            }
            try { process.destroyForcibly(); } catch (Throwable ignored) {}
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
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
            try { input.close(); } catch (Throwable ignored) {}
        }
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void safeDelete(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void safeRecycle(Bitmap bitmap) {
        try {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String format(double v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    private static final class GameFrame {
        final Bitmap bitmap;
        final int originalWidth;
        final int originalHeight;

        GameFrame(Bitmap bitmap, int originalWidth, int originalHeight) {
            this.bitmap = bitmap;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
        }
    }

    private static final class Component {
        final int label;
        final int minX;
        final int minY;
        final int maxX;
        final int maxY;
        final int area;

        Component(int label, int minX, int minY, int maxX, int maxY, int area) {
            this.label = label;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.area = area;
        }
    }

    private static final class FruitObject {
        final float centerX;
        final float centerY;
        final float[] rgb;
        final boolean[] shape;
        final float[] hist;

        FruitObject(float centerX, float centerY, float[] rgb, boolean[] shape, float[] hist) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.rgb = rgb;
            this.shape = shape;
            this.hist = hist;
        }
    }

    private static final class Similarity {
        final double score;
        final double rgbMad;
        final double histCos;
        final double shapeIou;

        Similarity(double score, double rgbMad, double histCos, double shapeIou) {
            this.score = score;
            this.rgbMad = rgbMad;
            this.histCos = histCos;
            this.shapeIou = shapeIou;
        }
    }

    private static final class PairChoice {
        final FruitObject a;
        final FruitObject b;
        final double score;
        final double rgbSimilarity;
        final double histCos;
        final double shapeIou;

        PairChoice(
                FruitObject a,
                FruitObject b,
                double score,
                double rgbSimilarity,
                double histCos,
                double shapeIou
        ) {
            this.a = a;
            this.b = b;
            this.score = score;
            this.rgbSimilarity = rgbSimilarity;
            this.histCos = histCos;
            this.shapeIou = shapeIou;
        }
    }
}
