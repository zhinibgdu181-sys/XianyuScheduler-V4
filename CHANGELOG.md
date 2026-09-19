
## V4.71
- 修复真人触摸监听中 BTN_TOUCH/TRACKING_ID 先于 ABS_MT_POSITION_X/Y 到达时，手势起点仍为 -1，导致所有后续真人 TAP/SWIPE 被判定为无效坐标而丢弃。
- 首个有效 X/Y 坐标到达后动态绑定当前手势起点；保留 V4.70 的独立监听、人工接管与教学窗口生命周期。

## V4.70
- 修复人工接管后物理触摸监听可能因线程启动竞态过早退出的问题。
- 人工接管后独立保持触摸监听，直到真人教学窗口真正结束。
- 增加触摸监听启动、首次接管、教学结束及无效坐标等诊断日志。
- 保留 V4.69 的手势去重与 WAIT 在完整手势结束后提交逻辑。
# V4.69 - 真人手势去重与坐标归一化

- 真人触摸监听忽略同一手指由 BTN_TOUCH / ABS_MT_TRACKING_ID 产生的重复 down 事件，避免一次点击被拆成多次 WAIT。
- 从触摸设备 ABS 元数据读取 X/Y 最大值，将原始触摸坐标归一化到实际屏幕像素坐标；若设备没有范围元数据，再使用保守回退。
- 保留真人 TAP/SWIPE/WAIT 的被动学习机制，不执行任何真人动作回放。
- 教学经验数据不再直接保存超出屏幕物理范围的原始触摸坐标。

# V4.68 - 修复真人触摸学习监听异常

- 修复真人操作经验序列化中的格式化风险：点击、滑动、等待记录不再依赖 `String.format`，改为类型安全的字符串拼接，避免 `IllegalFormatConversionException: d != java.lang.String` 直接杀死触摸监听线程。
- 对真人 WAIT / TAP / SWIPE 记录增加独立异常隔离；单条经验写入失败不会再退出物理触摸监听。
- 增加实际学习记录日志，人工操作后可以明确看到 WAIT/TAP/SWIPE 是否真正写入经验库。
- 保持原有被动教学约束：不自动重放真人操作、不绕过现有安全校验。

# V4.67 - 真人操作经验大脑

- 新增通用 `HumanOperationExperienceStore`，四类任务共享真人操作经验存储，但经验按任务/页面上下文记录，避免不同任务相互污染。
- 人工接管后开启最多 90 秒的被动真人操作学习窗口；不生成、不回放任何自动点击或滑动。
- 记录结构化点击经验：实际触摸位置、屏幕归一化上下文，并保留目标未知状态，不把一次人工坐标直接固化为自动坐标。
- 记录结构化滑动经验：起点、终点、距离、角度、持续时间、平均速度及页面上下文。
- 记录操作间隔，用于后续学习真人操作节奏。
- 真人经验库使用 45 天 TTL、最多 6000 条记录及约 5 MB 硬上限；超限从最旧记录开始淘汰。
- 前台服务生命周期覆盖通用真人学习窗口；自动执行线程结束后不会立即杀掉真人经验采样。
- 前台服务真正销毁时立即终止真人学习及物理触摸监听。
- 真人经验目前仅作为后续候选操作的经验来源，不能绕过现有安全校验。

# V4.66

- 真人教学生命周期与前台服务绑定：自动任务线程结束后，若真人教学仍在采样，前台服务保持存活直到教学窗口结束；不会因自动线程结束立即杀掉教学线程。
- 前台服务真正销毁时立即使真人教学观察失效并中断线程，避免软件服务退出后真人教学继续运行。
- 保持真人教学被动观察性质，不增加任何自动点击、滑动或回放人工操作。

# V4.65

