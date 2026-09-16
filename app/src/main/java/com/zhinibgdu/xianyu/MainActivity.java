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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int ALARM_REQUEST_CODE = 2001;
    private static final int MAX_LOG_LINES = 120;

    private TextView scheduleStatusText;
    private TextView runtimeStatusText;
    private TextView statusDetailText;
    private TextView learningStatusText;
    private TextView logText;
    private ScrollView logScroll;

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

        refreshSummary();
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
        showStatus(false);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(runningRefresh);
        super.onDestroy();
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
