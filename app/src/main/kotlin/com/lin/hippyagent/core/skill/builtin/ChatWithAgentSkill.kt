package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Chat with Agent - 智能体间对话
 * 通过文件系统向其他 Agent 发送消息
 */
class ChatWithAgentSkill(private val context: Context) {
    companion object {
        private val jsonExtractCache = mutableMapOf<String, Regex>()
        private fun getJsonExtractRegex(key: String): Regex {
            return jsonExtractCache.getOrPut(key) { Regex(""""$key"\s*:\s*"([^"]+)"""") }
        }
    }

    private val agentsDir: File get() = File(context.filesDir, "agents")
    private val messagesDir: File get() = File(context.filesDir, "messages")

    fun chatWithAgent(targetAgentId: String, message: String): Result<String> {
        return try {
            val agentProfile = loadAgentProfile(targetAgentId)
                ?: return Result.failure(IllegalArgumentException("Agent not found: $targetAgentId"))

            // 写入消息到目标 Agent 的收件箱
            messagesDir.mkdirs()
            val msgId = UUID.randomUUID().toString().take(8)
            val msgFile = File(messagesDir, "${targetAgentId}_$msgId.json")
            msgFile.writeText("""{"from":"current","to":"$targetAgentId","message":"${message.replace("\"", "\\\"").replace("\n", "\\n")}","timestamp":${System.currentTimeMillis()},"read":false}""")

            Timber.d("Message sent to agent $targetAgentId, msgId=$msgId")
            Result.success("消息已发送给 '${agentProfile.name}' (msgId=$msgId)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to chat with agent")
            Result.failure(e)
        }
    }

    fun listAvailableAgents(): Result<List<String>> {
        return try {
            if (!agentsDir.exists()) return Result.success(emptyList())
            val agents = agentsDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.map { it.nameWithoutExtension }
                ?: emptyList()
            Result.success(agents)
        } catch (e: Exception) {
            Timber.e(e, "Failed to list agents")
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


