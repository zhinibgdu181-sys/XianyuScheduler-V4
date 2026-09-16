# XianyuScheduler

Android Root 闲鱼任务自动执行助手。

当前版本：**V4.24.0**（versionCode 24）

## 当前功能

- ROOT 自动任务执行，支持 KernelSU 环境。
- 首页显示 ROOT 授权状态：未授权灰色，授权后绿色。
- 首页提供精确闹钟权限开关，并与 Android 系统真实授权状态同步。
- 每日定时任务、立即测试、真人示范学习、运行日志与清空日志。
- 水果/麻将小游戏使用视觉求解器；只允许操作游戏对象，不点击“打乱/消除/使用”等功能按钮。
- 自动导航与真人学习经验分离，学习记录不会被无条件回放。

## 权限

ROOT 必须由用户在 **KernelSU → 超级用户** 中明确给“闲鱼定时助手”授权；应用不会自行绕过授权。

Android 12 及以上的精确闹钟权限通过首页“闹钟权限”开关进入系统授权页。返回应用后状态会自动刷新。

## 构建

- package: `com.zhinibgdu.xianyu`
- Java 17
- compileSdk 35
- minSdk 26
- targetSdk 35

各版本详细变更见 `CHANGELOG.md`。
