# HippyAgent 设计文档

> 项目：HippyAgent Android 平台 AI 智能体应
> 架构：MVVM + Clean Architecture | Koin DI | Room | Jetpack Compose | Kotlin Coroutines
> 包名：com.lin.hippyagent

---

# 界面

## 聊天与会

### 聊天主界

- **功能描述**：用户与 AI Agent 的核心交互界面，支持流式消息展示、工具调用可视化、思考过程展
- **交互逻辑**
  1. 用户在输入栏输入文本/语音，发送后进入 Agent 处理循环
  2. Agent 回复以流式方式逐字渲染，支Markdown 渲染；sanitizeMarkdown 处理流式场景下未闭合的代码块（反引号奇数补全、三反引号奇数补闭合）
  3. 工具调用以卡片形式展示（名称、参数、状态、结果），可展开/折叠；参数和结果内容使用统一的 Surface 卡片容器（surfaceVariant 半透明底色 + RoundedCornerShape(6.dp) + 6.dp 内边距），相同排版（11.sp Monospace + lineHeight 14.sp + maxLines 无限）；工具参数为空或仅为空 JSON 对象 {} 时不显示参数栏；工具开始执行时自动展开，执行完成后延迟 800ms 自动折叠（FAILED 不自动折叠）；Turn 结束后强制折叠所有 tool，不受用户手动展开影响；FAILED 工具折叠时在摘要下方显示失败原因首行预览（⚠ 前缀，error 色，最多 40 字符）；工具执行异常时 catch 块返回 ToolResult(success=false, error="工具执行异常: ...")，确保失败原因不丢失；resultText 使用 takeIf { it.isNotBlank() } 防止空字符串覆盖 error 信息；工具复制操作（长按菜单复制参数/结果/参数和结果，以及复制图标按钮）均会在剪贴板内容前添加【工具名】前缀
  4. 文件工具调用采用紧凑卡片样式
     - read_file：前缀 + 文件+ 斜体路径，失败时下方显示 原因
     - write_file/edit_file：前缀 + 文件+ 斜体路径，点击展开显示 diff 视图；失败时文件名后显示原因，展开显示参数
     - append_file：前缀 + 文件+ 斜体路径，结果直接显示追加内容（"Appended to" 行和 "+ " 前缀）；失败时文件名后显示原因，展开显示参数
     - delete_file：DEL 前缀 + 文件+ 斜体路径；失败时文件名后显示原因，展开显示参数
  5. 思考过程（Thinking Block）以可折叠区域展示，流式输出时自动展开，Turn 结束后强制折叠为摘要（不受用户手动展开影响）；折叠时显示首行内容预览（最多50字符）；标题栏显示思考耗时（如"思考过程 · 1.2s"）
  6. 流式输出效果（仅普通聊天，群聊不启用）：文本和思考内容末尾显光标字符，标识智能体正在生成；元数据栏（token/模型/延迟）使AnimatedVisibility fadeIn+expandVertically 平滑出现；元数据栏内容：↑输入Token ↓输出Token · 缓存读取/写入Token · API调用次数 · 模型名称（fallback时显示🔄前缀） · 延迟时间；上下文比例进度条：显示当前上下文Token数/最大上下文窗口，>50%时黄色，<=50%时绿色，进度条旁显示百分比文字；移animateContentSize 避免流式期间弹簧动画持续触发 GC
   6. 过程折叠：轮次结束后（agent IDLE），整个轮次中所有 thinking + tool + 中间 message 合并为单个"N步过程"折叠卡片（ProcessDrawer），只保留最后一条 message 独立显示；agent 运行时正常展示各元素；用户可点击 ProcessDrawer 展开查看详情；群聊/chat_with_agent 仅显示 TextSegment；群聊折叠时不显示 ProcessDrawer（collapseProcess && !isGroupChat），展开时顶部显示 ProcessDrawer（showProcessHeader，仅组内第一个 AgentTurn）、底部唯一一个 ProcessCollapseButton（showProcessFooter，仅组内最后一个 AgentTurn），类似 thinking 的折叠效果；displayElements 和 processStepCount 使用 remember 缓存避免重组时重复计算；expandedGroups 在 agent IDLE 时仅清除流式输出组的展开状态，保留用户手动展开的组；每个 AgentTurn 中的 ProcessCollapseButton 仅渲染一次（移除元素列表末尾多余的重复按钮），确保按钮位置在轮次最后一条消息上方
  6b. 元数据徽章（token/模型/延迟）仅在 Turn 中最后一个非空 TextSegment 显示（lastMetadataElementIndex），避免多条 message 重复显示
  6c. ChatTurnConverter 中 thinking 字段仅在为 null 时赋值（if (thinking == null) thinking = thinkingBlock），防止后续 ASSISTANT 消息覆盖第一个 thinking
  6. 支持长按消息弹出操作菜单（复制、重新生成、删除）
  7. PRIVATE 角色消息以可折叠卡片展示（PrivateTurnCard），标题显示"📋 与 X 的讨论"（X 为 targetAgentId 对应的智能体名称，使用 AnnotatedString 加粗显示），默认折叠，点击展开显示完整对话内容（发起者: 消息 / 目标智能体: 回复格式，不含 thinking 和 tool）；长按复制讨论内容到剪贴板（触觉反馈 + Toast 提示"已复制讨论内容"）；PRIVATE 消息不更新会话 lastMessage 预览；响应全局折叠/展开控制（LocalCollapseAll/LocalExpandVisible CompositionLocal）
  8. 全局折叠/展开控制：TopAppBar 右上角为搜索图标按钮（点击切换搜索面板），通过 CompositionLocal（LocalCollapseAll / LocalExpandVisible）向下传递；ThinkingBlockView、ToolCallBlockView、PrivateTurnCard 均响应全局折叠和全局展开
  9. 自动滚动到底部：用户点击"回到底部"按钮后设置 userPinnedToBottom=true，流式输出和工具调用期间持续自动跟随滚动；智能体回到 IDLE 时仅在用户不在底部才重置 userPinnedToBottom（保持置底状态直到用户主动上滑）；用户上滑离开底部时取消置底；普通聊天和群聊界面均支持键盘弹出时自动滚动到底部（使用 ViewTreeObserver.OnGlobalLayoutListener 检测 IME 可见性变化）
  10. 用户消息气泡宽度自适应：气泡宽度由内容决定（wrapContentWidth），最大不超过屏幕 80%；引用区域和文本内容均跟随气泡宽度；去掉 unbounded=true 让 widthIn(max) 约束生效
  8. 支持滑动切换会话（侧边抽屉）
  9. 支持聊天内搜
  10. 消息排队机制：智能体 THINKING/EXECUTING_TOOL 时用户发送的消息入池排队（MessageQueueManager），智能体回到 IDLE 后自动 flush（flushQueuedMessages）；flush 时先写入一条带 `[Queue: N messages]` 前缀的 USER 消息到 sessionStore，再调用 deliverMessage(skipUserMessage=true) 避免重复写入

### 多选消息

- **功能描述**：批量选择消息进行转发、导出或删除操作
- **交互逻辑**
  1. 触发方式：消息抽屉菜单中"多选"按钮（调用 enterMultiSelectMode 进入多选模式） / 长按消息进入多选模式
  2. 多选模式下：消息左侧显示 Checkbox，点击消息切换选中状态，选中消息显示 primary 色边框
  3. 多选模式下：输入栏替换为横向操作栏（取消 | N条已选 | 转发 | 导出 | 删除）
  4. 转发：弹出智能体选择对话框，选择后新建对话并转发选中消息（格式：📋 转发的消息 + 每条消息的角色和内容）；转发后同步更新 UI 状态（sessionId/agentId/turns）并触发 deliverMessage 让智能体响应
  5. 导出：将选中消息导出为 Markdown 格式，复制到剪贴板
  6. 删除：批量删除选中消息，删除后自动退出多选模式
- **输入规则**：消息选择、操作按钮点击
- **输出结果**：消息转发/导出/删除

### 语音消息

- **功能描述**：用户可录制语音消息发送，语音自动转文字供智能体处理，同时保留语音供播放
- **交互逻辑**
  1. 长按麦克风按钮开始录音（VoiceRecorder 使用 AudioRecord 录制 PCM 16kHz mono 16bit，保存为 WAV 文件）
  2. 录音中显示红色脉冲动画 + 录音时长（如"3s"），最长 120 秒自动停止
  3. 松开按钮停止录音，最短 500ms 视为无效
  4. 录音完成后：PCM 数据通过 OnDeviceModelManager.transcribeAudio() 转写为文字；转写失败时回退为"[语音消息]"
  5. 消息内容为转写文字（供智能体处理），metadataJson 使用 JSONObject 安全序列化存储 voiceFile（WAV 文件路径）和 voiceDuration（时长毫秒）
  6. 聊天界面检测到 metadataJson 含 voiceFile 时，渲染 VoiceBubble 语音气泡（播放/暂停按钮 + 进度条 + 时长显示），而非普通文本
  7. VoiceBubble 使用 MediaPlayer 播放 WAV 文件：prepareAsync() 异步准备避免主线程 ANR；暂停后点击播放恢复而非重启；LaunchedEffect 轮询 currentPosition 实现真实进度追踪；播放完成自动释放资源
   8. VoiceRecorder.stopRecording() 为 suspend 函数，WAV 文件通过 withContext(Dispatchers.IO) 同步写入，消除文件未写入完成即被访问的竞态；isRecording 标记为 @Volatile 保证线程可见性
   9. 麦克风权限兼容 MIUI：某些 MIUI 设备上 checkSelfPermission(RECORD_AUDIO) 返回 GRANTED 但 SpeechRecognizer 仍报 ERROR_INSUFFICIENT_PERMISSIONS（AppOps 未记录权限授予）。AndroidBuiltinTranscriber 通过反射调用 AppOpsManager.setMode() 将 OP_RECORD_AUDIO 设为 MODE_ALLOWED；ChatScreen/GroupChatScreen 在 RECORD_AUDIO 权限授权后主动调用 AndroidBuiltinTranscriber.notifyAppOps(context) 确保 AppOps 同步
- **输入规则**：长按麦克风录音
- **输出结果**：语音消息气泡（含播放功能）+ STT 转写文字发送给智能体
  10. 智能体消息左侧竖线颜色根据发送者 agentId hash 生成 HSL 颜色（固定饱和度 0.6f，亮度 0.75f），不同智能体有专属标识色
  11. 进入聊天界面时自动滚动到底部（LaunchedEffect(Unit) + snapshotFlow.first 确保首次进入置底，切换会话时同步置底并设置 userPinnedToBottom）
  12. 用户消息上方居中显示发送时间（10.sp，onSurfaceVariant 50% 透明度），时间格式：今天显示"HH:mm"，昨天显示"昨天 HH:mm"，今年显示"M月d日 HH:mm"，跨年显示"yyyy年M月d日 HH:mm"
  13. 设置按钮点击后跳转到系统设置界面中当前智能体的配置栏（通过 pendingSettingsAgentId 信号 + LaunchedEffect 切换 pager 到设置页）
  13. 长按消息弹出操作菜单支持引用功能：引用时在输入框上方显示引用预览（发送者名称+内容摘要，可取消），发送时将引用信息（quotedMessageId/quotedContent/quotedSenderName）写入消息 metadataJson；用户消息气泡上方显示引用块（引用发送者名称+内容摘要）；智能体消息同样支持引用；Agent.buildPrompt 构建提示词时，从用户消息 metadataJson 提取引用信息，在用户消息文本前注入引用前缀（格式：[用户引用了xxx的消息: 引用内容]），使 LLM 能感知用户引用了哪条消息；修复：无附件消息路径使用 textWithoutAttachments（含引用前缀）替代 sessionMessage.content，确保引用信息正确注入
  14. 长按智能体头像或名字自动在输入框填充 @智能体名称
- **输入规则**：文本消息、语音输入、图片附
- **输出结果**：Agent 回复文本、工具调用结果、思考过程、计划步

### 输入

- **功能描述**：聊天输入组件，支持文本输入、语音输入、附件选择、模型切
- **交互逻辑**
  1. 文本输入框支持多行，Enter 发送，Shift+Enter 换行
  2. 语音按钮长按录音，松开发送（STT 转文字）；短按启动/停止 STT 语音输入文字；一般聊天和群聊界面均支持 STT 语音输入（GroupChatScreen 同样注入 STTService + 权限请求 + 启动/停止回调）
  3. 附件按钮支持选择图片/文件
  4. Chip 内嵌显示在输入框内部（OutlinedTextField prefix 区域），与文本混排，[@智能体] [附件1] 你好；chip 使用紧凑 Surface 样式（图文字+关闭按钮），按类型配色（文件图片技能紫/提及橙）
  5. Backspace 删除：输入框文本为空时按 Backspace 整体删除最后一chip
  6. 光标位置保持：使用 remember + LaunchedEffect 同步外部文本变化，退格整段删除时同步更新 textFieldValue；避免光标被强制跳到末尾
  6. 模型切换底部弹窗展示可用模型列表
  7. 输入栏不再包含终止按钮，终止功能仅通过顶栏标题行右侧停止图标实现；模型选择栏移至会话标题下方（与标题上下排列，整体靠左对齐，点击弹出模型切换弹窗）；智能体状态（PulsingStatusDot 思考中/执行中）移至输入框上方左对齐
- **输入规则**：文本、语音、图
- **输出结果**：发送消息到当前会话

### @提及输入

- **功能描述**：聊天输入框中所有 chip 类型（@提及、附件、技能）统一以文本形式注入输入框，不再显示独立的 chip 栏
- **交互逻辑**
  1. 输入 `@` 后弹出智能体选择列表，只展示当前群组成员
  2. 选择智能体后，输入框中插入 ` @智能体名称 `（前后各一个空格）
  3. 选择完成后自动请求焦点，光标定位到插入文本末尾（@name 后方空格之后）
  4. 选择附件/图片后，输入框中插入 `[附件: filename]` 文本，同时内部保留 URI chip 数据用于文件处理
  5. 选择技能后，输入框中插入 `/skillname` 文本
  6. 退格删除时检测整段模式（`@name ` / `[附件: xxx]` / `/skillname`），整段删除而非逐字符；删除附件文本时同步移除对应 URI chip
  7. 消息渲染时，只有匹配到群内存在智能体的 @提及 才会显示为 chip，否则保持纯文本
- **输入规则**：@符号触发 / 附件选择 / 技能选择
- **输出结果**：所有 chip 以文本形式内嵌输入框（无独立 chip 栏）

### /快捷指令输入

- **功能描述**：聊天输入框中输入 `/` 时弹出可用快捷指令和技能列表
- **交互逻辑**
  1. 输入 `/` 后弹出下拉菜单，展示系统命令和当前智能体可用技能
  2. 系统命令列表：/compact（压缩上下文）、/new（创建新会话）、/clear（清除消息）、/history（查看历史）、/mission（启动任务模式）、/plan（生成任务计划）、/proactive（主动记忆开关）、/stats（查看统计）、/backup（备份管理）、/summarize_status（总结状态）
  3. 技能列表从当前智能体的 agentSkills 动态获取，显示技能 ID 和名称
  4. 输入 `/` 后继续输入字符时实时过滤匹配项（前缀匹配）
  5. 选择命令后替换 `/` 及输入内容为完整命令文本（如 `/compact `）
  6. 选择技能后替换为 `/skillId ` 并添加 SKILL 类型 chip
  7. 仅在输入框中只出现一次 `/` 时触发下拉菜单，多次 `/` 不触发
  8. 系统命令判断逻辑：`isSystemCommand()` 只匹配已注册的命令名，不再拦截所有 `/` 开头的文本（避免技能命令被误杀）
- **输入规则**：`/` 触发 / 字符过滤 / 选择插入
- **输出结果**：命令文本或技能 chip 注入输入框

### 会话抽屉

- **功能描述**：侧边抽屉展示会话历史列表，支持搜索、新建、删除、置
- **交互逻辑**
  1. 左滑打开抽屉，展示当Agent 的所有会
  2. 支持按时置顶排序
  3. 支持搜索会话内容
  4. 长按会话弹出操作菜单（置顶、删除、重命名
- **输入规则**：无
- **输出结果**：切换到选中的会

### 计划面板

- **功能描述**：展Agent 当前执行的计划步骤和进度
- **交互逻辑**
  1. 计划开关控制是否启用计划模
  2. 计划面板展示步骤列表（待执行/执行已完失败
  3. 每个步骤显示描述和状
- **输入规则**：计划模式开
- **输出结果**：计划步骤状态更

### 权限请求对话

- **功能描述**：当 Agent 请求执行需要审批的操作时弹
- **交互逻辑**
  1. 展示工具名称、参数、安全评估结
  2. 用户可选择「允许一次」「始终允许」「拒绝」「不再允许」四种审批选项
  3. 始终允许后该工具不再弹出审批；不再允许后该工命令持久拒绝
  4. PermissionTurnCard 按钮区上方展示安全发现摘要（最3 条，含严重程度和标题，超出显...还有 N 
- **输入规则**：用户审批决
- **输出结果**：工具执行或拒绝

### Agent 列表对话

- **功能描述**：展示可Agent 列表，支持切换当前对Agent
- **交互逻辑**
  1. 展示所有已创建Agent（头像、名称、描述）
  2. 点击切换当前会话Agent
  3. 角标显示 Agent 状态（在线/离线/忙碌
- **输入规则**：Agent 选择
- **输出结果**：切Agent

### 群聊界面

- **功能描述**：多 Agent 群聊界面，展示多Agent 的对
- **交互逻辑**
  1. 展示群组内所Agent 的消息，按发言顺序排列
  2. 每个 Agent 消息带有不同颜色/头像标识（始终显示，不受 isGroupedWithPrevious 限制
  3. 用户@mention 特定 Agent，只有匹配到群内存在智能体的 @提及 才会渲染为带 agent 专属颜色的内联标签（使用 AnnotatedString + SpanStyle 内联到单个 Text 组件，mention 用 SpanStyle 着色+背景+粗体，换行遵循自然断词）；未匹配的 @提及 保持粗体纯文本显示；同时支持 @displayName @agentId 两种格式匹配，agentId 匹配时标签显示 displayName；skill 标签（/skillname）同样使用 SpanStyle 渲染；mention/skill 标签之间的文本使用 MarkdownText 渲染，确保群聊中 Agent 回复的 markdown 格式正常显示
  4. 群聊中始终显示头像（不受 showAvatars 设置影响），确保用户可区分不Agent
  5. isImageAvatar 检查仅判断 URL 是否非空（isNullOrBlank），支持 HTTP/HTTPS/content:// 等任意有URL
   6. 已读状态计算：readStateMap 过滤 disabledAgentIds 和已删除智能体（agentId 不在 agentProfiles 中的），禁用和已删除的智能体不显示已读/工作中状态；@提及匹配时同样排除禁用智能体
   7. 已删除/禁用智能体过滤：groupMemberIds 和 groupMembers 从 GroupRegistry.groupsFlow + AgentRepository.getProfiles() 通过 combine 响应式派生，删除/禁用智能体后自动刷新 UI（不再使用 remember(sessionId) 缓存导致 stale 列表）；AgentGroup.processMessage 回退逻辑检查 enabled；LLMSpeakerSelector 仅包含存在的启用智能体；@提及抽屉不显示已删除/禁用智能体
- **输入规则**：文本消息、@mention
- **输出结果**：群组内 Agent 回复

### 群聊 LLM 发言者选择

- **功能描述**：群聊中通过 LLM 决策选择下一个回复的智能体，决策依据包含群组内各智能体的完整描述
- **交互逻辑**
  1. AgentGroup.processAgentResponse 构建 GroupContext 时，agentDescriptions 优先使用 AgentDescriptionProvider 获取完整描述，回退到智能体名称
  2. LLMSpeakerSelector 在决策 prompt 中使用完整描述，帮助 LLM 更准确地判断哪个智能体适合回复
  3. 当存在显式 @提及时，跳过 LLM 决策，直接路由到被提及的智能体
