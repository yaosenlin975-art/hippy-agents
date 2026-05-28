package com.lin.hippyagent.core.mission

import com.lin.hippyagent.core.agent.Agent
import com.lin.hippyagent.core.agent.session.SessionStore
import com.lin.hippyagent.core.agent.session.MessageRole
import com.lin.hippyagent.core.notification.HippyAgentNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Mission 执行器
 * 负责 PRD 生成和执行循环
 */
class MissionRunner(
    private val agent: Agent,
    private val sessionStore: SessionStore,
    private val missionManager: MissionManager,
    private val notificationService: HippyAgentNotificationService? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Phase 1: 生成 PRD
     * 调用 LLM 根据任务描述生成 User Story 列表
     */
    suspend fun generatePrd(
        missionState: MissionState,
        sessionId: String
    ): Result<PrdDocument> = withContext(Dispatchers.Default) {
        runCatching {
            Timber.i("Mission PRD Generation: ${missionState.config.taskDescription}")
            
            // 构建 PRD 生成提示词
            val prdPrompt = buildPrdGenerationPrompt(missionState.config.taskDescription)
            
            // 调用 Agent 生成 PRD
            agent.processMessage(
                sessionId = sessionId,
                channelId = "mission",
                content = prdPrompt
            ).getOrElse { throw it }
            
            // 从会话最后一条消息中提取 PRD 内容
            val messages = sessionStore.getMessages(sessionId).getOrDefault(emptyList())
            val lastMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?: throw IllegalStateException("Agent 未生成 PRD 回复")
            
            // 解析 PRD JSON
            val prdContent = extractJsonFromMessage(lastMessage.content)
            val prd = json.decodeFromString<PrdDocument>(prdContent)
            
            // 保存 PRD 到文件系统
            savePrdToFile(missionState.config.taskId, prd)
            
            // 更新 Mission 状态
            missionManager.generatePrd(missionState.config.taskId, prdContent)
                .getOrElse { throw it }
            
            Timber.i("PRD generated with ${prd.userStories.size} user stories")
            prd
        }
    }

    /**
     * Phase 2: 执行循环
     * 按 PRD 逐个执行 User Story
     */
    suspend fun executeLoop(
        missionState: MissionState,
        sessionId: String
    ): Result<MissionState> = withContext(Dispatchers.Default) {
        runCatching {
            val prd = missionState.prd 
                ?: throw IllegalStateException("PRD 未生成，无法执行")
            
            Timber.i("Mission Execution Loop: ${prd.userStories.size} user stories")
            
            for ((index, story) in prd.userStories.withIndex()) {
                val iteration = index + 1
                
                if (iteration > missionState.config.maxIterations) {
                    Timber.w("Mission reached max iterations: ${missionState.config.maxIterations}")
                    missionManager.updateStoryProgress(
                        taskId = missionState.config.taskId,
                        storyId = story.id,
                        status = StoryStatus.FAILED,
                        iteration = iteration,
                        result = "达到最大迭代次数"
                    )
                    notificationService?.sendMissionCompleteNotification(
                        taskId = missionState.config.taskId,
                        taskName = missionState.config.taskDescription,
                        success = false
                    )
                    break
                }
                
                missionManager.updateStoryProgress(
                    taskId = missionState.config.taskId,
                    storyId = story.id,
                    status = StoryStatus.IN_PROGRESS,
                    iteration = iteration
                )

                notificationService?.sendMissionProgressNotification(
                    taskId = missionState.config.taskId,
                    taskName = missionState.config.taskDescription,
                    current = iteration,
                    total = prd.userStories.size,
                    status = "执行: ${story.title}"
                )
                
                val storyPrompt = buildStoryExecutionPrompt(story, missionState.config)
                agent.processMessage(
                    sessionId = sessionId,
                    channelId = "mission",
                    content = storyPrompt
                )

                val execMessages = sessionStore.getMessages(sessionId).getOrDefault(emptyList())
                val lastAssistantMsg = execMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                val success = lastAssistantMsg?.content?.isNotEmpty() == true
                
                var verificationResult: String? = null
                if (missionState.config.verifyCommand != null && success) {
                    verificationResult = runVerification(
                        missionState.config.verifyCommand!!,
                        missionState.config.workspaceDir
                    )
                }
                
                missionManager.updateStoryProgress(
                    taskId = missionState.config.taskId,
                    storyId = story.id,
                    status = if (success) StoryStatus.COMPLETED else StoryStatus.FAILED,
                    iteration = iteration,
                    result = verificationResult ?: "执行${if (success) "成功" else "失败"}"
                )
                
                Timber.d("User Story ${story.id} (${story.title}) completed: ${if (success) "PASS" else "FAIL"}")
            }

            val finalState = missionManager.getMissionState(missionState.config.taskId)
            if (finalState != null) {
                notificationService?.sendMissionCompleteNotification(
                    taskId = missionState.config.taskId,
                    taskName = missionState.config.taskDescription,
                    success = finalState.status == com.lin.hippyagent.core.mission.MissionStatus.COMPLETED
                )
            }
            
            // 返回最终状态
            missionManager.getMissionState(missionState.config.taskId)
                ?: throw IllegalStateException("Mission 状态获取失败")
        }
    }

    /**
     * 运行验证命令
     */
    private suspend fun runVerification(
        command: String,
        workspaceDir: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(File(workspaceDir))
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) "验证通过" else "验证失败: $output"
        } catch (e: Exception) {
            "验证执行异常: ${e.message}"
        }
    }

    /**
     * 保存 PRD 到文件
     */
    private fun savePrdToFile(taskId: String, prd: PrdDocument) {
        try {
            val prdDir = File(File(System.getProperty("user.dir") ?: "/data/data/com.lin.hippyagent/files"), "missions/$taskId")
            prdDir.mkdirs()
            
            val prdFile = File(prdDir, "prd.json")
            prdFile.writeText(Json { prettyPrint = true }.encodeToString(PrdDocument.serializer(), prd))
            
            // 同时生成可读的 Markdown 版本
            val mdContent = buildPrdMarkdown(prd)
            File(prdDir, "prd.md").writeText(mdContent)
            
            Timber.i("PRD saved to ${prdFile.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save PRD file")
        }
    }

    /**
     * 从消息中提取 JSON
     */
    private fun extractJsonFromMessage(content: String): String {
        // 尝试找到 JSON 代码块
        val jsonBlockRegex = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)\\n```")
        val match = jsonBlockRegex.find(content)
        if (match != null) {
            return match.groupValues[1]
        }
        
        // 如果没有代码块，尝试直接解析整个内容
        return content.trim()
    }

    /**
     * 构建 PRD 生成提示词
     */
    private fun buildPrdGenerationPrompt(taskDescription: String): String {
        return """
你是一个产品经理，请将以下任务描述转化为 PRD（产品需求文档），包含多个 User Story。

任务描述：$taskDescription

请以 JSON 格式输出，格式如下：
```json
{
  "title": "任务标题",
  "description": "任务描述",
  "userStories": [
    {
      "id": "US001",
      "title": "User Story 标题",
      "description": "作为[角色]，我想要[功能]，以便[价值]",
      "acceptanceCriteria": ["验收标准1", "验收标准2"]
    }
  ]
}
```

要求：
1. User Story 数量根据任务复杂度合理划分
2. 每个 User Story 必须有明确的验收标准
3. 验收标准要具体可验证
4. 只输出 JSON，不要其他内容
""".trimIndent()
    }

    /**
     * 构建 User Story 执行提示词
     */
    private fun buildStoryExecutionPrompt(
        story: UserStory,
        config: MissionConfig
    ): String {
        return """
你现在处于 Mission 模式，需要执行以下 User Story：

## ${story.title}
${story.description}

### 验收标准
${story.acceptanceCriteria.joinToString("\n") { "- $it" }}

### 工作目录
${config.workspaceDir}

### 任务
请完成这个 User Story，确保满足所有验收标准。
你可以使用所有可用的工具来完成任务。

完成后，请明确说明是否满足所有验收标准。
""".trimIndent()
    }

    /**
     * 生成 PRD 的 Markdown 版本
     */
    private fun buildPrdMarkdown(prd: PrdDocument): String {
        val sb = StringBuilder()
        sb.appendLine("# ${prd.title}")
        sb.appendLine()
        sb.appendLine(prd.description)
        sb.appendLine()
        
        for (story in prd.userStories) {
            sb.appendLine("## ${story.id} - ${story.title}")
            sb.appendLine(story.description)
            sb.appendLine()
            sb.appendLine("### 验收标准")
            story.acceptanceCriteria.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }
        
        return sb.toString()
    }
}

