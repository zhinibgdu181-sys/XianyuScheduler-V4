# V4.14 validation

- `TaskExecutor.java` + `ScreenOcr.java` 使用现有 Android/ML Kit stub 执行 `javac`：PASS（exit 0）。
- `versionCode = 14`, `versionName = 4.14.0`。
- 继续读取 `xianyu_learning_v413`，升级安装后兼容 V4.13 学习数据。
- 自动学习回放范围：仅 `EXTERNAL_APP -> EDGE_BACK_RIGHT/LEFT -> TASK_PANEL`。
- 同 App 示范允许 count>=1；跨 App 泛化要求 count>=2。
- 学习手势时间必须 80–1000 ms。
- 学习动作执行后必须通过前台包 + OCR/XML 验证 TASK_PANEL。
- learned TAP / UNKNOWN_XIANYU 不参与自动回放。
- 返回一次未验证成功时不会继续盲返回；转入已有恢复逻辑。
- 人工接管 `userAborted` 检查保留。

## 真机首轮验证

1. 在 V4.13 上直接覆盖安装 V4.14，不要卸载或清数据。
2. 先不要重新训练，直接执行一次含外部 App 跳转的任务。
3. 若进入大众点评等已示范 App，日志应出现 `[学习决策V4.14] 命中案例#...`。
4. 返回后只有检测到任务面板才出现 `✅ post_kind=TASK_PANEL 验证通过`。
5. 若不满足条件，应出现“没有满足阈值的安全返回经验；使用原恢复逻辑”，不能盲目复用。