- **输入规则**：群组消息历史、智能体描述
- **输出结果**：SpeakerSelected / Finish / Error / Continue 四种决策结果

## 会话与群

### 会话列表界面

- **功能描述**：展示所有会话的列表视图，支持分组、搜索、批量操
- **交互逻辑**
  1. 按时间倒序展示会话（标题、最后消息、时间、未读数
  2. 群组会话时间显示格式与普通会话统一（使formatInstantTime 智能相对时间
  3. 群组会话按关Session lastUpdatedAt 降序排列
  4. 群组卡片中时间靠右显示（groupName 占满剩余空间，时间自然右对齐
  5. 支持分组展示（自定义分组
  5. 支持搜索会话
  6. 支持滑动删除，删除时级联删除关联的 chat_with_agent 私聊会话
   7. 默认智能体 hippy（agentId="default-agent"）的会话始终显示，不受当前选中智能体过滤影响；默认智能体初始化时自带全部 10 个内置技能（news/himalaya/channel_message/pdf/docx/xlsx/pptx/guidance/qa_source_index/image_generate）
  8. 底部导航栏切换到会话列表
  9. FAB 按钮新建会话
  10. 多会话状态显示：sessionStatuses 按 sessionId 映射（而非 agentId），多个会话同时执行时各自独立显示智能体状态
- **输入规则**：搜索关键词、分组操
- **输出结果**：导航到聊天界面

### 群组设置界面

- **功能描述**：配Agent 群组的成员和规则
- **交互逻辑**
  1. 展示群组名称、成员列
  2. 添加/移除群组成员
  3. 添加成员时检查 BOOTSTRAP 状态：未初始化的智能体（BOOTSTRAP.md 存在且 .bootstrap_completed 不存在）在列表中禁用选择，显示"未初始化，请先与该智能体对话完成初始化"红色提示，头像和名称降低透明度
  4. 配置发言策略（轮LLM 选择/自由发言
  5. 配置群组规则
  6. 配置决策模型：在"只接收@消息"开关下方显示"决策模型"行，点击弹出 ModelSwitchSheet 选择 LLM 模型；选中后显示模型名（primary 色），右侧显示关闭按钮可清除；决策模型用于群聊中 LLM 发言者选择和终止判断，持久化到 agent_groups 表的 llmSelectorProviderId/llmSelectorModelName 字段
- **输入规则**：群组配置参数
- **输出结果**：群组配置更新

### 创建群组对话

- **功能描述**：创建新Agent 群组
- **交互逻辑**
  1. 输入群组名称
  2. 选择群组成员（从已有 Agent 列表中选择；未初始化的智能体禁用选择，显示"未初始化，请先与该智能体对话完成初始化"红色提示，Checkbox 禁用）
  3. 选择发言策略
- **输入规则**：群组名称、成员列表、策
- **输出结果**：创建群

## 智能体配

### 创建 Agent 界面

- **功能描述**：创建新AI Agent 实例
- **交互逻辑**
  1. 输入 Agent 名称、描
  2. 选择模型供应商和模型
  3. 配置系统提示
  4. 选择启用的技能和工具
- **输入规则**：Agent 配置参数
- **输出结果**：创Agent 并导航到配置界面

### Agent 配置界面

- **功能描述**：编Agent 的详细配
- **交互逻辑**
  1. 编辑基本信息（名称、描述、头像）
  2. 配置模型（供应商、模型名、回退模型、VLM 模型）；模型选择使用原子操作 updateModel(modelName, modelProvider) 同时更新模型名和供应商，避免中间状态触发自动保存
  3. 配置核心文件（RULES.md、SOUL.md、PROFILE.md 等）
  4. 配置技能列
  5. 配置禁用/延迟工具
  6. 导航到子配置页面（运行配置、心跳、MCP、频道、Cron 任务等）
- **输入规则**：Agent 配置参数
- **输出结果**：Agent 配置更新

### 运行配置界面

- **功能描述**：配Agent 运行时参
- **交互逻辑**
  1. 设置最大迭代次
  2. 设置最大输入长
  3. 开关自动继
  4. 配置重试策略
  5. 设置语言偏好
  6. 长期记忆配置：压缩时记忆开关、自动记忆搜索开关（开启后显示"自动搜索最大结果数"默认2条、"自动搜索最低相关性分数"滑动条0~1默认0.3）、启动时重建索引开关
- **输入规则**：运行时参数
- **输出结果**：RunningConfig 更新

### 心跳设置界面

- **功能描述**：配Agent 心跳（定时主动交互）
- **交互逻辑**
  1. 开关心跳功
  2. 设置心跳间隔
  3. 编辑心跳提示
- **输入规则**：心跳参
- **输出结果**：HeartbeatConfig 更新

### Cron 任务界面

- **功能描述**：管理 Agent 的定时任务
- **交互逻辑**
  1. 展示 Cron 任务列表（名称、Cron 表达式、状态）
  2. 新建/编辑/删除 Cron 任务
  3. 点击任务卡片弹出编辑弹窗，预填充现有数据（名称、执行内容、Cron 表达式），支持修改后保存
  4. 手动触发任务
  5. 新建/编辑弹窗支持选择会话：从当前智能体的会话列表中选择，或选择"新建会话"（默认）；选择已有会话后定时任务在该会话中执行
  6. 新建/编辑弹窗支持"后台静默执行"开关：开启后定时任务执行时不通知用户
  7. 任务卡片显示静默执行标识
  8. 返回键导航：onBackClick 回调传递 navController.popBackStack(Screen.Sessions.route, inclusive=false)，确保连续按返回键不会白屏；所有设置子页面统一使用指定目标路由的 popBackStack
- **输入规则**：Cron 表达式、任务描述、会话选择、静默模式
- **输出结果**：Cron 任务更新

### 核心文件编辑界面

- **功能描述**：编Agent 工作区的 Markdown 核心文件
- **交互逻辑**
  1. 展示核心文件列表（RULES.md、SOUL.md、PROFILE.md 等）
  2. 点击文件进入编辑模式
  3. 支持新建自定义核心文
  4. 支持启用/禁用核心文件
  5. 支持拖拽排序
  6. 未创建的默认文件显示"未创标签，点击编辑可创建
- **输入规则**：Markdown 文本
- **输出结果**：核心文件更

## 模型与连

### 模型供应商界

- **功能描述**：管AI 模型供应商（OpenAI、Anthropic、Ollama 等）
- **交互逻辑**
  1. 展示已配置的供应商列表（名称、协议、状态）
  2. 添加新供应商
  3. 编辑/删除供应
  4. 设置默认供应
- **输入规则**：供应商配置参数
- **输出结果**：供应商列表更新

### 供应商详情界

- **功能描述**：编辑单个模型供应商的详细配
- **交互逻辑**
  1. 编辑供应商名称、Base URL、协议类
  2. 配置 API Key（加密存储）
  3. 管理模型列表（添删除/设为默认
  4. 测试连接
- **输入规则**：供应商详情参数
- **输出结果**：供应商配置更新

### 端侧模型管理界面

- **已废弃**（2026-05-26）：端侧管理 UI（OnDeviceModelScreen/OnDeviceModelViewModel）已移除。端侧推理引擎（OnDeviceModelManager/OnDeviceModelStore）保留，继续为 STT 转写和 LiteRT-LM ModelClient 提供支持。原功能描述如下：
- **功能描述**：下载、管理和加载端侧离线推理模型（基于 Google LiteRT-LM 引擎），单页面布局（搜索功能集成到模型管理界面）
- **交互逻辑**
  1. 单页面布局：移除 Tab 栏，搜索功能集成到模型管理界面顶部（OutlinedTextField 搜索栏），直接显示模型管理内容
  2. 搜索 Tab：顶部搜索框过滤模型名称/描述/ID，卡片列表展示所有目录模型，显示名称、描述、大小、能力标签、RAM 要求；已下载模型显示"已下载"禁用按钮，下载中显示进度条，未下载显示"下载模型"按钮
  3. 模型管理 Tab：展示预置模型目录（从 assets/ondevice_models.json 加载），显示模型名称、大小、能力标签、RAM 要求；只显示已下载/下载中/暂停的模型，不再显示未下载模型
  4. 模型卡片按状态分五种样式：未下载（显示下载按钮）、下载中（进度条+速度+暂停）、暂停（进度条+继续下载）、已下载未加载（后端选择下拉+加载引擎按钮+删除）、已加载（引擎状态+卸载/删除）、下载失败（重新下载）
  5. 设备 RAM 不足时显示警告，仍允许用户强制下载
  6. 后端选择下拉：自动/CPU/GPU/NPU（ExposedDropdownMenuBox），加载引擎时传入 BackendPreference
  7. 同一时刻只加载一个端侧模型引擎，切换时自动卸载旧引擎（OnDeviceModelManager.currentEngineModelId StateFlow 管理）
  8. 下载使用 HuggingFace 镜像自动替换域名（HuggingFaceMirror 探测+缓存），OkHttp 断点续传
  9. 下载完成后自动注册虚拟 Provider 到 ModelProviderStore，模型出现在 Provider 列表可直接切换使用
  10. 删除模型时同步移除虚拟 Provider 和本地文件
  11. 设置项：自动卸载引擎开关（App 后台 5 分钟后自动卸载）
  12. 下载暂停与恢复：用户暂停下载时设置 pausedByUser 标记并将状态设为 PAUSED（保留部分文件），真实失败时设为 DOWNLOAD_FAILED（删除部分文件）；进程重启后 recoverStaleDownloads 检测残留 DOWNLOADING 状态并重置为 PAUSED
  13. 下载管理：TopBar 右上角显示下载图标+角标（活跃下载数），点击弹出 ModalBottomSheet 展示当前下载任务列表（进度+速度+暂停/继续）
  14. 模型目录：自建模型目录（ondevice_models.json），包含 8 个 2B 参数以下端侧模型（Gemma3-1B、Qwen2.5-1.5B、DeepSeek-R1-Distill-Qwen-1.5B、Phi-4-mini、Qwen3-0.6B、TinyLlama-1.1B、Gemma3-270M、SmolLM-135M），移除了原有的 Gemma4-E2B/E4B
- **输入规则**：模型选择、后端偏好、下载/加载/卸载/删除操作、搜索关键词
- **输出结果**：模型下载完成、引擎加载/卸载状态变更、虚拟 Provider 注册/注销

### API Key 管理界面

- **功能描述**：管理多API Key Profile，支持轮换和冷却
- **交互逻辑**
  1. 展示 API Key 列表（名称、状态、使用次数）
  2. 添加/删除 Key
  3. 查看冷却状
- **输入规则**：API Key 字符
- **输出结果**：Key 列表更新

### MCP 设置界面

- **功能描述**：配MCP（Model Context Protocol）客户端连接
- **交互逻辑**
  1. 展示已配置的 MCP 客户端列
  2. 添加 MCP 客户端（URL、名称）
  3. 启用/禁用客户
  4. 查看客户端提供的工具列表
- **输入规则**：MCP 客户端配
- **输出结果**：MCP 配置更新

### ACP 设置界面

- **功能描述**：配ACP（Agent Communication Protocol）客户端和服务器
- **交互逻辑**
  1. 管理 ACP 客户端连
  2. 配置 ACP 服务器端
  3. 查看连接状
- **输入规则**：ACP 配置参数
- **输出结果**：ACP 配置更新

### 频道配置界面

- **功能描述**：配置外部通信频道（微信、钉钉、飞书、企业微信、Telegram、Discord 等），支持扫码自动配置和手动填写两种方式
- **交互逻辑**
  1. 展示可用频道列表（图标、名称、连接状态），已配置频道显示 ✓ 标记
  2. 点击频道进入编辑页面，支持扫码登录绑定（QR_CODE 类型频道）和手动填写配置
  3. 扫码登录绑定：点击"扫码登录绑定"按钮 → 导航到独立 QrAuthScreen 全屏页面 → 获取并展示二维码 → 轮询扫码状态 → 成功后自动保存凭证到 SecureStorage + ChannelConfigStore
  4. 跳转扫码：二维码展示页面提供"跳转XX扫码"按钮，自动保存二维码图片到相册（Pictures/HippyAgent）并尝试调起对应 App 的扫一扫功能（三级降级：扫一扫 Scheme → 打开 App → 包名启动）
  5. 手动填写：扫码按钮下方保留手动配置字段输入，支持 Webhook URL、Token 等参数
  6. 从 Agent 配置页面的"频道配置"按钮导航，传递 agentId 参数
  7. 频道类型新增 AuthType 枚举（MANUAL / QR_CODE），微信/飞书/钉钉/企业微信为 QR_CODE 类型
- **输入规则**：频道配置参数 / 扫码授权
- **输出结果**：频道连接建立，凭证加密存储到 SecureStorage

### 二维码扫码绑定

- **功能描述**：通过二维码扫码自动获取频道凭证，免手动配置
- **交互逻辑**
  1. 用户在频道编辑页点击"扫码登录绑定"→ 导航到 QrAuthScreen（独立全屏页面）
  2. QrAuthScreen 展示 Idle → Loading → QrReady（显示二维码图片）→ Scanned → Success/Error 状态流转
  3. QrReady 状态下：展示二维码图片 + "请使用XX扫描上方二维码"提示 + "跳转XX扫码"按钮（仅已安装对应 App 时显示）
  4. 点击跳转按钮：保存二维码到相册 → 尝试调起对应 App 扫码（weixin://dl/scan / lark://client/scan / dingtalk://dingtalkclient/action/scan / wxwork://）
  5. 扫码成功后凭证自动保存：敏感字段（botToken/appSecret/clientSecret/secret）存入 SecureStorage，非敏感字段存入 ChannelConfigStore
  6. 二维码过期后显示错误提示，支持重试
  7. 轮询超时上限 5 分钟，超时自动转为 Expired 状态
  8. Scanned 状态后轮询间隔缩短为 500ms（Waiting 间隔 1500ms）
- **输入规则**：频道 ID + Agent ID
- **输出结果**：凭证加密存储，频道配置自动填充

### 四平台二维码认证 Provider

- **功能描述**：为微信/飞书/钉钉/企业微信提供统一的 QrAuthProvider 接口实现
- **交互逻辑**
  1. **微信 (WeixinQrAuthProvider)**：调用 ILinkClient.getBotQrcode() 获取二维码 → 轮询 ILinkClient.getQrcodeStatus() → 成功返回 botToken + baseUrl
  2. **飞书 (FeishuQrAuthProvider)**：RFC 8628 Device Flow → POST accounts.feishu.cn/oauth/v1/app/registration (init → begin → poll) → 成功返回 appId + appSecret + openId；客户端使用 ZXing 生成二维码图片
  3. **钉钉 (DingtalkQrAuthProvider)**：Device Flow → POST oapi.dingtalk.com/app/registration (init → begin → poll) → 成功返回 clientId + clientSecret；客户端使用 ZXing 生成二维码图片
  4. **企业微信 (WecomQrAuthProvider)**：GET work.weixin.qq.com/ai/qc/gen 获取 HTML → 正则提取 window.settings 中的 scode + auth_url → 轮询 GET /ai/qc/query_result → 成功返回 botId + secret；客户端使用 ZXing 生成二维码图片
  5. 所有 Provider 共享 Koin 注入的 OkHttpClient 实例（连接池复用）
  6. 二维码图片生成统一使用 QrCodeGenerator.kt（ZXing + Bitmap.recycle + ByteArrayOutputStream.use{}）
  7. 所有 HTTP Response 使用 `.use{}` 确保资源释放
- **输入规则**：各平台 API 请求
- **输出结果**：QrPollResult（Waiting/Scanned/Confirmed/Expired）

## 技能与插件

### 技能池界面

- **功能描述**：展示和管理所有已安装的技
- **交互逻辑**
  1. 展示技能卡片列表（名称、描述、状态）
  2. 启用/禁用技
  3. 查看技能详情（技能详情弹窗显示完整 SKILL.md 文件内容，而非简要描述）
  4. 导航到技能商
- **输入规则**：技能选择
- **输出结果**：技能启用状态更

### 技能商店界

- **功能描述**：浏览和安装在线技
- **交互逻辑**
  1. 展示可安装的技能列表（分类展示
  2. 搜索技
  3. 安装/卸载技
  4. 查看技能详情和评分
- **输入规则**：搜索关键词、安装选择
- **输出结果**：技能安卸载

### 插件界面

- **功能描述**：管理第三方插件
- **交互逻辑**
  1. 展示已安装插件列
  2. URL 安装插件
  3. 卸载插件
  4. 查看插件提供的工
- **输入规则**：插URL
- **输出结果**：插件安卸载

## 记忆与洞

### Second Brain 界面

- **功能描述**：展示和管理 Agent 的长期记
- **交互逻辑**
  1. 展示记忆条目列表（摘要、类型、置信度、重要性）
  2. 搜索记忆
  3. 手动添加/删除记忆
  4. 忽略/恢复记忆条目
  5. 按类型筛选（事实/偏好/程序/社交
- **输入规则**：搜索关键词、筛选条
- **输出结果**：记忆条目更

### Dream 记忆界面

- **功能描述**：展Dream 记忆处理历史和状
- **交互逻辑**
  1. 展示 Dream 处理历史列表（时间、状态、效果）
  2. 手动触发 Dream 处理
  3. 查看处理前后对比
- **输入规则**：手动触
- **输出结果**：Dream 处理结果

### 记忆压缩界面

- **功能描述**：展示和管理对话上下文压
- **交互逻辑**
  1. 展示压缩历史
  2. 查看压缩摘要
  3. 手动触发压缩
- **输入规则**：手动触
- **输出结果**：压缩结

### 洞察界面

- **功能描述**：展示使用统计和成本分析
- **交互逻辑**
  1. 展示 Token 使用量（按日/月）
  2. 展示成本估算（按模型/供应商）
  3. 展示会话统计（数量、平均长度）
  4. 工具排行：显示英文工具名（原始 toolName），不再使用中文显示名
  5. 技能排行：统计 load_skill 工具调用记录，按技ID 聚合调用次数，显示技能中文名（复BuiltinSkillNames 映射
  6. 智能体对话次数：按 agentId 分组统计会话数，显示智能体名称和对话次数，按次数降序排列
- **输入规则**：时间范围选择
- **输出结果**：统计数据展

## 安全与审

### 收件箱界

- **功能描述**：展示审批请求和推送消息的统一收件
- **交互逻辑**
  1. Tab 布局：审批请/ 推送消
  2. 审批请求 Tab 显示所有状态的审批记录（pending/approved/denied/timeout），不同状态用视觉样式区分：pending 正常显示+批准/拒绝按钮，approved 绿色"已批标签+降低透明度，denied 灰色"已拒标签+降低透明度，timeout 橙色"已超标签+降低透明
  3. 推送消Tab 显示智能体主动推送的通知事件
  4. 支持全部已读、刷新、单条删
- **输入规则**：审批决策（批准/拒绝）、已读标
- **输出结果**：审批状态更新、消息已读状态更

### 工具审批界面

- **功能描述**：管理工具执行审批规
- **交互逻辑**
  1. 展示工具审批策略列表
  2. 设置工具执行级别（自需审批/禁止
  3. 配置自定义审批规
- **输入规则**：审批策略选择
- **输出结果**：审批规则更

### 安全规则界面

- **功能描述**：查看和撤销工具审批的「不再询问」规则（始终允许/不再允许
- **交互逻辑**
  1. 展示所有持久化审批规则列表（工具名称、参数摘要、审批动作）
  2. 单条删除：点击删除图标撤销规则，撤销后该工具审批将重新询
  3. 批量清除：右上角删除按钮弹出确认对话框，清除所有规
  4. 空状态提示：无规则时显示引导文案
  5. 规则卡片按审批动作着色：始终允许→默认色，不再允许→错误容器
- **输入规则**：删清除操作
- **输出结果**：规则撤销，工具审批恢复询

### 权限中心界面

