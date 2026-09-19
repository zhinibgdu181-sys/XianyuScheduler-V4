package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V4.42.0: bounded observation recovery on the stable V4.41 stack model.
 * V4.40 ROI/downscale optimizations are intentionally removed after a real-device
 * ArrayIndexOutOfBoundsException before the first fruit tap.
 *
 * Confirmed game rules used by this solver:
 * 1) two equal fruits eliminate each other;
 * 2) the center-bottom tray can hold at most three unmatched fruits;
 * 3) a clicked fruit must physically reach the tray before the next dependent click.
 *
 * The solver therefore treats the tray as first-class state. A tray fruit is matched
 * before starting a new board-board pair. With two unmatched fruits, a new type is
 * introduced only when a complete high-confidence A/B pair is already available;
 * with all three slots occupied, only a direct match to a tray fruit is permitted.
 */
final class FruitGameSolver {

    enum Result {
        COMPLETED,
        SAFE_STOP_CLEAN,
        SAFE_STOP_DIRTY,
        NOT_FRUIT_GAME,
        ABORTED
    }

    interface Host {
        boolean tap(int x, int y, String reason);
        default void onFrameSize(int width, int height) {}
        boolean sleep(long minMs, long maxMs);
        boolean aborted();
        void log(String message);
        ScreenOcr.Snapshot ocr(String reason);
    }

    private static final int ANALYSIS_WIDTH = 720;
    private static final int GRID = 32;
    private static final long SCREENSHOT_TIMEOUT_MS = 4200L;
    private static final long MAX_ROUND_MS = 30L * 60L * 1000L;
    private static final int MAX_PAIR_ACTIONS = 130;
    private static final int MAX_RECOVERY_RETRY = 3;
    private static final int MAX_NO_ACTION_RETRY = 1;
    private static final int REMAINING_OCR_INTERVAL = 3;
    // A failed fruit tap must stay blocked until a confirmed elimination changes the board.
    // The old 12s TTL expired while the eight-frame verification was still running, which
    // allowed the same covered fruit to be selected again immediately afterwards.
    private static final long BLOCKED_POSITION_TTL_MS = MAX_ROUND_MS;

    // V4.38.0：三槽二消模型。槽位在中央竖井中从下往上堆叠。
    // 这些比例来自用户提供的 709x1536 连续实机截图，并按屏幕尺寸归一化。
    private static final int TRAY_CAPACITY = 3;
    private static final float TRAY_X0_FRAC = 0.445f;
    private static final float TRAY_X1_FRAC = 0.555f;
    private static final float TRAY_TOP_Y0_FRAC = 0.710f;
    private static final float TRAY_TOP_Y1_FRAC = 0.765f;
    private static final float TRAY_MID_Y0_FRAC = 0.765f;
    private static final float TRAY_MID_Y1_FRAC = 0.815f;
    private static final float TRAY_BOTTOM_Y0_FRAC = 0.815f;
    private static final float TRAY_BOTTOM_Y1_FRAC = 0.855f;
    private static final double TRAY_OCCUPIED_RATIO_MIN = 0.20;
    // 槽中水果与棋盘水果只比较HSV直方图；槽内相邻水果会发生局部遮挡，
    // 因此不能沿用完整sprite的shape IoU门槛。实机回放同类通常 >0.99。
    private static final double TRAY_HIST_MATCH_MIN = 0.985;
    private static final int TRAY_OBSERVE_RETRIES = 4;
    private static final int UNCHANGED_TRAY_CONFIRMATIONS = 2;

    // V4.37.0：截图回放中底部同类受采样/压缩影响约0.978~0.982。
    // 总分与更严格的RGB、直方图、形状三个门槛共同判断；不逐轮降低阈值。
    private static final double MIN_PAIR_SCORE = 0.975;
    private static final double MAX_RGB_MAD = 0.035;
    private static final double MIN_HIST_COS = 0.990;
    private static final double MIN_SHAPE_IOU = 0.970;

    // V4.36.3：按实机截图标定 V 形漏斗，而不是把“底部”当成一条水平线。
    // 截图中左右斜坡外侧约从 61.2%H 开始，向中央下降到约 74.0%H；
    // 中间约 43.5%W~56.5%W 是坑洞入口。水果先竖直自由落体，碰到斜坡后再被导向中央坑洞。
    private static final float FUNNEL_OUTER_Y_FRAC = 0.612f;
    private static final float FUNNEL_INNER_Y_FRAC = 0.740f;
    private static final float FUNNEL_LEFT_INNER_X_FRAC = 0.435f;
    private static final float FUNNEL_RIGHT_INNER_X_FRAC = 0.565f;

    // 检测区必须越过斜坡外侧上沿，否则最底层水果的下半部分会被裁掉。
    // 实机图中最底层水果中心仍在约 59%H，底部可到 62%H 左右。
    private static final float DETECTION_BOTTOM_Y_FRAC = 0.655f;
    // 只把中心仍位于蓝色棋盘自由落体区的完整水果作为候选；斜坡/坑洞 UI 不进入候选。
    private static final float BOARD_CENTER_MAX_Y_FRAC = 0.625f;

    private static final double[] FALLBACK_THRESHOLDS_V436 = {
            MIN_PAIR_SCORE
    };

