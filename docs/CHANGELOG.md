# HippyAgent 修改记录

## 2026-05-28

### 界面

- **附件 Chip 可视化**：ChatInputBar 新增 FlowRow 渲染 FILE/IMAGE 类型 chip，显示类型图标 + label + 关闭按钮，删除时同步清理输入框 `[附件:]` 文本；SKILL/MENTION 类型不渲染
- **通知服务增强**：HippyAgentNotificationService 删除旧渠道 `agent_message`；新增 `activeSessionNotifications` 追踪每个会话的通知 ID；ChatShared.setupForeground 进入前台时自动取消该会话所有通知

### 系统

- **视觉能力检测与图片消息处理**：Agent.buildPrompt 新增 `effectiveModelName` 参数 + `MODEL_VISION_REGEX` 视觉能力检测；有视觉模型编码 base64 ContentBlock.ImageUrl，无视觉模型降级为文本引导 + 提示切换模型；ChatViewModel.sendMessage 新增 `isCurrentModelVisionCapable()` 发送前 Toast 警告
- **Anthropic 协议多模态支持**：ModelClient.toAnthropicJsonRequestBody 修复 contentBlocks 序列化，将 OpenAI 格式 image_url 转为 Anthropic 原生 base64 image source 格式
- **语言切换 P0 修复**：LanguageManager SharedPreferences key 统一为 `hippy_settings`/`language`（与 MainActivity 一致），修复语言切换后重启无法生效的 bug
- **公共记忆导航修复**：AppNavigation.kt 补注册 Screen.CommonMemory 路由 composable，修复点击"公共记忆"闪退

### 文档

- **README 更新**：README.md + README_EN.md 删除"端侧离线推理"章节 + 核心亮点表条目 + 目录条目，章节重编号 6→5...10→9

### 子智能体系统

- **子智能体系统**：新增 SubAgentOrchestrator（子智能体编排器）、SubAgentAggregator（结果聚合器）、SubAgentLoopHandler（循环处理器）、SubAgentTools（子智能体工具集），支持 Agent 派生子 Agent 并行执行任务
- **计划系统**：新增 AgentPlanManager + PlanTools（create_plan/update_plan/get_plan），支持复杂任务分解为可管理子任务并追踪执行状态
- **工具调用修复管道**：新增 ToolCallRepairPipeline（三阶段修复：ScavengeRepair 残留修复 → TruncationRepair 截断修复 → StormBreaker 风暴拆解），自动修复 LLM 输出的异常工具调用
- **持久化任务队列**：新增 HippyJobQueue/HippyJobWorker/HippyJobEntity/HippyJobDao，Room 持久化任务队列，支持优先级排序、幂等提交、子任务级联、超时检测（StallDetector）、速率限制（RateLimiter）
- **Mission 系统**：新增 MissionManager/MissionRunner/MissionModels，任务生命周期管理
- **统计系统**：新增 StatsManager + AgentStatsManager，按 Agent/渠道/日期维度统计使用量
- **引导系统**：新增 OnboardingManager（DataStore 持久化），首次使用引导状态管理
- **版本迁移系统**：新增 MigrationManager，支持注册版本迁移脚本并按版本号顺序执行
- **网络监控**：新增 NetworkMonitor + OfflineMessageQueue，网络断开时缓存用户消息，恢复后自动按序发送
- **智能模型路由**：新增 ModelRouter + BudgetManager + MessageComplexity + RuleClassifier + TurnFailureTracker + EscalationContract + ForceSummary，基于消息复杂度、预算、失败追踪的智能模型选择
- **端侧模型管理**：新增 OnDeviceModelManager/OnDeviceModelStore/OnDeviceModelCatalog/LiteRTLMEngine/LiteRTLMModelClient/HuggingFaceSearchApi/HuggingFaceMirror，端侧模型下载、配置、推理一体化
- **技能策展引擎**：新增 CuratorEngine（三阶段 Pipeline 与 DreamWorker 对齐：LIGHT 清理 → DEEP 提取合并优化 → REM 偏好分析归档），CuratorMiddleware 注入 Agent 中间件链，SkillExtractor/SkillMerger/SkillOptimizer 自动技能管理
- **提示词系统**：新增 PromptBuilder + StandingOrdersManager，结构化提示词构建与常驻指令管理
- **对象池**：新增 ObjectPool + FastId，减少 GC 压力
- **插件系统**：新增 PluginManager + SkillValidator + UrlDownloader，插件安装校验与下载

