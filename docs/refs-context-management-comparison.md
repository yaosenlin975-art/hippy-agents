# refs 项目 vs hippy-agents 上下文管理 (注入/压缩) 对比与借鉴

> 调研范围: refs 项目 (DeepSeek-Reasonix / Codex / QwenPaw / gbrain / hermes-agent / openclaw) vs hippy-agents
> 文档生成时间: 2026-07-03

---

## 1. 本项目上下文管理现状

本项目是 Android Kotlin AI Agent, 上下文管理核心文件:

- `app\src\main\kotlin\com\lin\hippyagent\core\context\ContextManager.kt` (主上下文管理器)
- `app\src\main\kotlin\com\lin\hippyagent\core\context\CompactionTrigger.kt` (4 个触发器, 但未启用)
- `app\src\main\kotlin\com\lin\hippyagent\core\context\ConversationMemoryPolicy.kt` (对话轮次保留策略)
- `app\src\main\kotlin\com\lin\hippyagent\core\context\TokenEstimator.kt` (Token 估算器)
- `app\src\main\kotlin\com\lin\hippyagent\core\agent\Agent.kt` (Agent 主体, 含 buildPrompt/performLlmCompaction)
- `app\src\main\kotlin\com\lin\hippyagent\core\prompt\PromptBuilder.kt` (System Prompt 装配)
- `app\src\main\kotlin\com\lin\hippyagent\core\memory\compaction\IterativeSummaryMerger.kt` (规则降级摘要合并器)
- `app\src\main\kotlin\com\lin\hippyagent\core\model\routing\ContextManager.kt` (第二套, 未接入主流程)

### 1.1 注入机制

- `Agent.buildPrompt()` (Agent.kt:1865) 每轮 LLM 调用前装配 system prompt
- `PromptBuilder.buildSystemPrompt()` 按顺序拼接 **17 段** 动态内容: role / core_files / global_rules / working_directory / current_date / memory / skill_system / available-deferred-tools / app_aliases / clarification_system / citations / custom_instructions / common_memory / volunteered_memories / planContext / bootstrap_mode / critical_reminders
- 随后追加 systemPromptSuffix + escalationSuffix
- Mode 注入: `ModeSystemPromptInjector` 按 AgentMode (AUTO/NONE/CHAT/WORK) 提供后缀, 经 systemPromptSuffix 间接传入
- **问题**: 17 段动态内容拼进 system prompt, 含 memory/skill/plan 等高频变动段, **破坏 prefix cache 稳定性**

### 1.2 压缩机制

- 入口: `ContextManager.checkContext()` (ContextManager.kt:60-119)
- 算法: 计算 fixed tokens → 计算 compactThreshold(0.8)/reserveThreshold(0.1) → pruneToolResults (MD5 去重 + head 70%+tail 30% 截断 + 超长落盘) → 触发判定 → splitMessages (tool_use/tool_result 对齐保护 + 语义评分≥0.6 提升) → LLM 压缩
- LLM 压缩 3 级降级 (Agent.kt:2563-2675): summary model → 主模型 → IterativeSummaryMerger 规则合并 → 重新分割提升更多保留区
- 摘要注入: `SUMMARY_PREFIX` + existingSummary 作为 system 消息

### 1.3 已识别的 10 个限制

1. **compactionTriggers 未启用**: ContextManager 构造时 `compactionTriggers = emptyList()`, Agent.kt:388-391 未传入触发器。4 个触发器 (TokenCountTrigger / MessageCountTrigger / ContextRatioTrigger / DialogTurnPreservationTrigger) 全部定义但未生效, 压缩仅靠 80% token 阈值单路径触发
2. **partitionByDialogPolicy 未被使用**: ConversationMemoryPolicy 已实现但 checkContext 调用 splitMessages 而非它
3. **ContextWindowGuard 不阻断**: BLOCK 仅日志告警, 不抛异常
4. **Token 估算粗糙**: utf8_bytes/4, 中文 1 字符=3 字节≈0.75 token, 实际约 1.5-2x
5. **两套 ContextManager 并存**: core/context 与 core/model/routing 独立实现, 重复代码风险
6. **SummarizationMiddleware 形同虚设**: 仅设置 extra flag, 无处读取
7. **historyMaxLength=10000 配置未使用**: getMessages 始终取全部
8. **IterativeSummaryMerger.allocateBudget 未被调用**: dead code
9. **压缩触发时机单一**: 仅 buildPrompt (每轮对话开始前) 检查, **不在 repeat(maxIters) 工具迭代循环内检查**
10. **ModeSystemPromptInjector 未在 buildPrompt 直接调用**: 集成路径不直观

