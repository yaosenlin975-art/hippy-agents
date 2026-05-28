package com.lin.hippyagent.core.skill.builtin

import com.lin.hippyagent.core.skill.SkillInfo
import com.lin.hippyagent.core.skill.SkillManifest
import com.lin.hippyagent.core.skill.SkillTriggers
import com.lin.hippyagent.core.skill.SkillToolDef
import timber.log.Timber
import java.io.File

/**
 * Registry of built-in skills that ship with the app.
 * Scripts are documentation files describing each skill's capabilities.
 */
object BuiltinSkillRegistry {

    private val VERSION_FIELD_REGEX = Regex("""^version:\s*(.+)$""", RegexOption.MULTILINE)

    private val builtinSkills = listOf(
        BuiltinSkillDef(
            id = "news", name = "News",
            description = "Look up the latest news for the user from specified news sites. Provides authoritative URLs for politics, finance, society, world, tech, sports, and entertainment. Use the web_fetch tool (already available in your toolset) to open each URL and extract content, then summarize for the user.",
            version = "1.2",
            customBody = """---
name: news
description: "Look up the latest news for the user from specified news sites. Provides authoritative URLs for politics, finance, society, world, tech, sports, and entertainment. Use the web_fetch tool (already available in your toolset) to open each URL and extract content, then summarize for the user."
version: "1.2"
builtin: true
metadata:
  builtin_skill_version: "1.2"
  qwenpaw:
    emoji: "📰"
    requires: {}
---

# News Reference

When the user asks for "latest news", "what's in the news today", or "news in category X", use the **web_fetch** tool (already available in your toolset) with the categories and URLs below: fetch the page, then extract headlines and key points from the returned content and reply to the user.

## Categories and Sources

| Category      | Source                    | URL |
|---------------|---------------------------|-----|
| **Politics**  | People's Daily · CPC News | https://cpc.people.com.cn/ |
| **Finance**   | China Economic Net        | http://www.ce.cn/ |
| **Society**   | China News · Society      | https://www.chinanews.com/society/ |
| **World**     | CGTN                      | https://www.cgtn.com/ |
| **Tech**      | Science and Technology Daily | https://www.stdaily.com/ |
| **Sports**    | CCTV Sports               | https://sports.cctv.com/ |
| **Entertainment** | Sina Entertainment   | https://ent.sina.com.cn/ |

## How to Use (web_fetch)

1. **Clarify the user's need**: Determine which category or categories (politics / finance / society / world / tech / sports / entertainment), or pick 1-2 to fetch.
2. **Pick the URL**: Use the URL from the table for that category.
3. **Fetch the page**: Call **web_fetch** with:
   ```json
   {"url": "https://www.chinanews.com/society/"}
   ```
   Replace `url` with the corresponding URL from the table.
4. **Extract content**: Read the returned text content (headlines, dates, summaries).
5. **Summarize the reply**: Organize a short list (headline + one or two sentences + source) by time or importance; if a site is unreachable or times out, say so and suggest another source.

## Notes

- Page structure may change when sites are updated; if extraction fails, say so and suggest the user open the link directly.
- When visiting multiple categories, call `web_fetch` for each URL separately.
- You may include the original link in the reply so the user can open it.
"""
        ),
        BuiltinSkillDef(
            id = "himalaya", name = "Himalaya", description = "邮件管理，支持 IMAP/SMTP 邮件收发", version = "1.3.0",
            triggers = SkillTriggers(
                keywords = listOf("邮件", "email", "mail", "收件箱", "发邮件"),
                scenarios = listOf("email_management"),
                shouldUse = listOf("用户提到邮件相关操作", "需要收发、搜索、管理邮件"),
                shouldNotUse = listOf("非邮件场景")
            ),
            tools = listOf(
                SkillToolDef(
                    name = "himalaya_email",
                    description = "Email management: list, read, search, send emails via IMAP/SMTP"
                )
            ),
            customBody = """---
name: Himalaya
description: "邮件管理，支持 IMAP/SMTP 邮件收发"
version: 1.3.0
builtin: true
tools:
  - himalaya_email
keywords:
  - "邮件"
  - "email"
  - "mail"
  - "收件箱"
  - "发邮件"
---

# Himalaya 邮件管理技能

通过 himalaya CLI 管理 IMAP/SMTP 邮件。本技能在 Android 环境中通过 ALinux (PRoot) 容器运行 himalaya 二进制。

## 前置条件

1. **ALinux 环境已安装**：设置 → 环境检测 → 一键安装（首次需下载 ~80MB rootfs）
2. **himalaya 已安装**：环境检测页面可一键安装 himalaya 到 ALinux 容器
3. **邮箱账号已配置**：`~/.config/himalaya/config.toml`（容器内路径）

## 可用操作

调用 `himalaya_email` 工具，通过 `action` 参数指定操作：

### list_emails — 列出邮件
- `folder`: 邮件文件夹，默认 INBOX
- `limit`: 最大数量，默认 20
- `account`: 账户名（多账户时使用）

### read_email — 读取邮件
- `email_id`: 邮件 ID（必填）
- `folder`: 邮件文件夹，默认 INBOX

### search_emails — 搜索邮件
- `query`: 搜索关键词（必填）
- `folder`: 邮件文件夹，默认 INBOX

### send_email — 发送邮件
- `to`: 收件人地址（必填）
- `subject`: 邮件主题（必填）
- `body`: 邮件正文（必填）

## 配置示例（Gmail）

```toml
[accounts.gmail]
email = "you@gmail.com"
display-name = "Your Name"
default = true

backend.type = "imap"
backend.host = "imap.gmail.com"
backend.port = 993
backend.encryption.type = "tls"
backend.login = "you@gmail.com"
backend.auth.type = "password"
backend.auth.cmd = "echo 'YOUR_APP_PASSWORD'"

message.send.backend.type = "smtp"
message.send.backend.host = "smtp.gmail.com"
message.send.backend.port = 587
message.send.backend.encryption.type = "start-tls"
message.send.backend.login = "you@gmail.com"
message.send.backend.auth.type = "password"
message.send.backend.auth.cmd = "echo 'YOUR_APP_PASSWORD'"

folder.aliases.inbox = "INBOX"
folder.aliases.sent = "[Gmail]/Sent Mail"
folder.aliases.drafts = "[Gmail]/Drafts"
folder.aliases.trash = "[Gmail]/Trash"
```

## Android 环境注意事项

- himalaya 运行在 ALinux (PRoot) 容器中，配置文件路径为容器内 `~/.config/himalaya/config.toml`
- Gmail 需要开启 2FA 并生成应用专用密码，不支持直接使用账号密码
- 文件附件暂不支持，正文仅支持纯文本
- `folder.aliases` 必须使用 v1.2.0+ 语法（`folder.aliases.X = "..."`），旧版 `[accounts.NAME.folder.alias]` 语法会被静默忽略导致发送失败
- 容器内网络通过 PRoot 转发，如遇连接超时请检查宿主网络状态
"""
        ),
        BuiltinSkillDef(
            id = "channel_message", name = "Channel Message", description = "频道消息发送，主动向会话或频道发送单向消息", version = "1.2.0",
            triggers = SkillTriggers(
                keywords = listOf("频道", "channel", "通知", "推送", "发消息给"),
                scenarios = listOf("notification", "messaging"),
                shouldUse = listOf("用户要求向其他频道或会话发送消息", "需要主动推送通知到外部渠道"),
                shouldNotUse = listOf("当前对话回复", "群聊内@其他智能体")
            ),
            customBody = """---
name: Channel Message
description: "频道消息发送，主动向会话或频道发送单向消息"
version: "1.2.0"
builtin: true
keywords:
  - "频道"
  - "channel"
  - "通知"
  - "推送"
scenarios:
  - "notification"
  - "messaging"
---

# Channel Message

向已绑定的外部频道（微信、Telegram、飞书、WhatsApp 等）发送单向消息。

## 支持的频道类型

| 频道 | 说明 | 配置方式 |
|------|------|----------|
| 微信 | 通过个人微信发送消息 | 设置 → 频道 → 微信扫码绑定 |
| Telegram | 通过 Telegram Bot 发送 | 设置 → 频道 → 填入 Bot Token + Chat ID |
| 飞书 | 通过飞书 Bot 发送 | 设置 → 频道 → 填入 App ID + App Secret |
| WhatsApp | 通过 WhatsApp Business API | 设置 → 频道 → 填入 Phone Number ID + Token |
| 本地频道 | 应用内频道消息 | 自动创建，无需配置 |

## 使用方式

本技能为纯提示词技能，不提供独立工具。智能体通过 `channelManager` 向已绑定的频道发送消息：

1. **确认目标频道**：用户说"发消息给微信"或"通知 Telegram 频道"
2. **构造消息内容**：根据用户需求编写消息文本
3. **发送消息**：调用频道发送接口，消息以智能体身份发出

## 注意事项

- 频道消息是**单向推送**，不等待回复
- 发送前确认目标频道已绑定且在线
- 消息内容应简洁明了，适合目标频道的展示格式
- 微信频道受限于个人号消息频率，避免高频发送
- 如果频道未绑定，告知用户先在设置中完成频道绑定
"""
        ),
        BuiltinSkillDef(
            id = "pdf", name = "PDF Reader", description = "读取 PDF 文件内容并提取文本。从 PDF 内部文本对象中提取文字内容，支持简单未压缩文本 PDF。不支持扫描件/图片 PDF（无 OCR）、压缩流、加密文档和复杂排版。提取失败时应告知用户该文件无法自动读取。", version = "1.2.0",
            tools = listOf(SkillToolDef("read_pdf", "读取 PDF 文件内容并提取文本")),
            triggers = SkillTriggers(
                keywords = listOf("pdf", "PDF", "读取pdf", "read pdf"),
                fileExtensions = listOf("pdf"),
                scenarios = listOf("document_reading"),
                shouldUse = listOf("用户需要读取 PDF 文件内容"),
                shouldNotUse = listOf("非 PDF 文件")
            ),
            customBody = """---
name: PDF Reader
description: "读取 PDF 文件内容并提取文本。从 PDF 内部文本对象中提取文字内容，支持简单未压缩文本 PDF。不支持扫描件/图片 PDF（无 OCR）、压缩流、加密文档和复杂排版。提取失败时应告知用户该文件无法自动读取。"
version: "1.2.0"
builtin: true
tools:
  - read_pdf
keywords:
  - "pdf"
  - "PDF"
file_extensions:
  - "pdf"
---

# PDF Reader

读取 PDF 文件内容并提取文本。从 PDF 内部文本对象中提取文字内容。

## 适用场景

- 用户需要查看 PDF 文档的文本内容
- 需要从 PDF 中提取文字用于后续处理
- 分析 PDF 文档结构（纯文本布局）

## 限制说明

- **仅支持简单文本 PDF**：使用正则匹配 PDF 内部文本对象，无法处理压缩流、编码字体、加密文档
- **不支持扫描件/图片 PDF**：无法进行 OCR 识别，图片型 PDF 需要 OCR 工具
- **复杂排版可能丢失格式**：表格、多栏布局等无法正确还原，提取结果可能是无序文本
- 如果提取失败，工具会返回占位提示，应告知用户该文件无法自动提取，建议使用其他工具或手动处理

## 使用方式

调用 `read_pdf` 工具，传入文件路径即可。路径可以是绝对路径或相对于工作区的路径。
"""
        ),
        BuiltinSkillDef(
            id = "docx", name = "Word Reader", description = "读取 Word (.docx) 文件内容并提取纯文本。从 word/document.xml 的 <w:t> 标签中提取文字。仅支持 .docx 格式（Office Open XML），不支持旧版 .doc、图片、图表和嵌入对象。段落按 80 字符切行近似还原。", version = "1.2.0",
            tools = listOf(SkillToolDef("read_docx", "读取 Word (.docx) 文件内容并提取文本")),
            triggers = SkillTriggers(
                keywords = listOf("word", "docx", "Word", "读取word", "read word"),
                fileExtensions = listOf("docx", "doc"),
                scenarios = listOf("document_reading"),
                shouldUse = listOf("用户需要读取 Word 文件内容"),
                shouldNotUse = listOf("非 Word 文件")
            ),
            customBody = """---
name: Word Reader
description: "读取 Word (.docx) 文件内容并提取纯文本。从 word/document.xml 的 <w:t> 标签中提取文字。仅支持 .docx 格式（Office Open XML），不支持旧版 .doc、图片、图表和嵌入对象。段落按 80 字符切行近似还原。"
version: "1.2.0"
builtin: true
tools:
  - read_docx
keywords:
  - "word"
  - "docx"
file_extensions:
  - "docx"
  - "doc"
---

# Word Reader

读取 Word (.docx) 文件内容并提取纯文本。

## 适用场景

- 用户需要查看 Word 文档的文字内容
- 从文档中提取正文用于摘要或分析
- 批量处理多个文档提取关键信息

## 限制说明

- **仅支持 .docx 格式**（Office Open XML），不支持旧版 .doc 格式
- **提取纯文本内容**：从 word/document.xml 中提取 `<w:t>` 标签文本，不保留字体、颜色、大小等格式信息
- **不支持图片/嵌入对象**：仅提取文本，忽略图片、图表、SmartArt、嵌入对象
- **段落分割为近似处理**：按 80 字符切行，非精确段落还原，可能丢失原始换行结构

## 使用方式

调用 `read_docx` 工具，传入文件路径即可。路径可以是绝对路径或相对于工作区的路径。
"""
        ),
        BuiltinSkillDef(
            id = "xlsx", name = "Excel Reader", description = "读取 Excel (.xlsx) 文件内容并提取表格数据。从工作表中提取字符串、数值、布尔值等基本单元格类型，正确处理共享字符串表。仅支持 .xlsx 格式，不支持公式计算结果、图表、宏和 VBA。超大文件解析可能较慢。", version = "1.2.0",
            tools = listOf(SkillToolDef("read_xlsx", "读取 Excel (.xlsx) 文件内容并提取表格数据")),
            triggers = SkillTriggers(
                keywords = listOf("excel", "xlsx", "Excel", "表格", "读取excel"),
                fileExtensions = listOf("xlsx", "xls"),
                scenarios = listOf("spreadsheet_reading"),
                shouldUse = listOf("用户需要读取 Excel 文件内容"),
                shouldNotUse = listOf("非 Excel 文件")
            ),
            customBody = """---
name: Excel Reader
description: "读取 Excel (.xlsx) 文件内容并提取表格数据。从工作表中提取字符串、数值、布尔值等基本单元格类型，正确处理共享字符串表。仅支持 .xlsx 格式，不支持公式计算结果、图表、宏和 VBA。超大文件解析可能较慢。"
version: "1.2.0"
builtin: true
tools:
  - read_xlsx
keywords:
  - "excel"
  - "xlsx"
file_extensions:
  - "xlsx"
  - "xls"
---

# Excel Reader

读取 Excel (.xlsx) 文件内容并提取表格数据。

## 适用场景

- 用户需要查看 Excel 电子表格内容
- 从表格中提取数据用于分析或汇总
- 读取指定工作表的行列数据

## 限制说明

- **仅支持 .xlsx 格式**（Office Open XML），不支持旧版 .xls 格式
- **支持基本单元格类型**：字符串、数值、布尔值；不支持公式计算结果（显示的是公式本身而非计算结果）
- **共享字符串表**：正确处理 xl/sharedStrings.xml 中的共享字符串引用
- **不支持图表/宏/VBA**：仅提取工作表中的单元格数据，忽略图表和宏
- **大文件性能**：使用正则解析 XML，超大文件（数百列/万行以上）可能解析较慢

## 使用方式

调用 `read_xlsx` 工具，传入文件路径即可。路径可以是绝对路径或相对于工作区的路径。
"""
        ),
        BuiltinSkillDef(
            id = "pptx", name = "PowerPoint Reader", description = "读取 PowerPoint (.pptx) 文件内容并提取幻灯片文本。从 ppt/slides/ 按编号顺序提取 <a:t> 标签文本。仅支持 .pptx 格式，不支持旧版 .ppt、图片、动画和嵌入对象。每张幻灯片文本按编号顺序输出。", version = "1.2.0",
            tools = listOf(SkillToolDef("read_pptx", "读取 PowerPoint (.pptx) 文件内容并提取幻灯片文本")),
            triggers = SkillTriggers(
                keywords = listOf("ppt", "pptx", "PPT", "PowerPoint", "演示文稿"),
                fileExtensions = listOf("pptx", "ppt"),
                scenarios = listOf("presentation_reading"),
                shouldUse = listOf("用户需要读取 PPT 文件内容"),
                shouldNotUse = listOf("非 PPT 文件")
            ),
            customBody = """---
name: PowerPoint Reader
description: "读取 PowerPoint (.pptx) 文件内容并提取幻灯片文本。从 ppt/slides/ 按编号顺序提取 <a:t> 标签文本。仅支持 .pptx 格式，不支持旧版 .ppt、图片、动画和嵌入对象。每张幻灯片文本按编号顺序输出。"
version: "1.2.0"
builtin: true
tools:
  - read_pptx
keywords:
  - "ppt"
  - "pptx"
file_extensions:
  - "pptx"
  - "ppt"
---

# PowerPoint Reader

读取 PowerPoint (.pptx) 文件内容并提取幻灯片文本。

## 适用场景

- 用户需要查看演示文稿的文字内容
- 从 PPT 中提取所有幻灯片文本用于摘要
- 获取特定幻灯片的备注或正文

## 限制说明

- **仅支持 .pptx 格式**（Office Open XML），不支持旧版 .ppt 格式
- **仅提取文本内容**：从幻灯片 XML 中提取 `<a:t>` 标签文本，不保留布局、字体、颜色等样式信息
- **不支持图片/动画/嵌入对象**：仅提取文本，忽略图片、动画、图表、音视频等
- **幻灯片按编号排序**：从 ppt/slides/slide1.xml 开始依次提取，输出时标注幻灯片编号

## 使用方式

调用 `read_pptx` 工具，传入文件路径即可。路径可以是绝对路径或相对于工作区的路径。
"""
        ),
        BuiltinSkillDef(
            id = "guidance", name = "Guidance", description = "引导助手，搜索本地文档获取安装配置指南", version = "1.1.0",
            tools = listOf(SkillToolDef("guidance", "搜索本地文档获取安装配置指南")),
            triggers = SkillTriggers(
                keywords = listOf("帮助", "help", "指南", "guide", "配置", "安装", "怎么用"),
                scenarios = listOf("help", "onboarding"),
                shouldUse = listOf("用户询问如何安装、配置或使用应用"),
                shouldNotUse = listOf("非帮助类问题")
            ),
            customBody = """---
name: Guidance
description: "引导助手，搜索本地文档获取安装配置指南"
version: "1.1.0"
builtin: true
tools:
  - guidance
keywords:
  - "帮助"
  - "help"
  - "指南"
  - "guide"
  - "配置"
  - "安装"
---

# Guidance 引导助手

搜索 HippyAgent 内置文档，回答安装、配置、使用相关问题。

## 使用方式

调用 `guidance` 工具，传入 `query` 参数搜索本地文档：

- `query`: 搜索关键词，如"安装"、"模型配置"、"心跳"、"ALinux"

## 覆盖主题

| 主题 | 关键词 | 内容 |
|------|--------|------|
| 安装指南 | installation, 安装 | APK 安装、首次配置、环境检测 |
| 模型配置 | model, 模型 | API Key、供应商、故障转移 |
| 智能体配置 | agent, 智能体 | 核心文件、技能分配、群组聊天 |
| 技能管理 | skill, 技能 | 安装、启用/禁用、技能列表 |
| 心跳与定时 | heartbeat, 心跳 | 心跳 vs Cron 对比、HEARTBEAT.md |
| ALinux 环境 | environment, linux | PRoot 容器、包安装、命令执行 |
| 记忆管理 | memory, 记忆 | MEMORY.md 长期记忆 |
| 外部存储 | storage, 存储 | SAF 目录、数据迁移 |
| 核心文件 | corefiles | SOUL.md, RULES.md, AGENTS.md 等 |

## 回复策略

1. 优先从本地文档提取精确答案
2. 如本地文档未覆盖，建议用户访问 https://docs.hippyagent.com
3. 配置类问题给出具体操作路径（如"设置 → 模型提供商"）
"""
        ),
        BuiltinSkillDef(
            id = "qa_source_index", name = "QA Source Index", description = "知识库索引，按关键词搜索源代码和文档路径", version = "1.1.0",
            tools = listOf(SkillToolDef("qa_source_index", "按关键词搜索知识库索引")),
            triggers = SkillTriggers(
                keywords = listOf("知识库", "索引", "搜索", "qa", "QA", "问答"),
                scenarios = listOf("knowledge_search"),
                shouldUse = listOf("用户需要搜索知识库或代码索引", "需要定位特定功能的源码位置"),
                shouldNotUse = listOf("一般对话", "非代码/文档定位需求")
            ),
            customBody = """---
name: QA Source Index
description: "知识库索引，按关键词搜索源代码和文档路径"
version: "1.1.0"
builtin: true
tools:
  - qa_source_index
keywords:
  - "知识库"
  - "索引"
  - "搜索"
  - "qa"
  - "QA"
---

# QA Source Index 知识库索引

按关键词搜索 HippyAgent 源代码和文档路径，快速定位相关模块。

## 使用方式

调用 `qa_source_index` 工具，传入 `keyword` 参数：

- `keyword`: 搜索关键词，如"agent"、"会话"、"模型"、"工具"

## 索引覆盖范围

| 类别 | 关键词示例 | 涉及文件 |
|------|-----------|----------|
| Agent 系统 | agent, agent创建, agent配置, agent群组 | Agent.kt, AgentFactory.kt, AgentProfile.kt |
| 会话系统 | session, 会话, 消息 | SessionStore.kt, SessionMessage.kt |
| 工具系统 | tool, 工具, 工具审批 | ToolRegistry.kt, ToolGuardian.kt |
| 技能系统 | skill, 技能 | SkillManager.kt, BuiltinSkillRegistry.kt |
| 模型系统 | model, 模型, provider | ModelClient.kt, ModelProviderStore.kt |
| 记忆系统 | memory, 记忆 | MemoryManager.kt |
| 存储系统 | storage, 存储, 备份 | StorageManager.kt, BackupManager.kt |
| 通知系统 | notification, 通知 | NotificationService.kt |
| 安全系统 | security, 安全 | ToolGuardian.kt, SkillScanner.kt |
| 渠道系统 | channel, 渠道 | TelegramChannel.kt |
| 定时任务 | heartbeat, 心跳 | HeartbeatConfig.kt |
| UI 页面 | 设置, 聊天, 登录 | 各 Screen.kt |
| Android 特定 | 权限, 后台, 前台服务 | AndroidManifest.xml, AgentForegroundService.kt |
| 核心文件模板 | rules, soul, profile, bootstrap | assets/templates/*.md |

## 回复策略

1. 返回匹配的源码路径列表
2. 如无匹配，列出所有可用关键词供用户选择
3. 可配合 `guidance` 工具获取对应主题的详细文档
"""
        ),
        BuiltinSkillDef(
            id = "image_generate", name = "Image Generate", description = "根据文本描述生成图片，支持多种生图模型", version = "1.1.0",
            tools = listOf(SkillToolDef("image_generate", "根据文本描述生成图片")),
            triggers = SkillTriggers(
                keywords = listOf("生成图片", "画图", "生图", "generate image", "create image", "图片生成"),
                scenarios = listOf("image_generation"),
                shouldUse = listOf("用户要求生成或创建图片"),
                shouldNotUse = listOf("非图片生成请求", "图片编辑/修改")
            ),
            customBody = """---
name: Image Generate
description: "根据文本描述生成图片，支持多种生图模型"
version: "1.1.0"
builtin: true
tools:
  - image_generate
keywords:
  - "生成图片"
  - "画图"
  - "生图"
  - "generate image"
  - "create image"
scenarios:
  - "image_generation"
---

# Image Generate 图片生成

根据文本描述生成图片，通过 OpenRouter API 调用多种生图模型。

## 使用方式

调用 `image_generate` 工具：

- `prompt`（必填）: 图片内容描述，建议使用英文以获得更好效果
- `model`（可选）: 生图模型，默认 `google/gemini-2.5-flash-image`
- `aspect_ratio`（可选）: 图片比例 — `square`(方形)、`landscape`(横屏)、`portrait`(竖屏)

## 推荐模型

| 模型 | 适合场景 | 备注 |
|------|----------|------|
| google/gemini-2.5-flash-image | 通用生图、快速迭代 | 默认模型，速度快 |
| openai/gpt-5-image-mini | 文字渲染、海报、信息图 | 文字清晰度高 |
| bytedance-seed/seedream-4.5 | 创意设计、风格化 | 亚洲风格优化 |

## 前置条件

- 需要在「设置 → 模型提供商」中配置 **OpenRouter** API Key
- 如果未配置，工具会返回提示信息

## 生成流程

1. 理解用户需求，构造详细 prompt（主体 + 风格 + 构图 + 光影）
2. 选择合适的模型和比例
3. 调用 `image_generate` 工具
4. 工具返回图片本地路径，在回复中包含 `[附件: 路径]` 以便用户查看

## 注意事项

- prompt 越详细，生成效果越好：描述主体、风格、构图、色调、光影
- 图片保存到智能体工作区的 `media/` 目录
- 生成耗时约 10-30 秒，取决于模型和网络
- 不支持图片编辑/修改，仅支持从文本生成新图片
"""
        ),
    )

