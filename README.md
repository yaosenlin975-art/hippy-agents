# HippyAgent

<p align="right"><a href="README_EN.md">🇬🇧 English</a></p>

> **Android 原生 AI 智能体框架** — 赋予手机自主感知、决策与行动的能力。

HippyAgent 不只是一个聊天机器人。它能**看到你的屏幕、理解你的意图、操控你的手机、感知系统事件、与其他智能体协作**——完全运行在你的 Android 设备上，无需依赖云端服务。

> ⚠️ **项目状态**
>
> - 🚧 本项目**仍在开发中**，部分功能尚未完善，聊天界面等仍有一些已知 Bug，欢迎反馈！
> - 🤖 本项目**完全由 AI 移植和实现**——人类仅提供需求和方向

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.7-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![Platform](https://img.shields.io/badge/Platform-Android-34A853?logo=android)](https://www.android.com/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-28-34A853)]()

---

## 目录

- [核心亮点速览](#核心亮点速览)
- [1. 无障碍手机操控 —— AI 真正"动手"](#1-无障碍手机操控--ai-真正动手)
- [2. 智能体群组协作 —— 多 AI 分工协同](#2-智能体群组协作--多-ai-分工协同)
- [3. 智能体私聊讨论 —— Agent-to-Agent 密道](#3-智能体私聊讨论--agent-to-agent-密道)
- [4. 屏内替身模式 —— 跨 App 实时陪伴](#4-屏内替身模式--跨-app-实时陪伴)
- [5. 技能与插件系统](#5-技能与插件系统)
- [6. 安全审计与审批](#6-安全审计与审批)
- [7. 定时任务与记忆系统](#7-定时任务与记忆系统)
- [8. 多通道集成](#8-多通道集成)
- [9. Linux 子系统](#9-linux-子系统)
- [技术架构](#技术架构)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [文档](#文档)

---

## 核心亮点速览

| 能力 | 一句话说明 |
|------|-----------|
| 🖐️ **无障碍操控** | 双轨感知引擎：Accessibility 节点树 + VLM 视觉理解 + YOLO UI 检测，精准定位并操作任何 App 元素 |
| 👥 **群组协作** | 多智能体并行响应，LLM 自动选择发言者，提及链路由、并行仲裁器防冲突 |
| 🤫 **私聊讨论** | Agent 间私下协商（`chat_with_agent` / `delegate_external_agent`），讨论结果仅对用户透明展示 |
| 🪟 **屏内替身** | 悬浮窗跨 App 陪伴，实时屏幕感知 + 语音交互 + TTS 播报 |
| 🛡️ **安全审批** | 危险操作分级审批，审计日志全记录，防广告守卫 |
| 🔌 **技能商店** | 可安装/卸载的技能插件生态，技能策展自动优化 |
| ⏰ **定时任务** | Cron 表达式定时触发，支持静默后台执行 |
| 🧠 **记忆系统** | Second Brain 知识图谱 + Dream 记忆巩固 + 智能上下文压缩 |
| 📡 **多通道** | Telegram / 微信 / 钉钉 / Discord / 飞书等外部频道接入 |
| 🐧 **Linux 子系统** | PRoot 终端环境，执行 shell 脚本、Python、包管理 |
| 🔗 **ACP 协议** | Agent Communication Protocol，JSON-RPC 标准化互操作 |

---

## 1. 无障碍手机操控 —— AI 真正"动手"

HippyAgent 通过 Android AccessibilityService 获得「看」和「摸」屏幕的能力，但远不止于简单的节点遍历。

### 双轨感知引擎

```
用户指令 "帮我在淘宝搜 AirPods"
            │
    ┌───────┴────────┐
    ▼                ▼
 无障碍节点树      VLM 视觉理解
 (结构化元素)     (截图语义分析)
    │                │
    └───────┬────────┘
            ▼
      SmartPerceptionLayer
       (多模态融合)
            │
    ┌───────┴────────┐
    ▼                ▼
 DualTrackDecision   YOLO UI 检测
 Engine             (图标/按钮定位)
    │
    ▼
  精准操作坐标
  (点击/滑动/输入)
```

- **`SmartPerceptionLayer`**：统一融合无障碍节点树 + VLM 视觉分析结果，互补盲区
- **`DualTrackDecisionEngine`**：220 条语义记忆的热缓存，支持屏幕指纹快速匹配，VLM 置信度低于 0.78 时自动回退
- **`UiYoloDetectionEngine`**：YOLO 目标检测定位 UI 图标和按钮（如「搜索」「购物车」），融合引擎决定最终坐标

### 完整的操作生命周期

| 组件 | 职责 |
|------|------|
| `PhoneAutomator` | 协调感知→决策→执行全流程 |
| `NodeOperator` | Accessibility 节点精准操作（点击/长按/滑动/输入） |
| `GesturePlayer` | 手势轨迹回放（复杂滑动、缩放） |
| `ActionExecutor` | 统一操作入口，支持并行批处理 |
| `IncrementalSensor` | 渐进式调整（微调坐标 / 重试） |
| `AppSpecializationManager` | 针对特定 App（微信/淘宝/抖音等）的专用操作策略 |

### 防广告干扰 & 系统事件感知

- **`AdUiGuard`**：检测广告弹窗/诱导按钮，自动跳过或警告
- **`ScreenEventBus`**：感知系统通知、来电、屏幕变化，智能体可主动响应
- **`ScreenFrameSampler`**：按帧率采样屏幕图像，供替身模式实时感知

---

## 2. 智能体群组协作 —— 多 AI 分工协同

你可以创建多个拥有不同专长和性格的智能体，让它们在群聊中协同工作。

### 群聊路由架构

```
用户消息 "帮我分析这份数据并生成报告"
            │
            ▼
    MentionParser 解析 @提及
            │
    ┌───────┴───────┐
    ▼               ▼
 显式 @提及      无 @提及
 直接路由        ▼
          BroadcastPreScorer
           (全体智能体相关性打分)
               │
               ▼
        LLMSpeakerSelector
         (LLM 决策谁回答)
               │
        ┌──────┴──────┐
        │              │
   SpeakerSelected   Finish
   (指定发言者)     (无需回复)
        │
        ▼
    GroupMessageRouter
    ┌─────┼──────────┐
    ▼     ▼          ▼
 Agent1 Agent2   Agent3
 (分析) (验证)   (写报告)
```

### 核心协作机制

| 机制 | 说明 |
|------|------|
| **`LLMSpeakerSelector`** | 智能体带完整描述参与决策，LLM 基于群聊上下文选择最合适的发言者 |
| **`BroadcastPreScorer`** | 广播消息时预打分，过滤不相关的智能体，减少无效唤醒 |
| **`MentionParser`** | 解析 `@智能体名` 和 `@agentId`，支持模糊匹配 |
| **`MentionChainManager`** | 提及链传播控制，防无限循环 |
| **`ParallelArbitrator`** | 并行操作仲裁器，优先级抢占（用户直发 > AI提及 > 广播 > 静默），防止并发冲突 |
| **`ResultAggregator`** | 多智能体结果汇总，去重合并 |

### 群组配置

- 自定义群组成员和发言策略（轮询 / LLM 选择 / 自由发言）
- 决策模型独立配置（可选用轻量模型决策以省成本）
- 「只接收 @ 消息」模式：智能体仅在点名时发言
- 禁用智能体自动隔离，不参与路由

---

## 3. 智能体私聊讨论 —— Agent-to-Agent 密道

智能体之间可以通过私密通道「私下协商」，用户可见讨论结果但**不干扰主对话流**。

### 两种私聊模式

```
┌─────────────────────────────────────┐
│         chat_with_agent             │
│  ┌───┐  双向对话  ┌───┐             │
│  │ A │◄─────────►│ B │             │
│  └───┘  (同步)   └───┘             │
│  用户可查看完整对话记录              │
│  应用场景：信息核实、专业咨询        │
├─────────────────────────────────────┤
│      delegate_external_agent        │
│  ┌───┐  任务委托  ┌───┐             │
│  │ A │──────────►│ B │             │
│  └───┘  (异步)   └───┘             │
│  A 可继续其他工作，B 完成后回报     │
│  应用场景：子任务委托、并行处理      │
└─────────────────────────────────────┘
```

### 私聊特性

- **`AgentMessageBus`**：智能体间消息总线，支持同步/异步通信
- **`AgentMessageRouter`**：路由到指定智能体的消息队列
- **`ACPServer`**：Agent Communication Protocol JSON-RPC 服务端，支持跨系统互操作（`agent/chat`、`agent/status`、`agent/steer`、`agent/queue`）
- **`PrivateTurnCard`**：私聊过程以可折叠卡片展示，不影响对话上下文
- 私聊消息不更新主会话的 `lastMessage` 预览
- 支持复制私聊内容到剪贴板

---

## 4. 屏内替身模式 —— 跨 App 实时陪伴

智能体以悬浮窗形式跨应用存在，实时感知屏幕内容，语音交互，TTS 播报回复。

```
┌─────────────────────────────┐
│        用户正在刷抖音        │
│  ┌─────────────────────┐   │
│  │   🧠 Hippy 替身      │   │
│  │   状态：就绪          │   │
│  │   "这条视频在讲..."   │   │
│  └─────────────────────┘   │
│  ← 悬浮窗（半透明，不遮挡）  │
└─────────────────────────────┘
```

- **`CompanionController`**：完整生命周期管理（进入/退出/暂停）
- **`ScreenFrameSampler`**：按 fps 采样屏幕帧，写入环形缓存
- **`VisionFrameBuffer`**：线程安全的单帧缓存
- **`LocalVoiceVisionHub`**：语音听写 + 屏幕感知融合
- **`CompanionTtsManager`**：TTS 语音播报 Agent 回复
- COMPANION_SYSTEM_PROMPT.md：替身模式专用系统提示词

---

## 5. 技能与插件系统

### 内置系统命令

`/compact` `/new` `/clear` `/history` `/mission` `/plan` `/proactive` `/stats` `/backup` `/summarize_status`

### 技能商店

- 在线浏览可安装技能列表，一键安装/卸载
- 技能按分类展示，支持搜索和评分
- **Curator 技能策展**：Dream 模式自动分析会话，提取高频操作模式生成新技能

### 技能管理

- 按智能体独立管理技能开关
- 技能冲突检测
- 技能执行统计

---

## 6. 安全审计与审批

安全不是事后补充，而是内建于每一层。

### 审批决策引擎

| 引擎 | 职责 |
|------|------|
| `ActionApprover` | 工具执行前审批决策，支持「允许一次 / 始终允许 / 拒绝 / 不再允许」 |
| `DualTrackDecisionEngine` | 双轨验证操作意图（语义匹配 + 屏幕指纹） |
| `AdUiGuard` | 广告/诱导 UI 检测与自动拦截 |

### 审计与监控

- **`AuditLogger`**：操作全记录，含时间/操作/参数/结果
- **安全级别配置**：宽松 / 标准 / 严格
- **持久化规则管理**：可随时撤销「始终允许」「不再允许」设置
- **收件箱**：审批请求 + 推送通知统一管理，支持批量已读

### 工具安全守卫

- 路径遍历检测
- 敏感文件保护
- 权限最小化原则

---

## 7. 定时任务与记忆系统

### Cron 定时任务

- 标准 Cron 表达式（秒级精度）
- 可选择已有会话或自动新建
- **静默模式**：后台执行不通知用户
- 支持手动触发、暂停、编辑

### 记忆系统

```
Second Brain (长期知识库)
    │  事实、偏好、流程、社交记忆
    │  支持搜索、忽略、手动编辑
    │
    ├── Dream 记忆巩固 (后台处理)
    │   分析当日对话 → 提取关键信息
    │   → 整合冲突记忆 → 生成结构化记忆
    │
    ├── 上下文压缩 (Token 超限时)
    │   渐进式压缩历史消息 → LLM 摘要
    │   → 注入压缩摘要 → 保持对话连贯
    │
    └── 自动记忆搜索 (可控)
        发送消息前自动搜索相关记忆
        可配置：搜索条数 / 相关性阈值
```

- **启动时自动重建向量索引**
- 记忆类型筛选：事实 / 偏好 / 流程 / 社交
- 支持手动添加 / 删除 / 忽略

---

## 8. 多通道集成

一个智能体，多个入口。外部消息统一路由到智能体处理。

```
┌─────────┐  ┌──────────┐  ┌─────────┐
│Telegram │  │   微信    │  │  钉钉   │
└────┬────┘  └─────┬─────┘  └────┬────┘
     │             │             │
     └─────────────┼─────────────┘
                   ▼
           ┌──────────────┐
           │ ChannelManager│
           │  频道管理器   │
           └──────┬───────┘
                  ▼
           ┌──────────────┐
           │    Agent      │
           │   智能体核心   │
           └──────────────┘
```

支持的频道：Telegram / 微信 / 钉钉 / Discord / 飞书 / WhatsApp

---

## 9. Linux 子系统

内置基于 **PRoot** 的 Linux 环境，无需 root 权限。

- 终端仿真器，运行 bash / Python / Node.js
- 包管理器支持
- 环境变量管理
- 文件系统与工作区互通
- 智能体可调用 shell 工具执行系统命令
- 启动初始化 UI 显示环境检测进度（PRoot/网络/依赖）

---

## 技术架构

```
┌──────────────────────────────────────────────┐
│                   UI Layer                    │
│  Compose Multi-Screen Navigation              │
│  Chat / GroupChat / Settings / Agent Config   │
│  ConversationList / Inbox / SkillPool         │
├──────────────────────────────────────────────┤
│                ViewModel Layer                │
│  MVVM + StateFlow + Coroutines                │
│  ChatVM / ConversationListVM / PermissionVM   │
├──────────────────────────────────────────────┤
│                 Core Layer                    │
│  ┌─────────┐ ┌──────────┐ ┌────────────────┐ │
│  │ Agent   │ │ Collabo- │ │ Accessibility  │ │
│  │ Engine  │ │  ration  │ │   Framework    │ │
│  │ (ReAct) │ │ Groups   │ │ Perception+Act │ │
│  └─────────┘ └──────────┘ └────────────────┘ │
│  ┌─────────┐ ┌──────────┐ ┌────────────────┐ │
│  │ Skills  │ │  Memory  │ │   Channels     │ │
│  │ System  │ │  System  │ │   Telegram/... │ │
│  └─────────┘ └──────────┘ └────────────────┘ │
│  ┌─────────┐ ┌──────────┐ ┌────────────────┐ │
│  │  Linux  │ │  Model   │ │   Security     │ │
│  │  PRoot  │ │  Router  │ │   Audit+Guard  │ │
│  └─────────┘ └──────────┘ └────────────────┘ │
├──────────────────────────────────────────────┤
│                 Data Layer                    │
│  Room (SQLite) + SharedPreferences            │
│  Sessions / Messages / Memory / Skills        │
└──────────────────────────────────────────────┘
```

### 关键设计决策

- **MVVM + Clean Architecture**：UI / ViewModel / Core / Data 分层清晰
- **Koin DI**：编译期安全，无反射依赖
- **基于 Compose** 的声明式 UI，全量使用 Material 3
- **Kotlin Coroutines + Flow**：全异步，响应式数据流
- **ReAct 循环**：标准 Reasoning + Acting 模式，支持流式输出
- **Per-Session 互斥锁**：每个会话独立的消息处理锁，互不阻塞
- **对象池复用**：GC 友好，降低内存抖动
- **LLM 工具路由**：按需注入工具 Schema，寒暄场景省 ~60% Token

---

## 快速开始

### 环境要求

- Android 9.0+ (API 28+)
- Gradle 8.5+
- Kotlin 2.0+

### 构建

```bash
git clone <your-repo-url>
cd hippy-agent
./gradlew assembleDebug
# APK 输出路径: app/build/outputs/apk/debug/app-debug.apk
```

### 初始化

1. 安装 APK 到 Android 设备
2. 启动应用，完成初始化向导
3. 配置模型供应商（OpenAI / Anthropic / Ollama 等）
4. 创建第一个智能体
5. 授予无障碍服务权限（如需手机操控功能）
6. 开始对话

---

## 项目结构

```
hippy-agent/
├── app/
│   └── src/main/kotlin/com/lin/hippyagent/
│       ├── HippyAgentApp.kt              # Application 入口
│       ├── core/                         # 核心引擎
│       │   ├── agent/                    #   Agent 引擎 (ReAct 循环)
│       │   │   ├── collaboration/        #   群组协作 / ACP / 私聊
│       │   │   └── session/              #   会话存储 (Room DB)
│       │   ├── accessibility/            #   无障碍框架
│       │   │   └── yolo/                 #   YOLO UI 检测
│       │   ├── model/                    #   模型供应商路由
│       │   ├── memory/                   #   记忆系统
│       │   ├── channel/                  #   多通道集成
│       │   ├── linux/                    #   Linux PRoot
│       │   ├── mission/                  #   任务管理
│       │   ├── skill/                    #   技能系统
│       │   ├── notification/             #   通知服务
│       │   ├── permission/               #   权限管理
│       │   └── ...                       #
│       ├── data/                         # 数据层
│       ├── ui/                           # UI 层 (Compose)
│       │   ├── chat/                     #   聊天界面
│       │   ├── conversation/             #   会话列表
│       │   ├── agent/                    #   智能体配置
│       │   ├── settings/                 #   系统设置
│       │   ├── navigation/               #   导航系统
│       │   └── components/               #   通用组件
│       └── di/                           # Koin 依赖注入
├── docs/                                 # 设计文档
│   ├── design.md                         #   完整功能设计
│   ├── competitive-analysis.md           #   竞品对比分析
│   └── designs/                          #   专项设计方案
└── gradle/                               # Gradle 配置
```

---

## 文档

| 文档 | 说明 |
|------|------|
| [`docs/design.md`](docs/design.md) | 完整功能设计文档（界面模块 + 系统机制） |
| [`docs/competitive-analysis.md`](docs/competitive-analysis.md) | 与 OpenClaw / Hermes / QwenPaw / gbrain / X-OmniClaw 的横向对比 |
| [`docs/designs/`](docs/designs/) | 12 篇专项设计方案（提及机制 / 浏览器自动化 / 群聊 Hook 等） |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | 更新日志 |
| [`docs/DefaultRules.md`](docs/DefaultRules.md) | 默认规则体系 |

---

## 致敬

HippyAgent 的诞生离不开以下优秀开源项目的启发与贡献，在此致以最诚挚的敬意：

| 项目 | 说明 |
|------|------|
| **[QwenPaw](https://github.com/agentscope-ai/QwenPaw)** | HippyAgent 的基因来源。由 AgentScope 团队打造的个人 AI 助手，提供技能系统、记忆演化、多通道集成等核心设计理念 |
| **[OpenClaw](https://github.com/openclaw/openclaw)** | 个人 AI 助手运行时，多通道收件箱、技能生态、Cron 定时任务、MCP 集成等设计深刻影响了 HippyAgent 的架构 |
| **[X-OmniClaw](https://github.com/OPPO-Mente-Lab/X-OmniClaw)** | OPPO Mente Lab 出品的多模态 Android Agent，其双轨感知、Omni Memory、行为克隆技能等创新为 HippyAgent 的手机操控提供了重要参考 |
| **[Hermes Agent](https://github.com/NousResearch/hermes-agent)** | Nous Research 打造的自进化 AI Agent，其闭环学习、技能自改进、记忆系统为 HippyAgent 的 Dream 记忆巩固和技能策展提供了灵感 |
| **[GBrain](https://github.com/garrytan/gbrain)** | Garry Tan 构建的知识大脑，自连线知识图谱、混合检索、记忆一致性检查等设计启发了 HippyAgent 的 Second Brain 记忆系统 |
| **[HiClaw](https://github.com/higress-group/hiclaw)** | 阿里巴巴 Higress 团队打造的多 Agent 协作运行时平台，其 Manager-Workers 架构、Agent 间通信、安全网关等设计为 HippyAgent 的群组协作和 ACP 协议提供了借鉴 |

*没有这些卓越的开源项目，就没有今天的 HippyAgent。*

---

<p align="center">
  <sub>Built with Kotlin + Compose • Runs on Your Phone • Driven by Your Rules</sub>
</p>
