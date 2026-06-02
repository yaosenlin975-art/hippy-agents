package com.lin.hippyagent.core.prompt

import com.lin.hippyagent.core.memory.commonmemory.CommonMemoryEntry
import com.lin.hippyagent.core.pool.StringBuilderPool
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class PromptContext(
    val workingDir: File,
    val agentId: String,
    val sessionId: String = "",  // 会话 ID，用于 Standing Orders 注入跟踪
    val coreFiles: List<String> = listOf("AGENTS.md", "SOUL.md", "PROFILE.md", "RULES.md"),
    val memoryEnabled: Boolean = false,
    val memoryContent: String? = null,
    val customInstructions: String? = null,
    val globalRules: String? = null,
    val commonMemoryEntries: List<Pair<CommonMemoryEntry, Float>>? = null,
    val skills: List<SkillInfo> = emptyList(),
    val resolvedSkills: List<com.lin.hippyagent.core.skill.ResolvedSkill> = emptyList(),
    val skillCatalogText: String? = null,
    val planContext: String? = null,
    val clarificationEnabled: Boolean = false,
    val citationsEnabled: Boolean = false,
    val progressiveSkillLoading: Boolean = false,
    val deferredToolNames: Set<String> = emptySet(),
    val appAliases: Map<String, String> = emptyMap()
)

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val skillFilePath: String = ""
)

