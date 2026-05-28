package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber

/**
 * 引导助手技能 - 回答 HippyAgent 安装与配置问题
 * 使用内联指南文档，无需文件系统
 */
class GuidanceSkill(private val context: Context) {

    /** 核心文件体系说明 */
    private val coreFileGuide = """
## 核心文件体系

每个智能体工作区（workspaces/{agentId}/）包含以下核心文件：

| 文件 | 用途 | 默认加载 |
|------|------|----------|
| SOUL.md | 核心人格与行为原则 | ✅ |
| RULES.md | 铁律（冲突解决、安全操作、环境自愈） | ✅ |
| AGENTS.md | 行为规则（内部/外部操作、工具、心跳） | ✅ |
| PROFILE.md | 智能体身份 + 用户资料 | ✅ |
| MEMORY.md | 长期记忆（工具设置、经验日志） | ❌ |
| HEARTBEAT.md | 心跳任务清单 | 按心跳配置 |
| BOOTSTRAP.md | 首次启动引导脚本 | 仅首次 |

核心文件由智能体在引导模式中创建和编辑。用户也可以直接编辑这些文件来定制智能体行为。
    """.trimIndent()

    /** 可用技能列表 */
    private val skillGuide = """
## 可用技能

| 技能 | 说明 |
|------|------|
| cron | 定时任务（精确调度，如"每天9:00"） |
| pdf | PDF 文本提取（支持简单文本 PDF，不支持扫描件/加密文档） |
| docx | Word 文档纯文本提取（仅支持 .docx 格式） |
| xlsx | Excel 表格数据提取（仅支持 .xlsx，不支持公式结果） |
| pptx | PowerPoint 幻灯片文本提取（仅支持 .pptx 格式） |
| news | 新闻获取与摘要 |
| himalaya | 邮件管理（IMAP/SMTP） |
| web_fetch | 网页访问（OkHttp+WebView，无需Chromium） |
| channel_message | 频道消息推送 |
| chat_with_agent | 智能体间对话 |
| make_plan | 向更强模型请求执行计划 |
| file_reader | 多格式文件内容提取 |
| multi_agent_collaboration | 多智能体任务分配与汇总 |
| QA_source_index | 知识库索引与语义搜索 |
| alinux | Linux 容器环境（Ubuntu 24.04 arm64） |
| guidance | 本引导系统 |

技能安装: 设置 → 公共技能池 → 安装
技能分配: 智能体 → 技能抽屉 → 启用/禁用
    """.trimIndent()

    /** 心跳与 Cron 对比 */
    private val heartbeatVsCronGuide = """
## 心跳 vs Cron

| 场景 | 用心跳 | 用 Cron |
|------|--------|---------|
| 合并多个定期检查 | ✅ | ❌ |
| 需要对话上下文 | ✅ | ❌ |
| 精确时间触发 | ❌ | ✅ |
| 一次性提醒 | ❌ | ✅ |
| 减少API调用 | ✅ | ❌ |

心跳配置: 智能体设置 → 心跳 → 设定间隔或 cron 表达式
编辑 HEARTBEAT.md 添加定期检查清单
    """.trimIndent()

    /** 环境配置 */
    private val envGuide = """
## ALinux 环境配置

Hippy 内置 PRoot 容器，提供完整 Ubuntu 24.04 arm64 环境。

- 首次使用自动下载 rootfs (~80MB)，需网络连接
- Linux 环境与 Android 文件系统隔离，通过 /shared 目录共享文件
- 安装包: `install_package(package_name="python3")`
- 执行命令: `execute_bash(command="ls -la")`
- Python 执行: `execute_python(script="print('hello')")`
- 安装大型包 (python3, nodejs) 需约 200MB 额外空间
- 长时间运行命令建议设置 timeout 参数

环境检测: 设置 → 环境检测 → 一键安装与验证
    """.trimIndent()

    /** 记忆管理 */
    private val memoryGuide = """
## 记忆管理

- **MEMORY.md**: 长期记忆，手动更新
- 工具设置、用户偏好、经验教训写入 MEMORY.md
- 每次对话结束前检查：
  1. 关键信息写入记忆了吗？
  2. 用户需要跟进什么？
  3. 有破坏性操作需要记录吗？
- 心跳周期内可主动更新记忆
    """.trimIndent()

    /**
     * 搜索本地文档
     */
    private val inlineDocs: Map<String, String> by lazy {
        mapOf(
            "heartbeat" to heartbeatVsCronGuide,
            "memory" to memoryGuide,
            "environment" to envGuide,
            "corefiles" to coreFileGuide,
            "skill" to skillGuide,
            "installation" to getInstallationGuide().getOrDefault(""),
            "config_model" to getConfigGuide("model").getOrDefault(""),
            "config_agent" to getConfigGuide("agent").getOrDefault(""),
            "config_skill" to getConfigGuide("skill").getOrDefault(""),
            "config_heartbeat" to getConfigGuide("heartbeat").getOrDefault(""),
            "config_storage" to getConfigGuide("storage").getOrDefault("")
        )
    }