- **功能描述**：展示和管理应用权限状态
- **交互逻辑**
  1. 展示所有需要的 Android 权限及授予状态
  2. 危险权限（相机、麦克风、位置、通讯录、短信、电话、日历、存储、蓝牙）点击直接弹出系统权限请求弹窗（ActivityResultContracts.RequestPermission）
  3. 特殊权限：悬浮窗跳转本应用悬浮窗权限设置页（带 package Uri）、定时闹钟跳转本应用闹钟权限设置页（带 package Uri）、通知监听跳转系统通知监听设置页
  4. 展示无障碍服务状态
- **输入规则**：权限请求
- **输出结果**：权限授予

### 工具安全界面

- **功能描述**：配置工具安全守卫规
- **交互逻辑**
  1. 展示安全级别（宽标准/严格
  2. 配置每个 Guardian 的规
  3. 查看安全审计日志
- **输入规则**：安全级别选择
- **输出结果**：安全配置更

### 无障碍设置界

- **功能描述**：引导用户开启无障碍服务
- **交互逻辑**
  1. 展示无障碍服务说
  2. 检测服务状
  3. 引导跳转到系统无障碍设置
- **输入规则**：无
- **输出结果**：无障碍服务启用

## 数据与存

### 数据存储界面

- **功能描述**：展示存储使用情况和数据管理
- **交互逻辑**
  1. 展示存储空间使用情况（会话、记忆、文件等
  2. 清理缓存
  3. 管理工作区文
- **输入规则**：清理选择
- **输出结果**：存储空间释

### 备份恢复界面

- **已废弃**（2026-05-23）：界面及导航已删除，BackupRestoreScreen.kt 已移除

### 环境检查界面

- **功能描述**：检查运行环境状态
- **交互逻辑**
  1. 展示 Linux 环境状态
  2. 展示 PRoot 状态
  3. 展示网络状态
  4. 展示依赖包状态
  5. 安装操作在应用级 CoroutineScope 中执行，退出界面后安装继续后台运行
  6. 回到界面时优先从缓存加载安装结果，再由自动检测覆盖；正在安装的项不被自动检测覆盖
  7. 安装完成后自动刷新环境检测状态并缓存到 SharedPreferences；安装结果始终保存到缓存（不依赖 Composable state），确保退出界面后安装结果不丢失
- **输入规则**：无
- **输出结果**：环境状态展示

## 应用设置

### 设置主界

- **功能描述**：应用设置入口，分组展示所有设置项
- **交互逻辑**
  1. 分组展示：通用、Agent、模型、安全、数据、关
  2. 点击导航到对应设置子页面
- **输入规则**：设置项选择
- **输出结果**：导航到子页

### UI 设置界面

- **功能描述**：配置界面外
- **交互逻辑**
  1. 主题切换（浅深色/跟随系统
  2. Agent 头像显示开
  3. 字体大小调节
- **输入规则**：主题选择、开
- **输出结果**：UI 配置更新

### 语言设置界面

- **功能描述**：配置应用语言
- **交互逻辑**
  1. 展示可用语言列表
  2. 选择语言后重启应用生
- **输入规则**：语言选择
- **输出结果**：语言切换

### 全局规则界面

- **功能描述**：编辑全局规则文件（对所Agent 生效
- **交互逻辑**
  1. 展示全局规则 Markdown 编辑
  2. 保存规则
- **输入规则**：Markdown 文本
- **输出结果**：全局规则更新

### 通知设置界面

- **功能描述**：配置通知行为
- **交互逻辑**
  1. 开关各类通知（消息、权限请求、任务完成等
  2. 配置通知渠道
- **输入规则**：通知开
- **输出结果**：通知配置更新

### 环境变量界面

- **功能描述**：管Linux 环境变量
- **交互逻辑**
  1. 展示环境变量列表（键、值）
  2. 添加/编辑/删除环境变量
- **输入规则**：键值对
- **输出结果**：环境变量更

### 事件监听设置界面

- **功能描述**：管理系统事件 Hook 的启用/禁用和静默时间配置，控制 Agent 可感知的系统事件范围
- **交互逻辑**
  1. 展示所有系统事件类型（短信、来电、通知、电量、屏幕、APP安装、剪贴板、系统启动），每个类型独立开关
  2. 缺少权限的事件类型显示"缺少权限"红色提示，开关不可操作
  3. 静默时间开关：启用后设置开始/结束小时数，静默期间不触发事件（来电除外）
  4. 已启用的事件显示"已启用"蓝色标签，未启用显示"未启用"灰色标签
- **输入规则**：开关操作、静默时间小时数输入（0-23）
- **输出结果**：事件过滤配置实时生效到 EventFilter

### Dream 界面（含 Curator 技能策展）

- **功能描述**：Dream 模式配置 + Curator 自动技能管理展示
- **交互逻辑**
  1. Dream 模式配置：启用开关、执行间隔、条件开关（充电/WiFi/空闲）、记忆保留天数、立即执行按钮、执行历史
  2. Curator 技能策展区域：展示自动提取的技能统计（已提取/活跃/已归档）和技能列表
  3. 每个自动技能卡片显示：名称、使用次数、描述、置信度进度条
  4. 无自动技能时显示"暂无自动提取的技能，Dream 执行后将自动生成"
- **输入规则**：Dream 配置参数、触发执行
- **输出结果**：Dream 配置更新、Curator 技能数据展示

### 洞察界面（含 Standing Orders）

- **功能描述**：使用分析统计 + Standing Orders 常驻指令展示
- **交互逻辑**
  1. 时间筛选（7/30/90天）、概览卡片、Token 消耗统计、工具/技能排行、活动模式
  2. ~~Standing Orders 常驻指令卡片：展示所有已注册的常驻指令（soul/profile/rules/agents/heartbeat/global_rules）及优先级~~（常驻指令栏已移除，已废弃 2026-05）
  3. 工具排行栏显示英文工具名（原始 toolName），不再使用中文显示名
  4. 说明核心文件在对话开始时注入一次，后续轮次跳过未修改内容以节省 Token
- **输入规则**：时间范围选择
- **输出结果**：使用分析报告

### 关于界面

- **功能描述**：展示应用信
- **交互逻辑**
  1. 展示版本号、构建时
  2. 版本号格式：`0.1.{yyMMddHHmm}`，基于打包时间自动生成，通过 BuildConfig.VERSION_NAME 读取确保应用内显示最新值
  3. 展示开源许
  4. 展示项目链接
- **输入规则**：无
- **输出结果**：信息展

## 调试与诊

### 调试信息界面

- **功能描述**：展示应用运行时调试信息
- **交互逻辑**
  1. 展示系统信息（设备、Android 版本、内存）
  2. 展示 Agent 运行状
  3. 展示最近错误日
- **输入规则**：无
- **输出结果**：调试信息展

### 日志界面

- **功能描述**：查看实时日志和导出应用日志
- **交互逻辑**
  1. 顶部按钮栏：导出（导出日志到文件并分享）、实时日志（启动/停止 logcat 流）、清空（清除控制台内容）
  2. 进入界面自动启动实时日志流和自动滚动
  2. 实时日志控制台：黑色背景终端风格，使用 logcat --pid 读取当前进程日志，按级别着色（Error 红色、Warning 黄色、Info 青色、Debug 白色、其他蓝色），等宽字体 11sp
  3. 控制台支持文本选择复制、自动滚动到底部（可切换手动滚动）
  4. 日志缓冲区上限 2000 行，超出自动淘汰旧行；LazyColumn 使用 itemsIndexed + key=index 避免重复 key 导致 crash
  5. 导出功能：收集应用日志文件 + 系统信息，生成文本文件并分享
- **输入规则**：按钮操作
- **输出结果**：实时日志流 / 导出日志文件

### 工具列表界面

- **功能描述**：展示所有已注册的工
- **交互逻辑**
  1. 按分类展示工具列表（内置/Android/Linux/MCP/技能）
  2. 点击查看工具详情（参数、描述、权限要求）
  3. 搜索工具
- **输入规则**：搜索关键词
- **输出结果**：工具信息展

### 工具详情界面

- **功能描述**：展示单个工具的详细信息
- **交互逻辑**
  1. 展示工具名称、描述、参数定
  2. 展示权限要求
  3. 展示执行历史
- **输入规则**：无
- **输出结果**：工具详情展

## 引导

### 启动初始化界

- **功能描述**：首次启动时的初始化进度界面
- **交互逻辑**
  1. 展示初始化步骤进度（DI 容器 关键组件 后台任务
  2. 初始化完成后自动跳转
- **输入规则**：无
- **输出结果**：初始化完成

### 新手引导界面

- **功能描述**：首次使用时的引导流
- **交互逻辑**
  1. 引导配置模型供应
  2. 引导授予必要权限
  3. 引导创建第一Agent
  4. 完成后进入聊天界
  5. 首页（step 0）显示"已有 API Key? 直接配置 →"按钮，点击跳转到模型供应商界面
- **输入规则**：引导步骤操
- **输出结果**：引导完成，进入主界

## 调试与诊

### 界面交互展示页面

- **功能描述**：纯交互展示HTML 原型页面，用于无功能逻辑的界面流程演
- **交互逻辑**
  1. 以手机框架形式展示所有界面，同一时间只显示一个界
  2. 通过点击交互进行界面跳转，保持原有导航逻辑
  3. 主页 4 Tab（会收件洞察/设置）底部导航切
  4. 设置页分组卡片展开/折叠，点击跳转子页面
  5. Agent 配置卡片提供 12 个子配置入口
  6. 聊天界面支持会话抽屉、模型切换、计划面板、菜单等抽屉弹窗；ChatScreen/GroupChatScreen 共享 Hook（rememberChatSessionState/rememberChatTtsState/rememberChatAutoScrollState），inputText 收归 ChatInputViewModel 消除双源真相
  7. 所有子页面通过返回按钮回退到上一
  8. 新建按钮弹出 CreateDrawer（聊群组/分组/智能体）
  9. 包含 40+ 个界面的完整交互流程
- **输入规则**：点击交
- **输出结果**：界面跳转与展示

---

# 系统

## 运行时引

### 文件附件卡片渲染

- **功能描述**：智能体发送文件时在聊天界面渲染为可交互的文件卡片，而非弹出系统分享对话框
- **交互逻辑**
  1. `SendFileToUserTool` 执行后，在 `output` 中嵌入 `[附件: /path/to/file]` 文本标签
  2. 聊天消息渲染时，`MessageContentWithAttachments` 通过 `ATTACHMENT_CONTENT_REGEX` 匹配 `[附件: path]` 标签
  3. 匹配到图片扩展名（jpg/jpeg/png/gif/webp/bmp/svg）→ 用 `AsyncImage` 渲染
  4. 匹配到其他扩展名 → 用 `FileAttachmentCard` 渲染
  5. `FileAttachmentCard` 显示：文件图标（doc/docx/txt/md 用 Article 图标，其他用 AttachFile 图标）、文件名、扩展名（大写）、文件大小
  6. 三个操作按钮：查看（FileProvider + ACTION_VIEW）、保存（复制到 Downloads 目录）、分享（ACTION_SEND + FileProvider）
  7. 用户/智能体消息使用不同配色
- **输入规则**：`[附件: path]` 文本标签
- **输出结果**：文件卡片 UI，支持查看/保存/分享

### 智能体禁用状态隔离

- **功能描述**：当用户关闭智能体后台运行时，该智能体不应接收任何消息也不应运行
- **交互逻辑**
  1. `ChatWithAgentTool.execute()` 检查 `agent.profileConfig.enabled`，禁用时返回错误
  2. `SubmitToAgentTool.execute()` 同样检查 `agent.profileConfig.enabled`，禁用时返回错误
  3. `AgentGroup.processMessage()` 路由阶段过滤：`targetAgents.filter { agent != null && agent.profileConfig.enabled }`，移除 null 和禁用的智能体
  4. `AgentGroup.processAgentResponse()` 兜底检查：`if (!agent.profileConfig.enabled) return null`
  5. 被跳过的禁用智能体记录 Timber.w 日志
- **输入规则**：`AgentProfile.enabled` 布尔值
- **输出结果**：禁用智能体被完全隔离，不接收消息不运行

### 数据迁移机制

- **功能描述**：应用启动时自动迁移旧版数据标识符到新名称，确保升级后数据不丢失
- **交互逻辑**
  1. SharedPreferences 迁移：`androidpaw_settings` → `hippy_settings`，`androidpaw_config` → `hippy_config`
  2. 数据库文件迁移：`androidpaw.db` → `hippy.db`，`commonmemory.db` → `commonmemory.db`（含 WAL/SHM 文件）
  3. 迁移逻辑在 `HippyAgentApp.migrateLegacyPrefs()` 中执行，仅在应用启动时运行一次
  4. 已迁移的 SharedPreferences 通过 `_migrated` 标记位避免重复迁移
  5. 数据库文件迁移：新文件不存在且旧文件存在时才执行 rename
- **输入规则**：旧版数据文件/SharedPreferences
- **输出结果**：数据迁移到新名称，应用正常读取

### 全量工具注入

- **功能描述**：Agent 启动时直接注入所有已启用工具（技能工具 + 默认可见工具），不再有 onDemand 概念。已删除动态注入系统（OnDemandToolResolver、ToolRouter、GreetingDetector）
- **交互逻辑**
  1. ToolRegistry.register() 不再接受 onDemand 参数，所有注册工具默认可见
  2. ToolDefinition 不再包含 isOnDemand 字段
  3. getDefinitionsForAgent() 不再需要 onDemandFilter 参数
  4. Agent 不再调用 resolveOnDemandFilter()，不再有 forceOnDemandTools 字段
- **输入规则**：Agent 配置中的 defaultToolKit 和技能启用列表决定工具可见性
- **输出结果**：所有已启用工具直接注入 LLM tools schema，无需额外路由开销

### 应用包名中文映射

- **功能描述**：用户说「打开淘宝」时自动映射到 `com.taobao.taobao`，无需提供包名
- **交互逻辑**
  1. `app_packages.json` 内置 50+ 常用中文 App 名→包名映射
  2. `AppPackageResolver.resolvePackageName(input, packageManager)` 四级降级匹配：精确别名→大小写别名→包含匹配→PackageManager 应用标签匹配
  3. `LaunchAppTool.execute()` 中先调用 `AppPackageResolver.resolvePackageName()`，解析失败回退原始输入
  4. `PromptBuilder.buildAppAliasSection()` 将映射表注入系统提示词 `<app_aliases>` 区块
- **输入规则**：中文应用名或包名
- **输出结果**：解析后的包名，用于 `getLaunchIntentForPackage()`

### 屏内替身模式

- **功能描述**：跨 App 悬浮窗替身模式，Agent 可在用户使用其他应用时持续陪伴，通过屏幕帧采样感知屏幕内容，通过语音听写接收用户指令，通过 TTS 语音播报回复
- **交互逻辑**
  1. `CompanionController.enterCompanionMode(application, sessionId)` 启动替身模式：显示悬浮窗、初始化 TTS、创建帧采样器
  2. `CompanionFloatWindow` 显示跨 App 悬浮窗（TYPE_APPLICATION_OVERLAY），实时展示状态文本（就绪/听写中/处理中/执行中）
  3. `ScreenFrameSampler` 按配置 fps 采样屏幕帧，写入 `VisionFrameBuffer` 环形缓存
  4. `VisionFrameBuffer` 单帧环形缓存，@Synchronized 线程安全，push 时回收旧帧
  5. `CompanionTtsManager` 封装 TTSService，Agent 回复时语音播报
  6. `CompanionController` 管理完整生命周期：enterCompanionMode → startVoiceCapture/stopVoiceCapture → onAgentStarted/onAgentFinished → exitCompanionMode
  7. 系统提示词 `COMPANION_SYSTEM_PROMPT.md` 注入替身模式规则：综合屏幕+语音理解意图、简洁回复、答题场景处理
- **输入规则**：Application 上下文、sessionId、MediaProjection（可选）
- **输出结果**：悬浮窗状态更新、TTS 语音播报、屏幕帧缓存供 Agent 感知

### Agent 核心引擎

- **功能描述**：Agent 消息处理主循环，实现 ReAct（Reasoning + Acting）模
- **流程**
  1. 接收用户消息，构Prompt（系统提示词 + 核心文件 + 记忆 + 技+ 工具定义
  2. 调用 LLM 获取响应（流非流式）
  3. 解析响应中的 ToolCall
  4. 若有 ToolCall：执行工将结果追加到上下回到步骤 2
  5. 若无 ToolCall：返回最终文本回
  6. 循环检测：检测重复工具调用模式，自动中断
  7. 自动继续：当模型输出 `[CONTINUE]` 标记时自动继
- **关键机制**
  - Per-Session 互斥锁：同一会话同时只处理一条消息；互斥锁在 finally 块中先 unlock 再 updateSessionState(IDLE)，避免 status=IDLE 但 mutex 仍锁定的竞态窗口
  - Per-Session Job 跟踪：sessionJobs（ConcurrentHashMap<String, Job>）跟踪每个会话的协程 Job，支持 stopSession 精准取消单个会话、stop 取消所有会话；processMessage/processMessageStream 入口注册 Job，finally 块移除；destroy 时清空所有 Job
  - 对象池复用：StringBuilder、ToolCallInfo 列表
  - 上下文压缩：Token 超限时自动压缩历史消
  - 故障转移：主模型失败时自动切换到回退模型
  - AgentSessionManager 集成：可选的会话生命周期管理（创更新活跃时间
  - 消息 senderId 一致性：所有 ASSISTANT/TOOL 消息均携带 senderId=agentId，确保 ChatTurnConverter 正确归组；当 senderId 为 null 时（历史消息），不拆分为独立 turn，仅当两个连续非 TOOL 消息的 senderId 均非 null 且不同时才拆分
  - 错误持久化与广播：processMessageStream/processMessage 的异常捕获（OOM/超时/网络错误）均持久化为 ASSISTANT 消息（sessionStore.addMessage）+ 广播（channelManager.broadcast）+ 流式输出（emit StreamChunk.Content），确保用户在聊天界面可见错误提示
  - 共享方法提取：processMessage/processMessageStream 重复逻辑提取prepareMessageContext（离线检消息入队+模型解析+/pro检Prompt构建+工具定义）、handleToolCalls（修持久并行执行+失败信号+权限检测）、handleTextReply（纯文本回复持久广播），两个入口方法调用共享方法消除 ~70% 重复代码
  - 思考耗时记录：processMessageStream 流式循环中记录首次收到 reasoningContent 的时间（thinkingStartTime），流式结束后计算耗时（thinkingDurationMs），通过 sessionStore.updateMessageMetadata 写入消息 metadataJson（{"thinkingDurationMs": xxx}），ChatTurnConverter 解析时读取该字段填充 ThinkingBlock.durationMs
  - 上下文Token信息：Agent 暴露 contextTokenInfo StateFlow（ContextTokenInfo(currentTokens, maxTokens)），在 buildPrompt 中 checkContext 后更新；ChatViewModel 在 Agent 回到 IDLE 时读取该信息注入 TurnMetadata（contextTokens/maxContextTokens），用于 UI 显示上下文比例进度条
  - TurnMetadata 持久化与恢复：ChatViewModel 在 Agent 回到 IDLE 时计算单轮增量（inputTokens/outputTokens/totalTokens/apiCalls/latencyMs/contextTokens/maxContextTokens）注入 TurnMetadata，持久化到 SessionStore 消息 metadataJson；ChatTurnConverter 从最后一个 ASSISTANT 消息的 metadataJson 恢复 TurnMetadata
  - 自动记忆搜索控制：prepareMessageContext 中根据 RunningConfig 的 autoMemorySearchConfig 控制长期记忆搜索行为；enabled=false 时跳过记忆搜索和注入（commonMemoryEntries 为空，PromptBuilder.buildCommonMemorySection 不输出）；enabled=true 时使用 maxResults 限制搜索结果数量、minScore 过滤低相关性结果（按分数降序取前 maxResults 条）

### Agent 工厂