---

## 2. refs 各项目上下文管理详解

### 2.1 DeepSeek-Reasonix (Go, 编码 Agent)

**核心文件**: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\agent\compact.go`, `internal\control\input.go`

**Cache-First 哲学**: 系统提示前缀 (base prompt + tools + memory) 必须跨轮次字节稳定, 所有动态内容 (goal/memory/jobs/hook) 一律注入到 **user 消息开头**, 不碰 system prompt。

**4 阶段分层压缩** (compact.go:24-36, 85-146):
- 阈值: soft_compact_ratio=0.5, tool_result_snip_ratio=0.6, compact_ratio=0.8, compact_force_ratio=0.9, compact_target=0.5, tail_tokens=16384
- **Soft (0.5-0.6)**: 仅发 notice, 不动 prefix
- **Snip (0.6-0.8)**: SnipStaleToolResults, 给过期 tool 结果加 head/tail 标记, 保留配对
- **Prune (0.8+)**: PruneStaleToolResults, 替换为短占位符; 节省后低于阈值则跳过 summarize
- **Compact (仍超)**: summarize 折叠; 0.9+ force 即使不划算也 fold
- **关键约束**: snip/prune 绝不删除消息, 保持 tool_calls 与 tool result 配对; recent tail 不重写

**折叠算法**: planCompaction 定位 fold 区域 → partitionFold (保留小 user turn + compaction summary + policyKeep 的 error/blocked 消息) → foldEconomics (fold 区域 <400 tokens 跳过) → archiveMessages (写 ~/.reasonix/archive/<timestamp>.jsonl) → summarizeWithRetry → 摘要包在 <compaction-summary> 里

**结构化 summary 模板** (compact.go:55-80, 7 段):

```
## Standing facts & constraints
## Goal
## Decisions & rationale
## Files & code
## Commands & outcomes
## Errors & fixes
## Pending & next step
```

要求 terse, bullet points, 保留 identifiers/paths/numbers, 不发明。

**不丢事实**: pinnedPrefixLen 把 system + 第一条小 user turn + 所有先前 summary 全部 pin; 保留所有小 user turn (用户陈述的事实永不摘要掉); 大 user turn 仍可 fold 但原文已 archive。

**Token 估算**: tokPerChar 从上一轮真实 Usage.PromptTokens 反推, 限制 [0.05, 2] 区间; 无 usage 时 0.25 兜底; CJK 友好: estimateTextTokens 取 max(bytes/4, runes)。

**压缩卡死保护**: consecutiveCompacts >= 2 则 compactStuck=true, 暂停压缩并 warning。

**Memory v5 编译器**: `internal/memorycompiler/runtime.go`, 本地规则驱动, 执行轨迹更新策略评分与编译器突变, 模型不重写代码。

### 2.2 Codex (经 openclaw/hermes-agent 集成代码还原, refs 内无 Codex 源码)

**核心文件**: `D:\WorkSpaces\refs\openclaw\extensions\codex\src\app-server\context-engine-projection.ts`, `compact.ts`

**双层架构**: Codex 原生拥有线程历史与原生压缩; 外置 Context Engine **不能**修改 Codex 内部线程, 只能: 回合前 bootstrap+assemble → 投影为 Codex 兼容输入 (开发者指令 + 用户 prompt 文本) → 回合后 afterTurn/ingest + maintenance; 压缩分两层: context engine 的 compact() (主) + Codex 原生 thread/compact/start (次)。

**上下文投影** (context-engine-projection.ts):
- 投影输出: developerInstructionAddition (进开发者指令) + promptText (进 turn/start 用户输入) + assembledMessages
- 格式: `<conversation_context>` 块包裹历史, `Current user request:` 后跟用户原始 prompt
- Token 预算: DEFAULT_RENDERED_CONTEXT_CHARS=24000, APPROX_RENDERED_CHARS_PER_TOKEN=4, reserve + min floor 双约束 (reserveTokens 默认 20000, prompt 预算不得低于 min(8000, contextTokenBudget*0.5))
- 截断: truncateOlderContext 保留新上下文截断旧, `[truncated X chars from older context]` 标记
- **UTF-16 安全截尾** (sliceTailFromCodePointBoundary, 517-525): 检测低代理 (0xdc00-0xdfff) 跳过, 避免 U+FFFD 损坏

**Tool payload 脱敏**: elide 模式只保留 tool name + [omitted]; preserve 模式降为 shape + redactSensitiveFieldValue + 循环引用检测 (WeakSet)。

**Prompt-cache 稳定性**: 要求稳定消息顺序/角色标签, 无时间戳/随机 id/对象键顺序泄漏。

**看门狗矩阵**: turn_timeout(600s) + post_tool_quiet_timeout(90s/300s) + turnCompletionIdleTimeout(60s) + requestTimeout(60s); should_retire 标志强制下轮重建。

**事件投影器**: 把 Codex item 流翻译为 OpenAI 消息格式, 仅 item/completed 物化消息; 确定性 call_id (codex_<type>_<id> 或 sha256) 提升 cache 命中率。

### 2.3 QwenPaw (Python, 个人 AI 助手)

**三层记忆**:
- live working context (活动工作上下文)
- full verbatim history (完整逐字历史)
- distilled knowledge (蒸馏知识)
- 旧轮次被驱逐但可按需召回, "nothing is summarized away or lost"
- Scroll-based context management (独立文档页)
- Per-turn token usage popover, 非阻塞 flush 自适应节流

### 2.4 gbrain (TypeScript, AI Agent 大脑层)

**混合检索 + 知识图谱**:
- HNSW 向量 + BM25 关键词 + RRF + source-tier boost + intent-aware 改写 + ZeroEntropy reranker
- 三档搜索模式: conservative / balanced / tokenmax
- 自连线知识图谱: put_page 零 LLM 调用抽取实体引用写边, 多跳遍历
- Brain 层综合: 带引用与"未知缺口分析"的综合答案
- Dream cycle (夜间 cron): 去重人员页面、修复引用、评分 salience、发现矛盾、预备次日任务
- Schema packs (gbrain-base-v2 15 类), agent-authored schema

### 2.5 hermes-agent (Python, 自进化 AI Agent)

- **/compress** 压缩上下文, /usage, /insights [--days N]
- **Context Files**: 项目上下文塑造每次对话 (独立文档页)
- 持久记忆 + 用户画像, FTS5 会话搜索 + LLM 摘要做跨会话召回
- Honcho dialectic user modeling (辩证用户建模)
- 自主技能创建与使用中自改进

### 2.6 openclaw (TypeScript, 个人 AI 助手)

- **/compact [instructions]** 带指令的压缩 (用户可指定压缩重点)
- /usage off|tokens|full, /think <level>, /verbose, /trace
- 多 Agent 路由 (按 channel/account/peer 路由到隔离 agent, 各自 workspace + 会话)
- 沙箱分级 (agents.defaults.sandbox.mode: "non-main", Docker/SSH/OpenShell 后端)

---

## 3. 对比矩阵

| 维度 | hippy-agents | Reasonix | Codex | QwenPaw | gbrain | hermes-agent | openclaw |
|---|---|---|---|---|---|---|---|
| 注入位置 | system prompt (17 段) | user 消息开头 (Cache-First) | developer 指令 + prompt 文本双路 | live working context | Brain 层综合答案 | Context Files | /compact 指令 |
| 压缩阶段数 | 单阶段 (0.8 阈值) | 4 阶段 (soft/snip/prune/compact) | 双层 (CE compact + 原生) | 三层记忆 (不丢) | 不直接压缩 (检索) | /compress 单命令 | /compact 带指令 |
| Token 估算精度 | 粗糙 (utf8/4) | 高 (Usage 反推 + CJK 友好) | 中 (chars/4 + reserve) | per-turn 真实 | 不直接估算 | /usage 可视 | /usage 可视 |
| 迭代循环内压缩 | 否 (仅 buildPrompt) | 是 (control loop 内) | 是 (turn 内) | 自适应 flush | N/A | 否 | 否 |
| prompt-cache 友好 | 否 (17 段高频变动) | 是 (prefix 字节稳定) | 是 (稳定 call_id + 顺序) | 部分 | N/A | 部分 | 部分 |
| 摘要模板结构化 | 无固定模板 | 7 段结构化模板 | `<conversation_context>` 块 | 蒸馏知识 | 引用 + 缺口分析 | LLM 摘要 | 用户指令导向 |
| 长期记忆机制 | memory 系统 + 摘要 | archive JSONL + Memory v5 | afterTurn ingest | distilled knowledge | 知识图谱 + Dream cycle | 持久记忆 + FTS5 | 多 agent workspace |
| 工具结果保护 | MD5 去重 + 截断 | snip/prune 保配对 | elide/preserve 脱敏 | verbatim 保留 | N/A | N/A | N/A |
| 压缩卡死保护 | 无 | consecutiveCompacts>=2 | should_retire | 自适应节流 | N/A | N/A | N/A |
| 工具迭代内触发 | 否 | 是 | 是 | 自适应 | N/A | 否 | 否 |

---

## 4. 可借鉴点清单 (按价值排序)

### 4.1 [P0] Cache-First 注入原则 (Reasonix)
- **来源**: DeepSeek-Reasonix `internal/agent/compact.go` + `internal/control/input.go`
- **原理**: system prompt 前缀 (base + tools + 稳定 memory) 跨轮字节稳定, 动态内容 (goal/plan/skill/memory snapshot) 注入 user 消息开头
- **本项目收益**: 直接解决 hippy-agents prefix cache 稳定性问题 (限制 #10 + 17 段动态拼接), 提升首 token 延迟与单价 (cache hit 折扣)
- **适配难度**: 中。需要重构 PromptBuilder, 把 17 段拆分为「稳定段」(role/core_files/global_rules) 与「动态段」(memory/skill/plan/critical_reminders), 动态段移到 user 消息开头
- **风险**: user 消息开头注入可能影响部分模型对 system/user 边界的理解, 需要测试

### 4.2 [P0] 4 阶段分层压缩 (Reasonix)
- **来源**: Reasonix compact.go:24-36, 85-146
- **原理**: soft(0.5 提示) → snip(0.6 tool 结果加 head/tail 标记) → prune(0.8 占位符替换) → compact(仍超则 summarize), 每阶段独立可跳过
- **本项目收益**: 解决限制 #1 (触发器未启用) + #9 (单时机触发); snip/prune 阶段无 LLM 调用, 显著降低压缩成本
- **适配难度**: 中。本项目已有 pruneToolResults (≈ snip+prune 雏形), 需补 soft notice 阶段 + 把 4 个 CompactionTrigger 接入对应阶段
- **关键约束**: snip/prune 绝不删除消息, 保持 tool_use/tool_result 配对 (本项目 splitMessages 已有对齐保护, 可复用)

### 4.3 [P0] 结构化 summary 7 段模板 (Reasonix)
- **来源**: Reasonix compact.go:55-80
- **原理**: 摘要按 7 段 (Standing facts / Goal / Decisions / Files & code / Commands / Errors / Pending) 结构化, bullet 形式, 强制保留 identifiers/paths/numbers, 禁止发明
- **本项目收益**: 解决「无固定摘要模板」问题; 结构化摘要便于后续注入与跨会话检索
- **适配难度**: 低。仅修改 performLlmCompaction 的 prompt 模板即可
- **额外收益**: 7 段模板与 hippy-agents 的 memory 系统 (10 类型) 可对齐映射

### 4.4 [P1] Token 估算校准 (Reasonix)
- **来源**: Reasonix estimateTextTokens + tokPerChar 反推
- **原理**: tokPerChar 从上一轮真实 Usage.PromptTokens 反推, 限 [0.05, 2] 区间; estimateTextTokens 取 max(bytes/4, runes) 对 CJK 友好
- **本项目收益**: 解决限制 #4 (中文 token 估算粗糙 0.75 vs 实际 1.5-2x)
- **适配难度**: 低。本项目 LLM 调用已记录 Usage, 仅需在 TokenEstimator 加入反推逻辑 + CJK 取 max
- **风险**: 不同 provider token 计算口径不一致, 需 per-provider 校准

### 4.5 [P1] 压缩卡死保护 (Reasonix)
- **来源**: Reasonix compactStuck 机制
- **原理**: consecutiveCompacts >= 2 则 compactStuck=true, 暂停压缩并 warning, 防止压缩-膨胀死循环
- **本项目收益**: 防止限制 #9 (压缩时机单一) 引发的潜在死循环
- **适配难度**: 低。在 ContextManager 加一个计数器 + 标志位即可

### 4.6 [P1] UTF-16 安全截尾 (Codex)
- **来源**: Codex context-engine-projection.ts sliceTailFromCodePointBoundary (517-525)
- **原理**: 截断后检测末尾是否落在低代理 (0xdc00-0xdfff), 是则跳过避免 U+FFFD 损坏
- **本项目收益**: 解决 pruneToolResults head/tail 截断可能产生的 emoji/中文代理对损坏
- **适配难度**: 低。Kotlin String 基于 UTF-16, 直接移植检测逻辑即可
- **注意**: Kotlin 中等价于检查 `char.code in 0xDC00..0xDFFF`

### 4.7 [P1] 看门狗矩阵 (Codex)
- **来源**: Codex turn_timeout / post_tool_quiet_timeout / turnCompletionIdleTimeout / requestTimeout + should_retire
- **原理**: 多维度超时 + 退役标志强制下轮重建
- **本项目收益**: 解决限制 #3 (ContextWindowGuard 不阻断) + Agent 循环可能卡死
- **适配难度**: 中。需要为 Agent.maxIters 循环加 withTimeout, 区分 turn / tool / post-tool 维度
- **风险**: timeout 取消需要妥善释放工具资源 (WorkManager / Accessibility)

### 4.8 [P2] 三层记忆 (QwenPaw)
- **来源**: QwenPaw 三层记忆架构
- **原理**: live working context (活动) + full verbatim history (完整) + distilled knowledge (蒸馏), 旧轮次驱逐可按需召回, "nothing is summarized away or lost"
- **本项目收益**: 解决摘要后事实丢失风险; 与 hippy-agents 现有 memory 系统 + secondbrain.db 互补
- **适配难度**: 中-高。需要新增 verbatim history 层 (当前 MessageEntity 已部分承担), distilled knowledge 层可与 memory 系统合并
- **取舍**: 移动端存储受限, verbatim history 需要过期清理策略

### 4.9 [P2] /compact 带指令 (openclaw)
- **来源**: openclaw /compact [instructions]
- **原理**: 用户可指定压缩重点 (如 "保留所有文件路径"), 压缩器按指令调整保留策略
- **本项目收益**: 用户可控压缩方向, 避免关键信息被摘要掉
- **适配难度**: 低。在 performLlmCompaction 的 prompt 前追加用户指令即可
- **风险**: 用户指令可能与默认 7 段模板冲突, 需要明确优先级

### 4.10 [P2] Tool payload 脱敏 (Codex)
- **来源**: Codex elide/preserve 双模式 + redactSensitiveFieldValue
- **原理**: elide 模式只保留 tool name + [omitted]; preserve 模式降为 shape (键名 + 类型) + 敏感字段 redact
- **本项目收益**: 提升 prune 阶段信息密度, 减少 token 占用
- **适配难度**: 中。需要为 Tool 结果定义 shape 抽取器 + 敏感字段识别 (可与 security/AuditLogger 协同)

---

## 5. 不建议借鉴的点

### 5.1 Codex 子进程 JSON-RPC 架构
- **原因**: Android 单进程模型, hippy-agents 已是单一 Agent 进程; JSON-RPC 跨进程通信的开销与复杂度无收益
- **替代**: 已有的 Agent 主体 + Koin DI 已足够

### 5.2 gbrain 向量数据库 + 知识图谱
- **原因**: HNSW 向量索引 + BM25 + 知识图谱多跳遍历对内存/CPU/存储要求高, 移动端资源受限; Dream cycle 后台 cron 与 Android 后台限制冲突
- **替代**: 已有 Room FTS + memory 系统; 如需语义检索, 可考虑 ONNX Runtime 本地小模型 (项目已用 ONNX 做 YOLO)

### 5.3 Memory v5 编译器 (Reasonix)
- **原因**: 本地规则驱动的编译器突变 + 策略评分系统复杂度过高, 收益不明确; 模型不重写代码意味着需要预置大量规则
- **替代**: hippy-agents 已有 memory 系统 + 10 类型, 通过 prompt 引导 LLM 维护即可

### 5.4 Codex 双层压缩 (CE compact + 原生)
- **原因**: 双层架构源于 Codex 子进程不可侵入的约束, hippy-agents 单进程内可直接控制 ContextManager, 无需双层
- **替代**: 直接采用 Reasonix 4 阶段单层压缩

### 5.5 hermes-agent Honcho dialectic user modeling
- **原因**: 辩证用户建模需要额外服务依赖 (Honcho), 与 hippy-agents 本地优先原则冲突
- **替代**: 已有 memory 系统 + 用户画像 (如有) 已足够

### 5.6 openclaw 多 Agent 路由 (按 channel/account/peer)
- **原因**: hippy-agents 是单用户移动 Agent, 无多 channel/account 路由需求
- **替代**: 已有 AgentMode (AUTO/NONE/CHAT/WORK) 切换足够

### 5.7 gbrain Dream cycle 夜间 cron
- **原因**: Android 后台执行受 Doze 模式与电池优化限制, 长时夜间 cron 不可靠; WorkManager 已是更优的 Android 后台方案
- **替代**: 已有 WorkManager 2.9.0, 可用周期任务 + 约束 (充电/空闲) 实现轻量 Dream cycle

---

## 附录: 借鉴优先级总览

| 优先级 | 借鉴点 | 来源 | 适配难度 | 预期收益 |
|---|---|---|---|---|
| P0 | Cache-First 注入原则 | Reasonix | 中 | prefix cache 稳定性 |
| P0 | 4 阶段分层压缩 | Reasonix | 中 | 触发器启用 + 成本下降 |
| P0 | 结构化 summary 7 段模板 | Reasonix | 低 | 摘要质量提升 |
| P1 | Token 估算校准 | Reasonix | 低 | 解决中文估算偏差 |
| P1 | 压缩卡死保护 | Reasonix | 低 | 防死循环 |
| P1 | UTF-16 安全截尾 | Codex | 低 | 防 emoji 损坏 |
| P1 | 看门狗矩阵 | Codex | 中 | 防卡死 |
| P2 | 三层记忆 | QwenPaw | 中-高 | 防事实丢失 |
| P2 | /compact 带指令 | openclaw | 低 | 用户可控 |
| P2 | Tool payload 脱敏 | Codex | 中 | 信息密度提升 |

> 建议落地顺序: P0 三项先做 (Reasonix 借鉴), 验证收益后再做 P1, P2 视资源情况选做。
