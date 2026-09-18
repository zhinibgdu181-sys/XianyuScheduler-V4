package com.zhinibgdu.xianyu;

import android.content.Context;

/** 游戏任务模块，保留现有 Solver. */
public final class GameTask {
    private GameTask() {}
    public static boolean matches(String text) {
        return text.contains("游戏") || text.contains("消除") || text.contains("水果");
    }
    public static void run(Context context) {
        TaskStatusReceiver.writeLog(context, "INFO", "任务分类", "GAME_TASK");
    }
}