- **功能描述**：创建和管理 Agent 实例
- **流程**
  1. LRU 缓存（最10 实例），Agent ID 缓存
  2. AgentRepository 加载 AgentProfile
  3. 根据 Profile 配置创建 ModelClient
  4. 注入中间件链（DanglingToolCall Clarification Memory LoopDetection
  5. 注册 Agent AgentRegistry（可选）
  6. 返回 Agent 实例
- **关键机制**：缓存淘汰、延迟初始化、AgentRegistry 注册/注销；getAgent() 检测 enabled 变化自动触发 reloadAgent()；SettingsViewModel/ConversationListViewModel 操作后显式调用 reloadAgent()

### 中间件链

- **功能描述**：在 Agent 处理循环中插入可插拔的中间件
- **中间件列*
  - **DanglingToolCallMiddleware**：修复悬空的工具调用（LLM 输出 tool_call 但未完成 JSON）；死亡螺旋检测：任何 dangling ids（不仅是重复 id）均递增 consecutiveDanglingCount，阈值 MAX_CONSECUTIVE_DANGLING=3；修剪时使用完整 danglingSet（不仅 newIds）；removeEmptyAssistantMessages 同时检查 reasoningContent 避免误删含 thinking 的消息
  - **ClarificationMiddleware**：检测模糊请求，自动请求用户澄清
  - **MemoryMiddleware**：在 Prompt 中注入相关记
  - **TitleMiddleware**：自动生成会话标
  - **ThreadDataMiddleware**：注入线程上下文数据
  - **SummarizationMiddleware**：自动摘要长对话
  - **SubagentLimitMiddleware**：限制子代理数量
  - **DeferredToolFilterMiddleware**：过滤延迟加载的工具
  - **LoopDetectionMiddleware**：检测重复工具调用模式，Agent 内部 LoopDetector 协同工作
- **流程**：请中间 中间 ... Agent 处理 响应 ... 中间 中间

### 工具调用修复管道

- **功能描述**：修LLM 输出中的异常工具调用
- **流程**
  1. **ScavengeRepair**：从 LLM 输出文本中拾取被截断tool_call JSON
  2. **TruncationRepair**：修复被截断JSON 参数（补全括号等
  3. **StormBreaker**：检测并打断"风暴"（模型疯狂输出重tool calls
- **关键机制**：管道式处理，每一步可选择跳过或修

### Curator 技能自动创建

- **功能描述**：后台自动从 Agent 执行历史中提取可复用的技能模式，实现"越用越聪明"。Curator（策展人）分析成功执行轨迹，提取工具调用序列、参数模式、用户意图为可复用的技能（SKILL.md），定期合并相似技能、归档低频技能。
- **交互逻辑**
  1. **执行轨迹收集**：每次 Agent 执行完成后，`CuratorMiddleware` 将工具调用序列记录到 `ExecutionHistoryStore`（JSON Lines 文件）
  2. **LIGHT（每 6 小时）**：清理过期执行历史（7 天前），更新技能使用计数
  3. **DEEP（每 24 小时，集成在 DreamWorker 中）**：
     - `SkillExtractor` 分析近期成功的执行历史（工具≥2个、时长>5s、非单次），提取关键词、参数模式、工具序列，生成 `CuratorSkillManifest`
     - `SkillMerger` 计算技能相似度（工具集重叠率 60% + 关键词重叠率 40%），对相似度≥0.7 的技能执行合并
     - `SkillOptimizer` 提升高频技能置信度、衰减低频技能置信度
  4. **REM（每 168 小时）**：工具使用偏好统计、错误模式学习、30 天未使用且使用次数<3 的技能自动归档
- **输入规则**：无（全自动定时触发，复用现有 DreamWorker 的 WorkManager 调度）
- **输出结果**：JSON 格式的技能清单存储在 `curator/skills/` 目录；`CuratorReport` 记录提取/合并/归档数量
- **关键机制**
  - **DreamWorker 复用**：直接利用现有的三阶段 Dream 调度（LIGHT/DEEP/REM），不新增 WorkManager 任务
  - **文件存储**：JSON Lines 格式的执行历史（`curator/history/executions_YYYY-MM-DD.jsonl`），无需 Room 迁移
  - **启发式提取**：基于规则的关键词提取 + 参数模式模板，无需 LLM 即可运行
  - **副作用非致命**：所有 Curator 操作包裹在 `runCatching` 中，失败不影响主流程
  - **可扩展**：`SkillExtractor.generateDescriptionWithLLM()` 预留 LLM 摘要接口

### 系统事件 Hook

- **功能描述**：监听 Android 系统事件并转发到 Agent 处理。Agent 可主动响应短信到达、来电、电量变化、屏幕亮灭、APP 安装/卸载、剪贴板变化等事件，实现"主动感知"能力。
- **交互逻辑**
  1. `HippyAgentApp.onCreate()` 通过 Koin 获取 `SystemHookManager`，调用 `initialize()` 注册所有系统 Hook
  2. 各 `SystemHook` 注册 `BroadcastReceiver`/`Listener` 监听系统事件
  3. 事件到达 → `SystemHookManager.onSystemEvent()`：
     - **去重**：相同事件在 `minIntervalMs` 内忽略（如短信 30s、电量 10min）
     - **过滤**：`EventFilter` 检查静默时间、黑白名单规则
     - **分发**：通过 `SystemEventDispatcher`（Koin 单例）回调到 App 层
  4. `SystemEventDispatcher.onEvent()` 获取默认 Agent，调用 `processMessage` 注入事件提示词
  5. Agent 根据事件类型和内容决定是否响应以及如何响应
- **支持的 Hook（6 个）**
  - **SmsEventHook**：监听 `SMS_RECEIVED_ACTION`，提取发送者、正文、时间戳（需 `RECEIVE_SMS` 权限）
  - **CallEventHook**：监听 `PHONE_STATE_CHANGED`，区分来电 RINGING 和挂断 IDLE（需 `READ_PHONE_STATE` 权限）
  - **BatteryEventHook**：监听 `BATTERY_LOW` / `POWER_CONNECTED` / `POWER_DISCONNECTED`，触发低电量/充电状态事件
  - **ScreenEventHook**：监听 `ACTION_SCREEN_ON/OFF`，触发屏幕亮灭事件
  - **AppInstallEventHook**：监听 `ACTION_PACKAGE_ADDED/REMOVED/REPLACED`，跟踪应用安装/卸载
  - **ClipboardEventHook**：通过 `ClipboardManager.OnPrimaryClipChangedListener` 监听剪贴板变化
- **输入规则**：无（全自动系统事件监听，无需用户触发）
- **输出结果**：事件转换后的提示词发送到默认 Agent 的 `processMessage`
- **关键机制**
  - **去重限流**：每个事件类型有独立 `minIntervalMs`，`LinkedHashMap` 缓存去重 Key
  - **EventFilter**：支持 `BlockPackage`/`BlockSender`/`AllowOnly` 规则 + 跨天静默时间（如 23:00-07:00）
  - **权限防护**：每个 Hook 在 `isEnabled()` 中检查 Android 运行时权限，无权限时不注册广播
  - **非关键初始化**：放在 `HippyAgentApp` Phase 3d，不影响 App 冷启动速度
  - **非致命**：所有 Hook 异常捕获在各自 `runCatching` 中，单个 Hook 失败不影响其他 Hook

### Standing Orders 常驻指令

- **功能描述**：将 Agent 系统提示词中稳定不变的核心文件（SOUL.md/PROFILE.md/RULES.md/AGENTS.md/HEARTBEAT.md）标记为"常驻指令"，仅在首次对话轮次注入，后续轮次跳过。当文件内容被编辑时自动重新注入。大幅减少 Token 消耗。
- **交互逻辑**
  1. `StandingOrdersManager` 注册所有核心文件为常驻指令（优先级：soul(10) → profile(20) → rules(30) → agents(40) → heartbeat(50) → global_rules(60)）
  2. **轮次 1**：`PromptBuilder.buildSystemPrompt()` 调用 `StandingOrdersManager.getStandingOrders(sessionId, workingDir)`，发现所有指令均未注入，全部加载并追加到系统提示词
  3. **轮次 2（文件未变）**：再次调用 `getStandingOrders()`，所有指令的 SHA-256 哈希未变且已注入，返回空列表，跳过注入（仅输出 `<!-- standing_orders unchanged, skipped -->` 注释标记）
  4. **轮次 3（用户编辑了 RULES.md）**：`CoreFilesViewModel.saveFile()` → `StandingOrdersManager.update("rules", newContent)` → 哈希变化 → 标记所有会话需要重新注入 rules → 下次调用时仅注入 rules 的最新内容
  5. **上下文压缩时**：`getInjectionSummary(sessionId)` 返回所有已注入常驻指令的关键摘要，压缩保留 Standing 信息
- **输入规则**：`PromptContext.sessionId` 传入当前会话 ID，为 `""` 时退化为旧行为（每次全部注入）
- **输出结果**：`buildSystemPrompt()` 返回常驻指令 + 动态部分组成的完整系统提示词；首次注入 ~4000 tokens，后续注入 ~3000 tokens（仅动态部分）
- **关键机制**
  - **SHA-256 哈希变更检测**：每次调用时重新计算文件内容哈希，与注册时哈希对比决定是否重新注入
  - **Per-session 注入跟踪**：`ConcurrentHashMap<String, MutableSet<String>>` 跟踪每个会话已注入的指令 ID
  - **文件变更通知**：`StandingOrdersManager.update(id, content)` 标记所有会话的对应指令为"待重新注入"
  - **向后兼容**：`StandingOrdersManager` 为可选参数，不传时 `PromptBuilder` 退化为旧行为（全部注入）
  - **零第三方依赖**：纯 SHA-256 + HashMap 实现，无新增依赖
  - **global_rules 注入**：`buildGlobalRulesSection` 仅在不使用 Standing Orders（standingMgr==null 或 sessionId 为空）时执行，避免与 Standing Orders 的 global_rules order 重复注入；Standing Orders 激活时由 StandingOrdersManager 统一管理 global_rules 的注入和跳过逻辑

### 混合 RAG 搜索

- **功能描述**：在现有 FTS4 全文搜索基础上，引入 RRF（Reciprocal Rank Fusion）融合排序 + 启发式重排序，提升记忆检索质量。Phase 1 为纯算法实现（无向量嵌入），后续 Phase 2 可加入 ONNX 语义向量。P@5 从 ~30% 提升到 ~50%。
- **交互逻辑**
  1. 调用 `MemoryRepository.searchHybrid(query, limit)` 或 `searchHybridByAgentId(query, agentId, limit)` 触发混合搜索
  2. `HybridSearchEngine.search()` 执行搜索流水线：
     - **FTS4 检索**：agentId 非空时使用 `searchFtsByAgentId` 按智能体过滤，否则使用 `searchFts` 全局搜索，获取 topK=50 候选
     - **RRF 融合**：`RRFFuser.fuse()` 将排序位置转换为融合分数（Phase 1 仅单源，退化为恒等）
     - **启发式重排序**：`LightweightReranker.rerank()` 综合 6 个维度（关键词覆盖率/结构特征/时效性/置信度/证据次数/详情覆盖）重新排序
  3. 重排序后取 topK=8 返回给调用方
  4. Agent 的自动记忆注入（`prepareMessageContext`）使用 `searchHybridByAgentId` 按智能体隔离搜索，确保记忆不跨智能体泄漏
- **输入规则**：`query` 搜索词，`limit` 返回条数（默认 8）
- **输出结果**：`List<Pair<CommonMemoryEntry, Float>>`，带匹配度的排序结果
- **关键机制**
  - **RRF 算法**：`score = Σ 1/(k + rank)`，k=60，多源排序融合的基础（Phase 2 添加向量源后生效）
  - **6 维重排序**：关键词覆盖率(40%) + 结构化标题(15%) + 时效性(20%) + 置信度(15%) + 证据次数(10%) + 详情覆盖(10%)
  - **渐进式设计**：接口预留了 VectorStore 接入点，Phase 2 添加 ONNX 嵌入层后自动升级为双源 RRF
  - **零额外依赖**：Phase 1 纯算法，无 ONNX/无 Room 迁移，立即可用
  - **MemoryRepository 扩展**：新增 `searchHybrid()` 和 `searchHybridByAgentId()` 接口，`RoomMemoryRepositoryImpl` 通过 `HybridSearchEngine` 实现；`HybridSearchEngine.search()` 支持 agentId 参数，非空时使用 `searchFtsByAgentId` 替代 `searchFts`

### 会话存储

- **功能描述**：持久化会话和消息数
- **流程**
  1. 基于 Room 数据库存储（AppDatabase
  2. 三表拆分：sessions（16 字段，核心会话信息）、session_stats（7 字段，token 用量与费用统计，外键级联删除）、session_compression（2 字段，压缩摘要，外键级联删除）
  3. SessionDao 管理核心会话 CRUD（含 getFullById 三表 JOIN 查询返回 SessionFullRow）
  4. SessionStatsDao 管理 token 用量更新（updateTokenUsage）和完成时间（updateFinishedAt）
  5. SessionCompressionDao 管理压缩摘要（upsertSummary）
  6. MessageDao 管理 Message 实体（CRUD、按会话查询
  7. InsightsDao 通过 LEFT JOIN session_stats 聚合洞察统计
  8. RoomSessionStore 构造注入三个 DAO，createSession 事务插入 sessions + session_stats，toSession 从 SessionFullRow 映射
- **关键机制**：Room Migration（v1-v20）、FTS 搜索、索引优化、deleteMessage 按 ID 删除消息（MessageDao.deleteById）、SessionFullRow 三表 JOIN 投影、外键级联删除保证数据一致性

### 计划系统

- **功能描述**：Agent 执行计划的管
- **流程**
  1. Agent 通过 PlanTools 创建计划（步骤列表）
  2. 每个步骤有状态（pending/in_progress/completed/failed
  3. Agent 执行步骤并更新状
  4. PlanPanel UI 实时展示进度
- **关键机制**：PlanManager 管理计划生命周期

## 模型与路

### 端侧模型推理引擎

- **功能描述**：基于 Google LiteRT-LM 的端侧离线模型推理系统，支持 GPU/NPU 加速，独立管理端侧模型生命周期，通过虚拟 Provider 桥接到现有 ModelProvider 体系
- **交互逻辑**
  1. OnDeviceModelManager 统一管理端侧模型的下载、加载、推理、卸载全生命周期
  2. LiteRTLMEngine 封装 LiteRT-LM Kotlin API：Engine 初始化（EngineConfig+Backend）、Conversation 创建与推理、资源释放
  3. 单例引擎策略：currentEngineModelId StateFlow 跟踪当前加载的模型，loadEngine 时自动卸载旧引擎
  4. 后端选择：BackendPreference（AUTO/CPU/GPU/NPU），AUTO 模式尝试 GPU 失败回退 CPU
  5. 推理模式：每次推理创建新 Conversation（无状态模式），通过 ConversationConfig 设置 systemInstruction + SamplerConfig（topK/topP/temperature），sendMessage 触发生成，推理完毕 conv.close() 释放资源
  6. 虚拟 Provider 桥接：已下载端侧模型自动注册为 isVirtual=true 的 ModelProvider（id="ondevice-$modelId", protocol="litertlm"），ModelProviderStore.ensureDefaults() 跳过虚拟 Provider
  7. ModelClient 路由：resolveModelClient 增加 "litertlm" 协议分支，创建 LiteRTLMModelClient 适配器（委托 OnDeviceModelManager.generate/generateStream）
  8. 离线 Fallback：复用现有 FailoverEngine，端侧虚拟 Provider 作为 fallbackModelProvider 选项
  9. HuggingFaceMirror 镜像解析：自动替换 huggingface.co 域名为国内镜像（hf-mirror.com），AtomicReference 缓存 + DataStore 持久化
  10. OnDeviceModelStore 持久化：DataStore Preferences 存储模型状态、镜像域名、后端偏好
  11. 预置模型目录：assets/ondevice_models.json 定义 Gemma4 E2B/E4B 等模型配置
  12. AndroidManifest 声明 libvndksupport.so/libOpenCL.so（required=false）支持 GPU 加速
  13. 音频转写能力：OnDeviceModelManager.transcribeAudio(pcmBytes) 通过 LiteRT-LM 的 Content.AudioBytes 实现端侧语音转文字，engineMutex 互斥保护防止转写期间引擎卸载导致 native 崩溃
  14. LiteRTLMEngine 暴露 createConversation(config) 方法供外部构建自定义 ConversationConfig（如音频转写的低 temperature + STT prompt）
  15. capabilities 传递：LiteRTLMEngine 构造函数接收 capabilities: Set<OnDeviceCapability>，用于判断是否启用 audioBackend/visionBackend
- **输入规则**：ModelCallRequest（messages/tools/temperature/topP）、BackendPreference、模型 ID
- **输出结果**：ModelCallResponse/Flow<ModelStreamChunk>、引擎状态变更、虚拟 Provider 注册/注销
- **关键机制**
  - **虚拟 Provider 区分**：isVirtual=true 标记，UI 层隐藏编辑/删除按钮改为跳转端侧管理页
  - **单例引擎**：同一时刻仅一个端侧模型引擎在内存，切换自动卸载旧引擎
  - **无状态 Conversation**：每次推理新建+关闭，避免跨请求状态残留
  - **镜像缓存**：首次探测后缓存到内存+DataStore，后续直接使用缓存结果
  - **SamplerConfig 类型**：temperature/topP 使用 Double 类型（LiteRT-LM API 要求）

### 模型客户

- **功能描述**：统一的多供应LLM 调用接口
- **支持协议**
  - **OpenAI**：兼OpenAI API 格式（支SSE 流式
  - **Anthropic**：Anthropic Messages API（支SSE 流式
  - **Ollama**：Ollama 本地模型 API
- **流程**
  1. 构建请求（消息列+ 工具定义 + 参数
  2. 发HTTP 请求
  3. 解析响应（流式逐块解析 / 非流式整块解析）
  4. 返回 ModelCallResponse Flow<ModelStreamChunk>
- **关键机制**：OkHttp SSE、序列化/反序列化、错误重试、API Key 日志脱敏（仅显示末4位）；SSE onFailure 中提取 HTTP 状态码（如 429），用 runCatching 安全读取 response.body 避免二次 IOException

### 模型路由

- **功能描述**：根据消息复杂度自动选择合适的模型
- **流程**
  1. RuleClassifier 分析消息复杂度（简中等/复杂
  2. MessageComplexityExtractor 提取复杂度特
  3. ModelRouter 根据复杂度选择轻量/重量模型
  4. EscalationContract 定义模型升级条件
- **关键机制**：规则分类、预算管理、上下文管理

### 故障转移引擎

- **功能描述**：主模型调用失败时自动切换到备用模型
- **流程**
  1. 检测主模型调用失败（网络错超时/限流
  2. 查找回退模型列表
  3. 按顺序尝试回退模型
  4. 记录故障信息用于后续分析

### Token 用量管理

- **功能描述**：跟踪和统计 Token 使用量（含缓存 Token 追踪）
- **流程**
  1. 每次模型调用后记录 inputTokens/outputTokens/cacheReadTokens/cacheWriteTokens
  2. 按会话/Agent/时间维度聚合
  3. 提供用量查询接口
- **Cache Token 数据流**
  1. ModelUsage（LLM 响应层）包含 cacheReadTokens/cacheWriteTokens 字段
  2. OpenAI 兼容客户端解析 `prompt_tokens_details.cached_tokens` 为 cacheReadTokens
  3. Anthropic 客户端解析 `cache_read_input_tokens` 为 cacheReadTokens、`cache_creation_input_tokens` 为 cacheWriteTokens
  4. Agent._tokenUsage.update 累加 cacheReadTokens/cacheWriteTokens
  5. TokenUsageManager.recordUsage 传递 cache token 到持久化记录
  6. ChatViewModel 计算 cache token 增量注入 TurnMetadata（cacheReadTokens/cacheWriteTokens）
  7. SessionStore.updateSessionTokenUsage 传入 cache token
  8. UI TurnMetadataBar 显示 CR/CW 标记
