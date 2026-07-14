# DeepSeek-Reasonix Goal Mode + Target Model & Codex 模式/模型选择机制调研

> 调研对象: `D:\WorkSpaces\refs\DeepSeek-Reasonix` (Go 1.25 编码 Agent) 与 `D:\WorkSpaces\refs\openclaw` + `hermes-agent` + `evolver` 中的 Codex 集成代码
> 调研目的: 澄清"目标模 / target mode / target model"在两项目中的实际语义, 并评估对本项目 (HippyAgent, Android 单进程 Agent) 的适配性

---

## 1. 概念澄清

用户口中的 "target mode / 目标模" 在两项目中**均无字面对应**, 直接按字面搜索会导致落空。需要做以下术语对齐:

| 用户术语 | 实际项目概念 | 所属项目 |
|---|---|---|
| target mode / 目标模 | **Goal Mode (目标模式)** | DeepSeek-Reasonix |
| 目标模 (协作方式) | Collaboration mode (Normal/Plan/Goal) | DeepSeek-Reasonix |
| target model | `subagent_model` / `planner_model` / `subagent_models` map | DeepSeek-Reasonix |
| (Codex 中无对应) | Collaboration mode (Default/Heartbeat) + runtime + app-server mode | Codex 集成 |

### 1.1 DeepSeek-Reasonix 侧

- 实际命名是 **Goal Mode (目标模式)**, 不是 "target mode"。
- 协作方式是**一根正交轴**, 三选一: `Normal`(普通) / `Plan`(计划) / `Goal`(目标)。
- 另有一根**独立的省 token 模式开关**轴, 与协作方式正交。
- "target model" (按任务定向选模型) 在 Reasonix 中体现为 `subagent_model` / `planner_model` / `subagent_models` 三件套, 允许按技能 map 定向指定模型。

### 1.2 Codex 侧

- Codex 中**不存在 "target mode" 这一概念**。最接近的是:
  - **协作模式 (Collaboration mode)**: Default / Heartbeat
  - **Runtime 选择**: `codex` / `openclaw` / `auto`
  - **App-server 模式预设**: `yolo` / `guardian`
- 模型选择由 Codex **原生管理**, 外置集成引擎**不干预**模型路由, 仅通过 `thread/tokenUsage/updated` 通知被动接收用量。

### 1.3 重要前提