    private static final Pattern REMAINING_PATTERN =
            Pattern.compile("剩(?:余|餘|馀|小|数)?\\s*[:：]?\\s*(\\d{1,4})");
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
            return Result.SAFE_STOP_CLEAN;
        }
        host.log("[水果V4.44-step5] Solver启动，进入动作生成与点击验证闭环");

        if (!host.sleep(420L, 720L)) return Result.ABORTED;

        // V4.36：进入水果任务后可能经历 loading / “开始游戏”首页。
        // 不再只看一帧就判死刑；最多等待 8 秒，并在出现开始页时主动点“开始游戏”。
        int entryRecoveryCount = 0;
        int recoveryCount = 0;
        String firstText = "";
        boolean confirmed = false;
        long enterWaitStart = SystemClock.elapsedRealtime();

        while (!host.aborted()
                && SystemClock.elapsedRealtime() - enterWaitStart < 8000L) {
            try {
                ScreenOcr.Snapshot firstOcr = requireOcr(host,
                        firstText.isEmpty()
                                ? "水果游戏V4.36/进入确认"
                                : "水果游戏V4.36/加载等待");
                if (host.aborted()) return Result.ABORTED;

                firstText = normalize(firstOcr == null ? "" : firstOcr.fullText);

                if (looksLikeBlockingFunctionPopupText(firstText)) {
                    PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                            host, firstOcr, "进入确认");
                    if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                    if (popup != PopupDismissResult.DISMISSED) {
                        throw new RecoverableObservationException("OCR识别到道具弹窗但关闭失败");
                    }
                    firstText = "";
                    continue;
                }

                if (looksLikeFruitGame(firstText)
                        || (!looksLikeTaskPanel(firstText) && isRoundCompleted(firstText))) {
                    entryRecoveryCount = 0;
                    confirmed = true;
                    break;
                }

                if (looksLikeFruitStartScreen(firstText)) {
                    // OCR 已经给出了屏幕尺寸和“开始游戏”文本框。旧逻辑为了
                    // 获取同样的宽高又做一次 PNG 截图，实机上会额外阻塞约
                    // 2~3 秒，用户容易误以为程序没反应而手动点击。优先直接
                    // 使用 OCR 按钮中心；识别不到框时才使用经过白名单约束的
                    // 比例坐标，不再进行冗余截图。
                    int width = firstOcr == null ? 0 : firstOcr.width;
                    int height = firstOcr == null ? 0 : firstOcr.height;
                    if (width <= 0 || height <= 0) {
                        throw new RecoverableObservationException("开始页OCR尺寸无效");
                    }

                    ScreenOcr.Item startButton = firstOcr.findBest("开始游戏");
                    int startX = startButton == null
                            ? Math.round(width * 0.50f) : startButton.centerX();
                    int startY = startButton == null
                            ? Math.round(height * 0.75f) : startButton.centerY();

                    // OCR 偶尔会把标题区中的“开始游戏”片段当成按钮。只有落在
                    // GameTapPolicy 的启动按钮白名单范围内才采用 OCR 坐标。
                    if (!isFruitStartButtonCoordinate(startX, startY, width, height)) {
                        startX = Math.round(width * 0.50f);
                        startY = Math.round(height * 0.75f);
                    }

                    host.log("[开始页V4.43.9] 检测到‘开始游戏’页，直接点击 → "
                            + startX + "," + startY
                            + (startButton == null ? " / 比例坐标" : " / OCR按钮中心"));
                    if (host.aborted()) return Result.ABORTED;
                    if (!host.tap(startX, startY, "水果游戏-开始游戏")) {
                        if (host.aborted()) return Result.ABORTED;
                        throw new RecoverableObservationException("开始游戏点击发送失败");
                    }
                    host.log("[开始页V4.36] ✅ 已点击‘开始游戏’");
                    if (!host.sleep(420L, 680L)) return Result.ABORTED;
                    continue;
                }

                if (looksLikeTaskPanel(firstText)) {
                    host.log("[游戏V4.36] 已回到任务面板，判定未进入水果游戏");
                    return Result.NOT_FRUIT_GAME;
                }

                host.log("[游戏V4.36] 页面仍在加载/登录，继续等待");
                if (!host.sleep(600L, 900L)) return Result.ABORTED;
            } catch (RuntimeException e) {
                if (host.aborted()) return Result.ABORTED;
                if (++entryRecoveryCount > MAX_RECOVERY_RETRY) return Result.SAFE_STOP_CLEAN;
                host.log("[恢复V4.42] 进入确认重试 " + entryRecoveryCount + "/" + MAX_RECOVERY_RETRY
                        + " / " + e.getClass().getSimpleName());
                if (!host.sleep(300L, 500L)) return Result.ABORTED;
            }
        }
        if (host.aborted()) return Result.ABORTED;

        if (!confirmed) {
            host.log("[游戏V4.36] 等待 8 秒后仍未进入正式水果关卡，安全停止");
            return Result.NOT_FRUIT_GAME;
        }

        int remaining = parseRemaining(firstText);
        int progress = parsePercent(firstText);
        if (isRoundCompleted(firstText)) return Result.COMPLETED;
        // 缺失基线交给主循环重新截图/OCR，不把单帧漏识别当作停止。
        host.log("[游戏V4.36] ✅ 识别水果游戏"
                + (remaining >= 0 ? " / 剩余=" + remaining : "")
                + (progress >= 0 ? " / 进度=" + progress + "%" : ""));

        long started = SystemClock.elapsedRealtime();
        int pairActions = 0;
        int noActionRetry = 0;
        boolean recovering = false;
        boolean fruitTapAttempted = false;

        // V4.36 本轮验证失败过的对子进入黑名单，不再重复尝试
        Set<String> failedPairs = new HashSet<>();
        // 本局被证实“点击后仍停在原位”的水果位置。棋盘未变化时不再点它；
        // 一旦有一对成功消除、棋盘重新下落，就清空这些旧位置。
        Set<String> blockedPositions = new HashSet<>();
        Map<String, Long> blockedPositionTtl = new HashMap<>();

        while (!host.aborted()
                && pairActions < MAX_PAIR_ACTIONS
                && SystemClock.elapsedRealtime() - started < MAX_ROUND_MS) {
            pruneBlockedPositions(blockedPositions, blockedPositionTtl, SystemClock.elapsedRealtime());

            GameFrame frame = null;
            PostTapObservation ownedAfterA = null;
            boolean observationHealthy = false;
            try {
                frame = captureFrame(context, suPath, host);
                if (frame == null) throw new RecoverableObservationException("主循环截图失败");

                // 恢复时重新确认页面和剩余数，不沿用异常前的坐标或槽位。
                if (recovering || remaining < 2) {
                    ScreenOcr.Snapshot current = requireOcr(host, "水果V4.42/恢复页面确认");
                    if (host.aborted()) return Result.ABORTED;
                    String currentText = normalize(current.fullText);
                    if (looksLikeTaskPanel(currentText)) return Result.NOT_FRUIT_GAME;
                    if (isRoundCompleted(currentText)) return Result.COMPLETED;
                    if (!looksLikeFruitGame(currentText)) {
                        throw new RecoverableObservationException("暂未确认水果页面");
                    }
                    int currentRemaining = parseRemaining(currentText);
                    if (currentRemaining < 2) {
                        throw new RecoverableObservationException("剩余数基线暂未识别");
                    }
                    remaining = currentRemaining;
                }

                // V4.36：小游戏会在长时间无操作时自动弹出“解锁/消除/打乱”推广窗。
                // 它是异步遮挡层，不是求解器误点功能按钮。只允许点击弹窗自身右上角 X。
                // 关闭以后旧视觉决策全部作废，重新截图规划。
                if (looksLikeBlockingFunctionPopup(frame)) {
                    PopupDismissResult popup = dismissBlockingFunctionPopup(
                            context, suPath, host, frame, "主循环");
                    safeRecycle(frame.bitmap);
                    if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                    if (popup != PopupDismissResult.DISMISSED) {
                        throw new RecoverableObservationException("无法确认弹窗已关闭");
                    }
                    noActionRetry = 0;
                    host.log("[弹窗V4.36] 弹窗关闭后旧水果坐标全部作废，重新分析棋盘");
                    continue;
                }

                long visionStarted = SystemClock.elapsedRealtime();
                List<FruitObject> objects = detectFruitObjects(frame);
                host.log("[视觉V4.38.0] 检测耗时=" + (SystemClock.elapsedRealtime()-visionStarted)
                        + "ms / 候选=" + objects.size());

                // V4.38.0：棋盘可下落性与三槽状态同时进入决策层。
                DropAnalysis drop = analyzeDroppability(objects, frame.bitmap.getWidth(), frame.bitmap.getHeight());
                host.log("[下落V4.38.0] 识别水果=" + objects.size()
                        + " / 可直接下落=" + drop.droppable.size()
                        + " / 被遮挡=" + drop.blocked.size()
                        + blockedSummary(drop, frame));

                TrayState tray = detectTrayState(frame);
                host.log("[槽位V4.38.0] " + traySummary(tray));
                if (!tray.stable) {
                    safeRecycle(frame.bitmap);
                    throw new RecoverableObservationException("槽位仍处于掉落/碰撞动画");
                }

                if (recovering) {
                    host.log("[恢复V4.42] 已重新确认水果页面和稳定槽位，继续求解");
                    recovering = false;
                    recoveryCount = 0;
                }
                observationHealthy = true;

                if (objects.size() < 10) {
                    saveVisionDiagnostic(context, frame.bitmap, "low_objects_" + objects.size());
                    host.log("[游戏V4.38.0] 检测数量异常偏少，已保存视觉诊断图；不点击功能按钮");
                }

                // 第一优先级永远是“槽内已有水果 + 棋盘同类水果”。
                // 尤其三槽已满时，只允许这种一步即可二消的动作。
                TrayMatchChoice trayChoice = chooseBestTrayMatch(
                        tray, objects, frame.bitmap.getWidth(), frame.bitmap.getHeight(), blockedPositions);

                PairChoice pair = null;
                SafePushChoice safePush = null;
                double hitThreshold = MIN_PAIR_SCORE;
                // 栈模型：
                // 1) 有TOP可消时永远先消TOP；
                // 2) 0/1/2槽可启动一个已经锁定的完整A+A；
                // 3) 如果0/1槽没有现成对子，才允许一次“可证明有后手”的单水果压栈。
                //    depth=1时要求该水果直接挡住自己的同类，避免把第二格随便塞满。
                // 4) depth=2/3绝不做单水果冒险。
                if (trayChoice == null && tray.count < TRAY_CAPACITY) {
                    for (double t : FALLBACK_THRESHOLDS_V436) {
                        pair = chooseBestPairWithThreshold(
                                objects, frame.bitmap.getWidth(), frame.bitmap.getHeight(),
                                t, failedPairs, blockedPositions);
                        if (pair != null) {
                            hitThreshold = t;
                            break;
                        }
                    }
                    if (pair == null && tray.count <= 1) {
                        safePush = chooseBestSafePushV441(
                                objects, drop, tray.count,
                                frame.bitmap.getWidth(), frame.bitmap.getHeight(), blockedPositions);
                    }
                }

                host.log("[游戏V4.38.0] 参与分析对象=" + objects.size()
                        + " / 槽位=" + tray.count + "/" + TRAY_CAPACITY
                        + " / 顶部禁区<" + Math.round(frame.originalHeight * 0.115f)
                        + " / 漏斗外沿≈" + Math.round(frame.originalHeight * FUNNEL_OUTER_Y_FRAC)
                        + " / 中央入口≈" + Math.round(frame.originalHeight * FUNNEL_INNER_Y_FRAC)
                        + (trayChoice != null
                        ? " / TOP直配 hist=" + format(trayChoice.histCos)
                                + " rank=" + format(trayChoice.rank)
                        : pair != null
                        ? " / 棋盘对子=" + format(pair.score)
                                + " rgb=" + format(pair.rgbSimilarity)
                                + " hist=" + format(pair.histCos)
                                + " shape=" + format(pair.shapeIou)
                                + " decision=" + format(pair.decisionScore)
                                + " lower=" + format(pair.lowerBoth)
                                + " threshold=" + format(hitThreshold)
                        : safePush != null
                        ? " / 安全压栈 mate=" + format(safePush.mateScore)
                                + " directUnlock=" + safePush.directUnlockMate
                                + " unlock=" + safePush.unlockGain
                        : " / 无安全动作"));

                if (trayChoice == null && pair == null && safePush == null) {
                    host.log("[水果V4.44] 无可执行move: objects=" + objects.size()
                            + ", tray=" + tray.count
                            + ", blocked=" + blockedPositions.size());
                    if (noActionRetry == 0 || noActionRetry == MAX_NO_ACTION_RETRY) {
                        logNoActionTrayDiagnostics(host, frame, tray, objects, drop, blockedPositions);
                    }
                    if (noActionRetry == MAX_NO_ACTION_RETRY) {
                        saveVisionDiagnostic(context, frame.bitmap, "no_action_tray_" + tray.count);
                    }
                    safeRecycle(frame.bitmap);
                    ScreenOcr.Snapshot checkpoint = requireOcr(host,"水果游戏V4.38.0/无安全动作检查");
                    if (host.aborted()) return Result.ABORTED;
                    String text = normalize(checkpoint == null ? "" : checkpoint.fullText);
                    if (isRoundCompleted(text)) {
                        host.log("[游戏V4.38.0] ✅ 已检测到一关完成状态");
                        return Result.COMPLETED;
                    }
                    if (looksLikeBlockingFunctionPopupText(text)) {
                        PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                                host, checkpoint, "无安全动作检查");
                        if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                        if (popup != PopupDismissResult.DISMISSED) {
                            throw new RecoverableObservationException("OCR识别到道具弹窗但关闭失败");
                        }
                        noActionRetry = 0;
                        host.log("[弹窗V4.44.1] 弹窗关闭后立即重建棋盘，不计入无动作重试");
                        continue;
                    }

                    if (noActionRetry < MAX_NO_ACTION_RETRY) {
                        host.log("[水果V4.44-step5] 当前轮未生成有效动作，进入重新建模流程 retry=" + (noActionRetry + 1));
                noActionRetry++;
                        host.log("[无动作V4.42] 等待动画/棋盘刷新后重新截图 " + noActionRetry + "/"
                                + MAX_NO_ACTION_RETRY + " / objects=" + objects.size());
                        if (!host.sleep(300L, 500L)) return Result.ABORTED;
                        continue;
                    }
                    if (tray.count >= TRAY_CAPACITY) {
                        host.log("[槽位保护V4.38.0] 三槽已满且没有可直接二消的同类水果；"
                                + "禁止点击任何新类型，CLEAN安全停止");
                    } else if (tray.count == 2) {
                        host.log("[槽位保护V4.38.0] 当前2槽占用，但既没有槽位直配，"
                                + "也没有可锁定的完整棋盘对子；不做单水果冒险，CLEAN安全停止");
                    } else {
                        host.log("[游戏V4.38.0] 当前没有‘可直接下落 + 高置信同类’安全对子，CLEAN安全停止");
                    }
                    host.log("[水果V4.44-step4] 连续无动作达到阈值，准备安全停止前最后复核");
                    return fruitTapAttempted ? Result.SAFE_STOP_DIRTY : Result.SAFE_STOP_CLEAN;
                }

                noActionRetry = 0;
                host.log("[水果V4.44] move生成成功: trayChoice=" + (trayChoice != null)
                        + ", pair=" + (pair != null)
                        + ", safePush=" + (safePush != null));
                final int beforeRemaining = remaining;
                final int beforeTrayCount = tray.count;

                // ------------------------------------------------------------
                // 路径1：槽位优先匹配。只点击一个棋盘水果即可与槽中同类二消。
                // ------------------------------------------------------------
                if (trayChoice != null) {
                    FruitObject target = trayChoice.boardFruit;
                    int tx = mapX(frame, target.centerX);
                    int ty = mapY(frame, target.centerY);
                    safeRecycle(frame.bitmap);

                    host.log("[决策V4.38.0] 槽位优先二消 / 槽=" + trayChoice.trayItem.slotName
                            + " / 点击=(" + tx + "," + ty + ")"
                            + " / hist=" + format(trayChoice.histCos)
                            + " / 槽位=" + beforeTrayCount + "→期望" + Math.max(0, beforeTrayCount - 1));

                    fruitTapAttempted = true;

                    if (!host.tap(tx, ty, "水果游戏-槽位匹配")) {
                        if (host.aborted()) return Result.ABORTED;
                        throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                    }
                    if (!host.sleep(650L, 800L)) return Result.ABORTED;

                    PostTapObservation after = observeTrayAfterTap(
                            context, suPath, host, Math.max(0, beforeTrayCount - 1),
                            beforeTrayCount, "槽位匹配后");
                    if (after == null) {
                        return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                    }
                    int afterTrayCount = after.tray.count;
                    safeRecycle(after.frame.bitmap);

                    if (afterTrayCount == Math.max(0, beforeTrayCount - 1)) {
                        RemainingVerification verify = verifyPairRemaining(
                                host, beforeRemaining, pairActions + 1, "槽位直配");
                        if (verify.aborted) return Result.ABORTED;
                        if (verify.completed) return Result.COMPLETED;
                        if (!verify.confirmed) {
                            host.log("[水果V4.38.0] 槽位视觉已发生二消，但剩余数未闭环："
                                    + beforeRemaining + "→" + verify.afterRemaining + "；停止复核");
                            saveCurrentFrameDiagnostic(context, suPath, host, "tray_pair_unverified");
                            return Result.SAFE_STOP_DIRTY;
                        }
                        pairActions++;
                        remaining = verify.afterRemaining;
                        blockedPositions.clear();
                        blockedPositionTtl.clear();
                        host.log("[水果V4.38.0] ✅ 槽位二消确认：剩余 "
                                + beforeRemaining + "→" + remaining
                                + " / 槽位 " + beforeTrayCount + "→" + afterTrayCount);
                        continue;
                    }

                    // 如果高置信槽位分类仍然判错，但新水果只是安全进入了槽位，
                    // 不再追加任何点击，交给下一轮从真实槽位重新决策。
                    if (afterTrayCount == beforeTrayCount + 1 && afterTrayCount <= TRAY_CAPACITY) {
                        host.log("[槽位V4.38.0] 目标未形成二消而是进入槽位："
                                + beforeTrayCount + "→" + afterTrayCount
                                + "；立即重新建模，不再连点");
                        continue;
                    }
                    if (afterTrayCount == beforeTrayCount) {
                        markBlockedPosition(blockedPositions, blockedPositionTtl, target);
                        host.log("[槽位V4.38.0] 点击后槽位未变化；该位置加入本轮黑名单，不连续点击");
                        if (beforeTrayCount >= TRAY_CAPACITY) {
                            return Result.SAFE_STOP_DIRTY;
                        }
                        continue;
                    }

                    host.log("[槽位V4.38.0] 点击后槽位变化异常："
                            + beforeTrayCount + "→" + afterTrayCount + "，DIRTY安全停止");
                    return Result.SAFE_STOP_DIRTY;
                }

                // ------------------------------------------------------------
                // 路径2：0/1槽没有TOP直配、也没有完整A+A时，允许一次受约束压栈。
                // 只点一个水果，必须观察真实槽位变化后才重新规划；绝不连续盲点。
                // ------------------------------------------------------------
                if (safePush != null) {
                    FruitObject target = safePush.fruit;
                    int tx = mapX(frame, target.centerX);
                    int ty = mapY(frame, target.centerY);
                    safeRecycle(frame.bitmap);

                    host.log("[决策V4.41.0] 安全压栈 / depth=" + beforeTrayCount
                            + " / 点击=(" + tx + "," + ty + ")"
                            + " / mate=" + format(safePush.mateScore)
                            + " / directUnlock=" + safePush.directUnlockMate
                            + " / unlock=" + safePush.unlockGain);

                    fruitTapAttempted = true;

                    if (!host.tap(tx, ty, "水果游戏-安全压栈")) {
                        if (host.aborted()) return Result.ABORTED;
                        throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                    }
                    if (!host.sleep(650L, 800L)) return Result.ABORTED;

                    PostTapObservation afterPush = observeTrayAfterTap(
                            context, suPath, host, beforeTrayCount + 1,
                            beforeTrayCount, "安全压栈后");
                    if (afterPush == null) {
                        host.log("[栈模型V4.41.0] 压栈后无法稳定观察槽位，DIRTY安全停止");
                        return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                    }

                    int afterTrayCount = afterPush.tray.count;
                    safeRecycle(afterPush.frame.bitmap);

                    if (afterTrayCount == beforeTrayCount + 1) {
                        host.log("[栈模型V4.41.0] ✅ 压栈确认：depth "
                                + beforeTrayCount + "→" + afterTrayCount + "；重新截图规划TOP");
                        continue;
                    }

                    if (beforeTrayCount == 1 && afterTrayCount == 0) {
                        RemainingVerification verify = verifyPairRemaining(
                                host, beforeRemaining, pairActions + 1, "压栈意外命中TOP");
                        if (verify.aborted) return Result.ABORTED;
                        if (verify.completed) return Result.COMPLETED;
                        if (!verify.confirmed) return Result.SAFE_STOP_DIRTY;
                        pairActions++;
                        remaining = verify.afterRemaining;
                        blockedPositions.clear();
                        blockedPositionTtl.clear();
                        host.log("[栈模型V4.41.0] ✅ 实际发生TOP二消：剩余 "
                                + beforeRemaining + "→" + remaining);
                        continue;
                    }

                    if (afterTrayCount == beforeTrayCount) {
                        markBlockedPosition(blockedPositions, blockedPositionTtl, target);
                        host.log("[栈模型V4.41.0] 压栈点击未生效；坐标加入黑名单，重新规划");
                        continue;
                    }

                    host.log("[栈模型V4.41.0] 压栈后状态异常："
                            + beforeTrayCount + "→" + afterTrayCount + "，DIRTY安全停止");
                    return Result.SAFE_STOP_DIRTY;
                }

                // ------------------------------------------------------------
                // 路径3：槽位为0/1/2时，只要已经锁定一个完整高置信棋盘对子，
                // 就允许启动新的二消。count=2时A会暂时占满第3槽，随后B必须与A二消。
                // A必须先被视觉确认进入槽位，才允许点B。
                // ------------------------------------------------------------
                int beforeObjectCount = objects.size();
                int ax = mapX(frame, pair.a.centerX);
                int ay = mapY(frame, pair.a.centerY);
                int plannedBx = mapX(frame, pair.b.centerX);
                int plannedBy = mapY(frame, pair.b.centerY);
                FruitObject expectedB = pair.b;
                safeRecycle(frame.bitmap);

                host.log("[决策V4.38.0] 新对子 / A=(" + ax + "," + ay + ")"
                        + " / 计划B=(" + plannedBx + "," + plannedBy + ")"
                        + " / visual=" + format(pair.score)
                        + " / decision=" + format(pair.decisionScore)
                        + " / 槽位=" + beforeTrayCount
                        + " / 原因=槽位未满且完整A/B均已锁定可直接下落");

                fruitTapAttempted = true;

                if (!host.tap(ax, ay, "水果游戏-配对A")) {
                    if (host.aborted()) return Result.ABORTED;
                    throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                }
                if (!host.sleep(650L, 800L)) return Result.ABORTED;

                PostTapObservation afterAObs = observeTrayAfterTap(
                        context, suPath, host, beforeTrayCount + 1,
                        beforeTrayCount, "A点击后");
                if (afterAObs == null) {
                    host.log("[槽位V4.38.0] A后无法稳定观察槽位，DIRTY安全停止");
                    return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                }

                ownedAfterA = afterAObs;
                int afterATrayCount = afterAObs.tray.count;

                // 边界恢复：若A其实与原槽中的水果同类，它会直接完成二消，
                // 此时不应该继续点原计划B。
                if (beforeTrayCount > 0 && afterATrayCount == beforeTrayCount - 1) {
                    safeRecycle(afterAObs.frame.bitmap);
                    RemainingVerification verify = verifyPairRemaining(
                            host, beforeRemaining, pairActions + 1, "A直接命中槽位");
                    if (verify.aborted) return Result.ABORTED;
                    if (verify.completed) return Result.COMPLETED;
                    if (!verify.confirmed) return Result.SAFE_STOP_DIRTY;
                    pairActions++;
                    remaining = verify.afterRemaining;
                    blockedPositions.clear();
                    blockedPositionTtl.clear();
                    host.log("[水果V4.38.0] ✅ A直接与原槽水果二消；取消计划B");
                    continue;
                }

                if (afterATrayCount == beforeTrayCount) {
                    markBlockedPosition(blockedPositions, blockedPositionTtl, pair.a);
                    safeRecycle(afterAObs.frame.bitmap);
                    host.log("[点击验证V4.38.0] A未进入槽位 / 槽位仍=" + beforeTrayCount
                            + " / 对象基线=" + beforeObjectCount
                            + "；不点B，重新规划其它可下落水果");
                    continue;
                }

                if (afterATrayCount != beforeTrayCount + 1) {
                    safeRecycle(afterAObs.frame.bitmap);
                    host.log("[槽位V4.38.0] A后槽位变化异常："
                            + beforeTrayCount + "→" + afterATrayCount + "，DIRTY安全停止");
                    return Result.SAFE_STOP_DIRTY;
                }

                List<FruitObject> afterObjects = detectFruitObjects(afterAObs.frame);
                DropAnalysis afterDrop = analyzeDroppability(
                        afterObjects, afterAObs.frame.bitmap.getWidth(), afterAObs.frame.bitmap.getHeight());
                host.log("[点击验证V4.38.0] A已确认进入槽位 / 槽位="
                        + beforeTrayCount + "→" + afterATrayCount
                        + " / 水果=" + afterObjects.size()
                        + " / 可直接下落=" + afterDrop.droppable.size());

                // A进入槽后，B使用“全棋盘同类 + 当前可下落”重新定位，
                // 不再把旧B限制在原坐标附近。
                FruitMatch reacquired = findBestMatchingFruitGlobal(
                        expectedB, afterObjects,
                        afterAObs.frame.bitmap.getWidth(), afterAObs.frame.bitmap.getHeight(),
                        blockedPositions);
                if (reacquired == null) {
                    host.log("[水果V4.38.0] A已安全进入槽位，但当前没有可直接下落的同类B；"
                            + "保留真实槽位状态，下一轮优先找槽位匹配，不盲点");
                    safeRecycle(afterAObs.frame.bitmap);
                    continue;
                }

                FruitObject reacquiredB = reacquired.fruit;
                int bx = mapX(afterAObs.frame, reacquiredB.centerX);
                int by = mapY(afterAObs.frame, reacquiredB.centerY);
                safeRecycle(afterAObs.frame.bitmap);

                host.log("[决策V4.38.0] B全局重定位 / 原计划=("
                        + plannedBx + "," + plannedBy + ") → 新B=(" + bx + "," + by + ")"
                        + " / sim=" + format(reacquired.similarity.score)
                        + " rgb=" + format(1.0 - reacquired.similarity.rgbMad)
                        + " hist=" + format(reacquired.similarity.histCos)
                        + " shape=" + format(reacquired.similarity.shapeIou));

                fruitTapAttempted = true;

                if (!host.tap(bx, by, "水果游戏-配对B")) {
                    if (host.aborted()) return Result.ABORTED;
                    throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                }
                if (!host.sleep(700L, 850L)) return Result.ABORTED;

                PostTapObservation afterBObs = observeTrayAfterTap(
                        context, suPath, host, beforeTrayCount,
                        afterATrayCount, "B点击后");
                if (afterBObs == null) {
                    return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                }
                int afterBTrayCount = afterBObs.tray.count;
                safeRecycle(afterBObs.frame.bitmap);

                if (afterBTrayCount == beforeTrayCount) {
                    RemainingVerification verify = verifyPairRemaining(
                            host, beforeRemaining, pairActions + 1, "棋盘对子");
                    if (verify.aborted) return Result.ABORTED;
                    if (verify.completed) return Result.COMPLETED;
                    if (!verify.confirmed) {
                        failedPairs.add(pairKeyV435(pair.a, pair.b));
                        host.log("[水果V4.38.0] 槽位已回到基线，但剩余数未确认完整二消："
                                + beforeRemaining + "→" + verify.afterRemaining);
                        saveCurrentFrameDiagnostic(context, suPath, host, "pair_unverified");
                        return Result.SAFE_STOP_DIRTY;
                    }
                    pairActions++;
                    remaining = verify.afterRemaining;
                    blockedPositions.clear();
                    blockedPositionTtl.clear();
                    host.log("[水果V4.38.0] ✅ 棋盘对子确认：剩余 "
                            + beforeRemaining + "→" + remaining
                            + " / 槽位 " + beforeTrayCount + "→" + afterBTrayCount);
                    continue;
                }

                if (afterBTrayCount == beforeTrayCount + 1) {
                    markBlockedPosition(blockedPositions, blockedPositionTtl, reacquiredB);
                    host.log("[槽位V4.38.0] B未完成二消，A仍留在槽中；"
                            + "不再追点，下一轮按真实槽位继续");
                    continue;
                }

                if (afterBTrayCount > beforeTrayCount
                        && afterBTrayCount <= TRAY_CAPACITY) {
                    host.log("[槽位V4.38.0] B后出现额外未配对槽位："
                            + beforeTrayCount + "→" + afterBTrayCount
                            + "；停止连点并重新建模");
                    continue;
                }

                host.log("[槽位V4.38.0] B后槽位变化异常："
                        + beforeTrayCount + "→" + afterBTrayCount + "，DIRTY安全停止");
                return Result.SAFE_STOP_DIRTY;
            } catch (RuntimeException e) {
                if (host.aborted()) return Result.ABORTED;
                observationHealthy = false;
                recovering = true;
                if (++recoveryCount > MAX_RECOVERY_RETRY) {
                    host.log("[恢复V4.42] 三次恢复后仍失败，停止自动点击");
                    return fruitTapAttempted ? Result.SAFE_STOP_DIRTY : Result.SAFE_STOP_CLEAN;
                }
                host.log("[恢复V4.42] 重新截图/识别 " + recoveryCount + "/" + MAX_RECOVERY_RETRY
                        + " / " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (!host.sleep(300L, 500L)) return Result.ABORTED;
            } finally {
                if (observationHealthy) recoveryCount = 0;
                if (frame != null) safeRecycle(frame.bitmap);
                if (ownedAfterA != null) safeRecycle(ownedAfterA.frame.bitmap);
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[游戏V4.36] 达到本轮安全上限，停止自动点击");
        return Result.SAFE_STOP_CLEAN;
    }

    private static final class RecoverableObservationException extends RuntimeException {
        RecoverableObservationException(String message) { super(message); }
    }

    private static ScreenOcr.Snapshot requireOcr(Host host, String reason) {
        ScreenOcr.Snapshot snapshot = host.ocr(reason);
        if (snapshot == null || normalize(snapshot.fullText).isEmpty()) {
            throw new RecoverableObservationException("OCR为空: " + reason);
        }
        return snapshot;
    }

    private enum PopupDismissResult {
        NOT_PRESENT,
        DISMISSED,
        FAILED,
        ABORTED
    }

    private static void pruneBlockedPositions(Set<String> blockedPositions,
                                             Map<String, Long> blockedPositionTtl,
                                             long nowMs) {
        if (blockedPositions == null || blockedPositionTtl == null) return;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : blockedPositionTtl.entrySet()) {
            if (entry.getValue() <= nowMs) {
                expired.add(entry.getKey());
            }
        }
        for (String key : expired) {
            blockedPositions.remove(key);
            blockedPositionTtl.remove(key);
        }
    }

    private static void markBlockedPosition(Set<String> blockedPositions,
                                            Map<String, Long> blockedPositionTtl,
                                            FruitObject target) {
        if (blockedPositions == null || blockedPositionTtl == null || target == null) return;
        String key = positionKeyV4361(target);
        blockedPositions.add(key);
        blockedPositionTtl.put(key, SystemClock.elapsedRealtime() + BLOCKED_POSITION_TTL_MS);
    }

    /**
     * V4.36 空闲推广弹窗关闭器。调用方已经拿到当前帧。
     * 只点击弹窗自己的米黄色关闭 X：约 (0.866W, 0.281H)。
     * 点击后重新截图复检；命令执行成功并不等于弹窗真的消失。
     */
    private static PopupDismissResult dismissBlockingFunctionPopup(
            Context context, String suPath, Host host, GameFrame popupFrame, String stage
    ) {
        if (popupFrame == null || popupFrame.bitmap == null) return PopupDismissResult.FAILED;
        if (!looksLikeBlockingFunctionPopup(popupFrame)) return PopupDismissResult.NOT_PRESENT;
        if (host.aborted()) return PopupDismissResult.ABORTED;

        int closeX = Math.round(popupFrame.originalWidth * 0.866f);
        int closeY = Math.round(popupFrame.originalHeight * 0.281f);
        host.log("[弹窗V4.36] " + stage + " 检测到空闲功能弹窗 / 关闭X="
                + closeX + "," + closeY);

        if (!host.tap(closeX, closeY, "水果游戏-关闭道具弹窗")) {
            host.log("[弹窗V4.36] ❌ 关闭X点击发送失败");
            return host.aborted() ? PopupDismissResult.ABORTED : PopupDismissResult.FAILED;
        }

        host.log("[弹窗V4.36] 已发送关闭X点击，准备复检");
        if (!host.sleep(260L, 420L)) return PopupDismissResult.ABORTED;

        GameFrame verify = captureFrame(context, suPath, host);
        if (verify == null) {
            host.log("[弹窗V4.36] ❌ 关闭后复检截图失败");
            return PopupDismissResult.FAILED;
        }
        boolean stillPopup = looksLikeBlockingFunctionPopup(verify);
        safeRecycle(verify.bitmap);
        if (stillPopup) {
            host.log("[弹窗V4.36] ❌ 点击X后弹窗仍存在");
            return PopupDismissResult.FAILED;
        }

        host.log("[弹窗V4.36] ✅ 弹窗确认已关闭");
        return PopupDismissResult.DISMISSED;
    }

    private static PopupDismissResult dismissBlockingFunctionPopupFromOcr(
            Host host, ScreenOcr.Snapshot popup, String stage
    ) {
        if (host == null || popup == null
                || !looksLikeBlockingFunctionPopupText(popup.fullText)) {
            return PopupDismissResult.NOT_PRESENT;
        }
        if (host.aborted()) return PopupDismissResult.ABORTED;
        if (popup.width <= 0 || popup.height <= 0) return PopupDismissResult.FAILED;

        int closeX = Math.round(popup.width * 0.866f);
        int closeY = Math.round(popup.height * 0.281f);
        host.log("[弹窗V4.44.1] " + stage
                + " OCR检测到道具弹窗，立即关闭X=" + closeX + "," + closeY);
        if (!host.tap(closeX, closeY, "水果游戏-关闭道具弹窗")) {
            return host.aborted() ? PopupDismissResult.ABORTED : PopupDismissResult.FAILED;
        }
        if (!host.sleep(260L, 420L)) return PopupDismissResult.ABORTED;

        ScreenOcr.Snapshot verify;
        try {
            verify = requireOcr(host, "水果V4.44.1/道具弹窗关闭复检");
        } catch (RuntimeException e) {
            host.log("[弹窗V4.44.1] 关闭后OCR复检异常：" + e.getClass().getSimpleName());
            return PopupDismissResult.FAILED;
        }
        if (looksLikeBlockingFunctionPopupText(verify == null ? "" : verify.fullText)) {
            host.log("[弹窗V4.44.1] ❌ 点击X后OCR仍识别到道具弹窗");
            return PopupDismissResult.FAILED;
        }
        host.log("[弹窗V4.44.1] ✅ OCR确认道具弹窗已关闭");
        return PopupDismissResult.DISMISSED;
    }

    private static void saveCurrentFrameDiagnostic(
            Context context, String suPath, Host host, String suffix
    ) {
        GameFrame frame = captureFrame(context, suPath, host);
        if (frame == null) return;
        saveVisionDiagnostic(context, frame.bitmap, suffix);
        safeRecycle(frame.bitmap);
    }

    /**
     * V4.38.0 三槽检测。
     *
     * 实机槽位是中央竖井，从下往上依次堆叠。我们不把三个水果当作独立连通域，
     * 因为它们相互接触后经常会粘成一个大连通域；改为检测三个固定纵向带中的
     * “水果前景占比”。稳定状态必须满足 bottom-up 连续占用：
     * 0=[]，1=[bottom]，2=[mid,bottom]，3=[top,mid,bottom]。
     */
    private static TrayState detectTrayState(GameFrame frame) {
        if (frame == null || frame.bitmap == null) return TrayState.invalid();
        Bitmap bitmap = frame.bitmap;

        BandObservation top = observeTrayBand(
                bitmap, "TOP", TRAY_TOP_Y0_FRAC, TRAY_TOP_Y1_FRAC);
        BandObservation mid = observeTrayBand(
                bitmap, "MID", TRAY_MID_Y0_FRAC, TRAY_MID_Y1_FRAC);
        BandObservation bottom = observeTrayBand(
                bitmap, "BOTTOM", TRAY_BOTTOM_Y0_FRAC, TRAY_BOTTOM_Y1_FRAC);

        boolean topOccupied = top.ratio >= TRAY_OCCUPIED_RATIO_MIN;
        boolean midOccupied = mid.ratio >= TRAY_OCCUPIED_RATIO_MIN;
        boolean bottomOccupied = bottom.ratio >= TRAY_OCCUPIED_RATIO_MIN;

        // 槽位从下往上堆叠。出现“上层有、下层空”说明水果仍在动画途中。
        boolean stable = (!topOccupied || midOccupied)
                && (!midOccupied || bottomOccupied);

        int count = (topOccupied ? 1 : 0)
                + (midOccupied ? 1 : 0)
                + (bottomOccupied ? 1 : 0);

        List<TrayItem> items = new ArrayList<>();
        if (topOccupied) items.add(new TrayItem("TOP", top.hist, top.ratio));
        if (midOccupied) items.add(new TrayItem("MID", mid.hist, mid.ratio));
        if (bottomOccupied) items.add(new TrayItem("BOTTOM", bottom.hist, bottom.ratio));

        return new TrayState(count, stable, items, top.ratio, mid.ratio, bottom.ratio);
    }

    private static BandObservation observeTrayBand(
            Bitmap bitmap,
            String name,
            float y0Frac,
            float y1Frac
    ) {
        if (bitmap == null) return new BandObservation(name, 0.0, new float[72]);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return new BandObservation(name, 0.0, new float[72]);
        }

        int x0 = clamp(Math.round(width * TRAY_X0_FRAC), 0, width - 1);
        int x1 = clamp(Math.round(width * TRAY_X1_FRAC), x0 + 1, width);
        int y0 = clamp(Math.round(height * y0Frac), 0, height - 1);
        int y1 = clamp(Math.round(height * y1Frac), y0 + 1, height);

        int foreground = 0;
        int total = 0;
        float[] hist = new float[72];

        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = bitmap.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                total++;
                if (!isFruitForeground(r, g, b)) continue;

                foreground++;
                float[] hsv = rgbToHsv(r, g, b);
                int hBin = clamp((int) (hsv[0] / 360f * 12f), 0, 11);
                int sBin = clamp((int) (hsv[1] * 6f), 0, 5);
                hist[hBin * 6 + sBin] += 1f;
            }
        }

        normalizeL2(hist);
        double ratio = total <= 0 ? 0.0 : foreground / (double) total;
        return new BandObservation(name, ratio, hist);
    }

    private static String traySummary(TrayState tray) {
        if (tray == null) return "invalid";
        return "count=" + tray.count + "/" + TRAY_CAPACITY
                + " stable=" + tray.stable
                + " ratios(T/M/B)=" + format(tray.topRatio)
                + "/" + format(tray.midRatio)
                + "/" + format(tray.bottomRatio);
    }

    /**
     * 从当前槽位中寻找一个可与棋盘直接二消的水果。
     * 槽内sprite会被上下相邻水果遮挡，因此这里只用HSV直方图做类型匹配；
     * 棋盘目标仍必须通过完整的物理可下落检查。
     */
    private static TrayMatchChoice chooseBestTrayMatch(
            TrayState tray,
            List<FruitObject> objects,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (tray == null || !tray.stable || tray.count <= 0
                || objects == null || objects.isEmpty()) return null;

        /*
         * V4.39 栈模型：
         * tray.items 不再视为三个独立槽位。
         * 新水果落入最上层，因此只有 TOP 才能发生二消。
         *
         * 以前：
         * [西瓜, 香蕉, 桃子]
         * 会遍历三个水果，可能错误点击香蕉/桃子。
         *
         * 现在：
         * TOP=西瓜
         * 只允许寻找西瓜。
         */
        if (tray.items.isEmpty()) return null;

        TrayItem topItem = tray.items.get(0);
        if (topItem == null || topItem.hist == null) return null;

        TrayMatchChoice best = null;
        double denomY = Math.max(1.0, frameHeight);

        for (FruitObject fruit : objects) {
            if (fruit == null) continue;
            if (isBlockedPosition(blockedPositions, fruit)) continue;
            if (findNearestBlockingFruit(fruit, objects, frameWidth, frameHeight) != null) continue;

            double hist = histogramCos(topItem.hist, fruit.hist);
            if (hist < TRAY_HIST_MATCH_MIN) continue;

            double lower = clamp01(fruit.centerY / denomY);
            double rank = 0.84 * hist + 0.16 * lower;
            TrayMatchChoice candidate = new TrayMatchChoice(topItem, fruit, hist, rank);
            if (best == null || candidate.rank > best.rank) best = candidate;
        }

        return best;
    }

    /** Read-only evidence for missed TOP matches; uses the existing descriptors and drop analysis. */
    private static void logNoActionTrayDiagnostics(
            Host host, GameFrame frame, TrayState tray, List<FruitObject> objects,
            DropAnalysis drop, Set<String> blockedPositions) {
        host.log("[候选诊断V4.42.1] objects=" + objects.size()
                + " / " + traySummary(tray)
                + " / TOP直配门槛=" + String.format(Locale.US, "%.6f", TRAY_HIST_MATCH_MIN));
        if (tray.items.isEmpty() || tray.items.get(0).hist == null) {
            host.log("[候选诊断V4.42.1] 无TOP描述，不能检查槽位直配");
            return;
        }
        TrayItem top = tray.items.get(0);
        int shown = 0;
        for (FruitObject fruit : objects) {
            if (fruit == null) continue;
            if (shown++ >= 80) {
                host.log("[候选诊断V4.42.1] 候选超过80个，后续省略");
                break;
            }
            FruitObject blocker = null;
            for (BlockingRelation relation : drop.blocked) {
                if (relation.fruit == fruit) {
                    blocker = relation.blocker;
                    break;
                }
            }
            double hist = histogramCos(top.hist, fruit.hist);
            host.log("[候选诊断V4.42.1] 中心=(" + mapX(frame, fruit.centerX)
                    + "," + mapY(frame, fruit.centerY) + ")"
                    + " / TOP=" + top.slotName
                    + " / hist=" + String.format(Locale.US, "%.6f", hist)
                    + " / 颜色门槛=" + (hist >= TRAY_HIST_MATCH_MIN ? "通过" : "未通过")
                    + " / 位置黑名单=" + isBlockedPosition(blockedPositions, fruit)
                    + " / 下落=" + (blocker == null ? "畅通"
                    : "受阻于(" + mapX(frame, blocker.centerX) + ","
                            + mapY(frame, blocker.centerY) + ")"));
        }
    }

    private static double histogramCos(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0;
        return clamp01(dot / Math.sqrt(na * nb));
    }

    /**
     * 点击后等待槽位动画稳定。优先等待 expectedCount；若最终稳定在其它合法数量，
     * 也返回真实状态，让上层决定“继续建模”还是“DIRTY停止”，不再凭原位水果猜测。
     */
    private static PostTapObservation observeTrayAfterTap(
            Context context, String suPath, Host host,
            int expectedCount, int baselineCount, String stage
    ) {
        // 两轮验证只重新观察，绝不重放 tap；第一轮已包含稳定动画等待。
        for (int verification = 1; verification <= 2; verification++) {
            PostTapObservation observation = null;
            try {
                observation = observeTrayAfterTapOnce(
                        context, suPath, host, expectedCount, baselineCount, stage);
            } catch (RuntimeException e) {
                host.log("[验证V4.42] " + stage + " 观察异常: " + e.getClass().getSimpleName());
            }
            if (host.aborted()) {
                if (observation != null) safeRecycle(observation.frame.bitmap);
                return null;
            }
            if (observation != null && observation.tray.count == expectedCount) return observation;
            if (observation != null && observation.settledUnchanged) {
                host.log("[验证V4.44.0] " + stage
                        + " 连续" + UNCHANGED_TRAY_CONFIRMATIONS
                        + "帧保持原槽位，立即返回重新规划");
                return observation;
            }
            if (verification == 2) return observation;
            if (observation != null) safeRecycle(observation.frame.bitmap);
            host.log("[验证V4.42] " + stage + " 首次未确认，等待动画后进行第二次验证");
            if (!host.sleep(300L, 500L)) return null;
        }
        return null;
    }

    private static PostTapObservation observeTrayAfterTapOnce(
            Context context, String suPath, Host host,
            int expectedCount, int baselineCount, String stage
    ) {
        PostTapObservation lastStable = null;
        int consecutiveBaseline = 0;
        try {
            for (int attempt = 1; attempt <= TRAY_OBSERVE_RETRIES && !host.aborted(); attempt++) {
                GameFrame frame = captureFrame(context, suPath, host);
                try {
                    if (frame == null) {
                        if (lastStable != null) safeRecycle(lastStable.frame.bitmap);
                        lastStable = null;
                        if (!host.sleep(220L, 360L)) return null;
                        continue;
                    }
                    if (looksLikeBlockingFunctionPopup(frame)) {
                        if (lastStable != null) safeRecycle(lastStable.frame.bitmap);
                        lastStable = null;
                        PopupDismissResult popup = dismissBlockingFunctionPopup(
                                context, suPath, host, frame, stage + "/槽位观察");
                        if (popup != PopupDismissResult.DISMISSED) return null;
                        if (!host.sleep(220L, 360L)) return null;
                        continue;
                    }
                    TrayState tray = detectTrayState(frame);
                    host.log("[槽位观察V4.42] " + stage + " #" + attempt
                            + " / expected=" + expectedCount + " / " + traySummary(tray));
                    if (!tray.stable) {
                        consecutiveBaseline = 0;
                        if (lastStable != null) safeRecycle(lastStable.frame.bitmap);
                        lastStable = null;
                        if (!host.sleep(220L, 360L)) return null;
                        continue;
                    }
                    if (tray.count == expectedCount) {
                        PostTapObservation result = new PostTapObservation(frame, tray, false);
                        frame = null; // ownership transferred to caller
                        return result;
                    }
                    if (tray.count == baselineCount) {
                        consecutiveBaseline++;
                    } else {
                        consecutiveBaseline = 0;
                    }
                    if (lastStable != null) safeRecycle(lastStable.frame.bitmap);
                    lastStable = new PostTapObservation(
                            frame, tray, consecutiveBaseline >= UNCHANGED_TRAY_CONFIRMATIONS);
                    frame = null;
                    if (lastStable.settledUnchanged) {
                        PostTapObservation result = lastStable;
                        lastStable = null;
                        return result;
                    }
                    if (attempt < TRAY_OBSERVE_RETRIES && !host.sleep(260L, 420L)) return null;
                } finally {
                    if (frame != null) safeRecycle(frame.bitmap);
                }
            }
            if (host.aborted()) return null;
            PostTapObservation result = lastStable;
            lastStable = null; // ownership transferred to caller
            return result;
        } finally {
            if (lastStable != null) safeRecycle(lastStable.frame.bitmap);
        }
    }

    /**
     * V4.41 保守压栈选择器。它只解决V4.38“0槽没有现成A+A就直接退出”的死点，
     * 不改视觉、不改ROI、不改像素数组。
     *
     * depth=0：候选必须有高置信同类仍在棋盘中；优先选择能直接释放该同类的水果。
     * depth=1：更严格，必须“候选本身正挡住自己的同类”，这样压入第二格后，
     *          下一帧大概率立刻出现TOP同类，不允许无后手地把第二格塞满。
     */
    private static SafePushChoice chooseBestSafePushV441(
            List<FruitObject> objects,
            DropAnalysis drop,
            int trayCount,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (objects == null || objects.isEmpty() || drop == null || drop.droppable.isEmpty()) {
            return null;
        }
        if (trayCount < 0 || trayCount > 1) return null;

        SafePushChoice best = null;
        double denomY = Math.max(1.0, frameHeight);

        for (FruitObject fruit : drop.droppable) {
            if (fruit == null) continue;
            if (isBlockedPosition(blockedPositions, fruit)) continue;
            // Safe-push is optional, so use a wider physical corridor than ordinary pair
            // matching. This rejects edge-supported/partly covered fruit instead of
            // gambling a tray slot on it.
            if (!hasConservativeDropClearance(fruit, objects, frameWidth, frameHeight)) continue;

            double mateScore = 0.0;
            FruitObject bestMate = null;
            for (FruitObject other : objects) {
                if (other == null || other == fruit) continue;
                Similarity sim = similarity(fruit, other);
                if (sim.rgbMad <= MAX_RGB_MAD
                        && sim.histCos >= MIN_HIST_COS
                        && sim.shapeIou >= MIN_SHAPE_IOU
                        && sim.score >= MIN_PAIR_SCORE
                        && sim.score > mateScore) {
                    mateScore = sim.score;
                    bestMate = other;
                }
            }
            if (bestMate == null) continue;

            int unlockGain = 0;
            boolean directUnlockMate = false;
            for (BlockingRelation relation : drop.blocked) {
                if (relation == null || relation.blocker != fruit) continue;
                unlockGain++;
                if (relation.fruit == bestMate) directUnlockMate = true;
            }

            // depth=1时不能为了“也许以后有同类”去占第二格，必须有明确的后手。
            if (trayCount == 1 && !directUnlockMate) continue;

            double lower = clamp01(fruit.centerY / denomY);
            double rank = 0.64 * mateScore
                    + 0.18 * lower
                    + 0.10 * Math.min(1.0, unlockGain / 3.0)
                    + (directUnlockMate ? 0.08 : 0.0);

            SafePushChoice candidate = new SafePushChoice(
                    fruit, mateScore, directUnlockMate, unlockGain, rank);
            if (best == null || candidate.rank > best.rank) best = candidate;
        }
        return best;
    }

    /**
     * A落入槽位以后，棋盘可能重新排列。B不再限制在旧坐标附近；只要是当前帧中
     * 与原B视觉同类且可以直接下落的实例，都可以作为新的B。
     */
    private static FruitMatch findBestMatchingFruitGlobal(
            FruitObject expected,
            List<FruitObject> objects,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (expected == null || objects == null || objects.isEmpty()) return null;
        FruitMatch best = null;
        double denomY = Math.max(1.0, frameHeight);
        for (FruitObject candidate : objects) {
            if (candidate == null) continue;
            if (isBlockedPosition(blockedPositions, candidate)) continue;
            if (findNearestBlockingFruit(candidate, objects, frameWidth, frameHeight) != null) continue;

            Similarity sim = similarity(expected, candidate);
            if (sim.rgbMad > MAX_RGB_MAD
                    || sim.histCos < MIN_HIST_COS
                    || sim.shapeIou < MIN_SHAPE_IOU
                    || sim.score < MIN_PAIR_SCORE) continue;

            double lower = clamp01(candidate.centerY / denomY);
            double rank = 0.90 * sim.score + 0.10 * lower;
            if (best == null || rank > best.rank) {
                best = new FruitMatch(candidate, sim, rank);
            }
        }
        return best;
    }

    private static RemainingVerification verifyPairRemaining(
            Host host,
            int beforeRemaining,
            int actionIndex,
            String stage
    ) {
        if (actionIndex % REMAINING_OCR_INTERVAL != 0 && beforeRemaining > 6) {
            int estimatedRemaining = Math.max(0, beforeRemaining - 2);
            host.log("[快速验证V4.44.1] " + stage + "已由稳定槽位闭环确认；"
                    + "剩余数按 " + beforeRemaining + "→" + estimatedRemaining
                    + " 推进，第" + actionIndex + "次二消跳过OCR");
            return new RemainingVerification(true, false, false, estimatedRemaining);
        }
        int afterRemaining = -1;
        for (int verification = 1; verification <= 2; verification++) {
            if (host.aborted()) return RemainingVerification.aborted();
            try {
                ScreenOcr.Snapshot snapshot = requireOcr(host,
                        "水果V4.42/" + stage + "验证#" + actionIndex + "/" + verification);
                if (host.aborted()) return RemainingVerification.aborted();
                String text = normalize(snapshot.fullText);
                afterRemaining = parseRemaining(text);
                if (isRoundCompleted(text)) return RemainingVerification.completed(afterRemaining);
                if (PairVerification.confirmed(beforeRemaining, afterRemaining)) {
                    return new RemainingVerification(true, false, false, afterRemaining);
                }
            } catch (RuntimeException e) {
                host.log("[验证V4.42] 剩余数读取异常: " + e.getClass().getSimpleName());
            }
            if (host.aborted()) return RemainingVerification.aborted();
            if (verification == 1) {
                host.log("[验证V4.42] 首次剩余数未闭环，等待后第二次验证");
                if (!host.sleep(850L, 1150L)) return RemainingVerification.aborted();
            }
        }
        return new RemainingVerification(false, false, false, afterRemaining);
    }

    static boolean looksLikeFruitStartScreen(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        return t.contains("开始游戏")
                || (t.contains("第1关") && t.contains("开始")
                    && !t.contains("消除") && !t.contains("打乱"));
    }

    static boolean looksLikeBlockingFunctionPopupText(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        if (t.contains("解锁所有槽位")) return true;
        boolean useAction = t.contains("使用") || t.contains("立即使用") || t.contains("确认使用");
        boolean toolName = t.contains("解锁") || t.contains("消除") || t.contains("打乱");
        return useAction && toolName;
    }

    private static boolean isFruitStartButtonCoordinate(
            int x, int y, int width, int height
    ) {
        if (width <= 0 || height <= 0) return false;
        double nx = x / (double) width;
        double ny = y / (double) height;
        return nx >= 560.0 / 1440.0 && nx <= 880.0 / 1440.0
                && ny >= 2180.0 / 3120.0 && ny <= 2500.0 / 3120.0;
    }

    /**
     * 检测“解锁/消除/打乱”这类功能弹窗。
     *
     * 三类弹窗布局一致：屏幕中央会出现一块很大的暖白/米黄色面板，
     * 背景整体变暗。正常棋盘中央是蓝色天空，因此用中央区域的暖色亮像素
     * 占比可以稳定区分，不需要每轮都做一次慢 OCR。
     */
    private static boolean looksLikeBlockingFunctionPopup(GameFrame frame) {
        if (frame == null || frame.bitmap == null) return false;
        Bitmap bitmap = frame.bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return false;

        int x0 = clamp(Math.round(width * 0.18f), 0, width - 1);
        int x1 = clamp(Math.round(width * 0.82f), x0 + 1, width);
        int y0 = clamp(Math.round(height * 0.31f), 0, height - 1);
        int y1 = clamp(Math.round(height * 0.68f), y0 + 1, height);

        int step = Math.max(2, width / 240);
        int warmBright = 0;
        int samples = 0;

        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = bitmap.getPixel(x, y);
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;

                // 弹窗主体是暖白/米黄；正常天空是青蓝色。
                if (r > 205 && g > 175 && b > 115 && r - b > 20) {
                    warmBright++;
                }
                samples++;
            }
        }

        if (samples <= 0) return false;
        double ratio = warmBright / (double) samples;
        return ratio >= 0.46;
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
        String normalized = normalize(text);
        if (normalized.isEmpty()) return -1;

        Matcher m = REMAINING_PATTERN.matcher(normalized);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Throwable ignored) {
                // fall through to more permissive parsing below
            }
        }

        // OCR 里经常把“余/餘/馀/小”识别混淆；再兜底一层从“剩”字附近挖数字。
        Matcher fallback = Pattern.compile("剩(?:余|餘|馀|小|数)?\\s*[:：]?\\s*([0-9]{1,4})")
                .matcher(normalized);
        if (fallback.find()) {
            try {
                return Integer.parseInt(fallback.group(1));
            } catch (Throwable ignored) {
            }
        }
        return -1;
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
        File file = null;
        Bitmap original = null;
        Bitmap analysis = null;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) return null;
            file = File.createTempFile("xianyu_fruit_", ".png", dir);
            String path = shellQuote(file.getAbsolutePath());
            if (!runRoot(suPath, "screencap -p " + path + " && chmod 0644 " + path,
                    SCREENSHOT_TIMEOUT_MS, host) || host.aborted()) return null;
            original = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (original == null) return null;
            int ow = original.getWidth(), oh = original.getHeight();
            if (ow <= 0 || oh <= 0) return null;
            analysis = original;
            if (ow > ANALYSIS_WIDTH) {
                int ah = Math.max(1, Math.round((float)oh * ANALYSIS_WIDTH / ow));
                analysis = Bitmap.createScaledBitmap(original, ANALYSIS_WIDTH, ah, true);
                if (analysis != original) safeRecycle(original);
            }
            host.onFrameSize(ow, oh);
            GameFrame result = new GameFrame(analysis, ow, oh);
            original = null;
            analysis = null; // ownership transferred to caller
            return result;
        } catch (Exception e) {
            host.log("[水果V4.38.0] 截图失败：" + e.getClass().getSimpleName());
            return null;
        } finally {
            safeDelete(file);
            if (analysis != original) safeRecycle(analysis);
            safeRecycle(original);
        }
    }

    private static List<FruitObject> detectFruitObjects(GameFrame frame) {
        Bitmap bitmap = frame.bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // V4.36.3：检测区与“可点击自由落体区”分开。
        // 旧版直接把 ROI 截在 61.5%H（漏斗上沿），会把贴着漏斗的最底层水果
        // 下半部分裁掉，随后又因“触碰 ROI 边界”被丢弃。实机截图中最容易
        // 直接下落的底层水果恰好就在这里，因此把检测区延伸到 65.5%H。
        int roiTop = clamp(Math.round(height * 0.115f), 0, height - 1);
        int roiBottom = clamp(Math.round(height * DETECTION_BOTTOM_Y_FRAC), roiTop + 1, height);
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
        float maxBoardCenterY = height * BOARD_CENTER_MAX_Y_FRAC;
        for (Component c : components) {
            FruitObject object = buildDescriptor(
                    c, labels, pixels, width, height, roiTop, roiHeight);
            if (object == null) continue;

            // 允许水果图像的底部略微进入漏斗区域，但中心必须仍属于上方棋盘。
            // 这样能保留“贴漏斗”的最底层水果，同时排除斜坡、坑洞和底部按钮碎片。
            if (object.centerY > maxBoardCenterY) continue;
            result.add(object);
        }
        recoverTouchingFruit(result, pixels, width, height, roiTop, roiBottom);
        return result;
    }

    /** Recover touching sprites using complete, observed sprites from this frame.
     * No stored screenshot coordinates or synthetic color descriptors are used.
     * Coarse samples reject candidates cheaply; all foreground samples verify the hit.
     */
    private static void recoverTouchingFruit(List<FruitObject> result, int[] pixels,
                                             int width, int height, int roiTop, int roiBottom) {
        List<FruitObject> templates = new ArrayList<>();
        for (FruitObject f : result) {
            boolean duplicate = false;
            for (FruitObject t : templates) {
                if (similarity(f, t).score > 0.96) { duplicate = true; break; }
            }
            if (!duplicate) templates.add(f);
        }
        for (FruitObject t : templates) {
            int bw = Math.round(t.width()), bh = Math.round(t.height());
            int[] samples = new int[GRID * GRID];
            int count = 0;
            // Interior excludes edge anti-aliasing but retains texture and shading.
            for (int gy = 2; gy < GRID - 2; gy++) {
                for (int gx = 2; gx < GRID - 2; gx++) {
                    int k = gy * GRID + gx;
                    if (t.shape[k] && t.shape[k-2] && t.shape[k+2]
                            && t.shape[k-2*GRID] && t.shape[k+2*GRID]) samples[count++] = k;
                }
            }
            if (count < 180) continue;
            List<FruitObject> hits = new ArrayList<>();
            for (int y = roiTop + 8; y + bh < roiBottom - 8; y += 4) {
                if (y + bh * .5 > height * BOARD_CENTER_MAX_Y_FRAC) break;
                for (int x = 0; x + bw < width; x += 4) {
                    if (nearDetected(result, x + bw*.5f, y + bh*.5f, bw*.35f)) continue;
                    if (templateError(t, samples, count, pixels, width, x, y, bw, bh, 24) > .080) continue;
                    double bestError = .048;
                    int bestX = -1, bestY = -1;
                    for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < roiTop || xx+bw >= width || yy+bh >= roiBottom) continue;
                        double error = templateError(t, samples, count, pixels, width, xx, yy, bw, bh, count);
                        if (error < bestError) {bestError = error; bestX = xx; bestY = yy;}
                    }
                    if (bestX < 0) continue;
                    FruitObject recovered = observedTemplate(t, pixels, width, bestX, bestY, bw, bh);
                    Similarity sim = similarity(t, recovered);
                    // Full sprite check as well as core texture check. Keep pair thresholds unchanged.
                    if (sim.score < .975 || sim.rgbMad > .035 || sim.histCos < .970) continue;
                    if (!nearDetected(hits, recovered.centerX, recovered.centerY, bw*.5f)) hits.add(recovered);
                }
            }
            for (FruitObject hit : hits) {
                if (!nearDetected(result, hit.centerX, hit.centerY, bw*.5f)) result.add(hit);
            }
        }
    }

    private static boolean nearDetected(List<FruitObject> objects, float x, float y, float radius) {
        for (FruitObject f : objects) {
            if (Math.abs(f.centerX-x) < radius && Math.abs(f.centerY-y) < radius) return true;
        }
        return false;
    }

    private static double templateError(FruitObject t, int[] samples, int count, int[] pixels,
                                        int width, int x, int y, int bw, int bh, int requested) {
        double sum = 0;
        int n = Math.min(count, requested);
        for (int i = 0; i < n; i++) {
            int k = samples[i * count / n];
            int sx = x + (int)(((k % GRID + .5) * bw) / GRID);
            int sy = y + (int)(((k / GRID + .5) * bh) / GRID);
            int c = pixels[sy * width + sx];
            sum += Math.abs(((c>>16)&255)/255f - t.rgb[k*3]);
            sum += Math.abs(((c>>8)&255)/255f - t.rgb[k*3+1]);
            sum += Math.abs((c&255)/255f - t.rgb[k*3+2]);
            double cutoff = requested == 24 ? .080 : .048;
            if (sum > cutoff * n * 3) return 1.0;
        }
        return sum / (n * 3);
    }

    private static FruitObject observedTemplate(FruitObject t, int[] pixels, int width,
                                                 int x, int y, int bw, int bh) {
        float[] rgb = new float[GRID*GRID*3];
        float[] hist = new float[72];
        boolean[] shape = new boolean[GRID*GRID];
        for (int k = 0; k < shape.length; k++) {
            if (!t.shape[k]) continue;
            int sx = x + (int)(((k % GRID + .5) * bw) / GRID);
            int sy = y + (int)(((k / GRID + .5) * bh) / GRID);
            int c = pixels[sy * width + sx];
            int r=(c>>16)&255, g=(c>>8)&255, b=c&255;
            if (!isFruitForeground(r,g,b)) continue;
            shape[k]=true;
            rgb[k*3]=r/255f; rgb[k*3+1]=g/255f; rgb[k*3+2]=b/255f;
            float[] hsv=rgbToHsv(r,g,b);
            hist[clamp((int)(hsv[0]/360f*12f),0,11)*6+clamp((int)(hsv[1]*6f),0,5)]++;
        }
        normalizeL2(hist);
        return new FruitObject(x+(bw-1)*.5f,y+(bh-1)*.5f,x,y,x+bw-1,y+bh-1,rgb,shape,hist);
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

        float left = c.minX;
        float right = c.maxX;
        float top = roiTop + c.minY;
        float bottom = roiTop + c.maxY;
        float centerX = (left + right) * 0.5f;
        float centerY = (top + bottom) * 0.5f;
        return new FruitObject(centerX, centerY, left, top, right, bottom,
                rgb, shape, hist);
    }

    /**
     * V4.36.3 物理可达性。
     *
     * 实机画面不是“整屏同一个底部 Y”：左右黄色挡板构成 V 形漏斗。
     * 对每颗水果，先按它当前的 x 计算真正的自由落体终点：
     * - 左侧：落到左斜坡；
     * - 右侧：落到右斜坡；
     * - 中央开口：直接落入坑洞。
     * 只检查当前位置到这个局部终点之间是否存在其它水果。
     */
    private static DropAnalysis analyzeDroppability(
            List<FruitObject> objects,
            int frameWidth,
            int frameHeight
    ) {
        List<FruitObject> droppable = new ArrayList<>();
        List<BlockingRelation> blocked = new ArrayList<>();
        if (objects == null || objects.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            return new DropAnalysis(droppable, blocked);
        }

        for (FruitObject fruit : objects) {
            FruitObject blocker = findNearestBlockingFruit(
                    fruit, objects, frameWidth, frameHeight);
            if (blocker == null) {
                droppable.add(fruit);
            } else {
                blocked.add(new BlockingRelation(fruit, blocker));
            }
        }
        return new DropAnalysis(droppable, blocked);
    }

    /**
     * 返回 fruit 在自由落体阶段最先会撞到的水果。
     *
     * 这里有两个关键修正：
     * 1. 下落终点使用 V 形漏斗的 x-dependent floor，而不是固定 61.5%H；
     * 2. 横向碰撞宽度使用 shape mask 的“主体 8%~92% 分位宽度”，忽略叶子、透明边、
     *    高光等细碎外扩，避免把相邻列误判成完全阻挡。
     */
    private static FruitObject findNearestBlockingFruit(
            FruitObject fruit,
            List<FruitObject> objects,
            int frameWidth,
            int frameHeight
    ) {
        if (fruit == null || objects == null || frameWidth <= 0 || frameHeight <= 0) return null;

        final double dropEndY = funnelContactY(fruit.centerX, frameWidth, frameHeight);
        final double fruitRadiusX = effectiveCollisionHalfWidth(fruit);
        FruitObject nearest = null;
        double nearestY = Double.MAX_VALUE;

        for (FruitObject other : objects) {
            if (other == null || other == fruit) continue;

            double minVerticalSeparation = Math.max(3.0,
                    Math.min(fruit.height(), other.height()) * 0.14);
            if (other.centerY <= fruit.centerY + minVerticalSeparation) continue;

            // 障碍物主体已经在该水果会接触斜坡/进入坑洞的位置以下，不属于自由落体挡路。
            double otherTopCore = other.centerY - Math.max(2.0, other.height() * 0.36);
            if (otherTopCore >= dropEndY) continue;

            double otherRadiusX = effectiveCollisionHalfWidth(other);
            double centerDx = Math.abs(other.centerX - fruit.centerX);

            // 采用主体宽度后再留 8% 的擦边容差。只有明显会撞到主体才判 BLOCKED。
            // 轻微图像 bbox/叶子重叠不再一票否决。
            double collisionLimit = (fruitRadiusX + otherRadiusX) * 0.92;
            if (centerDx >= collisionLimit) continue;

            if (other.centerY < nearestY) {
                nearest = other;
                nearestY = other.centerY;
            }
        }
        return nearest;
    }

    private static boolean hasConservativeDropClearance(
            FruitObject fruit,
            List<FruitObject> objects,
            int frameWidth,
            int frameHeight
    ) {
        if (fruit == null || objects == null || frameWidth <= 0 || frameHeight <= 0) return false;
        final double dropEndY = funnelContactY(fruit.centerX, frameWidth, frameHeight);
        final double fruitRadiusX = effectiveCollisionHalfWidth(fruit);
        for (FruitObject other : objects) {
            if (other == null || other == fruit) continue;
            double minVerticalSeparation = Math.max(3.0,
                    Math.min(fruit.height(), other.height()) * 0.10);
            if (other.centerY <= fruit.centerY + minVerticalSeparation) continue;
            double otherTopCore = other.centerY - Math.max(2.0, other.height() * 0.40);
            if (otherTopCore >= dropEndY) continue;
            double collisionLimit = (fruitRadiusX + effectiveCollisionHalfWidth(other)) * 1.08;
            if (Math.abs(other.centerX - fruit.centerX) < collisionLimit) return false;
        }
        return true;
    }

    /**
     * 根据 x 计算水果自由落体阶段的终点。
     * 左右两条斜坡按实机截图做线性近似；中央开口没有斜坡，直接进入坑洞。
     */
    private static double funnelContactY(double x, int frameWidth, int frameHeight) {
        if (frameWidth <= 0 || frameHeight <= 0) return frameHeight;

        double leftInner = frameWidth * FUNNEL_LEFT_INNER_X_FRAC;
        double rightInner = frameWidth * FUNNEL_RIGHT_INNER_X_FRAC;
        double outerY = frameHeight * FUNNEL_OUTER_Y_FRAC;
        double innerY = frameHeight * FUNNEL_INNER_Y_FRAC;

        if (x <= leftInner) {
            double t = clamp01(x / Math.max(1.0, leftInner));
            return outerY + (innerY - outerY) * t;
        }
        if (x >= rightInner) {
            double denom = Math.max(1.0, frameWidth - rightInner);
            double t = clamp01((frameWidth - x) / denom);
            return outerY + (innerY - outerY) * t;
        }

        // 中央是坑洞入口；给出比斜坡内端更深的终点，仅用于判断上方水果是否挡路。
        return frameHeight * 0.86;
    }

    /**
     * 从 32x32 shape mask 估计真正参与碰撞的横向主体半宽。
     * 使用前景像素横向累计分布的 8%~92% 区间，主动忽略少量叶片/尖角/透明边。
     */
    private static double effectiveCollisionHalfWidth(FruitObject fruit) {
        if (fruit == null) return 1.0;
        if (fruit.shape == null || fruit.shape.length != GRID * GRID) {
            return Math.max(4.0, fruit.width() * 0.40);
        }

        int[] col = new int[GRID];
        int total = 0;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                if (!fruit.shape[y * GRID + x]) continue;
                col[x]++;
                total++;
            }
        }
        if (total <= 0) return Math.max(4.0, fruit.width() * 0.40);

        int lowTarget = Math.max(1, (int) Math.floor(total * 0.08));
        int highTarget = Math.max(lowTarget + 1, (int) Math.ceil(total * 0.92));
        int cumulative = 0;
        int left = 0;
        int right = GRID - 1;
        boolean leftSet = false;

        for (int x = 0; x < GRID; x++) {
            cumulative += col[x];
            if (!leftSet && cumulative >= lowTarget) {
                left = x;
                leftSet = true;
            }
            if (cumulative >= highTarget) {
                right = x;
                break;
            }
        }

        double coreFrac = (right - left + 1) / (double) GRID;
        coreFrac = Math.max(0.58, Math.min(0.90, coreFrac));
        return Math.max(4.0, fruit.width() * coreFrac * 0.5);
    }

    private static String blockedSummary(DropAnalysis drop, GameFrame frame) {
        if (drop == null || drop.blocked.isEmpty() || frame == null) return "";
        StringBuilder sb = new StringBuilder(" / 示例阻挡=");
        int shown = Math.min(3, drop.blocked.size());
        for (int i = 0; i < shown; i++) {
            BlockingRelation r = drop.blocked.get(i);
            if (i > 0) sb.append(";");
            sb.append("(")
                    .append(mapX(frame, r.fruit.centerX)).append(",")
                    .append(mapY(frame, r.fruit.centerY)).append(")<-(")
                    .append(mapX(frame, r.blocker.centerX)).append(",")
                    .append(mapY(frame, r.blocker.centerY)).append(")");
        }
        if (drop.blocked.size() > shown) sb.append("...");
        return sb.toString();
    }

    /**
     * V4.37.0 决策引擎：对全量对象检查阻挡；这里不是单纯找
     * “最像的一对”，而是在所有“可直接掉入坑洞”的可靠对子中综合考虑视觉置信度、
     * 底层优先、两颗水果都处于低位和纵向跨度。
     * 视觉阈值仍然是硬门槛，决策分只负责在“已经可靠”的对子之间排序。
     */
    private static PairChoice chooseBestPairWithThreshold(
            List<FruitObject> objects,
            int frameWidth,
            int frameHeight,
            double minScore,
            Set<String> blacklist,
            Set<String> blockedPositions
    ) {
        if (objects == null || objects.size() < 2) return null;
        PairChoice best = null;
        float maxY = 1f;
        for (FruitObject f : objects) maxY = Math.max(maxY, f.centerY);

        Set<FruitObject> clear = new HashSet<>();
        for (FruitObject f : objects) {
            if (findNearestBlockingFruit(f, objects, frameWidth, frameHeight) == null) clear.add(f);
        }
        for (int i = 0; i < objects.size(); i++) {
            FruitObject a = objects.get(i);
            if (!clear.contains(a)) continue;
            for (int j = i + 1; j < objects.size(); j++) {
                FruitObject b = objects.get(j);

                if (blockedPositions != null && !blockedPositions.isEmpty()) {
                    if (isBlockedPosition(blockedPositions, a)
                            || isBlockedPosition(blockedPositions, b)) {
                        continue;
                    }
                }

                if (blacklist != null && !blacklist.isEmpty()) {
                    String key = pairKeyV435(a, b);
                    if (blacklist.contains(key)) continue;
                }

                if (!clear.contains(b)) continue;
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

                // 受阻对象已在上方硬性排除，路径分用于记录。
                double dropScore = 1.0;

                double decision = 0.50 * sim.score
                        + 0.18 * lowerBoth
                        + 0.08 * avgLower
                        + 0.06 * compactness
                        + 0.18 * dropScore;

                PairChoice candidate = new PairChoice(
                        a.centerY >= b.centerY ? a : b, a.centerY >= b.centerY ? b : a, sim.score,
                        1.0 - sim.rgbMad, sim.histCos, sim.shapeIou, decision,
                        lowerBoth, compactness);
                if (best == null
                        || candidate.decisionScore > best.decisionScore + 0.01
                        || (Math.abs(candidate.decisionScore - best.decisionScore) <= 0.01
                        && candidate.lowerBoth > best.lowerBoth + 0.015)) {
                    best = candidate;
                }
            }
        }
        if (ENABLE_FRUIT_DECISION_DEBUG && best != null) {
            // 决策由上层统一输出，保留此处作为V4.42.4评分入口。
        }
        return best;
    }

    /**
     * A 下落后重新定位原计划中的 B。不能继续使用旧坐标，因为棋盘会发生下落重排。
     * 这里只在当前重新检测到的水果中寻找与 expected 最相似、且达到可靠门槛的实例。
     */
    private static FruitMatch findBestMatchingFruit(
            FruitObject expected,
            List<FruitObject> objects,
            int frameWidth,
            int frameHeight
    ) {
        if (expected == null || objects == null || objects.isEmpty()) return null;

        FruitMatch best = null;
        double maxDx = Math.max(18.0, frameWidth * 0.13);
        double maxUp = Math.max(12.0, frameHeight * 0.035);
        double maxDown = Math.max(30.0, frameHeight * 0.20);

        for (FruitObject candidate : objects) {
            if (candidate == null) continue;
            if (findNearestBlockingFruit(candidate, objects, frameWidth, frameHeight) != null) continue;

            double dx = Math.abs(candidate.centerX - expected.centerX);
            double dy = candidate.centerY - expected.centerY;
            // 点击 A 以后，其它水果应主要向下落；不允许在全屏范围凭“长得像”抢一个 B。
            if (dx > maxDx || dy < -maxUp || dy > maxDown) continue;

            Similarity sim = similarity(expected, candidate);
            if (sim.rgbMad > MAX_RGB_MAD
                    || sim.histCos < MIN_HIST_COS
                    || sim.shapeIou < MIN_SHAPE_IOU
                    || sim.score < MIN_PAIR_SCORE) {
                continue;
            }

            double spatial = 1.0
                    - 0.60 * Math.min(1.0, dx / maxDx)
                    - 0.40 * Math.min(1.0, Math.max(0.0, dy) / maxDown);
            double rank = 0.88 * sim.score + 0.12 * spatial;
            if (best == null || rank > best.rank) {
                best = new FruitMatch(candidate, sim, rank);
            }
        }
        return best;
    }

    /**
     * 在指定位置附近确认某颗水果是否仍存在。用于验证 A 点击是否真正生效。
     */
    private static FruitMatch findMatchingFruitNear(
            FruitObject expected,
            List<FruitObject> objects,
            float anchorX,
            float anchorY,
            int frameWidth,
            int frameHeight,
            double maxDxRatio,
            double maxDyRatio,
            double minScore
    ) {
        if (expected == null || objects == null) return null;
        double maxDx = Math.max(12.0, frameWidth * maxDxRatio);
        double maxDy = Math.max(12.0, frameHeight * maxDyRatio);
        FruitMatch best = null;
        for (FruitObject candidate : objects) {
            if (candidate == null) continue;
            double dx = Math.abs(candidate.centerX - anchorX);
            double dy = Math.abs(candidate.centerY - anchorY);
            if (dx > maxDx || dy > maxDy) continue;
            Similarity sim = similarity(expected, candidate);
            if (sim.score < minScore || sim.histCos < 0.98 || sim.shapeIou < 0.84) continue;
            double rank = sim.score - 0.03 * (dx / maxDx) - 0.02 * (dy / maxDy);
            if (best == null || rank > best.rank) best = new FruitMatch(candidate, sim, rank);
        }
        return best;
    }

    private static String positionKeyV4361(FruitObject f) {
        if (f == null) return "0,0";
        // 约 12px 分桶；棋盘没变化时足以稳定识别同一个不可点击位置。
        int qx = Math.round(f.centerX / 12f);
        int qy = Math.round(f.centerY / 12f);
        return qx + "," + qy;
    }

    private static boolean isBlockedPosition(Set<String> blockedPositions, FruitObject fruit) {
        return fruit != null && isBlockedPosition(blockedPositions, fruit.centerX, fruit.centerY);
    }

    static boolean isBlockedPosition(Set<String> blockedPositions, float centerX, float centerY) {
        if (blockedPositions == null || blockedPositions.isEmpty()) return false;
        int qx = Math.round(centerX / 12f);
        int qy = Math.round(centerY / 12f);
        // Detection centers can move by a few pixels between screenshots. Check adjacent
        // buckets so the same covered fruit cannot escape the blacklist at a boundary.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (blockedPositions.contains((qx + dx) + "," + (qy + dy))) return true;
            }
        }
        return false;
    }

    /**
     * V4.36 水果视觉特征指纹。用 RGB 采样 + HSV 直方图粗量化得到稳定哈希，
     * 同一水果的不同实例会得到相同 key，用于黑名单去重。
     */
    private static String pairKeyV435(FruitObject a, FruitObject b) {
        String ka = fruitKeyV435(a);
        String kb = fruitKeyV435(b);
        return ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
    }

    private static String fruitKeyV435(FruitObject f) {
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

    private static boolean runRoot(String suPath, String command, long timeoutMs, Host host) {
        return RootCommandRunner.run(suPath, command, timeoutMs, host::aborted);
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
        final float left;
        final float top;
        final float right;
        final float bottom;
        final float[] rgb;
        final boolean[] shape;
        final float[] hist;

        FruitObject(float centerX, float centerY,
                    float left, float top, float right, float bottom,
                    float[] rgb, boolean[] shape, float[] hist) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.rgb = rgb;
            this.shape = shape;
            this.hist = hist;
        }

        float width() {
            return Math.max(1f, right - left + 1f);
        }

        float height() {
            return Math.max(1f, bottom - top + 1f);
        }
    }

    private static final class BandObservation {
        final String name;
        final double ratio;
        final float[] hist;

        BandObservation(String name, double ratio, float[] hist) {
            this.name = name;
            this.ratio = ratio;
            this.hist = hist;
        }
    }

    private static final class TrayItem {
        final String slotName;
        final float[] hist;
        final double foregroundRatio;

        TrayItem(String slotName, float[] hist, double foregroundRatio) {
            this.slotName = slotName;
            this.hist = hist;
            this.foregroundRatio = foregroundRatio;
        }
    }

    private static final class TrayState {
        final int count;
        final boolean stable;
        final List<TrayItem> items;
        final double topRatio;
        final double midRatio;
        final double bottomRatio;

        TrayState(
                int count,
                boolean stable,
                List<TrayItem> items,
                double topRatio,
                double midRatio,
                double bottomRatio
        ) {
            this.count = count;
            this.stable = stable;
            this.items = items;
            this.topRatio = topRatio;
            this.midRatio = midRatio;
            this.bottomRatio = bottomRatio;
        }

        static TrayState invalid() {
            return new TrayState(0, false, new ArrayList<>(), 0.0, 0.0, 0.0);
        }
    }

    private static final class TrayMatchChoice {
        final TrayItem trayItem;
        final FruitObject boardFruit;
        final double histCos;
        final double rank;

        TrayMatchChoice(TrayItem trayItem, FruitObject boardFruit, double histCos, double rank) {
            this.trayItem = trayItem;
            this.boardFruit = boardFruit;
            this.histCos = histCos;
            this.rank = rank;
        }
    }

    private static final class PostTapObservation {
        final GameFrame frame;
        final TrayState tray;
        final boolean settledUnchanged;

        PostTapObservation(GameFrame frame, TrayState tray, boolean settledUnchanged) {
            this.frame = frame;
            this.tray = tray;
            this.settledUnchanged = settledUnchanged;
        }
    }

    private static final class RemainingVerification {
        final boolean confirmed;
        final boolean completed;
        final boolean aborted;
        final int afterRemaining;

        RemainingVerification(
                boolean confirmed,
                boolean completed,
                boolean aborted,
                int afterRemaining
        ) {
            this.confirmed = confirmed;
            this.completed = completed;
            this.aborted = aborted;
            this.afterRemaining = afterRemaining;
        }

        static RemainingVerification completed(int afterRemaining) {
            return new RemainingVerification(true, true, false, afterRemaining);
        }

        static RemainingVerification aborted() {
            return new RemainingVerification(false, false, true, -1);
        }
    }

    private static final class BlockingRelation {
        final FruitObject fruit;
        final FruitObject blocker;

        BlockingRelation(FruitObject fruit, FruitObject blocker) {
            this.fruit = fruit;
            this.blocker = blocker;
        }
    }

    private static final class DropAnalysis {
        final List<FruitObject> droppable;
        final List<BlockingRelation> blocked;

        DropAnalysis(List<FruitObject> droppable, List<BlockingRelation> blocked) {
            this.droppable = droppable;
            this.blocked = blocked;
        }
    }

    private static final class SafePushChoice {
        final FruitObject fruit;
        final double mateScore;
        final boolean directUnlockMate;
        final int unlockGain;
        final double rank;

        SafePushChoice(FruitObject fruit, double mateScore, boolean directUnlockMate,
                       int unlockGain, double rank) {
            this.fruit = fruit;
            this.mateScore = mateScore;
            this.directUnlockMate = directUnlockMate;
            this.unlockGain = unlockGain;
            this.rank = rank;
        }
    }

    private static final class FruitMatch {
        final FruitObject fruit;
        final Similarity similarity;
        final double rank;

        FruitMatch(FruitObject fruit, Similarity similarity, double rank) {
            this.fruit = fruit;
            this.similarity = similarity;
            this.rank = rank;
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

    private static final boolean ENABLE_FRUIT_DECISION_DEBUG = false;
}
