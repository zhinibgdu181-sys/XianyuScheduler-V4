package com.zhinibgdu.xianyu;

/** Task routing is decided before any task button is clicked. */
public enum TaskCategory {
    ALL("自动任务"), LOCAL("闲鱼本地任务"), VIDEO("视频任务"), GAME("小游戏任务");
    public final String label;
    TaskCategory(String label) { this.label = label; }
    public static TaskCategory fromMode(String mode) {
        if (mode == null || "auto".equals(mode)) return ALL;
        try { return valueOf(mode); } catch (IllegalArgumentException e) { return null; }
    }
    /** Null means unknown, external, or unsupported. */
    static TaskCategory classify(String title) {
        if (title == null) return null;
        String n = title.replaceAll("\\s+", "").replace("還想", "还想").replace("點點", "点点")
                .replace("壹", "1").replace("１", "1").replace("I关", "1关").replace("l关", "1关");
        if (n.isEmpty() || has(n, "未知", "发布", "下载", "安装", "添加闲鱼币到桌面")) return null;
        if (has(n, "视频", "观看", "看15秒", "看广告")) return VIDEO;
        if (has(n, "消了还想", "还想消", "点点消", "消不停")) return GAME;
        if (has(n, "支付宝", "蚂蚁庄园", "农场", "芭芭农场", "头条", "百亿补贴", "淘宝", "飞猪", "高德", "饿了么", "点淘", "试玩",
                "淘特", "百度", "大众点评", "美团", "快手", "一淘", "逛逛", "闪购", "领积分", "赚零花")) return null;
        if (has(n, "消了还想", "还想消", "点点消", "消不停", "水果", "麻将", "小游戏", "玩1关", "玩游戏")) return GAME;
        if (has(n, "签到", "浏览", "逛一逛", "指定频道", "好物", "福利", "商城", "会场", "搜一搜", "搜索", "搜商品", "访问闲鱼币", "领取")) return LOCAL;
        return null;
    }
    private static boolean has(String text, String... keys) {
        for (String key : keys) if (text.contains(key)) return true;
        return false;
    }
}