- **关键机制**：文件持久化、定期聚合

### ModelProvider 匹配器

- **功能描述**：统一的多供应商匹配逻辑，供 AgentModule 和 SecurityModule 共用
- **匹配级联**：id 精确匹配 → name 匹配 → isDefault 匹配 → 首个 enabled；providerId 为空时返回 null（不回退到默认）
- **按模型名查找**：`findProviderForModel(providers, modelName)` 遍历 providers 查找拥有该模型名的 provider，用于 modelProvider 为空时通过 modelName 反查 provider
- **关键机制**：`ModelProviderMatcher.findMatchingProvider(providers, providerId)` 返回 `ModelProvider?`（providerId 为空返回 null）；`findProviderForModel(providers, modelName)` 返回包含该模型的 `ModelProvider?`；AgentModule 中 modelProvider 非空时用 findMatchingProvider，为空时先用 findProviderForModel 通过 modelName 查找，再回退 findMatchingProvider("")


### API Key Profile 管理

- **功能描述**：管理多API Key，支持轮换和冷却
- **流程**
  1. 维护 Key 列表及使用计
  2. 选择可用 Key（未冷却、未超限
  3. Key 触发限流后进入冷却期
  4. 冷却结束后重新可

## 收件箱系

### 收件箱存

- **功能描述**：统一管理审批请求和推送消息的持久化存
- **流程**
  1. InboxStore 封装 InboxDao，提appendEvent/listEvents/getAllApprovals/resolveApproval 等方
  2. ApprovalService 管理审批生命周期：createPendingApproval 写入数据+ 创建 CompletableDeferred，waitForApproval 挂起等待用户决策，resolveApproval 更新数据库状+ 完成 Deferred
  3. SendNotificationTool 发送通知时同步调inboxStore.appendEvent 写入推送消
  4. ToolApprovalManager 统一管理审批流程：requestApproval 创建审批请求（写InboxStore + ApprovalService 挂起等待 CompletableDeferred，resolveApproval 处理用户决策（四选项 持久化规+ 写入收件箱事
  5. ToolGuardian HIGH+ 风险调用 ToolApprovalManager.requestApproval 挂起等待审批，前台弹+ 后台通知栏审批并
  6. ~~ToolGuard 审批请求同步ApprovalService~~（已废弃并删除，ToolGuardian + ToolApprovalManager 替代
  7. ~~ToolApprovalService.showApprovalNotification 同步调用 ApprovalService.createPendingApproval~~（已废弃并删除，通知栏审批委ToolApprovalManager.resolveApproval
- **关键机制**：Room 持久化（inbox_events + pending_approvals 表）、CompletableDeferred 挂起等待、Koin DI 注入、ToolApprovalReceiver 通过 companion object 静态引ToolApprovalManager（避BroadcastReceiver 中使runBlocking + GlobalContext）、ApprovalDecision 统一inbox 枚举（APPROVED/DENIED）、ToolApprovalManager 规则存储使用 DataStore（Preferences DataStore）、_pendingApprovals 使用 Mutex 保护并发安全

## 工具系统

### 工具注册

- **功能描述**：管理所有工具的注册、查询和执行
- **流程**
  1. ToolInitializer 注册所有内置工
  2. 工具按名称注册到 ToolRegistry
  3. Agent 构建请求时通getDefinitionsForAgent() 获取该 Agent 可见的工具列表（4 层过滤）
  4. Agent 请求执行工具时，Registry 查找并调
  5. 执行前进行安全检查（ToolGuardian 项检+ HIGH 起审 + ToolApprovalManager 四选项审批
  6. HIGH/CRITICAL 风险需用户审批（四选项：允许一始终允许/拒绝一不再允许），MEDIUM 仅记日志
  7. 审批结果写入收件箱（不管处理没处理都显示
  8. 安全阻止的工具调用写入收件箱（ToolApprovalManager.recordBlockedCall 记录 blocked_by_guardian 事件
  9. 执行后返ToolResult
- **关键机制**
  - 工具中文名映射：BuiltinToolNames 提供工具英文名→中文显示名映射，ToolCallBlockView 使用 getDisplayName() 展示中文名，无映射时回退英文原名
- **关键机制**
  - 权限检查器（Android 权限 + 自定义权限）
  - 延迟工具注册（DeferredToolRegistry
  - 工具访问控制（ToolAccessController）Agent 构建请求时使getDefinitionsForAgent(agentId) 而非 getVisibleDefinitions()，确denyList/allowList 真正生效
  - **按 Agent 角色动态工具过滤（defaultToolKit）**：已废弃（2026-05-26）。工具全量注入，不再以 defaultToolKit 过滤，仅保留访问控制层（denyList/allowList）过滤
  - 文件锁管理（FileLockManager，防止并发写入冲突）
  - TTL 管理（ToolTTLManager

### 按 Agent 角色动态工具过滤（defaultToolKit）— 已废弃

- **废弃时间**：2026-05-26
- **废弃原因**：工具全量注入，不再以 defaultToolKit 过滤。ToolRegistry.getDefinitionsForAgent() 仅做 TTL 可见性 + 访问控制两层过滤，所有工具对 Agent 可见
- **向后兼容**：AgentProfile.defaultToolKit 字段保留（序列化兼容），但不再影响工具过滤逻辑

### 内置工具

- **功能描述**：Agent 可调用的内置能力
- **工具列表**
  - **ShellTools**：执Shell 命令、获取工作目录、管理环境变
  - **FileTools**：读写文件、列表目录、搜索文件（write_file/edit_file 返回 diff 格式，append_file 返回追加内容原文，delete_file 返回简"Deleted path"）；文件不存在时相对路径自动重定向到智能体工作区（绝对路径不重定向）；edit_file 工具描述强制要求先用 read_file 读取原文再精确复制 old_text，old_text 未找到时返回含文件预览的错误提示
  - **GitTools**：Git 操作
  - **ImageGenerateTool**：AI 图片生成
  - **SkillTools**：技能管
  - **ReadLogcatTool**：读Logcat
  - **SendNotificationTool**：发送通知，system 类型同时发送系统通知栏通知和悬浮窗弹窗（无悬浮窗权限时仅走通知栏）
  - **SendImageToUserTool**：发送图
  - **CronTool**：定时任务管
  - **GetCurrentTimeTool**：获取当前时
  - **GradleTools**：Gradle 构建
  - **ViewMediaTools**：查看媒体文
  - **ToolSearchTool**：搜索延迟加载的工具
  - **AskClarificationTool**：请求用户澄
  - **LoadSkillTool**：动态加载技能；回退逻辑：workspace/skill.json → workspace/skill_config.json → AgentRepository.getEnabledSkillsFromJson（含 AgentProfile.skills 回退）；加载技能时调用 SkillLifecycleManager.activateSkill 动态注册技能关联工具
  - **MemorySearchTool**：搜索长期记忆，搜索范围包括：智能体的 MEMORY.md、memory 文件夹下所有 markdown 文件、Second Brain 记忆库；封装 MemoryRepository.searchHybrid + 工作区文件关键词搜索，支持 query + limit 参数，返回分类结果（工作区记忆文件 + Second Brain 记忆库）
  - **AgentInfoTool**：查询智能体信息（agent_info），支持4种查询模式：单智能体查询、列表查询、关键词搜索、群组成员查询

### Android 系统工具

- **功能描述**：调Android 系统能力的工
- **工具列表**
  - **WifiTools**：WiFi 管理
  - **SmsTools**：短信收
  - **SensorTools**：传感器数据读取
  - **PhoneTools**：拨打电
  - **NotificationTools**：通知管理（含通知缓存
  - **MediaTools**：媒体管
  - **MediaSessionTools**：媒体会话控
  - **LocationTools**：定
  - **ContactTools**：联系人访问
  - **CalendarTools**：日历管
  - **BluetoothTools**：蓝牙管
  - **AndroidTools**：通用 Android 操作
  - **AlarmTools**：闹钟管

### 网络工具

- **功能描述**：网络访问工
- **工具列表**
  - **WebFetchTool**：抓取网页内
  - **WebSearchTool**：网络搜

### 浏览器自动化工具

- **功能描述**：在嵌入式 WebView 中控制网页交互，支持导航、点击、输入、内容提取、截图等操作。Agent 可像用户一样操作网页，覆盖大量未 API 化的服务。相比 "截图+VLM 分析+点击坐标" 方案，WebView 操作不需要 VLM 调用，Token 成本更低。
- **交互逻辑**
  1. Agent 调用 `browser` 工具并指定 action 参数
  2. `BrowserAutomationTool` 根据 action 路由到对应处理函数
  3. `WebViewController` 管理 WebView 实例的创建、导航、JS 执行
  4. `PageContentExtractor` 将页面内容结构化为 LLM 友好格式
  5. 执行结果返回到 Agent 的 ReAct 循环
  6. 多个子命令共用同一个 WebView 实例，保持会话状态（cookie/localStorage）
  7. `WebViewController.destroy()` 释放 WebView 资源
- **子命令**
  - `browser_navigate(url)`：导航到 URL，返回结构化页面摘要（标题、描述、标题列表、链接、文本预览）
  - `browser_click(selector|text|index)`：点击元素，支持 CSS 选择器、文本内容、元素索引三种定位方式
  - `browser_type(selector, text)`：在输入框中输入文本
  - `browser_get_text()`：获取页面纯文本内容（上限 30K 字符）
  - `browser_get_html()`：获取页面原始 HTML（上限 50K 字符）
  - `browser_screenshot()`：对当前页面截图，保存为 PNG 到缓存目录
  - `browser_scroll(direction, amount)`：向下/上滚动页面
  - `browser_get_interactable()`：获取所有可交互元素列表（含索引、标签、文本、位置矩形），返回 Markdown 表格
  - `browser_back()`：返回上一页
  - `browser_forward()`：前进
  - `browser_wait(selector, timeout)`：等待 CSS 选择器匹配的元素出现
  - `browser_execute(script)`：执行任意 JavaScript，返回执行结果
  - `browser_close()`：关闭浏览器释放 WebView 资源
- **输入规则**
  - `action`：必填，操作类型字符串
  - `url/selector/text/index/direction/script/timeout/amount`：按 action 不同选填
- **输出结果**：结构化内容（Markdown 正文 + 表格）+ 截图文件路径（screenshot 时），单次输出上限 30K 字符
- **关键机制**
  - **WebView 会话保持**：单例 WebView 实例，连续操作共享 cookie/localStorage
  - **JS 安全转义**：所有用户输入参数经过 `escapeJs()` 转义防止注入
  - **延迟等待**：点击/导航后自动等待 500-800ms 让页面渲染
  - **CompletableDeferred 异步桥接**：WebView 回调 → 协程挂起恢复
  - **可交互元素解析**：`kotlinx.serialization.json` 解析 JS 返回的结构化 JSON

### 手机控制工具

- **功能描述**：通过无障碍服务控制手
- **工具列表**
  - **ScreenInteractTool**：屏幕交互（点击、滑动、输入）
  - **PhoneAutomateTool**：手机自动化（VLM 分析屏幕 + 执行操作

### Linux 工具

- **功能描述**：在 PRoot Linux 容器中执行操
- **工具列表**
  - **ExecuteBashTool**：执Bash 命令
  - **ExecutePythonTool**：执Python 脚本
  - **InstallPackageTool**：安Linux 
  - **FileTransferTool**：Android Linux 文件传输
  - **ClipboardSyncTool**：剪贴板同步
  - **DeviceAccessTool**：设备访
  - **SshServerTool**：SSH 服务

### MCP 工具注册

- **功能描述**：将 MCP 协议提供的工具转换为 Agent 可调用的工具
- **流程**
  1. MCPClientManager 连接 MCP 服务
  2. 获取服务器提供的工具列表
  3. MCPToolRegistrar MCP 工具转为 ToolDefinition
  4. 注册ToolRegistry
- **关键机制**：MCP 协议适配、工具参数转换、客户端断开时自动注销工具（unregisterClient

### 子代理工

- **功能描述**：支持当前智能体并发执行多个子任务（嵌套 session，不创建新智能体）
- **工具列表**
  - **SpawnSubAgentTool**：派发并发子任务（tasks 参数含 prompt，不含 agent_id；从 ToolContext 获取当前智能体 ID）
  - **CheckSubAgentTasksTool**：检查子代理任务状态
  - **AggregateSubAgentResultsTool**：聚合子代理结果

## 多智能体协作

### 群聊广播预检评分系统

- **功能描述**：群聊广播消息前的三关预检评分，决定哪Agent 需要接收广播消
- **流程**
  1. BroadcastPreScorer 组装三关评分管道：TriggerWordMatcher DescriptionPhraseMatcher SemanticScorer
  2. 对每个候Agent 依次执行管道中的 Scorer，取最高分
  3. 分数达到阈值（默认 7）时早期退出，不再执行后续 Scorer
  4. 返回按分数降序排列的 RelevanceScore 列表，isRelevant 判断是否需要广
- **三关评分*
  - **TriggerWordMatcher**：触发词精确匹配，命2+ 9 分，命中 1 7 分，未命0 
  - **DescriptionPhraseMatcher**：描述短语匹配，关键词交集每+2 分，短语命中每个 +3 分，上限 7 分；内置中文停用词过
  - **SemanticScorer**：语义相似度评分，HybridSemanticScorer 混合实现（0.6 关键词相似度 + 0.4 词频余弦相似度），ONNX 模型可用时自动刷新嵌入
- **关键机制**：管道式评分、早期退出、工厂函数自动检ONNX 模型文件决定是否启用语义评分

### 群聊编排

- **功能描述**：编排多 Agent 群聊对话，已合并AgentGroup 中统一管理
- **流程**
  1. AgentGroup 接收用户消息，通过 GroupMessageRouter 路由到目Agent
  2. 为每个目Agent 构建群组系统提示（通过 systemPromptSuffix 注入，使GroupChatPrompts.buildAgentSystemPrompt
  3. 群组系统提示包含：智能体自身身份描述（agentDescriptions[agentId]）、群组信息、其他智能体描述、@沟通方式、提及链路（含回环检测提示）、群聊交流规则（通过 @ 相互回复形成讨论）
  4. 所有 Agent 共享同一 sessionId（groupId），不再创建 `${groupId}_${agentId}` 的独立会话，消除 UI 中出现的幽灵会话
  5. 群组通信上下文通过 systemPromptSuffix 提供（按相关性过滤的消息、成员描述），不依赖 session 内其Agent 的消息；过滤规则：保留用户广播消+ @当前 Agent 的消+ 当前 Agent 自身消息，移takeLast(20) 硬截断，Agent ContextManager Token 限制自动压缩
  6. Agent 回复后通过 `sessionStore.updateMessageSenderId` 更新消息发送者标识，不再将消息从 agentSessionId 复制到 displaySessionId
  7. 群组模式下禁 subagent 系列工具、chat_with_agent 和 list_agents（通过 denyList 从源头过滤工具定义注入，LLM API 请求中不包含这些工具的 ToolDefinition，LLM 完全看不到这些工具；不使用 system prompt 列禁用工具的方式），多智能体讨论直接通过 @ 在群组中实现，群组处理结束后自动清 denyList
  8. 上下文双层过滤机制：filterGroupMessagesForAgent 过滤群聊消息（系统提示词上下文）+ contextMessageFilter 过滤 session 消息（移除前次调用的 TOOL 消息 + 过滤其他智能体的内部 TOOL 消息）
  9. processAgentResponse 调用 agent.processMessage 时传递 overrideProviderId=agent.profile.modelProvider.ifBlank{null}，确保群聊中每个智能体使用自己配置的模型供应商
  10. @提及信号优先：当 mentionedAgentIds 非空时，跳过 LLM 发言者选择和 ping-pong 检测（@提及是最强信号，无需浪费 LLM 调用，也不应被 ping-pong 拦截）
  11. LLM 终止判断后移：从处理前移至处理后，仅在轮次超过 maxRounds/2 或检测到 ping-pong 时才调用 LLM 判断是否终止
  12. Mention Chain 传播：Agent 回复后检查内容中的 @提及，通过 MentionChainManager.checkPropagation 防环后，将消息入队并通过 groupScope 异步触发被提及 Agent 处理（避免 agentMutexes 死锁：同步调用会导致 A 持锁等 B，B 又等 A 锁的循环等待）
  13. determineRespondingAgents 过滤已删除智能体（agentFactory.getAgent 非空检查）
  14. @提及过滤已删除智能体（agentProfilesMap 检查）
  15. AgentGroupManager.removeAgent 调用 AgentGroup.removeAgentId 从 _agentIds 中移除，GroupMemberListTool 的 agentNamesProvider 动态读取 _agentIds.value，确保 get_group_members 不返回已删除智能体
- **关键机制**：统一 groupId session 消除幽灵会话、contextMessageFilter 过滤其他 Agent 内部 TOOL 消息、updateMessageSenderId 替代消息复制、systemPromptSuffix 注入群组规则、denyList 工具限制、denyList 生命周期管理、双层上下文过滤（群聊消息过+ session TOOL 消息过滤）、ContextManager 自动压缩替代硬截断、_messages 使用 MutableStateFlow.update 原子更新（消除 Mutex 锁，避免与 agentMutexes 死锁）、activeCount try/finally 统一递减、@提及跳过 LLM 决策和 ping-pong 检测、广播消息兜底（targetAgents 为空时回退到全部智能体）、LLM 终止后移至处理后条件触发、Mention Chain 异步传播（MentionChainManager 防环 + 队列入队 + groupScope.launch 异步触发避免死锁；异步传播时 activeCount 递增，完成后递减，确保 isActive 状态准确反映所有异步处理进度）、串行循环后 drainQueues（串行 for 循环处理完所有 Agent 后，执行 drainQueues 处理异步 @传播期间入队的遗留消息，确保先回复的 Agent 的 @mention 能被后回复的 Agent 正确感知）、GroupMemberListTool onDemand 注册 + forceOnDemandTools 群聊强制注入 + 群聊结束注销、群聊 UI 轮询监听 group.isActive StateFlow（轮询持续到 isActive 变为 false，确保异步 @传播产生的消息也能实时刷新到 UI）、广播消息串行回复（hasExplicitMentions 为 true 时并行 supervisorScope+async，为 false 时串行 for 循环逐个处理，避免智能体"人格分裂"——并行时各 Agent 看不到彼此回复导致重复问候）、CancellationException 正确重抛（catch 块先捕获 CancellationException 并 throw，再 catch Exception，保证协程取消机制正常）
- **合并能力**（从 GroupChatOrchestrator 迁移，通过 AgentGroupConfig 可选启用）
  - **LLM 发言者选择**（`selectNextSpeakerWithLLM()`）：调用 LLMSpeakerSelector 选择下一个发言的智能体，支SpeakerSelected/Finish/Error/Continue 四种结果；启用后覆盖路由结果，仅发送给 LLM 选中的智能体；当有显式 @提及时跳过
  - **Ping-pong 检*（`detectPingPong()`）：基于 GroupCollaborationProtocol.shouldStopPingPong()，检测最近消息是否无新任问题/决策，判定无意义来回对话并自动终
  - **LLM 终止判断**（`shouldTerminateWithLLM()`）：调用 LLMSpeakerSelector.shouldTerminate()，让 LLM 判断群聊是否应该结束；终止关键词支持多语言（YES/yes/Y/是/完成/结束/done/complete/finished），拒绝终止关键词（NO/no/N/否/继续/未完成/continue/not yet/ongoing），trim+首词+大小写不敏感匹配；仅在处理后且满足条件（轮次>maxRounds/2 或 ping-pong 检测）时调用
  - 三项能力均通过 AgentGroupConfig 配置开关（enableLLMSpeakerSelection/enablePingPongDetection/enableLLMTermination），默认关闭
  - LLM 决策模型配置：AgentGroupConfig 和 GroupChatConfig 支持 llmSelectorProviderId + llmSelectorModelName 字段，指定群组决策（发言者选择/终止判断）使用的模型供应商和模型名；LLMSpeakerSelector 优先使用 providerId/modelName 组合查找 modelClient，回退到 llmSelectorModel 按模型名查找；AgentModule 构建时遍历所有 enabled provider，为每个模型注册双键（modelName + providerId/modelName），确保 provider+model 精确匹配可用

