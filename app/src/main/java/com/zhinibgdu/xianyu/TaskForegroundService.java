package com.zhinibgdu.xianyu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Owns the process lifetime while TaskExecutor is controlling Xianyu.
 * AlarmReceiver is intentionally kept short: it only schedules the next alarm
 * and starts this foreground service.
 */
public class TaskForegroundService extends Service {
    private static final String TAG = "XianyuTaskService";
    private static final String CHANNEL_ID = "xianyu_scheduler";
    private static final int NOTIFICATION_ID = 18009;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, TaskForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        acquireWakeLock();
        Notification notification = buildNotification("正在准备自动任务");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TaskStatusReceiver.writeLog(this, "INFO", "调度", "前台服务收到任务请求");

        if (TaskExecutor.isRunning()) {
            // A duplicate alarm/test request must not stop the service that owns
            // the currently running executor. Wait for the current run to finish.
            new Thread(() -> {
                while (TaskExecutor.isRunning()) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                stopForegroundCompat();
                stopSelf();
            }, "XianyuService-WaitExisting").start();
            return START_NOT_STICKY;
        }

        TaskExecutor.run(getApplicationContext(), () -> {
            TaskStatusReceiver.writeLog(
                    getApplicationContext(), "INFO", "调度", "任务执行器已结束，停止前台服务");
            stopForegroundCompat();
            stopSelf();
        });

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "XianyuScheduler:TaskWakeLock"
            );
            // Hard timeout protects against a service/executor bug leaking the lock.
            wakeLock.acquire(30L * 60L * 1000L);
        } catch (Throwable t) {
            Log.e(TAG, "获取 WakeLock 失败", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "闲鱼自动任务运行状态",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持定时自动任务在后台执行");
            manager.createNotificationChannel(channel);
        } catch (Throwable t) {
            Log.e(TAG, "创建通知渠道失败", t);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                18009,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("闲鱼定时助手")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
