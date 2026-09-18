package com.zhinibgdu.xianyu;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskCategoryTest {
    @Test public void routesTaskTitlesFromDeviceLog() {
        assertEquals(TaskCategory.LOCAL, TaskCategory.classify("每日签到"));
        assertEquals(TaskCategory.LOCAL, TaskCategory.classify("点击指定频道好物 (0/10)"));
        assertEquals(TaskCategory.LOCAL, TaskCategory.classify("去浏览福利好物"));
        assertEquals(TaskCategory.VIDEO, TaskCategory.classify("观看视频领取奖励 (0/3)"));
        assertEquals(TaskCategory.GAME, TaskCategory.classify("去消了还想消玩1关"));
        assertEquals(TaskCategory.GAME, TaskCategory.classify("去点点消不停玩１关"));
        assertEquals(TaskCategory.JUMP, TaskCategory.classify("去逛一逛芭芭农场"));
        assertEquals(TaskCategory.JUMP, TaskCategory.classify("去支付宝农场领水果"));
        assertEquals(TaskCategory.JUMP, TaskCategory.classify("点闪购商品领叠加红包"));
        assertEquals(TaskCategory.JUMP, TaskCategory.classify("去妈蚁庄园逛一逛"));
    }
    @Test public void videoRewardDoesNotLaunchGameSolver() {
        assertEquals(TaskCategory.VIDEO, TaskCategory.classify("看视频领取小游戏奖励"));
        assertEquals(TaskCategory.VIDEO, TaskCategory.classify("看广告领取奖励"));
    }
    @Test public void excludesUnknownAndSideEffectTasks() {
        assertNull(TaskCategory.classify(null));
        assertNull(TaskCategory.classify(""));
        assertNull(TaskCategory.classify("未知任务"));
        assertNull(TaskCategory.classify("下载小游戏"));
        assertNull(TaskCategory.classify("发布宝贝"));
    }
    @Test public void legacyRecordingModeCannotStartAutomation() {
        assertNull(TaskCategory.fromMode("learning"));
        assertNull(TaskCategory.fromMode("invalid"));
        assertEquals(TaskCategory.ALL, TaskCategory.fromMode("auto"));
        assertEquals(TaskCategory.ALL, TaskCategory.fromMode(null));
        for (TaskCategory c : TaskCategory.values()) assertSame(c, TaskCategory.fromMode(c.name()));
    }
}
