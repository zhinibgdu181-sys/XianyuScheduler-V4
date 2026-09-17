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
 * V4.36.2 视觉小游戏模块："消了还想消"水果配对。
 *
 * 规则：点击水果会进入下方坑位；两个相同水果自动消除；坑位最多3个。
 * 安全策略：只点击高置信度的完整同类对 A->A，一次只处理一对。
 * 只允许点击水果对象本身。禁止点击“打乱”“消除”“解锁”等任何游戏功能按钮。
 * 找不到高置信度对子时直接安全停止。
 *
 * V4.36.2 改进：
 * 1. 决策引擎：视觉置信度只是硬门槛，在可靠对子中优先处理底层、低风险、高释放价值组合。
 * 2. Observe→Evaluate→Act→Verify→Replan：点A后重新截图定位B，不使用已经失效的旧坐标。
 * 3. 每一对都用“剩余 N→N-2”闭环验证；失败组合进入本局黑名单并安全停止。
 * 4. 保留功能按钮禁区，不点击打乱/消除/解锁/使用。
 * 5. SAFE_STOP 拆分 CLEAN/DIRTY；点击过水果但未验证成功时禁止外层二次求解。
 * 6. 把长时间无操作自动出现的“解锁/消除/打乱”推广窗作为异步遮挡层处理。
 * 7. 新增真实“可下落性”建模：水果只有到下方坑洞的竖直通道没有被其它水果挡住，才允许进入点击候选。
 * 8. A 下落后重新计算整张棋盘的遮挡关系，B 必须在新局面中仍然可直接下落才允许点击。
 *
 * 这个实现不依赖 OpenCV。它把截图缩放到约720px宽，利用蓝色背景分割、
 * 连通域、归一化图块颜色/形状相似度寻找重复水果。
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

    // V4.36 保守阈值。实机日志中 0.981/0.976 的组合仍未真正消除，
    // 因此取消低阈值试错；坑位只有3个，宁可停止也不把相似但不同的水果塞入坑位。
    private static final double MIN_PAIR_SCORE = 0.990;
    private static final double MAX_RGB_MAD = 0.055;
    private static final double MIN_HIST_COS = 0.970;
    private static final double MIN_SHAPE_IOU = 0.80;

    private static final double[] FALLBACK_THRESHOLDS_V436 = {
            MIN_PAIR_SCORE
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
            return Result.SAFE_STOP_CLEAN;
        }

        if (!host.sleep(420L, 720L)) return Result.ABORTED;

        // V4.36：进入水果任务后可能经历 loading / “开始游戏”首页。
        // 不再只看一帧就判死刑；最多等待 8 秒，并在出现开始页时主动点“开始游戏”。
        String firstText = "";
        boolean confirmed = false;
        long enterWaitStart = SystemClock.elapsedRealtime();

        while (!host.aborted()
                && SystemClock.elapsedRealtime() - enterWaitStart < 8000L) {
            ScreenOcr.Snapshot firstOcr = host.ocr(
                    firstText.isEmpty()
                            ? "水果游戏V4.36/进入确认"
                            : "水果游戏V4.36/加载等待");
            if (host.aborted()) return Result.ABORTED;

            firstText = normalize(firstOcr == null ? "" : firstOcr.fullText);

            if (looksLikeFruitGame(firstText)) {
                confirmed = true;
                break;
            }

            if (looksLikeFruitStartScreen(firstText)) {
                GameFrame startFrame = captureFrame(context, suPath, host);
                if (startFrame == null) {
                    host.log("[游戏V4.36] 检测到‘开始游戏’页，但截图失败，安全停止");
                    return Result.SAFE_STOP_CLEAN;
                }
                int startX = Math.round(startFrame.originalWidth * 0.50f);
                int startY = Math.round(startFrame.originalHeight * 0.75f);
                safeRecycle(startFrame.bitmap);

                host.log("[游戏V4.36] 检测到‘开始游戏’页，点击开始 → "
                        + startX + "," + startY);
                if (host.aborted()) return Result.ABORTED;
                if (!host.tap(startX, startY, "水果游戏-开始游戏")) {
                    host.log("[开始页V4.36] 点击‘开始游戏’失败，安全停止");
                    return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_CLEAN;
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
        }

        if (!confirmed) {
            host.log("[游戏V4.36] 等待 8 秒后仍未进入正式水果关卡，安全停止");
            return Result.NOT_FRUIT_GAME;
        }

        int remaining = parseRemaining(firstText);
        int progress = parsePercent(firstText);
        host.log("[游戏V4.36] ✅ 识别水果游戏"
                + (remaining >= 0 ? " / 剩余=" + remaining : "")
                + (progress >= 0 ? " / 进度=" + progress + "%" : ""));

        long started = SystemClock.elapsedRealtime();
        int pairActions = 0;
        int consecutiveCaptureFail = 0;

        // V4.36 本轮验证失败过的对子进入黑名单，不再重复尝试
        Set<String> failedPairs = new HashSet<>();
        // 本局被证实“点击后仍停在原位”的水果位置。棋盘未变化时不再点它；
        // 一旦有一对成功消除、棋盘重新下落，就清空这些旧位置。
        Set<String> blockedPositions = new HashSet<>();

        while (!host.aborted()
                && pairActions < MAX_PAIR_ACTIONS
                && SystemClock.elapsedRealtime() - started < MAX_ROUND_MS) {

            GameFrame frame = captureFrame(context, suPath, host);
            if (frame == null) {
                consecutiveCaptureFail++;
                host.log("[游戏V4.36] 截图失败 " + consecutiveCaptureFail + "/3");
                if (consecutiveCaptureFail >= 3) return Result.SAFE_STOP_CLEAN;
                if (!host.sleep(250L, 420L)) return Result.ABORTED;
                continue;
            }
            consecutiveCaptureFail = 0;

            // V4.36：小游戏会在长时间无操作时自动弹出“解锁/消除/打乱”推广窗。
            // 它是异步遮挡层，不是求解器误点功能按钮。只允许点击弹窗自身右上角 X。
            // 关闭以后旧视觉决策全部作废，重新截图规划。
            if (looksLikeBlockingFunctionPopup(frame)) {
                PopupDismissResult popup = dismissBlockingFunctionPopup(
                        context, suPath, host, frame, "主循环");
                safeRecycle(frame.bitmap);
                if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                if (popup != PopupDismissResult.DISMISSED) {
                    host.log("[弹窗V4.36] 无法确认弹窗已关闭，CLEAN安全停止");
                    return Result.SAFE_STOP_CLEAN;
                }
                host.log("[弹窗V4.36] 弹窗关闭后旧水果坐标全部作废，重新分析棋盘");
                continue;
            }

            List<FruitObject> objects = detectFruitObjects(frame);

            // V4.36.2：真正按游戏物理规则判断“可下落”。
            // 只有水果到底部坑洞的竖直扫掠通道没有被其它水果占据，才允许点击。
            DropAnalysis drop = analyzeDroppability(objects);
            List<FruitObject> droppableObjects = drop.droppable;
            host.log("[下落V4.36.2] 识别水果=" + objects.size()
                    + " / 可直接下落=" + droppableObjects.size()
                    + " / 被遮挡=" + drop.blocked.size()
                    + blockedSummary(drop, frame));

            // V4.36 保守阈值：不再向 0.93/0.88/0.83 降级。
            // V4.36.2 更进一步：只在物理上可直接掉入坑洞的水果中寻找对子。
            PairChoice pair = null;
            double hitThreshold = MIN_PAIR_SCORE;
            for (double t : FALLBACK_THRESHOLDS_V436) {
                pair = chooseBestPairWithThreshold(
                        droppableObjects, t, failedPairs, blockedPositions);
                if (pair != null) {
                    hitThreshold = t;
                    break;
                }
            }

            host.log("[游戏V4.36.2] 可配对候选=" + droppableObjects.size()
                    + " / 总水果=" + objects.size()
                    + " / 顶部禁区<" + Math.round(frame.originalHeight * 0.115f)
                    + " / 底部禁区>" + Math.round(frame.originalHeight * 0.615f)
                    + (pair == null ? " / 无可下落高置信对子" :
                    " / 最佳对子=" + format(pair.score)
                            + " rgb=" + format(pair.rgbSimilarity)
                            + " hist=" + format(pair.histCos)
                            + " shape=" + format(pair.shapeIou)
                            + " decision=" + format(pair.decisionScore)
                            + " lower=" + format(pair.lowerBoth)
                            + " threshold=" + format(hitThreshold)));

            if (pair != null && hitThreshold < MIN_PAIR_SCORE) {
                host.log("[游戏V4.36] ⚠️ 阈值降级命中 " + format(hitThreshold)
                        + " / score=" + format(pair.score)
                        + " / 剩余水果=" + objects.size());
            }

            if (objects.size() < 10) {
                saveVisionDiagnostic(context, frame.bitmap, "low_objects_" + objects.size());
                host.log("[游戏V4.36] 检测数量异常偏少，已保存视觉诊断图；本轮不会点击功能按钮");
            }

            if (pair == null) {
                safeRecycle(frame.bitmap);

                ScreenOcr.Snapshot checkpoint = host.ocr("水果游戏V4.36/无对子检查");
                if (host.aborted()) return Result.ABORTED;
                String text = normalize(checkpoint == null ? "" : checkpoint.fullText);
                if (isRoundCompleted(text)) {
                    host.log("[游戏V4.36] ✅ 已检测到一关完成状态");
                    return Result.COMPLETED;
                }

                host.log("[游戏V4.36.2] 当前没有‘可直接下落 + 高置信同类’对子"
                        + (drop.blocked.isEmpty() ? "" : "；仍有被遮挡水果，暂不做单槽冒险试探")
                        + "，CLEAN安全停止");
                return Result.SAFE_STOP_CLEAN;
            }

            int beforeRemaining = remaining;
            int beforeObjectCount = objects.size();
            int ax = mapX(frame, pair.a.centerX);
            int ay = mapY(frame, pair.a.centerY);
            int plannedBx = mapX(frame, pair.b.centerX);
            int plannedBy = mapY(frame, pair.b.centerY);
            FruitObject expectedB = pair.b;
            safeRecycle(frame.bitmap);

            host.log("[决策V4.36.2] 选择当前可下落动作 / A=(" + ax + "," + ay + ")"
                    + " / 计划B=(" + plannedBx + "," + plannedBy + ")"
                    + " / visual=" + format(pair.score)
                    + " / decision=" + format(pair.decisionScore)
                    + " / lower=" + format(pair.lowerBoth)
                    + " / 原因=下落通道畅通+可靠同类+底层优先");

            // 只执行一步，然后重新观察。A 下落后旧的 B 坐标立即作废。
            if (!host.tap(ax, ay, "水果游戏-配对A")) {
                // 已经尝试发送 A 点击，无法确认设备是否实际接收；按 DIRTY 处理最安全。
                return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
            }
            if (!host.sleep(300L, 480L)) return Result.ABORTED;

            GameFrame afterA = captureFrame(context, suPath, host);
            if (afterA == null) {
                host.log("[游戏V4.36] A点击后无法重新观察；坑位状态未知，DIRTY安全停止");
                return Result.SAFE_STOP_DIRTY;
            }

            // A 后也要检查异步空闲弹窗。若此时弹窗存在，无法确认 A 是进入坑位
            // 还是点在遮挡层上；关闭后旧对子全部作废，按 DIRTY 退出本段。
            if (looksLikeBlockingFunctionPopup(afterA)) {
                PopupDismissResult popup = dismissBlockingFunctionPopup(
                        context, suPath, host, afterA, "A点击后");
                safeRecycle(afterA.bitmap);
                if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                host.log("[弹窗V4.36] A阶段出现异步弹窗；A是否进入坑位不可确认，DIRTY安全停止");
                return Result.SAFE_STOP_DIRTY;
            }

            List<FruitObject> afterObjects = detectFruitObjects(afterA);
            DropAnalysis afterDrop = analyzeDroppability(afterObjects);

            // 先确认 A 这一击真的改变了棋盘。
            // 物理规则已经保证 A 点击前“下落通道畅通”，这里再用“原位目标 + 总对象数变化”
            // 做第二层验证。若原位仍是同一水果且对象数没有减少，则认为 A 没有掉入坑洞。
            FruitMatch stillA = findMatchingFruitNear(
                    pair.a, afterObjects, pair.a.centerX, pair.a.centerY,
                    afterA.bitmap.getWidth(), afterA.bitmap.getHeight(),
                    0.060, 0.050, 0.990);
            int objectDeltaAfterA = beforeObjectCount - afterObjects.size();
            if (stillA != null && objectDeltaAfterA <= 0) {
                blockedPositions.add(positionKeyV4361(pair.a));
                int stillAx = mapX(afterA, stillA.fruit.centerX);
                int stillAy = mapY(afterA, stillA.fruit.centerY);
                safeRecycle(afterA.bitmap);
                host.log("[点击验证V4.36.2] A点击后目标仍在原位附近 → ("
                        + stillAx + "," + stillAy + ")"
                        + " / sim=" + format(stillA.similarity.score)
                        + " / 对象数变化=" + beforeObjectCount + "→" + afterObjects.size()
                        + " / 判定A未成功掉入坑洞；不点B，屏蔽该位置后重新规划");
                continue;
            }

            host.log("[点击验证V4.36.2] A后重建遮挡图 / 水果=" + afterObjects.size()
                    + " / 可直接下落=" + afterDrop.droppable.size()
                    + " / 被遮挡=" + afterDrop.blocked.size()
                    + " / 对象数变化=" + beforeObjectCount + "→" + afterObjects.size());

            // B 不能只“长得像”；它在 A 下落后的新局面里也必须仍然可以直接掉入坑洞。
            FruitMatch reacquired = findBestMatchingFruit(
                    expectedB, afterDrop.droppable,
                    afterA.bitmap.getWidth(), afterA.bitmap.getHeight());
            if (reacquired == null) {
                FruitMatch existingB = findBestMatchingFruit(
                        expectedB, afterObjects,
                        afterA.bitmap.getWidth(), afterA.bitmap.getHeight());
                if (existingB != null) {
                    FruitObject blocker = findNearestBlockingFruit(existingB.fruit, afterObjects);
                    host.log("[下落V4.36.2] B仍存在但没有进入‘可直接下落’候选"
                            + (blocker == null ? "" : " / blocker=("
                            + mapX(afterA, blocker.centerX) + ","
                            + mapY(afterA, blocker.centerY) + ")")
                            + "；A可能已进入坑位，DIRTY安全停止");
                } else {
                    host.log("[游戏V4.36.2] A已离开原位，但无法在合理范围重新定位可下落B；"
                            + "A可能已占用坑位，DIRTY安全停止");
                }
                safeRecycle(afterA.bitmap);
                return Result.SAFE_STOP_DIRTY;
            }
            FruitObject reacquiredB = reacquired.fruit;
            int bx = mapX(afterA, reacquiredB.centerX);
            int by = mapY(afterA, reacquiredB.centerY);
            double dxRatio = Math.abs(reacquiredB.centerX - expectedB.centerX)
                    / Math.max(1.0, afterA.bitmap.getWidth());
            double dyRatio = (reacquiredB.centerY - expectedB.centerY)
                    / Math.max(1.0, afterA.bitmap.getHeight());
            safeRecycle(afterA.bitmap);

            host.log("[决策V4.36.2] B可下落重定位 / 原计划=("
                    + plannedBx + "," + plannedBy + ") → 新B=(" + bx + "," + by + ")"
                    + " / sim=" + format(reacquired.similarity.score)
                    + " rgb=" + format(1.0 - reacquired.similarity.rgbMad)
                    + " hist=" + format(reacquired.similarity.histCos)
                    + " shape=" + format(reacquired.similarity.shapeIou)
                    + " / dx=" + format(dxRatio) + " dy=" + format(dyRatio));
            if (!host.tap(bx, by, "水果游戏-配对B")) {
                return host.aborted() ? Result.ABORTED : Result.SAFE_STOP_DIRTY;
            }
            pairActions++;
            if (!host.sleep(360L, 560L)) return Result.ABORTED;

            // B 后先检查异步空闲弹窗。若它抢占页面，先只点右上角 X 关闭，
            // 再通过 remaining 的闭环结果判断这对是否真的成功。
            GameFrame postB = captureFrame(context, suPath, host);
            if (postB != null) {
                if (looksLikeBlockingFunctionPopup(postB)) {
                    PopupDismissResult popup = dismissBlockingFunctionPopup(
                            context, suPath, host, postB, "B点击后");
                    safeRecycle(postB.bitmap);
                    if (popup == PopupDismissResult.ABORTED) return Result.ABORTED;
                    if (popup != PopupDismissResult.DISMISSED) {
                        host.log("[弹窗V4.36] B后弹窗无法确认关闭；坑位状态未知，DIRTY安全停止");
                        return Result.SAFE_STOP_DIRTY;
                    }
                    host.log("[弹窗V4.36] B后弹窗已关闭；继续用剩余数验证本对子");
                } else {
                    List<FruitObject> postBObjects = detectFruitObjects(postB);
                    FruitMatch stillB = findMatchingFruitNear(
                            reacquiredB, postBObjects, reacquiredB.centerX, reacquiredB.centerY,
                            postB.bitmap.getWidth(), postB.bitmap.getHeight(),
                            0.060, 0.050, 0.990);
                    host.log("[点击验证V4.36.2] B后对象数=" + afterObjects.size()
                            + "→" + postBObjects.size()
                            + (stillB == null ? " / B原位未检出"
                            : " / B原位仍有同类 sim=" + format(stillB.similarity.score)));
                    safeRecycle(postB.bitmap);
                }
            } else {
                host.log("[弹窗V4.36] B后弹窗守卫截图失败；继续进入OCR闭环验证");
            }

            // 每一对都闭环验证，不再等到第6对才发现整局没有进展。
            ScreenOcr.Snapshot verifyOcr = host.ocr("水果游戏V4.36/逐对验证#" + pairActions);
            if (host.aborted()) return Result.ABORTED;
            String verifyText = normalize(verifyOcr == null ? "" : verifyOcr.fullText);
            int afterRemaining = parseRemaining(verifyText);
            if (isRoundCompleted(verifyText)) {
                host.log("[验证V4.36] ✅ 第1关完成");
                return Result.COMPLETED;
            }
            if (afterRemaining >= 0) {
                if (beforeRemaining >= 0 && afterRemaining == beforeRemaining - 2) {
                    host.log("[验证V4.36] ✅ 配对确认：" + beforeRemaining + "→" + afterRemaining
                            + " / 本次决策有效，重新分析新局面");
                    remaining = afterRemaining;
                    // 棋盘已经重排，旧的“不可点击位置”坐标不再有意义。
                    blockedPositions.clear();
                } else if (beforeRemaining >= 0 && afterRemaining >= beforeRemaining) {
                    failedPairs.add(pairKeyV435(pair.a, pair.b));
                    host.log("[验证V4.36] ❌ 未确认消除：" + beforeRemaining + "→" + afterRemaining
                            + " / 当前视觉组合加入黑名单；坑位可能已有异类水果，DIRTY安全停止");
                    saveCurrentFrameDiagnostic(context, suPath, host,
                            "pair_fail_" + beforeRemaining + "_to_" + afterRemaining
                                    + "_score_" + format(pair.score).replace('.', '_'));
                    return Result.SAFE_STOP_DIRTY;
                } else {
                    host.log("[验证V4.36] 状态发生变化：" + beforeRemaining + "→" + afterRemaining
                            + " / 重新建图");
                    remaining = afterRemaining;
                }
            } else {
                host.log("[验证V4.36] OCR未读到剩余数；保留视觉结果并重新建图");
            }

            // OCR is intentionally sparse; screenshot vision handles most pairs.
            // Every few pairs verify that the game is still progressing / ended.
            if (pairActions % 6 == 0) {
                ScreenOcr.Snapshot checkpoint = host.ocr(
                        "水果游戏V4.36/进度检查#" + pairActions);
                if (host.aborted()) return Result.ABORTED;
                String text = normalize(checkpoint == null ? "" : checkpoint.fullText);

                int nowRemaining = parseRemaining(text);
                int nowProgress = parsePercent(text);
                host.log("[游戏V4.36] 进度检查 pair=" + pairActions
                        + (nowRemaining >= 0 ? " / 剩余=" + nowRemaining : "")
                        + (nowProgress >= 0 ? " / " + nowProgress + "%" : ""));

                if (isRoundCompleted(text)) {
                    host.log("[游戏V4.36] ✅ 第1关完成");
                    return Result.COMPLETED;
                }

                // If we unexpectedly left the fruit game, do not continue tapping.
                if (!text.isEmpty() && !looksLikeFruitGame(text)) {
                    if (looksLikeTaskPanel(text)) {
                        host.log("[游戏V4.36] 已自动返回任务面板，按完成流程交给外层验证");
                        return Result.COMPLETED;
                    }
                    host.log("[游戏V4.36] 页面已离开水果游戏，停止继续点击");
                    return Result.SAFE_STOP_CLEAN;
                }

                if (nowRemaining >= 0) remaining = nowRemaining;
                if (nowProgress >= 0) progress = nowProgress;
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[游戏V4.36] 达到本轮安全上限，停止自动点击");
        return Result.SAFE_STOP_CLEAN;
    }

    private enum PopupDismissResult {
        NOT_PRESENT,
        DISMISSED,
        FAILED,
        ABORTED
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

    private static void saveCurrentFrameDiagnostic(
            Context context, String suPath, Host host, String suffix
    ) {
        GameFrame frame = captureFrame(context, suPath, host);
        if (frame == null) return;
        saveVisionDiagnostic(context, frame.bitmap, suffix);
        safeRecycle(frame.bitmap);
    }

    private static boolean looksLikeFruitStartScreen(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        return t.contains("开始游戏")
                || (t.contains("第1关") && t.contains("开始")
                    && !t.contains("消除") && !t.contains("打乱"));
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

        File file = new File(dir, "xianyu_fruit_v436_" + android.os.Process.myPid() + ".png");
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
        // V4.36 real-device rule: fruit clipped/packed against the top cannot reliably
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
     * V4.36.2 物理可达性：点击后水果只会向下掉入坑洞。
     * 如果它的竖直扫掠通道内存在另一颗更靠下的水果，就判定为 BLOCKED。
     * 这里使用真实连通域 bbox，而不是仅按 y 坐标“底层优先”。
     */
    private static DropAnalysis analyzeDroppability(List<FruitObject> objects) {
        List<FruitObject> droppable = new ArrayList<>();
        List<BlockingRelation> blocked = new ArrayList<>();
        if (objects == null || objects.isEmpty()) {
            return new DropAnalysis(droppable, blocked);
        }

        for (FruitObject fruit : objects) {
            FruitObject blocker = findNearestBlockingFruit(fruit, objects);
            if (blocker == null) {
                droppable.add(fruit);
            } else {
                blocked.add(new BlockingRelation(fruit, blocker));
            }
        }
        return new DropAnalysis(droppable, blocked);
    }

    /**
     * 返回 fruit 正下方最先会碰到的水果。返回 null 表示到坑洞的竖直通道畅通。
     *
     * 横向判断采用“收窄后的水果 bbox 扫掠通道 + 最小重叠量”，避免把明显相邻的
     * 两列水果误判成互相阻挡；同时保留一个中心距约束，适配圆形/椭圆形水果。
     */
    private static FruitObject findNearestBlockingFruit(
            FruitObject fruit,
            List<FruitObject> objects
    ) {
        if (fruit == null || objects == null) return null;

        FruitObject nearest = null;
        double nearestY = Double.MAX_VALUE;
        double fruitWidth = Math.max(1.0, fruit.width());
        double corridorInset = fruitWidth * 0.14;
        double corridorLeft = fruit.left + corridorInset;
        double corridorRight = fruit.right - corridorInset;

        for (FruitObject other : objects) {
            if (other == null || other == fruit) continue;

            double minVerticalSeparation = Math.max(4.0,
                    Math.min(fruit.height(), other.height()) * 0.20);
            if (other.centerY <= fruit.centerY + minVerticalSeparation) continue;

            double overlap = Math.min(corridorRight, other.right)
                    - Math.max(corridorLeft, other.left);
            double minOverlap = Math.min(fruitWidth, Math.max(1.0, other.width())) * 0.12;
            double centerDx = Math.abs(other.centerX - fruit.centerX);
            double centerCollisionLimit = ((fruitWidth + other.width()) * 0.5) * 0.72;

            if (overlap < minOverlap || centerDx > centerCollisionLimit) continue;

            if (other.centerY < nearestY) {
                nearest = other;
                nearestY = other.centerY;
            }
        }
        return nearest;
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

    private static PairChoice chooseBestPair(List<FruitObject> objects) {
        return chooseBestPairWithThreshold(objects, MIN_PAIR_SCORE, null, null);
    }

    /**
     * V4.36.2 决策引擎：调用方已经先过滤掉 BLOCKED 水果；这里不是单纯找
     * “最像的一对”，而是在所有“可直接掉入坑洞”的可靠对子中综合考虑视觉置信度、
     * 底层优先、两颗水果都处于低位和纵向跨度。
     * 视觉阈值仍然是硬门槛，决策分只负责在“已经可靠”的对子之间排序。
     */
    private static PairChoice chooseBestPairWithThreshold(
            List<FruitObject> objects,
            double minScore,
            Set<String> blacklist,
            Set<String> blockedPositions
    ) {
        if (objects == null || objects.size() < 2) return null;
        PairChoice best = null;
        float maxY = 1f;
        for (FruitObject f : objects) maxY = Math.max(maxY, f.centerY);

        for (int i = 0; i < objects.size(); i++) {
            FruitObject a = objects.get(i);
            for (int j = i + 1; j < objects.size(); j++) {
                FruitObject b = objects.get(j);

                if (blockedPositions != null && !blockedPositions.isEmpty()) {
                    if (blockedPositions.contains(positionKeyV4361(a))
                            || blockedPositions.contains(positionKeyV4361(b))) {
                        continue;
                    }
                }

                if (blacklist != null && !blacklist.isEmpty()) {
                    String key = pairKeyV435(a, b);
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

            double dx = Math.abs(candidate.centerX - expected.centerX);
            double dy = candidate.centerY - expected.centerY;
            // 点击 A 以后，其它水果应主要向下落；不允许在全屏范围凭“长得像”抢一个 B。
            if (dx > maxDx || dy < -maxUp || dy > maxDown) continue;

            Similarity sim = similarity(expected, candidate);
            if (sim.rgbMad > 0.045
                    || sim.histCos < 0.985
                    || sim.shapeIou < 0.86
                    || sim.score < 0.992) {
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
             }
