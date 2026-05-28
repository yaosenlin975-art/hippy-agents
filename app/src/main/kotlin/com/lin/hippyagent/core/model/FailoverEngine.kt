package com.lin.hippyagent.core.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.min
import kotlin.math.pow

enum class FailoverReason {
    RATE_LIMITED,
    AUTH_FAILED,
    MODEL_UNAVAILABLE,
    CONTEXT_TOO_LONG,
    PROVIDER_ERROR,
    NETWORK_TIMEOUT,
    CONTENT_FILTERED,
    UNKNOWN
}

class FailoverError(
    message: String,
    val reason: FailoverReason,
    val provider: String? = null,
    val model: String? = null,
    val profileId: String? = null,
    val httpStatus: Int? = null,
    val code: String? = null,
    val rawError: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

data class FailoverConfig(
    val enabled: Boolean = true,
    val maxRetries: Int = 3,
    val backoffBaseSeconds: Float = 1.0f,
    val backoffMaxSeconds: Float = 10.0f,
    val fallbackModel: String? = null,
    val fallbackProvider: String? = null,
    val autoRotateProfile: Boolean = true,
    val surfaceUnrecoverable: Boolean = true
)

data class FailoverDecision(
    val action: FailoverAction,
    val nextProfileId: String? = null,
    val nextModel: String? = null,
    val nextProvider: String? = null,
    val retryDelayMs: Long = 0,
    val reason: String
)

enum class FailoverAction {
    RETRY_SAME,
    ROTATE_PROFILE,
    SWITCH_MODEL,
    SWITCH_PROVIDER,
    COMPRESS_CONTEXT,
    SURFACE_TO_USER,
    GIVE_UP
}

class FailoverEngine(
    private val config: FailoverConfig = FailoverConfig(),
    private val authProfileManager: AuthProfileManager? = null
) {
    private val _lastError = MutableStateFlow<FailoverError?>(null)
    val lastError: StateFlow<FailoverError?> = _lastError.asStateFlow()

    /**
     * 最近一次 executeWithFailover 的重试次数 — 仅供 UI 观察。
     * 不再用于逻辑判断，重试计数改为 per-call 局部变量，避免多会话并行干扰。
     */
    private val _retryCount = MutableStateFlow(0)
    val retryCount: StateFlow<Int> = _retryCount.asStateFlow()

    private val errorPatterns = mapOf(
        FailoverReason.RATE_LIMITED to listOf(
            "rate limit", "too many requests", "429", "quota exceeded",
            "requests per minute", "rpm limit", "tpm limit", "tokens per minute"
        ),
        FailoverReason.AUTH_FAILED to listOf(
            "invalid api key", "unauthorized", "authentication failed",
            "401", "403", "invalid credentials", "access denied"
        ),
        FailoverReason.MODEL_UNAVAILABLE to listOf(
            "model not found", "model not available", "503", "404",
            "model is overloaded", "model temporarily unavailable"
        ),
        FailoverReason.CONTEXT_TOO_LONG to listOf(
            "maximum context length", "context window exceeded",
            "too many tokens", "token limit exceeded", "context length exceeded"
        ),
        FailoverReason.PROVIDER_ERROR to listOf(
            "internal server error", "500", "502", "bad gateway",
            "server error", "upstream error"
        ),
        FailoverReason.NETWORK_TIMEOUT to listOf(
            "timeout", "timed out", "connection refused",
            "socket timeout", "connect exception", "unknown host"
        ),
        FailoverReason.CONTENT_FILTERED to listOf(
            "content policy", "content filtered", "safety",
            "inappropriate content", "flagged", "refused to generate"
        )
    )

    fun classifyError(error: Throwable, httpStatus: Int? = null): FailoverError {
        val message = error.message ?: ""

        val reason = errorPatterns.entries
            .firstOrNull { (_, patterns) -> patterns.any { p -> message.contains(p, ignoreCase = true) } }
            ?.key ?: when (httpStatus) {
                429 -> FailoverReason.RATE_LIMITED
                401, 403 -> FailoverReason.AUTH_FAILED
                404, 503 -> FailoverReason.MODEL_UNAVAILABLE
                400 -> if (message.contains("context", ignoreCase = true) || message.contains("token", ignoreCase = true))
                    FailoverReason.CONTEXT_TOO_LONG else FailoverReason.CONTENT_FILTERED
                500, 502 -> FailoverReason.PROVIDER_ERROR
                else -> FailoverReason.UNKNOWN
            }

        return FailoverError(
            message = message,
            reason = reason,
            httpStatus = httpStatus,
            rawError = message,
            cause = error
        )
    }

    fun decide(failoverError: FailoverError, currentProfileId: String? = null, currentRetry: Int): FailoverDecision {
        _lastError.value = failoverError

        if (currentRetry >= config.maxRetries) {
            return FailoverDecision(
                action = FailoverAction.GIVE_UP,
                reason = "达到最大重试次数 (${config.maxRetries})"
            )
        }

        return when (failoverError.reason) {
            FailoverReason.RATE_LIMITED -> {
                val nextProfile = if (config.autoRotateProfile && authProfileManager != null && currentProfileId != null) {
                    authProfileManager.getNextAvailableProfile(failoverError.provider ?: "", currentProfileId)
                } else null

                if (nextProfile != null) {
                    authProfileManager?.markCooldown(currentProfileId ?: "", "rate_limited", 60)
                    FailoverDecision(
                        action = FailoverAction.ROTATE_PROFILE,
                        nextProfileId = nextProfile.id,
                        retryDelayMs = calculateBackoff(currentRetry),
                        reason = "限流，轮换到 Profile: ${nextProfile.name}"
                    )
                } else {
                    FailoverDecision(
                        action = FailoverAction.RETRY_SAME,
                        retryDelayMs = calculateBackoff(currentRetry) * 2,
                        reason = "限流，无可轮换 Profile，退避重试"
                    )
                }
            }

            FailoverReason.AUTH_FAILED -> {
                if (currentProfileId != null) {
                    authProfileManager?.markCooldown(currentProfileId, "auth_failed", 300)
                }
                val nextProfile = if (config.autoRotateProfile && authProfileManager != null) {
                    authProfileManager.getNextAvailableProfile(failoverError.provider ?: "", currentProfileId)
                } else null

                if (nextProfile != null) {
                    FailoverDecision(
                        action = FailoverAction.ROTATE_PROFILE,
                        nextProfileId = nextProfile.id,
                        reason = "认证失败，轮换到 Profile: ${nextProfile.name}"
                    )
                } else {
                    FailoverDecision(
                        action = FailoverAction.SURFACE_TO_USER,
                        reason = "认证失败且无可轮换 Profile"
                    )
                }
            }

            FailoverReason.MODEL_UNAVAILABLE -> {
                val fallback = config.fallbackModel
                if (fallback != null) {
                    FailoverDecision(
                        action = FailoverAction.SWITCH_MODEL,
                        nextModel = fallback,
                        retryDelayMs = calculateBackoff(currentRetry),
                        reason = "模型不可用，切换到 fallback: $fallback"
                    )
                } else {
                    FailoverDecision(
                        action = FailoverAction.RETRY_SAME,
                        retryDelayMs = calculateBackoff(currentRetry),
                        reason = "模型不可用，无 fallback 模型，退避重试"
                    )
                }
            }

            FailoverReason.CONTEXT_TOO_LONG -> {
                FailoverDecision(
                    action = FailoverAction.COMPRESS_CONTEXT,
                    reason = "上下文过长，触发压缩"
                )
            }

            FailoverReason.PROVIDER_ERROR -> {
                val fallbackProvider = config.fallbackProvider
                if (fallbackProvider != null) {
                    FailoverDecision(
                        action = FailoverAction.SWITCH_PROVIDER,
                        nextProvider = fallbackProvider,
                        retryDelayMs = calculateBackoff(currentRetry),
                        reason = "供应商故障，切换到: $fallbackProvider"
                    )
                } else {
                    FailoverDecision(
                        action = FailoverAction.RETRY_SAME,
                        retryDelayMs = calculateBackoff(currentRetry),
                        reason = "供应商故障，无 fallback，退避重试"
                    )
                }
            }

            FailoverReason.NETWORK_TIMEOUT -> {
                FailoverDecision(
                    action = FailoverAction.RETRY_SAME,
                    retryDelayMs = calculateBackoff(currentRetry) * 2,
                    reason = "网络超时，退避重试"
                )
            }

            FailoverReason.CONTENT_FILTERED -> {
                FailoverDecision(
                    action = FailoverAction.SURFACE_TO_USER,
                    reason = "内容被过滤，无法自动恢复"
                )
            }

            FailoverReason.UNKNOWN -> {
                FailoverDecision(
                    action = FailoverAction.RETRY_SAME,
                    retryDelayMs = calculateBackoff(currentRetry),
                    reason = "未知错误，退避重试"
                )
            }
        }
    }

    suspend fun executeWithFailover(
        currentProfileId: String? = null,
        block: suspend () -> Result<String>
    ): Result<String> {
        var localRetryCount = 0
        _retryCount.value = 0

        while (localRetryCount <= config.maxRetries) {
            try {
                val result = block()
                if (result.isSuccess) {
                    _retryCount.value = localRetryCount
                    if (currentProfileId != null) {
                        authProfileManager?.clearCooldown(currentProfileId)
                    }
                    return result
                }

                val error = result.exceptionOrNull() ?: return result
                val failoverError = classifyError(error)
                val decision = decide(failoverError, currentProfileId, localRetryCount)

                Timber.w("Failover: ${decision.action} - ${decision.reason}")

                when (decision.action) {
                    FailoverAction.GIVE_UP, FailoverAction.SURFACE_TO_USER -> {
                        _retryCount.value = localRetryCount
                        return Result.failure(failoverError)
                    }
                    FailoverAction.COMPRESS_CONTEXT -> {
                        _retryCount.value = localRetryCount
                        return Result.failure(FailoverError(
                            "上下文过长，需要压缩后重试",
                            FailoverReason.CONTEXT_TOO_LONG
                        ))
                    }
                    else -> {
                        if (decision.retryDelayMs > 0) {
                            delay(decision.retryDelayMs)
                        }
                        localRetryCount++
                        _retryCount.value = localRetryCount
                    }
                }
            } catch (e: Exception) {
                val failoverError = classifyError(e)
                val decision = decide(failoverError, currentProfileId, localRetryCount)

                Timber.w("Failover (exception): ${decision.action} - ${decision.reason}")

                when (decision.action) {
                    FailoverAction.GIVE_UP, FailoverAction.SURFACE_TO_USER -> {
                        _retryCount.value = localRetryCount
                        return Result.failure(failoverError)
                    }
                    FailoverAction.COMPRESS_CONTEXT -> {
                        _retryCount.value = localRetryCount
                        return Result.failure(FailoverError(
                            "上下文过长，需要压缩后重试",
                            FailoverReason.CONTEXT_TOO_LONG
                        ))
                    }
                    else -> {
                        if (decision.retryDelayMs > 0) {
                            delay(decision.retryDelayMs)
                        }
                        localRetryCount++
                        _retryCount.value = localRetryCount
                    }
                }
            }
        }

        return Result.failure(Exception("达到最大重试次数"))
    }

    private fun calculateBackoff(retryCount: Int): Long {
        val delaySeconds = config.backoffBaseSeconds * 2.0f.pow(retryCount)
        return (min(delaySeconds, config.backoffMaxSeconds) * 1000).toLong()
    }

    fun resetRetryCount() {
        _retryCount.value = 0
    }
}

