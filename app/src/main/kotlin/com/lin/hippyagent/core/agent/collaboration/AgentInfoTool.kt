package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolResult
import timber.log.Timber

class AgentInfoTool(
    private val repository: AgentInfoRepository
) : Tool() {

    override val definition = ToolDefinition(
        name = "agent_info",
        description = "Query agent information. Modes: 1) query=agentId → single agent card; 2) query=\"list\" → all agent summaries; 3) query=\"search:keyword\" → search agents; 4) query=\"group:groupId\" → group member summaries",
        parameters = mapOf(
            "query" to ToolParameter("query", "string", "Agent ID, 'list', 'search:keyword', or 'group:groupId'", true),
            "refresh" to ToolParameter("refresh", "boolean", "Force refresh cache", false, false)
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        val callId = arguments["callId"] as? String ?: ""
        return try {
            val query = getRequiredArgument(arguments, "query")
            val refresh = (arguments["refresh"] as? Boolean) ?: false

            when {
                query == "list" -> handleList(callId)
                query.startsWith("search:") -> handleSearch(callId, query.removePrefix("search:"))
                query.startsWith("group:") -> handleGroupQuery(callId, query.removePrefix("group:"))
                else -> handleSingleQuery(callId, query, refresh)
            }
        } catch (e: Exception) {
            Timber.e(e, "agent_info execution failed")
            ToolResult(callId, false, error = e.message ?: "Unknown error")
        }
    }

    private suspend fun handleSingleQuery(callId: String, agentId: String, refresh: Boolean): ToolResult {
        val card = repository.getAgentCard(agentId, refresh)
            ?: return ToolResult(callId, false, error = "Agent not found: $agentId")

        val json = buildCardJson(card)
        val forUser = buildString {
            append(card.displayName)
            append(" (")
            append(card.agentId)
            append(")")
            if (card.identity != null) {
                append(" — ")
                append(card.identity)
            }
            if (card.modelInfo != null) {
                append(" [")
                append(card.modelInfo.provider)
                append("/")
                append(card.modelInfo.modelName)
                append("]")
            }
        }
        return ToolResult(callId, true, output = json, forUser = forUser)
    }

    private suspend fun handleList(callId: String): ToolResult {
        val summaries = repository.listAgentSummaries()
        val json = buildSummaryArrayJson(summaries)
        val forUser = summaries.joinToString("\n") { s ->
            "  - ${s.displayName} (${s.agentId})${s.identity?.let { " — $it" } ?: ""}"
        }.ifBlank { "No agents found" }
        return ToolResult(callId, true, output = json, forUser = forUser)
    }

    private suspend fun handleSearch(callId: String, keyword: String): ToolResult {
        val results = repository.searchAgents(keyword)
        val json = buildSummaryArrayJson(results)
        val forUser = results.joinToString("\n") { s ->
            "  - ${s.displayName} (${s.agentId})${s.identity?.let { " — $it" } ?: ""}"
        }.ifBlank { "No agents matching '$keyword'" }
        return ToolResult(callId, true, output = json, forUser = forUser)
    }

    private suspend fun handleGroupQuery(callId: String, groupId: String): ToolResult {
        val summaries = repository.getGroupAgentSummaries(groupId)
            ?: return ToolResult(callId, false, error = "Group not found: $groupId")
        val groupName = repository.getGroupName(groupId) ?: groupId
        val json = buildSummaryArrayJson(summaries)
        val forUser = buildString {
            append("Group: ")
            append(groupName)
            append("\n")
            append(summaries.joinToString("\n") { s ->
                "  - ${s.displayName} (${s.agentId})${s.identity?.let { " — $it" } ?: ""}"
            }.ifBlank { "  (no members)" })
        }
        return ToolResult(callId, true, output = json, forUser = forUser)
    }

    private fun buildCardJson(card: AgentCard): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"agentId\":").append(escapeJson(card.agentId)).append(",")
        sb.append("\"displayName\":").append(escapeJson(card.displayName)).append(",")
        sb.append("\"avatarUrl\":").append(card.avatarUrl?.let { escapeJson(it) } ?: "null").append(",")
        sb.append("\"identity\":").append(card.identity?.let { escapeJson(it) } ?: "null").append(",")
        sb.append("\"responsibilities\":").append(stringListToJson(card.responsibilities)).append(",")
        sb.append("\"boundaries\":").append(stringListToJson(card.boundaries)).append(",")
        sb.append("\"skills\":").append(skillsToJson(card.skills)).append(",")
        sb.append("\"collaboration\":").append(collaborationToJson(card.collaboration)).append(",")
        sb.append("\"modelInfo\":").append(card.modelInfo?.let { modelInfoToJson(it) } ?: "null")
        sb.append("}")
        return sb.toString()
    }

    private fun buildSummaryArrayJson(summaries: List<AgentCardSummary>): String {
        if (summaries.isEmpty()) return "[]"
        val sb = StringBuilder()
        sb.append("[")
        summaries.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"agentId\":").append(escapeJson(s.agentId)).append(",")
            sb.append("\"displayName\":").append(escapeJson(s.displayName)).append(",")
            sb.append("\"identity\":").append(s.identity?.let { escapeJson(it) } ?: "null").append(",")
            sb.append("\"responsibilityTags\":").append(stringListToJson(s.responsibilityTags)).append(",")
            sb.append("\"skillCount\":").append(s.skillCount)
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun stringListToJson(list: List<String>): String {
        if (list.isEmpty()) return "[]"
        val sb = StringBuilder()
        sb.append("[")
        list.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            sb.append(escapeJson(s))
        }
        sb.append("]")
        return sb.toString()
    }

    private fun skillsToJson(skills: List<SkillRef>): String {
        if (skills.isEmpty()) return "[]"
        val sb = StringBuilder()
        sb.append("[")
        skills.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            sb.append("{")
            sb.append("\"id\":").append(escapeJson(s.id)).append(",")
            sb.append("\"name\":").append(escapeJson(s.name)).append(",")
            sb.append("\"description\":").append(escapeJson(s.description)).append(",")
            sb.append("\"triggers\":").append(stringListToJson(s.triggers))
            sb.append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun collaborationToJson(c: CollaborationContract): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"mentionable\":").append(c.mentionable).append(",")
        sb.append("\"delegatable\":").append(c.delegatable).append(",")
        sb.append("\"preferredTopics\":").append(stringListToJson(c.preferredTopics)).append(",")
        sb.append("\"notes\":").append(c.notes?.let { escapeJson(it) } ?: "null")
        sb.append("}")
        return sb.toString()
    }

    private fun modelInfoToJson(m: ModelInfo): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"provider\":").append(escapeJson(m.provider)).append(",")
        sb.append("\"modelName\":").append(escapeJson(m.modelName))
        sb.append("}")
        return sb.toString()
    }
}
