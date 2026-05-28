package com.lin.hippyagent.core.tools

class ToolAccessController {
    private val agentToolAccess = mutableMapOf<String, Set<String>>()
    private val agentAllowLists = mutableMapOf<String, List<Regex>>()
    private val agentDenyLists = mutableMapOf<String, List<Regex>>()

    fun setAgentToolAccess(agentId: String, toolNames: Set<String>) {
        agentToolAccess[agentId] = toolNames
    }

    fun setAgentAllowList(agentId: String, patterns: List<String>) {
        agentAllowLists[agentId] = patterns.map { globToRegex(it) }
    }

    fun setAgentDenyList(agentId: String, patterns: List<String>) {
        agentDenyLists[agentId] = patterns.map { globToRegex(it) }
    }

    fun isToolAccessible(toolName: String, agentId: String, ownership: ToolOwnership): Boolean {
        if (!isAllowedByPolicy(toolName, agentId)) return false
        return when (ownership) {
            ToolOwnership.SYSTEM -> true
            ToolOwnership.SHARED -> true
            ToolOwnership.OWNER_ONLY -> {
                agentToolAccess[agentId]?.contains(toolName) ?: false
            }
        }
    }

    fun isAllowedByPolicy(toolName: String, agentId: String): Boolean {
        val denyPatterns = agentDenyLists[agentId]
        if (denyPatterns != null && denyPatterns.any { it.matches(toolName) }) return false

        val allowPatterns = agentAllowLists[agentId]
        if (allowPatterns != null && allowPatterns.isNotEmpty()) {
            return allowPatterns.any { it.matches(toolName) }
        }

        return true
    }

    private fun globToRegex(glob: String): Regex {
        val regexStr = glob
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^$regexStr$")
    }
}