### 中间件

- **ClarificationMiddleware**：拦截 ask 澄清工具调用，提取结构化澄清请求并转为直接回复
- **DanglingToolCallMiddleware**：检测并修复悬空工具调用（assistant 发出 tool_call 但后续无对应 tool result），防止死循环
- **DeferredToolFilterMiddleware**：过滤延迟加载工具的提前调用
- **SubagentLimitMiddleware**：子智能体并发数限制（AtomicInteger 计数器）
- **ThreadDataMiddleware**：注入工作目录等线程级数据到中间件上下文

### 工具

- **WebSearchTool**：联网搜索工具（搜狗免费搜索，无需 API Key）
- **WebFetchTool**：网页抓取工具（支持轻量 HTTP 和 WebView JS 渲染两种模式）
- **BrowserAutomationTool**：浏览器自动化工具（单入口多 action 设计，支持点击/输入/截图等）
- **ToolSearchTool**：延迟工具搜索工具（select:精确选择 / +关键词必含 / 正则搜索）
- **AskClarificationTool**：澄清请求工具

### 工具基础设施

- **DeferredToolRegistry**：延迟工具注册表，按需加载工具定义
- **ToolTTLManager**：工具生命周期管理，支持 TTL 倒计时自动过期隐藏
- **ToolAccessController**：工具访问控制器，支持 Agent 级别白名单/黑名单和所有权策略
- **FileLockManager**：文件锁管理器（Mutex + 超时），防止并发文件操作冲突

### 记忆系统

- **混合搜索引擎**：新增 HybridSearchEngine（FTS4 + RRF + LightweightReranker），Phase 1 实现，预留语义向量扩展
- **RRF 融合器**：新增 RRFFuser，多源搜索结果 Reciprocal Rank Fusion
- **迭代式摘要合并**：新增 IterativeSummaryMerger，上下文压缩时迭代合并已有摘要与新消息而非简单拼接
- **相册记忆**：新增 GalleryMemoryScanner + GalleryMemoryStore，扫描相册提取记忆
- **用户画像**：新增 UserProfileManager，用户偏好画像管理
- **查询意图分类**：新增 QueryIntentClassifier，识别搜索/回忆/事实核查等查询意图
- **主动记忆管理**：新增 ProactiveMemoryManager + ProactiveWorker，主动整理和更新记忆
- **记忆数据库**：从 secondbrain.db 迁移到 commonmemory.db，版本升级到 v4（FTS4 重建为 memory_id 外部内容表）

### 通知系统

- **HippyAgentNotificationService**：通知服务
- **BadgeManager**：会话未读角标管理
- **ForegroundSessionTracker**：前台会话追踪
- **NotificationOverlayService**：通知覆盖层服务
- **NotificationSettingsManager**：通知设置管理

### 数据库

- **主数据库**：从 androidpaw.db 更名为 hippy.db，版本从 v15 升级到 v20
  - v15→v16：新增 agent_groups 表（群组持久化到 Room）
  - v16→v17：sessions 新增 hidden 字段
  - v17→v18：agent_groups 新增 llmSelectorProviderId/llmSelectorModelName；sessions agentId 'default' → 'default-agent'
  - v18→v19：paw_jobs/paw_inbox 重命名为 hippy_jobs/hippy_inbox
  - v19→v20：Session 统计和压缩拆分为独立表（session_stats, session_compression），sessions 表精简移除冗余字段
- **Room 版本**：从 2.6.1 升级到 2.7.2

### 依赖

- 新增 LiteRT-LM（com.google.ai.edge.litertlm:litertlm-android:0.12.0）端侧模型推理
- 新增 jieba-analysis（com.huaban:jieba-analysis:1.0.2）中文分词

## 2026-05-20

### 界面

