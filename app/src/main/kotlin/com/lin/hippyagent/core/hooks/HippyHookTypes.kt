package com.lin.hippyagent.core.hooks

enum class HookAction {
    CONTINUE, MODIFY, RESPOND, DENY_TOOL, ABORT_TURN, HARD_ABORT
}

data class HookDecision(
    val action: HookAction,
    val reason: String? = null
)

sealed class HookResult<out T> {
    data class Continue<T>(val value: T? = null) : HookResult<T>()
    data class Modify<T>(val value: T) : HookResult<T>()
    data class Respond<T>(val value: T) : HookResult<T>()
    data class DenyTool<T>(val reason: String) : HookResult<T>()
    data class AbortTurn<T>(val reason: String) : HookResult<T>()
    data object HardAbort : HookResult<Nothing>()

    val decision: HookDecision
        get() = when (this) {
            is Continue -> HookDecision(HookAction.CONTINUE)
            is Modify -> HookDecision(HookAction.MODIFY)
            is Respond -> HookDecision(HookAction.RESPOND)
            is DenyTool -> HookDecision(HookAction.DENY_TOOL, reason)
            is AbortTurn -> HookDecision(HookAction.ABORT_TURN, reason)
            is HardAbort -> HookDecision(HookAction.HARD_ABORT)
        }
}


