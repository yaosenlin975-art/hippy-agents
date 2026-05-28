package com.lin.hippyagent.core.tools

class ToolTTLManager {
    private val toolTTLs = mutableMapOf<String, Int>()
    private val hiddenTools = mutableSetOf<String>()

    fun setTTL(toolName: String, ttl: Int) {
        toolTTLs[toolName] = ttl
    }

    fun removeTTL(toolName: String) {
        toolTTLs.remove(toolName)
    }

    fun isExpired(toolName: String): Boolean = toolName in hiddenTools

    fun tick(): List<String> {
        val expired = mutableListOf<String>()
        for ((name, ttl) in toolTTLs) {
            val newTTL = ttl - 1
            if (newTTL <= 0) {
                expired.add(name)
                hiddenTools.add(name)
            } else {
                toolTTLs[name] = newTTL
            }
        }
        for (name in expired) toolTTLs.remove(name)
        return expired
    }

    fun promote(names: List<String>) {
        for (name in names) {
            hiddenTools.remove(name)
            toolTTLs.remove(name)
        }
    }

    fun isVisible(toolName: String): Boolean = toolName !in hiddenTools

    fun copyState(): Pair<Map<String, Int>, Set<String>> = Pair(toolTTLs.toMap(), hiddenTools.toSet())

    fun restoreState(ttls: Map<String, Int>, hidden: Set<String>) {
        toolTTLs.clear()
        toolTTLs.putAll(ttls)
        hiddenTools.clear()
        hiddenTools.addAll(hidden)
    }
}
