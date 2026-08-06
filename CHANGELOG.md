# Changelog

## [v0.1.1] — 2026-08-06

> 当日已完成并通过 QA 验收的任务汇总（issue 标识 WS-*）；未闭环任务见下方「待提交 / 待修复」；沙箱至 GitHub 网络不通，所有提交均已本地落地（本地分支 `feat/android-system-integration` ahead 13），推送待网络恢复后执行。

### 🚀 新功能
- feat(security): 统一审批组件按风险分级落地（WS-4，QA 已验收 ✅）— 新增 `ui/chat/UnifiedApproval.kt`（LOW→inline 卡片 / MEDIUM→ApprovalBottomSheet / HIGH+→ApprovalDialog 5 按钮）、`core/security/RiskTranslator.kt`（命令自然语言翻译 + `estimateRisk`/`estimateToolRisk` 风险估计，40+ 规则）；`PermissionRequestDialog`/`InlineApprovalCard`/`OtherSessionApprovalDialog` 改为统一组件入口，四选项审批逻辑未回退；单测 `RiskTranslatorTest.kt` 28 条全绿
- feat(i18n): 多语言硬编码中文提取全部批次完成（WS-10，QA 已验收 ✅，提交 5bba9aa / e44b09a / fdb4351 / b2e868e）— 批次 1（24 key × 4 语言，17 文件）+ 批次 2（43 key × 4 语言，16 文件）+ values-en 9 处 emoji 损坏修复（U+FFFD）；QA 验收提出 Critical-1（批次 1 混入 WS-4 审批组件功能重写致提交树不可独立编译）与 Medium-2（19 个死 key），工程师回退为纯字符串提取并清理死 key（b2e868e），干净检出提交树 `:app:compileDebugKotlin` 通过后收口；清理后计数 zh 1916 / en 1878 / ja 1808 / ko 1808；剩余 core 层展示文本中文（StorageManager/TaskStatus）与 en/ja/ko 翻译缺口（en 38 / ja 108 / ko 108）已记录另开 issue
- feat(a11y): 无障碍与字号主题化批量交付（WS-5，批量部分 QA 已验证）— 硬编码 `.sp` 756→21 处、`MaterialTheme.typography` 引用 0→781；`contentDescription` 补齐 31 处交互图标；Chip 颜色收敛 colorScheme；附带修复 DEF-1 底部导航索引错位、OBS-1 系统字号双重缩放、OBS-2 会话标题硬截断（QA 06:34 复验均通过）；复验新发现 DEF-2（设置页 7 处 Switch 无无障碍标签）+ OBS-3（字号滑块回退口径不一致），状态 `in_progress` 待修（见下方待修复）
- feat(settings): 设置页顶部新增「高频」分组直达技能商店（WS-2，QA 已验收 ✅，提交 603036c）— `SettingsScreen.kt` 顶部新增 `emphasized` 高频分组（技能商店 Store 图标 + 描述 / 模型提供商 Dns 图标 + 描述），设置 Tab 1 次点击直达 `Screen.SkillStore`，现有入口保持兼容；string key × 4 语言

### 🐛 Bug 修复
- fix(chat): `ChatViewModel.getGroupMemberIds` 同步 `runBlocking { flow.first() }` 阻塞 → `AgentRepository.getProfilesSnapshot()` StateFlow 内存快照读取，消除主线程 ANR 风险（WS-9，QA 已验收 ✅）
- fix(ui): 底部导航收件箱/洞察/设置 Tab 索引错位修复（WS-13，QA 已验收 ✅，提交 9fc41cd）— `MainScreen.kt` 三个 Tab `selected`/`onClick` 1/2/3→2/3/4、`AppNavigation.kt` pager 页数 4→5、`onNavigateToAgentConfig` 3→4；DEF-1 矛盾点核对结论：修复早已写入共享工作树但从未提交（a2170cc 声明时 HEAD 仍为 1/2/3），本次提交后文档与代码一致；QA 静态复核通过（模拟器冒烟待集成构建恢复后补做）
- fix(quality): 48 处空 catch 静默吞异常收敛（WS-11，QA 已验收 ✅，提交 a1fe43a）— 48→0：关键链路（Agent 循环/工具执行/网络）补 `Timber.w/e` 日志含异常栈 41 处、收窄 catch 1 处（`PermissionCenterScreen` → `ActivityNotFoundException`）、环境探测/最佳努力操作补 `Timber.d` 或注释说明 6 处；QA 干净 worktree 复编 24 文件 0 错误
- fix(quality): 全仓 `!!` 非空断言收敛（WS-8，QA 已验收 ✅，提交 fff9ef9）— `app/src` 81→提交内 0 处：Agent 运行链路 `requireNotNull` 带消息、工具执行链路 `Tool.getOptionalArgument` 非空重载（不抛异常）、UI 链路安全调用；QA 复编 34 文件 0 错误；备注：提交点剩余 2 处 `!!` 位于并发在途 ChatViewModel 拆分 WIP，主路径补测待拆分收敛后执行（挂起跟踪）

