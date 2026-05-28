package com.lin.hippyagent.core.model.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for a single model endpoint.
 *
 * @param modelId    Unique model identifier (e.g. "qwen-turbo", "gpt-4o").
 * @param providerId Reference to the provider in [ModelProviderStore].
 * @param baseUrl    API base URL override (null = use provider default).
 * @param maxTokens  Maximum output tokens for this model.
 * @param costPer1kInput  Cost per 1k input tokens in USD (for budget tracking).
 * @param costPer1kOutput Cost per 1k output tokens in USD.
 */
@Serializable
data class ModelEndpointConfig(
    @SerialName("model_id")
    val modelId: String,

    @SerialName("provider_id")
    val providerId: String,

    @SerialName("base_url")
    val baseUrl: String? = null,

    @SerialName("max_tokens")
    val maxTokens: Int = 4096,

    @SerialName("cost_per_1k_input")
    val costPer1kInput: Double = 0.0,

    @SerialName("cost_per_1k_output")
    val costPer1kOutput: Double = 0.0
)

/**
 * Dual-endpoint routing configuration.
 *
 * Defines the light and heavy model endpoints, the fallback model,
 * and the classification threshold. This is the primary configuration
 * object consumed by [ModelRouter].
 *
 * @param lightModel     Model ID for simple/lightweight requests.
 * @param heavyModel     Model ID for complex/reasoning-heavy requests.
 * @param fallbackModel  Optional fallback model when the selected model is unavailable.
 * @param lightEndpoint  Optional endpoint config override for the light model.
 * @param heavyEndpoint  Optional endpoint config override for the heavy model.
 * @param fallbackEndpoint Optional endpoint config for the fallback model.
 * @param threshold      Classification threshold (0-1). Below = LIGHT, at or above = HEAVY.
 * @param enabled        Whether smart routing is active. When false, always use heavyModel.
 */
@Serializable
data class RoutingConfig(
    @SerialName("light_model")
    val lightModel: String,

    @SerialName("heavy_model")
    val heavyModel: String,

    @SerialName("fallback_model")
    val fallbackModel: String? = null,

    @SerialName("light_endpoint")
    val lightEndpoint: ModelEndpointConfig? = null,

    @SerialName("heavy_endpoint")
    val heavyEndpoint: ModelEndpointConfig? = null,

    @SerialName("fallback_endpoint")
    val fallbackEndpoint: ModelEndpointConfig? = null,

    @SerialName("threshold")
    val threshold: Float = 0.35f,

    @SerialName("enabled")
    val enabled: Boolean = true
)

/**
 * Top-level configuration for the smart routing subsystem.
 *
 * Bundles the routing config with per-provider settings and
 * runtime toggles. This is typically serialized from the agent's
 * configuration file or constructed programmatically.
 */
@Serializable
data class ModelRoutingConfig(
    @SerialName("routing")
    val routing: RoutingConfig = RoutingConfig(
        lightModel = "qwen-turbo",
        heavyModel = "qwen-max"
    ),

    @SerialName("classifier_threshold")
    val classifierThreshold: Float = 0.35f,

    @SerialName("log_decisions")
    val logDecisions: Boolean = true,

    @SerialName("cost_tracking_enabled")
    val costTrackingEnabled: Boolean = true,

    /**
     * Optional per-model endpoint overrides.
     * Keyed by model ID. When present, these override the endpoint
     * config in [RoutingConfig] for the corresponding model.
     */
    @SerialName("endpoint_overrides")
    val endpointOverrides: Map<String, ModelEndpointConfig> = emptyMap()
) {
    /**
     * Resolve the effective endpoint for a given model ID.
     * Checks overrides first, then falls back to the routing config's endpoints.
     */
    fun resolveEndpoint(modelId: String): ModelEndpointConfig? {
        return endpointOverrides[modelId]
            ?: when (modelId) {
                routing.lightModel -> routing.lightEndpoint
                routing.heavyModel -> routing.heavyEndpoint
                routing.fallbackModel -> routing.fallbackEndpoint
                else -> null
            }
    }

    /**
     * Convenience accessor: is smart routing enabled?
     */
    val isEnabled: Boolean get() = routing.enabled
}

