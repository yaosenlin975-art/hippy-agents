# 可移植特性调研与迁移方案

> 目标: 对比 Reasonix / Codex / QwenPaw / openclaw / hermes-agent / gbrain 六个参考项目, 梳理可移植到 hippy-agents 的全部候选特性, 共 31 项, 按 4 大类组织。
>
> 本文档只描述方案与工作量, 不修改任何 .kt 代码。每个候选特性统一 8 字段: 特性名 + 来源 / 原理简述 / 本项目现状 / 移植方案 / 实现步骤 / 风险点 / 优先级 / 预估工作量。
>
> 优先级约定: P0 高收益低风险, P1 中, P2 探索性。
> 工作量约定: S ≤ 1 天, M 1–3 天, L > 3 天。

---

## 总览

| 类别 | 范围 | 项数 | 编号 |
| --- | --- | --- | --- |
| A | 上下文管理改进 | 11 | 1–11 |
| B | Goal Mode + Target Model | 8 | 12–19 |
| C | Agent Loop 健壮性 | 7 | 20–26 |
| D | 多 Agent 与记忆 | 5 | 27–31 |

优先级分布: P0 共 12 项, P1 共 12 项, P2 共 7 项。
工作量分布: S 共 14 项, M 共 12 项, L 共 5 项。

---

## A 类: 上下文管理改进 (11 项)

### 1. 启用压缩触发器

