$content = Get-Content README.md -Raw

# Add feature section after project status
$insert = @"

## 🔥 核心特色

### 🧑‍🤝‍🧑 多智能体群组协作 _(正在开发)_

HippyAgent 支持多个 AI 智能体在同一个群组中协作：
- **群组聊天** — 多个智能体可在同一会话中协同工作、互相讨论
- **@提及机制** — 使用 `@AgentID` 指定目标智能体，未指定时消息公开可见
- **上下文隔离** — @A 的消息不会被 B 看到，保证私有对话
- **智能路由** — 用户消息自动路由到相关智能体，支持链式对话

> 群组协作功能正在积极开发中，目前支持基本的消息路由和上下文过滤，实时触发和深度协作将在后续版本中完善。

### 📱 无障碍辅助操控 _(正在开发)_

HippyAgent 通过 Android AccessibilityService 让 AI Agent 能够像人一样操作手机：
- **屏幕识别** — 读取当前界面的无障碍树，理解 App 布局
- **手势执行** — 点击、滑动、输入、长按，模拟真实用户操作
- **跨 App 操控** — 打开任意 App、发送短信、获取位置、读取通知
- **后台持续运行** — Foreground Service 保活，切换 App 不中断

> 无障碍功能正在开发中，目前已实现基础的屏幕观察和交互能力，完整操控流程和审批机制将在后续版本中完善。

"@

# Insert after "欢迎反馈" paragraph
$content = $content -replace '(欢迎反馈。?\s*\n\s*\n---\s*\n\s*\n## 💡 项目愿景)', ('欢迎反馈。' + "`n" + $insert + "`n---`n`n## 💡 项目愿景")

Set-Content README.md -Value $content -Encoding UTF8