refs 内**无 Codex 源码本身**, 下文 Codex 部分是通过三处集成代码还原:
- `D:\WorkSpaces\refs\openclaw\extensions\codex\src\app-server\` (最完整集成)
- `D:\WorkSpaces\refs\openclaw\docs\plugins\codex-harness-runtime.md` (运行时边界契约)
- `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py` (Python 实现, 协议层最清晰)

---

## 2. DeepSeek-Reasonix Goal Mode 详解

### 2.1 关键文件

- `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\goal.go` — Goal 状态机 (`goalMachine`)
- `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\turn_orchestrator.go` — 轮次编排
- `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\input.go` — `Compose` + `/goal` 命令解析
- `D:\WorkSpaces\refs\DeepSeek-Reasonix\docs\COLLABORATION_MODES.zh-CN.md` — 协作模式文档
- `D:\WorkSpaces\refs\DeepSeek-Reasonix\docs\GOAL_ENFORCEMENT.zh-CN.md` — Goal 强制执行文档

### 2.2 概念与适用场景

引自 `COLLABORATION_MODES.zh-CN.md:36-65`:

> 目标模式适合给 Reasonix 一个更长线的目标, 让它持续推进。目标启动后, Reasonix 会围绕该目标工作, 直到任务完成、遇到阻塞、被你停止, 或需要你确认关键决策。

四种终态 (`input.go:33-38`):
- `running` — 进行中
- `complete` — 完成
- `blocked` — 阻塞
- `stopped` — 被停止

### 2.3 触发方式 (`input.go:552-598`, `ParseGoalCommand`)

| 命令 | 作用 |
|---|---|
| `/goal <objective>` | 启动目标 |
| `/goal --strict <objective>` | 严格模式: 每次 `[goal:complete]` 都被拦截直到 todos 全完成 |
| `/goal --research <objective>` | 强制启用 AutoResearch 策略 |
| `/goal --simple <objective>` | 强制轻量 Goal, 不用 AutoResearch |
| `/goal clear` | 清除当前目标 |

此外, 普通聊天里命中"非常强的长周期信号" (如"持续排查直到根因明确") 时, Controller 自动升级为 Goal + AutoResearch, 见 `controller.go:956-981` 的 `AutoStartResearchGoal`。

### 2.4 状态机核心 (`goal.go`)

`goalMachine` 是一个**纯叶子 FSM** — 它**绝不回调 Controller**, 只在自身锁内做状态转移, 让 Controller 可以在持有 `c.mu` 时安全调用其 getter, 避免**锁倒置**死锁风险。

关键字段 (`goal.go:32-54`):

```go
type goalMachine struct {
    mu                 sync.Mutex
    goal               string
    status             string
    researchMode       GoalResearchMode
    autoResearchTaskID string
    turns              int      // 已运行的合成轮次数
    blocks             int      // 连续相同阻塞原因计数
    block              string   // 当前阻塞原因
    interceptMsg       string   // 待注入的拦截消息
    intercepts         int      // 连续拦截计数
    strict             bool     // 严格模式
    selfCheckDone      bool     // 严格模式下已做过自检
    idleTurns          int      // 无工具调用轮次
    statePath          string   // 持久化 sidecar 路径
    writeMu            sync.Mutex // 序列化磁盘写入
}
```

关键常量 (`goal.go:17-23`):

```go
const (
    maxGoalAutoTurns   = 50  // 自动轮次上限
    maxGoalIdleTurns   = 2   // 无工具调用 idle 阈值
    goalContinueTurn   = "Continue pursuing the active goal. If it is complete, ... end with [goal:complete]. If it is truly blocked ... end with [goal:blocked:<short reason>]. Otherwise do the next useful work and end with [goal:continue]."
    goalSelfCheckTurn  = "The agent signaled goal completion and all tasks are marked done. Before finalizing, perform a brief quality self-check: 1. Verify any changed files compile ... 2. Run the relevant tests ... 3. Confirm the original requirements are met ..."
)
```

### 2.5 主循环 (`turn_orchestrator.go:173-220`)

```go
func (o *turnOrchestrator) runGoalLoopWithRawDisplay(ctx context.Context, input, raw, display string) error {
    if err := o.runTurnWithRawDisplay(ctx, input, raw, display); err != nil {
        if ctx.Err() != nil { o.c.stopGoal(GoalStatusStopped) }
        return err
    }
    return o.continueGoal(ctx)
}

