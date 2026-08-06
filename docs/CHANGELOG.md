# Changelog

## [v0.1.1] — 2026-08-06

> 当日已完成并通过 QA 验收的任务汇总（issue 标识 WS-*）；已提交但待推送/待验收的改动见下方「已提交待验收」；沙箱至 GitHub 网络不通，所有提交均已本地落地，推送待网络恢复后执行。

### 🚀 新功能
- feat(security): 统一审批组件按风险分级落地（WS-4，QA 已验收 ✅）— 新增 `ui/chat/UnifiedApproval.kt`（LOW→inline 卡片 / MEDIUM→ApprovalBottomSheet / HIGH+→ApprovalDialog 5 按钮）、`core/security/RiskTranslator.kt`（命令自然语言翻译 + `estimateRisk`/`estimateToolRisk` 风险估计，40+ 规则）；`PermissionRequestDialog`/`InlineApprovalCard`/`OtherSessionApprovalDialog` 改为统一组件入口，四选项审批逻辑未回退；单测 `RiskTranslatorTest.kt` 28 条全绿
- feat(a11y): 无障碍与字号主题化（WS-5）— 硬编码 `.sp` 756→21 处、`MaterialTheme.typography` 引用 0→781；`contentDescription` 补齐 31 处交互图标（96→60 null，剩余装饰性）；ChatInputBar/ProviderChips Chip 颜色收敛 colorScheme；附带修复 DEF-1 底部导航索引错位（pager 4→5 页 + tab 索引修正，修复前设置页不可达）、OBS-1 系统字号双重缩放、OBS-2 会话标题 `take(5)` 硬截断。QA 复验（06:34）确认三个修复项全部通过、设置页 1.3x 走查无截断/重叠；复验新发现 DEF-2（设置页 7 处交互 Switch 无无障碍标签）+ OBS-3（字号滑块回退口径不一致，拖动会叠加 1.69x），状态回退 `in_progress` 待修
- feat(settings): 设置页顶部新增「高频」分组直达技能商店（WS-2，QA 已验收 ✅，提交 603036c）— `SettingsScreen.kt` 顶部新增 `emphasized` 高频分组（技能商店 Store 图标 + 描述 / 模型提供商 Dns 图标 + 描述），设置 Tab 1 次点击直达 `Screen.SkillStore`，现有入口（AgentConfigSection / SkillPoolScreen 右上角）保持兼容；string key × 4 语言

### 🐛 Bug 修复
- fix(chat): `ChatViewModel.getGroupMemberIds` 同步 `runBlocking { flow.first() }` 阻塞 → `AgentRepository.getProfilesSnapshot()` StateFlow 内存快照读取，消除主线程 ANR 风险（WS-9，QA 已验收 ✅）
- fix(ui): 底部导航收件箱/洞察/设置 Tab 索引错位修复（WS-13，已提交 9fc41cd 待验收）— `MainScreen.kt` 三个 Tab `selected`/`onClick` 1/2/3→2/3/4、`AppNavigation.kt` pager 页数 4→5、`onNavigateToAgentConfig` 3→4；DEF-1 矛盾点核对结论：修复早已写入共享工作树但从未提交（a2170cc 声明时 HEAD 仍为 1/2/3），本次提交后文档与代码一致

### 📝 文档
- docs: design.md 4 处过期描述修正 — 语言章节字符串数、技能数「共 10 个」、主页 Tab 描述、coding.md 死引用（WS-6，QA 已验收 ✅，提交 56484ac / PR #1）
- docs: design.md 同步 @提及/技能标签 AnnotatedString 内联渲染方案，标注 MatchTagChips 拆分项已取消（WS-3，QA 已验收 ✅）
- docs: design.md 2026-07-05 优化方案更新记录 — 第 1 项导航组织裁决（产品经理：维持底部导航现状，方案 B 作废存档）、第 3 项统一审批组件落地状态、第 5 项无障碍落地状态（WS-1/WS-4/WS-5，2026-08-06 已提交至 git）
- docs: WS-1 已收口 — QA 终检通过（2026-08-06 06:30 置 done）：design.md 裁决记录/实际方案与代码一致（共享工作树更新已随 a2170cc 提交，推送待网络恢复）

### ✍️ 已提交待验收（本地 commit 已落地，待推送 + QA 验收）
- [WS-11] 空 catch 收敛：48→0（补 Timber.w/e/d 日志 41 处、收窄 catch 1 处、注释说明 6 处），提交 a1fe43a；待补交付三步（@QA + `in_review`）
- [WS-10] 多语言硬编码中文提取全部批次完成：批次 1（24 key × 4 语言，17 文件）+ 批次 2（43 key × 4 语言，16 文件）+ values-en 9 处 emoji 损坏修复（U+FFFD），提交 5bba9aa / e44b09a / fdb4351；07:00 已交付 @QA，状态 `in_review`，剩余 core 层展示文本硬编码中文已记录为后续事项
- [WS-13] 底部导航 Tab 索引修复（见上「Bug 修复」），提交 9fc41cd；待补交付三步

### 🧹 待提交（代码已在本机共享工作树，待工程师按交付流程提交推送）
- [WS-8] `!!` 非空断言收敛：`app/src` 81→0（新增 `Tool.getOptionalArgument` 非空重载、`requireNotNull` 带消息等），修复 6 处 HEAD 既有编译错误；改动未提交，待验证 + 单独 commit + `in_review` + QA 验收
- [WS-12] 核心引擎单元测试补充（Agent/AgentGroup/审批链/混合检索/TurnConverter 等），新增测试待修复编译并跑 `testDebugUnitTest` 全绿后交付

## [v0.1.0] — 2026-05-20

### 🚀 新功能
- feat: 新增完整交互原型展示页面 prototype.html（40+ 界面） (6a17b41)
- feat: 完成多智能体群组聊天系统核心功能开发 — 多 Agent 群聊、@mention、消息广播 (d2b743c)
- feat: 完成多批次功能迭代与 bug 修复 — 群聊 UI/UX、会话管理、文件操作等多项改进 (52b4671)

### 🐛 Bug 修复
- fix(chat): @mention chip 改为内联插入输入框，去除 MENTION prefix 前置渲染，修复 chip 过滤逻辑错乱 (6a17b41)
- fix(chat): mentionOnly 模式下广播消息无 @ 时添加系统提示告知用户需要 @ 目标智能体 (6a17b41)
- fix(chat): 消息左侧竖线颜色绑定 senderAgentId 确保按智能体着色 (6a17b41)
- fix(chat): 群组会话按 lastUpdatedAt 降序排列，时间显示格式统一 (6a17b41)
- fix(insights): 技能排行正则修复，支持 JSON 中转义字符场景 (6a17b41)
- fix(tools): 文件路径重定向仅对相对路径执行，绝对路径直接返回 (6a17b41)

### 🎨 样式
- style(agent): 模型选择框风格统一 — 添加 SmartToy/Build/Tune 图标，统一标题/内容字号，优化占位提示文案 (6a17b41)

### 📝 文档
- docs: add agent_info tool design document (244b9a0)
- docs: design.md 补充 @mention 修复与群组排序记录 (6a17b41)
- docs: edits.md 更新最新修改日志 (6a17b41)

### 🧹 杂项
- chore: 初始化项目基础结构与资源文件 (84516a2)
- chore(gradle): 更新 gradle-wrapper.properties (6a17b41)
- chore(gitignore): 添加 gradle-*-bin.zip 排除规则 (6a17b41)