- 真人学习改为“经验提取”而非“行为回放”：真人点击本身不会进入可执行动作库，也不保存固定点击坐标供后续复现。
- 真人示范只有在后续结构状态连续稳定确认后，才允许作为已验证成功策略写入水果策略经验库；未验证、无进展、状态异常的观察只进入审计记录，不改变策略权重。
- 真人“安全压栈”进一步收紧：单纯看到托盘从1槽变2槽不算成功，必须同时观察到新可下落水果或新的直接对子等后续证据，并且不得把第三槽填满，才允许强化 SAFE_PUSH。
- 新增真人经验准入白名单：只有现有水果策略族可被强化，未知动作标记、手工接管本身以及失败/拒绝状态均不能进入策略记忆。
- 连续三次结构状态无变化时明确记录为“未验证进展”，不把等待或真人误操作误学成成功策略。
- 真人学习线程增加会话代次和中断机制，避免旧的90秒观察线程与下一次自动运行并行或错误关闭 OCR。
- 增加真人经验拒绝、错误状态与稳定后状态的回归测试。

# V4.64

- Fruit game: tighten the pre-tap asynchronous-popup clean-frame reuse window from 3.5s to 300ms. Older observations must pass the popup gate again.
- Human takeover remains an immediate automation stop, but the game now starts a bounded passive 90s teaching window for the fruit task. It never sends synthetic taps during teaching.
- Passive teaching records only verified structural progress: direct pair elimination, tray match, safe push, or tray-unblock. Human outcomes reinforce the existing structural strategy-experience store; raw demonstration transitions are retained in a bounded audit log.
- Add regression tests for human-demonstration strategy classification.

# V4.63

- Fruit game: add a bounded structural strategy-experience store. Similar states are bucketed by remaining count, tray occupancy, object/droppable/blocked scale, unlock opportunities, and continuation evidence instead of fixed coordinates.
- Strategy memory can rank only candidates that already pass the current visual safety gates. It never authorizes a tap or bypasses GameTapPolicy; the historical bias is capped and needs repeated verified outcomes before becoming meaningful.
- Record verified outcomes for tray matching, direct pairs, safe push, dependency push, tray-unblock, last-slot push, and controlled exploration so successful approaches are reinforced and ineffective approaches are downgraded on similar future boards.
- Add a low-priority 2/3-slot controlled exploration path: only an unobstructed, non-cascading fruit that directly unlocks at least one blocked fruit may be tried, with a maximum of three attempts per round and normal post-tap verification/blacklisting.
- Preserve the existing V4.60 explicit failure-page handoff and V4.62 revive-popup handling.

# V4.62

- Fruit game: recognize the post-failure “复活” video overlay separately from the terminal failure page.
- Do not watch the revive video; close only the game overlay's top-right X, verify the overlay is gone, and rebuild the board state.
- Added a dedicated tap-policy whitelist and regression tests for the revive-overlay close action.

# V4.61

- Xianyu: detect the explicit “有新版本可以升级了” overlay and click only its OCR-located “暂不升级” action.
- Apply the same guard during page probing and task scanning so an asynchronous version popup cannot block navigation or be mistaken for an exhausted/unknown task page.
- No coordinate fallback is used for the upgrade dialog; if the explicit dismiss button is not recognized, the automation leaves the page untouched.

# V4.60

- Fruit game: explicitly recognize the rendered failure state (失败 + 返回主页 + retry/challenge evidence) as a terminal game result instead of treating it as an unresolved SAFE_STOP.
- Fruit game: on an explicit failure page, OCR-locate and whitelist only the game-provided 返回主页 button, wait for page departure, then restore the Xianyu task panel.
- Task execution: a confirmed failed game is retired for the current scan only, preventing repeated selection while allowing the executor to continue with other tasks; it is not treated as manual takeover.

## 4.44.4
- 修复槽位数量未变化时仍用估算值伪造“剩余数减2”的问题；此类歧义状态现在强制OCR闭环，失败坐标立即进入本局黑名单。
- 0/1槽无完整对子时允许使用一个空槽做受限探索解阻，避免长时间停在1/3槽；2/3槽只允许同类已可直接点击且无级联风险的严格闭环动作。
- 禁止2/3槽执行多层反向解阻链，避免预测级联未发生后把第三槽填死。
- 复用刚通过弹窗检测的干净截图，移除同一动作前的重复整屏OCR；观察到明确槽位状态迁移后，省去第三张重复确认截图。
- 新增歧义槽位强制OCR、最后一槽安全门和槽位状态迁移回归测试，版本更新为4.44.4（64）。

