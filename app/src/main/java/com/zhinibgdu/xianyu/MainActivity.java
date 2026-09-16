package com.zhinibgdu.xianyu;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.TimePickerDialog;
import android.app.DatePickerDialog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int ALARM_REQUEST_CODE = 2001;
    private static final int MAX_LOG_LINES = 120;

    private TextView scheduleStatusText;
    private TextView runtimeStatusText;
    private TextView statusDetailText;
    private TextView learningStatusText;
    private TextView automationFeedbackText;
    private TextView todayDateText;
    private TextView todaySummaryText;
    private TextView todayCompletedText;
    private TextView dataPathText;
    private TextView logText;
    private ScrollView logScroll;

    // V4.26: 首页 / 今日 / 自动化 / 日志. The navigation bar is fixed and
    // styled as a floating dark pill similar to the user-provided reference UI.
    private View pageHome;
    private View pageToday;
    private View pageAutomation;
    private View pageLogs;
    private LinearLayout navHome;
    private LinearLayout navToday;
    private LinearLayout navAutomation;
    private LinearLayout navLogs;
    private ImageView navHomeIcon;
    private ImageView navTodayIcon;
    private ImageView navAutomationIcon;
    private ImageView navLogsIcon;
    private TextView navHomeText;
    private TextView navTodayText;
    private TextView navAutomationText;
    private TextView navLogsText;
    private int selectedBottomTab = 0;
    private String selectedRecordDate;

    private LinearLayout rootPermissionCard;
    private LinearLayout alarmPermissionCard;
    private TextView rootPermissionIcon;
    private TextView rootPermissionStatusText;
    private TextView rootPermissionHintText;
    private TextView alarmPermissionStatusText;
    private TextView alarmPermissionHintText;
    private volatile boolean rootCheckInFlight;

    // V4.31 缓存最近一次 ROOT 授权结果，用于卡片点击时决定是"重新检测"还是"跳管理器"
    private volatile boolean rootGrantedCache = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable runningRefresh = new Runnable() {
        @Override
        public void run() {
            showStatus(false);
            refreshTodayCompleted();
            if (TaskExecutor.isRunning()) {
                handler.postDelayed(this, 1500L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        scheduleStatusText = findViewById(R.id.schedule_status_text);
        runtimeStatusText = findViewById(R.id.runtime_status_text);
        statusDetailText = findViewById(R.id.status_detail_text);
        learningStatusText = findViewById(R.id.learning_status_text);
        automationFeedbackText = findViewById(R.id.automation_feedback_text);
        todayDateText = findViewById(R.id.today_date_text);
        todaySummaryText = findViewById(R.id.today_summary_text);
        todayCompletedText = findViewById(R.id.today_completed_text);
        dataPathText = findViewById(R.id.data_path_text);
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);

        pageHome = findViewById(R.id.page_home);
        pageToday = findViewById(R.id.page_today);
        pageAutomation = findViewById(R.id.page_automation);
        pageLogs = findViewById(R.id.page_logs);

        navHome = findViewById(R.id.nav_home);
        navToday = findViewById(R.id.nav_today);
        navAutomation = findViewById(R.id.nav_automation);
        navLogs = findViewById(R.id.nav_logs);
        navHomeIcon = findViewById(R.id.nav_home_icon);
        navTodayIcon = findViewById(R.id.nav_today_icon);
        navAutomationIcon = findViewById(R.id.nav_automation_icon);
        navLogsIcon = findViewById(R.id.nav_logs_icon);
        navHomeText = findViewById(R.id.nav_home_text);
        navTodayText = findViewById(R.id.nav_today_text);
        navAutomationText = findViewById(R.id.nav_automation_text);
        navLogsText = findViewById(R.id.nav_logs_text);

        rootPermissionCard = findViewById(R.id.root_permission_card);
        alarmPermissionCard = findViewById(R.id.alarm_permission_card);
        rootPermissionIcon = findViewById(R.id.root_permission_icon);
        rootPermissionStatusText = findViewById(R.id.root_permission_status_text);
        rootPermissionHintText = findViewById(R.id.root_permission_hint_text);
        alarmPermissionStatusText = findViewById(R.id.alarm_permission_status_text);
        alarmPermissionHintText = findViewById(R.id.alarm_permission_hint_text);

        Button schedule = findViewById(R.id.schedule_button);
        Button cancel = findViewById(R.id.cancel_button);
        Button test = findViewById(R.id.test_button);
        Button learning = findViewById(R.id.learning_button);
        Button stop = findViewById(R.id.stop_button);
        Button learningStop = findViewById(R.id.learning_stop_button);
        Button log = findViewById(R.id.log_button);
        Button clearLog = findViewById(R.id.clear_log_button);
        Button todayRefresh = findViewById(R.id.today_refresh_button);
        Button showPath = findViewById(R.id.show_path_button);

        schedule.setOnClickListener(v -> scheduleDailyTask());
        cancel.setOnClickListener(v -> cancelDailyTask());
        test.setOnClickListener(v -> triggerXianyuTask());
        learning.setOnClickListener(v -> triggerLearningMode());
        stop.setOnClickListener(v -> stopCurrentRun());
        learningStop.setOnClickListener(v -> stopLearningMode());
        log.setOnClickListener(v -> showStatus(true));
        clearLog.setOnClickListener(v -> confirmClearLog());
        todayRefresh.setOnClickListener(v -> refreshTodayCompleted());
        showPath.setOnClickListener(v -> showDataPaths());

        navHome.setOnClickListener(v -> selectBottomTab(0));
        navToday.setOnClickListener(v -> selectBottomTab(1));
        navAutomation.setOnClickListener(v -> selectBottomTab(2));
        navLogs.setOnClickListener(v -> selectBottomTab(3));
        selectBottomTab(0);

        // V4.31 点击卡片：已授权 → 重新检测；未授权 → 跳 KernelSU/Magisk 管理器
        rootPermissionCard.setOnClickListener(v -> {
            if (isRootGrantedNow()) {
                setStatusMessage("ROOT 已授权，正在重新检测。");
                refreshRootPermissionAsync(true);
            } else {
                openRootManager();
            }
        });


        alarmPermissionCard.setOnClickListener(v -> {
            setStatusMessage("正在打开系统“闹钟和提醒”权限页面；返回后会自动刷新状态。");
            requestExactAlarmPermission();
        });

        selectedRecordDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        todayDateText.setOnClickListener(v -> chooseRecordDate());

        refreshSummary();
        refreshTodayCompleted();
        refreshPermissionDashboard();
        showStatus(false);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSummary();
        refreshTodayCompleted();
        refreshPermissionDashboard();
        showStatus(false);
        selectBottomTab(selectedBottomTab);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(runningRefresh);
        super.onDestroy();
    }

    private void selectBottomTab(int tab) {
        selectedBottomTab = Math.max(0, Math.min(3, tab));

        pageHome.setVisibility(selectedBottomTab == 0 ? View.VISIBLE : View.GONE);
        pageToday.setVisibility(selectedBottomTab == 1 ? View.VISIBLE : View.GONE);
        pageAutomation.setVisibility(selectedBottomTab == 2 ? View.VISIBLE : View.GONE);
        pageLogs.setVisibility(selectedBottomTab == 3 ? View.VISIBLE : View.GONE);

        applyBottomNavState(navHome, navHomeIcon, navHomeText, selectedBottomTab == 0);
        applyBottomNavState(navToday, navTodayIcon, navTodayText, selectedBottomTab == 1);
        applyBottomNavState(navAutomation, navAutomationIcon, navAutomationText, selectedBottomTab == 2);
        applyBottomNavState(navLogs, navLogsIcon, navLogsText, selectedBottomTab == 3);

        if (selectedBottomTab == 0) {
            refreshPermissionDashboard();
            refreshSummary();
        } else if (selectedBottomTab == 1) {
            refreshTodayCompleted();
        } else if (selectedBottomTab == 2) {
            refreshSummary();
        } else {
            showStatus(false);
        }
    }

    private void applyBottomNavState(
            LinearLayout item,
            ImageView icon,
            TextView label,
            boolean selected
    ) {
        int color = selected ? 0xFF4B86FF : 0xFFAEB4BF;
        icon.setColorFilter(color);
        label.setTextColor(color);
        label.setTypeface(null,
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        item.setBackgroundResource(selected ? R.drawable.bg_bottom_nav_selected : 0);
        icon.setAlpha(selected ? 1.0f : 0.90f);
    }

    private void setStatusMessage(CharSequence message) {
        if (statusDetailText != null) statusDetailText.setText(message);
        if (automationFeedbackText != null) automationFeedbackText.setText(message);
    }

    private void refreshTodayCompleted() {
        String date = selectedRecordDate;
        if (date == null || date.trim().isEmpty()) {
            date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
            selectedRecordDate = date;
        }
        todayDateText.setText(date + "  ▼");
        List<String> tasks = TaskStatusReceiver.getCompletedTasksForDate(this, date);
        int coins = TaskStatusReceiver.getCoinsForDate(this, date);
        boolean isToday = date.equals(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));
        todaySummaryText.setText((isToday ? "今日" : date) + "完成 " + tasks.size() + " 个任务 · 闲鱼币 +" + coins);
        if (tasks.isEmpty()) {
            todayCompletedText.setText("该日期还没有已验证完成的任务。\n\n记录会按日期自动保存在本机。");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tasks.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(i + 1).append(". ").append(tasks.get(i));
            }
            if (coins == 0) {
                sb.append("\n\n闲鱼币：当前没有可被程序明确确认的奖励数值，因此不猜测、不虚报。");
            } else {
                sb.append("\n\n闲鱼币合计：+").append(coins);
            }
            todayCompletedText.setText(sb.toString());
        }
    }

    private void chooseRecordDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(selectedRecordDate);
            if (d != null) c.setTime(d);
        } catch (Throwable ignored) {}
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            selectedRecordDate = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            refreshTodayCompleted();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void refreshPermissionDashboard() {
        refreshAlarmPermissionCard();
        refreshRootPermissionAsync(false);
    }

    private boolean hasExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    private void refreshAlarmPermissionCard() {
        boolean granted = hasExactAlarmPermission();

        alarmPermissionCard.setBackgroundResource(
                granted ? R.drawable.bg_permission_granted : R.drawable.bg_permission_denied
        );
        alarmPermissionStatusText.setText(granted ? "已开启" : "未开启");
        alarmPermissionStatusText.setTextColor(granted ? 0xFF166534 : 0xFF4B5563);

        if (Build.VERSION.SDK_INT < 31) {
            alarmPermissionHintText.setText("当前 Android 版本无需单独授权");
        } else if (granted) {
            alarmPermissionHintText.setText("精确闹钟可用 · 每日定时可正常触发");
        } else {
            alarmPermissionHintText.setText("点击卡片进入系统“闹钟和提醒”权限页面");
        }
    }

    private void refreshRootPermissionAsync(boolean userRequested) {
        if (rootCheckInFlight) return;
        rootCheckInFlight = true;

        rootPermissionCard.setBackgroundResource(R.drawable.bg_permission_denied);
        rootPermissionIcon.setText("…");
        rootPermissionIcon.setTextColor(0xFF6B7280);
        rootPermissionStatusText.setText("检查中");
        rootPermissionStatusText.setTextColor(0xFF4B5563);
        rootPermissionHintText.setText("正在确认 KernelSU 授权状态");

        new Thread(() -> {
            RootPermissionResult result = probeRootPermission();
            runOnUiThread(() -> {
                rootCheckInFlight = false;
                applyRootPermissionResult(result);
                if (userRequested) {
                    Toast.makeText(
                            this,
                            result.granted ? "ROOT 权限已授权" : "ROOT 未授权，请在 KernelSU 中开启",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            });
        }, "root-permission-check").start();
    }

    private void applyRootPermissionResult(RootPermissionResult result) {
        // V4.31 缓存本次检测结果，供卡片点击时决定是"重新检测"还是"跳管理器"
        rootGrantedCache = result.granted;

        if (result.granted) {
            rootPermissionCard.setBackgroundResource(R.drawable.bg_permission_granted);
            rootPermissionIcon.setText("✓ ROOT");
            rootPermissionIcon.setTextColor(0xFF16A34A);
            rootPermissionStatusText.setText("已授权");
            rootPermissionStatusText.setTextColor(0xFF166534);
            rootPermissionHintText.setText(
                    (result.suPath == null ? "ROOT 可用" : result.suPath)
                            + " · uid=0\n点卡片可重新检测"
            );
        } else {
            rootPermissionCard.setBackgroundResource(R.drawable.bg_permission_denied);
            rootPermissionIcon.setText("ROOT");
            rootPermissionIcon.setTextColor(0xFF6B7280);
            rootPermissionStatusText.setText("未授权");
            rootPermissionStatusText.setTextColor(0xFF4B5563);
            rootPermissionHintText.setText(
                    result.suDetected
                            ? "已检测到 su · 点卡片打开 KernelSU/Magisk 授权\n授权后返回本应用自动刷新"
                            : "未检测到可用 su / ROOT 环境\n点卡片可尝试打开 Root 管理器"
            );
        }
    }

    /**
     * V4.31 判断上次检测结果是否为已授权。
     */
    private boolean isRootGrantedNow() {
        return rootGrantedCache;
    }

    /**
     * V4.31 尝试跳转到已安装的 Root 管理器：
     * 1. KernelSU 官方
     * 2. SukiSU Ultra（KernelSU 分支）
     * 3. Magisk
     * 4. MMRL（模块管理器）
     * 5. 都没装则打开本应用系统信息页
     * 6. 最后兜底只弹 Toast
     */
    private void openRootManager() {
        // 不硬编码 Activity 名，交给系统解析各包的主 Activity，
        // 这样对 KernelSU / Magisk / SukiSU 各版本都兼容。
        String[][] candidates = {
                {"me.weishu.kernelsu", "KernelSU"},
                {"com.sukisu.ultra", "SukiSU Ultra"},
                {"com.topjohnwu.magisk", "Magisk"},
                {"com.dergoogler.mmrl", "MMRL"}
        };

        for (String[] entry : candidates) {
            String pkg = entry[0];
            String label = entry[1];
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(launch);
                    Toast.makeText(
                            this,
                            "已打开 " + label
                                    + "，请切到“超级用户”页面给“闲鱼定时助手”授权，然后返回本应用",
                            Toast.LENGTH_LONG
                    ).show();
                    setStatusMessage(
                            "已跳转 " + label
                                    + "。请在超级用户列表中找到“闲鱼定时助手”并开启权限；\n"
                                    + "返回本应用后会自动刷新授权状态。"
                    );
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        // 没有装任何已知 Root 管理器：退而求其次，打开本应用的系统信息页，
        // 让用户自己去系统设置里找权限入口。
        try {
            Intent appInfo = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            appInfo.setData(Uri.parse("package:" + getPackageName()));
            appInfo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(appInfo);
            Toast.makeText(
                    this,
                    "未检测到 KernelSU/Magisk，已打开应用信息页",
                    Toast.LENGTH_LONG
            ).show();
            setStatusMessage("未检测到已安装的 Root 管理器。已打开本应用信息页，请手动前往管理器授权。");
        } catch (Throwable t) {
            Toast.makeText(
                    this,
                    "未找到可用的 Root 管理器，请手动打开 KernelSU/Magisk 授权",
                    Toast.LENGTH_LONG
            ).show();
            setStatusMessage("未找到可用的 Root 管理器。请手动打开 KernelSU/Magisk 给本应用授权。");
        }
    }

    private RootPermissionResult probeRootPermission() {
        String[] paths = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/magisk/su",
                "su"
        };

        boolean suDetected = false;
        for (String path : paths) {
            Process process = null;
            try {
                process = new ProcessBuilder(path, "-c", "id")
                        .redirectErrorStream(true)
                        .start();
                suDetected = true;

                boolean finished = process.waitFor(1600L, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroy();
                    return new RootPermissionResult(false, true, path);
                }

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() > 0) output.append('\n');
                        output.append(line);
                    }
                }

                if (process.exitValue() == 0 && output.toString().contains("uid=0")) {
                    return new RootPermissionResult(true, true, path);
                }

                return new RootPermissionResult(false, true, path);
            } catch (Throwable ignored) {
            } finally {
                if (process != null) {
                    try { process.destroy(); } catch (Throwable ignored) { }
                }
            }
        }

        return new RootPermissionResult(false, suDetected, null);
    }

    private static final class RootPermissionResult {
        final boolean granted;
        final boolean suDetected;
        final String suPath;

        RootPermissionResult(boolean granted, boolean suDetected, String suPath) {
            this.granted = granted;
            this.suDetected = suDetected;
            this.suPath = suPath;
        }
    }

    private void showDataPaths() {
        java.io.File external = getExternalFilesDir(null);
        String logPath = external == null ? "不可用" : new java.io.File(external, "xianyu_log.txt").getAbsolutePath();
        String text = "运行日志：\n" + logPath
                + "\n\n任务记录：\n/data/data/" + getPackageName()
                + "/shared_prefs/xianyu_records_v427.xml"
                + "\n\n真人学习库：\n/data/data/" + getPackageName()
                + "/shared_prefs/xianyu_learning_v413.xml";
        dataPathText.setText(text);
        new AlertDialog.Builder(this)
                .setTitle("数据保存路径")
                .setMessage(text + "\n\n/data/data 路径需要 Root 权限查看。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("确认删除当前全部运行日志？\n同时会清空诊断截图和学习日志文件，\n但不会影响今日任务记录、定时设置和学习库。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    boolean ok = TaskStatusReceiver.clearLog(getApplicationContext());
                    logText.setText("暂无日志。");
                    setStatusMessage(ok ? "日志已清空。" : "清空日志失败，请稍后重试。");
                    Toast.makeText(
                            this,
                            ok ? "日志已清空" : "清空失败",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .show();
    }

    private void triggerLearningMode() {
        if (TaskExecutor.isRunning()) {
            setStatusMessage("当前已有运行实例，请先停止当前任务。");
            Toast.makeText(this, "已有任务正在运行", Toast.LENGTH_SHORT).show();
            return;
        }

        setStatusMessage(
                "真人示范学习即将开始。\n"
                        + "启动后请手动离开本助手并正常操作闲鱼/广告/外部 App；"
                        + "程序只记录，不会主动点击或滑动。\n"
                        + "示范完成后切回本助手，学习会自动结束并保存。"
        );

        try {
            TaskForegroundService.startLearning(getApplicationContext());
            Toast.makeText(this, "学习模式已启动，请开始真人示范", Toast.LENGTH_LONG).show();
            handler.removeCallbacks(runningRefresh);
            handler.postDelayed(runningRefresh, 800L);
        } catch (Throwable t) {
            setStatusMessage("启动学习模式失败：" + t.getClass().getSimpleName()
                    + "：" + t.getMessage());
            Toast.makeText(this, "启动学习模式失败", Toast.LENGTH_LONG).show();
        }
    }

    private void stopLearningMode() {
        if (!TaskExecutor.isRunning() || !TaskExecutor.isLearningMode()) {
            Toast.makeText(this, "当前没有正在进行的真人学习", Toast.LENGTH_SHORT).show();
            return;
        }
        TaskExecutor.requestStop("用户点击“结束学习”");
        setStatusMessage("正在结束真人学习并保存去重后的学习案例……");
        handler.removeCallbacks(runningRefresh);
        handler.postDelayed(runningRefresh, 500L);
    }

    private void stopCurrentRun() {
        if (!TaskExecutor.isRunning()) {
            Toast.makeText(this, "当前没有运行任务", Toast.LENGTH_SHORT).show();
            return;
        }

        TaskExecutor.requestStop("用户点击“停止当前运行”");
        setStatusMessage(
                TaskExecutor.isLearningMode()
                        ? "正在结束学习模式并保存记录……"
                        : "已请求停止自动任务，后续主动 UI 操作将被立即拦截。"
        );
        handler.removeCallbacks(runningRefresh);
        handler.postDelayed(runningRefresh, 500L);
    }

    private void triggerXianyuTask() {
        setStatusMessage("正在启动任务服务，随后会检查 Root、打开闲鱼并进入任务页。\n运行期间请保持手机解锁。");
        setRuntimeState(true);
        try {
            TaskForegroundService.start(getApplicationContext());
            Toast.makeText(this, "任务已启动", Toast.LENGTH_SHORT).show();
            handler.removeCallbacks(runningRefresh);
            handler.postDelayed(runningRefresh, 800L);
        } catch (Throwable t) {
            setRuntimeState(false);
            setStatusMessage("启动任务失败：" + t.getClass().getSimpleName() + "：" + t.getMessage());
            Toast.makeText(this, "启动任务失败", Toast.LENGTH_LONG).show();
        }
    }

    public static boolean schedule(
            Context context,
            int hour,
            int minute,
            boolean persist
    ) {
        Context app = context.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(ALARM_SERVICE);
        if (am == null) return false;

        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            return false;
        }

        Intent intent = new Intent(app, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                app,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, minute);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        }

        try {
            am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    cal.getTimeInMillis(),
                    pi
            );
        } catch (SecurityException securityException) {
            TaskStatusReceiver.writeLog(
                    app, "FAILED", "调度",
                    "设置精确闹钟失败: " + securityException
            );
            return false;
        } catch (Throwable t) {
            TaskStatusReceiver.writeLog(
                    app, "FAILED", "调度",
                    "设置闹钟异常: " + t
            );
            return false;
        }

        if (persist) {
            AppConfig.saveSchedule(app, hour, minute);
        }
        return true;
    }

    public static boolean schedule(Context context, int hour, int minute) {
        return schedule(context, hour, minute, true);
    }

    private void scheduleDailyTask() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && am != null && !am.canScheduleExactAlarms()) {
            requestExactAlarmPermission();
            setStatusMessage("请先允许“闹钟和提醒/精确闹钟”权限，然后再次设置每日时间。");
            return;
        }

        int initialHour = AppConfig.isScheduleEnabled(this) ? AppConfig.getHour(this) : AppConfig.DEFAULT_HOUR;
        int initialMinute = AppConfig.isScheduleEnabled(this) ? AppConfig.getMinute(this) : AppConfig.DEFAULT_MINUTE;
        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            boolean ok = schedule(this, hourOfDay, minute, true);
            if (ok) {
                refreshSummary();
                setStatusMessage(String.format(Locale.US, "每日定时设置成功：%02d:%02d", hourOfDay, minute));
                Toast.makeText(this, String.format(Locale.US, "已设置每日 %02d:%02d", hourOfDay, minute), Toast.LENGTH_SHORT).show();
            } else {
                setStatusMessage("设置失败：系统未允许精确闹钟，或 AlarmManager 当前不可用。");
            }
        }, initialHour, initialMinute, true).show();
    }

    private void requestExactAlarmPermission() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())
            ));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
            } catch (Throwable ignored) {
            }
        }
    }

    private void cancelDailyTask() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            Intent intent = new Intent(this, AlarmReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(
                    this,
                    ALARM_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
            );
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
        AppConfig.setScheduleEnabled(this, false);
        refreshSummary();
        setStatusMessage("每日自动任务已取消。立即运行功能仍可单独使用。");
        Toast.makeText(this, "已取消每日任务", Toast.LENGTH_SHORT).show();
    }

    private void refreshSummary() {
        boolean enabled = AppConfig.isScheduleEnabled(this);
        if (enabled) {
            scheduleStatusText.setText(String.format(
                    Locale.US,
                    "每日任务：已启用 %02d:%02d",
                    AppConfig.getHour(this),
                    AppConfig.getMinute(this)
            ));
        } else {
            scheduleStatusText.setText("每日任务：未启用");
        }
        int learned = TaskExecutor.getLearningCaseCount(this);
        learningStatusText.setText("学习库：已保存 " + learned + " 个唯一案例");
        setRuntimeState(TaskExecutor.isRunning());
    }

    private void setRuntimeState(boolean running) {
        if (running) {
            runtimeStatusText.setText(TaskExecutor.isLearningMode() ? "学习中" : "运行中");
            runtimeStatusText.setTextColor(0xFF1D4ED8);
            runtimeStatusText.setBackgroundResource(R.drawable.bg_status_running);
        } else {
            runtimeStatusText.setText("空闲");
            runtimeStatusText.setTextColor(0xFF166534);
            runtimeStatusText.setBackgroundResource(R.drawable.bg_status_idle);
        }
    }

    private void showStatus(boolean userRequested) {
        boolean running = TaskExecutor.isRunning();
        setRuntimeState(running);

        java.io.File dir = getExternalFilesDir(null);
        java.io.File file = dir == null ? null : new java.io.File(dir, "xianyu_log.txt");

        String log = readRecentLog(file);
        logText.setText(log);
        learningStatusText.setText(
                "学习库：已保存 " + TaskExecutor.getLearningCaseCount(this) + " 个唯一案例");

        if (running) {
            if (TaskExecutor.isLearningMode()) {
                setStatusMessage(
                        "真人示范学习正在记录。\n"
                                + "程序不会主动点击/滑动；示范结束后切回本助手即可自动保存。");
            } else {
                setStatusMessage(
                        "任务正在执行 · 日志会自动刷新。\n"
                                + "切回本助手或点击“停止当前运行”都会立即停止主动操作。");
            }
        } else if (userRequested) {
            setStatusMessage("日志已刷新 · "
                    + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        }

        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String readRecentLog(java.io.File file) {
        if (file == null || !file.exists()) return "暂无日志。";
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return "暂无日志。";

            int start = Math.max(0, lines.size() - MAX_LOG_LINES);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.size(); i++) {
                if (i > start) sb.append('\n');
                sb.append(lines.get(i));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取日志失败：" + t;
        }
    }
                        }
