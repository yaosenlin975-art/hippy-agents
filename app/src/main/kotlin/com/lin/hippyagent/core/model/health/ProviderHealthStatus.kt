package com.lin.hippyagent.core.model.health

import kotlinx.serialization.Serializable

@Serializable
data class ProviderHealthStatus(
    val providerId: String,
    val healthy: Boolean,
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
    val errorType: ErrorType? = null,
    val checkedAt: Long
) {
    enum class ErrorType {
        NETWORK_UNREACHABLE,
        AUTH_FAILED,
        RATE_LIMITED,
        SERVER_ERROR,
        TIMEOUT,
        UNKNOWN
    }
}

@Serializable
data class HealthCheckSummary(
    val totalProviders: Int,
    val healthyProviders: Int,
    val unhealthyProviders: Int,
    val details: List<ProviderHealthStatus>,
    val checkedAt: Long
) {
    val isAllHealthy: Boolean get() = unhealthyProviders == 0 && totalProviders > 0
}
