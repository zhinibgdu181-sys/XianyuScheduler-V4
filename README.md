# 闲鱼定时助手 V4

基于 Android 精确闹钟 + 前台 Service + Root/UIAutomator 的本机自动任务助手。

> V4 已移除未实际参与任务执行的 LSPosed/libxposed 层。当前任务链不依赖 Xposed 注入。

## V4 主要修复

- 修复定时闹钟仍发送 `TRIGGER_TASK`、但项目中已经没有接收者的问题。
- 所有入口统一为：`AlarmManager -> AlarmReceiver -> TaskForegroundService -> TaskExecutor`。
- “立即测试任务”也走同一条前台 Service 执行链，避免测试和定时行为不一致。
- 新增 `AppConfig`，统一使用 `task_config`，修复 `cfg` / `task_config` 混用。
- 开机恢复只在用户已启用每日任务时重新设置，不再无条件创建 08:00 闹钟。
- Manifest 增加 `RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM` 和前台服务相关权限。
- 新增“取消每日自动任务”。
- 任务执行由前台 Service 保活，降低 Receiver 返回后进程被系统回收导致任务中断的概率。
- 页面跳转关键路径改为 UI 条件轮询，减少固定等待时间对网速/设备性能的依赖。
- 任务标题与“去完成/领取奖励”按钮的匹配升级为：纵向对齐、左右关系、DOM 层级、空间距离综合评分。
- 保留原有 Root 命令超时、UIAutomator dump 重试、单实例锁、日志和任务验证逻辑。

## 当前执行链

```text
用户设置每日 09:00
        |
        v
  AlarmManager
        |
        v
 AlarmReceiver
   |        |
   |        +--> 先安排下一次闹钟
   v
TaskForegroundService
        |
        v
   TaskExecutor
        |
        +--> 验证 Root
        +--> 启动/确认闲鱼前台
        +--> UIAutomator 获取页面结构
        +--> 导航到闲鱼币任务页
        +--> 扫描并执行任务
        +--> 验证/记录结果
```

## 构建

仓库的 GitHub Actions 使用 Gradle 9.3.1：

```bash
gradle assembleDebug --stacktrace --no-daemon
```

APK 输出通常位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

原 ZIP 没有包含 `gradle/wrapper/gradle-wrapper.jar`，所以直接运行 `./gradlew` 会失败；本地构建时请使用已安装的 Gradle，或补齐标准 Gradle Wrapper 文件。

## 首次使用

1. 安装 APK，并授予 Root 权限。
2. Android 12+：允许“闹钟和提醒/精确闹钟”。
3. Android 13+：建议允许通知，便于看到前台任务状态。
4. 打开助手，先点击“立即测试任务”，确认 Root、闲鱼导航和 UI 识别正常。
5. 再点击“设置每日 09:00 自动任务”。

## 注意事项

- 该项目依赖闲鱼当前版本可由 UIAutomator 读取的文本和节点结构；闲鱼 UI 改版后，候选文案或页面判定逻辑可能需要更新。
- 手机处于安全锁屏时，UI 自动化可能无法继续；V4 不尝试绕过系统锁屏。
- 前台切换到其它 App 时，现有 TaskExecutor 仍会按原逻辑判定为用户中止。
