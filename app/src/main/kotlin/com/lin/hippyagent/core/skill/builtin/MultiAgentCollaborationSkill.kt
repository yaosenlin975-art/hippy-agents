package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * 多智能体协作技能 - 支持 Agent 间任务分配和结果汇总
 * 通过文件系统读写实现 Agent 间通信
 */
class MultiAgentCollaborationSkill(private val context: Context) {
    companion object {
        private val jsonExtractCache = mutableMapOf<String, Regex>()
        private fun getJsonExtractRegex(key: String): Regex {
            return jsonExtractCache.getOrPut(key) { Regex(""""$key"\s*:\s*"([^"]+)"""") }
        }
    }

    private val agentsDir: File get() = File(context.filesDir, "agents")
    private val workspacesDir: File get() = File(context.filesDir, "workspaces")
    private val messagesDir: File get() = File(context.filesDir, "messages")

    fun chatWithAgent(
        targetAgentId: String,
        message: String,
        timeoutSeconds: Long = 60
    ): Result<String> {
        return try {
            val agentProfile = loadAgentProfile(targetAgentId)
                ?: return Result.failure(IllegalArgumentException("Agent not found: $targetAgentId"))

            // 写入消息到目标 Agent 的收件箱
            messagesDir.mkdirs()
            val msgId = UUID.randomUUID().toString().take(8)
            val msgFile = File(messagesDir, "${targetAgentId}_$msgId.json")
            msgFile.writeText("""{"from":"current","to":"$targetAgentId","message":"${message.replace("\"", "\\\"").replace("\n", "\\n")}","timestamp":${System.currentTimeMillis()},"read":false}""")

            Timber.d("Message sent to agent $targetAgentId, msgId=$msgId")
            Result.success("消息已发送给 Agent '${agentProfile.name}' (msgId=$msgId)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to chat with agent: $targetAgentId")
            Result.failure(e)
        }
    }

    fun submitTask(targetAgentId: String, taskDescription: String): Result<String> {
        return try {
            val agentProfile = loadAgentProfile(targetAgentId)
                ?: return Result.failure(IllegalArgumentException("Agent not found: $targetAgentId"))

            val taskId = "task_${System.currentTimeMillis()}"
            val taskDir = File(workspacesDir, targetAgentId)
            taskDir.mkdirs()
            val taskFile = File(taskDir, "$taskId.md")
            taskFile.writeText("# Task: $taskId\n\nTarget: $targetAgentId\nStatus: pending\n\n$taskDescription")

            Result.success("已提交异步任务:\n任务ID: $taskId\n目标Agent: ${agentProfile.name}\n描述: $taskDescription")
        } catch (e: Exception) {
            Timber.e(e, "Failed to submit task to agent: $targetAgentId")
            Result.failure(e)
        }
    }

    fun checkTask(taskId: String): Result<String> {
        return try {
            val taskFile = workspacesDir.listFiles()?.flatMap { dir ->
                dir.listFiles()?.filter { it.name == "$taskId.md" } ?: emptyList()
            }?.firstOrNull()

            if (taskFile != null) {
                val content = taskFile.readText()
                Result.success("任务 '$taskId' 状态:\n\n$content")
            } else {
                Result.success("任务 '$taskId' 未找到（可能尚未执行）")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun listAgents(): Result<String> {
        return try {
            if (!agentsDir.exists()) return Result.success("暂无可用 Agent")
            val agents = agentsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    val profile = loadAgentProfile(file.nameWithoutExtension)
                    profile?.let { "- ${it.name} (${it.modelProvider})" }
                } ?: emptyList()
            if (agents.isEmpty()) Result.success("暂无可用 Agent")
            else Result.success("可用 Agent 列表:\n${agents.joinToString("\n")}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun summarizeResults(results: Map<String, String>): Result<String> {
        return try {
            val sb = StringBuilder("# 多智能体结果汇总\n\n")
            results.forEach { (agentId, result) ->
                sb.appendLine("## $agentId")
                sb.appendLine(result)
                sb.appendLine()
            }
            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun loadAgentProfile(agentId: String): AgentProfileData? {
        val profileFile = File(agentsDir, "$agentId.json")
        if (!profileFile.exists()) return null
        return try {
            val json = profileFile.readText()
            val name = extractJsonString(json, "name") ?: agentId
            val modelProvider = extractJsonString(json, "modelProvider") ?: "unknown"
            AgentProfileData(name = name, modelProvider = modelProvider)
        } catch (e: Exception) { null }
    }

    private fun extractJsonString(json: String, key: String): String? {
        return getJsonExtractRegex(key).find(json)?.groupValues?.get(1)
    }

    private data class AgentProfileData(val name: String, val modelProvider: String)
}


