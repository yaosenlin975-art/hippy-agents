# HippyAgent 修改记录

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