## 4.44.3
- 配对A进入槽位后优先在原计划B附近做严格局部模板重定位，命中时跳过整张棋盘的连通域、形态学和模板恢复；失败仍回退完整检测。
- 去掉A点击后与慢速截图重复的固定650~800ms等待，继续保留真实槽位确认、四项相似度门槛和真实手指中断。
- 安全停止前增加晚到道具弹窗复核；识别到“解锁所有槽位/使用”等弹窗后关闭固定X并继续重建棋盘。
- 兼容实机OCR把“槽位”误读为“糟位”的情况，版本更新为4.44.3（63）。

## 4.44.2
- 水果栈新增“过桥压栈”：1槽无TOP直配时允许升到2槽，前提是同类已经可直接下落，或本次点击能直接释放同类。
- 2槽允许安全升到3槽，但只接受“同类当前已经可直接下落”的水果；下一轮必须优先匹配新TOP并立即退回2槽，禁止无后手占满第三槽。
- 过桥匹配适度放宽旋转香蕉、桃子等水果的形状一致性要求，同时维持严格颜色直方图、RGB差异和总分门槛。
- 安全压栈日志增加 `mateReady`，明确记录同类后手是否已经可下落；三槽已满时仍只允许TOP直配。
- 增加1→2、2→3安全条件和过桥匹配阈值回归测试，版本更新为4.44.2（62）。

## 4.44.1
- 道具弹窗新增 OCR 文本识别：出现“解锁所有槽位/使用”等组合时立即点击固定关闭 X，并通过新 OCR 确认弹窗确实消失；仍禁止点击“消除/解锁/打乱/使用”功能按钮。
- 静止且无安全动作的棋盘只进行 1 次新帧复核，不再重复 5 次截图与 OCR；Solver 内部恢复结束后不再由外层重复启动第二段。
- 已由稳定槽位变化确认的二消，每 3 次执行一次剩余数 OCR 强校验；临近关卡结束时仍逐次校验，减少正常游戏中的重复整屏 OCR。
- 无安全动作但本段已点击过水果时返回 DIRTY，明确保留现场，继续维持三槽保护与真实手指接管。
- 增加弹窗文本识别、OCR 节流和快速无动作停止回归测试，版本更新为 4.44.1（61）。

## 4.44.0
- 水果点击后槽位连续两帧保持原状时，立即判定点击未生效并重新规划，不再进行最多 8 张截图的重复等待。
- 无效点击位置黑名单保留到棋盘确认发生消除，并覆盖相邻坐标分桶，防止同一颗被遮挡水果因坐标轻微漂移而被重复点击。
- “安全压栈”使用更保守的下落通道检查，边缘受支撑或部分被卡住的水果不再作为可选点击目标。
- 缩短点击后预等待，保留槽位动画复检、三槽保护、真实手指中断和剩余数 OCR 闭环。
- 增加无效点击快速收敛与黑名单坐标漂移回归测试。

## 4.43.9
- 水果游戏启动页直接使用 OCR 按钮中心或安全比例坐标点击“开始游戏”，移除点击前约 2~3 秒的冗余 PNG 截图。
- 将“开始游戏 / 第1关 / 图鉴 / 排行榜”识别为水果游戏页面，避免重复运行小游戏模块时进入未知页面返回循环。
- 单独启动小游戏任务且闲鱼恢复到未完成游戏页时，直接交回对应 Solver；保留真实手指接管与游戏页禁止普通导航保护。
- 增加水果启动页自动点击和页面识别回归测试。

## 4.43.8
- 启动广告兼容“跳过5/跳过3”等倒计时文案，识别后立即点击，避免被当成未知页面后重复返回。
- 任务面板在 OCR 已稳定识别按钮时走快速路径，仅在 OCR 为空或分类结束前调用 UIAutomator 复核。
- 修正相同视口退出计数：第二次确认同一底部视口且完成 XML 复核后立即结束，避免额外重复扫描。