- **聊天主界面**：AgentTurnCard 提取共享组件消除双渲染模式重复代码：AgentHeader（头像名称+状态点）、ErrorBubble（错误内容卡片）、ContentBubble（正常内容气泡，含群聊彩色竖线），移除 AgentStatusChip 死代码
- **输入栏**：ChatInputBar 参数封装为 ChatInputUiState（@Immutable 数据类）+ ChatInputCallbacks（接口），12 个参数简化为 state + callbacks + modifier 三参数；ChatScreen/GroupChatScreen 调用方同步更新
- **权限请求对话框**：审批选项从 3 个扩展为 4 个（新增「不再允许」持久拒绝），PermissionTurnCard 新增安全发现摘要展示
- **群聊界面**：修复 mentionOnly 模式下广播消息静默丢弃无反馈的 bug（开启「只接收@信息」后无@广播消息添加系统提示告知用户需要@目标智能体）；修复 @mention 渲染三个 bug：MENTION_REGEX 去掉贪婪空格匹配防止 "@B 你好" 被渲染为整块 chip、输入框重复选择同一 agent 时 FlowRow 去重
- **会话列表界面**：AppNavigation.kt（~1098行）按职责拆分为 3 个文件：MainScreen.kt、DialogHost.kt、AppNavigation.kt
- **创建 Agent 界面**：createAgent 从 runBlocking 同步改为 viewModelScope.launch 异步，避免主线程 ANR；模型选择框风格统一（fallback→备用模型，标题字号统一13sp，内容字号统一14sp，三个选择器均添加图标 SmartToy/Build/Tune，占位提示统一）
- **Agent 配置界面**：提取共享配置组件到 SharedConfigComponents.kt，AgentConfigSection 和 AgentConfigScreen 共用组件
- **技能池界面**：技能列表 items 中 find 线性查找改为 remember associateBy Map 查找（性能优化）
- **洞察界面**：修复技能排行数据始终为空的 bug（SKILL_NAME_REGEX 无法匹配 arguments 字段中转义的 \"skill_name\" 格式）
- **收件箱界面**：（无额外修改，审批请求 Tab 从仅显示 pending 改为显示所有状态审批记录已在 05-19 完成）
- **安全规则界面**：新增安全规则管理界面
- **设置主界面**：系统与权限分组新增「安全规则」入口
- **通知弹窗服务**：（无额外修改，已在 05-19 新增）

### 系统

- **会话存储**：AppDatabase.kt（~614行）按 Entity/DAO 拆分为独立文件；数据库迁移提取到 DatabaseMigrations.kt；AppDatabase.kt 精简为仅 @Database 注解+抽象类（~38行）；exportSchema 改为 true
- **ModelProvider 匹配器**：从 AgentModule.kt 和 SecurityModule.kt 提取重复逻辑为独立 object
- **收件箱系统**：ToolGuardian DI 注册增加 context 参数；新增 ToolApprovalManager DI 注册；ToolGuard/ToolApprovalService DI 注册标记 @Deprecated；ToolApprovalReceiver 实现四按钮审批；删除 ToolGuard.kt 和 ToolApprovalService.kt 废弃文件；ApprovalDecision 统一为 inbox 版本枚举；ToolApprovalManager._pendingApprovals 添加 Mutex 并发保护；ToolApprovalManager 规则存储从 SharedPreferences 迁移到 DataStore；SecurityRulesViewModel 改用 StateFlow + viewModelScope 异步调用
- **MCP 工具注册**：新增 unregisterClient 方法，MCPClientManager.removeClient 时自动清理已注册工具
- **群聊广播预检评分系统**：修复 BroadcastPreScorer 注入问题：GroupMessageRouter 改用 broadcastPreScorerProvider lambda 动态获取 scorer
- **群聊编排器**：合并 GroupChatOrchestrator 到 AgentGroup；每个 Agent 独立 sessionId 隔离工作流；统一群聊上下文压缩；GroupChatOrchestrator 标记 @Deprecated；processMessage 重构为 try-finally 统一管理
- **Agent 信息系统**：（无额外修改，已在 05-19 新增）
- **Agent 群组管理**：AgentGroupManager 移除 GroupChatOrchestrator 依赖；GroupRegistry 新增 renameGroup 方法；GroupRegistry 从 JSON 文件持久化迁移到 Room 数据库；GroupLifecycleManager 合并到 AgentGroupManager；AgentGroupManager 新增 LLMSpeakerSelector/GroupCollaborationProtocol 构造函数注入
- **群聊 @ 机制核心组件**：移除 ContextWindowOptimizer；DisplayNameFuzzyMapper 新增 ConcurrentHashMap 缓存 + 30s TTL
- **群组协作协议**：shouldStopPingPong 集成到 AgentGroup.detectPingPong()；hasNewTask 从 content.length > 50 改为关键词检测；hasQuestion 从 contains("?") 改为疑问词检测
- **权限管理器**：新增自定义工具权限方法，使用 DataStore 替代 SharedPreferences；PermissionViewModel 移除 Context 和 SharedPreferences 依赖，改用 PermissionManager 委托
- **认证管理**：revokedJtis 添加 LinkedHashSet 上限 10000 淘汰机制
- **应用初始化**：AppModule.kt（~955行）按领域拆分为 6 个 DI 模块；HippyAgentApp.kt 简化为 modules(appModule)

