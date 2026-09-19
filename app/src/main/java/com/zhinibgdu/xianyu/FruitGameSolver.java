package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;
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

    private enum RestartResult {
        RESTARTED,
        NOT_AVAILABLE,
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
    private static final int MAX_DEADLOCK_RESTARTS = 2;
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
    private static final double TRAY_HIST_MATCH_MIN = 0.975;
    private static final int TRAY_OBSERVE_RETRIES = 4;
    private static final int UNCHANGED_TRAY_CONFIRMATIONS = 2;
    // A fruit tap may trigger a cascade: the clicked fruit leaves, one or more
    // fruits above it can then fall into the tray. Do not return the post-tap
    // observation on the first matching frame; require two settled frames.
    private static final int POST_TAP_SETTLED_CONFIRMATIONS = 2;
    private static final int FAST_REACQUIRE_RADIUS_PX = 24;

    // V4.53：动作后不再固定空等 650~850ms。下一阶段本身会立刻截图并做
    // 连续稳定帧确认，因此这里仅保留动画启动缓冲；若仍在动画中，由观察器继续等待。
    private static final long DIRECT_TAP_SETTLE_MIN_MS = 300L;
    private static final long DIRECT_TAP_SETTLE_MAX_MS = 440L;
    private static final long PAIR_B_TAP_SETTLE_MIN_MS = 360L;
    private static final long PAIR_B_TAP_SETTLE_MAX_MS = 500L;
    private static final long IDLE_POPUP_PROBE_INTERVAL_MS = 1600L;
    // A clean screenshot/OCR obtained immediately before planning is also a popup gate.
    // Re-running full-screen OCR for the same frame cost 2~3 seconds on the real device.
    private static final long PRE_TAP_POPUP_CLEAN_TTL_MS = 3500L;
    private static final String FRUIT_FEATURE_PREFS = "xianyu_task_profiles_v48";
    private static final String FRUIT_FEATURE_PREFIX = "fruit_drop_feature_v1_";

    // V4.37.0：截图回放中底部同类受采样/压缩影响约0.978~0.982。
    // 总分与更严格的RGB、直方图、形状三个门槛共同判断；不逐轮降低阈值。
    private static final double MIN_PAIR_SCORE = 0.975;
    private static final double MAX_RGB_MAD = 0.035;
    private static final double MIN_HIST_COS = 0.990;
    private static final double MIN_SHAPE_IOU = 0.970;

    // V4.44.2：过桥压栈允许同类水果因旋转/叶片方向产生更大的形状差异，
    // 但颜色直方图仍保持高门槛，避免把柠檬、橙子等近色水果混为一类。
    private static final double BRIDGE_MIN_PAIR_SCORE = 0.930;
    private static final double BRIDGE_MAX_RGB_MAD = 0.045;
    private static final double BRIDGE_MIN_HIST_COS = 0.975;
    private static final double BRIDGE_MIN_SHAPE_IOU = 0.800;

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
        host.log("[水果V4.55] Solver启动；启用自适应短等待、死局重开和可下落特征学习：单水果 "
                + DIRECT_TAP_SETTLE_MIN_MS + "~" + DIRECT_TAP_SETTLE_MAX_MS
                + "ms / B "
                + PAIR_B_TAP_SETTLE_MIN_MS + "~" + PAIR_B_TAP_SETTLE_MAX_MS
                + "ms / 异步弹窗探测间隔=" + IDLE_POPUP_PROBE_INTERVAL_MS + "ms");

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

                /*
                 * V4.48：先确认水果游戏主体已经进入，再处理覆盖在其上的道具弹窗。
                 * 真实设备上弹窗会盖住已经运行的第1关；不能因为OCR同时看到
                 * “解锁/消除/打乱”就把整个页面判成“尚未进入游戏”。
                 */
                boolean fruitSurface = looksLikeFruitGame(firstText)
                        || (!looksLikeTaskPanel(firstText) && isRoundCompleted(firstText));
                if (fruitSurface) {
                    entryRecoveryCount = 0;
                    confirmed = true;

                    if (looksLikeBlockingFunctionPopupText(firstText)) {
                        PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                                host, firstOcr, "已进入水果游戏/入口弹窗");
                        if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                        if (popup != PopupDismissResult.DISMISSED) {
                            throw new RecoverableObservationException("水果游戏已进入但道具弹窗关闭失败");
                        }
                        firstText = "";
                        host.log("[游戏V4.48] ✅ 已确认水果游戏主体；入口道具弹窗已连续关闭，立即进入求解");
                    } else {
                        host.log("[游戏V4.48] ✅ 已确认水果游戏主体，无入口道具弹窗，立即进入求解");
                    }
                    break;
                }

                if (looksLikeBlockingFunctionPopupText(firstText)) {
                    // 尚未确认水果主体时，弹窗才属于入口恢复事件。
                    // 已确认水果主体后，弹窗只是覆盖层，不再阻塞进入游戏状态机。
                    PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                            host, firstOcr, "入口等待/覆盖弹窗");
                    if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                    if (popup != PopupDismissResult.DISMISSED) {
                        throw new RecoverableObservationException("入口道具弹窗关闭失败");
                    }
                    firstText = "";
                    continue;
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
        int deadlockRestarts = 0;
        boolean recovering = false;
        boolean fruitTapAttempted = false;
        // V4.45.1：道具推广弹窗可能在长时间无操作后异步随机出现。
        // 不能只在“准备安全停止”时检查；游戏运行期间按1600ms节奏用OCR探测。
        long lastIdlePopupProbeAt = 0L;
        final long idlePopupProbeIntervalMs = IDLE_POPUP_PROBE_INTERVAL_MS;

        // 槽位计数表示“已占用槽”，不是“已解锁槽”。
        // 不允许把底部“解锁”按钮误判成当前棋盘的容量上限；
        // 只要当前局面存在安全压栈/二消解法，就优先按棋盘解法执行。

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

                // V4.45.1：持续监听异步道具弹窗。该弹窗可能在长时间无操作后突然出现，
                // 不能依赖“准备退出”阶段才检查；运行期间每约850ms主动做一次OCR探测。
                long nowForPopupProbe = SystemClock.elapsedRealtime();
                if (nowForPopupProbe - lastIdlePopupProbeAt >= idlePopupProbeIntervalMs) {
                    ScreenOcr.Snapshot popupProbe = requireOcr(host,
                            "水果V4.45.1/运行中弹窗监听");
                    if (host.aborted()) return Result.ABORTED;
                    String popupText = normalize(popupProbe == null ? "" : popupProbe.fullText);
                    if (looksLikeBlockingFunctionPopupText(popupText)) {
                        host.log("[弹窗V4.45.1] 运行中OCR发现异步道具弹窗，立即进入连续关闭流程");
                        PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                                host, popupProbe, "运行中监听");
                        if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                        if (popup != PopupDismissResult.DISMISSED) {
                            throw new RecoverableObservationException("运行中道具弹窗关闭失败");
                        }
                        noActionRetry = 0;
                        recovering = true;
                        host.log("[弹窗V4.45.1] ✅ 异步道具弹窗已关闭，当前视觉状态全部作废，重新截图建模");
                        continue;
                    }
                    lastIdlePopupProbeAt = SystemClock.elapsedRealtime();
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
                // The current bitmap has just passed the visual popup detector. Keep its
                // timestamp so the click path does not OCR the same surface again.
                lastIdlePopupProbeAt = SystemClock.elapsedRealtime();

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

                // 只将已经由几何分析确认的“无遮挡/被遮挡”作为先验；后续点击
                // 仍必须通过槽位和剩余数闭环，特征库绝不绕过物理安全判断。
                FruitDropFeatureStore featureStore = new FruitDropFeatureStore(context,
                        frame.bitmap.getWidth(), frame.bitmap.getHeight());

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

                // V4.51：直接槽位匹配被挡住时，先沿阻挡链反向找真正可点击的根节点。
                // 这条路径对1/2/3槽都有效；3槽也允许，因为最终目标本身会与槽内同类二消。
                if (trayChoice == null && tray.count > 0) {
                    SafePushChoice unblockPush = chooseBestTrayUnblockPush(
                            tray, objects, drop, tray.count,
                            frame.bitmap.getWidth(), frame.bitmap.getHeight(),
                            blockedPositions);
                    if (unblockPush != null) {
                        safePush = unblockPush;
                        host.log("[规划V4.51] 找到槽位同类反向解阻链：槽="
                                + unblockPush.dependencySlot
                                + " / chainDepth=" + unblockPush.dependencyDepth
                                + " / match=" + format(unblockPush.mateScore)
                                + " / rootCascade=" + unblockPush.cascadeFollowers);
                    }
                }

                // 栈模型：
                // 1) 优先处理“棋盘水果 + 任意已占槽水果”的直接二消；
                // 2) 没有直接二消/解阻链时，0/1/2槽才可启动已经证明有后手的安全压栈；
                // 3) 没有严格A+A时允许“可证明有后手”的过桥压栈；
                // 4) 3槽已满时禁止引入任何新类型，只允许与TOP/MID/BOTTOM任一槽位直配。
                if (trayChoice == null && safePush == null && tray.count < TRAY_CAPACITY) {
                    for (double t : FALLBACK_THRESHOLDS_V436) {
                        pair = chooseBestPairWithThreshold(
                                objects, frame.bitmap.getWidth(), frame.bitmap.getHeight(),
                                t, failedPairs, blockedPositions);
                        if (pair != null) {
                            hitThreshold = t;
                            break;
                        }
                    }
                    if (pair == null && tray.count <= 2) {
                        safePush = chooseBestSafePushV441(
                                objects, drop, tray,
                                frame.bitmap.getWidth(), frame.bitmap.getHeight(), blockedPositions);

                        // V4.46：单纯“找同类后手”仍然会漏掉真正需要解锁的局面。
                        // 例如：A 与槽内/另一颗水果高度相似，但 A 被 B 压住；B 本身可安全下落。
                        // 这时正确动作不是随便压一个“看起来有后手”的水果，而是先点 B，
                        // 释放 A，再让 A 完成下一步二消。这里把这种依赖链作为独立候选参与竞争。
                        SafePushChoice dependencyPush = chooseBestDependencyPushV446(
                                objects, drop, tray, tray.count,
                                frame.bitmap.getWidth(), frame.bitmap.getHeight(),
                                blockedPositions);
                        if (dependencyPush != null
                                && (safePush == null || dependencyPush.rank > safePush.rank)) {
                            safePush = dependencyPush;
                            host.log("[规划V4.46] 选择依赖链压栈：先解除阻挡，再执行后手二消");
                        }

                        // 0/1槽时不能因为暂时看不到完整对子就停几十秒。允许只占用
                        // 一个空槽的探索点击，优先选择能释放最多上层水果的底层目标。
                        // 2槽不走此路径：最后一槽必须已经有可直接点击的同类后手。
                        if (safePush == null && tray.count <= 1) {
                            safePush = chooseBestExplorationPush(
                                    objects, drop, tray.count,
                                    frame.bitmap.getWidth(), frame.bitmap.getHeight(), featureStore,
                                    blockedPositions);
                            if (safePush != null) {
                                host.log("[规划V4.54] 当前仅" + tray.count
                                        + "槽占用，使用一个空槽探索解阻；第三槽仍保留给确定二消");
                            }
                        }
                    }
                }

                host.log("[游戏V4.38.0] 参与分析对象=" + objects.size()
                        + " / 槽位=" + tray.count + "/" + TRAY_CAPACITY
                        + " / 顶部禁区<" + Math.round(frame.originalHeight * 0.115f)
                        + " / 漏斗外沿≈" + Math.round(frame.originalHeight * FUNNEL_OUTER_Y_FRAC)
                        + " / 中央入口≈" + Math.round(frame.originalHeight * FUNNEL_INNER_Y_FRAC)
                        + (trayChoice != null
                        ? " / 槽位直配[" + trayChoice.trayItem.slotName + "] hist="
                                + format(trayChoice.histCos)
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
                                + " mateReady=" + safePush.mateDroppable
                                + " continuationPairs=" + safePush.continuationPairs
                                + " unlock=" + safePush.unlockGain
                                + (safePush.dependencyDepth > 0
                                ? " unblockDepth=" + safePush.dependencyDepth
                                + " unblockSlot=" + safePush.dependencySlot
                                : "")
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
                    // 2/3 或 3/3 时已经没有可点的直配、完整对子或安全解阻链。
                    // 旧版会在这里被异步弹窗的“关闭→重新建模”循环拖住数分钟。
                    // 先用本帧OCR确认仍是水果页，随后只给一次短弹窗机会；仍无解才重开。
                    if (tray.count >= 2) {
                        RestartResult restart = restartDeadlockedRound(host, checkpoint, deadlockRestarts);
                        if (restart == RestartResult.ABORTED) return Result.ABORTED;
                        if (restart == RestartResult.RESTARTED) {
                            deadlockRestarts++;
                            remaining = -1;
                            noActionRetry = 0;
                            recovering = true;
                            fruitTapAttempted = false;
                            failedPairs.clear();
                            blockedPositions.clear();
                            blockedPositionTtl.clear();
                            host.log("[死局重开V4.55] ✅ 已开始新局，清空旧棋盘坐标并立即重建模型");
                            continue;
                        }
                    }
                    // 这里不能因为画面上存在“解锁”按钮就直接去看视频。
                    // 先前的错误逻辑把 tray.count==1 当成“第二槽锁定”，
                    // 从而跳过了本应执行的“葡萄压入第2槽 → 椰子二消”解法。
                    // 当前决策层已经把所有安全棋盘动作筛完；只有没有安全动作时才停止，
                    // 不主动观看视频广告破坏正常解法。
                    if (tray.count >= TRAY_CAPACITY) {
                        host.log("[槽位保护V4.38.0] 三槽已满且没有可直接二消的同类水果；"
                                + "禁止点击任何新类型，CLEAN安全停止");
                    } else if (tray.count == 2) {
                        host.log("[槽位保护V4.38.0] 当前2槽占用，但既没有槽位直配，"
                                + "也没有可锁定的完整棋盘对子；不做单水果冒险，CLEAN安全停止");
                    } else {
                        host.log("[游戏V4.38.0] 当前没有‘可直接下落 + 高置信同类’安全对子，CLEAN安全停止");
                    }
                    /*
                     * V4.45：这里是之前真正漏弹窗的地方。
                     *
                     * 道具弹窗不是一定在“无动作”那一帧出现。实机日志已经证明：
                     * 无动作复核时还是干净页面，约2秒后的“安全停止#1”OCR才出现
                     * “解锁所有檀位 / 使用 / 打乱”。如果此处直接 return，弹窗就被
                     * 外层页面探针看见了，但已经没有水果求解器来负责点击X。
                     *
                     * 因此安全停止前必须做一个短暂的弹窗监听窗口，而且无论本轮是否
                     * 发生过水果点击都要执行。只要OCR确认道具弹窗，就进入统一的
                     * 连续关闭链：第1个、第2个、第3个……直到连续两次干净。
                     */
                    if (!host.sleep(260L, 420L)) return Result.ABORTED;

                    boolean popupHandled = false;
                    // V4.52：弹窗可能在“无动作检查”结束后约2~3秒才异步出现。
                    // 原来只观察3轮，第三轮刚结束就返回，随后页面探针才看见弹窗，
                    // 此时水果求解器已经退出，导致弹窗无人关闭。
                    // 延长安全停止前监听窗口，仍然只关闭道具弹窗，不触碰解锁/广告。
                    final int SAFE_STOP_POPUP_WATCHES = 6;
                    for (int popupWatch = 0; popupWatch < SAFE_STOP_POPUP_WATCHES; popupWatch++) {
                        ScreenOcr.Snapshot lateCheckpoint;
                        try {
                            lateCheckpoint = requireOcr(
                                    host, "水果V4.45/安全停止前弹窗监听#" + (popupWatch + 1));
                        } catch (RuntimeException e) {
                            lateCheckpoint = null;
                            host.log("[弹窗V4.45] 监听OCR异常："
                                    + e.getClass().getSimpleName());
                        }

                        String lateText = lateCheckpoint == null ? "" : lateCheckpoint.fullText;
                        if (looksLikeBlockingFunctionPopupText(lateText)) {
                            PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                                    host, lateCheckpoint,
                                    "安全停止前弹窗监听#" + (popupWatch + 1));
                            if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                            if (popup == PopupDismissResult.DISMISSED) {
                                popupHandled = true;
                                noActionRetry = 0;
                                host.log("[弹窗V4.45] ✅ 晚到道具弹窗已完整关闭，重新建模");
                                break;
                            }
                            return Result.SAFE_STOP_DIRTY;
                        }

                        // 干净不代表后面不会淡入；继续观察下一帧。
                        // 最后一轮也不再提前退出，让异步弹窗有机会在窗口内出现。
                        if (popupWatch < SAFE_STOP_POPUP_WATCHES - 1
                                && !host.sleep(420L, 620L)) {
                            return Result.ABORTED;
                        }
                    }

                    if (popupHandled) {
                        continue;
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

                    if (handlePopupBeforeFruitTap(host, "槽位匹配", lastIdlePopupProbeAt)) {
                        safeRecycle(frame.bitmap);
                        frame = null;
                        recovering = true;
                        noActionRetry = 0;
                        continue;
                    }
                    fruitTapAttempted = true;

                    if (!host.tap(tx, ty, "水果游戏-槽位匹配")) {
                        if (host.aborted()) return Result.ABORTED;
                        throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                    }
                    if (!host.sleep(DIRECT_TAP_SETTLE_MIN_MS, DIRECT_TAP_SETTLE_MAX_MS)) return Result.ABORTED;

                    PostTapObservation after = observeTrayAfterTap(
                            context, suPath, host, Math.max(0, beforeTrayCount - 1),
                            beforeTrayCount, "槽位匹配后");
                    if (after == null) {
                        return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                    }
                    int afterTrayCount = after.tray.count;
                    lastIdlePopupProbeAt = SystemClock.elapsedRealtime();
                    safeRecycle(after.frame.bitmap);

                    if (afterTrayCount >= 0 && afterTrayCount <= TRAY_CAPACITY) {
                        // V4.50：一次点击可能“二消 + 自动补槽”。因此最终槽位数
                        // 不一定等于 before-1；剩余数下降2才是消除是否真实发生的最终证据。
                        boolean exactTrayEvidence = afterTrayCount
                                == Math.max(0, beforeTrayCount - 1);
                        RemainingVerification verify = verifyPairRemaining(
                                host, beforeRemaining, pairActions + 1, "槽位直配",
                                !exactTrayEvidence);
                        if (verify.aborted) return Result.ABORTED;
                        if (verify.completed) return Result.COMPLETED;
                        if (verify.confirmed) {
                            pairActions++;
                            remaining = verify.afterRemaining;
                            blockedPositions.clear();
                            blockedPositionTtl.clear();
                            host.log("[水果V4.50] ✅ 槽位二消闭环确认（允许自动落果补槽）：剩余 "
                                    + beforeRemaining + "→" + remaining
                                    + " / 槽位 " + beforeTrayCount + "→" + afterTrayCount);
                            continue;
                        }

                        if (afterTrayCount == Math.max(0, beforeTrayCount - 1)) {
                            host.log("[水果V4.50] 槽位数量符合二消预期，但剩余数未闭环；停止复核");
                            saveCurrentFrameDiagnostic(context, suPath, host, "tray_pair_unverified");
                            return Result.SAFE_STOP_DIRTY;
                        }

                        // 最终槽位比点击前多，优先视为“点击触发自动落果后的真实状态”；
                        // 不再把它当成视觉污染，直接重新建模。
                        if (afterTrayCount > beforeTrayCount) {
                            host.log("[水果V4.50] 槽位匹配后发生自动落果补槽："
                                    + beforeTrayCount + "→" + afterTrayCount
                                    + "；不追点，立即重新建模");
                            continue;
                        }

                        if (afterTrayCount == beforeTrayCount) {
                            markBlockedPosition(blockedPositions, blockedPositionTtl, target);
                            host.log("[槽位V4.50] 点击后槽位未消除且无补槽证据；位置加入本轮黑名单");
                            if (beforeTrayCount >= TRAY_CAPACITY) {
                                return Result.SAFE_STOP_DIRTY;
                            }
                            continue;
                        }

                        host.log("[槽位V4.50] 点击后槽位变化异常："
                                + beforeTrayCount + "→" + afterTrayCount + "，DIRTY安全停止");
                        return Result.SAFE_STOP_DIRTY;
                    }

                    host.log("[槽位V4.50] 点击后槽位数量非法："
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

                    host.log("[决策V4.46] "
                            + (safePush.dependencyChain ? "依赖链压栈" : "安全压栈")
                            + " / depth=" + beforeTrayCount
                            + " / 点击=(" + tx + "," + ty + ")"
                            + " / mate=" + format(safePush.mateScore)
                            + " / directUnlock=" + safePush.directUnlockMate
                            + " / mateReady=" + safePush.mateDroppable
                            + " / continuationPairs=" + safePush.continuationPairs
                            + " / unlock=" + safePush.unlockGain
                            + " / cascadeFollowers=" + safePush.cascadeFollowers);

                    if (handlePopupBeforeFruitTap(host, "安全压栈", lastIdlePopupProbeAt)) {
                        safeRecycle(frame.bitmap);
                        frame = null;
                        recovering = true;
                        noActionRetry = 0;
                        continue;
                    }
                    fruitTapAttempted = true;

                    if (!host.tap(tx, ty, "水果游戏-安全压栈")) {
                        if (host.aborted()) return Result.ABORTED;
                        throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                    }
                    if (!host.sleep(DIRECT_TAP_SETTLE_MIN_MS, DIRECT_TAP_SETTLE_MAX_MS)) return Result.ABORTED;

                    PostTapObservation afterPush = observeTrayAfterTap(
                            context, suPath, host, beforeTrayCount + 1,
                            beforeTrayCount, "安全压栈后");
                    if (afterPush == null) {
                        host.log("[栈模型V4.41.0] 压栈后无法稳定观察槽位，DIRTY安全停止");
                        return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                    }

                    int afterTrayCount = afterPush.tray.count;
                    lastIdlePopupProbeAt = SystemClock.elapsedRealtime();
                    safeRecycle(afterPush.frame.bitmap);

                    if (afterTrayCount >= 0 && afterTrayCount <= TRAY_CAPACITY) {
                        if (afterTrayCount > beforeTrayCount) {
                            featureStore.record(target, true);
                            host.log("[栈模型V4.50] ✅ 压栈后真实槽位增加："
                                    + beforeTrayCount + "→" + afterTrayCount
                                    + "；允许级联落果完成后重新规划，不再假定一次点击只增加1槽");
                            continue;
                        }

                        // V4.50：若点击触发“进入槽位 + 自动落果 + 二消”，最终槽位
                        // 甚至可能回到原数量或减少。剩余数下降2优先于槽位数量判断。
                        if (afterTrayCount <= beforeTrayCount) {
                            RemainingVerification verify = verifyPairRemaining(
                                    host, beforeRemaining, pairActions + 1, "压栈后级联二消", true);
                            if (verify.aborted) return Result.ABORTED;
                            if (verify.completed) return Result.COMPLETED;
                            if (verify.confirmed) {
                                pairActions++;
                                remaining = verify.afterRemaining;
                                blockedPositions.clear();
                                blockedPositionTtl.clear();
                                host.log("[栈模型V4.50] ✅ 压栈后发生级联二消：剩余 "
                                        + beforeRemaining + "→" + remaining
                                        + " / 槽位 " + beforeTrayCount + "→" + afterTrayCount);
                                continue;
                            }
                        }

                        if (afterTrayCount == beforeTrayCount) {
                            featureStore.record(target, false);
                            markBlockedPosition(blockedPositions, blockedPositionTtl, target);
                            host.log("[栈模型V4.50] 压栈点击未形成有效状态变化；"
                                    + "坐标加入黑名单，重新规划");
                            continue;
                        }

                        if (afterTrayCount < beforeTrayCount) {
                            recovering = true;
                            noActionRetry = 0;
                            host.log("[栈模型V4.50] 压栈后槽位反常减少且未确认二消；"
                                    + "重新OCR+截图，不连续点击");
                            continue;
                        }

                        continue;
                    }

                    host.log("[栈模型V4.50] 压栈后槽位数量非法："
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

                if (handlePopupBeforeFruitTap(host, "配对A", lastIdlePopupProbeAt)) {
                    // A尚未点击，此时不存在afterAObs；弹窗处理后直接重新观察。
                    recovering = true;
                    noActionRetry = 0;
                    continue;
                }
                fruitTapAttempted = true;

                if (!host.tap(ax, ay, "水果游戏-配对A")) {
                    if (host.aborted()) return Result.ABORTED;
                    throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                }
                // screencap本身在实机约需2~3秒，已经覆盖绝大多数下落动画；
                // 不再额外固定等待650~800ms。仍保留真实槽位截图确认，绝不盲点B。
                if (!host.sleep(120L, 220L)) return Result.ABORTED;

                PostTapObservation afterAObs = observeTrayAfterTap(
                        context, suPath, host, beforeTrayCount + 1,
                        beforeTrayCount, "A点击后");
                if (afterAObs == null) {
                    host.log("[槽位V4.38.0] A后无法稳定观察槽位，DIRTY安全停止");
                    return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                }

                ownedAfterA = afterAObs;
                int afterATrayCount = afterAObs.tray.count;
                // observeTrayAfterTap rejects/handles popup frames. Its returned frame is
                // therefore a fresh clean gate for the immediately following B click.
                lastIdlePopupProbeAt = SystemClock.elapsedRealtime();

                // V4.50：A点击后可能同时触发“二消 + 自动落果补槽”，
                // 因此不再要求槽位必须精确 +1。
                if (afterATrayCount >= 0 && afterATrayCount <= TRAY_CAPACITY) {
                    if (afterATrayCount <= beforeTrayCount) {
                        RemainingVerification verify = verifyPairRemaining(
                                host, beforeRemaining, pairActions + 1, "A点击后级联状态", true);
                        if (verify.aborted) return Result.ABORTED;
                        if (verify.completed) return Result.COMPLETED;
                        if (verify.confirmed) {
                            safeRecycle(afterAObs.frame.bitmap);
                            pairActions++;
                            remaining = verify.afterRemaining;
                            blockedPositions.clear();
                            blockedPositionTtl.clear();
                            host.log("[水果V4.50] ✅ A点击直接/级联二消确认；取消计划B：剩余 "
                                    + beforeRemaining + "→" + remaining
                                    + " / 槽位 " + beforeTrayCount + "→" + afterATrayCount);
                            continue;
                        }
                    }

                    if (afterATrayCount == beforeTrayCount) {
                        markBlockedPosition(blockedPositions, blockedPositionTtl, pair.a);
                        safeRecycle(afterAObs.frame.bitmap);
                        host.log("[点击验证V4.50] A点击后槽位回到原数且无二消闭环；"
                                + "不点B，重新规划其它可下落水果");
                        continue;
                    }

                    if (afterATrayCount > beforeTrayCount) {
                        host.log("[点击验证V4.50] A触发自动落果：槽位 "
                                + beforeTrayCount + "→" + afterATrayCount
                                + "；允许级联稳定后重新定位B");
                    }
                    // 到这里 A 已经进入当前真实槽位状态；B 必须从这张新图重新定位。
                } else {
                    safeRecycle(afterAObs.frame.bitmap);
                    host.log("[槽位V4.50] A后槽位数量非法："
                            + beforeTrayCount + "→" + afterATrayCount + "，DIRTY安全停止");
                    return Result.SAFE_STOP_DIRTY;
                }

                // 先在已锁定B的原坐标附近做小范围模板重定位。实机日志中B通常只漂移
                // 0~2像素；旧代码却每次都重跑整张棋盘的连通域、形态学和模板恢复，
                // 单次额外耗时约1.5~2.1秒。局部验证失败时仍回退全棋盘检测，安全门槛不变。
                long reacquireStarted = SystemClock.elapsedRealtime();
                FruitMatch reacquired = findMatchingFruitNearPlan(
                        expectedB, afterAObs.frame, blockedPositions);
                List<FruitObject> afterObjects = null;
                DropAnalysis afterDrop = null;
                if (reacquired == null) {
                    afterObjects = detectFruitObjects(afterAObs.frame);
                    afterDrop = analyzeDroppability(
                            afterObjects, afterAObs.frame.bitmap.getWidth(), afterAObs.frame.bitmap.getHeight());
                    reacquired = findBestMatchingFruitGlobal(
                            expectedB, afterObjects,
                            afterAObs.frame.bitmap.getWidth(), afterAObs.frame.bitmap.getHeight(),
                            blockedPositions);
                    host.log("[点击验证V4.44.3] A已确认进入槽位；局部B未命中，"
                            + "已回退全棋盘 / 水果=" + afterObjects.size()
                            + " / 可直接下落=" + afterDrop.droppable.size());
                } else {
                    host.log("[点击验证V4.44.3] A已确认进入槽位 / 槽位="
                            + beforeTrayCount + "→" + afterATrayCount
                            + " / B局部重定位耗时="
                            + (SystemClock.elapsedRealtime() - reacquireStarted) + "ms");
                }
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

                if (handlePopupBeforeFruitTap(host, "配对B", lastIdlePopupProbeAt)) {
                    recovering = true;
                    noActionRetry = 0;
                    continue;
                }
                fruitTapAttempted = true;

                if (!host.tap(bx, by, "水果游戏-配对B")) {
                    if (host.aborted()) return Result.ABORTED;
                    throw new RecoverableObservationException("水果点击发送失败，重新观察真实槽位");
                }
                if (!host.sleep(PAIR_B_TAP_SETTLE_MIN_MS, PAIR_B_TAP_SETTLE_MAX_MS)) return Result.ABORTED;

                PostTapObservation afterBObs = observeTrayAfterTap(
                        context, suPath, host, beforeTrayCount,
                        afterATrayCount, "B点击后");
                if (afterBObs == null) {
                    return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
                }
                int afterBTrayCount = afterBObs.tray.count;
                lastIdlePopupProbeAt = SystemClock.elapsedRealtime();
                safeRecycle(afterBObs.frame.bitmap);

                if (afterBTrayCount >= 0 && afterBTrayCount <= TRAY_CAPACITY) {
                    // B发生级联落果时，二消后最终槽位可能大于 beforeTrayCount。
                    // 所以先验证“剩余-2”这个游戏级事实，再看槽位数量。
                    RemainingVerification verify = verifyPairRemaining(
                            host, beforeRemaining, pairActions + 1, "棋盘对子",
                            afterBTrayCount != beforeTrayCount);
                    if (verify.aborted) return Result.ABORTED;
                    if (verify.completed) return Result.COMPLETED;
                    if (verify.confirmed) {
                        pairActions++;
                        remaining = verify.afterRemaining;
                        blockedPositions.clear();
                        blockedPositionTtl.clear();
                        host.log("[水果V4.50] ✅ 棋盘对子/级联落果二消确认：剩余 "
                                + beforeRemaining + "→" + remaining
                                + " / 槽位 " + beforeTrayCount + "→" + afterBTrayCount);
                        continue;
                    }

                    if (afterBTrayCount > beforeTrayCount) {
                        // 未确认二消，但槽位增加，说明至少发生了新的真实落果。
                        // 不再把它标成失败，也不重复点B；下一轮按真实棋盘重新规划。
                        host.log("[槽位V4.50] B点击后触发额外落果："
                                + beforeTrayCount + "→" + afterBTrayCount
                                + "；不追点，重新建模");
                        continue;
                    }

                    if (afterBTrayCount == beforeTrayCount) {
                        failedPairs.add(pairKeyV435(pair.a, pair.b));
                        host.log("[槽位V4.50] B点击后槽位回到基线但未确认二消；"
                                + "不追点，下一轮按真实槽位继续");
                        continue;
                    }

                    if (afterBTrayCount < beforeTrayCount) {
                        recovering = true;
                        noActionRetry = 0;
                        host.log("[槽位V4.50] B点击后槽位减少但剩余数未闭环；"
                                + "重新OCR+截图，不追点");
                        continue;
                    }
                }

                host.log("[槽位V4.50] B后槽位数量非法："
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

    /**
     * 最终点击闸门：在真正发送水果点击前，再做一次OCR确认。
     * 异步道具弹窗可能恰好出现在“规划完成”与input tap之间；
     * 任何规划坐标都不能绕过这个闸门。返回true表示本次点击被弹窗处理打断，
     * 调用方必须丢弃旧棋盘并重新规划。
     */
    private static boolean handlePopupBeforeFruitTap(
            Host host, String stage, long lastCleanProbeAtMs
    ) {
        if (host == null || host.aborted()) return false;
        long cleanAgeMs = lastCleanProbeAtMs <= 0L
                ? Long.MAX_VALUE
                : Math.max(0L, SystemClock.elapsedRealtime() - lastCleanProbeAtMs);
        if (cleanAgeMs <= PRE_TAP_POPUP_CLEAN_TTL_MS) {
            host.log("[弹窗V4.54] 复用刚确认的干净画面，跳过重复点击前OCR / stage="
                    + stage + " / age=" + cleanAgeMs + "ms");
            return false;
        }
        ScreenOcr.Snapshot probe;
        try {
            probe = requireOcr(host, "水果V4.47/点击前弹窗闸门/" + stage);
        } catch (RuntimeException e) {
            // OCR失败不能因为“看不见弹窗”就放行危险点击。
            host.log("[弹窗V4.47] 点击前OCR失败，禁止本次水果点击："
                    + e.getClass().getSimpleName());
            throw new RecoverableObservationException("点击前弹窗闸门OCR失败");
        }
        String text = normalize(probe == null ? "" : probe.fullText);
        if (!looksLikeBlockingFunctionPopupText(text)) return false;

        host.log("[弹窗V4.47] 点击前闸门发现道具弹窗，先关闭再重新规划");
        PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                host, probe, "点击前闸门/" + stage);
        if (popup == PopupDismissResult.ABORTED) return false;
        if (popup != PopupDismissResult.DISMISSED) {
            throw new RecoverableObservationException("点击前道具弹窗关闭失败");
        }
        host.log("[弹窗V4.47] 点击前弹窗已关闭；禁止使用旧水果坐标");
        return true;
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
    /**
     * V4.44.5：点击中央“解锁”视频按钮，等待广告/回到水果页，再把容量交给主循环。
     *
     * 重要：这里不把“点击发送成功”当作解锁成功。必须重新看到水果游戏页面，
     * 否则第二个广告、加载页或广告结束页会被误判成已解锁。
     */
    private static boolean unlockNextTraySlot(
            Context context, String suPath, Host host, int currentCapacity
    ) {
        if (context == null || host == null || currentCapacity >= TRAY_CAPACITY) return false;

        ScreenOcr.Snapshot ocr;
        try {
            ocr = requireOcr(host, "水果V4.44.5/寻找解锁槽位");
        } catch (RuntimeException e) {
            host.log("[槽位解锁V4.44.5] OCR失败：" + e.getClass().getSimpleName());
            return false;
        }
        if (host.aborted()) return false;

        int width = ocr == null ? 0 : ocr.width;
        int height = ocr == null ? 0 : ocr.height;
        if (width <= 0 || height <= 0) return false;

        ScreenOcr.Item unlock = ocr.findBest("解锁");
        int x = unlock == null ? Math.round(width * 0.50f) : unlock.centerX();
        int y = unlock == null ? Math.round(height * 0.865f) : unlock.centerY();

        // “解锁”可能同时出现在顶部功能区/说明文字中，只接受中央下方按钮区域。
        if (!isTrayUnlockButtonCoordinate(x, y, width, height)) {
            x = Math.round(width * 0.50f);
            y = Math.round(height * 0.865f);
        }

        host.log("[槽位解锁V4.44.5] 第" + (currentCapacity + 1)
                + "槽：点击“解锁”视频按钮 → " + x + "," + y
                + (unlock == null ? " / 比例坐标" : " / OCR坐标"));
        if (!host.tap(x, y, "水果游戏-解锁下一槽")) {
            return host.aborted() ? false : false;
        }

        // 解锁按钮触发的是广告奖励流程；奖励广告通常不能立即跳过，
        // 所以只在OCR明确出现“跳过/关闭”时处理，不用固定坐标强点广告内容。
        long deadline = SystemClock.elapsedRealtime() + 45000L;
        int closeAttempts = 0;
        int fruitChecks = 0;
        while (!host.aborted() && SystemClock.elapsedRealtime() < deadline) {
            if (!host.sleep(700L, 1000L)) return false;

            ScreenOcr.Snapshot current;
            try {
                current = requireOcr(host, "水果V4.44.5/解锁广告等待");
            } catch (RuntimeException e) {
                continue;
            }
            String text = normalize(current == null ? "" : current.fullText);

            // 道具推荐弹窗如果叠在广告/回到游戏瞬间，优先彻底清空它。
            if (looksLikeBlockingFunctionPopupText(text)) {
                PopupDismissResult popup = dismissBlockingFunctionPopupFromOcr(
                        host, current, "解锁广告后道具弹窗");
                if (popup == PopupDismissResult.ABORTED) return false;
                if (popup == PopupDismissResult.DISMISSED) continue;
                continue;
            }

            if (looksLikeFruitGame(text)) {
                fruitChecks++;
                if (fruitChecks >= 2) {
                    host.log("[槽位解锁V4.44.5] ✅ 广告流程结束，重新进入水果页");
                    return true;
                }
            } else {
                fruitChecks = 0;
            }

            ScreenOcr.Item dismiss = findAdDismissItem(current);
            if (dismiss != null && closeAttempts < 6) {
                int dx = dismiss.centerX();
                int dy = dismiss.centerY();
                host.log("[广告V4.44.5] 检测到可关闭控件 → " + dx + "," + dy);
                if (host.tap(dx, dy, "水果游戏-关闭广告")) {
                    closeAttempts++;
                }
            }
        }

        host.log("[槽位解锁V4.44.5] ❌ 45秒内未确认返回水果页");
        return false;
    }

    private static boolean isTrayUnlockButtonCoordinate(
            int x, int y, int width, int height
    ) {
        if (width <= 0 || height <= 0) return false;
        double nx = x / (double) width;
        double ny = y / (double) height;
        return nx >= 0.38 && nx <= 0.62 && ny >= 0.80 && ny <= 0.93;
    }

    private static ScreenOcr.Item findAdDismissItem(ScreenOcr.Snapshot ocr) {
        if (ocr == null || ocr.width <= 0 || ocr.height <= 0) return null;

        String[] terms = {"跳过广告", "跳过", "关闭广告", "关闭", "Skip", "Close"};
        for (String term : terms) {
            ScreenOcr.Item item = ocr.findBest(term);
            if (item == null) continue;
            double nx = item.centerX() / (double) ocr.width;
            double ny = item.centerY() / (double) ocr.height;

            // 只接受广告常见的边缘区域，避免在广告正文里误点“关闭/跳过”文字。
            if (term.contains("跳过")) {
                if (nx >= 0.55 || ny >= 0.75 || ny <= 0.25) return item;
            } else if (nx >= 0.68 || nx <= 0.32 || ny <= 0.25 || ny >= 0.80) {
                return item;
            }
        }
        return null;
    }

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

        host.log("[弹窗V4.36] 已发送关闭X点击，准备连续复检");

        // 同一个X可能关闭一个弹窗，而下一层弹窗立即接替；
        // 这里与OCR路径保持一致：必须连续两次确认干净，不能只看一帧。
        int dismissedCount = 1;
        int cleanChecks = 0;
        GameFrame verify = null;
        while (dismissedCount <= 12) {
            if (!host.sleep(220L, 360L)) return PopupDismissResult.ABORTED;
            verify = captureFrame(context, suPath, host);
            if (verify == null) {
                host.log("[弹窗V4.44.4] ❌ 连续复检截图失败");
                return PopupDismissResult.FAILED;
            }

            boolean stillPopup = looksLikeBlockingFunctionPopup(verify);
            if (!stillPopup) {
                cleanChecks++;
                safeRecycle(verify.bitmap);
                verify = null;
                if (cleanChecks >= 2) {
                    host.log("[弹窗V4.44.4] ✅ 连续" + cleanChecks
                            + "次确认无弹窗，共关闭" + dismissedCount + "个");
                    return PopupDismissResult.DISMISSED;
                }
                continue;
            }

            cleanChecks = 0;
            int nextCloseX = Math.round(verify.originalWidth * 0.866f);
            int nextCloseY = Math.round(verify.originalHeight * 0.281f);
            safeRecycle(verify.bitmap);
            verify = null;
            host.log("[弹窗V4.44.4] 检测到后续第" + (dismissedCount + 1)
                    + "个道具弹窗，继续关闭X=" + nextCloseX + "," + nextCloseY);
            if (!host.tap(nextCloseX, nextCloseY, "水果游戏-继续关闭道具弹窗")) {
                return host.aborted() ? PopupDismissResult.ABORTED : PopupDismissResult.FAILED;
            }
            dismissedCount++;
        }

        host.log("[弹窗V4.44.4] 连续道具弹窗超过12个，交给下一轮主循环继续复核");
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

        // 不再“一次点击 + 一次复检”就认为结束。
        // 第一个弹窗关闭后，第二个/第三个弹窗可能在下一帧才出现；
        // 连续轮询，直到连续两次确认没有弹窗。最多处理12个连续弹窗，
        // 下一轮主循环还会再次进入这里，因此不会因为固定次数而永久漏掉后续弹窗。
        int dismissedCount = 1;
        int cleanChecks = 0;
        ScreenOcr.Snapshot verify = null;
        while (dismissedCount <= 12) {
            if (!host.sleep(220L, 360L)) return PopupDismissResult.ABORTED;
            try {
                verify = requireOcr(host, "水果V4.44.4/道具弹窗连续复检#" + dismissedCount);
            } catch (RuntimeException e) {
                host.log("[弹窗V4.44.4] 连续复检OCR异常：" + e.getClass().getSimpleName());
                return PopupDismissResult.FAILED;
            }

            String verifyText = verify == null ? "" : verify.fullText;
            if (!looksLikeBlockingFunctionPopupText(verifyText)) {
                cleanChecks++;
                if (cleanChecks >= 2) {
                    host.log("[弹窗V4.44.4] ✅ 连续" + cleanChecks
                            + "次确认无道具弹窗，共关闭" + dismissedCount + "个");
                    return PopupDismissResult.DISMISSED;
                }
                continue;
            }

            cleanChecks = 0;
            if (verify.width <= 0 || verify.height <= 0) {
                return PopupDismissResult.FAILED;
            }
            int nextCloseX = Math.round(verify.width * 0.866f);
            int nextCloseY = Math.round(verify.height * 0.281f);
            host.log("[弹窗V4.44.4] 检测到后续第" + (dismissedCount + 1)
                    + "个道具弹窗，继续关闭X=" + nextCloseX + "," + nextCloseY);
            if (!host.tap(nextCloseX, nextCloseY, "水果游戏-继续关闭道具弹窗")) {
                return host.aborted() ? PopupDismissResult.ABORTED : PopupDismissResult.FAILED;
            }
            dismissedCount++;
        }

        host.log("[弹窗V4.44.4] ❌ 连续道具弹窗超过12个，交给下一轮主循环继续复核");
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
         * V4.48：三槽满并不意味着只能消TOP。
         * 只要棋盘上存在“与任意一个已占槽水果同类、且能够真正落入槽口”的水果，
         * 就可以通过这次点击完成对应的二消并释放一个槽位。
         *
         * 因此这里必须遍历 TOP / MID / BOTTOM 三个已占槽位，而不能把 tray.items.get(0)
         * 当成唯一合法匹配对象。尤其在 3/3 状态下，禁止安全压栈，但允许任意槽内同类
         * 的直接二消；如果三个槽位都没有可落槽的同类水果，才判定为无合法普通动作。
         */
        if (tray.items.isEmpty()) return null;

        TrayMatchChoice best = null;
        double denomY = Math.max(1.0, frameHeight);

        for (int slotIndex = 0; slotIndex < tray.items.size(); slotIndex++) {
            TrayItem trayItem = tray.items.get(slotIndex);
            if (trayItem == null || trayItem.hist == null) continue;

            for (FruitObject fruit : objects) {
                if (fruit == null) continue;
                if (isBlockedPosition(blockedPositions, fruit)) continue;
                // “无遮挡”仍不足以证明可以进槽；这里必须使用完整的下落通道判定。
                if (findNearestBlockingFruit(fruit, objects, frameWidth, frameHeight) != null) continue;

                double hist = histogramCos(trayItem.hist, fruit.hist);
                if (hist < TRAY_HIST_MATCH_MIN) continue;

                double lower = clamp01(fruit.centerY / denomY);

                /*
                 * V4.49：残局不能只按“当前这一消有多像”贪心。
                 * 3/3 时尤其危险：多个槽位都可能有候选，如果随便消掉一个，
                 * 下一步可能再也没有可落槽的同类水果。
                 *
                 * 因此这里做两层只读前瞻：
                 *   当前候选消掉 -> 剩余槽/棋盘还能不能立即产生下一消；
                 *   再消一次 -> 是否还能继续产生第三步。
                 *
                 * 这不是模拟游戏内部物理，只利用当前已经确认的水果坐标和
                 * 槽位直方图做保守的“可执行后手”判断。真实点击后仍然完全
                 * 重新截图，不复用预测状态。
                 */
                int followup1 = countTrayFollowupsAfterRemoval(
                        tray, objects, fruit, trayItem, frameWidth, frameHeight, blockedPositions);
                int followup2 = countTrayFollowupsAfterSecondRemoval(
                        tray, objects, fruit, trayItem, frameWidth, frameHeight, blockedPositions);

                // 槽位顺序只做极弱 tie-break，绝不否决 MID/BOTTOM 合法二消。
                double slotTieBreak = (tray.items.size() - slotIndex) * 0.0001;

                /*
                 * 前瞻权重高于Y位置，但低于当前匹配置信度。
                 * 3/3残局中，一个能继续产生后手的0.978匹配，
                 * 应优先于一个消完以后马上无动作的0.982匹配。
                 */
                double rank = 0.70 * hist
                        + 0.12 * lower
                        + 0.12 * Math.min(1.0, followup1 / 2.0)
                        + 0.05 * Math.min(1.0, followup2 / 2.0)
                        + slotTieBreak;

                if (tray.count >= TRAY_CAPACITY) {
                    hostlessLogTraySearch(
                            "3槽残局候选 slot=" + trayItem.slotName
                                    + " hist=" + format(hist)
                                    + " follow1=" + followup1
                                    + " follow2=" + followup2
                                    + " rank=" + format(rank));
                }

                TrayMatchChoice candidate = new TrayMatchChoice(trayItem, fruit, hist, rank);
                if (best == null || candidate.rank > best.rank) best = candidate;
            }
        }

        return best;
    }

    /**
     * V4.49：计算“消掉当前槽位匹配后”的直接后手数量。
     * 只读，不修改真实TrayState/objects；实际点击后必须重新建模。
     */
    private static int countTrayFollowupsAfterRemoval(
            TrayState tray,
            List<FruitObject> objects,
            FruitObject removedBoardFruit,
            TrayItem removedTrayItem,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (tray == null || objects == null || removedBoardFruit == null || removedTrayItem == null) return 0;
        int count = 0;
        for (FruitObject candidate : objects) {
            if (candidate == null || candidate == removedBoardFruit
                    || isBlockedPosition(blockedPositions, candidate)) continue;
            if (findNearestBlockingFruit(candidate, objects, frameWidth, frameHeight) != null) continue;

            for (TrayItem item : tray.items) {
                if (item == null || item == removedTrayItem || item.hist == null) continue;
                if (histogramCos(item.hist, candidate.hist) >= TRAY_HIST_MATCH_MIN) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * V4.49：在第一步后再看一层，避免选择“只能再消一次然后立即死”的残局动作。
     * 这里采用最保守的计数：只要存在至少两个不同的可执行后手，就认为存在
     * 两步以上的连续空间；不存在则返回0/1。
     */
    private static int countTrayFollowupsAfterSecondRemoval(
            TrayState tray,
            List<FruitObject> objects,
            FruitObject removedBoardFruit,
            TrayItem removedTrayItem,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (tray == null || objects == null || removedBoardFruit == null || removedTrayItem == null) return 0;

        List<TrayItem> remainingTray = new ArrayList<>();
        for (TrayItem item : tray.items) {
            if (item != null && item != removedTrayItem) remainingTray.add(item);
        }

        int best = 0;
        for (FruitObject first : objects) {
            if (first == null || first == removedBoardFruit
                    || isBlockedPosition(blockedPositions, first)) continue;
            if (findNearestBlockingFruit(first, objects, frameWidth, frameHeight) != null) continue;

            TrayItem firstMatch = null;
            double firstHist = 0.0;
            for (TrayItem item : remainingTray) {
                if (item == null || item.hist == null) continue;
                double h = histogramCos(item.hist, first.hist);
                if (h >= TRAY_HIST_MATCH_MIN && h > firstHist) {
                    firstHist = h;
                    firstMatch = item;
                }
            }
            if (firstMatch == null) continue;

            int second = 0;
            for (FruitObject candidate : objects) {
                if (candidate == null || candidate == removedBoardFruit || candidate == first
                        || isBlockedPosition(blockedPositions, candidate)) continue;
                if (findNearestBlockingFruit(candidate, objects, frameWidth, frameHeight) != null) continue;

                for (TrayItem item : remainingTray) {
                    if (item == null || item == firstMatch || item.hist == null) continue;
                    if (histogramCos(item.hist, candidate.hist) >= TRAY_HIST_MATCH_MIN) {
                        second++;
                        break;
                    }
                }
            }
            best = Math.max(best, second);
            if (best >= 2) return best;
        }
        return best;
    }

    /**
     * 诊断日志不应依赖Host对象，因此这里保持为空壳；3槽残局的详细候选信息
     * 由主循环的决策日志承载。保留方法只是为了避免把求解核心和UI日志耦合。
     */
    private static void hostlessLogTraySearch(String message) {
        // Intentionally no-op. Do not emit per-candidate logs on every frame.
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
        int expectedStableCount = 0;
        boolean sawDifferentStableCount = false;
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
                        expectedStableCount = 0;
                        if (lastStable != null) safeRecycle(lastStable.frame.bitmap);
                        lastStable = null;
                        if (!host.sleep(220L, 360L)) return null;
                        continue;
                    }

                    if (tray.count == expectedCount) {
                        // 不要在第一张“期望槽位”画面就返回。点击可能刚刚触发
                        // 上方水果自动下落；必须确认期望槽位连续稳定两帧。
                        consecutiveBaseline = 0;
                        if (lastStable != null) safeRecycle(lastStable.frame.bitmap);
                        lastStable = new PostTapObservation(frame, tray, false);
                        frame = null;
                        // A prior stable count proves that a real transition has already
                        // crossed the tray. On slow screencap devices this expected frame
                        // arrives ~2.5s later, so a third identical PNG adds latency without
                        // adding useful evidence. If expected was the first frame, retain the
                        // original two-frame confirmation.
                        if (sawDifferentStableCount) {
                            PostTapObservation result = lastStable;
                            lastStable = null;
                            return result;
                        }
                        if (++expectedStableCount >= POST_TAP_SETTLED_CONFIRMATIONS) {
                            PostTapObservation result = lastStable;
                            lastStable = null;
                            return result;
                        }
                        if (!host.sleep(220L, 360L)) return null;
                        continue;
                    }

                    expectedStableCount = 0;
                    sawDifferentStableCount = true;
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
     * depth=0：候选必须有高置信同类仍在棋盘中。
     * depth=1：允许升到2槽；同类必须已经可下落，或候选移除后会直接释放同类。
     * depth=2：允许升到3槽，但同类必须当前已经可下落，下一轮立即消除新TOP。
     */
    /**
     * V4.50：点击一个水果后，上方被它支撑的水果可能自动连续下落。
     * 在2/3槽时，这类“连锁落果”可能直接把最后一个槽塞满甚至溢出。
     * 只有完全没有潜在级联，或者唯一级联水果本身就是当前水果的可靠同类，
     * 才允许把候选送入第三槽。
     */
    static boolean allowsThirdSlotCascade(
            int trayCount, int cascadeFollowers, boolean cascadeMate
    ) {
        if (trayCount < 0 || trayCount >= TRAY_CAPACITY) return false;
        int freeSlots = TRAY_CAPACITY - trayCount;
        int additions = 1 + Math.max(0, cascadeFollowers);
        if (additions <= freeSlots) return true;

        // 唯一可接受的“超出容量”例外：候选本身进入槽位后，
        // 紧接着唯一的级联水果就是它的同类，二者会立即二消，净占用为0。
        return cascadeFollowers == 1 && cascadeMate;
    }

    /**
     * V4.51：级联不能只按“总共掉下来几个水果”计算容量。
     * 级联水果如果与现有槽位或点击根水果组成二消，会在进入槽位的同时释放容量。
     *
     * 例如：1槽已有葡萄；点击挡住两颗葡萄的龙眼/火龙果后，两颗葡萄会依次落槽。
     * 原来的 additions=3 会错误拒绝；实际第一颗葡萄与槽内葡萄二消，最终占用可保持在2槽。
     */
    static boolean allowsCascadeOccupancy(
            int trayCount, int cascadeFollowers, int eliminationPairs
    ) {
        if (trayCount < 0 || trayCount >= TRAY_CAPACITY) return false;
        if (cascadeFollowers < 0 || eliminationPairs < 0) return false;
        int incoming = 1 + cascadeFollowers;
        // 每完成一次二消，净占用减少2个“水果实体”。
        int projected = trayCount + incoming - 2 * eliminationPairs;
        return projected <= TRAY_CAPACITY;
    }

    private static int projectCascadeTrayCount(
            int trayCount, int incoming, int eliminationPairs
    ) {
        if (trayCount < 0) return Integer.MAX_VALUE;
        return trayCount + Math.max(0, incoming) - 2 * Math.max(0, eliminationPairs);
    }

    /**
     * 只计算当前 root + 其潜在级联后手与既有槽位之间的可消除配对数，
     * 再对剩余级联水果做同类配对。不存在真实点击，执行后仍必须重新截图确认。
     */
    private static int countCascadeEliminationPairs(
            FruitObject root,
            List<FruitObject> objects,
            DropAnalysis drop,
            List<TrayItem> trayItems
    ) {
        if (root == null || objects == null || drop == null) return 0;

        List<FruitObject> incoming = collectPotentialCascadeFollowers(root, objects, drop);
        // root 本身也是“进入槽位的新增实体”；把它加入匹配集合，
        // 才能正确建模“点击根水果后，根水果与某个级联同类立即二消”的情况。
        if (root != null) incoming.add(0, root);
        if (incoming.isEmpty()) return 0;

        boolean[] usedIncoming = new boolean[incoming.size()];
        int pairs = 0;

        // 先用已有槽位吸收最明显的同类级联水果；每个槽位只能被二消一次。
        if (trayItems != null) {
            for (TrayItem item : trayItems) {
                if (item == null || item.hist == null) continue;
                int bestIndex = -1;
                double bestHist = TRAY_HIST_MATCH_MIN;
                for (int i = 0; i < incoming.size(); i++) {
                    if (usedIncoming[i]) continue;
                    FruitObject f = incoming.get(i);
                    double hist = histogramCos(item.hist, f.hist);
                    if (hist >= bestHist) {
                        bestHist = hist;
                        bestIndex = i;
                    }
                }
                if (bestIndex >= 0) {
                    usedIncoming[bestIndex] = true;
                    pairs++;
                }
            }
        }

        // 再处理“点击根水果 + 级联水果”或“两个级联水果”自身二消。
        while (true) {
            int bestI = -1;
            int bestJ = -1;
            double bestHist = TRAY_HIST_MATCH_MIN;
            for (int i = 0; i < incoming.size(); i++) {
                if (usedIncoming[i]) continue;
                for (int j = i + 1; j < incoming.size(); j++) {
                    if (usedIncoming[j]) continue;
                    double hist = histogramCos(incoming.get(i).hist, incoming.get(j).hist);
                    if (hist >= bestHist) {
                        bestHist = hist;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }
            if (bestI < 0) break;
            usedIncoming[bestI] = true;
            usedIncoming[bestJ] = true;
            pairs++;
        }
        return pairs;
    }

    private static List<FruitObject> collectPotentialCascadeFollowers(
            FruitObject root,
            List<FruitObject> objects,
            DropAnalysis drop
    ) {
        List<FruitObject> result = new ArrayList<>();
        if (root == null || objects == null || drop == null || drop.blocked.isEmpty()) {
            return result;
        }

        Set<FruitObject> seen = new HashSet<>();
        List<FruitObject> frontier = new ArrayList<>();
        seen.add(root);
        frontier.add(root);
        while (!frontier.isEmpty()) {
            FruitObject current = frontier.remove(frontier.size() - 1);
            for (BlockingRelation relation : drop.blocked) {
                if (relation == null || relation.blocker != current || relation.fruit == null) continue;
                if (!objects.contains(relation.fruit) || seen.contains(relation.fruit)) continue;
                seen.add(relation.fruit);
                result.add(relation.fruit);
                frontier.add(relation.fruit);
            }
        }
        return result;
    }

    private static void hostlessLogCascadeGuard(String message) {
        // Intentionally no-op; retained as a hook for future diagnostic logging.
    }

    private static int countPotentialCascadeFollowers(
            FruitObject root,
            List<FruitObject> objects,
            DropAnalysis drop
    ) {
        if (root == null || objects == null || drop == null || drop.blocked.isEmpty()) return 0;

        Set<FruitObject> seen = new HashSet<>();
        List<FruitObject> frontier = new ArrayList<>();
        seen.add(root);
        frontier.add(root);

        while (!frontier.isEmpty()) {
            FruitObject current = frontier.remove(frontier.size() - 1);
            for (BlockingRelation relation : drop.blocked) {
                if (relation == null || relation.blocker != current || relation.fruit == null) continue;
                if (!objects.contains(relation.fruit) || seen.contains(relation.fruit)) continue;
                seen.add(relation.fruit);
                frontier.add(relation.fruit);
            }
        }
        return Math.max(0, seen.size() - 1);
    }

    private static boolean isPotentialCascadeFollower(
            FruitObject root,
            FruitObject target,
            List<FruitObject> objects,
            DropAnalysis drop
    ) {
        if (root == null || target == null || root == target) return false;
        if (objects == null || drop == null) return false;
        Set<FruitObject> seen = new HashSet<>();
        List<FruitObject> frontier = new ArrayList<>();
        seen.add(root);
        frontier.add(root);
        while (!frontier.isEmpty()) {
            FruitObject current = frontier.remove(frontier.size() - 1);
            for (BlockingRelation relation : drop.blocked) {
                if (relation == null || relation.blocker != current || relation.fruit == null) continue;
                if (!objects.contains(relation.fruit) || seen.contains(relation.fruit)) continue;
                if (relation.fruit == target) return true;
                seen.add(relation.fruit);
                frontier.add(relation.fruit);
            }
        }
        return false;
    }

    private static SafePushChoice chooseBestSafePushV441(
            List<FruitObject> objects,
            DropAnalysis drop,
            TrayState tray,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        int trayCount = tray == null ? -1 : tray.count;
        if (objects == null || objects.isEmpty() || drop == null || drop.droppable.isEmpty()) {
            return null;
        }
        if (trayCount < 0 || trayCount > 2) return null;

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
            boolean bestMateDroppable = false;
            boolean bestDirectUnlockMate = false;
            for (FruitObject other : objects) {
                if (other == null || other == fruit) continue;
                Similarity sim = similarity(fruit, other);
                if (isBridgeMateSimilarity(
                        sim.score, sim.rgbMad, sim.histCos, sim.shapeIou)
                        && sim.score > mateScore) {
                    boolean mateDroppable = drop.droppable.contains(other)
                            && !isBlockedPosition(blockedPositions, other);
                    boolean directUnlockMate = directlyUnlocksMate(fruit, other, drop);
                    if (!allowsBridgePush(trayCount, directUnlockMate, mateDroppable)) continue;
                    mateScore = sim.score;
                    bestMate = other;
                    bestMateDroppable = mateDroppable;
                    bestDirectUnlockMate = directUnlockMate;
                }
            }
            if (bestMate == null) continue;

            // The last free slot is never used for a fuzzy bridge. It is allowed only
            // when the mate is already directly clickable and the pair passes the full
            // strict thresholds used by ordinary A/B pairing.
            Similarity lastSlotMate = similarity(fruit, bestMate);
            boolean strictMate = lastSlotMate.rgbMad <= MAX_RGB_MAD
                    && lastSlotMate.histCos >= MIN_HIST_COS
                    && lastSlotMate.shapeIou >= MIN_SHAPE_IOU
                    && lastSlotMate.score >= MIN_PAIR_SCORE;

            int cascadeFollowers = countPotentialCascadeFollowers(fruit, objects, drop);
            if (!allowsLastSlotPush(
                    trayCount, bestMateDroppable, strictMate, cascadeFollowers)) continue;
            int cascadePairs = countCascadeEliminationPairs(
                    fruit, objects, drop, tray == null ? null : tray.items);
            int projectedTrayCount = projectCascadeTrayCount(
                    trayCount, 1 + cascadeFollowers, cascadePairs);
            if (!allowsCascadeOccupancy(
                    trayCount, cascadeFollowers, cascadePairs)) {
                continue;
            }

            int unlockGain = 0;
            for (BlockingRelation relation : drop.blocked) {
                if (relation == null || relation.blocker != fruit) continue;
                unlockGain++;
            }

            /*
             * V4.45：安全压栈不再用“释放几个水果”作为主要评分依据。
             * 真正重要的是：点击当前水果以后，能不能形成一个可执行的下一步。
             *
             * 两层前瞻：
             * 1) 当前候选的同类 mate 是否已经可下落，或是否会被当前点击直接释放；
             * 2) 假设“候选 + mate”随后完成二消，剩余棋盘是否至少还存在一个
             *    可靠对子。这样可以把“看起来能压栈、实际会把局面带死”的候选降权。
             */
            int continuationPairs = countReliablePairsAfterRemoving(
                    objects, fruit, bestMate, frameWidth, frameHeight, blockedPositions);
            double continuationScore = Math.min(1.0, continuationPairs / 2.0);
            double lower = clamp01(fruit.centerY / denomY);

            // 后手链的权重高于 unlockGain。unlockGain 仅作为极弱的平手因素。
            double rank = 0.44 * mateScore
                    + (bestDirectUnlockMate ? 0.24 : 0.0)
                    + (bestMateDroppable ? 0.18 : 0.0)
                    + 0.08 * continuationScore
                    + 0.04 * lower
                    + 0.02 * Math.min(1.0, unlockGain / 3.0)
                    - (trayCount == 2 ? 0.05 : 0.0);

            SafePushChoice candidate = new SafePushChoice(
                    fruit, mateScore, bestDirectUnlockMate,
                    bestMateDroppable, unlockGain, continuationPairs, rank,
                    false, cascadeFollowers);
            if (cascadeFollowers > 0) {
                hostlessLogCascadeGuard(
                        "safePush tray=" + trayCount
                                + " followers=" + cascadeFollowers
                                + " elimPairs=" + cascadePairs
                                + " projected=" + projectedTrayCount);
            }
            if (best == null || candidate.rank > best.rank) best = candidate;
        }
        return best;
    }

    static boolean allowsLastSlotPush(
            int trayCount,
            boolean mateDroppable,
            boolean strictMate,
            int cascadeFollowers
    ) {
        if (trayCount < 0 || trayCount >= TRAY_CAPACITY) return false;
        if (trayCount < 2) return true;
        return mateDroppable && strictMate && cascadeFollowers == 0;
    }

    /**
     * V4.54: bounded exploration for a mostly empty tray.
     *
     * When only 0/1 slots are occupied, using one empty slot can expose the fruit that
     * completes the next pair. The candidate must be genuinely droppable, must not start
     * an unpredictable cascade, and is ranked by how much of the board it unlocks.
     * This path is intentionally unavailable at 2/3 occupancy.
     */
    private static SafePushChoice chooseBestExplorationPush(
            List<FruitObject> objects,
            DropAnalysis drop,
            int trayCount,
            int frameWidth,
            int frameHeight,
            FruitDropFeatureStore featureStore,
            Set<String> blockedPositions
    ) {
        if (objects == null || objects.isEmpty() || drop == null
                || drop.droppable.isEmpty() || trayCount < 0 || trayCount > 1) {
            return null;
        }

        SafePushChoice best = null;
        for (FruitObject fruit : drop.droppable) {
            if (fruit == null || isBlockedPosition(blockedPositions, fruit)) continue;
            if (!hasConservativeDropClearance(fruit, objects, frameWidth, frameHeight)) continue;

            int cascadeFollowers = countPotentialCascadeFollowers(fruit, objects, drop);
            if (cascadeFollowers != 0) continue;

            int unlockGain = 0;
            for (BlockingRelation relation : drop.blocked) {
                if (relation != null && relation.blocker == fruit) unlockGain++;
            }
            double lower = clamp01(fruit.centerY / Math.max(1.0, frameHeight));
            // 历史经验只用于同样位置/外观桶的排序。没有经验时为0，不影响
            // 当前视觉算法；负经验也不会把“当前明确无遮挡”的水果直接判死。
            double learned = featureStore == null ? 0.0 : featureStore.prior(fruit);
            double rank = 0.66 * Math.min(1.0, unlockGain / 3.0)
                    + 0.24 * lower + 0.10 * learned;
            SafePushChoice candidate = new SafePushChoice(
                    fruit, 0.0, false, false, unlockGain, 0, rank,
                    false, 0);
            if (best == null || candidate.rank > best.rank) best = candidate;
        }
        return best;
    }

    /**
     * V4.51：反向解阻链。
     *
     * 当“槽位同类水果”已经被棋盘其它水果挡住时，不能因为目标本身不可直接下落
     * 就判定无动作。沿 BlockingRelation 反向追踪：
     *
     *   槽内A <- 棋盘A <- B <- C <- ... <- R
     *
     * 只要最终找到真正可直接下落的 R，就允许先点击 R，让整条链自然释放。
     * 点击后仍由真实槽位/剩余数闭环确认，不复用预测坐标。
     */
    private static SafePushChoice chooseBestTrayUnblockPush(
            TrayState tray,
            List<FruitObject> objects,
            DropAnalysis drop,
            int trayCount,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (tray == null || !tray.stable || tray.items.isEmpty()
                || objects == null || objects.isEmpty()
                || drop == null || drop.droppable.isEmpty()) {
            return null;
        }
        // At 2/3 occupancy a blocker would itself consume the final slot. The real-device
        // replay proved that a predicted multi-level cascade may not complete, leaving a
        // permanent 3/3 deadlock. Last-slot play is handled only by a directly clickable
        // strict A/B pair or direct tray match.
        if (trayCount >= 2) return null;

        SafePushChoice best = null;
        double denomY = Math.max(1.0, frameHeight);

        for (TrayItem trayItem : tray.items) {
            if (trayItem == null || trayItem.hist == null) continue;

            for (FruitObject target : objects) {
                if (target == null || isBlockedPosition(blockedPositions, target)) continue;
                double hist = histogramCos(trayItem.hist, target.hist);
                if (hist < TRAY_HIST_MATCH_MIN) continue;

                int[] depthHolder = new int[]{0};
                FruitObject root = findUnblockChainRoot(
                        target, objects, drop, blockedPositions, depthHolder);
                if (root == null || root == target || depthHolder[0] <= 0) continue;
                if (!drop.droppable.contains(root)) continue;
                if (!hasConservativeDropClearance(root, objects, frameWidth, frameHeight)) continue;
                if (!isPotentialCascadeFollower(root, target, objects, drop)) continue;

                int cascadeFollowers = countPotentialCascadeFollowers(root, objects, drop);
                int cascadePairs = countCascadeEliminationPairs(
                        root, objects, drop, tray.items);
                int projectedTrayCount = projectCascadeTrayCount(
                        trayCount, 1 + cascadeFollowers, cascadePairs);

                if (!allowsCascadeOccupancy(
                        trayCount, cascadeFollowers, cascadePairs)) {
                    continue;
                }

                double lower = clamp01(root.centerY / denomY);
                // 先保证“能释放槽内同类”；在多个链都可行时：
                // 匹配置信度 > 解阻链更短 > 更靠下的真正可点根节点。
                double rank = 0.68 * hist
                        + 0.20 * Math.min(1.0, 1.0 / depthHolder[0])
                        + 0.10 * lower
                        + 0.02 * Math.min(1.0, cascadePairs / 2.0);

                SafePushChoice candidate = new SafePushChoice(
                        root, hist, true, true,
                        depthHolder[0], 1, rank, true, cascadeFollowers,
                        depthHolder[0], trayItem.slotName);

                if (best == null || candidate.rank > best.rank) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * 从一个“与槽位同类但被阻挡”的目标，沿阻挡关系向下追踪到真正可直接下落的根。
     * BlockingRelation 定义为 relation.fruit（上方水果） <- relation.blocker（下方阻挡者）。
     */
    private static FruitObject findUnblockChainRoot(
            FruitObject target,
            List<FruitObject> objects,
            DropAnalysis drop,
            Set<String> blockedPositions,
            int[] depthHolder
    ) {
        if (target == null || objects == null || drop == null) return null;

        Set<FruitObject> seen = new HashSet<>();
        FruitObject current = target;
        int depth = 0;

        while (current != null && depth <= objects.size()) {
            if (depth > 0 && drop.droppable.contains(current)
                    && !isBlockedPosition(blockedPositions, current)) {
                if (depthHolder != null && depthHolder.length > 0) {
                    depthHolder[0] = depth;
                }
                return current;
            }

            FruitObject blocker = null;
            double nearestY = -Double.MAX_VALUE;
            for (BlockingRelation relation : drop.blocked) {
                if (relation == null || relation.fruit != current || relation.blocker == null) continue;
                if (!objects.contains(relation.blocker)
                        || isBlockedPosition(blockedPositions, relation.blocker)) continue;
                if (relation.blocker.centerY > nearestY) {
                    blocker = relation.blocker;
                    nearestY = relation.blocker.centerY;
                }
            }

            if (blocker == null || seen.contains(blocker)) return null;
            seen.add(current);
            current = blocker;
            depth++;
        }

        return null;
    }

    /**
     * V4.46 依赖链规划：
     *
     * 当“可直接下落的安全水果”本身没有足够强的同类后手时，
     * 不立即停止。先检查它是不是某个高置信水果的直接阻挡者。
     *
     * 典型链：
     *   B(可下落) -> 点击B入槽 -> A释放 -> A与槽内水果/另一颗可下落水果配对
     *
     * 这是单水果评分无法表达的状态转移，因此这里显式枚举一层依赖。
     * 真实执行仍然一次只点击一个水果，点击后重新截图，不复用旧坐标。
     */
    private static SafePushChoice chooseBestDependencyPushV446(
            List<FruitObject> objects,
            DropAnalysis drop,
            TrayState tray,
            int trayCount,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (objects == null || objects.isEmpty() || drop == null || tray == null) return null;
        if (trayCount < 0 || trayCount > 1 || drop.droppable.isEmpty()) return null;

        SafePushChoice best = null;

        for (FruitObject blocker : drop.droppable) {
            if (blocker == null || isBlockedPosition(blockedPositions, blocker)) continue;

            // 点击 blocker 后，它从棋盘消失；只在这个假设状态下判断被它压住的水果。
            List<FruitObject> remaining = new ArrayList<>();
            for (FruitObject f : objects) {
                if (f != null && f != blocker) remaining.add(f);
            }

            for (BlockingRelation relation : drop.blocked) {
                if (relation == null || relation.blocker != blocker || relation.fruit == null) continue;
                FruitObject released = relation.fruit;
                if (isBlockedPosition(blockedPositions, released)) continue;

                if (findNearestBlockingFruit(released, remaining, frameWidth, frameHeight) != null) {
                    continue;
                }

                double trayMatch = 0.0;
                for (TrayItem item : tray.items) {
                    if (item == null || item.hist == null) continue;
                    trayMatch = Math.max(trayMatch, histCosine(released.hist, item.hist));
                }

                double boardMatch = 0.0;
                FruitObject boardMate = null;
                for (FruitObject other : remaining) {
                    if (other == null || other == released) continue;
                    if (isBlockedPosition(blockedPositions, other)) continue;
                    if (findNearestBlockingFruit(other, remaining, frameWidth, frameHeight) != null) {
                        continue;
                    }
                    Similarity sim = similarity(released, other);
                    if (sim.rgbMad <= MAX_RGB_MAD
                            && sim.histCos >= MIN_HIST_COS
                            && sim.shapeIou >= MIN_SHAPE_IOU
                            && sim.score >= MIN_PAIR_SCORE
                            && sim.score > boardMatch) {
                        boardMatch = sim.score;
                        boardMate = other;
                    }
                }

                // trayCount=2 时，blocker 入第三槽后没有空位启动另一种类型，
                // 所以释放出来的水果必须能直接匹配 blocker 自身，或者与当前TOP直配。
                double blockerMatch = similarity(blocker, released).score;
                boolean topSafe = trayCount < 2
                        || blockerMatch >= MIN_PAIR_SCORE;

                boolean useful = trayMatch >= TRAY_HIST_MATCH_MIN
                        || boardMatch >= MIN_PAIR_SCORE
                        || (trayCount == 0 && blockerMatch >= MIN_PAIR_SCORE);
                if (!useful || !topSafe) continue;

                double chain = Math.max(trayMatch, boardMatch);
                if (trayCount == 0) chain = Math.max(chain, blockerMatch);

                int cascadeFollowers = countPotentialCascadeFollowers(blocker, objects, drop);
                int cascadePairs = countCascadeEliminationPairs(
                        blocker, objects, drop, tray.items);
                int projectedTrayCount = projectCascadeTrayCount(
                        trayCount, 1 + cascadeFollowers, cascadePairs);
                if (!allowsCascadeOccupancy(
                        trayCount, cascadeFollowers, cascadePairs)) continue;

                double lower = clamp01(blocker.centerY / Math.max(1.0, frameHeight));

                // “能立刻释放一个可执行后手”权重最高；距离底部只做次要排序。
                double rank = 0.58 * chain
                        + 0.24
                        + (trayMatch >= TRAY_HIST_MATCH_MIN ? 0.10 : 0.0)
                        + (boardMate != null ? 0.05 : 0.0)
                        + 0.03 * lower
                        - (trayCount == 2 ? 0.06 : 0.0);

                SafePushChoice candidate = new SafePushChoice(
                        blocker, chain, true, true, 1, 1, rank, true, cascadeFollowers);
                if (cascadeFollowers > 0) {
                    hostlessLogCascadeGuard(
                            "dependency tray=" + trayCount
                                    + " followers=" + cascadeFollowers
                                    + " elimPairs=" + cascadePairs
                                    + " projected=" + projectedTrayCount);
                }
                if (best == null || candidate.rank > best.rank) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * V4.45 两步前瞻：假设 candidate 进入槽位、随后点击 mate 完成二消，
     * 检查剩余棋盘中是否还有可靠的完整对子。
     *
     * 这里只做只读几何/视觉评估，不修改真实棋盘；真实执行仍然是“一次只点一步，
     * 点击后重新截图、重新建模”。
     */
    private static int countReliablePairsAfterRemoving(
            List<FruitObject> objects,
            FruitObject candidate,
            FruitObject mate,
            int frameWidth,
            int frameHeight,
            Set<String> blockedPositions
    ) {
        if (objects == null || objects.size() < 4) return 0;

        List<FruitObject> remaining = new ArrayList<>();
        Set<FruitObject> clear = new HashSet<>();
        for (FruitObject fruit : objects) {
            if (fruit == null || fruit == candidate || fruit == mate) continue;
            if (isBlockedPosition(blockedPositions, fruit)) continue;
            remaining.add(fruit);
        }

        for (FruitObject fruit : remaining) {
            if (findNearestBlockingFruit(fruit, remaining, frameWidth, frameHeight) == null) {
                clear.add(fruit);
            }
        }

        int pairs = 0;
        for (int i = 0; i < remaining.size(); i++) {
            FruitObject a = remaining.get(i);
            if (!clear.contains(a)) continue;
            for (int j = i + 1; j < remaining.size(); j++) {
                FruitObject b = remaining.get(j);
                if (!clear.contains(b)) continue;

                Similarity sim = similarity(a, b);
                if (sim.rgbMad > MAX_RGB_MAD
                        || sim.histCos < MIN_HIST_COS
                        || sim.shapeIou < MIN_SHAPE_IOU
                        || sim.score < MIN_PAIR_SCORE) {
                    continue;
                }
                pairs++;
                if (pairs >= 2) return pairs;
            }
        }
        return pairs;
    }

    private static double histCosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;
        double dot = 0.0;
        double aa = 0.0;
        double bb = 0.0;
        for (int i = 0; i < a.length; i++) {
            double av = a[i];
            double bv = b[i];
            dot += av * bv;
            aa += av * av;
            bb += bv * bv;
        }
        if (aa <= 0.0 || bb <= 0.0) return 0.0;
        return dot / Math.sqrt(aa * bb);
    }

    static boolean isBridgeMateSimilarity(
            double score, double rgbMad, double histCos, double shapeIou
    ) {
        return rgbMad <= BRIDGE_MAX_RGB_MAD
                && histCos >= BRIDGE_MIN_HIST_COS
                && shapeIou >= BRIDGE_MIN_SHAPE_IOU
                && score >= BRIDGE_MIN_PAIR_SCORE;
    }

    static boolean allowsBridgePush(
            int trayCount, boolean directUnlockMate, boolean mateDroppable
    ) {
        if (trayCount == 0) return true;
        if (trayCount == 1) return directUnlockMate || mateDroppable;
        return trayCount == 2 && mateDroppable;
    }

    private static boolean directlyUnlocksMate(
            FruitObject blocker, FruitObject mate, DropAnalysis drop
    ) {
        if (blocker == null || mate == null || drop == null) return false;
        for (BlockingRelation relation : drop.blocked) {
            if (relation != null && relation.blocker == blocker && relation.fruit == mate) {
                return true;
            }
        }
        return false;
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
        return verifyPairRemaining(host, beforeRemaining, actionIndex, stage, false);
    }

    /**
     * V4.50：对“级联落果导致槽位数量与常规预期不一致”的情况，
     * 不能使用剩余数快速估算，因为这一步的槽位结果本身就是异常/级联证据。
     * 必须用实际OCR确认剩余数确实减少2。
     */
    private static RemainingVerification verifyPairRemaining(
            Host host,
            int beforeRemaining,
            int actionIndex,
            String stage,
            boolean forceOcr
    ) {
        if (!forceOcr && actionIndex % REMAINING_OCR_INTERVAL != 0 && beforeRemaining > 6) {
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

        // 实机 OCR 会把“槽位”识别成“糟位/檀位/位”，也会把“使用”识别成
        // “[DJ使用/□使用”等带噪声文本。只要同时出现“解锁槽位”语义和
        // 工具动作词，就认为是阻塞型道具弹窗；绝不点击弹窗里的功能按钮，
        // 只交给 dismissBlockingFunctionPopup* 点击固定右上角 X。
        // OCR 经常只识别到“解锁 + 打乱”，漏掉“所有槽位/使用”等面板文字。
        // 正常水果页的“打乱”本身不会和“解锁”同时出现；因此这组组合也必须视为弹窗。
        boolean unlockSlotPopup = t.contains("解锁所有槽位")
                || t.contains("解锁所有糟位")
                || t.contains("解锁所有檀位");
        if (unlockSlotPopup) return true;

        boolean useAction = t.contains("使用")
                || t.contains("立即使用")
                || t.contains("确认使用");
        boolean toolName = t.contains("解锁")
                || t.contains("消除")
                || t.contains("打乱");
        // 正常水果页也固定显示“解锁/消除/打乱/剩余”，所以不能仅凭这些词判定弹窗。
        // 你提供的三种真实弹窗都包含“使用”按钮，或者包含“解锁所有槽位”正文。
        return useAction && toolName;
    }

    /**
     * V4.44.3 快速B重定位：只搜索原计划位置周围，不重跑全棋盘形态学。
     * 必须同时通过原有总分、RGB、直方图和形状门槛；任何不确定性都返回null，
     * 由调用方回退到完整检测。
     */
    private static FruitMatch findMatchingFruitNearPlan(
            FruitObject expected, GameFrame frame, Set<String> blockedPositions
    ) {
        if (expected == null || frame == null || frame.bitmap == null) return null;
        Bitmap bitmap = frame.bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int bw = Math.max(1, Math.round(expected.width()));
        int bh = Math.max(1, Math.round(expected.height()));
        int baseX = Math.round(expected.left);
        int baseY = Math.round(expected.top);
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] samples = new int[GRID * GRID];
        int sampleCount = 0;
        for (int k = 0; k < expected.shape.length; k++) {
            if (expected.shape[k]) samples[sampleCount++] = k;
        }
        if (sampleCount < GRID * GRID * 0.30) return null;

        FruitMatch best = null;
        for (int dy = -FAST_REACQUIRE_RADIUS_PX; dy <= FAST_REACQUIRE_RADIUS_PX; dy += 3) {
            for (int dx = -FAST_REACQUIRE_RADIUS_PX; dx <= FAST_REACQUIRE_RADIUS_PX; dx += 3) {
                int x = baseX + dx;
                int y = baseY + dy;
                if (x < 0 || y < 0 || x + bw >= width || y + bh >= height) continue;
                if (templateError(expected, samples, sampleCount, pixels, width,
                        x, y, bw, bh, 24) > 0.080) continue;
                if (templateError(expected, samples, sampleCount, pixels, width,
                        x, y, bw, bh, sampleCount) > 0.048) continue;
                FruitObject observed = observedTemplate(expected, pixels, width, x, y, bw, bh);
                if (isBlockedPosition(blockedPositions, observed)) continue;
                Similarity sim = similarity(expected, observed);
                if (!passesStrictPairSimilarity(sim)) continue;
                if (best == null || sim.score > best.similarity.score) {
                    best = new FruitMatch(observed, sim, sim.score);
                }
            }
        }
        return best;
    }

    private static boolean passesStrictPairSimilarity(Similarity sim) {
        return sim != null
                && sim.score >= MIN_PAIR_SCORE
                && sim.rgbMad <= MAX_RGB_MAD
                && sim.histCos >= MIN_HIST_COS
                && sim.shapeIou >= MIN_SHAPE_IOU;
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
     * Only invoked after two independent no-move models at 2/3 or 3/3 occupancy.
     * The gear itself is fixed by the game chrome; every destructive menu choice is
     * text-located by OCR and constrained again by GameTapPolicy.
     */
    private static RestartResult restartDeadlockedRound(
            Host host, ScreenOcr.Snapshot checkpoint, int restartCount
    ) {
        if (restartCount >= MAX_DEADLOCK_RESTARTS) {
            host.log("[死局重开V4.55] 已达本任务重开上限，不再循环重开");
            return RestartResult.NOT_AVAILABLE;
        }
        if (checkpoint == null || checkpoint.width <= 0 || checkpoint.height <= 0) {
            return RestartResult.NOT_AVAILABLE;
        }
        String text = normalize(checkpoint.fullText);
        if (!looksLikeFruitGame(text) || looksLikeBlockingFunctionPopupText(text)) {
            return RestartResult.NOT_AVAILABLE;
        }

        int gearX = Math.round(checkpoint.width * 0.052f);
        int gearY = Math.round(checkpoint.height * 0.047f);
        host.log("[死局重开V4.55] 连续无解，打开设置菜单尝试重新开始 "
                + (restartCount + 1) + "/" + MAX_DEADLOCK_RESTARTS);
        if (!host.tap(gearX, gearY, "水果游戏-死局设置")) {
            return host.aborted() ? RestartResult.ABORTED : RestartResult.NOT_AVAILABLE;
        }
        if (!host.sleep(280L, 440L)) return RestartResult.ABORTED;

        ScreenOcr.Snapshot menu = requireOcr(host, "水果V4.55/死局设置菜单");
        if (host.aborted()) return RestartResult.ABORTED;
        ScreenOcr.Item restart = findRestartItem(menu);
        if (restart == null || !isFruitRestartMenuCoordinate(
                restart.centerX(), restart.centerY(), menu.width, menu.height)) {
            host.log("[死局重开V4.55] 设置菜单未确认‘重新开始’，保留现场");
            return RestartResult.NOT_AVAILABLE;
        }
        if (!host.tap(restart.centerX(), restart.centerY(), "水果游戏-死局重新开始")) {
            return host.aborted() ? RestartResult.ABORTED : RestartResult.NOT_AVAILABLE;
        }
        if (!host.sleep(280L, 440L)) return RestartResult.ABORTED;

        // 部分版本会有二次确认；只在确认框中点击明确的“确定/重新开始”。
        ScreenOcr.Snapshot confirm = requireOcr(host, "水果V4.55/死局重开确认");
        if (host.aborted()) return RestartResult.ABORTED;
        ScreenOcr.Item confirmButton = findRestartConfirmItem(confirm);
        if (confirmButton != null && isFruitRestartMenuCoordinate(
                confirmButton.centerX(), confirmButton.centerY(), confirm.width, confirm.height)) {
            if (!host.tap(confirmButton.centerX(), confirmButton.centerY(), "水果游戏-死局确认重开")) {
                return host.aborted() ? RestartResult.ABORTED : RestartResult.NOT_AVAILABLE;
            }
        }
        if (!host.sleep(460L, 700L)) return RestartResult.ABORTED;
        return RestartResult.RESTARTED;
    }

    private static ScreenOcr.Item findRestartItem(ScreenOcr.Snapshot snapshot) {
        if (snapshot == null) return null;
        ScreenOcr.Item item = snapshot.findBest("重新开始");
        if (item == null) item = snapshot.findBest("重新玩");
        if (item == null) item = snapshot.findBest("重开");
        return item;
    }

    private static ScreenOcr.Item findRestartConfirmItem(ScreenOcr.Snapshot snapshot) {
        if (snapshot == null) return null;
        ScreenOcr.Item item = snapshot.findBest("确定");
        if (item == null) item = findRestartItem(snapshot);
        return item;
    }

    private static boolean isFruitRestartMenuCoordinate(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return false;
        double nx = x / (double) width;
        double ny = y / (double) height;
        return nx >= 0.18 && nx <= 0.82 && ny >= 0.22 && ny <= 0.82;
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
        // 弹窗面板实际占比可能因设备分辨率/动画而低于旧版0.46门槛。
        // 正常棋盘中央以蓝色为主，暖白/米黄占比通常远低于此值，因此降低门槛
        // 可以覆盖“小弹窗/淡入动画”，同时不会把普通棋盘误判为弹窗。
        return ratio >= 0.22;
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

    /**
     * A compact, bounded extension of the existing runtime feature preference store.
     * It records only post-tap facts: a visual bucket that did enter the tray is a
     * positive example; one that remained in place is a negative example.  It does
     * not persist coordinates precisely and it never overrides current occlusion
     * analysis, so experience cannot turn a covered fruit into a clickable one.
     */
    private static final class FruitDropFeatureStore {
        private final SharedPreferences prefs;
        private final int width;
        private final int height;

        FruitDropFeatureStore(Context context, int width, int height) {
            this.prefs = context == null ? null : context.getSharedPreferences(
                    FRUIT_FEATURE_PREFS, Context.MODE_PRIVATE);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }

        double prior(FruitObject fruit) {
            if (prefs == null || fruit == null) return 0.0;
            String key = key(fruit);
            int clear = prefs.getInt(key + "_clear", 0);
            int blocked = prefs.getInt(key + "_blocked", 0);
            int total = clear + blocked;
            if (total <= 0) return 0.0;
            // Laplace smoothing prevents one noisy observation from dominating.
            return ((clear + 1.0) / (total + 2.0)) - 0.5;
        }

        void record(FruitObject fruit, boolean enteredTray) {
            if (prefs == null || fruit == null) return;
            String key = key(fruit);
            String suffix = enteredTray ? "_clear" : "_blocked";
            int old = prefs.getInt(key + suffix, 0);
            // Bounded counters keep this feature bank small and resistant to stale runs.
            prefs.edit()
                    .putInt(key + suffix, Math.min(24, old + 1))
                    .putLong(key + "_last", System.currentTimeMillis())
                    .apply();
        }

        private String key(FruitObject fruit) {
            int col = clamp((int) (fruit.centerX * 6 / width), 0, 5);
            int row = clamp((int) (fruit.centerY * 7 / height), 0, 6);
            int color = dominantHistogramBin(fruit.hist) / 4;
            return FRUIT_FEATURE_PREFIX + col + '_' + row + '_' + color;
        }

        private static int dominantHistogramBin(float[] hist) {
            if (hist == null || hist.length == 0) return 0;
            int best = 0;
            for (int i = 1; i < hist.length; i++) {
                if (hist[i] > hist[best]) best = i;
            }
            return best;
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
        final boolean mateDroppable;
        final int unlockGain;
        final int continuationPairs;
        final double rank;
        final int cascadeFollowers;

        final boolean dependencyChain;
        final int dependencyDepth;
        final String dependencySlot;

        SafePushChoice(FruitObject fruit, double mateScore, boolean directUnlockMate,
                       boolean mateDroppable, int unlockGain, int continuationPairs,
                       double rank) {
            this(fruit, mateScore, directUnlockMate, mateDroppable,
                    unlockGain, continuationPairs, rank, false, 0, 0, null);
        }

        SafePushChoice(FruitObject fruit, double mateScore, boolean directUnlockMate,
                       boolean mateDroppable, int unlockGain, int continuationPairs,
                       double rank, boolean dependencyChain) {
            this(fruit, mateScore, directUnlockMate, mateDroppable,
                    unlockGain, continuationPairs, rank, dependencyChain, 0, 0, null);
        }

        SafePushChoice(FruitObject fruit, double mateScore, boolean directUnlockMate,
                       boolean mateDroppable, int unlockGain, int continuationPairs,
                       double rank, boolean dependencyChain, int cascadeFollowers) {
            this(fruit, mateScore, directUnlockMate, mateDroppable,
                    unlockGain, continuationPairs, rank, dependencyChain,
                    cascadeFollowers, 0, null);
        }

        SafePushChoice(FruitObject fruit, double mateScore, boolean directUnlockMate,
                       boolean mateDroppable, int unlockGain, int continuationPairs,
                       double rank, boolean dependencyChain, int cascadeFollowers,
                       int dependencyDepth, String dependencySlot) {
            this.fruit = fruit;
            this.mateScore = mateScore;
            this.directUnlockMate = directUnlockMate;
            this.mateDroppable = mateDroppable;
            this.unlockGain = unlockGain;
            this.continuationPairs = continuationPairs;
            this.rank = rank;
            this.dependencyChain = dependencyChain;
            this.cascadeFollowers = cascadeFollowers;
            this.dependencyDepth = dependencyDepth;
            this.dependencySlot = dependencySlot;
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
