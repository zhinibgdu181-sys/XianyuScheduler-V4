package com.zhinibgdu.xianyu;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * XianyuTaskExecutor V4.31
 *
 * 重点修复：
 * 1. dumpsys 前台解析不再把“未知”误判为模块 App。
 * 2. 只有明确检测到 com.zhinibgdu.xianyu 才触发用户中止。
 * 3. uiautomator 第一次失败自动重试。
 * 4. Root 命令统一检查退出码和超时。
 * 5. 任务由前台 Service 承载进程生命周期，不再依赖 Xposed/广播注入。
 * 6. 特征库按“任务 + 事件类型 + 原因/恢复方式”去重；同一案例只保留一条记录。
 * 7. V4.9 将历史经验接入执行决策：等待时间、返回策略、失败退避和策略日志。
 * 8. V4.10 返回策略：右侧最边缘 75% 高度优先；每次返回后识别页面；必要时第二次右滑，失败再用左侧兜底。
 * 9. V4.11：真实任务完成验证、页面特征库、任务状态机、近期经验加权、人工接管防自触发、
 *    OCR缓存分层、失败冷却、失败诊断截图、特征库版本/过期清理。
 * 10. V4.12：真人示范学习模式。学习模式只观察、不主动点击/滑动/启动目标 App；
 *     记录真人点击/滑动/边缘返回、动作前后页面、等待时间和页面转换，并对相同案例去重累计。
 * 11. V4.12：自动模式人工接管后进入硬停止态，所有后续 UI 变更 Root 命令都会被统一拦截。
 * 12. V4.13：修复真人示范触摸生命周期和持续时间采集；按 getevent 事件时间计算 DOWN→UP，
 *     新触摸不再继承上一手势坐标；增加语义去重、页面/外部 App 归一化和无关系统动作过滤。
 * 13. V4.15：自适应节奏。减少重复 OCR/固定等待；验证页复用最近任务面板快照。
 * 14. V4.16：任务面板立即连贯执行。返回/识别到 TASK_PANEL 后复用该帧直接挑选
 *     下一个“签到/领取奖励/去完成”，不再为了上一任务的进度变化额外停留。
 * 15. V4.17：修复“赚骰子/1分兑换”OCR粘连误点；导航OCR优先；UIAutomator快速超时；人工接管即时中断。
 * 16. V4.18：加入小游戏页面分类；“消了还想消”启用水果视觉配对求解器；麻将对子页先识别并纳入学习库。
 * 17. V4.19：“点点消不停”启用麻将视觉求解器：识别6x6棋盘/同牌，优先处理相邻对子，
 *     再滑动同牌靠近；每一步截图验证，失败动作进入本轮黑名单，不盲滑。
 * 18. V4.20：游戏分流不再依赖完整任务名；点击后以实际页面类型为准，OCR把“消”识成“渭”也能
 *     进入水果求解器。游戏求解期间启用独占锁，普通恢复和真人学习回放不得插手。
 * 19. V4.20：真人学习库降权为“受控建议库”：只允许同一外部App、重复>=3次、目标TASK_PANEL的
 *     左/右边缘返回案例自动复用；TAP/普通滑动/跨App经验永不自动回放。
 * 20. V4.20：首页→我的→闲鱼币→赚骰子改为OCR单帧接力，同一张OCR既确认页面又点击下一入口，
 *     删除重复确认OCR；扩展“赚骰子”OCR错字容错。
 * 21. V4.21：修复水果游戏守卫与快速导航。
 * 22. V4.22：游戏交互对象白名单；水果只点水果，麻将只操作麻将牌，功能按钮一律禁止自动点击。
 * 23. V4.31：水果残局动态阈值降级；OCR 错字容错扩展（渭/悄/肖→消）；学习日志自动截断。
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

    // V4.17: UIAutomator is fallback only. Never allow one dump to stall navigation
    // for the full generic root timeout.
    private static final long UI_DUMP_TIMEOUT_MS_V417 = 2800L;
    private static final int UI_DUMP_ATTEMPTS_V417 = 1;

    private static final int ROOT_PROBE_ATTEMPTS =
            2;

    private static final Pattern COMPONENT_PATTERN =
            Pattern.compile(
                    "(?:\\bu0\\s+)?([A-Za-z0-9_.$]+)/(?:[A-Za-z0-9_.$]+)"
            );

    private static final Pattern BOUNDS_PATTERN =
            Pattern.compile(
                    "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"
            );

    private static final String[] SKIP_TASK_KEYWORDS = {
            // 有明显副作用或会进入安装流程的任务默认不自动执行。
            "发布",
            "添加闲鱼币到桌面",
            "下载",
            "安装",
            "未知任务"
    };

    private static final String[] INTERNAL_BROWSE_KEYWORDS = {
            "浏览",
            "逛一逛",
            "指定频道",
            "好物",
            "福利",
            "商城",
            "会场"
    };

    private static final String[] REPEATABLE_TASK_KEYWORDS = {
            "视频",
            "指定频道",
            "浏览"
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

    // V4.8: physical touchscreen monitor. A real finger touch on the hardware
    // touchscreen is treated as an immediate manual takeover request.
    private static volatile boolean physicalTouchDetected = false;
    private static volatile long physicalTouchAt = 0L;
    private static volatile String physicalTouchDevice = "";
    private static volatile Process touchMonitorProcess;
    private static volatile Thread touchMonitorThread;

    // V4.13 learning mode. It is observation-only: no automated UI mutation is
    // allowed while this flag is true.
    private static volatile boolean learningModeV412 = false;
    private static volatile boolean learningStopRequestedV412 = false;
    private static volatile Process learningInputProcessV412;
    private static volatile Thread learningSamplerThreadV412;
    private static volatile PageProbeV411 learningLastPageV412;
    private static volatile long learningLastPageAtV412 = 0L;
    private static volatile boolean learningHasLeftHelperV412 = false;
    private static volatile long learningLastActionEndV412 = 0L;
    private static final Object learningPageHistoryLockV413 = new Object();
    private static final List<TimedLearningPageV413> learningPageHistoryV413 = new ArrayList<>();
    private static final String LEARNING_PREFS_V412 = "xianyu_learning_v413";
    private static final String LEARNING_LOG_FILE_V412 = "xianyu_learning_v413_log.txt";
    private static final long LEARNING_PAGE_SAMPLE_MS_V412 = 900L;
    private static final long LEARNING_POST_ACTION_DELAY_MS_V412 = 650L;

    // V4.11 manual-takeover hardening: shell-generated tap/swipe events are
    // ignored only inside a very short correlation window so the monitor does
    // not stop itself on devices that echo synthetic input to getevent.
    private static volatile long syntheticInputIgnoreUntilV411 = 0L;
    private static volatile long lastSyntheticInputAtV411 = 0L;

    // V4.11 OCR cache. The cache is deliberately short lived and is invalidated
    // after any UI-affecting root command.
    private static volatile ScreenOcr.Snapshot cachedOcrV411 = ScreenOcr.Snapshot.empty();
    private static volatile long cachedOcrAtV411 = 0L;
    private static final long OCR_CACHE_MS_V411 = 350L;

    // V4.15: a verified TASK_PANEL probe is much more valuable than a generic
    // OCR cache entry. Keep it briefly so completion verification can reuse the
    // frame that was already captured by conditional-return/recovery logic.
    private static volatile ScreenOcr.Snapshot lastTaskPanelOcrV415 = ScreenOcr.Snapshot.empty();
    private static volatile long lastTaskPanelOcrAtV415 = 0L;
    private static final long TASK_PANEL_OCR_REUSE_MS_V415 = 2200L;
    // V4.16: once a return/page probe has already confirmed TASK_PANEL, the very
    // same OCR frame may be consumed by the next scan. This removes the extra
    // screenshot round-trip between one completed task and the next click.
    private static final long TASK_PANEL_CHAIN_REUSE_MS_V416 = 2600L;

    // V4.20/V4.21 game-page ownership. While a visual solver owns the current
    // Xianyu surface, generic recovery and learned gesture replay are forbidden.
    private static volatile boolean gameSolverOwnsPageV420 = false;
    private static volatile String gameSolverKindV420 = "";

    // V4.21: if a game solver SAFE_STOPs while the actual game is still visible,
    // keep the page in place. Verification/scanning must never start generic
    // back/navigation loops from an unfinished game.
    private static volatile boolean gameIncompleteHoldV421 = false;
    private static volatile String gameIncompleteKindV421 = "";
    private static volatile String gameIncompleteTaskV421 = "";

    private static final String PROFILE_PREFS_V48 = "xianyu_task_profiles_v48";
    private static final int PROFILE_SCHEMA_V411 = 411;
    private static final long FEATURE_TTL_MS_V411 = 90L * 24L * 60L * 60L * 1000L;
    private static final long TASK_COOLDOWN_MS_V411 = 10L * 60L * 1000L;
    private static final int TASK_COOLDOWN_FAILS_V411 = 3;
    private static final int RECENT_HISTORY_MAX_V411 = 12;
    private static final int MAX_DIAGNOSTIC_SCREENSHOTS_V411 = 20;
    private static final String DIAGNOSTIC_DIR_V411 =
            "/sdcard/Android/data/" + MODULE_PACKAGE + "/files/xianyu_diagnostics";
    private static final Pattern PROGRESS_PATTERN_V411 =
            Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private TaskExecutor() {
    }

    public static boolean isLearningMode() {
        return running && learningModeV412;
    }

    public static int getLearningCaseCount(Context context) {
        if (context == null) return 0;
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(LEARNING_PREFS_V412, Context.MODE_PRIVATE)
                    .getInt("__case_count", 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void requestStop(String reason) {
        if (!running) return;
        if (learningModeV412) {
            learningStopRequestedV412 = true;
            diagnostic("[学习模式V4.13] 收到停止请求："
                    + (reason == null ? "" : reason));
            Process p = learningInputProcessV412;
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        } else {
            markUserAbortV48(reason == null || reason.isEmpty()
                    ? "用户请求停止"
                    : reason);
        }
    }

    public static void run(Context appContext) {
        run(appContext, null);
    }

    public static void run(Context appContext, Runnable onComplete) {
        startRunV412(appContext, onComplete, false);
    }

    public static void runLearning(Context appContext) {
        runLearning(appContext, null);
    }

    public static void runLearning(Context appContext, Runnable onComplete) {
        startRunV412(appContext, onComplete, true);
    }

    private static synchronized void startRunV412(
            Context appContext,
            Runnable onComplete,
            boolean learning
    ) {
        if (running) {
            diagnostic("⚠️ 已有任务正在运行，忽略重复触发");
            return;
        }

        if (appContext == null) {
            if (onComplete != null) {
                try { onComplete.run(); } catch (Throwable ignored) { }
            }
            return;
        }

        lastContext = appContext.getApplicationContext();
        running = true;
        learningModeV412 = learning;
        learningStopRequestedV412 = false;
        learningInputProcessV412 = null;
        userAborted = false;
        inBounceTask = false;
        physicalTouchDetected = false;
        physicalTouchAt = 0L;
        syntheticInputIgnoreUntilV411 = 0L;
        lastSyntheticInputAtV411 = 0L;
        invalidateOcrCacheV411();

        diagnostic(
                learning
                        ? "========== 真人示范学习开始 · V4.13 =========="
                        : "========== 闲鱼任务开始 · V4.31 =========="
        );

        if (learning) {
            diagnostic("🧠 学习模式只观察，不会主动点击、滑动、返回或启动闲鱼");
            diagnostic("🧠 请手动离开本助手并正常操作；完成后切回本助手即可结束学习");
        } else {
            diagnostic("💡 想中途停止：切回“闲鱼定时助手”即可");
        }

        notifyTask(
                lastContext,
                learning ? "闲鱼真人示范学习" : "闲鱼自动任务",
                learning ? "正在记录真人操作" : "任务已启动"
        );

        Thread worker = new Thread(
                () -> {
                    try {
                        if (learningModeV412) {
                            executeLearningV412(lastContext);
                        } else {
                            execute(lastContext);
                        }
                    } catch (Throwable t) {
                        diagnostic(
                                learningModeV412 ? "学习线程异常" : "任务线程异常",
                                t
                        );
                    } finally {
                        stopPhysicalTouchMonitorV48();
                        stopLearningSamplerV412();
                        Process lp = learningInputProcessV412;
                        learningInputProcessV412 = null;
                        if (lp != null) {
                            try { lp.destroy(); } catch (Throwable ignored) { }
                            try { lp.destroyForcibly(); } catch (Throwable ignored) { }
                        }

                        boolean wasLearning = learningModeV412;
                        learningModeV412 = false;
                        learningStopRequestedV412 = false;
                        running = false;
                        inBounceTask = false;

                        diagnostic(
                                wasLearning
                                        ? "========== 真人示范学习结束 =========="
                                        : "========== 闲鱼任务结束 =========="
                        );

                        notifyTask(
                                lastContext,
                                wasLearning ? "闲鱼真人示范学习" : "闲鱼自动任务",
                                wasLearning
                                        ? "学习记录已保存"
                                        : (userAborted ? "任务已中止" : "任务已结束")
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
                learning ? "XianyuLearn-V413" : "XianyuTask-V431"
        );

        worker.start();
    }


    private static void executeLearningV412(Context ctx) {
        String suPath = findSuPathWithRetry();
        if (suPath == null) {
            diagnostic("❌ [学习模式V4.13] Root 不可用，无法读取真实触摸事件");
            sendStatus("", "FAILED", "学习模式需要 Root 读取触摸事件");
            return;
        }

        RootResult id = rootWithPath(suPath, "id");
        if (id.exitCode != 0 || id.stdout == null || !id.stdout.contains("uid=0")) {
            diagnostic("❌ [学习模式V4.13] Root 权限验证失败");
            sendStatus("", "FAILED", "Root 权限验证失败");
            return;
        }

        String device = findTouchscreenDeviceV48(suPath);
        if (device == null || device.isEmpty()) {
            diagnostic("❌ [学习模式V4.13] 未识别到触摸屏输入设备");
            sendStatus("", "FAILED", "未识别到触摸屏设备");
            return;
        }

        int[] screen = getScreenSizeV43(suPath);
        int screenW = screen == null ? 1440 : screen[0];
        int screenH = screen == null ? 3120 : screen[1];
        TouchAxisV412 axis = readTouchAxisV412(suPath, device, screenW, screenH);

        learningHasLeftHelperV412 = false;
        learningLastActionEndV412 = SystemClock.elapsedRealtime();
        learningLastPageV412 = new PageProbeV411(
                MODULE_PACKAGE,
                ScreenOcr.Snapshot.empty(),
                "",
                PageKindV411.MODULE_APP,
                "helper"
        );
        learningLastPageAtV412 = SystemClock.elapsedRealtime();
        synchronized (learningPageHistoryLockV413) {
            learningPageHistoryV413.clear();
        }
        rememberLearningPageV413(learningLastPageV412, learningLastPageAtV412);

        diagnostic("[学习模式V4.13] 触摸设备=" + device
                + " screen=" + screenW + "x" + screenH
                + " rawX=" + axis.minX + ".." + axis.maxX
                + " rawY=" + axis.minY + ".." + axis.maxY);
        diagnostic("[学习模式V4.13] 触摸生命周期=TRACKING_ID/BTN_TOUCH DOWN→UP；持续时间使用 getevent 原始时间戳");
        diagnostic("[学习模式V4.13] 现在请真人正常操作手机；程序不会执行任何 input tap/swipe/back/am start");
        diagnostic("[学习模式V4.13] 完成示范后切回“闲鱼定时助手”，学习会自动结束");

        startLearningSamplerV412(suPath);

        Process process = null;
        int gestureCount = 0;
        int skippedCount = 0;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    suPath,
                    "-c",
                    "getevent -lt " + device + " 2>/dev/null"
            });
            learningInputProcessV412 = process;

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            );

            boolean active = false;
            int startRawX = -1;
            int startRawY = -1;
            int endRawX = -1;
            int endRawY = -1;
            long downEventMs = -1L;
            long downElapsedMs = 0L;
            long eventToElapsedOffsetMs = Long.MIN_VALUE;
            long lastUpEventMs = -1L;
            PageProbeV411 before = null;
            String line;

            while (running
                    && learningModeV412
                    && !learningStopRequestedV412
                    && (line = reader.readLine()) != null) {

                String u = line.toUpperCase(Locale.US);
                long eventMs = parseGeteventTimestampMsV413(line);
                if (eventMs >= 0L && eventToElapsedOffsetMs == Long.MIN_VALUE) {
                    eventToElapsedOffsetMs = SystemClock.elapsedRealtime() - eventMs;
                }

                boolean trackingDown = isTrackingDownV413(u);
                boolean trackingUp = isTrackingUpV413(u);
                boolean buttonDown = isBtnTouchDownV413(u);
                boolean buttonUp = isBtnTouchUpV413(u);

                // A new contact MUST reset all coordinates. This is the key V4.13
                // fix: the previous gesture's end point can never become this
                // gesture's start point.
                if ((trackingDown || buttonDown) && !active) {
                    active = true;
                    startRawX = -1;
                    startRawY = -1;
                    endRawX = -1;
                    endRawY = -1;
                    downEventMs = eventMs;
                    downElapsedMs = eventMs >= 0L && eventToElapsedOffsetMs != Long.MIN_VALUE
                            ? eventMs + eventToElapsedOffsetMs
                            : SystemClock.elapsedRealtime();
                    before = learningPageAtOrBeforeV413(downElapsedMs, 2800L);
                    if (before == null) before = learningLastPageV412;
                    if (before == null) {
                        before = freshestLearningPageV412(suPath, 2500L);
                    }
                }

                Integer xVal = parseGeteventAxisValueV412(u, "ABS_MT_POSITION_X", "0035");
                if (xVal != null && active) {
                    if (startRawX < 0) startRawX = xVal;
                    endRawX = xVal;
                }

                Integer yVal = parseGeteventAxisValueV412(u, "ABS_MT_POSITION_Y", "0036");
                if (yVal != null && active) {
                    if (startRawY < 0) startRawY = yVal;
                    endRawY = yVal;
                }

                if ((trackingUp || buttonUp) && active) {
                    active = false;
                    long processUpElapsedMs = SystemClock.elapsedRealtime();
                    long upElapsedMs = eventMs >= 0L && eventToElapsedOffsetMs != Long.MIN_VALUE
                            ? eventMs + eventToElapsedOffsetMs
                            : processUpElapsedMs;
                    long duration = durationFromEventTimesV413(
                            downEventMs,
                            eventMs,
                            downElapsedMs,
                            upElapsedMs);

                    int sx = axis.toScreenX(startRawX);
                    int sy = axis.toScreenY(startRawY);
                    int ex = axis.toScreenX(endRawX >= 0 ? endRawX : startRawX);
                    int ey = axis.toScreenY(endRawY >= 0 ? endRawY : startRawY);

                    if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
                        skippedCount++;
                        diagnostic("[学习模式V4.13] 忽略坐标不完整的触摸事件"
                                + " rawStart=(" + startRawX + "," + startRawY + ")"
                                + " rawEnd=(" + endRawX + "," + endRawY + ")");
                        learningLastActionEndV412 = upElapsedMs;
                        if (eventMs >= 0L) lastUpEventMs = eventMs;
                        startRawX = startRawY = endRawX = endRawY = -1;
                        before = null;
                        downEventMs = -1L;
                        continue;
                    }

                    long idleBefore;
                    if (downEventMs >= 0L && lastUpEventMs >= 0L && downEventMs >= lastUpEventMs) {
                        idleBefore = downEventMs - lastUpEventMs;
                    } else {
                        idleBefore = learningLastActionEndV412 <= 0L
                                ? 0L
                                : Math.max(0L, downElapsedMs - learningLastActionEndV412);
                    }

                    LearnedGestureV412 gesture = classifyLearnedGestureV412(
                            sx, sy, ex, ey, duration, screenW, screenH);

                    PageProbeV411 beforeFinal = before != null
                            ? before
                            : freshestLearningPageV412(suPath, 2500L);

                    long afterTargetElapsed = upElapsedMs + 450L;
                    if (!learningStopRequestedV412) {
                        long remaining = afterTargetElapsed - SystemClock.elapsedRealtime();
                        if (remaining > 0L) {
                            SystemClock.sleep(Math.min(LEARNING_POST_ACTION_DELAY_MS_V412, remaining));
                        }
                    }

                    PageProbeV411 after = learningStopRequestedV412
                            ? learningLastPageV412
                            : learningPageAtOrAfterV413(afterTargetElapsed, 2600L);
                    if (after == null && !learningStopRequestedV412) {
                        after = sampleLearningPageV412(suPath, "动作后");
                    }

                    if (after == null) {
                        after = new PageProbeV411(
                                "", ScreenOcr.Snapshot.empty(), "",
                                PageKindV411.UNKNOWN, "");
                    }
                    if (beforeFinal == null) {
                        beforeFinal = new PageProbeV411(
                                "", ScreenOcr.Snapshot.empty(), "",
                                PageKindV411.UNKNOWN, "");
                    }

                    if (shouldRecordLearningGestureV413(beforeFinal, gesture, after, duration)) {
                        LearningStoreV412.record(
                                ctx,
                                beforeFinal,
                                gesture,
                                after,
                                idleBefore,
                                duration,
                                screenW,
                                screenH
                        );

                        gestureCount++;
                        diagnostic("[学习动作V4.13] #" + gestureCount
                                + " " + gesture.kind
                                + " (" + sx + "," + sy + ")->(" + ex + "," + ey + ")"
                                + " duration=" + duration + "ms"
                                + " idleBefore=" + idleBefore + "ms"
                                + " page=" + learningPageKindV413(beforeFinal)
                                + "->" + learningPageKindV413(after));
                    } else {
                        skippedCount++;
                        diagnostic("[学习过滤V4.13] 忽略无关/异常动作 " + gesture.kind
                                + " duration=" + duration + "ms"
                                + " page=" + learningPageKindV413(beforeFinal)
                                + "->" + learningPageKindV413(after));
                    }

                    learningLastActionEndV412 = upElapsedMs;
                    if (eventMs >= 0L) lastUpEventMs = eventMs;
                    learningLastPageV412 = after;
                    learningLastPageAtV412 = SystemClock.elapsedRealtime();

                    startRawX = startRawY = endRawX = endRawY = -1;
                    before = null;
                    downEventMs = -1L;
                }
            }
        } catch (Throwable t) {
            if (!learningStopRequestedV412) {
                diagnostic("[学习模式V4.13] 触摸记录线程异常", t);
            }
        } finally {
            learningInputProcessV412 = null;
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) { }
            }
            stopLearningSamplerV412();
        }

        int cases = getLearningCaseCount(ctx);
        diagnostic("[学习模式V4.13] 本次真人示范结束；有效动作=" + gestureCount
                + " 忽略动作=" + skippedCount + " 当前唯一学习案例=" + cases);
        sendStatus("", "FINISHED", "学习完成，已保存 " + cases + " 个唯一案例");
    }

    private static long parseGeteventTimestampMsV413(String line) {
        if (line == null) return -1L;
        Matcher m = Pattern.compile("\\[\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\]").matcher(line);
        if (!m.find()) return -1L;
        try {
            double seconds = Double.parseDouble(m.group(1));
            return Math.round(seconds * 1000.0);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static boolean isTrackingDownV413(String upperLine) {
        if (upperLine == null || !(upperLine.contains("ABS_MT_TRACKING_ID") || upperLine.contains(" 0039 "))) return false;
        String t = upperLine.trim();
        return !(t.endsWith("FFFFFFFF") || t.endsWith("-1"));
    }

    private static boolean isTrackingUpV413(String upperLine) {
        if (upperLine == null || !(upperLine.contains("ABS_MT_TRACKING_ID") || upperLine.contains(" 0039 "))) return false;
        String t = upperLine.trim();
        return t.endsWith("FFFFFFFF") || t.endsWith("-1");
    }

    private static boolean isBtnTouchDownV413(String upperLine) {
        if (upperLine == null || !(upperLine.contains("BTN_TOUCH") || upperLine.contains(" 014A "))) return false;
        String t = upperLine.trim();
        return t.contains(" DOWN") || t.endsWith("00000001");
    }

    private static boolean isBtnTouchUpV413(String upperLine) {
        if (upperLine == null || !(upperLine.contains("BTN_TOUCH") || upperLine.contains(" 014A "))) return false;
        String t = upperLine.trim();
        return t.contains(" UP") || t.endsWith("00000000");
    }

    private static long durationFromEventTimesV413(
            long downEventMs,
            long upEventMs,
            long downElapsedMs,
            long upElapsedMs
    ) {
        if (downEventMs >= 0L && upEventMs >= downEventMs) {
            long d = upEventMs - downEventMs;
            if (d >= 1L && d <= 30_000L) return d;
        }
        long fallback = Math.max(1L, upElapsedMs - downElapsedMs);
        return Math.min(30_000L, fallback);
    }

    private static boolean shouldRecordLearningGestureV413(
            PageProbeV411 before,
            LearnedGestureV412 gesture,
            PageProbeV411 after,
            long durationMs
    ) {
        if (gesture == null) return false;
        String preFg = before == null ? "" : safe(before.fg);
        String postFg = after == null ? "" : safe(after.fg);

        if (MODULE_PACKAGE.equals(preFg) || MODULE_PACKAGE.equals(postFg)) return false;
        if (isLearningNoisePackageV413(preFg) || isLearningNoisePackageV413(postFg)) return false;
        if (durationMs <= 0L || durationMs > 30_000L) return false;

        // Extremely short large swipes are almost always malformed lifecycle
        // pairs rather than human gestures.
        double dist = Math.hypot(gesture.ex - gesture.sx, gesture.ey - gesture.sy);
        if (durationMs < 12L && dist > 80.0) return false;
        return true;
    }

    private static boolean isLearningNoisePackageV413(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return "android".equals(pkg)
                || "com.android.systemui".equals(pkg)
                || "com.samsung.android.permissioncontroller".equals(pkg)
                || "com.google.android.permissioncontroller".equals(pkg)
                || "com.android.permissioncontroller".equals(pkg);
    }

    private static void startLearningSamplerV412(String suPath) {
        stopLearningSamplerV412();

        Thread t = new Thread(() -> {
            long helperSince = 0L;
            while (running && learningModeV412 && !learningStopRequestedV412) {
                try {
                    String fg = getFg(suPath, false);

                    if (MODULE_PACKAGE.equals(fg)) {
                        if (learningHasLeftHelperV412) {
                            if (helperSince == 0L) helperSince = SystemClock.elapsedRealtime();
                            if (SystemClock.elapsedRealtime() - helperSince >= 650L) {
                                learningStopRequestedV412 = true;
                                diagnostic("[学习模式V4.13] 检测到真人切回助手，停止学习并保存记录");
                                Process p = learningInputProcessV412;
                                if (p != null) {
                                    try { p.destroy(); } catch (Throwable ignored) { }
                                }
                                break;
                            }
                        }
                    } else {
                        helperSince = 0L;
                        if (fg != null && !fg.isEmpty()) {
                            learningHasLeftHelperV412 = true;
                        }

                        long now = SystemClock.elapsedRealtime();
                        if (learningHasLeftHelperV412
                                && now - learningLastPageAtV412 >= LEARNING_PAGE_SAMPLE_MS_V412) {
                            sampleLearningPageV412(suPath, "空闲采样");
                        }
                    }

                    SystemClock.sleep(320L);
                } catch (Throwable t1) {
                    diagnostic("[学习模式V4.13] 页面采样异常：" + t1);
                    SystemClock.sleep(600L);
                }
            }
        }, "XianyuLearnSampler-V413");

        t.setDaemon(true);
        learningSamplerThreadV412 = t;
        t.start();
    }

    private static void stopLearningSamplerV412() {
        Thread t = learningSamplerThreadV412;
        learningSamplerThreadV412 = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.interrupt(); } catch (Throwable ignored) { }
        }
    }

    private static synchronized PageProbeV411 sampleLearningPageV412(
            String suPath,
            String reason
    ) {
        if (!learningModeV412 || learningStopRequestedV412) {
            return learningLastPageV412;
        }

        PageProbeV411 page;
        try {
            page = probePageV411(suPath, "学习/" + reason);
        } catch (Throwable t) {
            String fg = getFg(suPath, false);
            PageKindV411 kind;
            if (MODULE_PACKAGE.equals(fg)) kind = PageKindV411.MODULE_APP;
            else if (TARGET_PACKAGE.equals(fg)) kind = PageKindV411.UNKNOWN_XIANYU;
            else if (fg != null && !fg.isEmpty()) kind = PageKindV411.EXTERNAL_APP;
            else kind = PageKindV411.UNKNOWN;
            page = new PageProbeV411(
                    fg, ScreenOcr.Snapshot.empty(), "", kind, "sample_error");
        }

        learningLastPageV412 = page;
        learningLastPageAtV412 = SystemClock.elapsedRealtime();
        rememberLearningPageV413(page, learningLastPageAtV412);
        return page;
    }

    private static final class TimedLearningPageV413 {
        final long atElapsedMs;
        final PageProbeV411 page;

        TimedLearningPageV413(long atElapsedMs, PageProbeV411 page) {
            this.atElapsedMs = atElapsedMs;
            this.page = page;
        }
    }

    private static void rememberLearningPageV413(PageProbeV411 page, long atElapsedMs) {
        if (page == null) return;
        synchronized (learningPageHistoryLockV413) {
            learningPageHistoryV413.add(new TimedLearningPageV413(atElapsedMs, page));
            while (learningPageHistoryV413.size() > 24) {
                learningPageHistoryV413.remove(0);
            }
        }
    }

    private static PageProbeV411 learningPageAtOrBeforeV413(long targetElapsedMs, long maxAgeMs) {
        TimedLearningPageV413 best = null;
        synchronized (learningPageHistoryLockV413) {
            for (TimedLearningPageV413 item : learningPageHistoryV413) {
                if (item == null || item.page == null) continue;
                if (item.atElapsedMs <= targetElapsedMs
                        && targetElapsedMs - item.atElapsedMs <= maxAgeMs
                        && (best == null || item.atElapsedMs > best.atElapsedMs)) {
                    best = item;
                }
            }
        }
        return best == null ? null : best.page;
    }

    private static PageProbeV411 learningPageAtOrAfterV413(
            long targetElapsedMs,
            long maxWaitMs
    ) {
        TimedLearningPageV413 best = null;
        synchronized (learningPageHistoryLockV413) {
            for (TimedLearningPageV413 item : learningPageHistoryV413) {
                if (item == null || item.page == null) continue;
                if (item.atElapsedMs >= targetElapsedMs
                        && item.atElapsedMs - targetElapsedMs <= maxWaitMs
                        && (best == null || item.atElapsedMs < best.atElapsedMs)) {
                    best = item;
                }
            }
        }
        return best == null ? null : best.page;
    }

    private static PageProbeV411 freshestLearningPageV412(
            String suPath,
            long maxAgeMs
    ) {
        PageProbeV411 p = learningLastPageV412;
        long age = SystemClock.elapsedRealtime() - learningLastPageAtV412;
        if (p != null && age >= 0L && age <= maxAgeMs) {
            return p;
        }
        return sampleLearningPageV412(suPath, "动作前");
    }

    private static final class TouchAxisV412 {
        final int minX;
        final int maxX;
        final int minY;
        final int maxY;
        final int screenW;
        final int screenH;

        TouchAxisV412(
                int minX, int maxX, int minY, int maxY,
                int screenW, int screenH
        ) {
            this.minX = minX;
            this.maxX = Math.max(minX + 1, maxX);
            this.minY = minY;
            this.maxY = Math.max(minY + 1, maxY);
            this.screenW = Math.max(1, screenW);
            this.screenH = Math.max(1, screenH);
        }

        int toScreenX(int raw) {
            if (raw < 0) return -1;
            double r = (raw - minX) / (double) (maxX - minX);
            return clampV412((int) Math.round(r * (screenW - 1)), 0, screenW - 1);
        }

        int toScreenY(int raw) {
            if (raw < 0) return -1;
            double r = (raw - minY) / (double) (maxY - minY);
            return clampV412((int) Math.round(r * (screenH - 1)), 0, screenH - 1);
        }
    }

    private static TouchAxisV412 readTouchAxisV412(
            String suPath,
            String device,
            int screenW,
            int screenH
    ) {
        int minX = 0;
        int maxX = Math.max(1, screenW - 1);
        int minY = 0;
        int maxY = Math.max(1, screenH - 1);

        RootResult r = rootWithPath(
                suPath,
                "getevent -lp " + device + " 2>/dev/null"
        );

        if (r.exitCode == 0 && r.stdout != null) {
            int[] x = parseAxisRangeV412(r.stdout, "ABS_MT_POSITION_X", "0035");
            int[] y = parseAxisRangeV412(r.stdout, "ABS_MT_POSITION_Y", "0036");
            if (x != null) {
                minX = x[0];
                maxX = x[1];
            }
            if (y != null) {
                minY = y[0];
                maxY = y[1];
            }
        }

        return new TouchAxisV412(minX, maxX, minY, maxY, screenW, screenH);
    }

    private static int[] parseAxisRangeV412(
            String text,
            String label,
            String code
    ) {
        if (text == null) return null;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String u = line.toUpperCase(Locale.US);
            if (!u.contains(label) && !u.contains(code)) continue;

            Matcher min = Pattern.compile("(?i)\\bmin\\s+(-?\\d+)").matcher(line);
            Matcher max = Pattern.compile("(?i)\\bmax\\s+(-?\\d+)").matcher(line);
            if (min.find() && max.find()) {
                try {
                    int a = Integer.parseInt(min.group(1));
                    int b = Integer.parseInt(max.group(1));
                    if (b > a) return new int[]{a, b};
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Integer parseGeteventAxisValueV412(
            String upperLine,
            String label,
            String code
    ) {
        if (upperLine == null) return null;
        int idx = upperLine.indexOf(label);
        if (idx < 0) idx = upperLine.indexOf(code);
        if (idx < 0) return null;

        String tail = upperLine.substring(idx);
        Matcher m = Pattern.compile("(?i)(?:ABS_MT_POSITION_[XY]|003[56])\\s+([0-9A-F]{1,8})")
                .matcher(tail);
        if (!m.find()) return null;
        try {
            return (int) Long.parseLong(m.group(1), 16);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class LearnedGestureV412 {
        final String kind;
        final int sx;
        final int sy;
        final int ex;
        final int ey;

        LearnedGestureV412(String kind, int sx, int sy, int ex, int ey) {
            this.kind = kind;
            this.sx = sx;
            this.sy = sy;
            this.ex = ex;
            this.ey = ey;
        }
    }

    private static LearnedGestureV412 classifyLearnedGestureV412(
            int sx, int sy, int ex, int ey,
            long durationMs,
            int width, int height
    ) {
        int dx = ex - sx;
        int dy = ey - sy;
        double distance = Math.hypot(dx, dy);
        int edgePx = Math.max(8, Math.min(28, Math.round(width * 0.018f)));
        int swipeMin = Math.max(90, Math.round(width * 0.08f));

        String kind;
        if (sx <= edgePx && dx >= swipeMin && Math.abs(dx) > Math.abs(dy) * 1.3) {
            kind = "EDGE_BACK_LEFT";
        } else if (sx >= width - 1 - edgePx
                && dx <= -swipeMin
                && Math.abs(dx) > Math.abs(dy) * 1.3) {
            kind = "EDGE_BACK_RIGHT";
        } else if (distance < Math.max(34.0, width * 0.025)) {
            kind = durationMs >= 650L ? "LONG_PRESS" : "TAP";
        } else if (Math.abs(dy) > Math.abs(dx) * 1.15) {
            kind = dy < 0 ? "SWIPE_UP" : "SWIPE_DOWN";
        } else if (Math.abs(dx) > Math.abs(dy) * 1.15) {
            kind = dx < 0 ? "SWIPE_LEFT" : "SWIPE_RIGHT";
        } else {
            kind = "SWIPE_DIAGONAL";
        }

        return new LearnedGestureV412(kind, sx, sy, ex, ey);
    }

    private static int clampV412(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String learningPageKindV413(PageProbeV411 page) {
        if (page == null) return "UNKNOWN";
        String fg = safe(page.fg);
        String text = safe(page.text);

        if (MODULE_PACKAGE.equals(fg)) return "MODULE_APP";
        if (!fg.isEmpty() && !TARGET_PACKAGE.equals(fg)) return "EXTERNAL_APP";

        if (TARGET_PACKAGE.equals(fg)) {
            if (containsAny(text, "正在跳转", "打开淘宝", "打开支付宝", "打开美团")) {
                return "JUMP_PAGE";
            }
            if (containsAny(text,
                    "继续试玩", "安装应用可立即领奖", "安装完成即可领奖",
                    "立即下载", "继续播放视频内容", "广告")) {
                return "AD_OR_INSTALL";
            }
            if (containsAny(text, "得骰子赚闲鱼币")
                    || (containsAny(text, "闲鱼币")
                    && containsAny(text, "去完成", "领取奖励", "领取笑励", "收益+10%"))) {
                return "TASK_PANEL";
            }
            if (containsAny(text, "扔骰子寻宝", "碎片收集", "1分兑换")
                    || (containsAny(text, "赚骰子") && containsAny(text, "背包", "闲鱼币"))) {
                return "COIN_HOME";
            }
            if (containsAny(text, "我的收藏")
                    && containsAny(text, "我发布的", "我卖出的", "我买到的")) {
                return "MINE";
            }
            if (containsAny(text, "卖闲置")
                    && containsAny(text, "消息")
                    && containsAny(text, "我的")) {
                return "XIANYU_HOME";
            }
            if (FruitGameSolver.looksLikeFruitGame(text)) return "FRUIT_PAIR_GAME";
            if (MahjongGameSolver.looksLikeMahjongPairGame(text)) return "MAHJONG_PAIR_GAME";
        }

        switch (page.kind) {
            case TASK_PANEL: return "TASK_PANEL";
            case COIN_HOME: return "COIN_HOME";
            case MINE: return "MINE";
            case XIANYU_HOME: return "XIANYU_HOME";
            case AD_OR_INSTALL: return "AD_OR_INSTALL";
            case FRUIT_PAIR_GAME: return "FRUIT_PAIR_GAME";
            case MAHJONG_PAIR_GAME: return "MAHJONG_PAIR_GAME";
            case EXTERNAL_APP: return "EXTERNAL_APP";
            case MODULE_APP: return "MODULE_APP";
            case UNKNOWN_XIANYU: return "UNKNOWN_XIANYU";
            default: return "UNKNOWN";
        }
    }

    private static String learningAppKeyV413(String pkg) {
        String p = safe(pkg);
        if (p.isEmpty()) return "";
        if (TARGET_PACKAGE.equals(p)) return "XIANYU";
        if (MODULE_PACKAGE.equals(p)) return "HELPER";
        if ("com.taobao.taobao".equals(p)) return "TAOBAO";
        if ("com.eg.android.AlipayGphone".equals(p)) return "ALIPAY";
        if ("com.sankuai.meituan".equals(p)) return "MEITUAN";
        if ("com.dianping.v1".equals(p)) return "DIANPING";
        if ("com.sec.android.app.samsungapps".equals(p)) return "GALAXY_STORE";
        if (p.contains("permissioncontroller")) return "PERMISSION";
        return p;
    }

    private static String learningSemanticActionV413(
            PageProbeV411 before,
            LearnedGestureV412 gesture,
            int width,
            int height
    ) {
        if (before == null || gesture == null) return "";
        if (!("TAP".equals(gesture.kind) || "LONG_PRESS".equals(gesture.kind))) {
            return "";
        }
        String raw = learningActionLabelV412(
                before.ocr, gesture.sx, gesture.sy, width, height);
        return canonicalLearningActionV413(raw);
    }

    private static String canonicalLearningActionV413(String raw) {
        if (raw == null) return "";
        String s = raw
                .replace("领取笑励", "领取奖励")
                .replace("赚股子", "赚骰子")
                .replace("賺股子", "赚骰子")
                .replace("安井", "安装")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty()) return "";

        String button = "";
        if (s.contains("去完成")) button = "去完成";
        else if (s.contains("领取奖励")) button = "领取奖励";
        else if (s.contains("签到")) button = "签到";
        else if (s.contains("去浏览")) button = "去浏览";
        else if (s.contains("去领取")) button = "去领取";

        String row = "";
        int slash = s.indexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) {
            row = s.substring(slash + 1);
        } else if (!button.isEmpty()) {
            row = s.replace(button, "");
        } else {
            row = s;
        }

        row = row
                .replaceAll("[\\p{Punct}，。！？、：；（）【】《》“”‘’·]+", "")
                .replaceAll("\\s+", "")
                .replaceAll("\\d+\\s*/\\s*\\d+", "")
                .trim();
        if (row.length() > 28) row = row.substring(0, 28);
        if (row.matches("[0-9]+") || row.length() == 1) row = "";

        if (!button.isEmpty()) {
            return row.isEmpty() ? button : button + "/" + row;
        }
        return row;
    }

    private static String learningSemanticSpatialV413(
            LearnedGestureV412 gesture,
            String action,
            int width,
            int height
    ) {
        if (gesture == null || width <= 0 || height <= 0) return "";
        if ("EDGE_BACK_LEFT".equals(gesture.kind)
                || "EDGE_BACK_RIGHT".equals(gesture.kind)) {
            return "EDGE";
        }
        if (action != null && !action.isEmpty()) return "LABEL";

        double xr = gesture.sx / (double) Math.max(1, width - 1);
        double yr = gesture.sy / (double) Math.max(1, height - 1);

        if ("SWIPE_UP".equals(gesture.kind) || "SWIPE_DOWN".equals(gesture.kind)) {
            String band = yr < 0.34 ? "TOP" : (yr < 0.68 ? "MID" : "BOTTOM");
            return "SCROLL_" + band;
        }
        if ("TAP".equals(gesture.kind) || "LONG_PRESS".equals(gesture.kind)) {
            int bx = clampV412((int) Math.floor(xr * 4.0), 0, 3);
            int by = clampV412((int) Math.floor(yr * 8.0), 0, 7);
            return "T" + bx + ":" + by;
        }
        int bx = clampV412((int) Math.floor(xr * 3.0), 0, 2);
        int by = clampV412((int) Math.floor(yr * 4.0), 0, 3);
        return "G" + bx + ":" + by;
    }

    private static final class LearningStoreV412 {
        private static final String INDEX = "__ids";
        private static final String NEXT_ID = "__next_id";
        private static final int MAX_CASES = 220;

        static void record(
                Context context,
                PageProbeV411 before,
                LearnedGestureV412 gesture,
                PageProbeV411 after,
                long idleBeforeMs,
                long durationMs,
                int width,
                int height
        ) {
            if (context == null || gesture == null) return;

            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences(LEARNING_PREFS_V412, Context.MODE_PRIVATE);

            String preKind = learningPageKindV413(before);
            String postKind = learningPageKindV413(after);
            String preFg = before == null ? "" : safe(before.fg);
            String postFg = after == null ? "" : safe(after.fg);
            String preApp = learningAppKeyV413(preFg);
            String postApp = learningAppKeyV413(postFg);
            String preMarker = before == null ? "" : safe(before.marker);
            String postMarker = after == null ? "" : safe(after.marker);

            String actionLabel = learningSemanticActionV413(
                    before, gesture, width, height);
            String spatialBucket = learningSemanticSpatialV413(
                    gesture, actionLabel, width, height);

            // V4.13 semantic signature: no pixel-level coordinates and no raw
            // OCR marker noise. Same page transition + same human action is one
            // case; coordinates/timing are aggregated as statistics.
            String signature = preKind + "|" + preApp
                    + "|" + gesture.kind
                    + "|" + actionLabel
                    + "|" + spatialBucket
                    + "|" + postKind + "|" + postApp;

            List<String> ids = splitLearningIndexV412(p.getString(INDEX, ""));
            String foundId = null;
            for (String id : ids) {
                if (signature.equals(p.getString("c." + id + ".sig", ""))) {
                    foundId = id;
                    break;
                }
            }

            SharedPreferences.Editor e = p.edit();
            boolean created = false;
            if (foundId == null) {
                int next = p.getInt(NEXT_ID, 1);
                foundId = Integer.toString(next);
                e.putInt(NEXT_ID, next + 1);
                ids.add(foundId);
                created = true;
            }

            String b = "c." + foundId + ".";
            int oldCount = p.getInt(b + "count", 0);
            int newCount = oldCount + 1;

            long avgDuration = rollingAverageV412(
                    p.getLong(b + "avg_duration", 0L),
                    durationMs,
                    oldCount);
            long avgIdle = rollingAverageV412(
                    p.getLong(b + "avg_idle", 0L),
                    idleBeforeMs,
                    oldCount);

            int sx10000 = ratio10000V412(gesture.sx, width);
            int sy10000 = ratio10000V412(gesture.sy, height);
            int ex10000 = ratio10000V412(gesture.ex, width);
            int ey10000 = ratio10000V412(gesture.ey, height);

            e.putString(b + "sig", signature)
                    .putInt(b + "count", newCount)
                    .putString(b + "pre_kind", preKind)
                    .putString(b + "pre_fg", preFg)
                    .putString(b + "pre_app", preApp)
                    .putString(b + "pre_marker", preMarker)
                    .putString(b + "gesture", gesture.kind)
                    .putString(b + "action_label", actionLabel)
                    .putString(b + "spatial_bucket", spatialBucket)
                    .putString(b + "post_kind", postKind)
                    .putString(b + "post_fg", postFg)
                    .putString(b + "post_app", postApp)
                    .putString(b + "post_marker", postMarker)
                    .putLong(b + "avg_duration", avgDuration)
                    .putLong(b + "avg_idle", avgIdle)
                    .putLong(b + "min_duration", oldCount <= 0
                            ? durationMs : Math.min(p.getLong(b + "min_duration", durationMs), durationMs))
                    .putLong(b + "max_duration", oldCount <= 0
                            ? durationMs : Math.max(p.getLong(b + "max_duration", durationMs), durationMs))
                    .putInt(b + "sx", rollingIntAverageV412(
                            p.getInt(b + "sx", sx10000), sx10000, oldCount))
                    .putInt(b + "sy", rollingIntAverageV412(
                            p.getInt(b + "sy", sy10000), sy10000, oldCount))
                    .putInt(b + "ex", rollingIntAverageV412(
                            p.getInt(b + "ex", ex10000), ex10000, oldCount))
                    .putInt(b + "ey", rollingIntAverageV412(
                            p.getInt(b + "ey", ey10000), ey10000, oldCount))
                    .putLong(b + "last_at", System.currentTimeMillis());

            if (oldCount == 0) {
                e.putLong(b + "first_at", System.currentTimeMillis());
            }

            if (ids.size() > MAX_CASES) {
                int remove = ids.size() - MAX_CASES;
                for (int i = 0; i < remove; i++) {
                    String oldId = ids.get(i);
                    removeLearningCaseV412(e, oldId);
                }
                ids = new ArrayList<>(ids.subList(remove, ids.size()));
            }

            e.putString(INDEX, joinLearningIndexV412(ids))
                    .putInt("__case_count", ids.size())
                    .putInt("__schema", 413)
                    .apply();

            int avgSx = rollingIntAverageV412(
                    p.getInt(b + "sx", sx10000), sx10000, oldCount);
            int avgSy = rollingIntAverageV412(
                    p.getInt(b + "sy", sy10000), sy10000, oldCount);
            int avgEx = rollingIntAverageV412(
                    p.getInt(b + "ex", ex10000), ex10000, oldCount);
            int avgEy = rollingIntAverageV412(
                    p.getInt(b + "ey", ey10000), ey10000, oldCount);

            String summary = preKind
                    + (preApp.isEmpty() ? "" : "(" + preApp + ")")
                    + " --" + gesture.kind
                    + (actionLabel.isEmpty() ? "" : "[" + actionLabel + "]")
                    + "--> " + postKind
                    + (postApp.isEmpty() ? "" : "(" + postApp + ")")
                    + " count=" + newCount
                    + " idle≈" + avgIdle + "ms"
                    + " duration≈" + avgDuration + "ms"
                    + " gesture≈(" + String.format(Locale.US, "%.3f", avgSx / 10000.0)
                    + "," + String.format(Locale.US, "%.3f", avgSy / 10000.0)
                    + ")->(" + String.format(Locale.US, "%.3f", avgEx / 10000.0)
                    + "," + String.format(Locale.US, "%.3f", avgEy / 10000.0) + ")";

            diagnostic(created
                    ? "[学习库V4.13] 新增语义案例：" + summary
                    : "[学习库V4.13去重] 同案例仅更新统计：" + summary);
            appendLearningLogV412(context, summary);
        }

        private static long rollingAverageV412(long oldAvg, long value, int oldCount) {
            if (oldCount <= 0) return Math.max(0L, value);
            long count = Math.min(50L, oldCount);
            return Math.round((oldAvg * count + Math.max(0L, value)) / (double) (count + 1L));
        }

        private static int rollingIntAverageV412(int oldAvg, int value, int oldCount) {
            if (oldCount <= 0) return value;
            int count = Math.min(50, oldCount);
            return (int) Math.round((oldAvg * (double) count + value) / (count + 1.0));
        }

        private static int ratio10000V412(int value, int total) {
            if (total <= 1) return 0;
            return clampV412(
                    (int) Math.round(value * 10000.0 / (total - 1.0)),
                    0,
                    10000);
        }

        private static void removeLearningCaseV412(
                SharedPreferences.Editor e,
                String id
        ) {
            if (id == null || id.isEmpty()) return;
            String b = "c." + id + ".";
            String[] keys = {
                    "sig", "count", "pre_kind", "pre_fg", "pre_app", "pre_marker",
                    "gesture", "action_label", "spatial_bucket",
                    "post_kind", "post_fg", "post_app", "post_marker",
                    "avg_duration", "min_duration", "max_duration", "avg_idle",
                    "sx", "sy", "ex", "ey", "first_at", "last_at"
            };
            for (String key : keys) e.remove(b + key);
        }
    }

    private static String learningSpatialBucketV412(
            LearnedGestureV412 gesture,
            int width,
            int height
    ) {
        if (gesture == null || width <= 0 || height <= 0) return "";
        int bx = clampV412((int) Math.floor(gesture.sx * 20.0 / Math.max(1, width)), 0, 19);
        int by = clampV412((int) Math.floor(gesture.sy * 40.0 / Math.max(1, height)), 0, 39);
        int ex = clampV412((int) Math.floor(gesture.ex * 20.0 / Math.max(1, width)), 0, 19);
        int ey = clampV412((int) Math.floor(gesture.ey * 40.0 / Math.max(1, height)), 0, 39);
        return bx + ":" + by + ">" + ex + ":" + ey;
    }

    private static String learningActionLabelV412(
            ScreenOcr.Snapshot snapshot,
            int screenX,
            int screenY,
            int screenW,
            int screenH
    ) {
        if (snapshot == null || snapshot.items == null || snapshot.items.isEmpty()) {
            return "";
        }

        double sx = snapshot.width > 0 && screenW > 0
                ? screenX * (snapshot.width / (double) screenW)
                : screenX;
        double sy = snapshot.height > 0 && screenH > 0
                ? screenY * (snapshot.height / (double) screenH)
                : screenY;

        ScreenOcr.Item best = null;
        double bestScore = Double.MAX_VALUE;
        for (ScreenOcr.Item item : snapshot.items) {
            if (item == null || item.text == null || item.text.trim().isEmpty()) continue;
            double dx = item.centerX() - sx;
            double dy = item.centerY() - sy;
            double d = Math.hypot(dx, dy);

            boolean contains = item.bounds != null
                    && sx >= item.bounds.left - 24
                    && sx <= item.bounds.right + 24
                    && sy >= item.bounds.top - 24
                    && sy <= item.bounds.bottom + 24;

            double score = contains ? d * 0.25 : d;
            if (score < bestScore) {
                bestScore = score;
                best = item;
            }
        }

        if (best == null || bestScore > Math.max(220.0, snapshot.width * 0.18)) {
            return "";
        }

        String action = normalizeLearningTextV412(best.text);
        if (action.isEmpty()) return "";

        if (containsAny(action, "去完成", "领取奖励", "领取笑励", "签到", "去浏览", "去领取")) {
            ScreenOcr.Item row = null;
            double rowScore = Double.MAX_VALUE;
            for (ScreenOcr.Item item : snapshot.items) {
                if (item == null || item == best || item.text == null) continue;
                String txt = normalizeLearningTextV412(item.text);
                if (txt.length() < 2 || txt.length() > 32) continue;
                if (containsAny(txt,
                        "去完成", "领取奖励", "领取笑励", "签到",
                        "闲鱼币", "得骰子", "赚闲鱼币", "提醒签到")) {
                    continue;
                }

                double dy = Math.abs(item.centerY() - best.centerY());
                if (dy > 130) continue;
                if (item.centerX() >= best.centerX() - 80) continue;

                double dx = Math.max(0, best.centerX() - item.centerX());
                double score = dy * 4.0 + dx * 0.15;
                if (score < rowScore) {
                    rowScore = score;
                    row = item;
                }
            }

            if (row != null) {
                String rowText = normalizeLearningTextV412(row.text);
                if (!rowText.isEmpty()) {
                    return trimForLog(action + "/" + rowText, 60);
                }
            }
        }

        return trimForLog(action, 40);
    }

    private static String normalizeLearningTextV412(String text) {
        if (text == null) return "";
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() > 40) t = t.substring(0, 40);
        return t;
    }

    private static List<String> splitLearningIndexV412(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String v : raw.split(",")) {
            String x = v.trim();
            if (!x.isEmpty() && !out.contains(x)) out.add(x);
        }
        return out;
    }

    private static String joinLearningIndexV412(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.isEmpty()) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
        }
        return sb.toString();
    }

    private static void appendLearningLogV412(Context context, String line) {
        if (context == null || line == null) return;
        OutputStreamWriter writer = null;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            File file = new File(dir, LEARNING_LOG_FILE_V412);

            // V4.31 学习日志截断：文件超过 1MB 时保留最后 512KB，
            // 避免长期挂机导致文本日志无限膨胀。
            try {
                final long MAX_LEARN_LOG_BYTES = 1_000_000L;
                final long KEEP_LEARN_LOG_BYTES = 512_000L;
                if (file.exists() && file.length() > MAX_LEARN_LOG_BYTES) {
                    byte[] data = new byte[(int) KEEP_LEARN_LOG_BYTES];
                    int read;
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                        raf.seek(Math.max(0L, raf.length() - KEEP_LEARN_LOG_BYTES));
                        read = raf.read(data);
                    }
                    if (read > 0) {
                        try (FileOutputStream out = new FileOutputStream(file, false)) {
                            out.write("===== 学习日志自动截断 =====\n"
                                    .getBytes(StandardCharsets.UTF_8));
                            out.write(data, 0, read);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            writer = new OutputStreamWriter(
                    new FileOutputStream(file, true),
                    StandardCharsets.UTF_8);
            writer.write(System.currentTimeMillis() + " " + line + "\n");
            writer.flush();
        } catch (Throwable t) {
            diagnostic("[学习模式V4.13] 写学习记录失败：" + t);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Throwable ignored) { }
            }
        }
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

        // V4.11: prepare schema, remove duplicate/stale cases, and keep one
        // canonical record for each identical case.
        TaskProfileStoreV48.prepareV411();
        TaskProfileStoreV48.compactUniqueCases();

        String suPath =
                findSuPathWithRetry();

        if (suPath == null) {

            sendStatus(
                    "",
                    "FAILED",
                    "Root 未授权或不可用；请在 KernelSU → 超级用户中给闲鱼定时助手授权"
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

        startPhysicalTouchMonitorV48(suPath);

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
        boolean freshLaunchV421 = false;

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
            freshLaunchV421 = true;
        }

        // V4.21: do not pay an 8-second UIAutomator preflight before the OCR
        // navigator. On a fresh InitActivity launch the bottom “我的” tab is the
        // stable first action; destination OCR is the verification.
        if (!enterViaMineCoin(
                suPath,
                freshLaunchV421
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

        if (userAborted) {
            sendStatus(
                    "",
                    "ABORTED",
                    "人工接管，已停止；本次已验证完成 "
                            + completed
                            + " 个任务"
            );
        } else {
            sendStatus(
                    "",
                    "FINISHED",
                    "本次完成 "
                            + completed
                            + " 个任务"
            );
        }
    }

    private static boolean enterViaMineCoin(
            String suPath
    ) {
        return enterViaMineCoin(suPath, false);
    }

    private static boolean enterViaMineCoin(
            String suPath,
            boolean freshLaunchV421
    ) {

        diagnostic("[极速导航V4.31] 首页 → 我的 → 闲鱼币 → 赚骰子 → 任务面板");

        if (!ensureFg(suPath)) {
            diagnostic("[极速导航V4.31] 闲鱼没有在前台");
            return false;
        }

        PageProbeV411 page;

        // V4.23 fresh-launch fast path:
        // 1) optimistically tap the stable bottom "我的" once;
        // 2) perform only ONE OCR probe instead of looping 2-3 times;
        // 3) if InitActivity restored a deep Xianyu sub-page, recover one level
        //    at a time with an edge-back and re-probe. This keeps the common
        //    HOME -> MINE path fast without assuming InitActivity always lands HOME.
        if (freshLaunchV421) {
            if (!paceSleepV415(220L, 340L)) return false;
            diagnostic("[极速导航V4.31] 新启动闲鱼，先尝试一次底部‘我的’快速点击");
            boolean tappedMine = tapByRatioV43(
                    suPath, 0.885f, 0.950f, "极速导航V4.23-首页-我的", true);
            if (tappedMine && !paceSleepV415(420L, 620L)) return false;

            page = probePageV411(suPath, "极速导航V4.23/新启动单次确认");

            // If a previous deep H5/game/treasure page was restored, the bottom
            // navigation tap may be meaningless. Do not wait through three OCR
            // rounds; back out at most twice and classify after each step.
            int deepRecovery = 0;
            while (!userAborted
                    && page != null
                    && (page.kind == PageKindV411.UNKNOWN_XIANYU
                        || page.kind == PageKindV411.FRUIT_PAIR_GAME
                        || page.kind == PageKindV411.MAHJONG_PAIR_GAME)
                    && deepRecovery < 2) {
                deepRecovery++;
                diagnostic("[极速导航V4.31] 新启动恢复到旧子页面：" + page.kind
                        + "，执行右侧边缘返回 #" + deepRecovery);
                int[] size = getScreenSizeV43(suPath);
                int w = size == null ? 1440 : size[0];
                int h = size == null ? 3120 : size[1];
                int y = Math.max(1, Math.round(h * 0.75f));
                syntheticInputIgnoreUntilV411 = SystemClock.elapsedRealtime() + 900L;
                rootWithPath(suPath, "input swipe " + Math.max(1, w - 2) + " " + y
                        + " " + Math.max(1, Math.round(w * 0.76f)) + " " + y + " 250");
                if (!paceSleepV415(360L, 560L)) return false;
                page = probePageV411(suPath, "极速导航V4.23/旧子页面恢复#" + deepRecovery);
            }
        } else {
            page = probePageV411(suPath, "极速导航初始");
        }

        if (page.kind == PageKindV411.FRUIT_PAIR_GAME
                || page.kind == PageKindV411.MAHJONG_PAIR_GAME) {
            diagnostic("[游戏独占V4.31] 当前已经在小游戏页面，禁止导航流程把游戏当未知页退出");
            return false;
        }

        if (!isFastNavKnownPageV420(page.kind)) {
            diagnostic("[极速导航V4.31] 初始页=" + page.kind + "，先执行一次安全页面恢复");
            String recovered = recoverNavigationContextV45(suPath);
            if (recovered == null || userAborted) return false;
            page = probePageV411(suPath, "极速导航恢复后");
        }

        if (page.kind == PageKindV411.TASK_PANEL) {
            diagnostic("[极速导航V4.31] ✅ 已在任务面板");
            return true;
        }

        // HOME -> MINE. Home bottom navigation is stable; after HOME has been
        // positively identified, one direct proportional tap is faster than a
        // second OCR/UIAutomator pass.
        if (page.kind == PageKindV411.XIANYU_HOME) {
            diagnostic("[极速导航V4.31] 首页已确认，立即点击‘我的’");
            if (!tapByRatioV43(suPath, 0.885f, 0.950f, "极速导航-首页-我的", true)) {
                return false;
            }
            if (!paceSleepV415(300L, 460L)) return false;
            page = waitFastNavPageV420(suPath, PageKindV411.MINE, 5600L, "等待我的页");
            if (page == null) return false;
        }

        if (page.kind == PageKindV411.TASK_PANEL) return true;
        if (page.kind == PageKindV411.COIN_HOME) {
            // fall through; reuse this exact OCR frame to click earn-dice.
        } else if (page.kind == PageKindV411.MINE) {
            // MINE -> COIN_HOME. Reuse the OCR frame that confirmed MINE.
            diagnostic("[极速导航V4.31] 复用‘我的’页OCR，立即点击闲鱼币");
            boolean clickedCoin = clickOcrTextAnyV45(
                    suPath, page.ocr, false,
                    "闲鱼币", "闲鱼币中心", "赚闲鱼币", "领闲鱼币");
            if (!clickedCoin) {
                diagnostic("[极速导航V4.31] OCR未找到闲鱼币，使用已确认个人页比例坐标兜底");
                clickedCoin = tapByRatioV43(
                        suPath, 0.105f, 0.720f, "极速导航-我的-闲鱼币", false);
            }
            if (!clickedCoin) return false;

            // V4.23 optimistic chain: COIN_HOME's "赚骰子" entry is stable on
            // this layout. Avoid a full OCR round just to confirm COIN_HOME.
            // If the direct tap is too early/misses, the final task-panel probe
            // below will classify COIN_HOME and perform the normal OCR fallback.
            if (!paceSleepV415(850L, 1150L)) return false;
            diagnostic("[极速导航V4.31] 已点闲鱼币，乐观直点‘赚骰子’，减少一次整屏OCR");
            boolean optimisticEarn = tapByRatioV43(
                    suPath, 0.735f, 0.495f, "极速导航V4.23-闲鱼币-赚骰子快速点击", false);
            if (optimisticEarn && !paceSleepV415(650L, 950L)) return false;

            PageProbeV411 optimisticTask = probePageV411(suPath, "极速导航V4.23/乐观任务面板确认");
            if (optimisticTask.kind == PageKindV411.TASK_PANEL) {
                diagnostic("[极速导航V4.31] ✅ 乐观链路直接进入任务面板");
                return true;
            }
            if (optimisticTask.kind == PageKindV411.UNKNOWN_XIANYU) {
                // Coin home / task panel may still be rendering. One short retry
                // is cheaper than falling into full recovery and prevents false failures.
                if (!paceSleepV415(420L, 620L)) return false;
                optimisticTask = probePageV411(suPath, "极速导航V4.23/乐观链路短重试");
                if (optimisticTask.kind == PageKindV411.TASK_PANEL) {
                    diagnostic("[极速导航V4.31] ✅ 短重试后进入任务面板");
                    return true;
                }
            }
            page = optimisticTask;
        } else {
            diagnostic("[极速导航V4.31] 未到‘我的/闲鱼币’页面：" + page.kind);
            return false;
        }

        if (page.kind == PageKindV411.TASK_PANEL) return true;
        if (page.kind != PageKindV411.COIN_HOME) {
            diagnostic("[极速导航V4.31] 未确认闲鱼币主页，停止导航：" + page.kind);
            return false;
        }

        // COIN_HOME -> TASK_PANEL. Again, reuse the confirmation OCR frame.
        diagnostic("[极速导航V4.31] 复用闲鱼币主页OCR，立即点击‘赚骰子’");
        boolean clickedEarn = clickEarnDiceV417(suPath, page.ocr);
        if (!clickedEarn) {
            diagnostic("[极速导航V4.31] ‘赚骰子’OCR仍不稳定，使用已确认COIN_HOME比例坐标");
            clickedEarn = tapByRatioV43(
                    suPath, 0.735f, 0.495f, "极速导航-闲鱼币-赚骰子", false);
        }
        if (!clickedEarn) return false;
        if (!paceSleepV415(300L, 460L)) return false;

        PageProbeV411 task = waitFastNavPageV420(
                suPath, PageKindV411.TASK_PANEL, 5600L, "等待任务面板");
        if (task != null && task.kind == PageKindV411.TASK_PANEL) {
            diagnostic("[极速导航V4.31] ✅ 任务面板打开成功");
            return true;
        }

        // The only known dangerous mis-click is the adjacent 1-cent exchange.
        if (task != null && looksLikeCoinExchangePageV417(task.ocr)) {
            diagnostic("[极速导航V4.31] ⚠️ 误入闲鱼币兑好礼，单次右侧返回后重试");
            if (!backOneLevelToCoinHomeV417(suPath)) return false;
            PageProbeV411 coin = probePageV411(suPath, "兑换页返回后");
            if (coin.kind != PageKindV411.COIN_HOME) return false;
            if (!clickEarnDiceV417(suPath, coin.ocr)) return false;
            task = waitFastNavPageV420(
                    suPath, PageKindV411.TASK_PANEL, 6500L, "赚骰子重试");
            if (task != null && task.kind == PageKindV411.TASK_PANEL) {
                diagnostic("[极速导航V4.31] ✅ 重试后进入任务面板");
                return true;
            }
        }

        diagnostic("❌ [极速导航V4.31] 无法打开‘得骰子赚闲鱼币’任务面板");
        return false;
    }

    private static boolean isFastNavKnownPageV420(PageKindV411 kind) {
        return kind == PageKindV411.XIANYU_HOME
                || kind == PageKindV411.MINE
                || kind == PageKindV411.COIN_HOME
                || kind == PageKindV411.TASK_PANEL;
    }

    private static PageProbeV411 waitFastNavPageV420(
            String suPath,
            PageKindV411 expected,
            long timeoutMs,
            String reason
    ) {
        long end = SystemClock.elapsedRealtime() + Math.max(1200L, timeoutMs);
        int pass = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted || physicalTouchDetected) return null;
            PageProbeV411 page = probePageV411(
                    suPath, "极速导航/" + reason + "#" + (++pass));
            if (page.kind == expected || page.kind == PageKindV411.TASK_PANEL) {
                return page;
            }
            if (looksLikeCoinExchangePageV417(page.ocr)) {
                diagnostic("[极速导航V4.31] 检测到闲鱼币兑好礼，提前结束等待以便纠错");
                return page;
            }
            if (page.kind == PageKindV411.FRUIT_PAIR_GAME
                    || page.kind == PageKindV411.MAHJONG_PAIR_GAME
                    || page.kind == PageKindV411.MODULE_APP
                    || page.kind == PageKindV411.EXTERNAL_APP) {
                diagnostic("[极速导航V4.31] 等待" + expected + "时进入非导航页面：" + page.kind);
                return page;
            }
            if (!sleepAbortableV48(120L)) return null;
        }
        diagnostic("[极速导航V4.31] 等待" + expected + "超时：" + reason);
        return null;
    }

    /**
     * 在执行坐标兜底前先把闲鱼恢复到一个已知页面。
     * 这用于修复“红果免费短剧应用详情”之类的闲鱼内嵌页面被误当成首页，
     * 然后盲点右下角坐标的问题。
     */
    private static String recoverNavigationContextV45(String suPath) {

        for (int i = 0; i < 5; i++) {
            if (!ensureFg(suPath) || userAborted) return null;

            // V4.17: OCR first. Most IdleFish surfaces are WebView/Flutter-like and
            // UIAutomator is both slower and less reliable than the screenshot OCR.
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "页面恢复#" + (i + 1));
            if (isTaskPageV45(null, ocr)
                    || isCoinPageV45(null, ocr)
                    || isMinePageV45(null, ocr)
                    || isHomePageV45(null, ocr)) {
                return "";
            }

            String xml = dumpUi(suPath);
            if (isTaskPageV45(xml, ocr)
                    || isCoinPageV45(xml, ocr)
                    || isMinePageV45(xml, ocr)
                    || isHomePageV45(xml, ocr)) {
                return xml == null ? "" : xml;
            }

            String text = combinedTextV45(xml, ocr);
            diagnostic("[导航恢复V4.17] 未知闲鱼子页面，返回上一层："
                    + trimForLog(text, 300));

            rootWithPath(suPath, "input keyevent KEYCODE_BACK");
            if (!sleepAbortableV48(420L)) return null;
        }

        diagnostic("[导航恢复V4.17] 连续返回仍无法识别，重启闲鱼到主页面");
        rootWithPath(
                suPath,
                "am force-stop " + TARGET_PACKAGE
                        + "; sleep 1; am start -n " + TARGET_MAIN_ACTIVITY
        );

        if (!waitFg(suPath, 15000L)) return null;
        if (!sleepAbortableV48(650L)) return null;

        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "重启后的闲鱼");
        if (isTaskPageV45(null, ocr)
                || isCoinPageV45(null, ocr)
                || isMinePageV45(null, ocr)
                || isHomePageV45(null, ocr)) {
            return "";
        }
        String xml = dumpUi(suPath);
        if (isTaskPageV45(xml, ocr)
                || isCoinPageV45(xml, ocr)
                || isMinePageV45(xml, ocr)
                || isHomePageV45(xml, ocr)) {
            return xml == null ? "" : xml;
        }

        diagnostic("[导航恢复V4.17] 重启后仍无法识别闲鱼主页面");
        return null;
    }

    private static ScreenOcr.Snapshot captureOcrV45(
            String suPath,
            String reason
    ) {
        if (!learningModeV412 && (userAborted || physicalTouchDetected)) {
            return ScreenOcr.Snapshot.empty();
        }
        long now = SystemClock.elapsedRealtime();
        ScreenOcr.Snapshot cached = cachedOcrV411;
        if (cached != null
                && !cached.isEmpty()
                && now - cachedOcrAtV411 >= 0L
                && now - cachedOcrAtV411 <= OCR_CACHE_MS_V411) {
            diagnostic("[OCR缓存V4.11] " + reason + "，age="
                    + (now - cachedOcrAtV411) + "ms");
            return cached;
        }

        ScreenOcr.Snapshot snapshot = ScreenOcr.capture(lastContext, suPath);
        if (snapshot != null && !snapshot.isEmpty()) {
            cachedOcrV411 = snapshot;
            cachedOcrAtV411 = now;
            diagnostic("[OCR] " + reason + "："
                    + trimForLog(snapshot.fullText, 500));
            return snapshot;
        }
        return ScreenOcr.Snapshot.empty();
    }

    private static void invalidateOcrCacheV411() {
        cachedOcrV411 = ScreenOcr.Snapshot.empty();
        cachedOcrAtV411 = 0L;
        lastTaskPanelOcrV415 = ScreenOcr.Snapshot.empty();
        lastTaskPanelOcrAtV415 = 0L;
    }

    private static String combinedTextV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        StringBuilder sb = new StringBuilder();
        if (xml != null) sb.append(xml);
        if (ocr != null && !ocr.fullText.isEmpty()) {
            sb.append('\n').append(ocr.fullText);
        }
        return sb.toString();
    }

    private static boolean isHomePageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        String text = combinedTextV45(xml, ocr);
        if (text.isEmpty()) return false;
        if (text.contains("应用详情") && text.contains("立即下载")) return false;
        if (text.contains("得骰子赚闲鱼币")) return false;
        return text.contains("首页")
                && (text.contains("我的")
                || text.contains("消息")
                || text.contains("同城")
                || text.contains("卖闲置")
                || text.contains("领 ×1")
                || text.contains("领×1"));
    }

    private static boolean isMinePageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        if (isMinePageV43(xml)) return true;
        String text = combinedTextV45(null, ocr);
        int score = 0;
        if (text.contains("我的收藏")) score++;
        if (text.contains("历史浏览")) score++;
        if (text.contains("我的关注")) score++;
        if (text.contains("我的交易")) score++;
        if (text.contains("我发布的")) score++;
        if (text.contains("我卖出的")) score++;
        if (text.contains("闲鱼币")) score++;
        return score >= 2;
    }

    private static boolean isCoinPageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        if (isCoinPageV43(xml)) return true;
        String text = combinedTextV45(null, ocr);
        int score = 0;
        if (text.contains("扔骰子寻宝")) score += 2;
        if (text.contains("赚骰子")) score += 2;
        if (text.contains("碎片收集")) score++;
        if (text.contains("背包")) score++;
        if (text.contains("1分兑换")) score++;
        if (text.contains("闲鱼币抵扣")) score++;
        if (text.contains("IP兑换")) score++;
        return score >= 2;
    }

    private static boolean isTaskPageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        // XML 能明确看到真实任务按钮时仍然直接接受。
        if (isRealTaskPage(xml)) return true;

        if (ocr == null || ocr.isEmpty()) return false;

        String text = combinedTextV45(null, ocr);
        if (text.isEmpty()) return false;

        // 广告/试玩页里经常出现“继续试玩才能领取奖励”等文案，
        // 不能再仅凭“领取奖励”四个字判断为闲鱼任务面板。
        if (looksLikeAdOrInstallPageV47(text)) return false;

        boolean hasHeader = text.contains("得骰子赚闲鱼币");
        boolean hasRewardTag = text.contains("收益+10%")
                || text.contains("收益 +10%")
                || text.contains("收益十10%")
                || text.contains("收益＋10%");

        int validActions = countValidTaskActionsOcrV47(ocr);

        // 顶部仍可见时，标题 + 一个右侧合法按钮即可。
        if (hasHeader && validActions >= 1) return true;

        // 向下滚动后标题可能离开屏幕，此时每行仍会带“收益+10%”。
        // 要求奖励标签 + 至少一个右侧合法动作按钮，避免误把广告识别为任务页。
        return hasRewardTag && validActions >= 1;
    }

    private static int countValidTaskActionsOcrV47(ScreenOcr.Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return 0;
        int count = 0;
        for (ScreenOcr.Item item : snapshot.items) {
            if (isValidTaskActionOcrV47(snapshot, item)) count++;
        }
        return count;
    }

    private static boolean isValidTaskActionOcrV47(
            ScreenOcr.Snapshot snapshot,
            ScreenOcr.Item item
    ) {
        if (snapshot == null || item == null || item.text == null) return false;

        String raw = item.text.trim();
        if (raw.isEmpty()) return false;
        String compact = raw.replaceAll("\\s+", "");

        boolean isSignAction = "签到".equals(compact)
                || compact.endsWith("签到");

        boolean action = compact.contains("去完成")
                || compact.contains("领取奖励")
                || compact.contains("领取笑励")
                || isSignAction;
        if (!action) return false;

        // 明确排除试玩/下载广告文案。
        if (containsAny(compact,
                "试玩", "继续", "才能", "免费下载", "立即下载",
                "点击/滑动", "前往跳转", "广告", "跳过", "安装")) {
            return false;
        }

        // 真实任务按钮都位于卡片右侧。用户 1440 宽设备上中心约 x=1200，
        // 用比例而不是固定像素，可兼容其它分辨率。
        if (snapshot.width > 0) {
            float xRatio = (float) item.centerX() / (float) snapshot.width;
            if (xRatio < 0.66f) return false;
        }

        // 顶部标题/系统栏中的“签到”等误识别也不应成为任务按钮。
        if (snapshot.height > 0) {
            float yRatio = (float) item.centerY() / (float) snapshot.height;
            // 顶部“签到”按钮本来就高于普通任务行，单独放宽到 12%~40%。
            if (isSignAction) {
                if (yRatio < 0.12f || yRatio > 0.40f) return false;
            } else if (yRatio < 0.30f || yRatio > 0.975f) {
                return false;
            }
        }

        return true;
    }

    private static boolean looksLikeAdOrInstallPageV47(String text) {
        if (text == null || text.isEmpty()) return false;
        int score = 0;
        if (text.contains("试玩") || text.contains("继续试玩")) score++;
        if (text.contains("免费下载") || text.contains("立即下载")) score++;
        if (text.contains("点击/滑动前往跳转或下载应用")) score += 2;
        if (text.contains("广告")) score++;
        if (text.contains("应用详情") || text.contains("版本号：") || text.contains("开发者：")) score += 2;
        return score >= 2;
    }

    private static boolean waitMinePageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        int loop = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待我的页");
            if (isMinePageV45(null, ocr)
                    || isCoinPageV45(null, ocr)
                    || isTaskPageV45(null, ocr)) {
                return true;
            }
            // XML fallback only once when OCR is ambiguous.
            if (loop++ == 0) {
                String xml = dumpUi(suPath);
                if (isMinePageV45(xml, ocr)
                        || isCoinPageV45(xml, ocr)
                        || isTaskPageV45(xml, ocr)) return true;
            }
            if (!sleepAbortableV48(220L)) return false;
        }
        return false;
    }

    private static boolean waitCoinPageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        int loop = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待闲鱼币页");
            if (isCoinPageV45(null, ocr)) {
                diagnostic("[导航V4.17] ✅ 已确认闲鱼币主页");
                return true;
            }
            if (isTaskPageV45(null, ocr)) return true;
            if (loop++ == 0) {
                String xml = dumpUi(suPath);
                if (isCoinPageV45(xml, ocr)) {
                    diagnostic("[导航V4.17] ✅ XML兜底确认闲鱼币主页");
                    return true;
                }
                if (isTaskPageV45(xml, ocr)) return true;
            }
            if (!sleepAbortableV48(220L)) return false;
        }
        return false;
    }

    private static boolean waitTaskPageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        int loop = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待任务面板");
            if (isTaskPageV45(null, ocr)) return true;
            // Fail fast: this is the exact wrong page caused by OCR merging
            // “赚骰子 1分兑换”. The caller will return one level immediately.
            if (looksLikeCoinExchangePageV417(ocr)) return false;
            if (loop++ == 0 && ocr.isEmpty()) {
                String xml = dumpUi(suPath);
                if (isTaskPageV45(xml, ocr)) return true;
            }
            if (!sleepAbortableV48(220L)) return false;
        }
        return false;
    }

    private static boolean looksLikeCoinExchangePageV417(ScreenOcr.Snapshot ocr) {
        if (ocr == null || ocr.isEmpty()) return false;
        String text = ocr.fullText == null ? "" : ocr.fullText;
        int score = 0;
        if (text.contains("闲鱼币兑好礼")) score += 2;
        if (text.contains("每晚8点抢兑") || text.contains("开抢中")) score++;
        if (text.contains("已兑换") || text.contains("人想要")) score++;
        if (text.contains("预约") || text.contains("提醒我")) score++;
        return score >= 2;
    }

    private static boolean clickEarnDiceV417(String suPath, ScreenOcr.Snapshot ocr) {
        if (ocr == null || ocr.isEmpty()) return false;

        for (ScreenOcr.Item item : ocr.items) {
            if (item == null || item.text == null) continue;
            String t = normalizeEarnDiceTextV420(item.text);
            if (!t.contains("赚骰子")) continue;

            // A clean OCR box can be clicked at its center.
            if (!containsAny(t, "1分兑换", "1分兑換", "兑换", "兑換", "兑好物")) {
                int x = item.centerX();
                int y = item.centerY();
                diagnostic("[导航V4.17] OCR精确点击‘赚骰子’：" + item.text + " → " + x + "," + y);
                RootResult r = rootWithPath(suPath, "input tap " + x + " " + y);
                if (r.exitCode != 0) return false;
                return sleepAbortableV48(240L);
            }

            // ML Kit sometimes merges the two adjacent controls into one line:
            // “赚骰子  1分兑换”. Never click the center; click the left quarter.
            int width = Math.max(1, item.bounds.right - item.bounds.left);
            int x = item.bounds.left + Math.round(width * 0.26f);
            int y = item.centerY();
            diagnostic("[导航V4.17] OCR粘连‘赚骰子/1分兑换’，只点左侧："
                    + item.text + " → " + x + "," + y);
            RootResult r = rootWithPath(suPath, "input tap " + x + " " + y);
            if (r.exitCode != 0) return false;
            return sleepAbortableV48(240L);
        }
        return false;
    }

    private static String normalizeEarnDiceTextV420(String raw) {
        if (raw == null) return "";
        String t = raw.replace(" ", "")
                .replace("賺", "赚")
                .replace("股子", "骰子")
                .replace("股字", "骰子")
                .replace("酸子", "骰子")
                .replace("酸字", "骰子")
                .replace("般子", "骰子")
                .replace("般字", "骰子")
                .replace("骰字", "骰子");
        // OCR sometimes recognizes only one of the two characters. Constrain
        // this repair to strings beginning with “赚” so unrelated text is not changed.
        if (t.startsWith("赚") && t.length() >= 3 && !t.contains("赚骰子")) {
            char c = t.charAt(1);
            if (c == '股' || c == '酸' || c == '般' || c == '骰') {
                t = "赚骰子" + t.substring(Math.min(3, t.length()));
            }
        }
        return t;
    }

    private static boolean backOneLevelToCoinHomeV417(String suPath) {
        int[] screen = getScreenSizeV43(suPath);
        int w = screen != null && screen.length >= 2 ? screen[0] : 1440;
        int h = screen != null && screen.length >= 2 ? screen[1] : 3120;
        int sx = Math.max(1, w - 2);
        int sy = Math.max(1, Math.round(h * 0.75f));
        int ex = Math.max(1, Math.round(w * 0.76f));
        diagnostic("[导航V4.17] 单次右侧返回兑换页：" + sx + "," + sy + " → " + ex + "," + sy);
        RootResult r = rootWithPath(suPath, "input swipe " + sx + " " + sy + " " + ex + " " + sy + " 260");
        if (r.exitCode != 0) return false;
        if (!sleepAbortableV48(320L)) return false;
        return waitCoinPageV45(suPath, 4200L);
    }

    private static boolean clickOcrTextAnyV45(
            String suPath,
            ScreenOcr.Snapshot snapshot,
            boolean allowBottom,
            String... tokens
    ) {
        if (snapshot == null || snapshot.isEmpty()) return false;
        ScreenOcr.Item item = snapshot.findBest(tokens);
        if (item == null) return false;

        int x = item.centerX();
        int y = item.centerY();
        int height = snapshot.height;

        if (height > 0) {
            int gestureZone = Math.max(60, Math.round(height * 0.03f));
            if (y > height - gestureZone && !allowBottom) {
                diagnostic("[OCR点击] 位于系统手势区，取消：" + item.text);
                return false;
            }
        }

        if (!ensureFg(suPath)) return false;

        diagnostic("[OCR点击] " + item.text + " → " + x + "," + y);
        RootResult result = rootWithPath(suPath, "input tap " + x + " " + y);
        if (result.exitCode != 0) return false;
        SystemClock.sleep(900L);
        return true;
    }

    private static boolean isMinePageV43(
            String xml
    ) {

        if (xml == null
                || xml.isEmpty()) {
            return false;
        }

        int score = 0;

        if (xml.contains("我的收藏")) score++;
        if (xml.contains("历史浏览")) score++;
        if (xml.contains("我的关注")) score++;
        if (xml.contains("我的交易")) score++;
        if (xml.contains("我发布的")) score++;
        if (xml.contains("我卖出的")) score++;
        if (xml.contains("闲鱼币")) score++;

        return score >= 2;
    }

    private static boolean isCoinPageV43(
            String xml
    ) {

        if (xml == null
                || xml.isEmpty()) {
            return false;
        }

        int score = 0;

        if (xml.contains("扔骰子寻宝")) score += 2;
        if (xml.contains("赚骰子")) score += 2;
        if (xml.contains("碎片收集")) score++;
        if (xml.contains("背包")) score++;
        if (xml.contains("1分兑换")) score++;
        if (xml.contains("闲鱼币抵扣")) score++;
        if (xml.contains("IP兑换")) score++;

        return score >= 2;
    }

    private static boolean isRealTaskPage(
            String xml
    ) {

        if (xml == null
                || xml.isEmpty()) {
            return false;
        }

        boolean hasAction =
                xml.contains("领取奖励")
                        || xml.contains("去完成");

        if (!hasAction) {
            return false;
        }

        int score = 0;

        if (xml.contains("得骰子赚闲鱼币")) score += 2;
        if (xml.contains("领取奖励")) score += 2;
        if (xml.contains("去完成")) score += 2;
        if (xml.contains("签到")) score++;
        if (xml.contains("倒计时")) score++;
        if (xml.contains("看15秒视频")) score++;

        return score >= 2;
    }

    private static boolean waitMinePageV43(
            String suPath,
            long timeout
    ) {

        long end =
                SystemClock.elapsedRealtime()
                        + Math.max(0L, timeout);

        while (SystemClock.elapsedRealtime() < end) {

            if (userAborted) {
                return false;
            }

            String xml =
                    dumpUi(suPath);

            if (xml != null) {

                if (isMinePageV43(xml)
                        || isCoinPageV43(xml)
                        || isRealTaskPage(xml)) {

                    return true;
                }
            }

            SystemClock.sleep(350L);
        }

        return false;
    }

    private static boolean waitCoinPageV43(
            String suPath,
            long timeout
    ) {

        long end =
                SystemClock.elapsedRealtime()
                        + Math.max(0L, timeout);

        while (SystemClock.elapsedRealtime() < end) {

            if (userAborted) {
                return false;
            }

            String xml =
                    dumpUi(suPath);

            if (xml != null) {

                if (isCoinPageV43(xml)) {

                    diagnostic(
                            "[导航] ✅ 已确认闲鱼币主页"
                    );

                    return true;
                }

                if (isRealTaskPage(xml)) {
                    return true;
                }
            }

            SystemClock.sleep(350L);
        }

        return false;
    }

    private static boolean waitRealTaskPageV43(
            String suPath,
            long timeout
    ) {

        long end =
                SystemClock.elapsedRealtime()
                        + Math.max(0L, timeout);

        while (SystemClock.elapsedRealtime() < end) {

            if (userAborted) {
                return false;
            }

            String xml =
                    dumpUi(suPath);

            if (isRealTaskPage(xml)) {
                return true;
            }

            SystemClock.sleep(350L);
        }

        return false;
    }

    private static boolean tapByRatioV43(
            String suPath,
            float xRatio,
            float yRatio,
            String name,
            boolean allowBottom
    ) {

        int[] screen =
                getScreenSizeV43(suPath);

        if (screen == null) {

            diagnostic(
                    "[比例点击] 无法读取屏幕尺寸："
                            + name
            );

            return false;
        }

        int width =
                screen[0];

        int height =
                screen[1];

        int x =
                Math.round(
                        width * xRatio
                );

        int y =
                Math.round(
                        height * yRatio
                );

        diagnostic(
                "[比例点击] "
                        + name
                        + " → "
                        + x
                        + ","
                        + y
                        + " / "
                        + width
                        + "x"
                        + height
        );

        int gestureZone =
                Math.max(
                        60,
                        Math.round(
                                height * 0.03f
                        )
                );

        if (y > height - gestureZone) {

            if (!allowBottom) {

                diagnostic(
                        "[比例点击] 位于系统手势区，取消："
                                + name
                );

                return false;
            }

            diagnostic(
                    "[比例点击] 底部导航允许点击："
                            + name
            );
        }

        if (!ensureFg(suPath)) {
            return false;
        }

        RootResult result =
                rootWithPath(
                        suPath,
                        "input tap "
                                + x
                                + " "
                                + y
                );

        if (result.exitCode != 0) {

            diagnostic(
                    "[比例点击] 点击失败："
                            + name
                            + " exit="
                            + result.exitCode
            );

            return false;
        }

        sleepAbortableV48(450L);
        return !userAborted;
    }

    private static int[] getScreenSizeV43(
            String suPath
    ) {

        RootResult result =
                rootWithPath(
                        suPath,
                        "wm size 2>/dev/null"
                );

        if (result.exitCode != 0
                || result.stdout == null) {

            return null;
        }

        Matcher matcher =
                Pattern.compile(
                        "(\\d+)x(\\d+)"
                ).matcher(
                        result.stdout
                );

        int width = 0;
        int height = 0;

        while (matcher.find()) {

            try {

                width =
                        Integer.parseInt(
                                matcher.group(1)
                        );

                height =
                        Integer.parseInt(
                                matcher.group(2)
                        );

            } catch (Throwable ignored) {
            }
        }

        if (width <= 0
                || height <= 0) {

            return null;
        }

        if (width > height) {

            int temp = width;
            width = height;
            height = temp;
        }

        return new int[]{
                width,
                height
        };
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
        Set<String> executed = new HashSet<>();
        Map<String, Integer> attemptsByTask = new HashMap<>();
        int consecutiveFail = 0;

        for (int pass = 0; pass < 30; pass++) {

            if (userAborted) break;

            diagnostic("===== 快速扫描 " + (pass + 1) + "/30 =====");

            // V4.8: task panel is a WebView, so OCR is the fast primary path.
            // We only pay the much slower uiautomator cost when OCR is unclear.
            String xml = null;
            ScreenOcr.Snapshot taskOcr = ScreenOcr.Snapshot.empty();

            if (!ensureFg(suPath)) {
                if (userAborted) break;

                String fg = getFg(suPath, false);
                if (fg != null
                        && !fg.isEmpty()
                        && !TARGET_PACKAGE.equals(fg)
                        && !MODULE_PACKAGE.equals(fg)) {
                    diagnostic("[扫描恢复] 意外离开闲鱼：" + fg);
                    TaskProfileStoreV48.recordRecovery("__GLOBAL__", "scan_external:" + fg);
                    if (recoverToXianyuTaskPanelV47(suPath, "扫描阶段外部页面恢复")) {
                        consecutiveFail = 0;
                        sleepAbortableV48(350L);
                        continue;
                    }
                }

                consecutiveFail++;
                if (consecutiveFail >= 4) {
                    diagnostic("连续失败 4 次，退出扫描");
                    TaskProfileStoreV48.recordFailure("__GLOBAL__", "scan_fg_fail_x4");
                    break;
                }
                sleepAbortableV48(600L);
                continue;
            }

            taskOcr = consumeTaskPanelOcrForNextScanV416();
            if (taskOcr != null && !taskOcr.isEmpty()) {
                diagnostic("[连贯执行V4.16] 复用刚确认的任务面板，立即挑选下一任务");
            } else {
                taskOcr = captureOcrV45(suPath, "快速扫描任务页");
            }

            // V4.21 hard guard: scanning is only legal on TASK_PANEL. If a game
            // is still visible, never feed it into generic task-page recovery.
            String scanTextV421 = combinedTextV45(null, taskOcr);
            if (FruitGameSolver.looksLikeFruitGame(scanTextV421)
                    || MahjongGameSolver.looksLikeMahjongPairGame(scanTextV421)) {
                diagnostic("[游戏守卫V4.31] 扫描阶段仍处于小游戏；停止普通扫描，禁止导航/返回乱操作"
                        + (gameIncompleteHoldV421 ? " / reason=solver_safe_stop" : ""));
                break;
            }

            boolean taskPage = isTaskPageV45(null, taskOcr);

            if (!taskPage) {
                // Reuse the same OCR frame to close common reward popups without
                // taking another screenshot or XML dump.
                if (closePopupFromSnapshotV48(suPath, taskOcr)) {
                    sleepAbortableV48(300L);
                    continue;
                }

                xml = dumpUi(suPath);
                taskPage = isTaskPageV45(xml, taskOcr);
            }

            if (!taskPage) {
                diagnostic("[任务页] OCR/XML 均不像任务页，执行安全恢复");
                TaskProfileStoreV48.recordFailure("__NAV__", "task_panel_not_recognized");
                if (!enterViaMineCoin(suPath)) {
                    sleepAbortableV48(650L);
                }
                consecutiveFail++;
                continue;
            }

            consecutiveFail = 0;

            List<TaskCandidate> candidates =
                    findTaskCandidatesOcrV45(taskOcr);

            if (candidates.isEmpty() && xml != null) {
                candidates = findTaskCandidates(xml);
            }

            diagnostic("候选任务=" + candidates.size());

            if (candidates.isEmpty()) {
                if (!swipeUp(suPath)) {
                    sleepAbortableV48(500L);
                } else {
                    sleepAbortableV48(300L);
                }
                continue;
            }

            TaskCandidate target = null;
            int targetPriority = Integer.MAX_VALUE;

            for (TaskCandidate c : candidates) {
                if (c == null || c.bounds().isEmpty()) continue;

                if (shouldSkip(c.name)) {
                    diagnostic("[跳过] " + c.name);
                    executed.add(c.key());
                    continue;
                }

                long cooldownRemain = TaskProfileStoreV48.cooldownRemainingMsV411(c.name);
                if (cooldownRemain > 0L) {
                    diagnostic("[冷却V4.11] 本轮暂不执行：" + c.name
                            + "，剩余约" + Math.max(1L, cooldownRemain / 1000L) + "秒");
                    continue;
                }

                String attemptKey = normalizeTaskAttemptKeyV46(c.name);
                int attempts = attemptsByTask.getOrDefault(attemptKey, 0);
                int maxAttempts = maxAttemptsForTaskV46(c.name, c.isClaimReward);

                if (attempts >= maxAttempts) {
                    diagnostic("[跳过] 已达到本轮尝试上限 "
                            + attempts + "/" + maxAttempts + "：" + c.name);
                    continue;
                }

                if (!isRepeatableTaskV46(c.name)
                        && executed.contains(c.key())) {
                    continue;
                }

                int priority = taskPriorityV46(c);
                if (priority < targetPriority) {
                    target = c;
                    targetPriority = priority;
                }
            }

            if (target == null) {
                if (!swipeUp(suPath)) {
                    sleepAbortableV48(500L);
                } else {
                    sleepAbortableV48(300L);
                }
                continue;
            }

            String targetAttemptKey = normalizeTaskAttemptKeyV46(target.name);
            attemptsByTask.put(
                    targetAttemptKey,
                    attemptsByTask.getOrDefault(targetAttemptKey, 0) + 1
            );
            executed.add(target.key());

            diagnostic("[任务] " + target.name
                    + " / claim=" + target.isClaimReward
                    + " / priority=" + targetPriority
                    + " / attempt=" + attemptsByTask.get(targetAttemptKey)
                    + " / profile=" + TaskProfileStoreV48.summary(target.name));

            TaskProfileStoreV48.recordAttempt(target.name);
            TaskRunContextV411 flow = new TaskRunContextV411(target.name);
            TaskVerificationSnapshotV411 before =
                    buildTaskVerificationSnapshotV411(taskOcr, target.name, target.isClaimReward);
            if ("UNKNOWN".equals(before.action)) {
                before = new TaskVerificationSnapshotV411(
                        before.task,
                        true,
                        target.isClaimReward ? "CLAIM" : "GO",
                        before.current,
                        before.total,
                        before.pageText
                );
            }
            flow.move(TaskRunStateV411.DISCOVERED, "before=" + before.describe());

            long taskStart = SystemClock.elapsedRealtime();
            flow.move(TaskRunStateV411.CLICKING, target.bounds());

            if (!clickBounds(suPath, xml, target.bounds())) {
                flow.move(TaskRunStateV411.FAILED, "click_failed");
                TaskProfileStoreV48.recordFailure(target.name, "click_failed");
                captureFailureDiagnosticV411(suPath, target.name, "click_failed");
                continue;
            }

            flow.move(TaskRunStateV411.EXECUTING,
                    target.isClaimReward ? "claim_reward" : "task_action");

            boolean executionReturned;
            if (target.isClaimReward) {
                executionReturned = paceSleepV415(120L, 240L) && !userAborted;
            } else {
                executionReturned = executeSingleTask(suPath, target.name);
            }

            if (userAborted) break;

            flow.move(TaskRunStateV411.VERIFYING,
                    "executionReturned=" + executionReturned);

            TaskVerificationResultV411 verification =
                    verifyTaskCompletionV411(
                            suPath,
                            target.name,
                            target.isClaimReward,
                            before,
                            executionReturned
                    );

            long elapsed = SystemClock.elapsedRealtime() - taskStart;

            if (verification.verified) {
                flow.move(TaskRunStateV411.VERIFIED, verification.reason);
                completed++;
                TaskProfileStoreV48.recordSuccess(target.name, elapsed);
                sendStatus(
                        target.name,
                        "SUCCESS",
                        (target.isClaimReward ? "领取奖励已验证：" : "任务完成已验证：")
                                + verification.reason
                );
            } else if (!userAborted && executionReturned) {
                flow.move(TaskRunStateV411.UNVERIFIED, verification.reason);
                TaskProfileStoreV48.recordUnverifiedV411(target.name, verification.reason);
                if (TaskProfileStoreV48.shouldCaptureDiagnosticV415(target.name)) {
                    captureFailureDiagnosticV411(
                            suPath, target.name, "unverified_" + verification.reason);
                } else {
                    diagnostic("[快节奏V4.15] 首次未验证，暂不阻塞保存诊断截图");
                }
                sendStatus(target.name, "FAILED",
                        "已返回，但任务进度未验证：" + verification.reason);
            } else if (!userAborted) {
                flow.move(TaskRunStateV411.FAILED, verification.reason);
                TaskProfileStoreV48.recordFailure(target.name, verification.reason);
                if (TaskProfileStoreV48.shouldCaptureDiagnosticV415(target.name)) {
                    captureFailureDiagnosticV411(
                            suPath, target.name, "failed_" + verification.reason);
                } else {
                    diagnostic("[快节奏V4.15] 首次失败，暂不阻塞保存诊断截图");
                }
                sendStatus(target.name, "FAILED", "任务执行失败：" + verification.reason);
            }

            if (userAborted) break;

            if (gameIncompleteHoldV421) {
                diagnostic("[游戏守卫V4.31] 小游戏无法安全退出，保留当前页面并停止本轮扫描");
                break;
            }

            // V4.16: TASK_PANEL itself is the hand-off signal. Once the previous
            // task has returned here, do not wait for a delayed progress animation
            // before selecting the next visible action. Keep only a tiny UI-settle
            // window; the next scan normally consumes the already-confirmed OCR.
            if (verification.verified) {
                paceSleepV415(20L, 60L);
            } else {
                paceSleepV415(30L, 90L);
            }
        }

        return completed;
    }

    private static int taskPriorityV46(TaskCandidate candidate) {
        if (candidate == null) return 99;
        if (candidate.isClaimReward) return 0;

        String name = candidate.name == null ? "" : candidate.name;
        if (containsAny(name, "视频", "倒计时", "通过首页访问闲鱼币")) {
            return 1;
        }
        if (containsAny(name, INTERNAL_BROWSE_KEYWORDS)) {
            return 2;
        }
        if (isBounceTask(name)) {
            return 3;
        }
        return 2;
    }

    private static boolean isRepeatableTaskV46(String name) {
        return name != null && containsAny(name, REPEATABLE_TASK_KEYWORDS);
    }

    private static int maxAttemptsForTaskV46(String name, boolean claim) {
        if (claim) return 1;
        if (name == null) return 1;
        if (containsAny(name, "指定频道")) return 10;
        if (containsAny(name, "视频")) return 4;
        if (containsAny(name, "浏览")) return 3;
        return 1;
    }

    private static String normalizeTaskAttemptKeyV46(String name) {
        if (name == null) return "未知任务";
        return name
                .replaceAll("\\(\\d+/\\d+\\)", "")
                .replaceAll("\\d+/\\d+", "")
                .replaceAll("\\s+", "")
                .trim();
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

    private static List<TaskCandidate> findTaskCandidatesOcrV45(
            ScreenOcr.Snapshot snapshot
    ) {
        List<TaskCandidate> result = new ArrayList<>();
        if (snapshot == null || snapshot.isEmpty()) return result;

        // 任务候选只允许来自“真正的右侧任务按钮”。
        // 这样广告里的“继续试玩才能领取奖励哦”即使被 OCR 识别，
        // 也不会再被当成领取按钮。
        for (ScreenOcr.Item action : snapshot.items) {
            if (!isValidTaskActionOcrV47(snapshot, action)) continue;

            String actionText = action.text == null ? "" : action.text.trim();
            String compact = actionText.replaceAll("\\s+", "");

            boolean isComplete = compact.contains("去完成");
            boolean isClaim = compact.contains("领取奖励")
                    || compact.contains("领取笑励");
            boolean isSign = "签到".equals(compact) || compact.endsWith("签到");

            String name;
            if (isSign) {
                name = "每日签到";
            } else {
                ScreenOcr.Item title = findNearestTaskTitleOcrV45(snapshot, action);
                name = title == null ? "未知任务" : normalizeTaskName(title.text);
            }

            if (name.isEmpty()) name = "未知任务";

            result.add(new TaskCandidate(
                    name,
                    action.boundsString(),
                    isClaim || isSign
            ));

            diagnostic("[OCR任务匹配] action=" + actionText
                    + " -> title=" + name
                    + " bounds=" + action.boundsString());
        }

        return result;
    }

    private static ScreenOcr.Item findNearestTaskTitleOcrV45(
            ScreenOcr.Snapshot snapshot,
            ScreenOcr.Item action
    ) {
        if (snapshot == null || action == null) return null;

        ScreenOcr.Item best = null;
        double bestScore = Double.MAX_VALUE;
        int actionCx = action.centerX();
        int actionCy = action.centerY();

        for (ScreenOcr.Item item : snapshot.items) {
            if (item == null || item == action) continue;
            String text = normalizeTaskName(item.text);
            if (text.isEmpty()) continue;

            if (containsAny(
                    text,
                    "去完成", "领取奖励", "高额奖励", "收益+10%",
                    "收益 +10%", "签到", "得骰子赚闲鱼币",
                    "闲鱼币", "骰子"
            )) {
                continue;
            }

            if (text.matches("^[+\\-0-9.%/() 次币元]+$")) continue;

            int cx = item.centerX();
            int cy = item.centerY();
            int dy = Math.abs(cy - actionCy);
            int dx = Math.abs(cx - actionCx);

            if (dy > 190) continue;
            if (cx >= actionCx - 60) continue;

            double score = dy * 5.0 + dx * 0.05;

            // 中文任务标题通常比奖励数字更长。
            if (text.length() < 4) score += 260.0;
            if (text.length() >= 6) score -= 80.0;

            if (score < bestScore) {
                bestScore = score;
                best = item;
            }
        }

        return best;
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


    // =========================
    // V4.11 verification/state/page layer
    // =========================

    private enum TaskRunStateV411 {
        DISCOVERED,
        CLICKING,
        EXECUTING,
        RETURNING,
        VERIFYING,
        VERIFIED,
        UNVERIFIED,
        FAILED,
        COOLDOWN
    }

    private static final class TaskRunContextV411 {
        final String task;
        TaskRunStateV411 state;

        TaskRunContextV411(String task) {
            this.task = task == null ? "未知任务" : task;
        }

        void move(TaskRunStateV411 next, String detail) {
            state = next;
            diagnostic("[状态机V4.11] " + task + " -> " + next
                    + (detail == null || detail.isEmpty() ? "" : " / " + trimForLog(detail, 260)));
            TaskProfileStoreV48.recordStateV411(task, next.name(), detail);
        }
    }

    private enum PageKindV411 {
        TASK_PANEL,
        COIN_HOME,
        MINE,
        XIANYU_HOME,
        AD_OR_INSTALL,
        FRUIT_PAIR_GAME,
        MAHJONG_PAIR_GAME,
        EXTERNAL_APP,
        MODULE_APP,
        UNKNOWN_XIANYU,
        UNKNOWN
    }

    private static final class PageProbeV411 {
        final String fg;
        final ScreenOcr.Snapshot ocr;
        final String text;
        final PageKindV411 kind;
        final String marker;

        PageProbeV411(
                String fg,
                ScreenOcr.Snapshot ocr,
                String text,
                PageKindV411 kind,
                String marker
        ) {
            this.fg = fg == null ? "" : fg;
            this.ocr = ocr == null ? ScreenOcr.Snapshot.empty() : ocr;
            this.text = text == null ? "" : text;
            this.kind = kind == null ? PageKindV411.UNKNOWN : kind;
            this.marker = marker == null ? "" : marker;
        }
    }

    private static PageProbeV411 probePageV411(String suPath, String reason) {
        String fg = getFg(suPath, false);

        if (MODULE_PACKAGE.equals(fg)) {
            PageProbeV411 r = new PageProbeV411(
                    fg, ScreenOcr.Snapshot.empty(), "",
                    PageKindV411.MODULE_APP, "helper");
            TaskProfileStoreV48.observePageV411(r.kind.name(), fg, r.marker);
            return r;
        }

        if (fg != null && !fg.isEmpty() && !TARGET_PACKAGE.equals(fg)) {
            PageProbeV411 r = new PageProbeV411(
                    fg, ScreenOcr.Snapshot.empty(), "",
                    PageKindV411.EXTERNAL_APP, "external");
            TaskProfileStoreV48.observePageV411(r.kind.name(), fg, r.marker);
            return r;
        }

        if (!TARGET_PACKAGE.equals(fg)) {
            PageProbeV411 r = new PageProbeV411(
                    fg, ScreenOcr.Snapshot.empty(), "",
                    PageKindV411.UNKNOWN, "fg_unknown");
            TaskProfileStoreV48.observePageV411(r.kind.name(), fg, r.marker);
            return r;
        }

        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "页面探针V4.11/" + reason);
        String text = combinedTextV45(null, ocr);
        PageKindV411 kind;

        if (looksLikeAdOrInstallPageV47(text)) {
            kind = PageKindV411.AD_OR_INSTALL;
        } else if (isTaskPageV45(null, ocr)) {
            kind = PageKindV411.TASK_PANEL;
        } else if (isCoinPageV45(null, ocr)) {
            // Strong COIN_HOME evidence must win over a mere game-card title
            // such as "点点消不停" shown on the coin homepage.
            kind = PageKindV411.COIN_HOME;
        } else if (isMinePageV45(null, ocr)) {
            kind = PageKindV411.MINE;
        } else if (isHomePageV45(null, ocr)) {
            kind = PageKindV411.XIANYU_HOME;
        } else if (FruitGameSolver.looksLikeFruitGame(text)) {
            kind = PageKindV411.FRUIT_PAIR_GAME;
        } else if (MahjongGameSolver.looksLikeMahjongPairGame(text)) {
            kind = PageKindV411.MAHJONG_PAIR_GAME;
        } else {
            kind = PageKindV411.UNKNOWN_XIANYU;
        }

        String pageMarker = pageMarkerV411(text);
        if (kind == PageKindV411.TASK_PANEL && ocr != null && !ocr.isEmpty()) {
            lastTaskPanelOcrV415 = ocr;
            lastTaskPanelOcrAtV415 = SystemClock.elapsedRealtime();
        }
        TaskProfileStoreV48.observePageV411(kind.name(), fg, pageMarker);
        diagnostic("[页面特征V4.11] " + reason + " -> " + kind
                + (pageMarker.isEmpty() ? "" : " / " + pageMarker));

        return new PageProbeV411(fg, ocr, text, kind, pageMarker);
    }

    private static String pageMarkerV411(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] markers = {
                "得骰子赚闲鱼币", "继续试玩", "正在跳转", "打开淘宝",
                "闲鱼币", "我的收藏", "历史浏览", "闲鱼", "签到",
                "领取奖励", "去完成", "立即下载", "安装",
                "剩余", "消除", "打乱", "点击麻将对", "麻将对"
        };
        List<String> found = new ArrayList<>();
        for (String m : markers) {
            if (text.contains(m) && !found.contains(m)) found.add(m);
            if (found.size() >= 3) break;
        }
        if (found.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : found) {
            if (sb.length() > 0) sb.append('+');
            sb.append(s);
        }
        return sb.toString();
    }

    private static final class TaskVerificationSnapshotV411 {
        final String task;
        final boolean present;
        final String action;
        final int current;
        final int total;
        final String pageText;

        TaskVerificationSnapshotV411(
                String task,
                boolean present,
                String action,
                int current,
                int total,
                String pageText
        ) {
            this.task = task == null ? "未知任务" : task;
            this.present = present;
            this.action = action == null ? "UNKNOWN" : action;
            this.current = current;
            this.total = total;
            this.pageText = pageText == null ? "" : pageText;
        }

        String describe() {
            return "present=" + present
                    + ",action=" + action
                    + (current >= 0 && total > 0 ? ",progress=" + current + "/" + total : "");
        }
    }

    private static final class TaskVerificationResultV411 {
        final boolean verified;
        final String reason;
        final TaskVerificationSnapshotV411 after;

        TaskVerificationResultV411(
                boolean verified,
                String reason,
                TaskVerificationSnapshotV411 after
        ) {
            this.verified = verified;
            this.reason = reason == null ? "" : reason;
            this.after = after;
        }
    }

    private static TaskVerificationSnapshotV411 buildTaskVerificationSnapshotV411(
            ScreenOcr.Snapshot snapshot,
            String taskName,
            boolean knownClaim
    ) {
        if (snapshot == null || snapshot.isEmpty