## 2026-05-19

### 界面

- **群聊界面**：@mention chip 渲染、群聊头像名称始终显示；@mention 匹配同时支持 displayName 和 agentId；UserTurnCard 传递 agentProfiles 到 MessageContentWithAttachments；isImageAvatar 判断从仅识别 / 和 content:// 前缀改为 isNullOrBlank，支持 HTTP/HTTPS 等任意有效 URL
- **核心文件编辑界面**：修复核心文件列表只显示已存在文件的 bug（默认文件列表改为 CoreFileType 枚举）；修复文件栏只显示 SOUL.md 的 bug；文件栏底色改为 surfaceContainerLow 增强视觉区分，文件列表添加右侧垂直滚动条
- **模型供应商界面**：新增模型选择抽屉右上角设置按钮；修复不同供应商同名模型同时勾选的 bug（选中判断增加 providerId 匹配）；修复模型选择抽屉设置按钮点击无反应的 bug；修复不同供应商同名模型同时勾选的 bug（选中判断改为 providerId 严格匹配）
- **洞察界面**：工具排行显示中文名；新增技能排行统计
- **收件箱界面**：审批请求 Tab 从仅显示 pending 改为显示所有状态审批记录，按状态区分视觉样式
- **界面交互展示页面**：新增 prototype.html 交互展示页面
- **审批覆盖层**：改为前台服务，确保在其他应用上方显示；AccessibilityController 自动启动服务
- **通知弹窗服务**：新增

### 系统

- **收件箱系统**：SendNotificationTool 发送通知时同步写入收件箱；ToolGuard/ToolApprovalService 审批流程同步到 ApprovalService 持久化
- **群聊广播预检评分系统**：初始实现
- **Agent 信息系统**：新增 agent_info 工具系统（AgentInfoTool + AgentInfoRepository + AgentCardParser + AgentInfoCache + AgentProfile 扩展字段）
- **群聊 @ 机制核心组件**：新增群聊 @ 机制四大核心组件；Phase 2 集成
- **Agent 注册表**：注册到 Koin DI（AppModule），从死代码激活
- **Agent 会话管理器**：注册到 Koin DI（AppModule），从死代码激活
- **Agent 消息路由器**：注册到 Koin DI（AppModule），从死代码激活
- **主动触发器**：注册到 Koin DI（AppModule），从死代码激活
- **团队任务交接**：注册到 Koin DI（AppModule），从死代码激活
- **处理标记器**：注册到 Koin DI（AppModule），从死代码激活
- **群组协作协议**：注册到 Koin DI（AppModule），从死代码激活

### 清理

- 删除旧 `com.lin.hippyagent.skills` 包（SkillManager.kt + SkillManagerImpl.kt），零引用
- 删除 `core.backup.ZipHelper`（未使用），保留 `core.plugin.ZipHelper`（含 Zip Bomb 防护）
- 删除 `ui.settings.general.BackupRestoreScreen`（简化版），保留 `ui.settings.BackupRestoreScreen`
