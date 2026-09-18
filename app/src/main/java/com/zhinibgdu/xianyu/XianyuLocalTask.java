package com.zhinibgdu.xianyu;

import android.content.Context;

/** 闲鱼本地固定规则任务. */
public final class XianyuLocalTask {
    private XianyuLocalTask() {}
    public static void run(Context context) {
        TaskStatusReceiver.writeLog(context, "INFO", "任务分类", "XIANYU_LOCAL_TASK");
    }
}
