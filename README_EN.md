<p align="right"><a href="README.md">🇨🇳 中文</a></p>

# HippyAgent

> **Android Native AI Agent Framework** — Empowering your phone with autonomous perception, decision-making, and action.

HippyAgent is more than a chatbot. It can **see your screen, understand your intent, control your phone, sense system events, and collaborate with other agents** — all running entirely on your Android device, with no cloud dependency.

> ⚠️ **Project Status**
>
> - 🚧 This project is **still under development** — some features are not yet complete, and the chat interface still has a few known bugs. Feedback welcome!
> - 🤖 This project is **entirely ported and implemented by AI** — humans only provide requirements and direction

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.7-4285F4?logo=jetpackcompose)](https://developer.android.com/compose)
[![Platform](https://img.shields.io/badge/Platform-Android-34A853?logo=android)](https://www.android.com/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-28-34A853)]()

---

## Table of Contents

- [Core Highlights at a Glance](#core-highlights-at-a-glance)
- [1. Accessibility Phone Control — AI That Actually Acts](#1-accessibility-phone-control--ai-that-actually-acts)
- [2. Agent Group Collaboration — Multi-AI Teamwork](#2-agent-group-collaboration--multi-ai-teamwork)
- [3. Agent Private Chat — Agent-to-Agent Backchannel](#3-agent-private-chat--agent-to-agent-backchannel)
- [4. On-Screen Companion Mode — Cross-App Real-Time Presence](#4-on-screen-companion-mode--cross-app-real-time-presence)
- [5. On-Device Offline Inference — Works Without Internet](#5-on-device-offline-inference--works-without-internet)
- [6. Skills & Plugin System](#6-skills--plugin-system)
- [7. Security Audit & Approval](#7-security-audit--approval)
- [8. Scheduled Tasks & Memory System](#8-scheduled-tasks--memory-system)
- [9. Multi-Channel Integration](#9-multi-channel-integration)
- [10. Linux Subsystem](#10-linux-subsystem)
- [Technical Architecture](#technical-architecture)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Documentation](#documentation)
- [Acknowledgments](#acknowledgments)

---

## Core Highlights at a Glance

| Capability | One-Liner |
|------------|-----------|
| 🖐️ **Accessibility Control** | Dual-track perception: Accessibility node tree + VLM visual understanding + YOLO UI detection for precise element targeting |
| 👥 **Group Collaboration** | Multi-agent parallel response, LLM speaker selection, mention-chain routing, parallel arbitrator for conflict prevention |
| 🤫 **Private Chat** | Agent-to-agent private negotiation (`chat_with_agent` / `delegate_external_agent`), transparent to user only |
| 🪟 **On-Screen Companion** | Floating overlay across apps, real-time screen perception + voice interaction + TTS playback |
| 📱 **On-Device Models** | Based on Google LiteRT-LM, 8 sub-2B models for offline inference, always available |
| 🛡️ **Security Approval** | Tiered approval for dangerous operations, full audit logging, ad guard |
| 🔌 **Skill Store** | Installable/uninstallable skill plugin ecosystem, Curator auto-optimization |
| ⏰ **Scheduled Tasks** | Cron expression scheduling, silent background execution |
| 🧠 **Memory System** | Second Brain knowledge graph + Dream memory consolidation + intelligent context compression |
| 📡 **Multi-Channel** | Telegram / WeChat / DingTalk / Discord / Feishu and more |
| 🐧 **Linux Subsystem** | PRoot terminal environment, run shell scripts, Python, package management |
| 🔗 **ACP Protocol** | Agent Communication Protocol, JSON-RPC standardized interoperability |

---

## 1. Accessibility Phone Control — AI That Actually Acts

HippyAgent gains the ability to "see" and "touch" the screen through Android AccessibilityService, but goes far beyond simple node traversal.

### Dual-Track Perception Engine

```
User command "Search AirPods on Taobao"
            │
    ┌───────┴────────┐
    ▼                ▼
 Accessibility      VLM Visual
 Node Tree         Understanding
 (Structured)     (Screenshot Semantics)
    │                │
    └───────┬────────┘
            ▼
      SmartPerceptionLayer
       (Multimodal Fusion)
            │
    ┌───────┴────────┐
    ▼                ▼
 DualTrackDecision   YOLO UI Detection
 Engine             (Icon/Button Localization)
    │
    ▼
  Precise Action Coordinates
  (Tap/Swipe/Type)
```

- **`SmartPerceptionLayer`**: Unified fusion of accessibility node tree + VLM visual analysis, covering each other's blind spots
- **`DualTrackDecisionEngine`**: 220-entry semantic memory hot cache, screen fingerprint fast matching, auto-fallback when VLM confidence < 0.78
- **`UiYoloDetectionEngine`**: YOLO object detection for UI icons and buttons (e.g. "Search", "Cart"), fusion engine decides final coordinates

### Complete Action Lifecycle

| Component | Responsibility |
|-----------|---------------|
| `PhoneAutomator` | Orchestrates perception → decision → execution pipeline |
| `NodeOperator` | Accessibility node precise operations (tap/long-press/swipe/type) |
| `GesturePlayer` | Gesture trajectory playback (complex swipes, pinch) |
| `ActionExecutor` | Unified action entry point, supports parallel batch processing |
| `IncrementalSensor` | Incremental adjustment (micro-coordinate tuning / retry) |
| `AppSpecializationManager` | App-specific operation strategies (WeChat/Taobao/Douyin etc.) |

### Ad Guard & System Event Awareness

- **`AdUiGuard`**: Detects ad popups / misleading buttons, auto-skip or warn
- **`ScreenEventBus`**: Senses system notifications, incoming calls, screen changes; agents can proactively respond
- **`ScreenFrameSampler`**: Samples screen frames at fps rate for companion mode real-time perception

---

## 2. Agent Group Collaboration — Multi-AI Teamwork

Create multiple agents with different specialties and personalities, working together in group chats.

### Group Chat Routing Architecture

```
User message "Analyze this data and generate a report"
            │
            ▼
    MentionParser parses @mentions
            │
    ┌───────┴───────┐
    ▼               ▼
 Explicit @       No @mention
 Direct Route      ▼
          BroadcastPreScorer
           (Relevance scoring for all agents)
               │
               ▼
        LLMSpeakerSelector
         (LLM decides who speaks)
               │
        ┌──────┴──────┐
        │              │
   SpeakerSelected   Finish
   (Assign speaker) (No reply needed)
        │
        ▼
    GroupMessageRouter
    ┌─────┼──────────┐
    ▼     ▼          ▼
 Agent1 Agent2   Agent3
 (Analyze)(Verify) (Write Report)
```

### Core Collaboration Mechanisms

| Mechanism | Description |
|-----------|-------------|
| **`LLMSpeakerSelector`** | Agents participate with full descriptions; LLM selects the best speaker based on group chat context |
| **`BroadcastPreScorer`** | Pre-scores broadcast messages, filters irrelevant agents, reduces unnecessary wake-ups |
| **`MentionParser`** | Parses `@agentName` and `@agentId`, supports fuzzy matching |
| **`MentionChainManager`** | Mention chain propagation control, prevents infinite loops |
| **`ParallelArbitrator`** | Parallel operation arbitrator, priority preemption (User direct > AI mention > Broadcast > Silent), prevents concurrency conflicts |
| **`ResultAggregator`** | Multi-agent result aggregation, deduplication and merging |

### Group Configuration

- Custom group members and speaking strategy (round-robin / LLM selection / free speech)
- Independent decision model configuration (use lightweight model for decisions to save cost)
- "Only receive @messages" mode: agent speaks only when mentioned
- Disable agent auto-isolation, excluded from routing

---

## 3. Agent Private Chat — Agent-to-Agent Backchannel

Agents can negotiate privately through backchannels. Users can see the discussion results but **the main conversation flow is not disrupted**.

### Two Private Chat Modes

```
┌─────────────────────────────────────┐
│         chat_with_agent             │
│  ┌───┐  Two-way chat  ┌───┐        │
│  │ A │◄─────────────►│ B │        │
│  └───┘  (Synchronous) └───┘        │
│  User can view full conversation    │
│  Use case: Fact verification,       │
│  professional consultation          │
├─────────────────────────────────────┤
│      delegate_external_agent        │
│  ┌───┐  Task delegation  ┌───┐     │
│  │ A │──────────────────►│ B │     │
│  └───┘  (Asynchronous)   └───┘     │
│  A can continue other work,         │
│  B reports back when done           │
│  Use case: Subtask delegation,      │
│  parallel processing                │
└─────────────────────────────────────┘
```

### Private Chat Features

- **`AgentMessageBus`**: Inter-agent message bus, supports sync/async communication
- **`AgentMessageRouter`**: Routes to specified agent's message queue
- **`ACPServer`**: Agent Communication Protocol JSON-RPC server, supports cross-system interoperability (`agent/chat`, `agent/status`, `agent/steer`, `agent/queue`)
- **`PrivateTurnCard`**: Private chat displayed as collapsible card, doesn't affect conversation context
- Private chat messages don't update main session's `lastMessage` preview
- Supports copying private chat content to clipboard

---

## 4. On-Screen Companion Mode — Cross-App Real-Time Presence

The agent exists as a floating overlay across apps, with real-time screen perception, voice interaction, and TTS playback.

```
┌─────────────────────────────────┐
│     User is browsing Douyin     │
│  ┌─────────────────────────┐   │
│  │   🧠 Hippy Companion    │   │
│  │   Status: Ready          │   │
│  │   "This video is about…" │   │
│  └─────────────────────────┘   │
│  ← Floating overlay (semi-     │
│     transparent, non-blocking)  │
└─────────────────────────────────┘
```

- **`CompanionController`**: Full lifecycle management (enter/exit/pause)
- **`ScreenFrameSampler`**: Samples screen frames at fps, writes to ring buffer
- **`VisionFrameBuffer`**: Thread-safe single-frame cache
- **`LocalVoiceVisionHub`**: Voice dictation + screen perception fusion
- **`CompanionTtsManager`**: TTS voice playback for agent responses
- COMPANION_SYSTEM_PROMPT.md: Companion mode dedicated system prompt

---

## 5. On-Device Offline Inference — Works Without Internet

Based on Google **LiteRT-LM** engine, runs 8 sub-2B parameter models locally on device, with zero network dependency.

### Pre-installed Model Catalog

| Model | Parameters | Use Case |
|-------|-----------|----------|
| Gemma3-1B | 1B | General conversation |
| Qwen2.5-1.5B | 1.5B | Chinese conversation |
| DeepSeek-R1-Distill-Qwen-1.5B | 1.5B | Reasoning tasks |
| Phi-4-mini | ~2B | Code/Math |
| Qwen3-0.6B | 0.6B | Lightweight tasks |
| TinyLlama-1.1B | 1.1B | General |
| Gemma3-270M | 270M | Ultra-lightweight |
| SmolLM-135M | 135M | Minimum spec |

### Features

- **Backend selection**: Auto / CPU / GPU / NPU
- **HuggingFace mirror acceleration**: Auto-detects fastest mirror in China, resume download
- **Virtual Provider**: Auto-registers as model provider after download, switch and use
- **Memory protection**: Warns when device RAM is low, auto-unloads after 5 min in background
- **Only one engine loaded at a time**, auto-unloads old engine on switch

---

## 6. Skills & Plugin System

### Built-in System Commands

`/compact` `/new` `/clear` `/history` `/mission` `/plan` `/proactive` `/stats` `/backup` `/summarize_status`

### Skill Store

- Browse installable skill list online, one-click install/uninstall
- Skills displayed by category, with search and rating
- **Curator Skill Curation**: Dream mode auto-analyzes conversations, extracts frequent operation patterns to generate new skills

### Skill Management

- Per-agent independent skill toggle management
- Skill conflict detection
- Skill execution statistics

---

## 7. Security Audit & Approval

Security is not an afterthought — it's built into every layer.

### Approval Decision Engine

| Engine | Responsibility |
|--------|---------------|
| `ActionApprover` | Pre-execution approval decisions, supports "Allow Once / Always Allow / Deny / Never Allow" |
| `DualTrackDecisionEngine` | Dual-track operation intent verification (semantic matching + screen fingerprint) |
| `AdUiGuard` | Ad / misleading UI detection and auto-interception |

### Audit & Monitoring

- **`AuditLogger`**: Full operation log, including time/action/params/result
- **Security level configuration**: Relaxed / Standard / Strict
- **Persistent rule management**: Revoke "Always Allow" / "Never Allow" settings anytime
- **Inbox**: Approval requests + push notifications unified management, batch mark-as-read

### Tool Security Guard

- Path traversal detection
- Sensitive file protection
- Principle of least privilege

---

## 8. Scheduled Tasks & Memory System

### Cron Scheduled Tasks

- Standard Cron expressions (second-level precision)
- Choose existing session or auto-create new one
- **Silent mode**: Background execution without user notification
- Supports manual trigger, pause, edit

### Memory System

```
Second Brain (Long-term Knowledge Base)
    │  Facts, preferences, procedures, social memory
    │  Supports search, ignore, manual edit
    │
    ├── Dream Memory Consolidation (Background processing)
    │   Analyze daily conversations → Extract key information
    │   → Integrate conflicting memories → Generate structured memory
    │
    ├── Context Compression (When token limit exceeded)
    │   Progressive compression of history → LLM summary
    │   → Inject compressed summary → Maintain conversation coherence
    │
    └── Auto Memory Search (Configurable)
        Auto-search relevant memories before sending message
        Configurable: search count / relevance threshold
```

- **Auto-rebuild vector index on startup**
- Memory type filtering: Fact / Preference / Procedure / Social
- Supports manual add / delete / ignore

---

## 9. Multi-Channel Integration

One agent, multiple entry points. External messages are uniformly routed to the agent for processing.

```
┌─────────┐  ┌──────────┐  ┌─────────┐
│Telegram │  │  WeChat   │  │ DingTalk │
└────┬────┘  └─────┬─────┘  └────┬────┘
     │             │             │
     └─────────────┼─────────────┘
                   ▼
           ┌──────────────┐
           │ ChannelManager│
           └──────┬───────┘
                  ▼
           ┌──────────────┐
           │    Agent      │
           │    Core       │
           └──────────────┘
```

Supported channels: Telegram / WeChat / DingTalk / Discord / Feishu / WhatsApp

---

## 10. Linux Subsystem

Built-in **PRoot**-based Linux environment, no root required.

- Terminal emulator, run bash / Python / Node.js
- Package manager support
- Environment variable management
- File system and workspace interop
- Agent can invoke shell tools to execute system commands
- Startup initialization UI shows environment detection progress (PRoot/Network/Dependencies)

---

## Technical Architecture

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

### Key Design Decisions

- **MVVM + Clean Architecture**: Clear UI / ViewModel / Core / Data layering
- **Koin DI**: Compile-time safe, no reflection dependency
- **Compose-based** declarative UI, full Material 3
- **Kotlin Coroutines + Flow**: Fully async, reactive data flow
- **ReAct Loop**: Standard Reasoning + Acting pattern, streaming output support
- **Per-Session Mutex**: Independent message processing lock per session, non-blocking
- **Object Pool Reuse**: GC-friendly, reduced memory churn
- **LLM Tool Routing**: On-demand tool schema injection, ~60% token savings for casual chat

---

## Quick Start

### Prerequisites

- Android 9.0+ (API 28+)
- Gradle 8.5+
- Kotlin 2.0+

### Build

```bash
git clone <your-repo-url>
cd hippy-agent
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
```

### Initialization

1. Install APK on Android device
2. Launch the app, complete the setup wizard
3. Configure model provider (OpenAI / Anthropic / Ollama etc.)
4. Create your first agent
5. Grant Accessibility Service permission (for phone control features)
6. Start chatting

---

## Project Structure

```
hippy-agent/
├── app/
│   └── src/main/kotlin/com/lin/hippyagent/
│       ├── HippyAgentApp.kt              # Application entry
│       ├── core/                         # Core engines
│       │   ├── agent/                    #   Agent engine (ReAct loop)
│       │   │   ├── collaboration/        #   Group collaboration / ACP / Private chat
│       │   │   └── session/              #   Session storage (Room DB)
│       │   ├── accessibility/            #   Accessibility framework
│       │   │   └── yolo/                 #   YOLO UI detection
│       │   ├── model/                    #   Model provider routing
│       │   ├── memory/                   #   Memory system
│       │   ├── channel/                  #   Multi-channel integration
│       │   ├── linux/                    #   Linux PRoot
│       │   ├── mission/                  #   Task management
│       │   ├── skill/                    #   Skill system
│       │   ├── notification/             #   Notification service
│       │   ├── permission/               #   Permission management
│       │   └── ...                       #
│       ├── data/                         # Data layer
│       ├── ui/                           # UI layer (Compose)
│       │   ├── chat/                     #   Chat interface
│       │   ├── conversation/             #   Conversation list
│       │   ├── agent/                    #   Agent configuration
│       │   ├── settings/                 #   System settings
│       │   ├── navigation/               #   Navigation system
│       │   └── components/               #   Common components
│       └── di/                           # Koin dependency injection
├── docs/                                 # Design documents
│   ├── design.md                         #   Full feature design
│   ├── competitive-analysis.md           #   Competitive analysis
│   └── designs/                          #   Specialized design docs
└── gradle/                               # Gradle configuration
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [`docs/design.md`](docs/design.md) | Complete feature design document (UI modules + system mechanisms) |
| [`docs/competitive-analysis.md`](docs/competitive-analysis.md) | Cross-project comparison with OpenClaw / Hermes / QwenPaw / gbrain / X-OmniClaw |
| [`docs/designs/`](docs/designs/) | 12 specialized design documents (mention mechanism / browser automation / group chat hooks etc.) |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | Changelog |
| [`docs/DefaultRules.md`](docs/DefaultRules.md) | Default rules system |

---

## Acknowledgments

HippyAgent would not exist without the inspiration and contributions of the following outstanding open-source projects. We pay our highest respects:

| Project | Description |
|---------|-------------|
| **[QwenPaw](https://github.com/agentscope-ai/QwenPaw)** | HippyAgent's genetic source. A personal AI assistant by the AgentScope team, providing core design concepts for skills, memory evolution, and multi-channel integration |
| **[OpenClaw](https://github.com/openclaw/openclaw)** | Personal AI assistant runtime. Its multi-channel inbox, skills ecosystem, Cron scheduling, and MCP integration deeply influenced HippyAgent's architecture |
| **[X-OmniClaw](https://github.com/OPPO-Mente-Lab/X-OmniClaw)** | A multimodal Android Agent by OPPO Mente Lab. Its dual-track perception, Omni Memory, and behavior-cloning skills provided important references for HippyAgent's phone control |
| **[Hermes Agent](https://github.com/NousResearch/hermes-agent)** | A self-improving AI agent by Nous Research. Its closed learning loop, self-improving skills, and memory system inspired HippyAgent's Dream memory consolidation and skill curation |
| **[GBrain](https://github.com/garrytan/gbrain)** | A knowledge brain by Garry Tan. Its self-wiring knowledge graph, hybrid retrieval, and memory consistency checking inspired HippyAgent's Second Brain memory system |
| **[HiClaw](https://github.com/higress-group/hiclaw)** | A multi-agent collaborative runtime platform by Alibaba Higress. Its Manager-Workers architecture, inter-agent communication, and security gateway design informed HippyAgent's group collaboration and ACP protocol |

*Without these outstanding open-source projects, HippyAgent would not exist today.*

---

<p align="center">
  <sub>Built with Kotlin + Compose • Runs on Your Phone • Driven by Your Rules</sub>
</p>
