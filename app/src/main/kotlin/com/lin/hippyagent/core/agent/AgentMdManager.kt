package com.lin.hippyagent.core.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Agent Markdown 管理器
 * 管理工作区和记忆目录下的 Markdown 文件
 */
class AgentMdManager(
    private val context: Context
) {
    // 使用 limitedParallelism 限制并发数，减少线程池竞争
    private val fileDispatcher = Dispatchers.IO.limitedParallelism(4)

    private val workingDir by lazy {
        File(context.filesDir, "workspace").apply { mkdirs() }
    }

    private val memoryDir by lazy {
        File(context.filesDir, "memory").apply { mkdirs() }
    }

    /**
     * 列出工作区下的 .md 文件（含 memory/ 子目录）
     */
    suspend fun listWorkspaceMdFiles(agentId: String): List<String> = withContext(fileDispatcher) {
        val agentDir = File(workingDir, agentId).apply { mkdirs() }
        val files = mutableListOf<String>()
        // 顶层 .md 文件
        agentDir.listFiles { file -> file.isFile && file.extension == "md" }
            ?.mapTo(files) { it.name }
        // memory/ 子目录下的 .md 文件，以 "memory/" 前缀标记
        val memoryDir = File(agentDir, "memory")
        if (memoryDir.isDirectory) {
            memoryDir.listFiles { file -> file.isFile && file.extension == "md" }
                ?.mapTo(files) { "memory/${it.name}" }
        }
        files.sorted()
    }

    /**
     * 读取工作区文件
     */
    suspend fun readWorkspaceFile(agentId: String, filename: String): Result<String> = withContext(fileDispatcher) {
        runCatching {
            val file = File(workingDir, "$agentId/$filename")
            if (!file.exists()) throw IllegalArgumentException("文件不存在: $filename")
            file.readText()
        }
    }

    /**
     * 写入工作区文件
     */
    suspend fun writeWorkspaceFile(agentId: String, filename: String, content: String): Result<Unit> = withContext(fileDispatcher) {
        runCatching {
            val agentDir = File(workingDir, agentId).apply { mkdirs() }
            val file = File(agentDir, filename)
            file.writeText(content)
            Timber.i("Workspace file written: $filename")
        }
    }

    /**
     * 列出记忆目录下的 .md 文件
     */
    suspend fun listMemoryMdFiles(): List<String> = withContext(fileDispatcher) {
        memoryDir.listFiles { file -> file.extension == "md" }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 读取记忆文件
     */
    suspend fun readMemoryFile(filename: String): Result<String> = withContext(fileDispatcher) {
        runCatching {
            val file = File(memoryDir, filename)
            if (!file.exists()) throw IllegalArgumentException("记忆文件不存在: $filename")
            file.readText()
        }
    }

    /**
     * 写入记忆文件
     */
    suspend fun writeMemoryFile(filename: String, content: String): Result<Unit> = withContext(fileDispatcher) {
        runCatching {
            val file = File(memoryDir, filename)
            file.writeText(content)
            Timber.i("Memory file written: $filename")
        }
    }

    /**
     * 创建每日记忆文件
     * 格式: YYYY-MM-DD.md
     */
    suspend fun createDailyMemoryFile(): Result<String> = withContext(fileDispatcher) {
        runCatching {
            val today = LocalDate.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val filename = "${today.format(formatter)}.md"
            val file = File(memoryDir, filename)
            
            if (!file.exists()) {
                file.writeText("# ${today.format(formatter)} 记忆\n\n")
                Timber.i("Daily memory file created: $filename")
            }
            
            filename
        }
    }

    /**
     * 获取 MEMORY.md 文件路径
     */
    fun getMemoryMdFile(): File {
        return File(memoryDir, "MEMORY.md")
    }

    /**
     * 读取 MEMORY.md
     */
    suspend fun readMemoryMd(): Result<String> = withContext(fileDispatcher) {
        runCatching {
            val file = getMemoryMdFile()
            if (!file.exists()) throw IllegalStateException("MEMORY.md 不存在")
            file.readText()
        }
    }

    /**
     * 写入 MEMORY.md
     */
    suspend fun writeMemoryMd(content: String): Result<Unit> = withContext(fileDispatcher) {
        runCatching {
            val file = getMemoryMdFile()
            file.writeText(content)
            Timber.i("MEMORY.md written")
        }
    }

    /**
     * 追加内容到 MEMORY.md
     */
    suspend fun appendToMemoryMd(content: String): Result<Unit> = withContext(fileDispatcher) {
        runCatching {
            val file = getMemoryMdFile()
            file.appendText("\n$content\n")
            Timber.i("Content appended to MEMORY.md")
        }
    }

    /**
     * 获取 AGENTS.md 文件路径
     */
    fun getAgentsMdFile(): File {
        return File(memoryDir, "AGENTS.md")
    }

    /**
     * 读取 AGENTS.md
     */
    suspend fun readAgentsMd(): Result<String> = withContext(fileDispatcher) {
        runCatching {
            val file = getAgentsMdFile()
            if (!file.exists()) throw IllegalStateException("AGENTS.md 不存在")
            file.readText()
        }
    }

    /**
     * 写入 AGENTS.md
     */
    suspend fun writeAgentsMd(content: String): Result<Unit> = withContext(fileDispatcher) {
        runCatching {
            val file = getAgentsMdFile()
            file.writeText(content)
            Timber.i("AGENTS.md written")
        }
    }

    /**
     * 列出指定 agent 的核心文件（模板文件）
     */
    suspend fun listAgentCoreFiles(agentId: String): List<String> = withContext(fileDispatcher) {
        val agentDir = File(workingDir, agentId).apply { mkdirs() }
        val templateFiles = listOf(
            "SOUL.md", "RULES.md", "PROFILE.md", 
            "MEMORY.md", "HEARTBEAT.md", "BOOTSTRAP.md", "AGENTS.md"
        )
        templateFiles.filter { filename ->
            File(agentDir, filename).exists()
        }
    }

    /**
     * 确保模板文件存在
     */
    suspend fun ensureTemplateFiles(agentId: String): Result<Unit> = withContext(fileDispatcher) {
        runCatching {
            val agentDir = File(workingDir, agentId).apply { mkdirs() }
            
            val templates = mapOf(
                "SOUL.md" to "# ${agentId} 的灵魂\n\n## 性格特征\n- 友好、专业、主动",
                "RULES.md" to "# 规则\n\n## 行为准则\n1. 始终用中文回复",
                "PROFILE.md" to "# 个人档案\n\n## 基本信息\n- 名称: ${agentId}",
                "MEMORY.md" to "# 长期记忆\n\n## 关键信息",
                "HEARTBEAT.md" to "# 心跳配置\n\n## 定时任务",
                "BOOTSTRAP.md" to "# 启动引导\n\n## 初始化流程",
                "AGENTS.md" to "# AGENTS\n\nThis file defines the agents available in this workspace.\n\n## Agent Configuration\n\nEach agent has the following properties:\n- **Name**: Display name of the agent\n- **Role**: The role this agent plays\n- **Skills**: List of skills this agent can use\n- **Model**: The LLM model this agent uses\n\n## Usage\n\nAgents can be configured through the agent management interface."
            )
            
            templates.forEach { (filename, defaultContent) ->
                val file = File(agentDir, filename)
                if (!file.exists()) {
                    file.writeText(defaultContent)
                    Timber.i("Template file created: $filename")
                }
            }
        }
    }
}