    fun searchDocs(query: String): Result<String> {
        return try {
            val queryLower = query.lowercase()
            val results = mutableListOf<String>()

            inlineDocs.forEach { (name, content) ->
                if (content.lowercase().contains(queryLower)) {
                    val paragraphs = content.split("\n\n")
                    for (para in paragraphs) {
                        if (para.lowercase().contains(queryLower)) {
                            results.add("📄 $name:\n${para.trim().take(500)}")
                        }
                    }
                }
            }

            if (results.isNotEmpty()) {
                Result.success("找到 ${results.size} 个相关结果:\n\n${results.joinToString("\n\n---\n\n")}")
            } else {
                Result.success("未找到与「$query」相关的内容。\n可参考官网文档: https://docs.hippyagent.com")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to search docs")
            Result.failure(e)
        }
    }

    /**
     * 获取安装指南
     */
    fun getInstallationGuide(): Result<String> {
        val guide = """
# Hippy 安装指南

## 基本安装
1. 下载 APK 文件
2. 在 Android 设备上安装（允许未知来源）
3. 启动应用，按引导完成初始配置

## 首次配置
1. 进入「设置 → 模型提供商」添加 API Key
2. 选择默认模型
3. 默认智能体自动创建，BOOTSTRAP.md 引导你定义智能体身份

## ALinux 环境（可选）
1. 设置 → 环境检测 → 一键安装
2. 首次安装需下载 rootfs (~80MB)
3. 安装后可执行 bash/python/node 等命令

## 外部存储（可选）
1. 设置 → 数据存储 → 选择 SAF 目录
2. 应用会检测外部存储是否有历史数据
3. 可选择保留现有/合并/覆盖

## 常见问题
- Q: 无法连接模型？ A: 检查 API Key 和网络连接，确认模型提供商地址正确
- Q: 后台运行被杀死？ A: 电池优化白名单 + 关闭应用电池优化
- Q: 环境安装失败？ A: 重启应用后重新检测，ALinux 首次需下载 rootfs
- Q: BOOTSTRAP 反复出现？ A: 已修复，更新后不再重复引导
- Q: 技能丢失？ A: 检查技能抽屉启用状态，更新不会覆盖用户修改
        """.trimIndent()
        return Result.success(guide)
    }

    /**
     * 获取配置说明
     */
    fun getConfigGuide(topic: String = ""): Result<String> {
        val guides = mapOf(
            "model" to """
## 模型配置
- 支持 OpenAI、Anthropic、Ollama 等兼容协议
- 在「设置 → 模型提供商」中添加供应商和 API Key
- 每个智能体可独立选择模型
- 支持故障转移：配置多个模型，主模型失败时自动切换
            """.trimIndent(),
            "agent" to """
## 智能体配置
- 每个智能体有独立的核心文件（RULES.md, SOUL.md 等）
- 核心文件决定智能体的行为和人格
- 支持为智能体分配不同的技能
- 可配置最大迭代次数、上下文长度等参数
- 群组聊天支持多个智能体在同一会话中协作
            """.trimIndent(),
            "skill" to """
## 技能管理
$skillGuide
            """.trimIndent(),
            "heartbeat" to """
## 心跳配置
$heartbeatVsCronGuide
            """.trimIndent(),
            "corefiles" to coreFileGuide,
            "environment" to envGuide,
            "memory" to memoryGuide,
            "storage" to """
## 外部存储配置
- 设置 → 数据存储 → 选择 SAF 目录
- 支持 Android 存储访问框架 (SAF)
- 迁移时提供三选一：保留现有/合并/保留选择位置
- 合并模式: 以较新文件为准，递归合并目录
            """.trimIndent()
        )

        val guide = if (topic.isNotEmpty()) {
            guides[topic.lowercase()] ?: "未知主题「$topic」。可用主题: ${guides.keys.joinToString(", ")}"
        } else {
            buildString {
                appendLine("# Hippy 配置指南\n")
                guides.forEach { (key, value) ->
                    appendLine(value)
                    appendLine()
                }
            }
        }

        return Result.success(guide)
    }

    /**
     * 获取帮助信息
     */
    fun getHelp(): Result<String> {
        return Result.success(buildString {
            appendLine("# Hippy 帮助\n")
            appendLine("## 可用命令")
            appendLine("- search <关键词>: 搜索本地文档")
            appendLine("- install: 获取安装指南")
            appendLine("- config [主题]: 获取配置说明")
            appendLine("- help: 显示此帮助信息")
            appendLine()
            appendLine("## 配置主题列表")
            appendLine("- model: 模型配置")
            appendLine("- agent: 智能体配置")
            appendLine("- skill: 技能管理")
            appendLine("- heartbeat: 心跳与定时任务")
            appendLine("- corefiles: 核心文件体系")
            appendLine("- environment: ALinux 环境配置")
            appendLine("- memory: 记忆管理")
            appendLine("- storage: 外部存储配置")
            appendLine()
            appendLine("官网文档: https://docs.hippyagent.com")
            appendLine("GitHub: https://github.com/hippyagent/hippyagent")
        })
    }
}


