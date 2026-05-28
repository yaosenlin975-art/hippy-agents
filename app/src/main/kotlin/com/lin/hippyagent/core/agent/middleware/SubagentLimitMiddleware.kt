package com.lin.hippyagent.core.agent.middleware

import timber.log.Timber

class SubagentLimitMiddleware(
    private val maxChildren: Int = DEFAULT_MAX_CHILDREN
) : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    private val activeSubagents = java.util.concurrent.atomic.AtomicInteger(0)

    fun tryAcquire(): Boolean {
        while (true) {
            val current = activeSubagents.get()
            if (current >= maxChildren) {
                Timber.w("SubagentLimitMiddleware: limit reached ($current/$maxChildren)")
                return false
            }
            if (activeSubagents.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        activeSubagents.decrementAndGet()
    }

    fun activeCount(): Int = activeSubagents.get()

    companion object {
        const val PRIORITY = 70
        const val NAME = "subagent_limit"
        const val DEFAULT_MAX_CHILDREN = 3
    }
}