## 4.43.7
- 福利浏览任务改用右下角小黄鱼浮层消失作为完成信号，避免 OCR 从商品内容残留“滑动浏览9s”导致任务卡死。
- 完成确认阶段停止继续滑动，并在确认小黄鱼消失后立即进入任务验证。

## 4.43.6
- 任务分类耗尽时分别提示“闲鱼本地任务 / 视频任务 / 小游戏任务已完成”，并同步写入日志与前台通知。
- 运行开始时把日志、数据目录、诊断截图、任务记录的实际保存路径写入运行日志，便于定位和取日志。
- 修正运行日志启动版本号，改为读取实际 APK 的 versionName，避免日志显示旧版本号。
- 主界面增加系统状态栏/导航栏安全区适配；权限卡片改为可伸缩高度，日志区域调整为更适合小屏的可滚动尺寸。
- Actions 产物名称同步更新为 4.43.6。

## 4.43.5
- 福利浏览任务检测到倒计时完成后立即进入任务面板返回流程，完成阶段停止继续滑动。
- 任务列表检测到连续两次相同视口后判定已到底部，避免在底部反复空滑。

## 4.43.3
- 修复“去浏览福利好物”旧版仅等待 8.2 秒导致 15 秒浏览任务只滑动两次就提前结束；现在完整运行约 15 秒，并约每 2.5 秒自动滑动一次。
- APK 构建增加稳定签名配置入口；配置 GitHub Secrets 后后续版本可使用同一签名，避免升级时出现“安装包和已安装应用签名不一致”。

## 4.43.2\n- 修复“领取成功”被下一轮 OCR 当成可领取按钮而重复点击。\n- 修复“去浏览福利好物”完成后因任务进度延迟刷新被误判为未验证。\n\n# V4.42.1

- 无安全动作的首帧和最终重试帧记录候选中心、TOP直方图分数（六位小数）、门槛结果、位置黑名单及具体阻挡对象，最多记录80个候选。
- 最终无动作帧在回收前保存诊断图，便于对照同一帧的候选日志，定位槽内有香蕉却没有直配的原因。
- 保留 V4.42 恢复与禁止误退机制；不修改视觉识别、匹配阈值、三槽规则或动作选择。这是诊断补丁，不宣称已经修复漏匹配。
- 版本更新为 versionCode 49 / versionName 4.42.1。

# V4.42.0

- 新增最多三次连续观察恢复：截图/OCR异常、缺失剩余数、暂未确认水果页、槽动画不稳定或点击发送失败时重新截图确认；成功观察后清零恢复计数。
- 无安全动作时等待 300–500ms 后重新截图，最多重试三次；出现动作后清零无动作计数。低检测数量诊断保留。
- 点击后沿用原动画等待和槽位观察批次；首轮未确认时再等待并复检一次，不重复发送点击。剩余数任何未闭环结果（含OCR异常）均允许第二次验证。
- TaskExecutor 对 CLEAN/DIRTY/进入页漏识别统一复查，确认仍在水果页时允许一次新的 Solver 恢复；恢复耗尽后仍在水果页或页面未知则保留现场，禁止 BACK、重启与普通导航。
- 任务验证阶段在页面探针之前检查水果保留标志，避免后续空白OCR绕过保护。
- 不改水果识别、HSV/直方图阈值、三槽规则、A+A / A+B、槽优先决策；不包含 V4.43 搜索。
- 增加恢复、无动作、低检测、二次验证及用户中止的回归测试；CI 执行单元测试和 Debug APK 构建。
- 版本更新为 versionCode 48 / versionName 4.42.0。

# V4.41.0