func (o *turnOrchestrator) continueGoal(ctx context.Context) error {
    for {
        cont := o.advanceGoalAfterTurn()
        if !cont { return nil }
        if err := ctx.Err(); err != nil {
            c.stopGoal(GoalStatusStopped)
            return err
        }
        turn := goalContinueTurn
        if msg, ok := c.goals.takeIntercept(); ok {
            turn = msg  // 拦截消息替换默认 continuation
        }
        if err := o.runSyntheticTurnWithRawDisplay(ctx, turn, turn, ""); err != nil {
            if ctx.Err() != nil { c.stopGoal(GoalStatusStopped) }
            return err
        }
    }
}
```

执行流程:
1. 跑一轮真实用户输入
2. `advanceGoalAfterTurn` 推进 FSM (解析 `[goal:xxx]` 标记 → `goalMachine.advance` → 决定是否继续)
3. 如继续, 构造合成 user turn (`goalContinueTurn` 或拦截消息), 调用 `runSyntheticTurnWithRawDisplay` 跑下一轮
4. 循环直到 FSM 终止

### 2.6 FSM 转移逻辑 (`goal.go:198-282`)

```go
switch in.status {
case GoalStatusComplete:
    if incomplete := formatIncompleteTodos(...); len(incomplete) > 0 && (g.strict || g.intercepts == 0) {
        g.intercepts++
        g.interceptMsg = incomplete  // 拦截: 列出未完成 todos
        break
    }
    if g.strict && !g.selfCheckDone {
        g.selfCheckDone = true
        g.interceptMsg = goalSelfCheckTurn  // 严格模式: 先自检
        break
    }
    g.goal = ""; g.status = GoalStatusComplete; notice = goalCompleteNotice
case GoalStatusBlocked:
    reason := cleanGoalBlockReason(in.reason)
    if sameGoalBlock(g.block, reason) { g.blocks++ } else { g.blocks = 1; g.block = reason }
    if g.blocks >= 3 { g.status = GoalStatusBlocked; notice = "goal blocked: " + reason }
default:
    g.blocks = 0; g.intercepts = 0; g.selfCheckDone = false; g.idleTurns = 0
}
// Idle 检测
if notice == "" && g.interceptMsg == "" {
    if in.toolCalled { g.idleTurns = 0 } else {
        g.idleTurns++
        if g.idleTurns >= maxGoalIdleTurns {
            g.interceptMsg = "No tool calls in recent turns. Either make progress with tools or signal [goal:blocked:<reason>]."
        }
    }
}
// 上限保护
if g.turns >= maxGoalAutoTurns { g.status = GoalStatusBlocked; g.block = "goal continuation limit reached" }
```

### 2.7 标记解析 (`goal.go:443-464`)

从 assistant 输出的**最后一行**解析:

- `[goal:complete]` → complete
- `[goal:continue]` → running
- `[goal:blocked:<short reason>]` → blocked (带原因)

阻塞原因做了归一化 (`normalizeGoalBlockReason`), 对大小写/空白/标点不敏感, 避免 "minor wording drift" 重置阻塞计数。

### 2.8 上下文注入 (Cache-First 关键设计) (`input.go:153-207`, `Compose`)

```go
func (c *Controller) compose(text string, includeHookContext bool) string {
    notes := c.memory.drainPending()
    goal, goalStatus, goalResearchMode, autoResearchTaskID := c.goals.snapshot()
    if strings.TrimSpace(goal) != "" && goalStatus == GoalStatusRunning {
        prefix := activeGoalBlock(goal, goalResearchMode)
        if runtime := c.autoResearchRuntimeBlock(autoResearchTaskID); runtime != "" {
            prefix += "\n\n" + runtime
        }
        text = prefix + "\n\n" + text  // 注入到 user 消息开头
    }
    if plan { text = PlanModeMarker + "\n\n" + text }
    text = agent.WithResponseLanguage(text, responseLanguage)
    ...
    if len(notes) > 0 { text = "<memory-update>\n...\n</memory-update>\n\n" + text }
    if c.jobs != nil { ... text = "<background-jobs>\n" + note + "\n</background-jobs>\n\n" + text }
}
```

**核心设计**: Goal 上下文注入到 **user 消息开头** (以 `<active-goal>...</active-goal>` XML 块包裹), **绝不**修改 system prompt。这样 DeepSeek 的前缀缓存 (base prompt + tools + memory) 在 Goal 整个生命周期内保持**字节稳定**, cache hit rate 不掉。

`activeGoalBlock` 内容 (`input.go:333-349`):

```go
func activeGoalBlock(goal string, researchMode GoalResearchMode) string {
    b.WriteString(activeGoalOpen); b.WriteString("\n"); b.WriteString(goal); b.WriteString("\n\n")
    b.WriteString("Goal mode: pursue this goal autonomously. Keep working across turns until the goal is complete. Prefer sensible defaults over asking the user; use ask only when you are truly blocked on a user-owned decision. Do not stop after describing a plan; execute the next useful step. End every goal-mode assistant reply with exactly one status marker on its own line: [goal:continue], [goal:complete], or [goal:blocked:<short reason>].")
    if shouldUseAutoResearch(goal, researchMode) { b.WriteString("\n\n"); b.WriteString(autoResearchGoalInstructions) }
    b.WriteString("\n"); b.WriteString(activeGoalClose)
}
```

### 2.9 AutoResearch 策略 (`internal/autoresearch/task.go` + `input.go:351-362`)

- 把目标识别为"长周期研究/排障/优化/实现"时自动启用 (`isAutoResearchGoal` 关键词匹配: 研究/调研/排查/分析/实现/修复/验证/优化/文档/发布 等)
- 状态写**项目本地** `.reasonix/autoresearch/<task-id>/`:
  - `state/task_spec.json`
  - `state/progress.json`
  - `state/findings.jsonl`
  - `state/directions_tried.json`
  - `state/iteration_log.jsonl`
  - `logs/heartbeat.jsonl`
- 每轮把 runtime summary 注入 `<autoresearch-runtime>` 块 (`input.go:273-316`), 包含 `task_id` / `status` / `iteration` / `current_direction` / `stale_count` / `pivot_count` / `open_success_criteria` / `blocker` / `next_required_action`
- Agent 通过 `<autoresearch-evidence>` 块提交证据, host 持久化
- `stale_count >= 2` 触发"结构性 pivot"; `>= 4` 停止自主挖掘, 请求最小外部输入
- **明确不是全局 skill、不是 daemon** — 只是 Goal 的一种策略

### 2.10 严格模式 + 自检 (`GOAL_ENFORCEMENT.zh-CN.md`)

| 功能 | 触发 | 效果 |
|---|---|---|
| Todo 拦截 | 默认 | 声称完成但 todos 未完成时第一次拦截 |
| Override | 默认 | 第二次连续 `[goal:complete]` 覆盖拦截, 正常完成 |
| Strict | `--strict` | 持续拦截直到 todos 全完成, 不允许覆盖 |
| 质量自检 | `--strict` | todos 全完成后提示 agent 自检 (编译/测试/验证) |
| Idle 检测 | 默认 | 连续 2 轮无工具调用时提醒 |
| 并行调度 | `parallel_tasks` 工具 | 并发派发多个子 agent |

**证据审计门控** (`turn_orchestrator.go:222-256`): `advanceGoalAfterTurn` 同时收集 canonical todos 和 `executor.GoalReadinessFailure()` 以及 AutoResearch readiness, 任一未通过都阻止 `[goal:complete]`。

### 2.11 持久化与冷启动恢复

- 每个 session 有 goal-state sidecar (`store.SessionGoalState(sessionPath)`)
- `goalMachine.buildStateLocked` 在 `mu` 下序列化 `goalState`, `writeState` 在 `mu` 外序列化磁盘写入 (`writeMu`)
- `restoreRunningFromState` (`goal.go:377-408`) 冷启动时**只恢复 Running 状态**的目标; 终态 sidecar **故意忽略**, 避免复活已结束的 goal loop
- `terminalTodosFromState` 只在终态时返回 todo 快照 (用于 todo 修复, 不复活 goal)

### 2.12 完成后 Todo 修复 (`turn_orchestrator.go:289-311`)

`completeRemainingGoalTodos`: FSM 真正进入 `complete` 状态时, 把剩余未完成 todos 强制标记为 `completed`, 并发出合成 `todo_write` 事件让前端 panel 同步, 再持久化避免重载回退到旧状态。

---

## 3. DeepSeek-Reasonix Target Model 详解

### 3.1 配置项 (`reasonix.example.toml:47-49`, `docs/GUIDE.md:700-704`)

| 配置项 | 作用 |
|---|---|
| `subagent_model` | 子 agent 使用的模型 |
| `planner_model` | 计划器使用的模型 |
| `subagent_models = { review = "...", security_review = "..." }` | 按技能定向指定模型 (map) |

### 3.2 双模型独立 session (`docs/SPEC.md:167-184`)

- planner 和 executor 跑在**完全独立的 session** 里, 各自 prefix cache 稳定
- 如果共享 session 切模型, prefix 会被对方 turn 污染, cache 直接废
- Plan 文本作为 structured text 在两者间 handoff

### 3.3 Plan-Approved 短暂自动批准窗口 (`turn_orchestrator.go:144-160`)

- 用户批准 plan 后, `c.approval.setPlanAutoApprove(true)` 开启一个**仅当前执行窗口**的 auto-approve
- 避免 plan 中每个写操作都再问一次
- defer 关闭, 下一个 turn (即使 "continue") 回到正常 per-tool 审批

### 3.4 Permission / Collaboration / Approval 三轴正交 (`SPEC.md:357-381`)

- **Collaboration mode**: normal / plan / goal (协作轴)
- **Tool approval posture**: ask / auto / yolo (工具批准姿态)
- **Plan mode**: 更粗粒度的只读 gate, 在 permission 层之前
- **MCP read-only trust**: 单独的信任决策

四者独立组合, **不互相回答对方的问题** (即不能用 plan mode 替代 approval, 也不能用 approval 替代 collaboration)。

---

## 4. Codex 模式/模型选择机制

### 4.1 关键文件

- `D:\WorkSpaces\refs\openclaw\extensions\codex\src\app-server\` (最完整集成)
- `D:\WorkSpaces\refs\openclaw\docs\plugins\codex-harness-runtime.md` (运行时边界契约)
- `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py` (Python 实现, 协议层最清晰)

### 4.2 协作模式 (Collaboration mode) — Codex 原生概念

- **Default mode** — 普通对话回合
- **Heartbeat mode** — 心跳回合, 注入心跳专属的 initiative guidance 作为 collaboration-mode 开发者指令; 非心跳回合恢复 Default mode

文档原话 (`codex-harness-runtime.md:64-69`):

> Heartbeat-specific initiative guidance is sent as a Codex collaboration-mode developer instruction on the heartbeat turn itself. Ordinary chat turns restore Codex Default mode instead of carrying heartbeat philosophy in their normal runtime prompt.

当 `HEARTBEAT.md` 存在时, 指令指向该文件而非内联内容。

### 4.3 Runtime 选择 (`agentRuntime.id`)

| 取值 | 行为 |
|---|---|
| `"codex"` | 走 Codex app-server (fail-closed, 不可用时直接失败, **不悄悄回退**) |
| `"openclaw"` | 走 OpenClaw 内置 harness |
| `"auto"` | 自动选择 (默认) |

**fail-closed 路由**: 生产环境可预测性 > 可用性, 避免无声的行为漂移。

### 4.4 App-server 模式预设 (`appServer.mode`)

| 取值 | approvalPolicy | sandbox | approvalsReviewer |
|---|---|---|---|
| `"yolo"` | `"never"` | `"danger-full-access"` | `"user"` (默认本地可信) |
| `"guardian"` | `"on-request"` | `"workspace-write"` | `"auto_review"` |

### 4.5 服务等级 (`serviceTier`)

| 取值 | 含义 |
|---|---|
| `"priority"` | fast-mode 路由 |
| `"flex"` | flex 处理 |
| `null` | 清除覆盖 |
| `"fast"` (legacy) | 被接受为 `"priority"` |

### 4.6 Code Mode (Codex 原生工具面)

| 取值 | 行为 |
|---|---|
| `codeModeOnly: false` (默认) | 保留 Codex 原生 workspace + code 能力 |
| `codeModeOnly: true` | 仅 Codex code-mode-only 工具面 (OpenClaw 动态工具仍注册以便嵌套 `tools.*` 调用经 app-server `item/tool/call` 桥返回) |

### 4.7 模型选择

- Codex **原生管理**, 外置引擎**不干预**
- Codex 通过 `thread/tokenUsage/updated` 通知报告用量, 字段含:
  - `inputTokens`
  - `cachedInputTokens`
  - `outputTokens`
  - `reasoningOutputTokens`
  - `totalTokens`
  - `modelContextWindow`
- 已知限制: Codex **不暴露 cache-write tokens**, 该 bucket 始终为 0

### 4.8 协议层

Codex 的 agent loop **不在客户端**, 而在 `codex app-server` 子进程里。客户端通过 JSON-RPC 2.0 (stdio 换行分隔, 或 WebSocket) 驱动。

核心方法:
- `initialize`
- `thread/start`
- `turn/start`
- `turn/steer`
- `turn/interrupt`
- `thread/compact/start`

核心通知:
- `item/started`
- `item/completed`
- `turn/completed`
- `thread/tokenUsage/updated`

### 4.9 Item 类型系统 (`codex_event_projector.py:8-26`)

支持的 item 类型:
- `userMessage`
- `agentMessage`
- `reasoning`
- `commandExecution`
- `fileChange`
- `mcpToolCall`
- `dynamicToolCall`
- `plan`
- `hookPrompt`
- `collabAgentToolCall`

**投影规则**: 每个 item 至多投影为 1 个 assistant entry + 1 个 tool entry, 保持 `system → user → assistant → tool → assistant` 消息交替。

---

## 5. 对比分析

### 5.1 Reasonix Goal Mode vs Codex 协作模式 — 设计哲学差异

| 维度 | Reasonix Goal Mode | Codex 协作模式 |
|---|---|---|
| 驱动方式 | **FSM 驱动** (`goalMachine` 纯叶子状态机) | **协议预设** (Collaboration mode 作为 developer instruction 注入) |
| 状态归属 | 客户端持有 FSM, sidecar 持久化 | 状态在 app-server 子进程内, 客户端被动接收通知 |
| 终态判定 | 4 态 (running/complete/blocked/stopped) + 标记解析 `[goal:xxx]` | 2 态 (Default / Heartbeat), 由回合类型决定, 无终态机 |
| 持续推进 | 合成 user turn 循环 (`goalContinueTurn`), 直到 FSM 终止 | 心跳回合独立注入 initiative, 普通回合恢复 Default, 不自动续跑 |
| 拦截机制 | todos 未完成拦截 + strict 自检 + idle 检测 | 无对应, 仅 app-server mode 控制 approval |
| 缓存友好 | Goal 上下文注入 user 消息头, system prompt 字节稳定 | 协作模式作为 developer instruction 注入, 不修改 base |
| 失败处理 | fail-open (override 第二次 complete) + fail-closed (strict 模式) | fail-closed 路由 (runtime 不可用直接失败) |

**核心差异**: Reasonix 把"长周期目标"建模为**客户端 FSM + 合成轮次循环**, Codex 把"协作方式"建模为**协议层 developer instruction**, 不在客户端做状态机。前者是"我推着 agent 走", 后者是"我告诉 agent 这一回合该怎么走"。

### 5.2 Reasonix Target Model vs Codex 模型选择 — 任务定向 vs 原生管理

| 维度 | Reasonix Target Model | Codex 模型选择 |
|---|---|---|
| 控制权 | **客户端控制** (`subagent_model` / `planner_model` / `subagent_models` map) | **服务端原生管理**, 客户端不干预 |
| 粒度 | 按角色 (planner/executor) + 按技能 (map: review/security_review/...) | 不暴露给集成方 |
| Session 隔离 | 强制独立 session, 避免 prefix cache 污染 | 由 app-server 内部管理 |
| 用量反馈 | 客户端自行统计 | `thread/tokenUsage/updated` 通知 (缺 cache-write bucket) |
| 适用场景 | 多模型异构 (强模型 planning + 弱模型执行) | 单一模型路由, 集成方无需关心 |

**核心差异**: Reasonix 把模型选择当作**可配置的工程参数**, Codex 把模型选择当作**不透明的服务能力**。前者适合"用不同模型干不同活", 后者适合"我只要结果, 别问我模型"。

### 5.3 对本项目 (HippyAgent, Android 单进程 Agent) 的适配性评估

| 维度 | Reasonix Goal Mode | Codex 模式/模型 |
|---|---|---|
| 进程模型匹配 | **匹配** — HippyAgent 是单进程 Agent, Goal Mode 的 FSM 客户端持有模型与 HippyAgent 的 AgentStatus 状态机 + AgentMiddleware 中间件链同构 | **不匹配** — Codex 依赖 app-server 子进程 + JSON-RPC, HippyAgent 是单进程内 Agent loop, 无子进程边界 |
| 缓存策略匹配 | **高度匹配** — Goal 上下文注入 user 消息头而非 system prompt 的设计, 直接对应 HippyAgent 的 SSE 流式 + 前缀缓存优化需求 | 部分匹配 — cache-write bucket 缺失, 用量统计不完整 |
| 中间件链兼容 | **可融合** — Goal 标记解析 (`[goal:xxx]`) 可作为一个 AgentMiddleware 实现, advanceGoalAfterTurn 可挂载到 Agent loop 的 turn-after hook | 不直接兼容 — Codex 协议层在子进程, HippyAgent 中间件链在主进程 |
| 状态机映射 | `goalMachine` 的 4 态可映射到 HippyAgent 的 AgentStatus (IDLE/THINKING/EXECUTING_TOOL/ERROR/STOPPED), 但需要扩展 `GOAL_RUNNING` 子态 | Codex 无状态机, 无映射 |
| 持久化 | sidecar + 冷启动恢复只恢复 Running 态的设计, 可借鉴到 HippyAgent 的 Room SessionEntity (androidpaw.db v15) | 不适用 |
| Target Model 适配 | `subagent_models` map 可映射到 HippyAgent 的 ModelRepository + FailoverEngine, 按工具/技能定向选模型 | 不适用 — Codex 模型选择不透明 |
| 移动端资源约束 | Goal Mode 的 `maxGoalAutoTurns = 50` 上限保护 + idle 检测适合移动端长任务, 但 AutoResearch 的 `.reasonix/autoresearch/<task-id>/` 多文件 sidecar 在 Android 沙盒内需迁移到 Room | app-server 子进程 + stdio/WebSocket 在 Android 上需额外进程管理, 成本高 |

**评估结论**:
- **Goal Mode 设计哲学高度适配 HippyAgent**: 单进程 FSM + 中间件链 + 状态机 + Room 持久化, 几乎是 HippyAgent 现有架构的自然延伸。可直接借鉴: 标记解析、拦截机制、idle 检测、上限保护、冷启动只恢复 Running 态。
- **Target Model 三件套适配 HippyAgent**: `subagent_models` map 与 HippyAgent 的 ModelRepository + FailoverEngine 天然契合, 可实现按工具/技能定向选模型。
- **Codex 协议层不直接适配**: app-server 子进程模型与 Android 单进程架构冲突, 但其 Item 类型系统投影规则 (`system → user → assistant → tool → assistant` 交替) 可借鉴到 HippyAgent 的 ChatTurn 系统 (TurnElement 时间戳交错渲染)。
- **Codex fail-closed 路由思想可借鉴**: HippyAgent 的 FailoverEngine 当前是 fail-open (重试/切模型/切 provider/放弃), 在生产环境可考虑引入 fail-closed 选项避免无声行为漂移。

---

## 6. 关键文件路径索引

### 6.1 DeepSeek-Reasonix 关键文件 (绝对路径)

| 文件 | 作用 |
|---|---|
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\goal.go` | Goal 状态机 (`goalMachine` 纯叶子 FSM) |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\turn_orchestrator.go` | 轮次编排 (主循环 + advanceGoalAfterTurn + 完成后 Todo 修复) |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\input.go` | `Compose` 上下文注入 + `/goal` 命令解析 |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\control\controller.go` | Controller (含 `AutoStartResearchGoal` 自动升级逻辑) |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\internal\autoresearch\task.go` | AutoResearch 策略实现 |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\docs\COLLABORATION_MODES.zh-CN.md` | 协作模式文档 (Normal/Plan/Goal) |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\docs\GOAL_ENFORCEMENT.zh-CN.md` | Goal 强制执行文档 (Todo 拦截/Strict/自检/Idle) |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\docs\SPEC.md` | 规范文档 (双模型独立 session + 三轴正交) |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\docs\GUIDE.md` | 用户指南 (Target Model 配置说明) |
| `D:\WorkSpaces\refs\DeepSeek-Reasonix\reasonix.example.toml` | 配置示例 (`subagent_model` / `planner_model` / `subagent_models`) |

### 6.2 Codex 集成关键文件 (绝对路径)

| 文件 | 作用 |
|---|---|
| `D:\WorkSpaces\refs\openclaw\extensions\codex\src\app-server\` | Codex app-server 最完整集成 (目录) |
| `D:\WorkSpaces\refs\openclaw\docs\plugins\codex-harness-runtime.md` | 运行时边界契约 (Collaboration mode / Runtime / app-server mode) |
| `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_app_server_session.py` | Python 实现, JSON-RPC 协议层最清晰 |
| `D:\WorkSpaces\refs\hermes-agent\agent\transports\codex_event_projector.py` | Item 类型系统投影 (10 种 item 类型 + 消息交替规则) |
