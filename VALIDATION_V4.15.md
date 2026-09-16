# V4.15 validation

- `TaskExecutor.java` + `ScreenOcr.java` + `TaskStatusReceiver.java` 使用现有 Android/ML Kit stub 执行 `javac`：PASS（exit 0）。
- `versionCode = 15`, `versionName = 4.15.0`。
- V4.13 学习库 `xianyu_learning_v413` 保持兼容。
- V4.14 `EDGE_BACK_RIGHT/LEFT -> TASK_PANEL` 学习返回决策保留。
- 完成验证：领取类最多 2 次，普通任务最多 3 次；优先复用最近任务面板 OCR。
- 明确时长任务（如 `15秒`、`30s`）等待下限不会低于文字要求。
- 普通任务间隔使用小范围动态等待，避免固定节奏，同时保留 `userAborted` 硬停止检查。
- 第一次失败/未验证不阻塞截图；累计异常后恢复诊断截图。
- 人工接管最终状态为 `ABORTED`。

## 真机重点观察

1. 启动日志应显示 `闲鱼任务开始 · V4.15`。
2. 返回任务面板后应出现 `[快节奏V4.15] 真实完成验证#1复用刚才任务面板OCR`（条件满足时）。
3. 普通跳转任务策略日志应显示 `[策略V4.15] ... adaptive=xxxxms`，不同任务/不同轮次不应完全相同。
4. 若长期停在 `android` 系统中转页，应看到 `系统中转页持续 ... 提前进入返回验证`。
5. 已验证任务后应很快进入下一次 `快速扫描`，不再固定停顿数秒。
6. 人工触摸停止后状态应为 `ABORTED`，不能再报正常 `FINISHED`。
