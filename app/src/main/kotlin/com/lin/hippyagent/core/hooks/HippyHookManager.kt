package com.lin.hippyagent.core.hooks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import com.lin.hippyagent.core.inbox.ApprovalDecision
import kotlinx.coroutines.withTimeoutOrNull

data class HookRegistration(
    val name: String,
    val priority: Int = 100,
    val source: HookSource = HookSource.IN_PROCESS,
    val hook: Any
)

enum class HookSource {
    IN_PROCESS, PLUGIN
}

class HippyHookManager {
    var observerTimeoutMs: Long = 500L
    var interceptorTimeoutMs: Long = 5_000L
    var approvalTimeoutMs: Long = 60_000L

    private val hooks = mutableMapOf<String, HookRegistration>()
    @Volatile
    private var ordered: List<HookRegistration> = emptyList()
    private val lock = Any()

    private val _eventFlow = MutableSharedFlow<HippyEvent>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<HippyEvent> = _eventFlow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun mount(reg: HookRegistration) {
        synchronized(lock) {
            hooks[reg.name] = reg
            rebuildOrdered()
        }
    }

    fun unmount(name: String) {
        synchronized(lock) {
            hooks.remove(name)
            rebuildOrdered()
        }
    }

    private fun rebuildOrdered() {
        ordered = hooks.values.sortedWith(
            compareBy<HookRegistration> { it.source.ordinal }
                .thenBy { it.priority }
                .thenBy { it.name }
        )
    }

    private fun snapshot(): List<HookRegistration> = ordered.toList()

    suspend fun beforeTool(call: ToolCallHookRequest): Pair<ToolCallHookRequest, HookDecision> {
        var current = call.clone()
        for (reg in snapshot()) {
            val interceptor = reg.hook as? HippyToolInterceptor ?: continue
            val result = withTimeoutOrNull(interceptorTimeoutMs) {
                interceptor.beforeTool(current.clone())
            }
            if (result == null) continue
            when (result) {
                is HookResult.Continue -> result.value?.let { current = it }
                is HookResult.Modify -> current = result.value
                is HookResult.Respond -> return result.value to result.decision
                is HookResult.DenyTool -> return current to result.decision
                is HookResult.AbortTurn -> return current to result.decision
                is HookResult.HardAbort -> return current to result.decision
            }
        }
        return current to HookDecision(HookAction.CONTINUE)
    }

    suspend fun approveTool(req: ToolApprovalRequest): ApprovalDecision {
        for (reg in snapshot()) {
            val approver = reg.hook as? HippyToolApprover ?: continue
            val decision = withTimeoutOrNull(approvalTimeoutMs) {
                approver.approveTool(req.clone())
            } ?: return ApprovalDecision.DENIED
            if (decision != ApprovalDecision.APPROVED) return decision
        }
        return ApprovalDecision.APPROVED
    }

    suspend fun afterTool(result: ToolResultHookResponse): Pair<ToolResultHookResponse, HookDecision> {
        var current = result.clone()
        for (reg in snapshot()) {
            val interceptor = reg.hook as? HippyToolInterceptor ?: continue
            val hookResult = withTimeoutOrNull(interceptorTimeoutMs) {
                interceptor.afterTool(current.clone())
            } ?: continue
            when (hookResult) {
                is HookResult.Continue -> hookResult.value?.let { current = it }
                is HookResult.Modify -> current = hookResult.value
                is HookResult.AbortTurn -> return current to hookResult.decision
                is HookResult.HardAbort -> return current to hookResult.decision
                else -> {}
            }
        }
        return current to HookDecision(HookAction.CONTINUE)
    }

    suspend fun beforeLLM(req: LLMHookRequest): Pair<LLMHookRequest, HookDecision> {
        var current = req.clone()
        for (reg in snapshot()) {
            val interceptor = reg.hook as? HippyLLMInterceptor ?: continue
            val result = withTimeoutOrNull(interceptorTimeoutMs) {
                interceptor.beforeLLM(current.clone())
            } ?: continue
            when (result) {
                is HookResult.Continue -> result.value?.let { current = it }
                is HookResult.Modify -> current = result.value
                is HookResult.AbortTurn -> return current to result.decision
                is HookResult.HardAbort -> return current to result.decision
                else -> {}
            }
        }
        return current to HookDecision(HookAction.CONTINUE)
    }

    suspend fun afterLLM(resp: LLMHookResponse): Pair<LLMHookResponse, HookDecision> {
        var current = resp.clone()
        for (reg in snapshot()) {
            val interceptor = reg.hook as? HippyLLMInterceptor ?: continue
            val result = withTimeoutOrNull(interceptorTimeoutMs) {
                interceptor.afterLLM(current.clone())
            } ?: continue
            when (result) {
                is HookResult.Continue -> result.value?.let { current = it }
                is HookResult.Modify -> current = result.value
                is HookResult.AbortTurn -> return current to result.decision
                is HookResult.HardAbort -> return current to result.decision
                else -> {}
            }
        }
        return current to HookDecision(HookAction.CONTINUE)
    }

    fun emitEvent(event: HippyEvent) {
        scope.launch {
            _eventFlow.emit(event)
            for (reg in snapshot()) {
                val observer = reg.hook as? HippyEventObserver ?: continue
                withTimeoutOrNull(observerTimeoutMs) {
                    observer.onEvent(event)
                }
            }
        }
    }

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
