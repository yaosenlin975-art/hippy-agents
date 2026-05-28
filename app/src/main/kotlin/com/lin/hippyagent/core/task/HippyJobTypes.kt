package com.lin.hippyagent.core.task

data class HippyJobSubmitOpts(
    val queue: String = "default",
    val priority: Int = 0,
    val maxAttempts: Int = 3,
    val backoffType: BackoffType = BackoffType.EXPONENTIAL,
    val backoffDelayMs: Long = 5000,
    val backoffJitter: Float = 0.1f,
    val maxStalled: Int = 3,
    val timeoutMs: Long? = null,
    val idempotencyKey: String? = null,
    val maxWaiting: Int? = null,
    val delay: Long? = null,
    val parentJobId: Long? = null,
    val onChildFail: ChildFailPolicy = ChildFailPolicy.CONTINUE,
    val maxChildren: Int? = null
)

interface HippyJobHandler {
    suspend fun execute(context: HippyJobContext): Map<String, Any>
}

data class HippyJobContext(
    val id: Long,
    val name: String,
    val data: Map<String, Any>,
    val attemptsMade: Int,
    val updateProgress: suspend (Map<String, Any>) -> Unit,
    val updateTokens: suspend (Long, Long) -> Unit
)
