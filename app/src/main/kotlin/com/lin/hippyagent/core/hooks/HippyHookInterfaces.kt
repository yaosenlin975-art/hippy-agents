package com.lin.hippyagent.core.hooks

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

// 工具审批已 2026-06 迁移到 TaskApprovalService，hook 不再独立拦截；保留接口位以备未来插件扩展
// interface HippyToolApprover {
//     suspend fun approveTool(req: ToolApprovalRequest): ...
// }

data class HippyEvent(
    val type: String,
    val meta: EventMeta,
    val payload: Map<String, Any> = emptyMap()
)