### 📝 文档
- docs: design.md 4 处过期描述修正 — 语言章节字符串数、技能数「共 10 个」、主页 Tab 描述、coding.md 死引用（WS-6，QA 已验收 ✅，提交 56484ac / PR #1）
- docs: design.md 同步 @提及/技能标签 AnnotatedString 内联渲染方案，标注 MatchTagChips 拆分项已取消（WS-3，QA 已验收 ✅）
- docs: design.md 2026-07-05 优化方案更新记录 — 第 1 项导航组织裁决（产品经理：维持底部导航现状，方案 B 作废存档）、第 3 项统一审批组件落地状态、第 5 项无障碍落地状态（WS-1/WS-4/WS-5，2026-08-06 已提交至 git）
- docs: WS-1 已收口 — QA 终检通过（2026-08-06 06:30 置 done）：design.md 裁决记录/实际方案与代码一致（共享工作树更新已随 a2170cc 提交，推送待网络恢复）

### 📥 已提交待 QA 验收（in_review）
- [WS-12] 核心引擎单元测试补齐（提交 fdf10ae，09:00 置 in_review）— 新增 14 个测试文件 / 297 条单测全绿（`testDebugUnitTest` 0 失败）：AgentStatus/MessageQueueManager、AgentGroup 群聊纯逻辑（MentionParser/意图检测）、ToolLoopDetection、TaskApprovalService 审批状态机、ChatTurnConverter、CronJobManager 快照、HybridSearchEngine/RRFFuser/LightweightReranker、ToolApprovalManager/RiskTranslator、ToolGuardian 20+ 安全路径；测试暴露并修复 5 处实现缺陷（GroupChatPrompts 模板 `${agent.id}` 失效、ToolGuardian 危险路径双斜杠永不命中（高危误放行）、ToolLoopDetection 轮询/硬阈值、ChatTurnConverter 发送者切换后 TOOL 结果丢失）；覆盖率报告 `docs/ws12-coverage-report.md`（核心纯逻辑层方法触达率 98.4%，JaCoCo 离线用静态分析替代）；已 @QA 待验收

### 🧹 待提交 / 待修复（工作树在途，未闭环）
- [WS-7] 四个超千行文件拆分（Agent.kt 3080 行 / ChatViewModel.kt 2255 行 / ToolCallBlockView.kt 1208 行 / AgentTurnCard.kt 1020 行）：拆分 4 commit 已提交（f831807 / b8fc52a / 6954eaa / 3ef2b85，均 ≤900 行），但 QA 08:37 验收不通过（**Critical**：`formatDuration` 重复声明致干净检出无法编译）；修复 2bd6cd7（删除 ThinkingBlockView 私有版，统一复用 ToolCallShared internal 版）已提交但交付三步未闭环，状态 `blocked` 待复验重交付；09:00 巡检已第四次派发
- [WS-5] DEF-2 设置页 7 处 Switch 无障碍标签（`SettingsScreen.kt:454/468/499`、`SharedConfigComponents.kt:245/1062`、`UiSettingsScreen.kt:349`）+ OBS-3 字号滑块回退口径：QA 缺陷清单待修，修复后复验即收口置 done；07:06/08:00 两次派发无回复，09:00 巡检第三次派发

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