- 以 V4.39（V4.38 稳定视觉 + TOP-only 栈规则）为基线重建，不再沿用 V4.40 的 ROI 像素数组优化和 600px 分析宽度。
- 修复 V4.40 首帧即崩溃：V4.40 只分配 `width * roiHeight` 像素数组，却在 descriptor/template 路径继续用绝对 `y * width + x` 索引，实机与离线回放均可复现 `ArrayIndexOutOfBoundsException`。
- 恢复 V4.38 已验证可运行的 720 宽视觉、全帧像素数组、OCR 和动画等待，不再同时改视觉/导航/等待。
- 保留三槽 LIFO 规则：1槽 TOP=BOTTOM、2槽 TOP=MID、3槽 TOP=TOP；槽位直配只允许匹配当前栈顶。
- 新增保守“安全压栈”：仅 0/1 槽且无 TOP 直配、无完整 A+A 时触发；候选必须存在高置信同类。1槽时进一步要求候选直接挡住自己的同类，避免无后手占满第二格。
- 每次安全压栈只点击 1 个水果，等待稳定后重新截图、重新检测、重新规划，绝不沿用旧坐标连点。
- 2/3 槽禁止单水果压栈；2槽仍只允许 TOP 直配或已经锁定的完整 A+A。
- 离线回归：生产 Java 语法、43项运行断言、真实水果回放、0/1/2/3槽检测、TOP-only 栈规则、OCR 生命周期全部通过。
- 版本更新为 versionCode 47 / versionName 4.41.0。

# V4.38.0

- 按实机规则重建水果小游戏：两个相同水果二消，中央临时槽最多保存 3 个未配对水果。
- 新增 0/1/2/3 槽视觉检测；掉落动画中的非连续槽状态不会触发下一次点击。
- 槽中已有水果时优先寻找棋盘同类直接二消；三槽已满时严格禁止引入新类型。
- 两槽状态允许且仅允许启动已经锁定完整 A/B 的高置信棋盘对子，A 占满第三槽后 B 必须立即与 A 二消。
- 删除“A 点击后旧坐标仍有相似水果 = 点击失败”的错误判据，改为验证真实槽位状态变化。
- A 入槽后全局重新定位 B，并重新检查可下落性；每次二消继续使用 OCR `N → N-2` 闭环确认。
- 加入用户真实截图的空槽/1槽/2槽/3槽回归和槽位直配测试；版本更新为 43/4.38.0。

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
## 4.44.5

- 死局经两次独立建模确认后，限定通过设置菜单 OCR 识别“重新开始”并重开本关；每次任务最多重开两次，避免无穷循环。
- 将已验证的“点击后进槽/仍被阻挡”按归一化位置和外观桶写入运行特征库；后续仅作为安全探索压栈的排序先验，不绕过当前遮挡判断。
- 去除 2/3、3/3 无解时长时间弹窗监听造成的停滞，优先快速确认死局并恢复新局。
- 版本更新为 4.44.5（65）。
## 4.44.6

- 修复 2/3 槽位时已识别出可下落水果却因最后一槽过严而原地 OCR 空转的问题：无直配后允许一次受控第三槽解阻，仍要求无遮挡、无级联且有明确解锁收益或高置信后手。
- 首次建模不再额外做全屏弹窗 OCR，直接进入视觉决策；修正死局重开设置齿轮的实机归一化坐标，避免误点离开游戏。
- 版本更新为 4.44.6（66）。
## 4.44.7

- 特征库改为只记录已验证的进槽/受阻结果，不再参与当前动作排序或动作门槛，历史数据无法导致“不操作”。
- 修复 2/3 槽识别到可下落水果仍被二次保守条件全部过滤的问题：存在可下落目标时必须选择风险最低者推进，禁止继续原地 OCR 循环。
- 版本更新为 4.44.7（67）。
## 4.44.8

- 修复受控第三槽兜底绕过最后一槽安全门的问题；实录中的 `cascadeFollowers=8` 动作现在会被直接拒绝。
- 2/3 槽仅允许“无级联且同类后手当前可下落，或会被本次点击直接释放”的推进，避免主动填满三槽后无水果可配。
- 版本更新为 4.44.8（68）。
## 4.44.9

- 新增思路超时淘汰：同一棋盘15秒无点击且剩余数、槽位、候选规模均不变时，放弃“继续等待同一方案”并切换死局恢复；30秒为硬停止线。
- 同类局面累计3次超时后，后续直接跳过该等待思路；若相似局面后来成功生成动作，自动降低失败计数，避免错误经验永久污染。
- 长期记忆只淘汰“原地等待”策略，不拉黑水果、坐标或有效动作。
- 版本更新为 4.44.9（69）。