    fun getBuiltinSkills(): List<BuiltinSkillDef> = builtinSkills
    fun getBuiltinSkill(id: String): BuiltinSkillDef? = builtinSkills.find { it.id == id }

    fun installBuiltinSkills(skillsDir: File): List<SkillInfo> {
        skillsDir.mkdirs()
        val installed = mutableListOf<SkillInfo>()

        for (skill in builtinSkills) {
            val skillDir = File(skillsDir, skill.id)
            val isNewInstall = !skillDir.exists()
            skillDir.mkdirs()

            // 版本比较：仅在内置版本高于已安装版本时覆盖，避免覆盖用户修改
            val skillMd = skillDir.resolve("SKILL.md")
            val shouldOverwrite = isNewInstall || shouldUpdateSkill(skillMd, skill.version)

            if (shouldOverwrite) {
                val content = skill.customBody ?: buildSkillMd(skill)
                skillMd.writeText(content)
                Timber.d("Built-in skill ${if (isNewInstall) "installed" else "updated"}: ${skill.id} v${skill.version}")
            } else {
                Timber.d("Built-in skill skipped (user version >= builtin): ${skill.id}")
            }

            installed.add(SkillInfo(
                id = skill.id,
                name = skill.name,
                description = skill.description,
                version = skill.version,
                isEnabled = true,
                installedAt = System.currentTimeMillis(),
                scripts = skill.scripts,
                assets = emptyList(),
                isBuiltin = true
            ))
        }
        return installed
    }

