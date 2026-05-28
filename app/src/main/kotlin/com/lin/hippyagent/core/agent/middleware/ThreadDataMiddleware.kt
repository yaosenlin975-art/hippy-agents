package com.lin.hippyagent.core.agent.middleware

import timber.log.Timber

class ThreadDataMiddleware(
    private val workspaceDir: String
) : AgentMiddleware {

    override val priority: Int = PRIORITY
    override val name: String = NAME

    override fun beforeModel(context: MiddlewareContext): MiddlewareResult {
        context.extra["workspace_dir"] = workspaceDir
        return MiddlewareResult.Continue
    }

    companion object {
        const val PRIORITY = 10
        const val NAME = "thread_data"
    }
}
