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
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * XianyuTaskExecutor V4.9
 *
 * 重点修复：
 * 1. dumpsys 前台解析不再把“未知”误判为模块 App。
 * 2. 只有明确检测到 com.zhinibgdu.xianyu 才触发用户中止。
 * 3. uiautomator 第一次失败自动重试。
 * 4. Root 命令统一检查退出码和超时。
 * 5. 任务由前台 Service 承载进程生命周期，不再依赖 Xposed/广播注入。
 * 6. 特征库按“任务 + 事件类型 + 原因/恢复方式”去重；同一案例只保留一条记录。
 * 7. V4.9 将历史经验接入执行决策：等待时间、返回策略、失败退避和策略日志。
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

    private static final String PROFILE_PREFS_V48 = "xianyu_task_profiles_v48";

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
        physicalTouchDetected = false;
        physicalTouchAt = 0L;

        diagnostic(
                "========== 闲鱼任务开始 · V4.8.3 =========="
        );

        diagnostic(
                "💡 想中途停止：切回“闲鱼定时助手”即可"
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

                                stopPhysicalTouchMonitorV48();
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
                        "XianyuTask-V48"
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

        // V4.8.3: compact the local feature library once per run.
        // Duplicate case signatures are merged so each identical case keeps
        // exactly one record while its occurrence counter is retained.
        TaskProfileStoreV48.compactUniqueCases();

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

        diagnostic(
                "[导航] 开始：首页 → 我的 → 闲鱼币 → 赚骰子 → 任务面板"
        );

        if (!ensureFg(suPath)) {
            diagnostic("[导航] 闲鱼没有在前台");
            return false;
        }

        String xml = recoverNavigationContextV45(suPath);
        if (xml == null) {
            diagnostic("[导航] 无法恢复到可识别的闲鱼页面");
            return false;
        }

        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "导航初始页");

        if (isTaskPageV45(xml, ocr)) {
            diagnostic("[导航] 当前已经在任务面板");
            return true;
        }

        if (!isMinePageV45(xml, ocr)
                && !isCoinPageV45(xml, ocr)) {

            if (!isHomePageV45(xml, ocr)) {
                diagnostic("[导航] 当前不是首页/我的/闲鱼币，拒绝盲点底部坐标");
                return false;
            }

            diagnostic(
                    "[导航] 当前在首页，准备进入‘我的’"
            );

            boolean clickedMine =
                    clickTextAnyAllowBottom(
                            suPath,
                            xml,
                            "我的",
                            "我的闲鱼",
                            "个人中心"
                    );

            if (!clickedMine) {
                clickedMine = clickOcrTextAnyV45(
                        suPath,
                        ocr,
                        true,
                        "我的",
                        "我的闲鱼",
                        "个人中心"
                );
            }

            if (!clickedMine) {
                diagnostic(
                        "[导航] XML/OCR均未点击到‘我的’，使用首页比例坐标兜底"
                );

                clickedMine =
                        tapByRatioV43(
                                suPath,
                                0.885f,
                                0.950f,
                                "首页-我的",
                                true
                        );
            }

            if (!clickedMine) {
                diagnostic("❌ 无法点击‘我的’");
                logVisibleTexts(xml);
                return false;
            }

            diagnostic("[导航] ✅ 已点击‘我的’");

            if (!waitMinePageV45(suPath, 9000L)) {
                diagnostic("⚠️ 点击‘我的’后仍未识别到个人页");
            }

            xml = dumpUi(suPath);
            ocr = captureOcrV45(suPath, "我的页");

            if (xml == null && ocr.isEmpty()) {
                return false;
            }

        } else if (isMinePageV45(xml, ocr)) {
            diagnostic("[导航] 当前已经在‘我的’页面");
        } else {
            diagnostic("[导航] 当前已经进入闲鱼币页面");
        }

        if (!isCoinPageV45(xml, ocr)
                && !isTaskPageV45(xml, ocr)) {

            if (!isMinePageV45(xml, ocr)) {
                diagnostic("[导航] 未确认处于‘我的’页，拒绝点击闲鱼币坐标");
                return false;
            }

            diagnostic("[导航] 开始寻找‘闲鱼币’入口");

            boolean enteredCoin = false;

            for (int attempt = 1; attempt <= 3; attempt++) {

                if (!ensureFg(suPath)) return false;

                xml = dumpUi(suPath);
                ocr = captureOcrV45(suPath, "寻找闲鱼币#" + attempt);

                if (isTaskPageV45(xml, ocr)) return true;
                if (isCoinPageV45(xml, ocr)) {
                    enteredCoin = true;
                    break;
                }

                boolean clickedCoin = false;

                if (xml != null) {
                    clickedCoin = clickTextAny(
                            suPath,
                            xml,
                            "闲鱼币",
                            "闲鱼币中心",
                            "赚闲鱼币",
                            "领闲鱼币"
                    );
                }

                if (!clickedCoin) {
                    clickedCoin = clickOcrTextAnyV45(
                            suPath,
                            ocr,
                            false,
                            "闲鱼币",
                            "闲鱼币中心",
                            "赚闲鱼币",
                            "领闲鱼币"
                    );
                }

                if (!clickedCoin && isMinePageV45(xml, ocr)) {
                    diagnostic("[导航] XML/OCR未点击到闲鱼币，使用个人页比例坐标");
                    clickedCoin = tapByRatioV43(
                            suPath,
                            0.20f,
                            0.79f,
                            "我的页-闲鱼币",
                            false
                    );
                }

                if (clickedCoin) {
                    diagnostic("[导航] ✅ 已点击闲鱼币，第" + attempt + "次");
                    enteredCoin = waitCoinPageV45(suPath, 12000L);
                    if (enteredCoin) break;
                }

                SystemClock.sleep(700L);
            }

            if (!enteredCoin) {
                xml = dumpUi(suPath);
                ocr = captureOcrV45(suPath, "闲鱼币失败页");
                diagnostic("❌ 没有进入闲鱼币主页");
                if (!ocr.isEmpty()) {
                    diagnostic("[OCR文本] " + trimForLog(ocr.fullText, 1500));
                } else {
                    logVisibleTexts(xml);
                }
                return false;
            }

            xml = dumpUi(suPath);
            ocr = captureOcrV45(suPath, "闲鱼币主页");
        }

        if (isTaskPageV45(xml, ocr)) return true;

        if (!isCoinPageV45(xml, ocr)) {
            diagnostic("[导航] 未确认闲鱼币主页，拒绝盲点‘赚骰子’");
            return false;
        }

        diagnostic("[导航] 已进入闲鱼币，准备打开‘赚骰子’");

        for (int attempt = 1; attempt <= 3; attempt++) {

            if (!ensureFg(suPath)) return false;

            xml = dumpUi(suPath);
            ocr = captureOcrV45(suPath, "赚骰子#" + attempt);

            if (isTaskPageV45(xml, ocr)) {
                diagnostic("[导航] ✅ 已进入任务面板");
                return true;
            }

            boolean clickedEarn = false;
            if (xml != null) {
                clickedEarn = clickTextAny(suPath, xml, "赚骰子");
            }

            if (!clickedEarn) {
                clickedEarn = clickOcrTextAnyV45(
                        suPath,
                        ocr,
                        false,
                        "赚骰子"
                );
            }

            if (!clickedEarn) {
                diagnostic("[导航] XML/OCR未识别到‘赚骰子’，使用已确认闲鱼币页的比例坐标兜底");
                clickedEarn = tapByRatioV43(
                        suPath,
                        0.735f,
                        0.495f,
                        "闲鱼币-赚骰子",
                        false
                );
            }

            if (clickedEarn) {
                diagnostic("[导航] 已点击‘赚骰子’，等待任务弹窗");

                if (waitTaskPageV45(suPath, 9000L)) {
                    diagnostic("[导航] ✅ ‘得骰子赚闲鱼币’任务面板打开成功");
                    return true;
                }

                diagnostic("[导航] 点击赚骰子后暂时没识别到任务面板");
            }

            SystemClock.sleep(800L);
        }

        ScreenOcr.Snapshot finalOcr = captureOcrV45(suPath, "任务面板失败页");
        diagnostic("❌ 无法打开‘得骰子赚闲鱼币’任务面板");
        if (!finalOcr.isEmpty()) {
            diagnostic("[OCR文本] " + trimForLog(finalOcr.fullText, 1500));
        }
        return false;
    }

    /**
     * 在执行坐标兜底前先把闲鱼恢复到一个已知页面。
     * 这用于修复“红果免费短剧应用详情”之类的闲鱼内嵌页面被误当成首页，
     * 然后盲点右下角坐标的问题。
     */
    private static String recoverNavigationContextV45(String suPath) {

        for (int i = 0; i < 5; i++) {
            if (!ensureFg(suPath)) return null;

            String xml = dumpUi(suPath);
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "页面恢复#" + (i + 1));

            if (isTaskPageV45(xml, ocr)
                    || isCoinPageV45(xml, ocr)
                    || isMinePageV45(xml, ocr)
                    || isHomePageV45(xml, ocr)) {
                return xml == null ? "" : xml;
            }

            String text = combinedTextV45(xml, ocr);
            diagnostic("[导航恢复] 未知闲鱼子页面，返回上一层："
                    + trimForLog(text, 300));

            rootWithPath(suPath, "input keyevent KEYCODE_BACK");
            SystemClock.sleep(900L);
        }

        diagnostic("[导航恢复] 连续返回仍无法识别，重启闲鱼到主页面");
        rootWithPath(
                suPath,
                "am force-stop " + TARGET_PACKAGE
                        + "; sleep 1; am start -n " + TARGET_MAIN_ACTIVITY
        );

        if (!waitFg(suPath, 15000L)) return null;
        SystemClock.sleep(1800L);

        String xml = dumpUi(suPath);
        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "重启后的闲鱼");

        if (isTaskPageV45(xml, ocr)
                || isCoinPageV45(xml, ocr)
                || isMinePageV45(xml, ocr)
                || isHomePageV45(xml, ocr)) {
            return xml == null ? "" : xml;
        }

        diagnostic("[导航恢复] 重启后仍无法识别闲鱼主页面");
        return null;
    }

    private static ScreenOcr.Snapshot captureOcrV45(
            String suPath,
            String reason
    ) {
        ScreenOcr.Snapshot snapshot = ScreenOcr.capture(lastContext, suPath);
        if (snapshot != null && !snapshot.isEmpty()) {
            diagnostic("[OCR] " + reason + "："
                    + trimForLog(snapshot.fullText, 500));
            return snapshot;
        }
        return ScreenOcr.Snapshot.empty();
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
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            String xml = dumpUi(suPath);
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待我的页");
            if (isMinePageV45(xml, ocr)
                    || isCoinPageV45(xml, ocr)
                    || isTaskPageV45(xml, ocr)) {
                return true;
            }
            SystemClock.sleep(500L);
        }
        return false;
    }

    private static boolean waitCoinPageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            String xml = dumpUi(suPath);
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待闲鱼币页");
            if (isCoinPageV45(xml, ocr)) {
                diagnostic("[导航] ✅ 已确认闲鱼币主页");
                return true;
            }
            if (isTaskPageV45(xml, ocr)) return true;
            SystemClock.sleep(500L);
        }
        return false;
    }

    private static boolean waitTaskPageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            String xml = dumpUi(suPath);
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待任务面板");
            if (isTaskPageV45(xml, ocr)) return true;
            SystemClock.sleep(500L);
        }
        return false;
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

            taskOcr = captureOcrV45(suPath, "快速扫描任务页");
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
            long taskStart = SystemClock.elapsedRealtime();

            if (!clickBounds(suPath, xml, target.bounds())) {
                TaskProfileStoreV48.recordFailure(target.name, "click_failed");
                continue;
            }

            boolean success;

            if (target.isClaimReward) {
                success = sleepAbortableV48(420L) && !userAborted;
            } else {
                success = executeSingleTask(suPath, target.name);
            }

            long elapsed = SystemClock.elapsedRealtime() - taskStart;

            if (success && !userAborted) {
                completed++;
                TaskProfileStoreV48.recordSuccess(target.name, elapsed);
                sendStatus(
                        target.name,
                        "SUCCESS",
                        target.isClaimReward
                                ? "领取奖励成功"
                                : "任务执行完成"
                );
            } else if (!userAborted) {
                TaskProfileStoreV48.recordFailure(target.name, "task_not_confirmed");
                sendStatus(target.name, "FAILED", "任务未确认完成");
            }

            if (userAborted) break;

            // V4.8 removes the old unconditional dumpUi/closePopup pass here.
            // The next OCR scan handles popups using the already captured frame.
            sleepAbortableV48(320L);
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

    private static boolean executeSingleTask(
            String suPath,
            String taskName
    ) {

        diagnostic("[执行] " + taskName);

        boolean isVideo = containsAny(taskName, "视频", "观看", "看15秒");
        boolean isSearch = containsAny(taskName, "搜一搜", "搜索", "搜商品");
        boolean isBounce = isBounceTask(taskName);
        boolean isInternalBrowse = !isBounce
                && containsAny(taskName, INTERNAL_BROWSE_KEYWORDS);

        if (isVideo) {
            return executeVideoTaskPolling(suPath, taskName);
        }

        long defaultWaitMs = isSearch
                ? 6500L
                : isBounce
                ? 8500L
                : isInternalBrowse
                ? 9000L
                : 7000L;

        TaskProfileStoreV48.StrategyV49 strategy =
                TaskProfileStoreV48.chooseStrategyV49(taskName, defaultWaitMs, isBounce, false);
        long waitMs = strategy.waitMs;
        diagnostic("[策略V4.9] " + strategy.describe());

        if (isBounce) {
            inBounceTask = true;
            diagnostic("[执行] 允许预期外部 App 跳转：" + taskName);
        }

        long started = SystemClock.elapsedRealtime();

        try {
            long nextBrowseSwipe = 2800L;
            long nextFgCheck = 0L;

            while (SystemClock.elapsedRealtime() - started < waitMs) {
                if (!sleepAbortableV48(250L)) return false;

                long elapsed = SystemClock.elapsedRealtime() - started;

                if (elapsed >= nextFgCheck) {
                    String fg = getFg(suPath, false);
                    nextFgCheck = elapsed + 750L;

                    if (MODULE_PACKAGE.equals(fg)) {
                        markUserAbortV48("检测到用户切回闲鱼定时助手");
                        return false;
                    }
                }

                if (isInternalBrowse && elapsed >= nextBrowseSwipe) {
                    String fg = getFg(suPath, false);
                    if (MODULE_PACKAGE.equals(fg)) {
                        markUserAbortV48("浏览任务期间用户接管");
                        return false;
                    }
                    if (TARGET_PACKAGE.equals(fg)) {
                        rootWithPath(
                                suPath,
                                "input swipe 720 2250 720 1050 420"
                        );
                        diagnostic("[执行] 内部浏览滑动，elapsed=" + elapsed + "ms");
                    }
                    nextBrowseSwipe += 3000L;
                }
            }

            if (userAborted) return false;

            String fg = getFg(suPath, false);
            diagnostic("[执行] 前台=" + printableFg(fg));

            // Critical V4.8 fix: never relaunch Xianyu after the user has
            // explicitly switched to the helper app.
            if (MODULE_PACKAGE.equals(fg)) {
                markUserAbortV48("任务等待结束时检测到用户切回助手");
                return false;
            }

            if (!TARGET_PACKAGE.equals(fg)) {
                diagnostic("[执行] 任务结束后快速返回闲鱼");
                TaskProfileStoreV48.recordRecovery(taskName, "return_from:" + printableFg(fg));
                rootWithPath(suPath, "am start -n " + TARGET_MAIN_ACTIVITY);
                if (!waitFg(suPath, 4500L)) {
                    TaskProfileStoreV48.recordFailure(taskName, "return_to_xianyu_failed");
                    return false;
                }
                sleepAbortableV48(450L);
            }

            if (isSearch) {
                rootWithPath(suPath, "input keyevent 4");
                if (!sleepAbortableV48(450L)) return false;
            }

            // OCR-first completion check. uiautomator is only fallback now.
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "任务完成快速确认");
            String combined = combinedTextV45(null, ocr);

            if (closePopupFromSnapshotV48(suPath, ocr)) {
                return true;
            }

            if (containsAny(
                    combined,
                    "已完成", "任务完成", "完成任务", "已领取"
            )) {
                return true;
            }

            if (isTaskPageV45(null, ocr)) {
                return true;
            }

            String xml = dumpUi(suPath);
            if (xml != null && containsAny(
                    xml,
                    "已完成", "任务完成", "完成任务", "已领取",
                    "得骰子赚闲鱼币"
            )) {
                return true;
            }

            // Returning to Xianyu is treated as execution completion; the next
            // scan verifies whether progress/reward changed.
            return TARGET_PACKAGE.equals(getFg(suPath, false));

        } finally {
            if (isBounce) inBounceTask = false;
        }
    }

    private static boolean executeVideoTaskPolling(
            String suPath,
            String taskName
    ) {

        TaskProfileStoreV48.StrategyV49 videoStrategy =
                TaskProfileStoreV48.chooseStrategyV49(taskName, 22000L, true, true);
        long videoTimeout = Math.max(35000L, Math.min(55000L, videoStrategy.waitMs + 22000L));
        diagnostic("[视频策略V4.9] " + videoStrategy.describe()
                + " / timeout=" + videoTimeout + "ms");

        long start = SystemClock.elapsedRealtime();
        boolean sawAd = false;
        boolean attemptedReturn = false;
        boolean doubleSwipeDone = false;
        int loop = 0;

        while (SystemClock.elapsedRealtime() - start < videoTimeout) {

            if (!sleepAbortableV48(1500L)) return false;
            loop++;

            String fg = getFg(suPath, false);

            if (MODULE_PACKAGE.equals(fg)) {
                markUserAbortV48("视频任务期间用户切回助手");
                return false;
            }

            if (!TARGET_PACKAGE.equals(fg)) {
                diagnostic("[视频] 当前离开闲鱼：" + printableFg(fg));
                long elapsed = SystemClock.elapsedRealtime() - start;

                if (elapsed >= 12000L) {
                    TaskProfileStoreV48.recordRecovery(taskName, "video_external:" + printableFg(fg));
                    if (recoverToXianyuTaskPanelV47(suPath, "视频外部跳转恢复")) {
                        attemptedReturn = true;
                        if (!doubleSwipeDone) {
                            doubleSwipeDone = backGestureTwiceV481(suPath, "视频返回闲鱼连续侧滑");
                            if (doubleSwipeDone) {
                                TaskProfileStoreV48.setReturnSwipes(taskName, 2);
                            }
                        }
                        return true;
                    }
                }
                continue;
            }

            // OCR-only on most polls; this removes the old dumpUi + OCR pair.
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "视频快速轮询");
            String combined = combinedTextV45(null, ocr);

            if (looksLikeAdOrInstallPageV47(combined)) {
                sawAd = true;
                long elapsed = SystemClock.elapsedRealtime() - start;
                diagnostic("[视频] 检测到广告/试玩页，elapsed=" + elapsed + "ms");

                if (elapsed >= 18000L && !attemptedReturn) {
                    rootWithPath(suPath, "input keyevent 4");
                    sleepAbortableV48(650L);
                    attemptedReturn = true;
                }
                continue;
            }

            if (isTaskPageV45(null, ocr)) {
                if (sawAd && !doubleSwipeDone) {
                    doubleSwipeDone = backGestureTwiceV481(suPath, "视频结束后连续侧滑返回");
                    if (doubleSwipeDone) {
                        TaskProfileStoreV48.setReturnSwipes(taskName, 2);
                    }
                }
                diagnostic("[视频] ✅ 已回到真实任务面板");
                return true;
            }

            // Every fourth OCR poll, allow one XML fallback for hard pages.
            if (loop % 4 == 0) {
                String xml = dumpUi(suPath);
                if (isTaskPageV45(xml, ocr)) {
                    if (sawAd && !doubleSwipeDone) {
                        doubleSwipeDone = backGestureTwiceV481(suPath, "视频XML确认后连续侧滑返回");
                        if (doubleSwipeDone) {
                            TaskProfileStoreV48.setReturnSwipes(taskName, 2);
                        }
                    }
                    return true;
                }
            }

            if (sawAd
                    && SystemClock.elapsedRealtime() - start >= 24000L
                    && !attemptedReturn) {
                if (recoverToXianyuTaskPanelV47(suPath, "视频超时恢复")) {
                    attemptedReturn = true;
                    if (!doubleSwipeDone) {
                        doubleSwipeDone = backGestureTwiceV481(suPath, "视频超时恢复连续侧滑返回");
                        if (doubleSwipeDone) {
                            TaskProfileStoreV48.setReturnSwipes(taskName, 2);
                        }
                    }
                    return true;
                }
            }
        }

        if (recoverToXianyuTaskPanelV47(suPath, "视频55秒最终恢复")) {
            if (!doubleSwipeDone) {
                doubleSwipeDone = backGestureTwiceV481(suPath, "视频最终恢复连续侧滑返回");
                if (doubleSwipeDone) {
                    TaskProfileStoreV48.setReturnSwipes(taskName, 2);
                }
            }
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "视频最终确认");
            if (isTaskPageV45(null, ocr)) return true;
        }

        TaskProfileStoreV48.recordFailure(taskName, "video_timeout");
        diagnostic("[视频] 超时 " + videoTimeout + "ms，未确认完成");
        return false;
    }

    private static boolean recoverToXianyuTaskPanelV47(
            String suPath,
            String reason
    ) {
        if (userAborted) return false;
        diagnostic("[恢复] " + reason);

        String fg = getFg(suPath, false);
        if (MODULE_PACKAGE.equals(fg)) {
            markUserAbortV48("恢复过程中检测到用户切回助手");
            return false;
        }

        if (!TARGET_PACKAGE.equals(fg)) {
            rootWithPath(suPath, "am start -n " + TARGET_MAIN_ACTIVITY);
            if (!waitFg(suPath, 4500L)) {
                diagnostic("[恢复] 无法把闲鱼拉回前台");
                return false;
            }
            sleepAbortableV48(350L);
        }

        // Fast OCR check first.
        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "恢复快速检查");
        if (isTaskPageV45(null, ocr)) return true;

        // If we are on a system jump/open-app dialog, Back is faster than a
        // full navigation rebuild.
        if (containsAny(ocr.fullText, "正在跳转", "打开淘宝", "打开支付宝", "取消")) {
            rootWithPath(suPath, "input keyevent 4");
            sleepAbortableV48(450L);
            ScreenOcr.Snapshot retry = captureOcrV45(suPath, "跳转弹窗返回后");
            if (isTaskPageV45(null, retry)) return true;
        }

        String xml = dumpUi(suPath);
        if (isTaskPageV45(xml, ocr)) return true;

        TaskProfileStoreV48.recordRecovery("__NAV__", reason);
        return enterViaMineCoin(suPath);
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
            markUserAbortV48("明确检测到用户切回模块 App");
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

        if (isTransientForegroundV46(fg)) {
            diagnostic("⚠️ 检测到系统/桌面瞬时前台，短暂重试：" + fg);
            for (int i = 0; i < 4; i++) {
                SystemClock.sleep(350L);
                String retry = getFg(suPath, false);
                if (TARGET_PACKAGE.equals(retry)) return true;
                if (MODULE_PACKAGE.equals(retry)) {
                    userAborted = true;
                    diagnostic("🛑 重试确认用户切回模块 App");
                    return false;
                }
                if (!isTransientForegroundV46(retry)
                        && retry != null
                        && !retry.isEmpty()) {
                    fg = retry;
                    break;
                }
            }
        }

        // V4.6：停止手势统一为“切回闲鱼定时助手”。
        // 其它 App 可能是任务要求的跳转，或者系统短暂切换；不再直接把整个任务标记为用户中止。
        diagnostic(
                "⚠️ 当前不是闲鱼前台，不判定为用户中止："
                        + fg
        );

        return false;
    }

    private static boolean isTransientForegroundV46(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if ("android".equals(pkg)) {
            return true;
        }

        return containsAny(
                pkg,
                "launcher",
                "systemui",
                "permissioncontroller",
                "packageinstaller",
                "resolver",
                "chooser"
        );
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
                        + "uiautomator dump --compressed "
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

        return isRealTaskPage(
                xml
        );
    }

    private static boolean clickText(
            String suPath,
            String xml,
            String text
    ) {
        return clickText(
                suPath,
                xml,
                text,
                false
        );
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

        int[] rect = parseBounds(bounds);
        if (rect == null) return false;

        int left = rect[0];
        int top = rect[1];
        int right = rect[2];
        int bottom = rect[3];

        int x = (left + right) / 2;
        int y = (top + bottom) / 2;

        // V4.8 bounded tap jitter: only a tiny offset inside the already
        // detected target rectangle. This is for edge/mis-tap robustness,
        // not for bypassing platform controls.
        int safeXJitter = Math.max(0, Math.min(10, (right - left) / 8));
        int safeYJitter = Math.max(0, Math.min(8, (bottom - top) / 8));
        if (safeXJitter > 0) {
            x += ThreadLocalRandom.current().nextInt(-safeXJitter, safeXJitter + 1);
        }
        if (safeYJitter > 0) {
            y += ThreadLocalRandom.current().nextInt(-safeYJitter, safeYJitter + 1);
        }
        x = Math.max(left + 1, Math.min(right - 1, x));
        y = Math.max(top + 1, Math.min(bottom - 1, y));

        int height = getScreenHeight(suPath);
        int gestureZone = height > 0
                ? Math.max(60, Math.round(height * 0.03f))
                : 0;

        if (height > 0 && y > height - gestureZone) {
            if (!allowBottomGestureZone) {
                diagnostic("[点击] 位于底部手势区域，取消");
                return false;
            }
            diagnostic("[点击] 底部导航项，允许点击：y=" + y + "/" + height);
        }

        if (x < 1 || y < 1 || x > 2000 || y > 4000) return false;
        if (!ensureFg(suPath)) return false;
        if (userAborted) return false;

        diagnostic("[点击] 安全抖动坐标=" + x + "," + y);
        RootResult r = rootWithPath(suPath, "input tap " + x + " " + y);
        if (r.exitCode != 0) return false;

        sleepAbortableV48(320L);
        return !userAborted;
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

    private static boolean closePopupFromSnapshotV48(
            String suPath,
            ScreenOcr.Snapshot snapshot
    ) {
        if (snapshot == null || snapshot.isEmpty()) return false;

        String[] popupTexts = {
                "开心收下", "立即领取", "收下", "我知道了", "知道啦", "关闭"
        };
        for (String t : popupTexts) {
            if (clickOcrTextAnyV45(suPath, snapshot, false, t)) {
                diagnostic("[弹窗] OCR快速关闭：" + t);
                return true;
            }
        }
        return false;
    }

    /**
     * V4.8.1：广告/外部页退出使用“侧边返回手势”，绝不使用上滑。
     *
     * 用户设备使用全面屏手势：
     * - 左/右侧边向屏幕内滑 = 返回
     * - 底部向上滑 = 回桌面
     *
     * 因此这里连续执行两次左右侧边返回手势。
     * 第一次从左侧边缘向右滑，第二次从右侧边缘向左滑。
     */
    private static boolean backGestureTwiceV481(
            String suPath,
            String reason
    ) {
        if (userAborted) return false;

        int[] screen = getScreenSizeV43(suPath);
        if (screen == null) return false;

        int width = screen[0];
        int height = screen[1];

        // 起点贴近左右边缘，但避开最顶/最底部系统区域。
        int y1 = Math.round(height * 0.55f);
        int y2 = Math.round(height * 0.62f);

        int leftStart = 1;
        int leftEnd = Math.round(width * 0.24f);

        int rightStart = Math.max(1, width - 2);
        int rightEnd = Math.round(width * 0.76f);

        diagnostic("[侧滑返回×2] " + reason);

        if (!ensureFg(suPath) || userAborted) return false;

        RootResult first = rootWithPath(
                suPath,
                "input swipe "
                        + leftStart + " " + y1 + " "
                        + leftEnd + " " + y1 + " 260"
        );

        if (first.exitCode != 0) return false;
        diagnostic("[侧滑返回] 第1次完成（最左边缘 x=" + leftStart + " → " + leftEnd + "）");

        if (!sleepAbortableV48(360L)) return false;

        // 第一次返回后若用户切回助手，立即停止，绝不继续恢复闲鱼。
        String fgAfterFirst = getFg(suPath, false);
        if (MODULE_PACKAGE.equals(fgAfterFirst)) {
            markUserAbortV48("侧滑返回后检测到用户切回助手");
            return false;
        }

        // 第二次返回不调用 ensureFg()，因为广告的中间层可能短暂显示为
        // android/system package；直接完成第二次系统返回手势更可靠。
        RootResult second = rootWithPath(
                suPath,
                "input swipe "
                        + rightStart + " " + y2 + " "
                        + rightEnd + " " + y2 + " 260"
        );

        if (second.exitCode != 0) return false;
        diagnostic("[侧滑返回] 第2次完成（最右边缘 x=" + rightStart + " → " + rightEnd + "）");

        return sleepAbortableV48(500L);
    }

    private static boolean sleepAbortableV48(long millis) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, millis);
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted || physicalTouchDetected) return false;
            long remain = end - SystemClock.elapsedRealtime();
            SystemClock.sleep(Math.min(120L, Math.max(1L, remain)));
        }
        return !userAborted && !physicalTouchDetected;
    }

    private static void markUserAbortV48(String reason) {
        if (!userAborted) {
            userAborted = true;
            diagnostic("🛑 人工接管，立即停止：" + reason);
            TaskProfileStoreV48.recordFailure("__GLOBAL__", "manual_takeover:" + reason);
        }
    }

    private static void startPhysicalTouchMonitorV48(String suPath) {
        stopPhysicalTouchMonitorV48();
        physicalTouchDetected = false;
        physicalTouchAt = 0L;

        String device = findTouchscreenDeviceV48(suPath);
        if (device == null || device.isEmpty()) {
            diagnostic("[人工检测] 未识别到物理触摸设备，继续使用前台/状态检测");
            return;
        }

        physicalTouchDevice = device;
        diagnostic("[人工检测] 监听物理触摸设备：" + device);

        Thread thread = new Thread(() -> {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{
                        suPath,
                        "-c",
                        "getevent -lt " + device + " 2>/dev/null"
                });
                touchMonitorProcess = process;

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                );

                String line;
                while (running && !userAborted && (line = reader.readLine()) != null) {
                    String u = line.toUpperCase(Locale.US);
                    boolean touchDown =
                            (u.contains("BTN_TOUCH")
                                    && (u.contains("DOWN") || u.endsWith("00000001")))
                                    || (u.contains("ABS_MT_TRACKING_ID")
                                    && !u.endsWith("FFFFFFFF")
                                    && !u.endsWith("-1"));

                    if (touchDown) {
                        physicalTouchDetected = true;
                        physicalTouchAt = SystemClock.elapsedRealtime();
                        markUserAbortV48("检测到真实手指触摸屏幕");
                        break;
                    }
                }
            } catch (Throwable t) {
                if (running && !userAborted) {
                    diagnostic("[人工检测] 物理触摸监听退出：" + t);
                }
            } finally {
                if (process != null) {
                    try { process.destroy(); } catch (Throwable ignored) { }
                }
            }
        }, "XianyuTouchGuard-V48");

        thread.setDaemon(true);
        touchMonitorThread = thread;
        thread.start();
    }

    private static void stopPhysicalTouchMonitorV48() {
        Process p = touchMonitorProcess;
        touchMonitorProcess = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) { }
            try { p.destroyForcibly(); } catch (Throwable ignored) { }
        }
        touchMonitorThread = null;
    }

    private static String findTouchscreenDeviceV48(String suPath) {
        RootResult r = rootWithPath(suPath, "getevent -pl 2>/dev/null");
        if (r.exitCode != 0 || r.stdout == null || r.stdout.isEmpty()) return null;

        String[] lines = r.stdout.split("\\r?\\n");
        String currentDevice = null;
        StringBuilder section = new StringBuilder();
        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "add device END";
            if (line.startsWith("add device")) {
                if (currentDevice != null) {
                    int score = touchscreenSectionScoreV48(section.toString());
                    if (score > bestScore) {
                        bestScore = score;
                        best = currentDevice;
                    }
                }
                currentDevice = null;
                section.setLength(0);
                Matcher m = Pattern.compile("(/dev/input/event\\d+)").matcher(line);
                if (m.find()) currentDevice = m.group(1);
            }
            if (currentDevice != null) section.append(line).append('\n');
        }

        return bestScore >= 4 ? best : null;
    }

    private static int touchscreenSectionScoreV48(String section) {
        if (section == null) return -100;
        String s = section.toLowerCase(Locale.US);
        int score = 0;
        if (s.contains("touchscreen")) score += 6;
        if (s.contains("sec_touch")) score += 6;
        if (s.contains("tsp")) score += 4;
        if (s.contains("touch")) score += 3;
        if (s.contains("abs_mt_position_x") || s.contains("0035")) score += 2;
        if (s.contains("abs_mt_position_y") || s.contains("0036")) score += 2;
        if (s.contains("btn_touch") || s.contains("014a")) score += 1;
        if (s.contains("fingerprint")) score -= 5;
        if (s.contains("volume") || s.contains("gpio_keys")) score -= 5;
        return score;
    }

    private static final class TaskProfileStoreV48 {

        /*
         * V4.8.3 unique-case index.
         *
         * A "case" is not every occurrence. It is the canonical combination:
         *   normalized task name + event type + event detail
         *
         * Therefore the same failure/recovery/success pattern is represented by
         * one record only. Re-occurrence only updates count/last_at.
         */
        private static final String CASE_INDEX_KEY = "__case_index_v483";
        private static final String CASE_PREFIX = "__case_v483_";
        private static final int MAX_UNIQUE_CASES = 256;

        private static SharedPreferences prefs() {
            Context ctx = lastContext;
            return ctx == null
                    ? null
                    : ctx.getSharedPreferences(PROFILE_PREFS_V48, Context.MODE_PRIVATE);
        }

        private static String canonicalTask(String task) {
            String n = normalizeTaskAttemptKeyV46(task == null ? "" : task);
            // Collapse harmless OCR/format differences without merging genuinely
            // different tasks. This mainly removes whitespace and punctuation.
            n = n.replaceAll("[\\s\\p{Punct}，。！？；：、（）()【】\\[\\]·]+", "");
            return n.isEmpty() ? "未知任务" : n;
        }

        private static String base(String task) {
            String n = canonicalTask(task);
            return "p_" + Integer.toHexString(n.hashCode()) + "_";
        }

        private static String normalizeCaseDetail(String value) {
            if (value == null) return "";
            return value.trim().replaceAll("\\s+", " ");
        }

        private static String caseSignature(String task, String type, String detail) {
            return canonicalTask(task)
                    + "|" + normalizeCaseDetail(type)
                    + "|" + normalizeCaseDetail(detail);
        }

        private static String caseId(String signature) {
            String reverse = new StringBuilder(signature).reverse().toString();
            return Integer.toHexString(signature.hashCode())
                    + "_" + Integer.toHexString(reverse.hashCode())
                    + "_" + signature.length();
        }

        private static List<String> parseCaseIndex(String raw) {
            List<String> out = new ArrayList<>();
            if (raw == null || raw.isEmpty()) return out;

            Set<String> seen = new HashSet<>();
            String[] parts = raw.split("\\|");
            for (String part : parts) {
                String id = part == null ? "" : part.trim();
                if (id.isEmpty() || !seen.add(id)) continue;
                out.add(id);
            }
            return out;
        }

        private static String encodeCaseIndex(List<String> ids) {
            if (ids == null || ids.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            Set<String> seen = new HashSet<>();
            for (String id : ids) {
                if (id == null || id.isEmpty() || !seen.add(id)) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(id);
            }
            return sb.toString();
        }

        private static void recordUniqueCase(String task, String type, String detail) {
            SharedPreferences p = prefs();
            if (p == null) return;

            String signature = caseSignature(task, type, detail);
            String id = caseId(signature);
            String cp = CASE_PREFIX + id + "_";
            String storedSignature = p.getString(cp + "signature", "");
            long now = System.currentTimeMillis();

            if (signature.equals(storedSignature)) {
                // Exact duplicate: keep the original case record and only update
                // occurrence metadata. No second case is inserted into the library.
                int count = p.getInt(cp + "count", 1) + 1;
                p.edit()
                        .putInt(cp + "count", count)
                        .putLong(cp + "last_at", now)
                        .apply();
                diagnostic("[特征库去重] 已存在相同案例，仅更新次数："
                        + canonicalTask(task) + " / " + type
                        + " / count=" + count);
                return;
            }

            List<String> ids = parseCaseIndex(p.getString(CASE_INDEX_KEY, ""));

            // In the extremely unlikely event of a hash-id collision, derive a
            // deterministic alternate id instead of overwriting another case.
            if (!storedSignature.isEmpty() && !signature.equals(storedSignature)) {
                id = id + "_" + Integer.toHexString((signature + "#2").hashCode());
                cp = CASE_PREFIX + id + "_";
                storedSignature = p.getString(cp + "signature", "");
                if (signature.equals(storedSignature)) {
                    int count = p.getInt(cp + "count", 1) + 1;
                    p.edit().putInt(cp + "count", count).putLong(cp + "last_at", now).apply();
                    return;
                }
            }

            // Keep the library bounded. Remove the oldest indexed unique case.
            while (ids.size() >= MAX_UNIQUE_CASES) {
                String oldest = ids.remove(0);
                removeCaseFields(p, oldest);
            }

            if (!ids.contains(id)) ids.add(id);

            p.edit()
                    .putString(CASE_INDEX_KEY, encodeCaseIndex(ids))
                    .putString(cp + "signature", signature)
                    .putString(cp + "task", safe(task))
                    .putString(cp + "type", safe(type))
                    .putString(cp + "detail", safe(detail))
                    .putInt(cp + "count", 1)
                    .putLong(cp + "first_at", now)
                    .putLong(cp + "last_at", now)
                    .apply();

            diagnostic("[特征库] 新增唯一案例："
                    + canonicalTask(task) + " / " + type
                    + (detail == null || detail.isEmpty() ? "" : " / " + detail));
        }

        private static void removeCaseFields(SharedPreferences p, String id) {
            if (p == null || id == null || id.isEmpty()) return;
            String cp = CASE_PREFIX + id + "_";
            // The app's real SharedPreferences.Editor supports remove(). To stay
            // compatible with the current project/stub surface, clear values by
            // overwriting them; compactUniqueCases() also drops the index entry.
            p.edit()
                    .putString(cp + "signature", "")
                    .putString(cp + "task", "")
                    .putString(cp + "type", "")
                    .putString(cp + "detail", "")
                    .putInt(cp + "count", 0)
                    .putLong(cp + "first_at", 0L)
                    .putLong(cp + "last_at", 0L)
                    .apply();
        }

        static void compactUniqueCases() {
            SharedPreferences p = prefs();
            if (p == null) return;

            List<String> rawIds = parseCaseIndex(p.getString(CASE_INDEX_KEY, ""));
            List<String> kept = new ArrayList<>();
            Set<String> signatures = new HashSet<>();
            int removed = 0;

            for (String id : rawIds) {
                String cp = CASE_PREFIX + id + "_";
                String sig = p.getString(cp + "signature", "");
                if (sig == null || sig.isEmpty()) {
                    removed++;
                    continue;
                }
                if (!signatures.add(sig)) {
                    // Duplicate legacy/index entry: keep the first occurrence only.
                    removeCaseFields(p, id);
                    removed++;
                    continue;
                }
                kept.add(id);
            }

            if (kept.size() > MAX_UNIQUE_CASES) {
                int extra = kept.size() - MAX_UNIQUE_CASES;
                for (int i = 0; i < extra; i++) {
                    removeCaseFields(p, kept.get(i));
                }
                kept = new ArrayList<>(kept.subList(extra, kept.size()));
                removed += extra;
            }

            String compacted = encodeCaseIndex(kept);
            String old = p.getString(CASE_INDEX_KEY, "");
            if (!compacted.equals(old)) {
                p.edit().putString(CASE_INDEX_KEY, compacted).apply();
            }

            diagnostic("[特征库去重] 当前唯一案例=" + kept.size()
                    + (removed > 0 ? "，清理重复/无效=" + removed : ""));
        }

        static void recordAttempt(String task) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit()
                    .putString(b + "name", safe(task))
                    .putInt(b + "attempts", p.getInt(b + "attempts", 0) + 1)
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();
        }

        static void recordSuccess(String task, long elapsedMs) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            int success = p.getInt(b + "success", 0) + 1;
            long oldAvg = p.getLong(b + "avg_success_ms", 0L);
            long newAvg = oldAvg <= 0L
                    ? elapsedMs
                    : Math.round(oldAvg * 0.70 + elapsedMs * 0.30);
            p.edit()
                    .putString(b + "name", safe(task))
                    .putInt(b + "success", success)
                    .putLong(b + "avg_success_ms", newAvg)
                    .putString(b + "last_error", "")
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();

            // Success cases are bucketed by learned duration so repeating the
            // same normal success does not create unlimited case rows.
            String durationBucket = elapsedMs < 7000L ? "fast"
                    : elapsedMs < 15000L ? "normal" : "slow";
            recordUniqueCase(task, "success", durationBucket);

            diagnostic("[特征库] 成功：" + safe(task)
                    + " success=" + success + " avg=" + newAvg + "ms");
        }

        static void recordFailure(String task, String reason) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            int fail = p.getInt(b + "fail", 0) + 1;
            p.edit()
                    .putString(b + "name", safe(task))
                    .putInt(b + "fail", fail)
                    .putString(b + "last_error", safe(reason))
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();

            recordUniqueCase(task, "failure", reason);

            diagnostic("[特征库] 失败：" + safe(task)
                    + " fail=" + fail + " reason=" + safe(reason));
        }

        static void recordRecovery(String task, String recovery) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit()
                    .putString(b + "name", safe(task))
                    .putString(b + "last_recovery", safe(recovery))
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();

            recordUniqueCase(task, "recovery", recovery);
        }

        static long suggestedWaitMs(String task, long defaultMs) {
            SharedPreferences p = prefs();
            if (p == null) return defaultMs;
            String b = base(task);
            long avg = p.getLong(b + "avg_success_ms", 0L);
            if (avg <= 0L) return defaultMs;

            // Learned wait is conservative and bounded; it cannot collapse to
            // an unrealistically short value after one lucky run.
            long learned = Math.round(avg * 0.72);
            long blended = Math.round(defaultMs * 0.45 + learned * 0.55);
            return Math.max(4200L, Math.min(15000L, blended));
        }

        static final class StrategyV49 {
            final String task;
            final long waitMs;
            final int returnSwipes;
            final boolean cautious;
            final int success;
            final int fail;

            StrategyV49(String task, long waitMs, int returnSwipes,
                        boolean cautious, int success, int fail) {
                this.task = task;
                this.waitMs = waitMs;
                this.returnSwipes = returnSwipes;
                this.cautious = cautious;
                this.success = success;
                this.fail = fail;
            }

            String describe() {
                return safe(task) + " wait=" + waitMs + "ms"
                        + " returnSwipes=" + returnSwipes
                        + " mode=" + (cautious ? "cautious" : "normal")
                        + " history=S" + success + "/F" + fail;
            }
        }

        static StrategyV49 chooseStrategyV49(
                String task, long defaultMs, boolean external, boolean video
        ) {
            SharedPreferences p = prefs();
            if (p == null) {
                return new StrategyV49(task, defaultMs, video ? 2 : 0, false, 0, 0);
            }

            String b = base(task);
            int success = p.getInt(b + "success", 0);
            int fail = p.getInt(b + "fail", 0);
            long learned = suggestedWaitMs(task, defaultMs);

            // 连续失败较多时不要继续一味提速：给页面额外恢复时间。
            boolean cautious = fail >= 2 && fail > success;
            if (cautious) learned = Math.min(18000L, learned + 1800L);

            int learnedSwipes = p.getInt(b + "return_swipes", 0);
            int swipes;
            if (video) {
                // 用户实机已验证广告返回需要连续两次边缘返回。
                swipes = Math.max(2, learnedSwipes);
            } else if (external) {
                swipes = Math.max(0, learnedSwipes);
            } else {
                swipes = 0;
            }

            return new StrategyV49(task, learned, swipes, cautious, success, fail);
        }

        static int learnedReturnSwipesV49(String task, int fallback) {
            SharedPreferences p = prefs();
            if (p == null) return fallback;
            int n = p.getInt(base(task) + "return_swipes", fallback);
            return Math.max(0, Math.min(3, n));
        }

        static void setReturnSwipes(String task, int count) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit().putInt(b + "return_swipes", count).apply();
        }

        static String summary(String task) {
            SharedPreferences p = prefs();
            if (p == null) return "new";
            String b = base(task);
            int s = p.getInt(b + "success", 0);
            int f = p.getInt(b + "fail", 0);
            long avg = p.getLong(b + "avg_success_ms", 0L);
            String err = p.getString(b + "last_error", "");
            int rs = p.getInt(b + "return_swipes", 0);
            return "S" + s + "/F" + f
                    + (avg > 0 ? "/avg" + avg + "ms" : "")
                    + (rs > 0 ? "/back×" + rs : "")
                    + (err == null || err.isEmpty() ? "" : "/err=" + err);
        }
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
        final String actionBounds;
        final boolean isClaimReward;

        TaskCandidate(
                String name,
                Node actionNode,
                boolean isClaimReward
        ) {
            this.name = name == null ? "未知任务" : name;
            this.actionNode = actionNode;
            this.actionBounds = actionNode == null
                    ? ""
                    : getAttr(actionNode, "bounds");
            this.isClaimReward = isClaimReward;
        }

        TaskCandidate(
                String name,
                String actionBounds,
                boolean isClaimReward
        ) {
            this.name = name == null ? "未知任务" : name;
            this.actionNode = null;
            this.actionBounds = actionBounds == null ? "" : actionBounds;
            this.isClaimReward = isClaimReward;
        }

        String bounds() {
            return actionBounds == null ? "" : actionBounds;
        }

        String key() {
            return name
                    + "|"
                    + bounds()
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
