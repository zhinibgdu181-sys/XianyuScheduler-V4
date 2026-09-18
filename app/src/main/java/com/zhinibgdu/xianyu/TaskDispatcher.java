package com.zhinibgdu.xianyu;

import android.content.Context;

/** V4.43 fixed-rule task router. No human feature learning. */
public final class TaskDispatcher {
    private TaskDispatcher() {}

    public static void dispatch(Context context, String title) {
        String t = title == null ? "" : title;
        if (VideoTask.matches(t)) {
            VideoTask.run(context);
        } else if (GameTask.matches(t)) {
            GameTask.run(context);
        } else {
            XianyuLocalTask.run(context);
        }
    }
}
