package com.zhinibgdu.xianyu;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int ALARM_REQUEST_CODE = 2001;

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        root.addView(statusText);

        Button schedule = new Button(this);
        schedule.setText("设置每日 09:00 自动任务");
        schedule.setOnClickListener(v -> scheduleDailyTask());
        root.addView(schedule);

        Button cancel = new Button(this);
        cancel.setText("取消每日自动任务");
        cancel.setOnClickListener(v -> cancelDailyTask());
        root.addView(cancel);

        Button test = new Button(this);
        test.setText("立即测试任务");
        test.setOnClickListener(v -> triggerXianyuTask());
        root.addView(test);

        Button log = new Button(this);
        log.setText("查看最近状态");
        log.setOnClickListener(v -> showStatus());
        root.addView(log);

        setContentView(root);
        refreshSummary();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void triggerXianyuTask() {
        statusText.setText("正在启动前台任务服务…\nTaskExecutor 会自行验证 Root、打开闲鱼并进入任务页。");
        try {
            TaskForegroundService.start(getApplicationContext());
            Toast.makeText(this, "任务已启动", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            statusText.setText("启动任务失败：" + t);
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
            statusText.setText("请先允许“闹钟和提醒/精确闹钟”权限，然后再次点击设置。\n"
                    + "这是每日定时触发所必需的系统权限。");
            return;
        }

        boolean ok = schedule(
                this,
                AppConfig.DEFAULT_HOUR,
                AppConfig.DEFAULT_MINUTE,
                true
        );

        if (ok) {
            statusText.setText("已设置每日 09:00 自动任务\n"
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date()));
            Toast.makeText(this, "每日 09:00 已设置", Toast.LENGTH_SHORT).show();
        } else {
            statusText.setText("设置失败：系统未允许精确闹钟或 AlarmManager 不可用。");
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
        statusText.setText("每日自动任务已取消。\n立即测试功能仍可单独使用。");
        Toast.makeText(this, "已取消每日任务", Toast.LENGTH_SHORT).show();
    }

    private void refreshSummary() {
        boolean enabled = AppConfig.isScheduleEnabled(this);
        statusText.setText(
                "闲鱼自动任务\n\n"
                        + "架构：精确闹钟 → 前台服务 → Root/UIAutomator\n"
                        + "每日任务：" + (enabled
                        ? String.format(Locale.US, "已启用 %02d:%02d",
                        AppConfig.getHour(this), AppConfig.getMinute(this))
                        : "未启用")
                        + "\n\n"
                        + "本版本不再依赖 LSPosed/Xposed 触发。"
        );
    }

    private void showStatus() {
        boolean running = TaskExecutor.isRunning();
        java.io.File dir = getExternalFilesDir(null);
        java.io.File file = dir == null ? null : new java.io.File(dir, "xianyu_log.txt");

        String log = "";
        if (file != null && file.exists()) {
            try {
                log = new String(
                        java.nio.file.Files.readAllBytes(file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8
                );
            } catch (Throwable t) {
                log = "读取日志失败：" + t;
            }
        }

        if (log.trim().isEmpty()) log = "暂无日志。";
        statusText.setText("运行中：" + running + "\n\n" + log);
    }
}