    /**
     * 从已有 SKILL.md 的 frontmatter 中读取版本号，与内置版本比较。
     * 仅在内置版本更高时返回 true（需要更新）。
     */
    private fun shouldUpdateSkill(skillMd: File, builtinVersion: String): Boolean {
        if (!skillMd.exists()) return true
        return try {
            val content = skillMd.readText()
            // 从 YAML frontmatter 提取 version 字段
            val versionRegex = VERSION_FIELD_REGEX
            val match = versionRegex.find(content)
            val installedVersion = match?.groupValues?.get(1)?.trim() ?: return true // 无法解析版本 → 覆盖
            compareVersions(builtinVersion, installedVersion) > 0
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse skill version from ${skillMd.absolutePath}")
            true // 解析失败 → 安全覆盖
        }
    }

    /**
     * 简单语义版本比较。返回正数表示 v1 > v2，负数表示 v1 < v2，0 表示相等。
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    private fun buildSkillMd(skill: BuiltinSkillDef): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("name: ${skill.name}")
        sb.appendLine("description: ${skill.description}")
        sb.appendLine("version: ${skill.version}")
        sb.appendLine("builtin: true")
        if (skill.tools.isNotEmpty()) {
            sb.appendLine("tools:")
            skill.tools.forEach { sb.appendLine("  - ${it.name}") }
        }
        if (skill.triggers.keywords.isNotEmpty()) {
            sb.appendLine("keywords:")
            skill.triggers.keywords.forEach { sb.appendLine("  - \"$it\"") }
        }
        if (skill.triggers.fileExtensions.isNotEmpty()) {
            sb.appendLine("file_extensions:")
            skill.triggers.fileExtensions.forEach { sb.appendLine("  - \"$it\"") }
        }
        if (skill.triggers.scenarios.isNotEmpty()) {
            sb.appendLine("scenarios:")
            skill.triggers.scenarios.forEach { sb.appendLine("  - \"$it\"") }
        }
        if (skill.triggers.shouldUse.isNotEmpty()) {
            sb.appendLine("should_use:")
            skill.triggers.shouldUse.forEach { sb.appendLine("  - \"$it\"") }
        }
        if (skill.triggers.shouldNotUse.isNotEmpty()) {
            sb.appendLine("should_not_use:")
            skill.triggers.shouldNotUse.forEach { sb.appendLine("  - \"$it\"") }
        }
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("# ${skill.name}")
        sb.appendLine()
        sb.appendLine(skill.description)
        sb.appendLine()
        if (skill.tools.isNotEmpty()) {
            sb.appendLine("## Tools")
            sb.appendLine()
            skill.tools.forEach { t ->
                sb.appendLine("- **${t.name}**: ${t.description}")
            }
            sb.appendLine()
        }
        if (skill.triggers.shouldUse.isNotEmpty() || skill.triggers.shouldNotUse.isNotEmpty()) {
            sb.appendLine("## Decision Rules")
            sb.appendLine()
            if (skill.triggers.shouldUse.isNotEmpty()) {
                sb.appendLine("**Use this skill when:**")
                skill.triggers.shouldUse.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
            if (skill.triggers.shouldNotUse.isNotEmpty()) {
                sb.appendLine("**Do NOT use this skill when:**")
                skill.triggers.shouldNotUse.forEach { sb.appendLine("- $it") }
                sb.appendLine()
            }
        }
        if (skill.scripts.isNotEmpty()) {
            sb.appendLine("## Scripts")
            sb.appendLine()
            skill.scripts.forEach { sb.appendLine("- scripts/$it") }
            sb.appendLine()
        }
        return sb.toString()
    }
}

data class BuiltinSkillDef(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val scripts: List<String> = emptyList(),
    val tools: List<SkillToolDef> = emptyList(),
    val triggers: SkillTriggers = SkillTriggers(),
    /** 自定义 SKILL.md 全文，非 null 时优先于 buildSkillMd() 生成 */
    val customBody: String? = null
)