class PromptBuilder(
    private val sbPool: StringBuilderPool = StringBuilderPool()
) {

    fun buildSystemPrompt(context: PromptContext): String = sbPool.use { builder ->
        buildRoleSection(context, builder)
        buildCoreFilesSection(context, builder)
        buildGlobalRulesSection(context, builder)

        // ==== 动态部分（每轮都注入） ====
        buildWorkingDirectorySection(context, builder)
        buildCurrentDateSection(builder)
        buildMemorySection(context, builder)
        buildSkillSystemSection(context, builder)
        buildDeferredToolsSection(context, builder)
        buildAppAliasSection(context, builder)
        buildClarificationSection(context, builder)
        buildCitationsSection(context, builder)
        buildCustomInstructionsSection(context, builder)
        buildCommonMemorySection(context, builder)
        buildPlanContextSection(context, builder)
        buildBootstrapSection(context, builder)
        buildCriticalRemindersSection(context, builder)

        builder.toString()
    }

    private fun buildRoleSection(context: PromptContext, builder: StringBuilder) {
        val agentMd = File(context.workingDir, "AGENTS.md")
        val soulMd = File(context.workingDir, "SOUL.md")
        val profileMd = File(context.workingDir, "PROFILE.md")
        val hasRole = agentMd.exists() || soulMd.exists() || profileMd.exists()
        if (!hasRole) return

        builder.appendLine("<role>")
        for (filename in listOf("AGENTS.md", "SOUL.md", "PROFILE.md")) {
            val file = File(context.workingDir, filename)
            if (file.exists()) {
                builder.appendLine(file.readText())
                builder.appendLine()
            }
        }
        builder.appendLine("</role>")
        builder.appendLine()
    }

    private fun buildWorkingDirectorySection(context: PromptContext, builder: StringBuilder) {
        builder.appendLine("<working_directory>")
        builder.appendLine(context.workingDir.absolutePath)
        builder.appendLine("</working_directory>")
        builder.appendLine()
        builder.appendLine("<file_path_rules>")
        builder.appendLine("文件操作工具（read_file, write_file, edit_file, append_file, delete_file）需要使用绝对路径：")
        builder.appendLine("- Android 设备常用路径：/storage/emulated/0/Download（下载目录）")
        builder.appendLine("- 临时目录支持：/tmp/xxx 会自动映射到应用缓存目录")
        builder.appendLine("- SD卡路径支持：/sdcard/xxx 会自动转换为 /storage/emulated/0/xxx")
        builder.appendLine("- 工作目录相对路径：可以使用 ./xxx 或直接使用文件名（相对于工作目录）")
        builder.appendLine("</file_path_rules>")
        builder.appendLine()
    }

    private fun buildCurrentDateSection(builder: StringBuilder) {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        builder.appendLine("<current_date>")
        builder.appendLine(dateStr)
        builder.appendLine("</current_date>")
        builder.appendLine()
    }

    private fun buildCoreFilesSection(context: PromptContext, builder: StringBuilder) {
        val coreFileNames = context.coreFiles.filter { it !in listOf("AGENTS.md", "SOUL.md", "PROFILE.md", "HEARTBEAT.md") }
        val existingFiles = coreFileNames.filter { File(context.workingDir, it).exists() }
        if (existingFiles.isEmpty()) return

        builder.appendLine("<core_files>")
        for (filename in existingFiles) {
            val file = File(context.workingDir, filename)
            val content = file.readText()
            builder.appendLine("[$filename]")
            builder.appendLine(content)
            builder.appendLine()
        }
        builder.appendLine("</core_files>")
        builder.appendLine()
    }

    private fun buildMemorySection(context: PromptContext, builder: StringBuilder) {
        if (!context.memoryEnabled || context.memoryContent.isNullOrBlank()) return

        builder.appendLine("<memory>")
        builder.appendLine(context.memoryContent)
        builder.appendLine("</memory>")
        builder.appendLine()
    }

    private fun buildSkillSystemSection(context: PromptContext, builder: StringBuilder) {
        if (context.skillCatalogText.isNullOrBlank() && context.skills.isEmpty() && context.resolvedSkills.isEmpty()) {
            builder.appendLine("<skill_system>")
            builder.appendLine("你可以使用 load_skill 工具按需加载已启用技能的完整说明。当任务涉及某个技能时，调用 load_skill 获取详细用法。")
            builder.appendLine("</skill_system>")
            return
        }

        builder.appendLine("<skill_system>")

        if (!context.skillCatalogText.isNullOrBlank()) {
            if (context.progressiveSkillLoading) {
                builder.appendLine("你当前可用的技能目录如下。每个技能包含名称、描述和位置。")
                builder.appendLine("当需要使用某个技能时，使用 load_skill 工具加载该技能的完整说明。")
                builder.appendLine()
                builder.appendLine(context.skillCatalogText)
            } else {
                builder.appendLine(context.skillCatalogText)
            }
        } else if (context.skills.isNotEmpty()) {
            builder.appendLine("你当前启用了以下技能：")
            builder.appendLine()
            for (skill in context.skills) {
                val desc = if (skill.description.isNotBlank()) " — ${skill.description}" else ""
                builder.appendLine("- **${skill.name}** ($skill.id)$desc")
                if (skill.skillFilePath.isNotBlank()) {
                    builder.appendLine("  技能说明文件: `${skill.skillFilePath}`")
                }
            }
            builder.appendLine()
            builder.appendLine("使用技能前，先用 read_file 工具读取对应的 SKILL.md 文件了解技能的详细用法。")
        }

        if (context.resolvedSkills.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("<skill_triggers>")
            builder.appendLine("根据用户输入，以下技能可能与此请求相关：")
            builder.appendLine()
            for (resolved in context.resolvedSkills) {
                builder.appendLine("- **${resolved.name}** (${resolved.id}) — 匹配原因: ${resolved.matchReason}")
                val triggers = resolved.manifest.triggers
                if (triggers.shouldUse.isNotEmpty()) {
                    builder.appendLine("  使用条件: ${triggers.shouldUse.joinToString("; ")}")
                }
                if (triggers.shouldNotUse.isNotEmpty()) {
                    builder.appendLine("  不使用条件: ${triggers.shouldNotUse.joinToString("; ")}")
                }
                if (resolved.manifest.tools.isNotEmpty()) {
                    builder.appendLine("  可用工具: ${resolved.manifest.tools.joinToString(", ") { it.name }}")
                }
            }
            builder.appendLine()
            builder.appendLine("请根据上述条件判断是否需要使用对应技能。如果用户请求与技能的「不使用条件」匹配，则不应调用该技能。")
            builder.appendLine("</skill_triggers>")
        }

        builder.appendLine("</skill_system>")
        builder.appendLine()
    }

    private fun buildDeferredToolsSection(context: PromptContext, builder: StringBuilder) {
        if (context.deferredToolNames.isEmpty()) return

        builder.appendLine("<available-deferred-tools>")
        builder.appendLine("以下工具已安装但参数schema未加载。如需使用，请先调用 tool_search 工具获取完整定义。")
        builder.appendLine()
        for (name in context.deferredToolNames.sorted()) {
            builder.appendLine("- $name")
        }
        builder.appendLine("</available-deferred-tools>")
        builder.appendLine()
    }

    private fun buildAppAliasSection(context: PromptContext, builder: StringBuilder) {
        if (context.appAliases.isEmpty()) return

        builder.appendLine("<app_aliases>")
        builder.appendLine("以下应用名称与包名的映射关系，在需要引用应用时使用包名：")
        builder.appendLine()
        for ((alias, packageName) in context.appAliases.entries.sortedBy { it.key }) {
            builder.appendLine("- $alias → $packageName")
        }
        builder.appendLine("</app_aliases>")
        builder.appendLine()
    }

    private fun buildClarificationSection(context: PromptContext, builder: StringBuilder) {
        if (!context.clarificationEnabled) return

        builder.appendLine("<clarification_system>")
        builder.appendLine("当你遇到以下情况时，必须使用 ask 工具向用户提问，而不是自行假设：")
        builder.appendLine()
        builder.appendLine("1. **missing_info**: 用户请求缺少关键信息（如目标不明确、缺少必要参数）")
        builder.appendLine("2. **approach_choice**: 存在多种可行的执行方案，需要用户选择")
        builder.appendLine("3. **risk_confirmation**: 即将执行的操作有潜在风险，需要用户确认")
        builder.appendLine("4. **scope_ambiguity**: 用户请求范围过大或模糊，需要缩小范围")
        builder.appendLine("5. **dependency_check**: 任务依赖的资源或条件可能不满足，需要确认")
        builder.appendLine()
        builder.appendLine("调用 ask 后，立即停止当前轮次，等待用户回复。")
        builder.appendLine("</clarification_system>")
        builder.appendLine()
    }

    private fun buildCitationsSection(context: PromptContext, builder: StringBuilder) {
        if (!context.citationsEnabled) return

        builder.appendLine("<citations>")
        builder.appendLine("当你引用搜索结果或外部信息时，必须标注来源：")
        builder.appendLine()
        builder.appendLine("行内引用格式：[citation:标题](URL)")
        builder.appendLine()
        builder.appendLine("在回答末尾添加 Sources 区块：")
        builder.appendLine("## Sources")
        builder.appendLine("- [标题](URL)")
        builder.appendLine()
        builder.appendLine("如果搜索结果没有提供 URL，则标注来源名称即可。")
        builder.appendLine("</citations>")
        builder.appendLine()
    }

    private fun buildCustomInstructionsSection(context: PromptContext, builder: StringBuilder) {
        if (context.customInstructions.isNullOrBlank()) return

        builder.appendLine("<custom_instructions>")
        builder.appendLine(context.customInstructions)
        builder.appendLine("</custom_instructions>")
        builder.appendLine()
    }

    private fun buildGlobalRulesSection(context: PromptContext, builder: StringBuilder) {
        if (context.globalRules.isNullOrBlank()) return

        builder.appendLine("<global_rules>")
        builder.appendLine("以下是任何情况下都必须遵守的规则：")
        builder.appendLine()
        builder.appendLine(context.globalRules)
        builder.appendLine("</global_rules>")
        builder.appendLine()
    }

    private fun buildCommonMemorySection(context: PromptContext, builder: StringBuilder) {
        val entries = context.commonMemoryEntries ?: return
        if (entries.isEmpty()) return

        builder.appendLine("<common_memory>")
        builder.appendLine("以下是从你的长期记忆中检索到的相关信息：")
        builder.appendLine()
        for ((entry, score) in entries.take(8)) {
            builder.appendLine("- [${entry.type.value}] ${entry.summary}")
            if (!entry.detail.isNullOrBlank()) {
                builder.appendLine("  ${entry.detail.take(200)}")
            }
        }
        builder.appendLine("</common_memory>")
        builder.appendLine()
    }

    private fun buildPlanContextSection(context: PromptContext, builder: StringBuilder) {
        if (context.planContext.isNullOrBlank()) return

        builder.appendLine(context.planContext)
        builder.appendLine()
    }

    private fun buildBootstrapSection(context: PromptContext, builder: StringBuilder) {
        val bootstrapFile = File(context.workingDir, "BOOTSTRAP.md")
        val bootstrapCompleted = File(context.workingDir, ".bootstrap_completed")
        val isBootstrapMode = bootstrapFile.exists() && !bootstrapCompleted.exists()

        if (!isBootstrapMode) return

        val bootstrapContent = runCatching { bootstrapFile.readText() }.getOrNull() ?: ""
        builder.appendLine("<bootstrap_mode>")
        builder.appendLine(bootstrapContent)
        builder.appendLine()
        builder.appendLine("BOOTSTRAP.md 已存在于工作目录 — 这是首次启动设置。")
        builder.appendLine("除非用户说明不需要进行初始化行为, 否则你需要按照上方 BOOTSTRAP.md 的内容引导用户进行初始化。")
        builder.appendLine("1. 按照 BOOTSTRAP.md 的指引与用户对话，帮助用户定义身份和偏好。")
        builder.appendLine("2. 根据对话结果创建/更新文件（PROFILE.md, SOUL.md, MEMORY.md 等）。")
        builder.appendLine("3. 完成后删除 BOOTSTRAP.md 文件。")
        builder.appendLine("如果用户想跳过初始化，直接回答用户的问题即可。")
        builder.appendLine("</bootstrap_mode>")
        builder.appendLine()
    }

    private fun buildCriticalRemindersSection(context: PromptContext, builder: StringBuilder) {
        val reminders = mutableListOf<String>()
        if (context.clarificationEnabled) {
            reminders.add("遇到模糊需求时必须使用 ask，不要自行假设。")
        }
        if (context.citationsEnabled) {
            reminders.add("引用搜索结果时必须标注来源。")
        }
        if (reminders.isEmpty()) return

        builder.appendLine("<critical_reminders>")
        for (reminder in reminders) {
            builder.appendLine("- $reminder")
        }
        builder.appendLine("</critical_reminders>")
        builder.appendLine()
    }

    fun isBootstrapMode(workingDir: File): Boolean {
        val bootstrapFile = File(workingDir, "BOOTSTRAP.md")
        val bootstrapCompleted = File(workingDir, ".bootstrap_completed")
        return bootstrapFile.exists() && !bootstrapCompleted.exists()
    }

    fun markBootstrapCompleted(workingDir: File) {
        File(workingDir, ".bootstrap_completed").createNewFile()
        Timber.i("Bootstrap marked as completed for $workingDir")
    }
}