### 群聊交流规则

- **功能描述**：群聊中智能体之间的交流方式规则，通过 @ 在群组中直接讨论
- **交互逻辑**
  1. 直接在回复中 @目标智能体，对方会在群组中回复
  2. 多智能体讨论时，通过 @ 相互回复即可形成讨论
  3. 如果检测到 @ 回环（同一提及链中再次 @ 已出现的智能体），系统在智能体系统提示词中注入回环提示，由智能体自行判断是否继续讨论或给出最终回复
- **输入规则**：智能体回复中的 @提及
- **输出结果**：群组内可见回复

### @ 回环检测与提示

- **功能描述**：当 @ 传播形成回环（如 A→B→A）时，不再硬性阻断，而是允许传播并在系统提示词中注入回环提示，让智能体自行判断
- **交互逻辑**
  1. MentionChainManager.checkPropagation 检测到回环时，将目标标记为 cycleTarget 而非拒绝
  2. 回环目标仍然允许传播（加入 allowed 列表），同时记录在 PropagationResult.cycleTargets 中
  3. AgentGroup.processAgentQueue 传递 isCycleTarget 参数给 processAgentResponse
  4. GroupChatPrompts.buildAgentSystemPrompt 接收 isCycleTarget 参数，为 true 时注入"⚠ @ 回环检测"提示
  5. 提示内容：告知智能体已形成回环，请自行判断讨论是否已有结论，避免无意义来回对话
  6. 仅 maxDepth 超限时才硬性阻断（不允许传播）
- **输入规则**：MentionPath 中的回环检测结果
- **输出结果**：回环目标允许传播 + 系统提示词注入回环提示

### chat_with_agent 私聊级联删除

- **功能描述**：用户删除会话时，自动删除由该会话产生的 chat_with_agent 私聊会话
- **交互逻辑**
  1. 私聊会话 ID 格式：`private_{callerAgentId}_{targetAgentId}_{timestamp}_{sourceSessionId}`，sourceSessionId 标识发起该私聊的源会话
  2. 删除会话时调用 `SessionStore.deleteSessionWithPrivateChats(sessionId)`，按 `_sourceSessionId` 后缀匹配关联私聊并级联删除
  3. 先删除关联私聊会话及其消息，再删除主会话
- **输入规则**：sessionId
- **输出结果**：主会话及其关联私聊会话全部删除

### 会话隐藏

- **功能描述**：支持将会话标记为隐藏，使其不出现在会话列表中
- **交互逻辑**
  1. 调用 SessionStore.hideSession(sessionId) 将会话标记为 hidden=true
  2. 会话列表查询（getByAgentId/getAll/observeAll/searchByTitle）自动排除 hidden=true 的会话
  3. 隐藏会话仍可通过 getById 直接访问
  4. 数据库迁移 v16->v17：sessions 表新增 hidden 列（INTEGER NOT NULL DEFAULT 0）
- **输入规则**：sessionId
- **输出结果**：会话从列表中隐藏

### 私聊消息角色

- **功能描述**：新增 MessageRole.PRIVATE 枚举值，用于标识群聊中智能体之间的私聊消息
- **交互逻辑**
  1. PRIVATE 角色消息在 LLM API 中映射为 "user" 角色
  2. PRIVATE 消息不更新会话的 lastMessage 预览
  3. PRIVATE 消息在上下文评分中权重为 0.20f（介于 ASSISTANT 和 SYSTEM 之间）
  4. PRIVATE 消息在压缩摘要中显示为"私聊"标签
  5. ChatWithAgentTool 返回 ToolResult 时剥离目标智能体的 thinking 内容（使用 parseThinkingAndReply），仅返回正文回复给调用方；PRIVATE 消息同样只包含正文回复（不含 thinking 和 tool），确保讨论折叠正确显示双向对话、调用方不会将目标智能体的 thinking 当作自己的回复输出
  6. ChatWithAgentTool 执行前检查目标智能体 enabled 状态：`agent.profileConfig.enabled` 为 false 时返回错误 "Agent {id} is disabled"，不执行私聊
- **输入规则**：通过 sessionStore.addMessage(role=MessageRole.PRIVATE) 写入
- **输出结果**：私聊消息持久化，序列化/反序列化自动支持

### 禁用智能体消息隔离

- **功能描述**：当用户关闭智能体的后台运行（enabled=false）时，该智能体在 chat_with_agent 和群聊中不接收任何消息也不运行
- **交互逻辑**
  1. ChatWithAgentTool.execute 中获取目标智能体后检查 `agent.profileConfig.enabled`，为 false 时返回 ToolResult 错误
  2. AgentGroup.processMessage 中路由完成后过滤 targetAgents，移除 enabled=false 的智能体，并记录日志
  3. AgentGroup.processAgentResponse 中获取智能体后检查 `agent.profileConfig.enabled`，为 false 时跳过处理返回 null（防止 mention chain 传播到禁用智能体）
- **输入规则**：AgentProfile.enabled 字段
- **输出结果**：禁用智能体不参与任何消息处理和群聊交互

### Agent 信息系统

- **功能描述**：提供智能体元信息的查询与缓存能力，支持 Agent 间互相了解身份、职责、边界和协作偏好
- **交互逻辑**
  1. AgentInfoTool（agent_info）对外暴种查询模式：单智能体查询（query=agentId）、列表查询（query="list"）、关键词搜索（query="search:keyword"）、群组成员查询（query="group:groupId"
  2. AgentInfoRepository 聚合 AgentRegistry、AgentRepository、GroupRegistry 数据源，构建 AgentCard 数据模型
  3. AgentCardParser 作为 fallback 解析器，SOUL.md 解析 identity、从 AGENTS.md 解析 responsibilities/boundaries
  4. AgentInfoCache 基于 ConcurrentHashMap 的事件驱动缓存，监听 AgentRegistry 变更自动失效
  5. AgentProfile 扩展 identity/responsibilities/boundaries/collaboration 字段，优先使用结构化数据，fallback Markdown 解析
- **输入规则**：query 参数（agentId / list / search:keyword / group:groupId）、可refresh 参数
- **输出结果**：ToolResult 包含 JSON 格式 output 和人类可forUser 摘要

### Agent 群组管理

- **功能描述**：管Agent 群组的创建、配置和生命周期
- **流程**
  1. GroupRegistry 持久化群组配置（Room 数据agent_groups 表），支renameGroup 仅更新名称而不丢失其他字段
  2. AgentGroupManager 统一管理群组生命周期和状态机（直接持AgentFactory + AgentMessageBus + LLMSpeakerSelector + GroupCollaborationProtocol，不再依GroupChatOrchestrator/GroupLifecycleManager
  3. 群组生命周期状态机：FORMING ACTIVE SUSPENDED DISSOLVED
     - FORMING：群组刚创建，尚未添加成员；添加第一个成员后自动切换ACTIVE
     - ACTIVE：群组活跃，可正常通信；suspendGroup() 可切换为 SUSPENDED
     - SUSPENDED：群组暂停（所有成员离不活跃）；activateGroup() 可恢复为 ACTIVE
     - DISSOLVED：群组解散（所有成员移除或手动解散）；不可逆，不可再添加成
  4. 群组成员状态跟踪（GroupMemberStatus：ONLINE/INACTIVE/OFFLINE），支持 updateMemberStatus 更新心跳状
  5. 健康检查（checkGroupHealth）：检测不活跃/离线成员，给出建议状态（全部离线→SUSPENDED，无成员→DISSOLVED，否则→ACTIVE
  6. AgentMessageBus 联动：addAgent 时注册消息路由，removeAgent/dissolveGroup 时注销
  7. AgentGroup 定义群组成员和规则，处理群组消息路由Agent 响应
  8. getOrCreateAgentGroup 创建 AgentGroup 时注speakerSelector + collaborationProtocol，默认启LLM 发言者选择、Ping-pong 检测、LLM 终止判断
- **关键机制**：群组持久化（Room agent_groups 表，groupId/groupName/agentIds/mentionOnlyAgentIds/llmSelectorProviderId/llmSelectorModelName/createdAt 字段）、成员管理、AgentGroup 缓存（addAgent/removeAgent cleanup 旧实例确保重建一致性）、renameGroup 原子更新名称、updateGroupLlmSelector 更新决策模型配置（更新后清除 AgentGroup 缓存强制重建）、JSON→Room 数据迁移（首次启动自动迁groups.json 并删除旧文件）、生命周期状态机、成员状态跟踪、健康检查、消息总线联动、LLM 发言者选择/Ping-pong 检LLM 终止判断默认启用

### 群聊 @ 机制核心组件

- **功能描述**：群@mention 的回环检测提示、并行触发仲裁、名称模糊映射、上下文过滤三大基础组件
- **交互逻辑**
  1. MentionChainManager：回环检测机制，深度检查（>=maxDepth 拒绝）+ 环路检查（hasCycle：agentId 已在 path 中即视为环路，但不再硬性阻断，而是标记为 cycleTarget 允许传播，同时在系统提示词中注入回环提示让智能体自行判断），为每 allowed 目标生成独立 MentionPath；PropagationResult 新增 cycleTargets 字段记录回环目标；熔断器（连续拒绝=threshold 触发，冷却后自动恢复）；GroupMessageRouter 路由时检 isCircuitOpen 跳过熔断目标、被拒绝时调 recordRejection 记录
  2. ParallelArbitrator：并行触发仲裁，基于 TriggerTicket 和 TriggerPriority（USER_DIRECT > USER_BROADCAST > AI_MENTION > AI_SILENT）管理会话锁，支持优先级抢占（USER_DIRECT 抢占所有、USER_BROADCAST 抢占 AI_MENTION 和 AI_SILENT、AI_MENTION 抢占 AI_SILENT），30s 超时自动驱逐，evictExpired 惰性清理（仅当 lockTimestamps 超过 1000 条时触发）
  3. DisplayNameFuzzyMapper：@displayName agentId 映射，支持精确匹agentId 精确匹配 displayName 前缀匹配 包含匹配四级降级
- **输入规则**：MentionPath、TriggerTicket、mentionText、GroupChatMessage 列表
- **输出结果**：PropagationResult（allowed/rejected/paths/cycleTargets）、AcquireResult（Granted/Denied）、agentId

### Agent 消息总线

- **功能描述**：Agent 间消息传递的基础设施
- **流程**
  1. InMemoryAgentMessageBus 实现内存消息总线
  2. AgentMessageQueue 管理每个 Agent 的消息队列（双队列：urgentQueue + normalQueue，O(1) dequeue）
  3. MessageRelay 负责消息中继
  4. MentionParser 解析 @mention（Regex 预编译为顶层常量，避免每次调用重新编译）
  5. GroupMessageRouter 路由群组消息
- **关键机制**：发订阅模式、消息队

### ~~DAG 任务编排~~（已废弃，2026-05-20 删除）

- **功能描述**：基于有向无环图的任务编
- **流程**
  1. DagTaskOrchestrator 解析 DAG 任务
  2. TaskStateMachine 管理任务状态转
  3. 并行执行无依赖的任务
  4. 串行执行有依赖的任务
  5. 聚合所有任务结
- **关键机制**：DAG 拓扑排序、状态机、并行调度、ProcessingMarker 过期检测、TeamTaskHandoff 结果写入

### 子代理系

- **功能描述**：支持当前智能体并发执行多个子任务（嵌套 session），不创建新智能体
- **流程**
  1. Agent 通过 SpawnSubAgentTool 派发并发子任务（tasks 参数为 JSON 数组，每项含 prompt，不含 agent_id）
  2. SpawnSubAgentTool 从 ToolContext 获取当前 agentId 和 sessionId，传递给 SubAgentOrchestrator
  3. SubAgentOrchestrator 基于 HippyJob 队列调度子任务，将 agent_id 和 parent_session_id 写入 job data
  4. SubAgentLoopHandler 从 job data 读取 agent_id，通过 agentFactory.getAgent 获取当前智能体自身（不是新建智能体）
  5. 子任务使用嵌套 session（格式：subagent_{parentSessionId}_{jobId}），不出现在会话列表中
  6. 子任务完成后 SubAgentLoopHandler 调用 sessionStore.hideSession 隐藏会话
  7. 子任务设置 denyList 禁止子代理相关工具（spawn_subagent、spawn_sub_agent、check_subagent_tasks、aggregate_subagent_results、chat_with_agent），防止递归派生
  8. 父代理通过 CheckSubAgentTasksTool 检查进度，AggregateSubAgentResultsTool 汇总结果
- **关键机制**：HippyJob 队列、嵌套 session（不显示在会话列表）、denyList 防递归、session 完成后自动隐藏、当前智能体并发执行（不创建新智能体）

### ACP 通信

- **功能描述**：Agent Communication Protocol 服务器和客户
- **流程**
  1. ACPServer 提供 HTTP 端点供外Agent 连接
  2. AcpClientStore 管理外部 Agent 客户端信
  3. DelegateExternalAgentTool 允许委托任务给外Agent
- **关键机制**：HTTP 传输、客户端认证

### Agent 注册

- **功能描述**：全局 Agent 实例注册表，提供 Agent 的注册、查询和生命周期管理
- **交互逻辑**：Agent 创建时注册到 AgentRegistry，销毁时注销；其他组件通过 Registry 查询可用 Agent
- **输入规则**：Agent 实例注册/注销请求、Agent ID 查询
- **输出结果**：Agent 实例引用、Agent 列表

### Agent 会话管理

- **功能描述**：管Agent 的会话绑定和会话生命周期，维Agent Session 的映射关
- **交互逻辑**：Agent 启动时绑定会话，结束时释放；支持Agent 查询活跃会话、按会话查询所Agent
- **输入规则**：Agent ID、Session ID 绑定/解绑请求
- **输出结果**：AgentSession 映射、活跃会话列

### Agent 消息路由

- **功能描述**：Agent 间消息的路由分发，支持点对点和广播消息投
- **交互逻辑**
  1. 接收 AgentMessage（含发送方、接收方、负载）
  2. 点对点消息直接投递到目标 Agent 队列
  3. 广播消息投递到群组内所Agent
- **输入规则**：AgentMessage（senderId、recipientId、payload
- **输出结果**：消息投递到目标 Agent

### 主动触发

- **功能描述**：基于记忆和频道状态主动触Agent 交互，实Agent 主动式行
- **交互逻辑**
  1. 监听频道消息和记忆变
  2. MemoryManager + HybridRetriever（语义检+ 关键词检索）评估触发条件
  3. 满足条件时构造触发消息发送给目标 Agent
- **输入规则**：频道事件、记忆更
- **输出结果**：Agent 主动交互消息

### 团队任务交接

- **功能描述**：多 Agent 团队内的任务交接和工件传递，支持 Agent 间工作成果的有序流转
- **交互逻辑**
  1. Agent 完成子任务后，将产出物写入共workspace 目录
  2. TeamTaskHandoff 记录交接关系（源 Agent 目标 Agent 工件路径
  3. 目标 Agent workspace 读取交接工件继续执行
- **输入规则**：源 Agent ID、目Agent ID、工件描
- **输出结果**：交接记录、工件文件路

### 处理标记

- **功能描述**：标记和跟踪群组协作中消息的处理状态，防止重复处理和遗
- **交互逻辑**
  1. 消息进入处理时标记为 ProcessingMarkerData（含 Agent ID、消ID、时间戳
  2. 处理完成后清除标
  3. 其他 Agent 可查询消息是否已被处
- **输入规则**：消ID、Agent ID
- **输出结果**：处理状态（已处未处处理中）

### 群组协作协议

- **功能描述**：定义群组内 Agent 的协作规则和 Mention 交换协议，规范多 Agent 协作行为
- **交互逻辑**
  1. GroupCollaborationProtocol 定义协作规则（发言顺序、Mention 解析、回复协议）
  2. MentionExchange 处理 Agent 间的 @mention 交互
  3. shouldStopPingPong() 检测无意义来回对话，已集成AgentGroup.detectPingPong()
  4. ~~isMinimalMentionRequired(TaskEvent)~~（已删除 2026-05-21，TaskEvent 随 taskflow/taskqueue 死代码清理已移除）
- **输入规则**：群组配置、Mention 消息
- **输出结果**：协作规则、Mention 路由结果


## 任务与流

### HippyJob 任务队列

- **功能描述**：持久化任务队列，支持优先级、重试、超时、幂
- **流程**
  1. HippyJobQueue 提交任务到队
  2. HippyJobWorker 轮询获取待执行任
  3. 执行任务（支持子任务、超时、重试）
  4. 更新任务状态（WAITING ACTIVE COMPLETED/FAILED
  5. StallDetector 检测卡住的任务
  6. RateLimiter 限制任务执行速率
- **关键机制**
  - Room 持久化（hippy_jobs 表）
  - 优先级排序（复合索引 status+priority+createdAt
  - 指数退避重
  - 幂等性保证（idempotencyKey
  - 父子任务关系
  - 锁机制（lockToken + lockUntil

### 流程引擎

- **功能描述**：多步骤流程的编排和执行
- **流程**
  1. FlowEngine 创建流程记录
  2. FlowStepExecutor 执行每个步骤
  3. 步骤间可传递数
  4. 支持流程状态查询和取消
- **关键机制**：Room 持久化（flow_records + flow_steps 表）

### 心跳调度

- **功能描述**：定时触Agent 主动交互
- **流程**
  1. HeartbeatScheduler 根据配置创建定时任务
  2. 到达心跳时间时，构造心跳消
  3. 调用 Agent.processMessage 处理
  4. 记录心跳执行结果
- **关键机制**：CoroutineScheduler、配置化间隔

### Cron 任务管理

- **功能描述**：Cron 表达式驱动的定时任务，支持自然语言时间描述自动解析为 cron 表达式，支持选择会话和静默执行
- **流程**
  1. CronJobManager 解析 Cron 表达式
  2. 创建定时触发（OneTimeWorkRequest，WorkManager 调度）
  3. 到达时间时 CronJobWorker 通过 Koin 单例 CronJobManager 调用 executeJob
  4. executeJob 获取 Agent 实例，使用指定的 sessionId（若为空则创建或复用标题为"定时任务: {jobName}"的会话），调用 agent.processMessage 发送消息到对话
  5. 执行完成后记录执行历史，并重新调度下一次执行（updateJob 重新计算延迟）
  6. silentMode=true 时后台静默执行，不通知用户，不创建新会话（直接使用 channelId 作为 sessionId）
  7. App 重启后 loadHistory 自动重新调度所有 enabled 的定时任务
  8. parseCronToDelay 支持 */N 格式（如 */5 * * * * 每5分钟、0 */2 * * * 每2小时）
  9. CronTool 注册为 onDemand=false，始终注入 LLM tools schema
- **自然语言解析**
  1. NaturalLanguageCronParser 将中文自然语言时间描述解析为标准 cron 表达式
  2. CronTool 新增 `natural_language` 可选参数，当 `schedule` 为空时自动解析
  3. 支持的模式：每天X点、每周X X点、工作日X点、每隔X小时/分钟、每月X号X点、明天X点、X分钟/小时/秒后
  4. 解析结果包含 cron 表达式、人类可读描述、置信度、是否一次性任务、延迟毫秒数
  5. 无法解析时返回错误提示，引导用户使用 cron 表达式格式
- **CronTool 返回结果**
  1. create 操作返回创建成功的任务信息（任务 ID、名称、调度、指令）
  2. list 操作返回当前智能体的任务列表（ID、名称、调度、状态、指令），按 agentId 精确过滤
  3. delete 操作返回删除结果（任务 ID、名称）
  4. schedule 参数为可选，与 natural_language 二选一
