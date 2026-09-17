package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V4.34 视觉小游戏模块："消了还想消"水果配对。
 *
 * 规则：点击水果会进入下方坑位；两个相同水果自动消除；坑位最多3个。
 * 安全策略：只点击高置信度的完整同类对 A->A，一次只处理一对。
 * 只允许点击水果对象本身。禁止点击“打乱”“消除”“解锁”等任何游戏功能按钮。
 * 找不到高置信度对子时直接安全停止。
 *
 * V4.34 改进：
 * 1. 决策引擎：视觉置信度只是硬门槛，在可靠对子中优先处理底层、低风险、高释放价值组合。
 * 2. Observe→Evaluate→Act→Verify→Replan：点A后重新截图定位B，不使用已经失效的旧坐标。
 * 3. 每一对都用“剩余 N→N-2”闭环验证；失败组合进入本局黑名单并安全停止。
 * 4. 保留功能按钮禁区与动态视觉阈值，不点击打乱/消除/解锁/使用。
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
    private static final double MIN_PAIR_SCORE = 0.958;
    private static final double MAX_RGB_MAD = 0.090;
    private static final double MIN_HIST_COS = 0.900;
    private static final double MIN_SHAPE_IOU = 0.64;

    // V4.34 动态阈值降级：水果越少，遮挡/光影导致相似度下降，需要放宽阈值。
    private static final double[] FALLBACK_THRESHOLDS_V434 = {
            0.958, 0.93, 0.88, 0.83
    };

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
        ScreenOcr.Snapshot firstOcr = host.ocr("水果游戏V4.34/进入确认");
        if (host.aborted()) return Result.ABORTED;

        String firstText = normalize(firstOcr == null ? "" : firstOcr.fullText);
        if (!looksLikeFruitGame(firstText)) {
            host.log("[游戏V4.34] 当前页面不是水果配对游戏，停止视觉求解");
            return Result.NOT_FRUIT_GAME;
        }

        int remaining = parseRemaining(firstText);
        int progress = parsePercent(firstText);
        host.log("[游戏V4.34] ✅ 识别水果游戏"
                + (remaining >= 0 ? " / 剩余=" + remaining : "")
                + (progress >= 0 ? " / 进度=" + progress + "%" : ""));

        long started = SystemClock.elapsedRealtime();
        int pairActions = 0;
        int consecutiveCaptureFail = 0;

        // V4.34 本轮降级点击失败过的对子，进入黑名单不再重复尝试
        Set<String> failedPairs = new HashSet<>();

        while (!host.aborted()
                && pairActions < MAX_PAIR_ACTIONS
                && SystemClock.elapsedRealtime() - started < MAX_ROUND_MS) {

            GameFrame frame = captureFrame(context, suPath, host);
            if (frame == null) {
                consecutiveCaptureFail++;
                host.log("[游戏V4.34] 截图失败 " + consecutiveCaptureFail + "/3");
                if (consecutiveCaptureFail >= 3) return Result.SAFE_STOP;
                if (!host.sleep(250L, 420L)) return Result.ABORTED;
                continue;
            }
            consecutiveCaptureFail = 0;

            List<FruitObject> objects = detectFruitObjects(frame);

            // V4.34 动态阈值降级搜索：0.958 → 0.93 → 0.88 → 0.83
            PairChoice pair = null;
            double hitThreshold = MIN_PAIR_SCORE;
            for (double t : FALLBACK_THRESHOLDS_V434) {
                pair = chooseBestPairWithThreshold(objects, t, failedPairs);
                if (pair != null) {
                    hitThreshold = t;
                    break;
                }
            }

            host.log("[游戏V4.34] 可下落候选水果=" + objects.size()
                    + " / 顶部禁区<" + Math.round(frame.originalHeight * 0.115f)
                    + " / 底部禁区>" + Math.round(frame.originalHeight * 0.615f)
                    + (pair == null ? " / 无高置信对子" :
                    " / 最佳对子=" + format(pair.score)
                            + " rgb=" + format(pair.rgbSimilarity)
                            + " hist=" + format(pair.histCos)
                            + " shape=" + format(pair.shapeIou)
                            + " decision=" + format(pair.decisionScore)
                            + " lower=" + format(pair.lowerBoth)
                            + " threshold=" + format(hitThreshold)));

            if (pair != null && hitThreshold < MIN_PAIR_SCORE) {
                host.log("[游戏V4.34] ⚠️ 阈值降级命中 " + format(hitThreshold)
                        + " / score=" + format(pair.score)
                        + " / 剩余水果=" + objects.size());
            }

            if (objects.size() < 10) {
                saveVisionDiagnostic(context, frame.bitmap, "low_objects_" + objects.size());
                host.log("[游戏V4.34] 检测数量异常偏少，已保存视觉诊断图；本轮不会点击功能按钮");
            }

            if (pair == null) {
                safeRecycle(frame.bitmap);

                ScreenOcr.Snapshot checkpoint = host.ocr("水果游戏V4.34/无对子检查");
                if (host.aborted()) return Result.ABORTED;
                String text = normalize(checkpoint == null ? "" : checkpoint.fullText);
                if (isRoundCompleted(text)) {
                    host.log("[游戏V4.34] ✅ 已检测到一关完成状态");
                    return Result.COMPLETED;
                }

                host.log("[游戏V4.34] 所有阈值(0.958→0.83)均无对子，确认死局，安全停止");
                return Result.SAFE_STOP;
            }

            int beforeRemaining = remaining;
            int ax = mapX(frame, pair.a.centerX);
            int ay = mapY(frame, pair.a.centerY);
            FruitObject expectedB = pair.b;
            safeRecycle(frame.bitmap);

            host.log("[决策V4.34] 选择当前动作 / A=(" + ax + "," + ay + ")"
                    + " / visual=" + format(pair.score)
                    + " / decision=" + format(pair.decisionScore)
                    + " / lower=" + format(pair.lowerBoth)
                    + " / 原因=可靠同类+底层优先+释放空间");

            // 只执行一步，然后重新观察。A 下落后旧的 B 坐标立即作废。
            if (!host.tap(ax, ay, "水果游戏-配对A")) {
                return host.aborted() ? Result.ABORTED : Result.SAFE_STOP;
            }
            if (!host.sleep(300L, 480L)) return Result.ABORTED;

            GameFrame afterA = captureFrame(context, suPath, host);
            if (afterA == null) {
                host.log("[游戏V4.34] A下落后无法重新观察，安全停止");
                return Result.SAFE_STOP;
            }
            List<FruitObject> afterObjects = detectFruitObjects(afterA);
            FruitObject reacquiredB = findBestMatchingFruit(expectedB, afterObjects);
            if (reacquiredB == null) {
                safeRecycle(afterA.bitmap);
                host.log("[游戏V4.34] A下落后无法重新定位同类B；不盲点第三颗水果，安全停止");
                return Result.SAFE_STOP;
            }
            int bx = mapX(afterA, reacquiredB.centerX);
            int by = mapY(afterA, reacquiredB.centerY);
            safeRecycle(afterA.bitmap);

            host.log("[决策V4.34] A下落后重新分析 → B=(" + bx + "," + by + ")");
            if (!host.tap(bx, by, "水果游戏-配对B")) {
                return host.aborted() ? Result.ABORTED : Result.SAFE_STOP;
            }
            pairActions++;
            if (!host.sleep(520L, 820L)) return Result.ABORTED;

            // 每一对都闭环验证，不再等到第6对才发现整局没有进展。
            ScreenOcr.Snapshot verifyOcr = host.ocr("水果游戏V4.34/逐对验证#" + pairActions);
            if (host.aborted()) return Result.ABORTED;
            String verifyText = normalize(verifyOcr == null ? "" : verifyOcr.fullText);
            int afterRemaining = parseRemaining(verifyText);
            if (isRoundCompleted(verifyText)) {
                host.log("[验证V4.34] ✅ 第1关完成");
                return Result.COMPLETED;
            }
            if (afterRemaining >= 0) {
                if (beforeRemaining >= 0 && afterRemaining == beforeRemaining - 2) {
                    host.log("[验证V4.34] ✅ 配对确认：" + beforeRemaining + "→" + afterRemaining
                            + " / 本次决策有效，重新分析新局面");
                    remaining = afterRemaining;
                } else if (beforeRemaining >= 0 && afterRemaining >= beforeRemaining) {
                    failedPairs.add(pairKeyV434(pair.a, pair.b));
                    host.log("[验证V4.34] ❌ 未确认消除：" + beforeRemaining + "→" + afterRemaining
                            + " / 当前视觉组合加入黑名单；为避免填满坑位安全停止");
                    return Result.SAFE_STOP;
                } else {
                    host.log("[验证V4.34] 状态发生变化：" + beforeRemaining + "→" + afterRemaining
                            + " / 重新建图");
                    remaining = afterRemaining;
                }
            } else {
                host.log("[验证V4.34] OCR未读到剩余数；保留视觉结果并重新建图");
            }

            // OCR is intentionally sparse; screenshot vision handles most pairs.
            // Every few pairs verify that the game is still progressing / ended.
            if (pairActions % 6 == 0) {
                ScreenOcr.Snapshot checkpoint = host.ocr(
                        "水果游戏V4.34/进度检查#" + pairActions);
                if (host.aborted()) return Result.ABORTED;
                String text = normalize(checkpoint == null ? "" : checkpoint.fullText);

                int nowRemaining = parseRemaining(text);
                int nowProgress = parsePercent(text);
                host.log("[游戏V4.34] 进度检查 pair=" + pairActions
                        + (nowRemaining >= 0 ? " / 剩余=" + nowRemaining : "")
                        + (nowProgress >= 0 ? " / " + nowProgress + "%" : ""));

                if (isRoundCompleted(text)) {
                    host.log("[游戏V4.34] ✅ 第1关完成");
                    return Result.COMPLETED;
                }

                // If we unexpectedly left the fruit game, do not continue tapping.
                if (!text.isEmpty() && !looksLikeFruitGame(text)) {
                    if (looksLikeTaskPanel(text)) {
                        host.log("[游戏V4.34] 已自动返回任务面板，按完成流程交给外层验证");
                        return Result.COMPLETED;
                    }
                    host.log("[游戏V4.34] 页面已离开水果游戏，停止继续点击");
                    return Result.SAFE_STOP;
                }

                if (nowRemaining >= 0) remaining = nowRemaining;
                if (nowProgress >= 0) progress = nowProgress;
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[游戏V4.34] 达到本轮安全上限，停止自动点击");
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
        if (t.isEmpty()) return false;
        return t.contains("点击麻将对")
                || (t.contains("麻将对")
                    && (t.contains("水平相邻") || t.contains("相邻") || t.contains("试试点击")));
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

        File file = new File(dir, "xianyu_fruit_v434_" + android.os.Process.myPid() + ".png");
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
        // V4.34 real-device rule: fruit clipped/packed against the top cannot reliably
        // fall into the pit. Bottom contains remaining/progress, unlock, eliminate,
        // shuffle and the pit UI. Neither zone is ever a tap candidate.
        int roiTop = clamp(Math.round(height * 0.115f), 0, height - 1);
        int roiBottom = clamp(Math.round(height * 0.615f), roiTop + 1, height);
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

        int minBox = Math.max(34, Math.round(width * 0.060f));
        int maxBox = Math.max(minBox + 1, Math.round(width * 0.175f));

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
            if (fill < 0.28 || fill > 0.95) continue;

            // Reject anything touching the crop boundaries: those are likely
            // clipped fruit / UI fragments, not a complete droppable fruit.
            int edgeMargin = Math.max(5, Math.round(width * 0.010f));
            if (minY <= edgeMargin || maxY >= roiHeight - 1 - edgeMargin) continue;

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
        return chooseBestPairWithThreshold(objects, MIN_PAIR_SCORE, null);
    }

    /**
     * V4.34 决策引擎：不是单纯找“最像的一对”，而是在所有可靠对子中
     * 综合考虑视觉置信度、底层优先、两颗水果都处于低位、纵向跨度和释放空间。
     * 视觉阈值仍然是硬门槛，决策分只负责在“已经可靠”的对子之间排序。
     */
    private static PairChoice chooseBestPairWithThreshold(
            List<FruitObject> objects,
            double minScore,
            Set<String> blacklist
    ) {
        if (objects == null || objects.size() < 2) return null;
        PairChoice best = null;
        float maxY = 1f;
        for (FruitObject f : objects) maxY = Math.max(maxY, f.centerY);

        for (int i = 0; i < objects.size(); i++) {
            FruitObject a = objects.get(i);
            for (int j = i + 1; j < objects.size(); j++) {
                FruitObject b = objects.get(j);

                if (blacklist != null && !blacklist.isEmpty()) {
                    String key = pairKeyV434(a, b);
                    if (blacklist.contains(key)) continue;
                }

                Similarity sim = similarity(a, b);
                if (sim.rgbMad > MAX_RGB_MAD
                        || sim.histCos < MIN_HIST_COS
                        || sim.shapeIou < MIN_SHAPE_IOU
                        || sim.score < minScore) continue;

                // 两颗都越靠下越好。minY 可防止“一颗很低、一颗很高”靠平均值作弊。
                double lowerBoth = Math.min(a.centerY, b.centerY) / maxY;
                double avgLower = ((a.centerY + b.centerY) * 0.5) / maxY;
                double verticalGap = Math.abs(a.centerY - b.centerY) / maxY;
                double compactness = 1.0 - Math.min(1.0, verticalGap);

                // 视觉仍占最大权重，但底层位置成为真正的决策变量。
                double decision = 0.56 * sim.score
                        + 0.24 * lowerBoth
                        + 0.12 * avgLower
                        + 0.08 * compactness;

                PairChoice candidate = new PairChoice(a, b, sim.score,
                        1.0 - sim.rgbMad, sim.histCos, sim.shapeIou, decision,
                        lowerBoth, compactness);
                if (best == null || candidate.decisionScore > best.decisionScore) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * A 下落后重新定位原计划中的 B。不能继续使用旧坐标，因为棋盘会发生下落重排。
     * 这里只在当前重新检测到的水果中寻找与 expected 最相似、且达到可靠门槛的实例。
     */
    private static FruitObject findBestMatchingFruit(
            FruitObject expected,
            List<FruitObject> objects
    ) {
        if (expected == null || objects == null || objects.isEmpty()) return null;

        FruitObject best = null;
        double bestScore = -1.0;
        for (FruitObject candidate : objects) {
            if (candidate == null) continue;
            Similarity sim = similarity(expected, candidate);

            // 重新定位比初始配对稍宽松，但仍保持颜色/直方图/形状三重门槛，
            // 防止 A 下落后误把别的水果当成 B。
            if (sim.rgbMad > 0.075
                    || sim.histCos < 0.94
                    || sim.shapeIou < 0.72
                    || sim.score < 0.90) {
                continue;
            }

            if (sim.score > bestScore) {
                bestScore = sim.score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * V4.34 水果视觉特征指纹。用 RGB 采样 + HSV 直方图粗量化得到稳定哈希，
     * 同一水果的不同实例会得到相同 key，用于黑名单去重。
     */
    private static String pairKeyV434(FruitObject a, FruitObject b) {
        String ka = fruitKeyV434(a);
        String kb = fruitKeyV434(b);
        return ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
    }

    private static String fruitKeyV434(FruitObject f) {
        if (f == null || f.rgb == null) return "0";
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < f.rgb.length; i += 16) {
            long q = Math.round(f.rgb[i] * 16f);
            h ^= q;
            h *= 0x100000001b3L;
        }
        if (f.hist != null) {
            for (int i = 0; i < f.hist.length; i += 2) {
                long q = Math.round(f.hist[i] * 12f);
                h ^= q;
                h *= 0x100000001b3L;
            }
        }
        return Long.toHexString(h);
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
        boolean saturatedNonBlue = sat > 0.14f && (hue < 160f || hue > 220f);
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

    private static void saveVisionDiagnostic(Context context, Bitmap bitmap, String suffix) {
        if (context == null || bitmap == null || bitmap.isRecycled()) return;
        try {
            File base = context.getExternalFilesDir(null);
            if (base == null) return;
            File dir = new File(base, "xianyu_diagnostics");
            if (!dir.exists() && !dir.mkdirs()) return;
            File out = new File(dir, System.currentTimeMillis()
                    + "_fruit_vision_" + (suffix == null ? "diag" : suffix) + ".png");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                stream.flush();
            }
            File[] files = dir.listFiles((d, name) -> name != null && name.contains("_fruit_vision_"));
            if (files != null && files.length > 8) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 8; i < files.length; i++) safeDelete(files[i]);
            }
        } catch (Throwable ignored) {
        }
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
        return String.format(Locale.US, "%.3f", v);
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
        final double decisionScore;
        final double lowerBoth;
        final double compactness;

        PairChoice(FruitObject a, FruitObject b, double score,
                   double rgbSimilarity, double histCos, double shapeIou,
                   double decisionScore, double lowerBoth, double compactness) {
            this.a = a;
            this.b = b;
            this.score = score;
            this.rgbSimilarity = rgbSimilarity;
            this.histCos = histCos;
            this.shapeIou = shapeIou;
            this.decisionScore = decisionScore;
            this.lowerBoth = lowerBoth;
            this.compactness = compactness;
        }
    }
             }
