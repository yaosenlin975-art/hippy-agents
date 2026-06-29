package com.lin.hippyagent.core.model.health

import com.lin.hippyagent.core.model.ModelProvider
import com.lin.hippyagent.core.model.ModelProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

class ModelProviderHealthService(
    private val providerStore: ModelProviderStore
) {
    suspend fun checkAllProviders(): HealthCheckSummary = withContext(Dispatchers.IO) {
        val providers = providerStore.providers.first()
            .filter { it.enabled && it.apiKey.isNotBlank() && !it.isVirtual }
        val statuses = providers.map { provider ->
            checkProvider(provider)
        }
        val healthy = statuses.count { it.healthy }
        HealthCheckSummary(
            totalProviders = providers.size,
            healthyProviders = healthy,
            unhealthyProviders = providers.size - healthy,
            details = statuses,
            checkedAt = System.currentTimeMillis()
        )
    }

    suspend fun checkProvider(provider: ModelProvider): ProviderHealthStatus =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            try {
                val result = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                    probeProvider(provider)
                }
                val latency = System.currentTimeMillis() - startedAt
                if (result == null) {
                    ProviderHealthStatus(
                        providerId = provider.id,
                        healthy = false,
                        latencyMs = latency,
                        errorMessage = "Timeout after ${HEALTH_CHECK_TIMEOUT_MS}ms",
                        errorType = ProviderHealthStatus.ErrorType.TIMEOUT,
                        checkedAt = System.currentTimeMillis()
                    )
                } else {
                    ProviderHealthStatus(
                        providerId = provider.id,
                        healthy = result.healthy,
                        latencyMs = latency,
                        errorMessage = result.errorMessage,
                        errorType = result.errorType,
                        checkedAt = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Health check failed for provider ${provider.id}")
                ProviderHealthStatus(
                    providerId = provider.id,
                    healthy = false,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    errorMessage = e.message,
                    errorType = classifyException(e),
                    checkedAt = System.currentTimeMillis()
                )
            }
        }

    private fun probeProvider(provider: ModelProvider): ProbeResult {
        val baseUrl = provider.baseUrl.removeSuffix("/")
        val apiKey = provider.apiKey

        val modelsUrl = "$baseUrl/v1/models"
        val (code, _) = httpGet(modelsUrl, apiKey)
        if (code == 200) {
            return ProbeResult(healthy = true)
        }
        if (code in 401..403) {
            return ProbeResult(
                healthy = false,
                errorMessage = "Auth failed: HTTP $code",
                errorType = ProviderHealthStatus.ErrorType.AUTH_FAILED
            )
        }
        if (code == 429) {
            return ProbeResult(
                healthy = false,
                errorMessage = "Rate limited: HTTP 429",
                errorType = ProviderHealthStatus.ErrorType.RATE_LIMITED
            )
        }
        if (code in 500..599) {
            return ProbeResult(
                healthy = false,
                errorMessage = "Server error: HTTP $code",
                errorType = ProviderHealthStatus.ErrorType.SERVER_ERROR
            )
        }
        val headResult = httpHead(baseUrl, apiKey)
        if (headResult in 200..399) {
            return ProbeResult(healthy = true)
        }
        return ProbeResult(
            healthy = false,
            errorMessage = "HTTP $code on /v1/models, HTTP $headResult on base",
            errorType = ProviderHealthStatus.ErrorType.UNKNOWN
        )
    }

    private fun httpGet(urlStr: String, apiKey: String): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
            readTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("User-Agent", "HippyAgent-HealthCheck/1.0")
        }
        return try {
            conn.inputStream.use { it.readBytes() }
            conn.responseCode to "OK"
        } catch (e: Exception) {
            conn.responseCode to (e.message ?: "error")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpHead(urlStr: String, apiKey: String): Int {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
            readTimeout = HEALTH_CHECK_TIMEOUT_MS.toInt()
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun classifyException(e: Exception): ProviderHealthStatus.ErrorType = when (e) {
        is java.net.UnknownHostException,
        is java.net.ConnectException -> ProviderHealthStatus.ErrorType.NETWORK_UNREACHABLE
        is java.net.SocketTimeoutException -> ProviderHealthStatus.ErrorType.TIMEOUT
        else -> ProviderHealthStatus.ErrorType.UNKNOWN
    }

    private data class ProbeResult(
        val healthy: Boolean,
        val errorMessage: String? = null,
        val errorType: ProviderHealthStatus.ErrorType? = null
    )

    companion object {
        const val HEALTH_CHECK_TIMEOUT_MS: Long = 5000L
    }
}