- **关键机制**：Cron 表达式解析、AlarmManager、WorkerFactory 注册 CronJobWorker（五参构造：Context + WorkerParameters + AgentFactory + SessionStore + CronJobManager）、NaturalLanguageCronParser 正则匹配 + 预处理归一化、执行后自动重新调度；CronJobManager 并发安全：createJob/deleteJob/updateJob/recordExecution/clearHistory 使用 mutex.withLock 保护，读操作（getEnabledJobs/getJob/getExecutions/getStats）使用 toList() 快照避免 ConcurrentModificationException

## 记忆与人

### 记忆管理

- **功能描述**：协调记忆的存储、检索和更新
- **流程**
  1. 短期记忆：当前对话上下文
  2. 长期记忆：文件存+ 向量检
  3. Second Brain：Room + FTS5 全文搜索
- **关键机制**：分层记忆架

### Second Brain 记忆系统

- **功能描述**：结构化长期记忆存储和检
- **流程**
  1. MemoryExtractor 从对话中提取记忆条目
  2. 记忆条目包含：摘要、详情、类型、置信度、重要性、耐久
  3. MemoryDao 提供 CRUD + FTS 搜索
  4. MemoryRepository 提供高层接口（存检修剪/统计
  5. 记忆类型：事fact)、偏preference)、程procedure)、社social)
  6. 证据类型：对话、工具输出、用户反
- **关键机制**
  - Room 持久化（memories + memories_fts 虚拟表）
  - FTS5 全文搜索
  - 置信重要性评
  - 修剪策略（低重要+ 长期未使优先修剪

### Dream 记忆处理

- **功能描述**：后台整理和优化记忆
- **流程**
  1. DreamScheduler 定期触发（WorkManager
  2. DreamWorker 执行后台处理（构造参数：AppDatabase?、DreamEntityBridge?，均默认 null）
  3. DreamMemoryProcessor 处理流程
     - 扫描长期未整理的记忆
     - 合并相似记忆
     - 修剪低价值记
     - 生成新的洞察
  4. 记录 Dream 处理历史（dream_history 表）
- **关键机制**：WorkManager、增量处理、历史记

### 相册记忆

- **功能描述**：扫描设备相册，将照片元数据同步为图片记忆，支持关键词搜索
- **交互逻辑**
  1. GalleryMemoryScanner 通过 ContentResolver 查询 MediaStore.Images，获取最近 N 张照片的元数据（文件名、拍摄时间、相册名、MIME 类型）
  2. GalleryMemoryStore 将扫描结果写入工作区 IMAGE-MEMORY.md（Markdown 表格格式）
  3. GalleryMemoryTool 提供 Agent 可调用的工具接口：sync（同步最近 N 张照片到记忆）、search（按关键词搜索图片记忆）
  4. 搜索时从 IMAGE-MEMORY.md 中按关键词过滤匹配行，解析为 ImageEntry 返回
- **输入规则**：sync 操作的 limit 参数（默认 50）、search 操作的 keyword 参数
- **输出结果**：同步结果（已同步数量）、搜索结果（匹配的图片条目列表）

### 用户画像进化

- **功能描述**：从 Second Brain 记忆中提取用户行为模式，自动进化用户画像
- **交互逻辑**
  1. UserProfileManager 读写工作区 USER-PROFILE.md，维护用户画像数据（姓名、语言、回复风格、任务习惯、常见需求、偏好话题、近7天焦点、相册摘要、任务记忆摘要）
  2. evolveFromMemories 从记忆列表中提取 PREFERENCE 类型记忆作为任务习惯，按摘要频率排序提取偏好话题
  3. MemoryEvolutionTool 提供 Agent 可调用的工具接口：evolve（从记忆中进化用户画像）、show（查看当前用户画像）
  4. evolve 操作调用 MemoryRepository.searchHybrid 获取记忆，调用 UserProfileManager.evolveFromMemories 更新画像
- **输入规则**：action 参数（evolve/show）
- **输出结果**：evolve 返回进化结果摘要，show 返回当前画像信息

### 记忆检索

- **功能描述**：多策略记忆检
- **检索策*
  1. **KeywordRetriever**：关键词匹配
  2. **SymbolicRetriever**：符号检
  3. **ReflectionRetriever**：反思检
  4. **RetrievalPlanner**：检索规划（选择最佳检索策略组合）
  5. **QueryIntentClassifier**：查询意图分
- **关键机制**：混合检索、意图分

### 上下文压

- **功能描述**：当对话上下文超Token 限制时自动压
- **流程**
  1. ContextManager 检Token 使用
  2. CompactionTrigger 触发压缩条件
  3. IterativeSummaryMerger 迭代合并摘要
  4. ~~ToolOutputTrimmer 裁剪工具输出~~（已删除，2026-05-20）
  5. ~~TurnBoundaryGuard 保证回合边界完整性~~（已删除，2026-05-20）
- **关键机制**：Token 估算、渐进式压缩

### 知识图谱

- **功能描述**：实关系知识图谱存储和查
- **流程**
  1. EntityExtractor 从对话中提取实体和关
  2. KnowledgeGraphStore 存储Room（graph_entities + graph_relations 表）
  3. DreamEntityBridge 连接 Dream 处理和知识图
  4. 支持实体查询和关系遍
- **关键机制**：Room 持久化、实体模式匹

## 通信与网

### 频道管理

- **功能描述**：管理外部通信频道连接
- **流程**
  1. ChannelManager 管理频道注册和消息路由
  2. ChannelHealthService 监控频道健康状态
  3. 支持的频道：微信、钉钉、飞书、QQ、Telegram、Discord
  4. QrAuthManager 管理二维码扫码认证流程（Idle → Loading → QrReady → Scanned → Success/Error），自行轮询 QrAuthProvider.pollStatus()，5 分钟超时上限
  5. QrAuthProvider 接口统一抽象四平台认证：WeixinQrAuthProvider / FeishuQrAuthProvider / DingtalkQrAuthProvider / WecomQrAuthProvider
  6. AppLauncher 实现三级降级跳转扫码：扫一扫 Scheme → 打开 App → 包名启动
  7. QrImageSaver 保存二维码图片到相册 Pictures/HippyAgent 子目录（MediaStore + IS_PENDING）
  8. QrCodeGenerator 统一生成二维码 Base64 图片（ZXing + Bitmap.recycle + .use{}）
  9. 所有 Provider 共享 Koin 注入的 OkHttpClient 实例
- **关键机制**：频道适配器模式、健康检查、QrAuthProvider 策略模式、凭证 SecureStorage 加密存储

### 网络监控

- **功能描述**：监控网络连接状
- **流程**
  1. NetworkMonitor 监听网络变化
  2. 网络恢复时触OfflineMessageQueue 重发
  3. 离线时消息入队等
- **关键机制**：ConnectivityManager、Flow 状态流

### 离线消息队列

- **功能描述**：网络离线时缓存待发送消
- **流程**
  1. 网络不可用时消息入队
  2. 网络恢复时按序重
  3. 重发失败时重新入
- **关键机制**：SharedPreferences 持久化、自动重

### MCP 服务

- **功能描述**：实MCP 协议服务器，供外部工具连
- **流程**
  1. MCPServer 启动 HTTP 服务器（NanoHTTPD
  2. MCPServerTransport 处理 MCP 协议传输
  3. MCPToolRegistrar 将内部工具暴露为 MCP 工具
  4. MCPClientManager 管理外部 MCP 客户端连
- **关键机制**：NanoHTTPD、SSE、JSON-RPC

## 存储与数

### 存储管理

- **功能描述**：管理应用文件存
- **流程**
  1. StorageManager 管理工作目录
  2. WorkspaceManager 管理 Agent 工作
  3. SecureStorage 加密存储敏感数据（EncryptedSharedPreferences
  4. ConfigStorage 存储配置数据
- **关键机制**：SAF（Storage Access Framework）、加密存

### Agent 仓库

