package com.zhinibgdu.xianyu;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * XianyuTaskExecutor V13
 *
 * 重点修复：
 * 1. dumpsys 前台解析不再把“未知”误判为模块 App。
 * 2. 只有明确检测到 com.zhinibgdu.xianyu 才触发用户中止。
 * 3. uiautomator 第一次失败自动重试。
 * 4. Root 命令统一检查退出码和超时。
 * 5. 任务由前台 Service 承载进程生命周期，不再依赖 Xposed/广播注入。
 */
public final class TaskExecutor {

    private static final String TAG =
            "XianyuTaskExecutor";

    private static final String TARGET_PACKAGE =
            "com.taobao.idlefish";

    private static final String MODULE_PACKAGE =
            "com.zhinibgdu.xianyu";

    private static final String TARGET_MAIN_ACTIVITY =
            TARGET_PACKAGE
                    + "/com.taobao.fleamarket.home.activity.InitActivity";

    private static final String NOTIFICATION_CHANNEL =
            "xianyu_task";

    private static final int NOTIFICATION_ID =
            18008;

    private static final String LOG_FILE_NAME =
            "xianyu_log.txt";

    private static final String UI_DUMP_PREFIX =
            "/data/local/tmp/xianyu_ui_";

    private static final long ROOT_TIMEOUT_MS =
            8000L;

    private static final int BOTTOM_GESTURE_ZONE =
            200;

    private static final int ROOT_PROBE_ATTEMPTS =
            3;

    private static final Pattern COMPONENT_PATTERN =
            Pattern.compile(
                    "(?:\\bu0\\s+)?([A-Za-z0-9_.$]+)/(?:[A-Za-z0-9_.$]+)"
            );

    private static final Pattern BOUNDS_PATTERN =
            Pattern.compile(
                    "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"
            );

    private static final String[] SKIP_TASK_KEYWORDS = {
            "发布一件新宝贝",
            "发布一件优推抵扣宝贝",
            "添加闲鱼币到桌面"
    };

    private static volatile boolean running =
            false;

    /**
     * 供 MainActivity 查询任务是否正在运行。
     */
    public static boolean isRunning() {
        return running;
    }

    private static volatile boolean userAborted =
            false;

    private static volatile boolean inBounceTask =
            false;

    private static volatile String cachedSuPath;

    private static volatile Context lastContext;

    private TaskExecutor() {
    }

    public static void run(Context appContext) {
        run(appContext, null);
    }

    public static synchronized void run(
            Context appContext,
            Runnable onComplete
    ) {

        if (running) {

            diagnostic(
                    "⚠️ 已有任务正在运行，忽略重复触发"
            );

            return;
        }

        if (appContext == null) {
            if (onComplete != null) {
                try { onComplete.run(); } catch (Throwable ignored) { }
            }
            return;
        }

        lastContext =
                appContext.getApplicationContext();

        running = true;
        userAborted = false;
        inBounceTask = false;

        diagnostic(
                "========== 闲鱼任务开始 =========="
        );

        diagnostic(
                "💡 想中途停止：切到任何其它 App 即可"
        );

        notifyTask(
                lastContext,
                "闲鱼自动任务",
                "任务已启动"
        );

        Thread worker =
                new Thread(
                        () -> {

                            try {

                                execute(
                                        lastContext
                                );

                            } catch (Throwable t) {

                                diagnostic(
                                        "任务线程异常",
                                        t
                                );

                            } finally {

                                running = false;
                                inBounceTask = false;

                                diagnostic(
                                        "========== 闲鱼任务结束 =========="
                                );

                                notifyTask(
                                        lastContext,
                                        "闲鱼自动任务",
                                        userAborted
                                                ? "任务已中止"
                                                : "任务已结束"
                                );

                                if (onComplete != null) {
                                    try {
                                        onComplete.run();
                                    } catch (Throwable callbackError) {
                                        diagnostic("完成回调异常", callbackError);
                                    }
                                }
                            }
                        },
                        "XianyuTask-V13"
                );

        worker.start();
    }

    private static void goHome(
            Context ctx
    ) {

        try {

            RootResult r =
                    rootWithPath(
                            findSuPath(),
                            "input keyevent 3"
                    );

            diagnostic(
                    "[HOME] exit="
                            + r.exitCode
            );

        } catch (Throwable t) {

            diagnostic(
                    "返回桌面失败",
                    t
            );
        }
    }

    private static void execute(
            Context ctx
    ) {

        String suPath =
                findSuPathWithRetry();

        if (suPath == null) {

            sendStatus(
                    "",
                    "FAILED",
                    "Root 不可用"
            );

            return;
        }

        RootResult id =
                rootWithPath(
                        suPath,
                        "id"
                );

        diagnostic(
                "[ROOT] id="
                        + trimForLog(
                        id.stdout,
                        300
                )
        );

        if (id.exitCode != 0
                || !id.stdout.contains("uid=0")) {

            diagnostic(
                    "❌ Root 权限验证失败"
            );

            return;
        }

        // Scheduled runs often fire while the display is off. Wake the display,
        // but deliberately do not bypass a secure keyguard.
        rootWithPath(suPath, "input keyevent KEYCODE_WAKEUP");
        SystemClock.sleep(500L);

        try {
            KeyguardManager km = (KeyguardManager)
                    ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                diagnostic("❌ 当前设备处于锁屏状态；为安全起见不尝试绕过锁屏");
                sendStatus("", "FAILED", "设备锁屏，无法执行 UI 自动化");
                return;
            }
        } catch (Throwable t) {
            diagnostic("检查锁屏状态失败，继续尝试执行", t);
        }

        String fg =
                getFg(
                        suPath,
                        false
                );

