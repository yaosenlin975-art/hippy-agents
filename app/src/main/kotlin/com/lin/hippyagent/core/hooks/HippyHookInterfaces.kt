package com.lin.hippyagent.core.hooks

import com.lin.hippyagent.core.inbox.ApprovalDecision

interface HippyEventObserver {
    suspend fun onEvent(event: HippyEvent)
}

interface HippyLLMInterceptor {
    suspend fun beforeLLM(req: LLMHookRequest): HookResult<LLMHookRequest>
    suspend fun afterLLM(resp: LLMHookResponse): HookResult<LLMHookResponse>
}

interface HippyToolInterceptor {
    suspend fun beforeTool(call: ToolCallHookRequest): HookResult<ToolCallHookRequest>
    suspend fun afterTool(result: ToolResultHookResponse): HookResult<ToolResultHookResponse>
}

interface HippyToolApprover {
    suspend fun approveTool(req: ToolApprovalRequest): ApprovalDecision
}

data class HippyEvent(
    val type: String,
    val meta: EventMeta,
    val payload: Map<String, Any> = emptyMap()
)
