package com.zhinibgdu.xianyu;

import android.content.Context;

/** 视频任务模块. */
public final class VideoTask {
    private VideoTask() {}
    public static boolean matches(String text) {
        return text.contains("视频") || text.contains("观看");
    }
    public static void run(Context context) {
        TaskStatusReceiver.writeLog(context, "INFO", "任务分类", "VIDEO_TASK");
    }
}