        if (!TARGET_PACKAGE.equals(fg)) {

            diagnostic(
                    "当前前台="
                            + printableFg(fg)
                            + "，启动闲鱼"
            );

            rootWithPath(
                    suPath,
                    "am start -n "
                            + TARGET_MAIN_ACTIVITY
            );

            if (!waitFg(
                    suPath,
                    45000L
            )) {

                diagnostic(
                        "❌ 闲鱼未能进入前台"
                );

                return;
            }
        }

        waitForUiAny(
                suPath,
                8000L,
                "我的", "我的闲鱼", "闲鱼币", "去完成", "领取奖励"
        );

        if (!enterViaMineCoin(
                suPath
        )) {
            return;
        }

        int completed =
                scanAndExecuteTasks(
                        suPath,
                        ctx
                );

        diagnostic(
                "本次完成任务数="
                        + completed
        );

        sendStatus(
                "",
                "FINISHED",
                "本次完成 "
                        + completed
                        + " 个任务"
        );
    }

    private static boolean enterViaMineCoin(
            String suPath
    ) {
        diagnostic("[导航] 开始寻找闲鱼币任务列表");

        if (!ensureFg(suPath)) {
            diagnostic("[导航] 闲鱼没有在前台，无法导航");
            return false;
        }

        String xml = dumpUi(suPath);
        if (xml == null) {
            diagnostic("[导航] UI 获取失败");
            return false;
        }

        // 进入“我的”。如果当前已经在闲鱼币相关页面，允许找不到“我的”继续执行。
        if (clickTextAnyAllowBottom(suPath, xml, "我的", "我的闲鱼", "个人中心")) {
            diagnostic("[导航] 已点击“我的”");
            waitForUiAny(
                    suPath,
                    5000L,
                    "闲鱼币", "闲鱼币中心", "赚闲鱼币", "领闲鱼币"
            );
        } else {
            diagnostic("[导航] 当前 UI 找不到“我的”，继续寻找闲鱼币入口");
        }

        // 进入闲鱼币中心。不同版本文案不同，所以不再只认一个“闲鱼币”。
        boolean enteredCoin = false;
        for (int attempt = 1; attempt <= 5; attempt++) {
            xml = dumpUi(suPath);
            if (xml == null) {
                SystemClock.sleep(700L);
                continue;
            }

            if (isTaskPage(xml)) {
                diagnostic("[导航] 已经处于闲鱼币任务列表");
                return true;
            }

            if (clickTextAny(suPath, xml,
                    "闲鱼币", "闲鱼币中心", "赚闲鱼币", "闲鱼币任务", "领闲鱼币")) {
                diagnostic("[导航] 点击闲鱼币入口成功，第" + attempt + "次");
                enteredCoin = true;
                waitForUiAny(
                        suPath,
                        6500L,
                        "每日任务", "任务中心", "赚币任务", "闲鱼币任务",
                        "去完成", "领取奖励"
                );
                break;
            }

            if (attempt < 5) SystemClock.sleep(800L);
        }

        if (!enteredCoin) {
            diagnostic("❌ 没有找到闲鱼币入口");
            logVisibleTexts(xml);
            return false;
        }

        // 进入闲鱼币后，不再点击“扔骰子寻宝”。
        // 原逻辑把“扔骰子寻宝”当成任务入口，这很容易进入小游戏而不是任务列表。
        // 这里优先识别真正的任务列表，再尝试“每日任务/任务中心”等入口。
        for (int pass = 1; pass <= 10; pass++) {
            if (!ensureFg(suPath)) return false;

            xml = dumpUi(suPath);
            if (xml == null) {
                SystemClock.sleep(900L);
                continue;
            }

            if (isTaskPage(xml)) {
                diagnostic("[导航] 找到闲鱼币任务列表，第" + pass + "轮");
                return true;
            }

            if (clickTextAny(suPath, xml,
                    "每日任务", "任务中心", "赚币任务", "闲鱼币任务")) {
                diagnostic("[导航] 点击任务列表入口，第" + pass + "轮");
                waitForTaskPage(suPath, 6000L);
                continue;
            }

            // 某些版本任务卡片在首屏下方，先滚动再识别。
            diagnostic("[导航] 第" + pass + "轮未找到任务入口，向上滚动");
            if (!swipeUp(suPath)) {
                SystemClock.sleep(900L);
            }
            SystemClock.sleep(1000L);
        }

        xml = dumpUi(suPath);
        if (xml != null && isTaskPage(xml)) {
            diagnostic("[导航] 最终确认已进入任务列表");
            return true;
        }

        diagnostic("❌ 仍未找到闲鱼币任务列表");
        logVisibleTexts(xml);
        return false;
    }

    private static boolean clickTextAny(String suPath, String xml, String... texts) {
        if (texts == null) return false;
        for (String text : texts) {
            if (text != null && !text.isEmpty() && clickText(suPath, xml, text, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bottom navigation labels (especially "我的") legitimately live very close to
     * the gesture area.  Treating every node in the last 200 px as unsafe made the
     * navigator find "我的" and then deliberately refuse to tap it.
     */
    private static boolean clickTextAnyAllowBottom(String suPath, String xml, String... texts) {
        if (texts == null) return false;
        for (String text : texts) {
            if (text != null && !text.isEmpty() && clickText(suPath, xml, text, true)) {
                return true;
            }
        }
        return false;
    }

    private static void logVisibleTexts(String xml) {
        try {
            if (xml == null) return;
            Document doc = parseXml(xml);
            if (doc == null) return;
            NodeList nodes = doc.getElementsByTagName("node");
            StringBuilder sb = new StringBuilder("[UI文本]");
            int count = 0;
            for (int i = 0; i < nodes.getLength() && count < 80; i++) {
                Node n = nodes.item(i);
                String text = getAttr(n, "text");
                String desc = getAttr(n, "content-desc");
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(" ").append(text.trim());
                    count++;
                } else if (desc != null && !desc.trim().isEmpty()) {
                    sb.append(" [").append(desc.trim()).append("]");
                    count++;
                }
            }
            diagnostic(trimForLog(sb.toString(), 4000));
        } catch (Throwable t) {
            diagnostic("读取当前 UI 文本失败", t);
        }
    }

    private static int scanAndExecuteTasks(
            String suPath,
            Context ctx
    ) {

        int completed = 0;
        Set<String> executed =
                new HashSet<>();

        int consecutiveFail = 0;

        for (int pass = 0; pass < 30; pass++) {

            if (userAborted) break;

            diagnostic(
                    "===== 扫描 "
                            + (pass + 1)
                            + "/30 ====="
            );

            String xml =
                    dumpUi(suPath);

            if (xml == null) {

                if (userAborted) break;

                consecutiveFail++;

                if (consecutiveFail >= 6) {
                    diagnostic(
                            "连续失败 6 次，退出扫描"
                    );
                    break;
                }

                SystemClock.sleep(1500L);
                continue;
            }

            consecutiveFail = 0;

            if (!isTaskPage(xml)) {

                diagnostic(
                        "[任务页] 当前 UI 不像任务页，重新进入"
                );

                if (!enterViaMineCoin(suPath)) {
                    SystemClock.sleep(2000L);
                }

                continue;
            }

            List<TaskCandidate> candidates =
                    findTaskCandidates(xml);

            diagnostic(
                    "候选任务="
                            + candidates.size()
            );

            if (candidates.isEmpty()) {

                if (!swipeUp(suPath)) {
                    SystemClock.sleep(2000L);
                }

                continue;
            }

            TaskCandidate target = null;

            for (TaskCandidate c : candidates) {

                if (c == null
                        || c.actionNode == null) {
                    continue;
                }

                if (executed.contains(c.key())) {
                    continue;
                }

                if (shouldSkip(c.name)) {

                    diagnostic(
                            "[跳过] "
                                    + c.name
                    );

                    executed.add(c.key());
                    continue;
                }

                target = c;
                break;
            }

            if (target == null) {

                if (!swipeUp(suPath)) {
                    SystemClock.sleep(2000L);
                }

                continue;
            }

            executed.add(
                    target.key()
            );

            diagnostic(
                    "[任务] "
                            + target.name
                            + " / claim="
                            + target.isClaimReward
            );

            if (!clickBounds(
                    suPath,
                    xml,
                    getAttr(
                            target.actionNode,
                            "bounds"
                    )
            )) {
                continue;
            }

            boolean success;

            if (target.isClaimReward) {

                SystemClock.sleep(1200L);

                success = true;

            } else {

                success =
                        executeSingleTask(
                                suPath,
                                target.name
                        );
            }

            if (success) {

                completed++;

                sendStatus(
                        target.name,
                        "SUCCESS",
                        target.isClaimReward
                                ? "领取奖励成功"
                                : "任务执行完成"
                );

            } else if (!userAborted) {

                sendStatus(
                        target.name,
                        "FAILED",
                        "任务未确认完成"
                );
            }

            if (userAborted) break;

            SystemClock.sleep(1200L);

            closePopupIfAny(
                    suPath
            );
        }

        return completed;
    }

    private static boolean shouldSkip(
            String n
    ) {

        if (n == null) return false;

        for (String kw :
                SKIP_TASK_KEYWORDS) {

            if (n.contains(kw)) {
                return true;
            }
        }

        return false;
    }

    private static void closePopupIfAny(
            String suPath
    ) {

        String xml =
                dumpUi(suPath);

        if (xml == null) return;

        for (String t :
                new String[]{
                        "开心收下",
                        "收下",
                        "我知道了",
                        "知道啦",
                        "关闭"
                }) {

            if (clickText(
                    suPath,
                    xml,
                    t
            )) {

                SystemClock.sleep(800L);
                return;
            }
        }
    }

    private static List<TaskCandidate>
    findTaskCandidates(
            String xml
    ) {

        List<TaskCandidate> result =
                new ArrayList<>();

        try {

            Document doc =
                    parseXml(xml);

            if (doc == null) return result;

            NodeList nodes =
                    doc.getElementsByTagName(
                            "node"
                    );

            for (int i = 0;
                 i < nodes.getLength();
                 i++) {

                Node n = nodes.item(i);

                String text =
                        getAttr(
                                n,
                                "text"
                        );

                if ("去完成".equals(text)
                        || "领取奖励".equals(text)) {

                    Node nearest =
                            findNearestTaskName(
                                    nodes,
                                    i
                            );

                    String name =
                            nearest == null
                                    ? "未知任务"
                                    : normalizeTaskName(
                                    getAttr(
                                            nearest,
                                            "text"
                                    )
                            );

                    result.add(
                            new TaskCandidate(
                                    name,
                                    n,
                                    "领取奖励".equals(text)
                            )
                    );
                }
            }

        } catch (Throwable t) {

            diagnostic(
                    "findTaskCandidates 异常",
                    t
            );
        }

        return result;
    }

    private static Node findNearestTaskName(
            NodeList nodes,
            int actionIndex
    ) {
        Node action = nodes.item(actionIndex);
        int[] actionBounds = parseBounds(getAttr(action, "bounds"));
        if (actionBounds == null) return null;

        int actionCx = (actionBounds[0] + actionBounds[2]) / 2;
        int actionCy = (actionBounds[1] + actionBounds[3]) / 2;

        Node best = null;
        double bestScore = Double.MAX_VALUE;

        for (int i = 0; i < nodes.getLength(); i++) {
            if (i == actionIndex) continue;

            Node node = nodes.item(i);
            String text = getAttr(node, "text");
            if (text == null) continue;
            text = text.trim();

            if (text.isEmpty()
                    || "去完成".equals(text)
                    || "领取奖励".equals(text)
                    || "已完成".equals(text)) {
                continue;
            }

            int[] bounds = parseBounds(getAttr(node, "bounds"));
            if (bounds == null) continue;

            int cx = (bounds[0] + bounds[2]) / 2;
            int cy = (bounds[1] + bounds[3]) / 2;
            int dy = Math.abs(cy - actionCy);
            int dx = Math.abs(cx - actionCx);

            // Task title and action button should belong to approximately the same row.
            if (dy > 320) continue;

            int overlap = Math.max(0,
                    Math.min(bounds[3], actionBounds[3])
                            - Math.max(bounds[1], actionBounds[1]));
            int minHeight = Math.max(1, Math.min(
                    bounds[3] - bounds[1],
                    actionBounds[3] - actionBounds[1]));
            double overlapRatio = Math.min(1.0, (double) overlap / minHeight);

            // Lower score is better. Vertical alignment matters much more than raw
            // Euclidean distance because task titles are normally left of buttons.
            double score = dy * 4.0 + dx * 0.12;
            score += (1.0 - overlapRatio) * 260.0;

            // Text to the right of the action button is unlikely to be the task title.
            if (cx > actionCx + 80) score += 700.0;

            score += hierarchyPenalty(node, action);

            // Suppress common short counters/labels without hard-rejecting them.
            if (text.length() <= 2) score += 120.0;
            if (text.matches("[0-9+\\-.,% ]+")) score += 220.0;

            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }

        if (best != null) {
            diagnostic("[任务匹配] action=" + getAttr(action, "text")
                    + " -> title=" + getAttr(best, "text")
                    + " score=" + String.format(Locale.US, "%.1f", bestScore));
        }
        return best;
    }

    private static double hierarchyPenalty(Node candidate, Node action) {
        try {
            Node cp = candidate.getParentNode();
            Node ap = action.getParentNode();
            if (cp != null && cp == ap) return -320.0;

            Node cgp = cp == null ? null : cp.getParentNode();
            Node agp = ap == null ? null : ap.getParentNode();
            if (cgp != null && cgp == agp) return -180.0;

            if (cp != null && agp != null && cp == agp) return -100.0;
            if (ap != null && cgp != null && ap == cgp) return -100.0;
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    private static boolean executeSingleTask(
            String suPath,
            String taskName
    ) {

        diagnostic(
                "[执行] "
                        + taskName
        );

        boolean isVideo =
                containsAny(
                        taskName,
                        "视频",
                        "观看",
                        "看15秒"
                );

        boolean isSearch =
                containsAny(
                        taskName,
                        "搜一搜",
                        "搜索",
                        "搜商品"
                );

        boolean isBounce =
                isBounceTask(taskName);

        if (isVideo) {

            return executeVideoTaskPolling(
                    suPath,
                    taskName
            );
        }

        long waitMs =
                isSearch
                        ? 8000L
                        : isBounce
                        ? 18000L
                        : 12000L;

        if (isBounce) {
            inBounceTask = true;
        }

        try {

            long slept = 0L;

            while (slept < waitMs) {

                if (userAborted) return false;

                SystemClock.sleep(1000L);
                slept += 1000L;
            }

            if (userAborted) return false;

            String fg =
                    getFg(
                            suPath,
                            false
                    );

            diagnostic(
                    "[执行] 前台="
                            + printableFg(fg)
            );

            if (!TARGET_PACKAGE.equals(fg)) {

                rootWithPath(
                        suPath,
                        "am start -n "
                                + TARGET_MAIN_ACTIVITY
                );

                SystemClock.sleep(2500L);
            }

            if (isSearch) {

                rootWithPath(
                        suPath,
                        "input keyevent 4"
                );

                SystemClock.sleep(1200L);
            }

            String xml =
                    dumpUi(suPath);

            if (xml == null) {
                return true;
            }

            for (String t :
                    new String[]{
                            "开心收下",
                            "领取奖励",
                            "立即领取",
                            "领取",
                            "收下",
                            "我知道了"
                    }) {

                if (clickText(
                        suPath,
                        xml,
                        t
                )) {

                    SystemClock.sleep(1200L);
                    return true;
                }
            }

            return xml.contains("已完成")
                    || xml.contains("任务完成")
                    || xml.contains("完成任务")
                    || xml.contains("已领取");

        } finally {

            if (isBounce) {
                inBounceTask = false;
            }
        }
    }

    private static boolean executeVideoTaskPolling(
            String suPath,
            String taskName
    ) {

        diagnostic(
                "[视频] 开始轮询（最多 60 秒）"
        );

        long start =
                SystemClock.elapsedRealtime();

        boolean settled = false;

        while (
                SystemClock.elapsedRealtime()
                        - start < 60000L
        ) {

            if (userAborted) return false;

            SystemClock.sleep(3000L);

            String fg =
                    getFg(
                            suPath,
                            false
                    );

            if (!TARGET_PACKAGE.equals(fg)) {

                if (SystemClock.elapsedRealtime()
                        - start > 30000L) {

                    rootWithPath(
                            suPath,
                            "am start -n "
                                    + TARGET_MAIN_ACTIVITY
                    );

                    SystemClock.sleep(2500L);
                }

                continue;
            }

            String xml =
                    dumpUi(suPath);

            if (xml == null) continue;

            for (String t :
                    new String[]{
                            "开心收下",
                            "领取奖励",
                            "立即领取",
                            "收下"
                    }) {

                if (clickText(
                        suPath,
                        xml,
                        t
                )) {

                    SystemClock.sleep(1500L);
                    return true;
                }
            }

            for (String t :
                    new String[]{
                            "我知道了",
                            "知道了",
                            "确定"
                    }) {

                if (clickText(
                        suPath,
                        xml,
                        t
                )) {

                    SystemClock.sleep(1000L);
                    break;
                }
            }

            if (isTaskPage(xml)) {

                if (!settled) {

                    settled = true;
                    SystemClock.sleep(3000L);
                    continue;
                }

                return true;
            }
        }

        diagnostic(
                "[视频] 超时 60 秒，未确认完成"
        );

        return false;
    }

    private static boolean isBounceTask(
            String name
    ) {

        if (name == null) return false;

        return containsAny(
                name,
                "支付宝",
                "农场",
                "头条",
                "点点消",
                "消不停",
                "百亿补贴",
                "玩游戏",
                "淘宝",
                "飞猪",
                "高德",
                "饿了么",
                "点淘",
                "试玩",
                "淘特",
                "百度",
                "大众点评",
                "美团",
                "快手",
                "一淘",
                "逛逛",
                "闪购",
                "领积分",
                "刷视频",
                "赚零花"
        );
    }

    private static boolean swipeUp(
            String suPath
    ) {

        if (!ensureFg(suPath)) {
            return false;
        }

        RootResult r =
                rootWithPath(
                        suPath,
                        "input swipe 540 2200 540 800 600"
                );

        return r.exitCode == 0;
    }

    private static String getFg(
            String suPath,
            boolean allowCache
    ) {

        String result = "";

        String[] commands = {
                "dumpsys window displays 2>/dev/null",
                "dumpsys activity activities 2>/dev/null"
        };

        for (String command :
                commands) {

            RootResult r =
                    rootWithPath(
                            suPath,
                            command
                    );

            String raw =
                    (
                            r.stdout
                                    + "\n"
                                    + r.stderr
                    ).trim();

            if (raw.isEmpty()) continue;

            String parsed =
                    parseForegroundFromDumpsys(
                            raw
                    );

            if (!parsed.isEmpty()) {

                result = parsed;

                if (TARGET_PACKAGE.equals(parsed)
                        || MODULE_PACKAGE.equals(parsed)) {
                    break;
                }
            }
        }

        diagnostic(
                "[前台检测] 最终结果="
                        + printableFg(result)
        );

        return result;
    }

    private static String parseForegroundFromDumpsys(
            String raw
    ) {

        if (raw == null
                || raw.isEmpty()) {
            return "";
        }

        String[] lines =
                raw.split("\\r?\\n");

        String[] keys = {
                "mCurrentFocus=",
                "mFocusedApp=",
                "mResumedActivity=",
                "topResumedActivity="
        };

        for (String line : lines) {

            if (!containsAny(line, keys)) {
                continue;
            }

            if (line.contains(TARGET_PACKAGE)) {
                return TARGET_PACKAGE;
            }

            if (line.contains(MODULE_PACKAGE)) {
                return MODULE_PACKAGE;
            }
        }

        for (String line : lines) {

            if (!containsAny(line, keys)) {
                continue;
            }

            Matcher matcher =
                    COMPONENT_PATTERN.matcher(
                            line
                    );

            if (matcher.find()) {

                String pkg =
                        matcher.group(1);

                if (pkg != null
                        && !pkg.isEmpty()) {
                    return pkg;
                }
            }
        }

        return "";
    }

    /**
     * Polls UI state instead of blindly sleeping for a fixed page-load delay.
     * Returns as soon as the task page or one of the expected tokens appears.
     */
    private static boolean waitForUiAny(
            String suPath,
            long timeoutMs,
            String... tokens
    ) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (userAborted) return false;
            String xml = dumpUi(suPath);
            if (xml != null) {
                if (isTaskPage(xml)) return true;
                if (tokens != null && containsAny(xml, tokens)) return true;
            }
            SystemClock.sleep(350L);
        }
        return false;
    }

    private static boolean waitForTaskPage(
            String suPath,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (userAborted) return false;
            String xml = dumpUi(suPath);
            if (xml != null && isTaskPage(xml)) return true;
            SystemClock.sleep(350L);
        }
        return false;
    }

    private static boolean waitFg(
            String suPath,
            long timeout
    ) {

        long start =
                SystemClock.elapsedRealtime();

        while (
                SystemClock.elapsedRealtime()
                        - start < timeout
        ) {

            if (userAborted) return false;

            String fg =
                    getFg(
                            suPath,
                            false
                    );

            if (TARGET_PACKAGE.equals(fg)) {
                return true;
            }

            SystemClock.sleep(1000L);
        }

        return false;
    }

    private static boolean ensureFg(
            String suPath
    ) {

        String fg =
                getFg(
                        suPath,
                        false
                );

        if (TARGET_PACKAGE.equals(fg)) {
            return true;
        }

        if (MODULE_PACKAGE.equals(fg)) {

            userAborted = true;

            diagnostic(
                    "🛑 明确检测到用户切回模块 App，任务中止"
            );

            return false;
        }

        if (inBounceTask) {

            rootWithPath(
                    suPath,
                    "am start -n "
                            + TARGET_MAIN_ACTIVITY
            );

            long deadline =
                    SystemClock.elapsedRealtime()
                            + 6000L;

            while (
                    SystemClock.elapsedRealtime()
                            < deadline
            ) {

                if (userAborted) return false;

                SystemClock.sleep(600L);

                String retry =
                        getFg(
                                suPath,
                                false
                        );

                if (TARGET_PACKAGE.equals(
                        retry
                )) {
                    return true;
                }

                if (MODULE_PACKAGE.equals(
                        retry
                )) {

                    userAborted = true;
                    return false;
                }
            }

            return false;
        }

        /*
         * V10.8 核心修复：
         * “未知”绝不能等同于模块 App。
         */
        if (fg == null
                || fg.isEmpty()) {

            diagnostic(
                    "⚠️ 前台暂时无法解析，不判定为用户中止"
            );

            for (int i = 1; i <= 2; i++) {

                SystemClock.sleep(400L);

                String retry =
                        getFg(
                                suPath,
                                false
                        );

                if (TARGET_PACKAGE.equals(retry)) {
                    return true;
                }

                if (MODULE_PACKAGE.equals(retry)) {

                    userAborted = true;

                    diagnostic(
                            "🛑 重试确认用户切回模块 App"
                    );

                    return false;
                }
            }

            return true;
        }

        userAborted = true;

        diagnostic(
                "🛑 检测到其它 App 前台："
                        + fg
        );

        return false;
    }

    private static String dumpUi(
            String suPath
    ) {

        if (!ensureFg(suPath)) {
            return null;
        }

        for (int attempt = 1;
             attempt <= 3;
             attempt++) {

            if (userAborted) return null;

            String xml =
                    dumpUiOnce(suPath);

            if (xml != null
                    && !xml.isEmpty()) {

                diagnostic(
                        "uiautomator(第"
                                + attempt
                                + "次) exit=0"
                );

                return xml;
            }

            SystemClock.sleep(800L);
        }

        diagnostic(
                "❌ UIAutomator 连续 3 次失败"
        );

        return null;
    }

    private static String dumpUiOnce(
            String suPath
    ) {

        String file =
                UI_DUMP_PREFIX
                        + android.os.Process.myPid()
                        + "_"
                        + System.currentTimeMillis()
                        + ".xml";

        String command =
                "mkdir -p /data/local/tmp 2>/dev/null; "
                        + "rm -f "
                        + file
                        + " 2>/dev/null; "
                        + "uiautomator dump "
                        + file
                        + " >/dev/null 2>&1; "
                        + "if [ -s "
                        + file
                        + " ]; then cat "
                        + file
                        + "; fi; "
                        + "rm -f "
                        + file
                        + " 2>/dev/null";

        RootResult r =
                rootWithPath(
                        suPath,
                        command
                );

        if (r.exitCode != 0) {
            return null;
        }

        String xml =
                r.stdout;

        if (xml == null
                || xml.trim().isEmpty()) {
            return null;
        }

        int start =
                xml.indexOf("<?xml");

        if (start >= 0) {

            xml =
                    xml.substring(start);

        } else {

            start =
                    xml.indexOf("<hierarchy");

            if (start >= 0) {
                xml =
                        xml.substring(start);
            }
        }

        if (!xml.contains("<hierarchy")) {
            return null;
        }

        return xml.trim();
    }

    private static boolean isTaskPage(
            String xml
    ) {

        if (xml == null
                || xml.isEmpty()) {
            return false;
        }

        int score = 0;

        if (xml.contains("去完成")) score++;
        if (xml.contains("领取奖励")) score++;
        if (xml.contains("闲鱼币")) score++;
        if (xml.contains("任务")) score++;
        if (xml.contains("宝箱")) score++;

        return score >= 2;
    }

    private static boolean clickText(
            String suPath,
            String xml,
            String text,
            boolean allowBottomGestureZone
    ) {

        if (text == null
                || text.isEmpty()
                || xml == null) {
            return false;
        }

        try {

            Document doc =
                    parseXml(xml);

            if (doc == null) return false;

            NodeList nodes =
                    doc.getElementsByTagName(
                            "node"
                    );

            for (int i = 0;
                 i < nodes.getLength();
                 i++) {

                Node node =
                        nodes.item(i);

                String nodeText =
                        getAttr(
                                node,
                                "text"
                        );

                String desc =
                        getAttr(
                                node,
                                "content-desc"
                        );

                if (!text.equals(nodeText)
                        && !text.equals(desc)) {
                    continue;
                }

                String bounds =
                        getAttr(
                                node,
                                "bounds"
                        );

                if (bounds == null
                        || bounds.isEmpty()) {
                    continue;
                }

                return clickBounds(
                        suPath,
                        xml,
                        bounds,
                        allowBottomGestureZone
                );
            }

        } catch (Throwable t) {

            diagnostic(
                    "clickText 异常",
                    t
            );
        }

        return false;
    }

    private static boolean clickBounds(
            String suPath,
            String xml,
            String bounds
    ) {
        return clickBounds(suPath, xml, bounds, false);
    }

    private static boolean clickBounds(
            String suPath,
            String xml,
            String bounds,
            boolean allowBottomGestureZone
    ) {

        int[] center =
                parseCenter(bounds);

        if (center == null) {
            return false;
        }

        int x = center[0];
        int y = center[1];

        int height =
                getScreenHeight(suPath);

        if (height > 0
                && y > height
                - BOTTOM_GESTURE_ZONE) {

            if (!allowBottomGestureZone) {
                diagnostic(
                        "[点击] 位于底部手势区域，取消"
                );
                return false;
            }

            diagnostic(
                    "[点击] 底部导航项，允许点击：y="
                            + y
                            + "/"
                            + height
            );
        }

        if (x < 1
                || y < 1
                || x > 2000
                || y > 4000) {
            return false;
        }

        if (!ensureFg(suPath)) {
            return false;
        }

        RootResult r =
                rootWithPath(
                        suPath,
                        "input tap "
                                + x
                                + " "
                                + y
                );

        if (r.exitCode != 0) {
            return false;
        }

        SystemClock.sleep(700L);

        return true;
    }

    private static int getScreenHeight(
            String suPath
    ) {

        RootResult r =
                rootWithPath(
                        suPath,
                        "wm size 2>/dev/null"
                );

        Matcher m =
                Pattern.compile(
                        "(\\d+)x(\\d+)"
                ).matcher(
                        r.stdout == null
                                ? ""
                                : r.stdout
                );

        if (!m.find()) return 0;

        try {

            int width =
                    Integer.parseInt(
                            m.group(1)
                    );

            int height =
                    Integer.parseInt(
                            m.group(2)
                    );

            return Math.max(
                    width,
                    height
            );

        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Document parseXml(
            String xml
    ) {

        if (xml == null
                || xml.trim().isEmpty()) {
            return null;
        }

        try {

            DocumentBuilderFactory factory =
                    DocumentBuilderFactory
                            .newInstance();

            factory.setNamespaceAware(false);

            try {
                factory.setFeature(
                        "http://xml.org/sax/features/external-general-entities",
                        false
                );
            } catch (Throwable ignored) {
            }

            try {
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities",
                        false
                );
            } catch (Throwable ignored) {
            }

            try {
                factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true
                );
            } catch (Throwable ignored) {
            }

            DocumentBuilder builder =
                    factory.newDocumentBuilder();

            return builder.parse(
                    new ByteArrayInputStream(
                            xml.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );

        } catch (Throwable t) {

            diagnostic(
                    "parseXml 异常",
                    t
            );

            return null;
        }
    }

    private static String getAttr(
            Node node,
            String name
    ) {

        if (node == null
                || node.getAttributes() == null) {
            return "";
        }

        Node attr =
                node.getAttributes()
                        .getNamedItem(name);

        return attr == null
                ? ""
                : attr.getNodeValue();
    }

    private static int[] parseBounds(String bounds) {
        if (bounds == null || bounds.isEmpty()) return null;
        Matcher matcher = BOUNDS_PATTERN.matcher(bounds);
        if (!matcher.find()) return null;
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4))
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int[] parseCenter(
            String bounds
    ) {

        if (bounds == null
                || bounds.isEmpty()) {
            return null;
        }

        Matcher m =
                BOUNDS_PATTERN.matcher(
                        bounds
                );

        if (!m.find()) return null;

        try {

            int left =
                    Integer.parseInt(
                            m.group(1)
                    );

            int top =
                    Integer.parseInt(
                            m.group(2)
                    );

            int right =
                    Integer.parseInt(
                            m.group(3)
                    );

            int bottom =
                    Integer.parseInt(
                            m.group(4)
                    );

            return new int[]{
                    (left + right) / 2,
                    (top + bottom) / 2
            };

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean containsAny(
            String value,
            String... words
    ) {

        if (value == null
                || words == null) {
            return false;
        }

        for (String word : words) {

            if (word != null
                    && value.contains(word)) {
                return true;
            }
        }

        return false;
    }

    private static String findSuPathWithRetry() {
        for (int attempt = 1; attempt <= ROOT_PROBE_ATTEMPTS; attempt++) {
            String path = findSuPath();
            if (path != null) {
                return path;
            }

            if (attempt < ROOT_PROBE_ATTEMPTS) {
                diagnostic(
                        "⚠️ Root 暂不可用，"
                                + (attempt + 1)
                                + "/"
                                + ROOT_PROBE_ATTEMPTS
                                + " 次检测即将重试"
                );
                SystemClock.sleep(650L);
            }
        }
        return null;
    }

    private static String findSuPath() {

        if (cachedSuPath != null
                && !cachedSuPath.isEmpty()) {
            return cachedSuPath;
        }

        String[] paths = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/magisk/su",
                "su"
        };

        for (String path : paths) {

            try {

                RootResult r =
                        rootRaw(
                                path,
                                "id"
                        );

                if (r.exitCode == 0
                        && r.stdout.contains("uid=0")) {

                    cachedSuPath = path;

                    diagnostic(
                            "✅ Root 可用："
                                    + path
                    );

                    return path;
                }

            } catch (Throwable t) {

                diagnostic(
                        "检测 su 失败："
                                + path
                );
            }
        }

        return null;
    }

    private static RootResult rootWithPath(
            String suPath,
            String command
    ) {

        if (suPath == null
                || suPath.isEmpty()) {

            return new RootResult(
                    -1,
                    "",
                    "su path empty"
            );
        }

        diagnostic(
                "[ROOT] su -c "
                        + command
        );

        return rootRaw(
                suPath,
                command
        );
    }

    private static RootResult rootRaw(
            String suPath,
            String command
    ) {

        Process process = null;

        StringBuilder stdout =
                new StringBuilder();

        StringBuilder stderr =
                new StringBuilder();

        try {

            process =
                    Runtime.getRuntime()
                            .exec(
                                    new String[]{
                                            suPath,
                                            "-c",
                                            command
                                    }
                            );

            Thread outThread =
                    new Thread(
                            new StreamReader(
                                    process.getInputStream(),
                                    stdout
                            )
                    );

            Thread errThread =
                    new Thread(
                            new StreamReader(
                                    process.getErrorStream(),
                                    stderr
                            )
                    );

            outThread.start();
            errThread.start();

            boolean finished =
                    process.waitFor(
                            ROOT_TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                    );

            if (!finished) {

                process.destroy();

                try {
                    process.destroyForcibly();
                } catch (Throwable ignored) {
                }

                return new RootResult(
                        -2,
                        stdout.toString(),
                        "timeout"
                );
            }

            outThread.join(500L);
            errThread.join(500L);

            return new RootResult(
                    process.exitValue(),
                    stdout.toString(),
                    stderr.toString()
            );

        } catch (Throwable t) {

            return new RootResult(
                    -1,
                    stdout.toString(),
                    t.toString()
            );

        } finally {

            if (process != null) {

                try {
                    process.getInputStream().close();
                } catch (Throwable ignored) {
                }

                try {
                    process.getErrorStream().close();
                } catch (Throwable ignored) {
                }

                try {
                    process.getOutputStream().close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class StreamReader
            implements Runnable {

        private final InputStream input;
        private final StringBuilder output;

        StreamReader(
                InputStream input,
                StringBuilder output
        ) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {

            try {

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        input,
                                        StandardCharsets.UTF_8
                                )
                        );

                String line;

                while (
                        (line = reader.readLine())
                                != null
                ) {

                    synchronized (output) {

                        if (output.length()
                                < 1024 * 1024) {

                            output.append(
                                    line
                            ).append('\n');
                        }
                    }
                }

            } catch (Throwable ignored) {
            }
        }
    }

    private static void diagnostic(
            String message
    ) {
        if (message == null) message = "";
        Log.i(TAG, message);
        TaskStatusReceiver.writeLog(lastContext, "INFO", "系统日志", message);
    }

    private static void diagnostic(
            String message,
            Throwable throwable
    ) {
        diagnostic(
                message
                        + " : "
                        + (throwable == null ? "" : throwable.toString())
        );
    }

    private static void sendStatus(
            String taskName,
            String status,
            String detail
    ) {
        TaskStatusReceiver.writeLog(
                lastContext,
                safe(status),
                safe(taskName),
                safe(detail)
        );
    }

    private static void notifyTask(
            Context ctx,
            String title,
            String text
    ) {

        if (ctx == null) return;

        try {

            NotificationManager manager =
                    (NotificationManager)
                            ctx.getSystemService(
                                    Context.NOTIFICATION_SERVICE
                            );

            if (manager == null) return;

            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.O) {

                NotificationChannel channel =
                        new NotificationChannel(
                                NOTIFICATION_CHANNEL,
                                "闲鱼自动任务",
                                NotificationManager
                                        .IMPORTANCE_LOW
                        );

                manager.createNotificationChannel(
                        channel
                );
            }

            Intent launch =
                    ctx.getPackageManager()
                            .getLaunchIntentForPackage(
                                    MODULE_PACKAGE
                            );

            PendingIntent pendingIntent =
                    null;

            if (launch != null) {

                int flags =
                        PendingIntent.FLAG_UPDATE_CURRENT;

                if (Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.M) {

                    flags |=
                            PendingIntent.FLAG_IMMUTABLE;
                }

                pendingIntent =
                        PendingIntent.getActivity(
                                ctx,
                                1001,
                                launch,
                                flags
                        );
            }

            android.app.Notification.Builder builder;

            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.O) {

                builder =
                        new android.app.Notification.Builder(
                                ctx,
                                NOTIFICATION_CHANNEL
                        );

            } else {

                builder =
                        new android.app.Notification.Builder(
                                ctx
                        );
            }

            builder
                    .setSmallIcon(
                            android.R.drawable.ic_popup_sync
                    )
                    .setContentTitle(
                            safe(title)
                    )
                    .setContentText(
                            safe(text)
                    )
                    .setAutoCancel(true);

            if (pendingIntent != null) {
                builder.setContentIntent(
                        pendingIntent
                );
            }

            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.notify(
                    NOTIFICATION_ID,
                    builder.build()
            );

        } catch (SecurityException ignored) {

            diagnostic(
                    "通知权限不足，跳过通知"
            );

        } catch (Throwable t) {

            diagnostic(
                    "通知异常",
                    t
            );
        }
    }

    private static String safe(
            String value
    ) {

        return value == null
                ? ""
                : value;
    }

    private static String normalizeTaskName(
            String name
    ) {

        if (name == null) return "";

        return name
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private static String trimForLog(
            String value,
            int max
    ) {

        if (value == null) return "";

        if (value.length() <= max) {
            return value;
        }

        return value.substring(
                0,
                max
        ) + "...";
    }

    private static String printableFg(
            String fg
    ) {

        return fg == null
                || fg.isEmpty()
                ? "<未知>"
                : fg;
    }

    private static String shortCommand(
            String command
    ) {

        if (command == null) return "";

        return command.length() <= 45
                ? command
                : command.substring(
                        0,
                        45
                ) + "...";
    }

    private static final class TaskCandidate {

        final String name;
        final Node actionNode;
        final boolean isClaimReward;

        TaskCandidate(
                String name,
                Node actionNode,
                boolean isClaimReward
        ) {

            this.name =
                    name == null
                            ? "未知任务"
                            : name;

            this.actionNode =
                    actionNode;

            this.isClaimReward =
                    isClaimReward;
        }

        String key() {

            return name
                    + "|"
                    + getAttr(
                    actionNode,
                    "bounds"
            )
                    + "|"
                    + isClaimReward;
        }
    }

    private static final class RootResult {

        final int exitCode;
        final String stdout;
        final String stderr;

        RootResult(
                int exitCode,
                String stdout,
                String stderr
        ) {

            this.exitCode =
                    exitCode;

            this.stdout =
                    stdout == null
                            ? ""
                            : stdout;

            this.stderr =
                    stderr == null
                            ? ""
                            : stderr;
        }
    }
}
