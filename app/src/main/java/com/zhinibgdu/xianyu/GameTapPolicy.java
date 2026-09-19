package com.zhinibgdu.xianyu;

/** Coordinates are normalized to the screenshot dimensions, never a device model. */
final class GameTapPolicy {
    private GameTapPolicy() {}
    static boolean allows(int x, int y, int width, int height, String reason) {
        if (width <= 0 || height <= 0 || x < 0 || y < 0 || x >= width || y >= height) return false;
        double nx = x / (double) width, ny = y / (double) height;
        if ("水果游戏-开始游戏".equals(reason))
            return nx >= 560.0/1440 && nx <= 880.0/1440 && ny >= 2180.0/3120 && ny <= 2500.0/3120;
        if ("水果游戏-关闭道具弹窗".equals(reason)
                || (reason != null && reason.startsWith("水果游戏-继续关闭道具弹窗")))
            return nx >= 1160.0/1440 && nx <= 1325.0/1440 && ny >= 740.0/3120 && ny <= 960.0/3120;
        if (!"水果游戏-配对A".equals(reason)
                && !"水果游戏-配对B".equals(reason)
                && !"水果游戏-槽位匹配".equals(reason)
                && !"水果游戏-安全压栈".equals(reason)) return false;
        return ny >= .115 && ny <= .625;
    }
}
