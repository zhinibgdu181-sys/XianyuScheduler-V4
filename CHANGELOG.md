# V4.37.0

- 修复按设备固定像素判断水果点击范围，改用当前截图尺寸；取消水果点击抖动。
- 每对强制新OCR与严格N→N−2确认；未知状态停止，移除重复周期OCR。
- OCR识别器按运行会话复用，取消/超时后延迟释放正在处理的图片。
- 截图统一超时/取消/进程清理，水果截图使用独立临时文件。
- 预计算下落阻挡关系；模板误差超过门槛时提前结束无效计算。
- 修复停用定时后的旧闹钟触发、配置范围、空开机Intent；改善前台服务生命周期和唤醒锁续期。
- 日志裁剪保留完整UTF-8行，清理失败状态更准确。
- 添加可复现离线回归及CI步骤，应用版本42/4.37.0。

# Changelog

## V4.36.3 — 2026-09-17

- 根据用户提供的实机关卡截图重新标定下落几何：水果先竖直自由落体，左右黄色挡板形成 V 形漏斗，中央为坑洞入口。
- 下落路径不再使用固定 `61.5%H` 水平终点；现在按水果当前 `x` 计算对应斜坡接触高度，中央开口则直接视为通向坑洞。
- 修复 V4.36.2 的关键裁切问题：旧检测 ROI 正好截在漏斗外沿附近，最底层水果下半部被裁掉后会被“触碰 ROI 边界”规则丢弃；检测区现延伸到约 `65.5%H`，候选中心仍限制在蓝色棋盘区。
- 碰撞宽度从完整 bbox 改为 32×32 shape mask 的主体横向分位宽度，忽略叶片、尖角和透明边少量外扩，减少相邻水果列被误判为 BLOCKED。
- A 下落后继续重新构建整张棋盘的遮挡图，B 必须在新局面中仍然可直接下落才允许点击。
- 保留 V4.36.2 的 CLEAN/DIRTY 安全停止、0.990 高置信匹配、空闲弹窗守卫和完整日志复制。
- 版本号更新为 4.36.3（versionCode 39）。

# V4.36.2

- 水果求解：加入真实下落可达性建模。每颗水果使用连通域 bbox 构造向下扫掠通道；下方存在重叠水果时标记 `BLOCKED`，绝不点击。
- 水果求解：只在 `DROPPABLE` 水果中寻找高置信同类对子，不再把“位置靠下”误当成“能够掉入坑洞”。
- 水果求解：A 点击后立即重新识别全部水果并重建遮挡图；B 必须在新局面中仍然 `DROPPABLE` 才允许点击。
- 水果诊断：新增 `识别水果 / 可直接下落 / 被遮挡` 统计和阻挡关系示例坐标；A/B 点击后记录对象数变化与原位残留情况。
- 安全策略：若 A 已进入坑位但匹配 B 在新局面被挡住，按 DIRTY 停止，不再盲点其它水果。当前版本仍不进行“单槽占位解锁”的高风险策略。
- 保留 V4.36.1 的完整日志复制、空闲弹窗守卫、CLEAN/DIRTY 安全停止和严格视觉阈值。
- 版本：4.36.2（versionCode 38）。

# V4.36.1

- 水果求解：新增 A 点击生效验证。A 点击后若同一水果仍停在原位，不再盲点 B，而是屏蔽该位置并重新规划。
- 水果求解：B 重定位加入空间约束，不再在全屏范围仅凭外观相似度选择 B；记录原计划坐标、重定位坐标、相似度和位移。
- 水果求解：成功配对后清空旧的不可点击位置记录；保留 V4.36 的 DIRTY/CLEAN 安全停止与空闲弹窗守卫。
- 日志页：新增“复制全部”按钮，直接复制完整 `xianyu_log.txt`，界面仍仅渲染最近 120 行避免卡顿。
- 版本：4.36.1（versionCode 37）。

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
