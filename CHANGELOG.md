# V4.36

- Fruit game: split safe stop into `SAFE_STOP_CLEAN` and `SAFE_STOP_DIRTY`; DIRTY states never start a second solve segment.
- Fruit game: treat the long-idle “解锁/消除/打乱” recommendation dialog as an asynchronous blocking overlay.
- Fruit game: close only the dialog's own X at about `(0.866W, 0.281H)`, verify that the dialog actually disappeared, then discard stale fruit coordinates and re-plan.
- Fruit game: add popup checks after A and after B; ambiguous A-stage popup states are stopped as DIRTY.
- Fruit game: tighten pair threshold to `0.990`, remove 0.93/0.88/0.83 fallbacks, and tighten B re-acquisition to reduce false pairs observed in real logs.
- Fruit game: save a diagnostic screenshot when a visually selected pair fails the `remaining N -> N-2` verification.
- Recovery: fruit-game exit now allows two KEYCODE_BACK attempts (first may only close an idle popup), then restarts IdleFish as a final fallback before rebuilding navigation.
- Existing Mahjong solver and non-game task behavior are retained.

# V4.29

- Fruit game: top no-drop zone and bottom UI are hard forbidden touch zones.
- Fruit detection only accepts complete sprites in the middle droppable region.
- Added second-layer coordinate guard and explicit ALLOW/BLOCK logs.
- Fruit SAFE_STOP no longer uses edge swipe; it uses KEYCODE_BACK to avoid touching game controls.
- Existing V4.28 UI, records, scheduling, learning and Mahjong logic retained.

# Changelog

## 4.26.0

- 重构底部导航为四个固定入口：`首页 / 今日 / 自动化 / 日志`，采用深色悬浮圆角栏与蓝色选中态。
- 首页集中显示 ROOT、精确闹钟权限、当前状态与功能说明。
- 新增“今日任务”页：仅统计当天已经验证为 `SUCCESS` 的闲鱼任务；清空运行日志不会清空今日完成记录。
- “自动化”页集中每日定时、立即运行、停止任务与真人学习模式。
- “日志”页独立显示最近 120 行运行日志，并保留刷新/清空操作。
- 水果视觉识别放宽过窄的候选区域和尺寸阈值，并在检测水果数量异常偏少时自动保存视觉诊断图。
- 水果游戏仍严格禁止点击 `打乱 / 消除 / 解锁 / 使用` 等功能按钮，只允许点击水果对象。
- 水果求解器 `SAFE_STOP` 后不再直接终止整轮任务；会受控退出小游戏并继续扫描其它任务。只有无法安全退出时才保留现场并停止。
- 运行日志版本号统一更新到 V4.26。

## 4.25.0

- 增加固定底部导航，将原来的单页功能拆分为多个页面。

## 4.24.0

- 增加 ROOT 权限状态卡片与精确闹钟权限开关。

## 4.23.0

- 修复闲鱼币主页误判为麻将小游戏的问题，并优化恢复到旧子页面时的导航。

## 4.22.0

- 小游戏对象白名单：水果只点水果，麻将只操作麻将牌；禁止点击游戏功能按钮。

## V4.28.0
- 每日定时改为 TimePicker，自定义小时和分钟，不再固定 09:00。
- 任务记录页支持点击日期选择历史日期并查询该日 SUCCESS 任务。
- 闹钟权限卡移除 Switch，整张卡片点击跳转系统精确闹钟权限页，返回自动刷新。
- 数据/文件目录卡从自动化页移动到首页底部。
- 自动化页保持：任务运行 → 真人学习 → 每日定时。
