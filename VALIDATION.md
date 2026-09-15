# V4 校验记录

## 已完成的静态校验

- `AndroidManifest.xml`、`strings.xml`、`activity_main.xml` 均通过 XML 解析。
- 对所有 Java 源文件执行 `javac` 语法解析；由于当前环境没有 Android SDK，Android 类型会产生预期的“package android does not exist”错误，但未发现 Java 语法错误、非法转义、括号/分号错误或项目内方法参数不匹配错误。
- 已清除源码中的旧 `TRIGGER_TASK`、`cfg`、`MainModule`、libxposed/Xposed 运行时依赖。
- `TaskExecutor.run(...)` 只有 `TaskForegroundService` 一个生产入口。
- 定时和立即测试均统一走前台 Service。

## 当前环境无法完成的项目

当前执行环境没有 Android SDK；原始 ZIP 同时缺少：

```text
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

因此无法在这里完成 Android APK 的真实 Gradle 编译。仓库现有 GitHub Actions 已使用系统 Gradle 9.3.1，可以在具备 Android SDK 的 CI 中执行 `gradle assembleDebug`。

## 真机建议测试顺序

1. 安装 APK，授予 Root。
2. 允许“闹钟和提醒/精确闹钟”。
3. 点击“立即测试任务”。
4. 查看日志是否出现：前台服务启动、Root 验证、闲鱼进入前台、导航、候选任务、任务匹配评分。
5. 手动把执行时间临时改到未来几分钟（开发测试时）验证 AlarmReceiver 链路；正式再恢复 09:00。
6. 测试屏幕关闭但未安全锁屏的情况。
7. 测试安全锁屏：应明确记录“设备锁屏，无法执行 UI 自动化”，而不是误判为其它 App 并继续乱点。
8. 重启设备后确认已启用的每日闹钟会恢复；未启用时不应自动创建闹钟。