1. **特性名 + 来源项目**: 启用已实现但未接入的 CompactionTrigger 链 — 本项目自有。
2. **原理简述**: ContextManager 已定义触发器接口与 4 个实现, 但构造时默认 `compactionTriggers = emptyList()`, 触发器链从未被调用。接入后可按 token 数 / 消息数 / 上下文占比 / 对话轮次四个维度独立判定是否需要压缩, 替代单一 token 阈值。
3. **本项目现状**: 部分有。`CompactionTrigger` 接口与 `TokenCountTrigger`(8000)/`MessageCountTrigger`(40)/`ContextRatioTrigger`(0.75)/`DialogTurnPreservationTrigger`(12 轮, 复用 `ConversationMemoryPolicy.HARD_KEEP_RECENT_DIALOG_TURNS`) 均已实现, 见 [CompactionTrigger.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/context/CompactionTrigger.kt)。但 [Agent.kt:388-391](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/Agent.kt#L388-L391) 实例化 ContextManager 时只传 `profile.running` 与 `toolResultCacheDir`, 触发器参数走默认空列表。`ContextManager.checkContext` 内已有 `CompactionContext` 构造与触发器遍历路径, 只是永远拿到空列表。
4. **移植方案**:
   - 修改 `Agent.kt:388-391`, 显式传入 4 个触发器实例。
   - 在 `RunningConfig` / `LightContextConfig` 增加触发器阈值配置项 (token 阈值、消息数阈值、占比、对话轮次), 由配置驱动实例化, 而非硬编码。
   - 让 `ContextManager.checkContext` 的触发器判定结果 (`triggeredBySystem`) 真正影响 `needsCompression` 决策, 当前代码已计算 `compactionCtx` 但需确认是否与 `needsCompressionByToken` 取并集。
5. **实现步骤**:
   1. 在 `RunningConfig` 增加 `compactionTriggerConfig` 字段 (4 个阈值)。
   2. 修改 `Agent.kt:388-391` 构造触发器列表并传入 ContextManager。
   3. 在 `ContextManager.checkContext` (ContextManager.kt:60+) 确认触发器结果参与 `needsCompression` 判定。
   4. 补单测验证 4 个触发器独立触发。
6. **风险点 / 注意事项**: `DialogTurnPreservationTrigger` 依赖 `recentTurnCount` 字段, 需确认 `checkContext` 调用方是否正确传入该值 (来自 `ConversationMemoryPolicy`); 触发器阈值过小会导致频繁压缩, 建议先用默认值灰度。
7. **优先级**: P0 (高收益低风险, 代码已写好只需接线)。
8. **预估工作量**: S。

### 2. 工具迭代循环内压缩

1. **特性名 + 来源项目**: 工具迭代循环内中途压缩 — 本项目补齐。
2. **原理简述**: 当前压缩检查只发生在 `buildPrompt` (每轮用户对话开始前), 单轮对话内最多 `maxIters` 次工具迭代累积的 tool_result 可能让上下文爆掉却无法中途压缩。需要在 `repeat(maxIters)` 循环内每 N 次迭代做一次轻量 `checkContext`。
3. **本项目现状**: 无。`doProcessMessage` ([Agent.kt:819](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/Agent.kt#L819)) 内的 `repeat(profile.running.maxIters)` 循环 (Agent.kt:889/1191/1431 三处) 只跑 LLM 推理 + 工具执行, 不检查上下文。
4. **移植方案**:
   - 在 `repeat` 循环内每 K 次迭代 (K=5 或 K=10) 调用 `contextManager.checkContext`。
   - 触发压缩时, 关键约束: 必须保护 tool_use / tool_result 配对, 不能在配对中间截断, 否则 LLM 会报 "tool_use without tool_result" 错误。
   - 压缩后替换 `messages` 列表, 继续迭代。
5. **实现步骤**:
   1. 抽取一个 `maybeCompactMidLoop(messages, iteration)` 辅助函数。
   2. 在 3 处 `repeat` 循环开头 (889/1191/1431) 插入 `if (iteration % K == 0) messages = maybeCompactMidLoop(messages, iteration)`。
   3. 实现 tool_use/tool_result 配对保护: 压缩边界必须落在完整配对之后。
   4. 测试长工具迭代场景 (如 50 次文件读取) 验证不爆上下文。
6. **风险点 / 注意事项**: 三处 repeat 循环代码重复, 改一处要同步改三处, 容易漏; 中途压缩会打断流式输出, 需评估用户体验; 配对保护逻辑容易出错, 建议先在单处循环验证再推广。
7. **优先级**: P0。
8. **预估工作量**: M。

### 3. 4 阶段分层压缩

1. **特性名 + 来源项目**: 4 阶段分层压缩 (soft → snip → prune → summarize) — Reasonix。
2. **原理简述**: Reasonix 用 4 个递进阈值 (0.5/0.6/0.8/0.9) 把压缩分成告警、给过期 tool 结果加 head/tail 标记、替换为占位符、强制 fold 摘要四个阶段, 避免一上来就 LLM 摘要。snip/prune 绝不删除消息, 保持 tool_use/tool_result 配对。
3. **本项目现状**: 无。当前是单阶段: 80% token 阈值 → LLM 摘要。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\agent\compact.go:24-36,85-146`。
4. **移植方案**:
   - 在 `ContextManager.checkContext` 引入 4 阶段判定, 基于 `usageRatio`。
   - soft (0.5): 仅记日志告警, 不动作。
   - snip (0.6): 对超出保留窗口的 tool_result 调用 `headAndTailTruncate` (ContextManager.kt:192 已有) 加 head/tail 标记, 不删消息。
   - prune (0.8): 把更老的 tool_result 替换为占位符 `[pruned tool_result: <id>]`, 不删消息。
   - summarize (0.9): 强制 fold 为 LLM 摘要。
   - 复用现有 `headAndTailTruncate` 实现 snip 阶段。
5. **实现步骤**:
   1. 在 `ContextCheckResult` 增加 `stage: CompactionStage` 枚举字段。
   2. 实现 `snipToolResults(messages, keepRecent)` 与 `pruneToolResults(messages, keepRecent)`。
   3. 修改 `checkContext` 按 ratio 分派到 4 阶段。
   4. 修改 `Agent.performLlmCompaction` 调用方按 stage 决定是否调 LLM。
6. **风险点 / 注意事项**: 阈值需要针对本项目的上下文窗口 (通常 8k–32k) 重新校准, Reasonix 默认面向更大窗口; snip/prune 后 tool_result 变短但 id 必须保留, 否则配对校验失败。
7. **优先级**: P0。
8. **预估工作量**: L。

### 4. Cache-First 注入原则

1. **特性名 + 来源项目**: Cache-First 注入 (高频变动段移出 system prompt) — Reasonix。
2. **原理简述**: LLM 厂商的 prefix cache 要求 system prompt 前缀字节稳定。PromptBuilder 把 memory/skill/plan 等 17 段动态内容拼进 system prompt, 任何一段变动都会让整个 cache 失效。把高频变动段移到 user 消息开头的 XML 块, system prompt 只保留字节稳定的静态段。
3. **本项目现状**: 无。`PromptBuilder.buildSystemPrompt` ([PromptBuilder.kt:43-65](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/prompt/PromptBuilder.kt#L43-L65)) 把 17 段全部拼进 system prompt, 其中 memory/volunteered_memories/planContext/critical_reminders/commonMemory 每轮都可能变。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\input.go:153-207`。
4. **移植方案**:
   - system prompt 只保留: `<role>` / `<core_files>` / `<global_rules>` / `<working_directory>` / 静态 skill 目录。
   - 新增 `<active-context>` XML 块, 注入到每轮 user 消息开头, 包含: memory / volunteered_memories / planContext / critical_reminders / commonMemory / current_date。
   - `PromptBuilder` 拆成 `buildStableSystemPrompt` + `buildActiveContextBlock` 两个方法。
   - 修改 `Agent.buildPrompt` 把 active-context 块前置到 user 消息。
5. **实现步骤**:
   1. 拆分 PromptBuilder 为静态/动态两部分。
   2. 定义 `<active-context>` XML schema。
   3. 修改 Agent 主流程, 把动态段从 system prompt 移到 user 消息。
   4. 验证 prefix cache 命中率 (需厂商 API 返回 cache hit 指标)。
6. **风险点 / 注意事项**: 某些模型对 user 消息中的 system 指令遵从度低于 system prompt, 需测试 memory/plan 注入后 LLM 是否仍然遵循; current_date 移到 user 消息后每轮都变, 但只影响当前轮 cache, 不影响 system prompt 前缀 cache, 可接受。
7. **优先级**: P0。
8. **预估工作量**: M。

### 5. 结构化 summary 模板

1. **特性名 + 来源项目**: 7 段式结构化压缩摘要模板 — Reasonix。
2. **原理简述**: 压缩摘要用固定 7 段结构 (Standing facts / Goal / Decisions / Files & code / Commands & outcomes / Errors & fixes / Pending & next step), 要求 terse bullet points, 保留 identifiers/paths/numbers, 不发明, 让后续轮次能稳定恢复关键上下文。
3. **本项目现状**: 部分有。`COMPACT_SYSTEM_PROMPT` 已结构化但段落数少。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\agent\compact.go:55-80`。
4. **移植方案**:
   - 采用 Reasonix 7 段式重写 `COMPACT_SYSTEM_PROMPT`。
   - 每段加约束: "terse bullet points", "preserve identifiers/paths/numbers verbatim", "do not invent"。
   - 保留现有 `SUMMARY_PREFIX` ([Agent.kt:394-398](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/Agent.kt#L394-L398)) 的中文引导语。
5. **实现步骤**:
   1. 定位 `COMPACT_SYSTEM_PROMPT` 定义位置 (Agent.kt 内或独立文件)。
   2. 替换为 7 段式模板。
   3. 跑几轮长对话验证摘要质量。
6. **风险点 / 注意事项**: 中文场景下 bullet point 风格需调整; 7 段模板会增加摘要 prompt 长度, 但换来后续轮次命中率提升, 净收益为正。
7. **优先级**: P0。
8. **预估工作量**: S。

### 6. Token 估算校准

1. **特性名 + 来源项目**: Token 估算校准 (CJK 友好 + 真实 usage 反推) — Reasonix。
2. **原理简述**: 当前 `TokenEstimator` 用 `utf8_bytes/4`, 中文 1 字符 = 3 字节 ≈ 0.75 token, 实际中文 1 字符约 1.5–2 token, 严重偏低导致压缩触发滞后。Reasonix 从真实 usage 反推 tokens/char 比率并限制区间。
3. **本项目现状**: 部分有。`TokenEstimator` ([TokenEstimator.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/context/TokenEstimator.kt)) 用 `utf8_bytes/4`。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\agent\compact.go:580-608`。
4. **移植方案**:
   - `estimateTextTokens` 取 `max(bytes/4, runes)`, 即 utf8 字节估算与 rune (code point) 数取大值, 中文 rune 数更接近真实 token。
   - 从 `TokenUsageManager` ([TokenUsageManager.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/model/TokenUsageManager.kt)) 真实 usage 累积反推 `tokensPerChar` 比率, 限制 [0.05, 2] 区间, 周期性更新。
   - routing 版 ContextManager ([routing/ContextManager.kt:33](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/model/routing/ContextManager.kt#L33)) 用 `ESTIMATE_DIVISOR = 4.0f` 同样需校准。
5. **实现步骤**:
   1. 修改 `TokenEstimator` 增加 rune 估算分支。
   2. 在 `TokenUsageManager` 维护滑动窗口内的 `realTokens / charCount` 比率。
   3. 把比率回写到 `TokenEstimator` 的动态除数。
6. **风险点 / 注意事项**: 反推比率需要足够样本量, 冷启动阶段用 `max(bytes/4, runes)` 兜底; 不同模型 tokenizer 差异大, 比率应按模型维度记录。
7. **优先级**: P1。
8. **预估工作量**: S。

### 7. 压缩卡死保护

1. **特性名 + 来源项目**: 连续压缩卡死检测 — Reasonix。
2. **原理简述**: 若上下文窗口太小, 压缩后立即又触发压缩, 反复无效压缩烧 token。Reasonix 检测 `consecutiveCompacts >= 2` 时置 `compactStuck=true`, 暂停压缩并告警。
3. **本项目现状**: 无。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\agent\compact.go:139-145`。
4. **移植方案**:
   - 在 ContextManager 增加 `consecutiveCompacts` 计数。
   - 压缩成功 (压缩后 ratio 下降到安全区) 清零; 压缩后仍超阈值则 +1。
   - `>= 2` 时置 `compactStuck=true`, `checkContext` 直接返回 `needsCompression=false` 并打 warning: "context_window too small for compaction to help; raise context_window or shrink tool output"。
5. **实现步骤**:
   1. ContextManager 增加状态字段。
   2. `checkContext` 末尾更新计数。
   3. 卡死时通过返回值或异常通知上层。
6. **风险点 / 注意事项**: 卡死后不再压缩可能导致后续 LLM 调用因超长失败, 需配套 fallback (如机械截断); 计数需按 session 隔离, 不能全局累加。
7. **优先级**: P1。
8. **预估工作量**: S。

### 8. 三层记忆模型

1. **特性名 + 来源项目**: 三层记忆 (live working + full verbatim + distilled) — QwenPaw。
2. **原理简述**: QwenPaw 维护三层: 活动工作上下文 (当前可见) + 完整逐字历史 (旧轮次驱逐但可召回) + 蒸馏知识 (从历史摘要蒸馏出稳定事实)。核心承诺 "nothing is summarized away or lost", 旧轮次被驱逐后仍可按需召回。
3. **本项目现状**: 部分有。当前是二层: 活动 `SessionMessage` + `compressedSummary` 摘要。来源: `D:\WorkSpaces\refs\QwenPaw`。
4. **移植方案**:
   - 新增 distilled 知识层: 从多次对话摘要中蒸馏出稳定事实 (用户偏好、长期目标、已确认的文件结构等)。
   - 扩展 `SessionStore` ([SessionStore.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/session/SessionStore.kt)) 增加 distilled 层存储 (可复用 secondbrain.db memory 表)。
   - 驱逐的旧轮次保留完整逐字副本 (可 offload 到磁盘), 支持按需召回。
   - distilled 层注入 system prompt 静态段 (cache 友好), 召回的旧轮次注入 user 消息。
5. **实现步骤**:
   1. 设计 distilled 知识 schema (复用 MemoryEntity 或新建表)。
   2. 实现蒸馏 pipeline: 多次摘要 → 提取稳定事实 → 写入 distilled 层。
   3. 实现按需召回: 检索蒸馏层 + 检索逐字历史。
   4. 接入 PromptBuilder 注入。
6. **风险点 / 注意事项**: 蒸馏 pipeline 复杂, 容易引入幻觉; 移动端存储受限, 逐字历史 offload 需管理磁盘; 三层一致性维护成本高。建议作为长期探索项。
7. **优先级**: P2。
8. **预估工作量**: L。

### 9. /compact 带指令

1. **特性名 + 来源项目**: /compact 命令支持用户指定压缩重点 — openclaw。
2. **原理简述**: 自动压缩无法让用户干预重点, 用户可能希望 "保留所有文件路径和错误信息"。openclaw 支持 `/compact <instructions>` 把用户指令注入压缩 prompt。
3. **本项目现状**: 无。当前自动压缩, 用户无法干预。来源: `D:\WorkSpaces\refs\openclaw`。
4. **移植方案**:
   - 在命令解析层识别 `/compact <instructions>`。
   - 把 instructions 拼接到 `COMPACT_SYSTEM_PROMPT` 末尾, 如 "Additional focus: <instructions>"。
   - 触发一次手动压缩。
5. **实现步骤**:
   1. 找到现有斜杠命令解析入口。
   2. 增加 `/compact` 分支, 解析 instructions。
   3. 调用 `performLlmCompaction` 时传入 instructions。
6. **风险点 / 注意事项**: instructions 可能与 7 段模板冲突, 需明确优先级; 用户可能输入无效指令, 需容错。
7. **优先级**: P2。
8. **预估工作量**: S。

### 10. UTF-16 安全截尾

1. **特性名 + 来源项目**: UTF-16 代理对安全截尾 — Codex。
2. **原理简述**: head+tail 截断在代理对边界切分会产生 U+FFFD 损坏字符。Codex 的 `sliceTailFromCodePointBoundary` 检测低代理 (0xdc00–0xdfff) 起始位置并跳过。Java/Kotlin String 同样是 UTF-16, 相同问题相同解法。
3. **本项目现状**: 无。`headAndTailTruncate` ([ContextManager.kt:192-203](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/context/ContextManager.kt#L192-L203)) 用 `take`/`takeLast`, 未处理代理对。来源: `D:\WorkSpaces\refs\openclaw\extensions\codex\src\app-server\context-engine-projection.ts:517-525`。
4. **移植方案**:
   - 在 ContextManager 增加 `sliceTailFromCodePointBoundary(text, tailChars)` 工具函数。
   - `headAndTailTruncate` 的 tail 部分改用该函数。
   - 关键代码:

   ```kotlin
   fun sliceTailFromCodePointBoundary(text: String, tailChars: Int): String {
       var start = text.length - tailChars
       if (start > 0 && start < text.length) {
           val code = text[start].code
           if (code in 0xdc00..0xdfff) start += 1
       }
       return text.substring(start)
   }
   ```

5. **实现步骤**:
   1. 新增工具函数。
   2. 替换 `headAndTailTruncate` 中 `content.takeLast(...)`。
   3. 补单测覆盖 emoji / 罕用 CJK / surrogate pair 边界。
6. **风险点 / 注意事项**: head 截断同样有此问题, 但 head 切在高位代理前, `take` 不破坏; 主要修复 tail; 补充 emoji (如 😀 = U+1F600, UTF-16 双 char) 测试用例。
7. **优先级**: P1。
8. **预估工作量**: S。

### 11. 合并双轨 ContextManager

1. **特性名 + 来源项目**: 合并两套并存的 ContextManager — 本项目清理。
2. **原理简述**: 项目存在两套独立 ContextManager: `core/context/ContextManager` (class, 主流程在用) 与 `core/model/routing/ContextManager` (object 单例, 基于 ContextLevel 5 级枚举, 似乎未接入主流程)。重复实现 token 估算、折叠、截断逻辑, 维护成本高且行为不一致。
3. **本项目现状**: 部分有。主用 [core/context/ContextManager.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/context/ContextManager.kt) (class, 含触发器链、tool_result offload、head+tail); 另有 [core/model/routing/ContextManager.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/model/routing/ContextManager.kt) (object, 含 `ContextLevel` 枚举 NORMAL/FOLD_NORMAL/FOLD_AGGRESSIVE/FORCE_SUMMARY/MECHANICAL_TRUNCATE, 5 级阈值 0.5/0.7/0.8/0.95)。
4. **移植方案**:
   - 先用 Grep 全局确认 routing 版是否被调用 (检索 `routing.ContextManager` 与 `checkContextLevel`/`foldMessages`/`mechanicalTruncate` 调用点)。
   - 若无引用: 直接删除 routing 版, 把 `ContextLevel` 枚举迁移到 core/context (如 4 阶段分层压缩 #3 需要分级枚举)。
   - 若有引用: 把 routing 版的 5 级分级逻辑合并到 core/context 版, 删除 routing 版, 改造调用方。
5. **实现步骤**:
   1. Grep 确认 routing 版引用情况。
   2. 决策: 删除 or 合并。
   3. 迁移 `ContextLevel` 枚举到 core/context。
   4. 全局搜索确认无残留引用。
6. **风险点 / 注意事项**: routing 版可能是为模型路由层预留的 (EscalationContract 同包), 删除前确认无反射/字符串引用; `ContextLevel` 枚举若被序列化持久化 (标注 `@Serializable`), 迁移需保证反序列化兼容。
7. **优先级**: P1。
8. **预估工作量**: M。

---

## B 类: Goal Mode + Target Model (8 项)

### 12. Goal Mode FSM

1. **特性名 + 来源项目**: Goal Mode 有限状态机 (长线目标自主推进模式) — Reasonix。
2. **原理简述**: Reasonix 的 Goal Mode 让 agent 在用户设定目标后自主多轮推进, 用纯叶子 FSM (running/complete/blocked/stopped) 管理状态, 通过 `[goal:continue]`/`[goal:complete]`/`[goal:blocked:<reason>]` 标记驱动合成 continuation 轮, 上限 `maxGoalAutoTurns=50`。
3. **本项目现状**: 无。`AgentMode` ([WorkspaceSkillConfig.kt:8](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/skill/WorkspaceSkillConfig.kt#L8)) 仅 `AUTO, CHAT, WORK, NONE`, 无长线目标模式。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\goal.go`。
4. **移植方案**:
   - `AgentMode` 枚举增加 `GOAL`。
   - 新增 `core/agent/goal/GoalMachine.kt` 实现纯叶子 FSM (见 #19)。
   - 在 Agent 主循环 (Agent.kt doProcessMessage) 增加分支: GOAL 模式下解析 LLM 输出中的 `[goal:...]` 标记, 状态非 complete/blocked 时合成 continuation 轮。
   - `maxGoalAutoTurns=50` 上限, 超出强制停止。
5. **实现步骤**:
   1. 扩展 `AgentMode` 枚举 + 全局搜索补全 when 分支。
   2. 实现 `GoalMachine` (状态机 + 标记解析 + continuation 合成)。
   3. 改造 `doProcessMessage` 增加 GOAL 分支。
   4. 新增 `goalContinueTurn` 合成逻辑。
6. **风险点 / 注意事项**: GOAL 模式会大幅增加 LLM 调用次数与 token 消耗, 移动端需评估电量/流量; 50 轮上限需配合 #20 看门狗防止卡死; `AgentMode` 枚举变更影响 `WorkspaceSkillConfig` 序列化与 UI, 需全链路改造。
7. **优先级**: P0。
8. **预估工作量**: L。

### 13. Goal 上下文注入 (Cache-First)

1. **特性名 + 来源项目**: Goal 上下文以 user 消息 XML 块注入 — Reasonix。
2. **原理简述**: activeGoalBlock 包含 goal 文本 + 行为指令 (autonomous/端到端/状态标记要求), 注入 user 消息开头 `<active-goal>` XML 块, 绝不碰 system prompt, 保持 prefix cache 稳定。
3. **本项目现状**: 无。当前 mode prompt 经 `systemPromptSuffix` 注入 system prompt (见 [ModeSystemPromptInjector.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/mode/ModeSystemPromptInjector.kt))。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\input.go:333-349`。
4. **移植方案**:
   - GOAL 模式下, 每轮 user 消息开头注入 `<active-goal>` XML 块。
   - 块内容: goal 文本 + "你处于自主推进模式, 用 [goal:continue]/[goal:complete]/[goal:blocked:<reason>] 标记状态"。
   - 配合 #4 Cache-First, 与 `<active-context>` 块并列。
5. **实现步骤**:
   1. 定义 `<active-goal>` XML schema。
   2. 在 `buildActiveContextBlock` (#4) 增加 goal 分支。
   3. 验证 GOAL 模式下 system prompt 字节稳定。
6. **风险点 / 注意事项**: goal 文本每轮重复注入增加 token, 但换取 cache 命中, 净收益为正; 需确保 goal 文本本身在单次会话内不变。
7. **优先级**: P0。
8. **预估工作量**: M。

### 14. Idle 检测 + 阻塞归一化

1. **特性名 + 来源项目**: Idle 检测 + 阻塞原因归一化 — Reasonix。
2. **原理简述**: 连续 N=2 轮无工具调用判定 idle, 注入提醒; 阻塞原因归一化 (大小写/空白/标点) 避免 agent 改一两个字符绕过 3 次阻塞上限。
3. **本项目现状**: 无。仅死循环检测 (maxIters), 无 idle 检测。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\goal.go:198-282`。
4. **移植方案**:
   - `GoalMachine` 维护 `idleCount`: 连续 2 轮无 tool_call 则 +1, 触发注入提醒 "要么用工具推进, 要么 [goal:blocked]"。
   - `normalizeGoalBlockReason(reason)`: lower case + 去标点 + 去多余空白, 用于阻塞去重计数。
   - 同一归一化原因累计 3 次强制 `stopped`。
5. **实现步骤**:
   1. 在 `GoalMachine` 增加 idle 检测字段。
   2. 实现 `normalizeGoalBlockReason` (用顶层 `Regex` 去标点, 遵循编码规范)。
   3. 阻塞计数按归一化 key 累积。
6. **风险点 / 注意事项**: 归一化正则必须在 `companion object` 或顶层 `private val` 定义, 不得在函数内裸构造 (项目硬规则); idle 阈值 N=2 可能过激进, 建议可配置。
7. **优先级**: P0。
8. **预估工作量**: S。

### 15. AutoResearch 状态隔离

1. **特性名 + 来源项目**: AutoResearch 长周期研究状态隔离 — Reasonix。
2. **原理简述**: 长周期研究任务把状态存到项目本地目录 (.hippy/autoresearch/<task-id>/), 包含 task_spec/progress/findings/directions_tried/iteration_log, runtime summary 每轮注入 user turn, agent 通过 `<autoresearch-evidence>` 块提交证据, stale_count >= 2 触发结构性 pivot, >= 4 停止自主挖掘。
3. **本项目现状**: 无。无长周期研究策略。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\autoresearch\task.go`。
4. **移植方案**:
   - 在工作目录下 `.hippy/autoresearch/<task-id>/` 存状态文件 (JSON/JSONL)。
   - 文件: `task_spec.json` / `progress.json` / `findings.jsonl` / `directions_tried.json` / `iteration_log.jsonl`。
   - runtime summary 每轮注入 `<autoresearch-runtime>` user turn。
   - agent 输出 `<autoresearch-evidence>` 块时解析并追加到 findings.jsonl。
   - stale_count (连续无新发现轮数) >= 2 pivot, >= 4 停止。
5. **实现步骤**:
   1. 设计状态文件 schema。
   2. 实现 `AutoResearchStateStore` 读写 (复用 FileUtils.atomicWrite)。
   3. 实现 evidence 块解析。
   4. 接入 GoalMachine 的 continuation 逻辑。
6. **风险点 / 注意事项**: 移动端文件 IO 频繁影响电量; JSONL 追加需处理并发; pivot 策略 (换方向) 需要明确触发条件, 否则可能无限换方向。
7. **优先级**: P1。
8. **预估工作量**: M。

### 16. Plan-Approved auto-approve 窗口

1. **特性名 + 来源项目**: Plan 批准后开启 auto-approve 窗口 — Reasonix。
2. **原理简述**: 每次工具调用独立审批会很烦, plan 批准后开启当前执行窗口 auto-approve, defer 关闭, 下一个 turn 回到正常 per-tool 审批, 避免 plan 中每个写操作都再问一次。
3. **本项目现状**: 无。每次工具调用独立审批 (`TaskApprovalService` ([TaskApprovalService.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/task/TaskApprovalService.kt)))。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\turn_orchestrator.go:144-160`。
4. **移植方案**:
   - `TaskApprovalService` 增加 `setPlanAutoApprove(true)` / `defer { setPlanAutoApprove(false) }`。
   - plan 批准事件触发 setPlanAutoApprove(true)。
   - 当前 turn 结束 (defer) 关闭, 下个 turn 回到 per-tool 审批。
5. **实现步骤**:
   1. TaskApprovalService 增加 plan auto-approve 标志。
   2. plan 批准流程接入。
   3. turn 结束清理。
6. **风险点 / 注意事项**: auto-approve 窗口内的危险操作 (如删除) 仍需独立审批, 需白名单机制; defer 在协程中需用 `try/finally` 而非 Kotlin `defer` (Kotlin 无 defer)。
7. **优先级**: P1。
8. **预估工作量**: S。

### 17. 合成 user turn 机制

1. **特性名 + 来源项目**: 合成 user 消息驱动下一轮 — Reasonix。
2. **原理简述**: goal continuation / plan approved 等场景用合成 user 消息驱动下一轮, 比改 system prompt 灵活, 不破坏 cache。合成消息不显示为用户气泡。
3. **本项目现状**: 无。模式切换靠改 system prompt。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\turn_orchestrator.go:173-220`。
4. **移植方案**:
   - `SessionMessage` 增加 `IsSyntheticUserMessage` 标记字段。
   - 新增 `runSyntheticTurn(content)` 方法: 构造合成 user 消息注入队列, 触发一轮 LLM。
   - UI 层根据 `IsSyntheticUserMessage` 隐藏用户气泡。
   - GOAL continuation / plan approved 用合成消息而非改 system prompt。
5. **实现步骤**:
   1. SessionMessage 增加标记字段 (注意 Room schema 迁移 + version++)。
   2. 实现 `runSyntheticTurn`。
   3. UI 过滤合成消息。
   4. GOAL/plan 流程改用合成消息。
6. **风险点 / 注意事项**: SessionMessage schema 变更需 Room Migration + db version++ (主库 v15 → v16); 合成消息也计入压缩窗口, 需避免堆积; UI 过滤需全链路检查。
7. **优先级**: P0。
8. **预估工作量**: M。

### 18. Target Model: 按任务类型定向选模型

1. **特性名 + 来源项目**: 按任务类型定向选模型 (planner/subagent/命名子模型) — Reasonix。
2. **原理简述**: Reasonix 配置 `subagent_model` / `planner_model` / `subagent_models = { review = "...", security_review = "..." }`, 不同任务类型用不同模型, planner 与 executor 双模型独立 session 各保 prefix cache 稳定。
3. **本项目现状**: 无。仅 `EscalationContract` ([EscalationContract.kt](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/model/routing/EscalationContract.kt)) 的 `<<<NEEDS_PRO>>>` 自升级, 无按任务类型定向选模型。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\reasonix.example.toml:47-49`, `docs/GUIDE.md:700-704`。
4. **移植方案**:
   - `AgentProfile` 增加: `subagentModelName: String?` / `plannerModelName: String?` / `subagentModels: Map<String, String>` (任务类型 → 模型名)。
   - `SubAgentOrchestrator.spawnSubAgent` ([SubAgentOrchestrator.kt:17](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/subagent/SubAgentOrchestrator.kt#L17)) 增加任务类型参数, 按类型选模型。
   - planner 与 executor 各保独立 session (独立 ContextManager 实例), 保持各自 prefix cache 稳定。
5. **实现步骤**:
   1. AgentProfile 增加模型字段。
   2. SubAgentOrchestrator 增加任务类型 → 模型解析。
   3. 独立 session 隔离。
   4. 配置 UI 暴露模型映射。
6. **风险点 / 注意事项**: 多模型增加 API key 管理复杂度; 不同模型 context window 不同, ContextManager 需按模型动态获取; 移动端多模型并发受内存限制。
7. **优先级**: P0。
8. **预估工作量**: M。

### 19. 纯叶子 FSM 模式

1. **特性名 + 来源项目**: 纯叶子 FSM 设计原则 (不持锁回调) — Reasonix。
2. **原理简述**: 状态机设计为纯叶子: 输入全部由调用方在锁外采集, 输出只是数据, 绝不回调; `advance()` 取已采集的输入返回待持久化数据 + notice, 避免持锁回调导致锁倒置和死锁。
3. **本项目现状**: 无。状态机逻辑混在主流程 (Agent.kt 内联)。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\goal.go:25-31` (注释明确)。
4. **移植方案**:
   - `GoalMachine` / `TaskStateMachine` 设计为纯叶子: 只暴露 `advance(input): Output` 形式。
   - 输入数据 (idle 检测、tool 计数、阻塞原因) 由 Agent 主循环在锁外采集后传入。
   - 输出: 待持久化状态 + notice (是否合成 continuation、是否停止)。
   - 绝不在 FSM 内部回调 Agent 方法。
5. **实现步骤**:
   1. 定义 `GoalMachineInput` / `GoalMachineOutput` data class。
   2. 实现 `advance(input): Output` 纯函数。
   3. Agent 主循环调用 advance, 根据 Output 执行副作用。
6. **风险点 / 注意事项**: 纯叶子设计需要把所有副作用外移, 接口会变啰嗦; 但换来可测试性 (FSM 可纯单测) 与无死锁保证, 值得。
7. **优先级**: P1。
8. **预估工作量**: S。

---

## C 类: Agent Loop 健壮性 (7 项)

### 20. 看门狗矩阵 + should_retire

1. **特性名 + 来源项目**: 看门狗矩阵 (turn timeout / post-tool-quiet timeout / should_retire) — Codex。
2. **原理简述**: 仅 maxIters 上限不够, 需要多维看门狗: turn timeout (600s 整回合) + post-tool-quiet timeout (90s, 工具完成后 LLM 静默) + should_retire 标志 (强制下轮重建)。判定条件: 启动失败 / turn 超时 / 子进程意外退出 / 工具后静默超时。
3. **本项目现状**: 无。仅 maxIters 上限。来源: `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py`, `D:\WorkSpaces\refs\openclaw\extensions\codex\src\app-server\attempt-turn-watches.ts`。
4. **移植方案**:
   - 在 Agent 主循环外层包 `withTimeoutOrNull(turnTimeoutMs)` (默认 600_000)。
   - 工具执行完成后启动 post-tool-quiet 计时器, LLM 开始响应则取消; 超时 (90s) 置 `shouldRetire=true`。
   - `shouldRetire=true` 时当前 turn 中止, 下轮重建 session 上下文。
   - 判定条件枚举: `STARTUP_FAILED` / `TURN_TIMEOUT` / `SUBPROCESS_EXIT` / `POST_TOOL_QUIET`。
5. **实现步骤**:
   1. 定义 `WatchdogConfig` 与 `RetireReason` 枚举。
   2. 在 `doProcessMessage` 外层包 withTimeoutOrNull。
   3. 工具执行后 post-tool-quiet 计时器实现。
   4. shouldRetire 标志接入 ContextManager 重建逻辑。
6. **风险点 / 注意事项**: withTimeoutOrNull 中止协程需清理工具执行资源 (避免悬挂子进程); post-tool-quiet 阈值 90s 对慢模型可能偏小, 需按模型配置; shouldRetire 重建会丢失未持久化状态, 需确保关键状态已落库。
7. **优先级**: P0。
8. **预估工作量**: M。

### 21. Auth/失败分类器

1. **特性名 + 来源项目**: OAuth/网络错误子串匹配分类器 — Codex。
2. **原理简述**: 不透明错误直接抛给用户体验差, Codex 用 16 个子串 (invalid_grant/refresh token/expired_token/401 unauthorized/oauth 等) 匹配, 给出用户可操作提示 ("登录过期, 请重新登录")。
3. **本项目现状**: 无。错误处理粗放。来源: `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py:127-177`。
4. **移植方案**:
   - 新增 `AuthErrorClassifier` 工具对象。
   - 维护子串 → 用户提示映射表 (顶层 `private val` 或 `companion object`, 遵循编码规范)。
   - 在 FailoverEngine 错误分类阶段调用 classifier, 命中则生成用户可操作提示。
5. **实现步骤**:
   1. 定义子串列表与提示映射 (companion object)。
   2. 实现 `classify(error): AuthErrorHint?`。
   3. 接入 FailoverEngine 或 Agent 错误处理路径。
6. **风险点 / 注意事项**: 子串匹配大小写敏感问题, 需统一 lower case 后匹配; 子串列表需持续维护 (厂商错误信息变更); 误判会让用户误以为登录过期, 建议保守匹配。
7. **优先级**: P1。
8. **预估工作量**: S。

### 22. <turn_aborted> 标记回退

1. **特性名 + 来源项目**: <turn_aborted> 标记作为终结信号 — Codex。
2. **原理简述**: 某些模型在中断/上游错误时不发 turn/completed 而是塞 `<turn_aborted>` 标记, 检测该标记作为终结信号, 避免烧光整个回合 deadline。
3. **本项目现状**: 无。依赖 LLM 自然结束。来源: `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py:851-866`。
4. **移植方案**:
   - 在 LLM 流式响应解析层检测 `<turn_aborted>` 或 `<turn_aborted/>` 子串。
   - 命中则立即终止当前 turn, 不等 SSE [DONE]。
   - 记录 abort 原因 (若标记带属性)。
5. **实现步骤**:
   1. 在 SSE 解析器增加标记检测 (顶层 `Regex`)。
   2. 命中后抛出特定异常或返回 abort 信号。
   3. Agent 主循环捕获后正常结束 turn。
6. **风险点 / 注意事项**: 标记可能出现在工具参数中被误判, 需只在 assistant 文本输出中检测; 不同模型行为不一致, 需可配置开关。
7. **优先级**: P2。
8. **预估工作量**: S。

### 23. 事件投影器模式

1. **特性名 + 来源项目**: EventProjector 中间层解耦存储与 LLM 输入格式 — Codex。
2. **原理简述**: 引入中间层解耦存储格式 (SessionMessage) 与 LLM 输入格式 (ModelMessage), ProjectionResult 三字段 (messages/is_tool_iteration/final_text), 仅 item/completed 物化消息, 确定性 call_id 提升 cache 命中率。
3. **本项目现状**: 无。SessionMessage 直接转 ModelMessage (ChatTurnConverter)。来源: `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_event_projector.py`。
4. **移植方案**:
   - 新增 `EventProjector` 类: 输入 SessionMessage 事件流, 输出 `ProjectionResult`。
   - `ProjectionResult`: `messages: List<ModelMessage>` / `isToolIteration: Boolean` / `finalText: String`。
   - call_id 生成确定性化 (基于内容 hash 而非随机 UUID), 提升 cache 命中。
   - 仅 item/completed 事件物化消息, intermediate 不物化。
5. **实现步骤**:
   1. 定义 ProjectionResult schema。
   2. 实现 EventProjector (替换或包装 ChatTurnConverter)。
   3. call_id 确定性生成。
   4. 接入 Agent 主循环。
6. **风险点 / 注意事项**: 改造面大, 涉及 SessionMessage ↔ ModelMessage 全部转换路径; 确定性 call_id 需保证同一会话内唯一 (hash + 序号); 中间层增加复杂度, 需权衡收益。
7. **优先级**: P2。
8. **预估工作量**: M。

### 24. 审批桥双层映射

1. **特性名 + 来源项目**: 审批上层语义 ↔ 底层决策双层映射 — Codex。
2. **原理简述**: 上层语义 (auto/approval-required/unrestricted/yolo) 映射到底层决策 (accept/decline/acceptForSession), 权限 profile 映射 (yolo→full-access, auto→workspace-write, approval-required→read-only-with-approval)。
3. **本项目现状**: 部分有。`TaskApprovalService` 单层。来源: `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py:697-759`。
4. **移植方案**:
   - 上层语义枚举: `AUTO` / `APPROVAL_REQUIRED` / `UNRESTRICTED` / `YOLO`。
   - 底层决策枚举: `ACCEPT` / `DECLINE` / `ACCEPT_FOR_SESSION`。
   - 映射表 + 权限 profile 映射。
   - `TaskApprovalService` 增加上层语义入口, 内部映射到底层。
5. **实现步骤**:
   1. 定义双层枚举与映射表。
   2. TaskApprovalService 增加上层 API。
   3. 权限 profile 接入 Guardian 链。
6. **风险点 / 注意事项**: YOLO 模式 (full-access) 危险, 需额外确认; ACCEPT_FOR_SESSION 需 session 级缓存; 与 #16 plan auto-approve 协同设计。
7. **优先级**: P1。
8. **预估工作量**: S。

### 25. fileChange 缓存 (审批参数补全)

1. **特性名 + 来源项目**: 审批时补全 changeset 摘要 — Codex。
2. **原理简述**: 审批时可能缺 changeset, Codex 在 item/started 时缓存 changes 摘要到 `_pending_file_changes[item_id]`, 审批时按 itemId 查找。摘要格式 "2 update, 1 add: /path/a, /path/b, /path/c, +1 more"。
3. **本项目现状**: 无。审批时可能缺 changeset。来源: `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py:761-808`。
4. **移植方案**:
   - 工具调用 started 事件时, 缓存 changes 摘要到 `ConcurrentHashMap<String, String>`。
   - 审批请求时按 itemId 查找摘要, 附加到审批 prompt。
   - 摘要生成函数: 统计 update/add/delete 数, 列前 3 路径, "+N more"。
5. **实现步骤**:
   1. 定义 `_pendingFileChanges` 缓存 (上限 10k, 遵循编码规范)。
   2. 实现 `summarizeChanges(changes)` 函数。
   3. 审批流程查找缓存。
   4. 用完即清 (避免内存泄漏)。
6. **风险点 / 注意事项**: 缓存需 session 级隔离; 摘要生成对大量文件需限流 (前 3 路径); 缓存清理时机 (审批完成 or session 结束)。
7. **优先级**: P2。
8. **预估工作量**: S。

### 26. Prompt-cache 稳定性约束

1. **特性名 + 来源项目**: 移除 system prompt 中的非确定性内容 — Codex/Reasonix。
2. **原理简述**: system prompt 含时间戳/随机 id 会破坏 prefix cache。需移除非确定性内容 (时间戳改放 user 消息), 稳定消息顺序与角色标签, 无随机分隔符, 无 per-run id。
3. **本项目现状**: 部分有问题。`PromptBuilder.buildCurrentDateSection` ([PromptBuilder.kt:101-107](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/prompt/PromptBuilder.kt#L101-L107)) 把 `SimpleDateFormat` 时间戳拼进 system prompt。来源: `D:\WorkSpaces\refs\openclaw\docs\plan\codex-context-engine-harness.md:140-144,345-355`, `D:\WorkSpaces\refs\DeepSeek-Reasonix\REASONIX.md:14-16`。
4. **移植方案**:
   - 移除 `buildCurrentDateSection` 从 system prompt (移到 #4 的 `<active-context>` user 块)。
   - 全局审计 system prompt 生成路径, 确认无随机 UUID / 随机分隔符。
   - 消息顺序稳定: 同一会话内 system prompt 段顺序固定。
5. **实现步骤**:
   1. 移除 `buildCurrentDateSection` 调用 (PromptBuilder.kt:50)。
   2. 把 current_date 移到 active-context 块。
   3. Grep 审计 system prompt 路径的 `UUID.randomUUID` / `Random` / `Date()` 调用。
   4. 验证 prefix cache 命中率。
6. **风险点 / 注意事项**: 移除 current_date 后 LLM 可能丢失时间感知, 需确保 active-context 块每轮注入; 此项与 #4 强耦合, 建议一起实施。
7. **优先级**: P1。
8. **预估工作量**: S。

---

## D 类: 多 Agent 与记忆 (5 项)

### 27. 并行子 Agent 依赖图

1. **特性名 + 来源项目**: 并行子 Agent 任务依赖图 (depends_on) — Reasonix。
2. **原理简述**: Reasonix 的 parallel_tasks 支持任务间依赖, 独立任务先并发, 依赖任务等前置完成, WaitGroup 等待全部完成聚合结果。单任务拒绝 (强制用 task 工具, 避免无谓并发开销)。
3. **本项目现状**: 部分有。`SubAgentOrchestrator.spawnSubAgent` ([SubAgentOrchestrator.kt:17-58](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/agent/subagent/SubAgentOrchestrator.kt#L17-L58)) 支持 parentJobId 但无 dependsOn; `spawnMultiple` 并发提交所有任务无依赖排序; `awaitChildren` 用 deadline 轮询。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\agent\parallel_tasks.go`。
4. **移植方案**:
   - `spawnSubAgent` 增加 `dependsOn: List<Long> = emptyList()` 参数 (父 jobId 列表)。
   - `spawnMultiple` 接受 `SubAgentTask` 列表 (含 `dependsOnTaskIds`), 拓扑排序后分批提交: 零依赖任务首批并发, 依赖任务等前置完成。
   - 用 `HippyJobQueue` 的 job 状态做 WaitGroup。
   - 单任务 (tasks.size == 1) 拒绝, 抛出 "use task tool instead"。
5. **实现步骤**:
   1. `SubAgentTask` 增加 `dependsOnTaskIds: List<String>`。
   2. `spawnMultiple` 实现拓扑排序 + 分批提交。
   3. 依赖任务提交前 await 前置 job 完成。
   4. 单任务拒绝逻辑。
6. **风险点 / 注意事项**: 拓扑排序需检测循环依赖; 依赖任务失败时的级联处理 (复用 `ChildFailPolicy`); HippyJobQueue 需支持依赖等待原语 (或用轮询); 单任务拒绝可能误伤合法单任务场景, 需可配置。
7. **优先级**: P1。
8. **预估工作量**: M。

### 28. 混合检索 + 知识图谱

1. **特性名 + 来源项目**: HNSW 向量 + BM25 + RRF + 知识图谱 + Dream cycle — gbrain。
2. **原理简述**: gbrain 用 HNSW 向量 + BM25 关键词 + RRF 融合 + source-tier boost + intent-aware 改写 + ZeroEntropy reranker; 自连线知识图谱 (put_page 零 LLM 调用抽取实体引用写边); Dream cycle (夜间 cron 去重/修复/评分/发现矛盾)。
3. **本项目现状**: 部分有。`commonMemoryRepo.search` 已有混合检索。来源: `D:\WorkSpaces\refs\gbrain`。
4. **移植方案**:
   - 评估引入 HNSW 向量索引 (移动端资源受限, 可用 sqlite-vss 或轻量向量库如 hnswlib-java)。
   - BM25 关键词检索 (可复用 Room FTS4)。
   - RRF 融合向量 + 关键词结果。
   - 知识图谱: put_page 时正则抽取实体引用写边 (零 LLM), 支持多跳遍历。
   - Dream cycle: WorkManager 定时任务做去重/修复/评分/矛盾发现。
5. **实现步骤**:
   1. 评估移动端向量库选型。
   2. BM25 via Room FTS4。
   3. RRF 融合。
   4. 知识图谱 schema + 抽取。
   5. Dream cycle WorkManager 接入。
6. **风险点 / 注意事项**: 移动端 CPU/内存/电量受限, HNSW 全量索引可能不可行, 需评估数据规模; sqlite-vss 需 native 库增加 APK 体积; Dream cycle 后台任务受 Doze 模式限制; 此项复杂度最高, 建议长期探索, 可分阶段 (先 BM25 + RRF, 后知识图谱)。
7. **优先级**: P2。
8. **预估工作量**: L。

### 29. Context Files

1. **特性名 + 来源项目**: 项目级 .context/ 目录多文件按需注入 — hermes-agent。
2. **原理简述**: 支持项目级 `.context/` 目录多文件 (architecture.md / conventions.md / decisions.md), 按需注入 user 消息开头, 避免全部塞进 system prompt。
3. **本项目现状**: 部分有。AGENTS.md/SOUL.md 已有 (PromptBuilder `<role>` 段, [PromptBuilder.kt:67-84](file:///d:/WorkSpaces/hippy-agents/app/src/main/kotlin/com/lin/hippyagent/core/prompt/PromptBuilder.kt#L67-L84))。来源: `D:\WorkSpaces\refs\hermes-agent`。
4. **移植方案**:
   - 支持 `.context/` 目录, 扫描其中 .md 文件。
   - 文件名作为 key (如 `architecture` / `conventions` / `decisions`), 内容按需注入。
   - 注入方式: 移到 #4 的 `<active-context>` user 块 (cache 友好), 而非 system prompt。
   - 按需: 可配置哪些文件始终注入, 哪些按关键词触发。
5. **实现步骤**:
   1. 定义 `.context/` 目录扫描逻辑。
   2. PromptContext 增加 `contextFiles: Map<String, String>`。
   3. 注入到 active-context 块。
6. **风险点 / 注意事项**: 文件数量多时 user 消息膨胀, 需限制注入数量/大小; 按需触发逻辑需明确 (关键词匹配 or LLM 决策)。
7. **优先级**: P2。
8. **预估工作量**: S。

### 30. FTS5 会话搜索 + LLM 摘要召回

1. **特性名 + 来源项目**: 跨会话 FTS 搜索 + LLM 摘要召回 — hermes-agent。
2. **原理简述**: Room FTS 索引 SessionMessage.content, LLM 摘要后召回, 跨会话检索历史对话。
3. **本项目现状**: 无。无跨会话搜索。来源: `D:\WorkSpaces\refs\hermes-agent`。
4. **移植方案**:
   - Room FTS4 索引 `SessionMessage.content` (FTS4 原生支持, FTS5 需 API 21+, 本项目 minSdk 26 满足)。
   - 新增 `MessageFtsEntity` + DAO 搜索方法。
   - 主库 v15 → v16 Migration (加 FTS 虚拟表 + 触发器同步)。
   - 搜索结果 LLM 摘要后召回注入 active-context。
5. **实现步骤**:
   1. 定义 `MessageFtsEntity` (FTS4 虚拟表)。
   2. Migration v15 → v16: 建 FTS 表 + INSERT/UPDATE/DELETE 触发器同步 SessionMessage。
   3. AppDatabase version++ (15 → 16)。
   4. DAO `searchMessages(query): List<SessionMessage>`。
   5. LLM 摘要 + 注入逻辑。
6. **风险点 / 注意事项**: FTS 表与主表同步需触发器, 增加写放大; 主库 version++ 必须配 Migration (硬规则); FTS4 中文分词需 `tokenize=unicode61` 或自定义 tokenizer; 历史数据回填需在 Migration 中处理。
7. **优先级**: P1。
8. **预估工作量**: M。

### 31. Memory v5 执行编译器

1. **特性名 + 来源项目**: 本地规则驱动记忆策略编译器 — Reasonix。
2. **原理简述**: Reasonix Memory v5.9 用执行轨迹更新策略评分与编译器突变, 模型不重写代码, 自适应记忆策略。
3. **本项目现状**: 无。无自适应记忆策略。来源: `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\memorycompiler\runtime.go`。
4. **移植方案**:
   - 评估本地规则驱动编译器: 执行轨迹 (压缩是否有效、召回是否命中) → 策略评分 → 编译器突变 (调整阈值/段落权重)。
   - 模型不重写代码 (规则驱动, 非 LLM 驱动)。
   - 复杂度高, 建议长期探索。
5. **实现步骤**:
   1. 定义策略评分指标 (压缩有效率、召回命中率、token 节省率)。
   2. 实现轨迹采集。
   3. 实现编译器突变规则 (限幅, 避免剧烈震荡)。
   4. A/B 验证。
6. **风险点 / 注意事项**: 复杂度最高, 收益不明确; 突变规则设计不当会导致策略震荡; 移动端轨迹存储成本; 建议作为 P2 长期探索项, 先观察 #1–#8 落地效果再决定是否需要自适应。
7. **优先级**: P2。
8. **预估工作量**: L。

---

## 汇总与实施建议

### 优先级 × 工作量矩阵

| | S (≤1天) | M (1–3天) | L (>3天) |
| --- | --- | --- | --- |
| **P0** | #1 启用触发器, #5 summary 模板 | #2 循环内压缩, #4 Cache-First, #13 Goal 注入, #17 合成 turn, #18 Target Model | #3 4 阶段压缩, #12 Goal FSM, #20 看门狗 |
| **P1** | #6 Token 校准, #7 卡死保护, #10 UTF-16, #14 Idle 检测, #16 Plan auto-approve, #19 纯叶子 FSM, #21 Auth 分类器, #24 审批双层, #26 cache 稳定性 | #11 合并双轨, #15 AutoResearch, #30 FTS 搜索 | — |
| **P2** | #9 /compact, #22 turn_aborted, #25 fileChange, #29 Context Files | #23 事件投影器, #27 依赖图 | #8 三层记忆, #28 混合检索+图谱, #31 Memory v5 |

### 实施顺序建议

1. **第一批 (P0, 低风险接线)**: #1 启用触发器 → #5 summary 模板 → #10 UTF-16 安全截尾。这三项代码已就绪或改动小, 立即可见收益。
2. **第二批 (P0, 上下文重构)**: #4 Cache-First + #26 cache 稳定性 (强耦合, 一起做) → #3 4 阶段压缩 → #2 循环内压缩。上下文管理是后续所有改进的基础。
3. **第三批 (P0, Goal Mode 主线)**: #12 Goal FSM → #13 Goal 注入 → #14 Idle 检测 → #17 合成 turn → #19 纯叶子 FSM (设计原则贯穿)。Goal Mode 是最大的功能新增。
4. **第四批 (P0, 健壮性 + 模型路由)**: #20 看门狗 → #18 Target Model。这两项让 Goal Mode 可靠运行。
5. **第五批 (P1, 清理与增强)**: #6 #7 #11 #21 #24 #30 等独立项, 可并行推进。
6. **第六批 (P2, 探索性)**: #8 #28 #31 等长期项, 观察前五批效果后再决定。

### 依赖关系

- #4 Cache-First 是 #13 #26 #29 的前置 (统一 active-context 块)。
- #12 Goal FSM 是 #13 #14 #15 #17 的前置。
- #17 合成 turn 依赖 SessionMessage schema 变更 (Room Migration v15 → v16), 与 #30 FTS 搜索的 Migration 冲突, 需合并到同一次 version++。
- #11 合并双轨 ContextManager 应在 #3 4 阶段压缩之前做 (避免改两套)。
- #20 看门狗应在 #12 Goal FSM 之后做 (Goal Mode 50 轮上限需要看门狗兜底)。