- **功能描述**：Agent Profile CRUD 仓库
- **流程**
  1. 管理 agents/*.json 配置文件
  2. 管理 workspaces/ 工作区目
  3. 支持 SAF 同步
  4. PROFILE.md 名字双向同步
  5. 核心文件管理（CRUD、排序、启禁用）
  6. getAgentById(agentId) 直接从内存缓存获取 AgentProfile，供 InsightsEngine 等模块查询智能体名称
  6. 默认智能体内置头像：createDefaultAgentIfNeeded 时将 res/drawable/ic_hippy_avatar.png 写入 files/avatars/hippy.png，设置 avatarUrl 为该文件路径；已有默认智能体但无头像时自动补
- **关键机制**：JSON 文件持久化、SAF 同步

### 数据迁移

- **功能描述**：应用版本升级时的数据迁
- **流程**
  1. MigrationManager 注册迁移规则
  2. 检测版本变
  3. 执行迁移脚本
  4. Room Migration 处理数据库结构变更（v1-v17）
- **关键机制**：版本检测、迁移注

### 备份恢复

- **功能描述**：应用数据备份和恢复
- **流程**
  1. BackupManager 创建备份（ZIP 格式
  2. 选择备份范围（会配置/记忆/全部
  3. 恢复时解压并合并数据
- **关键机制**：ZIP 压缩、数据合并策

## 手机控制

### 无障碍服

- **功能描述**：通过 Android 无障碍服务控制手
- **流程**
  1. PhoneControlAccessibilityService 注册为无障碍服务
  2. AccessibilityController 管理服务连接
  3. NodeOperator 执行节点操作（点击、输入、滚动）
  4. NodeTreeProvider 提供节点树信
  5. ScreenObserver 监听屏幕变化
- **关键机制**：AccessibilityService、节点树遍历

### 手机自动

- **功能描述**：VLM 驱动的手机自动化操作
- **流程**
  1. 截取屏幕截图
  2. VLM（视觉语言模型）分析屏幕内
  3. DualTrackDecisionEngine 选择操作路径（无障碍vs VLM
  4. ActionExecutor 执行操作
  5. ActionApprover 审批高风险操
  6. 循环直到任务完成
- **关键机制**：VLM 视觉分析、双轨决策、审批机

### 审批覆盖

- **功能描述**：高风险操作的悬浮窗审批界面，可在其他应用上方显
- **流程**
  1. AccessibilityController.interact() 需要审批时，自动启ApprovalOverlayService（前台服务）
  2. ApprovalOverlayService 显示悬浮窗（TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL
  3. 展示操作详情和风险评
  4. 用户审批/拒绝
  5. PermissionActionReceiver 接收审批结果
- **关键机制**：前台服务（foregroundServiceType="specialUse"）、SYSTEM_ALERT_WINDOW、FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL、常驻通知

### 智能感知

- **功能描述**：增强屏幕理解的感知能力
- **流程**
  1. SmartPerceptionLayer 整合多源感知数据
  2. IncrementalSensor 增量检测屏幕变
  3. ScreenFrameSampler 采样屏幕
  4. VisionFrameBuffer 缓存视觉
  5. AdUiGuard 检测和过滤广告 UI
  6. AppSpecializationManager 应用特化处理
- **关键机制**：增量感知、帧采样、广告过

### 相机感知

- **功能描述**：通过 CameraFramePusher 实时捕获相机画面，推送到 VisionFrameBuffer，供 VLM 理解。实现「看相机画面+听语音→判断这是什么→自动执行后续操作」的闭环
- **交互逻辑**
  1. CameraFramePusher 管理 Camera2 API 生命周期（start/stop），将相机帧推送到 VisionFrameBuffer
  2. pushFrame(bitmap) 将 Bitmap 帧推入缓冲区，latestFrame() 获取最新帧
  3. CameraPreviewOverlay Composable 提供相机实时预览 UI（120x160dp TextureView），isActive 控制显示/隐藏，onFrameCaptured 回调帧数据
  4. 实际相机启动在 CameraPreviewOverlay 中通过 CameraX 实现，CameraFramePusher 预留 Camera2 接口
- **输入规则**：isActive 布尔值控制预览开关，cameraId 指定摄像头（默认"0"后置）
- **输出结果**：相机帧推送到 VisionFrameBuffer，UI 显示实时预览

### YOLO UI 检

- **功能描述**：基YOLO 模型UI 元素检
- **流程**
  1. UiYoloDetectionEngine 加载 ONNX YOLO 模型
  2. 检测屏幕上UI 元素（按钮、输入框等）
  3. UiDetectionFusionEngine 融合无障碍树YOLO 检测结
  4. 输出 FusedElement 列表
- **关键机制**：ONNX Runtime、多源融

### 本地语音视觉中心

- **功能描述**：本地语音识别和视觉理解的统一入口
- **流程**
  1. LocalVoiceVisionHub 管理本地 STT/VLM 模型
  2. Moonshine SDK 离线语音转文
  3. 本地 VLM 视觉理解
- **关键机制**：Moonshine SDK（API 35+，运行时兼容检查）、ONNX Runtime

### 离线语音转文字 (三轨制 STT)

- **功能描述**：三引擎语音转文字系统，Moonshine 流式优先 + LiteRT-LM Audio 批处理降级 + Android 内置 SpeechRecognizer 通用回退，零额外模型/依赖
- **交互逻辑**
  1. STTService 作为统一入口，内部通过 SttRouter 路由决策选择引擎
  2. SttRouter 决策优先级：API 35+ && Moonshine 已下载 → Moonshine（流式实时）；引擎已加载 && 模型支持 AUDIO → LiteRT-LM Audio（批处理）；SpeechRecognizer.isRecognitionAvailable → AndroidBuiltin（系统语音，几乎所有设备可用）；都不满足 → NONE（不可用）
  3. Moonshine 路径：反射调用 MicTranscriber，流式实时转写，边说边出字
  4. LiteRT-LM Audio 路径：LiteRTLMTranscriber 录音（AudioRecord 16kHz mono 16bit）→ 收集 PCM → Content.AudioBytes + STT prompt → Conversation.sendMessage → 批处理转写
  5. AndroidBuiltin 模式交互：点击麦克风按钮直接启动 SpeechRecognizer，流式返回中间结果，停止后返回最终文字结果；无需下载任何模型
  6. LiteRT-LM 模式交互：按住麦克风显示"正在聆听..."占位 → 松开后显示"正在识别..." → 最终结果一次性返回
  7. 录音限制：单次最长 30s（960KB PCM 上限），超时自动停止并触发转写
  8. 引擎互斥：OnDeviceModelManager.engineMutex 保护 transcribeAudio()，防止转写期间 unloadEngine() 导致 native 崩溃
  9. VoiceExtensionManager/STTService 注册到 Koin DI，ChatScreen 通过 koinInject() 获取
  10. ChatInputBar 显示当前 STT 引擎标签（"系统语音"在 AndroidBuiltin 时显示）
  11. STT 可用性响应式更新：SttRouter.isAvailableFlow（combine voiceManager.isSttAvailable + onDeviceModelManager.currentEngineModelId）通过 StateFlow 驱动 Compose 重组，ChatScreen 使用 collectAsStateWithLifecycle 观察
- **输入规则**：SttCallback（onPartialResult/onFinalResult/onError）、麦克风权限
- **输出结果**：SttResult（text/isFinal/confidence）、引擎标签（Moonshine/端侧/系统语音/不可用）
- **关键机制**
  - **三轨路由**：SttRouter 根据 Moonshine 可用性 + 端侧引擎状态 + Android SpeechRecognizer 自动选择，调用方无感知
  - **Android 内置回退**：AndroidBuiltinTranscriber 封装 SpeechRecognizer API，Android 所有版本可用，无需下载模型或额外权限（仅需 RECORD_AUDIO）
  - **批处理降级**：LiteRT-LM Audio 模式下录音→转写两阶段，非流式但零额外成本
  - **engineMutex 互斥**：transcribeAudio() 持锁期间 unloadEngine() 阻塞，避免 native 崩溃
  - **录音安全**：30s 超时 + 960KB 缓冲区上限 + AudioRecord 异常安全释放
  - **DI 单例**：VoiceExtensionManager/STTService/SttRouter/AndroidBuiltinTranscriber/LiteRTLMTranscriber 均为 Koin 单例

### 行为录制与技能生成

- **功能描述**：通过无障碍服务录制用户操作行为，解析 Deeplink，自动生成可复用的技能文件
- **交互逻辑**
  1. BehaviorRecorder 通过无障碍服务监听 AccessibilityEvent，录制用户操作事件（eventType/packageName/className/text/contentDescription/timestampMs）
  2. 录制过程中支持 bookmarkCurrentPage，通过 dumpsys activity top 获取当前页面 Intent 信息，DeeplinkParser 解析为 CapturedIntent（action/dataUri/component/extras）
  3. DeeplinkParser 从 dumpsys 输出中提取 Activity Section，使用正则匹配 Action/Data/Component/Extras 字段，并支持 buildAmCommand 生成可执行的 am start 命令
  4. DeeplinkBookmarkStore 持久化存储 Deeplink 书签（SharedPreferences + JSON 序列化），支持 add/remove/getAll 操作
  5. BehaviorSkillWriter 将录制事件转化为技能文件（SKILL.md），过滤有文本/描述的事件，去重后取前 20 步，生成包含触发条件、操作步骤、包名的技能定义
- **输入规则**：AccessibilityEvent 事件流、dumpsys 输出、应用别名
- **输出结果**：RecordedEvent 列表、CapturedIntent、am start 命令、SKILL.md 技能文件
- **关键机制**
  - **DeeplinkParser**：正则预编译（ACTION_REGEX/DATA_REGEX/COMPONENT_REGEX/EXTRA_REGEX 为 companion object 顶层 val），从 dumpsys activity top 输出提取 Intent 信息
  - **BehaviorRecorder**：StateFlow 驱动 UI 状态（isRecording/eventCount/currentPageTitle），单例 object 管理录制生命周期
  - **DeeplinkBookmarkStore**：SharedPreferences + kotlinx.serialization JSON 持久化，initialize 延迟初始化
  - **BehaviorSkillWriter**：事件去重（distinctBy className+text）、步骤上限 20、SKILL.md 标准格式输出

## Linux 容器

### PRoot 引擎

- **功能描述**：在 Android 上通过 PRoot 运行 Linux 环境
- **流程**
  1. PRootEngine 初始PRoot 环境
  2. PRootBridge 通过 JNI 调用 PRoot 原生
  3. LinuxProcess 管理 Linux 进程
  4. LinuxManager 提供高层管理接口
- **关键机制**：PRoot JNI、进程管理、rootfs 管理

### Linux 环境

- **功能描述**：Linux 容器环境配置
- **流程**
  1. LinuxDirs 定义目录结构
  2. LinuxConfig 管理配置
  3. LinuxEnv 管理环境变量
  4. ContainerConfig 容器配置
  5. LinuxMigrationManager 管理环境迁移
- **关键机制**：目录约定、配置持久化

### Linux 安全

- **功能描述**：Linux 容器安全控制
- **流程**
  1. ResourceLimiter 限制资源使用（CPU、内存、磁盘）
  2. CommandSandbox 沙箱化命令执
- **关键机制**：资源配额、命令白名单

### Linux 保活服务

- **功能描述**：保Linux 容器持续运行
- **流程**
  1. LinuxKeepAliveService 前台服务
  2. 显示常驻通知
  3. 定期检查容器状
  4. 容器异常时自动重
- **关键机制**：Foreground Service、Watchdog

## 安全系统

### 权限管理

- **功能描述**：管Android 权限请求和状
- **流程**
  1. PermissionManager 检查权限状
  2. 请求必要权限
  3. 记录权限授予状
  4. 自定义工具权限管理：approveCustomToolPermission（一持久）、isCustomToolApproved、isCustomToolPermanentlyApproved、denyCustomToolPermanently、isCustomToolPermanentlyDenied，使DataStore 持久
- **关键机制**：CopyOnWriteArrayList 线程安全临时授权、ConcurrentHashMap 正则缓存（matchesPattern 逐字符转义 `.()[]{}+^$|\`）、ConcurrentHashMap.newKeySet 线程安全 onceApprovedCustomPerms

### 工具安全守卫

- **功能描述**：工具执行前的安全检
- **流程**
  1. ToolGuardian 检查工具调用安全性（8 项检+ HIGH 起审
  2. ~~ToolGuard 组合多个 Guardian 的检查结果~~（已废弃并删除，ToolGuardian 内部处理
  3. Guardian 列表（由 ToolGuardian 内部管理）：
     - **AndroidPermissionGuardian**：检Android 权限
     - **StorageAccessGuardian**：检查存储访问权
     - **NetworkPolicyGuardian**：检查网络策
     - **AccessibilityGuardian**：检查无障碍权限
  4. 根据安全级别（宽标准/严格）决定是否自动执行或需审批
  5. PermissionViewModel 通过构造函数注PermissionManager（不再使GlobalContext
  6. 文件操作工具（read_file/write_file/edit_file/append_file/delete_file等）仅做路径可访问性检查，跳过危险命令模式检查和 Shell 逃逸检测
  7. 命令执行工具（execute_bash/execute_python等）仍做完整安全检查
- **关键机制**：Guardian 链、安全级别、审批流程、构造函数注

### 认证管理

- **功能描述**：管API Key 和认证信
- **流程**
  1. AuthManager 管理认证状
  2. SecretMigrationManager 处理密钥迁移
  3. SecureStorage 加密存储密钥
- **关键机制**：EncryptedSharedPreferences、密钥迁移、revokedJtis 上限淘汰0000

## 通知系统

### 通知服务

- **功能描述**：管理应用通知，支持前台会话通知抑制
- **流程**：
  1. HippyAgentNotificationService 发送各类通知
  2. BadgeManager 管理未读角标
  3. 通知类型：消息通知、权限请求通知、任务完成通知、工具审批通知、AGENT_MESSAGE 通知
  4. ForegroundSessionTracker 单例跟踪当前前台会话 ID，ChatScreen/GroupChatScreen 进入时设置、离开时清除
  5. sendAgentMessageNotification 发送前检查 ForegroundSessionTracker.isForeground(sessionId)，前台会话跳过系统通知
  6. 工具审批通知（sendToolApprovalNotification）：应用后台时自动发送，含4个操作按钮（允许本次/始终允许/拒绝/不再允许），通知标题含严重程度标签（🔴严重/🟠高/🟡中），按钮 PendingIntent 指向 ToolApprovalReceiver
  7. AGENT_MESSAGE 通知类型：使用 IMPORTANCE_HIGH channel 实现 heads-up 浮窗通知；当智能体发送消息且用户不在对应会话时触发浮窗通知；点击通知跳转到对应会话
  8. 渠道配置升级（agent_message_v2）：显式设置 enableVibration + vibrationPattern + setSound(TYPE_NOTIFICATION) + lockscreenVisibility(VISIBILITY_PRIVATE) + setShowBadge(true)，确保 heads-up 横幅弹窗在所有 OEM 上正常触发
  9. 通知样式升级：使用 MessagingStyle + Person API 替代 setContentTitle/ContentText，添加 setCategory(CATEGORY_MESSAGE) + setDefaults(DEFAULT_ALL)，系统将通知识别为消息类通知，优先展示 heads-up 横幅
- **关键机制**：NotificationChannel（v2 渠道绕过缓存）、MessagingStyle、Person、CATEGORY_MESSAGE、ForegroundSessionTracker

### 通知弹窗服务

- **功能描述**：智能体发送通知时，在屏幕顶部弹出悬浮窗显示通知内容，可在其他应用上方显
- **流程**
  1. SendNotificationTool 发system 类型通知时，调用 NotificationOverlayService.show()
  2. NotificationOverlayService 检SYSTEM_ALERT_WINDOW 权限，无权限则跳过弹
  3. 有权限时启动前台服务，显TYPE_APPLICATION_OVERLAY 悬浮
  4. 悬浮窗展示通知标题、内容和一确定"关闭按钮
  5. 用户点击"确定"30 秒自动超时后，移除悬浮窗并停止服
- **关键机制**：前台服务（foregroundServiceType="specialUse"）、SYSTEM_ALERT_WINDOW 权限检查、自动超dismiss

## 引导与初始化

### 应用初始

- **功能描述**：应用启动时的三阶段初始
- **流程**
  1. **阶段 1（同步）**：启Koin DI 容器
  2. **阶段 2（有序关键）**
     - AgentRepository & 默认 Agent 创建（含内置头像 `hippy.png` 写入 `files/avatars/hippy.png`，avatarUrl 指向该路径）
     - PermissionManager 初始
     - ModelProviderStore 默认供应
     - Second Brain 记忆数据
     - 工具注册（内+ Linux
     - LinuxKeepAliveService 启动
     - PawJobWorker 启动
  3. **阶段 3（非关键后台*
     - 权限请求通知监听
     - 工具审批请求通知监听（观察 ToolApprovalManager.pendingApprovals，后台时发送审批通知）
     - 内置技能安
     - 网络恢复监听 + 离线消息重发
     - Dream Memory 处理（延5 秒）
- **关键机制**：有序初始化、CoroutineScope + SupervisorJob

### 开机启

- **功能描述**：设备重启后自动恢复 Agent 服务
- **流程**
  1. BootReceiver 接收 BOOT_COMPLETED 广播
  2. BootSetupWorker（WorkManager）执行初始化
  3. 恢复心跳调度
  4. 恢复 Cron 任务
- **关键机制**：BroadcastReceiver、WorkManager

---

# 死代码检测报

## 高置信度死代

以下文件在项目中未被任何其他文件引用，属于确认的死代码：

| 文件 | | 说明 |
|------|-----|------|
| ~~TeamLeaderRole~~ | 已删除，未被任何文件引用 | 2026-05-20 |
| `core/agent/session/SessionStore.kt` | `LocalSessionStore` | 定义但未使用。DI 注册的是 RoomSessionStore，LocalSessionStore 可作为测试替身或内存回退方案保留 |

**已激活（原死代码，现已注册到 Koin DI 并集成）*

| 文件 | | 激活日| 集成方式 |
|------|-----|----------|----------|
| `core/agent/AgentRegistry.kt` | `AgentRegistry` | 2026-05-19 | Koin DI 单例，AgentFactory 创建/移除 Agent 时自动注注销 |
| `core/agent/AgentSessionManager.kt` | `AgentSessionManager`, `AgentSession` | 2026-05-19 | Koin DI 单例，Agent.processMessage 时自动创更新会话 |
| `core/agent/AgentMessageRouter.kt` | `AgentMessageRouter`, `AgentMessage` | 2026-05-19 | Koin DI 单例，提Agent 间直接消息路由能|
| `core/agent/middleware/LoopDetectionMiddleware.kt` | `LoopDetectionMiddleware` | 2026-05-19 | 已添加到 AgentFactory 中间件链（DanglingToolCall Clarification Memory LoopDetection|
| `core/agent/group/TeamTaskHandoff.kt` | `TeamTaskHandoff` | 2026-05-19 | Koin DI 单例，集成到 DagTaskOrchestrator，DAG 任务完成时自动写入结果文|
| `core/agent/group/ProcessingMarker.kt` | `ProcessingMarker`, `ProcessingMarkerData` | 2026-05-19 | Koin DI 单例，集成到 DagTaskOrchestrator GroupChatOrchestrator |
| `core/agent/group/GroupCollaborationProtocol.kt` | `GroupCollaborationProtocol`, `MentionExchange` | 2026-05-19 | Koin DI 单例，集成到 GroupChatOrchestrator，检Ping-Pong 对话自动终止 |
| `core/agent/proactive/ProactiveTrigger.kt` | `ProactiveTrigger` | 2026-05-19 | Koin DI 单例，提Agent 主动触发能力（空闲检+ 记忆关联 + 频道推送） |

## 重复/冗余代码（已清理

| 问题 | 处理方式 | 清理日期 |
|------|----------|----------|
| ~~SkillManager 重复体系~~ | 已删除旧`com.lin.hippyagent.skills` 包（SkillManager.kt + SkillManagerImpl.kt），零引| 2026-05-19 |
| ~~ZipHelper 重复~~ | 已删`core.backup.ZipHelper`（未使用），保留 `core.plugin.ZipHelper`（含 Zip Bomb 防护| 2026-05-19 |
| ~~ContextWindowOptimizer 死代码~~ | 已删`core/agent/collaboration/ContextWindowOptimizer.kt`，上下文过滤改为 AgentGroup.filterGroupMessagesForAgent + agent.contextMessageFilter 双层机制 | 2026-05-20 |
| ~~BackupRestoreScreen 重复~~ | 已删`ui.settings.general.BackupRestoreScreen`（简化版），保留 `ui.settings.BackupRestoreScreen`（BackupManager 驱动，支持列创建/恢复/删除| 2026-05-19 |

| ~~GroupLifecycleManager~~ | 已删除 core/agent/group/GroupLifecycleManager.kt，未被引用 | 2026-05-20 |
| ~~GroupChatOrchestrator~~ | 已删除 core/agent/collaboration/GroupChatOrchestrator.kt，未被引用 | 2026-05-20 |
| ~~AgentConfigSection.kt.bak~~ | 已删除备份文件 | 2026-05-20 |
| ~~ToolOutputTrimmer~~ | 已删除 core/agent/ToolOutputTrimmer.kt，上下文压缩改由 Agent.kt 内联处理 | 2026-05-20 |
| ~~TurnBoundaryGuard~~ | 已删除 core/agent/TurnBoundaryGuard.kt，轮次边界保护改由 Agent.kt 内联处理 | 2026-05-20 |
## 低置信度可能死代

| 文件/| 说明 |
|---------|------|
| core/taskqueue.* | ~~已清理 2026-05-20~~ |
| core/taskflow.* | ~~已清理 2026-05-20~~ |
| core/agent/group/DagTaskOrchestrator.kt | ~~已清理 2026-05-20~~ |
| core/agent/group/TaskStateMachine.kt | ~~已清理 2026-05-20~~ |

---

# 新增功能（2026-05-25）

## 界面 > 聊天与会话

### 多会话运行状态显示

- **功能描述**：在会话抽屉中显示非 IDLE 会话的运行状态（思考中/执行中），让用户直观感知哪些会话正在活跃运行
- **交互逻辑**
  1. ChatViewModel 新增 sessionStatuses: StateFlow<Map<String, AgentStatus>>，观察所有非 IDLE 会话的 Agent 状态
  2. ChatSessionDrawer 渲染每个会话项时，从 sessionStatuses 读取该会话的 Agent 状态
  3. 非 IDLE 状态时显示 PulsingStatusDot 脉冲动画组件，颜色区分：THINKING→蓝色脉冲，EXECUTING→橙色脉冲
  4. 状态文字显示在会话名称旁："思考中"/"执行中"
- **输入规则**：AgentStatus 状态流
- **输出结果**：会话抽屉中活跃会话的实时状态指示

### 轮次耗尽角标

- **功能描述**：当 Agent 迭代轮次耗尽时，在聊天界面底部输入栏上方显示警告角标，提示用户任务未能完全完成
- **交互逻辑**
  1. ChatUiState 新增 iterationExhausted 字段，默认 false
  2. ChatViewModel 流式收集 StreamChunk.Content 时，检测内容包含"迭代轮次已耗尽"关键字，设置 iterationExhausted = true
  3. 用户发送新消息时重置 iterationExhausted = false
  4. ChatScreen bottomBar 中，当 iterationExhausted 为 true 时，显示红色 Warning 图标 + "轮次已耗尽" 文字
- **输入规则**：Agent 流式输出中的耗尽通知
- **输出结果**：底部输入栏上方的红色警告角标

### 熄屏状态卡死恢复

- **功能描述**：当用户熄屏后重新进入聊天界面时，自动检测并恢复卡死的 Agent 状态
- **交互逻辑**
  1. ChatViewModel.initSession 初始化会话时，检查 Agent 当前会话状态
  2. 如果状态为 THINKING 或 EXECUTING_TOOL（说明上次任务因进程被杀等原因未正常结束），调用 agent.stopSession 强制重置
  3. 重置后状态变为 STOPPED，用户可正常发送新消息
- **输入规则**：Agent 会话状态
- **输出结果**：卡死状态自动恢复为 STOPPED，不再阻塞新消息

### 麦克风按钮文字移除

- **功能描述**：移除 ChatInputBar 中 STT 引擎标签文字显示，仅保留录音时长
- **交互逻辑**
  1. ChatInputBar 中移除 STT 引擎标签（"系统语音"/"端侧"/"Moonshine"）的显示
  2. 保留录音时长显示（"正在聆听..."/"正在识别..."状态文字）
  3. 麦克风按钮图标保持不变
- **输入规则**：无
- **输出结果**：麦克风按钮区域更简洁，仅显示图标和录音时长

## 界面 > 模型与连接

### 端侧模型 Huggingface 浏览

- **已废弃**（2026-05-26）：随端侧模型管理 UI 一并移除
- **功能描述**：在端侧模型管理界面添加 Huggingface 模型浏览 Tab，支持在线搜索和浏览 Huggingface 上的端侧模型
- **交互逻辑**
  1. OnDeviceModelScreen 添加 TabRow，包含"模型管理"和"Huggingface | 模型管理"两个 Tab
  2. HuggingFaceTab：顶部搜索栏 + 模型列表卡片
  3. 搜索栏输入关键词，300ms 防抖后调用 HuggingFaceSearchApi 搜索
  4. HuggingFaceSearchApi 调用 HF /api/models 接口，筛选支持 LiteRT-LM 格式的模型
  5. 模型卡片显示：模型名称、描述、下载量、标签；添加下载按钮，支持直接下载 HF 模型
  6. 点击模型卡片可查看详情或触发下载
  7. OnDeviceModelManager 新增 customModels 内存映射，支持动态注册 HF 模型配置
- **输入规则**：搜索关键词、Tab 切换
- **输出结果**：Huggingface 模型搜索结果列表

## 系统 > 存储与数据

### 会话未读红点统一数据源

- **功能描述**：统一会话未读计数的数据源，消除双 Flow 时序错位导致的红点闪烁问题
- **交互逻辑**
  1. UnreadSummary 新增 sessionUnreadCounts: Map<String, Int> 字段，作为会话未读数的唯一数据源
  2. 渲染层（ConversationListScreen、ChatSessionDrawer、ChatViewModel）使用 effectiveUnreadCount 替代 session.unreadCount
  3. effectiveUnreadCount 从 UnreadSummary.sessionUnreadCounts 读取，确保所有 UI 组件读取同一数据源
  4. 消除原有双 Flow（SessionStore + UnreadSummary）时序错位导致的红点闪烁
- **输入规则**：UnreadSummary 更新事件
- **输出结果**：会话未读红点显示一致，无闪烁

## 系统 > 运行时引擎

### 防死循环机制增强

- **功能描述**：增强 LoopDetector 的死循环检测策略，连续 WARN 时渐进升级而非直接打断
- **交互逻辑**
  1. LoopDetector 新增连续 warn 计数器（consecutiveWarnCount），记录连续 WARN 级别检测的次数
  2. 连续 3 次 WARN 自动升级为 HARD 级别，触发强制中断
  3. WARN 级别时注入系统提示词（"检测到可能的重复模式，请尝试不同的方法"），而非直接打断 Agent 执行
  4. 非 WARN 检测结果重置 consecutiveWarnCount 为 0
  5. HARD 级别行为不变，直接中断循环
- **输入规则**：工具调用模式检测结果
- **输出结果**：WARN 时注入提示词引导 Agent 自纠正，3 次连续 WARN 升级为 HARD 强制中断

### 启动环境检测

- **功能描述**：应用启动时后台静默检测运行环境依赖
- **交互逻辑**
  1. 应用启动时后台静默检测 Node.js/npx/Python 是否已安装
  2. 未安装则按顺序静默安装（LinuxManager.silentEnsureEnvironment）
  3. 不阻塞 UI，完全后台运行
- **输入规则**：无
- **输出结果**：运行环境依赖自动就绪

## 系统 > 工具系统

### Himalaya 邮件技能集成

- **功能描述**：集成 Himalaya 命令行邮件客户端，支持 Agent 通过 himalaya_email 工具收发邮件
- **交互逻辑**
  1. HimalayaSkill 改用 LinuxManager.exec() 执行 himalaya 命令，替代直接 Shell 调用
  2. 新增 HimalayaTool（工具名 himalaya_email），支持邮件收发操作：list（列出邮件）、read（读取邮件）、send（发送邮件）、search（搜索邮件）
  3. SkillLifecycleManager 在技能激活时注册 himalaya 工具到 ToolRegistry
  4. EnvCheckScreen 添加 himalaya 环境检测项，检查 himalaya 是否已安装及配置是否正确
  5. BuiltinSkillRegistry 添加 Himalaya 技能定义，包含 customBody（技能描述）和 tools（himalaya_email 工具定义）
- **输入规则**：himalaya_email 工具参数（action/subject/to/body/query 等）
- **输出结果**：邮件操作结果（邮件列表/邮件内容/发送状态/搜索结果）

### create_plan subtasks schema 修复

- **功能描述**：修复 PlanTools 的 subtasks 参数缺少数组元素结构定义的问题，确保 LLM 能正确生成结构化的子任务列表
- **交互逻辑**
  1. ToolParameter 新增 items 字段（ToolParameter? 类型），支持数组元素的嵌套结构定义
  2. PlanTools 的 subtasks 参数添加完整 items schema，定义数组元素包含 title（string）和 description（string）字段
  3. items 字段序列化时输出 JSON Schema 的 items 属性，LLM 据此生成符合结构的子任务数组
- **输入规则**：ToolParameter 定义更新
- **输出结果**：LLM 生成的 subtasks 符合预期结构，包含 title 和 description 字段

## 其他修复

### web_fetch 工具默认可见

- **功能描述**：web_fetch 工具从 onDemand 改为默认可见，减少路由开销
- **交互逻辑**
  1. ToolInitializer 注册 web_fetch 时设置 onDemand=false
  2. web_fetch 不再需要通过 tool_search 发现，直接出现在 LLM 工具列表中
- **输入规则**：无
- **输出结果**：web_fetch 始终对 Agent 可见

### load_skill 全局启用层 fallback

- **功能描述**：load_skill 工具在技能加载时增加全局启用层的回退逻辑
- **交互逻辑**
  1. LoadSkillTool 加载技能时，先检查 Agent 级别启用状态
  2. Agent 级别未启用时，回退检查全局启用层
  3. 全局启用层已启用则允许加载，否则返回错误提示
- **输入规则**：技能 ID
- **输出结果**：技能加载成功或错误提示

### 非 Plan 模式计划显示保留

- **功能描述**：非 Plan 模式下仍保留已有计划的显示，避免计划面板空白
- **交互逻辑**
  1. PlanPanel 在非 Plan 模式下仍渲染已有计划内容
  2. 非 Plan 模式下隐藏计划操作按钮（创建/更新/删除），仅展示只读视图
- **输入规则**：Plan 模式开关状态
- **输出结果**：非 Plan 模式下计划面板显示只读计划内容

### 停止按钮移至 TopBar

- **功能描述**：将 Agent 运行中的停止按钮从输入栏移至 TopBar 区域
- **交互逻辑**
  1. ChatInputBar 中移除停止按钮
  2. TopBar 右侧添加停止按钮，Agent 运行中显示，IDLE 时隐藏
  3. 停止按钮点击行为不变，取消当前 Agent 执行
- **输入规则**：Agent 运行状态
- **输出结果**：停止按钮在 TopBar 中显示，输入栏更简洁

### 麦克风权限错误消息优化

- **功能描述**：优化麦克风权限被拒绝时的错误消息，提供更清晰的引导
- **交互逻辑**
  1. STT 引擎检测到 RECORD_AUDIO 权限被拒绝时，显示优化后的错误消息
  2. 错误消息包含：权限说明（"需要麦克风权限才能使用语音输入"）+ 操作引导（"请在系统设置中授予权限"）
  3. 替代原有的通用权限错误提示
- **输入规则**：权限检测结果
- **输出结果**：用户友好的权限错误提示和操作引导
