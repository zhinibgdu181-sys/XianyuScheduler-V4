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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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
    private TextView logText;
    private ScrollView logScroll;

    // V4.24 permission dashboard. ROOT is display-only because KernelSU must
    // grant it explicitly; exact-alarm uses Android's system special-access page.
    private LinearLayout rootPermissionCard;
    private LinearLayout alarmPermissionCard;
    private TextView rootPermissionIcon;
    private TextView rootPermissionStatusText;
    private TextView rootPermissionHintText;
    private TextView alarmPermissionStatusText;
    private TextView alarmPermissionHintText;
    private Switch alarmPermissionSwitch;
    private boolean updatingAlarmSwitch;
    private volatile boolean rootCheckInFlight;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable runningRefresh = new Runnable() {
        @Override
        public void run() {
            showStatus(false);
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
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);

        rootPermissionCard = findViewById(R.id.root_permission_card);
        alarmPermissionCard = findViewById(R.id.alarm_permission_card);
        rootPermissionIcon = findViewById(R.id.root_permission_icon);
        rootPermissionStatusText = findViewById(R.id.root_permission_status_text);
        rootPermissionHintText = findViewById(R.id.root_permission_hint_text);
        alarmPermissionStatusText = findViewById(R.id.alarm_permission_status_text);
        alarmPermissionHintText = findViewById(R.id.alarm_permission_hint_text);
        alarmPermissionSwitch = findViewById(R.id.alarm_permission_switch);

        Button schedule = findViewById(R.id.schedule_button);
        Button cancel = findViewById(R.id.cancel_button);
        Button test = findViewById(R.id.test_button);
        Button learning = findViewById(R.id.learning_button);
        Button stop = findViewById(R.id.stop_button);
        Button log = findViewById(R.id.log_button);
        Button clearLog = findViewById(R.id.clear_log_button);

        schedule.setOnClickListener(v -> scheduleDailyTask());
        cancel.setOnClickListener(v -> cancelDailyTask());
        test.setOnClickListener(v -> triggerXianyuTask());
        learning.setOnClickListener(v -> triggerLearningMode());
        stop.setOnClickListener(v -> stopCurrentRun());
        log.setOnClickListener(v -> showStatus(true));
        clearLog.setOnClickListener(v -> confirmClearLog());

        rootPermissionCard.setOnClickListener(v -> {
            statusDetailText.setText(
                    "正在重新检测 ROOT 权限。若显示未授权，请打开 KernelSU → 超级用户，"
                            + "给“闲鱼定时助手”开启权限后再返回。"
            );
            refreshRootPermissionAsync(true);
        });

        alarmPermissionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingAlarmSwitch) return;
            handleAlarmPermissionToggle(isChecked);
        });

        refreshSummary();
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
        refreshPermissionDashboard();
        showStatus(false);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(runningRefresh);
        super.onDestroy();
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
            alarmPermissionHintText.setText("打开开关后在系统页面允许“闹钟和提醒”");
        }

        updatingAlarmSwitch = true;
        alarmPermissionSwitch.setChecked(granted);
        alarmPermissionSwitch.setEnabled(Build.VERSION.SDK_INT >= 31);
        updatingAlarmSwitch = false;
    }

    private void handleAlarmPermissionToggle(boolean requestedEnabled) {
        if (Build.VERSION.SDK_INT < 31) {
            refreshAlarmPermissionCard();
            return;
        }

        boolean actual = hasExactAlarmPermission();
        if (requestedEnabled == actual) {
            refreshAlarmPermissionCard();
            return;
        }

        if (requestedEnabled) {
            statusDetailText.setText(
                    "请在接下来的系统页面允许“闲鱼定时助手”的闹钟和提醒权限。\n"
                            + "返回本应用后，开关会自动变成绿色开启状态。"
            );
        } else {
            statusDetailText.setText(
                    "Android 不允许应用直接撤销自己的精确闹钟特殊权限。\n"
                            + "请在接下来的系统页面关闭，返回后开关会自动同步。"
            );
        }

        requestExactAlarmPermission();
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
        if (result.granted) {
            rootPermissionCard.setBackgroundResource(R.drawable.bg_permission_granted);
            rootPermissionIcon.setText("✓");
            rootPermissionIcon.setTextColor(0xFF16A34A);
            rootPermissionStatusText.setText("已授权");
            rootPermissionStatusText.setTextColor(0xFF166534);
            rootPermissionHintText.setText(
                    (result.suPath == null ? "ROOT 可用" : result.suPath)
                            + " · uid=0\n点卡片可重新检测"
            );
        } else {
            rootPermissionCard.setBackgroundResource(R.drawable.bg_permission_denied);
            rootPermissionIcon.setText("—");
            rootPermissionIcon.setTextColor(0xFF6B7280);
            rootPermissionStatusText.setText("未授权");
            rootPermissionStatusText.setTextColor(0xFF4B5563);
            rootPermissionHintText.setText(
                    result.suDetected
                            ? "已检测到 su · 请在 KernelSU → 超级用户中授权\n授权后返回本应用"
                            : "未检测到可用 su / ROOT 环境\n点卡片可重新检测"
            );
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

                // An executable su was found but this app did not receive uid=0.
                // Do not invoke several other su paths: KernelSU authorization is
                // explicit and repeated probes only create noise/delay.
                return new RootPermissionResult(false, true, path);
            } catch (Throwable ignored) {
                // Try the next known su path. The UI intentionally stays quiet here.
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

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("确认删除当前全部运行日志？\n不会影响定时设置和任务配置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    boolean ok = TaskStatusReceiver.clearLog(getApplicationContext());
                    logText.setText("暂无日志。");
                    statusDetailText.setText(ok ? "日志已清空。" : "清空日志失败，请稍后重试。");
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
            statusDetailText.setText("当前已有运行实例，请先停止当前任务。");
            Toast.makeText(this, "已有任务正在运行", Toast.LENGTH_SHORT).show();
            return;
        }

        statusDetailText.setText(
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
            statusDetailText.setText("启动学习模式失败：" + t.getClass().getSimpleName()
                    + "：" + t.getMessage());
            Toast.makeText(this, "启动学习模式失败", Toast.LENGTH_LONG).show();
        }
    }

    private void stopCurrentRun() {
        if (!TaskExecutor.isRunning()) {
            Toast.makeText(this, "当前没有运行任务", Toast.LENGTH_SHORT).show();
            return;
        }

        TaskExecutor.requestStop("用户点击“停止当前运行”");
        statusDetailText.setText(
                TaskExecutor.isLearningMode()
                        ? "正在结束学习模式并保存记录……"
                        : "已请求停止自动任务，后续主动 UI 操作将被立即拦截。"
        );
        handler.removeCallbacks(runningRefresh);
        handler.postDelayed(runningRefresh, 500L);
    }

    private void triggerXianyuTask() {
        statusDetailText.setText("正在启动任务服务，随后会检查 Root、打开闲鱼并进入任务页。\n运行期间请保持手机解锁。");
        setRuntimeState(true);
        try {
            TaskForegroundService.start(getApplicationContext());
            Toast.makeText(this, "任务已启动", Toast.LENGTH_SHORT).show();
            handler.removeCallbacks(runningRefresh);
            handler.postDelayed(runningRefresh, 800L);
        } catch (Throwable t) {
            setRuntimeState(false);
            statusDetailText.setText("启动任务失败：" + t.getClass().getSimpleName() + "：" + t.getMessage());
            Toast.makeText(this, "启动任务失败", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Schedules one exact alarm. Public for AlarmReceiver/BootReceiver.
     * persist=true means this is a user configuration change.
     */
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

    /** Compatibility overload retained for any old call site. */
    public static boolean schedule(Context context, int hour, int minute) {
        return schedule(context, hour, minute, true);
    }

    private void scheduleDailyTask() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 31 && am != null && !am.canScheduleExactAlarms()) {
            requestExactAlarmPermission();
            statusDetailText.setText("请先允许“闹钟和提醒/精确闹钟”权限，然后再次点击设置。\n这是每日定时触发所必需的系统权限。");
            return;
        }

        boolean ok = schedule(
                this,
                AppConfig.DEFAULT_HOUR,
                AppConfig.DEFAULT_MINUTE,
                true
        );

        if (ok) {
            refreshSummary();
            statusDetailText.setText("定时设置成功 · "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date()));
            Toast.makeText(this, "每日 09:00 已设置", Toast.LENGTH_SHORT).show();
        } else {
            statusDetailText.setText("设置失败：系统未允许精确闹钟，或 AlarmManager 当前不可用。");
        }
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
        statusDetailText.setText("每日自动任务已取消。立即测试功能仍可单独使用。");
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
                statusDetailText.setText(
                        "真人示范学习正在记录。\n"
                                + "程序不会主动点击/滑动；示范结束后切回本助手即可自动保存。");
            } else {
                statusDetailText.setText(
                        "任务正在执行 · 日志会自动刷新。\n"
                                + "切回本助手或点击“停止当前运行”都会立即停止主动操作。");
            }
        } else if (userRequested) {
            statusDetailText.setText("日志已刷新 · "
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
